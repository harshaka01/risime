defmodule RisiMeWeb.OIDCApiTest do
  @moduledoc "Contract v1.3 §6.1 over REST, with locally signed access tokens."
  use RisiMeWeb.ConnCase, async: false

  import RisiMe.Fixtures
  import RisiMe.OIDCHelpers
  import Ecto.Query, only: [from: 2]

  alias RisiMe.Accounts
  alias RisiMe.Accounts.User
  alias RisiMe.Repo

  setup do
    RisiMe.Auth.clear_cache()

    on_exit(fn ->
      Application.put_env(:risime, :dev_local_auth, true)
      put_oidc(enabled: true)
    end)
  end

  defp authed(conn, token), do: put_req_header(conn, "authorization", "Bearer " <> token)
  defp me(conn, token), do: conn |> authed(token) |> get(~p"/api/v1/me")

  describe "GET /auth/config" do
    test "lists the enabled modes; issuer and client_id only with oidc", %{conn: conn} do
      assert get(conn, ~p"/api/v1/auth/config") |> json_response(200) == %{
               "modes" => ["oidc", "dev"],
               "issuer" => "https://risicloud.ai/realms/aoa",
               "client_id" => "risime",
               "phone_verification" => "off",
               "signup" => "invite",
               "backup" => "on"
             }

      put_oidc(enabled: false)

      assert get(conn, ~p"/api/v1/auth/config") |> json_response(200) == %{
               "modes" => ["dev"],
               "phone_verification" => "off",
               "signup" => "invite",
               "backup" => "on"
             }

      put_oidc(enabled: true)
      Application.put_env(:risime, :dev_local_auth, false)
      assert %{"modes" => ["oidc"]} = get(conn, ~p"/api/v1/auth/config") |> json_response(200)
    end
  end

  describe "DEV_LOCAL_AUTH off" do
    test "dev login routes are 404 and opaque tokens are 401; JWTs still work", %{conn: conn} do
      %{token: dev_token} = logged_in_user()
      entry = allowlist_entry()
      Application.put_env(:risime, :dev_local_auth, false)

      assert post(conn, ~p"/api/v1/auth/request", %{phone: entry.phone, email: entry.email})
             |> json_response(404)

      assert post(conn, ~p"/api/v1/auth/verify", %{phone: entry.phone, code: "123456"})
             |> json_response(404)

      assert %{"error" => %{"code" => "invalid_token"}} =
               me(conn, dev_token) |> json_response(401)

      assert me(conn, access_token(entry.email)) |> json_response(200)
    end
  end

  test "JWTs are 401 while OIDC is disabled", %{conn: conn} do
    entry = allowlist_entry()
    put_oidc(enabled: false)

    assert %{"error" => %{"code" => "invalid_token"}} =
             me(conn, access_token(entry.email)) |> json_response(401)
  end

  describe "mapping" do
    test "first use creates the user from the allowlist (email case-insensitive), bound to sub",
         %{conn: conn} do
      entry = allowlist_entry(display_name: "Kamal", company: "Rise")
      token = access_token(String.upcase(entry.email), %{"sub" => "sub-kamal"})

      assert %{"user" => user} = me(conn, token) |> json_response(200)
      assert user["phone"] == entry.phone and user["display_name"] == "Kamal"
      assert Accounts.get_user(user["id"]).keycloak_sub == "sub-kamal"

      # Same user on later requests, cached or not.
      assert %{"user" => ^user} = me(conn, token) |> json_response(200)
      RisiMe.Auth.clear_cache()

      assert %{"user" => ^user} =
               me(conn, access_token(entry.email, %{"sub" => "sub-kamal"})) |> json_response(200)
    end

    test "an existing (dev-created) user with no binding is linked, not duplicated", %{conn: conn} do
      %{user: user, entry: entry} = logged_in_user()

      assert %{"user" => %{"id" => id}} =
               me(conn, access_token(entry.email, %{"sub" => "s1"})) |> json_response(200)

      assert id == user.id
      assert Accounts.get_user(user.id).keycloak_sub == "s1"
    end

    test "an email that isn't allowlisted is 403 not_allowlisted", %{conn: conn} do
      body = me(conn, access_token("stranger@example.com")) |> json_response(403)

      assert body == %{
               "error" => %{
                 "code" => "not_allowlisted",
                 "message" => "This email is not on the RisiMe allowlist"
               }
             }
    end

    test "a phone bound to another sub is 409 identity_conflict until an admin re-binds",
         %{conn: conn} do
      entry = allowlist_entry()
      assert me(conn, access_token(entry.email, %{"sub" => "first"})) |> json_response(200)

      assert %{"error" => %{"code" => "identity_conflict"}} =
               me(conn, access_token(entry.email, %{"sub" => "second"})) |> json_response(409)

      {:ok, "first"} = Accounts.rebind(entry.phone)
      assert me(conn, access_token(entry.email, %{"sub" => "second"})) |> json_response(200)
    end

    test "once bound, sub wins over a later email change", %{conn: conn} do
      entry = allowlist_entry()
      other = allowlist_entry()

      %{"user" => %{"id" => id}} =
        me(conn, access_token(entry.email, %{"sub" => "k1"})) |> json_response(200)

      RisiMe.Auth.clear_cache()

      assert %{"user" => %{"id" => ^id}} =
               me(conn, access_token(other.email, %{"sub" => "k1"})) |> json_response(200)
    end

    test "a user whose phone left the allowlist is 403 at the next uncached request",
         %{conn: conn} do
      entry = allowlist_entry()
      token = access_token(entry.email)
      assert me(conn, token) |> json_response(200)

      Repo.delete!(entry)
      RisiMe.Auth.clear_cache()
      assert %{"error" => %{"code" => "not_allowlisted"}} = me(conn, token) |> json_response(403)
    end

    test "concurrent first requests bind one user without a conflict" do
      entry = allowlist_entry()
      token = access_token(entry.email, %{"sub" => "racer"})

      results =
        1..8
        |> Enum.map(fn _ ->
          Task.async(fn ->
            Accounts.map_identity(%{sub: "racer", email: entry.email})
          end)
        end)
        |> Task.await_many()

      assert [{:ok, %User{id: id}}] = Enum.uniq_by(results, fn {:ok, u} -> u.id end)
      assert Repo.aggregate(from(u in User, where: u.phone == ^entry.phone), :count) == 1
      assert {:ok, %{user: %{id: ^id}}} = RisiMe.Auth.authenticate(token)
    end
  end

  test "bad tokens are 401 invalid_token", %{conn: conn} do
    entry = allowlist_entry()

    for token <- [
          "nope",
          access_token(entry.email, %{"exp" => System.os_time(:second) - 120}),
          access_token(entry.email, %{"iss" => "https://evil.example"}),
          access_token(entry.email, %{"typ" => "ID"})
        ] do
      assert %{
               "error" => %{
                 "code" => "invalid_token",
                 "message" => "Missing, invalid or expired token"
               }
             } =
               me(conn, token) |> json_response(401)
    end

    assert get(conn, ~p"/api/v1/me") |> json_response(401)
  end

  test "logout with a JWT is a 204 no-op", %{conn: conn} do
    entry = allowlist_entry()
    token = access_token(entry.email)
    assert conn |> authed(token) |> post(~p"/api/v1/auth/logout") |> response(204)
    assert me(conn, token) |> json_response(200)
  end
end
