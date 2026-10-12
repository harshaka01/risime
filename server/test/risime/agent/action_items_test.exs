defmodule RisiMe.Agent.ActionItemsTest do
  @moduledoc """
  Contract v1.42 §38 (decision 082): Risi voice → action items. The `extract_actions` model is
  faked; Risi's posts reach the test process. Covers the triggers (voice note, call), the
  quality gate (unclear → no items and no model call), opt-out speakers, Private and the Risi
  chat producing nothing, the two cards against the examples, the owner actions
  (`item_confirm`, `item_edit`, `item_calendar` → exactly one event, `item_remind`,
  `item_not_mine`, owner-only, expiry), cancelling on deletes, retention, the learning log
  (counts, never text) and My promises' `kind` / `source_ref` / `source=`.
  """
  use RisiMeWeb.ConnCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import Ecto.Query, only: [from: 2]
  import RisiMe.RisiHelpers
  import RisiMe.LedgerHelpers, only: [ledger_on!: 0, clock!: 1, ledger_devices!: 1]

  alias RisiMe.Agent.{ActionItems, Commitment, LearningLog, Secretary, Transcript, Voice}
  alias RisiMe.Agent.CallTranscribe.Session
  alias RisiMe.{Repo, TimeUUID}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  @examples Path.expand("../../../../contract/v1/examples", __DIR__)
  defp example(name), do: @examples |> Path.join(name) |> File.read!() |> Jason.decode!()
  defp keys(m), do: m |> Map.keys() |> Enum.sort()

  setup do
    ledger_on!()
    restore_on_exit([:risi_action_items])
    Application.put_env(:risime, :risi_action_items, true)
    now = DateTime.utc_now() |> DateTime.truncate(:millisecond)
    clock!(%{now | microsecond: {elem(now.microsecond, 0), 6}})
    RisiMe.MLSHelpers.with_attestation_key(%{})
    harsha = RisiMe.GroupHelpers.fast_user!("Harsha")
    kamal = RisiMe.GroupHelpers.fast_user!("Kamal")
    ledger_devices!([harsha])
    og = risi_chat!([harsha, kamal])
    rc = own_risi_chat!(harsha)
    %{harsha: harsha, kamal: kamal, og: og, rc: rc}
  end

  # The model: `fun.(body)` gives the items (refs resolved with `ref_of/2`).
  defp llm!(fun, summary \\ "Quotation and site visit.") do
    fake_llm!(fn
      "action_items", body -> %{"summary" => summary, "items" => fun.(body)}
      _, _ -> %{"commitments" => []}
    end)
  end

  defp it(body, kind, text, owner, line, opts \\ []) do
    %{
      "kind" => kind,
      "text" => text,
      "owner" => owner && (ref_of(body, owner) || owner),
      "counterparts" => Enum.map(Keyword.get(opts, :to, []), &ref_of(body, &1)),
      "due_local" => Keyword.get(opts, :due),
      "due_text" => Keyword.get(opts, :due_text),
      "source" => [ref_of(body, line)],
      "lang" => Keyword.get(opts, :lang, "en"),
      "confidence" => 0.9
    }
  end

  defp posts(acc \\ []) do
    receive do
      {:risi_post, conv, body, risi} -> posts([{conv, body, risi} | acc])
    after
      80 -> Enum.reverse(acc)
    end
  end

  defp post_in(posts, conv, kind),
    do: Enum.find(posts, fn {c, _, r} -> c == conv and r["kind"] == kind end)

  # A voice note's working copy as `Agent.Voice` buffers it (§37.6), then the job.
  defp voice!(w, user, parts, opts \\ []) do
    id = TimeUUID.at(DateTime.add(RisiMe.Agent.Clock.now(), -60, :second))

    env = %{
      "v" => 1,
      "type" => "voice_transcript",
      "status" => Keyword.get(opts, :status, "ok"),
      "text" => Enum.map_join(parts, " ", & &1["t"]),
      "language" => Keyword.get(opts, :language, "en"),
      "reason" => Keyword.get(opts, :reason),
      "p" => 0.95,
      "romanised" => false,
      "model" => "faster-whisper-large-v3",
      "parts" => parts,
      "duration_ms" => Keyword.get(opts, :duration_ms, 12_400)
    }

    :ok =
      Transcript.put(w.og, %{
        message_id: id,
        sender_id: user.id,
        sender_device: nil,
        plaintext: Jason.encode!(env)
      })

    id
  end

  defp run_voice!(w, id), do: ActionItems.from_voice(w.og, id)

  defp session!(w) do
    started = DateTime.add(RisiMe.Agent.Clock.now(), -600, :second)

    %Session{
      session_id: Ecto.UUID.generate(),
      call_id: Ecto.UUID.generate(),
      conversation_id: w.og,
      chat_id: Secretary.chat_id(w.og),
      state: "ended",
      discard: false,
      started_at: started,
      ended_at: DateTime.add(started, 300, :second)
    }
  end

  defp row(s, speaker, turn, parts, opts \\ []) do
    %{
      message_id: TimeUUID.generate(),
      speaker: speaker.id,
      text: Enum.map_join(parts, " ", & &1["t"]),
      language: Keyword.get(opts, :language, "en"),
      model: "faster-whisper-large-v3",
      ms: Keyword.get(opts, :ms, 20_000),
      start: DateTime.add(s.started_at, turn * 30, :second),
      turn: turn,
      parts: parts,
      p: Keyword.get(opts, :p, 0.95),
      romanised: Keyword.get(opts, :romanised, false)
    }
  end

  describe "voice notes" do
    test "the Official card and the owner's confirm card match the examples; counts only in the log",
         w do
      llm!(fn body ->
        [
          it(body, "promise", "Send the quotation", "Harsha", "I'll send the quotation",
            to: ["Kamal"]
          ),
          it(body, "number", "Budget: LKR 450,000", nil, "the budget is 450,000")
        ]
      end)

      parts = [
        %{"o" => 0, "t" => "the budget is 450,000"},
        %{"o" => 6100, "t" => "I'll send the quotation"}
      ]

      id = voice!(w, w.harsha, parts)
      assert :ok = run_voice!(w, id)
      out = posts()

      {_, body, card} = post_in(out, w.og, "action_items")
      ex = example("envelope_risi_action_items_voice_note.json")
      assert keys(card) == keys(ex["risi"])
      assert body == "Risi found 2 items in Harsha's voice note: 1 promise, 1 number."

      assert %{"source" => "voice_note", "status" => "ok", "summary" => nil, "notify" => []} =
               card

      assert card["source_ref"] == %{
               "type" => "voice_note",
               "call_id" => nil,
               "session_id" => nil,
               "message_id" => id,
               "turn" => nil,
               "offset_ms" => nil,
               "at" => RisiMe.Agent.Clock.ts(TimeUUID.to_datetime(id))
             }

      [promise, number] = card["items"]
      for i <- card["items"], do: assert(keys(i) == keys(hd(ex["risi"]["items"])))
      assert %{"kind" => "promise", "owner" => owner, "state" => "proposed"} = promise
      assert owner == w.harsha.id and promise["counterpart"] == [w.kamal.id]
      assert promise["source_ref"]["offset_ms"] == 6100
      assert promise["source_ref"]["message_id"] == id
      assert %{"kind" => "number", "owner" => nil, "state" => nil} = number
      assert keys(promise["source_ref"]) == keys(ex["risi"]["source_ref"])

      {_, cbody, confirm} = post_in(out, w.rc, "action_confirm")
      exc = example("envelope_risi_action_confirm_v143.json")
      assert keys(confirm) == keys(exc["risi"])
      assert confirm["for"] == [w.harsha.id] and confirm["notify"] == [w.harsha.id]
      assert [%{"item_id" => pid, "buttons" => buttons}] = confirm["items"]
      assert pid == promise["item_id"]
      # v1.43 §38.9: no due → [Add to calendar] (the phone asks with its picker); [Remind me]
      # always, with the Reminders skill state for the phone's note.
      assert buttons == ["add_promise", "add_calendar", "remind", "not_mine", "edit"]
      assert confirm["reminders_skill"] == "off"
      assert cbody =~ "From your voice note" and cbody =~ "• Send the quotation"

      # Stored: the ledger item (sealed, kind, source_ref), the number as an extra (30 days).
      c = Repo.get!(Commitment, pid)
      assert c.kind == "promise" and c.source == "voice_note" and c.item_state == "proposed"
      assert c.source_ref["offset_ms"] == 6100

      assert [%{kind: "number", text: "Budget: LKR 450,000"}] =
               ActionItems.extras(card["summary_id"])

      # The raw transcript is gone from the buffer after the extraction.
      assert Secretary.envelope(w.og, id) == nil

      # The learning log: counts per kind and owner, never text.
      [log] =
        LearningLog.list_by_chat(
          Secretary.chat_id(w.og),
          Calendar.strftime(Date.utc_today(), "%Y-%m")
        )
        |> Enum.filter(&(&1.task == "extract_actions"))

      refute inspect(log) =~ "quotation"
      refute inspect(log) =~ "450,000"
      assert log.meta =~ "\"source\":\"voice_note\""
    end

    test "unclear: the unclear card, no items, no model call", w do
      llm!(fn _ -> flunk("no model call") end)
      id = voice!(w, w.harsha, [], status: "unclear", reason: "script_check", language: "si")
      assert :ok = run_voice!(w, id)
      {_, body, card} = post_in(posts(), w.og, "action_items")
      ex = example("envelope_risi_action_items_unclear.json")
      assert keys(card) == keys(ex["risi"])

      assert %{"status" => "unclear", "reason" => "script_check", "items" => [], "lang" => "si"} =
               card

      # v1.43 §38.9 point 6: "I couldn't hear this clearly", in the source's language.
      assert body == example("envelope_risi_action_items_unclear_v143.json")["body"]
      assert body == ActionItems.unclear_text("si")
      assert llm_requests() == []
    end

    test "a clear note with no items posts nothing", w do
      llm!(fn _ -> [] end)
      id = voice!(w, w.harsha, [%{"o" => 0, "t" => "hello"}])
      assert :ok = run_voice!(w, id)
      assert posts() == []
    end

    test "the trigger: switch, ≥ 5 s, the sender's opt-outs; Private and the Risi chat never",
         w do
      Repo.delete_all(Oban.Job)
      ActionItems.voice_transcribed(w.og, TimeUUID.generate(), w.harsha.id, "ok", 4_999)
      ActionItems.voice_transcribed(w.og, TimeUUID.generate(), w.harsha.id, "failed", 9_000)
      ActionItems.voice_transcribed(w.rc, TimeUUID.generate(), w.harsha.id, "ok", 9_000)
      assert all_enqueued(worker: Job, args: %{"kind" => "voice_actions"}) == []

      {:ok, _} = Voice.put_setting(w.kamal.id, %{"transcribe" => true, "action_items" => false})
      ActionItems.voice_transcribed(w.og, TimeUUID.generate(), w.kamal.id, "ok", 9_000)
      assert all_enqueued(worker: Job, args: %{"kind" => "voice_actions"}) == []

      Application.put_env(:risime, :risi_action_items, false)
      ActionItems.voice_transcribed(w.og, TimeUUID.generate(), w.harsha.id, "ok", 9_000)
      assert all_enqueued(worker: Job, args: %{"kind" => "voice_actions"}) == []

      Application.put_env(:risime, :risi_action_items, true)
      ActionItems.voice_transcribed(w.og, TimeUUID.generate(), w.harsha.id, "unclear", 5_000)
      assert [_] = all_enqueued(worker: Job, args: %{"kind" => "voice_actions"})

      # Private: no extraction, no post, no model call (and a Private id never reaches it).
      llm!(fn _ -> flunk("no model call") end)
      private = "grp:" <> Ecto.UUID.generate()
      assert :ok = ActionItems.from_voice(private, TimeUUID.generate())
      dm = RisiMe.Messaging.conversation_id(w.harsha.id, w.kamal.id)
      assert :ok = ActionItems.from_call(dm, session!(w), [])
      assert posts() == [] and llm_requests() == []
    end
  end

  describe "calls" do
    test "the call card: turns and offsets; opted-out speakers are never in the input or owners",
         w do
      s = session!(w)

      # Kamal opted out of memory: his lines are not extracted from.
      Repo.insert_all("risi_learning_optouts", [
        %{
          user_id: Ecto.UUID.dump!(w.kamal.id),
          scope: "*",
          enabled: false,
          updated_at: DateTime.utc_now()
        }
      ])

      llm!(fn body ->
        [
          it(body, "meeting", "Site visit at level 3", "Harsha", "site visit Wednesday 10",
            due: "2099-10-14T10:00",
            due_text: "Wednesday 10 am"
          ),
          it(body, "decision", "Use the local supplier", nil, "we use the local supplier"),
          # The model names Kamal as owner: he opted out, so the item is unassigned.
          it(body, "task", "Order the tiles", "Kamal", "we use the local supplier")
        ]
      end)

      rows = [
        row(s, w.harsha, 1, [
          %{"o" => 0, "t" => "site visit Wednesday 10"},
          %{"o" => 4200, "t" => "we use the local supplier"}
        ]),
        row(s, w.kamal, 2, [%{"o" => 0, "t" => "secret plans of Kamal"}])
      ]

      assert :ok = ActionItems.from_call(w.og, s, rows)
      [req] = llm_requests()
      refute inspect(req) =~ "secret plans"

      out = posts()
      {_, body, card} = post_in(out, w.og, "action_items")
      ex = example("envelope_risi_action_items.json")
      assert keys(card) == keys(ex["risi"])
      assert body =~ "Risi found 3 items in this call: 1 task, 1 meeting, 1 decision."
      assert body =~ "Owners confirm theirs in their Risi chat."
      assert card["summary"] == "Quotation and site visit."

      assert card["source_ref"]["session_id"] == s.session_id and
               card["source_ref"]["turn"] == nil

      [task, meeting, decision] =
        Enum.sort_by(card["items"], & &1["kind"])
        |> then(fn l ->
          [
            Enum.find(l, &(&1["kind"] == "task")),
            Enum.find(l, &(&1["kind"] == "meeting")),
            Enum.find(l, &(&1["kind"] == "decision"))
          ]
        end)

      assert task["owner"] == nil and task["state"] == nil
      assert meeting["owner"] == w.harsha.id and meeting["state"] == "proposed"
      assert %{"turn" => 1, "offset_ms" => 0, "call_id" => cid} = meeting["source_ref"]
      assert cid == s.call_id
      assert %{"turn" => 1, "offset_ms" => 4200} = decision["source_ref"]

      {_, _, confirm} = post_in(out, w.rc, "action_confirm")
      assert [%{"buttons" => buttons}] = confirm["items"]
      assert "add_calendar" in buttons
    end

    test "the quality gate: low confidence and romanised Sinhala → unclear, no items, no model",
         w do
      llm!(fn _ -> flunk("no model call") end)
      s = session!(w)

      low = [
        row(s, w.harsha, 1, [%{"o" => 0, "t" => "a"}], p: 0.4),
        row(s, w.kamal, 2, [%{"o" => 0, "t" => "b"}], p: 0.5)
      ]

      assert :ok = ActionItems.from_call(w.og, s, low)
      {_, body, card} = post_in(posts(), w.og, "action_items")
      assert %{"status" => "unclear", "reason" => "low_confidence", "items" => []} = card
      assert body == "I couldn't hear this clearly."
      assert ActionItems.unclear_text("ta") == "இதை என்னால் தெளிவாகக் கேட்க முடியவில்லை."

      si =
        for {u, n} <- [{w.harsha, 1}, {w.kamal, 2}, {w.harsha, 3}, {w.kamal, 4}],
            do: row(s, u, n, [%{"o" => 0, "t" => "x"}], language: "si", romanised: n == 1)

      # 1 of 4 Sinhala turns romanised (25 %): clear. 2 of 4: unclear.
      assert ActionItems.gate(si) == nil
      si = List.update_at(si, 1, &%{&1 | romanised: true})
      assert ActionItems.gate(si) == "script_check"
      assert :ok = ActionItems.from_call(w.og, s, si)
      {_, _, card} = post_in(posts(), w.og, "action_items")
      assert card["reason"] == "script_check" and card["lang"] == "si"
      assert llm_requests() == []
      assert Repo.aggregate(Commitment, :count) == 0
    end
  end

  describe "owner actions (§38.4)" do
    setup w do
      llm!(fn body ->
        [
          it(body, "promise", "Send the quotation", "Harsha", "I'll send it",
            due: "2099-10-13T17:00",
            due_text: "tomorrow 5 pm"
          )
        ]
      end)

      id = voice!(w, w.harsha, [%{"o" => 0, "t" => "I'll send it"}])
      :ok = run_voice!(w, id)
      {_, _, card} = post_in(posts(), w.og, "action_items")
      [%{"item_id" => item}] = card["items"]
      %{note: id, item: item, summary_id: card["summary_id"]}
    end

    defp act!(w, user, rc, action, item, edit \\ nil) do
      env = %{
        "v" => 1,
        "type" => "risi_action",
        "target" => item,
        "action" => action,
        "edit" => edit
      }

      mid = TimeUUID.generate()

      m = %{
        message_id: mid,
        sender_id: user.id,
        sender_device: nil,
        plaintext: Jason.encode!(env)
      }

      :ok = Transcript.put(rc, m)
      Secretary.on_message(rc, m)

      case all_enqueued(worker: Job, args: %{"kind" => "action", "message_id" => mid}) do
        [job] -> perform_job(Job, job.args)
        [] -> :ok
      end
    end

    defp feedback_actions(w) do
      LearningLog.list_by_chat(
        Secretary.chat_id(w.og),
        Calendar.strftime(Date.utc_today(), "%Y-%m")
      )
      |> Enum.filter(&(&1.task == "action_feedback"))
      |> Enum.map(&Jason.decode!(&1.meta)["action"])
    end

    test "item_not_mine by the owner only: declined, item_update, feedback", w do
      rc_k = own_risi_chat!(w.kamal)
      act!(w, w.kamal, rc_k, "item_not_mine", w.item)
      assert Repo.get(Commitment, w.item)

      act!(w, w.harsha, w.rc, "item_not_mine", w.item)
      refute Repo.get(Commitment, w.item)
      {_, _, upd} = post_in(posts(), w.rc, "item_update")
      assert upd["state"] == "declined" and upd["item_id"] == w.item
      assert "not_mine" in feedback_actions(w)
    end

    test "item_remind confirms and sets a reminder at remind_at", w do
      at = DateTime.add(RisiMe.Agent.Clock.now(), 7200, :second)
      act!(w, w.harsha, w.rc, "item_remind", w.item, %{"remind_at" => RisiMe.Agent.Clock.ts(at)})
      c = Repo.get!(Commitment, w.item)
      assert c.item_state == "confirmed"

      assert Enum.any?(
               all_enqueued(
                 worker: Job,
                 args: %{"kind" => "item_reminder", "commitment_id" => w.item}
               ),
               &(DateTime.diff(&1.scheduled_at, at) |> abs() < 2)
             )

      assert "remind" in feedback_actions(w)
      # Repeats are idempotent.
      act!(w, w.harsha, w.rc, "item_confirm", w.item)
      assert Repo.get!(Commitment, w.item).item_state == "confirmed"
    end

    test "item_calendar creates exactly one event and posts the added card", w do
      RisiMe.CalendarHelpers.events_on!()
      RisiMe.CalendarHelpers.calendar_device!(w.harsha)
      act!(w, w.harsha, w.rc, "item_calendar", w.item)
      act!(w, w.harsha, w.rc, "item_calendar", w.item)

      assert Repo.aggregate(
               from(e in "risi_events", where: e.source_item_id == type(^w.item, :binary_id)),
               :count
             ) == 1

      out = posts()
      assert post_in(out, w.rc, "event_card")
      assert Repo.get!(Commitment, w.item).item_state == "confirmed"
      assert "calendar" in feedback_actions(w)
    end

    test "after expires_at actions are ignored; the expiry is feedback", w do
      Repo.update_all(from(d in RisiMe.Agent.Discussion, where: d.summary_id == ^w.summary_id),
        set: [created_at: DateTime.add(DateTime.utc_now(), -49 * 3600, :second)]
      )

      act!(w, w.harsha, w.rc, "item_confirm", w.item)
      assert Repo.get!(Commitment, w.item).item_state == "proposed"
      :ok = RisiMe.Agent.Ledger.expire(w.item)
      refute Repo.get(Commitment, w.item)
      assert "expired" in feedback_actions(w)
    end

    test "deleting the note (or its transcript) cancels its items; Official off forgets extras",
         w do
      :ok = ActionItems.cancel_notes(w.og, [w.note])
      refute Repo.get(Commitment, w.item)
      {_, _, upd} = post_in(posts(), w.rc, "item_update")
      assert upd["state"] == "cancelled"
    end

    test "My promises: kind, source_ref, ?source=", w do
      act!(w, w.harsha, w.rc, "item_confirm", w.item)
      rest = fn src -> RisiMe.Agent.Rest.commitments(w.harsha.id, :open, sources: src) end
      {:ok, [c]} = rest.(["call", "voice_note"])
      c = c |> Jason.encode!() |> Jason.decode!()
      ex = hd(example("risi_commitments_reply_v142.json")["commitments"])
      assert keys(c) == keys(ex)
      assert %{"kind" => "promise", "source" => "voice_note"} = c
      assert c["source_ref"]["message_id"] == w.note
      assert {:ok, []} = rest.(["chat"])
      assert {:ok, ["call", "voice_note"]} = RisiMe.Agent.Rest.parse_sources("call,voice_note")
      assert :error = RisiMe.Agent.Rest.parse_sources("nope")

      u = RisiMe.Fixtures.logged_in_user()

      assert build_conn()
             |> put_req_header("authorization", "Bearer " <> u.token)
             |> get("/api/v1/risi/commitments?source=nope")
             |> json_response(400)
    end
  end

  describe "v1.43 §38.9 ask, never guess" do
    test "an unowned task: Risi asks; any member claims it, the first wins; nothing tracked yet",
         w do
      ledger_devices!([w.kamal])
      rc_k = own_risi_chat!(w.kamal)

      llm!(fn body ->
        [
          it(body, "promise", "Send the quotation", "Harsha", "I'll send the quotation",
            due: "2099-10-13T17:00"
          ),
          it(body, "task", "Book the van", nil, "someone books the van",
            due: "2099-10-16",
            to: ["Harsha"]
          )
        ]
      end)

      id =
        voice!(w, w.harsha, [
          %{"o" => 0, "t" => "I'll send the quotation"},
          %{"o" => 5200, "t" => "someone books the van"}
        ])

      :ok = run_voice!(w, id)
      out = posts()
      {_, body, card} = post_in(out, w.og, "action_items")
      ex = example("envelope_risi_action_items_v143.json")
      assert keys(card) == keys(ex["risi"])
      assert body =~ ~s(Who's taking "Book the van"? Tap "It's mine" on the card.)
      task = Enum.find(card["items"], &(&1["kind"] == "task"))
      ex_task = Enum.find(ex["risi"]["items"], &(&1["claim"] == true))
      assert keys(task) == keys(ex_task)
      assert %{"owner" => nil, "state" => nil, "claim" => true} = task
      promise = Enum.find(card["items"], &(&1["kind"] == "promise"))
      refute Map.has_key?(promise, "claim")
      item = task["item_id"]
      refute Repo.get(Commitment, item)

      # Not from someone else's Risi chat; not from a non-member.
      act!(w, w.kamal, w.rc, "item_claim", item)
      refute Repo.get(Commitment, item)
      outsider = RisiMe.GroupHelpers.fast_user!("Out")
      ledger_devices!([outsider])
      act!(w, outsider, own_risi_chat!(outsider), "item_claim", item)
      refute Repo.get(Commitment, item)

      act!(w, w.kamal, rc_k, "item_claim", item)
      c = Repo.get!(Commitment, item)
      assert c.owner_id == w.kamal.id and c.item_state == "proposed" and c.kind == "task"
      assert c.all_day and c.due
      assert c.counterpart_ids == [w.harsha.id]

      out = posts()
      {_, cbody, confirm} = post_in(out, rc_k, "action_confirm")
      assert confirm["for"] == [w.kamal.id]
      assert [%{"item_id" => ^item, "state" => "proposed", "owner" => owner}] = confirm["items"]
      assert owner == w.kamal.id
      assert cbody =~ "From Harsha's voice note"

      # Every member hears it: the state and the owner.
      for rc <- [w.rc, rc_k] do
        {_, _, upd} = post_in(out, rc, "item_update")
        assert %{"state" => "proposed", "owner" => o, "by" => o, "item_id" => ^item} = upd
        assert o == w.kamal.id
        assert keys(upd) == keys(example("envelope_risi_item_update_v143.json")["risi"])
      end

      # The first claim wins.
      act!(w, w.harsha, w.rc, "item_claim", item)
      assert Repo.get!(Commitment, item).owner_id == w.kamal.id
      assert posts() == []

      # Nothing is tracked until the claimer confirms.
      act!(w, w.kamal, rc_k, "item_confirm", item)
      assert Repo.get!(Commitment, item).item_state == "confirmed"
    end

    test "a claim after 48 h is ignored", w do
      llm!(fn body -> [it(body, "task", "Book the van", nil, "book the van")] end)
      id = voice!(w, w.harsha, [%{"o" => 0, "t" => "book the van"}])
      :ok = run_voice!(w, id)
      {_, _, card} = post_in(posts(), w.og, "action_items")
      [%{"item_id" => item}] = card["items"]

      Repo.update_all(
        from(d in RisiMe.Agent.Discussion, where: d.summary_id == ^card["summary_id"]),
        set: [created_at: DateTime.add(DateTime.utc_now(), -49 * 3600, :second)]
      )

      act!(w, w.harsha, w.rc, "item_claim", item)
      refute Repo.get(Commitment, item)
    end

    test "an undated item: confirming asks for the date (item_clarify), never a guess", w do
      llm!(fn body ->
        [it(body, "promise", "Send the photos", "Harsha", "I'll send the photos")]
      end)

      id = voice!(w, w.harsha, [%{"o" => 0, "t" => "I'll send the photos"}])
      :ok = run_voice!(w, id)
      {_, _, card} = post_in(posts(), w.og, "action_items")
      [%{"item_id" => item}] = card["items"]

      act!(w, w.harsha, w.rc, "item_confirm", item)
      c = Repo.get!(Commitment, item)
      assert c.item_state == "confirmed" and c.due == nil
      out = posts()
      {_, q, clarify} = post_in(out, w.rc, "item_clarify")
      assert clarify["item_id"] == item and clarify["buttons"] == ["new_date"]
      assert q =~ ~s(When is "Send the photos" due?)

      # [Remind me] with no date: no reminder, and no second question.
      act!(w, w.harsha, w.rc, "item_remind", item)
      refute post_in(posts(), w.rc, "item_clarify")

      assert all_enqueued(
               worker: Job,
               args: %{"kind" => "item_reminder", "commitment_id" => item}
             ) ==
               []

      # [Add to calendar] without a time creates nothing.
      act!(w, w.harsha, w.rc, "item_calendar", item)

      assert Repo.aggregate(
               from(e in "risi_events", where: e.source_item_id == type(^item, :binary_id)),
               :count
             ) == 0
    end

    test "an all-day item: item_calendar adds one all-day event on that date", w do
      RisiMe.CalendarHelpers.events_on!()
      RisiMe.CalendarHelpers.calendar_device!(w.harsha)

      llm!(fn body ->
        [it(body, "promise", "Send the report", "Harsha", "by Friday", due: "2099-10-16")]
      end)

      id = voice!(w, w.harsha, [%{"o" => 0, "t" => "by Friday"}])
      :ok = run_voice!(w, id)
      out = posts()
      {_, _, confirm} = post_in(out, w.rc, "action_confirm")
      [%{"item_id" => item, "all_day" => true, "buttons" => buttons}] = confirm["items"]
      assert "add_calendar" in buttons and "remind" in buttons

      act!(w, w.harsha, w.rc, "item_calendar", item)
      act!(w, w.harsha, w.rc, "item_calendar", item)

      [e] =
        Repo.all(
          from(e in "risi_events",
            where: e.source_item_id == type(^item, :binary_id),
            select: %{all_day: e.all_day, start_at: e.start_at, end_at: e.end_at}
          )
        )

      assert e.all_day
      assert NaiveDateTime.diff(e.end_at, e.start_at) == 86_400
      assert Repo.get!(Commitment, item).item_state == "confirmed"
      refute post_in(posts(), w.rc, "item_clarify")
    end

    test "a member who wasn't a recipient gets the state only (no text, no due)", w do
      ledger_devices!([w.kamal])
      rc_k = own_risi_chat!(w.kamal)

      llm!(fn body ->
        [
          it(body, "promise", "Send the quotation", "Harsha", "I'll send it",
            due: "2099-10-13T17:00"
          )
        ]
      end)

      id = voice!(w, w.harsha, [%{"o" => 0, "t" => "I'll send it"}])
      :ok = run_voice!(w, id)
      {_, _, card} = post_in(posts(), w.og, "action_items")
      [%{"item_id" => item}] = card["items"]

      act!(w, w.harsha, w.rc, "item_edit", item, %{"text" => "Send the secret quote"})
      out = posts()
      {_, mine_body, mine} = post_in(out, w.rc, "item_update")
      assert mine["text"] == "Send the secret quote" and mine_body =~ "secret"
      {_, other_body, other} = post_in(out, rc_k, "item_update")
      assert %{"state" => "edited", "text" => nil, "due" => nil, "all_day" => nil} = other
      assert other["owner"] == w.harsha.id
      assert other_body == "Harsha confirmed an item from Harsha's voice note."
      refute inspect(other) =~ "secret"
    end
  end

  test "retention: extras live 30 days; Official off removes them", w do
    llm!(fn body -> [it(body, "decision", "Use the local supplier", nil, "local supplier")] end)
    id = voice!(w, w.harsha, [%{"o" => 0, "t" => "local supplier"}])
    :ok = run_voice!(w, id)
    {_, _, card} = post_in(posts(), w.og, "action_items")
    assert [_] = ActionItems.extras(card["summary_id"])
    :ok = ActionItems.prune(DateTime.add(DateTime.utc_now(), 29 * 86_400, :second))
    assert [_] = ActionItems.extras(card["summary_id"])
    :ok = ActionItems.prune(DateTime.add(DateTime.utc_now(), 31 * 86_400, :second))
    assert [] = ActionItems.extras(card["summary_id"])
  end

  test "the dev preview: the card only, nothing posted or stored, members only", w do
    llm!(fn body -> [it(body, "promise", "Send it", "Harsha", "I'll send it")] end)
    id = voice!(w, w.harsha, [%{"o" => 0, "t" => "I'll send it"}])
    assert {:ok, %{body: body, risi: card}} = ActionItems.Dev.preview_note(w.og, id, w.harsha.id)
    assert body =~ "Risi found 1 item" and card["kind"] == "action_items"
    refute inspect(card) =~ "I'll send it"
    assert posts() == []
    assert Repo.aggregate(Commitment, :count) == 0
    assert Secretary.envelope(w.og, id)

    outsider = RisiMe.GroupHelpers.fast_user!("Out")
    assert {:error, :not_member} = ActionItems.Dev.preview_note(w.og, id, outsider.id)

    assert {:error, :not_member} =
             ActionItems.Dev.preview_call(w.og, Ecto.UUID.generate(), outsider.id)

    assert {:error, :no_session} =
             ActionItems.Dev.preview_call(w.og, Ecto.UUID.generate(), w.harsha.id)
  end

  test "/auth/config risi_action_items" do
    ex = example("auth_config_v142.json")
    body = build_conn() |> get("/api/v1/auth/config") |> json_response(200)
    assert body["risi_action_items"] == ex["risi_action_items"]
    Application.put_env(:risime, :risi_action_items, false)

    refute Map.has_key?(
             build_conn() |> get("/api/v1/auth/config") |> json_response(200),
             "risi_action_items"
           )
  end
end
