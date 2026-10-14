defmodule RisiMe.Agent.SpokenMentionTest do
  @moduledoc """
  §37.11 (proposal 2026-10-14-spoken-risi): a spoken "Risi, …" counts as @Risi only when Risi is
  addressed. The golden set (en/si/ta/Singlish; zero false positives), the question after the
  vocative, the deterministic request id, the model on unsure cases with the strict threshold,
  and the `RISI_SPOKEN_MENTION` flag (default canary, `shadow` acts for nobody). The voice path
  itself is in `voice_test.exs`.
  """
  use RisiMe.DataCase, async: false

  import RisiMe.RisiHelpers

  alias RisiMe.Agent.SpokenMention
  alias RisiMe.Agents.{Eval, Flags}

  @moduletag capture_log: true

  @dir Path.expand("golden", __DIR__)

  setup do
    restore_on_exit([:risi_agent_flags, :risi_dev_users, :risi_spoken_mention])
    Application.delete_env(:risime, :risi_agent_flags)
    Application.delete_env(:risime, :risi_spoken_mention)
    :ok
  end

  test "golden set: no mention is ever @Risi; every language; recall by rules alone" do
    cases = Eval.load(@dir, "spoken_mention")
    r = Eval.run("spoken_mention", cases)
    assert r.n >= 40
    assert r.false_positives == 0, "mentions taken as @Risi: #{inspect(r.failed)}"
    assert r.recall >= 0.85
    assert r.failed == [], "misjudged: #{inspect(r.failed)}"
    assert Map.keys(r.by_lang) |> Enum.sort() == ~w(en si singlish ta)
    assert Enum.count(cases, &(&1["expect"] == "request")) >= 25
    assert Enum.count(cases, &(&1["expect"] == "none")) >= 25
    assert Enum.count(cases, &(&1["expect"] == "ask")) >= 4
    assert r.p95_ms < 50
  end

  test "the question is the text after the vocative (to the end)" do
    assert %{decision: :addressed, question: "remind me to call Kamal. He owes us."} =
             SpokenMention.classify("Risi, remind me to call Kamal. He owes us.")

    assert %{decision: :addressed, question: "When is the next meeting?"} =
             SpokenMention.classify("Risi? When is the next meeting?")

    assert %{decision: :addressed, question: "please add it to my calendar."} =
             SpokenMention.classify(
               "The visit moved to Monday. Hey Risi, please add it to my calendar."
             )

    assert %{decision: :mentioned, reason: "member_name"} =
             SpokenMention.classify("Rishi, can you send the drawings?", members: ["rishi"])

    assert %{decision: :none} = SpokenMention.classify("Send the drawings to the site.")
    assert %{decision: :none} = SpokenMention.classify("Risime is the app.")
  end

  test "the request id: a UUID of version-4 layout, the same for the same note" do
    note = RisiMe.TimeUUID.generate()
    rid = SpokenMention.request_id(note)
    assert rid == SpokenMention.request_id(String.upcase(note))
    assert rid != SpokenMention.request_id(RisiMe.TimeUUID.generate())
    assert {:ok, _} = Ecto.UUID.cast(rid)
    assert String.at(rid, 14) == "4"
    assert String.at(rid, 19) in ~w(8 9 a b)
  end

  test "unsure cases: the model decides only at confidence >= 0.9; otherwise not @Risi" do
    text = "Can you remind me about the invoice tomorrow, Risi?"
    assert %{decision: :unsure, reason: "trailing_vocative"} = SpokenMention.classify(text)

    # No model: not @Risi.
    assert %{action: :ask, addressed: false, decision: "unsure_ask", by: "rules"} =
             SpokenMention.decide(text, "en", model: false)

    for {conf, addressed} <- [{0.95, true}, {0.85, false}] do
      fake_llm!(fn "risi_spoken_mention", body ->
        assert body["max_tokens"] == 40
        %{"addressed" => true, "confidence" => conf}
      end)

      r = SpokenMention.decide(text, "en", model: "risi-l1")
      assert r.addressed == addressed and r.by == "model"
      assert r.decision == if(addressed, do: "unsure_yes", else: "unsure_ask")
      if addressed, do: assert(r.question == "Can you remind me about the invoice tomorrow")
    end

    fake_llm!(fn _, _ -> {:status, 500} end)

    assert %{action: :ask, decision: "unsure_ask"} =
             SpokenMention.decide(text, "en", model: "risi-l1")

    # Addressed with nothing asked: Risi asks back (never silent).
    assert %{action: :ask, decision: "ask"} =
             SpokenMention.decide("Hey Risi.", "en", model: false)

    assert %{action: :none} = SpokenMention.decide("Risi said so", "en", model: false)

    # A clear decision never asks the model.
    fake_llm!(fn _, _ -> flunk("model asked") end)

    assert %{addressed: true, by: "rules"} =
             SpokenMention.decide("Risi, remind me at 9", "en", model: "risi-l1")

    assert %{addressed: false, by: "rules"} =
             SpokenMention.decide("Risi said so", "en", model: "risi-l1")
  end

  test "RISI_SPOKEN_MENTION: default canary; off|shadow|canary|on; shadow acts for nobody" do
    dev = Ecto.UUID.generate()
    other = Ecto.UUID.generate()
    Application.put_env(:risime, :risi_dev_users, [dev])

    assert SpokenMention.mode() == :canary
    assert SpokenMention.acts_for?(dev) and not SpokenMention.acts_for?(other)

    assert :ok = Flags.put("RISI_SPOKEN_MENTION", "shadow")
    assert SpokenMention.mode() == :shadow
    refute SpokenMention.acts_for?(dev)

    assert :ok = Flags.put("RISI_SPOKEN_MENTION", "on")
    assert SpokenMention.acts_for?(other)
    assert {:error, :invalid_flag} = Flags.put("RISI_SPOKEN_MENTION", "maybe")
    # The other feature switches don't take shadow.
    assert {:error, :invalid_flag} = Flags.put("RISI_VOICE_TRANSCRIBE", "shadow")

    assert %{value: "on", from: "table"} =
             Enum.find(Flags.all(), &(&1.name == "RISI_SPOKEN_MENTION"))

    Flags.clear("RISI_SPOKEN_MENTION")
    Application.put_env(:risime, :risi_spoken_mention, false)
    assert SpokenMention.mode() == :off
    Application.put_env(:risime, :risi_spoken_mention, true)
    assert SpokenMention.mode() == :on
  end
end
