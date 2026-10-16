defmodule RisiMe.Agent.CallRisi.Dialogue do
  @moduledoc """
  Call Risi brain v3 (architect P0 2026-10-16, Harsha's call 75ec8683): the **open state** of a
  call, which the model sees on every turn and the brain keeps up to date. Pure functions; the
  state lives in the call's session (`dialogue`) and only for the call (never stored).

      %{
        card: nil | %{request_id, kind: "message" | "calendar" | "reminder", to, text, summary},
        awaiting: nil | %{slot: "message_text", to}
                      | %{slot: "message_to", text}
                      | %{slot: "day", pending}
                      | %{slot: "answer", question}
      }

  **Message slots** `{to, text}`: the text is never made up. A message request without the text
  asks "What should I say to <name>?" and the next words fill it; text the model gives is used
  only when the caller's own words (or the open card's) carry it (`grounded?/2`).
  """

  @empty %{card: nil, awaiting: nil}

  @doc "A call's state before anything is open."
  def new, do: @empty

  @doc "The state as given (nil = nothing open)."
  def get(nil), do: @empty
  def get(%{} = d), do: Map.merge(@empty, d)

  @doc "The question Risi asks when a message has no text yet."
  def ask_text(to) when is_binary(to) and to != "", do: "What should I say to #{to}?"
  def ask_text(_), do: "What should the message say?"

  @doc "The question Risi asks when a message names nobody."
  def ask_to, do: "Who should I send it to?"

  @doc """
  What Risi says once the message card is in the Risi chat (the card's summary
  "Send to Nimali Perera (Private): <text>"): the draft read back, and the card's real buttons
  (§42.2: [Send], [Edit], Cancel). A voice yes never sends to other people (§41.9).
  """
  def message_line(summary) when is_binary(summary) do
    case Regex.run(~r/^Send to (.+?) \((?:Private|Official)\):\s*(.+)$/su, summary) do
      [_, to, text] ->
        "Here's your message to #{to}: \"#{String.trim(text)}\". " <>
          "Tap Send on the card in your Risi chat to send it, or tell me what to change."

      _ ->
        "Your message is on a card in your Risi chat. Tap Send to send it, or Cancel."
    end
  end

  def message_line(_), do: "Your message is on a card in your Risi chat. Tap Send to send it."

  @doc "The recipient and text of a message card's summary, or nil."
  def message_parts(summary) when is_binary(summary) do
    case Regex.run(~r/^Send to (.+?) \((?:Private|Official)\):\s*(.+)$/su, summary) do
      [_, to, text] -> %{to: to, text: String.trim(text)}
      _ -> nil
    end
  end

  def message_parts(_), do: nil

  @doc "What Risi says when a voice yes meets a message card (only the caller's tap sends)."
  def message_tap_line,
    do:
      "I can't send it from the call: messages go only when you tap Send. " <>
        "Tap Send on the card in your Risi chat."

  @doc "What Risi says after the open card was cancelled (the card's row is `cancelled`)."
  def cancelled_line("message"), do: "Okay, cancelled. Nothing was sent."
  def cancelled_line(_), do: "Okay, cancelled. Nothing was added."

  ## Grounding: the text of a message is the caller's own words

  @stop ~w(the a an and or but to of in on at for is are am was be it its this that with my your
           i you he she we they me him her us them so just say tell hey hi oh ok okay please)

  @doc """
  True when `text` (a message's text the model gave) comes from `source` (the caller's words,
  the recipient's name, the open card's text): at least 75% of its content words appear there.
  A model that wrote "Hi Nimali, how are you" for "Can you send a message to Nimali?" fails.
  """
  def grounded?(text, source) when is_binary(text) and is_binary(source) do
    words = content_words(text)
    have = MapSet.new(content_words(source))

    case words do
      [] -> String.trim(text) != "" and String.length(String.trim(text)) <= 40
      _ -> Enum.count(words, &MapSet.member?(have, &1)) / length(words) >= 0.75
    end
  end

  def grounded?(_text, _source), do: false

  defp content_words(s) do
    s
    |> String.downcase()
    |> String.replace(~r/[’‘]/u, "'")
    |> String.split(~r/[^\p{L}\p{N}']+/u, trim: true)
    |> Enum.map(&String.trim(&1, "'"))
    |> Enum.reject(&(&1 == "" or &1 in @stop))
  end

  ## The state, as the model reads it

  @doc "The open card, in one line for the model."
  def card_line(%{card: %{kind: "message"} = c}),
    do:
      "a message to #{c[:to] || "someone"}, not sent yet: \"#{c[:text] || ""}\" " <>
        "(it waits for the user's Send tap)"

  def card_line(%{card: %{kind: kind, summary: s}}) when is_binary(s),
    do: "a #{kind} card, not added yet: #{s} (it waits for the user's Add tap)"

  def card_line(%{card: %{kind: kind}}), do: "a #{kind} card, not added yet"
  def card_line(_), do: "none"

  @doc "What Risi waits for, in one line for the model."
  def awaiting_line(%{awaiting: %{slot: "message_text", to: to}}),
    do: "the text of the message to #{to} (Risi asked what it should say)"

  def awaiting_line(%{awaiting: %{slot: "message_to"}}),
    do: "who the message is for (Risi asked)"

  def awaiting_line(%{awaiting: %{slot: "day", pending: p}}),
    do: "the day for this request: \"#{p}\" (Risi asked which day)"

  def awaiting_line(%{awaiting: %{slot: "answer", question: q}}),
    do: "an answer to Risi's question: \"#{q}\""

  def awaiting_line(_), do: "nothing"

  ## Fragments (architect P0 C)

  # Words a thought doesn't end on.
  @dangling ~w(and or but so to with for of the a an my your that because if than then when
               about from at in on into is are was were be will would can could should um uh
               like just also please saying)

  @doc """
  True when `text` reads as cut off ("Do you have anything", "… and", "… to,"): the brain holds it
  for the next words before acting. `punctuated?`: the speech-to-text adds punctuation (the
  server's Whisper does; the phone's recogniser doesn't), so a missing final stop is a sign.
  """
  def unfinished?(text, punctuated? \\ true)

  def unfinished?(text, punctuated?) when is_binary(text) do
    t = String.trim(text)
    last = t |> String.downcase() |> String.split(~r/[^\p{L}\p{N}']+/u, trim: true) |> List.last()

    cond do
      t == "" -> false
      Regex.match?(~r/(?:,|…|\.\.\.|-|—)\s*$/u, t) -> true
      last in @dangling -> true
      punctuated? and not Regex.match?(~r/[.?!]["')\]]?\s*$/u, t) -> true
      true -> false
    end
  end

  def unfinished?(_, _), do: false
end
