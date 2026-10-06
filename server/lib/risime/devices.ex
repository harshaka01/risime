defmodule RisiMe.Devices do
  @moduledoc """
  App installs and their push tokens (contract v1.5 §8.1, decision 028).
  """
  import Ecto.Query

  alias RisiMe.Repo
  alias RisiMe.Devices.Device

  @max_per_user 10
  @platforms ~w(android)
  @unseen_days 60

  @doc """
  Registers or updates a device (`PUT /me/devices/{device_id}`). An FCM token belongs to one
  install, so the same token under other device rows is removed. At most #{@max_per_user}
  devices per user; the least recently seen are evicted.
  """
  @spec register(String.t(), term, map, String.t() | nil) :: :ok | {:error, :invalid_device}
  def register(user_id, device_id, params, user_token_id \\ nil) do
    with {:ok, device_id} <- Ecto.UUID.cast(device_id),
         %{"platform" => platform, "push_token" => token} when platform in @platforms <- params,
         true <- is_binary(token) and String.trim(token) != "" and byte_size(token) <= 4096,
         version when is_nil(version) or (is_binary(version) and byte_size(version) <= 64) <-
           params["app_version"] do
      now = DateTime.utc_now()

      Repo.transaction(fn ->
        Repo.delete_all(
          from d in Device,
            where:
              d.push_token == ^token and not (d.user_id == ^user_id and d.device_id == ^device_id)
        )

        Repo.insert!(
          %Device{
            user_id: user_id,
            device_id: device_id,
            platform: platform,
            push_token: token,
            app_version: version,
            user_token_id: user_token_id,
            last_seen_at: now
          },
          on_conflict: [
            set: [
              platform: platform,
              push_token: token,
              app_version: version,
              user_token_id: user_token_id,
              last_seen_at: now,
              updated_at: now
            ]
          ],
          conflict_target: [:user_id, :device_id]
        )

        evict(user_id)
      end)

      :ok
    else
      _ -> {:error, :invalid_device}
    end
  end

  defp evict(user_id) do
    keep =
      from d in Device,
        where: d.user_id == ^user_id,
        order_by: [desc: d.last_seen_at],
        limit: @max_per_user,
        select: d.id

    Repo.delete_all(from d in Device, where: d.user_id == ^user_id and d.id not in subquery(keep))
  end

  @doc "Removes a device (`DELETE /me/devices/{device_id}`). Idempotent; a bad id is `:invalid_device`."
  def delete(user_id, device_id) do
    case Ecto.UUID.cast(device_id) do
      {:ok, id} ->
        Repo.delete_all(from d in Device, where: d.user_id == ^user_id and d.device_id == ^id)
        :ok

      :error ->
        {:error, :invalid_device}
    end
  end

  @doc "Removes the devices registered with a dev token (dev logout)."
  def delete_for_token(user_token_id) do
    {n, _} = Repo.delete_all(from d in Device, where: d.user_token_id == ^user_token_id)
    n
  end

  @doc "Removes every device row holding `push_token` (FCM said it is no longer valid)."
  def delete_push_token(push_token) do
    {n, _} = Repo.delete_all(from d in Device, where: d.push_token == ^push_token)
    n
  end

  @doc "The push tokens of a user's devices."
  def push_tokens(user_id) do
    Repo.all(from d in Device, where: d.user_id == ^user_id, select: d.push_token)
  end

  @doc "Deletes devices not seen (no PUT) for #{@unseen_days} days. Returns the count."
  def prune(now \\ DateTime.utc_now()) do
    cutoff = DateTime.add(now, -@unseen_days, :day)
    {n, _} = Repo.delete_all(from d in Device, where: d.last_seen_at < ^cutoff)
    n
  end
end
