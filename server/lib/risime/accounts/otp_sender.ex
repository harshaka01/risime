defmodule RisiMe.Accounts.OtpSender do
  @moduledoc """
  Delivers one-time codes (decision 020). `RisiMe.Accounts` builds the message; a sender only
  sends it. Senders per channel:

    * `:email`: `OtpSender.Email` (Swoosh), configurable as `config :risime, :otp_senders, email: …`;
    * `:sms`: chosen by `SMS_MODE` (`config :risime, :sms_mode`): `:notifylk` → `OtpSender.NotifyLk`,
      `:log` → `OtpSender.DevLog`, `:test` → `OtpSender.Test`. See `sms_sender/0` for the
      NotifyDEMO guard.

  With `OTP_DEV_LOG=true` (dev only) every code is also logged by `OtpSender.DevLog`.
  """

  @type message :: %{subject: String.t() | nil, body: String.t(), code: String.t()}

  @callback deliver(to :: String.t() | {String.t(), String.t()}, message) :: :ok | {:error, term}

  alias RisiMe.Accounts.OtpSender.{DevLog, Email, NotifyLk, Test}

  @doc "Sends `message` to `to` over `channel`. `log_as` is the destination shown in the dev log."
  @spec deliver(:email | :sms, term, message, String.t()) :: :ok | {:error, term}
  def deliver(channel, to, message, log_as) do
    case sender(channel) do
      {:ok, module} ->
        if dev_log?() and module != DevLog, do: DevLog.deliver(log_as, message)
        module.deliver(to, message)

      {:error, _} = error ->
        error
    end
  end

  @doc "The sender module for `channel`, or `{:error, reason}` when SMS can't be sent."
  def sender(:email), do: {:ok, Keyword.get(configured(), :email, Email)}
  def sender(:sms), do: sms_sender()

  @doc """
  The SMS sender, after the NotifyDEMO guard: Notify.lk's terms forbid OTP content from the
  shared demo sender ID, so `:notifylk` with that sender refuses (unless
  `NOTIFYLK_ALLOW_DEMO_OTP=true`) and falls back to `DevLog` only when `OTP_DEV_LOG=true`.
  """
  def sms_sender do
    case Application.get_env(:risime, :sms_mode, :log) do
      :test ->
        {:ok, Test}

      :log ->
        if dev_log?(), do: {:ok, DevLog}, else: {:error, :no_sms_sender}

      :notifylk ->
        cond do
          not NotifyLk.demo_blocked?() -> {:ok, NotifyLk}
          dev_log?() -> {:ok, DevLog}
          true -> {:error, :demo_sender_blocked}
        end
    end
  end

  defp configured, do: Application.get_env(:risime, :otp_senders, [])
  defp dev_log?, do: Application.get_env(:risime, :otp_dev_log, false) == true

  @doc "`+94771234522` → `+9477•••••22` (U+2022)."
  def mask(phone) when is_binary(phone) and byte_size(phone) > 7 do
    keep_head = String.slice(phone, 0, 5)
    keep_tail = String.slice(phone, -2, 2)
    keep_head <> String.duplicate("•", String.length(phone) - 7) <> keep_tail
  end

  def mask(_), do: "•••"
end
