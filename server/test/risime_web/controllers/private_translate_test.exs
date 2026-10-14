defmodule RisiMeWeb.PrivateTranslateTest do
  @moduledoc """
  Decision 092 / proposal 2026-10-14-private-transcribe §3: `POST /api/v1/translate`, the Private
  translate request. The flag (`off` → 404 and no model call; `canary` → RISI_DEV_USERS only;
  `on`), `User.private_transcribe` on `/me` (absent while off: parity), validation, the rate
  limit, local-only (never the fallback provider) and **nothing kept**: after a translate the
  text and its translation are in no log line (at debug level), no Postgres row (every public
  table, Oban's included), no Oban job and no learning-log row; the learning-log row holds the
  languages and latency only.
  """
  use RisiMeWeb.ConnCase, async: false

  import ExUnit.CaptureLog
  import RisiMe.Fixtures
  import RisiMe.GroupHelpers, only: [api: 4, api: 3, assert_status: 2]
  import RisiMe.RisiHelpers, only: [fake_llm!: 1, llm_requests: 0]

  alias RisiMe.Agent.LearningLog
  alias RisiMe.Repo

  @moduletag capture_log: true

  setup do
    keys = [:risi_agent_flags, :risi_dev_users, :risi_llm, :risi_test_pid]
    prev = for k <- keys, do: {k, Application.get_env(:risime, k)}

    on_exit(fn ->
      for {k, v} <- prev,
          do:
            if(v,
              do: Application.put_env(:risime, k, v),
              else: Application.delete_env(:risime, k)
            )
    end)

    a = logged_in_user(display_name: "Asha")
    b = logged_in_user(display_name: "Bimal")
    Application.put_env(:risime, :risi_dev_users, [String.downcase(a.user.id)])
    %{a: a, b: b}
  end

  defp flag(v),
    do: Application.put_env(:risime, :risi_agent_flags, %{"RISI_PRIVATE_TRANSCRIBE" => v})

  defp translate(token, body), do: api(:post, "/api/v1/translate", token, body)

  defp me(token), do: api(:get, "/api/v1/me", token) |> assert_status(200)

  test "off (the default): 404, no model call, and /me has no private_transcribe", %{a: a} do
    fake_llm!(fn _, _ -> %{"text" => "x", "source" => "en"} end)
    Application.delete_env(:risime, :risi_agent_flags)

    assert {404, %{"error" => %{"code" => "not_found"}}} =
             translate(a.token, %{"text" => "hello", "target" => "si"})

    assert llm_requests() == []
    refute Map.has_key?(me(a.token)["user"], "private_transcribe")
  end

  test "canary: only RISI_DEV_USERS (through /me); on: everyone", %{a: a, b: b} do
    fake_llm!(fn "translation", _ -> %{"text" => "ආයුබෝවන්", "source" => "en"} end)
    flag("canary")
    assert me(a.token)["user"]["private_transcribe"] == true
    refute Map.has_key?(me(b.token)["user"], "private_transcribe")

    assert %{"text" => "ආයුබෝවන්", "target" => "si", "source" => "en", "engine" => "risime-server"} =
             translate(a.token, %{"text" => "hello", "target" => "si"}) |> assert_status(200)

    assert {404, _} = translate(b.token, %{"text" => "hello", "target" => "si"})
    assert length(llm_requests()) == 1

    flag("on")
    assert me(b.token)["user"]["private_transcribe"] == true
    assert {200, _} = translate(b.token, %{"text" => "hello", "target" => "ta"})
  end

  test "bad requests are 422 and never reach the model", %{a: a} do
    fake_llm!(fn _, _ -> %{"text" => "x", "source" => "en"} end)
    flag("on")

    for body <- [
          %{"text" => "", "target" => "si"},
          %{"text" => "   ", "target" => "si"},
          %{"text" => String.duplicate("a", 4001), "target" => "si"},
          %{"text" => "hello", "target" => "fr"},
          %{"text" => "hello"},
          %{"text" => 12, "target" => "si"},
          %{"text" => "hello", "target" => "si", "source" => "de"},
          %{"target" => "en"}
        ] do
      assert {422, %{"error" => %{"code" => "bad_request"}}} = translate(a.token, body)
    end

    assert llm_requests() == []

    # The same language: returned as is, no model call.
    assert %{"text" => "hello", "source" => "en"} =
             translate(a.token, %{"text" => " hello ", "target" => "en", "source" => "en"})
             |> assert_status(200)

    assert llm_requests() == []
  end

  test "30 per user per minute", %{b: b} do
    fake_llm!(fn _, _ -> %{"text" => "x", "source" => "en"} end)
    flag("on")
    for _ <- 1..30, do: assert({200, _} = translate(b.token, %{"text" => "hi", "target" => "si"}))

    assert {429, %{"error" => %{"code" => "rate_limited"}}} =
             translate(b.token, %{"text" => "hi", "target" => "si"})
  end

  defmodule SpyFallback do
    @behaviour RisiMe.Agent.LLM
    def name, do: "spy_fallback"
    def configured?, do: true

    def chat(_body, _opts) do
      send(Application.get_env(:risime, :risi_test_pid), :fallback_called)

      {:ok,
       %{
         content: ~s({"text":"from fallback","source":"en"}),
         model: "spy",
         prompt_tokens: 1,
         completion_tokens: 1
       }}
    end
  end

  test "local only: a failing model is 503, never the (configured) fallback provider", %{a: a} do
    Application.put_env(:risime, :risi_test_pid, self())
    flag("on")

    Application.put_env(
      :risime,
      :risi_llm,
      Keyword.merge(Application.get_env(:risime, :risi_llm),
        fallback: true,
        fallback_provider: SpyFallback
      )
    )

    fake_llm!(fn _, _ -> {:status, 500} end)

    assert {503, %{"error" => %{"code" => "agent_unavailable"}}} =
             translate(a.token, %{"text" => "hello", "target" => "si"})

    refute_received :fallback_called
  end

  test "nothing is kept: no log line, no Postgres row, no Oban job, metadata-only learning log",
       %{a: a} do
    flag("on")
    secret = "PRIVATE-CANARY-" <> Ecto.UUID.generate()
    translated = "TRANSLATED-CANARY-" <> Ecto.UUID.generate()
    fake_llm!(fn "translation", _ -> %{"text" => translated, "source" => "en"} end)

    old_level = Logger.level()
    Logger.configure(level: :debug)

    log =
      try do
        capture_log([level: :debug], fn ->
          body =
            translate(a.token, %{"text" => "Meet at 10 " <> secret, "target" => "si"})
            |> assert_status(200)

          send(self(), {:body, body})
        end)
      after
        Logger.configure(level: old_level)
      end

    assert_received {:body, %{"text" => ^translated}}
    # The model got the text (that's the job), once.
    [sent] = llm_requests()
    assert inspect(sent) =~ secret

    # 1. No log line holds the text or the translation (Phoenix's "Parameters" line is off).
    refute log =~ secret
    refute log =~ translated
    refute log =~ "Parameters"

    # 2. No Postgres row anywhere (every public table, Oban's included) holds either.
    tables =
      Repo.query!(
        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'"
      ).rows
      |> List.flatten()

    assert "oban_jobs" in tables

    for t <- tables, needle <- [secret, translated] do
      hex = Base.encode16(needle, case: :lower)

      %{rows: [[n]]} =
        Repo.query!(
          ~s|SELECT count(*) FROM "#{t}" AS x WHERE x::text LIKE $1 OR x::text LIKE $2|,
          ["%" <> needle <> "%", "%" <> hex <> "%"]
        )

      assert n == 0, "#{t} holds the Private text"
    end

    # 3. No Oban job was enqueued for it.
    refute Enum.any?(Repo.all(Oban.Job), &(inspect(&1.args) =~ secret))

    # 4. The learning log: metadata only (languages, latency), no text, no prompt hash.
    rows =
      LearningLog.list_by_chat("private", Calendar.strftime(Date.utc_today(), "%Y-%m"), 1000)

    mine = Enum.filter(rows, &(&1.task == "private_translate"))
    assert mine != []

    for row <- rows do
      refute inspect(row) =~ secret
      refute inspect(row) =~ translated
    end

    for row <- mine do
      assert row.output == nil
      assert row.prompt_sha256 == nil
      assert row.source_message_ids in [nil, []]
      assert row.provider == "risi_l1"
      assert is_integer(row.latency_ms)
      meta = if is_binary(row.meta), do: Jason.decode!(row.meta), else: row.meta
      assert Map.keys(meta) |> Enum.sort() == ["mode", "source", "target"]
    end
  end
end
