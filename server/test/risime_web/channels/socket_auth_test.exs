defmodule RisiMeWeb.SocketAuthTest do
  @moduledoc "Contract v1.3 §6.2: socket auth, expiry (auth:expired + disconnect), auth:refresh."
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures
  import RisiMe.OIDCHelpers

  alias Phoenix.Socket.{Broadcast, Message}
  alias RisiMeWeb.{InboxChannel, UserSocket}

  # config/test.exs: auth_expiry_grace_ms 300. Tokens below use exp = now + 1, so the deadline
  # falls 1.3 to 2.3 s after connecting (exp has whole-second resolution).

  setup do
    RisiMe.Auth.clear_cache()
    on_exit(fn -> Application.put_env(:risime, :dev_local_auth, true) end)
    entry = allowlist_entry()
    %{entry: entry}
  end

  defp connect_bearer(token), do: connect(UserSocket, %{}, connect_info: %{auth_token: token})

  defp join_inbox(socket) do
    {:ok, _, chan} =
      subscribe_and_join(socket, InboxChannel, "inbox:" <> socket.assigns.user_id, %{})

    chan
  end

  defp refresh(chan, token) do
    ref = push(chan, "auth:refresh", %{"token" => token})

    receive do
      %Phoenix.Socket.Reply{ref: ^ref, status: s, payload: p} -> {s, p}
    after
      2000 -> flunk("no reply to auth:refresh")
    end
  end

  describe "connect" do
    test "accepts a JWT from the Authorization header (connect_info) or token=", %{entry: e} do
      token = access_token(e.email)
      assert {:ok, s1} = connect_bearer(token)
      assert {:ok, s2} = connect(UserSocket, %{"token" => token})
      assert s1.assigns.user_id == s2.assigns.user_id
      assert s1.assigns.auth_kind == :jwt
      # Every JWT socket has its own id.
      assert "jwt_socket:" <> _ = s1.id
      assert s1.id != s2.id
    end

    test "refuses invalid, unallowlisted and conflicting tokens", %{entry: e} do
      assert :error = connect_bearer("nope")
      assert :error = connect_bearer(access_token("stranger@example.com"))

      assert :error =
               connect_bearer(access_token(e.email, %{"exp" => System.os_time(:second) - 120}))

      assert {:ok, _} = connect_bearer(access_token(e.email, %{"sub" => "a"}))
      assert :error = connect_bearer(access_token(e.email, %{"sub" => "b"}))
    end

    test "dev tokens work only with DEV_LOCAL_AUTH" do
      %{token: dev} = logged_in_user()
      assert {:ok, s} = connect_bearer(dev)
      assert "user_token:" <> _ = s.id
      Application.put_env(:risime, :dev_local_auth, false)
      assert :error = connect_bearer(dev)
      assert :error = connect(UserSocket, %{"token" => dev})
    end
  end

  describe "expiry" do
    test "at exp + grace: auth:expired, then the socket is disconnected", %{entry: e} do
      now = System.os_time(:second)
      {:ok, socket} = connect_bearer(access_token(e.email, %{"exp" => now + 1}))
      @endpoint.subscribe(socket.id)
      _chan = join_inbox(socket)

      topic = "inbox:" <> socket.assigns.user_id
      assert_receive %Message{topic: ^topic, event: "auth:expired", payload: %{}}, 4_000
      assert_receive %Broadcast{event: "disconnect"}, 1_000
    end

    test "a socket without a joined channel is disconnected too", %{entry: e} do
      {:ok, socket} =
        connect_bearer(access_token(e.email, %{"exp" => System.os_time(:second) + 1}))

      @endpoint.subscribe(socket.id)
      assert_receive %Broadcast{event: "disconnect"}, 5_000
    end

    test "dev-token sockets have no deadline" do
      %{token: dev} = logged_in_user()
      {:ok, socket} = connect_bearer(dev)
      @endpoint.subscribe(socket.id)
      _chan = join_inbox(socket)
      refute_receive %Broadcast{event: "disconnect"}, 500
    end
  end

  describe "auth:refresh" do
    setup %{entry: e} do
      now = System.os_time(:second)
      {:ok, socket} = connect_bearer(access_token(e.email, %{"exp" => now + 1, "sub" => "me"}))
      @endpoint.subscribe(socket.id)
      %{socket: socket, chan: join_inbox(socket), now: now}
    end

    test "a token for the same user moves the deadline and returns expires_at",
         %{entry: e, chan: chan, now: now} do
      exp = now + 600

      assert {:ok, %{expires_at: at}} =
               refresh(chan, access_token(e.email, %{"exp" => exp, "sub" => "me"}))

      assert at == exp |> DateTime.from_unix!() |> RisiMe.Messaging.iso()
      assert at =~ ~r/\.000Z$/
      refute_receive %Broadcast{event: "disconnect"}, 3_000
    end

    test "a token for another user is identity_mismatch and the old deadline stays",
         %{chan: chan} do
      other = allowlist_entry()
      assert {:error, %{reason: "identity_mismatch"}} = refresh(chan, access_token(other.email))
      assert_receive %Broadcast{event: "disconnect"}, 4_000
    end

    test "invalid, unallowlisted, conflicting and dev tokens are refused", %{entry: e, chan: chan} do
      assert {:error, %{reason: "invalid_token"}} = refresh(chan, "nope")

      assert {:error, %{reason: "invalid_token"}} =
               refresh(chan, access_token(e.email, %{"typ" => "ID", "sub" => "me"}))

      assert {:error, %{reason: "not_allowlisted"}} =
               refresh(chan, access_token("stranger@example.com"))

      assert {:error, %{reason: "identity_mismatch"}} =
               refresh(chan, access_token(e.email, %{"sub" => "someone-else"}))

      %{token: dev} = logged_in_user()
      assert {:error, %{reason: "invalid_token"}} = refresh(chan, dev)

      ref = push(chan, "auth:refresh", %{})
      assert_reply ref, :error, %{reason: "bad_request"}
    end

    test "not_allowlisted after the phone left the allowlist", %{entry: e, chan: chan} do
      RisiMe.Repo.delete!(e)
      RisiMe.Auth.clear_cache()

      assert {:error, %{reason: "not_allowlisted"}} =
               refresh(chan, access_token(e.email, %{"sub" => "me"}))
    end
  end
end
