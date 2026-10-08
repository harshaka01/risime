defmodule RisiMe.Calls.LiveKit.Twirp do
  @moduledoc """
  LiveKit's `RoomService` over Twirp/JSON at `LIVEKIT_API_URL` (loopback only, decision 056).
  Every request carries its own 60-s JWT with only the grant it needs (§20.2): `roomCreate` for
  `CreateRoom`, `roomList` for `ListRooms`, `roomAdmin` on that room for `ListParticipants` and
  `RemoveParticipant`. Logs carry room names and counts only, never a token.
  """
  @behaviour RisiMe.Calls.LiveKit.API

  require Logger

  alias RisiMe.Calls.LiveKit

  @timeout 5_000

  @impl true
  def create_room(config, name, opts) do
    body = %{
      "name" => name,
      "empty_timeout" => opts.empty_timeout,
      "departure_timeout" => opts.departure_timeout,
      "max_participants" => opts.max_participants,
      "metadata" => opts.metadata
    }

    case call(config, "CreateRoom", body, %{"roomCreate" => true}) do
      {:ok, room} -> {:ok, room(room)}
      _ -> {:error, :unavailable}
    end
  end

  @impl true
  def list_rooms(config, names) do
    body = if names, do: %{"names" => names}, else: %{}

    case call(config, "ListRooms", body, %{"roomList" => true}) do
      {:ok, reply} -> {:ok, Enum.map(reply["rooms"] || [], &room/1)}
      _ -> {:error, :unavailable}
    end
  end

  @impl true
  def list_participants(config, room) do
    grant = %{"roomAdmin" => true, "room" => room}

    case call(config, "ListParticipants", %{"room" => room}, grant) do
      {:ok, reply} -> {:ok, for(p <- reply["participants"] || [], do: %{identity: p["identity"]})}
      # The room closed in between: nobody to list.
      {:error, :not_found} -> {:ok, []}
      error -> error
    end
  end

  @impl true
  def remove_participant(config, room, identity) do
    grant = %{"roomAdmin" => true, "room" => room}

    case call(config, "RemoveParticipant", %{"room" => room, "identity" => identity}, grant) do
      {:ok, _} -> :ok
      {:error, :not_found} -> :ok
      error -> error
    end
  end

  defp room(r) do
    %{
      name: r["name"],
      metadata: r["metadata"] || "",
      num_participants: int(r["num_participants"] || r["numParticipants"]),
      max_participants: int(r["max_participants"] || r["maxParticipants"])
    }
  end

  defp int(n) when is_integer(n), do: n
  defp int(s) when is_binary(s), do: String.to_integer(s)
  defp int(_), do: 0

  defp call(config, method, body, grant) do
    url = String.trim_trailing(config.api_url, "/") <> "/twirp/livekit.RoomService/" <> method

    case Req.post(url,
           json: body,
           headers: [{"authorization", "Bearer " <> LiveKit.api_token(config, grant)}],
           retry: false,
           receive_timeout: @timeout,
           connect_options: [timeout: @timeout]
         ) do
      {:ok, %Req.Response{status: 200, body: reply}} when is_map(reply) ->
        {:ok, reply}

      {:ok, %Req.Response{status: 404}} ->
        {:error, :not_found}

      {:ok, %Req.Response{status: status}} ->
        Logger.warning("livekit #{method}: HTTP #{status}")
        {:error, :unavailable}

      {:error, e} ->
        Logger.warning("livekit #{method}: #{Exception.message(e)}")
        {:error, :unavailable}
    end
  end
end
