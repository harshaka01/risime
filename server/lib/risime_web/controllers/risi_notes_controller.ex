defmodule RisiMeWeb.RisiNotesController do
  @moduledoc """
  `/api/v1/risi/notes…` (contract v1.29 §30.6, `RisiMe.Agent.Notes`). Every call:

    * `503 agent_unavailable` while `RISI_NOTES` is off (and when the notes can't be opened);
    * `403 invalid_device` unless `X-Device-Id` names a `risi_notes` device of the caller;
    * `429 rate_limited` over 60 reads or 30 deletes per user per minute (§30.7).

  Errors: `404 not_found`, `422 bad_request`.
  """
  use RisiMeWeb, :controller

  alias RisiMe.Agent.Notes
  alias RisiMe.RateLimiter
  alias RisiMeWeb.{ApiError, GroupController, MLSController}

  @reads 60
  @deletes 30
  @window :timer.minutes(1)

  defp me(conn), do: conn.assigns.current_user.id

  defp gate(conn, kind, fun) do
    {bucket, limit} =
      if kind == :delete, do: {:risi_notes_d, @deletes}, else: {:risi_notes_r, @reads}

    cond do
      not Notes.on?() ->
        ApiError.send_error(conn, 503, :agent_unavailable)

      not RisiMe.Devices.risi_notes_device?(me(conn), MLSController.caller_device(conn)) ->
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
      case Notes.list(me(conn), params) do
        {:ok, reply} -> json(conn, reply)
        error -> error(conn, error)
      end
    end)
  end

  def show(conn, %{"id" => id}) do
    gate(conn, :read, fn ->
      case Notes.get(me(conn), id) do
        {:ok, note} -> json(conn, %{note: note})
        error -> error(conn, error)
      end
    end)
  end

  def delete(conn, %{"id" => id}) do
    gate(conn, :delete, fn ->
      case Notes.delete(me(conn), id) do
        :ok -> send_resp(conn, 204, "")
        error -> error(conn, error)
      end
    end)
  end

  def delete_all(conn, _params) do
    gate(conn, :delete, fn ->
      :ok = Notes.delete_all(me(conn))
      send_resp(conn, 204, "")
    end)
  end

  defp error(conn, {:error, :bad_request}), do: ApiError.send_error(conn, 422, :bad_request)
  defp error(conn, error), do: GroupController.error(conn, error)
end
