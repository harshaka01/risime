defmodule RisiMe.LiveKitIntegrationTest do
  @moduledoc """
  Optional: the real local LiveKit (`infra/livekit`, 127.0.0.1:7880) with the key pair from the
  environment (`LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`; the repo `.env` in dev/test). Excluded by
  default; run with `mix test --only livekit`. Checks the Twirp client (CreateRoom with the
  §20.2 options, ListRooms, ListParticipants, RemoveParticipant) and that a participant token
  minted by the server passes LiveKit's `/rtc/validate`, while one signed with another secret
  doesn't. Never prints a secret or a token.
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
end
