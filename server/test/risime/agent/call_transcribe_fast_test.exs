defmodule RisiMe.Agent.CallTranscribeFastTest do
  @moduledoc """
  Proposal 2026-10-15-live-call-latency (P0-1b, flag `RISI_LIVE_FAST`): a fast session asks the
  phones for VAD-cut 1-4 s chunks with 0.5 s of overlap (`Session.chunk`), takes their higher
  rate, posts each chunk's line at once (no turn merging, no 30-s hold), drops the words the
  overlap repeats, sends its chunks at `priority: "live"` with `fast: true`, and holds the
  speech-guard pause file while it is on. Off (the default), nothing changes. Through the real
  endpoint; the speech service is `RisiMe.FakeSpeech`.
  """
  use RisiMeWeb.ConnCase, async: false

  import RisiMe.CallTranscribeHelpers

  alias RisiMe.Agent.CallTranscribe.Session

  @moduletag capture_log: true

  setup do
    old =
      for k <- [:risi_agent_flags, :risi_speech_pause_file],
          do: {k, Application.fetch_env(:risime, k)}

    on_exit(fn ->
      for {k, prev} <- old do
        case prev do
          {:ok, v} -> Application.put_env(:risime, k, v)
          :error -> Application.delete_env(:risime, k)
        end
      end
    end)

    :ok
  end

  defp fast!(mode),
    do: Application.put_env(:risime, :risi_agent_flags, %{"RISI_LIVE_FAST" => mode})

  # Uploads one 3-s chunk at `offset_ms` and waits until the fake service has seen it.
  defp say!(w, who, sid, seq, offset_ms, audio, dur_ms \\ 3_000) do
    assert {202, %{"seq" => ^seq, "duplicate" => false}} =
             upload!(w, who, sid, audio, %{
               "seq" => seq,
               "offset_ms" => offset_ms,
               "duration_ms" => dur_ms
             })

    assert_receive {:fake_speech, _, ^audio}, 2_000
  end

  defp request!(w) do
    {:ok, %{session: s}} = tpush!(w, :a, "request")
    s
  end

  test "off (the default): the session's chunk limits and turn merging are unchanged" do
    w = transcribe_world!()
    s = request!(w)
    assert s["chunk"] == %{"min_ms" => 5000, "max_ms" => 20_000, "max_bytes" => 262_144}

    {:ok, %{session: %{"state" => "on"}}} =
      tpush!(w, :b, "allow", %{"session_id" => s["session_id"]})

    refute session!(s["session_id"]).fast

    say!(w, :a, s["session_id"], 0, 0, "en:one", 12_000)
    assert_receive {:fake_speech_opts, "en:one", opts}
    refute opts[:fast]
    refute opts[:priority]
    # A lone speaker's turn stays open (30 s): no line yet.
    refute_post(w.og, "call_transcript", 300)
  end

  test "on: the phones are asked for VAD chunks; each chunk's line is posted at once" do
    fast!("on")
    w = transcribe_world!()
    s = request!(w)

    assert s["chunk"] == %{
             "min_ms" => 1000,
             "max_ms" => 4000,
             "max_bytes" => 262_144,
             "mode" => "vad",
             "overlap_ms" => 500
           }

    sid = s["session_id"]
    {:ok, %{session: %{"state" => "on"}}} = tpush!(w, :b, "allow", %{"session_id" => sid})
    assert session!(sid).fast
    _ = await_post(w.og, "call_transcribe")

    say!(w, :a, sid, 0, 0, "en:We will send the quote")
    assert_receive {:fake_speech_opts, _, opts}
    assert opts[:fast] == true
    assert opts[:priority] == "live"
    {_, t1} = await_post(w.og, "call_transcript")
    assert t1["turn"] == 1
    assert t1["text"] == "We will send the quote"
    assert t1["speaker"] == w.a.user.id

    # The same speaker again: its own line, at once (no merging, no 30-s hold).
    say!(w, :a, sid, 1, 3_500, "en:on Friday.")
    {_, t2} = await_post(w.og, "call_transcript")
    assert t2["turn"] == 2
    assert t2["text"] == "on Friday."
  end

  test "the overlap's repeated words are dropped; a chunk that only repeats posts nothing" do
    fast!("on")
    w = transcribe_world!()
    sid = start_on!(w)
    _ = await_post(w.og, "call_transcribe")

    say!(w, :a, sid, 0, 0, "en:We will send the revised quote")
    {_, _} = await_post(w.og, "call_transcript")
    # Starts 0.5 s before the previous chunk ended: "quote" is heard twice.
    say!(w, :a, sid, 1, 2_500, "en:Quote, on Friday.")
    {_, t} = await_post(w.og, "call_transcript")
    assert t["text"] == "on Friday."
    # Only the overlap again: nothing new.
    say!(w, :a, sid, 2, 5_000, "en:Friday")
    refute_post(w.og, "call_transcript", 300)
    # After a gap (no overlap) the same word is real speech.
    say!(w, :a, sid, 3, 9_000, "en:Friday works.")
    {_, t} = await_post(w.og, "call_transcript")
    assert t["text"] == "Friday works."
    # Another speaker's overlap is never compared with A's words.
    say!(w, :b, sid, 0, 10_000, "en:works for me")
    {_, t} = await_post(w.og, "call_transcript")
    assert t["text"] == "works for me"
  end

  test "a fast session takes its own rate: 8 at once (a normal one 429s the 4th)" do
    fast!("on")
    w = transcribe_world!()
    sid = start_on!(w)

    for i <- 0..7 do
      assert {202, _} = upload!(w, :a, sid, "silence", %{"seq" => i, "duration_ms" => 1000})
      # Let the (instant) fake service drain the queue: this test is about the rate.
      assert_receive {:fake_speech, _, "silence"}, 2_000
      Process.sleep(20)
    end

    assert {429, %{"error" => %{"code" => "rate_limited"}}} =
             upload!(w, :a, sid, "silence", %{"seq" => 8, "duration_ms" => 1000})
  end

  test "a fast session queues up to 4 chunks per participant" do
    fast!("on")
    w = transcribe_world!()
    sid = start_on!(w)
    Application.put_env(:risime, :fake_speech_gate, :closed)
    for i <- 0..3, do: assert({202, _} = upload!(w, :a, sid, "en:#{i}", %{"seq" => i}))

    assert {503, %{"error" => %{"code" => "transcribe_unavailable"}}} =
             upload!(w, :a, sid, "en:4", %{"seq" => 4})

    Application.put_env(:risime, :fake_speech_gate, :open)
  end

  test "canary: fast only with a RISI_DEV_USERS participant; compare is never fast" do
    fast!("canary")
    w = transcribe_world!()
    refute request!(w)["chunk"]["mode"]

    w = transcribe_world!()
    Application.put_env(:risime, :risi_dev_users, [String.downcase(w.b.user.id)])
    assert request!(w)["chunk"]["mode"] == "vad"

    fast!("on")
    w = transcribe_world!()
    Application.put_env(:risime, :risi_dev_users, [String.downcase(w.a.user.id)])
    RisiMe.Agent.CallTranscribe.put_dev_setting(w.a.user.id, true)
    s = request!(w)
    assert s["compare"]
    refute s["chunk"]["mode"]
  end

  @tag :tmp_dir
  test "while a fast session is on, the speech-guard pause file says live_call", %{tmp_dir: dir} do
    path = Path.join(dir, "speech-eval.pause")
    Application.put_env(:risime, :risi_speech_pause_file, path)
    fast!("on")
    w = transcribe_world!()
    _sid = start_on!(w)
    body = eventually(fn -> File.exists?(path) && File.read!(path) end)
    assert body =~ "reason=live_call"
    [_, until] = Regex.run(~r/until=(\d+)/, body)
    left = String.to_integer(until) - System.os_time(:second)
    assert left > 60 and left <= 120
  end

  describe "v1.50 §36.12: Sinhala and Tamil chunks are joined" do
    test "until 8 s of audio (minus the overlap): one line, transcribed with its language" do
      fast!("on")
      w = transcribe_world!()
      sid = start_on!(w)
      _ = await_post(w.og, "call_transcribe")

      say!(w, :a, sid, 0, 0, "si:හෙට", 4_000)
      assert_receive {:fake_speech_opts, "si:හෙට", opts}
      assert opts[:defer_langs] == ["si", "ta"]
      say!(w, :a, sid, 1, 3_500, "si:උදේ", 4_000)
      refute_post(w.og, "call_transcript", 200)
      # 4 + 3.5 + 3.5 ≥ 8 s (4-s cuts, 0.5 s overlaps): the three go as one.
      say!(w, :a, sid, 2, 7_000, "si:දහයට", 4_000)
      assert_receive {:fake_speech_opts, "si:උදේ", _}
      assert_receive {:fake_speech_opts, "si:දහයට", _}
      assert_receive {:fake_speech_opts, joined, jopts}, 2_000
      assert jopts[:joined] == true
      refute jopts[:language]
      assert jopts[:priority] == "live"
      refute jopts[:fast]
      assert joined =~ "si:හෙට"
      {_, t} = await_post(w.og, "call_transcript")
      assert t["text"] == "හෙට උදේ දහයට"
      assert t["language"] == "si"
      assert t["model"] == "whisper-small-sinhala"
      assert t["start"] == RisiMe.Agent.Clock.ts(session!(sid).started_at)
    end

    test "a pause of ≥ 600 ms after ≥ 4 s closes the joined line" do
      fast!("on")
      w = transcribe_world!()
      sid = start_on!(w)
      _ = await_post(w.og, "call_transcribe")
      say!(w, :a, sid, 0, 0, "ta:நாளை", 4_000)
      say!(w, :a, sid, 1, 3_500, "ta:காலை", 4_000)
      refute_post(w.og, "call_transcript", 200)
      # 7.5 s waiting, then 1 s of silence before the next chunk.
      say!(w, :a, sid, 2, 8_500, "ta:சந்திப்போம்", 4_000)
      {_, t} = await_post(w.og, "call_transcript")
      assert t["text"] == "நாளை காலை"
      assert t["language"] == "ta"
    end

    test "≥ 4 s ending at the phone's pause cut (< 3 s chunk) goes out at once" do
      fast!("on")
      w = transcribe_world!()
      sid = start_on!(w)
      _ = await_post(w.og, "call_transcribe")
      say!(w, :a, sid, 0, 0, "si:හෙට", 4_000)
      refute_post(w.og, "call_transcript", 200)
      say!(w, :a, sid, 1, 3_500, "si:උදේ", 1_500)
      {_, t} = await_post(w.og, "call_transcript")
      assert t["text"] == "හෙට උදේ"
    end

    test "a lone chunk goes out after the 6-s wait; English is never held" do
      fast!("on")
      w = transcribe_world!(timing: [join_wait_ms: 150])
      sid = start_on!(w)
      _ = await_post(w.og, "call_transcribe")
      say!(w, :a, sid, 0, 0, "si:හෙට")
      say!(w, :b, sid, 0, 500, "en:see you")
      {_, t} = await_post(w.og, "call_transcript")
      assert t["text"] == "see you"
      {_, t} = await_post(w.og, "call_transcript")
      assert t["text"] == "හෙට"
    end

    test "the same speaker's English sends the waiting Sinhala first; the end flushes the rest" do
      fast!("on")
      w = transcribe_world!()
      sid = start_on!(w)
      _ = await_post(w.og, "call_transcribe")
      say!(w, :a, sid, 0, 0, "si:හෙට")
      say!(w, :a, sid, 1, 4_000, "en:tomorrow then")
      texts = for _ <- 1..2, do: elem(await_post(w.og, "call_transcript"), 1)["text"]
      assert Enum.sort(texts) == Enum.sort(["හෙට", "tomorrow then"])
      say!(w, :a, sid, 2, 6_000, "ta:சரி")
      tpush!(w, :a, "stop", %{"session_id" => sid, "reason" => "ended"})
      {_, t} = await_post(w.og, "call_transcript")
      assert t["text"] == "சரி"
    end

    test "a contiguous chunk joins the waiting Sinhala whatever its own detection says" do
      fast!("on")
      w = transcribe_world!(timing: [join_wait_ms: 300])
      sid = start_on!(w)
      _ = await_post(w.og, "call_transcribe")
      say!(w, :a, sid, 0, 0, "si:හෙට")
      say!(w, :a, sid, 1, 3_000, "en:ude")
      assert_receive {:fake_speech_opts, "en:ude", opts}
      assert "en" in opts[:defer_langs]
      {_, t} = await_post(w.og, "call_transcript")
      assert t["text"] == "හෙට ude"
      assert t["language"] == "si"
    end

    test "stop and delete drops the waiting chunks untranscribed" do
      fast!("on")
      w = transcribe_world!()
      sid = start_on!(w)
      _ = await_post(w.og, "call_transcribe")
      say!(w, :a, sid, 0, 0, "si:හෙට")
      tpush!(w, :a, "stop", %{"session_id" => sid, "reason" => "stopped", "discard" => true})
      Process.sleep(300)
      refute_receive {:fake_speech, :transcribe, _}, 100
      refute_post(w.og, "call_transcript", 100)
    end
  end

  test "P0-1b English context: off by default; on, a continuing piece gets the previous line" do
    fast!("on")
    w = transcribe_world!()
    sid = start_on!(w)
    _ = await_post(w.og, "call_transcribe")
    say!(w, :a, sid, 0, 0, "en:We will send the quote")
    assert_receive {:fake_speech_opts, _, opts}
    refute opts[:en_prompt]
    _ = await_post(w.og, "call_transcript")
    say!(w, :a, sid, 1, 3_500, "en:on Friday.")
    assert_receive {:fake_speech_opts, "en:on Friday.", opts}
    refute opts[:en_prompt]

    w = transcribe_world!(timing: [fast_en_prompt: true])
    sid = start_on!(w)
    _ = await_post(w.og, "call_transcribe")
    say!(w, :a, sid, 0, 0, "en:We will send the quote")
    _ = await_post(w.og, "call_transcript")
    # Continues the line (starts before it ended): the line is the prompt.
    say!(w, :a, sid, 1, 2_500, "en:on Friday.")
    assert_receive {:fake_speech_opts, "en:on Friday.", opts}
    assert opts[:en_prompt] == "We will send the quote"
    _ = await_post(w.og, "call_transcript")
    # After a pause of more than 1 s: no prompt.
    say!(w, :a, sid, 2, 8_000, "en:see you")
    assert_receive {:fake_speech_opts, "en:see you", opts}
    refute opts[:en_prompt]
  end

  test "Session.chunk/1 of a stored row follows its fast column" do
    assert Session.chunk(%Session{fast: true})["mode"] == "vad"
    assert Session.chunk(%Session{fast: false}) == Session.chunk()
  end
end
