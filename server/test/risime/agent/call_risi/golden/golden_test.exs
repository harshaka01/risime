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

  @moduletag timeout: 7_200_000

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
      last = messages |> List.last() |> Map.get("content")

      cond do
        sys =~ "Rephrase the CALENDAR" ->
          on_delta.("Okay. " <> (last |> String.split("CALENDAR: ") |> List.last()))

        last =~ "ITEMS: " ->
          on_delta.("Okay. " <> (last |> String.split("ITEMS: ") |> List.last()))

        true ->
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

      case Map.get(book, RisiMe.Agent.CallRisi.GoldenTest.key_of(input)) do
        %{"decision" => d} = r ->
          Process.sleep(min(r["ms"] || 0, :persistent_term.get({__MODULE__, :cap}, 1_500)))
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

      kind = if input[:mode] == :dictation, do: :dictation, else: :turn

      if :ets.whereis(:golden_latency) != :undefined,
        do: :ets.insert(:golden_latency, {make_ref(), kind, ms})

      with {:ok, d} <- r do
        send(
          :persistent_term.get({Recorded, :test}),
          {:golden_decision, RisiMe.Agent.CallRisi.GoldenTest.key_of(input), d, ms}
        )
      end

      r
    end
  end

  defmodule Turn do
    @moduledoc false
    @friends %{
      "nimali" => "Nimali Perera",
      "ruwan" => "Ruwan Silva",
      "kamal" => "Kamal Jayasuriya",
      "kasun" => "Kasun Senaratne"
    }

    # The caller's people: the call's own `contacts` (the shared cases' list), else these four.
    def people do
      case :persistent_term.get({__MODULE__, :contacts}, nil) do
        [_ | _] = names ->
          Enum.map(names, &{"f-" <> first(&1), &1})

        _ ->
          [
            {"f-nimali", "Nimali Perera"},
            {"f-ruwan", "Ruwan Silva"},
            {"f-kamal", "Kamal Jayasuriya"},
            {"f-kasun", "Kasun Senaratne"}
          ]
      end
    end

    defp first(name), do: name |> String.downcase() |> String.split() |> hd()

    defp friend(to) do
      key = to |> String.downcase() |> String.split() |> List.first()

      case :persistent_term.get({__MODULE__, :contacts}, nil) do
        [_ | _] = names -> Enum.find(names, &(first(&1) == key))
        _ -> @friends[key]
      end
    end

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
        {:send, %{"to" => to, "text" => body} = s} ->
          case friend(to) do
            nil ->
              send(pid, {:risi_voice_answer, rid, "I couldn't find #{to} in your contacts."})

            name ->
              put_state(rid, "pending", "send_message")
              tab = if s["tab"] == "official", do: "Official", else: "Private"
              send(pid, {:risi_voice_card, rid, "Send to #{name} (#{tab}): #{body}"})
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
          write? =
            Regex.match?(~r/\b(?:add|make|book|schedule|put|block|set up|slot|remind)\b/i, text)

          if :persistent_term.get({__MODULE__, :cards}, false) or write? do
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

  # The read tools' data (a call's `messages` and `promises`; "me" = the caller).
  defmodule Reads do
    @behaviour RisiMe.Agent.CallRisi.Reads

    defp id("me"), do: :persistent_term.get({__MODULE__, :user})
    defp id(name), do: "f-" <> String.downcase(name)

    @impl true
    def messages(_user, since) do
      now = RisiMe.Agent.Clock.now()

      for {m, i} <- Enum.with_index(:persistent_term.get({__MODULE__, :messages}, [])),
          at = DateTime.add(now, -(m["mins_ago"] || 5) * 60 - i, :second),
          DateTime.compare(at, since) != :lt do
        %{
          sender_id: id(m["from"]),
          conversation_id: "grp:off-" <> String.downcase(m["chat"] || m["from"]),
          at: at,
          text: m["text"],
          from_risi: false
        }
      end
      |> Enum.sort_by(& &1.at, DateTime)
    end

    @impl true
    def commitments(_user) do
      for c <- :persistent_term.get({__MODULE__, :promises}, []) do
        %{
          owner_id: id(c["owner"]),
          counterpart_ids: Enum.map(c["to"] || [], &id/1),
          text: c["text"],
          due: c["due"] && elem(DateTime.from_iso8601(c["due"]), 1)
        }
      end
    end
  end

  # v1.55 §41.11's CallSend (the real one has its own tests): sends and undos go to the test.
  defmodule Send do
    def enabled_for?(_user), do: :persistent_term.get({__MODULE__, :on}, false)

    def target(_user, id) do
      name = String.replace_prefix(id, "f-", "")

      if name in :persistent_term.get({__MODULE__, :off}, []),
        do: {:error, :official_off},
        else: {:ok, %{conv: "grp:off-" <> name, name: name}}
    end

    def needs_tap?(text),
      do:
        Regex.match?(
          ~r/\b(?:money|pay|paid|salary|loan|transfer|rupees|fired|fire him|fire her|warning letter|lawyer|legal|court|sue|delete|stole|cheat|lied|fraud)\b/i,
          text
        )

    def send(args) do
      send(
        :persistent_term.get({RisiMe.Agent.CallRisi.GoldenTest.Turn, :test}),
        {:voice_sent, args}
      )

      :persistent_term.put({__MODULE__, :last}, args.name)
      {:ok, %{write_id: Ecto.UUID.generate(), message_id: "m-1", undo_until: DateTime.utc_now()}}
    end

    def undo(_user, _which) do
      case :persistent_term.get({__MODULE__, :last}, nil) do
        nil ->
          {:error, :none}

        name ->
          :persistent_term.erase({__MODULE__, :last})

          send(
            :persistent_term.get({RisiMe.Agent.CallRisi.GoldenTest.Turn, :test}),
            {:voice_undone, name}
          )

          {:ok, %{name: name}}
      end
    end

    def record_outcome(_user, _outcome, _ms), do: :ok
  end

  @doc false
  def key(text), do: text |> String.downcase() |> String.replace(~r/\s+/u, " ") |> String.trim()

  @doc false
  # A decision's key in a recorded book: the words; a dictation check also its earlier words.
  def key_of(%{mode: :dictation} = i) do
    tag = if i[:ended?], do: "dictation-end| ", else: "dictation| "
    tag <> key(Enum.join(i.parts, " / ") <> " >> " <> i.text)
  end

  def key_of(i), do: key(i.text)

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
      :risi_contacts,
      :risi_call_reads,
      :risi_call_send_impl,
      :risi_call_dict_pause_ms,
      :risi_call_dict_mmhm_ms,
      :risi_call_dict_silence_ms
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
    Application.put_env(:risime, :risi_call_reads, Reads)
    Application.put_env(:risime, :risi_call_send_impl, Send)
    # Dictation: a short pause check, no "Mm-hm" (its own test), silence ends after 4 s.
    Application.put_env(:risime, :risi_call_dict_pause_ms, 300)
    Application.put_env(:risime, :risi_call_dict_mmhm_ms, 60_000)
    Application.put_env(:risime, :risi_call_dict_silence_ms, 4_000)

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

    if :ets.whereis(:golden_latency) == :undefined,
      do: :ets.new(:golden_latency, [:named_table, :public, :bag])

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
    assert length(cs) >= 5 or System.get_env("GOLDEN_ONLY") not in [nil, ""]

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
    latency_report("golden calls")
    assert Enum.flat_map(results, &elem(&1, 1)) == []
  end

  ## Paraphrase variants (architect 11:45): 20 per golden task, generated by the model and
  ## reviewed, in `paraphrases/<task>.json`; their decisions recorded in
  ## `recorded/para-<task>.json` (by variant). A release needs ≥ 90% of them passing.

  @paraphrases Path.join(@dir, "paraphrases")

  defp paraphrase_tasks do
    @paraphrases
    |> File.ls!()
    |> Enum.filter(&String.ends_with?(&1, ".json"))
    |> Enum.filter(&String.contains?(&1, System.get_env("PARA_ONLY") || ""))
    |> Enum.sort()
    |> Enum.map(&(Path.join(@paraphrases, &1) |> File.read!() |> Jason.decode!()))
  end

  defp para_book_path(task), do: Path.join(@recorded, "para-#{task["task"]}.json")

  # PARA_SAMPLE=<n> (the model swap test's 35-minute window): the first n variants of each
  # tool's file, in file order, so every model gets the same ones.
  defp para_sample do
    case Integer.parse(System.get_env("PARA_SAMPLE") || "") do
      {n, ""} when n > 0 -> n
      _ -> nil
    end
  end

  defp sample(variants), do: if(n = para_sample(), do: Enum.take(variants, n), else: variants)

  defp run_paraphrases(mode) do
    # Recorded: the model's latency is not replayed in full (the variants' words don't pause).
    if mode == :recorded do
      :persistent_term.put({Recorded, :cap}, 40)
      :persistent_term.put({__MODULE__, :tail}, 450)
    end

    results =
      for task <- paraphrase_tasks() do
        books =
          case File.read(para_book_path(task)) do
            {:ok, s} -> Jason.decode!(s)
            _ -> %{}
          end

        runs =
          for v <- sample(task["variants"]) do
            if mode == :recorded,
              do: :persistent_term.put({Recorded, :book}, Map.get(books, v["golden_id"], %{}))

            errs = run_call(v)

            book =
              if mode == :live,
                do:
                  collect_decisions(%{})
                  |> Map.new(fn {k, {d, ms}} -> {k, %{"decision" => d, "ms" => ms}} end)

            {v["golden_id"], errs, book}
          end

        if mode == :live and System.get_env("GOLDEN_RECORD") in ["1", "true"] do
          # A sampled run (PARA_SAMPLE) keeps the other variants' recorded decisions.
          books = Map.merge(books, Map.new(runs, fn {id, _e, b} -> {id, b} end))

          File.write!(
            para_book_path(task),
            Jason.encode_to_iodata!(books, pretty: true)
            |> IO.iodata_to_binary()
            |> Kernel.<>("\n")
          )
        end

        {task["task"], Enum.map(runs, fn {id, e, _} -> {id, e} end)}
      end

    all = Enum.flat_map(results, &elem(&1, 1))
    passed = Enum.count(all, fn {_id, e} -> e == [] end)

    :persistent_term.erase({Recorded, :cap})
    :persistent_term.erase({__MODULE__, :tail})

    IO.puts(
      "\nPARAPHRASES (#{mode} #{Decider.model()}): #{passed}/#{length(all)} variants passed (per tool below)"
    )

    sampled = if para_sample(), do: " (sampled #{para_sample()}/20)", else: ""

    for {task, runs} <- results do
      ok = Enum.count(runs, fn {_, e} -> e == [] end)
      IO.puts("  #{task}: #{ok}/#{length(runs)}#{sampled}")
      for {id, e} <- runs, x <- e, do: IO.puts("    FAIL #{id} #{x}")
    end

    {passed, length(all)}
  end

  test "paraphrase variants (recorded decisions): ≥ 95% pass" do
    Application.put_env(:risime, :risi_call_decider, Recorded)
    {passed, total} = run_paraphrases(:recorded)
    assert total >= 60
    assert passed / total >= 0.95
  end

  @tag :llm_live
  test "paraphrase variants (live decision model): ≥ 95% pass" do
    Application.put_env(:risime, :risi_call_decider, Live)

    Application.put_env(:risime, :risi_llm,
      url: "http://127.0.0.1:8100/v1",
      api_key: System.get_env("LLM_API_KEY"),
      fallback: false
    )

    {passed, total} = run_paraphrases(:live)
    latency_report("paraphrases")
    assert passed / total >= 0.95
  end

  ## Shared cases with RisiWork (eval/shared/risime-cases.md; `shared/risime-cases.json`): 30 cases,
  ## intent / contact / dictation, each a short call whose LAST turn is scored. Recorded decisions in
  ## `recorded/shared-cases.json` (by case). The file's note says how the shared tool names map here.

  @shared Path.join(@dir, "shared/risime-cases.json")
  @shared_book Path.join(@recorded, "shared-cases.json")

  defp run_shared(mode) do
    data = @shared |> File.read!() |> Jason.decode!()
    top = Map.take(data, ~w(contacts now))

    books =
      case File.read(@shared_book) do
        {:ok, s} -> Jason.decode!(s)
        _ -> %{}
      end

    runs =
      for v <- data["variants"] do
        if mode == :recorded,
          do: :persistent_term.put({Recorded, :book}, Map.get(books, v["golden_id"], %{}))

        errs = run_call(Map.merge(top, v))

        book =
          if mode == :live,
            do:
              collect_decisions(%{})
              |> Map.new(fn {k, {d, ms}} -> {k, %{"decision" => d, "ms" => ms}} end)

        {v, errs, book}
      end

    if mode == :live and System.get_env("GOLDEN_RECORD") in ["1", "true"] do
      File.write!(
        @shared_book,
        Jason.encode_to_iodata!(Map.new(runs, fn {v, _, b} -> {v["golden_id"], b} end),
          pretty: true
        )
        |> IO.iodata_to_binary()
        |> Kernel.<>("\n")
      )
    end

    by = Enum.group_by(runs, fn {v, _, _} -> v["group"] end)

    line =
      for g <- ~w(intent contact dictation) do
        rs = Map.get(by, g, [])
        "#{g} #{Enum.count(rs, fn {_, e, _} -> e == [] end)}/#{length(rs)}"
      end

    IO.puts("\nSHARED CASES (#{mode} #{Decider.model()}): " <> Enum.join(line, ", "))
    for {v, e, _} <- runs, x <- e, do: IO.puts("  FAIL #{v["golden_id"]} #{x}")
    passed = Enum.count(runs, fn {_, e, _} -> e == [] end)
    {passed, length(runs)}
  end

  test "shared cases with RisiWork (recorded decisions)" do
    Application.put_env(:risime, :risi_call_decider, Recorded)
    :persistent_term.put({Recorded, :cap}, 40)
    :persistent_term.put({__MODULE__, :tail}, 450)
    {passed, total} = run_shared(:recorded)
    :persistent_term.erase({Recorded, :cap})
    :persistent_term.erase({__MODULE__, :tail})
    assert total == 30
    assert passed / total >= 0.8
  end

  @tag :llm_live
  test "shared cases with RisiWork (live decision model)" do
    Application.put_env(:risime, :risi_call_decider, Live)

    Application.put_env(:risime, :risi_llm,
      url: "http://127.0.0.1:8100/v1",
      api_key: System.get_env("LLM_API_KEY"),
      fallback: false
    )

    {passed, total} = run_shared(:live)
    latency_report("shared cases")
    assert passed / total >= 0.8
  end

  ## Simulated callers (architect 11:45): risi-l1 plays Harsha with a goal; 30 runs, scored on the
  ## goal reached within 4 turns and no question asked twice in a row. Gate: ≥ 90% and 0 repeats.

  @sim_messages [
    {"Nimali", "you will call her back in ten minutes", ["ten minutes", "10 minutes"]},
    {"Ruwan", "the car is ready for pickup", ["car"]},
    {"Kamal", "the report is finished", ["report"]},
    {"Nimali",
     "you called her a couple of times and she didn't answer, so she should call you back",
     ["call"]},
    {"Ruwan", "you'll be home by seven", ["seven", "7"]},
    {"Nimali", "dinner is at eight tonight", ["dinner"]},
    {"Kamal", "thanks for the help yesterday", ["thank"]},
    {"Nimali", "the documents are on her desk", ["document"]},
    {"Ruwan", "he should bring the keys", ["key"]},
    {"Kamal", "the meeting moved to three", ["three", "3"]}
  ]

  # How Whisper mishears the names (the caller's own words are not changed otherwise).
  @misheard %{
    "Nimali" => ["Nima Lee", "Nimaali", "Nee Mali", "Nimali"],
    "Ruwan" => ["Ruvan", "Roo Wan", "Ruwan"],
    "Kamal" => ["Kamel", "Kamal"]
  }

  defp sim_goals do
    # Half the callers give it in pieces (who, then what), as Harsha did on call 7.
    msgs =
      for {{to, what, keys}, i} <- Enum.with_index(Enum.take(@sim_messages, 4)),
          do: %{
            kind: :send,
            to: to,
            keys: keys,
            goal:
              "Get Risi to draft a message to your friend #{to} saying that #{what}. " <>
                if(rem(i, 2) == 1,
                  do:
                    "First only ask Risi to send a message, without saying to whom or what; " <>
                      "give the name, then the words, only when Risi asks. ",
                  else: ""
                ) <>
                "Done when Risi says the message is on a card for you to tap Send."
          }

    # v1.55 §41.11: on the work chat, Risi reads it back and sends on a yes.
    official =
      for {to, what, keys} <- [
            {"Ruwan", "the car is ready for pickup", ["car"]},
            {"Kamal", "the meeting moved to three", ["three", "3"]}
          ],
          do: %{
            kind: :official,
            to: to,
            keys: keys,
            goal:
              "Get Risi to send your colleague #{to} a message on the work chat saying that " <>
                "#{what}. When Risi reads it back and asks, say yes. Done when Risi says Sent."
          }

    # Architect 15:35 B: reads of his Official messages and of the ledger.
    reads =
      for {goal, keys} <- [
            {"Find out whether Nimali has replied to your message.", ["around six"]},
            {"Ask Risi casually if you have any new messages.", ["nimali", "ruwan", "kamal"]},
            {"Find out what Ruwan said in his last message.", ["car"]},
            {"Ask whether Kamal wrote back to you about the report.", ["report"]},
            {"Start with a greeting, then ask if anyone messaged you today.",
             ["nimali", "ruwan", "kamal"]},
            {"Ask Risi to read you Nimali's latest message.", ["around six"]}
          ],
          do: %{kind: :read, keys: keys, goal: goal <> " Done when Risi tells you."}

    promises =
      for {goal, keys} <- [
            {"Find out whether Kamal has followed up on the computer delivery he promised.",
             ["computer"]},
            {"Ask whether Kamal promised you anything.", ["computer"]},
            {"Ask what you yourself still have to do for Ruwan.", ["quote"]},
            {"Ask Risi what your open promises are.", ["quote"]},
            {"Ask in very few words about promises other people made to you, naming Kamal.",
             ["computer"]},
            {"Ask indirectly whether you owe Ruwan anything.", ["quote"]}
          ],
          do: %{kind: :promise, keys: keys, goal: goal <> " Done when Risi tells you."}

    cal =
      for style <- [
            "Ask casually",
            "Ask indirectly",
            "Start with a greeting, then ask",
            "Ask in very few words",
            "Ask whether you are free before your next meeting",
            "Ask what is next on your schedule"
          ],
          do: %{
            kind: :cal,
            goal:
              "Find out what time your next meeting is today. #{style}. Done when Risi tells you."
          }

    redo =
      for {to, first, second} <-
            Enum.take(
              [
                {"Nimali", "good night", "good morning"},
                {"Ruwan", "I'm on my way", "I'll be there in five minutes"},
                {"Kamal", "lunch at one", "lunch at two"},
                {"Nimali", "see you tomorrow", "see you on Monday"},
                {"Ruwan", "the shop is closed", "the shop opens at ten"},
                {"Kamal", "I'm sick today", "I'll be in late today"},
                {"Nimali", "happy birthday", "happy birthday, see you tonight"},
                {"Ruwan", "call me", "call me after six"},
                {"Kamal", "the printer is fixed", "the printer is still broken"},
                {"Nimali", "the train is late", "the train is cancelled"}
              ],
              6
            ),
          do: %{
            kind: :redo,
            to: to,
            keys: [second |> String.split() |> List.last() |> String.trim(".")],
            goal:
              "Get Risi to draft a message to #{to} saying \"#{first}\". As soon as Risi has " <>
                "drafted it, cancel it and ask for \"#{second}\" instead (in one or two turns). " <>
                "Done when the new message is on a card."
          }

    # Round-robin over the kinds, in a fixed order: SIM_RUNS=<n> takes the first n and still
    # covers send, Official send, read, promises, calendar and corrections.
    [msgs, official, reads, promises, cal, redo]
    |> Enum.map(&Enum.with_index/1)
    |> Enum.concat()
    |> Enum.sort_by(fn {g, i} ->
      {i, Enum.find_index(~w(send official read promise cal redo)a, &(&1 == g.kind))}
    end)
    |> Enum.map(&elem(&1, 0))
    |> then(fn gs ->
      case Integer.parse(System.get_env("SIM_RUNS") || "") do
        {n, ""} when n > 0 -> Enum.take(gs, n)
        _ -> gs
      end
    end)
  end

  @sim_data_messages [
    %{
      "from" => "me",
      "chat" => "nimali",
      "mins_ago" => 40,
      "text" => "What time are you going home?"
    },
    %{
      "from" => "Nimali",
      "chat" => "nimali",
      "mins_ago" => 10,
      "text" => "Around six. Do you need anything?"
    },
    %{
      "from" => "Ruwan",
      "chat" => "ruwan",
      "mins_ago" => 90,
      "text" => "The car is ready for pickup."
    },
    %{"from" => "me", "chat" => "kamal", "mins_ago" => 200, "text" => "Is the report done?"},
    %{
      "from" => "Kamal",
      "chat" => "kamal",
      "mins_ago" => 60,
      "text" => "Yes, the report is done and on your desk."
    }
  ]

  @sim_data_promises [
    %{
      "owner" => "Kamal",
      "to" => ["me"],
      "text" => "Deliver the new computer",
      "due" => "2026-10-17T05:30:00Z"
    },
    %{
      "owner" => "me",
      "to" => ["Ruwan"],
      "text" => "Send the quote to Lotusbay",
      "due" => "2026-10-20T11:30:00Z"
    }
  ]

  @sim_schema %{
    "type" => "object",
    "properties" => %{"say" => %{"type" => "string"}, "done" => %{"type" => "boolean"}},
    "required" => ~w(say done),
    "additionalProperties" => false
  }

  @sim_system """
  You play Harsha, a busy manager in Sri Lanka, on a phone call with Risi, his voice assistant. \
  You have one goal. Say your next words to Risi: short, natural spoken English as on a phone, \
  sometimes indirect ("tell her that…", "can you message…"), not always everything at once. \
  Never say you are an AI or a test. If Risi asks a question, answer it. If Risi did what you \
  wanted, set "done" to true and "say" to "". Return JSON {"say": "...", "done": false}.
  """

  defp sim_say(goal, transcript, n, i) do
    user =
      "Your goal: #{goal.goal}\n\nThe call so far:\n" <>
        Enum.map_join(transcript, "\n", fn {who, t} -> "#{who}: #{t}" end) <>
        "\n\nYour next words (turn #{n} of at most 4):"

    req = %{
      task: "sim_caller",
      conversation_id: "sim",
      chat_id: "sim",
      system: @sim_system,
      user: user,
      schema_name: "sim_caller",
      schema: @sim_schema,
      source_message_ids: [],
      model_alias: Decider.model(),
      max_tokens: 120,
      temperature: 0.9,
      # A fixed seed per caller and turn: every model meets the same callers.
      seed: 1_000 + i * 10 + n,
      timeout_ms: 15_000,
      retries: 1,
      invalid_output: :return
    }

    case RisiMe.Agent.LLM.complete(req) do
      {:ok, %{output: %{"say" => s} = o}} -> {String.trim(s), o["done"] == true}
      {:error, {:invalid_output, %{"say" => s} = o, _}} -> {String.trim(s), o["done"] == true}
      _ -> {"", true}
    end
  end

  defp mishear(text, n) do
    Enum.reduce(@misheard, text, fn {name, alts}, acc ->
      String.replace(acc, name, Enum.at(alts, rem(n, length(alts))))
    end)
  end

  defp run_sim(goal, i) do
    me = self()
    user = Ecto.UUID.generate()
    :persistent_term.put({Reads, :user}, user)
    :persistent_term.put({Reads, :messages}, @sim_data_messages)
    :persistent_term.put({Reads, :promises}, @sim_data_promises)
    :persistent_term.put({Turn, :contacts}, nil)
    :persistent_term.put({Send, :on}, true)
    :persistent_term.put({Send, :off}, [])
    :persistent_term.erase({Send, :last})
    :persistent_term.put({Turn, :extra}, [])
    :persistent_term.put({Turn, :cards}, false)
    Application.put_env(:risime, :risi_now, "2026-10-16T03:55:00Z")

    args = %{
      conv: "grp:risi-chat",
      call_id: Ecto.UUID.generate(),
      user_id: user,
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

    st = %{
      transcript: [{"Risi", "Hi, it's Risi. How can I help?"}],
      prev_qs: [],
      repeats: [],
      reached: false,
      cancelled: false,
      sends: [],
      voice: []
    }

    st =
      Enum.reduce_while(1..4, st, fn utt, st ->
        {say, done?} = sim_say(goal, st.transcript, utt, i)

        if done? or say == "" do
          {:halt, st}
        else
          heard_words = mishear(say, i + utt)
          flush()

          ev(pid, %{
            "ev" => "utterance",
            "utt" => utt,
            "pcm16k" => Base.encode64("en:" <> heard_words),
            "speech_ms" => 900,
            "eot_wait_ms" => 450
          })

          cmds = says(utt)
          runs = all_runs()
          cancelled = cancelled?()
          voice = for {:sent, n, t} <- voice_sends(), do: %{"to" => n, "text" => t}
          heard(pid, cmds)
          route = receive_route(utt)

          said =
            cmds
            |> Enum.map(& &1["text"])
            |> Enum.reject(&(&1 in Fillers.all()))
            |> Enum.join(" ")

          qs = questions(cmds)
          reps = for q <- qs, q in st.prev_qs, do: q
          sends = st.sends ++ for(r <- runs, s = r[:call_send], do: s)

          st = %{
            st
            | transcript: st.transcript ++ [{"Harsha", heard_words}, {"Risi", said}],
              prev_qs: qs,
              repeats: st.repeats ++ reps,
              cancelled: st.cancelled or cancelled,
              sends: sends,
              voice: st.voice ++ voice
          }

          reached = sim_reached?(goal, st, route, said)
          st = %{st | reached: reached}
          if reached, do: {:halt, st}, else: {:cont, st}
        end
      end)

    ev(pid, %{"ev" => "caller_left"})
    receive do: ({:posted, _} -> :ok), after: (2_000 -> :ok)
    end_session(pid)
    _ = missing()
    _ = collect_decisions(%{})
    st
  end

  defp sim_reached?(%{kind: :send, to: to, keys: keys}, st, _route, _said),
    do: Enum.any?(st.sends, &sim_send?(&1, to, keys))

  defp sim_reached?(%{kind: :official, to: to, keys: keys}, st, _route, said),
    do: said =~ "Sent." and Enum.any?(st.voice, &sim_send?(&1, to, keys))

  defp sim_reached?(%{kind: :read, keys: keys}, _st, route, said),
    do: route == "messages_read" and Enum.any?(keys, &(String.downcase(said) =~ &1))

  defp sim_reached?(%{kind: :promise, keys: keys}, _st, route, said),
    do:
      route in ["ledger_items", "calendar_read"] and
        Enum.any?(keys, &(String.downcase(said) =~ &1))

  defp sim_reached?(%{kind: :cal}, _st, route, said),
    do: route == "calendar_read" and said =~ "10"

  defp sim_reached?(%{kind: :redo, to: to, keys: keys}, st, _route, _said),
    do: length(st.sends) >= 2 and sim_send?(List.last(st.sends), to, keys)

  defp sim_send?(%{"to" => t, "text" => x}, to, keys),
    do:
      t =~ ~r/#{to}/i and
        Enum.any?(keys, &String.contains?(String.downcase(x), String.downcase(&1)))

  defp sim_send?(_, _, _), do: false

  @tag :llm_live
  test "simulated callers (live): ≥ 90% goals within 4 turns, 0 repeated questions" do
    Application.put_env(:risime, :risi_call_decider, Live)

    Application.put_env(:risime, :risi_llm,
      url: "http://127.0.0.1:8100/v1",
      api_key: System.get_env("LLM_API_KEY"),
      fallback: false
    )

    goals = sim_goals()
    results = for {g, i} <- Enum.with_index(goals), do: {g, run_sim(g, i)}
    latency_report("simulated callers")
    reached = Enum.count(results, fn {_g, st} -> st.reached end)
    repeats = results |> Enum.flat_map(fn {_g, st} -> st.repeats end)

    IO.puts(
      "\nSIMULATED CALLERS (live #{Decider.model()}): #{reached}/#{length(results)} goals reached " <>
        "within 4 turns, #{length(repeats)} repeated questions"
    )

    for {g, st} <- results, not st.reached or st.repeats != [] do
      IO.puts("  FAIL #{g.kind}: #{g.goal}")
      for {who, t} <- st.transcript, do: IO.puts("     #{who}: #{t}")
      for q <- st.repeats, do: IO.puts("     REPEATED: #{q}")
    end

    if path = System.get_env("SIM_OUT") do
      File.write!(
        path,
        Jason.encode!(
          for {g, st} <- results,
              do: %{
                kind: g.kind,
                goal: g.goal,
                reached: st.reached,
                repeats: st.repeats,
                transcript: Enum.map(st.transcript, fn {w, t} -> [w, t] end)
              }
        )
      )
    end

    assert length(results) == length(goals)
    assert reached / length(results) >= 0.9
    assert repeats == []
  end

  @doc false
  # The live model's decision latency since the last report (a decision is one non-streamed
  # JSON call, so its first token and its end are the same wait for the turn).
  def latency_report(label) do
    rows =
      if :ets.whereis(:golden_latency) != :undefined, do: :ets.tab2list(:golden_latency), else: []

    if rows != [], do: :ets.delete_all_objects(:golden_latency)

    pct = fn xs, q ->
      xs = Enum.sort(xs)
      if xs == [], do: "-", else: Enum.at(xs, min(length(xs) - 1, round(q * (length(xs) - 1))))
    end

    line =
      for k <- [:turn, :dictation] do
        xs = for {_, ^k, ms} <- rows, do: ms
        "#{k} p50 #{pct.(xs, 0.5)} ms, p95 #{pct.(xs, 0.95)} ms (n=#{length(xs)})"
      end

    IO.puts("DECISION LATENCY (#{label}, live #{Decider.model()}): " <> Enum.join(line, "; "))
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
      {:golden_decision, k, d, ms} -> collect_decisions(Map.put(acc, k, {d, ms}))
    after
      0 -> acc
    end
  end

  ## One golden call

  defp run_call(c) do
    me = self()
    :persistent_term.put({Turn, :contacts}, c["contacts"])
    :persistent_term.put({Reads, :messages}, c["messages"] || [])
    :persistent_term.put({Reads, :promises}, c["promises"] || [])
    :persistent_term.put({Send, :on}, c["voice_send"] == true)
    :persistent_term.put({Send, :off}, c["official_off"] || [])
    :persistent_term.erase({Send, :last})
    user = Ecto.UUID.generate()
    :persistent_term.put({Reads, :user}, user)
    :persistent_term.put({Turn, :extra}, c["extra_events"] || [])
    :persistent_term.put({Turn, :cards}, c["cards"] == true)
    Application.put_env(:risime, :risi_now, c["now"] || "2026-10-15T07:11:00Z")

    args = %{
      conv: "grp:risi-chat",
      call_id: Ecto.UUID.generate(),
      user_id: user,
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
        late = late_says(utt)

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

        if t["expect"]["silent"] == true do
          s0 = :persistent_term.get({__MODULE__, :silent}, MapSet.new())
          :persistent_term.put({__MODULE__, :silent}, MapSet.put(s0, utt))
        end

        case t["expect"] do
          nil ->
            {[], []}

          exp ->
            cmds = if exp["silent"], do: says(utt, [], 2_000), else: says(utt)
            runs = all_runs()
            cancelled = cancelled?()
            sent = voice_sends()
            heard(pid, cmds)
            # A silent turn has no turn row to wait for (dictation: its next words come soon).
            route = receive_route(utt, if(exp["silent"], do: 100, else: 2_000))
            qs = questions(cmds)

            # Architect 11:45: never the same question twice in a row.
            rep =
              for q <- qs, q in prev_qs, do: "unsafe t#{utt}: repeated question #{inspect(q)}"

            filler =
              for c <- cmds,
                  exp["no_filler"] == true and c["text"] in Fillers.all(),
                  do: "t#{utt}: a holding line before a question: #{inspect(c["text"])}"

            {late ++
               check(utt, exp, cmds, runs, route, cancelled) ++
               check_send(utt, exp, sent) ++ rep ++ filler, qs}
        end
      end)

    ev(pid, %{"ev" => "caller_left"})
    receive do: ({:posted, _} -> :ok), after: (2_000 -> :ok)
    end_session(pid)
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
    # A message read back is no question of Risi's ("How are you?" inside the quote), but its
    # words tell two read-backs apart.
    |> String.replace(~r/"[^"]*"\.?/u, &String.replace(&1, ~r/[.!?]/u, ""))
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
      for(
        m <- exp["send_has_not"] || [],
        send_env && String.contains?(String.downcase(send_env["text"]), String.downcase(m)),
        do: "#{tag}: draft text has #{inspect(m)} (#{inspect(send_env["text"])})"
      ),
      if(exp["send_tab"] && !(send_env && send_env["tab"] == exp["send_tab"]),
        do: "#{tag}: draft tab #{inspect(send_env && send_env["tab"])} != #{exp["send_tab"]}"
      ),
      if(
        exp["must_any"] &&
          !Enum.any?(exp["must_any"], &String.contains?(low, String.downcase(&1))),
        do: "#{tag}: none of #{inspect(exp["must_any"])} in #{inspect(said)}"
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

        says(
          utt,
          acc,
          if(answered?, do: :persistent_term.get({__MODULE__, :tail}, 1_500), else: wait)
        )
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
      {:voice_sent, _} -> flush()
      {:voice_undone, _} -> flush()
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

  # Risi's words for an earlier utterance that came after that turn's wait: a turn expected to be
  # silent that spoke late is a failure ("t7: said late …"); nothing stale reaches the next turn.
  defp late_says(utt, acc \\ []) do
    receive do
      {:media_cmd, %{"cmd" => "say", "utt" => u, "text" => t}} when u < utt ->
        late_says(utt, if(t in Fillers.all(), do: acc, else: acc ++ [{u, t}]))
    after
      0 ->
        silent = :persistent_term.get({__MODULE__, :silent}, MapSet.new())

        for {u, t} <- acc,
            MapSet.member?(silent, u),
            do: "unsafe t#{u}: should be silent, said late #{inspect(t)}"
    end
  end

  # The call's session is gone (its late words and timers never reach the next call).
  defp end_session(pid) do
    ref = Process.monitor(pid)
    receive do: ({:DOWN, ^ref, _, _, _} -> :ok), after: (3_000 -> Process.exit(pid, :kill))
    Process.sleep(200)
    drain()
    :persistent_term.put({__MODULE__, :silent}, MapSet.new())
  end

  defp drain do
    receive do
      {:media_cmd, _} -> drain()
      {:turn_run, _} -> drain()
      {:call_risi_turn, _} -> drain()
      {:voice_sent, _} -> drain()
      {:voice_undone, _} -> drain()
      {:cancelled, _} -> drain()
    after
      0 -> :ok
    end
  end

  defp voice_sends(acc \\ []) do
    receive do
      {:voice_sent, a} -> voice_sends(acc ++ [{:sent, a.name, a.text}])
      {:voice_undone, n} -> voice_sends(acc ++ [{:undone, n, nil}])
    after
      0 -> acc
    end
  end

  # v1.55 §41.11: a voice send only after the read-back's yes; "Sent." only with it.
  defp check_send(utt, exp, sent) do
    tag = "t#{utt}"
    sends = for {:sent, n, t} <- sent, do: {n, t}
    undos = for {:undone, n, _} <- sent, do: n

    [
      if(exp["voice_sent"] && !Enum.any?(sends, fn {n, _} -> n =~ ~r/#{exp["voice_sent"]}/i end),
        do: "#{tag}: nothing sent to #{exp["voice_sent"]} (#{inspect(sends)})"
      ),
      if(exp["voice_sent"] == nil and sends != [],
        do: "unsafe #{tag}: sent without a yes to the read-back: #{inspect(sends)}"
      ),
      if(exp["undone"] && undos == [], do: "#{tag}: nothing undone")
    ]
    |> Enum.reject(&is_nil/1)
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

  defp receive_route(utt, wait \\ 2_000) do
    receive do
      {:call_risi_turn, %{utt: ^utt, route: r}} -> r
    after
      wait -> nil
    end
  end
end
