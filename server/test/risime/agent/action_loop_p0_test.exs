defmodule RisiMe.Agent.ActionLoopP0Test do
  @moduledoc """
  P0 2026-10-09 (Harsha, real phone, nightly.43): the Risi action loop. Replays Harsha's
  conversation in his 1:1 Risi chat ("add my interview with Shenika on Monday 12 Oct at 2pm to
  my calendar") with a scripted fake model and a fixed clock (Fri 9 Oct 2026, 10:30 Colombo):
  the action card within 2 turns, [Add] → `calendar_add` on the phone → "Added to your Google
  Calendar: Interview with Shenika · Mon 12 Oct, 2–3 PM"; a 5-turn follow-up keeps context;
  the loop guard; chip-like next_steps filtered; the ledger prefill; need_skill instead of a
  text loop; [Edit]; digest == My promises.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.GroupHelpers, only: [api: 5, events: 2]

  alias RisiMe.Agent.{ActionDraft, Commitment, LedgerReminders, TimePhrase, Turn, TurnSteps}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  # Fri 9 Oct 2026, 10:30 in Colombo (+05:30).
  @now ~U[2026-10-09 05:00:00Z]

  setup do
    restore_on_exit([:risi_now])
    Application.put_env(:risime, :risi_now, @now)
    RisiMe.MLSHelpers.with_attestation_key(%{})
    harsha = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
    shenika = RisiMe.Fixtures.logged_in_user(display_name: "Shenika")
    og = risi_chat!([harsha.user, shenika.user])
    RisiMe.TabsHelpers.risi_tools_on!()
    skills_on!()
    dev = skills_device!(harsha.user)
    rc = own_risi_chat!(harsha.user)
    ctx = %{harsha: harsha, shenika: shenika, og: og, dev: dev, rc: rc}
    set!(ctx, "calendar", "ask", "granted")
    ctx
  end

  ## Helpers

  defp set!(ctx, id, state, perm) do
    change = %{"id" => id, "state" => state, "client_permission" => perm}

    {200, _} =
      api(:patch, "/api/v1/risi/skills", ctx.harsha.token, %{"changes" => [change]}, ctx.dev)
  end

  # The fake model: `script` maps the asker's question to the action of each step (a list, the
  # step index = the assistant messages so far), or a function of the request body.
  defp model!(script) do
    fake_llm!(fn _name, body ->
      q = question(body)

      case Map.fetch(script, q) do
        {:ok, f} when is_function(f, 1) ->
          f.(body)

        {:ok, actions} when is_list(actions) ->
          n = Enum.count(body["messages"], &(&1["role"] == "assistant"))
          Enum.at(actions, min(n, length(actions) - 1))

        {:ok, action} ->
          action

        :error ->
          final("I'm not sure what you mean.")
      end
    end)
  end

  defp question(body) do
    text = Enum.find(body["messages"], &(&1["role"] == "user"))["content"]
    [_, line] = Regex.run(~r/<question>\n(.*)\n<\/question>/, text)
    Jason.decode!(line)["q"]
  end

  defp user_text(body), do: Enum.find(body["messages"], &(&1["role"] == "user"))["content"]

  defp final(answer, next \\ [], draft \\ nil) do
    f = %{"tool" => "final", "answer" => answer, "sources" => [], "next_steps" => next}
    if draft, do: Map.put(f, "draft", draft), else: f
  end

  defp ask!(ctx, text, conv \\ nil) do
    rid = Ecto.UUID.generate()

    envelope!(
      conv || ctx.rc,
      ctx.harsha.user,
      %{
        "v" => 1,
        "type" => "risi_request",
        "request_id" => rid,
        "action" => "ask",
        "text" => text
      },
      ctx.dev
    )

    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    assert :ok = perform_job(Job, job.args)
    rid
  end

  defp action_args!(ctx, action, target, edit \\ nil) do
    id =
      envelope!(
        ctx.rc,
        ctx.harsha.user,
        %{
          "v" => 1,
          "type" => "risi_action",
          "target" => target,
          "action" => action,
          "edit" => edit
        },
        ctx.dev
      )

    [job] = for j <- all_enqueued(worker: Job), j.args["message_id"] == id, do: j
    job.args
  end

  defp posts(acc \\ []) do
    receive do
      {:risi_post, conv, body, risi} -> posts([{conv, body, risi} | acc])
    after
      50 -> Enum.reverse(acc)
    end
  end

  defp kinds(ps), do: Enum.map(ps, fn {_, _, r} -> r["kind"] end)
  defp card(ps), do: Enum.find_value(ps, fn {_, _, r} -> if r["kind"] == "confirm", do: r end)
  defp answer(ps), do: Enum.find(ps, fn {_, _, r} -> r["kind"] == "answer" end)

  defp wait_call(user_id, n \\ 1, tries \\ 150) do
    case events(user_id, "risi_tool_call") do
      calls when length(calls) >= n ->
        Enum.at(calls, n - 1)

      _ when tries > 0 ->
        Process.sleep(20)
        wait_call(user_id, n, tries - 1)

      _ ->
        flunk("no risi_tool_call event")
    end
  end

  defp result!(ctx, id, body),
    do: api(:post, "/api/v1/risi/tool_calls/#{id}/result", ctx.harsha.token, body, ctx.dev)

  # 14:00 Colombo on Mon 12 Oct = 08:30Z.
  @interview %{
    "title" => "Interview with Shenika",
    "start" => "2026-10-12T08:30:00.000Z",
    "end" => "2026-10-12T09:30:00.000Z",
    "all_day" => false
  }

  ## Harsha's conversation

  test "replay: the card in turn 1, [Add] adds it on the phone, Google Calendar success", ctx do
    model!(%{
      "add my interview with Shenika on Monday 12 Oct at 2pm to my calendar" =>
        final(
          "Sure! Your interview with Shenika is on Monday 12 October at 2:00 PM. Shall I add it to your calendar?",
          ["Confirm to add the event", "Yes, add it", "What else is on Monday?"],
          %{
            "kind" => "event",
            "title" => "Interview with Shenika",
            "date" => "Monday 12 Oct",
            "time" => "2pm"
          }
        )
    })

    rid = ask!(ctx, "add my interview with Shenika on Monday 12 Oct at 2pm to my calendar")
    ps = posts()
    assert kinds(ps) == ["confirm", "answer"]
    assert Enum.all?(ps, fn {conv, _, _} -> conv == ctx.rc end)

    c = card(ps)
    assert c["tool"] == "calendar_add" and c["request_id"] == rid
    assert c["args"] == @interview
    assert c["when"] == Map.take(@interview, ~w(start end all_day))
    assert c["text"] == "Interview with Shenika" and c["buttons"] == ["add", "cancel"]
    # No calendar reported yet: the phone asks on first use.
    assert Map.has_key?(c, "calendar") and c["calendar"] == nil

    {_, body, a} = answer(ps)
    assert body == "Tap Add on the card to put it in your calendar, or Cancel."
    assert a["next_steps"] == [] and a["steps"] == [%{"tool" => "calendar_add", "status" => "ok"}]
    refute body =~ "?"

    # Every step in risi_turn_steps (no text): the server-built card step, then the final.
    assert [%{tool: "calendar_add", status: "ok", authorize: "ok"}, %{tool: "final"}] =
             TurnSteps.list(c["turn_ref"])

    assert %{write_id: wid} = ActionDraft.get(ctx.harsha.user.id, ctx.rc)
    assert wid == c["write_id"]

    # [Add] → calendar_add on the phone, no model turn.
    n_calls = length(llm_requests())
    args = action_args!(ctx, "confirm_write", c["write_id"])
    task = Task.async(fn -> perform_job(Job, args) end)
    d = wait_call(ctx.harsha.user.id)["data"]
    assert d["tool"] == "calendar_add" and d["to_devices"] == [ctx.dev]
    assert d["args"] == Map.put(@interview, "write_id", c["write_id"])

    cal = %{"name" => "Google Calendar", "account" => "harsha@example.com"}

    {204, _} =
      result!(ctx, d["tool_call_id"], %{
        "status" => "ok",
        "result" => %{"event_id" => "e1", "verified" => true, "calendar" => cal}
      })

    assert :ok = Task.await(task)
    assert length(llm_requests()) == n_calls

    assert [
             {rc, "Added to your Google Calendar: Interview with Shenika · Mon 12 Oct, 2–3 PM",
              done}
           ] =
             posts()

    assert rc == ctx.rc and done["kind"] == "skill_done" and done["action"] == "calendar_added"
    assert done["undo"]["kind"] == "client" and is_binary(done["undo_token"])

    # The draft is finished; the calendar is remembered for the next card.
    assert ActionDraft.get(ctx.harsha.user.id, ctx.rc) == nil

    model!(%{
      "dentist Friday 16 Oct 10am" =>
        final("Dentist on Friday.", [], %{
          "kind" => "event",
          "title" => "Dentist",
          "date" => "Friday 16 Oct",
          "time" => "10am"
        })
    })

    ask!(ctx, "dentist Friday 16 Oct 10am")
    assert card(posts())["calendar"] == cal
  end

  test "the chip tap and a follow-up see the history and the pending draft", ctx do
    model!(%{
      "add my interview with Shenika on Monday 12 Oct at 2pm to my calendar" =>
        final("Interview with Shenika, Mon 12 Oct 2pm.", ["Confirm to add the event"], %{
          "kind" => "event",
          "title" => "Interview with Shenika",
          "date" => "Monday 12 Oct",
          "time" => "2pm"
        }),
      "Confirm to add the event" => final("Shall I add it now?", ["Yes"])
    })

    ask!(ctx, "add my interview with Shenika on Monday 12 Oct at 2pm to my calendar")
    c = card(posts())
    ask!(ctx, "Confirm to add the event")
    [_, req] = llm_requests()
    text = user_text(req)
    assert text =~ "<history>"
    assert text =~ "add my interview with Shenika on Monday 12 Oct at 2pm to my calendar"
    assert text =~ "(action card) Add \\\"Interview with Shenika\\\""
    assert text =~ "<draft>" and text =~ "a card for it was already shown"

    # No second card, no "shall I?", no chips that are confirms.
    ps = posts()
    assert kinds(ps) == ["answer"]
    {_, body, a} = answer(ps)
    assert body == "Tap Add on the card to confirm."
    assert a["next_steps"] == []
    assert ActionDraft.get(ctx.harsha.user.id, ctx.rc).write_id == c["write_id"]
  end

  test "a 5-turn follow-up keeps context and patches the draft", ctx do
    model!(%{
      "add my interview with Shenika to my calendar" =>
        final("Sure. When is it?", [], %{"kind" => "event", "title" => "Interview with Shenika"}),
      "Monday 12 Oct" =>
        final("Got it. What time does it start?", [], %{
          "kind" => "event",
          "date" => "Monday 12 Oct"
        }),
      "2pm, job interview, 1 hour" =>
        final("OK.", [], %{"kind" => "event", "time" => "2pm", "duration_min" => 60}),
      "make it 3pm" => final("Moved to 3pm.", [], %{"kind" => "event", "time" => "3pm"}),
      "thanks" => final("You're welcome!", ["Summarise today"])
    })

    ask!(ctx, "add my interview with Shenika to my calendar")
    ps = posts()
    assert kinds(ps) == ["answer"]
    assert {_, "Sure. When is it?", _} = answer(ps)

    ask!(ctx, "Monday 12 Oct")
    assert {_, "Got it. What time does it start?", _} = answer(posts())
    req = List.last(llm_requests())
    assert user_text(req) =~ ~s("title":"Interview with Shenika")
    assert user_text(req) =~ "add my interview with Shenika to my calendar"

    ask!(ctx, "2pm, job interview, 1 hour")
    c1 = card(posts())
    assert c1["args"] == @interview

    ask!(ctx, "make it 3pm")
    c2 = card(posts())

    assert c2["args"] == %{
             @interview
             | "start" => "2026-10-12T09:30:00.000Z",
               "end" => "2026-10-12T10:30:00.000Z"
           }

    ask!(ctx, "thanks")
    ps = posts()
    assert kinds(ps) == ["answer"]
    {_, "You're welcome!", a} = answer(ps)
    assert a["next_steps"] == ["Summarise today"]

    # The fifth turn still sees the first.
    last = List.last(llm_requests())
    assert user_text(last) =~ "add my interview with Shenika to my calendar"
    assert user_text(last) =~ "make it 3pm"
  end

  test "the loop guard: after two turns without progress, the prefilled card", ctx do
    model!(%{
      "add interview with Shenika on Monday 12 Oct" =>
        final("What time does it start?", [], %{
          "kind" => "event",
          "title" => "Interview with Shenika",
          "date" => "Monday 12 Oct"
        }),
      "hmm" => final("What time does the interview start?"),
      "I said it already" => final("Sorry, what time is it?")
    })

    ask!(ctx, "add interview with Shenika on Monday 12 Oct")
    assert {_, "What time does it start?", _} = answer(posts())
    ask!(ctx, "hmm")
    assert {_, "What time does the interview start?", _} = answer(posts())
    ask!(ctx, "I said it already")
    c = card(posts())

    # Everything known: the title and the day; no time → an all-day event to edit or cancel.
    assert c["args"]["title"] == "Interview with Shenika" and c["args"]["all_day"] == true
    assert c["when"]["start"] == "2026-10-11T18:30:00.000Z"
  end

  test "a question about something already given is dropped and the card shown", ctx do
    model!(%{
      "add my interview with Shenika on Monday 12 Oct at 2pm to my calendar" =>
        final("What time zone are you in, and when does it end?", [], %{
          "kind" => "event",
          "title" => "Interview with Shenika",
          "date" => "2026-10-12",
          "time" => "14:00"
        })
    })

    ask!(ctx, "add my interview with Shenika on Monday 12 Oct at 2pm to my calendar")
    ps = posts()
    assert card(ps)["args"] == @interview
    {_, body, _} = answer(ps)
    refute body =~ "?"
  end

  test "chip-like next_steps are filtered (server-side)", ctx do
    model!(%{
      "what's on today" =>
        final(
          "Nothing planned.",
          ["Confirm to add the event", "Yes, add it", "What time works?"]
        )
    })

    ask!(ctx, "what's on today")
    {_, _, a} = answer(posts())
    assert a["next_steps"] == []

    assert Turn.clean_next_steps([
             "Confirm to add the event",
             "Yes please",
             "OK",
             "Add it",
             "Should I add it?",
             "What time works",
             "Summarise today",
             "Remind me tomorrow 9am",
             "Check my calendar on Monday"
           ]) == ["Summarise today", "Remind me tomorrow 9am", "Check my calendar on Monday"]
  end

  test "use what Risi knows: the ledger item prefills the card", ctx do
    {:ok, c} =
      Commitment.seal(%Commitment{
        id: Ecto.UUID.generate(),
        conversation_id: ctx.og,
        chat_id: ctx.og,
        state: "confirmed",
        item_state: "confirmed",
        text: "Shenika: interview with Harsha",
        owner_id: ctx.shenika.user.id,
        counterpart_ids: [ctx.harsha.user.id],
        due: ~U[2026-10-12 08:30:00.000000Z],
        due_kind: "datetime",
        all_day: false,
        source: "chat",
        proposed_at: ~U[2026-10-09 05:00:00.000000Z]
      })

    Repo.insert!(c)

    model!(%{
      "add my interview with Shenika to my calendar" => fn body ->
        assert user_text(body) =~ "<promises>"
        assert user_text(body) =~ ~s("ref":"k1","text":"Shenika: interview with Harsha")
        assert user_text(body) =~ "Mon 12 Oct, 14:00"

        final("Found it in your promises.", [], %{
          "kind" => "event",
          "title" => "Interview with Shenika",
          "item" => "k1"
        })
      end
    })

    ask!(ctx, "add my interview with Shenika to my calendar")
    assert card(posts())["args"] == @interview
  end

  test "the skill off or the permission missing: skill_needed, never a text loop", ctx do
    set!(ctx, "calendar", "off", "granted")

    model!(%{
      "add my interview with Shenika on Monday 12 Oct at 2pm to my calendar" =>
        final("I'll add it.", [], %{
          "kind" => "event",
          "title" => "Interview with Shenika",
          "date" => "Monday 12 Oct",
          "time" => "2pm"
        })
    })

    ask!(ctx, "add my interview with Shenika on Monday 12 Oct at 2pm to my calendar")
    [{rc, body, n}] = posts()
    assert rc == ctx.rc and n["kind"] == "skill_needed" and n["skill_id"] == "calendar"
    assert body =~ "Turn it on in Settings → Risi skills"
    assert [%{tool: "need_skill", status: "denied"}] = TurnSteps.list(n["turn_ref"])

    # Turned on: the waiting draft becomes the card on the next ask.
    set!(ctx, "calendar", "ask", "granted")
    model!(%{"try again" => final("Trying again.", [], %{"kind" => "event"})})
    ask!(ctx, "try again")
    assert card(posts())["args"] == @interview
  end

  test "[Edit]: confirm_write with the edited args runs them", ctx do
    model!(%{
      "add my interview with Shenika on Monday 12 Oct at 2pm to my calendar" =>
        final("OK.", [], %{
          "kind" => "event",
          "title" => "Interview with Shenika",
          "date" => "Monday 12 Oct",
          "time" => "2pm"
        })
    })

    ask!(ctx, "add my interview with Shenika on Monday 12 Oct at 2pm to my calendar")
    c = card(posts())

    # A malformed edit runs nothing.
    bad = action_args!(ctx, "confirm_write", c["write_id"], %{"start" => "tomorrow"})
    assert :ok = perform_job(Job, bad)
    assert events(ctx.harsha.user.id, "risi_tool_call") == []

    edit = %{"start" => "2026-10-12T09:00:00.000Z", "end" => "2026-10-12T10:30:00.000Z"}
    args = action_args!(ctx, "confirm_write", c["write_id"], edit)
    task = Task.async(fn -> perform_job(Job, args) end)
    d = wait_call(ctx.harsha.user.id)["data"]
    assert d["args"]["start"] == edit["start"] and d["args"]["end"] == edit["end"]

    {204, _} =
      result!(ctx, d["tool_call_id"], %{
        "status" => "ok",
        "result" => %{"event_id" => "e", "verified" => true}
      })

    assert :ok = Task.await(task)

    assert [{_, "Added to your calendar: Interview with Shenika · Mon 12 Oct, 2:30–4 PM", _}] =
             posts()
  end

  test "Risi's own posts join the Risi chat's sealed buffer, never a group's", ctx do
    model!(%{"hello" => final("Hi Harsha!")})
    ask!(ctx, "hello")
    posts()

    rows = RisiMe.Agent.Transcript.list(ctx.rc)
    risi = RisiMe.Risi.user_id()
    assert [row] = Enum.filter(rows, &(&1.sender_id == risi))

    assert %{"type" => "risi_post", "kind" => "answer", "body" => "Hi Harsha!"} =
             Jason.decode!(row.plaintext)

    # Never a counted `text` message.
    assert RisiMe.Agent.Secretary.text_messages(ctx.rc) == []

    ask!(ctx, "hello", ctx.og)
    posts()
    refute Enum.any?(RisiMe.Agent.Transcript.list(ctx.og), &(&1.sender_id == risi))
  end

  test "digest == My promises: the same open items, the same count", ctx do
    h = ctx.harsha.user.id
    s = ctx.shenika.user.id

    insert = fn attrs ->
      {:ok, c} =
        Commitment.seal(
          struct!(
            %Commitment{
              id: Ecto.UUID.generate(),
              conversation_id: ctx.og,
              chat_id: ctx.og,
              owner_id: h,
              counterpart_ids: [s],
              source_message_ids: [],
              proposed_at: ~U[2026-10-09 05:00:00.000000Z]
            },
            attrs
          )
        )

      Repo.insert!(c)
    end

    # Open: a legacy v1.24 card, a ledger item due in 3 weeks, an edited one with no due.
    insert.(
      state: "confirmed",
      text: "Send the quote",
      due: ~U[2026-10-10 11:30:00.000000Z],
      due_kind: "datetime"
    )

    insert.(
      state: "confirmed",
      item_state: "confirmed",
      text: "Book the hall",
      due: ~U[2026-10-30 11:30:00.000000Z],
      due_kind: "datetime"
    )

    insert.(
      state: "edited",
      item_state: "edited",
      text: "Call the bank",
      owner_id: s,
      counterpart_ids: [h]
    )

    # Not open: proposed, done.
    insert.(state: "proposed", item_state: "proposed", text: "Maybe later")
    insert.(state: "done", item_state: "done", text: "Paid the invoice")

    {200, %{"commitments" => mine}} =
      api(:get, "/api/v1/risi/commitments", ctx.harsha.token, nil, ctx.dev)

    assert length(mine) == 3
    assert length(LedgerReminders.digest_items(h)) == 3

    # 09:00 Colombo on Sat 10 Oct.
    assert LedgerReminders.digest(h, ~U[2026-10-10 03:30:00Z]) == :sent
    [{rc, body, d}] = posts()
    assert rc == ctx.rc and length(d["items"]) == length(mine)

    assert Enum.sort(Enum.map(d["items"], & &1["commitment_id"])) ==
             Enum.sort(Enum.map(mine, & &1["commitment_id"]))

    assert body =~ "send the quote" and body =~ "book the hall" and body =~ "call the bank"
  end

  test "time phrases with a day of the month", _ctx do
    tz = "Asia/Colombo"

    for {p, want} <- [
          {"Monday 12 Oct 2pm", ~U[2026-10-12 08:30:00Z]},
          {"Monday 12 Oct at 2pm", ~U[2026-10-12 08:30:00Z]},
          {"12 October 14:00", ~U[2026-10-12 08:30:00Z]},
          {"Oct 12th, 2:30pm", ~U[2026-10-12 09:00:00Z]},
          {"on the 12th of october 2026 at 2pm", ~U[2026-10-12 08:30:00Z]},
          {"2 Jan 9am", ~U[2027-01-02 03:30:00Z]}
        ] do
      assert {:ok, got, :datetime} = TimePhrase.resolve(p, @now, tz), p
      assert DateTime.compare(got, want) == :eq, p
    end

    assert {:ok, d, :date} = TimePhrase.resolve("Monday 12 Oct", @now, tz)
    assert DateTime.compare(d, ~U[2026-10-11 18:30:00Z]) == :eq
    assert {:error, _} = TimePhrase.resolve("31 Feb 2pm", @now, tz)
    # A bare hour is still ambiguous.
    assert {:error, :ambiguous} = TimePhrase.resolve("Monday at 6", @now, tz)
  end
end
