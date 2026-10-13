defmodule RisiMe.Agent.CallRisi.Sentences do
  @moduledoc """
  Cuts a streamed answer into speakable pieces (decision 090): a piece is ready at a sentence end
  (`. ! ? …` followed by a space, or a newline). The **first** piece may also end at a clause
  (`, ; :` after at least 4 words), so the first audio starts sooner. Markdown marks and emoji are
  removed (they are not spoken). Pure functions; no I/O.
  """

  @end_re ~r/^(.+?[.!?…]["')\]]?)(?:\s+|\n+)(.*)$/su
  @nl_re ~r/^(.+?)\n+(.*)$/su
  @clause_re ~r/^(.+?[,;:])\s+(.*)$/su

  @doc """
  Feeds `delta` into `buffer`. Returns `{ready_pieces, rest}`; `first?` allows the clause cut.
  """
  def feed(buffer, delta, first? \\ false) do
    take(buffer <> delta, first?, [])
  end

  defp take(buf, first?, acc) do
    case cut(buf, first? and acc == []) do
      {piece, rest} ->
        case clean(piece) do
          "" -> take(rest, first?, acc)
          p -> take(rest, first?, [p | acc])
        end

      nil ->
        {Enum.reverse(acc), buf}
    end
  end

  defp cut(buf, clause?) do
    cond do
      m = Regex.run(@end_re, buf, capture: :all_but_first) -> List.to_tuple(m)
      m = Regex.run(@nl_re, buf, capture: :all_but_first) -> List.to_tuple(m)
      clause? -> clause_cut(buf)
      true -> nil
    end
  end

  defp clause_cut(buf) do
    case Regex.run(@clause_re, buf, capture: :all_but_first) do
      [head, rest] -> if length(String.split(head)) >= 4, do: {head, rest}
      _ -> nil
    end
  end

  @doc "The rest of the stream at its end, as a last piece (or nothing)."
  def flush(buffer) do
    case clean(buffer) do
      "" -> []
      p -> [p]
    end
  end

  @doc "Removes what is not spoken: markdown marks, list bullets, emoji, extra spaces."
  def clean(text) do
    text
    |> String.replace(~r/[*_`#>|~]+/u, "")
    |> String.replace(~r/^\s*(?:[-•]|\d+[.)])\s+/mu, "")
    |> String.replace(~r/[\x{1F000}-\x{1FAFF}\x{2600}-\x{27BF}\x{FE0F}]/u, "")
    |> String.replace(~r/\s+/u, " ")
    |> String.trim()
  end
end
