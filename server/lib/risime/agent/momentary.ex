defmodule RisiMe.Agent.Momentary do
  @moduledoc """
  Momentary requests made during a call or chat ("turn on the camera", "can you hear me",
  "share your screen", "unmute") are not commitments (architect answer 2026-10-16, item 4).
  The extraction prompts say so; this is the deterministic backstop for an item the model
  still returns.
  """

  @re ~r/\b(turn|switch)\s+(on|off)\s+(the\s+|your\s+|my\s+)?(camera|video|mic|microphone|sound|audio)\b|\b(can|could)\s+(you|u)\s+(hear|see)\s+(me|us)\b|\bshare\s+(the\s+|your\s+|my\s+)?screen\b|\b(un)?mute\b|\bcan\s+you\s+hear\b/iu

  @doc "True when `text` is a momentary call/chat request, not a commitment."
  def request?(text) when is_binary(text), do: Regex.match?(@re, text)
  def request?(_), do: false
end
