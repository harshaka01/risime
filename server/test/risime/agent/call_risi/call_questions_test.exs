defmodule RisiMe.Agent.CallRisi.CallQuestionsTest do
  @moduledoc """
  The Call Risi question set (`eval/sets/call-risi/questions.jsonl`, 50 cases, P0 2026-10-15):
  each case is a call — one or more spoken turns — through the REAL session and brain (routing,
  topic carry, fragment merge, spoken answers, once-per-call lines) with a fake media worker, a
  fake speech service (the case's text) and a fake §25 turn that answers from a fixed synthetic
  calendar (the case's clock: Thu 15 Oct 2026, 12:41 Colombo).

    mix test --only call_questions                       # fake risi-l1 (default; every case must pass)
    CALL_QUESTIONS_LIVE=1 mix test --only call_questions # live risi-l1 on 127.0.0.1:8100, read-only
                                                         # model requests; prints the pass count

  Checks per turn: `route`, `range` (the schedule read's local dates), `must` / `must_not`
  (case-insensitive, in what Risi said), `text` (the merged question); and on every turn: never
  "still busy", never a card's "From:" / "Checked:" line, never "I can't see your calendar".
  """
  use ExUnit.Case, async: false

  alias RisiMe.Agent.CallRisi.{Fillers, Session}

  @moduletag :call_questions
  @moduletag timeout: 600_000

  @set Path.expand("../../../../../eval/sets/call-risi/questions.jsonl", __DIR__)
  @live System.get_env("CALL_QUESTIONS_LIVE") in ["1", "true"]

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

  defmodule LLM do
    def stream([%{"content" => sys} | _] = messages, _opts, on_delta) do
      if sys =~ "Rephrase the CALENDAR" do
        cal =
          messages
          |> List.last()
          |> Map.get("content")
          |> String.split("CALENDAR: ")
          |> List.last()

        on_delta.("Okay. " <> cal)
      else
        on_delta.("Sure. What would you like to know?")
      end

      {:ok, %{model: "fake-l1", first_token_ms: 40, tokens: 3}}
    end
  end

  defmodule Turn do
    @moduledoc false
    @today ~D[2026-10-15]

    defp ev(kind, title, s, mins) do
      e = if mins, do: NaiveDateTime.add(s, mins * 60)

      %{
        kind: kind,
        title: title,
        start: s,
        end: e,
        all_day: false,
        start_utc: NaiveDateTime.to_iso8601(s) <> "Z",
        end_utc: e && NaiveDateTime.to_iso8601(e) <> "Z",
        tags: [kind]
      }
    end

    # The synthetic calendar (local times): phone busy blocks, one Risi reminder, one promise.
    def events do
      [
        ev("busy", nil, ~N[2026-10-15 14:00:00], 60),
        ev("busy", nil, ~N[2026-10-16 10:00:00], 60),
        ev("reminder", "Call Ruwan", ~N[2026-10-16 17:00:00], nil),
        ev("busy", nil, ~N[2026-10-17 11:00:00], 60),
        ev("busy", nil, ~N[2026-10-18 09:00:00], 90),
        ev("busy", nil, ~N[2026-10-19 15:00:00], 60),
        ev("promise", "Send the quote to Lotusbay", ~N[2026-10-20 17:00:00], nil)
      ]
    end

    def run(_conv, env, request_id, user) do
      lock = {{:risi_turn, user}, self()}

      if :global.set_lock(lock, [node()], 0) do
        try do
          work(env, request_id)
        after
          :global.del_lock(lock, [node()])
        end
      else
        {:snooze, 2}
      end
    end

    defp work(env, rid) do
      send(:persistent_term.get({__MODULE__, :test}), {:turn_run, env})
      {pid, _} = Process.get(:risi_voice_sink)
      text = env["text"]

      intent =
        case env["__schedule"] do
          %{} = r -> {:schedule_read, r}
          _ -> RisiMe.Agent.ScheduleIntent.classify(text, @today)
        end

      case intent do
        {:schedule_read, r} ->
          evs =
            for e <- events(),
                d = NaiveDateTime.to_date(e.start),
                Date.compare(d, r.from) != :lt and Date.compare(d, r.to) == :lt,
                do: e

          f = %{ok?: true, today: @today, from: r.from, to: r.to, events: evs, unread: []}
          send(pid, {:risi_voice_facts, rid, f})

          send(
            pid,
            {:risi_voice_answer, rid,
             Calendar.strftime(r.from, "%a %-d %b") <> ":\n\nFrom: your phone calendars · Risi"}
          )

          :ok

        :risi_items ->
          send(
            pid,
            {:risi_voice_answer, rid,
             "Here's what I've set up for you:\n- Fri 16 Oct, 17:00 · Reminder: Call Ruwan\n" <>
               "- Tue 20 Oct, 17:00 · Your promise: Send the quote to Lotusbay\n\nFrom: Risi"}
          )

          :ok

        {:memory, _} ->
          send(
            pid,
            {:risi_voice_answer, rid,
             "You told Ruwan you'd send the revised quote to Lotusbay by Tuesday.\n\n" <>
               "Checked: your Official chats"}
          )

          :ok

        _ ->
          # A write: its card waits in the Risi chat (no answer text).
          :ok
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
      :risi_now,
      :risi_llm
    ]

    prev = for k <- keys, do: {k, Application.get_env(:risime, k)}
    Application.put_env(:risime, :risi_call_media, Media)
    Application.put_env(:risime, :risi_speech, Speech)
    Application.put_env(:risime, :risi_call_turn, Turn)
    Application.put_env(:risime, :risi_call_runs, Runs)
    Application.put_env(:risime, :risi_now, "2026-10-15T07:11:00Z")

    if @live do
      # Read-only model requests to risi-l1 on loopback (LLMStream refuses anything else).
      Application.delete_env(:risime, :risi_call_llm)

      Application.put_env(:risime, :risi_llm,
        url: "http://127.0.0.1:8100/v1",
        api_key: System.get_env("LLM_API_KEY"),
        fallback: false
      )
    else
      Application.put_env(:risime, :risi_call_llm, LLM)
    end

    :persistent_term.put({Turn, :test}, self())

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

    :ok
  end

  test "the Call Risi question set" do
    cases = @set |> File.read!() |> String.split("\n", trim: true) |> Enum.map(&Jason.decode!/1)
    assert length(cases) == 50
    assert Enum.any?(cases, &(&1["id"] == "harsha-2026-10-15-call"))

    results = for c <- cases, do: {c["id"], run_case(c)}
    failed = for {id, errs} <- results, errs != [], do: {id, errs}
    passed = length(results) - length(failed)

    IO.puts(
      "\nCALL QUESTIONS (#{if @live, do: "live risi-l1", else: "fake model"}): " <>
        "#{passed}/#{length(results)} passed"
    )

    for {id, errs} <- failed, do: IO.puts("  FAIL #{id}: #{Enum.join(errs, "; ")}")

    if @live do
      # The safety checks hold even live; the rest is the measured score.
      unsafe = for {id, errs} <- failed, e <- errs, String.starts_with?(e, "unsafe"), do: {id, e}
      assert unsafe == []
    else
      assert failed == []
    end
  end

  ## One case = one call

  defp run_case(c) do
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
      metrics_to: me,
      tools?: true,
      caps: ~w(risi_calendar_check risi_items set_reminder risi_calendar_add memory_search),
      post: fn _conv, _body, risi -> send(me, {:posted, risi}) end
    }

    {:ok, pid} = DynamicSupervisor.start_child(RisiMe.Agent.CallRisi.Sessions, {Session, args})
    assert_receive {:media_cmd, %{"cmd" => "join"}}
    ev(pid, %{"ev" => "joined"})
    ev(pid, %{"ev" => "caller_joined"})
    assert_receive {:media_cmd, %{"cmd" => "say", "seq" => 1, "utt" => 0}}
    ev(pid, %{"ev" => "said", "seq" => 1, "utt" => 0, "interrupted" => false})
    ev(pid, %{"ev" => "idle"})

    {errs, _} =
      c["turns"]
      |> Enum.with_index(1)
      |> Enum.flat_map_reduce(nil, fn {t, utt}, pending ->
        if t["continues"], do: Process.sleep(t["after_ms"] || 1_000)
        flush_runs()
        lang = t["stt_lang"] || c["stt_lang"] || "en"

        ev(pid, %{
          "ev" => "utterance",
          "utt" => utt,
          "pcm16k" => Base.encode64(lang <> ":" <> t["say"]),
          "speech_ms" => if(t["continues"], do: 500, else: 900),
          "eot_wait_ms" => 450
        })

        case t["expect"] do
          nil ->
            # The first part of a split question: the caller goes on talking (nothing heard).
            _ = says(utt, [], 1_500)
            {[], utt}

          exp ->
            cmds = says(utt)
            run = last_run()
            heard(pid, cmds)
            route = receive_route(utt)
            _ = pending
            {check(c["id"], utt, exp, cmds, run, route), nil}
        end
      end)

    ev(pid, %{"ev" => "caller_left"})
    receive do: ({:posted, _} -> :ok), after: (2_000 -> :ok)
    errs
  end

  defp check(id, utt, exp, cmds, run, route) do
    said = cmds |> Enum.map(& &1["text"]) |> Enum.reject(&(&1 in Fillers.all())) |> Enum.join(" ")
    low = String.downcase(said)
    tag = "t#{utt}"

    [
      if(said == "", do: "#{tag}: nothing said"),
      if(exp["route"] && route != exp["route"],
        do: "#{tag}: route #{inspect(route)} != #{exp["route"]}"
      ),
      case exp["range"] do
        [f, t] ->
          got = run && run["__schedule"]

          unless got && Date.to_iso8601(got.from) == f && Date.to_iso8601(got.to) == t,
            do: "#{tag}: range #{inspect(got && {got.from, got.to})} != #{f}..#{t}"

        _ ->
          nil
      end,
      if(exp["text"] && (run == nil or run["text"] != exp["text"]),
        do: "#{tag}: text #{inspect(run && run["text"])}"
      ),
      for(
        m <- exp["must"] || [],
        not String.contains?(low, String.downcase(m)),
        do: "#{tag}: missing #{inspect(m)} in #{inspect(said)}"
      ),
      for(
        m <- exp["must_not"] || [],
        String.contains?(low, String.downcase(m)),
        do: "unsafe #{tag}: has #{inspect(m)}"
      ),
      for(
        {re, why} <- [
          {~r/still busy/i, "still busy"},
          {~r/\b(?:From|Checked|Not checked):/, "card text"},
          {~r/can'?t (?:see|access|read|get) (?:your|the) calendar/i, "claims it can't see"}
        ],
        Regex.match?(re, said),
        do: "unsafe #{tag}: #{why}"
      )
    ]
    |> List.flatten()
    |> Enum.reject(&is_nil/1)
    |> Enum.map(&"#{id} #{&1}")
  end

  defp ev(pid, map), do: send(pid, {:call_risi_media, map})

  defp says(utt, acc \\ [], wait \\ 15_000) do
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

  defp flush_runs do
    receive do
      {:turn_run, _} -> flush_runs()
      {:call_risi_turn, _} -> flush_runs()
    after
      0 -> :ok
    end
  end

  defp last_run(acc \\ nil) do
    receive do
      {:turn_run, env} -> last_run(env)
    after
      0 -> acc
    end
  end

  defp receive_route(utt) do
    receive do
      {:call_risi_turn, %{utt: ^utt, route: r}} -> r
    after
      2_000 -> nil
    end
  end
end
