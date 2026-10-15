defmodule RisiMe.Contract.ExamplesV150Test do
  @moduledoc """
  Contract v1.50 §36.12 (proposal 2026-10-15-live-call-latency, flag `RISI_LIVE_FAST`): a fast
  session's `chunk` (`mode: "vad"`, `overlap_ms`) and its per-chunk `call_transcript`, checked
  against what the server really sends (same keys, same value types; the fast `chunk` exactly).
  The behaviour is covered in test/risime/agent/call_transcribe_fast_test.exs.
  """
  use RisiMeWeb.ConnCase, async: false

  import RisiMe.CallTranscribeHelpers

  alias RisiMe.Agent.CallTranscribe

  @moduletag capture_log: true

  @dir Path.expand("../../../contract/v1/examples", __DIR__)

  @doc false
  def files, do: ~w(call_transcribe_reply_fast.json envelope_risi_call_transcript_fast.json)

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  defp assert_shape(ours, theirs, path \\ "$")

  defp assert_shape(ours, theirs, path) when is_map(theirs) do
    assert is_map(ours), "#{path}: expected an object, got #{inspect(ours)}"
    assert Enum.sort(Map.keys(ours)) == Enum.sort(Map.keys(theirs)), "#{path}: keys"
    for {k, v} <- theirs, do: assert_shape(ours[k], v, "#{path}.#{k}")
  end

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
    old = Application.fetch_env(:risime, :risi_agent_flags)
    Application.put_env(:risime, :risi_agent_flags, %{"RISI_LIVE_FAST" => "on"})

    on_exit(fn ->
      case old do
        {:ok, v} -> Application.put_env(:risime, :risi_agent_flags, v)
        :error -> Application.delete_env(:risime, :risi_agent_flags)
      end
    end)

    transcribe_world!()
  end

  test "call_transcribe_reply_fast.json and envelope_risi_call_transcript_fast.json", w do
    payload =
      Map.merge(example("call_transcribe_request.json"), %{
        "call_id" => w.call_id,
        "conversation_id" => w.og
      })

    assert {:ok, reply} = CallTranscribe.push(w.a.user.id, w.da, payload)
    reply = stringify(reply)
    ex = example("call_transcribe_reply_fast.json")
    assert_shape(reply, ex)
    assert reply["session"]["chunk"] == ex["session"]["chunk"]

    sid = reply["session"]["session_id"]
    {:ok, %{session: %{"state" => "on"}}} = tpush!(w, :b, "allow", %{"session_id" => sid})
    _ = await_post(w.og, "call_transcribe")

    assert {202, _} =
             upload!(w, :a, sid, "en:on Friday.", %{"offset_ms" => 3_500, "duration_ms" => 2_700})

    # Posted at once (no turn merging), in the v1.39 shape.
    {_, t} = await_post(w.og, "call_transcript")
    assert_shape(stringify(t), example("envelope_risi_call_transcript_fast.json")["risi"])
    assert t["text"] == "on Friday."
  end
end
