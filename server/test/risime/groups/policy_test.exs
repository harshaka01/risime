defmodule RisiMe.Groups.PolicyTest do
  @moduledoc "Runs every case of the shared fixture contract/v1/group_policy_cases.json (§12.4, §12.4a)."
  use ExUnit.Case, async: true

  alias RisiMe.Groups.Policy

  @fixture Path.expand("../../../../contract/v1/group_policy_cases.json", __DIR__)
  @external_resource @fixture
  @fixture_json @fixture |> File.read!() |> Jason.decode!()
  @cases Map.fetch!(@fixture_json, "cases")

  defp user(leaf), do: leaf |> String.split("/") |> hd()
  defp leaf(l), do: l |> String.split("/") |> List.to_tuple()

  test "the fixture has cases (v2: base-epoch leaves)" do
    assert @fixture_json["v"] == 2
    assert length(@cases) >= 26
  end

  for c <- @cases do
    @case c
    test "policy case: #{c["name"]}" do
      c = @case

      meta =
        case c["meta"] do
          nil -> nil
          m -> %{admins: m["admins"], name_changed: m["name_changed"]}
        end

      result =
        Policy.check(%{
          admins: c["admins"],
          agents: c["agents"],
          committer: user(c["committer"]),
          adds: Enum.map(c["adds"], &leaf/1),
          removes: Enum.map(c["removes"], &leaf/1),
          leaf_users: for({u, [_ | _]} <- c["leaves"], do: u),
          meta: meta
        })

      case c["expect"] do
        "accept" -> assert result == :ok, inspect(result)
        "reject" -> assert {:error, _} = result
      end
    end
  end
end
