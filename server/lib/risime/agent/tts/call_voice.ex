defmodule RisiMe.Agent.TTS.CallVoice do
  @moduledoc """
  Call Risi's voice, chosen by ear (Harsha's item C, 2026-10-15). The admin console's Voices page
  ("Rate for Call Risi") speaks the same realistic Risi answers (times, names) with today's Call
  Risi voice and the candidates, measures each voice's time to first audio, takes a 👍/👎 per
  voice (`RisiMe.Agent.TTS.Ratings`) and has a one-tap pick. The pick is the English voice choice
  (`tts_voice_en` through `RisiMe.Agent.TTS.put/4`: audit-logged as `setting_set`), which
  `RisiMe.Agent.CallRisi.voice/1` reads for every new call (live within 5 s, no deploy).

  Live calls stream only from the resident Kokoro service through the media worker, with a
  first-audio budget of #{1_500} ms (p50 of the samples). A voice outside that (another engine, a
  refused load, or slower than the budget) can still be heard and rated, never picked, and the
  page says why with the measured time.

  The candidates (Kokoro's own grades, VOICES.md in the model folder): Emma (bf_emma, B-, the
  best British voice, many hours of training data), Bella (af_bella, A-, the best-graded after
  Heart), Michael (am_michael, C+, a male option) and Chatterbox's built-in voice (a larger,
  more expressive model; console lab, PerTh watermark), and (2026-10-15) Chatterbox-Turbo, Dia2 and
  Orpheus. A lab engine can be picked only after it is made live for calls (`RISI_CALL_LIVE_ENGINES`
  and `TTS_LAB_PIN`; `RisiMe.Agent.TTS.VoicePick`).
  """
  alias RisiMe.Agent.TTS

  @budget_ms 1_500

  @names %{
    "kokoro:af_heart" => "Heart (Kokoro, US English, female)",
    "kokoro:bf_emma" => "Emma (Kokoro, British English, female)",
    "kokoro:af_bella" => "Bella (Kokoro, US English, female)",
    "kokoro:am_michael" => "Michael (Kokoro, US English, male)",
    "chatterbox:default" => "Chatterbox (built-in voice, English)",
    "chatterbox-turbo:default" => "Chatterbox-Turbo (stock voice, English)",
    "dia2:stock" => "Dia2 (stock speaker, English)",
    "orpheus:tara" => "Orpheus Tara (English)"
  }

  @candidates [
    {"kokoro:bf_emma",
     "Kokoro's best British voice (grade B-, many hours of data): calm, clear."},
    {"kokoro:af_bella", "Kokoro's best-graded voice after Heart (A-, the most data): warm."},
    {"kokoro:am_michael", "A male option (C+): even and neutral."},
    {"chatterbox:default",
     "A larger, more expressive model (MIT). Every clip carries Resemble's inaudible PerTh AI watermark."},
    {"chatterbox-turbo:default",
     "Chatterbox's fast English model (MIT, PerTh watermark): its stock voice."},
    {"dia2:stock",
     "A streaming dialogue model (Apache-2.0): the model's own speaker for a fixed seed."},
    {"orpheus:tara", "Orpheus 3B's Tara (Apache-2.0 + Llama 3.2 licence): expressive."}
  ]

  @samples [
    "You have two meetings tomorrow: the design review with Ruwan at ten thirty, and the budget call with Nadeesha at three.",
    "Done. I'll remind you to call Kasun at a quarter past five this evening.",
    "Thursday is free after two o'clock, so that's a good time to meet Dilani."
  ]

  @doc "The first-audio budget for live calls (ms, p50)."
  def budget_ms, do: @budget_ms

  @doc "The sample answers every voice speaks (the same for all)."
  def samples, do: @samples

  @doc "A readable name for `voice_id`."
  def name(voice_id), do: Map.get(@names, voice_id, voice_id)

  @doc """
  The voices to rate: today's Call Risi voice first (`now?: true`), then the candidates, each
  `%{id, name, why, now?}`.
  """
  def lineup(now \\ RisiMe.Agent.CallRisi.voice("en")) do
    cands =
      for {id, why} <- @candidates,
          id != now,
          do: %{id: id, name: name(id), why: why, now?: false}

    [
      %{id: now, name: name(now), why: "Call Risi speaks with this voice now.", now?: true}
      | cands
    ]
  end

  @doc """
  Speaks sample `i` with `voice_id` (one WAV, in memory): `{:ok, %{src, first_audio_ms, total_ms,
  load_ms}}` or `{:error, reason}`. The sample text is fixed; nothing is logged or stored.
  """
  def speak(voice_id, i, installed) do
    case TTS.synthesize(Enum.at(@samples, i), voice_id, installed: installed) do
      {:ok, %{audio: a} = ok} when byte_size(a) <= 8 * 1024 * 1024 ->
        {:ok,
         %{
           src: "data:#{ok.content_type};base64," <> Base.encode64(a),
           first_audio_ms: ok.first_audio_ms,
           total_ms: ok.total_ms,
           load_ms: Map.get(ok, :load_ms, 0)
         }}

      {:ok, _} ->
        {:error, :too_large}

      {:error, e} ->
        {:error, e}
    end
  end

  @doc "The p50 time to first audio (ms) over the samples that played (model loads excluded), or nil."
  def first_ms(results) do
    xs = for {:ok, r} <- results, is_integer(r.first_audio_ms), do: r.first_audio_ms

    case Enum.sort(xs) do
      [] -> nil
      s -> Enum.at(s, div(length(s) - 1, 2))
    end
  end

  @doc """
  Whether `voice_id` can be Call Risi's voice, given its sample `results` (a list, possibly
  partial): `:ok`; `:unmeasured` (no sample has played yet); `{:too_slow, ms}` (over the budget);
  `{:not_streamed, ms}` (another engine: Call Risi streams only from the resident Kokoro service);
  `{:cannot_run, reason}` (every sample failed, e.g. not enough free memory to load it).
  """
  def live(voice_id, results) do
    ms = first_ms(results)
    [engine | _] = String.split(voice_id, ":", parts: 2)

    # Kokoro streams from its resident service; a lab engine only once it is pinned for calls (2026-10-15).
    kokoro? = engine == "kokoro" or engine in RisiMe.Agent.CallRisi.live_engines()

    cond do
      ms == nil and results != [] and Enum.all?(results, &match?({:error, _}, &1)) ->
        {:cannot_run, results |> hd() |> elem(1)}

      ms == nil ->
        :unmeasured

      ms > @budget_ms ->
        {:too_slow, ms}

      not kokoro? ->
        {:not_streamed, ms}

      true ->
        :ok
    end
  end

  @doc """
  Makes `voice_id` Call Risi's (English) voice, by admin `by`: audit-logged, live for new calls
  within 5 s. Only a Kokoro voice (or a voice of a live lab engine, `CallRisi.live_engines/0`) that is installed (`installed_ids`) and measured within the
  budget (`live/2` is `:ok`); otherwise `{:error, reason}`.
  """
  def pick(voice_id, results, by, installed_ids) do
    case live(voice_id, results) do
      :ok -> TTS.put("en", voice_id, by, installed_ids)
      other -> {:error, other}
    end
  end
end
