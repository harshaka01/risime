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

  @doc """
  `POST /api/v1/risi/translate` (proposal 2026-10-14-voice-translate, v1.50 §37.13.2): one
  Official message (a transcribed note or a typed message Risi holds) translated for the caller
  only. `200` the translation; `403 not_available`, `404 not_found`, `422 bad_request`,
  `429 rate_limited`, `503 unavailable`. Nothing is posted, stored or logged.
  """
  def translate(conn, params) do
    case RisiMe.Agent.VoiceTranslate.translate_message(
           me(conn),
           params["conversation_id"],
           params["message_id"],
           params["target"]
         ) do
      {:ok, reply} -> json(conn, reply)
      {:error, :not_available} -> conn |> put_status(403) |> json(%{error: "not_available"})
      {:error, :not_found} -> conn |> put_status(404) |> json(%{error: "not_found"})
      {:error, :rate_limited} -> conn |> put_status(429) |> json(%{error: "rate_limited"})
      {:error, :unavailable} -> conn |> put_status(503) |> json(%{error: "unavailable"})
      {:error, _} -> ApiError.send_error(conn, 422, :bad_request)
    end
  end

  @doc "`POST /api/v1/risi/chat` (v1.25 §25.2): `201` new, `200` existing `{chat, group}`."
  def create_chat(conn, _params) do
    case RisiMe.RisiChat.create(me(conn), RisiMeWeb.MLSController.caller_device(conn)) do
      {:ok, :created, reply} -> conn |> put_status(201) |> json(reply)
      {:ok, :existing, reply} -> json(conn, reply)
      error -> GroupController.error(conn, error)
    end
  end

  @doc """
  `POST /api/v1/risi/tool_calls/{id}/result` (v1.25 §25.3): `204`, or 404 / 409
  `tool_call_expired` / 409 `write_not_confirmed` / 413 / 422 (`RisiMe.Agent.ToolCalls`).
  """
  def tool_result(conn, %{"id" => id}) do
    body = conn.body_params
    size = body_size(conn, body)
    device = RisiMeWeb.MLSController.caller_device(conn)

    case RisiMe.Agent.ToolCalls.result(me(conn), device, id, body, size) do
      :ok -> send_resp(conn, 204, "")
      {:error, :bad_request} -> ApiError.send_error(conn, 422, :bad_request)
      error -> GroupController.error(conn, error)
    end
  end

  defp body_size(conn, body) do
    case get_req_header(conn, "content-length") do
      [n] ->
        case Integer.parse(n) do
          {n, ""} -> n
          _ -> byte_size(Jason.encode!(body))
        end

      _ ->
        byte_size(Jason.encode!(body))
    end
  end

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
    case Rest.facts(me(conn), RisiMeWeb.MLSController.caller_device(conn)) do
      {:ok, facts} -> json(conn, %{facts: facts})
      error -> GroupController.error(conn, error)
    end
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

    # v1.29 §30.5: items gain `note_id` for a `risi_notes` device (while RISI_NOTES is on).
    notes? =
      RisiMe.Agent.Notes.on?() and
        RisiMe.Devices.risi_notes_device?(me(conn), RisiMeWeb.MLSController.caller_device(conn))

    # v1.42 §38.5: `source=call,voice_note` (default all); an unknown value is `400`.
    sources = Rest.parse_sources(params["source"])

    case sources != :error and
           Rest.commitments(me(conn), state, notes: notes?, sources: elem(sources, 1)) do
      false ->
        GroupController.error(conn, {:error, :bad_request})

      # Item 9: `totals` per direction (the personal digest carries the same `totals`).
      {:ok, commitments} ->
        totals = Enum.frequencies_by(commitments, & &1.direction)

        json(conn, %{
          commitments: commitments,
          totals: Map.merge(%{"i_promised" => 0, "promised_to_me" => 0, "others" => 0}, totals)
        })

      error ->
        GroupController.error(conn, error)
    end
  end
end
