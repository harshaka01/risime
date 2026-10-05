defmodule RisiMe.Accounts.OtpNotifier do
  @moduledoc """
  Emails login codes. With `OTP_DEV_LOG=true` (dev only) it also logs
  `[DEV OTP] <phone>: <code>`; codes are never logged otherwise.
  """
  import Swoosh.Email
  require Logger

  alias RisiMe.Mailer

  def deliver(%{phone: phone, email: email, display_name: name}, code) do
    if Application.get_env(:risime, :otp_dev_log, false) do
      Logger.info("[DEV OTP] #{phone}: #{code}")
    end

    email =
      new()
      |> to({name, email})
      |> from(Application.get_env(:risime, :mail_from, "RisiMe <no-reply@example.com>"))
      |> subject("Your RisiMe code: #{code}")
      |> text_body("""
      Hi #{name},

      Your RisiMe login code is #{code}. It expires in 5 minutes.

      If you didn't ask for this code, you can ignore this email.
      """)

    case Mailer.deliver(email) do
      {:ok, _} ->
        :ok

      {:error, reason} ->
        Logger.error("OTP email to #{phone} failed: #{inspect(reason)}")
        :error
    end
  end
end
