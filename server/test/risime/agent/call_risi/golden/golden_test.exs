defmodule RisiMe.Agent.CallRisi.GoldenTest do
  @moduledoc """
  **Golden calls** of Call Risi brain v3 (architect P0 2026-10-16): Harsha's real calls, scripted
  turn by turn (`calls/*.json`, his words, contacts anonymised), with the expected action of each
  turn. Every call runs through the REAL session and the v3 brain (fragments, dialogue state,
  guards, spoken lines) with a fake media worker, a fake speech service (the turn's words), a fake
  §25 turn (a synthetic calendar, the message and calendar cards, their rows) and:

    * **recorded** (plain `mix test`): the model's decisions as recorded from risi-l1
      (`recorded/<call>.json`, keyed by the turn's words, with the measured latency);
    * **live** (`mix test --only llm_live test/risime/agent/call_risi/golden`): the real decision
      model on loopback (`config :risime, :risi_call_brain` model, default risi-l1). With
      `GOLDEN_RECORD=1` the live decisions are written to `recorded/` (re-record after any prompt
      change). Ship only when every golden turn passes live.

  Every new bad call Harsha reports becomes a file here, not a new regex.
  """
  use ExUnit.Case, async: false

  alias RisiMe.Agent.CallRisi.{Decider, Fillers, Session}

  @moduletag timeout: 900_000

  @dir Path.expand(".", __DIR__)
  @calls Path.join(@dir, "calls")
  @recorded Path.join(@dir, "recorded")

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
    # `noise:` Whisper's words made up from noise (no_speech_prob 0.9); `unclear:` speech the
    # model is unsure of (avg_logprob -1.1, no_speech_prob 0.2: "unclear", not "no speech").
    def transcribe(<<_hdr::binary-size(44), kind::binary-size(6), ":", text::binary>>, _opts)
        when kind in ["noise_", "uncle_"] do
      nsp = if kind == "noise_", do: 0.9, else: 0.2

      {:ok,
       %{
         text: text,
         language: "en",
         model: "fake-asr",
         elapsed_ms: 10,
         speech: true,
         segments: [
           %{
             offset_ms: 0,
             text: text,
             avg_logprob: -1.1,
             no_speech_prob: nsp,
             compression_ratio: 1.1
           }
         ]
       }}
    end

    def transcribe(<<_hdr::binary-size(44), script::binary>>, _opts) do
      [lang, text] = String.split(script, ":", parts: 2)
      {:ok, %{text: text, language: lang, model: "fake-asr", elapsed_ms: 10, speech: text != ""}}
    end
  end

  # The phrase step of a calendar answer (risi-l1 rephrases the rendering): the rendering itself.
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

  # The recorded decider: the decision risi-l1 made for these words (and its latency).
  defmodule Recorded do
    @behaviour RisiMe.Agent.CallRisi.Decider
    @impl true
    def decide(input, _ctx) do
      book = :persistent_term.get({__MODULE__, :book}, %{})

      case Map.get(book, RisiMe.Agent.CallRisi.GoldenTest.key(input.text)) do
        %{"decision" => d} = r ->
          Process.sleep(min(r["ms"] || 0, 1_500))
          {:ok, d}

        nil ->
          # Live, a turn the next words merged into was killed before its decision came: it
          # has none recorded. Missing only when this turn is still wanted after that time.
          Process.sleep(1_500)
          send(:persistent_term.get({__MODULE__, :test}), {:golden_missing, input.text})
          {:error, :not_recorded}
      end
    end
  end

  # The live decider, recording what it decided (GOLDEN_RECORD=1).
  defmodule Live do
    @behaviour RisiMe.Agent.CallRisi.Decider
    @impl true
    def decide(input, ctx) do
      t0 = System.monotonic_time(:millisecond)
      r = Decider.decide(input, ctx)
      ms = System.monotonic_time(:millisecond) - t0

      with {:ok, d} <- r do
        send(:persistent_term.get({Recorded, :test}), {:golden_decision, input.text, d, ms})
      end

      r
    end
  end

  defmodule Turn do
    @moduledoc false
    @friends %{"nimali" => "Nimali Perera"}

    def people,
      do: [
        {"f-nimali", "Nimali Perera"},
        {"f-ruwan", "Ruwan Silva"},
        {"f-kamal", "Kamal Jayasuriya"}
      ]

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

    def extra do
      for x <- :persistent_term.get({__MODULE__, :extra}, []) do
        s = NaiveDateTime.from_iso8601!(x["start"])
        e = ev(x["kind"], x["title"], s, x["mins"])

        utc = fn n ->
          n |> NaiveDateTime.add(-19_800) |> NaiveDateTime.to_iso8601() |> Kernel.<>("Z")
        end

        %{e | start_utc: utc.(s), end_utc: e.end && utc.(e.end)}
      end
    end

    # The card rows: `{state, tool}` by request id.
    def state(rid) do
      case :persistent_term.get({__MODULE__, :write, rid}, nil) do
        nil -> nil
        {s, tool} -> %{state: s, confirmed?: false, tool: tool}
      end
    end

    def put_state(rid, s, tool), do: :persistent_term.put({__MODULE__, :write, rid}, {s, tool})

    # "Cancel that" (Writes.cancel_request/2): a pending card is cancelled.
    def cancel(_user, rid) do
      case state(rid) do
        %{state: "pending", tool: tool} ->
          put_state(rid, "cancelled", tool)
          send(:persistent_term.get({__MODULE__, :test}), {:cancelled, rid})
          :ok

        _ ->
          :none
      end
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

    # §34.2: a new request supersedes the open cards.
    defp supersede do
      for {{__MODULE__, :write, rid}, {"pending", tool}} <- :persistent_term.get(),
          do: put_state(rid, "superseded", tool)
    end

    defp work(env, rid) do
      send(:persistent_term.get({__MODULE__, :test}), {:turn_run, env})
      {pid, _} = Process.get(:risi_voice_sink)
      text = env["text"]
      supersede()

      intent =
        cond do
          is_map(env[:call_send]) -> {:send, env[:call_send]}
          is_map(env["__schedule"]) -> {:schedule_read, env["__schedule"]}
          env["__intent"] == "risi_items" -> :risi_items
          true -> RisiMe.Agent.ScheduleIntent.classify(text, ~D[2026-10-15])
        end

      case intent do
        {:send, %{"to" => to, "text" => body}} ->
          case @friends[to |> String.downcase() |> String.split() |> List.first()] do
            nil ->
              send(pid, {:risi_voice_answer, rid, "I couldn't find #{to} in your contacts."})

            name ->
              put_state(rid, "pending", "send_message")
              send(pid, {:risi_voice_card, rid, "Send to #{name} (Private): #{body}"})
              send(pid, {:risi_voice_answer, rid, "I've drafted it. Tap Send to send it."})
          end

          :ok

        {:schedule_read, r} ->
          evs =
            for e <- events() ++ extra(),
                d = NaiveDateTime.to_date(e.start),
                Date.compare(d, r.from) != :lt and Date.compare(d, r.to) == :lt,
                do: e

          today = r.from
          f = %{ok?: true, today: today, from: r.from, to: r.to, events: evs, unread: []}
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

        _ ->
          if :persistent_term.get({__MODULE__, :cards}, false) do
            title = if text =~ ~r/racing/i, do: "Discuss racing", else: "Appointment"
            put_state(rid, "pending", "risi_calendar_add")

            send(
              pid,
              {:risi_voice_card, rid, "Add to your Risi Calendar: #{title} · Fri 16 Oct, 9–10 AM"}
            )

            send(
              pid,
              {:risi_voice_answer, rid,
               "Tap Add on the card to put it in your Risi Calendar, or Cancel."}
            )
          end

          :ok
      end
    end
  end

  defmodule Runs do
    def record(_row), do: :ok
  end

  @doc false
  def key(text), do: text |> String.downcase() |> String.replace(~r/\s+/u, " ") |> String.trim()

  setup do
    keys = [
      :risi_call_media,
      :risi_speech,
      :risi_call_llm,
      :risi_call_turn,
      :risi_call_runs,
      :risi_now,
      :risi_agent_flags,
      :risi_llm,
      :risi_call_write_state,
      :risi_call_decider,
      :risi_call_cancel,
      :risi_claim_results,
      :risi_contacts
    ]

    prev = for k <- keys, do: {k, Application.get_env(:risime, k)}
    Application.put_env(:risime, :risi_call_media, Media)
    Application.put_env(:risime, :risi_speech, Speech)
    Application.put_env(:risime, :risi_call_turn, Turn)
    Application.put_env(:risime, :risi_call_llm, LLM)
    Application.put_env(:risime, :risi_call_write_state, &Turn.state/1)
    Application.put_env(:risime, :risi_call_cancel, &Turn.cancel/2)
    # find_contact: the caller's people (the fake turn drafts only to Nimali Perera).
    Application.put_env(:risime, :risi_contacts, fn _ -> Turn.people() end)

    Application.put_env(:risime, :risi_claim_results, fn rid ->
      case Turn.state(rid) do
        nil -> []
        %{state: s, tool: t} -> [%{write_id: rid, state: s, tool: t, via: "ask"}]
      end
    end)

    Application.put_env(:risime, :risi_agent_flags, %{
      "RISI_CALL_RISI" => "on",
      "RISI_AGENTS" => "on",
      "RISI_CALL_BRAIN" => "v3"
    })

    Application.put_env(:risime, :risi_call_runs, Runs)
    :persistent_term.put({Turn, :test}, self())
    :persistent_term.put({Recorded, :test}, self())

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

  defp calls do
    @calls
    |> File.ls!()
    |> Enum.filter(&String.ends_with?(&1, ".json"))
    # GOLDEN_ONLY=call-5: one call (live debugging).
    |> Enum.filter(&String.contains?(&1, System.get_env("GOLDEN_ONLY") || ""))
    |> Enum.sort()
    |> Enum.map(&(Path.join(@calls, &1) |> File.read!() |> Jason.decode!()))
  end

  test "golden calls (recorded decisions)" do
    Application.put_env(:risime, :risi_call_decider, Recorded)
    cs = calls()
    assert length(cs) >= 5

    results =
      for c <- cs do
        book =
          case File.read(Path.join(@recorded, c["golden_id"] <> ".json")) do
            {:ok, s} -> Jason.decode!(s)
            _ -> %{}
          end

        :persistent_term.put({Recorded, :book}, book)
        {c["golden_id"], run_call(c)}
      end

    report(results, "recorded")
    assert Enum.flat_map(results, &elem(&1, 1)) == []
  end

  @tag :llm_live
  test "golden calls (live decision model)" do
    Application.put_env(:risime, :risi_call_decider, Live)

    Application.put_env(:risime, :risi_llm,
      url: "http://127.0.0.1:8100/v1",
      api_key: System.get_env("LLM_API_KEY"),
      fallback: false
    )

    results =
      for c <- calls() do
        errs = run_call(c)
        if System.get_env("GOLDEN_RECORD") in ["1", "true"], do: record_book(c)
        {c["golden_id"], errs}
      end

    report(results, "live #{Decider.model()}")
    assert Enum.flat_map(results, &elem(&1, 1)) == []
  end

  defp report(results, mode) do
    turns = for {_id, errs} <- results, do: errs
    failed = for {id, errs} <- results, errs != [], do: {id, errs}

    IO.puts(
      "\nGOLDEN CALLS (#{mode}): #{length(results) - length(failed)}/#{length(results)} calls passed" <>
        " (#{turns |> List.flatten() |> length()} turn failures)"
    )

    for {id, errs} <- failed, e <- errs, do: IO.puts("  FAIL #{id} #{e}")
  end

  # The live decisions of the call just run, as its recorded book.
  defp record_book(c) do
    book =
      collect_decisions(%{})
      |> Map.new(fn {k, {d, ms}} -> {k, %{"decision" => d, "ms" => ms}} end)

    File.mkdir_p!(@recorded)

    File.write!(
      Path.join(@recorded, c["golden_id"] <> ".json"),
      Jason.encode_to_iodata!(book, pretty: true) |> IO.iodata_to_binary() |> Kernel.<>("\n")
    )
  end

  defp collect_decisions(acc) do
    receive do
      {:golden_decision, text, d, ms} -> collect_decisions(Map.put(acc, key(text), {d, ms}))
    after
      0 -> acc
    end
  end

  ## One golden call

  defp run_call(c) do
    me = self()
    :persistent_term.put({Turn, :extra}, c["extra_events"] || [])
    :persistent_term.put({Turn, :cards}, c["cards"] == true)
    Application.put_env(:risime, :risi_now, c["now"] || "2026-10-15T07:11:00Z")

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
      cal?: false,
      v3?: true,
      caps:
        ~w(risi_calendar_check risi_items set_reminder risi_calendar_add memory_search send_message),
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
      |> Enum.flat_map_reduce([], fn {t, utt}, prev_qs ->
        if t["continues"], do: Process.sleep(t["after_ms"] || 1_000)
        flush()

        kind =
          cond do
            t["noise"] -> "noise_"
            t["unclear"] -> "uncle_"
            true -> nil
          end

        audio = if kind, do: kind <> ":" <> t["say"], else: "en:" <> t["say"]

        ev(pid, %{
          "ev" => "utterance",
          "utt" => utt,
          "pcm16k" => Base.encode64(audio),
          "speech_ms" => t["speech_ms"] || if(t["continues"], do: 500, else: 900),
          "eot_wait_ms" => 450
        })

        case t["expect"] do
          nil ->
            {[], []}

          exp ->
            cmds = if exp["silent"], do: says(utt, [], 2_000), else: says(utt)
            runs = all_runs()
            cancelled = cancelled?()
            heard(pid, cmds)
            route = receive_route(utt)
            qs = questions(cmds)

            # Architect 11:45: never the same question twice in a row.
            rep =
              for q <- qs, q in prev_qs, do: "unsafe t#{utt}: repeated question #{inspect(q)}"

            filler =
              for c <- cmds,
                  exp["no_filler"] == true and c["text"] in Fillers.all(),
                  do: "t#{utt}: a holding line before a question: #{inspect(c["text"])}"

            {check(utt, exp, cmds, runs, route, cancelled) ++ rep ++ filler, qs}
        end
      end)

    ev(pid, %{"ev" => "caller_left"})
    receive do: ({:posted, _} -> :ok), after: (2_000 -> :ok)
    missing = missing()
    errs ++ for(m <- missing, do: "not recorded: #{inspect(m)} (record live: GOLDEN_RECORD=1)")
  end

  @doc false
  # The questions Risi asked in a turn (normalised sentences ending in "?").
  def questions(cmds) do
    cmds
    |> Enum.map(& &1["text"])
    |> Enum.reject(&(&1 in Fillers.all()))
    |> Enum.join(" ")
    |> String.split(~r/(?<=[.!?])\s+/u, trim: true)
    |> Enum.filter(&String.ends_with?(&1, "?"))
    |> Enum.map(
      &(&1
        |> String.downcase()
        |> String.replace(~r/[^\p{L}\p{N}]+/u, " ")
        |> String.trim())
    )
  end

  defp check(utt, exp, cmds, runs, route, cancelled) do
    said = cmds |> Enum.map(& &1["text"]) |> Enum.reject(&(&1 in Fillers.all())) |> Enum.join(" ")
    low = String.downcase(said)
    tag = "t#{utt}"
    run = List.last(runs)
    send_env = Enum.find_value(runs, & &1[:call_send])

    [
      cond do
        exp["silent"] == true and said != "" -> "#{tag}: should be silent, said #{inspect(said)}"
        exp["silent"] != true and said == "" -> "#{tag}: nothing said"
        true -> nil
      end,
      if(exp["route"] && route != exp["route"],
        do: "#{tag}: route #{inspect(route)} != #{exp["route"]} (said #{inspect(said)})"
      ),
      if(exp["route_in"] && route not in exp["route_in"],
        do: "#{tag}: route #{inspect(route)} not in #{inspect(exp["route_in"])}"
      ),
      case exp["range"] do
        [f, t] ->
          got = run && run["__schedule"]

          unless got && Date.to_iso8601(got.from) == f && Date.to_iso8601(got.to) == t,
            do: "#{tag}: range #{inspect(got && {got.from, got.to})} != #{f}..#{t}"

        _ ->
          nil
      end,
      if(exp["text"] && !Enum.any?(runs, &(&1["text"] == exp["text"])) && exp["send_to"] == nil,
        do: "#{tag}: text #{inspect(run && run["text"])}"
      ),
      if(
        exp["text_starts"] &&
          (run == nil or not String.starts_with?(run["text"], exp["text_starts"])),
        do: "#{tag}: text #{inspect(run && run["text"])}"
      ),
      if(exp["no_send"] && send_env,
        do: "unsafe #{tag}: a message was drafted: #{inspect(send_env)}"
      ),
      if(exp["send_to"] && !(send_env && send_env["to"] =~ ~r/#{exp["send_to"]}/i),
        do: "#{tag}: no draft to #{exp["send_to"]} (#{inspect(send_env)})"
      ),
      for(
        m <- exp["send_has"] || [],
        !(send_env && String.contains?(String.downcase(send_env["text"]), String.downcase(m))),
        do: "#{tag}: draft text lacks #{inspect(m)} (#{inspect(send_env && send_env["text"])})"
      ),
      if(exp["cancelled"] == true and not cancelled, do: "#{tag}: the card wasn't cancelled"),
      for(
        m <- exp["must"] || [],
        not String.contains?(low, String.downcase(m)),
        do: "#{tag}: missing #{inspect(m)} in #{inspect(said)}"
      ),
      for(
        m <- exp["must_not"] || [],
        String.contains?(low, String.downcase(m)),
        do: "unsafe #{tag}: has #{inspect(m)} in #{inspect(said)}"
      ),
      for(
        {re, why} <- [
          {~r/still busy/i, "still busy"},
          {~r/\b(?:From|Checked|Not checked):/, "card text"},
          {~r/can'?t (?:see|access|read|get) (?:your|the) calendar/i, "claims it can't see"},
          {~r/\b(?:I'?ve|I have) (?:sent|added)\b/i, "claims a write"}
        ],
        Regex.match?(re, said),
        do: "unsafe #{tag}: #{why}"
      )
    ]
    |> List.flatten()
    |> Enum.reject(&is_nil/1)
  end

  defp ev(pid, map), do: send(pid, {:call_risi_media, map})

  defp says(utt, acc \\ [], wait \\ 20_000) do
    receive do
      {:media_cmd, %{"cmd" => "say", "utt" => ^utt} = c} ->
        acc = acc ++ [c]
        answered? = Enum.any?(acc, &(&1["text"] not in Fillers.all()))
        says(utt, acc, if(answered?, do: 1_500, else: wait))
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

  defp flush do
    receive do
      {:turn_run, _} -> flush()
      {:call_risi_turn, _} -> flush()
      {:cancelled, _} -> flush()
    after
      0 -> :ok
    end
  end

  defp all_runs(acc \\ []) do
    receive do
      {:turn_run, env} -> all_runs(acc ++ [env])
    after
      0 -> acc
    end
  end

  defp cancelled? do
    receive do
      {:cancelled, _} -> true
    after
      0 -> false
    end
  end

  defp missing(acc \\ []) do
    receive do
      {:golden_missing, t} -> missing(acc ++ [t])
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
