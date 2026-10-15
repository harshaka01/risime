defmodule RisiMeWeb.CallRisiRoomsTest do
  @moduledoc """
  Decision 090 / proposal 2026-10-14-call-risi §41.1–§41.2, against the fake LiveKit: `start` with
  `risi_call` (the switch, the Risi-chat rule, the `call_risi` device, the limit, health, the 2-seat
  room with `k: "risi"`, the reply's `risi_call`, Risi's session started with its own token), `join`
  refused in a Call Risi room, `risi_call` on other actions, and **parity**: without `risi_call` (or
  with the switch off) every reply and room is exactly v1.47's, and `User.call_risi` is absent.
  """
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]
  import RisiMe.GroupHelpers

  alias RisiMe.FakeLiveKit
  alias RisiMe.Calls.LiveKit

  @caps ~w(groups images deletes calls video group_calls risi_tools call_risi)
  @old ~w(groups images deletes calls video group_calls risi_tools)

  defmodule Media do
    @behaviour RisiMe.Agent.CallRisi.Media
    @impl true
    def open(_owner, args) do
      send(:persistent_term.get({__MODULE__, :test}), {:session_started, args})
      {:ok, nil}
    end

    @impl true
    def command(_, _), do: :ok
    @impl true
    def close(_), do: :ok
  end

  setup :with_attestation_key
  setup :fake

  defp fake(ctx), do: FakeLiveKit.setup(ctx)

  setup do
    keys = [
      :risi_call_media,
      :risi_call_chat_check,
      :risi_call_health,
      :risi_agent_flags,
      :risi_dev_users,
      :admin_settings
    ]

    prev = for k <- keys, do: {k, Application.get_env(:risime, k)}

    on_exit(fn ->
      for {k, v} <- prev,
          do:
            if(v,
              do: Application.put_env(:risime, k, v),
              else: Application.delete_env(:risime, k)
            )
    end)

    case start_supervised(RisiMe.Agent.CallRisi.Supervisor) do
      {:ok, _} -> :ok
      {:error, _} -> :ok
    end

    :persistent_term.put({Media, :test}, self())
    [a, b] = for n <- ~w(Asha Bimal), do: logged_in_user(display_name: n)
    befriend!(a, b)
    a_dev = device!(a, @caps)
    a_old = device!(a, @old)
    b_dev = device!(b, @caps)
    clear_legacy!()
    id = group!(a, a_dev, a_old, [b])

    Application.put_env(:risime, :risi_call_media, Media)
    Application.put_env(:risime, :risi_call_chat_check, fn conv, _user -> conv == id end)
    Application.put_env(:risime, :risi_call_health, fn -> true end)
    Application.put_env(:risime, :risi_dev_users, [a.user.id])
    %{a: a, b: b, a_dev: a_dev, a_old: a_old, b_dev: b_dev, id: id}
  end

  defp flag(v), do: Application.put_env(:risime, :risi_agent_flags, %{"RISI_CALL_RISI" => v})

  defp device!(user, caps) do
    dev = Ecto.UUID.generate()

    params = %{
      "platform" => "android",
      "mls" => %{"signature_key" => b64(), "capabilities" => caps}
    }

    {:ok, _} = RisiMe.Devices.register(user.user.id, dev, params)
    :ok = RisiMe.MLS.record_instance(user.user.id, dev, nil, "0.3.0-test")
    dev
  end

  defp group!(a, a_dev, a_old, others) do
    body = %{
      "client_group_id" => Ecto.UUID.generate(),
      "member_ids" => Enum.map(others, & &1.user.id)
    }

    %{"group" => %{"id" => id}} =
      api(:post, "/api/v1/groups", a.token, body, a_dev) |> assert_status(201)

    commit = create_commit([a.user.id | Enum.map(others, & &1.user.id)], {a.user.id, a_dev})
    api(:post, "/api/v1/mls/groups/#{id}/commit", a.token, commit, a_dev) |> assert_status(200)
    _ = a_old
    id
  end

  defp rooms(user, dev, body), do: api(:post, "/api/v1/calls/rooms", user.token, body, dev)

  defp req(id, extra) do
    Map.merge(
      %{
        "conversation_id" => id,
        "call_id" => Ecto.UUID.generate(),
        "media" => "audio",
        "action" => "start"
      },
      extra
    )
  end

  defp stop_sessions do
    for {_, pid, _, _} <- DynamicSupervisor.which_children(RisiMe.Agent.CallRisi.Sessions),
        do: DynamicSupervisor.terminate_child(RisiMe.Agent.CallRisi.Sessions, pid)
  end

  describe "start with risi_call (§41.2)" do
    test "canary for a dev user: a 2-seat k=risi room, risi_call in the reply, Risi's session",
         ctx do
      %{a: a, a_dev: a_dev, id: id, livekit: lk} = ctx
      flag("canary")
      body = req(id, %{"risi_call" => true})
      {200, reply} = rooms(a, a_dev, body)

      assert reply["max_participants"] == 2
      assert reply["identity"] == "#{a.user.id}/#{a_dev}"

      assert reply["risi_call"] == %{
               "state" => "joining",
               "e2ee" => false,
               "risi_identity" => RisiMe.Agent.CallRisi.risi_identity(),
               "max_s" => 1800,
               "lang" => "en"
             }

      r = FakeLiveKit.room(reply["room"])
      assert r.max_participants == 2
      assert Jason.decode!(r.metadata) == %{"c" => id, "m" => "audio", "k" => "risi"}

      assert_receive {:session_started, args}
      assert args.conv == id and args.call_id == body["call_id"] and args.user_id == a.user.id
      assert args.device_id == a_dev and args.room == reply["room"]
      {:ok, claims} = LiveKit.verify(args.risi_token, lk.api_secret)
      assert claims["sub"] == RisiMe.Agent.CallRisi.risi_identity()
      assert claims["video"]["canPublishSources"] == ["microphone"]
      assert claims["video"]["room"] == reply["room"]
      # v1.52 §41.8: both tokens of a Call Risi room may publish data (the `risi.cal` packets).
      assert claims["video"]["canPublishData"] == true
      {:ok, mine} = LiveKit.verify(reply["token"], lk.api_secret)
      assert mine["video"]["canPublishData"] == true
      assert mine["video"]["canPublishSources"] == ["microphone"]

      # Nobody else may join a Call Risi room (b is a member with a group_calls device).
      join = body |> Map.delete("risi_call") |> Map.put("action", "join")
      assert {409, %{"error" => %{"code" => "call_full"}}} = rooms(ctx.b, ctx.b_dev, join)

      stop_sessions()
    end

    test "the order of refusals: switch, Risi chat, device, limit, health", ctx do
      %{a: a, b: b, a_dev: a_dev, a_old: a_old, id: id} = ctx

      flag("off")

      assert {403, %{"error" => %{"code" => "call_risi_off"}}} =
               rooms(a, a_dev, req(id, %{"risi_call" => true}))

      # canary: only RISI_DEV_USERS.
      flag("canary")

      assert {403, %{"error" => %{"code" => "call_risi_off"}}} =
               rooms(b, ctx.b_dev, req(id, %{"risi_call" => true}))

      Application.put_env(:risime, :risi_call_chat_check, fn _, _ -> false end)

      assert {422, %{"error" => %{"code" => "not_risi_chat"}}} =
               rooms(a, a_dev, req(id, %{"risi_call" => true}))

      Application.put_env(:risime, :risi_call_chat_check, fn conv, _ -> conv == id end)

      assert {422, %{"error" => %{"code" => "not_risi_chat"}}} =
               rooms(a, a_dev, req(id, %{"risi_call" => true, "media" => "video"}))

      assert {403, %{"error" => %{"code" => "invalid_device"}}} =
               rooms(a, a_old, req(id, %{"risi_call" => true}))

      Application.put_env(:risime, :risi_call_health, fn -> false end)

      assert {503, %{"error" => %{"code" => "risi_unavailable"}}} =
               rooms(a, a_dev, req(id, %{"risi_call" => true}))

      Application.put_env(:risime, :risi_call_health, fn -> true end)

      flag("on")
      {200, _} = rooms(a, a_dev, req(id, %{"risi_call" => true}))
      {200, _} = rooms(a, a_dev, req(id, %{"risi_call" => true}))

      assert {409, %{"error" => %{"code" => "risi_busy"}}} =
               rooms(a, a_dev, req(id, %{"risi_call" => true}))

      stop_sessions()
    end

    test "v1.48 §41.6: max_s comes from the admin setting, the same in the reply and the session",
         ctx do
      %{a: a, a_dev: a_dev, id: id} = ctx
      flag("on")

      ex =
        Path.expand("../../../../contract/v1/examples/calls_room_reply_call_risi.json", __DIR__)
        |> File.read!()
        |> Jason.decode!()

      for {v, want} <- [{"600", 600}, {"5", 60}, {"7200", 3600}, {"x", 1800}] do
        Application.put_env(:risime, :admin_settings, %{"call_risi_max_s" => v})
        {200, reply} = rooms(a, a_dev, req(id, %{"risi_call" => true}))
        assert reply["risi_call"]["max_s"] == want, v
        assert_receive {:session_started, args}
        assert args.max_s == want
        # The reply has exactly the example's fields (calls_room_reply_call_risi.json).
        assert Enum.sort(Map.keys(reply)) == Enum.sort(Map.keys(ex))
        assert Enum.sort(Map.keys(reply["risi_call"])) == Enum.sort(Map.keys(ex["risi_call"]))
        stop_sessions()
      end
    end

    test "risi_call on another action is 400", ctx do
      flag("on")

      for action <- ~w(join status upgrade) do
        assert {400, _} =
                 rooms(ctx.a, ctx.a_dev, req(ctx.id, %{"risi_call" => true, "action" => action}))
      end
    end
  end

  describe "parity (flag off or no risi_call)" do
    test "a normal start is v1.47's exactly, whatever the switch says", ctx do
      %{a: a, a_dev: a_dev, id: id} = ctx

      for v <- ~w(off canary on) do
        flag(v)

        for extra <- [%{}, %{"risi_call" => false}] do
          {200, reply} = rooms(a, a_dev, req(id, extra))

          assert Map.keys(reply) |> Enum.sort() ==
                   ~w(expires_at identity max_participants media room token url)

          assert reply["max_participants"] == 32
          r = FakeLiveKit.room(reply["room"])
          assert Jason.decode!(r.metadata) == %{"c" => id, "m" => "audio"}
          assert r.max_participants == 32
          # v1.52 §41.8: data stays off outside a Call Risi room.
          {:ok, claims} = LiveKit.verify(reply["token"], ctx.livekit.api_secret)
          assert claims["video"]["canPublishData"] == false
        end
      end

      refute_received {:session_started, _}
    end

    test "User.call_risi only while the switch applies to the user", ctx do
      %{a: a, b: b} = ctx
      flag("off")
      refute Map.has_key?(RisiMeWeb.ApiJSON.user(a.user), :call_risi)
      flag("canary")
      assert RisiMeWeb.ApiJSON.user(a.user).call_risi == true
      refute Map.has_key?(RisiMeWeb.ApiJSON.user(b.user), :call_risi)
      flag("on")
      assert RisiMeWeb.ApiJSON.user(b.user).call_risi == true

      Application.put_env(:risime, :risi_agent_flags, %{
        "RISI_CALL_RISI" => "on",
        "RISI_AGENTS" => "legacy"
      })

      refute Map.has_key?(RisiMeWeb.ApiJSON.user(b.user), :call_risi)
    end
  end
end
