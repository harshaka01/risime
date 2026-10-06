defmodule RisiMe.LoadTestTest do
  use RisiMe.DataCase, async: true

  alias RisiMe.Accounts
  alias RisiMe.LoadTest

  test "creates throwaway users with working tokens and cleans them all up" do
    users = LoadTest.create_users(3)
    assert length(users) == 3

    for u <- users do
      assert {%{id: id, phone: "+999" <> _}, _} = Accounts.fetch_by_token(u.token)
      assert id == u.id
    end

    :ok = LoadTest.befriend_ring(users)
    [a, b, _] = users
    assert RisiMe.Social.friends?(a.id, b.id)

    # --e2ee: MLS devices and e2ee groups for the ring, removed by cleanup.
    [ea | _] = LoadTest.prepare_e2ee(users)
    assert is_binary(ea.device_id)
    assert RisiMe.MLS.e2ee?(RisiMe.Messaging.conversation_id(a.id, b.id))

    assert LoadTest.cleanup() == {3, 3}
    refute RisiMe.MLS.e2ee?(RisiMe.Messaging.conversation_id(a.id, b.id))
    for u <- users, do: refute(Accounts.get_user(u.id))
  end

  test "percentiles in ms" do
    assert LoadTest.percentiles([]) == %{p50: nil, p95: nil, p99: nil, max: nil}
    samples = Enum.map(1..100, &(&1 * 1000))
    assert LoadTest.percentiles(samples) == %{p50: 50.0, p95: 95.0, p99: 99.0, max: 100.0}
  end
end
