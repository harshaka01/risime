defmodule RisiMe.LiveKitIntegrationTest do
  @moduledoc """
  Optional: the real local LiveKit (`infra/livekit`, 127.0.0.1:7880) with the key pair from the
  environment (`LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`; the repo `.env` in dev/test). Excluded by
  default; run with `mix test --only livekit`. Checks the Twirp client (CreateRoom with the
  §20.2 options, ListRooms, ListParticipants, RemoveParticipant) and that a participant token
  minted by the server passes LiveKit's `/rtc/validate`, while one signed with another secret
  doesn't. v1.23 (§23.6): with a participant connected over LiveKit's signalling WebSocket,
  `UpdateRoomMetadata` turns a voice room to video (and an idempotent `CreateRoom` keeps it),
  `UpdateParticipant` widens the live grant to microphone, camera and screen share, a missing
  participant is `:not_found`, and a token with screen share passes `/rtc/validate`. Never prints
  a secret or a token.
  """
  use ExUnit.Case, async: false

  alias RisiMe.Calls.LiveKit
  alias RisiMe.Calls.LiveKit.Twirp

  @moduletag :livekit

  setup do
    key = System.get_env("LIVEKIT_API_KEY", "")
    secret = System.get_env("LIVEKIT_API_SECRET", "")
    api_url = System.get_env("LIVEKIT_API_URL", "http://127.0.0.1:7880")

    if key == "" or secret == "",
      do: flunk("LIVEKIT_API_KEY / LIVEKIT_API_SECRET are not set")

    %{config: %{url: "ws://unused", api_url: api_url, api_key: key, api_secret: secret}}
  end

  test "rooms through the server API; a server-minted token is accepted by LiveKit", %{
    config: config
  } do
    conv = "grp:" <> Ecto.UUID.generate()
    call_id = Ecto.UUID.generate()
    room = LiveKit.room_name(config.api_secret, conv, call_id, "video")
    metadata = Jason.encode!(%{"c" => conv, "m" => "video"})

    # auto_create is off: a token alone doesn't open a room.
    identity = LiveKit.identity(Ecto.UUID.generate(), Ecto.UUID.generate())
    {token, _exp} = LiveKit.participant_token(config, identity, room, "video", now())
    assert validate(config, token) == 404

    opts = %{max_participants: 8, empty_timeout: 10, departure_timeout: 20, metadata: metadata}
    assert {:ok, created} = Twirp.create_room(config, room, opts)
    assert created.name == room and created.max_participants == 8
    assert created.metadata == metadata and created.num_participants == 0

    # Idempotent.
    assert {:ok, %{name: ^room}} = Twirp.create_room(config, room, opts)

    assert {:ok, [listed]} = Twirp.list_rooms(config, [room])
    assert listed.name == room and listed.metadata == metadata
    assert {:ok, all} = Twirp.list_rooms(config, nil)
    assert Enum.any?(all, &(&1.name == room))
    assert {:ok, []} = Twirp.list_rooms(config, [room <> "x"])

    assert {:ok, []} = Twirp.list_participants(config, room)
    assert :ok = Twirp.remove_participant(config, room, identity)

    {token, _exp} = LiveKit.participant_token(config, identity, room, "video", now())
    assert validate(config, token) == 200

    bad =
      LiveKit.sign(
        LiveKit.participant_claims(config.api_key, identity, room, "video", now()),
        "wrong-secret-0123456789abcdef"
      )

    assert validate(config, bad) == 401
  end

  defp now, do: System.os_time(:second)

  defp validate(config, token) do
    url = String.trim_trailing(config.api_url, "/") <> "/rtc/validate"
    %Req.Response{status: status} = Req.get!(url, params: [access_token: token], retry: false)
    status
  end

  test "v1.23 upgrade: UpdateRoomMetadata and UpdateParticipant on a connected participant", %{
    config: config
  } do
    conv = "grp:" <> Ecto.UUID.generate()
    call_id = Ecto.UUID.generate()
    room = LiveKit.room_name(config.api_secret, conv, call_id, "audio")
    voice = Jason.encode!(%{"c" => conv, "m" => "audio"})
    video = Jason.encode!(%{"c" => conv, "m" => "video"})
    opts = %{max_participants: 32, empty_timeout: 10, departure_timeout: 20, metadata: voice}
    assert {:ok, %{max_participants: 32}} = Twirp.create_room(config, room, opts)

    identity = LiveKit.identity(Ecto.UUID.generate(), Ecto.UUID.generate())
    {token, _exp} = LiveKit.participant_token(config, identity, room, "audio", now())
    ws = connect_signal(config, token)

    try do
      assert wait_until(fn ->
               match?({:ok, [%{identity: ^identity}]}, Twirp.list_participants(config, room))
             end)

      assert :ok = Twirp.update_room_metadata(config, room, video)
      assert {:ok, [r]} = Twirp.list_rooms(config, [room])
      assert r.metadata == video and r.max_participants == 32

      # An idempotent CreateRoom (a late `start` with the start media) keeps the metadata.
      assert {:ok, _} = Twirp.create_room(config, room, opts)
      assert {:ok, [%{metadata: ^video}]} = Twirp.list_rooms(config, [room])

      assert :ok =
               Twirp.update_participant(config, room, identity, LiveKit.video_permission(true))

      assert :ok =
               Twirp.update_participant(config, room, identity, LiveKit.video_permission(false))

      other = LiveKit.identity(Ecto.UUID.generate(), Ecto.UUID.generate())

      assert {:error, :not_found} =
               Twirp.update_participant(config, room, other, LiveKit.video_permission(false))

      assert {:error, :not_found} =
               Twirp.update_room_metadata(config, room <> "x", video)

      {token, _exp} = LiveKit.participant_token(config, other, room, "video", now(), true)
      {:ok, claims} = LiveKit.verify(token, config.api_secret)
      assert claims["video"]["canPublishSources"] == ~w(microphone camera screen_share)
      assert validate(config, token) == 200
    after
      Mint.HTTP.close(ws)
      Twirp.remove_participant(config, room, identity)
    end
  end

  # LiveKit adds a participant when its signalling WebSocket connects (no media needed).
  defp connect_signal(config, token) do
    uri = URI.parse(config.api_url)
    {:ok, conn} = Mint.HTTP.connect(:http, uri.host, uri.port, protocols: [:http1])
    path = "/rtc?" <> URI.encode_query(access_token: token, protocol: 15, auto_subscribe: 0)
    {:ok, conn, ref} = Mint.WebSocket.upgrade(:ws, conn, path, [])
    await_upgrade(conn, ref, nil)
  end

  defp await_upgrade(conn, ref, status) do
    receive do
      msg ->
        {:ok, conn, responses} = Mint.WebSocket.stream(conn, msg)

        status =
          Enum.find_value(responses, status, &(match?({:status, ^ref, _}, &1) && elem(&1, 2)))

        case Enum.find(responses, &match?({:headers, ^ref, _}, &1)) do
          {:headers, ^ref, headers} ->
            assert status == 101
            {:ok, conn, _ws} = Mint.WebSocket.new(conn, ref, status, headers)
            conn

          nil ->
            await_upgrade(conn, ref, status)
        end
    after
      5_000 -> flunk("no WebSocket upgrade from LiveKit")
    end
  end

  defp wait_until(fun, tries \\ 30) do
    cond do
      fun.() -> true
      tries == 0 -> false
      true -> Process.sleep(100) && wait_until(fun, tries - 1)
    end
  end
end
