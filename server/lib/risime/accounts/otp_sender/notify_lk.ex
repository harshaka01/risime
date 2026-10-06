defmodule RisiMe.Accounts.OtpSender.NotifyLk do
  @moduledoc """
  SMS through Notify.lk (decision 020): `POST https://app.notify.lk/api/v1/send` with a form
  body (`user_id`, `api_key`, `sender_id`, `to`, `message`). Success is HTTP 200 with
  `{"status": "success"}`.

  **Secrets.** `NOTIFYLK_API_KEY` is read from the OS environment only inside `form/2`, which is
  the last call before the request and is never logged. It is not in app config. Errors log only
  the HTTP status and up to 200 characters of the provider's message. Req is called without
  retry (a retry can mean a duplicate paid SMS) and without logging steps. Every exception is
  rescued and reduced to its type, so no crash report can print the request.

  **Guard.** Notify.lk's terms forbid OTP content from the shared `NotifyDEMO` sender ID.
  `demo_blocked?/0` is true while `NOTIFYLK_SENDER_ID` is that ID (case-insensitive), unless
  `NOTIFYLK_ALLOW_DEMO_OTP=true`.
  """
  @behaviour RisiMe.Accounts.OtpSender

  require Logger

  @demo_sender "notifydemo"

  @impl true
  def deliver("+94" <> _ = phone, %{body: body}) do
    to = String.trim_leading(phone, "+")

    try do
      (url() <> "/send")
      |> Req.post(Keyword.merge(req_options(), form: form(to, body)))
      |> handle_send()
    rescue
      e ->
        Logger.error("Notify.lk send raised #{inspect(e.__struct__)}")
        {:error, :sms_failed}
    end
  end

  def deliver(_phone, _message), do: {:error, :unsupported_number}

  defp handle_send({:ok, %Req.Response{status: 200, body: body}}) do
    case decode(body) do
      %{"status" => "success"} ->
        :ok

      other ->
        Logger.error("Notify.lk send refused: #{provider_text(other)}")
        {:error, :sms_failed}
    end
  end

  defp handle_send({:ok, %Req.Response{status: status, body: body}}) do
    Logger.error("Notify.lk send failed: HTTP #{status} #{provider_text(decode(body))}")
    {:error, :sms_failed}
  end

  defp handle_send({:error, exception}) do
    Logger.error("Notify.lk send failed: #{transport_reason(exception)}")
    {:error, :sms_failed}
  end

  @doc """
  Account status for `/health`: `{:ok, %{active: boolean, balance: number | nil}}` or
  `{:error, reason}`. POST form first; the documented endpoint is GET with query parameters,
  used as a fallback (that URL is never logged).
  """
  def status do
    with {:ok, %Req.Response{status: 200, body: body}} <- status_request(:post),
         %{"status" => "success", "data" => data} when is_map(data) <- decode(body) do
      {:ok, %{active: data["active"] == true, balance: number(data["acc_balance"])}}
    else
      _ -> status_get()
    end
  rescue
    e -> {:error, inspect(e.__struct__)}
  end

  defp status_get do
    case status_request(:get) do
      {:ok, %Req.Response{status: 200, body: body}} ->
        case decode(body) do
          %{"status" => "success", "data" => data} when is_map(data) ->
            {:ok, %{active: data["active"] == true, balance: number(data["acc_balance"])}}

          other ->
            {:error, provider_text(other)}
        end

      {:ok, %Req.Response{status: status}} ->
        {:error, "HTTP #{status}"}

      {:error, exception} ->
        {:error, transport_reason(exception)}
    end
  end

  defp status_request(:post),
    do: Req.post(url() <> "/status", Keyword.merge(req_options(), form: credentials()))

  defp status_request(:get),
    do: Req.get(url() <> "/status", Keyword.merge(req_options(), params: credentials()))

  @doc "True while the sender ID is Notify.lk's shared demo ID and no override is set."
  def demo_blocked? do
    sender = System.get_env("NOTIFYLK_SENDER_ID", "")

    String.downcase(String.trim(sender)) == @demo_sender and
      System.get_env("NOTIFYLK_ALLOW_DEMO_OTP") != "true"
  end

  @doc "True if the three NOTIFYLK_* variables are set (values are never read out)."
  def configured? do
    Enum.all?(
      ~w(NOTIFYLK_USER_ID NOTIFYLK_API_KEY NOTIFYLK_SENDER_ID),
      &(System.get_env(&1, "") != "")
    )
  end

  # The only place the key is read. Never log, inspect or return this list elsewhere.
  defp form(to, message) do
    credentials() ++
      [sender_id: System.get_env("NOTIFYLK_SENDER_ID", ""), to: to, message: message]
  end

  defp credentials do
    [
      user_id: System.get_env("NOTIFYLK_USER_ID", ""),
      api_key: System.get_env("NOTIFYLK_API_KEY", "")
    ]
  end

  defp url, do: Keyword.get(config(), :base_url, "https://app.notify.lk/api/v1")

  defp req_options do
    [retry: false, receive_timeout: 10_000, connect_options: [timeout: 5_000]] ++
      Keyword.get(config(), :req_options, [])
  end

  defp config, do: Application.get_env(:risime, :notifylk, [])

  defp decode(body) when is_map(body), do: body

  defp decode(body) when is_binary(body) do
    case Jason.decode(body) do
      {:ok, map} when is_map(map) -> map
      _ -> body
    end
  end

  defp decode(other), do: other

  defp provider_text(%{} = map),
    do: truncate(to_string(map["message"] || map["data"] || map["status"] || "no message"))

  defp provider_text(text) when is_binary(text), do: truncate(text)
  defp provider_text(_), do: "unreadable response"

  defp truncate(text), do: text |> String.replace(~r/\s+/, " ") |> String.slice(0, 200)

  defp transport_reason(%{__struct__: Req.TransportError, reason: reason}), do: inspect(reason)
  defp transport_reason(%{__struct__: mod}), do: inspect(mod)
  defp transport_reason(_), do: "unknown"

  defp number(n) when is_number(n), do: n

  defp number(s) when is_binary(s) do
    case Float.parse(s) do
      {f, _} -> f
      :error -> nil
    end
  end

  defp number(_), do: nil
end
