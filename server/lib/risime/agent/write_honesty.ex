defmodule RisiMe.Agent.WriteHonesty do
  @moduledoc """
  Write honesty (contract v1.32 §25.3, P0 2026-10-10: Risi said a meeting was added that was in
  no calendar). The model's text is never used to claim a write: a sentence that says something
  was added, set or scheduled is removed unless this turn ran a write that the phone or the
  server reported as done. A write that is only proposed (a confirm card) is not done: the
  result lines are posted by `ClientTools.exec` after the phone answers.
  """

  @claims [
    ~r/\b(?:I|we)(?:'ve| have)?\s+(?:just\s+|now\s+|already\s+|successfully\s+)?(?:added|scheduled|created|booked|saved|set(?:\s+(?:up|a|an|the|your|that|it)\b))/i,
    ~r/\b(?:has|have)\s+been\s+(?:added|set|scheduled|created|booked|saved)\b/i,
    ~r/\b(?:is|are|was)\s+(?:now\s+|all\s+)?(?:added|scheduled|booked|set up|on your calendar|in your calendar)\b/i,
    ~r/\badded\s+(?:it\s+)?to\s+your\b/i,
    ~r/\breminder\s+(?:is\s+|has been\s+)?set\b/i,
    ~r/\ball\s+set\b/i,
    ~r/^\s*(?:all\s+)?done[!.]?\s*$/i
  ]

  @doc "Does the sentence claim an add / reminder / schedule?"
  def claims_write?(text) when is_binary(text), do: Enum.any?(@claims, &Regex.match?(&1, text))
  def claims_write?(_), do: false

  @doc """
  `{answer, changed?}`. With `done` writes this turn the answer stays; otherwise every claiming
  sentence is removed, and an answer left empty becomes a plain statement.
  """
  def enforce(answer, done, proposed?) when is_binary(answer) do
    cond do
      done > 0 or not claims_write?(answer) ->
        {answer, false}

      true ->
        kept =
          answer
          |> String.split(~r/(?<=[.!?])\s+|\n+/, trim: true)
          |> Enum.reject(&claims_write?/1)
          |> Enum.join(" ")
          |> String.trim()

        {if(kept == "", do: none_text(proposed?), else: kept), true}
    end
  end

  def enforce(answer, _done, _proposed?), do: {answer, false}

  defp none_text(true), do: "I've prepared it for you: nothing is done until you tap Add."

  defp none_text(false),
    do: "Nothing has been done yet. I only confirm a change after your phone confirms it."
end
