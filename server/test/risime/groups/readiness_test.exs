defmodule RisiMe.Groups.ReadinessTest do
  @moduledoc """
  §12.1 group readiness: only installs that can still receive count (registered devices seen in
  the census window); an instance without a device_id never blocks on its own.
  """
  use RisiMe.DataCase, async: false

  import Ecto.Query, only: [from: 2]
  import RisiMe.GroupHelpers
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]

  alias RisiMe.{Groups, MLS}

  setup :with_attestation_key

  defp age!(user_id, key, seconds) do
    Repo.update_all(
      from(i in "app_instances",
        where: i.user_id == type(^user_id, :binary_id) and i.instance_key == ^key
      ),
      set: [last_seen_at: DateTime.add(DateTime.utc_now(), -seconds, :second)]
    )
  end

  defp ready?(u), do: u.id in Groups.ready_set([u.id])

  test "old instance without device_id + current groups-capable device ⇒ ready" do
    u = fast_user!()
    groups_device!(u)
    :ok = MLS.record_instance(u.id, nil, "jwt", nil)
    :ok = MLS.record_instance(u.id, nil, "token:1", "0.1.0")
    assert {ready, []} = Groups.readiness([u.id])
    assert u.id in ready
  end

  test "an unregistered device_id instance (replaced install) doesn't block" do
    u = fast_user!()
    groups_device!(u)
    :ok = MLS.record_instance(u.id, Ecto.UUID.generate(), nil, "0.2.0")
    assert ready?(u)
  end

  test "a registered install on an old build blocks, listed with its device_id" do
    u = fast_user!()
    groups_device!(u)
    old = Ecto.UUID.generate()

    {:ok, nil} =
      RisiMe.Devices.register(u.id, old, %{"platform" => "android", "push_token" => "t-#{old}"})

    :ok = MLS.record_instance(u.id, old, nil, "0.2.0")
    assert {ready, [%{device_id: ^old, reason: "legacy_app"}]} = Groups.readiness([u.id])
    refute u.id in ready
  end

  test "legacy_app without device_id only for a not-ready user whose newest instance it is" do
    u = fast_user!()
    # No device at all, newest instance is a pre-v1.7 build.
    :ok = MLS.record_instance(u.id, nil, "jwt", nil)
    assert {_, [%{device_id: nil, reason: "legacy_app"}]} = Groups.readiness([u.id])

    # A newer device_id instance (not registered yet): no_mls, the old build isn't the cause.
    d = Ecto.UUID.generate()
    age!(u.id, "legacy:jwt", 60)
    :ok = MLS.record_instance(u.id, d, nil, "0.3.0")
    assert {_, [%{device_id: nil, reason: "no_mls"}]} = Groups.readiness([u.id])
  end

  test "re-registering a device supersedes older instances without a device_id" do
    u = fast_user!()
    mls = Ecto.UUID.generate()
    # An MLS device without `groups`: not ready (legacy_app with its id).
    {:ok, _} =
      RisiMe.Devices.register(u.id, mls, %{
        "platform" => "android",
        "mls" => %{"signature_key" => Base.encode64(:crypto.strong_rand_bytes(32))}
      })

    :ok = MLS.record_instance(u.id, nil, "jwt", nil)
    {_, missing} = Groups.readiness([u.id])
    assert Enum.any?(missing, &match?(%{device_id: nil, reason: "legacy_app"}, &1))

    # The device re-registers after the old build was last seen: the legacy row is superseded.
    age!(u.id, "legacy:jwt", 60)
    groups_device!(u, mls)
    assert {_, []} = Groups.readiness([u.id])
    assert ready?(u)
  end
end
