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

    langs =
      RisiMe.Repo.all(
        from m in RisiMe.Agent.Memory.Message,
          where: m.sender_id == ^user and m.from_risi == false,
          order_by: [desc: m.at],
          limit: 40
      )
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

  def user_lang(_), do: "en"

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
