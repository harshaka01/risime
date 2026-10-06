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

    case RisiMe.Auth.authenticate(token) do
      {:ok, %{kind: :dev, user: user, token_record: record}} ->
        RisiMe.Accounts.touch_token(record)
        socket_id = id_for(record)
        connected(socket, user, socket_id, :dev, nil, token_id: record.id)

      {:ok, %{kind: :jwt, user: user, exp: exp}} ->
        socket_id = "jwt_socket:" <> Base.url_encode64(:crypto.strong_rand_bytes(12))
        connected(socket, user, socket_id, :jwt, exp, [])

      {:error, _} ->
        :telemetry.execute([:risime, :socket, :connect], %{count: 1}, %{result: :refused})
        :error
    end
  end

  defp connected(socket, user, socket_id, kind, exp, extra) do
    :telemetry.execute([:risime, :socket, :connect], %{count: 1}, %{result: :ok})
    RisiMe.SocketTracker.track(user.id, socket_id: socket_id, exp: exp)

    {:ok,
     assign(
       socket,
       [user_id: user.id, socket_id: socket_id, auth_kind: kind, token_id: nil] ++ extra
     )}
  end

  @doc "Socket id shared by a dev token's sockets, so logging out disconnects them."
  def id_for(%{id: token_id}), do: "user_token:#{token_id}"

  @impl true
  def id(socket), do: socket.assigns.socket_id
end
