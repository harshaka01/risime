defmodule RisiMe.Push.Test do
  @moduledoc """
  Test sender: sends `{:push, token, payload}` to the process that registered `token` with
  `register/1` (normally a test process, through a unique token per test). A token that
  starts with `"unregistered"` answers `{:error, :unregistered}`, one starting with `"retry"`
  answers `{:error, :retryable}`.

  Pushes are sent asynchronously (a cast to `RisiMe.Push.Dispatcher`, a task, and trailing
  timers up to `:push_coalesce_ms` later), so a push can be delivered after the test that
  caused it has ended. Routing by token, not by a global "current test pid", means such a late
  push goes to its own (dead) test process and can never land in the next test's mailbox.
  Unregistered tokens are dropped.
  """
  @behaviour RisiMe.Push

  @registry RisiMe.Push.TestRegistry

  @doc "The `Registry` (unique keys) the test helper starts; maps token → test process."
  def registry, do: @registry

  @doc "Routes pushes for `token` to the calling process until it exits."
  def register(token) do
    {:ok, _} = Registry.register(@registry, token, nil)
    :ok
  end

  @impl true
  def deliver(token, payload) do
    with [{pid, _}] <- lookup(token), do: send(pid, {:push, token, payload})

    cond do
      String.starts_with?(token, "unregistered") -> {:error, :unregistered}
      String.starts_with?(token, "retry") -> {:error, :retryable}
      true -> :ok
    end
  end

  defp lookup(token) do
    Registry.lookup(@registry, token)
  rescue
    ArgumentError -> []
  end
end
