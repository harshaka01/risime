defmodule RisiMe.TabsHelpers do
  @moduledoc "v1.24 §24 test helpers: `tabs` devices, Risi on, Official groups."
  import ExUnit.Callbacks, only: [on_exit: 1]

  alias RisiMe.Repo

  @tabs_caps ["groups", "member_devices", "tabs"]

  @doc "Registers a `tabs` device (groups + member_devices + tabs). Returns its id."
  def tabs_device!(user, opts \\ []) do
    device_id = Keyword.get(opts, :device_id, Ecto.UUID.generate())
    user_id = id_of(user)

    params = %{
      "platform" => "android",
      "mls" => %{
        "signature_key" => Base.encode64(:crypto.strong_rand_bytes(32)),
        "capabilities" => Keyword.get(opts, :caps, @tabs_caps)
      }
    }

    params =
      if t = opts[:push_token], do: Map.put(params, "push_token", t), else: params

    {:ok, att} = RisiMe.Devices.register(user_id, device_id, params)
    true = is_binary(att)
    :ok = RisiMe.MLS.record_instance(user_id, device_id, nil, "0.3.0-test")
    device_id
  end

  @doc "A groups-only (pre-v1.24) device, optionally with a push token."
  def old_device!(user, opts \\ []),
    do: tabs_device!(user, Keyword.put(opts, :caps, ["groups", "member_devices"]))

  @doc """
  `RISI=on`, the agent seeded, its device given an MLS key and `n` key packages. Returns
  `%{user_id, device_id}`.
  """
  def risi_on!(n \\ 5) do
    Application.put_env(:risime, :risi, true)
    on_exit(fn -> Application.delete_env(:risime, :risi) end)
    :ok = RisiMe.Risi.seed()

    d = Repo.get_by!(RisiMe.Devices.Device, device_id: RisiMe.Risi.device_id())

    d
    |> Ecto.Changeset.change(
      mls_signature_key: :crypto.strong_rand_bytes(32),
      mls_attestation: "attestation.of.risi"
    )
    |> Repo.update!()

    if n > 0 do
      :ok =
        RisiMe.MLS.upload_key_packages(RisiMe.Risi.user_id(), RisiMe.Risi.device_id(), %{
          "key_packages" => for(_ <- 1..n, do: kp())
        })
    end

    %{user_id: RisiMe.Risi.user_id(), device_id: RisiMe.Risi.device_id()}
  end

  @doc "`TABS=on` for the test."
  def tabs_on! do
    Application.put_env(:risime, :tabs, true)
    on_exit(fn -> Application.delete_env(:risime, :tabs) end)
    :ok
  end

  @doc "A contract example, decoded."
  def example(name),
    do:
      Path.expand("../../../contract/v1/examples/" <> name, __DIR__)
      |> File.read!()
      |> Jason.decode!()

  defp kp, do: Base.encode64(:crypto.strong_rand_bytes(64))

  @doc """
  An active Official group inserted directly (no REST), with the given `{user_id, role}` members
  (plus `agents` as agent members) and MLS state at epoch 1 holding `leaves`. For filter tests.
  """
  def official_group!(chat_id, members, opts \\ []) do
    id = "grp:" <> Ecto.UUID.generate()
    now = DateTime.utc_now()
    [{creator, _} | _] = members

    Repo.insert!(%RisiMe.Groups.Group{
      id: id,
      created_by: creator,
      client_group_id: Ecto.UUID.generate(),
      state: "active",
      generation: 1,
      created_at: now,
      chat_id: chat_id,
      tab: "official",
      chat_kind: Keyword.get(opts, :chat_kind, "group")
    })

    RisiMe.Groups.Tabs.put(id, "official")

    rows =
      for({u, role} <- members, do: member(id, u, role, "user", now)) ++
        for u <- Keyword.get(opts, :agents, []), do: member(id, u, "member", "agent", now)

    Repo.insert_all(RisiMe.Groups.Member, rows)

    Repo.insert_all("mls_groups", [
      %{conversation_id: id, generation: 1, epoch: 1, e2ee_since: now, updated_at: now}
    ])

    RisiMe.MLS.set_group_devices(id, Keyword.get(opts, :leaves, []), [])
    id
  end

  defp member(id, u, role, kind, now),
    do: %{
      group_id: id,
      user_id: u,
      role: role,
      kind: kind,
      state: "active",
      joined_at: now,
      inserted_at: now
    }

  defp id_of(%{user: %{id: id}}), do: id
  defp id_of(%{id: id}), do: id
  defp id_of(id) when is_binary(id), do: id
end
