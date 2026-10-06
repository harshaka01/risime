defmodule RisiMeWeb.ApiTest do
  use RisiMeWeb.ConnCase, async: true

  import RisiMe.Fixtures
  import Swoosh.TestAssertions
  import Ecto.Query, only: [from: 2]

  defp authed(conn, token), do: put_req_header(conn, "authorization", "Bearer " <> token)

  test "request + verify + me", %{conn: conn} do
    entry = allowlist_entry(display_name: "Harsha", company: "CodeGen")

    conn1 = post(conn, ~p"/api/v1/auth/request", %{phone: entry.phone, email: entry.email})
    assert json_response(conn1, 200) == %{"status" => "sent", "expires_in" => 300}
    code = receive_code()

    conn2 =
      post(conn, ~p"/api/v1/auth/verify", %{phone: entry.phone, code: code, device_name: "Pixel"})

    assert %{"token" => token, "user" => user} = json_response(conn2, 200)
    assert Map.keys(user) |> Enum.sort() == ~w(company display_name id phone phone_verified)
    assert user["phone"] == entry.phone

    assert %{"user" => ^user} = conn |> authed(token) |> get(~p"/api/v1/me") |> json_response(200)

    assert %{"user" => %{"display_name" => "H"}} =
             conn
             |> authed(token)
             |> patch(~p"/api/v1/me", %{display_name: " H "})
             |> json_response(200)

    assert %{"error" => %{"code" => "invalid_display_name"}} =
             conn
             |> authed(token)
             |> patch(~p"/api/v1/me", %{display_name: ""})
             |> json_response(422)
  end

  # Regression (2026-10-06 smoke test): first-time verify over HTTP for an allowlisted person who
  # has never logged in must create the user from the allowlist entry (and not 500); a later
  # login reuses that user.
  test "first-time verify creates the user from the allowlist; later logins reuse it", %{
    conn: conn
  } do
    entry =
      allowlist_entry(phone: unique_phone(), display_name: "Shenika Herath", company: "CodeGen")

    refute RisiMe.Repo.get_by(RisiMe.Accounts.User, phone: entry.phone)

    login = fn ->
      conn
      |> post(~p"/api/v1/auth/request", %{phone: entry.phone, email: entry.email})
      |> json_response(200)

      code = receive_code()

      conn
      |> post(~p"/api/v1/auth/verify", %{phone: entry.phone, code: code, device_name: "Pixel 7"})
      |> json_response(200)
    end

    assert %{"token" => token1, "user" => user} = login.()

    assert user == %{
             "id" => user["id"],
             "phone" => entry.phone,
             "display_name" => "Shenika Herath",
             "company" => "CodeGen",
             "phone_verified" => true
           }

    db_user = RisiMe.Repo.get_by!(RisiMe.Accounts.User, phone: entry.phone)
    assert db_user.id == user["id"] and db_user.email == entry.email

    assert %{"token" => token2, "user" => ^user} = login.()
    assert token1 != token2
    assert [_] = RisiMe.Repo.all(from u in RisiMe.Accounts.User, where: u.phone == ^entry.phone)
  end

  test "request errors", %{conn: conn} do
    assert %{"error" => %{"code" => "invalid_phone", "message" => _}} =
             conn
             |> post(~p"/api/v1/auth/request", %{phone: "123", email: "a@b.co"})
             |> json_response(422)

    assert %{"error" => %{"code" => "invalid_email"}} =
             conn
             |> post(~p"/api/v1/auth/request", %{phone: unique_phone(), email: "x"})
             |> json_response(422)

    phone = unique_phone()

    for _ <- 1..3 do
      conn
      |> post(~p"/api/v1/auth/request", %{phone: phone, email: "a@b.co"})
      |> json_response(200)
    end

    assert %{"error" => %{"code" => "rate_limited"}} =
             conn
             |> post(~p"/api/v1/auth/request", %{phone: phone, email: "a@b.co"})
             |> json_response(429)

    assert_no_email_sent()
  end

  test "verify errors", %{conn: conn} do
    entry = allowlist_entry()
    post(conn, ~p"/api/v1/auth/request", %{phone: entry.phone, email: entry.email})
    _code = receive_code()

    assert %{"error" => %{"code" => "invalid_code"}} =
             conn
             |> post(~p"/api/v1/auth/verify", %{phone: entry.phone, code: "abc"})
             |> json_response(401)

    for _ <- 1..4, do: post(conn, ~p"/api/v1/auth/verify", %{phone: entry.phone, code: "x"})

    assert %{"error" => %{"code" => "too_many_attempts"}} =
             conn
             |> post(~p"/api/v1/auth/verify", %{phone: entry.phone, code: "000000"})
             |> json_response(429)
  end

  test "expired code is 410", %{conn: conn} do
    entry = allowlist_entry()
    post(conn, ~p"/api/v1/auth/request", %{phone: entry.phone, email: entry.email})
    code = receive_code()

    RisiMe.Repo.update_all(RisiMe.Accounts.OtpChallenge,
      set: [expires_at: DateTime.add(DateTime.utc_now(), -1, :second)]
    )

    assert %{"error" => %{"code" => "expired"}} =
             conn
             |> post(~p"/api/v1/auth/verify", %{phone: entry.phone, code: code})
             |> json_response(410)
  end

  test "authenticated endpoints need a valid token; logout revokes it", %{conn: conn} do
    assert %{"error" => %{"code" => "invalid_token"}} =
             conn |> get(~p"/api/v1/me") |> json_response(401)

    assert conn |> authed("nope") |> get(~p"/api/v1/contacts") |> json_response(401)

    %{token: token} = logged_in_user()
    assert conn |> authed(token) |> post(~p"/api/v1/auth/logout") |> response(204)
    assert conn |> authed(token) |> get(~p"/api/v1/me") |> json_response(401)
  end

  test "contacts", %{conn: conn} do
    %{token: token} = logged_in_user(display_name: "A")
    %{user: b} = logged_in_user(display_name: "B", company: "Rise")
    c = allowlist_entry(display_name: "C")

    assert %{"contacts" => contacts} =
             conn |> authed(token) |> get(~p"/api/v1/contacts") |> json_response(200)

    assert %{
             "phone" => b.phone,
             "display_name" => "B",
             "company" => "Rise",
             "user_id" => b.id,
             "registered" => true
           } in contacts

    c_phone = c.phone

    assert %{"phone" => ^c_phone, "user_id" => nil, "registered" => false} =
             Enum.find(contacts, &(&1["phone"] == c_phone))
  end

  test "unknown route renders the contract error shape", %{conn: conn} do
    assert %{"error" => %{"code" => "not_found"}} =
             conn |> get("/api/v1/nope") |> json_response(404)
  end
end
