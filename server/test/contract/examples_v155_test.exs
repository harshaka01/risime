defmodule RisiMe.Contract.ExamplesV155Test do
  @moduledoc """
  Contract v1.55 §41.11 (Harsha's decision 2026-10-16 15:25 SLT, decision 094): Risi sends Official
  messages on a voice yes during a Call Risi. Shapes only here; the behaviour's tests come with the
  call brain's `send_message` (server/test/risime/agent/call_risi/).
  """
  use ExUnit.Case, async: true

  @dir Path.expand("../../../contract/v1/examples", __DIR__)
  @files ~w(envelope_risi_sent_for.json envelope_risi_message_sent_voice.json
            envelope_risi_action_undo_send.json)

  @doc "The v1.55 examples (for the coverage test in examples_test.exs)."
  def files, do: @files

  defp example(f), do: @dir |> Path.join(f) |> File.read!() |> Jason.decode!()

  test "sent_for: Risi's own post, labelled, never the user; text only in the body" do
    e = example("envelope_risi_sent_for.json")
    assert %{"type" => "text", "body" => body, "risi" => r} = e
    assert String.ends_with?(body, "\n— Sent by Risi for Harsha")

    assert %{
             "v" => 1,
             "kind" => "sent_for",
             "on_behalf_of" => %{"user_id" => _, "name" => "Harsha"}
           } = r

    # §24's `for` is a list of user ids on every Risi object: never reused for another shape.
    refute Map.has_key?(r, "for")
    assert Map.has_key?(r, "write_id") and Map.has_key?(r, "call_ref")
    refute Map.has_key?(r, "text")
  end

  test "message_sent by voice: Official, via risi, an undo window; undo_send names the write" do
    m = example("envelope_risi_message_sent_voice.json")["risi"]

    assert %{"kind" => "message_sent", "tab" => "official", "via" => "risi", "state" => "sent"} =
             m

    assert {:ok, until, 0} = DateTime.from_iso8601(m["undo_until"])
    assert {:ok, sent, 0} = DateTime.from_iso8601(m["made_by"]["at"])
    assert DateTime.diff(until, sent) == 120
    refute Map.has_key?(m, "text")

    u = example("envelope_risi_action_undo_send.json")

    assert u == %{
             "v" => 1,
             "type" => "risi_action",
             "action" => "undo_send",
             "target" => m["write_id"]
           }
  end
end
