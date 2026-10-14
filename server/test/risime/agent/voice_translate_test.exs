defmodule RisiMe.Agent.VoiceTranslateTest do
  @moduledoc """
  Proposal 2026-10-14-voice-translate (v1.50 §37.13, flag `RISI_VOICE_TRANSLATE`): "Wrong
  language?" (`risi_action` `transcript_language`), per-viewer Translate (`POST
  /api/v1/risi/translate`), the `always_translate` setting, and the English rendering for §38.
  Every example from the proposal is decoded and checked here. All texts are synthetic.
  """
  use RisiMeWeb.ConnCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import Ecto.Query, only: [from: 2]
  import RisiMe.CallTranscribeHelpers, only: [await_post: 3, refute_post: 3]

  alias RisiMe.Agent.{
    ActionItems,
    Media,
    Secretary,
    SpeechLang,
    Transcript,
    Voice,
    VoiceTranslate
  }

  alias RisiMe.{Blobs, TimeUUID}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  @examples Path.expand("../../../../contract/v1/examples", __DIR__)

  @ta_in_si "නාන් නාළෛක්කු කාලෛයිල වාරන්, අන්ද රිපෝට්ටෛ මුඩිච්චු අනුප්පුරන්"
  @ta_text "நான் நாளைக்கு காலையில வாறன், அந்த ரிப்போர்ட்ட முடிச்சு அனுப்புறன்"
  @en_text "I'll come tomorrow morning, finish that report and send it."

  # The proposal's examples (to be added to contract/v1/examples by root).
  @ex_action ~s|{"v":1,"type":"risi_action","action":"transcript_language","target":"d1e2f3a4-a6f2-11f1-8000-0242ac120002","edit":null,"language":"ta","voice":{"blob":{"blob_id":"5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8c","size":49168,"sha256":"q7FnzD0yS2bX5uJ8Kc3mT9vL1aP4eR6wY0hN2iO8gUs="},"enc":{"alg":"A256GCM-S64K","key":"Hc2Tj9wQ1mZ8rV4yK7pL3sX6aN0bF5gD2eU9iO1tR8c=","plain_size":49152},"duration_ms":12400}}|
  @ex_relang ~s|{"v":1,"type":"text","body":"Voice note from Kumar (0:12): நான் நாளைக்கு காலையில வாறன், அந்த ரிப்போர்ட்ட முடிச்சு அனுப்புறன்","risi":{"v":1,"kind":"voice_transcript","conversation_id":"grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b","message_id":"d1e2f3a4-a6f2-11f1-8000-0242ac120002","speaker":"7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3","duration_ms":12400,"status":"ok","text":"நான் நாளைக்கு காலையில வாறன், அந்த ரிப்போர்ட்ட முடிச்சு அனுப்புறன்","language":"ta","model":"tamil-asr-qwen3","relang":{"from":"si","to":"ta","by":"0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"},"call_ref":null,"made_by":{"model":"tamil-asr-qwen3","provider":"risime","at":"2026-10-14T04:12:00.000Z","also":[]},"notify":[]}}|
  @ex_request ~s|{"conversation_id":"grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b","message_id":"d1e2f3a4-a6f2-11f1-8000-0242ac120002","target":"en"}|
  @ex_reply ~s|{"conversation_id":"grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b","message_id":"d1e2f3a4-a6f2-11f1-8000-0242ac120002","target":"en","source_language":"ta","text":"I'll come tomorrow morning, finish that report and send it.","label":"Translated by Risi","made_by":{"model":"risi-l1","provider":"risime","at":"2026-10-14T04:12:30.000Z","also":[]}}|
  @ex_put ~s|{"transcribe":true,"action_items":true,"always_translate":"en"}|
  @ex_voice_reply ~s|{"transcribe":true,"action_items":true,"always_translate":"en","translate_available":true}|

  defmodule RelangSpeech do
    @moduledoc "FakeSpeech, except that a forced `ta` answers the Tamil text."
    @behaviour RisiMe.Agent.Speech

    @impl true
    def healthy?, do: true
    @impl true
    def compare(a, o), do: RisiMe.FakeSpeech.compare(a, o)

    @impl true
    def transcribe(audio, opts) do
      send(Application.get_env(:risime, :risi_test_pid), {:relang_speech, opts[:language]})

      case opts[:language] do
        "ta" ->
          {:ok,
           %{
             text: Application.get_env(:risime, :relang_text),
             language: "ta",
             model: "tamil-asr-qwen3",
             elapsed_ms: 50,
             speech: true,
             romanised: false,
             language_probability: nil,
             script: nil,
             segments: []
           }}

        _ ->
          RisiMe.FakeSpeech.transcribe(audio, opts)
      end
    end
  end

  setup do
    keys = [
      :risi_voice_transcribe,
      :risi_agent_flags,
      :risi_dev_users,
      :risi_speech,
      :risi_test_pid,
      :relang_text
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
    Application.put_env(:risime, :risi_speech, RelangSpeech)
    Application.put_env(:risime, :relang_text, @ta_text)
    Application.put_env(:risime, :risi_agent_flags, %{"RISI_VOICE_TRANSLATE" => "on"})
    a = RisiMe.Fixtures.logged_in_user(display_name: "Kumar")
    b = RisiMe.Fixtures.logged_in_user(display_name: "Kamal")
    og = RisiMe.RisiHelpers.risi_chat!([a.user, b.user])
    at = DateTime.add(DateTime.utc_now(), -60, :second)
    :ok = RisiMe.Groups.Membership.open(og, [RisiMe.Risi.user_id(), a.user.id, b.user.id], at)
    %{a: a, b: b, og: og}
  end

  ## Helpers (as RisiMe.Agent.VoiceTest)

  defp example(name), do: @examples |> Path.join(name) |> File.read!() |> Jason.decode!()

  defp encrypt(plain) do
    key = :crypto.strong_rand_bytes(32)
    kp = Media.payload_key(key)
    padded = plain <> :binary.copy(<<0>>, Media.padme(byte_size(plain)) - byte_size(plain))
    nonce = <<0::56, 0::32, 1::8>>

    {ct, tag} =
      :crypto.crypto_one_time_aead(:aes_256_gcm, kp, nonce, padded, "risime-media-v1", true)

    {ct <> tag, key}
  end

  defp note!(owner, conv, audio) do
    {cipher, key} = encrypt(audio)
    {tmp, io} = Blobs.open_tmp()
    :ok = IO.binwrite(io, cipher)
    :ok = :file.close(io)
    sha = :crypto.hash(:sha256, cipher)
    up = %{purpose: "media", conv: conv, client_blob_id: nil}
    {:created, reply} = Blobs.finish(owner, up, tmp, byte_size(cipher), sha)

    example("voice_payload.json")
    |> Map.put("blob", %{
      "blob_id" => reply.blob_id,
      "size" => byte_size(cipher),
      "sha256" => Base.encode64(sha)
    })
    |> Map.put("enc", %{
      "alg" => "A256GCM-S64K",
      "key" => Base.encode64(key),
      "plain_size" => byte_size(audio)
    })
  end

  defp transcribed!(w, audio) do
    env = note!(w.a.user.id, w.og, audio)
    id = TimeUUID.generate()
    {:started, pid} = Voice.received(w.og, %{message_id: id, sender_id: w.a.user.id}, env)
    {_, risi} = await_post(w.og, "voice_transcript", &(&1["message_id"] == id))
    ref = Process.monitor(pid)
    assert_receive {:DOWN, ^ref, :process, ^pid, _}, 5_000
    {id, env, risi}
  end

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

  defp relang_action(note_id, env, lang) do
    %{
      "v" => 1,
      "type" => "risi_action",
      "action" => "transcript_language",
      "target" => note_id,
      "edit" => nil,
      "language" => lang,
      "voice" => Map.take(env, ["blob", "enc", "duration_ms"])
    }
  end

  defp mapping(conv) do
    RisiMe.Repo.all(
      from v in "risi_voice_transcripts",
        where: v.conversation_id == ^conv,
        select: %{note: v.note_message_id, transcript: v.transcript_message_id}
    )
  end

  defp translate_conn(user, body) do
    build_conn()
    |> put_req_header("authorization", "Bearer " <> user.token)
    |> put_req_header("content-type", "application/json")
    |> post("/api/v1/risi/translate", Jason.encode!(body))
  end

  defp llm_translates!(text) do
    RisiMe.RisiHelpers.fake_llm!(fn
      "translate", _ -> %{"text" => text}
      _, _ -> {:status, 500}
    end)
  end

  ## Examples

  describe "the proposal's examples" do
    test "every example decodes; the action's voice passes the §37.2 checks" do
      for j <- [@ex_action, @ex_relang, @ex_request, @ex_reply, @ex_put, @ex_voice_reply],
          do: assert({:ok, %{}} = Jason.decode(j))

      action = Jason.decode!(@ex_action)
      voice = Map.merge(example("voice_payload.json"), action["voice"])
      assert {:ok, _} = Voice.validate(voice)

      relang = Jason.decode!(@ex_relang)["risi"]
      ex = example("envelope_risi_voice_transcript.json")["risi"]
      # The relang transcript is the §37.6 shape plus `relang` (no script: Tamil).
      assert Map.keys(relang) -- Map.keys(ex) == ["relang"]
      assert Map.keys(relang["relang"]) |> Enum.sort() == ~w(by from to)
    end
  end

  ## "Wrong language?" (§37.13.1)

  describe "transcript_language" do
    test "a receiver's tap: forced ta, a new post with relang, the old one deleted, the profile moved",
         w do
      {id, env, first} = transcribed!(w, "si:" <> @ta_in_si)
      assert first["language"] == "si"
      [%{transcript: old}] = mapping(w.og)

      act!(w.og, w.b.user, relang_action(id, env, "ta"))
      assert_received {:relang_speech, "ta"}
      {body, risi} = await_post(w.og, "voice_transcript", &(&1["message_id"] == id))
      assert risi["text"] == @ta_text
      assert risi["language"] == "ta"
      assert risi["relang"] == %{"from" => "si", "to" => "ta", "by" => w.b.user.id}
      assert body =~ @ta_text
      assert_received {:risi_delete, og, [^old]}
      assert og == w.og
      assert [%{transcript: new}] = mapping(w.og)
      assert new != old

      p = SpeechLang.profile(w.a.user.id)
      assert p.corrections["ta"] == 1
      assert p.ms["ta"] == 12_400

      # The working copy is the new transcript.
      assert %{"text" => @ta_text, "language" => "ta"} = Secretary.envelope(w.og, id)
    end

    test "refused: another note's blob, the flag off, Private, a 4th request in 10 min", w do
      {id, env, _} = transcribed!(w, "si:" <> @ta_in_si)
      {_id2, env2, _} = transcribed!(w, "si:මම හෙට එනවා")

      # A different note's audio is never accepted for this note.
      act!(w.og, w.b.user, relang_action(id, env2, "ta"))
      refute_received {:relang_speech, "ta"}

      Application.put_env(:risime, :risi_agent_flags, %{"RISI_VOICE_TRANSLATE" => "off"})
      act!(w.og, w.b.user, relang_action(id, env, "ta"))
      refute_received {:relang_speech, "ta"}

      Application.put_env(:risime, :risi_agent_flags, %{"RISI_VOICE_TRANSLATE" => "on"})
      for _ <- 1..3, do: act!(w.og, w.b.user, relang_action(id, env, "ta"))
      for _ <- 1..3, do: assert_received({:relang_speech, "ta"})
      act!(w.og, w.b.user, relang_action(id, env, "ta"))
      refute_received {:relang_speech, "ta"}

      # A bad language is ignored.
      assert :ok = Voice.transcript_language(w.og, w.b.user.id, relang_action(id, env, "fr"))
      # Private: never.
      assert :ok = Voice.transcript_language("dm:x_y", w.b.user.id, relang_action(id, env, "ta"))
      refute_received {:relang_speech, "ta"}
    end

    test "the learning-log row has the codes only", w do
      {id, env, _} = transcribed!(w, "si:" <> @ta_in_si)
      act!(w.og, w.b.user, relang_action(id, env, "ta"))
      _ = await_post(w.og, "voice_transcript", &(&1["message_id"] == id))

      month = Calendar.strftime(Date.utc_today(), "%Y-%m")

      rows =
        RisiMe.Agent.LearningLog.list_by_chat(Secretary.chat_id(w.og), month, 50)
        |> Enum.filter(&(&1[:task] == "transcribe"))

      relang =
        Enum.find(rows, fn r ->
          match?(%{"relang" => true}, decode(r[:meta]))
        end)

      assert relang
      assert %{"from" => "si", "to" => "ta"} = decode(relang[:meta])
      refute inspect(relang) =~ "நான்"
      assert relang[:output] in [nil, ""]
    end
  end

  defp decode(m) when is_binary(m), do: Jason.decode!(m)
  defp decode(m), do: m

  ## Translate (§37.13.2)

  describe "POST /risi/translate" do
    test "a held transcript, for the asking member only, labelled; nothing posted or logged", w do
      {id, _env, _} = transcribed!(w, "ta:" <> @ta_text)
      llm_translates!(@en_text)

      conn = translate_conn(w.b, %{conversation_id: w.og, message_id: id, target: "en"})
      reply = json_response(conn, 200)
      assert reply["text"] == @en_text
      assert reply["label"] == "Translated by Risi"
      assert reply["source_language"] == "ta"
      assert reply["target"] == "en"
      assert reply["made_by"]["model"] == "risi-l1"
      ex = Jason.decode!(@ex_reply)
      assert Enum.sort(Map.keys(reply)) == Enum.sort(Map.keys(ex))
      refute_post(w.og, "voice_transcript", 100)

      [req] = RisiMe.RisiHelpers.llm_requests()
      assert List.last(req["messages"])["content"] =~ @ta_text

      month = Calendar.strftime(Date.utc_today(), "%Y-%m")

      row =
        RisiMe.Agent.LearningLog.list_by_chat(Secretary.chat_id(w.og), month, 50)
        |> Enum.find(&(&1[:task] == "translate"))

      assert row
      refute inspect(row) =~ "tomorrow"
      refute inspect(row) =~ "நான்"
    end

    test "a typed Tamil message in Official", w do
      id = RisiMe.RisiHelpers.say!(w.og, w.a.user, @ta_text)
      llm_translates!(@en_text)
      conn = translate_conn(w.b, %{conversation_id: w.og, message_id: id, target: "en"})
      assert %{"text" => @en_text, "source_language" => "ta"} = json_response(conn, 200)
    end

    test "403 / 404 / 422 / 503", w do
      id = RisiMe.RisiHelpers.say!(w.og, w.a.user, @ta_text)

      assert %{"error" => "not_found"} =
               translate_conn(w.b, %{
                 conversation_id: w.og,
                 message_id: TimeUUID.generate(),
                 target: "en"
               })
               |> json_response(404)

      assert translate_conn(w.b, %{conversation_id: w.og, message_id: id, target: "fr"}).status ==
               422

      assert %{"error" => "not_available"} =
               translate_conn(w.b, %{conversation_id: "dm:a_b", message_id: id, target: "en"})
               |> json_response(403)

      outsider = RisiMe.Fixtures.logged_in_user(display_name: "Out")

      assert translate_conn(outsider, %{conversation_id: w.og, message_id: id, target: "en"}).status ==
               403

      RisiMe.RisiHelpers.fake_llm!(fn _, _ -> {:status, 500} end)

      assert %{"error" => "unavailable"} =
               translate_conn(w.b, %{conversation_id: w.og, message_id: id, target: "en"})
               |> json_response(503)

      Application.put_env(:risime, :risi_agent_flags, %{"RISI_VOICE_TRANSLATE" => "off"})

      assert translate_conn(w.b, %{conversation_id: w.og, message_id: id, target: "en"}).status ==
               403
    end
  end

  ## The setting (§37.13.2)

  describe "/me/risi/voice always_translate" do
    test "round trip while available; the v1.41 shape while not", w do
      get = fn ->
        build_conn()
        |> put_req_header("authorization", "Bearer " <> w.a.token)
        |> get("/api/v1/me/risi/voice")
        |> json_response(200)
      end

      assert get.() == %{
               "transcribe" => true,
               "action_items" => true,
               "always_translate" => "off",
               "translate_available" => true
             }

      reply =
        build_conn()
        |> put_req_header("authorization", "Bearer " <> w.a.token)
        |> put_req_header("content-type", "application/json")
        |> put("/api/v1/me/risi/voice", @ex_put)
        |> json_response(200)

      assert reply == Jason.decode!(@ex_voice_reply)
      assert VoiceTranslate.always_translate(w.a.user.id) == "en"

      # A PUT without the field keeps it.
      build_conn()
      |> put_req_header("authorization", "Bearer " <> w.a.token)
      |> put_req_header("content-type", "application/json")
      |> put("/api/v1/me/risi/voice", ~s({"transcribe":false,"action_items":true}))
      |> json_response(200)

      assert %{"always_translate" => "en", "transcribe" => false} = get.()

      Application.put_env(:risime, :risi_agent_flags, %{"RISI_VOICE_TRANSLATE" => "off"})
      assert get.() == %{"transcribe" => false, "action_items" => true}
    end
  end

  ## §37.13.4: the English rendering

  describe "the internal English rendering" do
    setup do
      # The note is understood in the transcription task: the fake model is shared.
      Req.Test.set_req_test_to_shared()
      on_exit(fn -> Req.Test.set_req_test_to_private() end)
    end

    test "a Tamil note's working copy holds it; §38 and the Ledger see original + English", w do
      llm_translates!(@en_text)
      {id, _env, _} = transcribed!(w, "ta:" <> @ta_text)
      env = Secretary.envelope(w.og, id)
      assert env["text_en"] == @en_text

      src = ActionItems.voice_source(w.og, id, env)
      [line] = ActionItems.voice_lines(src, env)
      assert line.text =~ @ta_text
      assert line.text =~ "[English: " <> @en_text <> "]"

      assert [%{text: text}] = Secretary.text_messages(w.og)
      assert text =~ @en_text

      # The posted transcript keeps the original only.
      refute_post(w.og, "voice_transcript", 50)
    end

    test "an English note gets none; the flag off: none", w do
      llm_translates!(@en_text)
      {id, _env, _} = transcribed!(w, "en:I will send it tomorrow")
      refute Map.has_key?(Secretary.envelope(w.og, id), "text_en")

      Application.put_env(:risime, :risi_agent_flags, %{"RISI_VOICE_TRANSLATE" => "off"})
      {id2, _env, _} = transcribed!(w, "ta:" <> @ta_text)
      refute Map.has_key?(Secretary.envelope(w.og, id2), "text_en")
    end
  end
end
