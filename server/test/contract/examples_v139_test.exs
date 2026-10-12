defmodule RisiMe.Contract.ExamplesV139Test do
  @moduledoc """
  Contract v1.39 §36 (decision 080, the call-transcription fast path): every new example file
  is checked against what the server really sends or answers (same keys, same value types;
  error replies exactly). The behaviour is covered in
  test/risime/agent/call_transcribe_*_test.exs.
  """
  use RisiMeWeb.ConnCase, async: false

  import RisiMe.CallTranscribeHelpers
  import Ecto.Query, only: [from: 2]

  alias RisiMe.Agent.CallTranscribe

  @moduletag capture_log: true

  @dir Path.expand("../../../contract/v1/examples", __DIR__)

  @doc false
  def files,
    do: ~w(auth_config_v139.json call_offer_official_payload.json call_transcribe_allow.json
           call_transcribe_chunk_reply.json call_transcribe_decline.json call_transcribe_reply.json
           call_transcribe_request.json call_transcribe_stop.json device_put_call_transcribe.json
           envelope_risi_call_transcribe_on.json envelope_risi_call_transcribe_stopped.json
           envelope_risi_call_transcript.json envelope_risi_call_transcript_compare.json
           envelope_risi_discussion_card_transcribed.json error_not_consented.json
           error_not_official.json error_transcribe_off.json me_reply_risi_dev.json
           me_risi_dev_put.json me_risi_dev_reply.json signal_call_transcribe.json)

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  # Same keys at every level and the same JSON type for each value (null matches anything).
  defp assert_shape(ours, theirs, path \\ "$")

  defp assert_shape(ours, theirs, path) when is_map(theirs) do
    assert is_map(ours), "#{path}: expected an object, got #{inspect(ours)}"

    assert Enum.sort(Map.keys(ours)) == Enum.sort(Map.keys(theirs)),
           "#{path}: keys #{inspect(Enum.sort(Map.keys(ours)))} vs #{inspect(Enum.sort(Map.keys(theirs)))}"

    for {k, v} <- theirs, do: assert_shape(ours[k], v, "#{path}.#{k}")
  end

  # Same length: element by element (an example's nulls sit at fixed places); else the first.
  defp assert_shape(ours, [t | _] = theirs, path) do
    assert is_list(ours) and ours != [], "#{path}: expected a non-empty list"

    if length(ours) == length(theirs),
      do: for({o, x} <- Enum.zip(ours, theirs), do: assert_shape(o, x, path <> "[]")),
      else: for(o <- ours, do: assert_shape(o, t, path <> "[]"))
  end

  defp assert_shape(ours, [], path), do: assert(is_list(ours), path)
  defp assert_shape(_ours, nil, _path), do: :ok
  defp assert_shape(ours, t, path) when is_binary(t), do: assert(is_binary(ours), path)
  defp assert_shape(ours, t, path) when is_boolean(t), do: assert(is_boolean(ours), path)
  defp assert_shape(ours, t, path) when is_integer(t), do: assert(is_integer(ours), path)

  defp stringify(m), do: m |> Jason.encode!() |> Jason.decode!()

  setup do
    transcribe_world!()
  end

  test "auth_config_v139.json: call_transcribe on with the switch, Risi and speech; absent off",
       %{conn: conn} do
    body = conn |> get("/api/v1/auth/config") |> json_response(200)
    assert body["call_transcribe"] == example("auth_config_v139.json")["call_transcribe"]

    Application.put_env(:risime, :fake_speech_healthy, false)

    refute Map.has_key?(
             build_conn() |> get("/api/v1/auth/config") |> json_response(200),
             "call_transcribe"
           )

    Application.put_env(:risime, :fake_speech_healthy, true)
    Application.put_env(:risime, :risi_call_transcribe, false)

    refute Map.has_key?(
             build_conn() |> get("/api/v1/auth/config") |> json_response(200),
             "call_transcribe"
           )
  end

  test "me_reply_risi_dev.json, me_risi_dev_put.json, me_risi_dev_reply.json; not_developer", w do
    Application.put_env(:risime, :risi_dev_users, [w.a.user.id])
    req = fn who -> build_conn() |> put_req_header("authorization", "Bearer " <> w[who].token) end

    me = req.(:a) |> get("/api/v1/me") |> json_response(200)
    assert me["user"]["risi_dev"] == example("me_reply_risi_dev.json")["user"]["risi_dev"]
    me_b = req.(:b) |> get("/api/v1/me") |> json_response(200)
    refute Map.has_key?(me_b["user"], "risi_dev")

    assert %{"transcribe_compare" => false} =
             req.(:a) |> get("/api/v1/me/risi/dev") |> json_response(200)

    put = example("me_risi_dev_put.json")

    assert req.(:a) |> put("/api/v1/me/risi/dev", put) |> json_response(200) ==
             example("me_risi_dev_reply.json")

    assert req.(:a) |> get("/api/v1/me/risi/dev") |> json_response(200) ==
             example("me_risi_dev_reply.json")

    assert %{"error" => %{"code" => "not_developer"}} =
             req.(:b) |> put("/api/v1/me/risi/dev", put) |> json_response(403)

    assert %{"error" => %{"code" => "not_developer"}} =
             req.(:b) |> get("/api/v1/me/risi/dev") |> json_response(403)

    assert %{"error" => %{"code" => "bad_request"}} =
             req.(:a)
             |> put("/api/v1/me/risi/dev", %{"transcribe_compare" => "yes"})
             |> json_response(422)
  end

  test "device_put_call_transcribe.json: the capability is kept", w do
    ex = example("device_put_call_transcribe.json")
    device = Ecto.UUID.generate()

    assert build_conn()
           |> put_req_header("authorization", "Bearer " <> w.a.token)
           |> put("/api/v1/me/devices/#{device}", ex)
           |> response(200)

    assert CallTranscribe.transcribe_device?(w.a.user.id, device)
  end

  test "the pushes, call_transcribe_reply.json and signal_call_transcribe.json", w do
    RisiMe.Messaging.subscribe(w.b.user.id)

    ex = fn name ->
      Map.merge(example(name), %{"call_id" => w.call_id, "conversation_id" => w.og})
    end

    assert {:ok, reply} =
             CallTranscribe.push(w.a.user.id, w.da, ex.("call_transcribe_request.json"))

    reply = stringify(reply)
    assert_shape(reply, example("call_transcribe_reply.json"))
    assert reply["session"]["chunk"] == example("call_transcribe_reply.json")["session"]["chunk"]
    sid = reply["session"]["session_id"]

    assert_receive {:signal, %{kind: "call_transcribe"} = sig}, 1_000
    assert stringify(sig)["kind"] == "call_transcribe"

    assert {:ok, on} =
             CallTranscribe.push(
               w.b.user.id,
               w.db,
               Map.put(ex.("call_transcribe_allow.json"), "session_id", sid)
             )

    assert_receive {:signal, %{kind: "call_transcribe", data: %{"state" => "on"}} = sig}, 1_000
    assert_shape(stringify(sig), example("signal_call_transcribe.json"))
    assert_shape(stringify(on)["session"], example("signal_call_transcribe.json")["data"])

    {_, line} = await_post(w.og, "call_transcribe")
    assert_shape(stringify(line), example("envelope_risi_call_transcribe_on.json")["risi"])

    assert {202, chunk} = upload!(w, :a, sid, "en:Let's send the revised quote by Friday.")
    assert_shape(chunk, example("call_transcribe_chunk_reply.json"))

    assert {:ok, _} =
             CallTranscribe.push(
               w.b.user.id,
               w.db,
               Map.put(ex.("call_transcribe_stop.json"), "session_id", sid)
             )

    {_, t} = await_post(w.og, "call_transcript")
    assert_shape(stringify(t), example("envelope_risi_call_transcript.json")["risi"])
    {_, st} = await_post(w.og, "call_transcribe", &(&1["state"] == "stopped"))
    assert_shape(stringify(st), example("envelope_risi_call_transcribe_stopped.json")["risi"])
  end

  test "call_transcribe_decline.json", w do
    {:ok, %{session: %{"session_id" => sid}}} = tpush!(w, :a, "request")

    payload =
      example("call_transcribe_decline.json")
      |> Map.merge(%{"call_id" => w.call_id, "conversation_id" => w.og, "session_id" => sid})

    assert {:ok, %{session: %{"state" => "declined"}}} =
             CallTranscribe.push(w.b.user.id, w.db, payload)
  end

  test "envelope_risi_call_transcript_compare.json", w do
    Application.put_env(:risime, :risi_dev_users, [w.a.user.id])
    :ok = CallTranscribe.put_dev_setting(w.a.user.id, true)
    sid = start_on!(w)
    assert {202, _} = upload!(w, :b, sid, "si:හෙට උදේ දහයට හමුවෙමු.", %{"seq" => 4})
    {body, risi} = await_post(w.og, "call_transcript_compare")
    ex = example("envelope_risi_call_transcript_compare.json")
    assert_shape(stringify(risi), ex["risi"])
    assert hd(String.split(body, "\n")) == hd(String.split(ex["body"], "\n"))
    assert length(String.split(body, "\n")) == 3
  end

  test "the upload errors: error_not_consented.json, error_not_official.json, error_transcribe_off.json",
       w do
    {:ok, %{session: %{"session_id" => sid}}} = tpush!(w, :a, "request")
    assert {409, err} = upload!(w, :a, sid, "en:x")
    assert err == example("error_not_consented.json")

    tpush!(w, :b, "allow", %{"session_id" => sid})

    RisiMe.Repo.update_all(
      from(m in RisiMe.Groups.Member, where: m.group_id == ^w.og and m.kind == "agent"),
      set: [state: "removed"]
    )

    assert {403, err} = upload!(w, :a, sid, "en:x")
    assert err == example("error_not_official.json")

    Application.put_env(:risime, :risi_call_transcribe, false)
    assert {503, err} = upload!(w, :a, sid, "en:x")
    assert err == example("error_transcribe_off.json")
  end

  test "call_offer_official_payload.json: a call_offer (inside MLS) with tab official" do
    ex = example("call_offer_official_payload.json")
    assert ex["type"] == "call_offer"
    assert ex["tab"] == "official"
  end

  test "envelope_risi_discussion_card_transcribed.json: the card of a transcribed call", w do
    RisiMe.LedgerHelpers.ledger_on!()

    RisiMe.LedgerHelpers.discussion_llm!(fn body ->
      %{"items" => [RisiMe.LedgerHelpers.item(body, "Test User A", "Send the revised quote")]}
    end)

    sid = start_on!(w)

    for {who, seq, off, audio} <- [
          {:a, 0, 0, "en:Let's send the revised quote"},
          {:a, 1, 12, "en:by Friday."},
          {:b, 0, 24, "si:හරි."}
        ] do
      assert {202, _} =
               upload!(w, who, sid, audio, %{"seq" => seq, "offset_ms" => off * 1000})

      assert_receive {:fake_speech, _, ^audio}, 2_000
      Process.sleep(60)
    end

    tpush!(w, :a, "stop", %{"session_id" => sid, "reason" => "ended"})
    _ = await_post(w.og, "call_transcribe", &(&1["state"] == "ended"))
    eventually(fn -> CallTranscribe.Summary.segments(w.og, session!(sid)) |> length() == 2 end)

    assert :ok =
             perform_job(%{
               "kind" => "call_transcribe_summary",
               "conv" => w.og,
               "session_id" => sid
             })

    {_, card} = await_post(w.og, "discussion_card")
    ex = example("envelope_risi_discussion_card_transcribed.json")["risi"]
    assert_shape(stringify(card), ex)
    assert card["lang"] == "en"
  end

  defp perform_job(args),
    do: Oban.Testing.perform_job(RisiMe.Workers.Risi, args, repo: RisiMe.Repo)
end
