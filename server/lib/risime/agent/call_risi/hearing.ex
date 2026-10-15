defmodule RisiMe.Agent.CallRisi.Hearing do
  @moduledoc """
  The Voice agent's hearing gate (Harsha 2026-10-16 04:52 SLT, call 56f51277): on his 04:43 call
  Whisper made up "Bye-bye. Ready?", "You're on.", "you know", "It'll be fun.", "Yeah, I didn't
  know I was going to promote…" and "And I tend to come out. So do I." from noise, silence or
  Risi's own echo, and Risi answered them (and lost his real request under them). Risi never
  answers words nobody said.

  `judge/2` takes one call utterance's transcription (`RisiMe.Agent.Speech` result, with the
  service's per-segment `avg_logprob`, `no_speech_prob`, `compression_ratio`) and the audio facts
  (`pcm` 16-kHz s16le, `speech_ms`, `during_tts?`) and returns:

    * `{:ok, text}` — words to act on (made-up tail sentences such as "Bye-bye. Ready?" stripped);
    * `{:drop, code}` — no speech: nothing is said, nothing is replaced (codes below);
    * `{:unclear, code}` — speech, but too unsure to act on: Risi says "Sorry, I didn't catch
      that." at most once in a row (the session's `unclear?`), then stays silent.

  Codes (only codes are logged, never text): `no_speech` (the service's VAD), `no_speech_prob`
  (every segment ≥ 0.6), `blocklist` (only Whisper's typical made-up phrases, on a short or quiet
  utterance), `quiet` (low energy and an unsure model), `echo` (heard while Risi was speaking and
  no louder than her echo would be), `low_logprob`, `compressible` (repetition).

  Thresholds are in `config :risime, :risi_call_hearing` (keyword) so they can be tuned without a
  release of the rules; defaults below.
  """

  alias RisiMe.Agent.SpeechQuality

  @defaults [
    # A segment is dropped when Whisper itself says it is probably not speech, or it is unsure.
    seg_no_speech: 0.6,
    seg_logprob: -1.0,
    seg_compression: 2.4,
    # The whole utterance: below this mean logprob it is "unclear".
    unclear_logprob: -0.8,
    # Energy (p90 of 20-ms frame RMS, int16) of a quiet utterance (noise, far voices, echo rest).
    quiet_rms: 700,
    # A barge-in utterance must be at least this loud to be the caller (else it is echo).
    echo_rms: 1_400,
    # Short: a blocklisted phrase is dropped when the speech is under this (or ≤ 4 words).
    short_ms: 1_500
  ]

  # Whisper's well-known inventions on noise and silence, and those of Harsha's 04:43 call.
  # Never "yes / yeah / okay / no": those answer Risi's questions (§41.9 voice confirm).
  @blocklist [
    "you know",
    "thank you",
    "thanks",
    "thank you very much",
    "thanks for watching",
    "thank you for watching",
    "thanks for listening",
    "thank you for listening",
    "please subscribe",
    "subscribe",
    "like and subscribe",
    "bye",
    "bye-bye",
    "bye bye",
    "goodbye",
    "ready",
    "you're on",
    "you are on",
    "it'll be fun",
    "so do i",
    "i see",
    "oh",
    "hmm",
    "um",
    "uh",
    "the end",
    "see you",
    "see you next time",
    "you"
  ]

  @doc "The once-in-a-row line for an unclear utterance."
  def unclear_line, do: "Sorry, I didn't catch that."

  @doc "See the moduledoc."
  def judge(r, audio \\ %{})

  def judge(%{text: text} = r, audio) when is_binary(text) do
    cfg = cfg()
    text = String.trim(text)

    cond do
      text == "" or r[:speech] == false ->
        {:drop, "no_speech"}

      true ->
        segs = scored_segments(r)
        rms = energy(audio[:pcm])
        judge_segments(r, text, segs, rms, audio, cfg)
    end
  end

  def judge(_r, _audio), do: {:drop, "no_speech"}

  defp judge_segments(r, text, segs, rms, audio, cfg) do
    {kept, dropped} = Enum.split_with(segs, &keep_segment?(&1, cfg))

    cond do
      segs != [] and kept == [] ->
        if Enum.any?(dropped, &((&1[:no_speech_prob] || 0) >= cfg[:seg_no_speech])),
          do: {:drop, "no_speech_prob"},
          else: {:unclear, "low_logprob"}

      true ->
        heard = if segs == [], do: text, else: kept |> Enum.map_join(" ", & &1.text) |> trim()
        logprob = mean(kept, :avg_logprob) || r[:avg_logprob]
        nsp = max_of(kept, :no_speech_prob) || r[:no_speech_prob]
        whole(heard, logprob, nsp, rms, audio, cfg)
    end
  end

  defp whole(heard, logprob, nsp, rms, audio, cfg) do
    stripped = strip_made_up(heard)
    quiet? = is_number(rms) and rms < cfg[:quiet_rms]
    short? = short?(heard, audio, cfg)
    unsure? = (is_number(logprob) and logprob < -0.6) or (is_number(nsp) and nsp > 0.3)

    cond do
      stripped == "" and (short? or quiet?) ->
        {:drop, "blocklist"}

      quiet? and unsure? ->
        {:drop, "quiet"}

      audio[:during_tts?] == true and is_number(rms) and rms < cfg[:echo_rms] ->
        {:drop, "echo"}

      is_number(logprob) and logprob < cfg[:unclear_logprob] ->
        {:unclear, "low_logprob"}

      match?(
        {:reject, c, _} when c in ~w(repeat_token repeat_ngram compressible),
        SpeechQuality.check(%{text: heard}, nil)
      ) ->
        {:unclear, "compressible"}

      stripped == "" ->
        # A clear, loud "Thank you." said to Risi: kept.
        {:ok, heard}

      true ->
        {:ok, stripped}
    end
  end

  defp keep_segment?(s, cfg) do
    not ((s[:no_speech_prob] || 0) >= cfg[:seg_no_speech] or
           (s[:avg_logprob] || 0) < cfg[:seg_logprob] or
           (s[:compression_ratio] || 0) > cfg[:seg_compression])
  end

  # Segments that carry the model's own scores (the ct2 Whisper route); others can't be judged.
  defp scored_segments(r) do
    for %{text: t} = s <- r[:segments] || [],
        is_number(s[:avg_logprob]) or is_number(s[:no_speech_prob]),
        do: %{s | text: String.trim(t)}
  end

  defp short?(heard, audio, cfg) do
    words = length(String.split(heard))
    ms = audio[:speech_ms]
    words <= 4 or (is_integer(ms) and ms < cfg[:short_ms])
  end

  @doc """
  The words without made-up sentences ("Can I have a meeting set up today for two? Bye-bye.
  Ready?" → "Can I have a meeting set up today for two?"); "" when every sentence is one.
  """
  def strip_made_up(text) do
    text
    |> sentences()
    |> Enum.reject(&made_up?/1)
    |> Enum.join(" ")
    |> trim()
  end

  @doc "True when the sentence is only Whisper's typical made-up phrases."
  def made_up?(sentence) do
    norm =
      sentence
      |> String.downcase()
      |> String.replace(~r/[’‘]/u, "'")
      |> String.replace(~r/[^\p{L}\p{N}'\s-]/u, " ")
      |> String.replace(~r/\s+/u, " ")
      |> String.trim()

    norm != "" and only_blocklisted?(norm)
  end

  # The sentence is a run of blocklisted phrases ("bye-bye ready", "you know").
  defp only_blocklisted?(""), do: true

  defp only_blocklisted?(norm) do
    Enum.any?(@blocklist, fn p ->
      cond do
        norm == p ->
          true

        String.starts_with?(norm, p <> " ") ->
          only_blocklisted?(String.trim_leading(norm, p <> " "))

        true ->
          false
      end
    end)
  end

  defp sentences(text) do
    Regex.split(~r/(?<=[.?!…])\s+/u, String.trim(text), trim: true)
  end

  @doc "p90 of the 20-ms frame RMS (int16) of 16-kHz s16le PCM; nil without audio."
  def energy(pcm) when is_binary(pcm) and byte_size(pcm) >= 640 do
    rms =
      for <<frame::binary-size(640) <- pcm>> do
        {sum, n} =
          for <<s::little-signed-16 <- frame>>, reduce: {0, 0} do
            {acc, k} -> {acc + s * s, k + 1}
          end

        :math.sqrt(sum / n)
      end
      |> Enum.sort()

    Enum.at(rms, min(length(rms) - 1, trunc(length(rms) * 0.9))) |> round()
  end

  def energy(_), do: nil

  defp mean(segs, key) do
    case for(s <- segs, is_number(s[key]), do: s[key]) do
      [] -> nil
      vs -> Enum.sum(vs) / length(vs)
    end
  end

  defp max_of(segs, key) do
    case for(s <- segs, is_number(s[key]), do: s[key]) do
      [] -> nil
      vs -> Enum.max(vs)
    end
  end

  defp trim(s), do: s |> String.replace(~r/\s+/u, " ") |> String.trim()

  defp cfg, do: Keyword.merge(@defaults, Application.get_env(:risime, :risi_call_hearing, []))
end
