defmodule RisiMeWeb.SocialController do
  @moduledoc "Invites, friends and blocks (contract v1.6 §9.1, §9.2)."
  use RisiMeWeb, :controller

  alias RisiMe.Social
  alias RisiMeWeb.ApiError

  defp me(conn), do: conn.assigns.current_user

  ## Invites

  def create_invite(conn, params) do
    case Social.create_invite(me(conn), params) do
      {:ok, invite} ->
        conn |> put_status(201) |> json(%{invite: Social.invite_json(invite)})

      {:error, {:rate_limited, seconds}} ->
        ApiError.send_error(conn, 429, :rate_limited, retry_after: seconds)

      {:error, code} ->
        ApiError.send_error(conn, 422, code)
    end
  end

  def list_invites(conn, _params) do
    json(conn, %{invites: Enum.map(Social.list_invites(me(conn)), &Social.invite_json/1)})
  end

  def revoke_invite(conn, %{"id" => id}) do
    :ok = Social.revoke_invite(me(conn), id)
    send_resp(conn, 204, "")
  end

  ## Friends

  def request(conn, params) do
    case Social.request(me(conn), params["phone"]) do
      :ok ->
        conn |> put_status(202) |> json(%{status: "requested"})

      {:error, :invalid_phone} ->
        ApiError.send_error(conn, 422, :invalid_phone)

      {:error, {:rate_limited, seconds}} ->
        ApiError.send_error(conn, 429, :rate_limited, retry_after: seconds)
    end
  end

  def index(conn, _params), do: json(conn, Social.list(me(conn)))

  def accept(conn, %{"id" => id}) do
    case Social.accept(me(conn), id) do
      {:ok, friend} -> json(conn, %{friend: friend})
      {:error, :not_found} -> ApiError.send_error(conn, 404, :not_found)
    end
  end

  def decline(conn, %{"id" => id}), do: no_content(conn, Social.decline(me(conn), id))
  def cancel(conn, %{"id" => id}), do: no_content(conn, Social.cancel(me(conn), id))

  def unfriend(conn, %{"user_id" => user_id}) do
    :ok = Social.unfriend(me(conn), user_id)
    send_resp(conn, 204, "")
  end

  ## Blocks

  def block(conn, params) do
    :ok = Social.block(me(conn), params["user_id"])
    send_resp(conn, 204, "")
  end

  def unblock(conn, %{"user_id" => user_id}) do
    :ok = Social.unblock(me(conn), user_id)
    send_resp(conn, 204, "")
  end

  defp no_content(conn, :ok), do: send_resp(conn, 204, "")
  defp no_content(conn, {:error, :not_found}), do: ApiError.send_error(conn, 404, :not_found)
end
