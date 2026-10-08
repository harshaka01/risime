defmodule RisiMe.Push.FCM do
  @moduledoc """
  FCM HTTP v1 sender (decision 028).

  * **Credentials:** the service-account JSON at `FCM_SERVICE_ACCOUNT_FILE` (outside the repo,
    mode 600), read when a token is needed and never logged. `project_id`, `client_email`,
    `private_key` and `token_uri` come from it.
  * **Access token:** a JWT (RS256, signed with JOSE using the account's key) is exchanged at the
    token endpoint for an OAuth access token (scope `firebase.messaging`). The token is cached in
    this process and refreshed 5 min before it expires. A 401 from FCM drops the cache.
  * **Send:** `POST https://fcm.googleapis.com/v1/projects/{project_id}/messages:send` with
    `data` = `RisiMe.Push.payload/0`, `android.priority: high`, `collapse_key: inbox`,
    `ttl: 3600s`; for the call wake-up (`RisiMe.Push.call_payload/0`, v1.13 §16.8)
    `collapse_key: call`, `ttl: 45s`. Nothing else.
  * **Results:** 200 → `:ok`; `UNREGISTERED` / `INVALID_ARGUMENT` → `{:error, :unregistered}`
    (the dispatcher deletes the device); 5xx, 429 or 401 → `{:error, :retryable}` (the dispatcher
    retries once); anything else → `{:error, :failed}`.
  * **Redaction:** logs carry the HTTP status and FCM's error status only, never a push token,
    an access token, the JWT or the key. Req runs without retries and without logging steps.
  """
  @behaviour RisiMe.Push
  use GenServer

  require Logger

  @scope "https://www.googleapis.com/auth/firebase.messaging"
  @refresh_margin_s 300

  def start_link(_opts), do: GenServer.start_link(__MODULE__, :ok, name: __MODULE__)

  @impl RisiMe.Push
  def deliver(push_token, payload) do
    with {:ok, %{token: access, project_id: project}} <- access_token() do
      send_message(access, project, push_token, payload)
    end
  rescue
    e ->
      Logger.error("FCM send raised #{inspect(e.__struct__)}")
      {:error, :failed}
  end

  @doc false
  def message(push_token, payload) do
    %{message: %{token: push_token, data: payload, android: android(payload)}}
  end

  # v1.13 §16.8: the call wake-up is high priority, lives 45 s and collapses on "call".
  defp android(%{"type" => "call"}), do: %{priority: "high", collapse_key: "call", ttl: "45s"}
  defp android(_inbox), do: %{priority: "high", collapse_key: "inbox", ttl: "3600s"}

  defp send_message(access, project, push_token, payload) do
    url = "https://fcm.googleapis.com/v1/projects/#{project}/messages:send"

    result =
      Req.post(
        url,
        [json: message(push_token, payload), auth: {:bearer, access}] ++ req_options()
      )

    case result do
      {:ok, %Req.Response{status: 200, body: body}} ->
        # Audit only: the dispatcher logs the last path segment of FCM's message name.
        with %{"name" => name} when is_binary(name) <- body,
             do: Process.put(:push_fcm_message, name |> String.split("/") |> List.last())

        :ok

      {:ok, %Req.Response{status: 401}} ->
        GenServer.cast(__MODULE__, :invalidate)
        Logger.warning("FCM send: HTTP 401, access token dropped")
        {:error, :retryable}

      {:ok, %Req.Response{status: status, body: body}} ->
        code = fcm_error(body)
        Logger.warning("FCM send failed: HTTP #{status} #{code}")

        cond do
          code in ["UNREGISTERED", "INVALID_ARGUMENT"] -> {:error, :unregistered}
          status >= 500 or status == 429 -> {:error, :retryable}
          true -> {:error, :failed}
        end

      {:error, %{__struct__: mod} = e} ->
        reason = if mod == Req.TransportError, do: inspect(e.reason), else: inspect(mod)
        Logger.warning("FCM send failed: #{reason}")
        {:error, :retryable}
    end
  end

  # FCM's error status (`error.status`) or, for v1 errors, the `errorCode` of the details.
  defp fcm_error(%{"error" => %{} = error}) do
    detail =
      error
      |> Map.get("details", [])
      |> Enum.find_value(fn d -> is_map(d) && d["errorCode"] end)

    detail || error["status"] || "unknown"
  end

  defp fcm_error(_), do: "unknown"

  defp req_options do
    [retry: false, receive_timeout: 10_000] ++
      Keyword.get(Application.get_env(:risime, :fcm, []), :req_options, [])
  end

  ## Access token cache

  @doc false
  def access_token, do: GenServer.call(__MODULE__, :access_token, 30_000)

  @impl GenServer
  def init(:ok), do: {:ok, nil}

  @impl GenServer
  def handle_call(:access_token, _from, cached) do
    now = System.os_time(:second)

    case cached do
      %{expires_at: exp} = c when exp - @refresh_margin_s > now ->
        {:reply, {:ok, c}, cached}

      _ ->
        case fetch_access_token(now) do
          {:ok, fresh} -> {:reply, {:ok, fresh}, fresh}
          {:error, _} = error -> {:reply, error, nil}
        end
    end
  end

  @impl GenServer
  def handle_cast(:invalidate, _cached), do: {:noreply, nil}

  defp fetch_access_token(now) do
    with {:ok, account} <- service_account(),
         {:ok, assertion} <- jwt(account, now),
         {:ok, %Req.Response{status: 200, body: %{"access_token" => token} = body}} <-
           Req.post(
             account["token_uri"] || "https://oauth2.googleapis.com/token",
             [
               form: [
                 grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
                 assertion: assertion
               ]
             ] ++ req_options()
           ) do
      expires_in = if is_integer(body["expires_in"]), do: body["expires_in"], else: 3600

      {:ok, %{token: token, project_id: account["project_id"], expires_at: now + expires_in}}
    else
      {:ok, %Req.Response{status: status}} ->
        Logger.error("FCM access token request failed: HTTP #{status}")
        {:error, :failed}

      {:error, reason} when is_atom(reason) ->
        {:error, reason}

      _ ->
        Logger.error("FCM access token request failed")
        {:error, :failed}
    end
  end

  defp jwt(account, now) do
    claims = %{
      "iss" => account["client_email"],
      "scope" => @scope,
      "aud" => account["token_uri"] || "https://oauth2.googleapis.com/token",
      "iat" => now,
      "exp" => now + 3600
    }

    header = %{"alg" => "RS256", "typ" => "JWT", "kid" => account["private_key_id"]}
    jwk = JOSE.JWK.from_pem(account["private_key"])
    {_, compact} = jwk |> JOSE.JWT.sign(header, claims) |> JOSE.JWS.compact()
    {:ok, compact}
  rescue
    _ ->
      Logger.error("FCM service account key could not be used to sign")
      {:error, :bad_service_account}
  end

  # Read at use; the path and contents are never logged.
  defp service_account do
    path = Application.get_env(:risime, :fcm_service_account_file)

    with path when is_binary(path) <- path,
         {:ok, raw} <- File.read(path),
         {:ok, %{"project_id" => p, "client_email" => e, "private_key" => k} = account}
         when is_binary(p) and is_binary(e) and is_binary(k) <- Jason.decode(raw) do
      {:ok, account}
    else
      _ ->
        Logger.error("FCM service account file missing or invalid (FCM_SERVICE_ACCOUNT_FILE)")
        {:error, :bad_service_account}
    end
  end
end
