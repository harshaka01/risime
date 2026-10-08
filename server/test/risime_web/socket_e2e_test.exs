defmodule RisiMeWeb.SocketE2ETest do
  @moduledoc """
  End to end through the real endpoint: a Bandit listener on a random loopback port, a Mint
  WebSocket client, and a locally signed access token in the `Authorization` header (§6.2).
  """
  use RisiMe.DataCase, async: false

  import RisiMe.Fixtures
  import RisiMe.OIDCHelpers

  setup do
    RisiMe.Auth.clear_cache()

    {:ok, pid} =
      start_supervised(
        {Bandit, plug: RisiMeWeb.Endpoint, ip: :loopback, port: 0, startup_log: false}
      )

    {:ok, {_ip, port}} = ThousandIsland.listener_info(pid)
    %{port: port}
  end

  defp upgrade(port, headers, path \\ "/socket/websocket?vsn=2.0.0") do
    {:ok, conn} = Mint.HTTP.connect(:http, "127.0.0.1", port, protocols: [:http1])
    {:ok, conn, ref} = Mint.WebSocket.upgrade(:ws, conn, path, headers)
    {conn, status, resp_headers, leftover} = await(conn, ref, nil, [], [])

    case status do
      101 ->
        {:ok, conn, ws} = Mint.WebSocket.new(conn, ref, status, resp_headers)
        {:ok, %{conn: conn, ws: ws, ref: ref, buffer: leftover}}

      other ->
        Mint.HTTP.close(conn)
        {:error, other}
    end
  end

  defp await(conn, ref, status, headers, data) do
    receive do
      msg ->
        {:ok, conn, responses} = Mint.WebSocket.stream(conn, msg)

        {status, headers, data, done} =
          Enum.reduce(responses, {status, headers, data, false}, fn
            {:status, ^ref, s}, {_, h, d, dn} -> {s, h, d, dn}
            {:headers, ^ref, h}, {s, _, d, dn} -> {s, h, d, dn}
            {:data, ^ref, x}, {s, h, d, dn} -> {s, h, [x | d], dn}
            {:done, ^ref}, {s, h, d, _} -> {s, h, d, true}
            _, acc -> acc
          end)

        if done or (status == 101 and headers != []),
          do: {conn, status, headers, data},
          else: await(conn, ref, status, headers, data)
    after
      5_000 -> flunk("no upgrade response")
    end
  end

  defp send_frame(s, frame) do
    {:ok, ws, data} = Mint.WebSocket.encode(s.ws, {:text, Jason.encode!(frame)})
    {:ok, conn} = Mint.WebSocket.stream_request_body(s.conn, s.ref, data)
    %{s | ws: ws, conn: conn}
  end

  defp recv_frame(s) do
    receive do
      msg ->
        {:ok, conn, responses} = Mint.WebSocket.stream(s.conn, msg)
        s = %{s | conn: conn}

        frames =
          for {:data, _, data} <- responses, reduce: [] do
            acc ->
              {:ok, _ws, fs} = Mint.WebSocket.decode(s.ws, data)
              acc ++ fs
          end

        case frames do
          [{:text, text} | _] -> {s, Jason.decode!(text)}
          _ -> recv_frame(s)
        end
    after
      5_000 -> flunk("no frame")
    end
  end

  test "Authorization: Bearer on the upgrade authenticates; join works", %{port: port} do
    entry = allowlist_entry()
    token = access_token(entry.email)

    assert {:ok, s} = upgrade(port, [{"authorization", "Bearer " <> token}])
    {:ok, %{user: user}} = RisiMe.Auth.authenticate(token)

    s = send_frame(s, ["1", "1", "inbox:" <> user.id, "phx_join", %{}])
    {_s, reply} = recv_frame(s)

    assert ["1", "1", _, "phx_reply", %{"status" => "ok", "response" => %{"events" => []}}] =
             reply
  end

  test "token= still works; bad or missing tokens get 403", %{port: port} do
    entry = allowlist_entry()
    token = access_token(entry.email)
    assert {:ok, _} = upgrade(port, [], "/socket/websocket?vsn=2.0.0&token=" <> token)
    assert {:error, 403} = upgrade(port, [{"authorization", "Bearer nope"}])
    assert {:error, 403} = upgrade(port, [])

    assert {:error, 403} =
             upgrade(port, [{"authorization", "Bearer " <> access_token("x@example.com")}])
  end

  # Next ping or close frame (the push watchdog's probe), skipping text frames.
  defp recv_control(s) do
    receive do
      msg ->
        {:ok, conn, responses} = Mint.WebSocket.stream(s.conn, msg)
        s = %{s | conn: conn}

        frames =
          for {:data, _, data} <- responses, reduce: [] do
            acc ->
              {:ok, _ws, fs} = Mint.WebSocket.decode(s.ws, data)
              acc ++ fs
          end

        case Enum.find(frames, &(elem(&1, 0) in [:ping, :close])) do
          nil -> recv_control(s)
          {:ping, data} -> {s, {:ping, data}}
          _close -> {s, :close}
        end
    after
      5_000 -> flunk("no control frame")
    end
  end

  defp send_control(s, frame) do
    {:ok, ws, data} = Mint.WebSocket.encode(s.ws, frame)
    {:ok, conn} = Mint.WebSocket.stream_request_body(s.conn, s.ref, data)
    %{s | ws: ws, conn: conn}
  end

  describe "push watchdog over a real socket" do
    setup do
      test_push!()
      Application.put_env(:risime, :push_watchdog_ms, 300)
      on_exit(fn -> Application.put_env(:risime, :push_watchdog_ms, nil) end)

      entry = allowlist_entry()
      token = access_token(entry.email)
      {:ok, %{user: user}} = RisiMe.Auth.authenticate(token)
      device_id = Ecto.UUID.generate()
      push = push_token("tok-e2e")

      {:ok, nil} =
        RisiMe.Devices.register(user.id, device_id, %{
          "platform" => "android",
          "push_token" => push
        })

      %{token: token, user: user, device_id: device_id, push: push}
    end

    defp join(ctx) do
      path = "/socket/websocket?vsn=2.0.0&device_id=" <> ctx.device_id
      {:ok, s} = upgrade(ctx.port, [{"authorization", "Bearer " <> ctx.token}], path)
      s = send_frame(s, ["1", "1", "inbox:" <> ctx.user.id, "phx_join", %{}])
      {s, ["1", "1", _, "phx_reply", %{"status" => "ok"}]} = recv_frame(s)
      s
    end

    test "the pong (the client's WebSocket layer answers it) holds the push", ctx do
      s = join(ctx)
      RisiMe.Push.notify(ctx.user.id)
      {s, {:ping, data}} = recv_control(s)
      assert byte_size(data) == 8
      _s = send_control(s, {:pong, data})
      push = ctx.push
      refute_receive {:push, ^push, _}, 700
    end

    test "no pong: the push goes out and the dead socket is closed", ctx do
      s = join(ctx)
      RisiMe.Push.notify(ctx.user.id)
      {s, {:ping, _data}} = recv_control(s)
      push = ctx.push
      assert_receive {:push, ^push, %{"type" => "inbox"}}, 1_000
      assert {_s, :close} = recv_control(s)
    end
  end
end
