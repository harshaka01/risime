defmodule RisiMe.Agent.GoogleLink do
  @moduledoc """
  **The Google Calendar link** (contract v1.31 §31, decision 074).

  The Google token, the account's email, calendar names and ids and event titles and ids live on
  the user's Google device only. The server makes no Google call. It stores one small row per
  user (`risi_gcal_links`): which device holds the link, its state and two counts. Nothing else.

  `state/1` is the `Link` of §31.3: `paused` is computed (there is a link, but the Calendar
  skill is `off`). Every change writes one stored `google_calendar_link` inbox event, delivered
  to the user's `google_calendar` devices only (no push wake). Logs carry the user id, the
  device id and the state only.
  """
  use Ecto.Schema

  import Ecto.Query

  require Logger

  alias RisiMe.{Devices, Messaging, Repo}

  @primary_key {:user_id, :binary_id, autogenerate: false}
  schema "risi_gcal_links" do
    field :device_id, :binary_id
    field :state, :string
    field :read_calendars, :integer
    field :write_calendar, :boolean
    field :mirror, :boolean
    field :connected_at, :utc_datetime_usec
    field :updated_at, :utc_datetime_usec
    field :reconnect_card_at, :utc_datetime_usec
  end

  @put_keys ~w(connect state read_calendars write_calendar mirror)

  @doc "True while the Google link is switched on (`RISI_GCAL=on` and `RISI_EVENTS=on`)."
  def on?, do: RisiMe.Risi.gcal_on?()

  @doc "The user's stored link row (or nil)."
  def get(user_id), do: Repo.get(__MODULE__, user_id)

  @doc "The link when it is `connected` (nil otherwise: no link, `reauth_needed`)."
  def connected(user_id) do
    case get(user_id) do
      %__MODULE__{state: "connected"} = l -> l
      _ -> nil
    end
  end

  @doc "The `Link` of §31.3 (`not_connected` when there is none)."
  def state(user_id) do
    case get(user_id) do
      nil -> not_connected()
      l -> link_json(l)
    end
  end

  defp not_connected do
    %{
      state: "not_connected",
      device_id: nil,
      device_name: nil,
      read_calendars: 0,
      write_calendar: false,
      mirror: false,
      connected_at: nil,
      updated_at: nil
    }
  end

  defp link_json(%__MODULE__{} = l) do
    paused? = RisiMe.Agent.Skills.state(l.user_id, "calendar") == "off"

    %{
      state: if(paused?, do: "paused", else: l.state),
      device_id: l.device_id,
      device_name: device_name(l.device_id),
      read_calendars: l.read_calendars,
      write_calendar: l.write_calendar,
      mirror: l.mirror,
      connected_at: Messaging.iso(l.connected_at),
      updated_at: Messaging.iso(l.updated_at)
    }
  end

  # The name the device registered with (§1.2): its login token's `device_name`.
  defp device_name(device_id) do
    Repo.one(
      from d in Devices.Device,
        join: t in RisiMe.Accounts.UserToken,
        on: t.id == d.user_token_id,
        where: d.device_id == ^device_id,
        select: t.device_name,
        limit: 1
    )
  end

  @doc """
  `PUT` (§31.3). `{:ok, link}` or `{:error, :bad_request | :not_google_device}`. `connect: true`
  makes `device_id` the Google device (an older holder gets `reason: "replaced"`); `connect:
  false` updates the link and only the current Google device may.
  """
  def put(user_id, device_id, body) do
    with {:ok, p} <- parse_put(body) do
      now = DateTime.utc_now()
      old = get(user_id)

      cond do
        p.connect ->
          upsert(user_id, device_id, p, old, now)

        old == nil or old.device_id != device_id ->
          {:error, :not_google_device}

        true ->
          upsert(user_id, device_id, p, old, now)
      end
    end
  end

  defp upsert(user_id, device_id, p, old, now) do
    connected_at = if old && old.device_id == device_id, do: old.connected_at, else: now

    row = %__MODULE__{
      user_id: user_id,
      device_id: device_id,
      state: p.state,
      read_calendars: p.read_calendars,
      write_calendar: p.write_calendar,
      mirror: p.mirror,
      connected_at: connected_at,
      updated_at: now,
      reconnect_card_at: old && old.reconnect_card_at
    }

    {:ok, saved} =
      Repo.insert(row,
        on_conflict:
          {:replace,
           [
             :device_id,
             :state,
             :read_calendars,
             :write_calendar,
             :mirror,
             :connected_at,
             :updated_at
           ]},
        conflict_target: :user_id,
        returning: true
      )

    replaced? = old != nil and old.device_id != device_id

    reason =
      cond do
        replaced? -> "replaced"
        old == nil or old.device_id != device_id -> "connected"
        true -> "updated"
      end

    Logger.info("gcal link #{reason} user=#{user_id} device=#{device_id} state=#{p.state}")
    publish(user_id, link_json(saved), saved.device_id, reason, false, now)

    if p.state == "reauth_needed" and (old == nil or old.state != "reauth_needed"),
      do: RisiMe.Agent.GoogleCards.reconnect(saved)

    {:ok, link_json(get(user_id))}
  end

  defp parse_put(%{} = b) do
    ok? =
      Map.keys(b) -- @put_keys == [] and
        Enum.all?(@put_keys, &Map.has_key?(b, &1)) and
        is_boolean(b["connect"]) and b["state"] in ~w(connected reauth_needed) and
        is_integer(b["read_calendars"]) and b["read_calendars"] in 0..10 and
        is_boolean(b["write_calendar"]) and is_boolean(b["mirror"])

    if ok?,
      do:
        {:ok,
         %{
           connect: b["connect"],
           state: b["state"],
           read_calendars: b["read_calendars"],
           write_calendar: b["write_calendar"],
           mirror: b["mirror"]
         }},
      else: {:error, :bad_request}
  end

  defp parse_put(_), do: {:error, :bad_request}

  @doc """
  `DELETE` (§31.3), from any `google_calendar` device. The Google device is told `disconnected`
  with `remove_copies`. Idempotent. Always `:ok`.
  """
  def disconnect(user_id, remove_copies? \\ false) do
    case get(user_id) do
      nil ->
        :ok

      l ->
        Repo.delete(l)
        Logger.info("gcal link disconnected user=#{user_id} device=#{l.device_id}")
        publish(user_id, not_connected(), l.device_id, "disconnected", remove_copies?)
        :ok
    end
  end

  @doc """
  The device is gone (logout, deletion, pruning): its links go with it, and the user's other
  `google_calendar` devices refresh. Returns the count.
  """
  def device_removed(device_ids) when is_list(device_ids) do
    {_, rows} =
      Repo.delete_all(
        from l in __MODULE__, where: l.device_id in ^device_ids, select: {l.user_id, l.device_id}
      )

    for {user_id, device_id} <- rows do
      Logger.info("gcal link removed with its device user=#{user_id} device=#{device_id}")
      publish(user_id, not_connected(), device_id, "disconnected", false)
    end

    length(rows)
  end

  @doc "Marks the reconnect card as posted (at most one per 24 h)."
  def mark_card(%__MODULE__{} = l, at),
    do:
      Repo.update_all(from(x in __MODULE__, where: x.user_id == ^l.user_id),
        set: [reconnect_card_at: at]
      )

  # §31.3 stored event: `{"state", "device_id", "reason", "remove_copies", "server_ts"}`.
  defp publish(user_id, link, device_id, reason, remove_copies?, now \\ DateTime.utc_now()) do
    Messaging.publish_quiet(user_id, "google_calendar_link", %{
      "state" => link.state,
      "device_id" => device_id,
      "reason" => reason,
      "remove_copies" => remove_copies?,
      "server_ts" => Messaging.iso(now)
    })
  end
end
