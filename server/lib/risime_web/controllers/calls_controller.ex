defmodule RisiMeWeb.CallsController do
  @moduledoc """
  `GET /api/v1/calls/turn` (contract v1.13 §16.7): TURN REST credentials.
  `POST /api/v1/calls/rooms` (v1.19 §20.2, v1.23 §23.6 `upgrade`): LiveKit rooms and tokens for
  group calls.
  """
  use RisiMeWeb, :controller

  alias RisiMe.RateLimiter
  alias RisiMe.Calls.{Rooms, Turn}
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

  # v1.19 §20.2. The calling device is `X-Device-Id`; nothing about the token is logged.
  def rooms(conn, params) do
    device_id = conn |> get_req_header("x-device-id") |> List.first()

    case Rooms.handle(conn.assigns.current_user.id, device_id, params) do
      {:ok, reply} ->
        json(conn, reply)

      {:error, {:rate_limited, s}} ->
        ApiError.send_error(conn, 429, :rate_limited, retry_after: s)

      {:error, :calls_unavailable} ->
        ApiError.send_error(conn, 503, :calls_unavailable)

      {:error, :bad_request} ->
        ApiError.send_error(conn, 400, :bad_request)

      {:error, :invalid_device} ->
        ApiError.send_error(conn, 403, :invalid_device)

      {:error, :not_found} ->
        ApiError.send_error(conn, 404, :not_found)

      {:error, :call_ended} ->
        ApiError.send_error(conn, 404, :call_ended)

      {:error, :call_full} ->
        ApiError.send_error(conn, 409, :call_full)

      {:error, :not_in_call} ->
        ApiError.send_error(conn, 409, :not_in_call)

      {:error, :too_many_for_video} ->
        ApiError.send_error(conn, 409, :too_many_for_video)

      # Decision 090 (proposal 2026-10-14-call-risi §41.2): only with `risi_call: true`.
      {:error, :call_risi_off} ->
        ApiError.send_error(conn, 403, :call_risi_off)

      {:error, :not_risi_chat} ->
        ApiError.send_error(conn, 422, :not_risi_chat)

      {:error, :risi_busy} ->
        ApiError.send_error(conn, 409, :risi_busy)

      {:error, :risi_unavailable} ->
        ApiError.send_error(conn, 503, :risi_unavailable)
    end
  end

  @doc "`GET /api/v1/calls/{call_id}/transcribe` (v1.39 §36.3): the newest session, or null."
  def transcribe(conn, %{"call_id" => call_id}) do
    case RisiMe.Agent.CallTranscribe.current(conn.assigns.current_user.id, call_id) do
      {:ok, session} -> json(conn, %{session: session})
      {:error, :not_found} -> ApiError.send_error(conn, 404, :not_found)
    end
  end

  @doc """
  `POST /api/v1/calls/{call_id}/transcribe/chunks` (v1.39 §36.4): one chunk of the uploader's
  own microphone. The multipart body was read into memory by `RisiMeWeb.Plugs.MemoryMultipart`
  (never a temp file); nothing about the audio is logged.
  """
  def transcribe_chunk(conn, %{"call_id" => call_id} = params) do
    device_id = conn |> get_req_header("x-device-id") |> List.first()
    user = conn.assigns.current_user.id

    case RisiMe.Agent.CallTranscribe.upload(user, device_id, call_id, params) do
      {:ok, reply} ->
        conn |> put_status(202) |> json(reply)

      {:error, 429, :rate_limited} ->
        ApiError.send_error(conn, 429, :rate_limited, retry_after: 8)

      {:error, status, code} ->
        ApiError.send_error(conn, status, code)
    end
  end
end
