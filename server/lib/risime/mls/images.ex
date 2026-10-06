defmodule RisiMe.MLS.Images do
  @moduledoc """
  The `images` capability (contract v1.11 §14.1): `images_ready` / `missing_images` on
  `GET /mls/groups/{conversation_id}`. A client hint only; the server can't see message types.

  `images_ready` = the conversation is e2ee and every app instance of every member user (the
  caller's own included) seen in the last 30 days advertises `images`. As for group readiness
  (§12.1), only installs that can still receive count: a census instance of a **registered**
  device (a removed device id can't receive anything), or a device-less (pre-v1.7) instance seen
  after the user's latest device registration. A member with no `images` device and no such
  instance (e.g. one that never connected) is listed with `device_id: null`.
  """
  import Ecto.Query

  alias RisiMe.{Groups, MLS, Repo}
  alias RisiMe.Devices.Device

  @census_days 30

  @doc "Adds `images_ready` and `missing_images` to a `GET /mls/groups/{id}` view."
  def put_readiness(%{e2ee: e2ee} = view, conversation_id) do
    missing = conversation_id |> member_ids() |> missing()
    Map.merge(view, %{images_ready: e2ee and missing == [], missing_images: missing})
  end

  defp member_ids("grp:" <> _ = conv), do: Groups.active_member_ids(conv)

  defp member_ids(conv) do
    case MLS.members(conv) do
      {:ok, ids} -> ids
      :error -> []
    end
  end

  @doc "The instances of `user_ids` that can still receive and don't advertise `images`."
  def missing([]), do: []

  def missing(user_ids) do
    since = DateTime.add(DateTime.utc_now(), -@census_days, :day)

    instances =
      Repo.all(
        from i in "app_instances",
          where: i.user_id in type(^user_ids, {:array, :binary_id}) and i.last_seen_at > ^since,
          order_by: [asc: i.last_seen_at],
          select:
            {type(i.user_id, :binary_id), type(i.device_id, :binary_id),
             type(i.last_seen_at, :utc_datetime_usec)}
      )

    devices =
      Repo.all(
        from d in Device,
          where: d.user_id in ^user_ids,
          select:
            {{d.user_id, d.device_id}, {d.mls_signature_key, d.capabilities, d.last_seen_at}}
      )
      |> Map.new()

    images? = fn ref ->
      case devices[ref] do
        {key, caps, _} when is_binary(key) -> "images" in (caps || [])
        _ -> false
      end
    end

    last_registration =
      Enum.reduce(devices, %{}, fn {{u, _d}, {_, _, t}}, acc ->
        Map.update(acc, u, t, &if(DateTime.compare(t, &1) == :gt, do: t, else: &1))
      end)

    from_instances =
      for {u, d, seen} <- instances,
          receives?(devices, last_registration, u, d, seen),
          not (d != nil and images?.({u, d})),
          do: %{user_id: u, device_id: d}

    listed = MapSet.new(from_instances, & &1.user_id)

    # A member with no `images` device and nothing listed yet (e.g. never connected).
    without =
      for u <- user_ids,
          not MapSet.member?(listed, u),
          not Enum.any?(Map.keys(devices), fn {du, _} = ref -> du == u and images?.(ref) end),
          do: %{user_id: u, device_id: nil}

    Enum.uniq(from_instances ++ without)
  end

  defp receives?(devices, _last, u, d, _seen) when d != nil, do: Map.has_key?(devices, {u, d})

  defp receives?(_devices, last, u, nil, seen),
    do: last[u] == nil or DateTime.compare(seen, last[u]) != :lt
end
