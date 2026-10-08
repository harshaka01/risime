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

  # v1.24 §24.1 tab rules: the server's Policy does not enforce tab/agent rules yet (group_meta
  # is opaque to the server; the MLS core enforces them). The cases are loaded and tagged
  # :pending_v124, excluded by default (`mix test --only pending_v124` shows the gap).
  @tab_cases Map.fetch!(@fixture_json, "tab_cases")

  test "the fixture has tab_cases (v1.24 §24.1)" do
    assert length(@tab_cases) >= 18
    assert Enum.all?(@tab_cases, &(&1["expect"] in ~w(accept reject)))
  end

  for c <- @tab_cases do
    @case c
    @tag :pending_v124
    test "tab case: #{c["name"]}" do
      c = @case
      meta = c["meta"] && %{admins: c["meta"]["admins"], name_changed: c["meta"]["name_changed"]}

      result =
        Policy.check(%{
          admins: c["admins"],
          agents: c["agents"] ++ c["agent_users"],
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
