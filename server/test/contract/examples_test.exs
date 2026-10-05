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
              join_reply.json msg_send.json msg_send_reply.json)
  # v1.2 (presence/typing): parse-only placeholders added by root with the contract merge; the
  # server role replaces them with real encoder/decoder checks when it implements §2.5/§2.6.
  @pending_v1_2 ~w(presence_watch.json presence_watch_reply.json signal_presence.json
                   signal_typing.json typing.json)

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

    assert @files -- (@checked ++ @pending_v1_2) == [],
           "add checks for: #{inspect(@files -- (@checked ++ @pending_v1_2))}"
  end

  test "v1.2 examples are valid JSON objects (placeholder)" do
    for name <- @pending_v1_2, do: assert(is_map(example(name)))
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

  defp atomize(map), do: Map.new(map, fn {k, v} -> {String.to_existing_atom(k), v} end)
end
