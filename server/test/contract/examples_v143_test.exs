defmodule RisiMe.Contract.ExamplesV143Test do
  @moduledoc """
  Contract v1.43 §38.9 (ask, never guess): every new example file. The server-produced ones are
  compared against real server output in test/risime/agent/action_items_test.exs (the claimable
  card, the claimer's `action_confirm`, the `item_update` with `owner`, the unclear wording).
  Here: the shape rules, and that the client-sent `item_claim` is an action the server handles.
  """
  use ExUnit.Case, async: true

  @dir Path.expand("../../../contract/v1/examples", __DIR__)

  @item_keys ~w(all_day counterpart due due_text item_id kind lang owner source_ref state text)

  @doc false
  def files,
    do: ~w(envelope_risi_action_items_v143.json envelope_risi_action_item_claim.json
           envelope_risi_action_confirm_v143.json envelope_risi_item_update_v143.json
           envelope_risi_action_items_unclear_v143.json)

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  test "the files exist and are JSON" do
    for f <- files(), do: assert(%{"v" => 1} = example(f))
  end

  test "action_items: claim only on unowned promise/task/meeting; Risi asks in the body" do
    %{"body" => body, "risi" => c} = example("envelope_risi_action_items_v143.json")
    assert c["kind"] == "action_items" and c["status"] == "ok"

    for i <- c["items"] do
      assert Enum.sort(Map.keys(i) -- ["claim"]) == @item_keys

      if i["claim"] do
        assert i["claim"] == true and i["owner"] == nil and i["state"] == nil
        assert i["kind"] in ~w(promise task meeting)
        assert body =~ ~s(Who's taking "#{i["text"]}"? Tap "It's mine" on the card.)
      end
    end

    assert Enum.count(c["items"], & &1["claim"]) == 1
  end

  test "item_claim is a risi_action the server handles" do
    a = example("envelope_risi_action_item_claim.json")
    assert %{"type" => "risi_action", "action" => "item_claim", "edit" => nil} = a
    assert a["action"] in RisiMe.Agent.LedgerActions.actions()

    [claimable] =
      Enum.filter(example("envelope_risi_action_items_v143.json")["risi"]["items"], & &1["claim"])

    assert a["target"] == claimable["item_id"]
  end

  test "action_confirm: reminders_skill, remind always, add_calendar for an all-day due" do
    %{"risi" => a} = example("envelope_risi_action_confirm_v143.json")
    assert a["reminders_skill"] in ~w(off ask allowed)
    [i] = a["items"]
    assert i["all_day"] == true and is_binary(i["due"])
    assert "remind" in i["buttons"] and "add_calendar" in i["buttons"]
    assert i["buttons"] -- ~w(add_promise add_calendar remind not_mine edit) == []
  end

  test "item_update to a non-recipient: state and owner only" do
    %{"risi" => u} = example("envelope_risi_item_update_v143.json")
    assert %{"kind" => "item_update", "text" => nil, "due" => nil, "all_day" => nil} = u
    assert is_binary(u["owner"]) and u["state"] == "proposed"
  end

  test "unclear: I couldn't hear this clearly, no items" do
    %{"body" => body, "risi" => u} = example("envelope_risi_action_items_unclear_v143.json")
    assert %{"status" => "unclear", "items" => []} = u
    assert body == RisiMe.Agent.ActionItems.unclear_text(u["lang"])
    assert RisiMe.Agent.ActionItems.unclear_text("en") == "I couldn't hear this clearly."
  end
end
