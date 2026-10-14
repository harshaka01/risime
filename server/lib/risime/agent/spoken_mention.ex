defmodule RisiMe.Agent.SpokenMention do
  @moduledoc """
  A spoken "Risi, …" in an Official voice note counts as @Risi (Harsha 2026-10-14; proposal
  `contract/proposals/2026-10-14-spoken-risi.md`, §37.11), **in Official only** and **only when
  Risi is addressed**, never when Risi is merely mentioned ("I told Risi…", "Risi said…").

  * **Where:** called by `RisiMe.Agent.Voice` for an `ok` §37.6 transcript in an Official `grp:`
    (never Private, never a `dm:`, never the Risi chat, where every note already is a request,
    §37.8). `check/4` re-checks that itself.
  * **Rules first** (`classify/2`, pure, microseconds): a name form (`risi`, `rishi`, `රිසි`,
    `ரிசி`, …) as a whole word at the start of the utterance or of a sentence (after optional
    greetings), followed by a vocative separator and a request, or directly by a request cue.
    Mid-sentence, possessive/suffixed or followed by a reporting word = mentioned. A member whose
    first name is the form heard (a colleague called Rishi) = mentioned.
  * **Unsure** cases go to the Fast router's model (`RISI_ROUTER_MODEL`, loopback) and count only
    at confidence ≥ 0.9; no model, an error or less = **not @Risi**.
  * **Flag** `RISI_SPOKEN_MENTION` = `off | shadow | canary | on` (default `canary`): `canary`
    acts for `RISI_DEV_USERS` and shadow-logs everyone else; `shadow` logs only.
  * **Logged:** one `risi_agent_runs` row per decision (agent `spoken_mention`): codes, language,
    latency and a hashed user. **Never text.**
  """
  require Logger

  alias RisiMe.Agents.{Flags, Router, Runs}

  @flag "RISI_SPOKEN_MENTION"
  @agent "spoken_mention"
  @threshold 0.9
  @max_question 1000
  @rules_version "spoken-rules-v1"

  # Whole-word name forms (Latin case-insensitive, Sinhala, Tamil). Longest first.
  @forms ~w(reesee reesi risee risie rishi rizi risy risi රිසී රීසි රිශි රිසි ரிஷி ரீசி ரிசி)
  @greetings ~w(hey hi hello hallo ok okay oh so um uh well anyway aney ane ado machan
                අනේ හලෝ හායි ආ ஏய் ஹலோ ஹாய்)

  # Request cues, Latin (whole words or phrases).
  @cues_latin [
    "can you",
    "can u",
    "could you",
    "would you",
    "will you",
    "please",
    "pls",
    "plz",
    "remind",
    "tell me",
    "tell us",
    "what",
    "what's",
    "whats",
    "when",
    "where",
    "who",
    "whom",
    "which",
    "why",
    "how",
    "add",
    "schedule",
    "book",
    "send",
    "summarise",
    "summarize",
    "summary",
    "recap",
    "find",
    "search",
    "show",
    "check",
    "note",
    "list",
    "cancel",
    "help",
    "give me",
    "make",
    "create",
    "do you",
    "did i",
    "did we",
    "is there",
    "are there",
    "any",
    "i need",
    "i want",
    "let me know",
    "mata",
    "mage",
    "apita",
    "karanna",
    "karapan",
    "mathak karanna",
    "mathak karapan",
    "matak karanna",
    "kiyanna",
    "kiyapan",
    "balanna",
    "balapan",
    "hoyanna",
    "danna",
    "daanna",
    "yawanna",
    "denna",
    "puluwanda",
    "puluwan da",
    "monada",
    "mokakda",
    "mokadda",
    "kawadada",
    "koheda",
    "kauda",
    "kawda",
    "enakku",
    "sollu",
    "sollunga",
    "paaru",
    "enna",
    "eppo",
    "enga"
  ]

  # Request cues, Sinhala and Tamil (prefixes: the languages are agglutinative).
  @cues_script ~w(මට මටත් අපිට කරුණාකර දෙන්න කියන්න කියපන් බලන්න හොයන්න දාන්න හදන්න යවන්න කරන්න
                  පුළුවන්ද මොකක්ද මොනවද කවදාද කොහෙද කීයටද කවුද ඇයි කොහොමද
                  எனக்கு எங்களுக்கு தயவு நினைவூட்டு நினைவுபடுத்து சொல்லு சொல்லுங்க பாருங்க அனுப்பு என்ன
                  எப்போ எப்போது எங்கே யார் ஏன் எப்படி முடியுமா கொடுங்க சேருங்க)

  # Reporting or descriptive words after the name: Risi is being talked about.
  @reporting ~w(said says say told tells was wasn't is isn't has hasn't had have did didn't does
                doesn't will won't would knows knew thinks thought mentioned replied answered posted
                sent reminded gave got and or also too about from kiwwa kiwa kiyuwa kiyala kiyanawa
                gana ekka sonnadu sonnar patri
                කිව්වා කියනවා කිව්ව කියපු කියලා කියල ගැන එක්ක දන්නවා ලියපු යැව්වා
                சொன்னது சொன்னார் சொன்னாங்க சொல்கிறது சொல்லிச்சு பற்றி கூறினார் கூட)

  # Sentence ends and vocative separators (regex character classes).
  @end_class "[.!?।…\\n]"
  @sep_class "[,:!;\\-–—،]"

  @fillers ~r/^(?:(?:u+m+|u+h+|e+r+m*|a+h+|h+m+|so|okay|ok|well|අ+|ම්+|அ+)(?![\p{L}\p{M}\p{N}])[\s,.]*)+/iu

  @doc "The flag's mode: `:off | :shadow | :canary | :on`."
  def mode, do: Flags.feature(@flag)

  @doc "True when an addressed note of `user` becomes a request (on, or canary and a dev user)."
  def acts_for?(user), do: Flags.feature_for(@flag, user)

  @doc "The confidence the model must reach on an unsure case."
  def threshold, do: @threshold

  ## Entry point (the voice task)

  @doc """
  An Official transcript `t` (`%{status, text, language}`) of `sender`'s note `note_id`.
  `chip_rid` is the note's `request_id` (the @Risi chip was on while recording, §37.11), or nil.
  Returns nil (an ordinary note) or `%{action: :request | :ask, request_id, text}`:

    * `:request`: handled as the sender's `ask` (`text` = the question);
    * `:ask` (never silent): addressed to Risi but the request is unclear, or the address is
      borderline: Risi asks back instead of staying quiet (`text` = what was heard).

  Logs one run row whenever a name form was heard or the chip was on (shadow and acting alike).
  Never raises.
  """
  def check(conv, note_id, sender, t, chip_rid \\ nil)

  def check(conv, note_id, sender, %{status: "ok", text: text} = t, chip_rid)
      when is_binary(text) do
    mode = mode()

    if mode != :off and in_scope?(conv) do
      t0 = System.monotonic_time(:microsecond)

      r =
        if chip?(chip_rid),
          do: chip(text),
          else: decide(text, t[:language], members: member_names(conv, sender), conv: conv)

      ms = (System.monotonic_time(:microsecond) - t0) / 1000
      acts = acts_for?(sender)

      if r.decision != "none", do: record(r, mode, acts, t[:language], sender, ms)

      cond do
        not acts ->
          nil

        r.action == :request and is_binary(r.question) ->
          %{action: :request, request_id: rid(note_id, chip_rid), text: r.question}

        r.action in [:request, :ask] ->
          %{action: :ask, request_id: rid(note_id, chip_rid), text: String.trim(text)}

        true ->
          nil
      end
    end
  rescue
    e ->
      Logger.warning("Risi spoken mention skipped in #{conv}: #{inspect(e.__struct__)}")
      nil
  end

  def check(_conv, _note_id, _sender, _t, _chip_rid), do: nil

  @doc "True when `rid` is a usable chip `request_id` (a UUID)."
  def chip?(rid) when is_binary(rid), do: match?({:ok, _}, Ecto.UUID.cast(rid))
  def chip?(_), do: false

  defp rid(_note_id, chip_rid) when is_binary(chip_rid), do: String.downcase(chip_rid)
  defp rid(note_id, _), do: request_id(note_id)

  # The chip: the whole note is for Risi (no name needed); a leading "Risi, …" is dropped.
  defp chip(text) do
    q =
      case classify(text) do
        %{decision: d, question: q} when d in [:addressed, :unsure, :ask] and is_binary(q) -> q
        _ -> String.trim(text)
      end
      |> String.slice(0, @max_question)

    %{
      action: if(q == "", do: :ask, else: :request),
      addressed: q != "",
      decision: "chip",
      by: "chip",
      reason: "chip",
      question: if(q == "", do: nil, else: q),
      confidence: 1.0
    }
  end

  # Official only: never Private, never a `dm:`, never the Risi chat.
  defp in_scope?("grp:" <> _ = conv),
    do: RisiMe.Agent.official?(conv) and not RisiMe.Groups.Tabs.risi_chat?(conv)

  defp in_scope?(_), do: false

  @doc """
  The request id of a spoken request: a UUID (version-4 layout) derived from the note's id, so a
  repeat is the same request (one answer).
  """
  def request_id(note_id) do
    <<a::48, _::4, b::12, _::2, c::62, _::binary>> =
      :crypto.hash(:sha256, "risi-spoken-mention:" <> String.downcase(note_id))

    Ecto.UUID.cast!(<<a::48, 4::4, b::12, 2::2, c::62>>)
  end

  ## Decision (rules, then the model for unsure cases)

  @doc """
  `%{action: :request | :ask | :none, addressed: bool, decision, by: "rules" | "model", reason,
  question, confidence}`. `decision`: `"addressed"` (rules) or `"unsure_yes"` (the model, ≥ 0.9)
  → `:request`; `"ask"` (addressed, no clear request) or `"unsure_ask"` (borderline) → `:ask`
  (Risi asks back: never silent); `"mentioned"` / `"none"` → `:none` (a mention is never acted
  on). Options: `members` (first names of the conversation's other human members), `model`
  (overrides `RISI_ROUTER_MODEL`; `false` = rules only), `conv`.
  """
  def decide(text, lang \\ nil, opts \\ []) do
    r = classify(text, opts)

    case r.decision do
      :addressed ->
        out(r, :request, "addressed", "rules", 1.0)

      :ask ->
        out(r, :ask, "ask", "rules", 1.0)

      :unsure ->
        case model_check(text, lang, opts) do
          {:ok, true, conf} when conf >= @threshold ->
            out(r, :request, "unsure_yes", "model", conf)

          {:ok, _, conf} ->
            out(r, :ask, "unsure_ask", "model", conf)

          _ ->
            out(r, :ask, "unsure_ask", "rules", nil)
        end

      :mentioned ->
        out(r, :none, "mentioned", "rules", 1.0)

      :none ->
        out(r, :none, "none", "rules", 1.0)
    end
  end

  defp out(r, action, decision, by, conf),
    do: %{
      action: action,
      addressed: action == :request,
      decision: decision,
      by: by,
      reason: r.reason,
      question: if(action == :none, do: nil, else: r.question),
      confidence: conf
    }

  @doc """
  The rules (pure): `%{decision: :addressed | :unsure | :mentioned | :none, reason, question}`.
  `question` is the text after the vocative (to the end), for `:addressed` and `:unsure`.
  """
  def classify(text, opts \\ []) when is_binary(text) do
    text = String.trim(text)
    members = opts |> Keyword.get(:members, []) |> Enum.map(&String.downcase/1)

    hits = for s <- sentence_starts(text), h <- [at_start(text, s)], h != nil, do: h

    result =
      case hits do
        [] -> elsewhere(text)
        _ -> best(Enum.map(hits, &judge/1))
      end

    if result.decision in [:addressed, :unsure, :ask] and result[:form] in members,
      do: %{decision: :mentioned, reason: "member_name", question: nil},
      else: Map.delete(result, :form)
  end

  defp best(results) do
    Enum.find(results, &(&1.decision == :addressed)) ||
      Enum.find(results, &(&1.decision == :unsure)) ||
      Enum.find(results, &(&1.decision == :ask)) || hd(results)
  end

  # Byte offsets where a sentence starts: 0 and after every run of sentence-end characters.
  defp sentence_starts(text) do
    ends =
      for [{at, len}] <- Regex.scan(~r/#{@end_class}+\s*/u, text, return: :index),
          at + len < byte_size(text),
          do: at + len

    [0 | ends]
  end

  defp at_start(text, start) do
    sub = binary_part(text, start, byte_size(text) - start)

    case Regex.run(start_re(), sub, capture: :all_names) do
      [form, rest] -> %{form: String.downcase(form), rest: rest}
      _ -> nil
    end
  end

  defp start_re do
    greet = Enum.map_join(@greetings, "|", &Regex.escape/1)
    forms = Enum.map_join(@forms, "|", &Regex.escape/1)

    ~r/^\s*(?:(?:#{greet})[\s,!.]+)*(?<form>#{forms})(?![\p{L}\p{M}\p{N}'’])(?<rest>.*)$/ius
  end

  # One sentence-start occurrence.
  defp judge(%{form: form, rest: rest}) do
    {sep?, remainder} = split_sep(rest)
    # Speech fillers right after the name ("Risi um, what…") are not words of the request.
    remainder = Regex.replace(@fillers, remainder, "")
    [first_sentence] = Regex.run(~r/^(?:(?!#{@end_class}).)*#{@end_class}?/us, remainder)

    decision =
      cond do
        String.trim(remainder) == "" ->
          {:ask, "no_request"}

        cue_first?(remainder) ->
          {:addressed, if(sep?, do: "vocative_cue", else: "name_cue")}

        sep? and (cue_in?(first_sentence) or question?(first_sentence)) ->
          {:addressed, "vocative_request"}

        sep? ->
          {:unsure, "vocative_no_request"}

        reporting_first?(remainder) ->
          {:mentioned, "reporting"}

        # "Risi ada meeting eke decisions monada?": a question with a question word.
        question?(first_sentence) and cue_in?(first_sentence) ->
          {:addressed, "name_question"}

        cue_in?(first_sentence) ->
          {:unsure, "cue_later"}

        true ->
          {:mentioned, "no_vocative"}
      end

    {d, reason} = decision

    %{
      decision: d,
      reason: reason,
      form: form,
      question: if(d in [:addressed, :unsure, :ask], do: question(remainder))
    }
  end

  # The separator after the name: a vocative mark or a sentence end ("Risi? Remind me…").
  defp split_sep(rest) do
    case Regex.run(~r/^\s*((?:#{@sep_class}|#{@end_class})+)\s*(.*)$/us, rest) do
      [_, _sep, remainder] -> {true, remainder}
      _ -> {false, String.trim_leading(rest)}
    end
  end

  # No sentence-start occurrence: a trailing vocative with a request ("…, Risi?") is unsure; any
  # other occurrence is a mention; no name at all is none.
  defp elsewhere(text) do
    forms = Enum.map_join(@forms, "|", &Regex.escape/1)
    any = ~r/(?<![\p{L}\p{M}\p{N}])(?<form>#{forms})(?![\p{L}\p{M}\p{N}])/iu

    case Regex.run(any, text, capture: :all_names) do
      nil ->
        %{decision: :none, reason: "no_name", question: nil}

      [form] ->
        trailing = ~r/[,،]\s*(?:#{forms})\s*[?.!]*\s*$/iu

        sentence =
          text
          |> String.split(~r/(?<=#{@end_class})\s*/u, trim: true)
          |> Enum.find("", &Regex.match?(trailing, &1))

        if sentence != "" and (cue_in?(sentence) or question?(sentence)) do
          q = Regex.replace(trailing, sentence, "") |> String.trim()

          %{
            decision: :unsure,
            reason: "trailing_vocative",
            question: question(q),
            form: String.downcase(form)
          }
        else
          %{decision: :mentioned, reason: "mid_sentence", question: nil}
        end
    end
  end

  defp cue_latin_re do
    alt = @cues_latin |> Enum.map(&Regex.escape/1) |> Enum.map(&String.replace(&1, "\\ ", "\\s+"))
    "(?:#{Enum.join(alt, "|")})(?![\\p{L}\\p{M}\\p{N}])"
  end

  defp cue_script_re, do: "(?:#{Enum.map_join(@cues_script, "|", &Regex.escape/1)})"

  # A Sinhala word ending in the question particle ද (කීයටද, පුළුවන්ද, …).
  defp si_question_re, do: "\\S*\\p{Sinhala}ද(?![\\p{L}\\p{M}])"

  defp cue_first?(s) do
    Regex.match?(~r/^\s*(?:#{cue_latin_re()}|#{cue_script_re()}|#{si_question_re()})/iu, s)
  end

  defp cue_in?(s) do
    Regex.match?(
      ~r/(?:^|[\s,])(?:#{cue_latin_re()}|#{cue_script_re()}|#{si_question_re()})/iu,
      s
    )
  end

  defp question?(s), do: Regex.match?(~r/\?\s*$/u, String.trim(s))

  defp reporting_first?(s) do
    alt = @reporting |> Enum.map_join("|", &Regex.escape/1)
    Regex.match?(~r/^\s*(?:#{alt})(?![\p{L}\p{M}\p{N}])/iu, s)
  end

  defp question(s) do
    q = s |> String.trim() |> String.slice(0, @max_question)
    if q == "", do: nil, else: q
  end

  ## The model (unsure cases only)

  defp model_check(text, lang, opts) do
    case Keyword.get(opts, :model, Router.model()) do
      m when m in [nil, false] -> :no_model
      m -> ask_model(m, text, lang, opts)
    end
  end

  @schema %{
    "type" => "object",
    "properties" => %{
      "addressed" => %{"type" => "boolean"},
      "confidence" => %{"type" => "number", "minimum" => 0, "maximum" => 1}
    },
    "required" => ["addressed", "confidence"],
    "additionalProperties" => false
  }

  @doc false
  def prompt do
    """
    A voice note was transcribed. Decide whether the speaker ADDRESSES the assistant named Risi with a request (talks to Risi and asks it to do or answer something), or only MENTIONS Risi (talks about Risi to other people). Answer only JSON {"addressed","confidence"}. If unsure, answer false. The transcript may be English, Sinhala, Tamil or Singlish; Risi may be written Risi, Rishi, රිසි or ரிசி.
    """
  end

  defp ask_model(model, text, lang, opts) do
    req =
      %{
        task: "spoken_mention",
        conversation_id: Keyword.get(opts, :conv) || "",
        chat_id: "",
        system: prompt(),
        user: "Transcript (#{lang || "?"}): " <> String.slice(text, 0, 600),
        schema_name: "risi_spoken_mention",
        schema: @schema,
        source_message_ids: [],
        max_tokens: 40,
        timeout_ms: 1_500,
        retries: 0,
        model_alias: model
      }
      |> then(fn r -> if u = Router.model_url(), do: Map.put(r, :base_url, u), else: r end)

    case RisiMe.Agent.LLM.complete(req) do
      {:ok, %{output: %{"addressed" => a, "confidence" => c}}}
      when is_boolean(a) and is_number(c) ->
        {:ok, a, c / 1}

      _ ->
        {:error, :model}
    end
  rescue
    _ -> {:error, :model}
  end

  ## Members and the run row

  # First names of the conversation's human members other than the sender (a colleague called
  # Rishi is never Risi).
  defp member_names(conv, sender) do
    for m <- RisiMe.Agent.Secretary.members(conv),
        m.user_id != sender,
        is_binary(m.name),
        first <- [m.name |> String.split(~r/\s+/u, trim: true) |> List.first()],
        first != nil,
        do: String.downcase(first)
  rescue
    _ -> []
  end

  defp record(r, mode, acts, lang, user, ms) do
    Runs.record(%{
      agent: @agent,
      mode: if(acts, do: Atom.to_string(mode), else: "shadow"),
      decision: r.decision,
      status: "ok",
      reason: r.reason,
      by: r.by,
      latency_ms: ms,
      learned_version: @rules_version,
      lang: lang,
      user: user
    })

    Logger.info(
      "risi spoken mention: decision=#{r.decision} by=#{r.by} reason=#{r.reason} " <>
        "acts=#{acts} lang=#{lang || "-"}"
    )
  end
end
