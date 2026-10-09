defmodule RisiMeWeb.RisiController do
  @moduledoc """
  `/api/v1/risi/*` (contract v1.24 §24.11): feedback, what Risi knows about me, my promises.
  Everything is scoped to the authenticated user, so there is no device gate: `X-Device-Id` is
  optional and ignored (root integration fix, pilot nightly.39: the app sent none and got 404).
  Works with RISI off.
  """
  use RisiMeWeb, :controller

  alias RisiMe.RateLimiter
  alias RisiMe.Agent.Rest
  alias RisiMeWeb.{ApiError, GroupController}

  @feedback_limit 60
  @window :timer.minutes(1)

  defp me(conn), do: conn.assigns.current_user.id

  def feedback(conn, params) do
    with {:ok, call_ref, rating, reason} <- parse(params),
         :ok <- RateLimiter.hit_if_allowed(:risi_feedback, me(conn), @feedback_limit, @window),
         :ok <- Rest.feedback(me(conn), call_ref, rating, reason) do
      send_resp(conn, 204, "")
    else
      {:error, :rate_limited} ->
        ApiError.send_error(conn, 429, :rate_limited,
          retry_after: RateLimiter.retry_after_s(@window)
        )

      {:error, :bad_request} ->
        ApiError.send_error(conn, 400, :bad_request)

      error ->
        GroupController.error(conn, error)
    end
  end

  defp parse(%{"call_ref" => ref, "rating" => r} = p)
       when is_binary(ref) and r in ["up", "down"] do
    case p["reason"] do
      nil -> {:ok, ref, r, nil}
      s when is_binary(s) and byte_size(s) <= 2_000 -> {:ok, ref, r, s}
      _ -> {:error, :bad_request}
    end
  end

  defp parse(_), do: {:error, :bad_request}

  def facts(conn, _params) do
    json(conn, %{facts: Rest.facts(me(conn))})
  end

  def delete_fact(conn, %{"fact_id" => id}) do
    with :ok <- Rest.delete_fact(me(conn), id) do
      send_resp(conn, 204, "")
    else
      error -> GroupController.error(conn, error)
    end
  end

  def delete_facts(conn, _params) do
    :ok = Rest.delete_facts(me(conn))
    send_resp(conn, 204, "")
  end

  def commitments(conn, params) do
    state = if params["state"] == "all", do: :all, else: :open

    json(conn, %{commitments: Rest.commitments(me(conn), state)})
  end
end
