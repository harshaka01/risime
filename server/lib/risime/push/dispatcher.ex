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
    cond do
      Push.sender() == nil -> :ok
      RisiMe.Presence.online?(user_id) -> log_skipped_online(user_id)
      true -> GenServer.cast(__MODULE__, {:notify, user_id})
    end

    :ok
  end

  # Audit: a push suppressed because the user has a live inbox channel (off the message path).
  defp log_skipped_online(user_id) do
    Task.Supervisor.start_child(RisiMe.Push.TaskSupervisor, fn ->
      online = Enum.count(Devices.device_ids(user_id), &RisiMe.Presence.device_online?/1)

      Logger.info(
        "push: skipped kind=inbox user=#{user_hash(user_id)} reason=online devices_online=#{online}"
      )
    end)
  end

  @doc false
  def user_hash(user_id),
    do:
      :crypto.hash(:sha256, to_string(user_id))
      |> Base.encode16(case: :lower)
      |> binary_part(0, 8)

  defp device_tag(device_id), do: device_id |> to_string() |> String.slice(0, 8)

  @doc "Sends the wake-up to every device of `user_id` now (used by the debounce)."
  def push_now(user_id) do
    case Push.sender() do
      nil -> :ok
      sender -> push_user(sender, user_id)
    end

    :ok
  end

  defp push_user(sender, user_id) do
    hash = user_hash(user_id)

    case Devices.push_targets(user_id) do
      [] ->
        Logger.info("push: none kind=inbox user=#{hash} reason=no_token")

      targets ->
        for {device_id, token} <- targets do
          deliver(sender, token, Push.payload(), {hash, device_id})
        end
    end
  end

  # Owner (user hash, device id) of a token, resolved before a send can delete the device.
  defp owner(token) do
    case Devices.token_owners([token]) do
      %{^token => {user_id, device_id}} -> {user_hash(user_id), device_id}
      _ -> {"unknown", nil}
    end
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
            result = deliver(sender, token, Push.call_payload(), owner(token))
            Logger.info("call push: result=#{result}")
          end)
        end

        :ok
    end
  end

  @doc """
  v1.14 §12.4a: sends the inbox wake-up (§8.2) to these push tokens now (a group op waits for a
  committer). Not coalesced per user: the caller caps it per device.
  """
  def push_inbox([]), do: :ok

  def push_inbox(tokens) do
    case Push.sender() do
      nil ->
        :ok

      sender ->
        for token <- tokens do
          Task.Supervisor.start_child(RisiMe.Push.TaskSupervisor, fn ->
            deliver(sender, token, Push.payload(), owner(token))
          end)
        end

        :ok
    end
  end

  # Times the whole delivery (retry included), logs one audit line, returns the result atom.
  defp deliver(sender, token, payload, {hash, device_id}) do
    kind = payload["type"] || "inbox"
    started = System.monotonic_time(:millisecond)
    Process.delete(:push_fcm_message)
    result = attempt(sender, token, payload, 1)
    ms = System.monotonic_time(:millisecond) - started

    msg =
      case Process.delete(:push_fcm_message) do
        nil -> ""
        id -> " msg=#{id}"
      end

    Logger.info(
      "push: kind=#{kind} user=#{hash} device=#{device_tag(device_id)} result=#{result} ms=#{ms}#{msg}"
    )

    result
  end

  # One attempt plus one retry on a retryable error; an unregistered token deletes the device.
  defp attempt(sender, token, payload, retries) do
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
        attempt(sender, token, payload, retries - 1)

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
        Logger.debug("push: skipped kind=inbox user=#{user_hash(user_id)} reason=coalesced")

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

    if RisiMe.Presence.online?(user_id),
      do: log_skipped_online(user_id),
      else: send_async(user_id)

    {:noreply, state}
  end

  defp send_async(user_id) do
    Task.Supervisor.start_child(RisiMe.Push.TaskSupervisor, fn -> push_now(user_id) end)
  end
end
