defmodule RisiMe.LoadTest do
  @moduledoc """
  Dev-only load generator for the realtime path (see `mix risime.loadtest`,
  docs/decisions/009-load-test.md).

  It creates N throwaway users directly in Postgres (allowlist entry, user, bearer token; no OTP),
  with phones under the reserved `+999` prefix. Each user opens a real WebSocket (Mint), joins
  its inbox and sends DMs to random other users at a steady rate below the 20/10 s send limit.
  Recipients ack `delivered`, and `read` for every other message. It measures:

    * send→reply: `msg:send` pushed → its ok reply;
    * send→push: `msg:send` pushed → the recipient's `event` push (same BEAM, monotonic clock).

  Afterwards the users and allowlist entries are deleted (tokens cascade). Cassandra rows
  expire by TTL.
  """
  import Ecto.Query

  alias RisiMe.Accounts.{AllowlistEntry, User, UserToken}
  alias RisiMe.Repo

  @phone_prefix "+999"
  @reply_timeout_ms 5_000

  ## Users

  @doc "Creates `n` throwaway users. Returns `[%{id, token}]`."
  def create_users(n) do
    run = :rand.uniform(899_999) + 100_000
    now = DateTime.utc_now()

    for i <- 1..n do
      phone = "#{@phone_prefix}#{run}#{String.pad_leading(Integer.to_string(i), 4, "0")}"
      email = "load#{run}-#{i}@loadtest.invalid"

      attrs = %{
        phone: phone,
        email: email,
        display_name: "Load #{i}",
        company: "LoadTest"
      }

      Repo.insert!(struct(AllowlistEntry, attrs))
      user = Repo.insert!(struct(User, attrs))
      token = :crypto.strong_rand_bytes(32) |> Base.url_encode64(padding: false)

      Repo.insert!(%UserToken{
        user_id: user.id,
        token_hash: :crypto.hash(:sha256, token),
        device_name: "loadtest",
        last_seen_at: now
      })

      %{id: user.id, token: token}
    end
  end

  @doc """
  v1.6: makes each user friends with its #{10} ring neighbours on each side, so every send goes to
  a friend (friendships are deleted with the users).
  """
  def befriend_ring(users) do
    ids = Enum.map(users, & &1.id) |> List.to_tuple()
    n = tuple_size(ids)
    k = ring_size(n)

    for i <- 0..(n - 1), j <- 1..k//1, k > 0 do
      RisiMe.Social.make_friends!(elem(ids, i), elem(ids, rem(i + j, n)))
    end

    :ok
  end

  defp ring_size(n), do: min(10, div(n - 1, 2))

  @doc "Deletes every load-test user and allowlist entry (the `+999` prefix), from any run."
  def cleanup do
    # e2ee groups of load-test users (their device rows go with the users).
    load_ids =
      MapSet.new(Repo.all(from u in User, where: like(u.phone, ^"#{@phone_prefix}%"), select: u.id))

    convs =
      for conv <- Repo.all(from g in "mls_groups", select: g.conversation_id),
          {:ok, members} <- [RisiMe.MLS.members(conv)],
          Enum.any?(members, &MapSet.member?(load_ids, &1)),
          do: conv

    Repo.delete_all(from g in "mls_group_devices", where: g.conversation_id in ^convs)
    Repo.delete_all(from g in "mls_commits", where: g.conversation_id in ^convs)
    Repo.delete_all(from g in "mls_groups", where: g.conversation_id in ^convs)

    {users, _} = Repo.delete_all(from u in User, where: like(u.phone, ^"#{@phone_prefix}%"))

    {entries, _} =
      Repo.delete_all(from a in AllowlistEntry, where: like(a.phone, ^"#{@phone_prefix}%"))

    {users, entries}
  end

  ## Run

  @doc """
  Runs the load: `opts` are `:url` (http://127.0.0.1:4100), `:users`, `:duration_s` and
  `:interval_ms` (per-user send interval). Returns the report map.
  """
  def run(users, opts) do
    stats = :ets.new(:loadtest_stats, [:public, :duplicate_bag, write_concurrency: true])
    sends = :ets.new(:loadtest_sends, [:public, :set, write_concurrency: true])
    uri = URI.parse(opts[:url])
    ids = users |> Enum.map(& &1.id) |> List.to_tuple()
    parent = self()
    deadline = now_ms() + opts[:duration_s] * 1000

    pids =
      for {u, idx} <- Enum.with_index(users) do
        cfg = %{
          user: u,
          idx: idx,
          ids: ids,
          uri: uri,
          stats: stats,
          sends: sends,
          interval: opts[:interval_ms],
          e2ee: opts[:e2ee] == true,
          deadline: deadline,
          parent: parent
        }

        spawn_link(fn -> client(cfg) end)
      end

    # Wait for every client (they stop at the deadline, then drain for a few seconds).
    for _ <- pids do
      receive do
        {:client_done, _} -> :ok
      after
        opts[:duration_s] * 1000 + 30_000 -> :ok
      end
    end

    report(stats, sends, opts)
  end

  ## One simulated user

  defp client(cfg) do
    {:ok, conn, ws, ref} = connect(cfg)
    state = %{conn: conn, ws: ws, ref: ref, cfg: cfg, ref_n: 1, pending: %{}, acks: 0}
    state = send_frame(state, ["j", "j", "inbox:" <> cfg.user.id, "phx_join", %{since: nil}])
    # Spread the first sends over one interval so the load is steady.
    Process.send_after(self(), :tick, :rand.uniform(cfg.interval))
    Process.send_after(self(), :heartbeat, 30_000)
    loop(state)
  rescue
    e ->
      :ets.insert(cfg.stats, {:error, {:client_crash, Exception.message(e)}})
      send(cfg.parent, {:client_done, self()})
  end

  defp connect(cfg) do
    %URI{host: host, port: port} = cfg.uri
    {:ok, conn} = Mint.HTTP.connect(:http, host, port, protocols: [:http1])
    path = "/socket/websocket?vsn=2.0.0&token=" <> cfg.user.token
    path = if cfg.user[:device_id], do: path <> "&device_id=" <> cfg.user.device_id, else: path
    {:ok, conn, ref} = Mint.WebSocket.upgrade(:ws, conn, path, [])
    await_upgrade(conn, ref, nil, nil)
  end

  defp await_upgrade(conn, ref, status, headers) do
    receive do
      message ->
        {:ok, conn, responses} = Mint.WebSocket.stream(conn, message)

        {status, headers, done} =
          Enum.reduce(responses, {status, headers, false}, fn
            {:status, ^ref, s}, {_, h, d} -> {s, h, d}
            {:headers, ^ref, h}, {s, _, d} -> {s, h, d}
            {:done, ^ref}, {s, h, _} -> {s, h, true}
            _, acc -> acc
          end)

        if done do
          {:ok, conn, ws} = Mint.WebSocket.new(conn, ref, status, headers)
          {:ok, conn, ws, ref}
        else
          await_upgrade(conn, ref, status, headers)
        end
    after
      10_000 -> raise "websocket upgrade timed out"
    end
  end

  defp loop(state) do
    receive do
      :tick ->
        state =
          if now_ms() < state.cfg.deadline do
            Process.send_after(self(), :tick, state.cfg.interval)
            send_message(state)
          else
            Process.send_after(self(), :finish, 5_000)
            state
          end

        loop(state)

      :heartbeat ->
        Process.send_after(self(), :heartbeat, 30_000)
        loop(send_frame(state, [nil, ref(state), "phoenix", "heartbeat", %{}]) |> bump())

      :finish ->
        for {_ref, {_cmid, _t}} <- state.pending,
            do: :ets.insert(state.cfg.stats, {:error, :reply_timeout})

        Mint.HTTP.close(state.conn)
        send(state.cfg.parent, {:client_done, self()})

      message ->
        case Mint.WebSocket.stream(state.conn, message) do
          {:ok, conn, responses} ->
            state = %{state | conn: conn}
            loop(Enum.reduce(responses, state, &handle_response/2))

          {:error, conn, reason, _} ->
            :ets.insert(state.cfg.stats, {:error, {:stream, inspect(reason)}})
            loop(%{state | conn: conn})

          :unknown ->
            loop(state)
        end
    end
  end

  defp handle_response({:data, ref, data}, %{ref: ref} = state) do
    {:ok, ws, frames} = Mint.WebSocket.decode(state.ws, data)
    Enum.reduce(frames, %{state | ws: ws}, &handle_frame/2)
  end

  defp handle_response(_, state), do: state

  defp handle_frame({:text, text}, state) do
    case Jason.decode!(text) do
      [_join_ref, ref, _topic, "phx_reply", %{"status" => status, "response" => resp}] ->
        handle_reply(state, ref, status, resp)

      [_join_ref, nil, _topic, "event", %{"kind" => "message", "data" => data}] ->
        on_message(state, data)

      _ ->
        state
    end
  end

  defp handle_frame({:close, _, _}, state) do
    :ets.insert(state.cfg.stats, {:error, :closed_by_server})
    state
  end

  defp handle_frame(_, state), do: state

  defp handle_reply(state, "j", status, _resp) do
    if status != "ok", do: :ets.insert(state.cfg.stats, {:error, {:join, status}})
    state
  end

  defp handle_reply(state, ref, status, resp) do
    case Map.pop(state.pending, ref) do
      {{_cmid, t0}, pending} ->
        if status == "ok" do
          :ets.insert(state.cfg.stats, {:reply, now_us() - t0})
        else
          :ets.insert(state.cfg.stats, {:error, {:send, resp["reason"]}})
        end

        %{state | pending: pending}

      {nil, _} ->
        state
    end
  end

  # e2ee: the sender's own copy (for its other devices) is skipped.
  defp on_message(%{cfg: %{user: %{id: me}}} = state, %{"from" => me}), do: state

  defp on_message(state, %{"client_msg_id" => cmid, "message_id" => id}) do
    case :ets.take(state.cfg.sends, cmid) do
      [{^cmid, t0}] -> :ets.insert(state.cfg.stats, {:push, now_us() - t0})
      [] -> :ok
    end

    state = send_frame(state, ack(state, id, "delivered")) |> bump()
    state = %{state | acks: state.acks + 1}

    if rem(state.acks, 2) == 0,
      do: send_frame(state, ack(state, id, "read")) |> bump(),
      else: state
  end

  defp ack(state, id, status) do
    ["j", ref(state), "inbox:" <> state.cfg.user.id, "msg:ack",
     %{message_ids: [id], status: status}]
  end

  defp send_message(state) do
    %{ids: ids, idx: idx} = state.cfg
    n = tuple_size(ids)
    # v1.6: only friends can be messaged; friends are the ring neighbours (see befriend_ring/1).
    k = ring_size(n)
    offset = Enum.random(Enum.to_list(-k..-1) ++ Enum.to_list(1..k))
    to = elem(ids, rem(idx + offset + n, n))
    cmid = Uniq.UUID.uuid4()
    ref = ref(state)
    t0 = now_us()
    :ets.insert(state.cfg.sends, {cmid, t0})
    :ets.insert(state.cfg.stats, {:sent, 1})

    state
    |> send_frame([
      "j",
      ref,
      "inbox:" <> state.cfg.user.id,
      "msg:send",
      message_payload(state.cfg, cmid, to)
    ])
    |> Map.update!(:pending, &Map.put(&1, ref, {cmid, t0}))
    |> bump()
    |> expire_pending()
  end

  # Replies older than the timeout count as timeouts and are forgotten.
  defp expire_pending(state) do
    cutoff = now_us() - @reply_timeout_ms * 1000

    {old, live} = Enum.split_with(state.pending, fn {_, {_, t0}} -> t0 < cutoff end)
    for _ <- old, do: :ets.insert(state.cfg.stats, {:error, :reply_timeout})
    %{state | pending: Map.new(live)}
  end

  # Plaintext, or (--e2ee) opaque random ciphertext at the group's generation/epoch: the server
  # can't tell, so this measures its real e2ee path (group lookup, larger payloads).
  defp message_payload(%{e2ee: true}, cmid, to) do
    %{
      client_msg_id: cmid,
      to: to,
      ciphertext: Base.encode64(:crypto.strong_rand_bytes(160)),
      generation: 1,
      epoch: 1,
      client_ts: nil
    }
  end

  defp message_payload(_cfg, cmid, to),
    do: %{client_msg_id: cmid, to: to, body: "load test message", client_ts: nil}

  @doc """
  `--e2ee`: gives every user an MLS device (fake key, fake attestation; the server never checks
  either on the send path) and makes every friendship of the ring an e2ee group at epoch 1.
  Returns the users with `:device_id`. Everything is deleted with the users.
  """
  def prepare_e2ee(users) do
    now = DateTime.utc_now()

    users =
      for u <- users do
        device_id = Ecto.UUID.generate()

        Repo.insert!(%RisiMe.Devices.Device{
          user_id: u.id,
          device_id: device_id,
          platform: "android",
          mls_signature_key: :crypto.strong_rand_bytes(32),
          mls_attestation: "loadtest",
          mls_attested_at: now,
          last_seen_at: now
        })

        Map.put(u, :device_id, device_id)
      end

    ids = users |> Enum.map(& &1.id) |> List.to_tuple()
    devs = Map.new(users, &{&1.id, &1.device_id})
    n = tuple_size(ids)
    k = ring_size(n)

    for i <- 0..(n - 1), j <- 1..k//1, k > 0 do
      {a, b} = {elem(ids, i), elem(ids, rem(i + j, n))}
      conv = RisiMe.Messaging.conversation_id(a, b)

      Repo.insert_all(
        "mls_groups",
        [%{conversation_id: conv, generation: 1, epoch: 1, e2ee_since: now, updated_at: now}],
        on_conflict: :nothing
      )

      Repo.insert_all(
        "mls_group_devices",
        for(u <- [a, b], do: %{conversation_id: conv, user_id: Ecto.UUID.dump!(u), device_id: Ecto.UUID.dump!(devs[u])}),
        on_conflict: :nothing
      )
    end

    users
  end

  defp send_frame(state, frame) do
    {:ok, ws, data} = Mint.WebSocket.encode(state.ws, {:text, Jason.encode!(frame)})
    {:ok, conn} = Mint.WebSocket.stream_request_body(state.conn, state.ref, data)
    %{state | ws: ws, conn: conn}
  end

  defp ref(state), do: Integer.to_string(state.ref_n)
  defp bump(state), do: %{state | ref_n: state.ref_n + 1}

  ## Report

  @doc false
  def report(stats, sends, opts) do
    all = :ets.tab2list(stats)
    replies = for {:reply, us} <- all, do: us
    pushes = for {:push, us} <- all, do: us
    sent = Enum.count(all, &match?({:sent, _}, &1))
    errors = for {:error, e} <- all, do: e

    %{
      users: opts[:users],
      duration_s: opts[:duration_s],
      interval_ms: opts[:interval_ms],
      sent: sent,
      replies_ok: length(replies),
      pushes_received: length(pushes),
      pushes_missing: :ets.info(sends, :size),
      throughput_per_s: Float.round(sent / max(opts[:duration_s], 1), 1),
      reply_ms: percentiles(replies),
      push_ms: percentiles(pushes),
      errors: errors |> Enum.frequencies() |> Map.new(fn {k, v} -> {inspect(k), v} end)
    }
  end

  @doc "p50/p95/p99/max in milliseconds from microsecond samples."
  def percentiles([]), do: %{p50: nil, p95: nil, p99: nil, max: nil}

  def percentiles(samples) do
    sorted = samples |> Enum.sort() |> List.to_tuple()
    n = tuple_size(sorted)
    at = fn p -> elem(sorted, min(n - 1, max(0, ceil(p * n) - 1))) / 1000 end
    %{p50: at.(0.5), p95: at.(0.95), p99: at.(0.99), max: elem(sorted, n - 1) / 1000}
  end

  defp now_us, do: System.monotonic_time(:microsecond)
  defp now_ms, do: System.monotonic_time(:millisecond)
end
