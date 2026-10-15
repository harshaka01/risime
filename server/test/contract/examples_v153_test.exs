defmodule RisiMe.Contract.ExamplesV153Test do
  @moduledoc """
  Contract v1.53 §41.10 (Harsha 2026-10-16 04:52 SLT, P0 hearing): the caller's English speech
  recognised on the phone, sent to Risi over the Call Risi room's data channel (topic
  `risi.stt`). The shapes of every new example; the server's handling is covered with the
  Call Risi session tests.
  """
  use ExUnit.Case, async: true

  @dir Path.expand("../../../contract/v1/examples", __DIR__)
  @files ~w(device_put_call_risi_stt.json call_risi_stt_partial.json call_risi_stt_final.json
            call_risi_stt_off.json call_risi_stt_mode.json call_risi_stt_speaking.json)

  @doc "The v1.53 examples (for the coverage test in examples_test.exs)."
  def files, do: @files

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  test "device_put_call_risi_stt.json: call_risi_stt only together with call_risi" do
    caps = example("device_put_call_risi_stt.json")["mls"]["capabilities"]
    assert "call_risi_stt" in caps and "call_risi" in caps
  end

  test "phone → Risi: partial, final, off" do
    assert %{"t" => "partial", "seq" => s, "text" => t} = example("call_risi_stt_partial.json")
    assert is_integer(s) and s >= 1 and is_binary(t)

    f = example("call_risi_stt_final.json")
    assert Enum.sort(Map.keys(f)) == ~w(conf lang seq t text)
    assert f["t"] == "final" and f["lang"] == "en" and f["conf"] >= 0 and f["conf"] <= 1
    assert String.length(f["text"]) <= 1000

    assert %{"t" => "off", "reason" => r} = example("call_risi_stt_off.json")
    assert r in ~w(unavailable error busy language)
  end

  test "Risi → phone: mode, speaking" do
    assert %{"t" => "mode", "mode" => m} = example("call_risi_stt_mode.json")
    assert m in ~w(phone server)
    assert %{"t" => "speaking", "on" => on} = example("call_risi_stt_speaking.json")
    assert is_boolean(on)
  end
end
