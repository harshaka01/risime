defmodule RisiMeWeb.PhoneGateTest do
  @moduledoc "Contract v1.4 §7: the phone_unverified gate on REST and the socket, and resets."
  use RisiMeWeb.ChannelCase, async: false

  import Phoenix.ConnTest, except: [connect: 2, connect: 3]
  import Plug.Conn, only: [put_req_header: 3]
  import RisiMe.Fixtures
  import RisiMe.OIDCHelpers
  import Ecto.Query, only: [from: 2]

  alias RisiMe.Accounts.User
  alias RisiMe.Repo
  alias RisiMeWeb.{InboxChannel, UserSocket}

  setup do
    RisiMe.Auth.clear_cache()
    Application.put_env(:risime, :phone_verification, :required)
    on_exit(fn -> Application.put_env(:risime, :phone_verification, :off) end)
    entry = allowlist_entry()
    %{entry: entry, token: access_token(entry.email, %{"sub" => "gate-" <> entry.phone})}
  end

  defp api(method, path, token, body \\ nil) do
    conn = build_conn()
    conn = if token, do: put_req_header(conn, "authorization", "Bearer " <> token), else: conn
    dispatch(conn, @endpoint, method, path, body)
  end

  defp body(conn), do: Jason.decode!(conn.resp_body)

  # Verifies `entry`'s user directly (the SMS flow is tested in PhoneVerificationTest).
  defp verify!(token) do
    {:ok, %{user: user}} = RisiMe.Auth.authenticate(token)

    Repo.update_all(from(u in User, where: u.id == ^user.id),
      set: [phone_verified_for: user.phone]
    )

    user
  end

  describe "REST" do
    test "unverified: /me, logout, verify routes and /auth/config are open; the rest is 403",
         %{token: t} do
      assert %{"user" => %{"phone_verified" => false}} = api(:get, "/api/v1/me", t) |> body()

      assert api(:get, "/api/v1/auth/config", nil) |> body() |> Map.get("phone_verification") ==
               "required"

      for {method, path, payload} <- [
            {:get, "/api/v1/contacts", nil},
            {:patch, "/api/v1/me", %{"display_name" => "X"}}
          ] do
        conn = api(method, path, t, payload)
        assert conn.status == 403

        assert body(conn) == %{
                 "error" => %{
                   "code" => "phone_unverified",
                   "message" => "Confirm your phone number to continue"
                 }
               }
      end

      assert api(:post, "/api/v1/me/phone/verify/request", t, %{}).status == 200
      assert api(:post, "/api/v1/auth/logout", t).status == 204
    end

    test "check order: 401, then 403 not_allowlisted / 409, then 403 phone_unverified", %{
      entry: e
    } do
      assert api(:get, "/api/v1/contacts", "nope").status == 401

      assert %{"error" => %{"code" => "not_allowlisted"}} =
               api(:get, "/api/v1/contacts", access_token("stranger@example.com")) |> body()

      first = access_token(e.email, %{"sub" => "s-first"})
      assert api(:get, "/api/v1/me", first).status == 200

      assert %{"error" => %{"code" => "identity_conflict"}} =
               api(:get, "/api/v1/contacts", access_token(e.email, %{"sub" => "s-second"}))
               |> body()

      assert %{"error" => %{"code" => "phone_unverified"}} =
               api(:get, "/api/v1/contacts", first) |> body()
    end

    test "verified users and dev sessions pass", %{token: t} do
      verify!(t)
      assert api(:get, "/api/v1/contacts", t).status == 200

      %{token: dev} = logged_in_user()
      assert api(:get, "/api/v1/contacts", dev).status == 200
    end

    test "Contact.registered only for verified users while the gate is on", %{token: t} do
      verify!(t)
      b = allowlist_entry()
      b_token = access_token(b.email)
      verify!(b_token)
      c = allowlist_entry()
      {:ok, _} = RisiMe.Auth.authenticate(access_token(c.email))

      contacts = api(:get, "/api/v1/contacts", t) |> body() |> Map.fetch!("contacts")
      by_phone = Map.new(contacts, &{&1["phone"], &1})
      assert by_phone[b.phone]["registered"] == true
      assert by_phone[c.phone]["registered"] == false
      assert is_binary(by_phone[c.phone]["user_id"])

      Application.put_env(:risime, :phone_verification, :off)
      contacts = api(:get, "/api/v1/contacts", t) |> body() |> Map.fetch!("contacts")
      assert Enum.find(contacts, &(&1["phone"] == c.phone))["registered"] == true
    end
  end

  describe "socket" do
    test "an unverified user's connect is refused; verified and dev users connect", %{token: t} do
      assert :error = connect(UserSocket, %{}, connect_info: %{auth_token: t})
      verify!(t)
      assert {:ok, _} = connect(UserSocket, %{}, connect_info: %{auth_token: t})

      %{token: dev} = logged_in_user()
      assert {:ok, _} = connect(UserSocket, %{"token" => dev})
    end

    test "auth:refresh answers phone_unverified after a reset", %{token: t} do
      user = verify!(t)
      {:ok, sock} = connect(UserSocket, %{}, connect_info: %{auth_token: t})
      {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user.id, %{})

      Repo.update_all(from(u in User, where: u.id == ^user.id), set: [phone_verified_for: nil])
      ref = push(chan, "auth:refresh", %{"token" => t})
      assert_reply ref, :error, %{reason: "phone_unverified"}
    end

    test "msg:send to an unverified user is unknown_recipient while the gate is on", %{token: t} do
      user = verify!(t)
      other = allowlist_entry()
      {:ok, %{user: unverified}} = RisiMe.Auth.authenticate(access_token(other.email))

      {:ok, sock} = connect(UserSocket, %{}, connect_info: %{auth_token: t})
      {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user.id, %{})

      ref =
        push(chan, "msg:send", %{
          "client_msg_id" => Uniq.UUID.uuid4(),
          "to" => unverified.id,
          "body" => "hi"
        })

      assert_reply ref, :error, %{reason: "unknown_recipient"}
    end

    test "a verification reset notification closes the user's sockets", %{token: t} do
      user = verify!(t)
      {:ok, sock} = connect(UserSocket, %{}, connect_info: %{auth_token: t})
      @endpoint.subscribe(sock.id)

      # What Postgres delivers on commit (the trigger can't fire inside the test sandbox).
      listener = Process.whereis(RisiMe.Accounts.ResetListener)
      send(listener, {:notification, self(), make_ref(), "risime_verification_reset", user.id})
      assert_receive %Phoenix.Socket.Broadcast{event: "disconnect"}, 1_000
    end
  end
end
