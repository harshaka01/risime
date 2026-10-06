defmodule RisiMe.Groups.PolicyTest do
  @moduledoc "Runs every case of the shared fixture contract/v1/group_policy_cases.json (§12.4)."
  use ExUnit.Case, async: true

  alias RisiMe.Groups.Policy

  @fixture Path.expand("../../../../contract/v1/group_policy_cases.json", __DIR__)
  @external_resource @fixture
  @cases @fixture |> File.read!() |> Jason.decode!() |> Map.fetch!("cases")

  defp user(leaf), do: leaf |> String.split("/") |> hd()

  test "the fixture has cases" do
    assert length(@cases) >= 15
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
          adds: Enum.map(c["adds"], &user/1),
          removes: Enum.map(c["removes"], &user/1),
          meta: meta
        })

      case c["expect"] do
        "accept" -> assert result == :ok, inspect(result)
        "reject" -> assert {:error, _} = result
      end
    end
  end
end
