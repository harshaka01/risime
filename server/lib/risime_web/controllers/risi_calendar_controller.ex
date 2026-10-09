defmodule RisiMeWeb.RisiCalendarController do
  @moduledoc """
  `/api/v1/risi/calendar…` (contract v1.29 §29.3, `RisiMe.Agent.Calendar`). Every call:

    * `503 agent_unavailable` while `RISI_EVENTS` is off (and when the rows can't be opened);
    * `403 invalid_device` unless `X-Device-Id` names a `risi_events` device of the caller;
    * `429 rate_limited` over 60 writes or 120 reads per user per minute (§29.12).

  Errors: `403 not_owner`, `404 not_found`, `409 version_conflict` (with the current
  `event`), `410 cursor_expired`, `422 bad_request`, `422 not_invitable`.
  """
  use RisiMeWeb, :controller

  alias RisiMe.Agent.Calendar
  alias RisiMe.RateLimiter
  alias RisiMeWeb.{ApiError, GroupController, MLSController}

  @writes 60
  @reads 120
  @window :timer.minutes(1)

  defp me(conn), do: conn.assigns.current_user.id

  defp gate(conn, kind, fun) do
    {bucket, limit} = if kind == :write, do: {:risi_cal_w, @writes}, else: {:risi_cal_r, @reads}

    cond do
      not Calendar.on?() ->
        ApiError.send_error(conn, 503, :agent_unavailable)

      not RisiMe.Devices.risi_events_device?(me(conn), MLSController.caller_device(conn)) ->
        ApiError.send_error(conn, 403, :invalid_device)

      RateLimiter.hit_if_allowed(bucket, me(conn), limit, @window) != :ok ->
        ApiError.send_error(conn, 429, :rate_limited,
          retry_after: RateLimiter.retry_after_s(@window)
        )

      true ->
        fun.()
    end
  end

  def index(conn, params) do
    gate(conn, :read, fn ->
      case Calendar.list(me(conn), params["from"], params["to"]) do
        {:ok, reply} -> json(conn, reply)
        error -> error(conn, error)
      end
    end)
  end

  def changes(conn, params) do
    gate(conn, :read, fn ->
      case Calendar.changes(me(conn), params["since"], params["limit"]) do
        {:ok, reply} -> json(conn, reply)
        error -> error(conn, error)
      end
    end)
  end

  def show(conn, %{"id" => id}) do
    gate(conn, :read, fn ->
      case Calendar.get(me(conn), id) do
        {:ok, event} -> json(conn, %{event: event})
        error -> error(conn, error)
      end
    end)
  end

  def create(conn, _params) do
    gate(conn, :write, fn ->
      case Calendar.create_rest(me(conn), conn.body_params) do
        {:ok, :created, event} -> conn |> put_status(201) |> json(%{event: event})
        {:ok, :existing, event} -> json(conn, %{event: event})
        error -> error(conn, error)
      end
    end)
  end

  def update(conn, %{"id" => id}) do
    gate(conn, :write, fn ->
      case Calendar.patch(me(conn), id, conn.body_params) do
        {:ok, event} -> json(conn, %{event: event})
        error -> error(conn, error)
      end
    end)
  end

  def delete(conn, %{"id" => id}) do
    gate(conn, :write, fn ->
      case Calendar.delete(me(conn), id) do
        :ok -> send_resp(conn, 204, "")
        error -> error(conn, error)
      end
    end)
  end

  def respond(conn, %{"id" => id}) do
    gate(conn, :write, fn ->
      case Calendar.respond(me(conn), id, conn.body_params) do
        {:ok, event} -> json(conn, %{event: event})
        error -> error(conn, error)
      end
    end)
  end

  def resolve(conn, %{"suggestion_id" => id}) do
    gate(conn, :write, fn ->
      case Calendar.resolve(me(conn), id, conn.body_params) do
        {:ok, event} -> json(conn, %{event: event})
        error -> error(conn, error)
      end
    end)
  end

  def settings(conn, _params) do
    gate(conn, :read, fn -> json(conn, %{settings: Calendar.settings(me(conn))}) end)
  end

  def update_settings(conn, _params) do
    gate(conn, :write, fn ->
      case Calendar.put_settings(me(conn), conn.body_params) do
        {:ok, s} -> json(conn, %{settings: s})
        error -> error(conn, error)
      end
    end)
  end

  def delete_all(conn, _params) do
    gate(conn, :write, fn ->
      :ok = Calendar.delete_all(me(conn))
      send_resp(conn, 204, "")
    end)
  end

  defp error(conn, {:error, {:version_conflict, event}}),
    do: ApiError.send_error(conn, 409, :version_conflict, extra: [event: event])

  defp error(conn, {:error, :bad_request}), do: ApiError.send_error(conn, 422, :bad_request)
  defp error(conn, {:error, :not_owner}), do: ApiError.send_error(conn, 403, :not_owner)

  defp error(conn, {:error, :cursor_expired}),
    do: ApiError.send_error(conn, 410, :cursor_expired)

  defp error(conn, {:error, :not_invitable}),
    do: ApiError.send_error(conn, 422, :not_invitable)

  defp error(conn, error), do: GroupController.error(conn, error)
end
