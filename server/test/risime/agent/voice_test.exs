defmodule RisiMe.Agent.VoiceTest do
  @moduledoc """
  Contract v1.41 §37.6–§37.8 (decision 081): Risi and voice notes. The §14.3 blob opening against
  the contract vectors; the §37.2 validation; transcription only in Official (never Private, not
  forwarded, not `transcribe: false`, not with the sender's setting off, not over 5 min, not
  with the switch off); the `voice_transcript` post (ok / unclear / failed) against the
  examples; the audio path leaves no plaintext anywhere (blob store = ciphertext only, no new
  file, the buffer holds no key, the learning log no text); the delete rules; the Risi chat
  (`ask` in the transcript's language, the "couldn't hear" answer).
  """
  use RisiMeWeb.ConnCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import Ecto.Query, only: [from: 2]

  import RisiMe.CallTranscribeHelpers,
    only: [await_post: 3, refute_post: 2, refute_post: 3, eventually: 1]

  alias RisiMe.Agent.{Media, Secretary, Transcript, Voice, VoiceJobs}
  alias RisiMe.{Blobs, TimeUUID}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  @examples Path.expand("../../../../contract/v1/examples", __DIR__)
  @vectors Path.expand("../../../../contract/v1/media_vectors.json", __DIR__)

  defp example(name), do: @examples |> Path.join(name) |> File.read!() |> Jason.decode!()

  setup do
    keys = [
      :risi_voice_transcribe,
      :risi_action_items,
      :risi_ledger,
      :risi_dev_users,
      :risi_agent_flags,
      :risi_speech,
      :fake_speech_healthy,
      :fake_speech_down,
      :risi_voice_queue,
      :risi_test_pid,
      :risi_tools,
      :risi_turn
    ]

    old = for k <- keys, do: {k, Application.fetch_env(:risime, k)}

    on_exit(fn ->
      for {k, prev} <- old do
        case prev do
          {:ok, v} -> Application.put_env(:risime, k, v)
          :error -> Application.delete_env(:risime, k)
        end
      end
    end)

    Application.put_env(:risime, :risi_voice_transcribe, true)
    Application.put_env(:risime, :risi_speech, RisiMe.FakeSpeech)
    a = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
    b = RisiMe.Fixtures.logged_in_user(display_name: "Kamal")
    og = RisiMe.RisiHelpers.risi_chat!([a.user, b.user])
    intervals!(og, [a.user.id, b.user.id])
    %{a: a, b: b, og: og}
  end

  ## §14.3 encryption (test-only: the server has no encrypt API)

  defp encrypt(plain) do
    key = :crypto.strong_rand_bytes(32)
    kp = Media.payload_key(key)
    padded = plain <> :binary.copy(<<0>>, Media.padme(byte_size(plain)) - byte_size(plain))
    segs = for <<s::binary-size(65_536) <- padded>>, do: s
    tail = binary_part(padded, length(segs) * 65_536, byte_size(padded) - length(segs) * 65_536)
    segs = if tail == "", do: segs, else: segs ++ [tail]
    n = length(segs)

    cipher =
      segs
      |> Enum.with_index()
      |> Enum.map(fn {s, i} ->
        nonce = <<0::56, i::32, if(i == n - 1, do: 1, else: 0)::8>>

        {ct, tag} =
          :crypto.crypto_one_time_aead(:aes_256_gcm, kp, nonce, s, "risime-media-v1", true)

        ct <> tag
      end)
      |> IO.iodata_to_binary()

    {cipher, key}
  end

  # Uploads the ciphertext as `owner`'s `media` blob of `conv` (the store's real write path).
  defp blob!(owner, conv, cipher) do
    {tmp, io} = Blobs.open_tmp()
    :ok = IO.binwrite(io, cipher)
    :ok = :file.close(io)
    sha = :crypto.hash(:sha256, cipher)
    up = %{purpose: "media", conv: conv, client_blob_id: nil}
    {:created, reply} = Blobs.finish(owner, up, tmp, byte_size(cipher), sha)
    reply.blob_id
  end

  # A voice envelope of `audio` (the fake speech service reads it as a script) in `conv`.
  defp note!(owner, conv, audio, extra \\ %{}) do
    {cipher, key} = encrypt(audio)
    blob_id = blob!(owner, conv, cipher)

    env =
      example("voice_payload.json")
      |> Map.put("blob", %{
        "blob_id" => blob_id,
        "size" => byte_size(cipher),
        "sha256" => Base.encode64(:crypto.hash(:sha256, cipher))
      })
      |> Map.put("enc", %{
        "alg" => "A256GCM-S64K",
        "key" => Base.encode64(key),
        "plain_size" => byte_size(audio)
      })
      |> Map.merge(extra)

    {env, cipher, key}
  end

  defp receive_note(conv, user, env) do
    id = TimeUUID.generate()
    {id, Voice.received(conv, %{message_id: id, sender_id: user.id}, env)}
  end

  defp await_done({:started, pid}) do
    ref = Process.monitor(pid)
    assert_receive {:DOWN, ^ref, :process, ^pid, _}, 5_000
    :ok
  end

  describe "A256GCM-S64K (§14.3), in memory" do
    test "the contract vectors" do
      v = @vectors |> File.read!() |> Jason.decode!()
      hex = &Base.decode16!(&1, case: :lower)

      for p <- v["positive"] do
        assert Media.payload_key(hex.(p["key"])) == hex.(p["payload_key"]), p["name"]

        assert Media.padme(int(p["plain_size"])) ==
                 int(p["padded_size"])

        assert {:ok, plain} =
                 Media.decrypt(
                   hex.(p["cipher"]),
                   hex.(p["key"]),
                   int(p["plain_size"])
                 )

        assert plain == hex.(p["plain"]), p["name"]
      end

      for n <- v["negative"] do
        expect = n["expect"] |> String.downcase() |> String.to_atom()

        assert {:error, ^expect} =
                 Media.decrypt(
                   hex.(n["cipher"]),
                   hex.(n["key"]),
                   int(n["plain_size"])
                 ),
               n["name"]
      end
    end

    test "round trip of a two-segment note" do
      audio = "en:" <> String.duplicate("x", 70_000)
      {cipher, key} = encrypt(audio)
      assert {:ok, ^audio} = Media.decrypt(cipher, key, byte_size(audio))
    end
  end

  describe "validation (§37.2)" do
    test "the valid examples pass; the bad waveform, other mimes and durations are dropped", w do
      for f <-
            ~w(voice_payload.json voice_payload_forwarded.json voice_payload_reply_no_transcribe.json voice_payload_risi_chat.json),
          do: assert({:ok, _} = Voice.validate(example(f)), f)

      bad = example("voice_payload_bad_waveform.json")
      assert Voice.validate(bad) == :error
      ok = example("voice_payload.json")
      assert Voice.validate(%{ok | "mime" => "audio/wav"}) == :error
      assert Voice.validate(%{ok | "duration_ms" => 499}) == :error
      assert Voice.validate(%{ok | "duration_ms" => 1_800_001}) == :error
      assert Voice.validate(put_in(ok, ["enc", "nonce"], "AAAA")) == :error
      assert {:ok, _} = Voice.validate(%{ok | "mime" => "audio/mp4"})

      # Dropped as malformed before anything is fetched: no speech call.
      Application.put_env(:risime, :risi_test_pid, self())
      assert {_, :dropped} = receive_note(w.og, w.a.user, bad)
      refute_receive {:fake_speech, _, _}, 100
    end
  end

  describe "transcription in Official" do
    test "ok: the voice_transcript post matches the example; no plaintext anywhere", w do
      text = "හෙට උදේ දහයට හමුවෙමු. මම quotation එක එවන්නම්."
      audio = "si:" <> text
      {env, cipher, key} = note!(w.a.user.id, w.og, audio)
      before_tmp = File.ls!(System.tmp_dir!()) |> MapSet.new()
      before_blobs = blob_files()

      {id, started} = receive_note(w.og, w.a.user, env)
      assert {:started, _} = started
      {body, risi} = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(started)

      ex = example("envelope_risi_voice_transcript.json")
      assert Enum.sort(Map.keys(risi)) == Enum.sort(Map.keys(ex["risi"]))
      assert Enum.sort(Map.keys(risi["made_by"])) == Enum.sort(Map.keys(ex["risi"]["made_by"]))
      assert Enum.sort(Map.keys(risi["script"])) == Enum.sort(Map.keys(ex["risi"]["script"]))

      assert %{
               "status" => "ok",
               "text" => ^text,
               "language" => "si",
               "message_id" => ^id,
               "conversation_id" => conv,
               "duration_ms" => 12_400,
               "notify" => [],
               "call_ref" => nil,
               "script" => %{"ok" => true}
             } = risi

      assert conv == w.og and risi["speaker"] == w.a.user.id

      assert risi["model"] == "whisper-small-sinhala" and
               risi["made_by"]["model"] == risi["model"]

      assert body == "Voice note from Harsha (0:12): " <> text

      # No plaintext audio anywhere: the blob store holds the ciphertext only, nothing new was
      # written there or in the temp dir, and the buffered working copy has no key or blob id.
      assert blob_files() == before_blobs

      # (The temp dir is shared with other processes: whatever appeared there holds no audio. Files
      # this process can't read belong to other users/processes, e.g. Docker's runc-process*, and
      # can't have been written by the server under test.)
      for f <- MapSet.difference(File.ls!(System.tmp_dir!()) |> MapSet.new(), before_tmp),
          path = Path.join(System.tmp_dir!(), f),
          File.regular?(path),
          {:ok, content} <- [File.read(path)],
          do: refute(content =~ audio)

      for f <- blob_files() do
        content = File.read!(f)
        refute content =~ audio
      end

      assert Enum.any?(blob_files(), &(File.read!(&1) == cipher))
      [row] = Transcript.list(w.og)
      assert row.message_id == id

      assert %{"type" => "voice_transcript", "text" => ^text, "status" => "ok"} =
               Jason.decode!(row.plaintext)

      refute row.plaintext =~ Base.encode64(key)
      refute row.plaintext =~ env["blob"]["blob_id"]
      # PART 9: counted as the speaker's message under the note's id (never a request).
      assert [%{message_id: ^id, text: ^text, voice: true, duration_ms: 12_400} = m] =
               Secretary.text_messages(w.og)

      assert m.sender_id == w.a.user.id

      # One learning-log row: no text, no audio.
      [log] =
        eventually(fn ->
          RisiMe.Agent.LearningLog.list_by_chat(
            Secretary.chat_id(w.og),
            Calendar.strftime(Date.utc_today(), "%Y-%m")
          )
          |> Enum.filter(&(&1.task == "transcribe"))
          |> then(&(&1 != [] && &1))
        end)

      assert log.status == "ok" and log.output in [nil, ""]
      refute inspect(log) =~ "quotation"
      assert log.meta =~ "\"audio_s\":12.4"
    end

    test "unclear (romanised after the retry; low language probability) and failed", w do
      {env, _, _} = note!(w.a.user.id, w.og, "sx:Heta ude dahayata", %{"duration_ms" => 4_100})
      {_, s} = receive_note(w.og, w.a.user, env)
      {body, risi} = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(s)
      ex = example("envelope_risi_voice_transcript_unclear.json")
      assert Enum.sort(Map.keys(risi)) == Enum.sort(Map.keys(ex["risi"]))

      assert %{"status" => "unclear", "text" => nil, "romanised" => true, "language" => "si"} =
               risi

      assert %{"ok" => false, "sinhala_chars" => 0} = risi["script"]
      assert body == "Risi couldn't hear Harsha's voice note (0:04) clearly."

      {env, _, _} = note!(w.a.user.id, w.og, "lowp:mumble")
      {_, s} = receive_note(w.og, w.a.user, env)
      {_, risi} = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(s)
      assert %{"status" => "unclear", "text" => nil} = risi
      refute Map.has_key?(risi, "romanised")

      Application.put_env(:risime, :risi_test_pid, self())
      {env, _, _} = note!(w.a.user.id, w.og, "fail")
      {_, s} = receive_note(w.og, w.a.user, env)
      {_, risi} = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(s)
      # Failed twice (one retry), then posted as failed.
      assert_received {:fake_speech, :transcribe, "fail"}
      assert_received {:fake_speech, :transcribe, "fail"}
      assert %{"status" => "failed", "text" => nil, "model" => nil, "language" => nil} = risi
    end

    test "decision 071 d: the language_hint is the sender's and the chat's si/ta, else none", w do
      Application.put_env(:risime, :risi_speech, __MODULE__.HintSpeech)

      {env, _, _} = note!(w.a.user.id, w.og, "en:hello")
      {_, s} = receive_note(w.og, w.a.user, env)
      _ = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(s)
      assert_received {:speech_opts, opts}
      assert opts[:language_hint] == nil
      # Incident 2026-10-14: a note is asked live, within 3 × its duration (at least 15 s).
      assert opts[:priority] == "live" and opts[:timeout] >= 15_000

      remember!("elsewhere", w.a.user.id, "හෙට උදේ දහයට හමුවෙමු.")
      remember!(w.og, w.b.user.id, "நாளை சந்திப்போம்")
      # B's own language is not A's note's: only A's and the chat's.
      remember!("elsewhere", w.b.user.id, "நாளை சந்திப்போம்")
      {env, _, _} = note!(w.a.user.id, w.og, "si:හෙට")
      {_, s} = receive_note(w.og, w.a.user, env)
      _ = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(s)
      assert_received {:speech_opts, opts}
      assert opts[:language_hint] == ["si", "ta"]

      # Nothing typed in si/ta remembered any more, but A's Sinhala note was transcribed in this
      # chat: the chat's spoken language (the learning log) hints si for B's note too, and A's
      # own speech tally now says si.
      {env, _, _} = note!(w.b.user.id, w.og, "en:hello")
      RisiMe.Repo.delete_all(RisiMe.Agent.Memory.Message)
      {_, s} = receive_note(w.og, w.b.user, env)
      _ = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(s)
      assert_received {:speech_opts, opts}
      assert opts[:language_hint] == ["si"]
      assert RisiMe.Agent.Lang.speech_lang(w.a.user.id) == "si"
      assert RisiMe.Agent.Lang.speech_lang(w.b.user.id) == "en"
    end

    test "a blob of another conversation, or a changed blob, is never used", w do
      other = RisiMe.RisiHelpers.risi_chat!([w.a.user, w.b.user])
      intervals!(other, [w.a.user.id, w.b.user.id])
      {env, _, _} = note!(w.a.user.id, other, "en:hello")
      {_, s} = receive_note(w.og, w.a.user, env)
      {_, risi} = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(s)
      assert risi["status"] == "failed"
      refute_received {:fake_speech, _, _}

      {env, _, _} = note!(w.a.user.id, w.og, "en:hello")
      env = put_in(env, ["blob", "sha256"], Base.encode64(:crypto.hash(:sha256, "other")))
      {_, s} = receive_note(w.og, w.a.user, env)
      {_, risi} = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(s)
      assert risi["status"] == "failed"
      refute_received {:fake_speech, _, _}
    end

    test "not transcribed: forwarded, transcribe:false, the sender's setting, > 5 min, switch off",
         w do
      Application.put_env(:risime, :risi_test_pid, self())
      fwd = example("voice_payload_forwarded.json")["forwarded"]

      cases = [
        %{"forwarded" => fwd},
        %{"transcribe" => false},
        %{"duration_ms" => 300_001}
      ]

      for extra <- cases do
        {env, _, _} = note!(w.a.user.id, w.og, "en:hello", extra)
        assert {_, :skip} = receive_note(w.og, w.a.user, env)
      end

      # Exactly 5 min is still transcribed.
      {env, _, _} = note!(w.a.user.id, w.og, "en:five", %{"duration_ms" => 300_000})
      {_, s} = receive_note(w.og, w.a.user, env)
      assert {:started, _} = s
      _ = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(s)
      assert_received {:fake_speech, :transcribe, "en:five"}

      # The server-side setting (either opt-out wins).
      {:ok, _} = Voice.put_setting(w.a.user.id, %{"transcribe" => false, "action_items" => true})
      {env, _, _} = note!(w.a.user.id, w.og, "en:hello")
      assert {_, :skip} = receive_note(w.og, w.a.user, env)
      # b's notes are still transcribed.
      {env, _, _} = note!(w.b.user.id, w.og, "en:from b")
      assert {_, {:started, _} = s} = receive_note(w.og, w.b.user, env)
      _ = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(s)

      # The kill switch, and an unhealthy speech service.
      Application.put_env(:risime, :risi_voice_transcribe, false)
      {env, _, _} = note!(w.b.user.id, w.og, "en:hello")
      assert {_, :skip} = receive_note(w.og, w.b.user, env)
      Application.put_env(:risime, :risi_voice_transcribe, true)
      Application.put_env(:risime, :fake_speech_healthy, false)
      assert {_, :skip} = receive_note(w.og, w.b.user, env)

      refute_received {:fake_speech, _, "en:hello"}
      refute_post(w.og, "voice_transcript", 100)
    end

    test "Private: no Risi path at all", w do
      Application.put_env(:risime, :risi_test_pid, self())
      # A Private group of the same members (the Official group's Private side) and a DM.
      private = private_group!([w.a.user.id, w.b.user.id])
      dm = RisiMe.Messaging.conversation_id(w.a.user.id, w.b.user.id)

      for conv <- [private, dm] do
        {env, _, _} = note!(w.a.user.id, w.og, "en:private words")
        assert {_, :skip} = receive_note(conv, w.a.user, env)
        assert Voice.decide(conv, w.a.user.id, env, elem(Voice.validate(env), 1)) == :skip
        # Nothing reaches the buffer either.
        assert {:error, :private_tab} =
                 Transcript.put(conv, %{
                   message_id: TimeUUID.generate(),
                   sender_id: w.a.user.id,
                   plaintext: "{}"
                 })
      end

      refute_received {:fake_speech, _, _}
      refute_post(private, "voice_transcript", 100)
    end
  end

  describe "deletes (§37.6)" do
    test "the note deleted for everyone takes the transcript; transcript_delete by the sender only",
         w do
      {env, _, _} = note!(w.a.user.id, w.og, "en:delete me")
      {id, s} = receive_note(w.og, w.a.user, env)
      {_, _} = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(s)
      [%{transcript_message_id: tid}] = mapping(w.og)

      # Kamal's transcript_delete is ignored.
      action = %{example("envelope_risi_action_transcript_delete.json") | "target" => id}
      act!(w.og, w.b.user, action)
      refute_received {:risi_delete, _, _}
      assert [_] = mapping(w.og)

      # Harsha's deletes it (and its working copy).
      act!(w.og, w.a.user, action)
      assert_received {:risi_delete, og, [^tid]}
      assert og == w.og
      assert mapping(w.og) == []
      refute Enum.any?(Transcript.list(w.og), &(&1.message_id == id))

      # A note deleted for everyone (§15): a job deletes its transcript.
      {env, _, _} = note!(w.a.user.id, w.og, "en:second")
      {id2, s} = receive_note(w.og, w.a.user, env)
      _ = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(s)
      [%{transcript_message_id: tid2}] = mapping(w.og)
      :ok = Secretary.message_deleted(w.og, [id2])
      assert [job] = all_enqueued(worker: Job, args: %{"kind" => "voice_delete"})
      assert :ok = perform_job(Job, job.args)
      assert_received {:risi_delete, _, [^tid2]}
      assert mapping(w.og) == []

      # Official off forgets the mapping.
      {env, _, _} = note!(w.a.user.id, w.og, "en:third")
      {_, s} = receive_note(w.og, w.a.user, env)
      _ = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(s)
      :ok = Secretary.forget(w.og)
      assert mapping(w.og) == []
    end
  end

  describe "the Risi chat (§37.8)" do
    setup w do
      rc = RisiMe.RisiHelpers.own_risi_chat!(w.a.user)
      intervals!(rc, [w.a.user.id])
      %{rc: rc}
    end

    test "transcribed, then handled as an ask with the note's request_id, in its language", w do
      rid = Ecto.UUID.generate()
      text = "හෙට මගේ meetings මොනවද?"

      # `transcribe: false` is ignored here (the note is a request), and so is the setting.
      {:ok, _} = Voice.put_setting(w.a.user.id, %{"transcribe" => false, "action_items" => true})
      {env, _, _} = note!(w.a.user.id, w.rc, "si:" <> text, %{"request_id" => rid})
      {id, s} = receive_note(w.rc, w.a.user, env)
      {_, risi} = await_post(w.rc, "voice_transcript", fn _ -> true end)
      await_done(s)
      assert %{"status" => "ok", "text" => ^text, "language" => "si"} = risi

      # The buffered request the §24.11 path picks up.
      assert %{
               "type" => "risi_request",
               "action" => "ask",
               "request_id" => ^rid,
               "text" => ^text,
               "lang" => "si",
               "via" => "voice"
             } = Secretary.envelope(w.rc, id)

      assert [job] = all_enqueued(worker: Job, args: %{"kind" => "request", "request_id" => rid})
      assert job.args["message_id"] == id and job.args["user_id"] == w.a.user.id
    end

    test "P1 2026-10-14: the request carries the note's sending device (its skills, its tools)",
         w do
      rid = Ecto.UUID.generate()
      dev = Ecto.UUID.generate()
      {env, _, _} = note!(w.a.user.id, w.rc, "en:send Upali a message", %{"request_id" => rid})
      id = TimeUUID.generate()

      s =
        Voice.received(w.rc, %{message_id: id, sender_id: w.a.user.id, sender_device: dev}, env)

      {_, _} = await_post(w.rc, "voice_transcript", fn _ -> true end)
      await_done(s)

      # Before the fix the buffered request had `sender_device: nil`, so the turn ran with no
      # device and every phone skill read "permission is off on this phone".
      assert %{"request_id" => ^rid, "__device" => ^dev} = Secretary.envelope(w.rc, id)
    end

    test "unclear: the transcript card says so and Risi answers the fixed sentence; no ask", w do
      # Harsha 2026-10-14: empty/noise-only = "very short"; a low-confidence guess = "say it
      # again"; never "type it".
      for {audio, want} <- [
            {"silence", "That was very short. Could you say it again?"},
            {"lowp:mumble", "I couldn't hear that clearly. Could you say it again?"}
          ] do
        rid = Ecto.UUID.generate()
        {env, _, _} = note!(w.a.user.id, w.rc, audio, %{"request_id" => rid})
        {_, s} = receive_note(w.rc, w.a.user, env)
        {_, card} = await_post(w.rc, "voice_transcript", fn _ -> true end)
        assert card["status"] == "unclear"
        {body, answer} = await_post(w.rc, "answer", &(&1["request_id"] == rid))
        await_done(s)
        assert body == want
        refute body =~ "type"
        assert answer["request_id"] == rid and answer["notify"] == [w.a.user.id]
        assert all_enqueued(worker: Job, args: %{"kind" => "request"}) == []
      end
    end

    test "hallucination guard: a looping transcript is unclear; Risi asks again; no ask", w do
      rid = Ecto.UUID.generate()
      loop = "si:මම ඒක " <> String.duplicate("කරන්නේ ", 20)
      {env, _, _} = note!(w.a.user.id, w.rc, loop, %{"request_id" => rid})
      {_, s} = receive_note(w.rc, w.a.user, env)
      {_, card} = await_post(w.rc, "voice_transcript", fn _ -> true end)
      assert card["status"] == "unclear" and card["text"] == nil
      {body, _} = await_post(w.rc, "answer", &(&1["request_id"] == rid))
      await_done(s)
      assert body == "I couldn't hear that clearly. Could you say it again?"
      assert all_enqueued(worker: Job, args: %{"kind" => "request"}) == []
    end

    test "a note under 1 s: the 'very short' line at once, no speech call, no ask", w do
      Application.put_env(:risime, :risi_test_pid, self())
      RisiMe.Messaging.subscribe(w.a.user.id)

      for ms <- [500, 999] do
        rid = Ecto.UUID.generate()

        {env, _, _} =
          note!(w.a.user.id, w.rc, "en:hi", %{"request_id" => rid, "duration_ms" => ms})

        {_, {:replied, pid}} = receive_note(w.rc, w.a.user, env)
        await_done({:started, pid})
        {body, _} = await_post(w.rc, "answer", &(&1["request_id"] == rid))
        assert body == "That was very short. Could you say it again?"
        refute_received {:fake_speech, _, _}
        assert all_enqueued(worker: Job, args: %{"kind" => "request", "request_id" => rid}) == []
      end

      # 1 s exactly is transcribed as usual.
      {_rid, _id, card, _args} =
        voice_ask!(w, "en:one second", %{"duration_ms" => 1_000})

      assert card["status"] == "ok"
    end

    # PART 4d: a voice note in the Risi chat is a turn like a typed message.
    defp final(answer),
      do: %{"tool" => "final", "answer" => answer, "sources" => [], "next_steps" => []}

    defp voice_ask!(w, audio, extra \\ %{}) do
      rid = Ecto.UUID.generate()
      {env, _, _} = note!(w.a.user.id, w.rc, audio, Map.put(extra, "request_id", rid))
      {id, s} = receive_note(w.rc, w.a.user, env)
      {_, card} = await_post(w.rc, "voice_transcript", fn _ -> true end)
      await_done(s)
      [job] = all_enqueued(worker: Job, args: %{"kind" => "request", "request_id" => rid})
      {rid, id, card, job.args}
    end

    test "PART 4d: 'Risi is typing' at once, then the turn answers in the note's language", w do
      Application.put_env(:risime, :risi_tools, true)
      RisiMe.Messaging.subscribe(w.a.user.id)
      si_answer = "වතුර උණු කරලා කෝපි කුඩු දාන්න."
      RisiMe.RisiHelpers.fake_llm!(fn "risi_next_action", _ -> final(si_answer) end)

      rid = Ecto.UUID.generate()
      text = "කෝපි හදන හැටි කියන්න"
      {env, _, _} = note!(w.a.user.id, w.rc, "si:" <> text, %{"request_id" => rid})
      {_, s} = receive_note(w.rc, w.a.user, env)

      # The typing bubble goes out before the speech service is even asked.
      assert_receive {:signal,
                      %{
                        kind: "risi_progress",
                        data: %{"request_id" => ^rid, "state" => "working"}
                      }},
                     500

      {_, card} = await_post(w.rc, "voice_transcript", fn _ -> true end)
      await_done(s)
      assert card["status"] == "ok" and card["language"] == "si"

      [job] = all_enqueued(worker: Job, args: %{"kind" => "request", "request_id" => rid})
      assert :ok = perform_job(Job, job.args)
      {body, answer} = await_post(w.rc, "answer", &(&1["request_id"] == rid))
      assert body == si_answer and answer["notify"] == [w.a.user.id]

      # The model got the transcript as the user's message and the Sinhala language rule.
      [req | _] = Agent.get(Process.get(:risi_llm_rec), & &1)
      prompt = Jason.encode!(req["messages"])
      assert prompt =~ text
      assert prompt =~ "the asker wrote in Sinhala"
    end

    test "PART 4d: the model down gets the offline notice, then the answer by itself", w do
      Application.put_env(:risime, :risi_tools, true)
      RisiMe.RisiHelpers.fake_llm!(fn _, _ -> {:status, 503} end)
      {rid, _id, _card, args} = voice_ask!(w, "en:tell me a short joke")

      assert {:snooze, _} = perform_job(Job, args)
      {body, notice} = await_post(w.rc, "answer", &(&1["request_id"] == rid))
      assert body == RisiMe.Agent.Offline.text() and notice["notify"] == [w.a.user.id]

      RisiMe.RisiHelpers.fake_llm!(fn "risi_next_action", _ -> final("A short joke.") end)
      assert :ok = perform_job(Job, args)
      assert {"A short joke.", _} = await_post(w.rc, "answer", &(&1["request_id"] == rid))
    end

    test "PART 4d never silent: the switch off or a note over 5 min get an answer", w do
      RisiMe.Messaging.subscribe(w.a.user.id)

      for {prep, extra, want} <- [
            {fn -> Application.put_env(:risime, :risi_voice_transcribe, false) end, %{},
             "Voice notes to Risi are coming soon for your account."},
            {fn -> Application.put_env(:risime, :risi_voice_transcribe, true) end,
             %{"duration_ms" => 300_001}, "over 5 minutes"}
          ] do
        prep.()
        rid = Ecto.UUID.generate()
        {env, _, _} = note!(w.a.user.id, w.rc, "en:hello", Map.put(extra, "request_id", rid))
        {_, {:replied, pid}} = receive_note(w.rc, w.a.user, env)
        await_done({:started, pid})
        {body, a} = await_post(w.rc, "answer", &(&1["request_id"] == rid))
        assert body =~ want and a["notify"] == [w.a.user.id]
        refute body =~ "type"

        assert_receive {:signal,
                        %{kind: "risi_progress", data: %{"request_id" => ^rid, "state" => "done"}}}

        refute_received {:fake_speech, _, _}
        assert all_enqueued(worker: Job, args: %{"kind" => "request", "request_id" => rid}) == []
      end
    end

    test "PART 4d: a speech service that looks unhealthy is still tried (with the ack at once)",
         w do
      Application.put_env(:risime, :fake_speech_healthy, false)
      rid = Ecto.UUID.generate()
      {env, _, _} = note!(w.a.user.id, w.rc, "en:still heard", %{"request_id" => rid})
      {_, s} = receive_note(w.rc, w.a.user, env)
      {body, _} = await_post(w.rc, "answer", &(&1["request_id"] == rid))
      assert body == "Got your voice note. I'll reply in a moment."
      {_, card} = await_post(w.rc, "voice_transcript", fn _ -> true end)
      await_done(s)
      assert card["status"] == "ok" and card["text"] == "still heard"
      assert [_] = all_enqueued(worker: Job, args: %{"kind" => "request", "request_id" => rid})
    end

    # Proposal 2026-10-14-voice-queue (P1 hotfix): the service down never gets "type it".
    test "speech down: ack at once, queued (sealed, no audio), answered when the service is back",
         w do
      Application.put_env(:risime, :fake_speech_down, true)
      RisiMe.Messaging.subscribe(w.a.user.id)
      rid = Ecto.UUID.generate()
      {env, _cipher, key} = note!(w.a.user.id, w.rc, "en:what's on today", %{"request_id" => rid})
      {id, s} = receive_note(w.rc, w.a.user, env)
      {body, ack} = await_post(w.rc, "answer", &(&1["request_id"] == rid))
      await_done(s)
      assert body == "Got your voice note. I'll reply in a moment."
      assert ack["notify"] == [w.a.user.id]
      refute body =~ "type"
      refute_post(w.rc, "voice_transcript")

      assert_receive {:signal,
                      %{kind: "risi_progress", data: %{"request_id" => ^rid, "state" => "done"}}}

      # The queued row: the key only sealed (never in clear), no audio; the job holds ids only.
      assert RisiMe.Agent.VoiceQueue.queued?(id)

      [sealed] =
        RisiMe.Repo.all(
          from q in "risi_voice_queue", where: q.note_message_id == ^id, select: q.sealed
        )

      refute :binary.match(sealed, key) != :nomatch
      refute :binary.match(sealed, Base.encode64(key)) != :nomatch
      assert [job] = all_enqueued(worker: RisiMe.Agent.VoiceQueue)
      assert Map.keys(job.args) |> Enum.sort() == ~w(conv note request_id sender until)
      refute Jason.encode!(job.args) =~ Base.encode64(key)

      # Still down: snoozed with backoff, nothing posted.
      assert {:snooze, 15} = perform_job(RisiMe.Agent.VoiceQueue, job.args)
      refute_post(w.rc, "answer")

      # Back: the normal card, the ask, and the row is gone.
      Application.put_env(:risime, :fake_speech_down, false)
      assert :ok = perform_job(RisiMe.Agent.VoiceQueue, job.args)
      {_, card} = await_post(w.rc, "voice_transcript", fn _ -> true end)
      assert card["status"] == "ok" and card["text"] == "what's on today"
      refute RisiMe.Agent.VoiceQueue.queued?(id)
      assert [_] = all_enqueued(worker: Job, args: %{"kind" => "request", "request_id" => rid})
    end

    test "the queued row carries the phone/Google transcript, sealed (fallback input only)", w do
      id = TimeUUID.generate()
      phone = %{"text" => "හෙට එනවා", "lang" => "si-LK", "engine" => "android-online-google"}

      note = %{
        blob_id: Ecto.UUID.generate(),
        size: 100,
        sha256: :crypto.strong_rand_bytes(32),
        key: :crypto.strong_rand_bytes(32),
        plain_size: 80,
        mime: "audio/ogg; codecs=opus",
        duration_ms: 3_000,
        phone_transcript: phone
      }

      assert {:ok, _} =
               RisiMe.Agent.VoiceQueue.enqueue(w.rc, id, w.a.user.id, note, Ecto.UUID.generate())

      [sealed] =
        RisiMe.Repo.all(
          from q in "risi_voice_queue", where: q.note_message_id == ^id, select: q.sealed
        )

      refute sealed =~ "හෙට"
      {:ok, k} = RisiMe.Agent.Seal.data()
      {:ok, m} = RisiMe.Agent.Seal.open(k, "risi_voice_queue:#{w.rc}:#{id}", sealed)
      assert m["phone_transcript"] == phone
    end

    test "speech down past the bound: the failed card and an honest answer, never 'type it'", w do
      Application.put_env(:risime, :fake_speech_down, true)
      Application.put_env(:risime, :risi_voice_queue, max_ms: 0)
      rid = Ecto.UUID.generate()
      {env, _, _} = note!(w.a.user.id, w.rc, "en:hello", %{"request_id" => rid})
      {id, s} = receive_note(w.rc, w.a.user, env)
      {_, _ack} = await_post(w.rc, "answer", &(&1["request_id"] == rid))
      await_done(s)

      [job] = all_enqueued(worker: RisiMe.Agent.VoiceQueue)
      assert :ok = perform_job(RisiMe.Agent.VoiceQueue, job.args)
      {_, card} = await_post(w.rc, "voice_transcript", fn _ -> true end)
      assert card["status"] == "failed" and card["text"] == nil
      {body, _} = await_post(w.rc, "answer", &(&1["request_id"] == rid))
      assert body =~ "I couldn't listen to your voice note"
      refute body =~ "type"
      refute RisiMe.Agent.VoiceQueue.queued?(id)
      assert all_enqueued(worker: Job, args: %{"kind" => "request", "request_id" => rid}) == []
    end

    test "canary (2a): the Risi chat of a RISI_DEV_USERS user transcribes; others get the honest off line",
         w do
      Application.put_env(:risime, :risi_voice_transcribe, false)
      Application.put_env(:risime, :risi_agent_flags, %{"RISI_VOICE_TRANSCRIBE" => "canary"})
      Application.put_env(:risime, :risi_dev_users, [String.downcase(w.a.user.id)])
      assert RisiMe.Agents.Flags.feature_for("RISI_VOICE_TRANSCRIBE", w.a.user.id)
      {_rid, _id, card, _args} = voice_ask!(w, "en:canary hears me")
      assert card["status"] == "ok" and card["text"] == "canary hears me"

      rc = RisiMe.RisiHelpers.own_risi_chat!(w.b.user)
      intervals!(rc, [w.b.user.id])
      rid = Ecto.UUID.generate()
      {env, _, _} = note!(w.b.user.id, rc, "en:hi", %{"request_id" => rid})
      assert {_, {:replied, pid}} = receive_note(rc, w.b.user, env)
      await_done({:started, pid})
      {body, _} = await_post(rc, "answer", &(&1["request_id"] == rid))
      assert body == "Voice notes to Risi are coming soon for your account."
      refute body =~ "type"
    end

    test "without a request_id, or forwarded, nothing happens", w do
      {env, _, _} = note!(w.a.user.id, w.rc, "en:hi")
      assert {_, :skip} = receive_note(w.rc, w.a.user, env)

      fwd = example("voice_payload_forwarded.json")["forwarded"]

      {env, _, _} =
        note!(w.a.user.id, w.rc, "en:hi", %{
          "request_id" => Ecto.UUID.generate(),
          "forwarded" => fwd
        })

      assert {_, :skip} = receive_note(w.rc, w.a.user, env)
    end
  end

  describe "the setting (§37.7)" do
    test "GET/PUT /api/v1/me/risi/voice against the examples", w do
      conn = fn -> build_conn() |> put_req_header("authorization", "Bearer " <> w.a.token) end

      assert %{"transcribe" => true, "action_items" => true} =
               conn.() |> get("/api/v1/me/risi/voice") |> json_response(200)

      put = example("me_risi_voice_put.json")
      reply = conn.() |> put("/api/v1/me/risi/voice", put) |> json_response(200)
      assert reply == example("me_risi_voice_reply.json")
      assert conn.() |> get("/api/v1/me/risi/voice") |> json_response(200) == reply
      assert Voice.setting(w.a.user.id) == %{transcribe: false, action_items: true}
      # b is unaffected.
      assert Voice.setting(w.b.user.id) == %{transcribe: true, action_items: true}

      assert conn.()
             |> put("/api/v1/me/risi/voice", %{"transcribe" => "no"})
             |> json_response(422)

      assert build_conn() |> get("/api/v1/me/risi/voice") |> response(401)
    end

    test "/auth/config voice_transcribe: on only with the switch, Risi and the speech service" do
      ex = example("auth_config_v141.json")
      body = build_conn() |> get("/api/v1/auth/config") |> json_response(200)
      assert body["voice_transcribe"] == ex["voice_transcribe"]

      Application.put_env(:risime, :fake_speech_healthy, false)

      refute Map.has_key?(
               build_conn() |> get("/api/v1/auth/config") |> json_response(200),
               "voice_transcribe"
             )

      Application.put_env(:risime, :fake_speech_healthy, true)
      Application.put_env(:risime, :risi_voice_transcribe, false)

      refute Map.has_key?(
               build_conn() |> get("/api/v1/auth/config") |> json_response(200),
               "voice_transcribe"
             )
    end
  end

  describe "PART 9: an Official voice note is understood like a typed message" do
    # The memory rows of `conv` (ids only).
    defp memory_rows(conv) do
      RisiMe.Repo.all(
        from m in RisiMe.Agent.Memory.Message,
          where: m.conversation_id == ^conv,
          select: %{message_id: m.message_id, sender_id: m.sender_id, from_risi: m.from_risi}
      )
    end

    defp transcribe!(w, user, audio, conv \\ nil) do
      conv = conv || w.og
      {env, _, _} = note!(user.id, conv, audio)
      {id, s} = receive_note(conv, user, env)
      assert {:started, _} = s
      _ = await_post(conv, "voice_transcript", &(&1["message_id"] == id))
      await_done(s)
      id
    end

    defp jobs(kind, conv),
      do: all_enqueued(worker: Job, args: %{"kind" => kind, "conv" => conv})

    test "memory: the speaker's message under the note's id; the transcript post isn't remembered",
         w do
      id = transcribe!(w, w.a.user, "en:I will send the quotation tomorrow")
      a = w.a.user.id

      assert [%{message_id: ^id, sender_id: ^a, from_risi: false}] =
               eventually(fn -> memory_rows(w.og) |> then(&(&1 != [] && &1)) end)

      # A speaker who opted out of memory is transcribed but not remembered (§35).
      :ok = RisiMe.Agent.Memory.set_learning(w.b.user.id, w.og, false)
      _ = transcribe!(w, w.b.user, "en:I will call the bank")
      assert [%{message_id: ^id}] = memory_rows(w.og)

      # An unclear note teaches nothing.
      {env, _, _} = note!(a, w.og, "en:")
      {_, s} = receive_note(w.og, w.a.user, env)
      _ = await_post(w.og, "voice_transcript", &(&1["status"] == "unclear"))
      await_done(s)
      assert [%{message_id: ^id}] = memory_rows(w.og)
    end

    test "the Ledger: ledger off = the v1.24 extraction; on = the quiet rule counts the note",
         w do
      id = transcribe!(w, w.a.user, "en:I will send the quotation tomorrow")
      assert [_] = jobs("extract", w.og)

      Application.put_env(:risime, :risi_ledger, true)
      id2 = transcribe!(w, w.a.user, "en:Kamal will book the van")
      assert [_] = jobs("discussion_quiet", w.og)
      ids = RisiMe.Agent.Ledger.counted(w.og, nil) |> Enum.map(& &1.message_id)
      assert id in ids and id2 in ids
      # §38 off: no voice-note extraction.
      assert jobs("voice_actions", w.og) == []
    end

    test "§38 on: the note's promises come from its action items only (never offered twice)", w do
      Application.put_env(:risime, :risi_ledger, true)
      Application.put_env(:risime, :risi_action_items, true)
      id = transcribe!(w, w.a.user, "en:I will send the quotation tomorrow")

      assert [%{args: %{"message_id" => ^id}}] = jobs("voice_actions", w.og)
      assert jobs("discussion_quiet", w.og) == []
      assert RisiMe.Agent.Ledger.counted(w.og, nil) == []
      # Still in memory and in the chat's context (summaries, answers).
      assert [%{message_id: ^id}] = Secretary.text_messages(w.og)

      assert [%{message_id: ^id}] =
               eventually(fn -> memory_rows(w.og) |> then(&(&1 != [] && &1)) end)

      # A sender with action items off: the quiet rule counts the note instead.
      {:ok, _} = Voice.put_setting(w.b.user.id, %{"transcribe" => true, "action_items" => false})
      id2 = transcribe!(w, w.b.user, "en:I will call the bank")
      assert [_] = jobs("discussion_quiet", w.og)
      assert [%{message_id: ^id2}] = RisiMe.Agent.Ledger.counted(w.og, nil)
    end

    test "transcript_delete takes what Risi learned from the note", w do
      id = transcribe!(w, w.a.user, "en:I will send the quotation tomorrow")
      assert [_] = eventually(fn -> memory_rows(w.og) |> then(&(&1 != [] && &1)) end)
      action = %{example("envelope_risi_action_transcript_delete.json") | "target" => id}
      act!(w.og, w.a.user, action)
      assert memory_rows(w.og) == []
      assert Secretary.text_messages(w.og) == []
    end

    test "never Private, never the Risi chat", w do
      Application.put_env(:risime, :risi_test_pid, self())
      private = private_group!([w.a.user.id, w.b.user.id])
      dm = RisiMe.Messaging.conversation_id(w.a.user.id, w.b.user.id)

      # Even a transcript-shaped message handed to the typed-message entry points does nothing.
      pt =
        Jason.encode!(%{
          "v" => 1,
          "type" => "voice_transcript",
          "status" => "ok",
          "text" => "private words",
          "duration_ms" => 12_400
        })

      for conv <- [private, dm] do
        {env, _, _} = note!(w.a.user.id, w.og, "en:private words")
        assert {_, :skip} = receive_note(conv, w.a.user, env)
        m = %{message_id: TimeUUID.generate(), sender_id: w.a.user.id, plaintext: pt}
        assert :ok = RisiMe.Agent.Memory.put_message(conv, m)
        assert :ok = Secretary.on_message(conv, m)
        assert memory_rows(conv) == []
        assert all_enqueued(worker: Job) |> Enum.filter(&(&1.args["conv"] == conv)) == []
      end

      refute_received {:fake_speech, _, _}

      # The Risi chat: the note is a request (§37.8), never memory or the Ledger.
      rc = RisiMe.RisiHelpers.own_risi_chat!(w.a.user)
      intervals!(rc, [w.a.user.id])
      rid = Ecto.UUID.generate()
      {env, _, _} = note!(w.a.user.id, rc, "en:what is on tomorrow", %{"request_id" => rid})
      {_, s} = receive_note(rc, w.a.user, env)
      _ = await_post(rc, "voice_transcript", fn _ -> true end)
      await_done(s)
      assert memory_rows(rc) == []
      assert jobs("extract", rc) == [] and jobs("discussion_quiet", rc) == []
    end
  end

  describe "§37.11 spoken @Risi (proposal 2026-10-14-spoken-risi)" do
    alias RisiMe.Agent.SpokenMention

    setup w do
      old = Application.fetch_env(:risime, :risi_spoken_mention)

      on_exit(fn ->
        case old do
          {:ok, v} -> Application.put_env(:risime, :risi_spoken_mention, v)
          :error -> Application.delete_env(:risime, :risi_spoken_mention)
        end
      end)

      # Default canary: Harsha (A) is the dev user.
      Application.delete_env(:risime, :risi_spoken_mention)
      Application.put_env(:risime, :risi_dev_users, [String.downcase(w.a.user.id)])
      :ok
    end

    defp spoken!(w, user, audio) do
      {env, _, _} = note!(user.id, w.og, audio)
      {id, s} = receive_note(w.og, user, env)
      assert {:started, _} = s
      _ = await_post(w.og, "voice_transcript", &(&1["message_id"] == id))
      await_done(s)
      id
    end

    defp runs,
      do:
        RisiMe.Repo.all(
          from r in "risi_agent_runs",
            where: r.agent == "spoken_mention",
            select: %{mode: r.mode, decision: r.decision, by: r.by, reason: r.reason}
        )

    defp requests(conv),
      do: all_enqueued(worker: Job, args: %{"kind" => "request", "conv" => conv})

    test "canary, dev user: an addressed note is an ask (once), not extracted", w do
      assert SpokenMention.mode() == :canary
      id = spoken!(w, w.a.user, "en:Risi, remind me to send the quotation on Friday.")
      rid = SpokenMention.request_id(id)

      assert [job] = requests(w.og)

      assert job.args["request_id"] == rid and job.args["message_id"] == id and
               job.args["user_id"] == w.a.user.id

      # The working copy carries the request: the text after the vocative.
      assert %{
               "type" => "voice_transcript",
               "spoken_request" => %{
                 "request_id" => ^rid,
                 "text" => "remind me to send the quotation on Friday."
               }
             } = Secretary.envelope(w.og, id)

      # A request, so (as §37.8) no extraction and no quiet-rule count.
      assert jobs("extract", w.og) == [] and jobs("discussion_quiet", w.og) == []
      assert [%{mode: "canary", decision: "addressed", by: "rules"}] = runs()

      # A repeat is the same request (unique request_id): still one job.
      :ok = Secretary.spoken_request(w.og, id, w.a.user.id, rid)
      assert [_] = requests(w.og)

      # Never text in the run rows.
      raw =
        RisiMe.Repo.all(from r in "risi_agent_runs", select: fragment("row_to_json(?)::text", r))

      refute Enum.any?(raw, &(&1 =~ "quotation"))
    end

    test "the request job answers with voice_ref and the note in refs", w do
      Application.put_env(:risime, :risi_tools, true)

      RisiMe.RisiHelpers.fake_llm!(fn "risi_next_action", _ ->
        %{"tool" => "final", "answer" => "On Friday.", "sources" => [], "next_steps" => []}
      end)

      id = spoken!(w, w.a.user, "en:Risi, when is the quotation due?")
      [job] = requests(w.og)
      assert :ok = perform_job(Job, job.args)
      rid = job.args["request_id"]
      {_, answer} = await_post(w.og, "answer", &(&1["request_id"] == rid))

      assert answer["voice_ref"] == %{"message_id" => id, "speaker" => w.a.user.id}
      assert answer["notify"] == [w.a.user.id]

      # The model got the question after the vocative.
      [req | _] = Agent.get(Process.get(:risi_llm_rec), & &1)
      assert Jason.encode!(req["messages"]) =~ "when is the quotation due?"
    end

    test "merely mentioned: an ordinary note (extraction as before), no request", w do
      _ = spoken!(w, w.a.user, "en:I told Risi yesterday to remind me about the quotation.")
      _ = spoken!(w, w.a.user, "en:Risi said the meeting is at three.")
      assert requests(w.og) == []
      assert [_] = jobs("extract", w.og)
      assert Enum.sort(Enum.map(runs(), & &1.decision)) == ["mentioned", "mentioned"]
    end

    test "canary for a non-dev user and shadow: logged, never a request", w do
      _ = spoken!(w, w.b.user, "en:Risi, remind me to call the bank.")
      assert requests(w.og) == []
      assert [%{mode: "shadow", decision: "addressed"}] = runs()
      assert [_] = jobs("extract", w.og)

      Application.put_env(:risime, :risi_spoken_mention, :shadow)
      _ = spoken!(w, w.a.user, "en:Risi, remind me to call the bank.")
      assert requests(w.og) == []
      assert length(runs()) == 2 and Enum.all?(runs(), &(&1.mode == "shadow"))
    end

    test "off: no detection, no run row; addressed but unclear: Risi asks back (never silent)",
         w do
      Application.put_env(:risime, :risi_spoken_mention, false)
      _ = spoken!(w, w.a.user, "en:Risi, remind me to call the bank.")
      assert requests(w.og) == [] and runs() == []

      Application.put_env(:risime, :risi_spoken_mention, true)
      id = spoken!(w, w.a.user, "en:Risi, the venue is booked.")
      assert requests(w.og) == []
      assert [%{decision: "unsure_ask", reason: "vocative_no_request"}] = runs()

      {body, ask} = await_post(w.og, "answer", &(&1["voice_ref"]["message_id"] == id))
      assert body =~ "Did you want me to"
      assert ask["notify"] == [w.a.user.id] and ask["refs"] == [id]
      assert ask["request_id"] == SpokenMention.request_id(id)
      # Still an ordinary note for the chat (the Ledger sees it).
      assert [_] = jobs("extract", w.og)
    end

    test "the @Risi chip (request_id on the note): a request without the name, even with transcribe off",
         w do
      RisiMe.Messaging.subscribe(w.a.user.id)
      {:ok, _} = Voice.put_setting(w.a.user.id, %{"transcribe" => false, "action_items" => true})
      rid = Ecto.UUID.generate()

      {env, _, _} =
        note!(w.a.user.id, w.og, "si:හෙට මගේ meetings මොනවද", %{
          "request_id" => rid,
          "transcribe" => false
        })

      {id, s} = receive_note(w.og, w.a.user, env)

      assert_receive {:signal,
                      %{
                        kind: "risi_progress",
                        data: %{"request_id" => ^rid, "state" => "working"}
                      }},
                     500

      _ = await_post(w.og, "voice_transcript", &(&1["message_id"] == id))
      await_done(s)
      assert [job] = requests(w.og)
      assert job.args["request_id"] == rid and job.args["message_id"] == id

      assert %{"spoken_request" => %{"request_id" => ^rid, "text" => "හෙට මගේ meetings මොනවද"}} =
               Secretary.envelope(w.og, id)

      assert [%{decision: "chip", by: "chip"}] = runs()

      # A chip note Risi couldn't hear gets the "couldn't hear" answer, never silence.
      rid2 = Ecto.UUID.generate()
      {env, _, _} = note!(w.a.user.id, w.og, "silence", %{"request_id" => rid2})
      {id2, s} = receive_note(w.og, w.a.user, env)
      _ = await_post(w.og, "voice_transcript", &(&1["message_id"] == id2))
      {body, a} = await_post(w.og, "answer", &(&1["request_id"] == rid2))
      await_done(s)
      assert body =~ "couldn't hear"
      assert a["voice_ref"] == %{"message_id" => id2, "speaker" => w.a.user.id}

      # Kamal is not in the canary: his chip note is an ordinary note (and his opt-out holds).
      {env, _, _} =
        note!(w.b.user.id, w.og, "en:what is on tomorrow", %{
          "request_id" => Ecto.UUID.generate(),
          "transcribe" => false
        })

      assert {_, :skip} = receive_note(w.og, w.b.user, env)
    end

    test "never Private or a dm, and the Risi chat keeps §37.8 only (one request)", w do
      Application.put_env(:risime, :risi_spoken_mention, true)
      private = private_group!([w.a.user.id, w.b.user.id])
      dm = RisiMe.Messaging.conversation_id(w.a.user.id, w.b.user.id)
      t = %{status: "ok", text: "Risi, remind me to call the bank.", language: "en"}

      for conv <- [private, dm] do
        assert SpokenMention.check(conv, TimeUUID.generate(), w.a.user.id, t) == nil

        assert :ok =
                 Secretary.spoken_request(
                   conv,
                   TimeUUID.generate(),
                   w.a.user.id,
                   Ecto.UUID.generate()
                 )
      end

      rc = RisiMe.RisiHelpers.own_risi_chat!(w.a.user)
      intervals!(rc, [w.a.user.id])
      assert SpokenMention.check(rc, TimeUUID.generate(), w.a.user.id, t) == nil

      rid = Ecto.UUID.generate()

      {env, _, _} =
        note!(w.a.user.id, rc, "en:Risi, remind me to call the bank.", %{"request_id" => rid})

      {_, s} = receive_note(rc, w.a.user, env)
      _ = await_post(rc, "voice_transcript", fn _ -> true end)
      await_done(s)
      assert [%{args: %{"request_id" => ^rid}}] = requests(rc)
      assert runs() == []
    end
  end

  describe "incident 2026-10-14: Transcribing… [Stop] / Try again (RISI_VOICE_PROGRESS)" do
    setup do
      old = Application.get_env(:risime, :fake_speech_gate)
      on_exit(fn -> Application.put_env(:risime, :fake_speech_gate, old) end)
      :ok
    end

    defp signals(acc \\ []) do
      receive do
        {:signal, %{kind: "voice_transcribing", data: d}} -> signals([d | acc])
      after
        200 -> Enum.reverse(acc)
      end
    end

    test "flag on: working → Stop (nothing posted) → Try again → done", w do
      Application.put_env(:risime, :risi_agent_flags, %{"RISI_VOICE_PROGRESS" => "on"})
      RisiMe.Messaging.subscribe(w.a.user.id)
      Application.put_env(:risime, :fake_speech_gate, :closed)

      {env, _, _} = note!(w.a.user.id, w.og, "en:hello there")
      {id, {:started, pid}} = receive_note(w.og, w.a.user, env)
      Process.sleep(100)

      assert [%{"state" => "working", "seq" => 1, "can_cancel" => true, "message_id" => ^id}] =
               signals()

      # Only the sender may stop it.
      assert {:error, :not_running} = VoiceJobs.cancel(w.og, id, w.b.user.id)
      assert {:ok, true} = VoiceJobs.cancel(w.og, id, w.a.user.id)
      refute Process.alive?(pid)
      assert [%{"state" => "cancelled", "can_retry" => true, "seq" => 2}] = signals()
      assert {:error, :not_running} = VoiceJobs.cancel(w.og, id, w.a.user.id)

      Application.put_env(:risime, :fake_speech_gate, :open)
      assert {:error, :not_found} = VoiceJobs.retry(w.og, id, w.b.user.id)
      assert :ok = VoiceJobs.retry(w.og, id, w.a.user.id)
      {_body, risi} = await_post(w.og, "voice_transcript", fn _ -> true end)
      assert risi["message_id"] == id and risi["status"] == "ok"
      Process.sleep(100)
      states = Enum.map(signals(), & &1["state"])
      assert states == ["working", "done"]
      # Done: nothing is held any more.
      assert VoiceJobs.lookup(w.og, id) == nil
      assert {:error, :expired} = VoiceJobs.retry(w.og, id, w.a.user.id)
    end

    test "flag off (v1.47): no signal, Stop/Try again say no, nothing held", w do
      RisiMe.Messaging.subscribe(w.a.user.id)
      {env, _, _} = note!(w.a.user.id, w.og, "fail")
      {id, started} = receive_note(w.og, w.a.user, env)
      _ = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(started)
      assert signals() == []
      refute VoiceJobs.enabled_for?(w.a.user.id)
      assert VoiceJobs.lookup(w.og, id) == nil
    end

    test "the phone transcript is ignored when Risi's model answers, and while the flag is off",
         w do
      phone = %{"text" => "PHONE-TEXT", "lang" => "en-US", "engine" => "android-on-device"}

      # Risi's model answers: the phone's text is nowhere in the post.
      Application.put_env(:risime, :risi_agent_flags, %{"RISI_VOICE_PHONE_FALLBACK" => "on"})
      {env, _, _} = note!(w.a.user.id, w.og, "en:from the model", %{"phone_transcript" => phone})
      {_, s} = receive_note(w.og, w.a.user, env)
      {body, risi} = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(s)
      assert risi["text"] == "from the model" and not Map.has_key?(risi, "source")
      refute body =~ "PHONE-TEXT"

      # The flag off (v1.47): a failing service gives `failed`, never the phone's text.
      Application.put_env(:risime, :risi_agent_flags, %{"RISI_VOICE_PHONE_FALLBACK" => "off"})
      {env, _, _} = note!(w.a.user.id, w.og, "fail", %{"phone_transcript" => phone})
      {_, s} = receive_note(w.og, w.a.user, env)
      {body, risi} = await_post(w.og, "voice_transcript", fn r -> r["status"] == "failed" end)
      await_done(s)
      assert risi["text"] == nil
      refute body =~ "PHONE-TEXT"
    end

    test "flag on: a failing service falls back to the phone transcript (source phone)", w do
      Application.put_env(:risime, :risi_agent_flags, %{"RISI_VOICE_PHONE_FALLBACK" => "on"})
      phone = %{"text" => "from the phone", "lang" => "en-US", "engine" => "android-on-device"}
      {env, _, _} = note!(w.a.user.id, w.og, "fail", %{"phone_transcript" => phone})
      {_, s} = receive_note(w.og, w.a.user, env)
      {_body, risi} = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(s)

      assert %{
               "status" => "ok",
               "text" => "from the phone",
               "model" => "phone-on-device",
               "source" => "phone",
               "made_by" => %{"provider" => "phone"}
             } = risi
    end

    test "hallucination guard in Official: 'couldn't hear', no text, nothing acts on it", w do
      Application.put_env(:risime, :risi_ledger, true)
      Application.put_env(:risime, :risi_action_items, true)
      loop = "si:මම ඒක " <> String.duplicate("කරන්නේ ", 20)
      {env, _, _} = note!(w.a.user.id, w.og, loop)
      {id, s} = receive_note(w.og, w.a.user, env)
      {body, risi} = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(s)
      assert risi["status"] == "unclear" and risi["text"] == nil
      assert body =~ "couldn't hear"
      refute body =~ "කරන්නේ"
      # No §38 extraction job and no typed-message understanding of it.
      assert all_enqueued(worker: Job, args: %{"kind" => "voice_items", "message_id" => id}) == []
      assert all_enqueued(worker: Job, args: %{"kind" => "action", "message_id" => id}) == []
    end

    test "flag on: a failed note can be tried again", w do
      Application.put_env(:risime, :risi_agent_flags, %{"RISI_VOICE_PROGRESS" => "on"})
      RisiMe.Messaging.subscribe(w.a.user.id)
      {env, _, _} = note!(w.a.user.id, w.og, "fail")
      {id, started} = receive_note(w.og, w.a.user, env)
      {_, risi} = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(started)
      assert risi["status"] == "failed"

      assert [%{"state" => "working"}, %{"state" => "failed", "can_retry" => true}] = signals()
      assert %{state: :failed} = VoiceJobs.lookup(w.og, id)
    end
  end

  describe "Harsha's Sinhala compare mode (RISI_SPEECH_COMPARE_DEV, dev users' own notes)" do
    defmodule CompareFake do
      @moduledoc "Candidates for the compare mode: fixed texts per model."
      def transcribe_model(_audio, "qwen3-asr-0.6b-sinhala", _opts),
        do: {:ok, %{text: "මම ඒක " <> String.duplicate("කරන්නේ ", 20), model: "q"}}

      def transcribe_model(_audio, "whisper-small-sinhala", _opts),
        do: {:ok, %{text: "ගෙදර යන්න බස් එකක් නැහැ", model: "w"}}

      def transcribe_model(_audio, "omnilingual-ctc-1b", _opts),
        do: {:ok, %{text: "හෙට උදේ දහයට හමුවෙමු", model: "c"}}

      def extra_transcribe(_audio, _model, _opts), do: {:error, :off}
    end

    setup w do
      dir = Path.join(System.tmp_dir!(), "asr-gold-#{System.unique_integer([:positive])}")

      prev =
        for k <- [:risi_asr_gold_dir, :risi_speech_compare_impl],
            do: {k, Application.get_env(:risime, k)}

      on_exit(fn ->
        for {k, v} <- prev, do: Application.put_env(:risime, k, v)
        File.rm_rf(dir)
      end)

      Application.put_env(:risime, :risi_asr_gold_dir, dir)
      Application.put_env(:risime, :risi_speech_compare_impl, CompareFake)
      Application.put_env(:risime, :risi_dev_users, [String.downcase(w.a.user.id)])
      rc = RisiMe.RisiHelpers.own_risi_chat!(w.a.user)
      intervals!(rc, [w.a.user.id])
      %{rc: rc, dir: dir}
    end

    test "his Sinhala note: the chat gets the guarded transcript, his Risi chat the compare; a reply labels it",
         w do
      text = "හෙට උදේ දහයට හමුවෙමු"
      phone = %{"text" => text, "lang" => "si-LK", "engine" => "android-on-device"}
      {env, _, _} = note!(w.a.user.id, w.og, "si:" <> text, %{"phone_transcript" => phone})
      {id, s} = receive_note(w.og, w.a.user, env)
      {_, risi} = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(s)
      assert risi["status"] == "ok" and risi["text"] == text

      {body, _} = await_post(w.rc, "answer", fn _ -> true end)
      assert body =~ "Sinhala compare for your voice note"
      assert body =~ "1. Qwen3-ASR Sinhala" and body =~ "guard: repeat_token"
      assert body =~ "2. Whisper small Sinhala"
      assert body =~ "3. omniASR-CTC-1B"
      assert body =~ "4. omniASR-LLM-7B: skipped"
      assert body =~ "5. Phone (on-device)"
      # Never in the Official chat.
      refute_post(w.og, "answer")

      # Sealed on disk, with the training marker, mode 600.
      assert File.exists?(Path.join(w.dir, "NO_TRAINING"))
      assert {:ok, %{mode: mode}} = File.stat(Path.join(w.dir, "#{id}.audio.sealed"))
      assert Bitwise.band(mode, 0o777) == 0o600
      refute File.read!(Path.join(w.dir, "#{id}.json.sealed")) =~ "හෙට"

      # His reply "2" labels it; the scores and the route decision follow.
      assert RisiMe.Agent.SpeechCompare.label_reply(w.rc, w.a.user.id, " 3 ") == :handled
      {saved, _} = await_post(w.rc, "answer", &(&1["answer"] =~ "Saved"))
      assert saved =~ "omnilingual-ctc-1b CER 0%"
      [gold] = RisiMe.Agent.SpeechGold.labelled()
      assert gold["label"] == text and gold["label_source"] == "pick:3"
      assert gold["scores"]["omnilingual-ctc-1b"]["cer"] == 0.0
      assert gold["scores"]["qwen3-asr-0.6b-sinhala"]["cer"] > 0.5
      # First note: CTC clearly less wrong → his per-user Sinhala route.
      assert RisiMe.Agent.SpeechRoutes.user_choice(w.a.user.id, "si") == "omnilingual-ctc-1b"
      assert RisiMe.Agent.SpeechRoutes.user_choice(w.b.user.id, "si") == "auto"

      # Nothing left to label: a plain "1" is a normal request again.
      assert RisiMe.Agent.SpeechCompare.label_reply(w.rc, w.a.user.id, "1") == :pass

      # Deleting the note deletes its gold files.
      Voice.notes_deleted(w.og, [id])
      refute File.exists?(Path.join(w.dir, "#{id}.audio.sealed"))
    end

    test "his opt-in Google transcript: 'Google' in the compare, and shown when ours fails the guard",
         w do
      google = %{
        "text" => "හෙට උදේ දහයට හමුවෙමු",
        "lang" => "si-LK",
        "engine" => "android-online-google"
      }

      loop = "si:මම ඒක " <> String.duplicate("කරන්නේ ", 20)
      {env, _, _} = note!(w.a.user.id, w.og, loop, %{"phone_transcript" => google})
      {_, s} = receive_note(w.og, w.a.user, env)
      {_, risi} = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(s)

      assert %{
               "status" => "ok",
               "text" => "හෙට උදේ දහයට හමුවෙමු",
               "source" => "phone",
               "engine" => "android-online-google",
               "model" => "phone-google"
             } = risi

      {body, _} = await_post(w.rc, "answer", fn _ -> true end)
      assert body =~ "5. Google"
      # Shadow: scored against Google at once (no label yet); the console's scoreboard shows it.
      sb = RisiMe.Agent.SpeechCompare.scoreboard()
      assert sb.n == 1 and sb.labelled == 0
      assert Enum.any?(sb.models, &(&1.model == "omnilingual-ctc-1b" and &1.mean_cer == 0.0))

      # His off switch: no more compare posts, the scoring goes on quietly.
      assert RisiMe.Agent.SpeechCompare.label_reply(w.rc, w.a.user.id, "compare off") == :handled
      {off, _} = await_post(w.rc, "answer", &(&1["answer"] =~ "Compare messages are off"))
      assert off =~ "scores the models quietly"
      refute RisiMe.Agent.SpeechCompare.post_on?(w.a.user.id)
      {env, _, _} = note!(w.a.user.id, w.og, "si:හෙට", %{"phone_transcript" => google})
      {_, s} = receive_note(w.og, w.a.user, env)
      _ = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(s)
      Process.sleep(300)
      refute_post(w.rc, "answer")
      assert RisiMe.Agent.SpeechCompare.scoreboard().n == 2
    end

    test "not a dev user, or the flag off: no compare", w do
      Application.put_env(:risime, :risi_dev_users, [])
      {env, _, _} = note!(w.a.user.id, w.og, "si:හෙට")
      {_, s} = receive_note(w.og, w.a.user, env)
      _ = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(s)
      Process.sleep(200)
      refute_post(w.rc, "answer")
      assert File.ls(w.dir) in [{:error, :enoent}, {:ok, []}]
    end

    test "parse, cer/wer and the decision rule" do
      alias RisiMe.Agent.SpeechCompare, as: C
      assert C.parse("3") == {:ok, :pick, 3}
      assert C.parse("fix: හෙට එනවා") == {:ok, :typed, "හෙට එනවා"}
      assert C.parse("what's on tomorrow?") == :no
      assert C.cer("abc", "abd") == 0.333
      assert C.wer("a b c", "a x c") == 0.333

      assert {"omnilingual-ctc-1b", _} =
               C.decide(1, %{
                 "omnilingual-ctc-1b" => {0.2, 1},
                 "qwen3-asr-0.6b-sinhala" => {0.9, 1}
               })

      assert {"none", _} =
               C.decide(2, %{
                 "omnilingual-ctc-1b" => {0.7, 2},
                 "qwen3-asr-0.6b-sinhala" => {0.9, 2}
               })

      assert {"auto", _} =
               C.decide(2, %{
                 "omnilingual-ctc-1b" => {0.3, 2},
                 "qwen3-asr-0.6b-sinhala" => {0.35, 2}
               })

      assert {"qwen3-asr-0.6b-sinhala", _} =
               C.decide(5, %{
                 "omnilingual-ctc-1b" => {0.3, 5},
                 "qwen3-asr-0.6b-sinhala" => {0.2, 5}
               })

      assert {"none", _} = C.decide(6, %{"omnilingual-ctc-1b" => {0.7, 5}})
    end
  end

  describe "PART 9: canary switches (RISI_VOICE_TRANSCRIBE, RISI_ACTION_ITEMS)" do
    test "canary transcribes only RISI_DEV_USERS' notes; /auth/config says off", w do
      Application.put_env(:risime, :risi_test_pid, self())
      Application.put_env(:risime, :risi_voice_transcribe, :canary)
      Application.put_env(:risime, :risi_dev_users, [String.downcase(w.a.user.id)])

      assert Voice.mode() == :canary
      refute Voice.enabled?()
      assert Voice.enabled_for?(w.a.user.id) and not Voice.enabled_for?(w.b.user.id)

      {env, _, _} = note!(w.b.user.id, w.og, "en:from kamal")
      assert {_, :skip} = receive_note(w.og, w.b.user, env)
      {env, _, _} = note!(w.a.user.id, w.og, "en:from harsha")
      assert {_, {:started, _} = s} = receive_note(w.og, w.a.user, env)
      _ = await_post(w.og, "voice_transcript", fn _ -> true end)
      await_done(s)
      assert_received {:fake_speech, :transcribe, "en:from harsha"}
      refute_received {:fake_speech, :transcribe, "en:from kamal"}

      refute Map.has_key?(
               build_conn() |> get("/api/v1/auth/config") |> json_response(200),
               "voice_transcribe"
             )

      # The Risi chat of a non-dev user gets the "can't listen" answer (never silent).
      rc = RisiMe.RisiHelpers.own_risi_chat!(w.b.user)
      intervals!(rc, [w.b.user.id])
      {env, _, _} = note!(w.b.user.id, rc, "en:hi", %{"request_id" => Ecto.UUID.generate()})
      assert {_, {:replied, _}} = receive_note(rc, w.b.user, env)
    end

    test "a runtime flag (table or forced) wins over the application env; values validated", w do
      on_exit(fn -> RisiMe.Agents.Flags.refresh() end)
      alias RisiMe.Agents.Flags

      Application.put_env(:risime, :risi_agent_flags, %{"RISI_VOICE_TRANSCRIBE" => "off"})
      assert Voice.mode() == :off
      {env, _, _} = note!(w.a.user.id, w.og, "en:hello")
      assert {_, :skip} = receive_note(w.og, w.a.user, env)
      Application.delete_env(:risime, :risi_agent_flags)

      assert :ok = Flags.put("RISI_VOICE_TRANSCRIBE", "canary")
      assert :ok = Flags.put("RISI_ACTION_ITEMS", "on")
      assert {:error, :invalid_flag} = Flags.put("RISI_ACTION_ITEMS", "shadow")
      assert Voice.mode() == :canary
      assert RisiMe.Agent.ActionItems.mode() == :on

      assert %{value: "canary", from: "table"} =
               Enum.find(Flags.all(), &(&1.name == "RISI_VOICE_TRANSCRIBE"))

      :ok = Flags.clear("RISI_VOICE_TRANSCRIBE")
      :ok = Flags.clear("RISI_ACTION_ITEMS")
      # Back to the application env (`true` here; the default is off).
      assert Voice.mode() == :on
      assert RisiMe.Agent.ActionItems.mode() == :off
    end

    test "RISI_ACTION_ITEMS=canary: a dev user's voice notes only", w do
      Application.put_env(:risime, :risi_ledger, true)
      Application.put_env(:risime, :risi_action_items, "canary")
      Application.put_env(:risime, :risi_dev_users, [String.downcase(w.a.user.id)])
      alias RisiMe.Agent.ActionItems

      refute ActionItems.on?() or ActionItems.config_on?()
      assert ActionItems.on_for?([w.b.user.id, w.a.user.id])
      refute ActionItems.on_for?([w.b.user.id])

      a = TimeUUID.generate()
      b = TimeUUID.generate()
      :ok = ActionItems.voice_transcribed(w.og, a, w.a.user.id, "ok", 12_400)
      :ok = ActionItems.voice_transcribed(w.og, b, w.b.user.id, "ok", 12_400)

      assert [%{args: %{"message_id" => ^a}}] =
               all_enqueued(worker: Job, args: %{"kind" => "voice_actions"})
    end
  end

  ## Helpers

  # The §14.2 media read rule: membership intervals (Risi's too), as the real join writes them.
  defmodule HintSpeech do
    @moduledoc false
    # `RisiMe.FakeSpeech` that also reports the options of each transcribe.
    @behaviour RisiMe.Agent.Speech

    @impl true
    def healthy?, do: RisiMe.FakeSpeech.healthy?()

    @impl true
    def transcribe(audio, opts \\ []) do
      if pid = Application.get_env(:risime, :risi_test_pid), do: send(pid, {:speech_opts, opts})
      RisiMe.FakeSpeech.transcribe(audio, opts)
    end

    @impl true
    def compare(audio, opts \\ []), do: RisiMe.FakeSpeech.compare(audio, opts)
  end

  # A remembered human message (as `Memory.put_message/2` stores it).
  defp remember!(conv, sender, text) do
    id = Ecto.UUID.generate()
    {:ok, sealed} = RisiMe.Agent.Seal.seal_text("risi_memory_messages", id, "body", text)
    now = RisiMe.Agent.Clock.usec(DateTime.utc_now())

    {1, _} =
      RisiMe.Repo.insert_all(RisiMe.Agent.Memory.Message, [
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

  defp intervals!(conv, users) do
    at = DateTime.add(DateTime.utc_now(), -60, :second)
    :ok = RisiMe.Groups.Membership.open(conv, [RisiMe.Risi.user_id() | users], at)
  end

  defp int(v) when is_integer(v), do: v
  defp int(v) when is_binary(v), do: String.to_integer(v)

  defp blob_files do
    dir = Blobs.blob_dir()

    if File.dir?(dir),
      do:
        Path.wildcard(Path.join(dir, "**/*"), match_dot: true)
        |> Enum.filter(&File.regular?/1)
        |> Enum.sort(),
      else: []
  end

  defp mapping(conv) do
    RisiMe.Repo.all(
      from v in "risi_voice_transcripts",
        where: v.conversation_id == ^conv,
        select: %{note: v.note_message_id, transcript_message_id: v.transcript_message_id}
    )
  end

  # A `risi_action` from `user`, buffered and run as `Agent.Conversation` and the job would.
  defp act!(conv, user, action) do
    id = TimeUUID.generate()

    m = %{
      message_id: id,
      sender_id: user.id,
      sender_device: nil,
      plaintext: Jason.encode!(action)
    }

    :ok = Transcript.put(conv, m)
    Secretary.on_message(conv, m)
    [job] = all_enqueued(worker: Job, args: %{"kind" => "action", "message_id" => id})
    perform_job(Job, job.args)
  end

  defp private_group!(members) do
    id = "grp:" <> Ecto.UUID.generate()
    now = DateTime.utc_now()

    RisiMe.Repo.insert!(%RisiMe.Groups.Group{
      id: id,
      created_by: hd(members),
      client_group_id: Ecto.UUID.generate(),
      state: "active",
      generation: 1,
      created_at: now,
      tab: "private",
      chat_kind: "group"
    })

    id
  end
end
