defmodule RisiMe.Agent.RisiHotfixTest do
  @moduledoc """
  Hotfix 2026-10-09 (pilot, Harsha's phone): never `rate_limited` in normal use (backoff, the
  other model, answer late), every limiter decision and upstream failure logged; the system
  prompt carries Risi's identity and what it can do now / what's coming; "what can you do" works
  in an empty chat; the "transcript does not contain" post-check; no "Message 1" labels.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import ExUnit.CaptureLog
  import RisiMe.RisiHelpers

  alias RisiMe.Agent.{Capabilities, LLM}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  defmodule OtherModel do
    @moduledoc false
    @behaviour RisiMe.Agent.LLM
    def name, do: "other"
    def configured?, do: true

    def chat(_body, _opts) do
      if pid = Application.get_env(:risime, :risi_test_pid), do: send(pid, :other_model_called)

      {:ok,
       %{
         content: ~s({"answer":"From the other model.","refs":[],"confidence":0.7}),
         model: "other-model",
         prompt_tokens: 1,
         completion_tokens: 1
       }}
    end
  end

  setup do
    harsha = RisiMe.GroupHelpers.fast_user!("Harsha")
    kamal = RisiMe.GroupHelpers.fast_user!("Kamal")
    og = risi_chat!([harsha, kamal])
    old = Application.get_env(:risime, :risi_llm)
    on_exit(fn -> Application.put_env(:risime, :risi_llm, old) end)
    %{harsha: harsha, kamal: kamal, og: og}
  end

  defp put_llm(kv),
    do:
      Application.put_env(
        :risime,
        :risi_llm,
        Keyword.merge(Application.get_env(:risime, :risi_llm), kv)
      )

  defp ask!(ctx, text) do
    rid = Ecto.UUID.generate()

    envelope!(ctx.og, ctx.harsha, %{
      "v" => 1,
      "type" => "risi_request",
      "request_id" => rid,
      "action" => "ask",
      "text" => text
    })

    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    {rid, perform_job(Job, job.args)}
  end

  test "\"what can you do\" in an empty chat: the model answers with the capability list", ctx do
    fake_llm!(fn "answer", _ ->
      %{"answer" => "I can track promises and summarise.", "refs" => [], "confidence" => 0.9}
    end)

    {rid, :ok} = ask!(ctx, "What can you do?")
    assert_receive {:risi_post, _, _, %{"kind" => "answer", "request_id" => ^rid}}

    [req] = llm_requests()
    system = hd(req["messages"])["content"]
    assert system =~ "You are Risi, the RisiMe assistant"
    assert system =~ "What you can do now:"
    assert system =~ "track promises"
    assert system =~ "summarise this chat's last 24 hours"
    assert system =~ "Coming soon"
    assert system =~ "checking your calendar"
    assert system =~ "never say that the transcript"
    # Nothing that isn't available is claimed as available.
    [now, _coming] = String.split(system, "Coming soon")
    refute now =~ "calendar"
  end

  test "the post-check: \"the transcript does not contain\" is retried once with a correction",
       ctx do
    say!(ctx.og, ctx.kamal, "Site visit Thursday")
    {:ok, n} = Agent.start_link(fn -> 0 end)

    fake_llm!(fn "answer", _ ->
      case Agent.get_and_update(n, &{&1, &1 + 1}) do
        0 ->
          %{
            "answer" => "The transcript does not contain your calendar.",
            "refs" => [],
            "confidence" => 0.5
          }

        _ ->
          %{
            "answer" =>
              "I can't see your calendar yet. Calendar access is coming soon; want me to set a reminder instead?",
            "refs" => [],
            "confidence" => 0.8
          }
      end
    end)

    {rid, :ok} = ask!(ctx, "Tuesday ok for the call?")
    assert_receive {:risi_post, _, body, %{"kind" => "answer", "request_id" => ^rid}}
    assert body =~ "Calendar access is coming soon"
    [_, retry] = llm_requests()
    roles = Enum.map(retry["messages"], & &1["role"])
    assert roles == ~w(system user assistant user)
    assert List.last(retry["messages"])["content"] =~ "Don't say that the transcript"
  end

  test "the post-check: still forbidden after the retry → a server-built answer", ctx do
    fake_llm!(fn "answer", _ ->
      %{
        "answer" => "The chat doesn't mention that.",
        "refs" => ["m1"],
        "confidence" => 0.4
      }
    end)

    {rid, :ok} = ask!(ctx, "Who is my dentist?")
    assert_receive {:risi_post, _, body, %{"kind" => "answer", "request_id" => ^rid} = a}
    assert length(llm_requests()) == 2
    refute Capabilities.forbidden?(body)
    assert body =~ "Right now I can"
    assert body =~ "Coming soon"
    assert a["refs"] == []
  end

  test "sources: no \"Message 1\" labels in text; only real message refs", ctx do
    m1 = say!(ctx.og, ctx.kamal, "Site visit Thursday 10 am")

    fake_llm!(fn "answer", body ->
      ref = ref_of(body, "Site visit Thursday 10 am")

      %{
        "answer" => "Kamal said Thursday 10 am (Message 1) [#{ref}].",
        "refs" => [ref, "m99"],
        "confidence" => 0.9
      }
    end)

    {rid, :ok} = ask!(ctx, "When is the site visit?")
    assert_receive {:risi_post, _, body, %{"kind" => "answer", "request_id" => ^rid} = a}
    assert body == "Kamal said Thursday 10 am."
    assert a["answer"] == body
    assert a["refs"] == [m1]
  end

  test "strip_labels and forbidden? cover the usual phrasings" do
    assert Capabilities.strip_labels("Yes, see Message 1 and 2.") == "Yes, see."
    assert Capabilities.strip_labels("Thursday (m1, m3).") == "Thursday."
    assert Capabilities.strip_labels("Thursday [Message 4]") == "Thursday"
    assert Capabilities.strip_labels("Room m12 is booked") == "Room is booked"
    assert Capabilities.strip_labels("Meet at 10") == "Meet at 10"

    for s <- [
          "The transcript does not contain that.",
          "The conversation doesn't mention a dentist.",
          "There is no information about it in the chat.",
          "The context has no details on that."
        ],
        do: assert(Capabilities.forbidden?(s), s)

    refute Capabilities.forbidden?(
             "I can't see your calendar yet. Calendar access is coming soon."
           )
  end

  test "upstream 429/5xx/timeout: backoff retries, then the other model when configured" do
    {:ok, n} = Agent.start_link(fn -> 0 end)

    fake_llm!(fn _, _ ->
      case Agent.get_and_update(n, &{&1, &1 + 1}) do
        0 -> {:status, 429}
        1 -> :timeout
        _ -> %{"answer" => "ok", "refs" => [], "confidence" => 0.9}
      end
    end)

    log =
      capture_log(fn -> assert {:ok, %{output: %{"answer" => "ok"}}} = LLM.complete(req()) end)

    assert log =~ "risi llm: provider=risi_l1 status=429 attempt=1"
    assert log =~ "risi llm: provider=risi_l1 status=timeout attempt=2"
    assert length(llm_requests()) == 3

    # risi_l1 keeps failing: the configured other model answers.
    Application.put_env(:risime, :risi_test_pid, self())
    put_llm(fallback_provider: OtherModel)
    fake_llm!(fn _, _ -> {:status, 503} end)

    assert {:ok, %{output: %{"answer" => "From the other model."}}} = LLM.complete(req())
    assert_receive :other_model_called
    assert length(llm_requests()) == 4
  end

  test "backoff never runs past the request's deadline" do
    put_llm(backoff_ms: 400)
    fake_llm!(fn _, _ -> {:status, 503} end)
    deadline = System.monotonic_time(:millisecond) + 1_000
    t0 = System.monotonic_time(:millisecond)
    assert {:error, :model_unavailable} = LLM.complete(Map.put(req(), :deadline_ms, deadline))
    assert System.monotonic_time(:millisecond) - t0 < 1_500
    assert length(llm_requests()) < 4
  end

  defp req do
    %{
      task: "ask",
      conversation_id: "grp:" <> Ecto.UUID.generate(),
      chat_id: "grp:" <> Ecto.UUID.generate(),
      system: "system",
      user: "u",
      schema_name: "answer",
      schema: RisiMe.Agent.Prompts.answer_schema(),
      source_message_ids: []
    }
  end
end
