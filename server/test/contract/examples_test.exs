defmodule RisiMe.ContractExamplesTest do
  @moduledoc """
  Every file in contract/v1/examples/ is decoded and checked against the server's own
  encoders (by producing the same payload for real and comparing shape and formats) and
  decoders (by feeding the example in).
  """
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures

  alias RisiMe.Messaging
  alias RisiMeWeb.{ApiJSON, InboxChannel, UserSocket}

  @dir Path.expand("../../../contract/v1/examples", __DIR__)
  @files @dir |> File.ls!() |> Enum.filter(&String.ends_with?(&1, ".json")) |> Enum.sort()
  @checked ~w(auth_verify_reply.json contacts_reply.json event_message.json event_status.json
              join_reply.json msg_send.json msg_send_reply.json presence_watch.json
              presence_watch_reply.json signal_presence.json signal_typing.json typing.json
              auth_config.json error_not_allowlisted.json error_invalid_token.json
              error_identity_conflict.json auth_refresh.json auth_refresh_reply.json
              auth_refresh_error.json)

  @uuid ~r/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
  @timeuuid ~r/^[0-9a-f]{8}-[0-9a-f]{4}-1[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
  @ts ~r/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()
  # What actually goes over the wire.
  defp wire(term), do: term |> Jason.encode!() |> Jason.decode!()
  defp keys(map), do: map |> Map.keys() |> Enum.sort()

  # Same keys, and every value has the same "kind" (uuid / timeuuid / timestamp / type).
  defp assert_same_shape(ours, theirs) when is_map(ours) and is_map(theirs) do
    assert keys(ours) == keys(theirs)
    for {k, v} <- theirs, do: assert_same_shape(ours[k], v)
  end

  defp assert_same_shape(ours, theirs) when is_binary(theirs) do
    for re <- [@timeuuid, @uuid, @ts], Regex.match?(re, theirs) do
      assert ours =~ re, "#{inspect(ours)} should match #{inspect(re)} like #{theirs}"
    end

    assert is_binary(ours)
  end

  defp assert_same_shape(ours, theirs) when is_list(theirs), do: assert(is_list(ours))
  defp assert_same_shape(ours, theirs) when is_boolean(theirs), do: assert(is_boolean(ours))
  defp assert_same_shape(ours, theirs) when is_integer(theirs), do: assert(is_integer(ours))
  defp assert_same_shape(ours, nil), do: assert(ours == nil or is_binary(ours))

  test "every example file is covered by this test" do
    assert length(@files) > 0

    assert @files -- @checked == [], "add checks for: #{inspect(@files -- @checked)}"
  end

  setup do
    a = logged_in_user(display_name: "A", company: "CodeGen")
    b = logged_in_user(display_name: "B", company: "Rise")
    {:ok, sock_a} = connect(UserSocket, %{"token" => a.token})
    {:ok, sock_b} = connect(UserSocket, %{"token" => b.token})
    {:ok, _, chan_a} = subscribe_and_join(sock_a, InboxChannel, "inbox:" <> a.user.id, %{})
    {:ok, _, chan_b} = subscribe_and_join(sock_b, InboxChannel, "inbox:" <> b.user.id, %{})
    %{a: a, b: b, sock_b: sock_b, chan_a: chan_a, chan_b: chan_b}
  end

  test "auth_verify_reply.json", %{a: a} do
    ex = example("auth_verify_reply.json")
    assert is_binary(ex["token"])
    assert ApiJSON.user(atomize(ex["user"])) |> wire() == ex["user"]
    assert_same_shape(wire(%{token: a.token, user: ApiJSON.user(a.user)}), ex)
  end

  test "contacts_reply.json", %{a: a} do
    ex = example("contacts_reply.json")
    for c <- ex["contacts"], do: assert(ApiJSON.contact(atomize(c)) |> wire() == c)

    _unregistered = allowlist_entry()
    ours = wire(%{contacts: Enum.map(RisiMe.Accounts.list_contacts(a.user), &ApiJSON.contact/1)})

    for c <- ours["contacts"],
        do:
          assert_same_shape(c, Enum.find(ex["contacts"], &(&1["registered"] == c["registered"])))
  end

  test "msg_send.json is accepted by the server decoder", %{a: a, b: b, chan_a: chan_a} do
    ex = example("msg_send.json")
    # The example's recipient does not exist here: it parses, then fails on the recipient.
    assert {:error, :unknown_recipient} = Messaging.send(a.user.id, ex)

    ref = push(chan_a, "msg:send", %{ex | "to" => b.user.id})
    assert_reply ref, :ok, reply
    assert_same_shape(wire(reply), example("msg_send_reply.json"))
  end

  test "msg_send_reply.json", %{chan_a: chan_a, b: b} do
    ref =
      push(chan_a, "msg:send", %{
        "client_msg_id" => Uniq.UUID.uuid4(),
        "to" => b.user.id,
        "body" => "x"
      })

    assert_reply ref, :ok, reply
    assert_same_shape(wire(reply), example("msg_send_reply.json"))
  end

  test "event_message.json and event_status.json", %{a: a, b: b, chan_a: chan_a, chan_b: chan_b} do
    ref =
      push(chan_a, "msg:send", %{
        "client_msg_id" => Uniq.UUID.uuid4(),
        "to" => b.user.id,
        "body" => "x"
      })

    assert_reply ref, :ok, %{message_id: id}

    topic_b = "inbox:" <> b.user.id
    assert_receive %Phoenix.Socket.Message{topic: ^topic_b, event: "event", payload: message}
    assert_same_shape(wire(message), example("event_message.json"))

    ref = push(chan_b, "msg:ack", %{"message_ids" => [id], "status" => "delivered"})
    assert_reply ref, :ok, %{}
    topic_a = "inbox:" <> a.user.id
    assert_receive %Phoenix.Socket.Message{topic: ^topic_a, event: "event", payload: status}
    assert_same_shape(wire(status), example("event_status.json"))

    # The decoders accept the example events: an ack for the example message id is a no-op.
    ex_id = example("event_message.json")["data"]["message_id"]
    ref = push(chan_b, "msg:ack", %{"message_ids" => [ex_id], "status" => "read"})
    assert_reply ref, :ok, %{}
  end

  test "join_reply.json", %{sock_b: sock_b, b: b} do
    ex = example("join_reply.json")

    {:ok, reply, _} =
      subscribe_and_join(sock_b, InboxChannel, "inbox:" <> b.user.id, %{"since" => nil})

    assert_same_shape(wire(reply), ex)
  end

  test "presence_watch.json and presence_watch_reply.json", %{chan_a: chan_a} do
    # The decoder accepts the example; its id isn't a registered user here, so it is left out.
    ref = push(chan_a, "presence:watch", example("presence_watch.json"))
    assert_reply ref, :ok, %{presences: []}

    %{user: c} = logged_in_user()
    RisiMe.Accounts.touch_last_seen(c.id, DateTime.utc_now())
    ref = push(chan_a, "presence:watch", %{"user_ids" => [c.id]})
    assert_reply ref, :ok, reply
    ex = example("presence_watch_reply.json")
    assert_same_shape(wire(reply), ex)
    assert_same_shape(hd(wire(reply)["presences"]), hd(ex["presences"]))
  end

  test "signal_presence.json", %{chan_a: chan_a, a: a} do
    %{user: d, token: token} = logged_in_user()
    ref = push(chan_a, "presence:watch", %{"user_ids" => [d.id]})
    assert_reply ref, :ok, _

    {:ok, sock_d} = connect(UserSocket, %{"token" => token})
    {:ok, _, _} = subscribe_and_join(sock_d, InboxChannel, "inbox:" <> d.id, %{})

    topic_a = "inbox:" <> a.user.id
    assert_receive %Phoenix.Socket.Message{topic: ^topic_a, event: "signal", payload: signal}
    ex = example("signal_presence.json")
    assert_same_shape(wire(signal), ex)
    assert wire(signal)["data"]["last_seen"] == nil
  end

  test "typing.json and signal_typing.json", %{a: a, b: b, chan_a: chan_a} do
    ex = example("typing.json")
    # The example's recipient does not exist here: it parses, then fails on the recipient.
    assert {:error, :unknown_recipient} = Messaging.typing(a.user.id, ex)

    ref = push(chan_a, "typing", %{ex | "to" => b.user.id})
    assert_reply ref, :ok, %{}

    topic_b = "inbox:" <> b.user.id
    assert_receive %Phoenix.Socket.Message{topic: ^topic_b, event: "signal", payload: signal}
    assert_same_shape(wire(signal), example("signal_typing.json"))
    assert wire(signal)["data"]["conversation_id"] =~ ~r/^dm:[0-9a-f-]{36}_[0-9a-f-]{36}$/
  end

  ## v1.3 (§6)

  defp http, do: Phoenix.ConnTest.build_conn()

  defp get_json(path, token \\ nil) do
    conn =
      if token,
        do: Plug.Conn.put_req_header(http(), "authorization", "Bearer " <> token),
        else: http()

    conn = Phoenix.ConnTest.dispatch(conn, @endpoint, :get, path, nil)
    {conn.status, Jason.decode!(conn.resp_body)}
  end

  test "auth_config.json is what GET /auth/config returns with both modes" do
    assert get_json("/api/v1/auth/config") == {200, example("auth_config.json")}
  end

  test "error_invalid_token.json, error_not_allowlisted.json, error_identity_conflict.json" do
    RisiMe.Auth.clear_cache()
    assert get_json("/api/v1/me", "nope") == {401, example("error_invalid_token.json")}

    assert get_json("/api/v1/me", RisiMe.OIDCHelpers.access_token("nobody@example.com")) ==
             {403, example("error_not_allowlisted.json")}

    entry = allowlist_entry()

    {200, _} =
      get_json("/api/v1/me", RisiMe.OIDCHelpers.access_token(entry.email, %{"sub" => "x1"}))

    assert get_json("/api/v1/me", RisiMe.OIDCHelpers.access_token(entry.email, %{"sub" => "x2"})) ==
             {409, example("error_identity_conflict.json")}
  end

  test "auth_refresh.json, auth_refresh_reply.json and auth_refresh_error.json" do
    import RisiMe.OIDCHelpers
    entry = allowlist_entry()

    {:ok, sock} =
      connect(UserSocket, %{},
        connect_info: %{auth_token: access_token(entry.email, %{"sub" => "c1"})}
      )

    {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> sock.assigns.user_id, %{})

    # The decoder accepts the example payload (its token is not a valid JWT here).
    ref = push(chan, "auth:refresh", example("auth_refresh.json"))
    assert_reply ref, :error, %{reason: "invalid_token"}

    ref = push(chan, "auth:refresh", %{"token" => access_token(entry.email, %{"sub" => "c1"})})
    assert_reply ref, :ok, reply
    assert_same_shape(wire(reply), example("auth_refresh_reply.json"))

    other = allowlist_entry()
    ref = push(chan, "auth:refresh", %{"token" => access_token(other.email)})
    assert_reply ref, :error, error
    assert wire(error) == example("auth_refresh_error.json")
  end

  defp atomize(map), do: Map.new(map, fn {k, v} -> {String.to_existing_atom(k), v} end)
end
