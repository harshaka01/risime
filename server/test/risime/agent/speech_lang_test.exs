defmodule RisiMe.Agent.SpeechLangTest do
  @moduledoc """
  P1 2026-10-14 (docs/status/incident-2026-10-14-tamil-script.md): the Voice agent's language
  decision for voice notes (`RisiMe.Agent.SpeechLang`): the plan from the detector and the
  sender's profile, the parallel si/ta run when they are close, the Sinhala-script check of a
  result that is Tamil sounds, risi-l1's likelihood arbitration, the CTC transliteration, the
  profile's counts and corrections, and the flag (off = today's route).

  Every text here is synthetic (written for the test), never a real note.
  """
  use RisiMe.DataCase, async: false

  alias RisiMe.Agent.{Speech, SpeechLang}

  # Synthetic: a colloquial Sri Lankan Tamil sentence written phonetically in Sinhala letters (as
  # a CTC or Sinhala model writes Tamil speech), and the same sentence in Tamil script.
  @ta_in_si "නාන් නාළෛක්කු කාලෛයිල වාරන්, අන්ද රිපෝට්ටෛ මුඩිච්චු අනුප්පුරන්, එනක්කු කොඤ්චම් වේලෛ ඉරුක්කු"
  @ta_text "நான் நாளைக்கு காலையில வாறன், அந்த ரிப்போர்ட்ட முடிச்சு அனுப்புறன்"
  @si_text "මම හෙට උදේ එනවා, ඒ වාර්තාව ඉවර කරලා එවන්නම් කියලා කිව්වා"

  defmodule Scripted do
    @moduledoc "A speech impl: `detect` from `:speech_lang_detect`, transcribe by language."
    @behaviour RisiMe.Agent.Speech

    @impl true
    def healthy?, do: true

    @impl true
    def compare(_audio, _opts), do: {:error, :unavailable}

    @impl true
    def detect(_audio, _opts) do
      send(Application.get_env(:risime, :risi_test_pid), :detect)

      case Application.get_env(:risime, :speech_lang_detect) do
        nil -> {:error, :unavailable}
        d -> {:ok, d}
      end
    end

    @impl true
    def transcribe(_audio, opts) do
      send(Application.get_env(:risime, :risi_test_pid), {:transcribe, opts[:language]})
      texts = Application.get_env(:risime, :speech_lang_texts, %{})

      case Map.get(texts, opts[:language] || "auto") do
        nil ->
          {:error, :failed}

        {text, model} ->
          {:ok,
           %{
             text: text,
             language: opts[:language] || "si",
             model: model,
             elapsed_ms: 100,
             speech: true,
             romanised: false,
             language_probability: 0.9,
             script: nil,
             segments: []
           }}
      end
    end

    @impl true
    def fallback_transcribe(_audio, _opts), do: {:error, :unavailable}
  end

  setup do
    keys = [
      :risi_speech,
      :risi_test_pid,
      :speech_lang_detect,
      :speech_lang_texts,
      :risi_voice_lang_id,
      :risi_speech_lang
    ]

    old = for k <- keys, do: {k, Application.get_env(:risime, k)}

    on_exit(fn ->
      for {k, v} <- old,
          do:
            if(v == nil,
              do: Application.delete_env(:risime, k),
              else: Application.put_env(:risime, k, v)
            )
    end)

    Application.put_env(:risime, :risi_speech, Scripted)
    Application.put_env(:risime, :risi_test_pid, self())
    Application.put_env(:risime, :risi_voice_lang_id, :on)
    :ok
  end

  # risi-l1's prompt logprobs: three prefix tokens, then one token per character, -1.0 each for a
  # Tamil-script text and -3.0 for anything else (a Sinhala-letter rendering of Tamil sounds).
  defp lm_stub do
    Application.put_env(:risime, :risi_speech_lang, lm: true)
    :persistent_term.erase({SpeechLang, :prefix_tokens})

    Req.Test.stub(RisiMe.Agent.LLM.Local, fn conn ->
      {:ok, body, conn} = Plug.Conn.read_body(conn)
      %{"prompt" => prompt, "prompt_logprobs" => 0, "max_tokens" => 1} = Jason.decode!(body)
      text = String.replace_prefix(prompt, "Transcript: ", "")
      v = if Speech.script_lang(text) == "ta", do: -1.0, else: -3.0
      toks = [nil, lp(-1.0), lp(-1.0), lp(-1.0)] ++ for(_ <- String.codepoints(text), do: lp(v))
      Req.Test.json(conn, %{"choices" => [%{"prompt_logprobs" => toks}]})
    end)
  end

  defp lp(v), do: %{"1" => %{"logprob" => v, "rank" => 1}}

  defp det(si, ta, en), do: %{"probs" => %{"si" => si, "ta" => ta, "en" => en}, "reason" => "x"}

  defp prof(ms \\ %{}, corrections \\ %{}), do: %{ms: ms, notes: %{}, corrections: corrections}

  describe "plan/3 (pure)" do
    test "the 09:26 note's detection (si 0.76, ta 0.06, en 0.17, no hint) runs both" do
      assert SpeechLang.plan(det(0.7644, 0.0637, 0.1718), prof(), []) == :both
    end

    test "a clear Sinhala chunk is forced si; a clear Tamil one ta, even in a si-hinted chat" do
      assert SpeechLang.plan(det(0.99, 0.001, 0.009), prof(), ["si"]) == {:force, "si"}
      # The old `prior` hint left ta out entirely: a si hint routed this chunk to Sinhala.
      assert SpeechLang.plan(det(0.02, 0.97, 0.01), prof(), ["si"]) == {:force, "ta"}
    end

    test "English and unsure speech stay on today's path" do
      assert SpeechLang.plan(det(0.02, 0.0, 0.98), prof(), ["si"]) == :auto
      assert SpeechLang.plan(det(0.3, 0.05, 0.65), prof(), []) == :auto
      assert SpeechLang.plan(det(0.45, 0.1, 0.45), prof(), []) == :auto
      assert SpeechLang.plan(nil, prof(), ["si"]) == :auto
      assert SpeechLang.plan(%{"reason" => "no_speech"}, prof(), []) == :auto
    end

    test "the sender's profile is a bounded prior: a Tamil speaker's close call leans ta" do
      tamil = prof(%{"ta" => 120_000})
      sinhala = prof(%{"si" => 600_000})
      d = det(0.6, 0.3, 0.1)
      assert SpeechLang.posterior_ta(d, tamil) > SpeechLang.posterior_ta(d, prof())
      assert SpeechLang.posterior_ta(d, sinhala) < SpeechLang.posterior_ta(d, prof())
      # A strong detection still wins over a strong profile.
      assert SpeechLang.plan(det(0.001, 0.99, 0.009), sinhala, ["si"]) == {:force, "ta"}
      assert SpeechLang.prior_ta(prof()) == 0.5
      assert SpeechLang.prior_ta(prof(%{}, %{"ta" => 5})) == 0.85
    end
  end

  describe "the script and phonotactic check" do
    test "Tamil sounds in Sinhala letters score high, Sinhala low" do
      assert SpeechLang.tamil_score(@ta_in_si) >= 0.7
      assert SpeechLang.tamil_score(@si_text) < 0.3
      assert SpeechLang.tamil_score("hello there") == 0.0
    end

    test "a phonetic CTC text judged Tamil is written in Tamil letters" do
      {text, lang} = SpeechLang.fallback_script(@ta_in_si, prof())
      assert lang == "ta"
      assert Speech.script_lang(text) == "ta"
      refute text =~ ~r/[\x{0D80}-\x{0DFF}]/u
      assert String.starts_with?(text, "நான்")
      assert SpeechLang.fallback_script(@si_text, prof()) == {@si_text, "si"}
    end

    test "risi-l1's per-character likelihood (prompt logprobs, prefix dropped)" do
      lm_stub()
      assert {:ok, s} = SpeechLang.lm_score(@ta_text)
      assert_in_delta s, -1.0, 0.001
      assert {:ok, s} = SpeechLang.lm_score(@ta_in_si)
      assert_in_delta s, -3.0, 0.001
      assert SpeechLang.lm_score("හරි") == :error
    end
  end

  describe "transcribe/3" do
    defp run, do: &Speech.transcribe/2

    test "the incident: close si/ta runs both in parallel and risi-l1 keeps the Tamil text" do
      lm_stub()
      Application.put_env(:risime, :speech_lang_detect, det(0.7644, 0.0637, 0.1718))

      Application.put_env(:risime, :speech_lang_texts, %{
        "si" => {@ta_in_si, "omnilingual-ctc-1b"},
        "ta" => {@ta_text, "tamil-asr-qwen3"}
      })

      user = Ecto.UUID.generate()

      assert {:ok, r} =
               SpeechLang.transcribe("audio", [sender: user, priority: "live"], run())

      assert_received :detect
      assert_received {:transcribe, "si"}
      assert_received {:transcribe, "ta"}
      assert r.text == @ta_text
      assert r.language == "ta"
      assert r.model == "tamil-asr-qwen3"
      assert r.script == nil
      assert r.lang_id == %{plan: "both", reason: "both_lm_ta", first: "si"}

      assert [%{decision: "ta", legacy_route: "si", agreed: false, reason: "both:both_lm_ta"}] =
               Repo.all(
                 from r in "risi_agent_runs",
                   where: r.agent == "voice" and r.mode == "lang_id",
                   select: %{
                     decision: r.decision,
                     legacy_route: r.legacy_route,
                     agreed: r.agreed,
                     reason: r.reason
                   }
               )
    end

    test "both run, real Sinhala: risi-l1 keeps the Sinhala text" do
      Application.put_env(:risime, :risi_speech_lang, lm: true)
      :persistent_term.erase({SpeechLang, :prefix_tokens})

      Req.Test.stub(RisiMe.Agent.LLM.Local, fn conn ->
        {:ok, body, conn} = Plug.Conn.read_body(conn)
        text = Jason.decode!(body)["prompt"] |> String.replace_prefix("Transcript: ", "")
        v = if Speech.script_lang(text) == "si", do: -1.8, else: -3.4
        toks = [nil, lp(-1.0), lp(-1.0), lp(-1.0)] ++ for(_ <- String.codepoints(text), do: lp(v))
        Req.Test.json(conn, %{"choices" => [%{"prompt_logprobs" => toks}]})
      end)

      Application.put_env(:risime, :speech_lang_detect, det(0.8, 0.1, 0.1))

      Application.put_env(:risime, :speech_lang_texts, %{
        "si" => {@si_text, "qwen3-asr-0.6b-sinhala"},
        "ta" => {"மம ஹெட உதே என்வா", "tamil-asr-qwen3"}
      })

      assert {:ok, r} = SpeechLang.transcribe("audio", [], run())
      assert r.text == @si_text
      assert r.language == "si"
      assert r.script.ok
      assert r.lang_id.reason == "both_lm_si"
    end

    test "a clear Sinhala note: one si run, kept" do
      Application.put_env(:risime, :speech_lang_detect, det(0.99, 0.001, 0.009))
      Application.put_env(:risime, :speech_lang_texts, %{"si" => {@si_text, "qwen3"}})

      assert {:ok, %{text: @si_text, language: "si", lang_id: %{plan: "force_si"}}} =
               SpeechLang.transcribe("audio", [language_hint: ["si"]], run())

      assert_received {:transcribe, "si"}
      refute_received {:transcribe, "ta"}
    end

    test "no detection: today's path; a Sinhala-script result that is Tamil sounds is re-run as ta" do
      Application.put_env(:risime, :speech_lang_texts, %{
        "auto" => {@ta_in_si, "whisper-small-sinhala"},
        "ta" => {@ta_text, "whisper-tamil-medium"}
      })

      assert {:ok, r} = SpeechLang.transcribe("audio", [language_hint: ["si"]], run())
      assert_received {:transcribe, nil}
      assert_received {:transcribe, "ta"}
      # Without risi-l1 (off here) the feature score decides.
      assert r.text == @ta_text
      assert r.language == "ta"
      assert r.lang_id == %{plan: "auto", reason: "suspect_det_ta", first: "si"}
    end

    test "the ta run fails: a CTC text judged Tamil is transliterated, never Sinhala letters" do
      Application.put_env(:risime, :speech_lang_detect, det(0.7644, 0.0637, 0.1718))

      Application.put_env(:risime, :speech_lang_texts, %{
        "si" => {@ta_in_si, "omnilingual-ctc-1b"}
      })

      assert {:ok, r} = SpeechLang.transcribe("audio", [], run())
      assert r.language == "ta"
      assert Speech.script_lang(r.text) == "ta"
      assert r.lang_id.reason == "both_translit_ta"
    end

    test "Speech.transcribe_note goes through it while the flag is on, and not when off" do
      Application.put_env(:risime, :speech_lang_detect, det(0.02, 0.97, 0.01))
      Application.put_env(:risime, :speech_lang_texts, %{"ta" => {@ta_text, "tamil-asr-qwen3"}})

      assert {:ok, %{language: "ta", text: @ta_text}} =
               Speech.transcribe_note("audio", duration_ms: 5_000, language_hint: ["si"])

      assert_received :detect
      assert_received {:transcribe, "ta"}

      Application.put_env(:risime, :risi_voice_lang_id, false)
      Application.put_env(:risime, :speech_lang_texts, %{"auto" => {@si_text, "qwen3"}})

      assert {:ok, r} = Speech.transcribe_note("audio", duration_ms: 5_000)
      refute_received :detect
      assert_received {:transcribe, nil}
      refute Map.has_key?(r, :lang_id)
    end
  end

  describe "the profile" do
    test "notes and corrections are counted per language (codes and numbers only)" do
      user = Ecto.UUID.generate()
      assert SpeechLang.profile(user) == prof()

      SpeechLang.learn(user, "si", 6_100)
      p = SpeechLang.profile(user)
      assert p.ms == %{"si" => 6_100}
      assert p.notes == %{"si" => 1}

      # "Wrong language?": that note was Tamil.
      SpeechLang.correct(user, "si", "ta", 6_100)
      p = SpeechLang.profile(user)
      assert p.ms["si"] == 0
      assert p.notes["si"] == 0
      assert p.ms["ta"] == 6_100
      assert p.notes["ta"] == 1
      assert p.corrections["ta"] == 1
      assert SpeechLang.prior_ta(p) > 0.75
    end
  end
end
