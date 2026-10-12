defmodule RisiMeWeb.VoicePlayedV141Test do
  @moduledoc """
  Contract v1.41 §37.4 (decision 081): the `played` receipt. Order delivered < read < played
  (played implies read); a DM `status: "played"` reaches only the sender's `voice` devices (the
  others see `read`), once per (message, recipient); groups get `played` / `all_played` and
  `played_at` rows once the message has a played ack.
  """
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]
  import RisiMe.GroupHelpers

  alias Phoenix.Socket.Message
  alias RisiMe.Messaging
  alias RisiMeWeb.{InboxChannel, UserSocket}

  setup :with_attestation_key

  defp device!(user, caps) do
    id = Ecto.UUID.generate()

    {:ok, _} =
      RisiMe.Devices.register(user.user.id, id, %{
        "platform" => "android",
        "mls" => %{
          "signature_key" => Base.encode64(:crypto.strong_rand_bytes(32)),
          "capabilities" => caps
        }
      })

    id
  end

  defp join!(user, device) do
    {:ok, sock} = connect(UserSocket, %{"token" => user.token, "device_id" => device})

    {:ok, reply, chan} =
      subscribe_and_join(sock, InboxChannel, "inbox:" <> user.user.id, %{"since" => nil})

    {chan, reply}
  end

  defp call(chan, event, payload) do
    ref = push(chan, event, payload)

    receive do
      %Phoenix.Socket.Reply{ref: ^ref, status: s, payload: p} -> {s, p}
    after
      2000 -> flunk("no reply")
    end
  end

  # Statuses pushed live on a user's inbox topic (the test process is every channel's transport).
  defp pushed_statuses(topic) do
    receive do
      %Message{topic: ^topic, event: "event", payload: %{kind: "status", data: d}} ->
        [d["status"] | pushed_statuses(topic)]
    after
      300 -> []
    end
  end

  describe "DM" do
    setup do
      a = logged_in_user(display_name: "Asha")
      b = logged_in_user(display_name: "Bimal")
      befriend!(a, b)
      %{a: a, b: b}
    end

    test "played implies read; the played status reaches only voice devices, once", %{a: a, b: b} do
      voice = device!(a, ~w(groups voice))
      plain = device!(a, ~w(groups))
      {a_voice, _} = join!(a, voice)
      b_dev = device!(b, ~w(groups voice))
      {b_chan, _} = join!(b, b_dev)

      ref =
        push(a_voice, "msg:send", %{
          "client_msg_id" => Ecto.UUID.generate(),
          "to" => b.user.id,
          "body" => "voice stand-in",
          "client_ts" => "2026-10-12T09:15:30.123Z"
        })

      assert_reply ref, :ok, %{message_id: mid}

      assert {:ok, _} =
               call(b_chan, "msg:ack", %{"message_ids" => [mid], "status" => "delivered"})

      # The example ack is accepted as it is.
      ack = example("msg_ack_played.json") |> Map.put("message_ids", [mid])
      flush()
      assert {:ok, _} = call(b_chan, "msg:ack", ack)

      # Stored: delivered, then read (implied), then played.
      statuses = for e <- events(a.user.id, "status"), do: e["data"]["status"]
      assert statuses == ~w(delivered read played)

      played = last_event(a.user.id, "status")
      ex = example("event_status_played.json")
      assert Map.keys(played["data"]) |> Enum.sort() == Map.keys(ex["data"]) |> Enum.sort()
      assert %{"status" => "played", "by" => by, "message_id" => ^mid} = played["data"]
      assert by == b.user.id

      # A repeat from another of b's devices, and a later read, change nothing.
      assert {:ok, _} = call(b_chan, "msg:ack", ack)
      assert {:ok, _} = call(b_chan, "msg:ack", %{"message_ids" => [mid], "status" => "read"})
      assert length(events(a.user.id, "status")) == 3

      # The voice device's sync sees played; the plain device's sync stops at read.
      {_, %{events: v_events}} = join!(a, voice)
      {_, %{events: p_events}} = join!(a, plain)
      assert status_list(v_events) == ~w(delivered read played)
      assert status_list(p_events) == ~w(delivered read)
      # A device-less (pre-v1.7) socket is not a voice device either.
      {:ok, sock} = connect(UserSocket, %{"token" => a.token})

      {:ok, %{events: legacy}, _} =
        subscribe_and_join(sock, InboxChannel, "inbox:" <> a.user.id, %{"since" => nil})

      assert status_list(legacy) == ~w(delivered read)
    end

    test "live pushes: a plain socket never gets played; a read message gets only played", %{
      a: a,
      b: b
    } do
      plain = device!(a, ~w(groups))
      {a_plain, _} = join!(a, plain)
      b_dev = device!(b, ~w(groups voice))
      {b_chan, _} = join!(b, b_dev)

      ref =
        push(a_plain, "msg:send", %{
          "client_msg_id" => Ecto.UUID.generate(),
          "to" => b.user.id,
          "body" => "x",
          "client_ts" => "2026-10-12T09:15:30.123Z"
        })

      assert_reply ref, :ok, %{message_id: mid}
      assert {:ok, _} = call(b_chan, "msg:ack", %{"message_ids" => [mid], "status" => "read"})
      flush()
      assert {:ok, _} = call(b_chan, "msg:ack", %{"message_ids" => [mid], "status" => "played"})
      assert pushed_statuses("inbox:" <> a.user.id) == []
      # Already read: only the played event was added.
      assert for(e <- events(a.user.id, "status"), do: e["data"]["status"]) == ~w(read played)
    end

    test "own messages and bad statuses", %{a: a, b: b} do
      a_dev = device!(a, ~w(groups voice))
      {a_chan, _} = join!(a, a_dev)

      ref =
        push(a_chan, "msg:send", %{
          "client_msg_id" => Ecto.UUID.generate(),
          "to" => b.user.id,
          "body" => "x",
          "client_ts" => "2026-10-12T09:15:30.123Z"
        })

      assert_reply ref, :ok, %{message_id: mid}
      assert {:ok, _} = call(a_chan, "msg:ack", %{"message_ids" => [mid], "status" => "played"})
      assert events(a.user.id, "status") == []

      assert {:error, %{reason: "bad_request"}} =
               call(a_chan, "msg:ack", %{"message_ids" => [mid], "status" => "heard"})
    end
  end

  describe "group" do
    setup do
      [a, b, c] = for n <- ~w(Asha Bimal Chamari), do: logged_in_user(display_name: n)
      befriend!(a, b)
      befriend!(a, c)
      a_dev = groups_device!(a)
      groups_device!(b)
      groups_device!(c)

      body = %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => [b.user.id, c.user.id]}

      %{"group" => %{"id" => id}} =
        api(:post, "/api/v1/groups", a.token, body, a_dev) |> assert_status(201)

      api(
        :post,
        "/api/v1/mls/groups/#{id}/commit",
        a.token,
        create_commit([a.user.id, b.user.id, c.user.id], {a.user.id, a_dev}),
        a_dev
      )
      |> assert_status(200)

      %{a: a, b: b, c: c, a_dev: a_dev, id: id}
    end

    test "played / all_played on group_receipt, played_at rows", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, id: id} = ctx
      {chan, _} = join!(a, a_dev)

      {:ok, %{message_id: mid}} =
        call(chan, "msg:send", %{
          "client_msg_id" => Ecto.UUID.generate(),
          "conversation_id" => id,
          "ciphertext" => Base.encode64(:crypto.strong_rand_bytes(64)),
          "generation" => 1,
          "epoch" => 1,
          "client_ts" => "2026-10-12T09:15:30.123Z"
        })

      :ok = Messaging.ack(b.user.id, [mid], "read")
      :ok = Messaging.ack(c.user.id, [mid], "delivered")
      wait_until(fn -> match?(%{"all_delivered" => true}, receipt(a)) end)
      # No played ack yet: no played fields anywhere.
      refute Map.has_key?(receipt(a), "played")
      {200, reply} = api(:get, "/api/v1/groups/#{id}/messages/#{mid}/receipts", a.token)
      refute Enum.any?(reply["receipts"], &Map.has_key?(&1, "played_at"))

      # c plays without having read: read is implied, all_read flips (sent at once).
      :ok = Messaging.ack(c.user.id, [mid], "played")
      wait_until(fn -> match?(%{"all_read" => true}, receipt(a)) end)
      r = receipt(a)
      assert %{"read" => 2, "played" => 1, "all_played" => false} = r
      ex = example("event_group_receipt_v141.json")
      assert Enum.sort(Map.keys(r)) == Enum.sort(Map.keys(ex["data"]))

      {200, reply} = api(:get, "/api/v1/groups/#{id}/messages/#{mid}/receipts", a.token)
      ex = example("group_receipts_reply_v141.json")
      assert Enum.sort(Map.keys(reply)) == Enum.sort(Map.keys(ex))
      rows = Map.new(reply["receipts"], &{&1["user_id"], &1})
      assert Enum.sort(Map.keys(rows[c.user.id])) == Enum.sort(Map.keys(hd(ex["receipts"])))
      assert is_binary(rows[c.user.id]["played_at"]) and rows[c.user.id]["read_at"]
      assert rows[b.user.id]["played_at"] == nil and Map.has_key?(rows[b.user.id], "played_at")

      # A repeat changes nothing; b's played flips all_played (sent at once).
      :ok = Messaging.ack(c.user.id, [mid], "played")
      :ok = Messaging.ack(b.user.id, [mid], "played")
      wait_until(fn -> match?(%{"all_played" => true}, receipt(a)) end)
      assert %{"played" => 2, "of" => 2} = receipt(a)
      # Never a status event for a group message.
      assert events(a.user.id, "status") == []
    end
  end

  defp receipt(a), do: (last_event(a.user.id, "group_receipt") || %{})["data"] || %{}

  defp status_list(events) do
    for e <- events,
        e = Jason.decode!(Jason.encode!(e)),
        e["kind"] == "status",
        do: e["data"]["status"]
  end

  defp flush do
    receive do
      %Message{} -> flush()
    after
      50 -> :ok
    end
  end

  defp example(name) do
    Path.expand("../../../../contract/v1/examples/#{name}", __DIR__)
    |> File.read!()
    |> Jason.decode!()
  end

  defp wait_until(fun, timeout \\ 5_000) do
    cond do
      fun.() ->
        :ok

      timeout <= 0 ->
        flunk("condition not met in time")

      true ->
        Process.sleep(20)
        wait_until(fun, timeout - 20)
    end
  end
end
