defmodule RisiMeWeb.MeController do
  use RisiMeWeb, :controller

  alias RisiMe.Accounts
  alias RisiMeWeb.{ApiError, ApiJSON}

  def show(conn, _params), do: json(conn, %{user: user_json(conn, conn.assigns.current_user)})

  # v1.24 §24.11: `{"tz": "<IANA zone>"}` alone sets the timezone (`422 bad_request` for an
  # unknown zone); `display_name` as before. Both may come together (tz is checked first).
  def update(conn, params) do
    user = conn.assigns.current_user

    # Every field is validated before anything is written (one update).
    with :ok <- check_tz(params),
         {:ok, user} <- Accounts.update_me(user, Map.take(params, ["tz", "display_name"])) do
      json(conn, %{user: user_json(conn, user)})
    else
      {:error, :bad_tz} -> ApiError.send_error(conn, 422, :bad_request)
      {:error, _changeset} -> ApiError.send_error(conn, 422, :invalid_display_name)
    end
  end

  defp check_tz(%{"tz" => tz}), do: if(Accounts.valid_tz?(tz), do: :ok, else: {:error, :bad_tz})
  defp check_tz(_params), do: :ok

  @doc "`POST /me/phone/verify/request` (contract v1.4 §7.1)."
  def phone_request(conn, _params) do
    result =
      if conn.assigns.current_auth.kind == :dev,
        do: {:error, :already_verified},
        else: Accounts.request_phone_verification(conn.assigns.current_user)

    case result do
      {:ok, %{expires_in: expires_in, to: to}} ->
        json(conn, %{status: "sent", expires_in: expires_in, to: to})

      {:error, :already_verified} ->
        ApiError.send_error(conn, 409, :already_verified)

      {:error, {:rate_limited, seconds}} ->
        failure(conn, :rate_limited)
        ApiError.send_error(conn, 429, :rate_limited, retry_after: seconds)

      {:error, :sms_unavailable} ->
        ApiError.send_error(conn, 503, :sms_unavailable)
    end
  end

  @doc "`POST /me/phone/verify/confirm` (contract v1.4 §7.1)."
  def phone_confirm(conn, params) do
    result =
      if conn.assigns.current_auth.kind == :dev,
        do: {:error, :already_verified},
        else: Accounts.confirm_phone_verification(conn.assigns.current_user, params["code"])

    case result do
      {:ok, user} ->
        json(conn, %{user: user_json(conn, user)})

      {:error, :already_verified} ->
        ApiError.send_error(conn, 409, :already_verified)

      {:error, {:invalid_code, nil}} ->
        failure(conn, :phone_code_invalid)
        ApiError.send_error(conn, 401, :invalid_code)

      {:error, {:invalid_code, left}} ->
        failure(conn, :phone_code_invalid)
        ApiError.send_error(conn, 401, :invalid_code, extra: [attempts_left: left])

      {:error, :expired} ->
        ApiError.send_error(conn, 410, :expired)

      {:error, :too_many_attempts} ->
        failure(conn, :too_many_attempts)
        ApiError.send_error(conn, 429, :too_many_attempts, retry_after: 60)
    end
  end

  defp failure(conn, kind),
    do: RisiMe.AuthLog.failure(RisiMeWeb.ClientIP.from_conn(conn), kind, conn.request_path)

  @doc "`GET /api/v1/me/risi/dev` (v1.39 §36.1): the developer's settings."
  def risi_dev(conn, _params) do
    user = conn.assigns.current_user

    if RisiMe.Agent.CallTranscribe.dev?(user.id),
      do: json(conn, %{transcribe_compare: RisiMe.Agent.CallTranscribe.dev_setting(user.id)}),
      else: ApiError.send_error(conn, 403, :not_developer)
  end

  @doc "`PUT /api/v1/me/risi/dev` (v1.39 §36.1): `{\"transcribe_compare\": bool}`."
  def put_risi_dev(conn, params) do
    user = conn.assigns.current_user

    cond do
      not RisiMe.Agent.CallTranscribe.dev?(user.id) ->
        ApiError.send_error(conn, 403, :not_developer)

      not is_boolean(params["transcribe_compare"]) ->
        ApiError.send_error(conn, 422, :bad_request)

      true ->
        :ok = RisiMe.Agent.CallTranscribe.put_dev_setting(user.id, params["transcribe_compare"])
        json(conn, %{transcribe_compare: params["transcribe_compare"]})
    end
  end

  @doc "`GET /api/v1/me/risi/voice` (v1.41 §37.7): `{\"transcribe\": bool, \"action_items\": bool}`."
  def risi_voice(conn, _params) do
    user = conn.assigns.current_user.id
    json(conn, RisiMe.Agent.Voice.setting(user) |> with_translate(user) |> with_send(user))
  end

  @doc """
  `PUT /api/v1/me/risi/voice` (v1.41 §37.7): both booleans required; `200` the same shape.
  Proposal 2026-10-14-voice-translate (§37.13.2): optional `always_translate` (`off | en | si |
  ta`; absent keeps the stored value) while Translate is available to the user.
  """
  def put_risi_voice(conn, params) do
    user = conn.assigns.current_user.id
    translate? = RisiMe.Agent.VoiceTranslate.enabled_for?(user)

    with :ok <- put_always_translate(user, params, translate?),
         :ok <- put_voice_send(user, params),
         {:ok, setting} <- RisiMe.Agent.Voice.put_setting(user, params) do
      json(conn, setting |> with_translate(user) |> with_send(user))
    else
      {:error, :bad_request} -> ApiError.send_error(conn, 422, :bad_request)
    end
  end

  defp put_always_translate(user, %{"always_translate" => v}, true),
    do: RisiMe.Agent.VoiceTranslate.put_always_translate(user, v)

  defp put_always_translate(_user, _params, _translate?), do: :ok

  # v1.55 §41.11: `voice_send` (optional; ignored while RISI_CALL_SEND is off for the user).
  defp put_voice_send(user, %{"voice_send" => v}) when is_boolean(v),
    do: RisiMe.Agent.CallSend.put_voice_send(user, v)

  defp put_voice_send(_user, %{"voice_send" => v}) when not is_nil(v), do: {:error, :bad_request}
  defp put_voice_send(_user, _params), do: :ok

  defp with_send(setting, user), do: RisiMe.Agent.CallSend.with_setting(setting, user)

  # The v1.41 shape unchanged while Translate isn't available to the user (absent = false).
  defp with_translate(setting, user) do
    if RisiMe.Agent.VoiceTranslate.enabled_for?(user),
      do:
        Map.merge(setting, %{
          always_translate: RisiMe.Agent.VoiceTranslate.always_translate(user),
          translate_available: true,
          translate_languages: RisiMe.Agent.VoiceTranslate.targets()
        }),
      else: setting
  end

  @doc """
  `POST /api/v1/me/risi/voice/cancel` (proposal 2026-10-14-transcribe-progress, flag
  `RISI_VOICE_PROGRESS`): Stop the running transcription of the caller's own note. Only notes
  Risi is transcribing (Official / the Risi chat) are known here; never Private.
  """
  def risi_voice_cancel(conn, params) do
    user = conn.assigns.current_user.id

    with true <- RisiMe.Agent.VoiceJobs.enabled_for?(user),
         {:ok, conv, id} <- voice_job_params(params),
         {:ok, can_retry} <- RisiMe.Agent.VoiceJobs.cancel(conv, id, user) do
      json(conn, %{state: "cancelled", can_retry: can_retry})
    else
      _ -> conn |> put_status(404) |> json(%{error: "not_running"})
    end
  end

  @doc "`POST /api/v1/me/risi/voice/retry` (same proposal): Try again."
  def risi_voice_retry(conn, params) do
    user = conn.assigns.current_user.id

    with true <- RisiMe.Agent.VoiceJobs.enabled_for?(user),
         {:ok, conv, id} <- voice_job_params(params) do
      case RisiMe.Agent.VoiceJobs.retry(conv, id, user) do
        :ok -> conn |> put_status(202) |> json(%{state: "working"})
        {:error, :running} -> conn |> put_status(409) |> json(%{error: "running"})
        {:error, :expired} -> conn |> put_status(410) |> json(%{error: "expired"})
        {:error, _} -> conn |> put_status(404) |> json(%{error: "not_found"})
      end
    else
      _ -> conn |> put_status(404) |> json(%{error: "not_found"})
    end
  end

  defp voice_job_params(%{"conversation_id" => conv, "message_id" => id})
       when is_binary(conv) and is_binary(id) and byte_size(conv) < 200 and byte_size(id) < 64,
       do: {:ok, conv, String.downcase(id)}

  defp voice_job_params(_), do: :error

  # v1.24 §24.11: `User` gains `tz` once set (absent: the server default).
  defp user_json(conn, user) do
    json = ApiJSON.user(user, Accounts.phone_verified?(user, conn.assigns.current_auth.kind))
    if user.tz, do: Map.put(json, :tz, user.tz), else: json
  end
end
