defmodule RisiMe.Agent.CallRisi.Fillers do
  @moduledoc """
  The holding lines Risi says while a tool runs (decision 090). Spoken **only** when a tool is
  really called (a tool route of `RisiMe.Agents.Router`), never for a plain chat answer. A filler
  never states or implies a result ("let me check", never "you're free"). Per route and language;
  the pick rotates so a call doesn't repeat the same line. v1 speaks English (Kokoro).
  """

  @lines %{
    "en" => %{
      "calendar_read" => [
        "Hmm, let me check your calendar…",
        "One moment, I'll look at your calendar."
      ],
      "calendar_write" => ["Okay, let me set that up…", "Sure, one moment while I prepare that."],
      "reminder" => ["Okay, let me set that reminder up…", "Sure, one moment."],
      "ledger_items" => ["Let me look at your items…", "One moment, I'll check what's open."],
      "memory" => ["Let me think back…", "One moment, I'll look through what we discussed."],
      "summary_chat" => ["Okay, let me pull that together…"],
      "message_write" => ["Okay, let me draft that…"],
      "planner" => ["Hmm, let me look into that…", "One moment, let me check."]
    }
  }

  @doc "True when `route` uses a tool (so a filler is honest)."
  def tool_route?(route), do: route != "chat"

  @doc "A filler for `route` in `lang` (`n` rotates), or nil for the chat route."
  def pick(route, lang, n \\ 0)
  def pick("chat", _lang, _n), do: nil

  def pick(route, lang, n) do
    table = Map.get(@lines, lang) || @lines["en"]
    lines = Map.get(table, route) || table["planner"]
    Enum.at(lines, rem(n, length(lines)))
  end

  @doc "Every filler line (tests: none of them states a result)."
  def all, do: for({_l, t} <- @lines, {_r, ls} <- t, l <- ls, do: l)
end
