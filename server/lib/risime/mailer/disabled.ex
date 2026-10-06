defmodule RisiMe.Mailer.Disabled do
  @moduledoc """
  Prod mailer when SMTP isn't configured (`RISIME_MAILER` unset): sends nothing and logs one
  line without recipient, subject or body (the subject and body carry login codes). Login codes
  then reach the operator only through `[DEV OTP]` lines (`OTP_DEV_LOG=true`).
  """
  use Swoosh.Adapter

  require Logger

  @impl true
  def deliver(_email, _config) do
    Logger.info("email not sent: no SMTP configured (RISIME_MAILER unset)")
    {:ok, %{id: "disabled"}}
  end
end
