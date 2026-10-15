defmodule RisiMe.Agent.CallRisi.Call3Test do
  @moduledoc """
  Harsha's 3rd real Call Risi call (pilot v0.3.0-nightly.10-srv10, 15 Oct 2026 14:13–14:17Z, id
  `harsha-2026-10-15-call-3`), replayed through the real session and brain with a fake media
  worker, fake speech, a fake risi-l1 (whose chat answer is the pilot's false "I have added …") and
  a fake §25 turn that reads a synthetic calendar shaped like his (a Risi Calendar item "Free up
  calendar on 18th Oct whole day" stored as 00:00–01:00, the same block on the phone; "Send SSH
  access for iPhone stuff by 6 PM tomorrow" Fri 18:00–19:00) and makes confirm cards that stay
  pending until the test "taps Add".

  Was: the all-day item as "midnight to 1 am" (twice), a 2 am–10 pm follow-up answered "just ask me
  directly", "Was that for me?" after "That's correct", and "I have added the racing discussion"
  with nothing added (then "but I have added the entry for you").
  """
  use ExUnit.Case, async: false

  alias RisiMe.Agent.CallRisi.{Brain, Fillers, Session, Spoken, WriteTruth}

  defmodule Media do
    @behaviour RisiMe.Agent.CallRisi.Media
    @impl true
    def open(_owner, args), do: {:ok, args.test}
    @impl true
    def command(test, cmd) do
      send(test, {:media_cmd, cmd})
      :ok
    end

    @impl true
    def close(_), do: :ok
  end

  defmodule Speech do
    @behaviour RisiMe.Agent.Speech
    @impl true
    def healthy?, do: true
    @impl true
    def compare(_, _), do: {:error, :failed}
    @impl true
    def transcribe(<<_hdr::binary-size(44), script::binary>>, _opts) do
      [lang, text] = String.split(script, ":", parts: 2)
      {:ok, %{text: text, language: lang, model: "fake-asr", elapsed_ms: 10, speech: text != ""}}
    end
  end

  # risi-l1: a calendar rephrase echoes the rendering; a chat answer is the pilot's false claim.
  defmodule LLM do
    def stream([%{"content" => sys} | _] = messages, _opts, on_delta) do
      if sys =~ ~r/Rephrase the (?:CALENDAR|ITEMS)/ do
        cal =
          messages |> List.last() |> Map.get("content") |> String.split(~r/(?:CALENDAR|ITEMS): /)

        on_delta.("Okay. " <> List.last(cal))
      else
        on_delta.(
          "Got it. I have added the racing discussion to your calendar for tomorrow from 9 to 10. " <>
            "You should see it there."
        )
      end

      {:ok, %{model: "fake-l1", first_token_ms: 40, tokens: 3}}
    end
  end

  defmodule Turn do
    @moduledoc false
    @today ~D[2026-10-15]

    def utc(%NaiveDateTime{} = n),
      do: n |> NaiveDateTime.add(-19_800) |> NaiveDateTime.to_iso8601() |> Kernel.<>("Z")

    defp ev(kind, title, s, mins) do
      e = NaiveDateTime.add(s, mins * 60)

      %{
        kind: kind,
        title: title,
        start: s,
        end: e,
        all_day: false,
        start_utc: utc(s),
        end_utc: utc(e),
        tags: [kind]
      }
    end

    def events do
      [
        ev("busy", nil, ~N[2026-10-16 18:00:00], 60),
        ev(
          "risi_calendar_event",
          "Send SSH access for iPhone stuff by 6 PM tomorrow",
          ~N[2026-10-16 18:00:00],
          60
        ),
        ev(
          "risi_calendar_event",
          "Free up calendar on 18th Oct whole day",
          ~N[2026-10-18 00:00:00],
          60
        ),
        ev("busy", nil, ~N[2026-10-18 00:00:00], 60)
      ]
    end

    def run(_conv, env, rid, _user) do
      test = :persistent_term.get({__MODULE__, :test})
      send(test, {:turn_run, env})
      {pid, _} = Process.get(:risi_voice_sink)
      text = env["text"]

      range =
        case env["__schedule"] do
          %{} = r ->
            r

          _ ->
            case RisiMe.Agent.ScheduleIntent.classify(text, @today) do
              {:schedule_read, r} -> r
              _ -> nil
            end
        end

      if range do
        evs =
          for e <- events(),
              d = NaiveDateTime.to_date(e.start),
              Date.compare(d, range.from) != :lt and Date.compare(d, range.to) == :lt,
              do: e

        unread = :persistent_term.get({__MODULE__, :unread}, [])

        f = %{
          ok?: true,
          today: @today,
          from: range.from,
          to: range.to,
          events: evs,
          unread: unread
        }

        send(pid, {:risi_voice_facts, rid, f})

        send(
          pid,
          {:risi_voice_answer, rid,
           Calendar.strftime(range.from, "%a %-d %b") <> ":\n\nFrom: your phone calendars · Risi"}
        )
      else
        # A write: a confirm card (pending until the test taps Add), then the turn's answer.
        summary =
          if text =~ ~r/racing/i,
            do: "Add to your Risi Calendar: Discuss racing · Fri 16 Oct, 9–10 AM",
            else: "Add to your Risi Calendar: Discuss RISD · Fri 16 Oct, 10 AM–12 PM"

        :persistent_term.put({__MODULE__, :write, rid}, "pending")
        :persistent_term.put({__MODULE__, :last_write}, rid)
        send(pid, {:risi_voice_card, rid, summary})

        send(
          pid,
          {:risi_voice_answer, rid,
           "Tap Add on the card to put it in your Risi Calendar, or Cancel."}
        )
      end

      :ok
    end

    def state(rid) do
      case :persistent_term.get({__MODULE__, :write, rid}, nil) do
        nil -> nil
        s -> %{state: s, confirmed?: s in ~w(done running), tool: "risi_calendar_add"}
      end
    end
  end

  defmodule Runs do
    def record(_row), do: :ok
  end

  setup do
    keys = [
      :risi_call_media,
      :risi_speech,
      :risi_call_llm,
      :risi_call_turn,
      :risi_call_runs,
      :risi_call_write_state,
      :risi_now,
      :risi_agent_flags
    ]

    prev = for k <- keys, do: {k, Application.get_env(:risime, k)}
    Application.put_env(:risime, :risi_call_media, Media)
    Application.put_env(:risime, :risi_speech, Speech)
    Application.put_env(:risime, :risi_call_llm, LLM)
    Application.put_env(:risime, :risi_call_turn, Turn)
    Application.put_env(:risime, :risi_call_write_state, &Turn.state/1)

    Application.put_env(:risime, :risi_agent_flags, %{
      "RISI_CALL_RISI" => "on",
      "RISI_AGENTS" => "on"
    })

    Application.put_env(:risime, :risi_call_runs, Runs)
    # Harsha's call: Thu 15 Oct 2026, 19:43 in Colombo.
    Application.put_env(:risime, :risi_now, "2026-10-15T14:13:00Z")
    :persistent_term.put({Turn, :test}, self())
    :persistent_term.put({Turn, :unread}, [])

    on_exit(fn ->
      for {k, v} <- prev,
          do:
            if(v,
              do: Application.put_env(:risime, k, v),
              else: Application.delete_env(:risime, k)
            )
    end)

    case start_supervised(RisiMe.Agent.CallRisi.Supervisor) do
      {:ok, _} -> :ok
      {:error, {{:already_started, _}, _}} -> :ok
      {:error, {:already_started, _}} -> :ok
    end

    me = self()

    args = %{
      conv: "grp:risi-chat",
      call_id: Ecto.UUID.generate(),
      user_id: Ecto.UUID.generate(),
      device_id: Ecto.UUID.generate(),
      room: "room1",
      risi_token: "tok",
      worker_url: "ws://127.0.0.1:7880",
      test: me,
      tools?: true,
      cal?: false,
      caps: ~w(risi_calendar_check risi_items set_reminder risi_calendar_add),
      post: fn conv, body, risi -> send(me, {:posted, conv, body, risi}) end
    }

    %{args: args}
  end

  defp start!(args) do
    {:ok, pid} = DynamicSupervisor.start_child(RisiMe.Agent.CallRisi.Sessions, {Session, args})
    assert_receive {:media_cmd, %{"cmd" => "join"}}
    ev(pid, %{"ev" => "joined"})
    ev(pid, %{"ev" => "caller_joined"})
    assert_receive {:media_cmd, %{"cmd" => "say", "seq" => 1, "utt" => 0}}
    ev(pid, %{"ev" => "said", "seq" => 1, "utt" => 0, "ms" => 1500, "interrupted" => false})
    ev(pid, %{"ev" => "idle"})
    pid
  end

  defp ev(pid, map), do: send(pid, {:call_risi_media, map})

  defp utter(pid, utt, text),
    do:
      ev(pid, %{
        "ev" => "utterance",
        "utt" => utt,
        "pcm16k" => Base.encode64("en:" <> text),
        "speech_ms" => 900,
        "eot_wait_ms" => 450
      })

  defp says(utt, acc \\ [], wait \\ 10_000) do
    receive do
      {:media_cmd, %{"cmd" => "say", "utt" => ^utt} = c} ->
        acc = acc ++ [c]
        answered? = Enum.any?(acc, &(&1["text"] not in Fillers.all()))
        says(utt, acc, if(answered?, do: 600, else: wait))
    after
      wait -> acc
    end
  end

  defp heard(pid, cmds) do
    for c <- cmds,
        do:
          ev(pid, %{"ev" => "said", "seq" => c["seq"], "utt" => c["utt"], "interrupted" => false})

    ev(pid, %{"ev" => "idle"})
  end

  defp turn(pid, utt, text) do
    utter(pid, utt, text)
    cmds = says(utt)
    heard(pid, cmds)
    Process.sleep(1_700)
    cmds |> Enum.map(& &1["text"]) |> Enum.reject(&(&1 in Fillers.all())) |> Enum.join(" ")
  end

  defp last_run(acc \\ nil) do
    receive do
      {:turn_run, env} -> last_run(env)
    after
      0 -> acc
    end
  end

  @write_claim ~r/\b(?:I(?:'ve|\s+have)\s+added|I\s+can\s+add|it'?s\s+added|you\s+should\s+see)\b/i

  test "Harsha's 3rd call (harsha-2026-10-15-call-3): no false write claim, all day, the window",
       %{args: args} do
    pid = start!(args)

    # 1. The 18th: an all-day item, said once and naturally; never "midnight to 1 am".
    a1 = turn(pid, 1, "Do you have anything on the 18th of October?")
    assert %{"__schedule" => %{from: ~D[2026-10-18], to: ~D[2026-10-19]}} = last_run()
    assert a1 =~ "Sunday the 18th: on your Risi Calendar, free up calendar, all day."
    refute a1 =~ ~r/midnight|1 am|busy/
    refute a1 =~ "18th Oct"

    # 2. A time window of the topic's day is a calendar read of that window (was "ask me directly").
    a2 =
      turn(
        pid,
        2,
        "Yeah, but do I have anything from, say, 2 a.m. until... until maybe like, you know, 10 p.m."
      )

    assert %{"__schedule" => %{from: ~D[2026-10-18], to: ~D[2026-10-19]} = r2} = last_run()
    refute Map.has_key?(r2, :window)
    assert a2 =~ "between 2 am and 10 pm"
    assert a2 =~ "all day"
    refute a2 =~ ~r/ask me directly|check your phone/i

    # 3. The 18th again: the same right answer.
    a3 = turn(pid, 3, "So what's on my calendar on the 18th of October?")
    assert a3 =~ "free up calendar, all day"
    refute a3 =~ "midnight"

    # 4. "The 16th?": tomorrow's item, its title without the date words; the phone block at the
    # same time is the same event (said once).
    a4 = turn(pid, 4, "The 16th?")
    assert %{"__schedule" => %{from: ~D[2026-10-16], to: ~D[2026-10-17]}} = last_run()

    assert a4 =~
             "Tomorrow: on your Risi Calendar, send SSH access for iPhone stuff, from 6 to 7 pm."

    refute a4 =~ ~r/busy|by 6 PM tomorrow/

    # 5. A write: the card is said for what it is, never "added".
    a5 =
      turn(
        pid,
        5,
        "Yeah, just add an entry on my calendar and block it out from 10 to 12. discuss about RISD."
      )

    assert %{"text" => "Yeah, just add an entry" <> _} = last_run()

    assert a5 =~
             "I haven't added it yet. I've put a card in your Risi chat: Discuss RISD, tomorrow"

    assert a5 =~ "Tap Add"
    refute a5 =~ @write_claim

    # 6. The correction goes to the write turn (the card is replaced), never the chat route.
    a6 = turn(pid, 6, "I don't know. From 9 to 10. discuss about racing")

    assert %{
             "text" =>
               "Change the event I just asked you to add: I don't know. From 9 to 10." <> _
           } =
             last_run()

    assert a6 =~ "Discuss racing, tomorrow, 9 to 10 am"
    refute a6 =~ @write_claim

    # 7. "That's correct" is for Risi: the truth, from the card's row.
    a7 = turn(pid, 7, "That's correct")
    refute a7 =~ "Was that for me?"
    assert a7 =~ "I haven't added it yet. Tap Add on the card in your Risi chat"
    assert last_run() == nil

    # 8. "Yeah, that's for you, just add that entry": still the truth (a card needs the tap).
    a8 = turn(pid, 8, "Yeah, yeah, that's for you, just to add that particular entry.")
    refute a8 =~ "Was that for me?"
    assert a8 =~ "I haven't added it yet."
    refute a8 =~ @write_claim

    # 9. "I can't see that you have added something": plainly, the card isn't confirmed.
    a9 = turn(pid, 9, "Rishi, I can't see that you have added something.")
    assert a9 =~ "It isn't added yet: the card in your Risi chat hasn't been confirmed."
    refute a9 =~ @write_claim

    # 10. A chat-route answer that claims a write (the pilot's model) is replaced by the truth.
    a10 = turn(pid, 10, "That's what it is.")
    refute a10 =~ "Was that for me?"
    refute a10 =~ @write_claim
    assert a10 =~ "I haven't added it yet."

    # 11. After the tap, the truth changes.
    rid = :persistent_term.get({Turn, :last_write})
    :persistent_term.put({Turn, :write, rid}, "done")
    a11 = turn(pid, 11, "Did you add it?")
    assert a11 =~ "Yes, it's added: it's in your Risi Calendar."

    ev(pid, %{"ev" => "caller_left"})
    assert_receive {:posted, _, body, _}, 3_000
    [before_tap, _] = String.split(body, "You: Did you add it?")
    refute before_tap =~ @write_claim
    refute body =~ "midnight"
  end

  test "call 4 (§41.9 voice confirm): add by voice, a clear yes confirms like Add, then 'did you add it?'",
       %{args: args} do
    me = self()

    Application.put_env(:risime, :risi_agent_flags, %{
      "RISI_CALL_RISI" => "on",
      "RISI_AGENTS" => "on",
      "RISI_CALL_VOICE_CONFIRM" => "on"
    })

    Application.put_env(:risime, :risi_call_voice_confirm, fn rid, ctx ->
      send(me, {:voice_confirm, rid, ctx.user_id})
      :persistent_term.put({Turn, :write, rid}, "done")
      {:done, "wid"}
    end)

    Application.put_env(:risime, :risi_claim_results, fn rid ->
      case Turn.state(rid) do
        nil -> []
        %{state: s} -> [%{write_id: "wid", state: s, tool: "risi_calendar_add", via: "confirm"}]
      end
    end)

    on_exit(fn ->
      Application.delete_env(:risime, :risi_call_voice_confirm)
      Application.delete_env(:risime, :risi_claim_results)
    end)

    pid = start!(args)

    a1 = turn(pid, 1, "Can you add an appointment tomorrow from 9 to 10, racing discussion")
    assert a1 =~ "I haven't added it yet. I've drafted it: Discuss racing, tomorrow, 9 to 10 am."
    assert a1 =~ "Shall I add it to your Risi Calendar?"
    refute a1 =~ ~r/I(?:'ve| have) added/

    a2 = turn(pid, 2, "yes")
    user = args.user_id
    assert_receive {:voice_confirm, _rid, ^user}
    assert a2 == "Added to your Risi Calendar: Discuss racing, tomorrow, 9 to 10 am."

    a3 = turn(pid, 3, "Did you add it?")
    assert a3 =~ "Yes, it's added: it's in your Risi Calendar."
  end

  test "voice confirm off: a yes never confirms; the card waits for the tap", %{args: args} do
    me = self()

    Application.put_env(:risime, :risi_call_voice_confirm, fn rid, _ctx ->
      send(me, {:voice_confirm, rid})
      {:done, "wid"}
    end)

    on_exit(fn -> Application.delete_env(:risime, :risi_call_voice_confirm) end)
    pid = start!(args)
    a1 = turn(pid, 1, "Can you add an appointment tomorrow from 9 to 10, racing discussion")
    assert a1 =~ "Tap Add"
    a2 = turn(pid, 2, "yes, add it")
    refute_received {:voice_confirm, _}
    assert a2 =~ "I haven't added it yet."
  end

  test "'The 16th?' when the phone calendar didn't answer: said first, never 'nothing'", %{
    args: args
  } do
    :persistent_term.put({Turn, :unread}, [
      "I couldn't reach your phone calendar (it didn't answer in time)."
    ])

    pid = start!(args)
    a1 = turn(pid, 1, "Do you have anything on the 16th?")
    assert a1 =~ ~r/^(?:Okay\. )?I couldn't reach your phone calendar/
    refute a1 =~ ~r/nothing (?:on|scheduled)|free/i
    assert a1 =~ "send SSH access for iPhone stuff"
  end

  describe "WriteTruth" do
    test "claims and promises of a write" do
      for s <- [
            "I have added the racing discussion to your calendar for tomorrow from 9 to 10.",
            "but I have added the entry for you.",
            "You should see the racing discussion on your calendar for tomorrow from 9 to 10.",
            "Okay, I can add that for you.",
            "I've gone ahead and booked it.",
            "It's been saved.",
            "The racing discussion is now in your calendar.",
            "Done, it's set for 9.",
            "I'll schedule that now."
          ],
          do: assert(WriteTruth.claim?(s), s)

      for s <- [
            "Sunday the 18th: on your Risi Calendar, free up calendar, all day.",
            "Tap Add on the card to put it in your Risi Calendar, or Cancel.",
            "I haven't added it yet.",
            "Sure, which day do you mean?",
            "I can check that for you."
          ],
          do: refute(WriteTruth.claim?(s), s)
    end

    test "replies after a write turn" do
      assert WriteTruth.confirmation?("That's correct")

      assert WriteTruth.confirmation?(
               "Yeah, yeah, that's for you, just to add that particular entry."
             )

      assert WriteTruth.confirmation?("yes add it")
      refute WriteTruth.confirmation?("I don't know. From 9 to 10. discuss about racing")
      assert WriteTruth.correction?("I don't know. From 9 to 10. discuss about racing")
      assert WriteTruth.correction?("to el discuss about")
      refute WriteTruth.correction?("That's correct")
      assert WriteTruth.complaint?("Rishi, I can't see that you have added something.")
      assert WriteTruth.complaint?("Did you add it?")
      refute WriteTruth.complaint?("That's correct")
    end

    test "the status of the call's card" do
      w = %{request_id: "r", route: "calendar_write", last?: true}
      st = fn s -> fn _ -> s && %{state: s, confirmed?: false, tool: "risi_calendar_add"} end end
      assert WriteTruth.status_line(w, :confirm, st.("pending")) =~ "I haven't added it yet."
      assert WriteTruth.status_line(w, :complaint, st.("pending")) =~ "hasn't been confirmed"
      assert WriteTruth.status_line(w, :complaint, st.("done")) =~ "Yes, it's added"
      assert WriteTruth.status_line(w, :claim, st.("cancelled")) =~ "nothing was added"
      assert WriteTruth.status_line(w, :claim, st.(nil)) =~ "I haven't added anything yet."
      assert WriteTruth.status_line(nil, :claim) =~ "I haven't added anything on this call."
    end

    test "the card, spoken" do
      assert WriteTruth.card_line(
               "Add to your Risi Calendar: Interview · Fri 16 Oct, 11 AM–1 PM · with Shenika",
               ~D[2026-10-15]
             ) ==
               "I haven't added it yet. I've put a card in your Risi chat: Interview, tomorrow, " <>
                 "11 am to 1 pm, with Shenika. Tap Add to put it in your Risi Calendar, or Cancel."
    end
  end

  describe "the phone's own writes on a call (device call 4, 15 Oct 15:29Z)" do
    test "card summaries of every kind, spoken" do
      t = ~D[2026-10-15]

      assert WriteTruth.card_line(
               ~s(Add "Call with the bank" to your calendar on Fri 16 Oct, 16:00–17:00),
               t
             ) =~ "I've put a card in your Risi chat: Call with the bank, tomorrow, 4 pm to 5 pm."

      assert WriteTruth.added_line("Remind you on Fri 16 Oct, 17:00: call Ruwan", t) ==
               "Added to your reminders: call Ruwan, tomorrow, 5 pm."
    end

    test "a voice yes never runs the phone's calendar_add or an alarm; only the caller's own cards" do
      ctx = %{user_id: "u", conv: "grp:rc"}

      w = %{
        user_id: "u",
        tool: "risi_calendar_add",
        personal: true,
        card_conversation_id: "grp:rc"
      }

      assert RisiMe.Agent.CallRisi.VoiceConfirm.allowed(w, ctx) == :ok

      for {k, v} <- [
            tool: "calendar_add",
            tool: "set_alarm",
            tool: "send_message",
            user_id: "other",
            personal: false,
            card_conversation_id: "grp:official"
          ],
          do:
            assert(
              {:not_allowed, _} =
                RisiMe.Agent.CallRisi.VoiceConfirm.allowed(Map.put(w, k, v), ctx)
            )
    end

    test "a pending phone card: the yes is told to tap Add (it isn't added)" do
      w = %{request_id: "r", route: "calendar_write", last?: true}
      st = fn _ -> %{state: "pending", confirmed?: false, tool: "calendar_add"} end
      line = WriteTruth.status_line(w, :confirm, st)
      assert line =~ "I haven't added it yet. Your phone adds this one itself, so tap Add"
      assert line =~ "your calendar"
    end
  end

  describe "Spoken" do
    test "natural titles" do
      assert Spoken.natural_title("Free up calendar on 18th Oct whole day") == "free up calendar"

      assert Spoken.natural_title("Send SSH access for iPhone stuff by 6 PM tomorrow") ==
               "send SSH access for iPhone stuff"

      assert Spoken.natural_title("Call Ruwan") == "call Ruwan"
      assert Spoken.natural_title("SSH keys") == "SSH keys"
      assert Spoken.natural_title("Tomorrow") == "Tomorrow"
    end

    test "windows" do
      assert {~T[02:00:00], ~T[22:00:00], "between 2 am and 10 pm"} =
               Spoken.window(
                 "Yeah, but do I have anything from, say, 2 a.m. until... until maybe like, you know, 10 p.m."
               )

      assert {~T[09:00:00], ~T[17:00:00], _} = Spoken.window("anything between 9 and 5?")
      assert {~T[13:00:00], nil, "after lunch"} = Spoken.window("am I free after lunch?")
      assert {_, ~T[12:00:00], "in the morning"} = Spoken.window("anything in the morning")
      assert {~T[18:00:00], nil, "after 6 pm"} = Spoken.window("anything after 6 pm on Friday")
      assert Spoken.window("Do you have anything on the 18th of October?") == nil
      assert Spoken.window("what about 18 October") == nil
    end

    test "a window keeps all-day items and those in it" do
      evs = Turn.events() ++ [busy(~N[2026-10-18 23:00:00])]

      f = %{
        ok?: true,
        today: ~D[2026-10-15],
        from: ~D[2026-10-18],
        to: ~D[2026-10-19],
        events: Enum.filter(evs, &(NaiveDateTime.to_date(&1.start) == ~D[2026-10-18])),
        unread: [],
        window: {~T[02:00:00], ~T[22:00:00], "between 2 am and 10 pm"}
      }

      {text, _} = Spoken.render(f)

      assert text ==
               "Sunday the 18th between 2 am and 10 pm: on your Risi Calendar, free up calendar, all day."
    end

    test "a 24-hour phone block from midnight is all day" do
      s = ~N[2026-10-18 00:00:00]
      e = ~N[2026-10-19 00:00:00]

      f = %{
        ok?: true,
        today: ~D[2026-10-15],
        from: ~D[2026-10-18],
        to: ~D[2026-10-19],
        events: [
          %{
            kind: "busy",
            title: nil,
            start: s,
            end: e,
            all_day: false,
            start_utc: Turn.utc(s),
            end_utc: Turn.utc(e),
            tags: ["busy"]
          }
        ],
        unread: []
      }

      assert {"Sunday the 18th: busy all day.", true} = Spoken.render(f)
    end

    test "a rephrase that claims a write is not used" do
      r = "Tomorrow: busy from 9 to 10 am."
      assert Spoken.checked("Tomorrow you're busy from 9 to 10 am.", r)
      refute Spoken.checked("I've added it: tomorrow you're busy from 9 to 10 am.", r)
    end
  end

  describe "Brain" do
    test "routes of the call's replies" do
      topic = %{
        route: "calendar_read",
        range: %{from: ~D[2026-10-18], to: ~D[2026-10-19], now?: false}
      }

      write = %{request_id: "r", route: "calendar_write", last?: true}
      ctx = %{user_id: "u", today: ~D[2026-10-15], topic: topic, write: write}

      assert {"write_status", :confirm} = Brain.decide("That's correct", ctx)

      assert {"write_status", :complaint} =
               Brain.decide("Rishi, I can't see that you have added something.", ctx)

      assert {"calendar_write", :correction} =
               Brain.decide("I don't know. From 9 to 10. discuss about racing", ctx)

      # A new calendar question after a write is still a read.
      assert {"calendar_read", %{from: ~D[2026-10-16]}} =
               Brain.decide("What's on my calendar tomorrow?", ctx)

      no_write = %{ctx | write: nil}

      assert {"calendar_read", %{from: ~D[2026-10-18], window: {~T[02:00:00], ~T[22:00:00], _}}} =
               Brain.decide("do I have anything from 2 am until 10 pm", no_write)

      assert {"calendar_read", %{from: ~D[2026-10-18], window: {~T[13:00:00], nil, _}}} =
               Brain.decide("and after lunch?", no_write)

      # Without a write in the call, "that's correct" is chat (no card to speak of).
      assert {"chat", nil} = Brain.decide("That's correct", %{no_write | topic: nil})
    end

    test "'Was that for me?' only when Risi asked or offered nothing" do
      asked = %{history: [%{"role" => "assistant", "content" => "Was that for me?"}]}

      stated = %{
        history: [%{"role" => "assistant", "content" => "Sunday the 18th: busy all day."}]
      }

      assert Brain.addressed("Not in the menu.", asked) == :yes
      assert Brain.addressed("That's correct", stated) == :yes
      assert Brain.addressed("the blue folder", stated) == :ask
      assert Brain.addressed("the blue folder", asked) == :yes
    end

    test "the chat guard replaces a claim with the truth and drops the rest" do
      Process.delete({Brain, :claimed})
      ctx = %{write: nil}
      assert Brain.guard(ctx, "Got it.") == "Got it."
      assert Brain.guard(ctx, "I have added the racing discussion.") =~ "I haven't added anything"
      assert Brain.guard(ctx, "It is there now.") == nil
    end
  end

  defp busy(s) do
    e = NaiveDateTime.add(s, 3600)

    %{
      kind: "busy",
      title: nil,
      start: s,
      end: e,
      all_day: false,
      start_utc: Turn.utc(s),
      end_utc: Turn.utc(e),
      tags: ["busy"]
    }
  end
end
