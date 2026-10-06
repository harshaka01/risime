defmodule RisiMeWeb.ClientIP do
  @moduledoc """
  The client's IP for rate limits and the auth log (decision 024).

  `X-Forwarded-For` is trusted **only when the TCP peer is loopback**: Caddy on the same host
  terminates TLS and sets the header to the real client. Its last entry is used (the one the
  local proxy wrote). From any other peer the header is ignored and the peer address is used.
  """

  @doc "Client IP of a `Plug.Conn`, as a string."
  def from_conn(%Plug.Conn{remote_ip: peer} = conn),
    do: resolve(peer, Plug.Conn.get_req_header(conn, "x-forwarded-for"))

  @doc "Client IP from socket `connect_info` (`:peer_data` and `:x_headers`)."
  def from_connect_info(info) when is_map(info) do
    peer = get_in(info, [:peer_data, :address])
    xff = for {"x-forwarded-for", v} <- Map.get(info, :x_headers, []), do: v
    if peer, do: resolve(peer, xff), else: "unknown"
  end

  def from_connect_info(_), do: "unknown"

  defp resolve(peer, xff) do
    with true <- loopback?(peer),
         [_ | _] = values <- xff,
         last when is_binary(last) <- values |> Enum.join(",") |> String.split(",") |> List.last(),
         {:ok, ip} <- last |> String.trim() |> String.to_charlist() |> :inet.parse_address() do
      ip_string(ip)
    else
      _ -> ip_string(peer)
    end
  end

  defp loopback?({127, _, _, _}), do: true
  defp loopback?({0, 0, 0, 0, 0, 0, 0, 1}), do: true
  defp loopback?(_), do: false

  defp ip_string(ip) when is_tuple(ip), do: ip |> :inet.ntoa() |> to_string()
  defp ip_string(_), do: "unknown"
end
