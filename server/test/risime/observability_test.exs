defmodule RisiMe.ObservabilityTest do
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures

  alias RisiMe.JSONLogFormatter
  alias RisiMeWeb.{InboxChannel, UserSocket}

  describe "JSON log formatter" do
    test "writes one JSON object per line with allowlisted metadata only" do
      event = %{
        level: :info,
        msg: {:string, ["hello ", "world"]},
        meta: %{
          time: 1_791_000_000_000_000,
          request_id: "F1",
          mfa: {RisiMe.Messaging, :send, 2},
          secret_thing: "s3cr3t"
        }
      }

      line = event |> JSONLogFormatter.format(%{}) |> IO.iodata_to_binary()
      assert String.ends_with?(line, "\n")
      decoded = Jason.decode!(line)
      assert decoded["msg"] == "hello world"
      assert decoded["level"] == "info"
      assert decoded["request_id"] == "F1"
      assert decoded["mfa"] == "RisiMe.Messaging.send/2"
      assert decoded["time"] =~ ~r/^\d{4}-\d\d-\d\dT/
      refute Map.has_key?(decoded, "secret_thing")
      refute line =~ "s3cr3t"
    end

    test "formats format/args and reports" do
      line = JSONLogFormatter.format(%{level: :warning, msg: {~c"x=~p", [1]}, meta: %{}}, %{})
      assert Jason.decode!(IO.iodata_to_binary(line))["msg"] == "x=1"

      line = JSONLogFormatter.format(%{level: :error, msg: {:report, %{a: 1}}, meta: %{}}, %{})
      assert Jason.decode!(IO.iodata_to_binary(line))["msg"] =~ "a: 1"
    end

    test "message bodies, OTP codes and tokens are filtered from Phoenix param logs" do
      filtered =
        Phoenix.Logger.filter_values(%{
          "body" => "hi",
          "code" => "123456",
          "token" => "abc",
          "to" => "x"
        })

      assert filtered == %{
               "body" => "[FILTERED]",
               "code" => "[FILTERED]",
               "token" => "[FILTERED]",
               "to" => "x"
             }
    end
  end

  describe "telemetry" do
    setup do
      events = [
        [:risime, :socket, :connect],
        [:risime, :socket, :disconnect],
        [:risime, :inbox, :join],
        [:risime, :message, :send, :stop],
        [:risime, :message, :ack]
      ]

      ref = :telemetry_test.attach_event_handlers(self(), events)
      on_exit(fn -> :telemetry.detach(ref) end)
      %{ref: ref}
    end

    test "connect, join, send, ack and disconnect emit events", %{ref: ref} do
      a = logged_in_user()
      b = logged_in_user()
      assert :error = connect(UserSocket, %{"token" => "nope"})
      assert_receive {[:risime, :socket, :connect], ^ref, %{count: 1}, %{result: :refused}}

      # A separate "connection process", so its exit is a disconnect.
      test = self()

      conn_pid =
        spawn(fn ->
          {:ok, _} = UserSocket.connect(%{"token" => a.token}, %Phoenix.Socket{}, %{})
          send(test, :connected)
          receive do: (:stop -> :ok)
        end)

      assert_receive :connected
      assert_receive {[:risime, :socket, :connect], ^ref, %{count: 1}, %{result: :ok}}

      {:ok, sock_a} = connect(UserSocket, %{"token" => a.token})
      {:ok, _, chan_a} = subscribe_and_join(sock_a, InboxChannel, "inbox:" <> a.user.id, %{})
      assert_receive {[:risime, :inbox, :join], ^ref, %{count: 1}, %{result: :ok}}

      ref_push =
        push(chan_a, "msg:send", %{
          "client_msg_id" => Uniq.UUID.uuid4(),
          "to" => b.user.id,
          "body" => "hi"
        })

      assert_reply ref_push, :ok, %{message_id: id}

      assert_receive {[:risime, :message, :send, :stop], ^ref, %{duration: d}, %{result: :ok}}
      assert is_integer(d) and d > 0

      {:ok, sock_b} = connect(UserSocket, %{"token" => b.token})
      {:ok, _, chan_b} = subscribe_and_join(sock_b, InboxChannel, "inbox:" <> b.user.id, %{})
      ref_ack = push(chan_b, "msg:ack", %{"message_ids" => [id], "status" => "read"})
      assert_reply ref_ack, :ok, %{}
      assert_receive {[:risime, :message, :ack], ^ref, %{count: 1}, %{status: :read}}

      open = RisiMe.SocketTracker.open_count()
      assert open >= 1
      send(conn_pid, :stop)
      user_a = a.user.id

      assert_receive {[:risime, :socket, :disconnect], ^ref, %{duration: _},
                      %{user_id: ^user_a, reason: :normal}}
    end

    test "every RisiMe metric is defined" do
      names = Enum.map(RisiMeWeb.Telemetry.metrics(), & &1.name)

      for n <- [
            [:risime, :message, :send, :stop, :duration],
            [:risime, :message, :ack, :count],
            [:risime, :socket, :connect, :count],
            [:risime, :socket, :disconnect, :duration],
            [:risime, :socket, :open, :count],
            [:risime, :inbox, :join, :count]
          ],
          do: assert(n in names)
    end
  end
end
