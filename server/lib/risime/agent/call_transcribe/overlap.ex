defmodule RisiMe.Agent.CallTranscribe.Overlap do
  @moduledoc """
  Proposal 2026-10-15-live-call-latency: a fast session's phone cuts its microphone at pauses
  into 1-4 s chunks, and each chunk starts up to 0.5 s before the previous one ended, so a word
  cut at the edge is never lost. The overlap can make the speech model repeat that word at the
  head of the next chunk; `strip/2` drops the longest head of the new text (at most 6 words)
  that repeats the tail of the speaker's previous line. Words are compared lower-cased without
  punctuation (any script: Sinhala, Tamil and English words are space-separated). Pure; no text
  is logged.
  """

  @max_words 6
  @tail_words 8

  @doc "The normalised words of `text` (lower case, no punctuation)."
  def words(text) when is_binary(text) do
    text |> String.split() |> Enum.map(&norm/1) |> Enum.reject(&(&1 == ""))
  end

  @doc "The previous tail `tail` extended by `text` (the last 8 normalised words)."
  def tail(tail, text), do: Enum.take(tail ++ words(text), -@tail_words)

  @doc """
  `text` without the head that repeats the end of `tail` (the speaker's previous words). Returns
  the text unchanged when nothing repeats, and `""` when all of it does.
  """
  def strip(tail, text) when is_list(tail) and is_binary(text) do
    raw = String.split(text)
    normed = Enum.map(raw, &norm/1)
    max = Enum.min([@max_words, length(raw), length(tail)])

    k =
      Enum.reduce(1..max//1, 0, fn k, best ->
        head = Enum.take(normed, k)
        if Enum.all?(head, &(&1 != "")) and head == Enum.take(tail, -k), do: k, else: best
      end)

    raw |> Enum.drop(k) |> Enum.join(" ")
  end

  defp norm(w), do: w |> String.downcase() |> String.replace(~r/[^\p{L}\p{M}\p{N}]/u, "")
end
