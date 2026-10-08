defmodule RisiMeWeb.CallsV118Test do
  @moduledoc """
  Contract v1.18 §19 (1:1 video calls), the server's part (§19.12): the cleartext `media` on
  `call:signal` (validation, default, copy onto the event), the `video` delivery filter (live and
  join/sync), the call push only to `video` devices (first push and 3-s fallback),
  `video_not_ready` after `calls_not_ready`, and `video_ready` / `missing_video`.
  """
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers

  alias RisiMe.Messaging
  alias RisiMeWeb.{InboxChannel, UserSocket}

  @video ~w(groups images deletes calls video)
  @voice ~w(groups images deletes calls)
  @old ~w(groups images deletes)

  setup :with_attestation_key
  setup :test_push!

  setup do
    a = logged_in_user(display_name: "Asha")
    b = logged_in_user(display_name: "Bimal")
    befriend!(a, b)
    a_dev = device!(a, @video)
    b_dev = device!(b, @video)
    RisiMe.GroupHelpers.clear_legacy!()
    conv = e2ee_group!(a, b)
    chan_a = join_dev(a, a_dev)
    %{a: a, b: b, a_dev: a_dev, b_dev: b_dev, conv: conv, chan_a: chan_a}
  end

  defp device!(user, caps, token \\ nil) do
    dev = Ecto.UUID.generate()

    params = %{
      "platform" => "android",
      "mls" => %{"signature_key" => b64(), "capabilities" => caps}
    }

    params = if token, do: Map.put(params, "push_token", token), else: params
    {:ok, _} = RisiMe.Devices.register(user.user.id, dev, params)
    :ok = RisiMe.MLS.record_instance(user.user.id, dev, nil, "0.3.0-test")
    dev
  end

  defp join_dev(user, dev, payload \\ %{}) do
    {:ok, sock} = connect(UserSocket, %{"token" => user.token, "device_id" => dev})
    {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user.user.id, payload)
    chan
  end

  defp join_reply(user, dev, payload) do
    {:ok, sock} = connect(UserSocket, %{"token" => user.token, "device_id" => dev})
    {:ok, reply, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user.user.id, payload)
    {reply, chan}
  end

  defp request(chan, event, payload) do
    ref = push(chan, event, payload)

    receive do
      %Phoenix.Socket.Reply{ref: ^ref, status: s, payload: p} -> {s, p}
    after
      2000 -> flunk("no reply to #{event}")
    end
  end

  defp payload(to, extra) do
    Map.merge(
      %{
        "client_msg_id" => Ecto.UUID.generate(),
        "to" => to,
        "call_id" => Ecto.UUID.generate(),
        "ring" => true,
        "ciphertext" => b64(64),
        "generation" => 1,
        "epoch" => 1
      },
      extra
    )
  end

  defp sig(chan, to, extra \\ %{}), do: request(chan, "call:signal", payload(to, extra))

  defp live_events(topic, kind, timeout \\ 300) do
    receive do
      %Phoenix.Socket.Message{topic: ^topic, event: "event", payload: %{kind: ^kind} = e} ->
        [e | live_events(topic, kind, timeout)]
    after
      timeout -> []
    end
  end

  defp friend!(a, devices) do
    c = logged_in_user()
    befriend!(a, c)
    devs = for {caps, token} <- devices, do: device!(c, caps, token)
    RisiMe.GroupHelpers.clear_legacy!()
    e2ee_group!(a, c)
    {c, devs}
  end

  describe "media on call:signal (§19.2)" do
    test "default audio, video copied onto the event; any other value is bad_request", ctx do
      %{a: a, b: b, chan_a: chan_a} = ctx
      topic = "inbox:" <> a.user.id

      {:ok, %{message_id: id1}} = sig(chan_a, b.user.id)
      assert [%{event_id: ^id1, data: %{"media" => "audio"}}] = live_events(topic, "call_signal")

      {:ok, %{message_id: id2}} = sig(chan_a, b.user.id, %{"media" => "audio", "ring" => false})
      assert [%{event_id: ^id2, data: %{"media" => "audio"}}] = live_events(topic, "call_signal")

      {:ok, %{message_id: id3}} = sig(chan_a, b.user.id, %{"media" => "video", "ring" => false})
      assert [%{event_id: ^id3, data: %{"media" => "video"}}] = live_events(topic, "call_signal")

      for bad <- ["VIDEO", "screen", nil, 1, true] do
        assert {:error, %{reason: "bad_request"}} = sig(chan_a, b.user.id, %{"media" => bad})
      end

      # Stored with it (join/sync replay).
      {:ok, events, _} = Messaging.fetch_events(b.user.id, nil, 500, true)
      assert Enum.find(events, &(&1.event_id == id3)).data["media"] == "video"
    end

    test "video_not_ready: a video ring to a user without a video device, after calls_not_ready",
         ctx do
      %{a: a, chan_a: chan_a} = ctx
      {voice_only, _} = friend!(a, [{@voice, nil}])
      {old_only, _} = friend!(a, [{@old, nil}])

      assert {:error, %{reason: "video_not_ready"}} =
               sig(chan_a, voice_only.user.id, %{"media" => "video"})

      # Without any calls device the earlier check wins.
      assert {:error, %{reason: "calls_not_ready"}} =
               sig(chan_a, old_only.user.id, %{"media" => "video"})

      # Non-ring video signals are fine.
      assert {:ok, _} = sig(chan_a, voice_only.user.id, %{"media" => "video", "ring" => false})

      # Once the user has a video device, the video ring goes through.
      _ = device!(voice_only, @video)
      assert {:ok, _} = sig(chan_a, voice_only.user.id, %{"media" => "video"})
    end
  end

  describe "the video delivery filter (§19.2)" do
    test "a voice-only calls socket gets audio signals but never video ones, live or on join/sync",
         ctx do
      %{a: a, chan_a: chan_a} = ctx
      {c, [c_video, c_voice]} = friend!(a, [{@video, nil}, {@voice, nil}])
      {:ok, before, _} = Messaging.fetch_events(c.user.id, nil, 500, true)
      since = before |> List.last() |> then(&(&1 && &1.event_id))
      chan_voice = join_dev(c, c_voice)
      topic = "inbox:" <> c.user.id

      {:ok, %{message_id: v1}} = sig(chan_a, c.user.id, %{"media" => "video"})
      {:ok, %{message_id: a1}} = sig(chan_a, c.user.id, %{"ring" => false})

      assert topic |> live_events("call_signal") |> Enum.map(& &1.event_id) == [a1]

      {reply, _} = join_reply(c, c_voice, %{"since" => since})
      assert Enum.map(reply.events, & &1.event_id) == [a1]
      {:ok, sync} = request(chan_voice, "sync", %{"since" => since})
      assert Enum.map(sync.events, & &1.event_id) == [a1]

      # The video device sees both.
      {reply, _} = join_reply(c, c_video, %{"since" => since})
      assert Enum.map(reply.events, & &1.event_id) == [v1, a1]

      # The voice device gains `video` while connected: video signals from then on.
      key = RisiMe.Repo.get_by(RisiMe.Devices.Device, device_id: c_voice).mls_signature_key

      {:ok, _} =
        RisiMe.Devices.register(c.user.id, c_voice, %{
          "platform" => "android",
          "mls" => %{"signature_key" => Base.encode64(key), "capabilities" => @video}
        })

      _ = live_events(topic, "call_signal", 100)

      {:ok, %{message_id: v2}} =
        sig(chan_a, c.user.id, %{"media" => "video", "ring" => false})

      assert topic |> live_events("call_signal") |> Enum.map(& &1.event_id) |> Enum.uniq() ==
               [v2]

      {:ok, sync} = request(chan_voice, "sync", %{"since" => since})
      assert Enum.map(sync.events, & &1.event_id) == [v1, a1, v2]
    end
  end

  describe "the call push (§19.2, §16.8)" do
    test "a video ring pushes only video devices; a voice ring all calls devices", ctx do
      %{a: a, chan_a: chan_a} = ctx
      vt = push_token("fcm-video")
      ot = push_token("fcm-voice")
      {c, _} = friend!(a, [{@video, vt}, {@voice, ot}])

      {:ok, _} = sig(chan_a, c.user.id, %{"media" => "video"})
      assert_receive {:push, ^vt, %{"type" => "call"}}, 1000
      refute_receive {:push, ^ot, _}, 500

      # A voice ring (another call) reaches both (after the 5-s callee window in prod; the
      # limiter is per pair, so use a new pair).
      {d, _} =
        friend!(a, [{@video, vt2 = push_token("fcm-v2")}, {@voice, ot2 = push_token("fcm-o2")}])

      {:ok, _} = sig(chan_a, d.user.id)
      assert_receive {:push, ^vt2, _}, 1000
      assert_receive {:push, ^ot2, _}, 1000
    end

    test "the 3-s fallback of a video ring reaches only live-looking video devices", ctx do
      %{a: a, chan_a: chan_a} = ctx
      vt = push_token("fcm-video")
      ot = push_token("fcm-voice")
      {c, [c_video, c_voice]} = friend!(a, [{@video, vt}, {@voice, ot}])
      _ = join_dev(c, c_video)
      _ = join_dev(c, c_voice)

      {:ok, _} = sig(chan_a, c.user.id, %{"media" => "video"})
      refute_receive {:push, _, _}, 100
      assert_receive {:push, ^vt, %{"type" => "call"}}, 1000
      refute_receive {:push, ^ot, _}, 500
    end
  end

  describe "video_ready / missing_video (§19.1)" do
    defp view(user, conv) do
      {200, view} = RisiMe.GroupHelpers.api(:get, "/api/v1/mls/groups/#{conv}", user.token)
      view
    end

    test "one video device per user is enough; the others are listed; plaintext never ready",
         ctx do
      %{a: a, b: b, conv: conv} = ctx
      assert %{"video_ready" => true, "missing_video" => []} = view(a, conv)

      {c, [_voice]} = friend!(a, [{@voice, nil}])
      conv_c = Messaging.conversation_id(a.user.id, c.user.id)
      v = view(a, conv_c)
      assert v["video_ready"] == false and v["calls_ready"] == true
      assert [%{"user_id" => cid}] = v["missing_video"]
      assert cid == c.user.id

      tablet = device!(b, @voice)
      # The new tablet supersedes b's phone until the phone is seen again (§12.1).
      :ok = RisiMe.MLS.record_instance(b.user.id, ctx.b_dev, nil, "0.3.0-test")
      v = view(a, conv)
      assert v["video_ready"] == true
      assert v["missing_video"] == [%{"user_id" => b.user.id, "device_id" => tablet}]

      d = logged_in_user()
      befriend!(a, d)
      _ = device!(d, @video)
      conv_d = Messaging.conversation_id(a.user.id, d.user.id)
      {200, plain} = RisiMe.GroupHelpers.api(:get, "/api/v1/mls/groups/#{conv_d}", a.token)
      assert plain["video_ready"] in [false, nil]
    end
  end
end
