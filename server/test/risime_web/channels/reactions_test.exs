defmodule RisiMeWeb.ReactionsTest do
  @moduledoc "Contract v1.8 §11: message length, plaintext reactions."
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures

  alias RisiMe.Messaging
  alias RisiMeWeb.{InboxChannel, UserSocket}

  setup do
    Application.put_env(:risime, :push_sender, RisiMe.Push.Test)
    Application.put_env(:risime, :push_test_pid, self())

    on_exit(fn ->
      Application.put_env(:risime, :push_sender, nil)
      Application.delete_env(:risime, :push_test_pid)
    end)

    a = logged_in_user()
    b = logged_in_user()
    befriend!(a, b)

    {:ok, nil} =
      RisiMe.Devices.register(b.user.id, Ecto.UUID.generate(), %{
        "platform" => "android",
        "push_token" => "fcm-b"
      })

    {:ok, sock} = connect(UserSocket, %{"token" => a.token})
    {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> a.user.id, %{})
    %{a: a, b: b, chan: chan}
  end

  defp send_msg(chan, payload) do
    ref = push(chan, "msg:send", Map.merge(%{"client_msg_id" => Uniq.UUID.uuid4()}, payload))

    receive do
      %Phoenix.Socket.Reply{ref: ^ref, status: s, payload: p} -> {s, p}
    after
      2000 -> flunk("no reply")
    end
  end

  defp react(chan, to, target, emoji \\ "👍", op \\ "add", cmid \\ Uniq.UUID.uuid4()) do
    send_msg(chan, %{
      "client_msg_id" => cmid,
      "to" => to,
      "reaction" => %{"target" => target, "emoji" => emoji, "op" => op}
    })
  end

  defp events(user_id, kind) do
    {:ok, events, _} = Messaging.fetch_events(user_id, nil)
    Enum.filter(events, &(&1.kind == kind))
  end

  describe "length (§11.1)" do
    test "graphemes up to 4096, bytes up to 16 KiB, byte cap first", %{b: b, chan: chan} do
      to = b.user.id
      assert {:ok, _} = send_msg(chan, %{"to" => to, "body" => String.duplicate("a", 4096)})

      assert {:error, %{reason: "too_long"}} =
               send_msg(chan, %{"to" => to, "body" => String.duplicate("a", 4097)})

      # 4096 four-byte emoji = exactly 16 KiB: fine; one more grapheme is over both limits.
      assert {:ok, _} = send_msg(chan, %{"to" => to, "body" => String.duplicate("🫨", 4096)})

      assert {:error, %{reason: "too_long"}} =
               send_msg(chan, %{"to" => to, "body" => String.duplicate("🫨", 4097)})

      # Under 4096 graphemes but over 16 KiB (ZWJ families are 25 bytes each).
      assert {:error, %{reason: "too_long"}} =
               send_msg(chan, %{"to" => to, "body" => String.duplicate("👨‍👩‍👧‍👦", 700)})

      assert {:ok, _} = send_msg(chan, %{"to" => to, "body" => "👨‍👩‍👧‍👦"})
      assert {:error, %{reason: "empty_body"}} = send_msg(chan, %{"to" => to, "body" => "  \n"})
    end
  end

  describe "reactions (§11.2)" do
    setup %{b: b, chan: chan} do
      {:ok, %{message_id: target}} = send_msg(chan, %{"to" => b.user.id, "body" => "react to me"})
      %{target: target}
    end

    test "both inboxes get the same reaction event; no push; no status", %{
      a: a,
      b: b,
      chan: chan,
      target: target
    } do
      # Drain the push for the message itself.
      assert_receive {:push, "fcm-b", _}, 1_000

      cmid = Uniq.UUID.uuid4()

      assert {:ok, %{message_id: rid}} =
               react(chan, b.user.id, String.upcase(target), "❤️", "add", cmid)

      for u <- [a.user.id, b.user.id] do
        assert [%{event_id: ^rid, data: data}] = events(u, "reaction")

        assert %{"target" => ^target, "emoji" => "❤️", "op" => "add", "client_msg_id" => ^cmid} =
                 data

        assert data["message_id"] == rid and data["from"] == a.user.id and data["to"] == b.user.id
      end

      refute_receive {:push, _, _}, 400

      # Acks naming a reaction are ignored: no status event for a.
      :ok = Messaging.ack(b.user.id, [rid], "read")
      assert events(a.user.id, "status") == []

      # Idempotent resend: same reply, no second event.
      assert {:ok, %{message_id: ^rid}} = react(chan, b.user.id, target, "❤️", "add", cmid)
      assert length(events(b.user.id, "reaction")) == 1

      # remove is accepted too.
      assert {:ok, _} = react(chan, b.user.id, target, "❤️", "remove")
    end

    test "unknown_target is the same error in every case", %{
      a: a,
      b: b,
      chan: chan,
      target: target
    } do
      {:ok, %{message_id: reaction_id}} = react(chan, b.user.id, target)

      c = logged_in_user()
      befriend!(a, c)

      {:ok, %{message_id: other_conv}} =
        send_msg(chan, %{"to" => c.user.id, "body" => "elsewhere"})

      for t <- [
            "nope",
            nil,
            42,
            Ecto.UUID.generate(),
            RisiMe.TimeUUID.generate(),
            other_conv,
            reaction_id
          ] do
        assert {:error, %{reason: "unknown_target"}} = react(chan, b.user.id, t)
      end
    end

    test "invalid_emoji; valid emoji of every shape", %{b: b, chan: chan, target: target} do
      # The server checks shape only (one grapheme, <= 32 bytes, no control/whitespace); whether
      # it is an emoji is the clients' check (§11.2).
      for e <- ["ab", "", " ", "\n", "👍👍", "\u200B", "\u00A0", "\u0007", 42, nil] do
        assert {:error, %{reason: reason}} = react(chan, b.user.id, target, e)
        assert reason in ["invalid_emoji"], "#{inspect(e)} gave #{reason}"
      end

      for e <- ["👍", "❤️", "👍🏽", "👨‍👩‍👧‍👦", "🇱🇰", "1️⃣", "🏴󠁧󠁢󠁳󠁣󠁴󠁿", "🫨"] do
        assert {:ok, _} = react(chan, b.user.id, target, e), "#{inspect(e)} should be valid"
      end
    end

    test "bad op, more than one content field: bad_request", %{b: b, chan: chan, target: target} do
      assert {:error, %{reason: "bad_request"}} = react(chan, b.user.id, target, "👍", "toggle")

      assert {:error, %{reason: "bad_request"}} =
               send_msg(chan, %{
                 "to" => b.user.id,
                 "body" => "x",
                 "reaction" => %{"target" => target, "emoji" => "👍", "op" => "add"}
               })

      assert {:error, %{reason: "bad_request"}} =
               send_msg(chan, %{
                 "to" => b.user.id,
                 "body" => "x",
                 "ciphertext" => "AA==",
                 "generation" => 1,
                 "epoch" => 1
               })
    end

    test "order of checks: not_friends and e2ee_required before reaction checks; shared rate limit",
         %{a: a, b: b, chan: chan, target: target} do
      %{user: stranger} = logged_in_user()
      assert {:error, %{reason: "not_friends"}} = react(chan, stranger.id, "nope", "bad emoji")

      for _ <- 1..19, do: send_msg(chan, %{"to" => b.user.id, "body" => "x"})
      assert {:error, %{reason: "rate_limited"}} = react(chan, b.user.id, target)

      import RisiMe.MLSHelpers
      e2ee_group!(a, b)
      assert {:error, %{reason: "e2ee_required"}} = react(chan, b.user.id, "nope", "bad emoji")
    end
  end
end
