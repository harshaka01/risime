defmodule RisiMe.Calls.LiveKit.API do
  @moduledoc """
  LiveKit's server API (`RoomService`), as much as v1.19 §20.2 and v1.23 §23.6 need. The real client is
  `RisiMe.Calls.LiveKit.Twirp` (loopback HTTP, per-request 60-s JWTs); tests use a fake.

  A room is `%{name, metadata, num_participants, max_participants}`; a participant is
  `%{identity}`. Every callback gets the `RisiMe.Calls.LiveKit.config/0` map first. Any
  transport or server failure is `{:error, :unavailable}`.
  """

  @type config :: map
  @type room :: %{
          name: String.t(),
          metadata: String.t(),
          num_participants: non_neg_integer,
          max_participants: non_neg_integer
        }

  @doc "`CreateRoom` (idempotent): `opts` has `max_participants`, `empty_timeout`, `departure_timeout`, `metadata`."
  @callback create_room(config, name :: String.t(), opts :: map) ::
              {:ok, room} | {:error, :unavailable}

  @doc "`ListRooms`: `names` = nil lists every room."
  @callback list_rooms(config, names :: [String.t()] | nil) ::
              {:ok, [room]} | {:error, :unavailable}

  @doc "`ListParticipants` of one room."
  @callback list_participants(config, room :: String.t()) ::
              {:ok, [%{identity: String.t()}]} | {:error, :unavailable}

  @doc "`RemoveParticipant` (a participant already gone is `:ok`)."
  @callback remove_participant(config, room :: String.t(), identity :: String.t()) ::
              :ok | {:error, :unavailable}

  @doc "`UpdateRoomMetadata` (v1.23 §23.6): replaces the room's metadata. A missing room is `:not_found`."
  @callback update_room_metadata(config, room :: String.t(), metadata :: String.t()) ::
              :ok | {:error, :not_found | :unavailable}

  @doc """
  `UpdateParticipant` with a `ParticipantPermission` (v1.23 §23.6, `permission` as
  `RisiMe.Calls.LiveKit.video_permission/1`). A participant that isn't there is `:not_found`.
  """
  @callback update_participant(
              config,
              room :: String.t(),
              identity :: String.t(),
              permission :: map
            ) :: :ok | {:error, :not_found | :unavailable}
end
