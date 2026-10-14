defmodule RisiMe.Agent.SpeechQuality do
  @moduledoc """
  The hallucination guard (Harsha 2026-10-14 08:35 SLT: a 16-s Sinhala note came back as
  Sinhala-script text with not one correct word, one word repeated; the model was large-v3 as the
  memory fallback). A transcript that fails any check below is **rejected**: Risi posts "couldn't
  hear clearly" (§37.6 `unclear`) instead of the text and acts on nothing (no spoken @Risi, no
  §38 items, no memory, no answer; in the Risi chat the user is asked to say it again).

  Checks (codes in parentheses; only codes and numbers are ever logged, never text):
    * a token repeated ≥ 4 times in a row (`repeat_token`);
    * one n-gram (n = 1..4) repeated ≥ 4 times covering ≥ 50 % of the tokens (`repeat_ngram`);
    * ≥ 8 tokens with under 35 % unique (`low_unique`);
    * the text compresses more than 2.4 : 1 with zlib, at ≥ 40 characters, Whisper's own
      repetition test (`compressible`);
    * more than 30 characters per second of audio (`too_long_for_audio`);
    * the segments' mean `avg_logprob` under -1.0 (`low_logprob`), or a `no_speech_prob` over 0.6
      with a mean logprob under -0.7 (`no_speech`), when the service reports them;
    * a valid phone transcript (§37.10) that disagrees: character error rate against it over 0.8
      with both ≥ 10 characters (`phone_disagree`).
  """

  @doc """
  `:ok` or `{:reject, code, metrics}` for a transcription result (`text`, optional
  `avg_logprob` / `no_speech_prob`), the note's `duration_ms` and the phone's text (or nil).
  """
  def check(result, duration_ms, phone_text \\ nil)

  def check(%{text: text} = r, duration_ms, phone_text) when is_binary(text) do
    text = String.trim(text)
    m = metrics(text, duration_ms, r, phone_text)

    code =
      cond do
        text == "" ->
          nil

        m.max_run >= 4 ->
          "repeat_token"

        m.ngram_cover >= 0.5 ->
          "repeat_ngram"

        m.tokens >= 8 and m.unique_ratio < 0.35 ->
          "low_unique"

        m.chars >= 40 and m.compression > 2.4 ->
          "compressible"

        is_number(m.chars_per_s) and m.chars_per_s > 30 ->
          "too_long_for_audio"

        is_number(m.avg_logprob) and m.avg_logprob < -1.0 ->
          "low_logprob"

        is_number(m.no_speech) and m.no_speech > 0.6 and is_number(m.avg_logprob) and
            m.avg_logprob < -0.7 ->
          "no_speech"

        is_number(m.phone_cer) and m.phone_cer > 0.8 ->
          "phone_disagree"

        true ->
          nil
      end

    if code, do: {:reject, code, m}, else: :ok
  end

  def check(_r, _d, _p), do: :ok

  @doc "The metrics of a text (numbers only)."
  def metrics(text, duration_ms, r \\ %{}, phone_text \\ nil) do
    toks = String.split(text)
    n = length(toks)
    chars = String.length(text)

    %{
      tokens: n,
      chars: chars,
      max_run: max_run(toks),
      ngram_cover: ngram_cover(toks),
      unique_ratio: if(n > 0, do: Float.round(length(Enum.uniq(toks)) / n, 3), else: 1.0),
      compression:
        if(chars > 0,
          do: Float.round(byte_size(text) / byte_size(:zlib.compress(text)), 3),
          else: 0.0
        ),
      chars_per_s:
        if(is_integer(duration_ms) and duration_ms > 0,
          do: Float.round(chars / (duration_ms / 1000), 2)
        ),
      avg_logprob: Map.get(r, :avg_logprob),
      no_speech: Map.get(r, :no_speech_prob),
      phone_cer: phone_cer(text, phone_text)
    }
  end

  defp max_run([]), do: 0

  defp max_run(toks) do
    toks
    |> Enum.chunk_by(& &1)
    |> Enum.map(&length/1)
    |> Enum.max()
  end

  # The share of tokens covered by the most repeated n-gram (n = 1..4) seen ≥ 4 times.
  defp ngram_cover(toks) when length(toks) < 4, do: 0.0

  defp ngram_cover(toks) do
    n = length(toks)

    1..4
    |> Enum.map(fn k ->
      case toks |> Enum.chunk_every(k, 1, :discard) |> Enum.frequencies() |> Map.values() do
        [] -> 0.0
        counts -> (counts |> Enum.max() |> then(&if(&1 >= 4, do: &1 * k, else: 0))) / n
      end
    end)
    |> Enum.max()
    |> min(1.0)
    |> Float.round(3)
  end

  defp phone_cer(_text, nil), do: nil

  defp phone_cer(text, phone) when is_binary(phone) do
    a = text |> normalise() |> String.graphemes() |> Enum.take(600)
    b = phone |> normalise() |> String.graphemes() |> Enum.take(600)

    if length(a) >= 10 and length(b) >= 10,
      do: Float.round(levenshtein(a, b) / length(b), 3)
  end

  defp phone_cer(_, _), do: nil

  @doc false
  def normalise(s),
    do:
      s
      |> String.downcase()
      |> String.replace(~r/[\p{P}\x{200C}\x{200D}]/u, "")
      |> String.replace(~r/\s+/u, "")

  @doc "Edit distance of two grapheme lists."
  def levenshtein(a, b) do
    row0 = Enum.to_list(0..length(b))

    a
    |> Enum.with_index(1)
    |> Enum.reduce(row0, fn {ca, i}, prev ->
      {row, _} =
        b
        |> Enum.zip(tl(prev))
        |> Enum.zip(prev)
        |> Enum.reduce({[i], i}, fn {{cb, up}, diag}, {acc, left} ->
          v = Enum.min([up + 1, left + 1, diag + if(ca == cb, do: 0, else: 1)])
          {[v | acc], v}
        end)

      Enum.reverse(row)
    end)
    |> List.last()
  end
end
