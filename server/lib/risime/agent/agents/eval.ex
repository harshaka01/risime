defmodule RisiMe.Agents.Eval do
  @moduledoc """
  The per-agent golden-set harness (decision 084, PART 6 step 1). Golden sets are hand-checked
  JSONL files (`server/test/risime/agent/golden/<set>.jsonl`; English, Sinhala, Tamil and
  Singlish), seeded from `contract/v1/risi_routing_cases.json`, `docs/status/risi.md`'s test
  phrases and the model eval set (`eval/risi/`), plus new cases.

  * `router` — the Fast router: rules (always), and the model on every case with `live: true`.
  * `ledger` — `commitment_extract` with today's prompt and schema (`RisiMe.Agent.Prompts`):
    owners, counterparts, dues and the task; **hallucination checks**: an owner who isn't a
    member, a due date where the chat gives none, or a name/amount/date in the text that the
    chat doesn't contain.
  * `summary` — the chat `summary` with today's prompt and schema: required points present,
    nothing forbidden, no invented name/amount/date.

  Without `live: true` the model is whatever `RisiMe.Agent.LLM` is configured with — in tests
  the fake model (`RisiMe.RisiHelpers.fake_llm!/1`) answering each case's oracle (`fake`, or the
  expected items) or its **trap** (a deliberate hallucination the checker must catch). That
  keeps the harness and the checker honest in every `mix test`; the real numbers come from
  `scripts/risi-eval --live` (risi-l1, sequential, light).

  Results: `%{agent, mode, n, ok, accuracy, halluc, halluc_caught, p50_ms, p95_ms, by_lang,
  failed: [case ids]}`. No chat text leaves the harness except the golden data itself.
  """

  alias RisiMe.Agent.{LLM, Prompts}
  alias RisiMe.Agents.{Router, Runs}

  @now ~U[2026-10-11 04:30:00Z]
  @today ~D[2026-10-11]
  @tz "Asia/Colombo"

  @doc "The golden sets."
  def sets, do: ~w(router ledger summary)

  @doc "Loads a golden set from `dir`."
  def load(dir, set) do
    Path.join(dir, set <> ".jsonl")
    |> File.stream!()
    |> Stream.map(&String.trim/1)
    |> Stream.reject(&(&1 == ""))
    |> Enum.map(&Jason.decode!/1)
  end

  @doc """
  Runs one set. Options: `live: bool` (default false), `trap: bool` (fake mode: answer the
  case's trap instead of its oracle where it has one).
  """
  def run(set, cases, opts \\ [])

  def run("router", cases, opts) do
    live = Keyword.get(opts, :live, false)

    results =
      for c <- cases do
        t0 = System.monotonic_time(:microsecond)
        rules = Router.rules(c["text"], @today)
        rules_ms = (System.monotonic_time(:microsecond) - t0) / 1000

        model =
          if live do
            t1 = System.monotonic_time(:millisecond)
            m = model_route(c)
            {m, System.monotonic_time(:millisecond) - t1}
          end

        %{
          id: c["id"],
          lang: c["lang"],
          ok: rules.route == c["route"],
          got: rules.route,
          ms: rules_ms,
          model_ok: model && elem(model, 0) == c["route"],
          model_got: model && elem(model, 0),
          model_ms: model && elem(model, 1),
          halluc: 0
        }
      end

    summary("router", results, live)
    |> Map.put(
      :model,
      if live do
        oks = Enum.count(results, & &1.model_ok)

        %{
          accuracy: oks / max(length(results), 1),
          p50_ms: Runs.percentile(Enum.map(results, & &1.model_ms), 0.5),
          p95_ms: Runs.percentile(Enum.map(results, & &1.model_ms), 0.95),
          failed: for(r <- results, !r.model_ok, do: r.id)
        }
      end
    )
  end

  def run("ledger", cases, opts) do
    results = for c <- cases, do: ledger_case(c, opts)
    summary("ledger", results, Keyword.get(opts, :live, false))
  end

  def run("summary", cases, opts) do
    results = for c <- cases, do: summary_case(c, opts)
    summary("summary", results, Keyword.get(opts, :live, false))
  end

  @doc "Runs every set in `dir`: `%{set => result}`."
  def run_all(dir, opts \\ []) do
    Map.new(sets(), fn s -> {s, run(s, load(dir, s), opts)} end)
  end

  ## Router (live): the model on every case

  defp model_route(c) do
    req = %{
      task: "route_eval",
      conversation_id: "eval",
      chat_id: "eval",
      system: Router.prompt(),
      user: "Request (#{c["lang"]}): " <> c["text"],
      schema_name: "risi_route",
      schema: Router.schema(),
      source_message_ids: [],
      max_tokens: 40,
      timeout_ms: 5_000,
      retries: 0
    }

    case LLM.complete(req) do
      {:ok, %{output: %{"route" => r}}} -> r
      _ -> "error"
    end
  end

  ## Ledger

  defp ledger_case(c, opts) do
    {text, refs, names} = render(c)
    Process.put(:risi_eval_case, {c, refs, Keyword.get(opts, :trap, false)})

    req = %{
      task: "commitment_extract",
      conversation_id: "eval",
      chat_id: "eval",
      system: Prompts.extract_system(),
      user: text,
      schema_name: "commitments",
      schema: Prompts.extract_schema(),
      source_message_ids: []
    }

    t0 = System.monotonic_time(:millisecond)
    out = LLM.complete(req)
    ms = System.monotonic_time(:millisecond) - t0

    case out do
      {:ok, %{output: %{"commitments" => items}}} ->
        got = Enum.map(items, &named(&1, names))
        source = c["messages"] |> Enum.map(&Enum.at(&1, 1)) |> Enum.join("\n")
        no_due? = Enum.all?(c["expect"], &is_nil(&1["due"]))

        halluc =
          Enum.count(got, fn g ->
            g.owner == nil or Enum.any?(g.to, &is_nil/1) or
              (no_due? and g.due != nil and c["expect"] != []) or
              invented(source <> "\n" <> Enum.join(c["members"], " "), g.text) != []
          end)

        matched = match_items(c["expect"], got)
        ok = matched == length(c["expect"]) and length(got) == length(c["expect"]) and halluc == 0

        %{id: c["id"], lang: c["lang"], ok: ok, halluc: halluc, ms: ms, trap: c["trap"] != nil}

      _ ->
        %{id: c["id"], lang: c["lang"], ok: false, halluc: 0, ms: ms, error: true, trap: false}
    end
  end

  defp named(item, names) do
    %{
      owner: Map.get(names, item["owner"]),
      to: Enum.map(item["counterparts"] || [], &Map.get(names, &1)),
      due: item["due_local"] && String.slice(item["due_local"], 0, 10),
      text: item["text"] || ""
    }
  end

  defp match_items(expect, got) do
    {n, _} =
      Enum.reduce(expect, {0, got}, fn e, {n, left} ->
        case Enum.find(left, &item_match?(e, &1)) do
          nil -> {n, left}
          g -> {n + 1, List.delete(left, g)}
        end
      end)

    n
  end

  defp item_match?(e, g) do
    text = String.downcase(g.text)

    g.owner == e["owner"] and Enum.sort(g.to) == Enum.sort(e["to"]) and g.due == e["due"] and
      Enum.any?(e["kw"], &String.contains?(text, String.downcase(&1)))
  end

  @doc """
  The fake model's oracle for the current case (tests): the expected items as the model would
  write them (refs), or the case's trap.
  """
  def fake_reply("commitments", _body) do
    {c, refs, trap?} = Process.get(:risi_eval_case)
    by_name = Map.new(refs.users, fn {ref, id} -> {id, ref} end)
    ref = fn name -> Map.fetch!(by_name, "user:" <> name) end

    items =
      for {e, i} <- Enum.with_index(c["expect"]) do
        trap = if trap? && i == 0, do: c["trap"]

        %{
          "text" => (trap && trap["text"]) || Enum.join(e["kw"] |> Enum.take(1), " "),
          "owner" => ref.(e["owner"]),
          "counterparts" => Enum.map(e["to"], ref),
          "due_local" => if(trap, do: trap["due_local"], else: e["due"]),
          "due_text" => nil,
          "source" => ["m1"],
          "confidence" => 0.9
        }
      end

    %{"commitments" => items}
  end

  def fake_reply("summary", _body) do
    {c, _refs, trap?} = Process.get(:risi_eval_case)
    if trap? && c["trap"], do: c["trap"], else: c["fake"]
  end

  def fake_reply(_, _), do: {:status, 500}

  ## Summary

  defp summary_case(c, opts) do
    {text, refs, _names} = render(c)
    Process.put(:risi_eval_case, {c, refs, Keyword.get(opts, :trap, false)})

    req = %{
      task: "summarise",
      conversation_id: "eval",
      chat_id: "eval",
      system: Prompts.summary_system(),
      user: text,
      schema_name: "summary",
      schema: Prompts.summary_schema(),
      source_message_ids: []
    }

    t0 = System.monotonic_time(:millisecond)
    out = LLM.complete(req)
    ms = System.monotonic_time(:millisecond) - t0

    case out do
      {:ok, %{output: o}} ->
        all =
          [o["summary"] | o["decisions"] ++ o["action_items"] ++ o["open_questions"]]
          |> Enum.join("\n")

        low = String.downcase(all)
        source = c["messages"] |> Enum.map(&Enum.at(&1, 1)) |> Enum.join("\n")

        must =
          Enum.all?(c["must"] || [], fn any ->
            Enum.any?(any, &String.contains?(low, String.downcase(&1)))
          end)

        forbid = Enum.any?(c["forbid"] || [], &String.contains?(low, String.downcase(&1)))
        inv = invented(source <> "\n" <> Enum.join(c["members"], " "), all)
        halluc = length(inv) + if(forbid, do: 1, else: 0)

        %{
          id: c["id"],
          lang: c["lang"],
          ok: must and halluc == 0,
          halluc: halluc,
          ms: ms,
          trap: c["trap"] != nil
        }

      _ ->
        %{id: c["id"], lang: c["lang"], ok: false, halluc: 0, ms: ms, error: true, trap: false}
    end
  end

  ## Rendering a golden chat with today's renderer

  defp render(c) do
    members = for n <- c["members"], do: %{user_id: "user:" <> n, name: n}
    k = length(c["messages"])

    msgs =
      for {[from, text, new?], i} <- Enum.with_index(c["messages"]) do
        %{
          message_id: RisiMe.TimeUUID.at(DateTime.add(@now, (i - k) * 60, :second)),
          sender_id: "user:" <> from,
          text: text,
          new?: new?
        }
      end

    {text, refs} = Prompts.render(members, msgs, @tz, now: @now)
    names = Map.new(refs.users, fn {ref, "user:" <> n} -> {ref, n} end)
    {text, refs, names}
  end

  ## Hallucination check

  @weekdays ~w(monday tuesday wednesday thursday friday saturday sunday)
  @months ~w(january february march april may june july august september october november december)
  @stop ~w(the a an i we you he she they it this that yes no ok okay risi summary who what when
           where why how and or but not none nothing include book send update prepare write
           transfer pay check meet call)

  @doc """
  The names, amounts and dates in `output` that `source` does not contain (case-insensitive):
  numbers (digits, commas ignored), English weekday and month names, and capitalised Latin words
  that are not at the start of a sentence or line. Returns the invented tokens.
  """
  def invented(source, output) do
    src = String.downcase(source)
    src_digits = Regex.scan(~r/\d[\d,]*/u, source) |> Enum.map(&String.replace(hd(&1), ",", ""))
    src_words = Regex.scan(~r/[\p{L}]+/u, src) |> Enum.map(&hd/1) |> MapSet.new()

    numbers =
      Regex.scan(~r/\d[\d,]*/u, output)
      |> Enum.map(&String.replace(hd(&1), ",", ""))
      |> Enum.reject(&(&1 in src_digits))

    dates =
      Regex.scan(~r/[\p{L}]+/u, String.downcase(output))
      |> Enum.map(&hd/1)
      |> Enum.filter(&(&1 in @weekdays or &1 in @months))
      |> Enum.reject(&MapSet.member?(src_words, &1))

    names =
      Regex.scan(~r/(?<![.!?:;\n]\s)(?<![.!?:;\n])(?<!^)(?<=\s)\p{Lu}\p{Ll}+/u, output)
      |> Enum.map(&hd/1)
      |> Enum.reject(fn w ->
        d = String.downcase(w)
        d in @stop or d in @weekdays or d in @months or MapSet.member?(src_words, d)
      end)

    Enum.uniq(numbers ++ dates ++ names)
  end

  ## Summaries of a set

  defp summary(agent, results, live) do
    n = length(results)
    ok = Enum.count(results, & &1.ok)
    lat = Enum.map(results, & &1.ms)

    %{
      agent: agent,
      mode: if(live, do: "live", else: "fake"),
      n: n,
      ok: ok,
      accuracy: if(n > 0, do: ok / n, else: nil),
      halluc: Enum.sum(Enum.map(results, & &1.halluc)),
      halluc_caught: Enum.count(results, &(Map.get(&1, :trap) && &1.halluc > 0)),
      p50_ms: Runs.percentile(lat, 0.5),
      p95_ms: Runs.percentile(lat, 0.95),
      by_lang:
        results
        |> Enum.group_by(& &1.lang)
        |> Map.new(fn {l, rs} -> {l, %{n: length(rs), ok: Enum.count(rs, & &1.ok)}} end),
      failed: for(r <- results, !r.ok, do: r.id)
    }
  end
end
