defmodule RisiMe.Agent.SpeechQualityTest do
  @moduledoc """
  The hallucination guard (Harsha 2026-10-14 08:35 SLT): repetition, low uniqueness,
  compressibility, text too long for the audio, low Whisper confidence and disagreement with the
  phone's transcript are rejected; ordinary Sinhala, Singlish, Tamil and English pass.
  """
  use ExUnit.Case, async: true

  alias RisiMe.Agent.SpeechQuality, as: Q

  defp r(text, extra \\ %{}), do: Map.merge(%{text: text}, extra)

  test "the incident: 'කරන්නේ' repeated is rejected" do
    text = "මම ඒක කරන්නේ " <> String.duplicate("කරන්නේ ", 20)
    assert {:reject, "repeat_token", m} = Q.check(r(text), 16_000)
    assert m.max_run >= 20
  end

  test "a repeated phrase (n-gram) is rejected" do
    text = String.duplicate("අපි යමු හෙට ", 6)
    assert {:reject, code, _} = Q.check(r(text), 12_000)
    assert code in ["repeat_ngram", "low_unique"]
  end

  test "a highly compressible character loop is rejected" do
    text = String.duplicate("කකකකකකකක", 8)
    assert {:reject, code, _} = Q.check(r(text), 20_000)
    assert code in ["repeat_token", "compressible"]
  end

  test "far more text than the audio can hold is rejected" do
    text = "හෙට උදේ දහයට අපි කාර්යාලයේ හමුවෙමු. මම quotation එක එවන්නම්. ඊට පස්සේ අපි කතා කරමු."
    assert {:reject, "too_long_for_audio", _} = Q.check(r(text), 1_000)
    assert :ok = Q.check(r(text), 8_000)
  end

  test "low Whisper confidence is rejected when the service reports it" do
    assert {:reject, "low_logprob", _} = Q.check(r("හෙට හමුවෙමු", %{avg_logprob: -1.4}), 3_000)

    assert {:reject, "no_speech", _} =
             Q.check(r("හෙට හමුවෙමු", %{avg_logprob: -0.8, no_speech_prob: 0.9}), 3_000)

    assert :ok = Q.check(r("හෙට හමුවෙමු", %{avg_logprob: -0.3, no_speech_prob: 0.1}), 3_000)
  end

  test "a transcript that disagrees badly with the phone's is rejected" do
    phone = "හෙට උදේ දහයට meeting එක තියෙනවා"
    assert {:reject, "phone_disagree", m} = Q.check(r("ගෙදර යන්න බස් එකක් නැහැ අද"), 6_000, phone)
    assert m.phone_cer > 0.8
    assert :ok = Q.check(r("හෙට උදේ දහයට meeting එක තියෙනවා"), 6_000, phone)
    assert :ok = Q.check(r("හෙට උදේ දහයට මීටින් එක තියෙනවා"), 6_000, phone)
  end

  test "ordinary speech passes" do
    for {text, ms} <- [
          {"හෙට උදේ දහයට හමුවෙමු. මම quotation එක එවන්නම්.", 6_000},
          {"நாளை காலை பத்து மணிக்கு சந்திப்போம்", 5_000},
          {"Let's meet tomorrow at ten and I'll send the quotation.", 4_000},
          {"ok", 1_000},
          {"", 2_000}
        ] do
      assert :ok = Q.check(r(text), ms), text
    end
  end

  test "levenshtein/2" do
    assert Q.levenshtein(String.graphemes("kitten"), String.graphemes("sitting")) == 3
    assert Q.levenshtein([], String.graphemes("abc")) == 3
  end
end
