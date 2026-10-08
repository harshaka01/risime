defmodule RisiMeWeb.UserSocket do
  @moduledoc """
  Realtime entry point: `/socket/websocket?vsn=2.0.0` (V1 and V2 serializers).

  The token (contract v1.3 §6.2) comes from the `Authorization: Bearer` header of the upgrade
  (preferred; copied into `connect_info[:auth_token]` by `RisiMeWeb.Endpoint`), from the
  channels client's `authToken` subprotocol, or from the `token=` query parameter.
  A missing, invalid or foreign token, or a user who isn't allowlisted or bound, is refused.

  Socket ids: a dev token's sockets share `user_token:<id>` so logout disconnects them; each
  JWT socket gets its own `jwt_socket:<random>` for its expiry disconnect.
  """
  use Phoenix.Socket

  channel "inbox:*", RisiMeWeb.InboxChannel

  @impl true
  def connect(params, socket, connect_info) do
    token = connect_info[:auth_token] || params["token"]

    case RisiMe.Auth.authenticate(token) |> phone_gate() do
      {:ok, %{kind: :dev, user: user, token_record: record}} ->
        RisiMe.Accounts.touch_token(record)
        socket_id = id_for(record)
        connected(socket, user, socket_id, :dev, nil, [token_id: record.id], params)

      {:ok, %{kind: :jwt, user: user, exp: exp}} ->
        socket_id = "jwt_socket:" <> Base.url_encode64(:crypto.strong_rand_bytes(12))
        connected(socket, user, socket_id, :jwt, exp, [], params)

      {:error, _} ->
        :telemetry.execute([:risime, :socket, :connect], %{count: 1}, %{result: :refused})
        ip = RisiMeWeb.ClientIP.from_connect_info(connect_info)
        RisiMe.AuthLog.failure(ip, :socket_refused, "/socket/websocket")
        :error
    end
  end

  # Contract v1.4 §7.2: an unverified user's upgrade is refused like a 403.
  defp phone_gate({:ok, auth} = ok),
    do:
      if(RisiMe.Accounts.phone_verified?(auth.user, auth.kind),
        do: ok,
        else: {:error, :phone_unverified}
      )

  defp phone_gate(error), do: error

  defp connected(socket, user, socket_id, kind, exp, extra, params) do
    :telemetry.execute([:risime, :socket, :connect], %{count: 1}, %{result: :ok})
    RisiMe.SocketTracker.track(user.id, socket_id: socket_id, exp: exp)

    # v1.7 census: every app instance, by its per-install device_id; pre-v1.7 apps send none and
    # count as one legacy instance per dev token (or per user for Keycloak tokens).
    device_id =
      case Ecto.UUID.cast(params["device_id"]) do
        {:ok, id} -> id
        :error -> nil
      end

    legacy_key = if kind == :dev, do: "token:#{extra[:token_id]}", else: "jwt"
    first_seen = RisiMe.MLS.census(user.id, device_id, legacy_key, params["app_version"])

    {:ok,
     assign(
       socket,
       [
         user_id: user.id,
         socket_id: socket_id,
         auth_kind: kind,
         token_id: nil,
         device_id: device_id,
         # v1.10 §13.2: `history_before` on every join and `sync` reply of this socket.
         history_before: first_seen && RisiMe.Messaging.iso(first_seen)
       ] ++
         extra
     )}
  end

  @doc "Socket id shared by a dev token's sockets, so logging out disconnects them."
  def id_for(%{id: token_id}), do: "user_token:#{token_id}"

  @impl true
  def id(socket), do: socket.assigns.socket_id

  @doc """
  WebSock control frames (Bandit calls this when it is exported; it answers pings itself).
  A pong answers a push-watchdog ping (`RisiMe.Push.Dispatcher`): the client has read every
  frame sent before it.
  """
  @impl true
  def handle_control({data, opcode: :pong}, state) do
    RisiMe.Push.Dispatcher.pong(data)
    {:ok, state}
  end

  def handle_control(_frame, state), do: {:ok, state}
end
