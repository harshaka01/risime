defmodule RisiMeWeb.RisiGoogleController do
  @moduledoc """
  `/api/v1/risi/calendar/google` (contract v1.31 §31.3, `RisiMe.Agent.GoogleLink`). Every call:

    * `503 agent_unavailable` while `RISI_GCAL` (with `RISI_EVENTS`) is off;
    * `403 invalid_device` unless `X-Device-Id` names a `google_calendar` device of the caller;
    * `429 rate_limited` over 30 writes per user per minute.

  Errors: `409 not_google_device`, `422 bad_request`.
  """
  use RisiMeWeb, :controller

  alias RisiMe.Agent.GoogleLink
  alias RisiMe.RateLimiter
  alias RisiMeWeb.{ApiError, MLSController}

  @writes 30
  @window :timer.minutes(1)

  defp me(conn), do: conn.assigns.current_user.id

  defp gate(conn, kind, fun) do
    cond do
      not GoogleLink.on?() ->
        ApiError.send_error(conn, 503, :agent_unavailable)

      not RisiMe.Devices.google_calendar_device?(me(conn), MLSController.caller_device(conn)) ->
        ApiError.send_error(conn, 403, :invalid_device)

      kind == :write and
          RateLimiter.hit_if_allowed(:risi_gcal_w, me(conn), @writes, @window) != :ok ->
        ApiError.send_error(conn, 429, :rate_limited,
          retry_after: RateLimiter.retry_after_s(@window)
        )

      true ->
        fun.()
    end
  end

  def show(conn, _params) do
    gate(conn, :read, fn -> json(conn, %{google: GoogleLink.state(me(conn))}) end)
  end

  def put(conn, _params) do
    gate(conn, :write, fn ->
      device = MLSController.caller_device(conn)

      case GoogleLink.put(me(conn), device, conn.body_params) do
        {:ok, link} -> json(conn, %{google: link})
        {:error, :not_google_device} -> ApiError.send_error(conn, 409, :not_google_device)
        {:error, :bad_request} -> ApiError.send_error(conn, 422, :bad_request)
      end
    end)
  end

  def delete(conn, params) do
    gate(conn, :write, fn ->
      case Map.get(params, "remove_copies", "false") do
        v when v in ["true", "false"] ->
          :ok = GoogleLink.disconnect(me(conn), v == "true")
          send_resp(conn, 204, "")

        _ ->
          ApiError.send_error(conn, 422, :bad_request)
      end
    end)
  end
end
