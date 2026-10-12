defmodule RisiMe.Agent.SpeechHintTest do
  @moduledoc """
  Decision 071 amendment d: the speech service's `language_hint`. `Lang.speech_hint/2` (the
  participants' and the chat's si/ta languages, `[]` when none is known) and the call session
  (§36) sending it once per session on every chunk, transcribe and compare.
  """
  use RisiMeWeb.ConnCase, async: false

  import RisiMe.CallTranscribeHelpers

  alias RisiMe.Agent.{Clock, Lang, Memory, Seal}
  alias RisiMe.{Repo, TimeUUID}

  @moduletag capture_log: true

  defmodule Rec do
    @moduledoc false
    # `RisiMe.FakeSpeech` that also reports the options it was called with.
    @behaviour RisiMe.Agent.Speech

    @impl true
    def healthy?, do: RisiMe.FakeSpeech.healthy?()

    @impl true
    def transcribe(audio, opts \\ []) do
      report(:transcribe, opts)
      RisiMe.FakeSpeech.transcribe(audio, opts)
    end

    @impl true
    def compare(audio, opts \\ []) do
      report(:compare, opts)
      RisiMe.FakeSpeech.compare(audio, opts)
    end

    defp report(kind, opts) do
      if pid = Application.get_env(:risime, :risi_test_pid),
        do: send(pid, {:speech_opts, kind, opts})
    end
  end

  # A remembered human message (as `Memory.put_message/2` stores it).
  defp remember!(conv, sender, text) do
    id = Ecto.UUID.generate()
    {:ok, sealed} = Seal.seal_text("risi_memory_messages", id, "body", text)
    now = Clock.usec(DateTime.utc_now())

    {1, _} =
      Repo.insert_all(Memory.Message, [
        %{
          id: id,
          conversation_id: conv,
          message_id: TimeUUID.generate(),
          sender_id: sender,
          from_risi: false,
          at: now,
          body_sealed: sealed,
          inserted_at: now
        }
      ])

    :ok
  end

  describe "Lang.speech_hint/2" do
    setup do
      a = RisiMe.Fixtures.logged_in_user(display_name: "Test User A")
      b = RisiMe.Fixtures.logged_in_user(display_name: "Test User B")
      _ = RisiMe.RisiHelpers.risi_chat!([a.user, b.user])
      %{a: a.user.id, b: b.user.id, conv: "conv-#{System.unique_integer([:positive])}"}
    end

    test "nothing known, or only English: no hint", w do
      assert Lang.speech_hint([w.a, w.b], w.conv) == []
      remember!(w.conv, w.a, "Let's meet at ten")
      assert Lang.speech_hint([w.a, w.b], w.conv) == []
      assert Lang.speech_hint([nil, "not-a-uuid"], nil) == []
    end

    test "the participants' and the chat's si/ta, once each", w do
      remember!("other", w.a, "හෙට උදේ දහයට හමුවෙමු.")
      assert Lang.speech_hint([w.a, w.b], w.conv) == ["si"]

      remember!(w.conv, w.b, "நாளை சந்திப்போம்")
      assert Lang.conv_lang(w.conv) == "ta"
      assert Lang.speech_hint([w.a, w.b], w.conv) == ["si", "ta"]
      assert Lang.speech_hint([w.b], w.conv) == ["ta"]
    end
  end

  describe "the call session (§36)" do
    setup do
      w = transcribe_world!()
      Application.put_env(:risime, :risi_speech, Rec)
      w
    end

    defp say!(w, who, sid, seq, audio) do
      assert {202, %{"accepted" => true}} =
               upload!(w, who, sid, audio, %{
                 "seq" => seq,
                 "offset_ms" => seq * 12_000,
                 "duration_ms" => 12_000
               })

      assert_receive {:fake_speech, _, ^audio}, 2_000
    end

    test "nothing known about the languages: no language_hint", w do
      sid = start_on!(w)
      say!(w, :a, sid, 0, "en:hello")
      assert_received {:speech_opts, :transcribe, []}
    end

    test "a Sinhala participant and a Tamil chat: the hint on every chunk", w do
      remember!("elsewhere", w.b.user.id, "හෙට උදේ දහයට හමුවෙමු.")
      remember!(w.og, w.a.user.id, "நாளை சந்திப்போம்")
      sid = start_on!(w)
      say!(w, :a, sid, 0, "en:hello")
      assert_received {:speech_opts, :transcribe, [language_hint: hint]}
      assert Enum.sort(hint) == ["si", "ta"]
      say!(w, :b, sid, 0, "si:හෙට")
      assert_received {:speech_opts, :transcribe, [language_hint: ^hint]}
    end

    test "compare mode sends it too" do
      w = transcribe_world!()
      Application.put_env(:risime, :risi_speech, Rec)
      Application.put_env(:risime, :risi_dev_users, [w.a.user.id])
      :ok = RisiMe.Agent.CallTranscribe.put_dev_setting(w.a.user.id, true)
      remember!("elsewhere", w.a.user.id, "හෙට උදේ දහයට හමුවෙමු.")
      sid = start_on!(w)
      assert session!(sid).compare
      say!(w, :a, sid, 0, "si:හෙට")
      assert_received {:speech_opts, :compare, [language_hint: ["si"]]}
    end
  end
end
