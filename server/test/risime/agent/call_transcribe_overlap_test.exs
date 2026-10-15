defmodule RisiMe.Agent.CallTranscribeOverlapTest do
  @moduledoc "Proposal 2026-10-15-live-call-latency: the overlap de-duplication (pure)."
  use ExUnit.Case, async: true

  alias RisiMe.Agent.CallTranscribe.Overlap

  test "drops the longest repeated head (case and punctuation ignored)" do
    tail = Overlap.tail([], "We will send the revised quote")
    assert Overlap.strip(tail, "Quote, on Friday.") == "on Friday."
    assert Overlap.strip(tail, "the revised quote on Friday") == "on Friday"
    assert Overlap.strip(tail, "revised quote") == ""
  end

  test "keeps text that doesn't repeat, and repeats in the middle" do
    tail = Overlap.tail([], "see you at ten")
    assert Overlap.strip(tail, "tomorrow at ten") == "tomorrow at ten"
    assert Overlap.strip([], "anything") == "anything"
    assert Overlap.strip(tail, "") == ""
  end

  test "at most 6 words are dropped" do
    tail = Overlap.words("a b c d e f g h")
    assert Overlap.strip(tail, "b c d e f g h i") == "b c d e f g h i"
    assert Overlap.strip(tail, "c d e f g h i") == "i"
  end

  test "Sinhala and Tamil words (vowel signs kept)" do
    tail = Overlap.tail([], "හෙට උදේ දහයට හමුවෙමු")
    assert Overlap.strip(tail, "හමුවෙමු. ඔෆිස් එකේ") == "ඔෆිස් එකේ"
    tail = Overlap.tail([], "நாளை காலை சந்திப்போம்")
    assert Overlap.strip(tail, "சந்திப்போம் அலுவலகத்தில்") == "அலுவலகத்தில்"
    # A different vowel sign is a different word.
    assert Overlap.strip(Overlap.words("කට"), "කිට") == "කිට"
  end

  test "the tail keeps the last 8 words" do
    assert Overlap.tail([], "1 2 3 4 5 6 7 8 9 10") == ~w(3 4 5 6 7 8 9 10)
  end
end
