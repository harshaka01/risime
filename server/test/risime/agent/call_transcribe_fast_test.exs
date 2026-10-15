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

  test "Session.chunk/1 of a stored row follows its fast column" do
    assert Session.chunk(%Session{fast: true})["mode"] == "vad"
    assert Session.chunk(%Session{fast: false}) == Session.chunk()
  end
end
