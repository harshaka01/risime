defmodule RisiMe.Agent.CompactJsonTest do
  use ExUnit.Case, async: true

  alias RisiMe.Agent.CompactJson
  alias RisiMe.Agent.CallRisi.Decider

  test "the call decision schema becomes a compact grammar, tool first, required fields kept" do
    g = CompactJson.grammar(Decider.schema())
    assert g =~ ~s<root ::= (("{" "\\"tool\\"" ":" root_enum_0 root_part_1 "}"))>
    # required: no way around it
    assert g =~ ~s<root_part_1 ::= (("," "\\"action\\"" ":" root_enum_1 root_part_2))>
    # optional: may be skipped
    assert g =~
             ~s<root_part_2 ::= ((root_part_3) | ("," "\\"cancel_open\\"" ":" basic_boolean root_part_3))>

    assert g =~ ~s<("\\"read_messages\\"")>
    refute g =~ ~s<": ">
  end

  test "a schema it can't express is nil (the JSON schema stays)" do
    assert CompactJson.grammar(%{
             "type" => "object",
             "properties" => %{"a" => %{"type" => "array"}},
             "required" => ["a"]
           }) == nil

    assert CompactJson.grammar(%{
             "type" => "object",
             "properties" => %{"a" => %{"type" => "string"}}
           }) == nil

    assert CompactJson.grammar(%{"type" => "string"}) == nil
  end
end
