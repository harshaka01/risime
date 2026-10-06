defmodule RisiMeWeb.CallsController do
  @moduledoc "`GET /api/v1/calls/turn` (contract v1.13 §16.7): TURN REST credentials."
  use RisiMeWeb, :controller

  alias RisiMe.RateLimiter
  alias RisiMe.Calls.Turn
  alias RisiMeWeb.ApiError

  @limit 20
  @window :timer.hours(1)

  # 20 per user per hour, counted only for allowed requests (polling while limited doesn't
  # extend the lockout). Nothing about the credential is logged.
  def turn(conn, _params) do
    user_id = conn.assigns.current_user.id

    case Turn.config() do
      {:error, :calls_unavailable} ->
        ApiError.send_error(conn, 503, :calls_unavailable)

      {:ok, _, _, _} ->
        case RateLimiter.hit_if_allowed(:calls_turn, user_id, @limit, @window) do
          :ok ->
            {:ok, reply} = Turn.credentials()
            json(conn, reply)

          {:error, :rate_limited} ->
            ApiError.send_error(conn, 429, :rate_limited,
              retry_after: RateLimiter.retry_after_s(@window)
            )
        end
    end
  end
end
