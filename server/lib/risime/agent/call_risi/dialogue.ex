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
                      | %{slot: "message_pick", options} | %{slot: "message_confirm"}
                      | %{slot: "send_confirm"},
        pending: nil | %{task: "message", to, to_id, to_candidates, text, said, tab, fixed},
        people: [name],      # the people of this call, the one in focus first
        pinned: nil | name,  # a recipient the caller corrected to: wins every near tie
        dictation: nil | %{to, tab, asked?}   # Risi is taking down a message
      }

  **The call's people** (architect 16:05 item 4): every recipient a message of this call went to
  (or Risi picked after asking) is kept, the one in focus first; a recipient the caller
  corrected to ("Nimali, not Kamal") is `pinned`. find_contact's near ties go to them, and "her"
  means the one in focus. A finished task (its card made, or a new request) never captures the
  next words (16:05 item 2).

  **A pending task, not one slot** (architect 11:45): `pending` holds the message being made
  (`to`, the `to_candidates` heard as a near name, the `text`). A question Risi asks about it
  (`awaiting`, with the `question:` it asked) nests inside it and returns to it; off-topic talk
  keeps it open; only "cancel" (or the card being made) drops it. Risi never asks the same
  question twice in a row: the second time it says what it heard and offers a choice.

  **Message slots** `{to, text}`: the text is never made up. A message request without the text
  asks "What should I say to <name>?" and the next words fill it; text the model gives is used
  only when the caller's own words (or the open card's) carry it (`grounded?/2`).
  """

  @empty %{
    card: nil,
    awaiting: nil,
    pending: nil,
    people: [],
    pinned: nil,
    dictation: nil,
    tabs: %{}
  }

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
        "Here's your message to #{to}: #{read_back(text)} " <>
          "Tap Send on the card in your Risi chat to send it, or tell me what to change."

      _ ->
        "Your message is on a card in your Risi chat. Tap Send to send it, or Cancel."
    end
  end

  def message_line(_), do: "Your message is on a card in your Risi chat. Tap Send to send it."

  @doc "The recipient and text of a message card's summary, or nil."
  def message_parts(summary) when is_binary(summary) do
    case Regex.run(~r/^Send to (.+?) \((Private|Official)\):\s*(.+)$/su, summary) do
      [_, to, tab, text] -> %{to: to, tab: String.downcase(tab), text: String.trim(text)}
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

  ## Grounding: the text of a message is the caller's own words (a soft check)

  # Pronouns (her ↔ you), politeness and filler words don't count.
  @stop ~w(the a an and or but to of in on at for is are am was were be been it its this that
           with my your yours i you he she we they me him her us them his hers their so just say
           tell hey hi oh ok okay please kindly thanks thank pls plz then also really actually
           know let ask message text saying said says)

  @doc """
  True when `text` (a message's text the model gave) comes from `source` (the whole call so far:
  the caller's words, the recipient's name, the open card's text): at least 70% of its content
  words appear there, after contractions ("didn't" = did not), pronoun shifts (her ↔ you) and
  politeness words are normalised and words lightly stemmed ("called" = "call"). A soft check:
  when it fails while Risi waits for the text, Risi reads the text back and asks (never the
  same question again). A model that wrote "Hi Nimali, how are you" for "Can you send a
  message to Nimali?" fails.
  """
  def grounded?(text, source) when is_binary(text) and is_binary(source) do
    words = content_words(text)
    have = MapSet.new(content_words(source))

    case words do
      [] -> String.trim(text) != "" and String.length(String.trim(text)) <= 40
      _ -> Enum.count(words, &MapSet.member?(have, &1)) / length(words) >= 0.7
    end
  end

  def grounded?(_text, _source), do: false

  defp content_words(s) do
    s
    |> String.downcase()
    |> String.replace(~r/[’‘]/u, "'")
    |> String.replace(~r/\b(ca|wo|sha)n't\b/u, "\\1n not")
    |> String.replace(~r/n't\b/u, " not")
    |> String.replace(~r/'(?:ll|re|ve|d|m|s)\b/u, "")
    |> String.split(~r/[^\p{L}\p{N}']+/u, trim: true)
    |> Enum.map(&String.trim(&1, "'"))
    |> Enum.reject(&(&1 == "" or &1 in @stop))
    |> Enum.map(&stem/1)
    |> Enum.map(&Map.get(same(), &1, &1))
  end

  # Words that mean the same in a reworded message.
  defp same,
    do: %{
      "won" => "will",
      "never" => "not",
      "no" => "not",
      "phone" => "call",
      "ring" => "call",
      "twice" => "couple",
      "few" => "couple",
      "pick" => "answer",
      "reply" => "answer",
      "respond" => "answer"
    }

  defp stem(w) do
    cond do
      String.length(w) > 5 and String.ends_with?(w, "ing") ->
        String.slice(w, 0..-4//1)

      String.length(w) > 4 and String.ends_with?(w, "ed") ->
        String.slice(w, 0..-3//1)

      String.length(w) > 3 and String.ends_with?(w, "s") and not String.ends_with?(w, "ss") ->
        String.slice(w, 0..-2//1)

      true ->
        w
    end
  end

  @doc """
  The caller's own words as the message text while Risi waits for it: only a leading "tell
  her/him that", "say that", "just say" (and "the message is") goes; nothing else is changed.
  """
  def strip_lead(text) when is_binary(text) do
    t =
      Regex.replace(
        ~r/^\s*(?:(?:okay|ok|so|yes|yeah|well|um|uh|and)[,.]?\s+)*(?:(?:please|just|can you|could you)\s+)*(?:tell (?:her|him|them)(?: that)?|say(?: that)?|let (?:her|him|them) know(?: that)?|the message (?:is|should be)|the text (?:is|should be)|message)[,:]?\s+/iu,
        String.trim(text),
        ""
      )

    t = String.trim(t)

    if t == "" do
      String.trim(text)
    else
      {f, rest} = String.split_at(t, 1)
      String.upcase(f) <> rest
    end
  end

  def strip_lead(_), do: ""

  ## The pending task (architect 11:45: a task, not one slot)

  @doc "A new message task."
  def message_task(fields \\ %{}),
    do:
      Map.merge(
        %{
          task: "message",
          to: nil,
          to_id: nil,
          to_candidates: [],
          text: nil,
          said: nil,
          tab: nil,
          fixed: nil
        },
        fields
      )

  @doc """
  True for words that don't answer anything ("Hello?", "Sorry?", "What?", "Can you hear me?"):
  Risi says what it heard and offers a choice, keeping the task.
  """
  def non_answer?(text) when is_binary(text) do
    t =
      text
      |> String.downcase()
      |> String.replace(~r/[^\p{L}\p{N}' ]+/u, " ")
      |> String.split()
      |> Enum.join(" ")

    Regex.match?(
      ~r/^(?:(?:hello|hallo|hullo|hi|hey|risi|rishi|sorry|pardon|what|huh|hmm|um|uh|eh)\s*)+$|^(?:sorry |hello )?(?:what did you say|come again|say that again|(?:can|could) you (?:repeat|say) (?:that|it)(?: again)?|(?:can|could) you hear me|are you there|i didn'?t (?:hear|catch) (?:you|that)|pardon me)$/u,
      t
    )
  end

  def non_answer?(_), do: false

  @doc """
  What Risi says instead of asking the same question twice in a row (architect 11:45): what it
  heard and a choice.
  """
  def heard_line(heard, d), do: heard_lead(heard) <> choice(d)

  defp heard_lead(heard) do
    words = heard |> to_string() |> String.split()

    case words do
      [] ->
        "Sorry, I didn't get that. "

      _ when length(words) > 10 ->
        "I heard " <> quoted(Enum.join(Enum.take(words, 8), " ") <> "…") <> " "

      _ ->
        "I heard " <> quoted(Enum.join(words, " ")) <> " "
    end
  end

  @doc """
  What Risi says when it would ask the same thing again (`tries` = 1, 2, …): never the same
  question twice in a row. The first retry says what Risi heard (or the message it holds) and
  offers a choice; later ones say what is still missing, with no question.
  """
  def retry_line(d, heard, tries)

  def retry_line(%{awaiting: %{slot: "message_to"}, pending: %{text: t}}, _heard, 1)
      when is_binary(t),
      do: "I have your message: #{quoted(short(t))} Who is it for?"

  def retry_line(%{awaiting: %{slot: "message_to"}}, heard, 1),
    do: heard_lead(heard) <> "Who is the message for? You can also spell the name."

  # Later retries alternate (never the same line twice in a row) and say the message is kept.
  def retry_line(%{awaiting: %{slot: "message_to"}} = d, _heard, n) do
    kept = if is_binary((d[:pending] || %{})[:text]), do: "I have the message ready. ", else: ""

    if rem(n, 2) == 0,
      do: kept <> "I still need the person's name. Say it or spell it, or say cancel.",
      else: kept <> "Who gets it? Say \"to\" and their name, or say cancel."
  end

  def retry_line(%{awaiting: %{slot: "message_text", to: to}}, _heard, n) when n >= 2,
    do: "I still need the words of the message to #{to}. Say them, or say cancel."

  def retry_line(%{awaiting: %{slot: "message_pick", options: os}}, _heard, n) when n >= 2,
    do: "Please say one of the names: #{or_names(os)}. Or say cancel."

  def retry_line(d, heard, _), do: heard_line(heard, d)

  defp short(t) do
    w = String.split(t)
    if length(w) > 14, do: Enum.join(Enum.take(w, 12), " ") <> "…", else: t
  end

  defp choice(%{awaiting: %{slot: "message_text", to: to}}),
    do: "Tell me what the message to #{to} should say, or say cancel."

  defp choice(%{awaiting: %{slot: "message_to"}}),
    do: "Tell me who the message is for, or say cancel."

  defp choice(%{awaiting: %{slot: "message_pick", options: [a | _] = os}}),
    do: "Say #{or_names(os)}, or spell the name. Or say cancel. (For example: #{a}.)"

  defp choice(%{awaiting: %{slot: "message_confirm"}}),
    do: "Say yes to put the message on a card, or tell me what to change."

  defp choice(%{awaiting: %{slot: "day"}}),
    do: "Tell me the day, like today or Friday, or say cancel."

  defp choice(_), do: "Please say it another way, or say cancel."

  @doc "\"A or B\" / \"A, B or C\"."
  def or_names([one]), do: one
  def or_names(l), do: Enum.join(Enum.drop(l, -1), ", ") <> " or " <> List.last(l)

  @doc "The question when 2–3 people match the name heard."
  def ask_pick(options), do: "Did you mean #{or_names(options)}?"

  @doc "The read-back of the whole message before its card (the draft is not made yet)."
  def confirm_line(to, text), do: "I'll send #{to}: #{quoted(text)} Shall I put it on a card?"

  # "\"Call me.\"" / "\"Call me\".": one full stop, never ".\".".
  @doc false
  def quoted(text) do
    t = String.trim(text)
    if Regex.match?(~r/[.?!]$/u, t), do: "\"#{t}\"", else: "\"#{t}\"."
  end

  @doc "A yes to Risi's yes-or-no question (no \"but\", \"wait\" or a change after it)."
  def yes?(text) when is_binary(text) do
    t = text |> String.downcase() |> String.trim()

    Regex.match?(
      ~r/^(?:yes|yeah|yep|yup|ya|sure|ok|okay|correct|right|exactly|that'?s (?:right|correct|it|fine)|that is (?:right|correct)|go ahead|please do|do it|put it|draft it|perfect|absolutely)\b/u,
      t
    ) and not Regex.match?(~r/\b(?:but|wait|change|not|no|instead|wrong|fix|edit|modify)\b/u, t) and
      length(String.split(t)) <= 8
  end

  def yes?(_), do: false

  @doc "A yes to \"Shall I fix the misheard words?\" (\"Yes, fix them\", \"Please fix it\")."
  def fix_yes?(text) when is_binary(text) do
    t = text |> String.downcase() |> String.trim()

    yes?(t) or
      (Regex.match?(
         ~r/^(?:(?:yes|yeah|yep|ok|okay|sure|please)[,.!]?\s+)*(?:please\s+)?(?:fix|correct)\b/u,
         t
       ) and not Regex.match?(~r/\b(?:don'?t|do not|no|not)\b/u, t) and
         length(String.split(t)) <= 6)
  end

  def fix_yes?(_), do: false

  @doc "A bare no to Risi's yes-or-no question (\"No.\", \"No, that's wrong\")."
  def no?(text) when is_binary(text) do
    t = text |> String.downcase() |> String.replace(~r/[^\p{L}' ]+/u, " ") |> String.trim()

    Regex.match?(~r/^(?:no|nope|nah|wrong|not (?:right|that|correct))\b/u, t) and
      length(String.split(t)) <= 4
  end

  def no?(_), do: false

  @doc """
  True when the caller says they already gave the message ("I just said it to you", "I told
  you"): the text is their earlier words.
  """
  def said_already?(text) when is_binary(text),
    do:
      Regex.match?(
        ~r/\bi(?:'ve| have)? (?:just |already )?(?:said|told) (?:it|that|you|this)\b|\bi (?:just|already) (?:said|told)\b/iu,
        text
      )

  def said_already?(_), do: false

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

  def awaiting_line(%{awaiting: %{slot: "message_pick", options: os}}),
    do: "which person the message is for: #{or_names(os)} (Risi asked)"

  def awaiting_line(%{awaiting: %{slot: "message_confirm"}, pending: %{to: to, text: t}}),
    do: "a yes or no: Risi read back the message to #{to}: \"#{t}\" and asked to put it on a card"

  def awaiting_line(%{awaiting: %{slot: "send_confirm", to: to}}),
    do:
      "a yes or no: Risi read back the message to #{to} and asked \"Send it?\" " <>
        "(yes = confirm_open; a change = message_draft)"

  def awaiting_line(%{awaiting: %{slot: "fix_confirm"}}),
    do: "a yes or no: Risi asked whether to fix the misheard words of the message"

  def awaiting_line(%{awaiting: %{slot: "day", pending: p}}),
    do: "the day for this request: \"#{p}\" (Risi asked which day)"

  def awaiting_line(%{awaiting: %{slot: "answer", question: q}}),
    do: "an answer to Risi's question: \"#{q}\""

  def awaiting_line(_), do: "nothing"

  @doc "The open task, in one line for the model."
  def pending_line(%{pending: %{task: "message"} = p}) do
    to =
      cond do
        is_binary(p.to) -> p.to
        p.to_candidates != [] -> "#{or_names(p.to_candidates)} (not sure which)"
        true -> "nobody yet"
      end

    text = if is_binary(p.text), do: "\"#{p.text}\"", else: "no text yet"
    "a message to #{to}, #{text} (no card yet)"
  end

  def pending_line(_), do: "none"

  ## The people of this call (architect 16:05 item 4)

  @doc "The person in focus (the pinned one, else the last recipient of this call), or nil."
  def focus(%{pinned: p}) when is_binary(p), do: p
  def focus(%{people: [p | _]}), do: p
  def focus(_), do: nil

  @doc "`name` is now in focus (first); `pin?` pins it (a correction)."
  def add_person(d, name, pin? \\ false)

  def add_person(d, name, pin?) when is_binary(name) and name != "" do
    d = get(d)
    d = %{d | people: [name | Enum.reject(d.people, &(&1 == name))] |> Enum.take(5)}
    if pin?, do: %{d | pinned: name}, else: d
  end

  def add_person(d, _name, _pin?), do: get(d)

  @doc """
  The chat the caller chose for `name` in this call ("send it to work" once: the next messages to
  that person on the call go to Official too), or nil.
  """
  def tab_for(d, name) when is_binary(name), do: Map.get(get(d).tabs || %{}, name)
  def tab_for(_d, _name), do: nil

  @doc "Remembers the chat chosen for `name` (Official or Private)."
  def remember_tab(d, name, tab) when is_binary(name) and tab in ["official", "private"],
    do: %{get(d) | tabs: Map.put(get(d).tabs || %{}, name, tab)}

  def remember_tab(d, _name, _tab), do: get(d)

  @doc "The call's people, in one line for the model."
  def people_line(%{people: [_ | _] = ps} = d) do
    pinned = if is_binary(d[:pinned]), do: " (#{d.pinned} confirmed by the user)", else: ""
    "#{Enum.join(ps, ", ")}; in focus: #{focus(d)}#{pinned}"
  end

  def people_line(_), do: "none yet"

  @doc """
  The composed dictation without its end signal at the very end ("… and that is the message I
  want sent.", "Okay, that's it."): the caller's words for Risi, never message text.
  """
  def strip_end(text) when is_binary(text) do
    Regex.replace(
      ~r/(?:^|(?<=[.!?,;])|\s+(?:and|so)\b)\s*(?:(?:and|so|okay|ok|yeah|right)[,.]?\s+)*(?:that'?s it|that is it|that'?s all|that is all|all done|i'?m done|done|okay|ok|that'?s the message|that is the message(?: that)?(?: i want)?(?: to (?:get it |be |have it )?sent| sent)?|send it|i told you (?:exactly )?what (?:needs|i want) to be said)[\s.!]*$/iu,
      String.trim(text),
      ""
    )
    |> String.trim()
    |> case do
      "" -> String.trim(text)
      t -> if Regex.match?(~r/[.?!]$/u, t), do: t, else: t <> "."
    end
  end

  def strip_end(_), do: ""

  @doc "True when the words carry an end signal (\"that's it\", \"all done\", \"that's the message\" …)."
  def end_signal?(text) when is_binary(text) do
    strip_end(text) !=
      String.trim(text) |> then(&if(Regex.match?(~r/[.?!]$/u, &1), do: &1, else: &1 <> ".")) or
      Regex.match?(
        ~r/\b(?:that'?s it|that is it|that'?s all|that is all|all done|i'?m done|that'?s the message|that is the message|i told you (?:exactly )?what)\b/iu,
        text
      )
  end

  def end_signal?(_), do: false

  ## Lines

  @doc "What Risi says for a request no tool covers (architect 16:05 item 6)."
  def unsupported_line,
    do:
      "I can't do that yet. On a call I can read your calendar, your promises and your Official " <>
        "messages, add events and reminders, and draft or send messages for you."

  @doc """
  The read-back of a message Risi may send itself (contract v1.55 §41.11): "I'll send Nimali on
  Official: '…'. Send it?". A long text is read as one line (its start, then how much more).
  """
  def send_question(to, text), do: "I'll send #{to} on Official: #{read_back(text)} Send it?"

  @doc "A message text read back: whole when short, else its first words and how many more."
  def read_back(text) do
    w = String.split(String.trim(text))

    if length(w) > 30,
      do:
        quoted(Enum.join(Enum.take(w, 22), " ") <> " …") <> " And #{length(w) - 22} more words.",
      else: quoted(text)
  end

  @doc "When the caller asks Risi to send a Private message now (only their tap sends it)."
  def private_tap_line(to) do
    who = if is_binary(to) and to != "", do: "your Private chat with #{to}", else: "your chat"

    "I can only draft it for #{who}. Tap Send on the card in your Risi chat to send it."
  end

  @doc "Risi asks whether to use the corrected text (a dictation with misheard words)."
  def fix_question(fixed),
    do: "Some words may be misheard. Shall I fix them? It would say: #{read_back(fixed)}"

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
