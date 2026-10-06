defmodule RisiMe.Accounts.OtpSender.Test do
  @moduledoc "Test sender: sends `{:otp, :sms, to, message}` to the calling test process."
  @behaviour RisiMe.Accounts.OtpSender

  @impl true
  def deliver(to, message) do
    for pid <- Enum.uniq([self() | Process.get(:"$callers", [])]),
        do: send(pid, {:otp, :sms, to, message})

    :ok
  end
end
