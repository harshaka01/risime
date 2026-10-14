defmodule RisiMe.Agent.SpeechNoteTest do
  @moduledoc """
  Incident 2026-10-14 (Sinhala voice notes hung for minutes): `Speech.transcribe_note/2`'s live
  budget (3 × duration, ≥ 15 s), the fallback order (routed model → phone transcript while the
  flag says so → the fast CPU model → failed), the Voice agent's `risi_agent_runs` row (codes
  only), and the eval pause file (`SpeechGuard`).
  """
  use RisiMe.DataCase, async: false

  alias RisiMe.Agent.{Speech, SpeechGuard}

  defmodule Scripted do
    @moduledoc "A speech impl whose answers come from `:speech_note_script`."
    @behaviour RisiMe.Agent.Speech

    @impl true
    def healthy?, do: true

    @impl true
    def transcribe(_audio, opts) do
      send(Application.get_env(:risime, :risi_test_pid), {:primary, opts})
      next(:primary)
    end

    @impl true
    def compare(_audio, _opts), do: {:error, :unavailable}

    @impl true
    def fallback_transcribe(_audio, opts) do
      send(Application.get_env(:risime, :risi_test_pid), {:ctc, opts})
      next(:ctc)
    end

    defp next(kind) do
      script = Application.get_env(:risime, :speech_note_script, %{})

      case Map.get(script, kind, []) do
        [h | t] ->
          Application.put_env(:risime, :speech_note_script, Map.put(script, kind, t))
          h

        [] ->
          {:error, :unavailable}
      end
    end
  end

  defp ok(text, lang, model),
    do:
      {:ok,
       %{
         text: text,
         language: lang,
         model: model,
         elapsed_ms: 100,
         speech: true,
         romanised: false,
         language_probability: 0.99,
         script: nil,
         segments: []
       }}

  setup do
    old =
      for k <- [:risi_speech, :risi_test_pid, :speech_note_script],
          do: {k, Application.get_env(:risime, k)}

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
    :ok
  end

  defp script(s), do: Application.put_env(:risime, :speech_note_script, s)

  defp runs do
    Repo.all(
      from r in "risi_agent_runs",
        where: r.agent == "voice" and r.mode == "stt",
        select: %{
          decision: r.decision,
          status: r.status,
          fell_back: r.fell_back,
          reason: r.reason,
          budget_ms: r.budget_ms,
          lang: r.lang
        }
    )
  end

  test "the live budget is 3 × the duration, at least 15 s" do
    assert Speech.budget_ms(6_000) == 18_000
    assert Speech.budget_ms(2_000) == 15_000
    assert Speech.budget_ms(60_000) == 180_000
    assert Speech.budget_ms(nil) == 15_000
    assert Speech.fallback_budget_ms(4_000) == 10_000
    assert Speech.fallback_budget_ms(30_000) == 30_000
  end

  test "the routed model within the budget: asked live, no fallback, one ok run row" do
    script(%{primary: [ok("හෙට හමුවෙමු", "si", "qwen3-asr-0.6b-sinhala")]})

    assert {:ok, r} =
             Speech.transcribe_note("audio", duration_ms: 6_000, language_hint: ["si"])

    assert_received {:primary, opts}
    assert opts[:timeout] == 18_000
    assert opts[:priority] == "live"
    assert opts[:language_hint] == ["si"]
    refute_received {:ctc, _}
    assert r.fallback == nil

    assert [
             %{
               decision: "qwen3-asr-0.6b-sinhala",
               status: "ok",
               fell_back: false,
               budget_ms: 18_000,
               lang: "si"
             }
           ] = runs()
  end

  test "a timeout falls back to the phone's transcript while the flag says so" do
    script(%{primary: [{:error, :timeout}]})
    phone = %{"text" => " හෙට උදේ දහයට ", "lang" => "si-LK", "engine" => "android-on-device"}

    assert {:ok, r} =
             Speech.transcribe_note("audio",
               duration_ms: 4_000,
               phone_transcript: phone,
               phone_fallback: true
             )

    # A timeout is not retried (the budget is spent).
    assert_received {:primary, _}
    refute_received {:primary, _}
    refute_received {:ctc, _}
    assert r.text == "හෙට උදේ දහයට"
    assert r.language == "si"
    assert r.model == "phone-on-device"
    assert r.source == "phone"
    assert r.fallback == "phone"
    assert r.fallback_reason == "timeout"
    assert r.script.ok

    assert [
             %{
               status: "ok",
               fell_back: true,
               reason: "timeout_phone",
               decision: "phone-on-device"
             }
           ] =
             runs()
  end

  test "the phone transcript is ignored while the flag is off (v1.47): the CPU model answers" do
    script(%{primary: [{:error, :timeout}], ctc: [ok("හෙට උදේ", "en", "omnilingual-ctc-1b")]})
    phone = %{"text" => "hello", "lang" => "en-US", "engine" => "android-on-device"}

    assert {:ok, r} =
             Speech.transcribe_note("audio",
               duration_ms: 12_000,
               phone_transcript: phone,
               phone_fallback: false
             )

    assert_received {:ctc, opts}
    assert opts[:timeout] == 12_000
    assert opts[:priority] == "live"
    # The CTC model isn't language-conditioned: the language is its script's.
    assert r.language == "si"
    assert r.fallback == "ctc"
    assert r.model == "omnilingual-ctc-1b"
    refute Map.get(r, :source)
    assert [%{status: "ok", fell_back: true, reason: "timeout_ctc"}] = runs()
  end

  test "a malformed phone transcript is skipped (lenient), then the CPU model" do
    script(%{primary: [{:error, :timeout}], ctc: [ok("hello there", "en", "omnilingual-ctc-1b")]})

    assert {:ok, %{fallback: "ctc", language: "en"}} =
             Speech.transcribe_note("audio",
               duration_ms: 3_000,
               phone_transcript: %{"text" => "hi", "engine" => "x"},
               phone_fallback: true
             )
  end

  test "hallucination guard: a looping transcript is rejected; the CPU model answers" do
    loop = "මම ඒක " <> String.duplicate("කරන්නේ ", 20)

    script(%{
      primary: [ok(loop, "si", "faster-whisper-large-v3")],
      ctc: [ok("හෙට උදේ දහයට හමුවෙමු", "en", "omnilingual-ctc-1b")]
    })

    assert {:ok, r} = Speech.transcribe_note("audio", duration_ms: 16_000)
    assert r.fallback == "ctc" and r.fallback_reason == "rejected_repeat_token"
    refute Map.get(r, :rejected)
    assert [%{status: "ok", fell_back: true, reason: "rejected_repeat_token_ctc"}] = runs()
  end

  test "hallucination guard: every candidate rejected → the note is marked rejected" do
    loop = String.duplicate("කරන්නේ ", 20)

    script(%{
      primary: [ok(loop, "si", "faster-whisper-large-v3")],
      ctc: [ok(loop, "si", "omnilingual-ctc-1b")]
    })

    assert {:ok, r} = Speech.transcribe_note("audio", duration_ms: 16_000)
    assert r.rejected == "repeat_token" and r.model == "faster-whisper-large-v3"

    assert [%{status: "rejected", fell_back: false, reason: "rejected_repeat_token"}] = runs()
  end

  test "a fast error is retried once (as before), then the fallbacks, then failed" do
    script(%{
      primary: [{:error, :unavailable}, {:error, :unavailable}],
      ctc: [{:error, :timeout}]
    })

    assert {:error, :failed} = Speech.transcribe_note("audio", duration_ms: 5_000)
    assert_received {:primary, _}
    assert_received {:primary, _}
    assert_received {:ctc, _}

    assert [%{status: "error", fell_back: true, reason: "unavailable_none", decision: "none"}] =
             runs()
  end

  test "the retry after a fast error can succeed with no fallback" do
    script(%{primary: [{:error, :unavailable}, ok("hello", "en", "faster-whisper-large-v3")]})

    assert {:ok, %{fallback: nil, text: "hello"}} =
             Speech.transcribe_note("audio", duration_ms: 5_000)
  end

  test "SpeechStats.notes/1: today's notes per answering model, timeouts and fallbacks" do
    script(%{
      primary: [ok("a", "en", "faster-whisper-large-v3"), {:error, :timeout}, {:error, :timeout}],
      ctc: [ok("හෙට", "en", "omnilingual-ctc-1b"), {:error, :failed}]
    })

    for _ <- 1..3, do: Speech.transcribe_note("audio", duration_ms: 4_000)

    s = RisiMe.Agent.SpeechStats.notes()
    assert %{n: 3, timeouts: 2, fallbacks: 1, failed: 1} = s
    assert s.by_reason == %{"timeout_ctc" => 1, "timeout_none" => 1}
    models = Map.new(s.by_model, &{&1.model, &1.n})
    assert models == %{"faster-whisper-large-v3" => 1, "omnilingual-ctc-1b" => 1, "none" => 1}
  end

  test "phone_transcript/1 follows the §37.10 receiver rules" do
    assert {:ok, "a", "si-LK"} =
             Speech.phone_transcript(%{"text" => " a ", "lang" => "si-LK", "engine" => "e"})

    assert :error = Speech.phone_transcript(%{"text" => "  ", "lang" => "si-LK", "engine" => "e"})
    assert :error = Speech.phone_transcript(%{"text" => "a", "lang" => "s", "engine" => "e"})
    assert :error = Speech.phone_transcript(%{"text" => "a", "lang" => "si-LK", "engine" => ""})
    assert :error = Speech.phone_transcript(nil)
  end

  test "script_lang/1 and script_check/1" do
    assert Speech.script_lang("හෙට meeting එක") == "si"
    assert Speech.script_lang("நாளை சந்திப்போம்") == "ta"
    assert Speech.script_lang("see you tomorrow") == "en"
    assert %{ok: false, latin_chars: 4} = Speech.script_check("heta")
    assert %{ok: true, sinhala_chars: 3} = Speech.script_check("හෙට")
  end

  describe "SpeechGuard (the eval pause file)" do
    setup do
      dir = Path.join(System.tmp_dir!(), "speech-guard-#{System.unique_integer([:positive])}")
      path = Path.join(dir, "speech-eval.pause")
      old = Application.get_env(:risime, :risi_speech_pause_file)
      Application.put_env(:risime, :risi_speech_pause_file, path)

      on_exit(fn ->
        Application.put_env(:risime, :risi_speech_pause_file, old)
        File.rm_rf(dir)
      end)

      %{path: path}
    end

    test "a slow, fallen-back or failed live note pauses evals; a fast one doesn't", %{path: p} do
      assert SpeechGuard.reason({:ok, %{fallback: nil}}, 2_000, 6_000) == nil
      assert SpeechGuard.reason({:ok, %{fallback: nil}}, 9_500, 6_000) == "live_slow"
      assert SpeechGuard.reason({:ok, %{fallback: "ctc"}}, 1_000, 6_000) == "live_fallback_ctc"
      assert SpeechGuard.reason({:error, :failed}, 1_000, 6_000) == "live_failed"

      SpeechGuard.observe_live({:ok, %{fallback: nil}}, 1_000, 6_000)
      refute File.exists?(p)
      refute SpeechGuard.paused?()

      SpeechGuard.observe_live({:error, :failed}, 20_000, 6_000)
      assert File.read!(p) =~ ~r/^until=\d+ reason=live_failed by=risime-server\n$/
      assert SpeechGuard.paused?()
    end

    test "a manual pause (no until=) is never overwritten", %{path: p} do
      File.mkdir_p!(Path.dirname(p))
      File.write!(p, "paused by harsha\n")
      SpeechGuard.pause("live_slow")
      assert File.read!(p) == "paused by harsha\n"
      assert SpeechGuard.paused?()
    end

    test "an expired pause is not paused", %{path: p} do
      File.mkdir_p!(Path.dirname(p))
      File.write!(p, "until=1 reason=live_slow by=risime-server\n")
      refute SpeechGuard.paused?()
    end
  end
end
