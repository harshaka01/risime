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
  import RisiMe.CallTranscribeHelpers, only: [await_post: 3, refute_post: 3, eventually: 1]

  alias RisiMe.Agent.{Media, Secretary, Transcript, Voice}
  alias RisiMe.{Blobs, TimeUUID}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  @examples Path.expand("../../../../contract/v1/examples", __DIR__)
  @vectors Path.expand("../../../../contract/v1/media_vectors.json", __DIR__)

  defp example(name), do: @examples |> Path.join(name) |> File.read!() |> Jason.decode!()

  setup do
    keys = [
      :risi_voice_transcribe,
      :risi_speech,
      :fake_speech_healthy,
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
      # (The temp dir is shared with other processes: whatever appeared there holds no audio.)
      for f <- MapSet.difference(File.ls!(System.tmp_dir!()) |> MapSet.new(), before_tmp),
          path = Path.join(System.tmp_dir!(), f),
          File.regular?(path),
          do: refute(File.read!(path) =~ audio)

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
      # Never `text` (nothing else counts it), never a request.
      assert Secretary.text_messages(w.og) == []

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

    test "unclear: the transcript card says so and Risi answers the fixed sentence; no ask", w do
      rid = Ecto.UUID.generate()
      {env, _, _} = note!(w.a.user.id, w.rc, "silence", %{"request_id" => rid})
      {_, s} = receive_note(w.rc, w.a.user, env)
      {_, card} = await_post(w.rc, "voice_transcript", fn _ -> true end)
      assert card["status"] == "unclear"
      {body, answer} = await_post(w.rc, "answer", fn _ -> true end)
      await_done(s)
      assert body == "I couldn't hear that clearly. Please try again or type it."
      assert answer["request_id"] == rid and answer["notify"] == [w.a.user.id]
      assert all_enqueued(worker: Job, args: %{"kind" => "request"}) == []
    end

    # PART 4d: a voice note in the Risi chat is a turn like a typed message.
    defp final(answer),
      do: %{"tool" => "final", "answer" => answer, "sources" => [], "next_steps" => []}

    defp voice_ask!(w, audio) do
      rid = Ecto.UUID.generate()
      {env, _, _} = note!(w.a.user.id, w.rc, audio, %{"request_id" => rid})
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
             "I can't listen to voice notes right now. Please type it."},
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

        assert_receive {:signal,
                        %{kind: "risi_progress", data: %{"request_id" => ^rid, "state" => "done"}}}

        refute_received {:fake_speech, _, _}
        assert all_enqueued(worker: Job, args: %{"kind" => "request", "request_id" => rid}) == []
      end
    end

    test "PART 4d: a speech service that looks unhealthy is still tried; down = the failed card + answer",
         w do
      Application.put_env(:risime, :fake_speech_healthy, false)
      {_rid, _id, card, _args} = voice_ask!(w, "en:still heard")
      assert card["status"] == "ok" and card["text"] == "still heard"

      rid = Ecto.UUID.generate()
      {env, _, _} = note!(w.a.user.id, w.rc, "fail", %{"request_id" => rid})
      {_, s} = receive_note(w.rc, w.a.user, env)
      {_, card} = await_post(w.rc, "voice_transcript", fn r -> r["status"] == "failed" end)
      await_done(s)
      assert card["text"] == nil
      {body, _} = await_post(w.rc, "answer", &(&1["request_id"] == rid))
      assert body == "I couldn't hear that clearly. Please try again or type it."
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

  ## Helpers

  # The §14.2 media read rule: membership intervals (Risi's too), as the real join writes them.
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
