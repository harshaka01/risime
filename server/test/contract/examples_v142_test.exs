defmodule RisiMe.Contract.ExamplesV142Test do
  @moduledoc """
  Contract v1.42 §38 (decision 082, Risi voice → action items): every new example file. The
  server-produced ones are compared against real server output in
  test/risime/agent/action_items_test.exs (`action_items` for a call, a voice note and unclear,
  `action_confirm`, `risi_commitments_reply_v142.json`, `auth_config_v142.json`). Here: the shape
  rules of every example, and the client-sent `risi_action`s are ones the server handles.
  """
  use ExUnit.Case, async: true

  @dir Path.expand("../../../contract/v1/examples", __DIR__)

  @kinds ~w(promise task meeting decision question number)
  @ref_keys ~w(at call_id message_id offset_ms session_id turn type)
  @item_keys ~w(all_day counterpart due due_text item_id kind lang owner source_ref state text)

  @doc false
  def files,
    do: ~w(auth_config_v142.json envelope_risi_action_confirm.json
           envelope_risi_action_item_calendar.json envelope_risi_action_item_not_mine.json
           envelope_risi_action_item_remind.json envelope_risi_action_items.json
           envelope_risi_action_items_unclear.json envelope_risi_action_items_voice_note.json
           risi_commitments_reply_v142.json)

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  defp check_ref(%{} = r, type) do
    assert Enum.sort(Map.keys(r)) == @ref_keys
    assert r["type"] == type and is_binary(r["at"])
  end

  defp check_item(i, type) do
    assert Enum.sort(Map.keys(i) -- ["buttons"]) == @item_keys
    assert i["kind"] in @kinds
    assert is_binary(i["text"]) and String.length(i["text"]) in 1..200
    if i["owner"] == nil, do: assert(i["state"] == nil)
    check_ref(i["source_ref"], type)
  end

  test "action_items: call, voice note and unclear" do
    %{"risi" => c} = example("envelope_risi_action_items.json")
    assert %{"kind" => "action_items", "source" => "call", "status" => "ok", "reason" => nil} = c
    check_ref(c["source_ref"], "call")
    assert length(c["items"]) == 4
    for i <- c["items"], do: check_item(i, "call")
    assert Enum.all?(c["items"], &is_integer(&1["source_ref"]["turn"]))

    %{"risi" => v} = example("envelope_risi_action_items_voice_note.json")
    assert %{"source" => "voice_note", "summary" => nil} = v
    for i <- v["items"], do: check_item(i, "voice_note")
    assert Enum.all?(v["items"], &is_binary(&1["source_ref"]["message_id"]))

    %{"risi" => u} = example("envelope_risi_action_items_unclear.json")
    assert %{"status" => "unclear", "reason" => "script_check", "items" => []} = u
  end

  test "action_confirm and the new risi_action values" do
    %{"risi" => a} = example("envelope_risi_action_confirm.json")
    assert %{"kind" => "action_confirm", "for" => [owner], "notify" => [owner]} = a
    assert is_binary(a["expires_at"])

    for i <- a["items"] do
      check_item(i, "call")
      assert i["owner"] == owner and i["state"] == "proposed"
      assert i["buttons"] -- ~w(add_promise add_calendar remind not_mine edit) == []
    end

    assert %{"action" => "item_calendar", "edit" => %{"start" => _, "end" => _}} =
             example("envelope_risi_action_item_calendar.json")

    assert %{"action" => "item_remind", "edit" => %{"remind_at" => _}} =
             example("envelope_risi_action_item_remind.json")

    assert %{"action" => "item_not_mine", "edit" => nil} =
             example("envelope_risi_action_item_not_mine.json")

    # The server handles each new action (owner only; test/risime/agent/action_items_test.exs).
    for f <-
          ~w(envelope_risi_action_item_calendar.json envelope_risi_action_item_remind.json
                envelope_risi_action_item_not_mine.json),
        do: assert(example(f)["action"] in RisiMe.Agent.LedgerActions.actions())

    assert RisiMe.Agent.ActionItems.kinds() == @kinds
  end

  test "My promises v1.42 and the switch" do
    [i] = example("risi_commitments_reply_v142.json")["commitments"]
    assert i["kind"] == "promise" and i["source"] == "call"
    check_ref(i["source_ref"], "call")
    assert example("auth_config_v142.json")["risi_action_items"] == "on"
  end
end
