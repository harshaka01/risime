defmodule RisiMe.Agent.Capabilities do
  @moduledoc """
  What Risi can do **now** and what is **coming** (contract v1.25 §25.1 system prompt, decision
  068; hotfix 2026-10-09 from the pilot: "what can you do" must answer with this list, and a
  question Risi can't answer gets what it can do instead of "the transcript does not contain").

  The lists are generated from what is actually available: the stage-1 secretary features
  always (they run wherever Risi is in an Official chat), plus the tools of the registry that
  `RisiMe.Agent.Tools.allowed/1` offers this asker (v1.25, `RISI_TOOLS=on`). Anything not
  available yet is listed as coming, never claimed.
  """

  @now [
    "track promises made in this chat: when someone commits to something I propose a card, " <>
      "and once it is confirmed I remind them before the deadline and follow up",
    "answer questions about this chat's last 24 hours",
    "summarise this chat's last 24 hours",
    "write a short report of the period",
    "post a morning digest of open promises"
  ]

  # Tool name → what it adds (only listed as "now" once the tool is offered to the asker).
  @tools %{
    "set_reminder" => "set reminders when you ask (\"remind us Tuesday 2pm\")",
    "calendar_check" => "check whether you're free in your phone's calendar",
    "calendar_add" => "add events to your phone's calendar after you tap Add",
    "risi_calendar_check" => "check whether you're free in your Risi Calendar (and your phone's)",
    "risi_calendar_add" => "add events to your Risi Calendar and invite people after you tap Add",
    "search_chats" => "search your Official chats",
    "summarise" => "summarise a chat in English, Sinhala or Tamil",
    "draft_reply" => "draft replies for you to send",
    "remember" => "remember notes for you (\"Swan is my dentist\")",
    "forget" => "forget a note when you ask",
    "ask_risiwork" => "ask RisiWork for you"
  }

  @coming [
    {"calendar_check",
     "checking your calendar and adding events to it (needs the Calendar skill and the phone)"},
    {"set_reminder", "reminders on request"},
    {"search_chats", "searching your chats"},
    {"remember", "remembering notes for you"},
    {"ask_risiwork", "asking RisiWork"}
  ]

  @doc "What Risi can do now for an asker offered `tools` (tool names)."
  def now(tools \\ []) do
    @now ++ for(t <- tools, line = @tools[t], do: line)
  end

  @doc "What is coming (not offered to this asker yet)."
  def coming(tools \\ []) do
    # v1.29: a Risi Calendar user checks their calendar with `risi_calendar_check`.
    tools = if "risi_calendar_check" in tools, do: ["calendar_check" | tools], else: tools
    for {t, line} <- @coming, t not in tools, do: line
  end

  @doc "The capability block of a system prompt."
  def prompt(tools \\ []) do
    """
    What you can do now:
    #{Enum.map_join(now(tools), "\n", &("- " <> &1))}
    Coming soon (not available yet; never pretend to do these):
    #{Enum.map_join(coming(tools), "\n", &("- " <> &1))}
    #{calendar_rules()}
    """
  end

  @doc """
  What Risi may say about calendars (P0 2026-10-10, Harsha: Risi said it could not read Google
  Calendar). Google accounts synced to the phone are part of the phone's calendar provider; only
  the separate Google API link (§31) is optional, and its absence is never a reason to refuse.
  """
  def calendar_rules do
    "Calendars: the asker's phone calendar includes the Google accounts synced to the phone, " <>
      "so you can read and add events there with the calendar tools when they are offered. " <>
      "Google Calendar on the phone is within reach for both. Answer a calendar question only from " <>
      "the calendar tool result: say what it found, or exactly why it could not read (no " <>
      "calendar permission, sync off, the phone did not answer). Never say the asker is free " <>
      "without a successful read. When no calendar tool is offered, say the Calendar skill " <>
      "must be turned on (Settings → Risi skills → Calendar); never say the calendar can't be read."
  end

  @doc "A short sentence of what Risi can do now and what's coming (server-built answers)."
  def sentence(tools \\ []) do
    now = now(tools) |> Enum.take(3) |> Enum.join("; ")

    case coming(tools) do
      [] -> "Right now I can #{now}."
      coming -> "Right now I can #{now}. Coming soon: #{Enum.join(coming, ", ")}."
    end
  end

  # The forbidden phrasing (§25.1): "the transcript/context/chat does not contain/mention …",
  # "there is no information in the transcript", "not mentioned in the conversation".
  @forbidden [
    ~r/\b(transcript|context|conversation|chat|messages?)\b[^.!?\n]{0,40}\b(does not|doesn't|do not|don't|did not|didn't|has no|have no|lacks?)\b[^.!?\n]{0,20}\b(contain|include|mention|say|have|information|info|details?|data)/i,
    ~r/\b(not|no)\b[^.!?\n]{0,30}\b(mentioned|found|included|contained|information|details?)\b[^.!?\n]{0,20}\b(in|from)\s+the\s+(transcript|context|conversation|chat)/i
  ]

  @doc "True when an answer uses the forbidden \"the transcript does not contain\" phrasing."
  def forbidden?(text) when is_binary(text), do: Enum.any?(@forbidden, &Regex.match?(&1, text))
  def forbidden?(_), do: false

  @doc """
  The corrective user message of the one post-check retry: never say the transcript lacks
  something; say what Risi can do now and what's coming, and offer an alternative.
  """
  def retry_instruction do
    "Don't say that the transcript, chat or context does not contain something. If you can't " <>
      "answer from what you have, say plainly what you can do now and what is coming, and offer " <>
      "an alternative (for example: \"I can't ask RisiWork yet. It is coming " <>
      "soon; want me to set a reminder instead?\"). Answer again in the same JSON format."
  end

  @doc "A server-built answer when the model keeps using the forbidden phrasing."
  def fallback_answer(tools \\ []),
    do: "I couldn't find that in what I can see here. " <> sentence(tools)

  # Placeholder labels a model may put in text: "Message 1", "(m3)", "[m1, m2]", "msg #4".
  @labels [
    ~r/\s*[\(\[]\s*(?:m|msg|message)\s*#?\d{1,4}(?:\s*(?:,|and|&)\s*(?:m|msg|message)?\s*#?\d{1,4})*\s*[\)\]]/i,
    ~r/\b(?:message|msg)\s*#?\d{1,4}\b(?:\s*(?:,|and|&)\s*#?\d{1,4}\b)*/i,
    ~r/\bm\d{1,4}\b/
  ]

  @doc """
  Removes ref labels ("Message 1", "(m3)") from a model's text: sources travel only as real
  message refs (`conversation_id` + `message_id`) the app renders as quotes, never as labels.
  """
  def strip_labels(text) when is_binary(text) do
    @labels
    |> Enum.reduce(text, &Regex.replace(&1, &2, ""))
    |> String.replace(~r/[ \t]{2,}/, " ")
    |> String.replace(~r/\s+([.,;:!?])/, "\\1")
    |> String.replace(~r/\(\s*\)|\[\s*\]/, "")
    |> String.trim()
  end

  def strip_labels(other), do: other
end
