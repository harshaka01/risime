defmodule RisiMe.Agent.LLMTest do
  @moduledoc """
  v1.24 S6 (§24.12, decisions 061, 066): the model router (loopback only, guided JSON, one
  retry, the global queue, the fallback gate) and the learning log (no raw text, ever).
  """
  use RisiMe.DataCase, async: false

  import ExUnit.CaptureLog
  import RisiMe.RisiHelpers

  alias RisiMe.Agent.{LearningLog, LLM}
  alias RisiMe.Agent.LLM.Local

  @moduletag capture_log: true

  setup do
    old = Application.get_env(:risime, :risi_llm)
    on_exit(fn -> Application.put_env(:risime, :risi_llm, old) end)
    %{llm: old}
  end

  defp put_llm(kv),
    do:
      Application.put_env(
        :risime,
        :risi_llm,
        Keyword.merge(Application.get_env(:risime, :risi_llm), kv)
      )

  defp req(text, ids \\ []) do
    %{
      task: "summarise",
      conversation_id: "grp:" <> Ecto.UUID.generate(),
      chat_id: "grp:" <> Ecto.UUID.generate(),
      system: "system",
      user: text,
      schema_name: "answer",
      schema: RisiMe.Agent.Prompts.answer_schema(),
      source_message_ids: ids
    }
  end

  @answer %{"answer" => "Thursday", "refs" => ["m1"], "confidence" => 0.9}

  test "only loopback base URLs are accepted; anything else never opens a socket" do
    for ok <- [
          "http://127.0.0.1:8100/v1",
          "http://localhost:8100/v1",
          "http://127.3.4.5:9/v1/",
          "http://[::1]:8100/v1",
          "https://127.0.0.1/v1"
        ],
        do: assert({:ok, _} = Local.check_url(ok), ok)

    for bad <- [
          "http://10.0.0.5:8100/v1",
          "http://203.115.26.139:8100/v1",
          "https://api.example.com/v1",
          "http://127.0.0.1.example.com/v1",
          "http://localhost.evil.test/v1",
          "http://[::2]:8100/v1",
          "http://0.0.0.0:8100/v1"
        ],
        do: assert({:error, :non_loopback} = Local.check_url(bad), bad)

    assert {:error, :bad_url} = Local.check_url("ftp://127.0.0.1/v1")
    assert {:error, :bad_url} = Local.check_url("http://user:pw@127.0.0.1/v1")

    fake_llm!(fn _, _ -> @answer end)
    put_llm(url: "http://203.115.26.139:8100/v1")
    assert {:error, :model_unavailable} = LLM.complete(req("hello"))
    assert llm_requests() == []
  end

  test "the request: alias risi-l1, json_schema guided decoding, thinking off, low temperature, bearer" do
    test = self()
    put_llm(api_key: "test-key-never-real")

    Req.Test.stub(Local, fn conn ->
      send(test, {:auth, Plug.Conn.get_req_header(conn, "authorization")})

      case conn.request_path do
        "/v1/models" ->
          Req.Test.json(conn, %{
            "data" => [%{"id" => "risi-l1", "root" => "/m/Qwen3.6-35B-A3B-FP8"}]
          })

        _ ->
          {:ok, raw, conn} = Plug.Conn.read_body(conn)
          send(test, {:body, Jason.decode!(raw)})

          Req.Test.json(conn, %{
            "model" => "risi-l1",
            "choices" => [%{"message" => %{"content" => Jason.encode!(@answer)}}],
            "usage" => %{"prompt_tokens" => 10, "completion_tokens" => 5}
          })
      end
    end)

    assert {:ok, %{call_ref: ref, output: @answer, confidence: 0.9}} = LLM.complete(req("hello"))
    assert_receive {:body, body}
    assert body["model"] == "risi-l1"
    assert body["temperature"] in [0.2, 0.3]
    assert body["chat_template_kwargs"] == %{"enable_thinking" => false}

    assert %{"type" => "json_schema", "json_schema" => %{"name" => "answer", "schema" => _}} =
             body["response_format"]

    assert_receive {:auth, ["Bearer test-key-never-real"]}
    assert {:ok, %{model: model, provider: "risi_l1"}} = LearningLog.get(ref)
    assert model in ["Qwen3.6-35B-A3B-FP8", "risi-l1"]
  end

  test "one retry: a 5xx then an answer succeeds; two failures are model_unavailable (logged)" do
    {:ok, n} = Agent.start_link(fn -> 0 end)

    fake_llm!(fn _, _ ->
      case Agent.get_and_update(n, &{&1, &1 + 1}) do
        0 -> {:status, 503}
        _ -> @answer
      end
    end)

    assert {:ok, _} = LLM.complete(req("a"))
    assert length(llm_requests()) == 2

    fake_llm!(fn _, _ -> {:status, 500} end)
    r = req("b", [RisiMe.TimeUUID.generate()])
    assert {:error, :model_unavailable} = LLM.complete(r)
    assert length(llm_requests()) == 2

    [row] = LearningLog.list_by_chat(r.chat_id, Calendar.strftime(Date.utc_today(), "%Y-%m"))
    assert row.status == "http_5xx"
    assert row.output == nil
    assert row.source_message_ids == r.source_message_ids

    # Output that breaks the schema counts as a failure too (retried once).
    fake_llm!(fn _, _ -> {:raw, ~s({"answer": 5})} end)
    assert {:error, :model_unavailable} = LLM.complete(req("c"))
    assert length(llm_requests()) == 2
  end

  test "the global Risi queue: no free slot is rate_limited without a call" do
    fake_llm!(fn _, _ -> @answer end)
    put_llm(max_in_flight: 0)
    assert {:error, :rate_limited} = LLM.complete(req("x"))
    assert llm_requests() == []
    assert LLM.in_flight() == 0
  end

  defmodule FakeFallback do
    @behaviour RisiMe.Agent.LLM
    def name, do: "fallback_test"

    def chat(_body, _opts) do
      send(Application.get_env(:risime, :risi_test_pid), :fallback_called)
      {:error, :not_configured}
    end
  end

  test "the fallback is consulted only with RISI_FALLBACK=on and a low confidence" do
    Application.put_env(:risime, :risi_test_pid, self())
    on_exit(fn -> Application.delete_env(:risime, :risi_test_pid) end)
    low = %{@answer | "confidence" => 0.1}
    fake_llm!(fn _, _ -> low end)
    put_llm(fallback_provider: FakeFallback)

    # Off (the default): never consulted, whatever the confidence.
    assert {:ok, %{call_ref: r1, confidence: 0.1}} = LLM.complete(req("x"))
    refute_received :fallback_called
    assert {:ok, %{fallback: "off"}} = LearningLog.get(r1)

    # On, low confidence: consulted; the stub can't answer, so the local answer stands.
    put_llm(fallback: true, fallback_threshold: 0.5)
    assert {:ok, %{call_ref: r2, output: ^low}} = LLM.complete(req("x"))
    assert_received :fallback_called
    assert {:ok, %{fallback: "unavailable", provider: "risi_l1"}} = LearningLog.get(r2)

    # On, confident: not consulted.
    fake_llm!(fn _, _ -> @answer end)
    assert {:ok, _} = LLM.complete(req("x"))
    refute_received :fallback_called

    # The real stub makes no call at all.
    assert {:error, :not_configured} = RisiMe.Agent.LLM.Fallback.chat(%{}, [])
  end

  test "the learning log keeps the prompt only as ids + SHA-256: the canary is in no row or log" do
    canary = "CANARY-" <> Ecto.UUID.generate()
    fake_llm!(fn _, _ -> @answer end)
    ids = [RisiMe.TimeUUID.generate(), RisiMe.TimeUUID.generate()]
    r = req("the chat says " <> canary, ids)

    log =
      capture_log([level: :debug], fn ->
        assert {:ok, %{call_ref: ref}} = LLM.complete(r)
        send(self(), {:ref, ref})
      end)

    assert_received {:ref, ref}
    [sent] = llm_requests()
    assert inspect(sent) =~ canary

    {:ok, entry} = LearningLog.get(ref)
    assert entry.prompt_sha256 == LLM.prompt_hash(sent["messages"])
    assert entry.source_message_ids == ids
    assert entry.task == "summarise"
    assert entry.model_alias == "risi-l1"
    assert entry.status == "ok"
    assert entry.cost == 0.0
    assert Jason.decode!(entry.output) == @answer
    assert entry.latency_ms >= 0
    assert entry.prompt_tokens == 120

    month = LearningLog.month(ref)
    [by_chat] = LearningLog.list_by_chat(r.chat_id, month)
    by_day = LearningLog.list_by_day(LearningLog.day(ref), LearningLog.bucket(ref))
    assert Enum.any?(by_day, &(&1.call_id == ref))

    for row <- [entry, by_chat | by_day], do: refute(inspect(row) =~ canary)
    refute log =~ canary

    # Feedback: one row per user, a repeat replaces it.
    u = Ecto.UUID.generate()
    :ok = LearningLog.put_feedback(ref, u, "down", "Wrong date")
    :ok = LearningLog.put_feedback(ref, u, "up", nil)
    assert [%{rating: "up", reason: nil}] = LearningLog.list_feedback(ref)
  end
end
