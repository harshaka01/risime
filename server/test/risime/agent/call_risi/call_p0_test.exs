defmodule RisiMe.Agent.CallRisi.CallP0Test do
  @moduledoc """
  P0 2026-10-15: Harsha's real Call Risi call (pilot, 07:11–07:13Z) replayed against a fake media
  worker, fake speech, a fake risi-l1 and a fake §25 turn that behaves like the real one where it
  matters: a per-user lock (§25.6, `{:snooze, 2}` when held), the v2 card text with local events
  ("Thu 15 Oct:" + "From: your phone calendars · Risi") as its answer, and the read's facts
  (`{:risi_voice_facts, …}`) for the call.

  The phone calendar (Thu 15 Oct is today): Thu 14:00–15:00, Fri 10:00–11:00, Sat 11:00–12:00,
  Sun 18 09:00–10:30; one Risi reminder "Call Ruwan" Fri 17:00.
  """
  use ExUnit.Case, async: false

  alias RisiMe.Agent.CallRisi.{Fillers, Session}

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

  # risi-l1: a calendar rephrase echoes the rendering (or says something unsafe when told to);
  # a chat answer is a fixed line.
  defmodule LLM do
    def stream([%{"content" => sys} | _] = messages, _opts, on_delta) do
      test = :persistent_term.get({__MODULE__, :test})
      send(test, {:llm, messages})

      if sys =~ "Rephrase the CALENDAR" do
        cal =
          messages
          |> List.last()
          |> Map.get("content")
          |> String.split("CALENDAR: ")
          |> List.last()

        case :persistent_term.get({__MODULE__, :phrase}, :echo) do
          :echo -> on_delta.("Okay. " <> cal)
          :unsafe -> on_delta.("I can't see your calendar right now, sorry.")
        end
      else
        on_delta.("Sure, which day do you mean?")
      end

      {:ok, %{model: "fake-l1", first_token_ms: 40, tokens: 3}}
    end
  end

  defmodule Turn do
    @moduledoc false
    @today ~D[2026-10-15]

    def events do
      [
        busy(~D[2026-10-15], 14, 0, 60),
        busy(~D[2026-10-16], 10, 0, 60),
        %{
          kind: "reminder",
          title: "Call Ruwan",
          start: ~N[2026-10-16 17:00:00],
          end: nil,
          all_day: false,
          start_utc: "2026-10-16T11:30:00.000Z",
          end_utc: nil,
          tags: ["reminder"]
        },
        busy(~D[2026-10-17], 11, 0, 60),
        busy(~D[2026-10-18], 9, 0, 90)
      ]
    end

    defp busy(d, h, m, mins) do
      s = NaiveDateTime.new!(d, Time.new!(h, m, 0))
      e = NaiveDateTime.add(s, mins * 60)

      %{
        kind: "busy",
        title: nil,
        start: s,
        end: e,
        all_day: false,
        start_utc: "#{NaiveDateTime.to_iso8601(s)}Z",
        end_utc: "#{NaiveDateTime.to_iso8601(e)}Z",
        tags: ["busy"]
      }
    end

    # Like RisiMe.Agent.Turn.run/4: one running turn per user.
    def run(_conv, env, request_id, user) do
      # "hang": a phone that never answers (the turn waits on it outside this sketch's lock).
      if env["text"] =~ "hang", do: Process.sleep(:infinity)
      lock = {{:risi_turn, user}, self()}

      if :global.set_lock(lock, [node()], 0) do
        try do
          work(env, request_id, user)
        after
          :global.del_lock(lock, [node()])
        end
      else
        {:snooze, 2}
      end
    end

    defp work(env, request_id, _user) do
      test = :persistent_term.get({RisiMe.Agent.CallRisi.CallP0Test.LLM, :test})
      send(test, {:turn_run, env})
      {pid, _} = Process.get(:risi_voice_sink)
      text = env["text"]

      if text =~ "slow", do: Process.sleep(:infinity)

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

        facts = %{
          ok?: true,
          today: @today,
          from: range.from,
          to: range.to,
          events: evs,
          unread: []
        }

        send(pid, {:risi_voice_facts, request_id, facts})

        # The real v2 card text with local events: the head line and the footer only.
        head = Calendar.strftime(range.from, "%a %-d %b")

        send(
          pid,
          {:risi_voice_answer, request_id, head <> ":\n\nFrom: your phone calendars · Risi"}
        )
      else
        send(pid, {:risi_voice_answer, request_id, "Okay, I've put that in your Risi chat."})
      end

      # Delivering the card can take a moment after the answer (the lock is still held).
      Process.sleep(:persistent_term.get({__MODULE__, :hold_ms}, 0))
      :ok
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
      :risi_call_merge_ms,
      :risi_call_tool_wait_ms,
      :risi_now,
      :risi_agent_flags
    ]

    prev = for k <- keys, do: {k, Application.get_env(:risime, k)}
    Application.put_env(:risime, :risi_call_media, Media)
    Application.put_env(:risime, :risi_speech, Speech)
    Application.put_env(:risime, :risi_call_llm, LLM)
    Application.put_env(:risime, :risi_call_turn, Turn)
    # The session checks the switch every 10 s: on for the whole test.
    Application.put_env(:risime, :risi_agent_flags, %{
      "RISI_CALL_RISI" => "on",
      "RISI_AGENTS" => "on"
    })

    Application.put_env(:risime, :risi_call_runs, Runs)
    # Harsha's call: Thu 15 Oct 2026, 12:41 in Colombo.
    Application.put_env(:risime, :risi_now, "2026-10-15T07:11:00Z")
    :persistent_term.put({LLM, :test}, self())
    :persistent_term.put({LLM, :phrase}, :echo)
    :persistent_term.put({Turn, :hold_ms}, 0)

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
      caps: ~w(risi_calendar_check risi_items set_reminder memory_search),
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

  defp utter(pid, utt, script, speech_ms \\ 900),
    do:
      ev(pid, %{
        "ev" => "utterance",
        "utt" => utt,
        "pcm16k" => Base.encode64(script),
        "speech_ms" => speech_ms,
        "eot_wait_ms" => 450
      })

  # Every piece Risi says for `utt`, until an answer piece came and 400 ms passed quietly.
  defp says(utt, acc \\ [], wait \\ 10_000) do
    receive do
      {:media_cmd, %{"cmd" => "say", "utt" => ^utt} = c} ->
        acc = acc ++ [c]
        answered? = Enum.any?(acc, &(&1["text"] not in Fillers.all()))
        says(utt, acc, if(answered?, do: 800, else: wait))
    after
      wait -> acc
    end
  end

  defp answer_text(cmds),
    do: cmds |> Enum.map(& &1["text"]) |> Enum.reject(&(&1 in Fillers.all())) |> Enum.join(" ")

  # The caller heard every piece in full, then the worker went idle.
  defp heard(pid, cmds) do
    for c <- cmds,
        do:
          ev(pid, %{"ev" => "said", "seq" => c["seq"], "utt" => c["utt"], "interrupted" => false})

    ev(pid, %{"ev" => "idle"})
  end

  defp transcript(pid) do
    ev(pid, %{"ev" => "caller_left"})
    assert_receive {:posted, _, _, risi}
    risi["lines"]
  end

  @still_busy ~r/still busy/i
  @cant_see ~r/can'?t (?:see|get|access|read)/i

  test "Harsha's call, 15 Oct (id harsha-2026-10-15-call): five questions, all answered", %{
    args: args
  } do
    pid = start!(args)

    # U1: a true list of what Risi can do on this call.
    utter(pid, 1, "en:What can you do?")
    u1 = says(1)
    a1 = answer_text(u1)
    assert a1 =~ "what's on your calendar"
    assert a1 =~ "reminders, promises and follow-ups"
    refute a1 =~ "send a message"
    heard(pid, u1)

    # U2: today AND tomorrow, spoken from the read; never the card's head line or footer.
    utter(pid, 2, "en:Check my calendar for today and tomorrow?")
    u2 = says(2)
    assert hd(u2)["text"] in Fillers.all()
    assert_receive {:turn_run, %{"__schedule" => %{from: ~D[2026-10-15], to: ~D[2026-10-17]}}}
    a2 = answer_text(u2)
    assert a2 =~ "Today: busy from 2 to 3 pm."
    assert a2 =~ "Tomorrow: busy from 10 to 11 am and a reminder, Call Ruwan, at 5 pm."
    assert a2 =~ "I can see the times; the titles stay on your phone."
    refute a2 =~ "From:"
    refute a2 =~ "Thu 15 Oct"
    heard(pid, u2)

    # U3, split by a pause: "Do you have anything" … ~1 s … "on the 18th?". The fragment's chat
    # answer is cut; the two merge into one calendar question for Sun 18 Oct.
    utter(pid, 3, "en:Do you have anything")
    _u3a = says(3, [], 1_000)
    Process.sleep(1_000)
    utter(pid, 4, "en:on the 18th?", 500)
    u3 = says(4)
    assert_receive {:turn_run, %{"text" => "Do you have anything on the 18th?"}}
    a3 = answer_text(u3)
    assert a3 =~ "Sunday the 18th: busy from 9 to 10:30 am."
    refute a3 =~ @cant_see
    heard(pid, u3)

    # U4: "why can't you…" is a calendar follow-up (the 18th again), never a claim it can't see.
    # Its card takes a while to deliver after the answer: the lock stays held for 1.5 s.
    :persistent_term.put({Turn, :hold_ms}, 1_500)
    utter(pid, 5, "en:Why can't you get the calendar information?")
    u4 = says(5)
    :persistent_term.put({Turn, :hold_ms}, 0)
    a4 = answer_text(u4)
    assert a4 =~ "Sunday the 18th: busy from 9 to 10:30 am."
    refute a4 =~ @cant_see
    # The titles line is said once a call.
    refute a4 =~ "titles stay on your phone"
    heard(pid, u4)

    # U5: the week (today → Sunday), right away: the busy lock is waited out, never "still busy".
    utter(pid, 6, "en:Give me the entire week… tell me the important")
    u5 = says(6)
    a5 = answer_text(u5)
    refute a5 =~ @still_busy
    assert a5 =~ "Today: busy from 2 to 3 pm."
    assert a5 =~ "Saturday the 17th: busy from 11 am to 12 noon."
    assert a5 =~ "Sunday the 18th: busy from 9 to 10:30 am."
    heard(pid, u5)

    lines = transcript(pid)
    texts = Enum.map(lines, & &1["text"])
    assert "Do you have anything on the 18th?" in texts
    refute "Do you have anything" in texts
    refute Enum.any?(texts, &(&1 =~ "From:"))
    refute Enum.any?(texts, &(&1 =~ @still_busy))
  end

  test "a new question kills a stuck tool turn and frees the user's turn lock", %{args: args} do
    # Seconds apart (no fragment merge): the stuck turn is replaced, not continued.
    Application.put_env(:risime, :risi_call_merge_ms, -100_000)
    pid = start!(args)
    utter(pid, 1, "en:what's on my calendar tomorrow, slow")
    assert_receive {:turn_run, %{"text" => "what's on my calendar tomorrow, slow"}}
    [filler] = says(1, [], 300)
    assert filler["text"] in Fillers.all()

    utter(pid, 2, "en:what's on my calendar tomorrow")
    u2 = says(2)
    assert answer_text(u2) =~ "Tomorrow: busy from 10 to 11 am"
    refute answer_text(u2) =~ @still_busy
  end

  test "a tool turn that never answers: the timeout line says what failed, once a call", %{
    args: args
  } do
    Application.put_env(:risime, :risi_call_tool_wait_ms, 300)
    pid = start!(args)
    utter(pid, 1, "en:what's on my calendar tomorrow, hang")
    u1 = says(1)

    assert answer_text(u1) ==
             "Your phone's calendar didn't answer in time, so I'll put the answer in your Risi chat."

    heard(pid, u1)
    utter(pid, 2, "en:what's on my calendar on Friday, hang")
    u2 = says(2)
    assert answer_text(u2) == "That one will be in your Risi chat too."
  end

  test "an unsafe rephrase falls back to the rendering", %{args: args} do
    :persistent_term.put({LLM, :phrase}, :unsafe)
    pid = start!(args)
    utter(pid, 1, "en:what's on my calendar tomorrow")
    a = answer_text(says(1))
    assert a =~ "Tomorrow: busy from 10 to 11 am and a reminder, Call Ruwan, at 5 pm."
    refute a =~ @cant_see
  end

  test "fragments further apart than the merge window are not merged; the topic still carries", %{
    args: args
  } do
    Application.put_env(:risime, :risi_call_merge_ms, -100_000)
    pid = start!(args)
    utter(pid, 1, "en:what's on my calendar today")
    heard(pid, says(1))
    utter(pid, 2, "en:and on the 18th?")
    says(2)

    assert_receive {:turn_run,
                    %{"text" => "and on the 18th?", "__schedule" => %{from: ~D[2026-10-18]}}}
  end

  test "Sinhala speech: one line once a call, then the answer in English", %{args: args} do
    pid = start!(args)
    utter(pid, 1, "si:What can you do?")
    u1 = says(1)
    a = answer_text(u1)
    assert a =~ "I can only speak English on calls for now"
    assert a =~ "On this call I can"
    heard(pid, u1)
    utter(pid, 2, "si:what's on my calendar tomorrow")
    refute answer_text(says(2)) =~ "only speak English"
  end
end
