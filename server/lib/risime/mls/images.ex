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

  The `deletes` capability (v1.12 §15.1) is computed the same way into `deletes_ready` /
  `missing_deletes`, also for plaintext DMs (`deletes_ready` doesn't require e2ee; an instance
  without MLS capabilities counts as missing).
  """
  import Ecto.Query

  alias RisiMe.{Groups, MLS, Repo}
  alias RisiMe.Devices.Device

  @census_days 30

  @doc """
  Adds `images_ready`/`missing_images` (§14.1) and `deletes_ready`/`missing_deletes` (§15.1) to
  a `GET /mls/groups/{id}` view.
  """
  def put_readiness(%{e2ee: e2ee} = view, conversation_id, caller \\ nil) do
    members = member_ids(conversation_id)
    missing = missing(members, "images")
    missing_deletes = missing(members, "deletes")

    view
    |> Map.merge(%{
      images_ready: e2ee and missing == [],
      missing_images: missing,
      deletes_ready: missing_deletes == [],
      missing_deletes: missing_deletes
    })
    |> put_calls(conversation_id, members)
    |> put_group_calls(conversation_id, members, caller)
  end

  # v1.19 §20.1: `group_calls_ready` when the caller's user and at least one other active member
  # have a `group_calls` device that can still receive (superseded devices never count);
  # `missing_group_calls` lists the members' instances without it (information only).
  # v1.33: also false while the server has no LiveKit configured.
  defp put_group_calls(view, "grp:" <> _, members, caller) do
    ready = ready_users(members, "group_calls", skip_superseded: true)
    livekit? = RisiMe.Calls.LiveKit.configured?()

    view
    |> Map.merge(%{
      group_calls_ready:
        livekit? and caller != nil and MapSet.member?(ready, caller) and
          Enum.any?(members, &(&1 != caller and MapSet.member?(ready, &1))),
      missing_group_calls: missing(members, "group_calls")
    })
    |> put_unavailable(livekit?)
  end

  defp put_group_calls(view, _conv, _members, _caller), do: view

  # v1.33 §20.1: without LiveKit (`POST /calls/rooms` would answer 503 calls_unavailable)
  # `group_calls_ready` is false and the view says why.
  defp put_unavailable(view, true), do: view
  defp put_unavailable(view, false), do: Map.put(view, :group_calls_unavailable, "server")

  # v1.13 §16.1 (DMs only): `calls_ready` when the DM is e2ee and each of the two users has at
  # least one instance seen in the last 30 days that advertises `calls` (a device without it
  # just doesn't ring); `missing_calls` lists the other instances. v1.18 §19.1: `video_ready` /
  # `missing_video` computed the same way for `video`.
  defp put_calls(%{e2ee: e2ee} = view, "dm:" <> _, members) do
    ready = ready_users(members, "calls")
    video = ready_users(members, "video", skip_superseded: true)
    all? = fn set -> e2ee and members != [] and Enum.all?(members, &MapSet.member?(set, &1)) end

    Map.merge(view, %{
      calls_ready: all?.(ready),
      missing_calls: missing(members, "calls"),
      video_ready: all?.(video),
      missing_video: missing(members, "video")
    })
  end

  defp put_calls(view, _conv, _members), do: view

  @doc """
  The users among `user_ids` with at least one census instance seen in the last 30 days on a
  registered MLS device that advertises `capability` (`skip_superseded: true`: not counting
  superseded devices).
  """
  def ready_users(user_ids, capability, opts \\ []) do
    since = DateTime.add(DateTime.utc_now(), -@census_days, :day)

    # v1.18 §19.1, v1.19 §20.1: superseded devices (a reinstall's old id) never count (§12.1).
    # `calls_ready` (v1.13) keeps its original rule.
    superseded =
      if opts[:skip_superseded], do: MLS.superseded_devices(user_ids), else: MapSet.new()

    Repo.all(
      from i in "app_instances",
        join: d in Device,
        on: d.user_id == i.user_id and d.device_id == i.device_id,
        where:
          i.user_id in type(^user_ids, {:array, :binary_id}) and i.last_seen_at > ^since and
            not is_nil(d.mls_signature_key) and ^capability in d.capabilities,
        distinct: true,
        select: {d.user_id, d.device_id}
    )
    |> Enum.reject(&MapSet.member?(superseded, &1))
    |> MapSet.new(&elem(&1, 0))
  end

  defp member_ids("grp:" <> _ = conv), do: Groups.active_member_ids(conv)

  defp member_ids(conv) do
    case MLS.members(conv) do
      {:ok, ids} -> ids
      :error -> []
    end
  end

  @doc "The instances of `user_ids` that can still receive and don't advertise `capability`."
  def missing(user_ids, capability \\ "images")
  def missing([], _capability), do: []

  def missing(user_ids, capability) do
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

    capable? = fn ref ->
      case devices[ref] do
        {key, caps, _} when is_binary(key) -> capability in (caps || [])
        _ -> false
      end
    end

    last_registration =
      Enum.reduce(devices, %{}, fn {{u, _d}, {_, _, t}}, acc ->
        Map.update(acc, u, t, &if(DateTime.compare(t, &1) == :gt, do: t, else: &1))
      end)

    superseded = MLS.superseded_devices(user_ids)

    from_instances =
      for {u, d, seen} <- instances,
          receives?(devices, last_registration, u, d, seen),
          not MapSet.member?(superseded, {u, d}),
          not (d != nil and capable?.({u, d})),
          do: %{user_id: u, device_id: d}

    listed = MapSet.new(from_instances, & &1.user_id)

    # A member with no capable device and nothing listed yet (e.g. never connected).
    without =
      for u <- user_ids,
          not MapSet.member?(listed, u),
          not Enum.any?(Map.keys(devices), fn {du, _} = ref -> du == u and capable?.(ref) end),
          do: %{user_id: u, device_id: nil}

    Enum.uniq(from_instances ++ without)
  end

  defp receives?(devices, _last, u, d, _seen) when d != nil, do: Map.has_key?(devices, {u, d})

  defp receives?(_devices, last, u, nil, seen),
    do: last[u] == nil or DateTime.compare(seen, last[u]) != :lt
end
