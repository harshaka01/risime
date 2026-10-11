defmodule RisiMe.Agent.Step4Test do
  @moduledoc """
  v1.36 §35.4 (Risi Brain v2 step 4), fixed clock (Fri 9 Oct 2026, Colombo), fake model,
  `RISI_LEDGER` and `RISI_NOTES` **off** (all of this works without them):

    1. the 07:00 daily brief: today's Risi Calendar events, promises due/overdue, waiting on
       others, deadlines from memory, yesterday's decisions; once a day; nothing → no post;
       the user's language; the 09:00 ledger digest is skipped that day;
    2. a missed deadline asks the owner for the status (and tells the counterparts);
    3. a meeting cancelled in the chat cancels the Risi Calendar event (read back), notifies the
       participants and offers "Reschedule?" with slots free for everyone;
    4. a meeting moved in the chat moves the event (read back); no single match → no change.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.CalendarHelpers

  alias RisiMe.Agent.{Brief, Calendar, Commitment, Commitments, Memory, MemoryIngest, Propagation}

  @moduletag capture_log: true

  # 07:10 in Colombo on Fri 9 Oct 2026.
  @morning ~U[2026-10-09 01:40:00Z]

  setup do
    ctx = calendar_world!()
    Application.put_env(:risime, :risi_ledger, false)
    restore_on_exit([:risi_brief])

    for u <- [ctx.harsha, ctx.shenika],
        do: u.user |> Ecto.Changeset.change(tz: "Asia/Colombo") |> Repo.update!()

    ctx
  end

  ## Helpers

  defp event!(ctx, owner, title, start, stop, with \\ [], conv \\ nil) do
    {:ok, view} =
      Calendar.create(owner, %{
        title: title,
        notes: nil,
        tz: "Asia/Colombo",
        start: RisiMe.Agent.Clock.usec(start),
        stop: RisiMe.Agent.Clock.usec(stop),
        all_day: false,
        with: with,
        drop: true,
        reminder_min: :default,
        source: %{conversation_id: conv || ctx.og, message_ids: [], item_id: nil, note_id: nil},
        created_by: "risi"
      })

    view["event_id"]
  end

  defp promise!(ctx, owner, counterparts, text, due) do
    {:ok, c} =
      Commitment.seal(%Commitment{
        id: Ecto.UUID.generate(),
        conversation_id: ctx.og,
        chat_id: ctx.og,
        state: "confirmed",
        text: text,
        owner_id: owner,
        counterpart_ids: counterparts,
        due: due,
        due_kind: "datetime",
        all_day: false,
        source_message_ids: [],
        proposed_at: ~U[2026-10-01 05:00:00.000000Z]
      })

    Repo.insert!(c)
  end

  defp briefs(ps),
    do: for({c, b, %{"kind" => "digest", "scope" => "brief"} = r} <- ps, do: {c, b, r})

  ## 1. The brief

  test "the 07:00 brief lists the day from the calendar, promises and memory, once a day", ctx do
    Application.put_env(:risime, :risi_now, @morning)

    event!(ctx, ctx.h, "Site visit", ~U[2026-10-09 05:30:00Z], ~U[2026-10-09 06:30:00Z])
    promise!(ctx, ctx.h, [ctx.s], "Send the quote", ~U[2026-10-08 06:00:00.000000Z])
    promise!(ctx, ctx.h, [ctx.s], "Call the client", ~U[2026-10-09 10:30:00.000000Z])
    promise!(ctx, ctx.s, [ctx.h], "Share the drawings", ~U[2026-10-10 06:00:00.000000Z])

    Application.put_env(:risime, :risi_now, ~U[2026-10-08 06:00:00Z])
    m = say!(ctx.og, ctx.shenika.user, "We go with the blue panels")

    {:ok, _} =
      Memory.put_item(ctx.og, %{
        kind: "decision",
        text: "Blue panels chosen",
        source_message_ids: [m]
      })

    Application.put_env(:risime, :risi_now, @morning)

    {:ok, _} =
      Memory.put_item(ctx.og, %{
        kind: "deadline",
        text: "Permit form due today",
        people_ids: [ctx.h],
        at: ~U[2026-10-09 12:00:00Z],
        source_message_ids: [m]
      })

    posts()
    Brief.sweep()

    all = briefs(posts())
    # Shenika gets her own (she owes the drawings, and the decision is in her chat too).
    assert Enum.sort(Enum.map(all, &elem(&1, 0))) == Enum.sort([ctx.hrc, ctx.src])
    [{_, body, r}] = for {c, _, _} = b <- all, c == ctx.hrc, do: b

    assert body ==
             Enum.join(
               [
                 "Good morning. Your day, Fri 9 Oct",
                 "Today:\n• 11:00–12:00 · Site visit, tentative",
                 "Due today:\n• Call the client (Fri 9 Oct, 16:00)",
                 "Overdue:\n• Send the quote (Thu 8 Oct, 11:30)",
                 "Waiting on others:\n• Shenika: Share the drawings (Sat 10 Oct, 11:30)",
                 "From your chats:\n• Permit form due today",
                 "Yesterday's decisions:\n• Blue panels chosen",
                 "Checked: Risi Calendar · Risi's items · your promises · my memory of your Official chats (not your phone calendar)."
               ],
               "\n\n"
             )

    assert r["date"] == "2026-10-09" and r["notify"] == [ctx.h] and r["language"] == "en"
    assert length(r["items"]) == 3
    assert [%{"title" => "Site visit"}] = r["events"]
    assert [%{"text" => "Blue panels chosen"}] = r["decisions"]

    # Once a day; and not at 08:10.
    Brief.sweep()
    assert briefs(posts()) == []
    assert Brief.sent_today?(ctx.h)
    assert Brief.brief(ctx.s, ~U[2026-10-09 02:40:00Z]) == :skipped
  end

  test "nothing to say → no brief; RISI_BRIEF=off → none at all", ctx do
    Application.put_env(:risime, :risi_now, @morning)
    posts()
    Brief.sweep()
    assert briefs(posts()) == []

    promise!(ctx, ctx.h, [], "Send the quote", ~U[2026-10-08 06:00:00.000000Z])
    Application.put_env(:risime, :risi_brief, false)
    Brief.sweep()
    assert briefs(posts()) == []
  end

  test "the brief's labels are in the user's language", ctx do
    Application.put_env(:risime, :risi_now, @morning)
    promise!(ctx, ctx.h, [], "Send the quote", ~U[2026-10-08 06:00:00.000000Z])

    for i <- 1..3 do
      id = RisiMe.TimeUUID.at(DateTime.add(@morning, -86_400 + i))
      pt = Jason.encode!(%{"v" => 1, "type" => "text", "body" => "හෙට හමුවෙමු #{i}"})
      :ok = Memory.put_message(ctx.og, %{message_id: id, sender_id: ctx.h, plaintext: pt})
    end

    posts()
    Brief.sweep()
    assert [{_, body, r}] = briefs(posts())
    assert r["language"] == "si"
    assert body =~ "සුබ උදෑසනක්" and body =~ "කල් ඉකුත් වූ:\n• Send the quote"
  end

  test "the 09:00 ledger digest is skipped on a day the brief went out", ctx do
    Application.put_env(:risime, :risi_ledger, true)
    Application.put_env(:risime, :risi_now, @morning)
    promise!(ctx, ctx.h, [], "Send the quote", ~U[2026-10-08 06:00:00.000000Z])
    posts()
    Brief.sweep()
    assert [_] = briefs(posts())
    assert RisiMe.Agent.LedgerReminders.digest(ctx.h, ~U[2026-10-09 03:40:00Z]) == :skipped
  end

  ## 2. A missed deadline asks the owner

  test "the first escalation asks the owner for the status in their Risi chat", ctx do
    c = promise!(ctx, ctx.h, [ctx.s], "Send the quote", ~U[2026-10-08 06:00:00.000000Z])
    posts()
    Commitments.escalate(c.id, c.schedule_v, 1)
    ps = posts()

    assert [{rc, body, r}] = for({c2, b, %{"phase" => "overdue"} = r} <- ps, do: {c2, b, r})
    assert rc == ctx.hrc and r["kind"] == "reminder" and r["notify"] == [ctx.h]

    assert body ==
             "\"Send the quote\" was due Thu 8 Oct, 11:30. Is it done? Tap Done on its card in the chat, or agree a new date there."

    assert Enum.any?(ps, fn {c2, _, r} -> c2 == ctx.og and r["kind"] == "escalation" end)

    # The second one only tells the counterparts.
    Commitments.escalate(c.id, c.schedule_v, 2)
    refute Enum.any?(posts(), fn {_, _, r} -> r["phase"] == "overdue" end)
  end

  ## 3–4. Cancellation and change propagation

  defp ingest!(ctx, item) do
    m = say!(ctx.og, ctx.shenika.user, "update about the meeting")
    pt = Jason.encode!(%{"v" => 1, "type" => "text", "body" => "update about the meeting"})
    :ok = Memory.put_message(ctx.og, %{message_id: m, sender_id: ctx.s, plaintext: pt})

    fake_llm!(fn "memory_extract", _ ->
      %{
        "items" => [
          Map.merge(%{"people" => ["Shenika"], "project" => nil, "refs" => ["m1"]}, item)
        ]
      }
    end)

    assert :ok = MemoryIngest.run(ctx.og)
  end

  test "a meeting cancelled in the chat: event cancelled (read back), everyone told, Reschedule? with free slots",
       ctx do
    # Mon 12 Oct 14:00–15:00 Colombo, made from this chat.
    id =
      event!(ctx, ctx.h, "Site visit", ~U[2026-10-12 08:30:00Z], ~U[2026-10-12 09:30:00Z], [ctx.s])

    # Shenika is busy Saturday 09:00–12:00 (her own event).
    event!(ctx, ctx.s, "Busy", ~U[2026-10-10 03:30:00Z], ~U[2026-10-10 06:30:00Z], [], ctx.src)
    posts()

    ingest!(ctx, %{
      "kind" => "meeting",
      "text" => "The site visit on Monday is cancelled",
      "at" => "2026-10-12T14:00",
      "status" => "cancelled",
      "new_at" => nil
    })

    assert {%{state: "cancelled"}, _} = Calendar.load(id)
    ps = posts()

    for rc <- [ctx.hrc, ctx.src] do
      assert [{body, r}] = for({^rc, b, %{"kind" => "answer"} = r} <- ps, do: {b, r})
      # Now is Fri 10:30: the first slot is 2 h on (12:30), Saturday is free only after 12:00.
      assert body ==
               "\"Site visit\" was cancelled in the chat. Reschedule? Free for everyone in Risi Calendar: Fri 9 Oct 12:30, Sat 10 Oct 12:00, Mon 12 Oct 09:00."

      assert [%{"label" => "Fri 9 Oct 12:30", "action" => "ask", "text" => t} | _] =
               r["next_actions"]

      assert t =~ ~r/^Add "Site visit" with \w+ on Fri 9 Oct 12:30$/
      assert r["made_by"]["model"] == nil
    end

    # The participants were told by the event update too.
    assert Enum.any?(ps, fn {_, _, r} ->
             r["kind"] == "event_update" and r["change"] == "cancelled"
           end)
  end

  test "a meeting moved in the chat moves the event (read back); no single match changes nothing",
       ctx do
    id =
      event!(ctx, ctx.h, "Site visit", ~U[2026-10-12 08:30:00Z], ~U[2026-10-12 09:30:00Z], [ctx.s])

    ingest!(ctx, %{
      "kind" => "meeting",
      "text" => "The site visit moves to Tuesday 10am",
      "at" => "2026-10-12T14:00",
      "status" => "moved",
      "new_at" => "2026-10-13T10:00"
    })

    assert {%{state: "active", start_at: s, end_at: e}, _} = Calendar.load(id)
    assert s == ~U[2026-10-13 04:30:00.000000Z] and e == ~U[2026-10-13 05:30:00.000000Z]

    # Two upcoming events, a vague cancellation that matches neither clearly: nothing changes.
    a =
      event!(ctx, ctx.h, "Budget review", ~U[2026-10-14 08:30:00Z], ~U[2026-10-14 09:30:00Z], [
        ctx.s
      ])

    b =
      event!(ctx, ctx.h, "Team lunch", ~U[2026-10-15 08:30:00Z], ~U[2026-10-15 09:30:00Z], [ctx.s])

    assert Propagation.meeting_changed(ctx.og, %{status: "cancelled", text: "that one is off"}) ==
             :none

    for x <- [a, b], do: assert({%{state: "active"}, _} = Calendar.load(x))
  end
end
