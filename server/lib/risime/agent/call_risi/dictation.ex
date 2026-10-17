defmodule RisiMe.Agent.CallRisi.Dictation do
  @moduledoc """
  **Dictation mode** of Call Risi (Harsha's call 22e016e0, 16 Oct 10:20Z): while Risi takes a
  message down, the caller's pauses start **no turn** (on that call every pause-split utterance
  restarted the turn: "replaced", 2–7 s to the first audio, and "I'm listening. Please go ahead."
  twice). The session (`RisiMe.Agent.CallRisi.Session`) owns the timers; this module is the pure
  state:

      %{to, tab, asked?, parts: [%{text, fixed, raw}], new: [words], checking: nil | n, gen,
        mmhm?, utt}

    * Words arrive (`add/3`) into `new`. Nothing is said for them.
    * After a pause (`pause_ms`, 700 ms with no speech) the new words are checked once by the
      model (`RisiMe.Agent.CallRisi.Decider`, mode `:dictation`), which labels them: `more` (the
      next piece of the message), `restart` (the caller starts the message over: the earlier
      pieces go), `end` (an end signal: "okay", "that's it", "send it", "all done", or a whole
      short answer when Risi asked what to say), `instruction` (a request to Risi, never message
      text: architect 16:05 item 1) or `noise` (dropped). It also gives the piece's message words
      (lead-ins, talk to Risi and the end signal removed) and, when a word looks misheard, the
      piece corrected. `apply/4` says what follows.
    * After a 3 s pause, at most one "Mm-hm." (never two in a row without new words).
    * After ~6 s of silence the dictation ends.

  **The text is built from the pieces**, never generated whole: each piece's words are checked
  against what the caller said (`Dialogue.grounded?/2`, else the words as said), and the message
  is the pieces in order (one short model output per pause, no long one at the end).
  """

  alias RisiMe.Agent.CallRisi.Dialogue

  @doc "A dictation for `to` (nil: not said yet), with the words already given."
  def new(%{} = m) do
    parts =
      for t <- m[:parts] || [], is_binary(t), t != "" do
        {body, flags} = Dialogue.split_instructions(t)
        %{text: body, fixed: "", raw: t, flags: flags}
      end

    %{
      to: m[:to],
      tab: m[:tab],
      asked?: m[:asked?] == true,
      parts: parts,
      new: [],
      checking: nil,
      gen: 0,
      mmhm?: false,
      utt: nil
    }
  end

  @doc "The caller's next words (one utterance)."
  def add(d, text, utt) when is_binary(text) and text != "",
    do: %{d | new: d.new ++ [String.trim(text)], mmhm?: false, utt: utt}

  def add(d, _text, _utt), do: d

  @doc "Everything said in this dictation so far (the pieces as heard, then the unchecked words)."
  def raw(d), do: Enum.join(Enum.map(d.parts, & &1.raw) ++ d.new, " ")

  @doc "The message so far (the pieces' message words), as the model is shown it."
  def texts(d), do: Enum.map(d.parts, & &1.text)

  @doc "The words the next check looks at, and how many utterances they are."
  def pending(d), do: {Enum.join(d.new, " "), length(d.new)}

  @doc """
  The result of a check of the first `n` new utterances: `{:more, d}` (keep listening),
  `{:finish, d, %{text, fixed, raw, to, tab}}`, `{:instruction, d, words}` (leave dictation;
  `words` are a turn of their own). `ended?`: the check came from silence.
  """
  def apply(d, n, result, ended?) do
    {checked, rest} = Enum.split(d.new, n)
    words = Enum.join(checked, " ")
    d = %{d | checking: nil, new: rest}

    # "Hello?", "Can you hear me?" when Risi asked what to say: no text; Risi answers it.
    # … and "No, …" / "That's not true" / a spelled name (architect 04:20 item 7) is a correction
    # of the task, never the answer's text.
    result =
      if d.asked? and d.parts == [] and
           (Dialogue.non_answer?(words) or Dialogue.correction?(words)),
         do: {:ok, %{kind: "instruction", text: "", fixed: ""}},
         else: result

    case result do
      {:ok, %{kind: "instruction"}} ->
        {:instruction, %{d | new: []}, Enum.join(checked ++ rest, " ")}

      # "Hello?" when Risi asked what to say is no noise: Risi answers it (says what it heard).
      {:ok, %{kind: "noise"}} when d.asked? and d.parts == [] ->
        if Dialogue.non_answer?(words),
          do: {:instruction, %{d | new: []}, Enum.join(checked ++ rest, " ")},
          else: {:more, d}

      {:ok, %{kind: "noise"}} ->
        if ended? and rest == [] and d.parts != [], do: finish(d), else: {:more, d}

      {:ok, %{kind: kind} = r} when kind in ~w(more restart end) ->
        d = if kind == "restart", do: %{d | parts: []}, else: d
        d = add_piece(d, piece(words, r, kind == "end"))

        # A dictation the caller announced ends on an end signal (or silence), never because
        # the model took a pause for the end (22e016e0: a noise piece "ended" it).
        # Item 1 (04:0x call): never end on a fragment ("… the quality of messages that").
        end? =
          kind == "end" and rest == [] and
            (ended? or d.asked? or Dialogue.end_signal?(words)) and
            (Dialogue.end_signal?(words) or not Dialogue.unfinished?(words))

        if end? or (ended? and rest == []), do: finish(d), else: {:more, d}

      _ ->
        # The model failed: the words are kept as said; silence still ends it.
        {t, flags} = Dialogue.split_instructions(Dialogue.strip_lead(words))
        d = add_piece(d, %{text: t, fixed: "", raw: words, flags: flags})
        if ended? and rest == [], do: finish(d), else: {:more, d}
    end
  end

  # The piece's message words: the model's when the caller said them, else the words as said.
  defp piece(words, r, last?) do
    text =
      cond do
        r.text != "" and Dialogue.grounded?(r.text, words) -> r.text
        # The model found no message words in an end signal or in "let me start again".
        r.text == "" and (r.kind == "restart" or (last? and Dialogue.end_signal?(words))) -> ""
        true -> Dialogue.strip_lead(words)
      end

    text = if last?, do: Dialogue.strip_end(text), else: text
    # Item 2: the caller's sending instructions are never message text (they set the route).
    {text, flags} = Dialogue.split_instructions(text)
    {fixed, _} = Dialogue.split_instructions(r.fixed || "")
    %{text: text, fixed: fixed, raw: words, flags: flags}
  end

  # A piece with no message words (only an end signal) keeps its words for the transcript.
  defp add_piece(d, p), do: %{d | parts: d.parts ++ [p]}

  @doc "The dictation ends now (silence after the last piece was checked)."
  def close(d), do: finish(d)

  defp finish(d) do
    kept = Enum.reject(d.parts, &(String.trim(&1.text) == ""))
    text = kept |> Enum.map(& &1.text) |> Enum.join(" ") |> String.trim()

    fixed? = Enum.any?(kept, &(&1.fixed != "" and norm(&1.fixed) != norm(&1.text)))

    fixed =
      if fixed?,
        do: kept |> Enum.map(&if(&1.fixed != "", do: &1.fixed, else: &1.text)) |> Enum.join(" "),
        else: ""

    flags = Enum.map(d.parts, &Map.get(&1, :flags, %{}))

    {:finish, d,
     %{
       text: text,
       fixed: fixed,
       raw: raw(d),
       to: d.to,
       tab: if(Enum.any?(flags, &(&1[:official?] == true)), do: "official", else: d.tab),
       send_now: Enum.any?(flags, &(&1[:send_now?] == true))
     }}
  end

  defp norm(s),
    do: s |> String.downcase() |> String.replace(~r/[^\p{L}\p{N}]+/u, " ") |> String.trim()
end
