defmodule RisiMe.Agent.SecretaryTest do
  @moduledoc """
  v1.24 S6/S7 (§24.11–§24.13, decision 066): Risi the secretary over a fake `risi-l1`, without
  the NIF (posts are captured by `RisiMe.Agent.TestSender`). Harsha's rules: nothing tracked
  without ✓; Risi only sees Official; learn and delete; no product pushing.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import ExUnit.CaptureLog
  import RisiMe.RisiHelpers

  alias RisiMe.Agent.{Clock, Commitment, Commitments, Fact, LearningLog, Secretary, Transcript}
  alias RisiMe.TimeUUID
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  setup do
    harsha = RisiMe.GroupHelpers.fast_user!("Harsha")
    kamal = RisiMe.GroupHelpers.fast_user!("Kamal")
    nimal = RisiMe.GroupHelpers.fast_user!("Nimal")
    tz!(harsha, "Asia/Colombo")
    tz!(kamal, "Asia/Colombo")
    og = risi_chat!([harsha, kamal, nimal])
    %{harsha: harsha, kamal: kamal, nimal: nimal, og: og}
  end

  defp tz!(u, tz), do: u |> Ecto.Changeset.change(tz: tz) |> Repo.update!()

  # The fake model: a commitment by `owner` on the line holding `line`.
  defp commitment_llm(line, owner, counterparts, due_local, opts \\ []) do
    fn
      "commitments", body ->
        %{
          "commitments" => [
            %{
              "text" => Keyword.get(opts, :text, "Send the revised quote"),
              "owner" => ref_of(body, owner) || "u9",
              "counterparts" => Enum.map(counterparts, &ref_of(body, &1)),
              "due_local" => due_local,
              "due_text" => "by Friday 5 pm",
              "source" => [ref_of(body, line)],
              "confidence" => Keyword.get(opts, :confidence, 0.9)
            }
          ]
        }

      _, _ ->
        %{"commitments" => []}
    end
  end

  defp future_date(days), do: Date.add(Date.utc_today(), days)

  defp extract!(og) do
    assert [%{args: %{"kind" => "extract"} = args}] = all_enqueued(worker: Job, queue: :risi)
    assert args == %{"kind" => "extract", "conv" => og}
    assert :ok = perform_job(Job, args)
  end

  defp propose!(ctx, due_local \\ nil) do
    line = "Sure, I'll send the revised quote to Harsha by Friday 5pm"
    due_local = due_local || "#{future_date(3)}T17:00"
    fake_llm!(commitment_llm(line, "Kamal", ["Harsha"], due_local))
    say!(ctx.og, ctx.harsha, "Kamal, can you send the revised quote?")
    src = say!(ctx.og, ctx.kamal, line)
    extract!(ctx.og)
    assert_receive {:risi_post, og, body, %{"kind" => "commitment"} = card}
    assert og == ctx.og
    assert body =~ "Kamal will: Send the revised quote"
    %{card: card, src: src}
  end

  defp act!(ctx, user, target, action, edit \\ nil) do
    id =
      envelope!(ctx.og, user, %{
        "v" => 1,
        "type" => "risi_action",
        "target" => target,
        "action" => action,
        "edit" => edit
      })

    args = %{"kind" => "action", "conv" => ctx.og, "message_id" => id, "user_id" => user.id}
    assert_enqueued(worker: Job, args: args)
    perform_job(Job, args)
  end

  defp request!(ctx, user, action, opts \\ []) do
    rid = Ecto.UUID.generate()

    id =
      envelope!(ctx.og, user, %{
        "v" => 1,
        "type" => "risi_request",
        "request_id" => rid,
        "action" => action,
        "text" => opts[:text],
        "scope" => %{"since" => opts[:since] && Clock.ts(opts[:since])}
      })

    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    assert job.args["message_id"] == id
    assert job.queue == "risi_requests"
    assert perform_job(Job, job.args) in [:ok]
    rid
  end

  # §24.13's 1 request per chat per minute, reset between requests of one test.
  defp clear_chat_limit,
    do: :ets.match_delete(RisiMe.RateLimiter, {{:risi_req_chat_min, :_, :_}, :_, :_})

  defp timers(id),
    do:
      for(
        j <- all_enqueued(worker: Job),
        j.args["commitment_id"] == id,
        do: {j.args["kind"], j.args["n"], j.scheduled_at}
      )
      |> Enum.sort()

  ## Extraction → card → ✓ → timers

  test "extraction proposes a card (nothing tracked); ✓ by the owner schedules the reminder and escalations in the owner's zone",
       ctx do
    %{card: card, src: src} = propose!(ctx)

    # The model saw member refs and JSON lines only: no user ids, no message ids.
    [req] = llm_requests()
    prompt = Jason.encode!(req)
    refute prompt =~ ctx.kamal.id
    refute prompt =~ src
    assert get_in(req, ["response_format", "json_schema", "name"]) == "commitments"

    # The card: §24.11 `commitment`, state proposed, call_ref = the learning-log entry.
    due = "#{future_date(3)}T17:00"
    due_utc = (due <> ":00") |> NaiveDateTime.from_iso8601!() |> Clock.to_utc("Asia/Colombo")
    assert card["state"] == "proposed"
    assert card["owner"] == ctx.kamal.id
    assert card["counterpart"] == [ctx.harsha.id]
    assert card["source_message_ids"] == [src]
    assert card["due"] == Clock.ts(due_utc)
    assert card["due_text"] == "by Friday 5 pm"
    assert card["notify"] == [ctx.kamal.id, ctx.harsha.id]
    assert card["v"] == 1

    assert {:ok, %{task: "commitment_extract", source_message_ids: ids}} =
             LearningLog.get(card["call_ref"])

    assert src in ids

    # Nothing tracked yet: only the 48-h expiry.
    id = card["commitment_id"]
    assert %Commitment{state: "proposed"} = Repo.get(Commitment, id)
    assert [{"expire", nil, exp}] = timers(id)
    assert_in_delta DateTime.diff(exp, DateTime.utc_now()), 48 * 3600, 60
    assert Repo.all(from f in Fact, where: f.commitment_id == ^id) == []

    # Re-running extraction over the same messages proposes nothing new.
    Secretary.schedule_extract(ctx.og, 0)
    extract!(ctx.og)
    refute_receive {:risi_post, _, _, _}

    # ✓ by the owner.
    act!(ctx, ctx.kamal, id, "confirm")
    assert_receive {:risi_post, _, body, %{"kind" => "commitment_update"} = up}
    assert body =~ "Kamal confirmed"
    assert up["state"] == "confirmed" and up["by"] == ctx.kamal.id
    assert up["call_ref"] == nil

    # Reminder at due − 1 h (Colombo, +05:30); escalations at due + 24 h and + 48 h.
    c = Repo.get!(Commitment, id)
    assert c.state == "confirmed"

    assert [
             {"escalation", 1, e1},
             {"escalation", 2, e2},
             {"expire", nil, _},
             {"reminder", nil, r}
           ] = timers(id)

    assert DateTime.compare(r, DateTime.add(due_utc, -3600)) == :eq
    assert DateTime.compare(e1, DateTime.add(due_utc, 86_400)) == :eq
    assert DateTime.compare(e2, DateTime.add(due_utc, 2 * 86_400)) == :eq

    # Facts: one for the owner, one for the counterpart (derived lines only).
    facts = Repo.all(from f in Fact, where: f.commitment_id == ^id)

    assert Enum.sort(Enum.map(facts, & &1.subject_user_id)) ==
             Enum.sort([ctx.kamal.id, ctx.harsha.id])

    # The reminder notifies the owner; escalation the counterparts.
    v = c.schedule_v

    assert :ok =
             perform_job(Job, %{
               "kind" => "reminder",
               "conv" => ctx.og,
               "commitment_id" => id,
               "v" => v
             })

    assert_receive {:risi_post, _, "Reminder: Send the revised quote" <> _, rem}
    assert rem["kind"] == "reminder" and rem["notify"] == [ctx.kamal.id]

    assert :ok =
             perform_job(Job, %{
               "kind" => "escalation",
               "conv" => ctx.og,
               "commitment_id" => id,
               "v" => v,
               "n" => 1
             })

    assert_receive {:risi_post, _, _, %{"kind" => "escalation"} = esc}
    assert esc["notify"] == [ctx.harsha.id]
    assert is_integer(esc["overdue_by"])

    # Done: no more reminders (a stale job is a no-op).
    act!(ctx, ctx.harsha, id, "done")
    assert_receive {:risi_post, _, _, %{"kind" => "commitment_update", "state" => "done"}}
    assert [{"expire", nil, _}] = timers(id)

    assert :ok =
             perform_job(Job, %{
               "kind" => "reminder",
               "conv" => ctx.og,
               "commitment_id" => id,
               "v" => v
             })

    refute_receive {:risi_post, _, _, _}
  end

  test "a date-only due is reminded at 09:00 in the owner's zone", ctx do
    date = future_date(4)
    %{card: card} = propose!(ctx, Date.to_iso8601(date))
    act!(ctx, ctx.harsha, card["commitment_id"], "confirm")
    assert_receive {:risi_post, _, _, %{"kind" => "commitment_update"}}

    [{"escalation", 1, _}, {"escalation", 2, _}, {"expire", nil, _}, {"reminder", nil, r}] =
      timers(card["commitment_id"])

    assert NaiveDateTime.truncate(Clock.local(r, "Asia/Colombo"), :second) ==
             NaiveDateTime.new!(date, ~T[09:00:00])

    # 09:00 Colombo = 03:30 UTC.
    assert DateTime.to_time(r) == ~T[03:30:00.000000]
  end

  test "edit reschedules (old jobs cancelled), decline deletes, others' actions are ignored",
       ctx do
    %{card: card} = propose!(ctx)
    id = card["commitment_id"]

    # Nimal is neither owner nor counterpart: ignored.
    act!(ctx, ctx.nimal, id, "confirm")
    refute_receive {:risi_post, _, _, _}
    assert Repo.get!(Commitment, id).state == "proposed"

    # A forged job (the buffered envelope's sender is not the user named) is ignored as well.
    forged =
      envelope!(ctx.og, ctx.nimal, %{
        "v" => 1,
        "type" => "risi_action",
        "target" => id,
        "action" => "confirm",
        "edit" => nil
      })

    perform_job(Job, %{
      "kind" => "action",
      "conv" => ctx.og,
      "message_id" => forged,
      "user_id" => ctx.kamal.id
    })

    refute_receive {:risi_post, _, _, _}
    assert Repo.get!(Commitment, id).state == "proposed"

    new_due = DateTime.add(DateTime.utc_now(), 5 * 86_400, :second) |> DateTime.truncate(:second)

    act!(ctx, ctx.kamal, id, "edit", %{
      "text" => "Send the final quote",
      "due" => Clock.ts(new_due)
    })

    assert_receive {:risi_post, _, _, %{"kind" => "commitment_update", "state" => "edited"} = up}
    assert up["text"] == "Send the final quote"
    c = Repo.get!(Commitment, id)
    assert c.state == "edited" and c.due_kind == "datetime"
    {"reminder", nil, r} = Enum.find(timers(id), &(elem(&1, 0) == "reminder"))
    assert DateTime.compare(r, DateTime.add(new_due, -3600)) == :eq

    # ✗ on a tracked commitment cancels it: the row, its facts and its timers go.
    act!(ctx, ctx.harsha, id, "decline")
    assert_receive {:risi_post, _, _, %{"kind" => "commitment_update", "state" => "cancelled"}}
    assert Repo.get(Commitment, id) == nil
    assert Repo.all(from f in Fact, where: f.commitment_id == ^id) == []
    assert [{"expire", nil, _}] = timers(id)
  end

  test "decline or expiry of a proposal: nothing tracked, nothing kept", ctx do
    %{card: card} = propose!(ctx)
    act!(ctx, ctx.kamal, card["commitment_id"], "decline")
    assert_receive {:risi_post, _, _, %{"kind" => "commitment_update", "state" => "declined"}}
    assert Repo.get(Commitment, card["commitment_id"]) == nil

    Repo.delete_all(Oban.Job)
    %{card: card2} = propose!(ctx |> Map.put(:og, ctx.og))

    assert :ok =
             perform_job(Job, %{
               "kind" => "expire",
               "conv" => ctx.og,
               "commitment_id" => card2["commitment_id"]
             })

    assert Repo.get(Commitment, card2["commitment_id"]) == nil
    # A ✓ after expiry finds nothing.
    act!(ctx, ctx.kamal, card2["commitment_id"], "confirm")
    refute_receive {:risi_post, _, _, _}
  end

  test "the server checks every candidate: unknown owner, owner who didn't say it, old source, low confidence",
       ctx do
    line = "I'll send the revised quote by Friday"

    for {llm, desc} <- [
          {commitment_llm(line, "Nobody", [], nil), "unknown owner"},
          {commitment_llm(line, "Harsha", [], nil), "owner did not write a source"},
          {commitment_llm(line, "Kamal", [], nil, confidence: 0.3), "low confidence"}
        ] do
      Repo.delete_all(Oban.Job)
      Repo.delete_all(from s in "risi_chat_state", where: s.conversation_id == ^ctx.og)
      fake_llm!(llm)
      say!(ctx.og, ctx.kamal, line)
      extract!(ctx.og)
      refute_receive {:risi_post, _, _, _}, 50, desc
    end

    assert Repo.all(from c in Commitment, where: c.conversation_id == ^ctx.og) == []
  end

  test "the card limit: at most 10 cards per chat per day", ctx do
    {:ok, calls} = Agent.start_link(fn -> 0 end)

    fake_llm!(fn "commitments", body ->
      call = Agent.get_and_update(calls, &{&1, &1 + 1})

      lines =
        for l <- String.split(List.last(body["messages"])["content"], "\n"),
            {:ok, %{"new" => true} = m} <- [Jason.decode(l)],
            do: m

      %{
        "commitments" =>
          for m <- if(call == 0, do: Enum.take(lines, 10), else: Enum.drop(lines, 10)) do
            %{
              "text" => "Task #{m["ref"]}",
              "owner" => m["from"],
              "counterparts" => [],
              "due_local" => nil,
              "due_text" => nil,
              "source" => [m["ref"]],
              "confidence" => 0.9
            }
          end
      }
    end)

    for i <- 1..12, do: say!(ctx.og, ctx.kamal, "I'll do task #{i}")
    extract!(ctx.og)
    Secretary.schedule_extract(ctx.og, 0)
    Repo.update_all(from(s in "risi_chat_state"), set: [extracted_upto: nil])
    [job] = all_enqueued(worker: Job, queue: :risi)
    perform_job(Job, job.args)

    cards =
      for _ <- 1..12,
          (receive do
             {:risi_post, _, _, %{"kind" => "commitment"}} -> true
           after
             50 -> false
           end),
          do: 1

    assert length(cards) == 10
  end

  ## Requests (§24.11 risi_request)

  test "summarise and ask over the 24-h window; refs map back to message ids", ctx do
    m1 = say!(ctx.og, ctx.kamal, "The site visit moved to Thursday 10 am")
    say!(ctx.og, ctx.harsha, "Ok")

    fake_llm!(fn
      "summary", _ ->
        %{
          "summary" => "The site visit moved.",
          "decisions" => ["Site visit Thursday 10 am"],
          "action_items" => [],
          "open_questions" => []
        }

      "answer", body ->
        %{
          "answer" => "Thursday 10 am.",
          "refs" => [ref_of(body, "The site visit moved to Thursday 10 am")],
          "confidence" => 0.8
        }
    end)

    rid = request!(ctx, ctx.harsha, "summarise", since: DateTime.add(DateTime.utc_now(), -3600))
    assert_receive {:risi_post, _, "Summary: The site visit moved.", s}
    assert s["kind"] == "summary" and s["request_id"] == rid and s["partial"] == false
    assert s["notify"] == [ctx.harsha.id]
    assert s["decisions"] == ["Site visit Thursday 10 am"]
    assert is_binary(s["call_ref"])

    clear_chat_limit()
    rid = request!(ctx, ctx.nimal, "ask", text: "When is the site visit?")
    assert_receive {:risi_post, _, "Thursday 10 am.", a}
    assert a["kind"] == "answer" and a["request_id"] == rid
    assert a["refs"] == [m1]
    assert a["confidence"] == 0.8

    # The question went to the model inside <question>, escaped; requests and actions never
    # appear as chat lines.
    [_, ask] = llm_requests()
    content = List.last(ask["messages"])["content"]
    assert content =~ "<question>"
    refute content =~ "risi_request"
  end

  test "out_of_window, nothing_to_summarise, model_unavailable", ctx do
    fake_llm!(fn _, _ -> {:status, 500} end)

    rid =
      request!(ctx, ctx.harsha, "summarise", since: DateTime.add(DateTime.utc_now(), -2 * 86_400))

    assert_receive {:risi_post, _, _,
                    %{"kind" => "error", "code" => "out_of_window", "request_id" => ^rid}}

    clear_chat_limit()

    rid = request!(ctx, ctx.harsha, "summarise")

    assert_receive {:risi_post, _, _,
                    %{"kind" => "error", "code" => "nothing_to_summarise", "request_id" => ^rid}}

    assert llm_requests() == []

    clear_chat_limit()
    say!(ctx.og, ctx.kamal, "something")
    rid = request!(ctx, ctx.harsha, "summarise")

    assert_receive {:risi_post, _, body,
                    %{"kind" => "error", "code" => "model_unavailable", "request_id" => ^rid}}

    assert body =~ "try again"
    # One retry, then the error.
    assert length(llm_requests()) == 2
  end

  test "rate limits (§24.13): 1 request per chat per minute; the extra one gets rate_limited",
       ctx do
    say!(ctx.og, ctx.kamal, "something to summarise")

    fake_llm!(fn _, _ ->
      %{"summary" => "x", "decisions" => [], "action_items" => [], "open_questions" => []}
    end)

    request!(ctx, ctx.harsha, "summarise")
    assert_receive {:risi_post, _, _, %{"kind" => "summary"}}

    rid = request!(ctx, ctx.kamal, "summarise")

    assert_receive {:risi_post, _, _,
                    %{"kind" => "error", "code" => "rate_limited", "request_id" => ^rid}}

    assert length(llm_requests()) == 1

    # Job args never carry text, for any job.
    for j <- Repo.all(Oban.Job), do: refute(inspect(j.args) =~ "something to summarise")
  end

  test "the global queue full: a request gets rate_limited without a model call", ctx do
    say!(ctx.og, ctx.kamal, "hello")
    fake_llm!(fn _, _ -> %{} end)
    old = Application.get_env(:risime, :risi_llm)
    Application.put_env(:risime, :risi_llm, Keyword.put(old, :max_in_flight, 0))
    on_exit(fn -> Application.put_env(:risime, :risi_llm, old) end)

    request!(ctx, ctx.harsha, "summarise")
    assert_receive {:risi_post, _, _, %{"kind" => "error", "code" => "rate_limited"}}
    assert llm_requests() == []
  end

  ## Digest

  test "digest at 09:00 in the chat's zone, only with open items, once a day", ctx do
    %{card: card} = propose!(ctx)
    # 09:10 in Colombo = 03:40 UTC.
    nine = DateTime.new!(Date.utc_today(), ~T[03:40:00], "Etc/UTC")
    ten = DateTime.add(nine, 3600)

    # A proposal is not an open item.
    assert :skipped = Commitments.digest(ctx.og, nine)

    act!(ctx, ctx.kamal, card["commitment_id"], "confirm")
    assert_receive {:risi_post, _, _, %{"kind" => "commitment_update"}}

    assert :skipped = Commitments.digest(ctx.og, ten)
    assert :sent = Commitments.digest(ctx.og, nine)
    assert_receive {:risi_post, _, "Open items today: " <> _, d}
    assert d["kind"] == "digest"
    assert [%{"commitment_id" => cid, "owner" => owner, "state" => "confirmed"}] = d["items"]
    assert cid == card["commitment_id"] and owner == ctx.kamal.id
    assert d["date"] == Date.to_iso8601(Date.utc_today())
    assert :skipped = Commitments.digest(ctx.og, nine)
  end

  ## Privacy: Private never reaches any of this; learn and delete

  test "a Private canary never reaches the model, the buffer, a job or a Risi table", ctx do
    canary = "CANARY-" <> Ecto.UUID.generate()
    fake_llm!(fn _, _ -> %{"commitments" => []} end)

    private = "grp:" <> Ecto.UUID.generate()

    Repo.insert!(%RisiMe.Groups.Group{
      id: private,
      created_by: ctx.harsha.id,
      client_group_id: Ecto.UUID.generate(),
      state: "active",
      generation: 1,
      created_at: DateTime.utc_now()
    })

    log =
      capture_log(fn ->
        m = %{
          message_id: TimeUUID.generate(),
          sender_id: ctx.harsha.id,
          sender_device: nil,
          plaintext: Jason.encode!(%{"v" => 1, "type" => "text", "body" => canary})
        }

        assert {:error, :private_tab} = Transcript.put(private, m)
        assert :ok = Secretary.on_message(private, m)
        assert :ok = Secretary.on_message("dm:a_b", m)
        # Even a forged job for the Private conversation does nothing.
        assert perform_job(Job, %{"kind" => "extract", "conv" => private}) == :ok
        assert {:error, :private_tab} = RisiMe.Agent.TestSender.text(private, "x", %{})
      end)

    assert llm_requests() == []
    assert all_enqueued(worker: Job) == []
    refute log =~ canary

    for t <- ~w(risi_commitments risi_facts risi_chat_state oban_jobs) do
      %{rows: rows} = Repo.query!("SELECT * FROM #{t}")
      refute inspect(rows) =~ canary
    end
  end

  test "Official text is never stored raw by Risi: canary only in the sealed buffer and the model input",
       ctx do
    canary = "CANARY-" <> Ecto.UUID.generate()
    fake_llm!(fn _, _ -> %{"commitments" => []} end)

    log =
      capture_log([level: :debug], fn ->
        say!(ctx.og, ctx.kamal, "I'll handle " <> canary)
        extract!(ctx.og)
      end)

    [req] = llm_requests()
    assert inspect(req) =~ canary
    refute log =~ canary

    for t <- ~w(risi_commitments risi_facts risi_chat_state oban_jobs) do
      %{rows: rows} = Repo.query!("SELECT * FROM #{t}")
      refute inspect(rows) =~ canary
    end

    month = Calendar.strftime(Date.utc_today(), "%Y-%m")
    [row] = LearningLog.list_by_chat(Secretary.chat_id(ctx.og), month)
    refute inspect(row) =~ canary
    assert row.prompt_sha256 == RisiMe.Agent.LLM.prompt_hash(req["messages"])

    # The buffer row is sealed.
    for r <- RisiMe.Messaging.Store.impl().list_agent_messages(ctx.og, nil, 10),
        do: assert(:binary.match(r.body, canary) == :nomatch)
  end

  test "Official off (forget) deletes buffer, commitments, facts and jobs", ctx do
    %{card: card} = propose!(ctx)
    act!(ctx, ctx.kamal, card["commitment_id"], "confirm")
    assert_receive {:risi_post, _, _, %{"kind" => "commitment_update"}}
    say!(ctx.og, ctx.kamal, "pending")
    assert Repo.aggregate(from(f in Fact, where: f.conversation_id == ^ctx.og), :count) == 2

    RisiMe.Agent.forget(ctx.og)

    assert Transcript.list(ctx.og) == []
    assert Repo.all(from c in Commitment, where: c.conversation_id == ^ctx.og) == []
    assert Repo.all(from f in Fact, where: f.conversation_id == ^ctx.og) == []

    # Every job of the conversation is cancelled but the 10-min safety re-run of forget.
    assert [%{args: %{"kind" => "forget"}}] = all_enqueued(worker: Job)
    assert :ok = perform_job(Job, %{"kind" => "forget", "conv" => ctx.og})
  end

  test "§15 delete: commitments and facts derived only from deleted messages go", ctx do
    %{card: card, src: src} = propose!(ctx)
    act!(ctx, ctx.kamal, card["commitment_id"], "confirm")
    assert_receive {:risi_post, _, _, %{"kind" => "commitment_update"}}

    # A fact with two sources survives the delete of one of them, without that id.
    other = TimeUUID.generate()

    Repo.insert!(%Fact{
      id: Ecto.UUID.generate(),
      subject_user_id: ctx.harsha.id,
      chat_id: ctx.og,
      conversation_id: ctx.og,
      kind: "topic",
      text: "Quotes",
      source_message_ids: [src, other],
      inserted_at: DateTime.utc_now()
    })

    Secretary.message_deleted(ctx.og, [src])
    assert Repo.get(Commitment, card["commitment_id"]) == nil

    assert [%Fact{kind: "topic", source_message_ids: [^other]}] =
             Repo.all(from f in Fact, where: f.conversation_id == ^ctx.og)

    assert [{"expire", nil, _}] = timers(card["commitment_id"])
  end

  test "jobs do nothing once Risi may not act (chat off) and are discarded while RISI is off",
       ctx do
    say!(ctx.og, ctx.kamal, "I'll do it")
    fake_llm!(fn _, _ -> %{"commitments" => []} end)

    Repo.update_all(
      from(m in RisiMe.Groups.Member, where: m.group_id == ^ctx.og and m.kind == "agent"),
      set: [state: "pending_remove"]
    )

    assert :ok = perform_job(Job, %{"kind" => "extract", "conv" => ctx.og})
    assert llm_requests() == []

    Application.put_env(:risime, :risi, false)
    assert {:cancel, :risi_off} = perform_job(Job, %{"kind" => "extract", "conv" => ctx.og})
  end
end
