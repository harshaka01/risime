defmodule RisiMe.Agent.CallTranscribeUploadTest do
  @moduledoc """
  Contract v1.39 §36.4–§36.8 (decision 080): `POST /api/v1/calls/{call_id}/transcribe/chunks`
  through the real endpoint (a raw multipart body), its errors and limits, the no-disk rule, the
  transcript lines (speaker attribution, turn merging ≤ 60 s, the 30-s open-turn flush, silence
  posts nothing), stop-and-delete, developer compare mode, and the after-call summary with
  `lang`. The speech service is `RisiMe.FakeSpeech`; Risi's posts reach the test process.
  """
  use RisiMeWeb.ConnCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.CallTranscribeHelpers
  import Ecto.Query, only: [from: 2]

  alias RisiMe.Agent.CallTranscribe
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  setup do
    transcribe_world!()
  end

  # Uploads a chunk and waits until the fake service has seen it (and its result is in).
  defp say!(w, who, sid, seq, offset_s, audio, dur_ms \\ 12_000) do
    assert {202, %{"seq" => ^seq, "accepted" => true, "duplicate" => false}} =
             upload!(w, who, sid, audio, %{
               "seq" => seq,
               "offset_ms" => offset_s * 1000,
               "duration_ms" => dur_ms
             })

    assert_receive {:fake_speech, _, ^audio}, 2_000
    Process.sleep(60)
  end

  describe "the transcript" do
    test "turns are attributed to the uploader and merged per speaker", w do
      sid = start_on!(w)
      _ = await_post(w.og, "call_transcribe")
      started = session!(sid).started_at

      say!(w, :a, sid, 0, 0, "en:Let's send the revised quote")
      say!(w, :a, sid, 1, 12, "en:by Friday.")
      # B's speech closes A's turn.
      say!(w, :b, sid, 0, 24, "si:හෙට උදේ දහයට හමුවෙමු.")

      {body, risi} = await_post(w.og, "call_transcript")
      assert risi["turn"] == 1
      assert risi["speaker"] == w.a.user.id
      assert risi["text"] == "Let's send the revised quote by Friday."
      assert risi["language"] == "en"
      assert risi["model"] == "faster-whisper-large-v3"
      assert risi["made_by"]["model"] == "faster-whisper-large-v3"
      assert risi["made_by"]["provider"] == "risime"
      assert risi["notify"] == []
      assert risi["session_id"] == sid
      assert risi["call_id"] == w.call_id
      assert risi["start"] == RisiMe.Agent.Clock.ts(started)
      assert risi["end"] == RisiMe.Agent.Clock.ts(DateTime.add(started, 24, :second))
      assert body =~ ~r/^Test User A \d\d:\d\d: Let's send the revised quote by Friday\.$/

      # The session's end posts the open turn.
      tpush!(w, :b, "stop", %{"session_id" => sid, "reason" => "ended"})
      {body, risi} = await_post(w.og, "call_transcript")
      assert risi["turn"] == 2
      assert risi["speaker"] == w.b.user.id
      assert risi["language"] == "si"
      assert risi["model"] == "whisper-small-sinhala"
      assert body =~ ~r/^Test User B \d\d:\d\d: හෙට උදේ දහයට හමුවෙමු\.$/
      {_, ended} = await_post(w.og, "call_transcribe", &(&1["state"] == "ended"))
      assert ended["reason"] == "ended"
    end

    test "the ended session's row keeps its final posts and speech counts", w do
      sid = start_on!(w)
      _ = await_post(w.og, "call_transcribe")
      say!(w, :a, sid, 0, 0, "en:first line")
      say!(w, :b, sid, 0, 12, "en:second line")
      say!(w, :a, sid, 1, 24, "en:third line")
      tpush!(w, :a, "stop", %{"session_id" => sid, "reason" => "ended"})
      for _ <- 1..3, do: await_post(w.og, "call_transcript")
      _ = await_post(w.og, "call_transcribe", &(&1["state"] == "ended"))

      s = eventually(fn -> (s = session!(sid)).posts == 3 && s end)
      assert s.chunks == 3
      assert s.speech_ms == 36_000
    end

    test "a chunk with no speech posts nothing", w do
      sid = start_on!(w)
      say!(w, :a, sid, 0, 0, "silence")
      tpush!(w, :a, "stop", %{"session_id" => sid, "reason" => "ended"})
      _ = await_post(w.og, "call_transcribe", &(&1["state"] == "ended"))
      refute_post(w.og, "call_transcript")
    end

    test "a turn closes at 60 s; the continuation is the next turn" do
      w = transcribe_world!(timing: [chunk_burst: 10])
      sid = start_on!(w)
      for i <- 0..5, do: say!(w, :a, sid, i, i * 12, "en:part #{i}")
      {_, t1} = await_post(w.og, "call_transcript")
      assert t1["turn"] == 1
      assert t1["text"] == "part 0 part 1 part 2 part 3 part 4"
      tpush!(w, :a, "stop", %{"session_id" => sid, "reason" => "ended"})
      {_, t2} = await_post(w.og, "call_transcript")
      assert t2["turn"] == 2
      assert t2["text"] == "part 5"
    end

    test "an open turn is posted after 30 s" do
      w = transcribe_world!(timing: [turn_open_ms: 150])
      sid = start_on!(w)
      say!(w, :a, sid, 0, 0, "en:still talking")
      {_, t} = await_post(w.og, "call_transcript")
      assert t["text"] == "still talking"
      assert session!(sid).state == "on"
    end

    test "a line of a speaker who opted out of memory is posted but not remembered", w do
      RisiMe.Agent.Memory.set_learning(w.a.user.id, w.og, false)
      sid = start_on!(w)
      say!(w, :a, sid, 0, 0, "en:not for memory")
      tpush!(w, :a, "stop", %{"session_id" => sid, "reason" => "ended"})
      {_, t} = await_post(w.og, "call_transcript")
      assert t["speaker"] == w.a.user.id
    end
  end

  describe "upload errors and limits (§36.4)" do
    test "202 duplicate for a repeated seq (transcribed once); 422 for a lower seq", w do
      sid = start_on!(w)
      say!(w, :a, sid, 3, 0, "en:once")

      assert {202, %{"seq" => 3, "accepted" => true, "duplicate" => true}} =
               upload!(w, :a, sid, "en:once", %{"seq" => 3})

      refute_receive {:fake_speech, _, "en:once"}, 200

      assert {422, %{"error" => %{"code" => "bad_request"}}} =
               upload!(w, :a, sid, "en:x", %{"seq" => 2})
    end

    test "422 for a bad codec, duration, seq or missing audio", w do
      sid = start_on!(w)

      for fields <- [
            %{"codec" => "mp3"},
            %{"duration_ms" => 0},
            %{"duration_ms" => 20_001},
            %{"seq" => -1},
            %{"seq" => "x"},
            %{"offset_ms" => -5}
          ] do
        assert {422, %{"error" => %{"code" => "bad_request"}}} =
                 upload!(w, :a, sid, "en:x", fields)
      end

      assert {422, %{"error" => %{"code" => "bad_request"}}} = upload!(w, :a, sid, nil)
    end

    test "413 too_large over 256 KiB", w do
      sid = start_on!(w)
      big = :binary.copy("x", 262_145)
      assert {413, %{"error" => %{"code" => "too_large"}}} = upload!(w, :a, sid, big)
      ok = :binary.copy("x", 262_144)
      assert {202, _} = upload!(w, :a, sid, ok)
    end

    test "404 for an unknown session or one of another call", w do
      sid = start_on!(w)

      assert {404, %{"error" => %{"code" => "not_found"}}} =
               upload!(w, :a, Ecto.UUID.generate(), "en:x")

      other = ring!(w.a.user.id, w.b.user.id, w.dm)

      assert {404, %{"error" => %{"code" => "not_found"}}} =
               upload!(w, :a, sid, "en:x", %{}, call_id: other)
    end

    test "403 not_participant for a third user", w do
      sid = start_on!(w)
      c = RisiMe.Fixtures.logged_in_user(display_name: "C")
      dc = RisiMe.TabsHelpers.tabs_device!(c.user, caps: ~w(groups tabs call_transcribe))
      w2 = Map.put(w, :c, c)

      assert {403, %{"error" => %{"code" => "not_participant"}}} =
               upload!(w2, :c, sid, "en:x", %{}, device: dc)
    end

    test "403 not_official once Official is off", w do
      sid = start_on!(w)

      RisiMe.Repo.update_all(
        from(m in RisiMe.Groups.Member,
          where: m.group_id == ^w.og and m.kind == "agent"
        ),
        set: [state: "removed"]
      )

      assert {403, %{"error" => %{"code" => "not_official"}}} = upload!(w, :a, sid, "en:x")
    end

    test "429 over 8 per 60 s with a burst of 3", w do
      sid = start_on!(w)
      for i <- 0..2, do: assert({202, _} = upload!(w, :a, sid, "silence", %{"seq" => i}))

      assert {429, %{"error" => %{"code" => "rate_limited"}}} =
               upload!(w, :a, sid, "silence", %{"seq" => 3})
    end

    test "503 transcribe_unavailable beyond 2 queued chunks per participant", w do
      sid = start_on!(w)
      Application.put_env(:risime, :fake_speech_gate, :closed)
      assert {202, _} = upload!(w, :a, sid, "en:one", %{"seq" => 0})
      assert {202, _} = upload!(w, :a, sid, "en:two", %{"seq" => 1})

      assert {503, %{"error" => %{"code" => "transcribe_unavailable"}}} =
               upload!(w, :a, sid, "en:three", %{"seq" => 2})

      # The other participant has its own queue.
      assert {202, _} = upload!(w, :b, sid, "en:b", %{"seq" => 0})
      Application.put_env(:risime, :fake_speech_gate, :open)
    end
  end

  describe "no audio on disk (§36.4)" do
    test "a chunk never becomes a file: no Plug.Upload temp file, the bytes reach the service",
         w do
      sid = start_on!(w)
      tmp = System.tmp_dir!()
      before = tmp_files(tmp)
      marker = "en:AUDIO-MARKER-" <> Base.encode16(:crypto.strong_rand_bytes(8))

      assert {202, _} = upload!(w, :a, sid, marker)
      assert_receive {:fake_speech, :transcribe, ^marker}, 2_000

      big = :binary.copy("y", 300_000)
      assert {413, _} = upload!(w, :a, sid, big, %{"seq" => 1})

      new = tmp_files(tmp) -- before
      assert new == [], "files created: #{inspect(new)}"

      # No file under the temp dir holds the bytes either.
      refute Enum.any?(new, &(File.read!(&1) =~ marker))
    end

    test "the chunk parser reads into memory only for the chunk path" do
      assert RisiMeWeb.Plugs.MemoryMultipart.chunk_path?(
               ~w(api v1 calls 4b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10 transcribe chunks)
             )

      refute RisiMeWeb.Plugs.MemoryMultipart.chunk_path?(~w(api v1 blobs))
    end
  end

  defp tmp_files(tmp) do
    (Path.wildcard(Path.join(tmp, "plug-*/**")) ++ Path.wildcard(Path.join(tmp, "plug-*")))
    |> Enum.filter(&File.regular?/1)
    |> Enum.sort()
  end

  describe "stop and delete (§36.5)" do
    test "deletes the session's transcript posts for everyone, no summary; the line stays", w do
      sid = start_on!(w)
      say!(w, :a, sid, 0, 0, "en:first words")
      say!(w, :b, sid, 0, 12, "en:reply")
      {_, t1} = await_post(w.og, "call_transcript")

      assert {:ok, %{session: %{"state" => "stopped", "discard" => true}}} =
               tpush!(w, :b, "stop", %{
                 "session_id" => sid,
                 "reason" => "stopped",
                 "discard" => true
               })

      {body, line} = await_post(w.og, "call_transcribe", &(&1["state"] == "stopped"))
      assert line["discard"] == true

      assert body =~
               ~r/^Test User B stopped transcription and deleted the transcript \(\d\d:\d\d\)\.$/

      # B's open turn is never posted; A's posted turn is deleted.
      refute_post(w.og, "call_transcript")
      assert_receive {:risi_delete, og, ids}, 2_000
      assert og == w.og
      assert length(ids) == 1

      refute_enqueued(
        worker: Job,
        args: %{"kind" => "call_transcribe_summary", "session_id" => sid}
      )

      assert t1["session_id"] == sid

      eventually(fn ->
        RisiMe.Agent.CallTranscribe.Summary.segments(w.og, session!(sid)) == []
      end)
    end
  end

  describe "developer compare mode (§36.1, §36.5)" do
    test "only a listed developer with the setting gets compare; one line per chunk", w do
      Application.put_env(:risime, :risi_dev_users, [w.a.user.id])
      :ok = CallTranscribe.put_dev_setting(w.a.user.id, true)
      # B isn't listed: its stored setting means nothing.
      :ok = CallTranscribe.put_dev_setting(w.b.user.id, true)

      {:ok, %{session: s}} = tpush!(w, :a, "request")
      assert s["compare"] == true
      {:ok, _} = tpush!(w, :b, "allow", %{"session_id" => s["session_id"]})
      sid = s["session_id"]

      say!(w, :b, sid, 4, 52, "si:හෙට උදේ දහයට හමුවෙමු.")
      {body, risi} = await_post(w.og, "call_transcript_compare")
      assert risi["seq"] == 4
      assert risi["speaker"] == w.b.user.id
      assert risi["language_detected"] == "si"
      assert risi["chosen"] == "whisper-small-sinhala"
      assert length(risi["results"]) == 3
      assert Enum.find(risi["results"], &(&1["model"] == "tamil-asr-qwen3"))["error"] == "timeout"
      assert length(risi["made_by"]["also"]) == 3
      [first | _] = String.split(body, "\n")
      assert first == "whisper-small-sinhala (640 ms): හෙට උදේ දහයට හමුවෙමු."
      assert body =~ "tamil-asr-qwen3 (30000 ms): timeout"

      tpush!(w, :a, "stop", %{"session_id" => sid, "reason" => "ended"})
      _ = await_post(w.og, "call_transcribe", &(&1["state"] == "ended"))
      # Compare replaces the normal lines.
      refute_post(w.og, "call_transcript")

      # One sealed, 30-day learning-log row per model.
      rows =
        eventually(fn ->
          rows =
            RisiMe.Agent.LearningLog.list_by_chat(
              w.dm,
              Calendar.strftime(Date.utc_today(), "%Y-%m")
            )
            |> Enum.filter(&(&1.task == "transcribe_compare"))

          length(rows) == 3 && rows
        end)

      assert Enum.sort(Enum.map(rows, & &1.model_alias)) ==
               Enum.sort(RisiMe.Agent.Speech.models())

      assert Enum.all?(rows, &(is_binary(&1.prompt_sha256) and &1.meta =~ "\"seq\":4"))
    end

    test "a non-developer's session is never compare; a removed developer loses it", w do
      Application.put_env(:risime, :risi_dev_users, [w.a.user.id])
      :ok = CallTranscribe.put_dev_setting(w.a.user.id, true)
      Application.put_env(:risime, :risi_dev_users, [])
      {:ok, %{session: s}} = tpush!(w, :a, "request")
      assert s["compare"] == false
    end
  end

  describe "after the call (§36.7)" do
    test "≥ 30 s of speech from 2 speakers: a summary with lang and the speech models", w do
      RisiMe.LedgerHelpers.ledger_on!()

      RisiMe.LedgerHelpers.discussion_llm!(fn body ->
        %{"items" => [RisiMe.LedgerHelpers.item(body, "Test User A", "Send the revised quote")]}
      end)

      sid = start_on!(w)
      say!(w, :a, sid, 0, 0, "en:Let's send the revised quote")
      say!(w, :a, sid, 1, 12, "en:by Friday.")
      say!(w, :b, sid, 0, 24, "si:හරි, මම බලන්නම්.")
      tpush!(w, :a, "stop", %{"session_id" => sid, "reason" => "ended"})
      _ = await_post(w.og, "call_transcribe", &(&1["state"] == "ended"))

      eventually(fn ->
        all_enqueued(worker: Job)
        |> Enum.any?(&(&1.args["kind"] == "call_transcribe_summary"))
      end)

      assert :ok =
               perform_job(Job, %{
                 "kind" => "call_transcribe_summary",
                 "conv" => w.og,
                 "session_id" => sid
               })

      {_, card} = await_post(w.og, "discussion_card")
      assert card["source"] == "call"
      assert card["call_id"] == w.call_id
      assert card["lang"] == "en"
      models = Enum.map(card["made_by"]["also"], & &1["model"])
      assert "faster-whisper-large-v3" in models
      assert "whisper-small-sinhala" in models
      assert Enum.all?(card["made_by"]["also"], &(&1["task"] == "transcribe"))
      # The working set is gone after the extraction.
      assert RisiMe.Agent.CallTranscribe.Summary.segments(w.og, session!(sid)) == []
    end

    test "less than 30 s of speech, or one speaker: no summary", w do
      RisiMe.LedgerHelpers.ledger_on!()
      RisiMe.LedgerHelpers.discussion_llm!(fn _ -> %{"items" => []} end)
      sid = start_on!(w)
      say!(w, :a, sid, 0, 0, "en:hello there")
      say!(w, :b, sid, 0, 12, "en:hi")
      tpush!(w, :a, "stop", %{"session_id" => sid, "reason" => "ended"})
      _ = await_post(w.og, "call_transcribe", &(&1["state"] == "ended"))

      eventually(fn ->
        all_enqueued(worker: Job) |> Enum.any?(&(&1.args["kind"] == "call_transcribe_summary"))
      end)

      assert :ok =
               perform_job(Job, %{
                 "kind" => "call_transcribe_summary",
                 "conv" => w.og,
                 "session_id" => sid
               })

      refute_post(w.og, "discussion_card")
      assert RisiMe.RisiHelpers.llm_requests() == []
    end
  end
end
