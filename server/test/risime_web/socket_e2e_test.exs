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
end
