defmodule RisiMe.Contract.ExamplesV153Test do
  @moduledoc """
  Contract v1.53 §41.10 (Harsha 2026-10-16 04:52 SLT, P0 hearing): the caller's English speech
  recognised on the phone, sent to Risi over the Call Risi room's data channel (topic
  `risi.stt`). The shapes of every new example; the server's handling is covered with the
  Call Risi session tests.
  """
  use RisiMeWeb.ConnCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]

  alias RisiMe.Agent.CallRisi.PhoneStt

  @dir Path.expand("../../../contract/v1/examples", __DIR__)
  @files ~w(device_put_call_risi_stt.json call_risi_stt_partial.json call_risi_stt_final.json
            call_risi_stt_off.json call_risi_stt_mode.json call_risi_stt_speaking.json)

  @doc "The v1.53 examples (for the coverage test in examples_test.exs)."
  def files, do: @files

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  setup :with_attestation_key

  defp caps_of(user_id, device_id),
    do:
      RisiMe.Repo.get_by!(RisiMe.Devices.Device, user_id: user_id, device_id: device_id).capabilities

  test "device_put_call_risi_stt.json: call_risi_stt only together with call_risi" do
    ex = example("device_put_call_risi_stt.json")
    caps = ex["mls"]["capabilities"]
    assert "call_risi_stt" in caps and "call_risi" in caps

    # What the server keeps: the capability with call_risi, and the session's phone mode with it.
    u = logged_in_user(display_name: "Stt Caps")
    dev = Ecto.UUID.generate()
    {:ok, _} = RisiMe.Devices.register(u.user.id, dev, ex)
    assert "call_risi_stt" in caps_of(u.user.id, dev)
    assert PhoneStt.capable?(u.user.id, dev)

    # Without call_risi (here: without risi_tools, so call_risi goes too) it is dropped.
    dev2 = Ecto.UUID.generate()

    {:ok, _} =
      RisiMe.Devices.register(
        u.user.id,
        dev2,
        put_in(ex, ["mls", "capabilities"], caps -- ["risi_tools"])
      )

    refute "call_risi_stt" in caps_of(u.user.id, dev2)
    refute PhoneStt.capable?(u.user.id, dev2)

    # The v1.52 device (no call_risi_stt) keeps the server's speech-to-text.
    dev3 = Ecto.UUID.generate()
    {:ok, _} = RisiMe.Devices.register(u.user.id, dev3, example("device_put_call_risi_cal.json"))
    refute PhoneStt.capable?(u.user.id, dev3)
  end

  test "call_risi_stt_mode.json: the worker command the server sends is the example's mode" do
    ex = example("call_risi_stt_mode.json")
    cmd = PhoneStt.mode_cmd(ex["mode"])
    assert cmd |> Map.delete("cmd") |> Map.put("t", "mode") == ex
  end

  test "call_risi_stt_final.json: what the server accepts from the worker" do
    ex = example("call_risi_stt_final.json")
    assert {:ok, f} = PhoneStt.final(Map.put(ex, "ev", "stt"))
    assert f.text == ex["text"] and f.conf == ex["conf"] and f.during_risi? == false
    assert PhoneStt.judge(f, false) == {:ok, ex["text"]}
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
