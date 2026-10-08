defmodule RisiMeWeb.AuthLogTest do
  @moduledoc "Decision 024: client IP, per-IP limits on /auth/*, the fail2ban auth log."
  use RisiMeWeb.ConnCase, async: false

  import RisiMe.Fixtures
  import RisiMe.OIDCHelpers

  alias RisiMe.AuthLog
  alias RisiMeWeb.ClientIP

  @line ~r/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ risime auth_failure ip=(\S+) kind=([a-z_]+) path=(\S+)$/

  defp from_ip(conn, ip), do: %{conn | remote_ip: ip}

  defp lines_for(ip_string) do
    AuthLog.path()
    |> File.read!()
    |> String.split("\n", trim: true)
    |> Enum.filter(&String.contains?(&1, "ip=#{ip_string} "))
  end

  describe "ClientIP" do
    test "X-Forwarded-For only from a loopback peer; its last entry", %{conn: conn} do
      xff = fn c, v -> put_req_header(c, "x-forwarded-for", v) end

      assert ClientIP.from_conn(conn |> from_ip({127, 0, 0, 1}) |> xff.("203.0.113.9")) ==
               "203.0.113.9"

      assert ClientIP.from_conn(conn |> from_ip({127, 0, 0, 1}) |> xff.("10.0.0.1, 198.51.100.7")) ==
               "198.51.100.7"

      assert ClientIP.from_conn(conn |> from_ip({0, 0, 0, 0, 0, 0, 0, 1}) |> xff.("2001:db8::5")) ==
               "2001:db8::5"

      # Not from loopback: the header is ignored.
      assert ClientIP.from_conn(conn |> from_ip({198, 51, 100, 1}) |> xff.("203.0.113.9")) ==
               "198.51.100.1"

      # Garbage in the header: the peer.
      assert ClientIP.from_conn(conn |> from_ip({127, 0, 0, 1}) |> xff.("not an ip")) ==
               "127.0.0.1"

      assert ClientIP.from_conn(conn |> from_ip({127, 0, 0, 1})) == "127.0.0.1"
    end

    test "from socket connect_info" do
      info = %{
        peer_data: %{address: {127, 0, 0, 1}, port: 1, ssl_cert: nil},
        x_headers: [{"x-forwarded-for", "203.0.113.20"}]
      }

      assert ClientIP.from_connect_info(info) == "203.0.113.20"

      assert ClientIP.from_connect_info(%{info | peer_data: %{address: {192, 0, 2, 1}}}) ==
               "192.0.2.1"

      assert ClientIP.from_connect_info(%{}) == "unknown"
    end
  end

  describe "per-IP limits" do
    test "POST /auth/request: 10 per IP per 15 min, then 429 + Retry-After + auth log", %{
      conn: conn
    } do
      ip = unique_ip()
      conn = from_ip(conn, ip)

      for i <- 1..10 do
        phone = "+9477#{String.pad_leading("#{i}", 7, "0")}"

        assert post(conn, ~p"/api/v1/auth/request", %{phone: phone, email: "x#{i}@example.com"}).status ==
                 200
      end

      resp =
        post(conn, ~p"/api/v1/auth/request", %{phone: "+94770000099", email: "y@example.com"})

      assert %{"error" => %{"code" => "rate_limited"}} = json_response(resp, 429)
      assert [retry] = get_resp_header(resp, "retry-after")
      assert String.to_integer(retry) in 1..900

      ip_s = ip |> :inet.ntoa() |> to_string()
      assert [line] = lines_for(ip_s)
      assert [_, ^ip_s, "rate_limited", "/api/v1/auth/request"] = Regex.run(@line, line)
    end

    test "behind the local proxy, each X-Forwarded-For client has its own budget", %{conn: conn} do
      conn = from_ip(conn, {127, 0, 0, 1})
      {p1, p2} = {unique_phone(), unique_phone()}

      for i <- 1..11 do
        c = put_req_header(conn, "x-forwarded-for", "198.51.100.#{i}")

        assert post(c, ~p"/api/v1/auth/request", %{phone: p1, email: "z@example.com"}).status in [
                 200,
                 429
               ]
      end

      # Distinct client IPs: only the per-phone limit (3 per 15 min) applied, not the per-IP one.
      c = put_req_header(conn, "x-forwarded-for", "198.51.100.200")

      assert post(c, ~p"/api/v1/auth/request", %{phone: p2, email: "w@example.com"}).status ==
               200
    end

    test "POST /auth/verify: 20 per IP per 15 min", %{conn: conn} do
      conn = from_ip(conn, unique_ip())

      for _ <- 1..20 do
        assert post(conn, ~p"/api/v1/auth/verify", %{phone: "+94773333333", code: "000000"}).status ==
                 401
      end

      assert post(conn, ~p"/api/v1/auth/verify", %{phone: "+94773333333", code: "000000"}).status ==
               429
    end
  end

  describe "auth log" do
    test "the line format, with nothing secret in it", %{conn: conn} do
      ip = unique_ip()
      ip_s = ip |> :inet.ntoa() |> to_string()
      conn = from_ip(conn, ip)

      entry = allowlist_entry()
      :ok = RisiMe.Accounts.request_otp(entry.phone, entry.email)
      code = receive_code()
      post(conn, ~p"/api/v1/auth/verify", %{phone: entry.phone, code: wrong(code)})

      token = access_token("stranger@example.com")
      conn |> put_req_header("authorization", "Bearer " <> token) |> get(~p"/api/v1/me")
      conn |> put_req_header("authorization", "Bearer nope") |> get(~p"/api/v1/me")

      lines = lines_for(ip_s)
      kinds = for l <- lines, [_, ^ip_s, kind, _] <- [Regex.run(@line, l)], do: kind
      assert Enum.sort(kinds) == ["invalid_code", "invalid_token", "not_allowlisted"]

      text = Enum.join(lines, "\n")

      for secret <- [entry.phone, entry.email, code, wrong(code), token, "stranger"],
          do: refute(text =~ secret)
    end

    test "every kind is accepted and values are sanitised" do
      for kind <- AuthLog.kinds(), do: assert(:ok = AuthLog.failure("192.0.2.77", kind, "/x"))

      line = AuthLog.line("1.2.3.4\nkind=fake", :invalid_token, "/api/v1/me?token=abc def")
      assert line =~ ~r/ip=1\.2\.3\.4kindfake kind=invalid_token path=\/api\/v1\/metokenabcdef$/
      refute line =~ "\n"
    end

    test "refused sockets are logged as socket_refused with the client IP" do
      info = %{
        peer_data: %{address: {127, 0, 0, 1}},
        x_headers: [{"x-forwarded-for", "192.0.2.88"}]
      }

      assert :error = RisiMeWeb.UserSocket.connect(%{"token" => "nope"}, %Phoenix.Socket{}, info)
      assert [line] = lines_for("192.0.2.88")
      assert [_, "192.0.2.88", "socket_refused", "/socket/websocket"] = Regex.run(@line, line)
    end
  end

  defp wrong(code),
    do:
      code
      |> String.to_integer()
      |> Kernel.+(1)
      |> rem(1_000_000)
      |> Integer.to_string()
      |> String.pad_leading(6, "0")
end
