defmodule RisiMeWeb.RisiController do
  @moduledoc """
  `/api/v1/risi/*` (contract v1.24 §24.11): feedback, what Risi knows about me, my promises.
  Like `GET /chats`, every call needs `X-Device-Id` naming a `tabs` device of the caller
  (`404` for the reads, `403 invalid_device` for the mutating calls). Works with RISI off.
  """
  use RisiMeWeb, :controller

  alias RisiMe.{Devices, RateLimiter}
  alias RisiMe.Agent.Rest
  alias RisiMeWeb.{ApiError, GroupController, MLSController}

  @feedback_limit 60
  @window :timer.minutes(1)

  defp me(conn), do: conn.assigns.current_user.id

  defp tabs?(conn), do: Devices.tabs_device?(me(conn), MLSController.caller_device(conn))

  def feedback(conn, params) do
    with true <- tabs?(conn) || {:error, :invalid_device},
         {:ok, call_ref, rating, reason} <- parse(params),
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
    if tabs?(conn),
      do: json(conn, %{facts: Rest.facts(me(conn))}),
      else: GroupController.error(conn, {:error, :not_found})
  end

  def delete_fact(conn, %{"fact_id" => id}) do
    with true <- tabs?(conn) || {:error, :invalid_device},
         :ok <- Rest.delete_fact(me(conn), id) do
      send_resp(conn, 204, "")
    else
      error -> GroupController.error(conn, error)
    end
  end

  def delete_facts(conn, _params) do
    if tabs?(conn) do
      :ok = Rest.delete_facts(me(conn))
      send_resp(conn, 204, "")
    else
      GroupController.error(conn, {:error, :invalid_device})
    end
  end

  def commitments(conn, params) do
    state = if params["state"] == "all", do: :all, else: :open

    if tabs?(conn),
      do: json(conn, %{commitments: Rest.commitments(me(conn), state)}),
      else: GroupController.error(conn, {:error, :not_found})
  end
end
