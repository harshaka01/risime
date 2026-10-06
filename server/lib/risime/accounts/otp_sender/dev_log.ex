defmodule RisiMe.Accounts.OtpSender.DevLog do
  @moduledoc """
  Logs `[DEV OTP] <destination>: <code>`. Logs nothing unless `OTP_DEV_LOG=true` (dev only);
  without it, `OtpSender.sms_sender/0` never selects this module.
  """
  @behaviour RisiMe.Accounts.OtpSender

  require Logger

  @impl true
  def deliver(destination, %{code: code}) do
    if Application.get_env(:risime, :otp_dev_log, false) == true,
      do: Logger.info("[DEV OTP] #{destination}: #{code}")

    :ok
  end
end
