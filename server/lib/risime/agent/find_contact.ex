defmodule RisiMe.Agent.FindContact do
  @moduledoc """
  **find_contact** and **list_contacts** (architect 2026-10-16 10:15 addendum item 2–3, 11:45 P0):
  the people a caller can mean, for the call brain.

  The people: the caller's RisiMe friends (their 1:1 chat partners) and the members of their
  chats (`RisiMe.Agent.CalendarTools.contacts/1`; tests: `config :risime, :risi_contacts, fun`).

  A name heard by speech-to-text ("Shani Kao", "Seneca", "S-H-E-N-I-K-A") is scored against each
  person's display name and each word of it, after:
    * a name spelled letter by letter is joined ("S-H-E-N-I-K-A" → "shenika");
    * Sinhala and Tamil script is transliterated to Latin letters;
    * a loose Latin form (sh/s, th/t, w/v, ee/i, aa/a, double letters) and a sound-alike key.

  Score (0..1): 1.0 exact, else the best of the edit similarity of the plain and loose forms and
  0.8 for an equal sound-alike key. `find/2` returns the top 3 with a score ≥ 0.45;
  `decide/1`: one clear match (≥ 0.75, the next ≥ 0.15 behind) → `{:one, person}`, 2–3 near
  (≥ 0.6) → `{:pick, people}`, else `:none`. Never "not found" while a match scores ≥ 0.6.
  """

  alias RisiMe.Agent.NameCheck

  @doc "The caller's people as `[{user_id, display_name}]` (never raises)."
  def people(user_id) do
    f = Application.get_env(:risime, :risi_contacts)

    if is_function(f, 1),
      do: f.(user_id),
      else: RisiMe.Agent.CalendarTools.contacts(user_id)
  rescue
    _ -> []
  catch
    _, _ -> []
  end

  @doc "Up to `max` display names (list_contacts: names only, read aloud)."
  def list(user_id, max \\ 10) do
    user_id |> people() |> Enum.map(&elem(&1, 1)) |> Enum.uniq() |> Enum.take(max)
  end

  @doc "The top 3 people for `said`: `[%{user_id, name, score}]`, best first."
  def find(said, people) when is_binary(said) do
    s = said |> spelled() |> latin() |> NameCheck.norm()

    if s == "" do
      []
    else
      people
      |> Enum.map(fn {id, name} -> %{user_id: id, name: name, score: score(s, name)} end)
      |> Enum.filter(&(&1.score >= 0.45))
      |> Enum.sort_by(&{-&1.score, &1.name})
      |> Enum.uniq_by(& &1.user_id)
      |> Enum.take(3)
    end
  end

  def find(_, _), do: []

  @doc "What the brain does with `find/2`'s result (see the moduledoc)."
  def decide([]), do: :none

  def decide([top | rest]) do
    near = Enum.filter(rest, &(&1.score >= 0.6))

    cond do
      # The name exactly (spelled, or a word of the display name): no question.
      top.score == 1.0 and (near == [] or hd(near).score < 1.0) -> {:one, top}
      top.score >= 0.75 and (near == [] or top.score - hd(near).score >= 0.15) -> {:one, top}
      top.score >= 0.6 -> {:pick, [top | near] |> Enum.take(3)}
      true -> :none
    end
  end

  ## Scoring

  defp score(s, name) do
    n = name |> latin() |> NameCheck.norm()
    forms = Enum.uniq([n | String.split(n)])
    # "Shani Kao" is one name split by the speech-to-text: also try it joined.
    heard = Enum.uniq([s, String.replace(s, " ", "")])

    if Enum.any?(heard, &(&1 in forms)) do
      1.0
    else
      for h <- heard, f <- forms, reduce: 0.0 do
        best -> max(best, pair(h, f))
      end
    end
  end

  defp pair(h, f) do
    plain = sim(h, f)
    loose = sim(loose(h), loose(f))
    key = NameCheck.phonetic(String.replace(h, " ", ""))

    ph =
      if key != nil and key == NameCheck.phonetic(String.replace(f, " ", "")), do: 0.8, else: 0.0

    Enum.max([plain, loose * 0.95, ph])
  end

  defp sim(a, b) do
    l = max(String.length(a), String.length(b))
    if l == 0, do: 0.0, else: 1.0 - NameCheck.lev(a, b) / l
  end

  @doc false
  def loose(s) do
    s
    |> String.replace(~r/[^a-z]/, "")
    |> String.replace(~r/sh|ch|zh/, "s")
    |> String.replace(~r/th|dh/, "t")
    |> String.replace(~r/ph/, "f")
    |> String.replace(~r/w/, "v")
    |> String.replace(~r/[ck]/, "k")
    |> String.replace(~r/z/, "s")
    |> String.replace(~r/ee|ie|ea|ey|y/, "i")
    |> String.replace(~r/oo|ou/, "u")
    |> String.replace(~r/(.)\1+/, "\\1")
  end

  @doc ~S|A name spelled letter by letter ("S-H-E-N-I-K-A", "S H E N I K A") joined; else as is.|
  def spelled(s) do
    parts = String.split(String.trim(s), ~r/[\s.\-,]+/u, trim: true)

    if length(parts) >= 3 and Enum.all?(parts, &(String.length(&1) == 1)),
      do: Enum.join(parts),
      else: s
  end

  ## Sinhala and Tamil script → Latin letters (enough to match a name)

  @si_cons %{
    "ක" => "k",
    "ඛ" => "kh",
    "ග" => "g",
    "ඝ" => "gh",
    "ඞ" => "n",
    "ච" => "ch",
    "ඡ" => "ch",
    "ජ" => "j",
    "ඣ" => "jh",
    "ඤ" => "ny",
    "ට" => "t",
    "ඨ" => "th",
    "ඩ" => "d",
    "ඪ" => "dh",
    "ණ" => "n",
    "ත" => "th",
    "ථ" => "th",
    "ද" => "d",
    "ධ" => "dh",
    "න" => "n",
    "ප" => "p",
    "ඵ" => "ph",
    "බ" => "b",
    "භ" => "bh",
    "ම" => "m",
    "ය" => "y",
    "ර" => "r",
    "ල" => "l",
    "ව" => "w",
    "ශ" => "sh",
    "ෂ" => "sh",
    "ස" => "s",
    "හ" => "h",
    "ළ" => "l",
    "ෆ" => "f",
    "ඟ" => "ng",
    "ඬ" => "nd",
    "ඳ" => "nd",
    "ඹ" => "mb"
  }
  @si_vow %{
    "අ" => "a",
    "ආ" => "aa",
    "ඇ" => "e",
    "ඈ" => "e",
    "ඉ" => "i",
    "ඊ" => "ee",
    "උ" => "u",
    "ඌ" => "oo",
    "එ" => "e",
    "ඒ" => "e",
    "ඔ" => "o",
    "ඕ" => "o",
    "ඖ" => "au",
    "ඓ" => "ai"
  }
  @si_sign %{
    "ා" => "aa",
    "ැ" => "e",
    "ෑ" => "e",
    "ි" => "i",
    "ී" => "ee",
    "ු" => "u",
    "ූ" => "oo",
    "ෙ" => "e",
    "ේ" => "e",
    "ො" => "o",
    "ෝ" => "o",
    "ෞ" => "au",
    "ෛ" => "ai",
    "්" => "",
    "ෘ" => "ru",
    "ං" => "n",
    "ඃ" => "h"
  }
  @ta_cons %{
    "க" => "k",
    "ங" => "ng",
    "ச" => "s",
    "ஞ" => "ny",
    "ட" => "d",
    "ண" => "n",
    "த" => "th",
    "ந" => "n",
    "ப" => "p",
    "ம" => "m",
    "ய" => "y",
    "ர" => "r",
    "ல" => "l",
    "வ" => "v",
    "ழ" => "zh",
    "ள" => "l",
    "ற" => "r",
    "ன" => "n",
    "ஜ" => "j",
    "ஷ" => "sh",
    "ஸ" => "s",
    "ஹ" => "h"
  }
  @ta_vow %{
    "அ" => "a",
    "ஆ" => "aa",
    "இ" => "i",
    "ஈ" => "ee",
    "உ" => "u",
    "ஊ" => "oo",
    "எ" => "e",
    "ஏ" => "e",
    "ஐ" => "ai",
    "ஒ" => "o",
    "ஓ" => "o",
    "ஔ" => "au"
  }
  @ta_sign %{
    "ா" => "aa",
    "ி" => "i",
    "ீ" => "ee",
    "ு" => "u",
    "ூ" => "oo",
    "ெ" => "e",
    "ே" => "e",
    "ை" => "ai",
    "ொ" => "o",
    "ோ" => "o",
    "ௌ" => "au",
    "்" => ""
  }

  @doc "Sinhala and Tamil letters as Latin (other text unchanged)."
  def latin(s) when is_binary(s) do
    if s =~ ~r/[\x{0D80}-\x{0DFF}\x{0B80}-\x{0BFF}]/u do
      s
      |> String.codepoints()
      |> translit([])
    else
      s
    end
  end

  def latin(s), do: s

  defp translit([], acc), do: acc |> Enum.reverse() |> Enum.join()

  defp translit([c | rest], acc) do
    cons = @si_cons[c] || @ta_cons[c]

    cond do
      cons != nil ->
        case rest do
          [n | rest2] when is_map_key(@si_sign, n) or is_map_key(@ta_sign, n) ->
            translit(rest2, [cons <> (@si_sign[n] || @ta_sign[n]) | acc])

          _ ->
            translit(rest, [cons <> "a" | acc])
        end

      Map.has_key?(@si_vow, c) or Map.has_key?(@ta_vow, c) ->
        translit(rest, [@si_vow[c] || @ta_vow[c] | acc])

      Map.has_key?(@si_sign, c) or Map.has_key?(@ta_sign, c) ->
        translit(rest, acc)

      true ->
        translit(rest, [c | acc])
    end
  end
end
