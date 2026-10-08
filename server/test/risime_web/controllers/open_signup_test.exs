defmodule RisiMeWeb.OpenSignupTest do
  @moduledoc "Contract v1.20 §21: open sign-up behind OPEN_SIGNUP, phone_confirmed and matching."
  use RisiMeWeb.ConnCase, async: false

  import ExUnit.CaptureLog
  require Logger
  import Ecto.Query, only: [from: 2]
  import RisiMe.Fixtures
  import RisiMe.OIDCHelpers

  alias RisiMe.{Accounts, AuthLog, Messaging, Repo}
  alias RisiMe.Accounts.User

  @loose [open: true, per_ip_hour: 10_000, per_sub_day: 10_000, global_per_day: 10_000]

  setup do
    RisiMe.Auth.clear_cache()
    previous = Application.get_env(:risime, :signup)
    Application.put_env(:risime, :signup, @loose)

    on_exit(fn ->
      if previous,
        do: Application.put_env(:risime, :signup, previous),
        else: Application.delete_env(:risime, :signup)
    end)
  end

  defp signup_cfg(changes),
    do: Application.put_env(:risime, :signup, Keyword.merge(@loose, changes))

  # Each test gets its own client IP, so the per-IP counters never leak between tests.
  defp from_ip(conn, ip), do: %{conn | remote_ip: ip}
  defp unique_ip, do: {198, 51, 100, :rand.uniform(250)}

  defp authed(conn, token), do: put_req_header(conn, "authorization", "Bearer " <> token)

  defp me(conn, token), do: conn |> authed(token) |> get(~p"/api/v1/me")

  defp signup(conn, token, body),
    do: conn |> authed(token) |> post(~p"/api/v1/auth/signup", body)

  defp new_identity(name \\ "New Person") do
    email = "new-#{System.unique_integer([:positive])}@example.org"
    {email, access_token(email, %{"sub" => Ecto.UUID.generate(), "name" => name})}
  end

  defp signed_up!(conn, name \\ "Nimal") do
    {email, token} = new_identity(name)
    phone = unique_phone()

    %{"user" => user} =
      signup(conn, token, %{"phone" => phone, "display_name" => name}) |> json_response(200)

    %{token: token, email: email, phone: phone, user: user}
  end

  defp api(conn, token, method, path, body \\ nil),
    do: conn |> authed(token) |> dispatch(@endpoint, method, path, body)

  defp auth_kinds(ip_string) do
    AuthLog.path()
    |> File.read!()
    |> String.split("\n", trim: true)
    |> Enum.filter(&String.contains?(&1, "ip=#{ip_string} "))
    |> Enum.map(fn line -> line |> then(&Regex.run(~r/kind=([a-z_]+)/, &1)) |> List.last() end)
  end

  describe "the switch" do
    test "GET /auth/config reports signup open or invite", %{conn: conn} do
      assert %{"signup" => "open"} = get(conn, ~p"/api/v1/auth/config") |> json_response(200)
      signup_cfg(open: false)
      assert %{"signup" => "invite"} = get(conn, ~p"/api/v1/auth/config") |> json_response(200)
    end

    test "off: a stranger is not_allowlisted and POST /auth/signup is signup_closed", %{
      conn: conn
    } do
      signup_cfg(open: false)
      {_email, token} = new_identity()
      ip = unique_ip()

      assert %{"error" => %{"code" => "not_allowlisted"}} = me(conn, token) |> json_response(403)

      assert %{"error" => %{"code" => "signup_closed"}} =
               conn
               |> from_ip(ip)
               |> signup(token, %{"phone" => unique_phone(), "display_name" => "X"})
               |> json_response(403)

      assert "signup_refused" in auth_kinds(:inet.ntoa(ip) |> to_string())
    end

    test "on: a stranger gets signup_required (not an auth-log failure)", %{conn: conn} do
      {_email, token} = new_identity()

      assert %{"error" => %{"code" => "signup_required", "message" => message}} =
               me(conn, token) |> json_response(403)

      assert message == "Create your RisiMe account to continue"
    end

    test "existing open users keep working after the switch goes off", %{conn: conn} do
      s = signed_up!(conn)
      signup_cfg(open: false)
      RisiMe.Auth.clear_cache()
      assert %{"user" => %{"id" => id}} = me(conn, s.token) |> json_response(200)
      assert id == s.user["id"]
    end
  end

  describe "POST /auth/signup" do
    test "creates the user bound to sub with the token's email; then /me works", %{conn: conn} do
      {email, token} = new_identity("Nimal Perera")
      phone = unique_phone()

      assert %{"user" => user} =
               signup(conn, token, %{"phone" => phone, "display_name" => "  Nimal Perera "})
               |> json_response(200)

      assert user["phone"] == phone and user["display_name"] == "Nimal Perera"
      assert user["company"] == "" and user["vouched_by"] == nil
      assert user["phone_confirmed"] == false
      # §21.4: phone_verified keeps its §7.1 meaning (the gate is off in test).
      assert user["phone_verified"] == true

      db = Repo.get!(User, user["id"])
      assert db.email == email and db.signup_source == "open" and is_binary(db.keycloak_sub)

      assert %{"user" => ^user} = me(conn, token) |> json_response(200)

      # Idempotent: the same identity again returns the same user, whatever the body says.
      assert %{"user" => ^user} =
               signup(conn, token, %{"phone" => unique_phone(), "display_name" => "Other"})
               |> json_response(200)
    end

    test "an already-mapped identity (allowlist) gets its user back", %{conn: conn} do
      entry = allowlist_entry()
      token = access_token(entry.email)

      assert %{"user" => %{"phone" => phone, "phone_confirmed" => true}} =
               signup(conn, token, %{"phone" => unique_phone(), "display_name" => "X"})
               |> json_response(200)

      assert phone == entry.phone
    end

    test "unverified email, dev token, no token: 401 invalid_token", %{conn: conn} do
      body = %{"phone" => unique_phone(), "display_name" => "X"}

      unverified =
        access_token("unverified@example.org", %{"email_verified" => false})

      assert %{"error" => %{"code" => "invalid_token"}} =
               signup(conn, unverified, body) |> json_response(401)

      assert me(conn, unverified) |> json_response(401)

      %{token: dev} = logged_in_user()

      assert %{"error" => %{"code" => "invalid_token"}} =
               signup(conn, dev, body) |> json_response(401)

      assert %{"error" => %{"code" => "invalid_token"}} =
               post(conn, ~p"/api/v1/auth/signup", body) |> json_response(401)

      refute Repo.get_by(User, phone: body["phone"])
    end

    test "400 bad_request for a bad phone or name", %{conn: conn} do
      ip = unique_ip()

      for body <- [
            %{"phone" => "0771234567", "display_name" => "X"},
            %{"phone" => "+94 77 123 4567", "display_name" => "X"},
            %{"phone" => unique_phone(), "display_name" => "   "},
            %{"phone" => unique_phone(), "display_name" => String.duplicate("a", 65)},
            %{"phone" => unique_phone()},
            %{"phone" => 94_771_234_567, "display_name" => "X"},
            %{}
          ] do
        {_e, token} = new_identity()

        assert %{"error" => %{"code" => "bad_request"}} =
                 conn |> from_ip(ip) |> signup(token, body) |> json_response(400)
      end

      assert "signup_refused" in auth_kinds(:inet.ntoa(ip) |> to_string())
    end

    test "a 64-character name is fine", %{conn: conn} do
      {_e, token} = new_identity()
      name = String.duplicate("ක", 64)

      assert %{"user" => %{"display_name" => ^name}} =
               signup(conn, token, %{"phone" => unique_phone(), "display_name" => name})
               |> json_response(200)
    end

    test "409 phone_taken: a user's phone, an allowlisted phone, a pending invite's phone", %{
      conn: conn
    } do
      %{user: existing} = logged_in_user()
      entry = allowlist_entry()
      %{token: inviter} = logged_in_user()
      invited = unique_phone()

      api(conn, inviter, :post, "/api/v1/invites", %{
        "phone" => invited,
        "email" => "invitee-#{System.unique_integer([:positive])}@example.org",
        "name" => "Invitee"
      })
      |> json_response(201)

      for phone <- [existing.phone, entry.phone, invited] do
        {_e, token} = new_identity()

        assert %{"error" => %{"code" => "phone_taken", "message" => message}} =
                 signup(conn, token, %{"phone" => phone, "display_name" => "Squatter"})
                 |> json_response(409)

        assert message == "This phone number can't be used for a new RisiMe account"
      end

      # Another open sign-up's phone is taken too.
      s = signed_up!(conn)
      {_e, token} = new_identity()

      assert %{"error" => %{"code" => "phone_taken"}} =
               signup(conn, token, %{"phone" => s.phone, "display_name" => "X"})
               |> json_response(409)
    end
  end

  describe "limits" do
    test "per sub: 3 attempts per day, then 429 with Retry-After", %{conn: conn} do
      signup_cfg(per_sub_day: 3)
      {_e, token} = new_identity()
      ip = unique_ip()
      bad = %{"phone" => "nope", "display_name" => "X"}

      for _ <- 1..3,
          do: assert(conn |> from_ip(ip) |> signup(token, bad) |> json_response(400))

      resp = conn |> from_ip(ip) |> signup(token, bad)
      assert %{"error" => %{"code" => "rate_limited"}} = json_response(resp, 429)
      [retry] = get_resp_header(resp, "retry-after")
      assert String.to_integer(retry) in 1..86_400
      assert "signup_rate_limited" in auth_kinds(:inet.ntoa(ip) |> to_string())

      # Even a valid request from that sub waits.
      assert conn
             |> from_ip(ip)
             |> signup(token, %{"phone" => unique_phone(), "display_name" => "X"})
             |> json_response(429)
    end

    test "per IP: 5 attempts per hour", %{conn: conn} do
      signup_cfg(per_ip_hour: 5)
      ip = unique_ip()

      for _ <- 1..5 do
        {_e, token} = new_identity()

        assert conn
               |> from_ip(ip)
               |> signup(token, %{"phone" => "bad", "display_name" => "X"})
               |> json_response(400)
      end

      {_e, token} = new_identity()

      resp =
        conn
        |> from_ip(ip)
        |> signup(token, %{"phone" => unique_phone(), "display_name" => "X"})

      assert json_response(resp, 429)
      assert [_] = get_resp_header(resp, "retry-after")
    end

    test "global: successful sign-ups per day", %{conn: conn} do
      day_ago = DateTime.add(DateTime.utc_now(), -1, :day)

      already =
        Repo.aggregate(
          from(u in User,
            where: u.signup_source == "open" and u.inserted_at > ^day_ago
          ),
          :count
        )

      signup_cfg(global_per_day: already + 1)
      signed_up!(conn)
      {_e, token} = new_identity()

      resp = signup(conn, token, %{"phone" => unique_phone(), "display_name" => "X"})
      assert %{"error" => %{"code" => "rate_limited"}} = json_response(resp, 429)
      assert [_] = get_resp_header(resp, "retry-after")
    end

    test "an identity that already maps counts no limit", %{conn: conn} do
      s = signed_up!(conn)
      signup_cfg(per_sub_day: 0, per_ip_hour: 0, global_per_day: 0)

      assert %{"user" => %{"id" => id}} =
               signup(conn, s.token, %{"phone" => s.phone, "display_name" => "X"})
               |> json_response(200)

      assert id == s.user["id"]
    end
  end

  describe "allowlist and invites are unchanged with the switch on" do
    test "allowlisted email signs in; an invite binds its phone and befriends the inviter", %{
      conn: conn
    } do
      entry = allowlist_entry()

      assert %{"user" => %{"phone" => phone}} =
               me(conn, access_token(entry.email)) |> json_response(200)

      assert phone == entry.phone

      %{token: inviter_token, user: inviter} = logged_in_user()
      invited_phone = unique_phone()
      email = "invited-#{System.unique_integer([:positive])}@example.org"

      api(conn, inviter_token, :post, "/api/v1/invites", %{
        "phone" => invited_phone,
        "email" => email,
        "name" => "Invited"
      })
      |> json_response(201)

      assert %{"user" => %{"id" => id, "phone" => ^invited_phone, "phone_confirmed" => true}} =
               me(conn, access_token(email)) |> json_response(200)

      assert RisiMe.Social.friends?(id, inviter.id)
    end
  end

  describe "phone matching (§21.5)" do
    test "requests to an unconfirmed phone aren't delivered; after SMS verification they are",
         %{conn: conn} do
      %{token: a_token, user: a} = logged_in_user(display_name: "Alice")
      s = signed_up!(conn)

      assert api(conn, a_token, :post, "/api/v1/friends/requests", %{"phone" => s.phone})
             |> json_response(202)

      %{"outgoing" => [%{"id" => req_id}]} =
        api(conn, a_token, :get, "/api/v1/friends") |> json_response(200)

      assert %{"incoming" => []} =
               api(conn, s.token, :get, "/api/v1/friends") |> json_response(200)

      assert api(conn, s.token, :post, "/api/v1/friends/requests/#{req_id}/accept")
             |> json_response(404)

      assert api(conn, s.token, :post, "/api/v1/friends/requests/#{req_id}/decline")
             |> json_response(404)

      refute RisiMe.Social.friends?(a.id, s.user["id"])

      # Once the phone is SMS-verified, it is matched (and phone_confirmed turns true).
      u_id = s.user["id"]

      Repo.update_all(from(u in User, where: u.id == ^u_id),
        set: [phone_verified_for: s.phone]
      )

      RisiMe.Auth.clear_cache()
      assert %{"user" => %{"phone_confirmed" => true}} = me(conn, s.token) |> json_response(200)

      assert %{"incoming" => [%{"id" => ^req_id, "phone_confirmed" => true}]} =
               api(conn, s.token, :get, "/api/v1/friends") |> json_response(200)
    end

    test "no crossing auto-accept for an unconfirmed requester; the target accepts, then they can message",
         %{conn: conn} do
      %{token: a_token, user: a} = logged_in_user(display_name: "Alice")
      s = signed_up!(conn, "Nimal")
      u_id = s.user["id"]

      # Alice asked for that number earlier (meant for its real owner).
      api(conn, a_token, :post, "/api/v1/friends/requests", %{"phone" => s.phone})
      |> json_response(202)

      Messaging.subscribe(a.id)

      assert api(conn, s.token, :post, "/api/v1/friends/requests", %{"phone" => a.phone})
             |> json_response(202)

      refute RisiMe.Social.friends?(a.id, u_id)

      assert_receive {:signal,
                      %{
                        kind: "friend",
                        data: %{
                          "action" => "request_received",
                          "user" => %{"user_id" => ^u_id, "phone_confirmed" => false}
                        }
                      }}

      # Messaging needs an accepted request.
      msg = %{"client_msg_id" => Uniq.UUID.uuid4(), "to" => a.id, "body" => "hi"}
      assert {:error, :not_friends} = Messaging.send(u_id, msg)

      assert %{"incoming" => [%{"id" => id, "user_id" => ^u_id, "phone_confirmed" => false}]} =
               api(conn, a_token, :get, "/api/v1/friends") |> json_response(200)

      assert %{"friend" => %{"user_id" => ^u_id, "phone_confirmed" => false}} =
               api(conn, a_token, :post, "/api/v1/friends/requests/#{id}/accept")
               |> json_response(200)

      assert %{"friends" => [%{"user_id" => a_id, "phone_confirmed" => true}]} =
               api(conn, s.token, :get, "/api/v1/friends") |> json_response(200)

      assert a_id == a.id
      assert {:ok, _} = Messaging.send(u_id, msg)
    end
  end

  describe "privacy-safe diagnostic (§21.7)" do
    test "refusals log the email domain and a salted hash, never the address", %{conn: conn} do
      level = Logger.level()
      Logger.configure(level: :info)
      on_exit(fn -> Logger.configure(level: level) end)

      email = "Secret.Person-#{System.unique_integer([:positive])}@Example.ORG"
      token = access_token(String.downcase(email), %{"sub" => Ecto.UUID.generate()})

      log = capture_log([level: :info], fn -> me(conn, token) |> json_response(403) end)
      hash = Accounts.email_hash(email)
      assert hash =~ ~r/^[0-9a-f]{12}$/
      assert log =~ "reason=signup_required email_domain=example.org email_hash=#{hash}"
      refute log =~ ~r/secret\.person/i

      # Once per email per 10 minutes.
      again = capture_log([level: :info], fn -> me(conn, token) |> json_response(403) end)
      refute again =~ hash

      signup_cfg(open: false)
      email2 = "other-#{System.unique_integer([:positive])}@example.net"

      log2 =
        capture_log([level: :info], fn ->
          me(conn, access_token(email2)) |> json_response(403)
        end)

      assert log2 =~ "reason=not_allowlisted email_domain=example.net"
      refute log2 =~ email2
    end
  end
end
