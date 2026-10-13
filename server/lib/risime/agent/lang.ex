defmodule RisiMe.Agent.Lang do
  @moduledoc """
  The asker's language for Risi's answers (Risi Brain v2, step 2; docs/RISI-MODELS.md: risi-l1
  answers 22–33 % of Sinhala and Tamil requests in English).

  * `detect/1`: `"si"` when the text has Sinhala letters (U+0D80–U+0DFF), `"ta"` for Tamil
    (U+0B80–U+0BFF), the one with more letters when both appear; otherwise `"en"` (Singlish is
    written in Latin letters and answered in English).
  * `rule/1`: the system-prompt line that names the language **and its script**.
  * `wrong_script?/2`: the post-check: a Sinhala/Tamil request whose answer has (almost) none of
    that script's letters. The turn then asks the model once to rewrite it (`retry_instruction/1`).
  * `t/2`: the server's own fixed sentences in en/si/ta.
  """

  @doc "The language of `text`: \"si\", \"ta\" or \"en\"."
  def detect(text) when is_binary(text) do
    {si, ta} = counts(text)

    cond do
      si == 0 and ta == 0 -> "en"
      si >= ta -> "si"
      true -> "ta"
    end
  end

  def detect(_), do: "en"

  defp counts(text) do
    text
    |> String.to_charlist()
    |> Enum.reduce({0, 0}, fn
      c, {s, t} when c in 0x0D80..0x0DFF -> {s + 1, t}
      c, {s, t} when c in 0x0B80..0x0BFF -> {s, t + 1}
      _, acc -> acc
    end)
  end

  @doc "The system-prompt rule for `lang`."
  def rule("si"),
    do:
      "- LANGUAGE: the asker wrote in Sinhala. Write the whole answer and next_steps in Sinhala, " <>
        "in Sinhala script (සිංහල අකුරින්), never in English. Names, times and dates may stay as they are."

  def rule("ta"),
    do:
      "- LANGUAGE: the asker wrote in Tamil. Write the whole answer and next_steps in Tamil, in " <>
        "Tamil script (தமிழ் எழுத்தில்), never in English. Names, times and dates may stay as they are."

  def rule(_), do: "- LANGUAGE: answer in the language of the question."

  @doc "True when `lang` is si/ta and `answer` has under 20 % of its letters in that script."
  def wrong_script?(lang, answer) when lang in ~w(si ta) and is_binary(answer) do
    {si, ta} = counts(answer)
    letters = answer |> String.replace(~r/[^\p{L}\p{M}]/u, "") |> String.length()
    own = if lang == "si", do: si, else: ta
    letters > 0 and own / letters < 0.2
  end

  def wrong_script?(_lang, _answer), do: false

  @doc "The corrective instruction after a wrong-script answer."
  def retry_instruction("si"),
    do:
      "Your answer was not in Sinhala. Reply again with the same JSON action, but write answer " <>
        "and next_steps in Sinhala script (සිංහල). Keep the same facts and sources."

  def retry_instruction(_),
    do:
      "Your answer was not in Tamil. Reply again with the same JSON action, but write answer " <>
        "and next_steps in Tamil script (தமிழ்). Keep the same facts and sources."

  @doc """
  A user's own language (Risi Brain v2 step 3, §35.4 "in that participant's language"): the
  script of their last 40 remembered Official messages. "si" or "ta" when at least 30 % of them
  are in that script, else "en" (Singlish is Latin script).
  """
  def user_lang(user) when is_binary(user) do
    import Ecto.Query

    from(m in RisiMe.Agent.Memory.Message,
      where: m.sender_id == ^user and m.from_risi == false,
      order_by: [desc: m.at],
      limit: 40
    )
    |> lang_of()
  end

  def user_lang(_), do: "en"

  @doc """
  A chat's language (decision 071 amendment d): the script of its last 40 remembered human
  messages, with `user_lang/1`'s thresholds ("en" when nothing is remembered).
  """
  def conv_lang(conv) when is_binary(conv) do
    import Ecto.Query

    from(m in RisiMe.Agent.Memory.Message,
      where: m.conversation_id == ^conv and m.from_risi == false,
      order_by: [desc: m.at],
      limit: 40
    )
    |> lang_of()
  end

  def conv_lang(_), do: "en"

  defp lang_of(query) do
    langs =
      query
      |> RisiMe.Repo.all()
      |> Enum.flat_map(fn m ->
        case RisiMe.Agent.Seal.open_text("risi_memory_messages", m.id, "body", m.body_sealed) do
          {:ok, t} -> [detect(t)]
          _ -> []
        end
      end)

    n = length(langs)
    f = Enum.frequencies(langs)

    cond do
      n == 0 -> "en"
      Map.get(f, "si", 0) >= Map.get(f, "ta", 0) and Map.get(f, "si", 0) / n >= 0.3 -> "si"
      Map.get(f, "ta", 0) / n >= 0.3 -> "ta"
      true -> "en"
    end
  rescue
    _ -> "en"
  end

  @doc """
  The speech service's `language_hint` (decision 071 amendment d, `WHISPER_HINT_MODE=prior`):
  the Sinhala/Tamil languages the server knows for this call or note, in this order, without
  duplicates:

  1. each of the `users`: the script of their typed Official messages (`user_lang/1`), then the
     language of their own earlier transcribed speech (`speech_lang/1`: calls and voice notes);
  2. the Official chat: the script of its remembered messages (`conv_lang/1`), then the speech
     languages of its earlier transcribed calls and notes (`chat_speech_lang/1`).

  The speech signals matter most: people who type English or Singlish (Latin letters, "en") but
  speak Sinhala leave no script trace (pilot 2026-10-13: every call participant's typed messages
  were Latin script, so the hint was always empty). `[]` when none is known: only si/ta steer the
  service ("en" changes nothing there), so an all-English call sends no hint.
  """
  def speech_hint(users, conv) do
    per_user = Enum.flat_map(List.wrap(users), &[user_lang(&1), speech_lang(&1)])

    (per_user ++ [conv_lang(conv), chat_speech_lang(chat_of(conv))])
    |> Enum.filter(&(&1 in ~w(si ta)))
    |> Enum.uniq()
  end

  defp chat_of(conv) when is_binary(conv) do
    RisiMe.Agent.Secretary.chat_id(conv)
  rescue
    _ -> conv
  end

  defp chat_of(_), do: nil

  # A spoken language counts once it has at least 10 s and 10 % of the transcribed speech: the
  # speech service rarely labels English speech as Sinhala or Tamil, but often labels Sinhala as
  # English without a hint, and in `prior` mode a hinted si/ta leaves clear English unchanged.
  @speech_min_ms 10_000
  @speech_share 0.1
  # A user's tally halves past 2 h of speech, so recent calls weigh more.
  @speech_decay_ms 2 * 3_600_000

  @doc """
  A user's spoken language: "si" or "ta" when their transcribed call turns and voice notes
  (`note_speech/2`) hold at least 10 s and 10 % of that language, else "en".
  """
  def speech_lang(user) when is_binary(user) do
    case Ecto.UUID.dump(user) do
      {:ok, uid} ->
        %{rows: rows} =
          RisiMe.Repo.query!(
            "SELECT language, speech_ms FROM risi_speech_langs WHERE user_id = $1",
            [uid]
          )

        rows |> Map.new(fn [l, ms] -> {l, ms} end) |> spoken()

      :error ->
        "en"
    end
  rescue
    _ -> "en"
  end

  def speech_lang(_), do: "en"

  @doc """
  A chat's spoken language: the `audio_s` per language of the learning log's `transcribe` rows
  (calls and voice notes) of `chat_id`, this month and last, with `speech_lang/1`'s rule.
  """
  def chat_speech_lang(chat_id) when is_binary(chat_id) do
    month = Date.utc_today() |> Date.beginning_of_month()

    [month, Date.add(month, -1)]
    |> Enum.flat_map(
      &RisiMe.Agent.LearningLog.list_by_chat(chat_id, Calendar.strftime(&1, "%Y-%m"), 500)
    )
    |> Enum.filter(&(&1[:task] == "transcribe"))
    |> Enum.reduce(%{}, fn row, acc ->
      Map.merge(acc, audio_ms(row[:meta]), fn _, a, b -> a + b end)
    end)
    |> spoken()
  rescue
    _ -> "en"
  end

  def chat_speech_lang(_), do: "en"

  # A call row: `"audio_s" => %{lang => s}`; a voice-note row: `"language"` and `"audio_s" => s`.
  defp audio_ms(meta) when is_binary(meta) do
    case Jason.decode(meta) do
      {:ok, m} -> audio_ms(m)
      _ -> %{}
    end
  end

  defp audio_ms(%{"audio_s" => %{} = by_lang}),
    do: for({l, s} <- by_lang, is_number(s), into: %{}, do: {l, round(s * 1000)})

  defp audio_ms(%{"audio_s" => s, "language" => l}) when is_number(s) and is_binary(l),
    do: %{l => round(s * 1000)}

  defp audio_ms(_), do: %{}

  defp spoken(ms) do
    total = ms |> Map.values() |> Enum.sum()
    si = Map.get(ms, "si", 0)
    ta = Map.get(ms, "ta", 0)
    {lang, top} = if si >= ta, do: {"si", si}, else: {"ta", ta}

    if total > 0 and top >= @speech_min_ms and top / total >= @speech_share,
      do: lang,
      else: "en"
  end

  @doc """
  Adds `user`'s transcribed speech (`%{lang => ms}`; si/ta/en) to their tally in
  `risi_speech_langs` (language codes and milliseconds, never text). Best effort: `:ok`.
  """
  def note_speech(user, lang_ms) when is_binary(user) and is_map(lang_ms) do
    with {:ok, uid} <- Ecto.UUID.dump(user) do
      now = DateTime.utc_now()

      for {l, ms} <- lang_ms, l in ~w(si ta en), is_integer(ms), ms > 0 do
        RisiMe.Repo.query!(
          "INSERT INTO risi_speech_langs (user_id, language, speech_ms, updated_at) " <>
            "VALUES ($1, $2, $3, $4) ON CONFLICT (user_id, language) DO UPDATE SET " <>
            "speech_ms = risi_speech_langs.speech_ms + EXCLUDED.speech_ms, " <>
            "updated_at = EXCLUDED.updated_at",
          [uid, l, ms, now]
        )
      end

      RisiMe.Repo.query!(
        "UPDATE risi_speech_langs SET speech_ms = speech_ms / 2 WHERE user_id = $1 AND " <>
          "(SELECT sum(speech_ms) FROM risi_speech_langs WHERE user_id = $1) > $2",
        [uid, @speech_decay_ms]
      )
    end

    :ok
  rescue
    e ->
      require Logger
      Logger.warning("Risi speech languages not noted: #{inspect(e.__struct__)}")
      :ok
  end

  def note_speech(_user, _lang_ms), do: :ok

  @doc "The language's name for a model prompt."
  def name("si"), do: "Sinhala (සිංහල script)"
  def name("ta"), do: "Tamil (தமிழ் script)"
  def name(_), do: "English"

  @months ~w(Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec)

  @doc "A short date: `5 Oct` (the same in every language, as Risi's other answers)."
  def date(%Date{} = d), do: "#{d.day} #{Enum.at(@months, d.month - 1)}"

  @doc "The server's fixed sentences."
  def t(:nothing, "si"), do: "ඒ ගැන ඔබේ Official චැට් වලින් මට මතක ඇති කිසිවක් නැහැ."
  def t(:nothing, "ta"), do: "உங்கள் Official அரட்டைகளில் இதைப் பற்றி எனக்கு எதுவும் நினைவில் இல்லை."
  def t(:nothing, _), do: "I don't have anything about that in my memory of your Official chats."

  def t({:since, d}, "si"),
    do: "මගේ මතකය ආරම්භ වුණේ #{date(d)} සිටයි; ඊට පෙර කතා කළ දේවල් මම දන්නේ නැහැ."

  def t({:since, d}, "ta"),
    do: "என் நினைவு #{date(d)} முதல் தான் தொடங்குகிறது; அதற்கு முன் பேசியவை எனக்குத் தெரியாது."

  def t({:since, d}, _),
    do:
      "My memory of your Official chats starts on #{date(d)}, so I don't know what was said before that."

  def t(:no_memory, "si"),
    do: "මම ඔබේ Official චැට් මතක තබා ගන්නට පටන් ගත්තේ දැන් විතරයි; තවම මට මතක ඇති දෙයක් නැහැ."

  def t(:no_memory, "ta"),
    do: "உங்கள் Official அரட்டைகளை நான் இப்போது தான் நினைவில் வைக்கத் தொடங்கினேன்; இன்னும் எதுவும் இல்லை."

  def t(:no_memory, _),
    do: "I've only just started remembering your Official chats, so I don't have anything yet."

  def t(:unavailable, "si"), do: "මට දැන් මගේ මතකය කියවන්න බැහැ. ටික වේලාවකින් නැවත අහන්න."
  def t(:unavailable, "ta"), do: "இப்போது என் நினைவைப் படிக்க முடியவில்லை. சிறிது நேரம் கழித்து மீண்டும் கேளுங்கள்."
  def t(:unavailable, _), do: "I can't read my memory right now. Ask me again in a moment."
end
