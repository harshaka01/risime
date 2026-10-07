defmodule RisiMe.Push.Dispatcher do
  @moduledoc """
  When to push (contract v1.5 §8.0, decision 028).

  `notify/1` is called for every stored inbox event. If the user has a live inbox channel
  (`RisiMe.Presence.online?/1`, which includes the 5 s offline grace) nothing happens.
  Otherwise the user gets at most one push every 10 s (`:push_coalesce_ms`):

    * no push in the last 10 s → send now;
    * otherwise → one trailing push when the 10 s are up (if still offline).

  An in-memory debounce rather than Oban jobs: a push is a best-effort wake-up, a lost one after
  a restart costs nothing (the app syncs on next open), and it keeps the send path off Postgres.
  Sends run in `RisiMe.Push.TaskSupervisor`, so a slow FCM never blocks a message send.
  """
  use GenServer

  require Logger

  alias RisiMe.{Devices, Push}

  @table __MODULE__

  def start_link(_opts), do: GenServer.start_link(__MODULE__, :ok, name: __MODULE__)

  @doc "An inbox event was stored for `user_id`."
  def notify(user_id) do
    if Push.sender() != nil and not RisiMe.Presence.online?(user_id),
      do: GenServer.cast(__MODULE__, {:notify, user_id})

    :ok
  end

  @doc "Sends the wake-up to every device of `user_id` now (used by the debounce)."
  def push_now(user_id) do
    case Push.sender() do
      nil -> :ok
      sender -> for token <- Devices.push_tokens(user_id), do: deliver(sender, token)
    end

    :ok
  end

  @doc """
  v1.13 §16.8: sends the call wake-up to these push tokens now, in the push task supervisor
  (never coalesced, never the 10-s rule, never the user-level inbox push).
  """
  def push_call([]), do: :ok

  def push_call(tokens) do
    case Push.sender() do
      nil ->
        :ok

      sender ->
        for token <- tokens do
          Task.Supervisor.start_child(RisiMe.Push.TaskSupervisor, fn ->
            # Decision 054: the call push's result is logged (token never; FCM failures already are).
            result = deliver(sender, token, Push.call_payload())
            Logger.info("call push: result=#{result}")
          end)
        end

        :ok
    end
  end

  # One attempt plus one retry on a retryable error; an unregistered token deletes the device.
  defp deliver(sender, token, payload \\ Push.payload(), retries \\ 1) do
    case sender.deliver(token, payload) do
      :ok ->
        :telemetry.execute([:risime, :push, :sent], %{count: 1}, %{result: :ok})
        :ok

      {:error, :unregistered} ->
        Devices.delete_push_token(token)
        :telemetry.execute([:risime, :push, :sent], %{count: 1}, %{result: :unregistered})
        :unregistered

      {:error, :retryable} when retries > 0 ->
        Process.sleep(Application.get_env(:risime, :push_retry_ms, 1_000))
        deliver(sender, token, payload, retries - 1)

      {:error, reason} ->
        :telemetry.execute([:risime, :push, :sent], %{count: 1}, %{result: reason})
        reason
    end
  end

  defp coalesce_ms, do: Application.get_env(:risime, :push_coalesce_ms, 10_000)

  ## Server
  # ETS row: {user_id, last_sent_ms, trailing_timer | nil}

  @impl true
  def init(:ok) do
    :ets.new(@table, [:named_table, :protected, :set])
    {:ok, nil}
  end

  @impl true
  def handle_cast({:notify, user_id}, state) do
    now = System.monotonic_time(:millisecond)
    window = coalesce_ms()

    case :ets.lookup(@table, user_id) do
      [{_, last, timer}] when now - last < window ->
        # Within the window: make sure exactly one trailing push is scheduled.
        if timer == nil do
          t = Process.send_after(self(), {:trailing, user_id}, last + window - now)
          :ets.insert(@table, {user_id, last, t})
        end

      _ ->
        send_async(user_id)
        :ets.insert(@table, {user_id, now, nil})
    end

    {:noreply, state}
  end

  @impl true
  def handle_info({:trailing, user_id}, state) do
    :ets.insert(@table, {user_id, System.monotonic_time(:millisecond), nil})
    unless RisiMe.Presence.online?(user_id), do: send_async(user_id)
    {:noreply, state}
  end

  defp send_async(user_id) do
    Task.Supervisor.start_child(RisiMe.Push.TaskSupervisor, fn -> push_now(user_id) end)
  end
end
