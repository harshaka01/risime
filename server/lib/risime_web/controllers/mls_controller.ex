defmodule RisiMeWeb.MLSController do
  @moduledoc """
  E2EE endpoints (contract v1.7 §10). The calling device is identified by the `X-Device-Id`
  header (required for commits, optional for claims; decision 034).
  """
  use RisiMeWeb, :controller

  alias RisiMe.MLS
  alias RisiMe.MLS.Attestation
  alias RisiMeWeb.ApiError

  defp me(conn), do: conn.assigns.current_user.id

  @doc false
  def caller_device(conn) do
    case get_req_header(conn, "x-device-id") do
      [id] ->
        case Ecto.UUID.cast(id),
          do: (
            {:ok, id} -> id
            :error -> nil
          )

      _ ->
        nil
    end
  end

  def attestation_keys(conn, _params) do
    if Attestation.available?(),
      do: json(conn, %{keys: Attestation.public_keys()}),
      else: ApiError.send_error(conn, 503, :mls_unavailable)
  end

  def upload_key_packages(conn, %{"device_id" => device_id} = params) do
    case MLS.upload_key_packages(me(conn), device_id, params) do
      :ok -> send_resp(conn, 204, "")
      error -> error(conn, error)
    end
  end

  def key_package_count(conn, %{"device_id" => device_id}) do
    case MLS.key_package_count(me(conn), device_id) do
      {:ok, n} -> json(conn, %{count: n})
      error -> error(conn, error)
    end
  end

  def claim(conn, params) do
    case MLS.claim(me(conn), params["user_ids"], caller_device(conn), params["conversation_id"]) do
      {:ok, devices} -> json(conn, %{devices: devices})
      error -> error(conn, error)
    end
  end

  def group(conn, %{"conversation_id" => conv}) do
    case MLS.group_view(me(conn), conv) do
      {:ok, view} -> json(conn, RisiMe.MLS.Images.put_readiness(view, conv, me(conn)))
      error -> error(conn, error)
    end
  end

  def commit(conn, %{"conversation_id" => conv} = params) do
    case MLS.commit(me(conn), caller_device(conn), conv, params) do
      {:ok, epoch} -> json(conn, %{epoch: epoch})
      error -> error(conn, error)
    end
  end

  def commits(conn, %{"conversation_id" => conv} = params) do
    since =
      case Integer.parse(params["since_epoch"] || "0") do
        {n, ""} -> n
        _ -> 0
      end

    limit =
      case Integer.parse(params["limit"] || "") do
        {n, ""} -> n
        _ -> nil
      end

    case MLS.commits_since(me(conn), conv, since, limit) do
      {:ok, commits, has_more} -> json(conn, %{commits: commits, has_more: has_more})
      error -> error(conn, error)
    end
  end

  @doc "`POST /mls/groups/{dm}/rejoin` (v1.16, proposal 2026-10-07-dm-device-readd §4)."
  def rejoin(conn, %{"conversation_id" => conv}) do
    case MLS.rejoin_dm(me(conn), caller_device(conn), conv) do
      {:ok, op, candidates} -> conn |> put_status(202) |> json(%{op: op, candidates: candidates})
      error -> error(conn, error)
    end
  end

  @doc """
  `POST /mls/groups/grp:…/reset` (v1.9 §12.8, admins); for a DM (v1.16 §3) any participant's
  current MLS device.
  """
  def reset(conn, %{"conversation_id" => "dm:" <> _ = conv} = params) do
    case MLS.reset_dm(me(conn), caller_device(conn), conv, params) do
      {:ok, generation} -> json(conn, %{generation: generation})
      error -> error(conn, error)
    end
  end

  def reset(conn, %{"conversation_id" => conv} = params) do
    case RisiMe.Groups.Commit.reset(me(conn), caller_device(conn), conv, params) do
      {:ok, generation} -> json(conn, %{generation: generation})
      error -> error(conn, error)
    end
  end

  # v1.9: group errors (403 invalid_device, the group not_ready message, …) for grp: ids.
  defp error(%{path_params: %{"conversation_id" => "grp:" <> _}} = conn, error),
    do: RisiMeWeb.GroupController.error(conn, error)

  defp error(%{body_params: %{"conversation_id" => "grp:" <> _}} = conn, error),
    do: RisiMeWeb.GroupController.error(conn, error)

  defp error(conn, {:error, :mls_unavailable}),
    do: ApiError.send_error(conn, 503, :mls_unavailable)

  defp error(conn, {:error, :invalid_device}), do: ApiError.send_error(conn, 422, :invalid_device)
  defp error(conn, {:error, :not_friends}), do: ApiError.send_error(conn, 403, :not_friends)
  defp error(conn, {:error, :not_found}), do: ApiError.send_error(conn, 404, :not_found)

  defp error(conn, {:error, :rate_limited}),
    do: ApiError.send_error(conn, 429, :rate_limited, retry_after: 60)

  defp error(conn, {:error, {:epoch_conflict, epoch}}),
    do: ApiError.send_error(conn, 409, :epoch_conflict, extra: [epoch: epoch])

  defp error(conn, {:error, {:not_ready, missing}}),
    do: ApiError.send_error(conn, 409, :not_ready, extra: [missing: missing])

  defp error(conn, {:error, {:generation_conflict, g}}),
    do: ApiError.send_error(conn, 409, :generation_conflict, extra: [generation: g])

  defp error(conn, {:error, :log_expired}), do: ApiError.send_error(conn, 410, :log_expired)
  defp error(conn, {:error, :not_admin}), do: ApiError.send_error(conn, 403, :not_admin)
  defp error(conn, {:error, :not_member}), do: ApiError.send_error(conn, 403, :not_member)

  defp error(conn, {:error, :too_many_devices}),
    do: ApiError.send_error(conn, 422, :too_many_devices)

  defp error(conn, _), do: ApiError.send_error(conn, 400, :bad_request)
end
