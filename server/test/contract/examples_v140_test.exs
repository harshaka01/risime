defmodule RisiMe.Contract.ExamplesV140Test do
  @moduledoc """
  Contract v1.40 §36.11 (decision 071 amendment b, the Sinhala script check): the new example
  files against what the server really posts when the speech service reports the script check
  (fake speech, `"sx:…"` audio): `script` / `romanised` per compare result, and `romanised` on a
  `call_transcript` turn whose Sinhala text stayed out of Sinhala script after the retry.
  """
  use RisiMeWeb.ConnCase, async: false

  import RisiMe.CallTranscribeHelpers

  alias RisiMe.Agent.CallTranscribe

  @moduletag capture_log: true

  @dir Path.expand("../../../contract/v1/examples", __DIR__)

  @doc false
  def files,
    do: ~w(envelope_risi_call_transcript_compare_si_script.json
           envelope_risi_call_transcript_romanised.json)

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  # Same keys at every level and the same JSON type for each value (null matches anything).
  defp assert_shape(ours, theirs, path \\ "$")

  defp assert_shape(ours, theirs, path) when is_map(theirs) do
    assert is_map(ours), "#{path}: expected an object, got #{inspect(ours)}"

    assert Enum.sort(Map.keys(ours)) == Enum.sort(Map.keys(theirs)),
           "#{path}: keys #{inspect(Enum.sort(Map.keys(ours)))} vs #{inspect(Enum.sort(Map.keys(theirs)))}"

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
    transcribe_world!()
  end

  test "envelope_risi_call_transcript_compare_si_script.json: the script check per model", w do
    Application.put_env(:risime, :risi_dev_users, [w.a.user.id])
    :ok = CallTranscribe.put_dev_setting(w.a.user.id, true)
    sid = start_on!(w)
    assert {202, _} = upload!(w, :b, sid, "sx:හෙට උදේ දහයට හමුවෙමු.", %{"seq" => 5})
    {body, risi} = await_post(w.og, "call_transcript_compare")
    ex = example("envelope_risi_call_transcript_compare_si_script.json")
    risi = stringify(risi)
    assert_shape(risi, ex["risi"])
    assert risi["language_detected"] == "si"

    by_model = Map.new(risi["results"], &{&1["model"], &1})

    assert by_model["faster-whisper-large-v3"]["script"] ==
             %{"expected" => "sinh", "sinhala_chars" => 0, "latin_chars" => 23, "ok" => false}

    assert by_model["faster-whisper-large-v3"]["romanised"] == true
    assert by_model["whisper-small-sinhala"]["script"]["ok"] == true
    assert by_model["whisper-small-sinhala"]["romanised"] == false
    # The body is unchanged by v1.40: "<model> (<elapsed> ms): <text>", chosen first.
    assert hd(String.split(body, "\n")) == hd(String.split(ex["body"], "\n"))
  end

  test "a compare chunk without the service's script check carries no script fields", w do
    Application.put_env(:risime, :risi_dev_users, [w.a.user.id])
    :ok = CallTranscribe.put_dev_setting(w.a.user.id, true)
    sid = start_on!(w)
    assert {202, _} = upload!(w, :b, sid, "si:හෙට උදේ දහයට හමුවෙමු.", %{"seq" => 4})
    {_, risi} = await_post(w.og, "call_transcript_compare")

    for r <- stringify(risi)["results"] do
      refute Map.has_key?(r, "script")
      refute Map.has_key?(r, "romanised")
    end
  end

  test "envelope_risi_call_transcript_romanised.json: romanised only when still romanised", w do
    sid = start_on!(w)
    assert {202, _} = upload!(w, :a, sid, "sx:Heta ude dahayata hamuwemu.")
    assert {:ok, _} = tpush!(w, :b, "stop", %{"session_id" => sid})
    {body, t} = await_post(w.og, "call_transcript")
    t = stringify(t)
    ex = example("envelope_risi_call_transcript_romanised.json")
    assert_shape(t, ex["risi"])
    assert t["romanised"] == true
    assert t["language"] == "si"
    assert t["text"] == "Heta ude dahayata hamuwemu."
    # The visible line is unchanged: no model name, no script badge.
    assert body =~ ~r/^Test User A \d\d:\d\d: Heta ude dahayata hamuwemu\.$/
  end

  test "a Sinhala turn in Sinhala script has no romanised field", w do
    sid = start_on!(w)
    assert {202, _} = upload!(w, :a, sid, "si:හෙට උදේ දහයට හමුවෙමු.")
    assert {:ok, _} = tpush!(w, :b, "stop", %{"session_id" => sid})
    {_, t} = await_post(w.og, "call_transcript")
    refute Map.has_key?(stringify(t), "romanised")
  end
end
