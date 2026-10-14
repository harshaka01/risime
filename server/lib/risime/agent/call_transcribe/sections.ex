defmodule RisiMe.Agent.CallTranscribe.Sections do
  @moduledoc """
  P0 2026-10-15 (a long call got action items but no summary): a call transcript is summarised
  in **sections** (map-reduce).

  * `split/2` cuts the transcript lines into sections that fit risi-l1 comfortably. risi-l1 is
    served with `--max-model-len 32768` and a 60-s receive timeout; on 14 Oct a 36-min call was
    one 15.7k-token prompt, so a 75-min call would not have fit at all. A section holds at most
    `max_chars` of line text (each line counted with an overhead for its refs and time) and at
    most `max_span_s` of call time: defaults 16 000 characters (about 5k tokens) and 600 s,
    `config :risime, :risi_call_sections`.
  * Each section is extracted on its own (the callers: `ActionItems.from_call/3` and
    `Ledger.call_ended/2`), then `reduce/2` merges the sections' key points and one-line
    summaries into one: one small `call_summary_reduce` model call, else a deterministic merge.
    A summary always posts.
  * A section whose extraction failed is skipped, and `gap_text/2` says in one line which part
    of the call the summary leaves out.
  """
  require Logger

  alias RisiMe.Agent.{LLM, Prompts}

  @line_overhead 80
  @defaults [max_chars: 16_000, max_span_s: 600]

  @doc """
  The section limits (`max_chars`, `max_span_s`). `RISI_CALL_SECTION_S` (seconds, read at run
  time) overrides `max_span_s`: `scripts/call-summary-test` sets it low so a one-minute gate call
  takes the long-call path; the pilot leaves it unset.
  """
  def limits do
    l = Keyword.merge(@defaults, Application.get_env(:risime, :risi_call_sections, []))

    case Integer.parse(System.get_env("RISI_CALL_SECTION_S") || "") do
      {s, ""} when s > 0 -> Keyword.put(l, :max_span_s, s)
      _ -> l
    end
  end

  @doc """
  Cuts `lines` (oldest first, each with a `:text`) into consecutive sections; `at` gives a
  line's time. Every section has at least one line; `[]` gives `[]`.
  """
  def split([], _at), do: []

  def split(lines, at) do
    l = limits()

    {done, cur, _chars, _first} =
      Enum.reduce(lines, {[], [], 0, nil}, fn line, {done, cur, chars, first} ->
        size = String.length(to_string(line[:text])) + @line_overhead
        t = at.(line)

        if cur != [] and
             (chars + size > l[:max_chars] or DateTime.diff(t, first) >= l[:max_span_s]) do
          {[Enum.reverse(cur) | done], [line], size, t}
        else
          {done, [line | cur], chars + size, first || t}
        end
      end)

    Enum.reverse([Enum.reverse(cur) | done])
  end

  @doc """
  Runs `fun` on each section (in order) and splits the results: `{oks, failed}` where `oks` are
  `{section_index, value}` of `{:ok, value}` results and `failed` the `{section_index, reason}`
  of the others (`{:error, reason}`, any other value as the reason). Indexes start at 1.
  """
  def map(sections, fun) do
    sections
    |> Enum.with_index(1)
    |> Enum.map(fn {sec, i} ->
      case fun.(sec, i) do
        {:ok, v} -> {:ok, i, v}
        {:error, reason} -> {:error, i, reason}
        other -> {:error, i, other}
      end
    end)
    |> Enum.split_with(&(elem(&1, 0) == :ok))
    |> then(fn {oks, bad} ->
      {Enum.map(oks, fn {:ok, i, v} -> {i, v} end),
       Enum.map(bad, fn {:error, i, r} -> {i, r} end)}
    end)
  end

  @doc """
  One summary of the call from its sections' parts (`[%{key_points: [str], summary: str | nil}]`,
  in call order): `%{key_points, summary, call_ref}`. One part is returned as it is (no model
  call). More parts go through one `call_summary_reduce` model call (`base`: `conversation_id`,
  `chat_id`, `lang`, `meta`); when it fails, a deterministic merge: the key points taken in turn
  from each part (duplicates dropped, at most 8), the parts' summaries joined (≤ 280).
  `key_points` may be `[]` and `summary` nil only when no part had either.
  """
  def reduce(_base, [one]),
    do: %{key_points: one.key_points || [], summary: one[:summary], call_ref: nil}

  def reduce(base, parts) do
    req = %{
      task: "call_summary_reduce",
      conversation_id: base.conversation_id,
      chat_id: base.chat_id,
      system: Prompts.reduce_system(base[:lang] || "en"),
      user: render(parts),
      schema_name: "call_summary",
      schema: Prompts.reduce_schema(),
      source_message_ids: [],
      max_tokens: 1_200,
      meta: Map.merge(base[:meta] || %{}, %{"sections" => length(parts)}),
      # Counts only, never text (as §38.7).
      log_output: fn out ->
        Jason.encode!(%{
          "key_points" => length(out["key_points"] || []),
          "summary" => is_binary(out["summary"]) and out["summary"] != ""
        })
      end
    }

    case LLM.complete(req) do
      {:ok, %{call_ref: call_ref, output: out}} ->
        points = clean_points(out["key_points"])
        summary = clean_summary(out["summary"])

        if points == [] and summary == nil,
          do: merge(parts),
          else: %{key_points: points, summary: summary, call_ref: call_ref}

      {:error, reason} ->
        Logger.warning("call summary: sections not reduced (#{inspect(reason)}); merged")
        merge(parts)
    end
  end

  @doc "The deterministic merge of `reduce/2` (no model)."
  def merge(parts) do
    lists = Enum.map(parts, &(&1.key_points || []))
    longest = lists |> Enum.map(&length/1) |> Enum.max(fn -> 0 end)

    points =
      for(i <- 0..(longest - 1)//1, l <- lists, p = Enum.at(l, i), p != nil, do: p)
      |> Enum.uniq_by(&String.downcase/1)
      |> Enum.take(8)

    summary =
      parts
      |> Enum.map(& &1[:summary])
      |> Enum.reject(&(&1 in [nil, ""]))
      |> Enum.uniq()
      |> Enum.join(" ")
      |> clean_summary()

    %{key_points: points, summary: summary, call_ref: nil}
  end

  @doc "1–8 trimmed key points of at most 200 characters."
  def clean_points(points) do
    (points || [])
    |> Enum.map(&(&1 |> to_string() |> String.trim() |> String.slice(0, 200)))
    |> Enum.reject(&(&1 == ""))
    |> Enum.take(8)
  end

  @doc "A trimmed one-line summary of at most 280 characters, or nil."
  def clean_summary(nil), do: nil

  def clean_summary(s) do
    case s |> to_string() |> String.trim() do
      "" -> nil
      t -> cut(t)
    end
  end

  # At most 280 characters, cut at a word when there is one.
  defp cut(t) do
    if String.length(t) <= 280 do
      t
    else
      head = String.slice(t, 0, 279)

      case String.split(head, ~r/\s+(?=\S*$)/, parts: 2) do
        [words, _] when words != "" -> words <> "…"
        _ -> head <> "…"
      end
    end
  end

  defp render(parts) do
    body =
      parts
      |> Enum.with_index(1)
      |> Enum.map_join("\n", fn {p, i} ->
        lines =
          [if(p[:summary], do: "Summary: #{p.summary}")] ++
            Enum.map(p.key_points || [], &("- " <> &1))

        "Part #{i}:\n" <> (lines |> Enum.reject(&is_nil/1) |> Enum.join("\n"))
      end)

    "<notes>\n#{body}\n</notes>"
  end

  @doc """
  The one line for sections that failed: `failed` are their `{first, last}` times, the call
  started at `start`. "I couldn't summarise minutes 10–20 of this call; the summary covers the
  rest."
  """
  def gap_text(failed, %DateTime{} = start) do
    spans =
      failed
      |> Enum.map(fn {a, b} ->
        from = max(div(DateTime.diff(a, start), 60), 0)
        to = max(div(DateTime.diff(b, start) + 59, 60), from + 1)
        "#{from}–#{to}"
      end)

    which =
      case spans do
        [one] -> "minutes #{one}"
        many -> "minutes " <> Enum.join(Enum.drop(many, -1), ", ") <> " and " <> List.last(many)
      end

    "I couldn't summarise #{which} of this call; the summary covers the rest."
  end
end
