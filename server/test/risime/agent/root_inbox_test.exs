defmodule RisiMe.Agent.RootInboxTest do
  @moduledoc """
  Harsha 2026-10-15 15:31 SLT: root reaches Harsha in his Risi chat (`RisiMe.Agent.RootInbox`).
  A post renders `ask` chips; his tap or short reply is recorded and the brain does not run;
  other users are never intercepted; an unrelated question goes to the brain; one open
  question per topic; no config → refused.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers

  alias RisiMe.Agent.RootInbox
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  setup do
    restore_on_exit([:risi_tool_registry, :risi_turn, :risi_root_notify_user])
    RisiMe.MLSHelpers.with_attestation_key(%{})
    harsha = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
    kamal = RisiMe.Fixtures.logged_in_user(display_name: "Kamal")
    _og = risi_chat!([harsha.user, kamal.user])
    RisiMe.TabsHelpers.risi_tools_on!()
    skills_on!()
    dev = skills_device!(harsha.user)
    rc = own_risi_chat!(harsha.user)
    kdev = skills_device!(kamal.user)
    krc = own_risi_chat!(kamal.user)
    Application.put_env(:risime, :risi_root_notify_user, harsha.user.id)

    fake_llm!(fn _, _ ->
      %{"tool" => "final", "answer" => "Brain answer.", "sources" => [], "next_steps" => []}
    end)

    %{harsha: harsha, kamal: kamal, dev: dev, rc: rc, kdev: kdev, krc: krc}
  end

  defp ask!(conv, user, dev, text) do
    rid = Ecto.UUID.generate()

    envelope!(
      conv,
      user,
      %{
        "v" => 1,
        "type" => "risi_request",
        "request_id" => rid,
        "action" => "ask",
        "text" => text
      },
      dev
    )

    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    perform_job(Job, job.args)
  end

  defp posts(acc \\ []) do
    receive do
      {:risi_post, conv, body, risi} -> posts([{conv, body, risi} | acc])
    after
      50 -> Enum.reverse(acc)
    end
  end

  test "a release notice catches no reply (Harsha 15 Oct)", _ctx do
    {:ok, _} = RootInbox.post("rel", "nightly.11 is ready.", [], "release")
    [{_, body, _}] = posts()
    assert body =~ "New release: nightly.11 is ready."
    assert RootInbox.fetch_new() == []
  end

  test "a command comes as its own message, to long-press and copy (Harsha 15 Oct)", _ctx do
    b64 =
      %{"text" => "Please run this.", "topic" => "cmd", "options" => ["Done"], "sudo" => "ls -la"}
      |> Jason.encode!()
      |> Base.encode64()

    ExUnit.CaptureIO.capture_io(fn -> RootInbox.cli_post(b64) end)
    [{_, q, qa}, {_, c, ca}] = posts()
    assert q =~ "The command is in the next message"
    refute q =~ "ls -la"
    assert qa["next_actions"] != []
    assert c == "ls -la" and ca["next_actions"] == [] and ca["notify"] == []
  end

  test "a post renders the body and ask chips in the configured user's Risi chat", ctx do
    {:ok, id} = RootInbox.post("apk", "Install nightly.60?", ["Yes", "Not now"])
    assert is_binary(id)
    [{rc, body, a}] = posts()
    assert rc == ctx.rc
    assert body == "Root needs you: Install nightly.60?"
    assert a["kind"] == "answer" and a["answer"] == body
    assert a["notify"] == [ctx.harsha.user.id]
    assert a["made_by"]["model"] == nil

    assert a["next_actions"] == [
             %{"label" => "Yes", "action" => "ask", "text" => "Root: Yes"},
             %{"label" => "Not now", "action" => "ask", "text" => "Root: Not now"}
           ]

    {:ok, _} = RootInbox.post("calls", "Call Risi is live", [], "try")
    [{_, "Try now: Call Risi is live", _}] = posts()
    {:ok, _} = RootInbox.post("outage", "Server down", [], "p0")
    [{_, "P0: Server down", _}] = posts()
  end

  test "more than 3 options are lettered lines, no chips", ctx do
    {:ok, _} = RootInbox.post("pick", "Which voice?", ~w(Emma Bella Michael Today))
    [{_, body, a}] = posts()
    assert body =~ "A. Emma\nB. Bella\nC. Michael\nD. Today"
    assert body =~ "Reply with the letter."
    assert a["next_actions"] == []

    :ok = ask!(ctx.rc, ctx.harsha.user, ctx.dev, "b")
    [{_, ack, _}] = posts()
    assert ack == RootInbox.ack()
    assert llm_requests() == []
    [a] = RootInbox.fetch_new()
    assert a.answer == "Bella" and a.answer_kind == "text"
  end

  test "a chip tap is recorded as a tap and the brain does not run", ctx do
    {:ok, _} = RootInbox.post("apk", "Install nightly.60?", ["Yes", "Not now"])
    _ = posts()
    :ok = ask!(ctx.rc, ctx.harsha.user, ctx.dev, "Root: Not now")
    [{rc, body, a}] = posts()
    assert rc == ctx.rc and body == "Got it, I've passed that to root."
    assert a["kind"] == "answer"
    assert llm_requests() == []

    [ans] = RootInbox.fetch_new()
    assert ans.topic == "apk" and ans.question == "Install nightly.60?"
    assert ans.answer == "Not now" and ans.answer_kind == "tap"

    assert RootInbox.line(ans) =~
             ~r/^- \d{4}-\d\d-\d\dT.* · apk · Q: Install nightly.60\? · A: Not now \(tap\)$/

    # Delivered once.
    assert RootInbox.fetch_new() == []
  end

  test "a short text reply while a question is open is recorded", ctx do
    {:ok, _} = RootInbox.post("phone", "Did the call ring on your phone?", [])
    _ = posts()
    :ok = ask!(ctx.rc, ctx.harsha.user, ctx.dev, "yes it rang")
    [{_, body, _}] = posts()
    assert body == RootInbox.ack()
    assert llm_requests() == []
    [ans] = RootInbox.fetch_new()
    assert ans.answer == "yes it rang" and ans.answer_kind == "text"
  end

  test "an unrelated question during an open question goes to the brain", ctx do
    {:ok, _} = RootInbox.post("apk", "Install nightly.60?", ["Yes", "Not now"])
    _ = posts()
    :ok = ask!(ctx.rc, ctx.harsha.user, ctx.dev, "Draft a short note to Kamal about lunch")
    assert llm_requests() != []
    assert RootInbox.fetch_new() == []
  end

  test "a reply after the question is answered goes to the brain", ctx do
    {:ok, _} = RootInbox.post("apk", "Install nightly.60?", ["Yes", "Not now"])
    _ = posts()
    :ok = ask!(ctx.rc, ctx.harsha.user, ctx.dev, "Root: Yes")
    _ = posts()
    :ok = ask!(ctx.rc, ctx.harsha.user, ctx.dev, "yes")
    assert llm_requests() != []
  end

  test "other users are never intercepted", ctx do
    {:ok, _} = RootInbox.post("apk", "Install nightly.60?", ["Yes", "Not now"])
    _ = posts()
    :ok = ask!(ctx.krc, ctx.kamal.user, ctx.kdev, "Root: Yes")
    [{rc, body, _}] = posts()
    assert rc == ctx.krc and body != RootInbox.ack()
    assert llm_requests() != []
    assert RootInbox.fetch_new() == []
  end

  test "a new post on a topic supersedes the old one; duplicate text is refused", _ctx do
    {:ok, a} = RootInbox.post("apk", "Install nightly.60?", ["Yes", "Not now"])
    assert {:error, :duplicate} = RootInbox.post("apk", "Install nightly.60?", ["Yes", "Not now"])
    {:ok, b} = RootInbox.post("apk", "Install nightly.61 instead?", ["Yes", "Not now"])
    assert a != b
    {:ok, _} = RootInbox.post("sudo", "Run the ufw rule?", ["Done"])
    _ = posts()

    # The superseded question is never answered; "Root:" goes to the newest open question.
    :ok =
      RootInbox.intercept(
        RisiMe.RisiChat.active_id(RootInbox.user()),
        %{"text" => "Root: Done"},
        Ecto.UUID.generate(),
        RootInbox.user()
      )

    :ok =
      RootInbox.intercept(
        RisiMe.RisiChat.active_id(RootInbox.user()),
        %{"text" => "Root: Yes"},
        Ecto.UUID.generate(),
        RootInbox.user()
      )

    answers = RootInbox.fetch_new()

    assert Enum.map(answers, &{&1.topic, &1.question, &1.answer}) == [
             {"sudo", "Run the ufw rule?", "Done"},
             {"apk", "Install nightly.61 instead?", "Yes"}
           ]
  end

  test "without the config nothing is posted or intercepted", ctx do
    Application.delete_env(:risime, :risi_root_notify_user)
    System.delete_env("RISI_ROOT_NOTIFY_USER")
    assert {:error, :not_configured} = RootInbox.post("apk", "Install?", ["Yes"])
    assert posts() == []
    :ok = ask!(ctx.rc, ctx.harsha.user, ctx.dev, "Root: Yes")
    [{_, body, _}] = posts()
    assert body != RootInbox.ack()
    assert llm_requests() != []
  end
end
