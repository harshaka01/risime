defmodule RisiMe.Agent.CallRisi.CallTitlesTest do
  @moduledoc """
  Contract v1.52 §41.8 wired into the Voice agent (P0 2026-10-15 item 3): on a calendar voice turn
  a `call_risi_cal` device is asked for its meeting titles (`cal_req` through the worker); the
  titles are spoken in that one answer and nowhere else: not in the stored transcript line (times
  only, `titles_spoken: true`), not in the posted body, not in the learning log (counts only),
  not in the next turn's model history. A device without the capability is never asked; a phone
  that doesn't answer in 2 s gets times only and the times-only line once a call.
  """
  use RisiMe.DataCase, async: false

  alias RisiMe.Agent.CallRisi.{Fillers, PhoneCalendar, Session}

  @moduletag capture_log: true

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
      {:ok, %{text: text, language: lang, model: "fake-asr", elapsed_ms: 10, speech: true}}
    end
  end

  defmodule LLM do
    def stream([%{"content" => sys} | _] = messages, _opts, on_delta) do
      send(:persistent_term.get({__MODULE__, :test}), {:llm, messages})

      if sys =~ "Rephrase the CALENDAR" do
        on_delta.(
          "Okay. " <>
            (messages
             |> List.last()
             |> Map.get("content")
             |> String.split("CALENDAR: ")
             |> List.last())
        )
      else
        on_delta.("You're welcome.")
      end

      {:ok, %{model: "fake-l1", first_token_ms: 40, tokens: 3}}
    end
  end

  defmodule Log do
    def record(entry) do
      send(:persistent_term.get({RisiMe.Agent.CallRisi.CallTitlesTest.LLM, :test}), {:log, entry})
      :ok
    end
  end

  # Tomorrow (Fri 16 Oct) 10:00–11:00 busy on the phone (04:30–05:30Z).
  defmodule Turn do
    def run(_conv, env, rid, _user) do
      {pid, _} = Process.get(:risi_voice_sink)

      facts = %{
        ok?: true,
        today: ~D[2026-10-15],
        from: env["__schedule"].from,
        to: env["__schedule"].to,
        unread: [],
        events: [
          %{
            kind: "busy",
            title: nil,
            start: ~N[2026-10-16 10:00:00],
            end: ~N[2026-10-16 11:00:00],
            all_day: false,
            start_utc: "2026-10-16T04:30:00.000Z",
            end_utc: "2026-10-16T05:30:00.000Z",
            tags: ["busy"]
          }
        ]
      }

      send(pid, {:risi_voice_facts, rid, facts})
      send(pid, {:risi_voice_answer, rid, "Fri 16 Oct:\n\nFrom: your phone calendars · Risi"})
      :ok
    end
  end

  defmodule Runs do
    def record(_), do: :ok
  end

  setup do
    keys =
      ~w(risi_call_media risi_speech risi_call_llm risi_call_turn risi_call_runs risi_now risi_learning_log risi_agent_flags)a

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
    Application.put_env(:risime, :risi_learning_log, Log)
    Application.put_env(:risime, :risi_now, "2026-10-15T07:11:00Z")
    :persistent_term.put({LLM, :test}, self())

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

    u = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
    me = self()

    args = %{
      conv: "grp:risi-chat",
      call_id: Ecto.UUID.generate(),
      user_id: u.user.id,
      device_id: Ecto.UUID.generate(),
      room: "room1",
      risi_token: "tok",
      worker_url: "ws://127.0.0.1:7880",
      test: me,
      tools?: true,
      caps: ~w(risi_calendar_check),
      post: fn conv, body, risi -> send(me, {:posted, conv, body, risi}) end
    }

    %{args: args}
  end

  defp start!(args) do
    {:ok, pid} = DynamicSupervisor.start_child(RisiMe.Agent.CallRisi.Sessions, {Session, args})
    assert_receive {:media_cmd, %{"cmd" => "join"}}
    ev(pid, %{"ev" => "joined"})
    ev(pid, %{"ev" => "caller_joined"})
    assert_receive {:media_cmd, %{"cmd" => "say", "seq" => 1}}
    ev(pid, %{"ev" => "said", "seq" => 1, "utt" => 0, "interrupted" => false})
    ev(pid, %{"ev" => "idle"})
    pid
  end

  defp ev(pid, m), do: send(pid, {:call_risi_media, m})

  defp utter(pid, utt, text),
    do:
      ev(pid, %{
        "ev" => "utterance",
        "utt" => utt,
        "pcm16k" => Base.encode64("en:" <> text),
        "speech_ms" => 900,
        "eot_wait_ms" => 450
      })

  defp says(utt, acc \\ [], wait \\ 6_000) do
    receive do
      {:media_cmd, %{"cmd" => "say", "utt" => ^utt} = c} ->
        acc = acc ++ [c]
        done? = Enum.any?(acc, &(&1["text"] not in Fillers.all()))
        says(utt, acc, if(done?, do: 500, else: wait))
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

  defp answer(cmds),
    do: cmds |> Enum.map(& &1["text"]) |> Enum.reject(&(&1 in Fillers.all())) |> Enum.join(" ")

  test "a capable phone: the title is spoken once, never stored, logged or posted", %{args: args} do
    pid = start!(Map.put(args, :cal?, true))
    utter(pid, 1, "what's on my calendar tomorrow")

    assert_receive {:media_cmd, %{"cmd" => "cal_req", "id" => id, "from" => from, "to" => to}},
                   3_000

    assert from =~ "2026-10-16T00:00:00"
    assert to =~ "2026-10-17T00:00:00"

    ev(pid, %{
      "ev" => "cal",
      "id" => id,
      "ok" => true,
      "events" => [
        %{
          "start" => "2026-10-16T10:00:00+05:30",
          "end" => "2026-10-16T11:00:00+05:30",
          "all_day" => false,
          "busy" => true,
          "title" => "Interview with Nadeesha"
        }
      ]
    })

    u1 = says(1)
    assert answer(u1) =~ "Tomorrow: Interview with Nadeesha from 10 to 11 am."
    refute answer(u1) =~ PhoneCalendar.times_only_note()
    heard(pid, u1)

    # The learning log of the phrasing: counts only.
    assert_receive {:log, %{task: "voice_call_answer", meta: meta} = row}
    assert meta["titles_n"] == 1 and meta["cal_ok"] == true
    refute inspect(row) =~ "Nadeesha"

    # The next turn's model history: times only.
    utter(pid, 2, "thanks")
    assert_receive {:llm, [%{"content" => "You are Risi, the user's assistant" <> _} | _] = msgs}
    refute inspect(msgs) =~ "Nadeesha"
    heard(pid, says(2))

    ev(pid, %{"ev" => "caller_left"})
    assert_receive {:posted, _, body, risi}
    refute body =~ "Nadeesha"
    refute inspect(risi) =~ "Nadeesha"
    [line] = for l <- risi["lines"], l["titles_spoken"], do: l
    assert line["text"] =~ "Tomorrow: busy from 10 to 11 am."
  end

  test "the phone doesn't answer in 2 s: times only, the times-only line once", %{args: args} do
    pid = start!(Map.put(args, :cal?, true))
    utter(pid, 1, "what's on my calendar tomorrow")
    assert_receive {:media_cmd, %{"cmd" => "cal_req"}}, 3_000
    u1 = says(1)
    assert answer(u1) =~ "Tomorrow: busy from 10 to 11 am."
    assert answer(u1) =~ PhoneCalendar.times_only_note()
    heard(pid, u1)
    assert_receive {:log, %{meta: %{"cal_ok" => false, "cal_reason" => "timeout"}}}

    utter(pid, 2, "what's on my calendar on Friday")
    u2 = says(2)
    assert answer(u2) =~ "busy from 10 to 11 am"
    refute answer(u2) =~ PhoneCalendar.times_only_note()
  end

  test "without the capability the phone is never asked (parity: times only)", %{args: args} do
    pid = start!(Map.put(args, :cal?, false))
    utter(pid, 1, "what's on my calendar tomorrow")
    u1 = says(1)
    assert answer(u1) =~ "Tomorrow: busy from 10 to 11 am."
    refute_received {:media_cmd, %{"cmd" => "cal_req"}}
  end

  test "another id's reply is not forwarded", %{args: args} do
    pid = start!(Map.put(args, :cal?, true))
    utter(pid, 1, "what's on my calendar tomorrow")
    assert_receive {:media_cmd, %{"cmd" => "cal_req"}}, 3_000

    ev(pid, %{
      "ev" => "cal",
      "id" => Ecto.UUID.generate(),
      "ok" => true,
      "events" => [
        %{
          "start" => "2026-10-16T10:00:00+05:30",
          "end" => "2026-10-16T11:00:00+05:30",
          "title" => "Secret thing"
        }
      ]
    })

    refute answer(says(1)) =~ "Secret"
  end
end
