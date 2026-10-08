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

  # v1.24 §24.1 tab rules: the same Policy with `tab`, `conversation`, `agent_users`, the full
  # base-epoch leaf list and the extended meta (tab, chat_id_changed, agents).
  @tab_cases Map.fetch!(@fixture_json, "tab_cases")

  test "the fixture has tab_cases (v1.24 §24.1)" do
    assert length(@tab_cases) >= 18
    assert Enum.all?(@tab_cases, &(&1["expect"] in ~w(accept reject)))
  end

  for c <- @tab_cases do
    @case c
    test "tab case: #{c["name"]}" do
      c = @case

      meta =
        c["meta"] &&
          %{
            admins: c["meta"]["admins"],
            name_changed: c["meta"]["name_changed"],
            tab: c["meta"]["tab"],
            chat_id_changed: c["meta"]["chat_id_changed"],
            agents: c["meta"]["agents"]
          }

      result =
        Policy.check(%{
          admins: c["admins"],
          agents: c["agents"],
          agent_users: c["agent_users"],
          tab: c["tab"],
          conversation: c["conversation"],
          committer: user(c["committer"]),
          adds: Enum.map(c["adds"], &leaf/1),
          removes: Enum.map(c["removes"], &leaf/1),
          leaf_users: for({u, [_ | _]} <- c["leaves"], do: u),
          leaves: for({u, ds} <- c["leaves"], d <- ds, do: {u, d}),
          meta: meta
        })

      case c["expect"] do
        "accept" -> assert result == :ok, inspect(result)
        "reject" -> assert {:error, _} = result
      end
    end
  end

  test "an agent leaf in Private or a dm: is private_tab" do
    base = %{
      admins: ["A"],
      agents: [],
      agent_users: ["R"],
      committer: "A",
      adds: [{"R", "r1"}],
      removes: [],
      leaf_users: ["A"],
      leaves: [{"A", "a1"}],
      meta: nil
    }

    assert Policy.check(Map.put(base, :tab, "private")) == {:error, :private_tab}

    assert Policy.check(Map.merge(base, %{tab: "official", conversation: "dm", agents: ["R"]})) ==
             {:error, :private_tab}

    assert Policy.check(Map.merge(base, %{tab: "official", agents: ["R"]})) == :ok
  end

  test "a member's agent removal may not add another user's device" do
    c = %{
      admins: ["A"],
      agents: ["R"],
      tab: "official",
      committer: "B",
      adds: [{"C", "c2"}],
      removes: [{"R", "r1"}],
      leaf_users: ["A", "B", "C", "R"],
      leaves: [{"A", "a1"}, {"B", "b1"}, {"C", "c1"}, {"R", "r1"}],
      meta: nil
    }

    assert {:error, _} = Policy.check(c)
    assert Policy.check(%{c | adds: []}) == :ok
  end
end
