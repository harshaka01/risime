defmodule RisiMe.Agent.RisiNotesTest do
  @moduledoc """
  v1.29 §30 Risi Notes: the triggers (quiet, call end, `@Risi summarise` without a period), the
  posting threshold, the note model and its sealed storage, delivery (`note_card` per notes
  recipient, `notes_saved` in Official, `discussion_summary` only to people without notes, never
  both), meetings → proposed Risi Calendar events with `note_id`, ticking ↔ promises
  (`done`, `item_reopen`, `item_update`), Official off, retention, and the canary (no note text in
  logs, inbox, job args). Fake `risi-l1`, Risi's clock fixed.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.LedgerHelpers
  import RisiMe.GroupHelpers, only: [events: 2]

  alias RisiMe.Agent.{Commitment, Discussion, Ledger, Notes}
  alias RisiMe.Agent.Calendar.Event
  alias RisiMe.Agent.Notes.{Note, Recipient}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  @note_keys ~w(note_id conversation_id chat_id source call_id media duration_s started_at
                ended_at with topic language key_points items events created_at call_ref made_by)
  @card_keys @note_keys ++ ~w(kind for expires_at notify v)
  @saved_keys ~w(kind note_id source with summary items_count events_count made_by notify v
                 call_ref)
  @caps ~w(groups member_devices tabs risi_tools risi_skills risi_ledger risi_events)

  setup do
    ledger_on!()
    restore_on_exit([:risi_notes])
    Application.put_env(:risime, :risi_notes, true)
    RisiMe.CalendarHelpers.events_on!()
    RisiMe.TabsHelpers.risi_tools_on!()
    skills_on!()

    t0 = stable_t0()
    t0 = %{t0 | microsecond: {0, 6}}
    clock!(t0)

    RisiMe.MLSHelpers.with_attestation_key(%{})
    harsha = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
    shenika = RisiMe.Fixtures.logged_in_user(display_name: "Shenika")
    kamal = RisiMe.Fixtures.logged_in_user(display_name: "Kamal")
    for u <- [harsha, shenika, kamal], do: tz!(u.user, "Asia/Colombo")

    hd = RisiMe.TabsHelpers.tabs_device!(harsha.user, caps: @caps ++ ["risi_notes"])
    sd = RisiMe.TabsHelpers.tabs_device!(shenika.user, caps: @caps ++ ["risi_notes"])
    # Kamal's app has the ledger but not notes.
    kd = RisiMe.TabsHelpers.tabs_device!(kamal.user, caps: @caps)

    og = risi_chat!([harsha.user, shenika.user, kamal.user])
    rc_h = own_risi_chat!(harsha.user)
    rc_s = own_risi_chat!(shenika.user)
    rc_k = own_risi_chat!(kamal.user)

    %{
      harsha: harsha,
      shenika: shenika,
      kamal: kamal,
      h: harsha.user.id,
      s: shenika.user.id,
      k: kamal.user.id,
      hd: hd,
      sd: sd,
      kd: kd,
      og: og,
      rc_h: rc_h,
      rc_s: rc_s,
      rc_k: rc_k,
      t0: t0
    }
  end

  ## Helpers

  defp tz!(u, tz), do: u |> Ecto.Changeset.change(tz: tz) |> Repo.update!()

  defp posts(acc \\ []) do
    receive do
      {:risi_post, conv, body, risi} -> posts([{conv, body, risi} | acc])
    after
      50 -> Enum.reverse(acc)
    end
  end

  defp in_conv(ps, conv), do: for({^conv, body, risi} <- ps, do: {body, risi})
  defp kinds(ps), do: Enum.map(ps, fn {_, _, r} -> r["kind"] end)

  defp keys(m), do: m |> Map.keys() |> Enum.sort()

  # A fake model answering the note-shaped extraction with `fun.(body)` merged in.
  defp note_llm!(fun) do
    fake_llm!(fn
      "discussion_summary", body ->
        Map.merge(
          %{
            "key_points" => ["The interview needs a room.", "Shenika leads it."],
            "summary" => "Interview planning.",
            "items" => [],
            "topic" => "interview planning",
            "language" => "en",
            "meetings" => []
          },
          fun.(body)
        )

      "summary", _body ->
        %{
          "summary" => "Legacy summary.",
          "decisions" => [],
          "action_items" => [],
          "open_questions" => []
        }

      _, _ ->
        %{"commitments" => []}
    end)
  end

  defp meeting(body, opts \\ []) do
    %{
      "title" => Keyword.get(opts, :title, "Interview"),
      "start_local" => Keyword.get(opts, :start, "2026-12-02T14:00"),
      "end_local" => nil,
      "proposed_by" => ref_of(body, "Shenika"),
      "with" => [ref_of(body, "Harsha"), ref_of(body, "Shenika")],
      "source" => [],
      "confidence" => Keyword.get(opts, :confidence, 0.9)
    }
  end

  defp quote_item(body),
    do:
      item(body, "Harsha", "Send the revised quote",
        to: ["Shenika"],
        due: "2026-12-01",
        due_text: "by 1 Dec"
      )

  defp discuss!(ctx, fun, texts \\ nil) do
    note_llm!(fun)
    {_, last} = talk!(ctx.og, [ctx.harsha.user, ctx.shenika.user], ctx.t0, 6, texts)
    clock!(DateTime.add(last, 600))
    assert :ok = quiet!(ctx.og)
    posts()
  end

  defp act!(ctx, who, action, target, edit \\ nil) do
    {user, dev, rc} =
      case who do
        :h -> {ctx.harsha.user, ctx.hd, ctx.rc_h}
        :s -> {ctx.shenika.user, ctx.sd, ctx.rc_s}
      end

    id =
      envelope!(
        rc,
        user,
        %{
          "v" => 1,
          "type" => "risi_action",
          "target" => target,
          "action" => action,
          "edit" => edit
        },
        dev
      )

    [job] = for j <- all_enqueued(worker: Job), j.args["message_id"] == id, do: j
    assert :ok = perform_job(Job, job.args)
  end

  defp api(ctx, who, method, path, body \\ nil) do
    {token, dev} =
      case who do
        :h -> {ctx.harsha.token, ctx.hd}
        :s -> {ctx.shenika.token, ctx.sd}
        :k -> {ctx.kamal.token, ctx.kd}
      end

    RisiMe.GroupHelpers.api(method, "/api/v1/risi" <> path, token, body, dev)
  end

  defp request!(ctx, scope \\ nil) do
    rid = Ecto.UUID.generate()
    env = %{"v" => 1, "type" => "risi_request", "request_id" => rid, "action" => "summarise"}
    env = if scope, do: Map.put(env, "scope", scope), else: env
    envelope!(ctx.og, ctx.harsha.user, env, ctx.hd)
    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    assert :ok = perform_job(Job, job.args)
    rid
  end

  ## Quiet trigger, delivery, model, storage

  test "quiet → a note_card in both notes users' Risi chats, notes_saved in Official, no
        discussion_summary/card; the meeting is a proposed event with note_id; sealed row",
       ctx do
    out =
      discuss!(ctx, fn body ->
        %{
          "items" => [quote_item(body)],
          "meetings" => [meeting(body)]
        }
      end)

    refute "discussion_summary" in kinds(out)
    refute "discussion_card" in kinds(out)

    [{body_h, h}] = for {b, %{"kind" => "note_card"} = r} <- in_conv(out, ctx.rc_h), do: {b, r}
    [{_body_s, s}] = for {b, %{"kind" => "note_card"} = r} <- in_conv(out, ctx.rc_s), do: {b, r}
    assert in_conv(out, ctx.rc_k) == []

    for {card, me} <- [{h, ctx.h}, {s, ctx.s}] do
      assert keys(card) == Enum.sort(@card_keys)
      assert card["for"] == me and card["notify"] == [me]
      assert card["source"] == "chat" and card["conversation_id"] == ctx.og
      assert Enum.sort(card["with"]) == Enum.sort([ctx.h, ctx.s])
      assert card["topic"] == "interview planning" and card["language"] == "en"
      assert card["key_points"] == ["The interview needs a room.", "Shenika leads it."]
      assert [%{"text" => "Send the revised quote", "state" => "proposed"} = i] = card["items"]
      assert i["owner"] == ctx.h and i["counterpart"] == [ctx.s] and i["all_day"]
      assert [ev] = card["events"]
      assert keys(ev) == ~w(all_day end event_id my_status start title)
      assert ev["title"] == "Interview" and ev["my_status"] == "proposed"
      assert ev["start"] == "2026-12-02T08:30:00.000Z" and ev["end"] == "2026-12-02T09:30:00.000Z"
      assert card["made_by"]["model"] == "risi-l1"
    end

    assert h["note_id"] == s["note_id"]

    [header | _] = lines = String.split(body_h, "\n")
    assert header =~ ~r/^Notes: Harsha × Shenika · interview planning · \w{3} \d{1,2} \w{3}$/
    assert "• The interview needs a room." in lines
    assert "Agreed:" in lines and "• You: Send the revised quote (by 1 Dec)" in lines
    assert "Meetings:" in lines
    assert Enum.any?(lines, &String.starts_with?(&1, "• Interview · "))
    assert List.last(lines) == "Open in RisiMe"

    # Official: exactly one notes_saved, no items/owners/dues.
    [{saved_body, saved}] = in_conv(out, ctx.og)
    assert saved["kind"] == "notes_saved" and keys(saved) == Enum.sort(@saved_keys)
    assert saved_body == "Notes saved · open in your Risi chat"
    assert saved["note_id"] == h["note_id"] and saved["notify"] == []
    assert saved["items_count"] == 1 and saved["events_count"] == 1
    assert saved["summary"] == "Interview planning."

    # The meeting: one proposed Risi-made event, invites to both, linked to the note.
    [e] =
      Repo.all(
        from e in Event, where: e.source_note_id == ^h["note_id"] and is_nil(e.source_item_id)
      )

    assert e.created_by == "risi" and e.owner == ctx.s

    invites = for {_, _, %{"kind" => "calendar_invite"} = r} <- out, do: r
    assert Enum.any?(invites, &(&1["note_id"] == h["note_id"] and &1["notify"] == [ctx.h]))
    assert Enum.any?(invites, &(&1["note_id"] == h["note_id"] and &1["notify"] == [ctx.s]))

    # Sealed at rest; the discussion row exists too (same id); recipients are the notes users.
    note = Repo.get!(Note, h["note_id"])
    refute note.body_sealed =~ "interview planning"
    assert Repo.get(Discussion, h["note_id"])

    assert Enum.sort(Repo.all(from r in Recipient, select: r.user_id)) ==
             Enum.sort([ctx.h, ctx.s])

    refute Repo.query!("SELECT row_to_json(t)::text FROM risi_notes t").rows
           |> List.flatten()
           |> Enum.any?(&(&1 =~ "interview"))

    # The item's own (timed-day) event: an all-day item makes none; the note has one event.
    {200, %{"note" => full}} = api(ctx, :h, :get, "/notes/" <> h["note_id"])
    assert keys(full) == Enum.sort(@note_keys)
    assert length(full["events"]) == 1
  end

  test "mixed audience: a recipient without risi_notes gets the discussion_summary (same id),
        notes users the note_card, Official only notes_saved",
       ctx do
    out =
      discuss!(ctx, fn body ->
        %{
          "items" => [
            quote_item(body),
            item(body, "Kamal", "Book the room", to: ["Shenika"], due_text: "tomorrow")
          ]
        }
      end)

    [k] = for {_, %{"kind" => "discussion_summary"} = r} <- in_conv(out, ctx.rc_k), do: r
    [h] = for {_, %{"kind" => "note_card"} = r} <- in_conv(out, ctx.rc_h), do: r
    assert k["summary_id"] == h["note_id"]
    assert length(k["items"]) == 2 and length(h["items"]) == 2

    refute "discussion_summary" in kinds(
             for p = {c, _, _} <- out, c in [ctx.rc_h, ctx.rc_s], do: p
           )

    refute "note_card" in kinds(for p = {c, _, _} <- out, c == ctx.rc_k, do: p)
    assert [{_, %{"kind" => "notes_saved"}}] = in_conv(out, ctx.og)
  end

  test "threshold: nothing for 2 key points and no item/meeting; 3 key points make a note; a
        meeting alone makes a note; the cutoff moves either way",
       ctx do
    assert discuss!(ctx, fn _ -> %{} end) == []
    assert Repo.aggregate(Note, :count) == 0

    # The window was covered: the same talk is not summarised again.
    assert :ok = quiet!(ctx.og)
    assert posts() == []

    ctx = %{ctx | t0: DateTime.add(ctx.t0, 3600)}

    out =
      discuss!(ctx, fn _ ->
        %{"key_points" => ["One.", "Two.", "Three."]}
      end)

    assert [_] = for({_, _, %{"kind" => "note_card"} = r} <- out, do: r) |> Enum.take(1)
    [{_, saved}] = in_conv(out, ctx.og)
    assert saved["items_count"] == 0 and saved["events_count"] == 0

    ctx = %{ctx | t0: DateTime.add(ctx.t0, 3600)}

    out =
      discuss!(ctx, fn body -> %{"meetings" => [meeting(body, start: "2026-12-03T10:00")]} end)

    assert Enum.count(out, fn {_, _, r} -> r["kind"] == "note_card" end) == 2
    assert Repo.aggregate(Note, :count) == 2
  end

  test "switch off: v1.28 exactly (discussion_summary + discussion_card, no topic asked)", ctx do
    Application.put_env(:risime, :risi_notes, false)
    rec = discussion_llm!(fn body -> %{"items" => [quote_item(body)]} end)
    {_, last} = talk!(ctx.og, [ctx.harsha.user, ctx.shenika.user], ctx.t0)
    clock!(DateTime.add(last, 600))
    assert :ok = quiet!(ctx.og)
    out = posts()

    assert "discussion_summary" in kinds(out) and "discussion_card" in kinds(out)
    refute "note_card" in kinds(out) or "notes_saved" in kinds(out)
    assert Repo.aggregate(Note, :count) == 0

    [req] = Agent.get(rec, & &1)
    refute Map.has_key?(req["response_format"]["json_schema"]["schema"]["properties"], "topic")

    # /auth/config: absent while off, "on" while on.
    {200, cfg} = RisiMe.GroupHelpers.api(:get, "/api/v1/auth/config", ctx.harsha.token)
    refute Map.has_key?(cfg, "risi_notes")
    assert {503, _} = api(ctx, :h, :get, "/notes")
    Application.put_env(:risime, :risi_notes, true)
    {200, cfg} = RisiMe.GroupHelpers.api(:get, "/api/v1/auth/config", ctx.harsha.token)
    assert cfg["risi_notes"] == "on"
  end

  test "a low-confidence or past meeting is not an event; a meeting at an item's time is left
        to the item",
       ctx do
    out =
      discuss!(ctx, fn body ->
        %{
          "key_points" => ["A.", "B.", "C."],
          "meetings" => [
            meeting(body, confidence: 0.5),
            meeting(body, start: "2020-01-01T10:00", title: "Old")
          ]
        }
      end)

    [h] = for {_, %{"kind" => "note_card"} = r} <- in_conv(out, ctx.rc_h), do: r
    assert h["events"] == []
    assert Repo.aggregate(Event, :count) == 0
  end

  test "a notes recipient without an active Risi chat: the note_card is held, then posted", ctx do
    nimal = RisiMe.Fixtures.logged_in_user(display_name: "Nimal").user
    RisiMe.TabsHelpers.tabs_device!(nimal, caps: @caps ++ ["risi_notes"])
    og = risi_chat!([ctx.harsha.user, nimal])
    note_llm!(fn body -> %{"items" => [item(body, "Nimal", "Book the room", to: ["Harsha"])]} end)
    {_, last} = talk!(og, [ctx.harsha.user, nimal], ctx.t0)
    clock!(DateTime.add(last, 600))
    assert :ok = quiet!(og)
    out = posts()

    [h] = for {c, _, %{"kind" => "note_card"} = r} <- out, c == ctx.rc_h, do: r
    refute Enum.any?(out, fn {_, _, r} -> r["kind"] == "note_card" and r["for"] == nimal.id end)
    assert Repo.aggregate(from(p in "risi_followups_pending"), :count) == 1

    rc_n = own_risi_chat!(nimal)
    assert :ok = RisiMe.Agent.LedgerOut.deliver_pending(nimal.id)
    assert [{^rc_n, body, n}] = posts()
    assert n["kind"] == "note_card" and n["for"] == nimal.id and n["note_id"] == h["note_id"]
    assert body =~ "Notes: Nimal × Harsha"
  end

  ## Ticking ↔ promises

  test "confirm → My promises (note_id); tick (done) ↔ reopen keep the note and My promises in
        step; item_update to both; reopen only within 7 days",
       ctx do
    out = discuss!(ctx, fn body -> %{"items" => [quote_item(body)]} end)
    [h] = for {_, %{"kind" => "note_card"} = r} <- in_conv(out, ctx.rc_h), do: r
    [%{"item_id" => item_id}] = h["items"]

    act!(ctx, :h, "item_confirm", item_id)
    posts()

    {200, %{"commitments" => [c]}} = api(ctx, :h, :get, "/commitments")
    assert c["note_id"] == h["note_id"] and c["state"] == "confirmed"

    # Kamal's (non-notes) device: no note_id key at all (v1.28 exactly).
    {200, %{"commitments" => kc}} = api(ctx, :k, :get, "/commitments")
    refute Enum.any?(kc, &Map.has_key?(&1, "note_id"))

    # Shenika (counterpart) ticks it in the note.
    act!(ctx, :s, "done", item_id)
    ups = for {c, _, %{"kind" => "item_update"} = r} <- posts(), do: {c, r}
    assert Enum.sort(Enum.map(ups, &elem(&1, 0))) == Enum.sort([ctx.rc_h, ctx.rc_s])

    assert Enum.all?(ups, fn {_, r} ->
             r["state"] == "done" and r["summary_id"] == h["note_id"]
           end)

    {200, %{"note" => n}} = api(ctx, :h, :get, "/notes/" <> h["note_id"])
    assert [%{"state" => "done"}] = n["items"]

    # Harsha reopens it in My promises: back to confirmed everywhere.
    act!(ctx, :h, "item_reopen", item_id)
    ups = for {_, b, %{"kind" => "item_update"} = r} <- posts(), do: {b, r}
    assert length(ups) == 2
    assert Enum.all?(ups, fn {b, r} -> r["state"] == "confirmed" and b =~ "Harsha reopened" end)
    assert Repo.get!(Commitment, item_id).item_state == "confirmed"
    {200, %{"note" => n}} = api(ctx, :s, :get, "/notes/" <> h["note_id"])
    assert [%{"state" => "confirmed"}] = n["items"]

    # A second reopen is ignored (not done); done again, then 8 days later: no reopen.
    act!(ctx, :h, "item_reopen", item_id)
    assert posts() == []
    act!(ctx, :h, "done", item_id)
    posts()
    clock!(DateTime.add(RisiMe.Agent.Clock.now(), 8 * 86_400))
    act!(ctx, :s, "item_reopen", item_id)
    assert posts() == []
    assert Repo.get!(Commitment, item_id).item_state == "done"
  end

  ## @Risi summarise and call end

  test "@Risi summarise without a period → a note (source request) instead of the summary; a
        repeat within 10 min re-posts notes_saved to the asker only; a period is unchanged",
       ctx do
    note_llm!(fn body -> %{"items" => [quote_item(body)]} end)
    # Two messages only: the quiet rule wouldn't fire, the request does.
    say!(ctx.og, ctx.shenika.user, "Can you send the quote?", ctx.t0)
    say!(ctx.og, ctx.harsha.user, "Yes, by 1 Dec.", DateTime.add(ctx.t0, 30))
    clock!(DateTime.add(ctx.t0, 120))

    request!(ctx)
    out = posts()
    refute Enum.any?(out, fn {_, _, r} -> r["kind"] == "summary" end)
    [{_, saved}] = in_conv(out, ctx.og)
    assert saved["kind"] == "notes_saved" and saved["source"] == "request"
    [h] = for {_, %{"kind" => "note_card"} = r} <- in_conv(out, ctx.rc_h), do: r
    assert h["source"] == "request" and h["note_id"] == saved["note_id"]

    clock!(DateTime.add(ctx.t0, 300))
    request!(ctx)
    assert [{c, _, again}] = posts()
    assert c == ctx.og and again["kind"] == "notes_saved"
    assert again["note_id"] == saved["note_id"] and again["notify"] == [ctx.h]
    assert Repo.aggregate(Note, :count) == 1

    # A period scope: §27.13 as before (no new note).
    clock!(DateTime.add(ctx.t0, 900))
    request!(ctx, "today")
    out = posts()
    refute Enum.any?(out, fn {_, _, r} -> r["kind"] in ~w(note_card notes_saved) end)
    assert Enum.any?(out, fn {_, _, r} -> r["kind"] == "summary" end)
    assert Repo.aggregate(Note, :count) == 1
  end

  test "@Risi summarise with nothing note-worthy answers with the §24.11 summary", ctx do
    note_llm!(fn _ -> %{} end)
    say!(ctx.og, ctx.shenika.user, "hello", ctx.t0)
    clock!(DateTime.add(ctx.t0, 60))
    request!(ctx)
    out = posts()
    assert [{_, %{"kind" => "summary"}}] = in_conv(out, ctx.og)
    assert Repo.aggregate(Note, :count) == 0
  end

  test "call end: a listened call → a note with source call; another call's end runs the quiet
        check at once",
       ctx do
    note_llm!(fn body -> %{"items" => [quote_item(body)]} end)

    transcript =
      for {u, i} <- Enum.with_index([ctx.harsha.user, ctx.shenika.user]) do
        %{
          message_id: RisiMe.TimeUUID.at(DateTime.add(ctx.t0, i * 10)),
          sender_id: u.id,
          text: "spoken line #{i}"
        }
      end

    clock!(DateTime.add(ctx.t0, 60))

    assert :ok =
             Ledger.call_ended(ctx.og, %{
               call_id: Ecto.UUID.generate(),
               media: "audio",
               duration_s: 300,
               transcript: transcript
             })

    out = posts()
    [h] = for {_, %{"kind" => "note_card"} = r} <- in_conv(out, ctx.rc_h), do: r
    assert h["source"] == "call" and h["media"] == "audio" and h["duration_s"] == 300
    assert [{_, %{"kind" => "notes_saved", "source" => "call"}}] = in_conv(out, ctx.og)

    # Typed talk, a call ends 1 min later: no 10-min wait. (Spacing: 31 min after the first.)
    note_llm!(fn _ -> %{"key_points" => ["One.", "Two.", "Three."]} end)
    t1 = DateTime.add(ctx.t0, 3600)
    {_, last} = talk!(ctx.og, [ctx.harsha.user, ctx.shenika.user], t1)
    clock!(DateTime.add(last, 60))
    assert :ok = Ledger.call_ended(ctx.og, %{call_id: Ecto.UUID.generate()})

    assert Enum.any?(posts(), fn {_, _, r} ->
             r["kind"] == "notes_saved" and r["source"] == "chat"
           end)
  end

  ## REST

  test "notes REST: list newest first, search (key point, item, name), paging, get, delete
        (list only; items stay), delete all, purge when nobody keeps it",
       ctx do
    out1 = discuss!(ctx, fn body -> %{"items" => [quote_item(body)]} end)
    [n1] = for {_, %{"kind" => "note_card"} = r} <- in_conv(out1, ctx.rc_h), do: r
    ctx2 = %{ctx | t0: DateTime.add(ctx.t0, 3600)}

    out2 =
      discuss!(ctx2, fn _ ->
        %{"key_points" => ["Budget is tight.", "Hire later.", "Zebra plan."], "topic" => "budget"}
      end)

    [n2] = for {_, %{"kind" => "note_card"} = r} <- in_conv(out2, ctx.rc_h), do: r

    {200, %{"notes" => [a, b], "has_more" => false}} = api(ctx, :h, :get, "/notes")
    assert a["note_id"] == n2["note_id"] and b["note_id"] == n1["note_id"]

    assert keys(a) ==
             ~w(conversation_id ended_at events_count items_count note_id open_items_count source topic with)

    assert b["items_count"] == 1 and b["open_items_count"] == 1 and a["items_count"] == 0

    assert {200, %{"notes" => [%{"note_id" => id2}]}} = api(ctx, :h, :get, "/notes?q=ZEBRA")
    assert id2 == n2["note_id"]

    assert {200, %{"notes" => [%{"note_id" => id1}]}} =
             api(ctx, :h, :get, "/notes?q=revised%20quote")

    assert id1 == n1["note_id"]
    assert {200, %{"notes" => [_, _]}} = api(ctx, :h, :get, "/notes?q=shenika")
    assert {200, %{"notes" => []}} = api(ctx, :h, :get, "/notes?q=nothing-like-this")

    assert {200, %{"notes" => [p1], "has_more" => true}} = api(ctx, :h, :get, "/notes?limit=1")
    assert p1["note_id"] == n2["note_id"]

    assert {200, %{"notes" => [p2], "has_more" => false}} =
             api(ctx, :h, :get, "/notes?limit=1&before=" <> n2["note_id"])

    assert p2["note_id"] == n1["note_id"]

    assert {422, _} = api(ctx, :h, :get, "/notes?limit=51")
    assert {422, _} = api(ctx, :h, :get, "/notes?q=" <> String.duplicate("a", 101))
    assert {422, _} = api(ctx, :h, :get, "/notes?before=nope")

    # Kamal never received them; a device without risi_notes is refused.
    {k_token, kd} =
      {ctx.kamal.token,
       RisiMe.TabsHelpers.tabs_device!(ctx.kamal.user, caps: @caps ++ ["risi_notes"])}

    assert {404, _} =
             RisiMe.GroupHelpers.api(
               :get,
               "/api/v1/risi/notes/" <> n1["note_id"],
               k_token,
               nil,
               kd
             )

    assert {403, _} = api(ctx, :k, :get, "/notes")

    # Harsha deletes note 1: gone from his list; Shenika still has it; the item stays.
    [%{"item_id" => item_id}] = n1["items"]
    assert {204, nil} = api(ctx, :h, :delete, "/notes/" <> n1["note_id"])
    assert {404, _} = api(ctx, :h, :get, "/notes/" <> n1["note_id"])
    assert {404, _} = api(ctx, :h, :delete, "/notes/" <> n1["note_id"])
    assert {200, %{"notes" => [_]}} = api(ctx, :h, :get, "/notes")
    assert {200, _} = api(ctx, :s, :get, "/notes/" <> n1["note_id"])
    assert Repo.get(Commitment, item_id)

    # Shenika deletes all: note 1 is kept by nobody → purged; note 2 still Harsha's.
    assert {204, nil} = api(ctx, :s, :delete, "/notes")
    refute Repo.get(Note, n1["note_id"])
    assert Repo.get(Note, n2["note_id"])
    assert Repo.get(Commitment, item_id)
    assert {200, %{"notes" => []}} = api(ctx, :s, :get, "/notes")
  end

  test "notes REST without RISI_DATA_KEY: 503 when the caller has notes", ctx do
    discuss!(ctx, fn body -> %{"items" => [quote_item(body)]} end)
    Application.delete_env(:risime, :risi_data_key)
    assert {503, %{"error" => %{"code" => "agent_unavailable"}}} = api(ctx, :h, :get, "/notes")
  end

  ## Deletion, retention, Private, capability, storage, canary

  test "Official off deletes the chat's notes on the server", ctx do
    discuss!(ctx, fn body -> %{"items" => [quote_item(body)]} end)
    assert Repo.aggregate(Note, :count) == 1
    RisiMe.Agent.Secretary.forget(ctx.og)
    assert Repo.aggregate(Note, :count) == 0
    assert Repo.aggregate(Recipient, :count) == 0
  end

  test "retention: notes older than 365 days are purged by the hourly prune", ctx do
    discuss!(ctx, fn body -> %{"items" => [quote_item(body)]} end)
    [n] = Repo.all(Note)
    :ok = Notes.prune(DateTime.add(n.created_at, 364 * 86_400, :second))
    assert Repo.get(Note, n.note_id)
    :ok = Notes.prune(DateTime.add(n.created_at, 366 * 86_400, :second))
    refute Repo.get(Note, n.note_id)
  end

  test "never from Private or a Risi chat: a summarise there makes no note", ctx do
    note_llm!(fn body -> %{"items" => [quote_item(body)]} end)
    say!(ctx.rc_h, ctx.harsha.user, "Send the revised quote by 1 Dec", ctx.t0)
    say!(ctx.rc_h, ctx.harsha.user, "and book the room", DateTime.add(ctx.t0, 10))
    clock!(DateTime.add(ctx.t0, 60))
    assert :no_note = Ledger.request_note(ctx.rc_h, ctx.h)
    assert :ok = Ledger.call_ended(ctx.rc_h, %{call_id: nil})

    private = "grp:" <> Ecto.UUID.generate()
    assert :ok = Ledger.call_ended(private, %{call_id: nil})
    refute RisiMe.Agent.official?(private)
    assert Repo.aggregate(Note, :count) == 0
  end

  test "capability: risi_notes is kept only with risi_events", ctx do
    caps = ~w(groups member_devices tabs risi_tools risi_ledger risi_notes)
    d = RisiMe.TabsHelpers.tabs_device!(ctx.kamal.user, caps: caps)
    refute "risi_notes" in RisiMe.Devices.get(ctx.k, d).capabilities
    refute Notes.user?(ctx.k)
    assert Notes.user?(ctx.h)
  end

  test "migration: no plaintext text column in risi_notes; CHECKs present" do
    cols =
      Repo.query!(
        "SELECT column_name FROM information_schema.columns WHERE table_name = 'risi_notes'"
      ).rows
      |> List.flatten()

    for bad <- ~w(topic key_points text title summary body language), do: refute(bad in cols)
    assert "body_sealed" in cols

    checks =
      Repo.query!(
        "SELECT conname FROM pg_constraint WHERE conrelid = 'risi_notes'::regclass AND contype = 'c'"
      ).rows
      |> List.flatten()

    assert "risi_notes_source" in checks and "risi_notes_body_sealed" in checks

    assert_raise Postgrex.Error, fn ->
      Repo.query!(
        "INSERT INTO risi_notes (note_id, conversation_id, chat_id, source, started_at, ended_at, body_sealed, created_at) VALUES (gen_random_uuid(), 'grp:x', 'x', 'chat', now(), now(), 'short', now())"
      )
    end
  end

  test "canary: note text never reaches the log, inbox events or job args", ctx do
    canary = "CANARY-#{Ecto.UUID.generate()}"

    log =
      ExUnit.CaptureLog.capture_log([level: :debug], fn ->
        discuss!(ctx, fn body ->
          %{
            "topic" => canary,
            "key_points" => [canary, canary <> "2", canary <> "3"],
            "items" => [item(body, "Harsha", canary, to: ["Shenika"], due: "2026-12-01")],
            "meetings" => [meeting(body, title: canary)]
          }
        end)

        {200, _} = api(ctx, :h, :get, "/notes?q=" <> canary)
      end)

    refute log =~ "CANARY"
    refute Jason.encode!(events(ctx.h, nil) ++ events(ctx.s, nil)) =~ "CANARY"
    refute Jason.encode!(Enum.map(all_enqueued(worker: Job), & &1.args)) =~ "CANARY"

    for t <- ~w(risi_notes risi_note_recipients risi_discussions risi_events) do
      refute Repo.query!("SELECT row_to_json(t)::text FROM #{t} t").rows
             |> List.flatten()
             |> Enum.any?(&(&1 =~ "CANARY"))
    end
  end
end
