defmodule RisiMe.Agent.SchemaOrderP01011Test do
  @moduledoc """
  P0 2026-10-11: the `risi_next_action` schema sent to the model lists `tool` FIRST in every
  alternative (the live risi-l1 eval: 8% correct tool calls without, 68% with).
  """
  use ExUnit.Case, async: true

  alias RisiMe.Agent.{ClientTools, Tools}

  test "the encoded schema's property order starts with tool, in every alternative" do
    allowed = [Tools.capabilities(), ClientTools.calendar_check(), ClientTools.schedule_message()]
    json = allowed |> Tools.schema(["calendar"]) |> Tools.wire_schema() |> Jason.encode!()

    decoded = Jason.decode!(json, objects: :ordered_objects)

    for alt <- decoded["anyOf"] do
      [{first, _} | _] = alt["properties"].values
      assert first == "tool"
      assert alt.values |> hd() |> elem(0) == "type"
    end

    # The map form still validates (same content).
    assert Jason.decode!(json) == Tools.schema(allowed, ["calendar"])
  end
end
