defmodule RisiMe.Accounts.OtpSender.Email do
  @moduledoc "Emails a code through Swoosh (`RisiMe.Mailer`)."
  @behaviour RisiMe.Accounts.OtpSender

  import Swoosh.Email
  require Logger

  @impl true
  def deliver(to, %{subject: subject, body: body}) do
    email =
      new()
      |> to(to)
      |> from(Application.get_env(:risime, :mail_from, "RisiMe <no-reply@example.com>"))
      |> subject(subject)
      |> text_body(body)

    case RisiMe.Mailer.deliver(email) do
      {:ok, _} ->
        :ok

      {:error, reason} ->
        Logger.error("OTP email failed: #{inspect(reason, limit: 5)}")
        {:error, :email_failed}
    end
  end
end
