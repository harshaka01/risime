defmodule RisiMe.Agent.SpeechLang do
  @moduledoc """
  The Voice agent's spoken-language decision for a voice note (P1 2026-10-14,
  docs/status/incident-2026-10-14-tamil-script.md: a Tamil speaker's note came out as Tamil sounds
  in Sinhala script). Flag `RISI_VOICE_LANG_ID` (`off | canary | on`, default `on`; `off` is
  today's path, byte-identical).

  1. **Detect** (`Speech.detect/2`: the speech service's `/v1/audio/detect`, large-v3, *no*
     hint) and combine it with the **sender's language profile** (`profile/1`: per-language
     speech milliseconds, note and correction counts in `risi_speech_langs`; codes and numbers,
     never audio or text). The profile is a bounded prior over si/ta (`posterior_ta/2`), so a
     chat's majority language never decides for a speaker (the old `prior` hint left Tamil out
     entirely when the chat hint was `si`).
  2. **Plan** (`plan/3`, pure): `:auto` (today's path: English, low confidence, no detection),
     `{:force, "si" | "ta"}` when the posterior is clear, or `:both` when si and ta are close: the
     si and the ta route run in parallel and `arbitrate/3` keeps one.
  3. **Script and phonotactic check** on every Sinhala-script result (`suspect?/3`): a text that
     looks like Tamil sounds written in Sinhala letters (risi-l1's per-character likelihood of the
     text, `lm_score/1`, a Sinhala-word/Tamil-ending feature score, `tamil_score/1`, the detector
     or the profile) is re-run as `ta` and arbitrated. The final language always follows the
     script of the final text (`Speech.script_lang/1`), and a phonetic CTC fallback text that is
     Tamil is transliterated into Tamil script (`si_to_ta/1`) rather than shown in Sinhala letters.

  **Arbitration:** both texts' per-character log-likelihood under risi-l1 (prompt logprobs, one
  request each, ~0.1 s); Tamil wins when `lm(ta) - lm(si) > #{-0.22}` (fleurs-ta / yt-si, 60 pairs:
  every Tamil clip ≥ -0.16, every Sinhala clip ≤ -0.28). Without the model: the detector posterior
  and the feature score.

  Every decision writes one `risi_agent_runs` row (agent `voice`, mode `lang_id`: the decision,
  the first guess, the reason code, latency). No text or audio is logged or stored here.
  """
  require Logger

  alias RisiMe.Agent.Speech

  @langs ~w(si ta en)

  # The plan's thresholds (detect-1 eval, 2026-10-13: the ta share ta/(si+ta) is ≤ 0.038 on 95 %
  # of Sinhala chunks and ≥ 0.79 on 95 % of Tamil chunks; the 09:26 note had 0.077).
  @si_max 0.04
  @ta_min 0.9
  @en_min 0.9
  @low_p 0.5
  # The profile prior: Dirichlet-smoothed milliseconds (+60 s per correction), clamped.
  @alpha_ms 20_000
  @correction_ms 60_000
  @prior_clamp {0.15, 0.85}
  # risi-l1 per-character log-likelihood: under -2.5 a Sinhala text is suspect (~90 % of Tamil clips
  # through a Sinhala model; ~8 % of real Sinhala, median -1.87).
  @lm_suspect -2.5
  @lm_margin -0.22
  @lm_min_chars 6
  @lm_prefix "Transcript: "

  @doc "True when the language decision is on for `sender`."
  def enabled?(sender) do
    RisiMe.Agents.Flags.feature_for("RISI_VOICE_LANG_ID", sender)
  rescue
    _ -> false
  end

  @doc """
  One note through the language decision. `run` is the primary transcription
  (`fn audio, opts -> {:ok, result} | {:error, _} end`); a forced language is passed as
  `opts[:language]`. Returns `run`'s shape; an `{:ok, r}` carries `lang_id: %{plan, reason,
  decided, first}` (codes only).
  """
  def transcribe(audio, opts, run) when is_function(run, 2) do
    t0 = System.monotonic_time(:millisecond)
    sender = opts[:sender]
    prof = profile(sender)

    det =
      case Speech.detect(audio, priority: opts[:priority] || "live", timeout: 5_000) do
        {:ok, %{} = d} -> d
        _ -> nil
      end

    plan = plan(det, prof, opts[:language_hint])
    s = if det, do: posterior_ta(det, prof)

    {result, reason, first} =
      case plan do
        :both ->
          both(audio, opts, run, s)

        {:force, lang} ->
          run.(audio, Keyword.put(opts, :language, lang))
          |> checked(audio, opts, run, s, prof, :clear)

        :auto ->
          checked(run.(audio, opts), audio, opts, run, s, prof, :unsure)
      end

    result = label(result)
    record(result, plan, reason, first, sender, System.monotonic_time(:millisecond) - t0)

    case result do
      {:ok, r} ->
        {:ok, Map.put(r, :lang_id, %{plan: plan_code(plan), reason: reason, first: first})}

      other ->
        other
    end
  end

  ## The plan (pure)

  @doc """
  The plan for a detection (`/v1/audio/detect`'s body, or nil), the sender's profile and the
  note's hint: `:auto | {:force, "si" | "ta"} | :both`.
  """
  def plan(nil, _prof, _hint), do: :auto
  def plan(%{"reason" => "no_speech"}, _prof, _hint), do: :auto

  def plan(%{} = det, prof, hint) do
    raw = probs(det, "raw_probs") || probs(det, "probs")
    norm = probs(det, "probs") || raw

    cond do
      raw == nil ->
        :auto

      en_likely?(raw, norm, local_hint?(prof, hint)) ->
        :auto

      true ->
        s = posterior_ta(det, prof)

        cond do
          s >= @ta_min -> {:force, "ta"}
          s <= @si_max -> {:force, "si"}
          true -> :both
        end
    end
  end

  defp local_hint?(prof, hint) do
    Enum.any?(List.wrap(hint), &(&1 in ~w(si ta))) or
      Enum.any?(~w(si ta), &(Map.get(prof.ms, &1, 0) > 0 or Map.get(prof.corrections, &1, 0) > 0))
  end

  # English (or unsure) stays on today's path: with a si/ta expectation only a very likely en
  # (as the service's `prior` mode, but over si+ta+en); without one, the service's own rule.
  defp en_likely?(raw, _norm, true) do
    total = Enum.sum(Map.values(raw))
    total <= 0 or Map.get(raw, "en", 0) / total >= @en_min
  end

  defp en_likely?(_raw, norm, false) do
    {best, p} = Enum.max_by(norm, fn {_, v} -> v end)
    best == "en" or p < @low_p
  end

  @doc """
  The posterior Tamil share between si and ta: the detector's ta/(si+ta) times the sender's
  profile prior (bounded, so a strong detection still wins). 0.5 when the detector gives neither.
  """
  def posterior_ta(det, prof) do
    raw = probs(det, "raw_probs") || probs(det, "probs") || %{}
    si = Map.get(raw, "si", 0.0)
    ta = Map.get(raw, "ta", 0.0)
    share = if si + ta > 0, do: ta / (si + ta), else: 0.5
    pt = prior_ta(prof)
    num = share * pt
    den = num + (1 - share) * (1 - pt)
    if den > 0, do: num / den, else: share
  end

  @doc "The profile's prior for Tamil over si/ta (0.5 without data; clamped to 0.15–0.85)."
  def prior_ta(prof) do
    eff = fn l ->
      Map.get(prof.ms, l, 0) + @correction_ms * Map.get(prof.corrections, l, 0)
    end

    p = (eff.("ta") + @alpha_ms) / (eff.("si") + eff.("ta") + 2 * @alpha_ms)
    {lo, hi} = @prior_clamp
    p |> max(lo) |> min(hi)
  end

  defp probs(det, key) do
    case det[key] do
      %{} = m ->
        m = for {l, v} <- m, l in @langs, is_number(v), into: %{}, do: {l, v * 1.0}
        if map_size(m) == 0, do: nil, else: m

      _ ->
        nil
    end
  end

  ## Running and checking

  defp both(audio, opts, run, s) do
    [si, ta] =
      ["si", "ta"]
      |> Enum.map(fn l -> Task.async(fn -> run.(audio, Keyword.put(opts, :language, l)) end) end)
      |> Task.await_many(:infinity)

    {res, reason} = arbitrate(si, ta, s)
    {res, "both_" <> reason, first_guess(s)}
  end

  defp first_guess(nil), do: nil
  defp first_guess(s), do: if(s >= 0.5, do: "ta", else: "si")

  # A Sinhala-script result that looks like Tamil is re-run as ta and arbitrated. After a clear
  # detection (`:clear`, ta share <= 0.04) risi-l1's likelihood alone is not enough: phonetic
  # Sinhala (CTC) often scores low, and an extra Tamil run costs seconds on a busy GPU.
  defp checked({:ok, r} = ok, audio, opts, run, s, prof, how) do
    if Speech.script_lang(r.text) == "si" and suspect?(r.text, s, prof, how == :unsure) do
      ta = run.(audio, Keyword.put(opts, :language, "ta"))
      {res, reason} = arbitrate(ok, ta, s)
      {res, "suspect_" <> reason, "si"}
    else
      {ok, "kept", lang_of(r)}
    end
  end

  defp checked(other, _audio, _opts, _run, _s, _prof, _how), do: {other, "error", nil}

  defp lang_of(%{text: t} = r) do
    case Speech.script_lang(t) do
      "en" -> r[:language]
      l -> l
    end
  end

  @doc """
  True when a Sinhala-script `text` should be double-checked as Tamil: the detector posterior
  `s` (> 0.04), a profile with Tamil evidence (prior > 0.55), the feature score (≥ 0.7) or
  risi-l1's per-character likelihood (< -2.5; only when `lm?`: no clear detection).
  """
  def suspect?(text, s, prof, lm? \\ true) do
    (is_number(s) and s > @si_max) or prior_ta(prof) > 0.55 or tamil_score(text) >= 0.7 or
      (lm? and
         case lm_score(text) do
           {:ok, lp} -> lp < @lm_suspect
           _ -> false
         end)
  end

  @doc """
  Picks between a si-route result and a ta-route result (either may be an error): the one in its
  own script; both valid: risi-l1's likelihood margin, else the detector posterior `s` and the
  feature score. A Sinhala-script phonetic (CTC) text judged Tamil is transliterated.
  Returns `{result, reason_code}`.
  """
  def arbitrate(si, ta, s) do
    si_ok = valid(si, "si")
    ta_ok = valid(ta, "ta")

    cond do
      si_ok and ta_ok ->
        {:ok, a} = si
        {:ok, b} = ta

        case lm_margin(a.text, b.text) do
          {:ok, m} ->
            if m > @lm_margin, do: {ta, "lm_ta"}, else: {si, "lm_si"}

          :error ->
            if tamil_verdict?(a.text, s), do: {ta, "det_ta"}, else: {si, "det_si"}
        end

      ta_ok ->
        {ta, "only_ta"}

      si_ok ->
        {:ok, a} = si

        if ctc?(a) and tamil_verdict?(a.text, s),
          do: {{:ok, %{a | text: si_to_ta(a.text), language: "ta"}}, "translit_ta"},
          else: {si, "only_si"}

      match?({:ok, _}, si) ->
        {si, "unchecked"}

      true ->
        {if(match?({:ok, _}, ta), do: ta, else: si), "error"}
    end
  end

  defp valid({:ok, %{text: t, speech: true}}, lang) when is_binary(t),
    do: String.trim(t) != "" and Speech.script_lang(t) == lang

  defp valid(_, _), do: false

  @doc """
  A Sinhala-script phonetic (CTC) fallback text with no Tamil run to compare: Tamil letters when
  it clearly looks Tamil (feature score ≥ 0.7, or a sender whose profile is clearly Tamil),
  else unchanged. Returns `{text, language}`.
  """
  def fallback_script(text, prof) when is_binary(text) do
    if Speech.script_lang(text) == "si" and (tamil_score(text) >= 0.7 or prior_ta(prof) >= 0.7),
      do: {si_to_ta(text), "ta"},
      else: {text, Speech.script_lang(text)}
  end

  defp ctc?(r), do: is_binary(r[:model]) and r[:model] =~ ~r/ctc/i

  defp tamil_verdict?(text, s) do
    tamil_score(text) >= 0.7 or (is_number(s) and s >= 0.5)
  end

  # The final language is the final text's script (Sinhala or Tamil letters), else unchanged.
  defp label({:ok, %{text: t} = r}) when is_binary(t) do
    case Speech.script_lang(t) do
      "en" ->
        {:ok, r}

      l ->
        {:ok,
         %{
           r
           | language: l
         }
         |> Map.put(:script, if(l == "si", do: r[:script] || Speech.script_check(t)))
         |> Map.put(:romanised, if(l == "si", do: r[:romanised] == true, else: false))}
    end
  end

  defp label(other), do: other

  defp plan_code(:auto), do: "auto"
  defp plan_code(:both), do: "both"
  defp plan_code({:force, l}), do: "force_" <> l

  defp record(result, plan, reason, first, sender, latency) do
    decided =
      case result do
        {:ok, r} -> r.language
        _ -> nil
      end

    row = %{
      agent: "voice",
      mode: "lang_id",
      decision: decided || "none",
      legacy_route: first,
      agreed: first == nil or first == decided,
      status: if(decided, do: "ok", else: "error"),
      reason: plan_code(plan) <> ":" <> reason,
      latency_ms: latency,
      lang: decided
    }

    row = if is_binary(sender), do: Map.put(row, :user, sender), else: row
    RisiMe.Agents.Runs.record(row)

    Logger.info(
      "Risi voice lang_id: plan=#{plan_code(plan)} reason=#{reason} first=#{first} " <>
        "decided=#{decided} ms=#{latency}"
    )
  rescue
    _ -> :ok
  end

  ## The sender's language profile (L1)

  @doc """
  `user`'s spoken-language profile from `risi_speech_langs`: `%{ms: %{lang => ms},
  notes: %{lang => n}, corrections: %{lang => n}}` (empty maps without data).
  """
  def profile(user) when is_binary(user) do
    case Ecto.UUID.dump(user) do
      {:ok, uid} ->
        %{rows: rows} =
          RisiMe.Repo.query!(
            "SELECT language, speech_ms, notes, corrections FROM risi_speech_langs " <>
              "WHERE user_id = $1",
            [uid]
          )

        Enum.reduce(rows, empty(), fn [l, ms, n, c], acc ->
          %{
            ms: Map.put(acc.ms, l, ms || 0),
            notes: Map.put(acc.notes, l, n || 0),
            corrections: Map.put(acc.corrections, l, c || 0)
          }
        end)

      :error ->
        empty()
    end
  rescue
    _ -> empty()
  end

  def profile(_), do: empty()

  defp empty, do: %{ms: %{}, notes: %{}, corrections: %{}}

  @doc """
  A voice note in `lang` of `ms` (the decided language): its milliseconds (as
  `Lang.note_speech/2`, with the same decay) and one more note. Best effort: `:ok`.
  """
  def learn(user, lang, ms) when is_binary(user) and lang in @langs and is_integer(ms) do
    RisiMe.Agent.Lang.note_speech(user, %{lang => ms})
    bump(user, lang, "notes")
  end

  def learn(_, _, _), do: :ok

  @doc """
  A "Wrong language?" correction (proposal 2026-10-14-voice-translate): the note of `ms` was
  `to`, not `from`: its milliseconds move from `from` to `to` and `to` gets one correction.
  Codes and numbers only. Best effort: `:ok`.
  """
  def correct(user, from, to, ms)
      when is_binary(user) and to in @langs and is_integer(ms) and ms >= 0 do
    with {:ok, uid} <- Ecto.UUID.dump(user) do
      if from in @langs and from != to do
        RisiMe.Repo.query!(
          "UPDATE risi_speech_langs SET speech_ms = GREATEST(speech_ms - $3, 0), " <>
            "notes = GREATEST(notes - 1, 0) WHERE user_id = $1 AND language = $2",
          [uid, from, ms]
        )
      end

      RisiMe.Agent.Lang.note_speech(user, %{to => max(ms, 1)})
      bump(user, to, "notes")
      bump(user, to, "corrections")
    end

    :ok
  rescue
    _ -> :ok
  end

  def correct(_, _, _, _), do: :ok

  defp bump(user, lang, col) when col in ["notes", "corrections"] do
    with {:ok, uid} <- Ecto.UUID.dump(user) do
      RisiMe.Repo.query!(
        "INSERT INTO risi_speech_langs (user_id, language, speech_ms, #{col}, updated_at) " <>
          "VALUES ($1, $2, 0, 1, $3) ON CONFLICT (user_id, language) DO UPDATE SET " <>
          "#{col} = risi_speech_langs.#{col} + 1, updated_at = EXCLUDED.updated_at",
        [uid, lang, DateTime.utc_now()]
      )
    end

    :ok
  rescue
    _ -> :ok
  end

  ## The phonotactic feature score (no model)

  @si_lex MapSet.new(~w(මම මට මගේ අපි අපිට අපේ ඔයා ඔයාට ඔයාගේ ඔබ ඔබට ඔබේ එයා එයාට මේ ඒ අර මෙහෙ
    එහෙ කොහෙද මොකද මොකක්ද කවුද කීයද ඇයි කොහොමද හරි නෑ නැහැ නැත ඔව් ඕනේ ඕන එක එකක් එකට එකේ දෙක තුන
    කියලා කිව්වා කියන්න කරන්න කරලා කරනවා යනවා එනවා තියෙනවා තියනවා තිබුණා තිබ්බා දැන් අද හෙට ඊයේ
    පස්සේ කලින් ගොඩක් ටිකක් වගේ විතරයි විතරක් නම් සහ හා සඳහා ගැන වෙනුවෙන් අතර තමයි තමා වෙලා වුණා
    වෙනවා දෙන්න ගන්න බලන්න ඉන්න හොඳයි හොඳ ලොකු පොඩි නිසා නිසාම නමුත් ඒත් හැබැයි ඉතින් එතකොට ඊට
    කියන ඔය මොනවා මොනවද එහෙම මෙහෙම වැඩ වැඩේ ගෙදර සල්ලි රුපියල් ලංකාව ශ්‍රී රජය ආණ්ඩුව ජනතාව දී දි
    ද සිට දක්වා))
  @si_end ~r/(නවා|ලා|න්න|නේ|ගේ|ට|ක්|යි|ද|ව|වල|වා|ය|යා|එක|ත්)$/u
  # Logistic weights fitted on 1,615 Sinhala-script outputs of Sinhala speech vs Tamil speech
  # through Sinhala models (real, 51 clips) and transliterated Tamil (synthetic); held out:
  # ≥ 0.7 flags 1.5 % of Sinhala texts and 25 % of the real Tamil ones (the trigger is ≥ 0.5;
  # risi-l1 does the real separation).
  @w %{
    si_vowel: -2.0,
    prenasal: -0.47,
    si_lex: -5.58,
    end_m: 3.5,
    end_l: 2.5,
    end_u: 3.45,
    si_end: -4.71,
    ai: 0.99,
    tr: 1.05
  }
  @b 1.49

  @doc """
  0..1: how much a Sinhala-script `text` looks like Tamil sounds (few Sinhala words and Sinhala
  endings, many Tamil-like word endings -m/-l/-u). 0.0 for a text without Sinhala words.
  """
  def tamil_score(text) when is_binary(text) do
    words = Regex.scan(~r/[\x{0D80}-\x{0DFF}\x{200D}]+/u, text) |> Enum.map(&hd/1)

    case length(words) do
      0 ->
        0.0

      n ->
        rate = fn pred -> Enum.count(words, pred) / n end

        f = %{
          si_vowel: rate.(&String.match?(&1, ~r/[ඇඈැෑ]/u)),
          prenasal: rate.(&String.match?(&1, ~r/[ඟඬඳඹ]/u)),
          si_lex: rate.(&MapSet.member?(@si_lex, &1)),
          end_m: rate.(&(String.ends_with?(&1, "ම්") and not MapSet.member?(@si_lex, &1))),
          end_l: rate.(&(String.ends_with?(&1, "ල්") or String.ends_with?(&1, "ළ්"))),
          end_u: rate.(&String.ends_with?(&1, "ු")),
          si_end: rate.(&Regex.match?(@si_end, &1)),
          ai: rate.(&String.contains?(&1, "ෛ")),
          tr: rate.(&(String.contains?(&1, "ට්‍ර") and not String.starts_with?(&1, "ට")))
        }

        z = Enum.reduce(@w, @b, fn {k, w}, acc -> acc + w * f[k] end)
        1 / (1 + :math.exp(-z))
    end
  end

  def tamil_score(_), do: 0.0

  ## risi-l1 likelihood

  @doc """
  risi-l1's mean log-likelihood per character of `text` (vLLM prompt logprobs, one request, no
  generation): `{:ok, float}` or `:error` (off, too short, or the model unavailable).
  """
  def lm_score(text) when is_binary(text) do
    text = String.trim(text)

    with true <- lm_on?(),
         true <- length(String.codepoints(text)) >= @lm_min_chars,
         {:ok, n0} <- prefix_tokens(),
         {:ok, vals} <- prompt_logprobs(@lm_prefix <> text) do
      {:ok, Enum.sum(Enum.drop(vals, n0)) / length(String.codepoints(text))}
    else
      _ -> :error
    end
  end

  def lm_score(_), do: :error

  defp lm_margin(si_text, ta_text) do
    with {:ok, a} <- lm_score(si_text), {:ok, b} <- lm_score(ta_text), do: {:ok, b - a}
  end

  defp lm_on?, do: Keyword.get(Application.get_env(:risime, :risi_speech_lang, []), :lm, true)

  defp prefix_tokens do
    key = {__MODULE__, :prefix_tokens}

    case :persistent_term.get(key, nil) do
      n when is_integer(n) ->
        {:ok, n}

      _ ->
        with {:ok, vals} <- prompt_logprobs(@lm_prefix) do
          :persistent_term.put(key, length(vals))
          {:ok, length(vals)}
        end
    end
  end

  defp prompt_logprobs(prompt) do
    llm = Application.get_env(:risime, :risi_llm, [])

    with {:ok, base} <- RisiMe.Agent.LLM.Local.base_url(),
         {:ok, %{status: 200, body: %{"choices" => [%{"prompt_logprobs" => lps} | _]}}}
         when is_list(lps) <-
           Req.post(
             Req.new(
               [
                 url: base <> "/completions",
                 json: %{
                   model: "risi-l1",
                   prompt: prompt,
                   max_tokens: 1,
                   temperature: 0,
                   prompt_logprobs: 0
                 },
                 receive_timeout: 3_000,
                 connect_options: [timeout: 1_000],
                 retry: false,
                 redirect: false,
                 headers:
                   case llm[:api_key] do
                     k when is_binary(k) and k != "" -> [{"authorization", "Bearer " <> k}]
                     _ -> []
                   end
               ] ++ (llm[:req_options] || [])
             )
           ) do
      vals =
        for %{} = m <- lps,
            {_, %{"logprob" => v}} <- Enum.take(m, 1),
            is_number(v),
            do: v * 1.0

      {:ok, vals}
    else
      _ -> :error
    end
  rescue
    _ -> :error
  end

  ## Sinhala letters → Tamil letters (a phonetic CTC text judged Tamil)

  @cons %{
    "ක" => "க",
    "ඛ" => "க",
    "ග" => "க",
    "ඝ" => "க",
    "ඞ" => "ங",
    "ං" => "ங்",
    "ච" => "ச",
    "ඡ" => "ச",
    "ජ" => "ஜ",
    "ඣ" => "ச",
    "ඤ" => "ஞ",
    "ඥ" => "ஞ",
    "ට" => "ட",
    "ඨ" => "ட",
    "ඩ" => "ட",
    "ඪ" => "ட",
    "ණ" => "ண",
    "ත" => "த",
    "ථ" => "த",
    "ද" => "த",
    "ධ" => "த",
    "න" => "ந",
    "ප" => "ப",
    "ඵ" => "ப",
    "බ" => "ப",
    "භ" => "ப",
    "ම" => "ம",
    "ය" => "ய",
    "ර" => "ர",
    "ල" => "ல",
    "ළ" => "ள",
    "ව" => "வ",
    "ශ" => "ஷ",
    "ෂ" => "ஷ",
    "ස" => "ஸ",
    "හ" => "ஹ",
    "ෆ" => "ப",
    "ඟ" => "ங்க",
    "ඬ" => "ண்ட",
    "ඳ" => "ந்த",
    "ඹ" => "ம்ப"
  }
  @vowels %{
    "අ" => "அ",
    "ආ" => "ஆ",
    "ඇ" => "எ",
    "ඈ" => "ஏ",
    "ඉ" => "இ",
    "ඊ" => "ஈ",
    "උ" => "உ",
    "ඌ" => "ஊ",
    "එ" => "எ",
    "ඒ" => "ஏ",
    "ඓ" => "ஐ",
    "ඔ" => "ஒ",
    "ඕ" => "ஓ",
    "ඖ" => "ஔ"
  }
  @signs %{
    "ා" => "ா",
    "ැ" => "ெ",
    "ෑ" => "ே",
    "ි" => "ி",
    "ී" => "ீ",
    "ු" => "ு",
    "ූ" => "ூ",
    "ෙ" => "ெ",
    "ේ" => "ே",
    "ෛ" => "ை",
    "ො" => "ொ",
    "ෝ" => "ோ",
    "ෞ" => "ௌ",
    "්" => "்"
  }

  @doc "Sinhala letters → the nearest Tamil letters (for a phonetic text; other text unchanged)."
  def si_to_ta(text) when is_binary(text) do
    text
    |> String.replace("‍", "")
    |> String.codepoints()
    |> Enum.map_join(fn c -> @cons[c] || @vowels[c] || @signs[c] || c end)
    # A final "ன்" is how Tamil writes a word-final n.
    |> then(&Regex.replace(~r/ந்(?=\s|$|[[:punct:]])/u, &1, "ன்"))
  end
end
