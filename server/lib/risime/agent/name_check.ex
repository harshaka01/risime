defmodule RisiMe.Agent.NameCheck do
  @moduledoc """
  The name check before a write (contract v1.35 §34.5, P0 2026-10-11: "Shutazi" was saved
  although the contact is "Shirazi").

  Each person name of a write is matched against the asker's friends (contacts and co-members
  of their chats, by display name and by each word of it), both sides normalised (NFKD, marks
  removed, case-folded, spaces collapsed):

    * **exact** → the write goes straight through;
    * **close** (Levenshtein ≤ 2, ≤ 1 for names of 4 characters or fewer, or an equal phonetic
      key, Latin script only) → `{:clarify, options}` (up to 3, best first, ties to the most
      recently messaged contact: lower `rank`);
    * **no close match** → `:pass` (the person need not be a contact).
  """

  @doc "Normalised form of a name."
  def norm(s) when is_binary(s) do
    s
    |> :unicode.characters_to_nfkd_binary()
    |> String.replace(~r/\p{Mn}/u, "")
    |> String.downcase()
    |> String.replace(~r/[^\p{L}\p{N}\s'-]/u, " ")
    |> String.split()
    |> Enum.join(" ")
  end

  def norm(_), do: ""

  @doc """
  `said` against `friends` (`[%{name, user_id, rank}]`): `:exact`, `:pass` or
  `{:clarify, [friend]}`.
  """
  def match(said, friends) do
    s = norm(said)

    if s == "" do
      :pass
    else
      keys = Enum.map(friends, fn f -> {f, forms(f.name)} end)

      if Enum.any?(keys, fn {_f, forms} -> s in forms end) do
        :exact
      else
        close =
          for {f, forms} <- keys,
              d = best(s, forms),
              d != nil,
              do: {d, f.rank || 0, f}

        case close |> Enum.sort_by(fn {d, r, _} -> {d, r} end) |> Enum.map(&elem(&1, 2)) do
          [] -> :pass
          opts -> {:clarify, opts |> Enum.uniq_by(& &1.user_id) |> Enum.take(3)}
        end
      end
    end
  end

  defp forms(name) do
    n = norm(name)
    Enum.uniq([n | String.split(n)])
  end

  # The best distance to a close form (phonetic matches count as 2), or nil.
  defp best(s, forms) do
    max = if String.length(s) <= 4, do: 1, else: 2

    forms
    |> Enum.flat_map(fn f ->
      d = lev(s, f)

      cond do
        d <= max -> [d]
        phonetic(s) != nil and phonetic(s) == phonetic(f) -> [max]
        true -> []
      end
    end)
    |> Enum.min(fn -> nil end)
  end

  @doc "Levenshtein distance (graphemes)."
  def lev(a, b) do
    a = String.graphemes(a)
    b = String.graphemes(b)
    row0 = Enum.to_list(0..length(b))

    a
    |> Enum.with_index(1)
    |> Enum.reduce(row0, fn {ca, i}, prev ->
      {row, _} =
        b
        |> Enum.zip(prev)
        |> Enum.zip(tl(prev))
        |> Enum.reduce({[i], i}, fn {{cb, diag}, up}, {acc, left} ->
          v = Enum.min([up + 1, left + 1, diag + if(ca == cb, do: 0, else: 1)])
          {[v | acc], v}
        end)

      Enum.reverse(row)
    end)
    |> List.last()
  end

  @doc """
  A phonetic key in the spirit of the Double Metaphone primary code (Latin script only, at
  least 4 letters; nil otherwise): similar consonants merged, vowels after the first dropped,
  repeats collapsed.
  """
  def phonetic(s) do
    if String.length(s) >= 4 and s =~ ~r/^[a-z' -]+$/ do
      s = String.replace(s, ~r/[^a-z]/, "")

      key =
        s
        |> String.replace(~r/^(kn|gn|pn|wr)/, fn m -> String.last(m) end)
        |> String.replace("ph", "f")
        |> String.replace(~r/c(?=[eiy])/, "s")
        |> String.replace(~r/sh|ch/, "x")
        |> String.replace(~r/[cqk]/, "k")
        |> String.replace("ck", "k")
        |> String.replace(~r/[sz]/, "s")
        |> String.replace(~r/[dt]h?/, "t")
        |> String.replace(~r/[bp]/, "p")
        |> String.replace(~r/[vw]/, "f")
        |> String.replace(~r/[gj]/, "k")
        |> String.replace("x", "x")

      [first | rest] = String.graphemes(key)
      rest = rest |> Enum.reject(&(&1 in ~w(a e i o u y h))) |> Enum.dedup()
      first = if first in ~w(a e i o u y), do: "a", else: first
      Enum.join([first | rest])
    end
  end

  ## Names in a write's args (§34.5)

  @doc """
  The person names a write names: the `with` slot, the words after "with " in an English title
  (up to punctuation), and a `schedule_message`'s `to`.
  """
  def names_of(tool, args) when is_map(args) do
    with_slot =
      case args["with"] do
        l when is_list(l) -> Enum.filter(l, &is_binary/1)
        _ -> []
      end

    from_text =
      for k <- ~w(title text), t = args[k], is_binary(t), n <- after_with(t), do: n

    to =
      if tool == "schedule_message" and is_binary(args["to"]) and
           String.downcase(String.trim(args["to"])) not in ["this chat", "this group", "here"],
         do: [args["to"]],
         else: []

    (with_slot ++ from_text ++ to)
    |> Enum.map(&String.trim/1)
    |> Enum.reject(&(&1 == "" or quoted?(&1)))
    |> Enum.uniq()
  end

  def names_of(_tool, _args), do: []

  @stop ~w(me my you your us our the a an him her them his their team everyone all)

  defp after_with(text) do
    case Regex.run(~r/\bwith\s+([^,.;:!?()\n]+)/iu, text) do
      [_, rest] ->
        rest
        |> String.split(~r/\s+(?:and|&)\s+|\s*,\s*/u)
        |> Enum.map(fn part ->
          part
          |> String.split()
          |> Enum.take_while(&(&1 =~ ~r/^\p{Lu}/u and String.downcase(&1) not in @stop))
          |> Enum.join(" ")
        end)
        |> Enum.reject(&(&1 == ""))

      _ ->
        []
    end
  end

  defp quoted?(s), do: String.starts_with?(s, "\"")

  @doc "Replaces `said` by `name` in every text slot of `args` (and the `with` list)."
  def replace(args, said, name) do
    re = ~r/\b#{Regex.escape(said)}\b/iu

    Map.new(args, fn
      {k, v} when k in ~w(title text to) and is_binary(v) ->
        {k, Regex.replace(re, v, name)}

      {"with", l} when is_list(l) ->
        {"with", Enum.map(l, &if(is_binary(&1), do: Regex.replace(re, &1, name), else: &1))}

      kv ->
        kv
    end)
  end
end
