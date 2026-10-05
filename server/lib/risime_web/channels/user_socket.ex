defmodule RisiMeWeb.UserSocket do
  @moduledoc """
  Realtime entry point: `/socket/websocket?token=<token>&vsn=2.0.0` (V1 and V2 serializers).
  A missing, unknown or revoked token refuses the connection.
  """
  use Phoenix.Socket

  channel "inbox:*", RisiMeWeb.InboxChannel

  @impl true
  def connect(%{"token" => token}, socket, _connect_info) when is_binary(token) do
    case RisiMe.Accounts.fetch_by_token(token) do
      {user, token_record} ->
        RisiMe.Accounts.touch_token(token_record)
        :telemetry.execute([:risime, :socket, :connect], %{count: 1}, %{result: :ok})
        RisiMe.SocketTracker.track(user.id)
        {:ok, assign(socket, user_id: user.id, token_id: token_record.id)}

      nil ->
        refused()
    end
  end

  def connect(_params, _socket, _connect_info), do: refused()

  defp refused do
    :telemetry.execute([:risime, :socket, :connect], %{count: 1}, %{result: :refused})
    :error
  end

  @doc "Socket id per token, so logging out disconnects that token's sockets."
  def id_for(%{id: token_id}), do: "user_token:#{token_id}"

  @impl true
  def id(socket), do: id_for(%{id: socket.assigns.token_id})
end
