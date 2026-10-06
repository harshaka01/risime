defmodule RisiMe.Accounts.ResetListener do
  @moduledoc """
  LISTENs on `risime_verification_reset` (a trigger on `users`, see the migration) and closes
  the affected user's sockets (contract v1.4 §7.2). Postgres delivers the notification on
  commit, from any client: the server itself, `mix risime.allow --rebind` or manual SQL.
  """
  use GenServer

  require Logger

  @channel "risime_verification_reset"

  def start_link(_opts), do: GenServer.start_link(__MODULE__, :ok, name: __MODULE__)

  @impl true
  def init(:ok) do
    config = RisiMe.Repo.config() |> Keyword.drop([:pool, :pool_size])
    {:ok, pid} = Postgrex.Notifications.start_link(config)
    {:ok, _ref} = Postgrex.Notifications.listen(pid, @channel)
    {:ok, pid}
  end

  @impl true
  def handle_info({:notification, _pid, _ref, @channel, user_id}, state) do
    n = RisiMe.SocketTracker.disconnect_user(user_id)
    Logger.info("verification reset for a user: closed #{n} socket(s)")
    {:noreply, state}
  end

  def handle_info(_other, state), do: {:noreply, state}
end
