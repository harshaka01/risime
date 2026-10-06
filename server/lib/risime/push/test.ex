defmodule RisiMe.Push.Test do
  @moduledoc """
  Test sender: sends `{:push, token, payload}` to the pid in `:push_test_pid`. A token that
  starts with `"unregistered"` answers `{:error, :unregistered}`, one starting with `"retry"`
  answers `{:error, :retryable}`.
  """
  @behaviour RisiMe.Push

  @impl true
  def deliver(token, payload) do
    if pid = Application.get_env(:risime, :push_test_pid), do: send(pid, {:push, token, payload})

    cond do
      String.starts_with?(token, "unregistered") -> {:error, :unregistered}
      String.starts_with?(token, "retry") -> {:error, :retryable}
      true -> :ok
    end
  end
end
