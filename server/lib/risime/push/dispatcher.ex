defmodule RisiMe.Push.Dispatcher do
  @moduledoc """
  When to push (contract v1.5 §8.0, decision 028).

  `notify/1` is called for every stored inbox event. Without a joined inbox channel
  (`RisiMe.Presence.connections/1`; the 5-s presence grace alone doesn't count) the user gets at
  most one push every 10 s (`:push_coalesce_ms`):

    * no push in the last 10 s → send now;
    * otherwise → one trailing push when the 10 s are up (if still offline).

  **Watchdog.** A joined channel isn't proof of a live socket: a phone that loses its network
  without a close (Doze, a network switch, a tunnel) stays joined until the websocket timeout
  (60 s), and every push in between was skipped. So for each joined channel the dispatcher asks
  the channel to send a WebSocket **ping** after the events it pushed (no wire change: RFC 6455
  control frames, which OkHttp and browsers answer by themselves). The pong
  (`RisiMeWeb.UserSocket.handle_control/2`) means the client read the events: no push. No pong
  within `:push_watchdog_ms` (8 s) → that device gets the push (`push: watchdog …` line) and the
  dead socket is closed, so presence drops and later events take the offline path. One ping is
  out per connection; events meanwhile re-ping on its pong. Group call rings use the same probe
  with `:push_call_watchdog_ms` (4 s; 1:1 rings have the §16.8 3-s fallback). `nil` disables.

  An in-memory debounce rather than Oban jobs: a push is a best-effort wake-up, a lost one after
  a restart costs nothing (the app syncs on next open), and it keeps the send path off Postgres.
  Sends run in `RisiMe.Push.TaskSupervisor`, so a slow FCM never blocks a message send.
  """
  use GenServer

  require Logger

  alias RisiMe.{Devices, Presence, Push}

  @table __MODULE__
  @ping_bytes 8

  def start_link(_opts), do: GenServer.start_link(__MODULE__, :ok, name: __MODULE__)

  @doc """
  An inbox event was stored for `user_id`. v1.24 §24.7: `scope` `:tabs` (an Official event or a
  `chat_event`) counts only the user's `tabs` devices: only their sockets hold the push, and
  only their tokens get it.
  """
  def notify(user_id, scope \\ :all) do
    if Push.sender() != nil do
      case connections(user_id, scope) do
        # §8.0 "no live inbox channel": the presence grace period alone doesn't hold a push.
        [] ->
          GenServer.cast(__MODULE__, {:notify, user_id, scope})

        conns ->
          case watchdog_ms() do
            nil -> log_skipped_online(user_id)
            ms -> GenServer.cast(__MODULE__, {:probe, :inbox, user_id, conns, ms, %{}})
          end
      end
    end

    :ok
  end

  defp connections(user_id, :all), do: Presence.connections(user_id)

  defp connections(user_id, :risi_tools) do
    case Presence.connections(user_id) do
      [] ->
        []

      conns ->
        tools = MapSet.new(Devices.risi_tools_device_ids(user_id))
        Enum.filter(conns, fn {_, d, _} -> MapSet.member?(tools, d) end)
    end
  end

  defp connections(user_id, :tabs) do
    case Presence.connections(user_id) do
      [] ->
        []

      conns ->
        tabs = MapSet.new(Devices.tabs_device_ids(user_id))
        Enum.filter(conns, fn {_, d, _} -> MapSet.member?(tabs, d) end)
    end
  end

  @doc """
  A group ring (§16.8, server S6) found these `{device_id, token}` call targets of `user_id`
  live: probe their sockets and send the call push to each one that doesn't answer within
  `:push_call_watchdog_ms` (a half-open socket would otherwise never ring).
  """
  def watch_call(_user_id, []), do: :ok

  def watch_call(user_id, targets) do
    with sender when sender != nil <- Push.sender(),
         ms when is_integer(ms) <- call_watchdog_ms() do
      tokens = Map.new(targets)
      conns = for {_, d, _} = c <- Presence.connections(user_id), Map.has_key?(tokens, d), do: c
      probed = MapSet.new(conns, &elem(&1, 1))
      # Gone between the ring's check and now: push at once.
      push_call(for {d, tok} <- targets, not MapSet.member?(probed, d), do: tok)
      if conns != [], do: GenServer.cast(__MODULE__, {:probe, :call, user_id, conns, ms, tokens})
    end

    :ok
  end

  @doc "A pong for a watchdog ping arrived (`RisiMeWeb.UserSocket.handle_control/2`)."
  def pong(data) when is_binary(data) and byte_size(data) == @ping_bytes,
    do: GenServer.cast(__MODULE__, {:pong, data})

  def pong(_data), do: :ok

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

  @doc """
  Sends the wake-up to every device of `user_id` now (used by the debounce); `scope` `:tabs`:
  only to `tabs` devices (v1.24 §24.7).
  """
  def push_now(user_id, scope \\ :all) do
    case Push.sender() do
      nil -> :ok
      sender -> push_user(sender, user_id, scope)
    end

    :ok
  end

  defp push_user(sender, user_id, scope) do
    hash = user_hash(user_id)

    targets =
      case scope do
        :all ->
          Devices.push_targets(user_id)

        :risi_tools ->
          tools = MapSet.new(Devices.risi_tools_device_ids(user_id))
          for {d, _} = t <- Devices.push_targets(user_id), MapSet.member?(tools, d), do: t

        :tabs ->
          tabs = MapSet.new(Devices.tabs_device_ids(user_id))
          for {d, _} = t <- Devices.push_targets(user_id), MapSet.member?(tabs, d), do: t
      end

    case targets do
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
  defp watchdog_ms, do: Application.get_env(:risime, :push_watchdog_ms, 8_000)
  defp call_watchdog_ms, do: Application.get_env(:risime, :push_call_watchdog_ms, 4_000)

  ## Server
  # ETS row: {user_id, last_sent_ms, trailing_timer | nil, trailing_scope}
  # State: probes %{{channel_pid, kind} => probe}, ids %{ping data => probe key}.

  @impl true
  def init(:ok) do
    :ets.new(@table, [:named_table, :protected, :set])
    {:ok, %{probes: %{}, ids: %{}}}
  end

  # One outstanding ping per connection and kind. An event while one is out marks it `again`:
  # its pong then starts a new ping, so the last event is always covered by a ping sent after it.
  @impl true
  def handle_cast({:probe, kind, user_id, conns, ms, tokens}, state) do
    state =
      Enum.reduce(conns, state, fn {chan, device_id, transport}, acc ->
        key = {chan, kind}

        case acc.probes do
          %{^key => probe} ->
            put_in(acc.probes[key], %{probe | again: true})

          _ ->
            start_probe(acc, key, %{
              user_id: user_id,
              device_id: device_id,
              transport: transport,
              ms: ms,
              token: tokens[device_id]
            })
        end
      end)

    {:noreply, state}
  end

  def handle_cast({:pong, data}, state) do
    case state.ids do
      %{^data => {chan, kind} = key} ->
        probe = state.probes[key]
        Process.cancel_timer(probe.timer)
        state = drop_probe(state, key)

        if probe.again and Process.alive?(chan) do
          {:noreply, start_probe(state, key, probe)}
        else
          if kind == :inbox, do: log_skipped_online(probe.user_id)
          {:noreply, state}
        end

      _ ->
        {:noreply, state}
    end
  end

  @impl true
  def handle_cast({:notify, user_id, scope}, state) do
    now = System.monotonic_time(:millisecond)
    window = coalesce_ms()

    case :ets.lookup(@table, user_id) do
      [{_, last, timer, pending}] when now - last < window ->
        # Within the window: make sure exactly one trailing push is scheduled; it covers every
        # coalesced event (any `:all` event widens a `:tabs` one).
        Logger.debug("push: skipped kind=inbox user=#{user_hash(user_id)} reason=coalesced")

        scope = if timer == nil, do: scope, else: merge(pending, scope)

        t =
          timer || Process.send_after(self(), {:trailing, user_id}, last + window - now)

        :ets.insert(@table, {user_id, last, t, scope})

      _ ->
        send_async(user_id, scope)
        :ets.insert(@table, {user_id, now, nil, nil})
    end

    {:noreply, state}
  end

  @impl true
  def handle_info({:trailing, user_id}, state) do
    scope =
      case :ets.lookup(@table, user_id) do
        [{_, _, _, s}] when s != nil -> s
        _ -> :all
      end

    :ets.insert(@table, {user_id, System.monotonic_time(:millisecond), nil, nil})

    case {connections(user_id, scope), watchdog_ms()} do
      {[], _} ->
        send_async(user_id, scope)
        {:noreply, state}

      {_conns, nil} ->
        log_skipped_online(user_id)
        {:noreply, state}

      {conns, ms} ->
        handle_cast({:probe, :inbox, user_id, conns, ms, %{}}, state)
    end
  end

  # No pong in time: the socket is dead but still joined (a lost network keeps it "live" until
  # the websocket timeout). Push this device as if it were offline and drop the socket, so the
  # next events take the offline path at once (a live client just reconnects).
  def handle_info({:watchdog, key, data}, state) do
    case state.probes do
      %{^key => %{data: ^data} = probe} ->
        {_chan, kind} = key
        waited = System.monotonic_time(:millisecond) - probe.started

        Logger.info(
          "push: watchdog kind=#{kind} user=#{user_hash(probe.user_id)} " <>
            "device=#{device_tag(probe.device_id)} waited_ms=#{waited}"
        )

        if kind == :inbox, do: mark_sent(probe.user_id)
        watchdog_push(kind, probe)
        if is_pid(probe.transport), do: send(probe.transport, disconnect())
        {:noreply, drop_probe(state, key)}

      _ ->
        {:noreply, state}
    end
  end

  defp start_probe(state, {chan, _kind} = key, attrs) do
    data = :crypto.strong_rand_bytes(@ping_bytes)
    # Through the channel process, so the ping follows the events it pushed on the wire.
    send(chan, {:push_watchdog_ping, data})
    timer = Process.send_after(self(), {:watchdog, key, data}, attrs.ms)

    probe =
      Map.merge(attrs, %{
        data: data,
        timer: timer,
        started: System.monotonic_time(:millisecond),
        again: false
      })

    %{state | probes: Map.put(state.probes, key, probe), ids: Map.put(state.ids, data, key)}
  end

  defp drop_probe(state, key) do
    {probe, probes} = Map.pop(state.probes, key)
    %{state | probes: probes, ids: Map.delete(state.ids, probe.data)}
  end

  # The watchdog push counts for the 10-s window, so the offline path doesn't push again at once.
  defp mark_sent(user_id) do
    {timer, scope} =
      case :ets.lookup(@table, user_id) do
        [{_, _, t, s}] -> {t, s}
        [] -> {nil, nil}
      end

    :ets.insert(@table, {user_id, System.monotonic_time(:millisecond), timer, scope})
  end

  defp watchdog_push(:call, %{token: token}) when is_binary(token), do: push_call([token])
  defp watchdog_push(:call, _probe), do: :ok

  defp watchdog_push(:inbox, %{user_id: user_id, device_id: device_id}) do
    Task.Supervisor.start_child(RisiMe.Push.TaskSupervisor, fn ->
      with sender when sender != nil <- Push.sender() do
        hash = user_hash(user_id)

        # A legacy socket without a device id stands for every device of the user.
        case for {d, tok} <- Devices.push_targets(user_id), device_id in [nil, d], do: {d, tok} do
          [] -> Logger.info("push: none kind=inbox user=#{hash} reason=no_token")
          targets -> for {d, tok} <- targets, do: deliver(sender, tok, Push.payload(), {hash, d})
        end
      end
    end)
  end

  defp disconnect, do: %Phoenix.Socket.Broadcast{topic: nil, event: "disconnect", payload: %{}}

  defp send_async(user_id, scope) do
    Task.Supervisor.start_child(RisiMe.Push.TaskSupervisor, fn -> push_now(user_id, scope) end)
  end

  # The wider scope wins: :all > :tabs > :risi_tools (v1.25 §25.2).
  defp merge(:all, _), do: :all
  defp merge(_, :all), do: :all
  defp merge(:tabs, _), do: :tabs
  defp merge(_, :tabs), do: :tabs
  defp merge(_, scope), do: scope
end
