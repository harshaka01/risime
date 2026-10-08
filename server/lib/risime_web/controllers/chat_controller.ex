defmodule RisiMeWeb.ChatController do
  @moduledoc """
  Chats with two tabs (contract v1.24 §24.2, §24.4, §24.8). Every call needs `X-Device-Id`
  naming a `tabs` device of the caller: `403 invalid_device` for the mutating calls, `404` for
  the reads. Errors reuse `RisiMeWeb.GroupController.error/2`.
  """
  use RisiMeWeb, :controller

  alias RisiMe.Chats
  alias RisiMeWeb.{GroupController, MLSController}

  defp me(conn), do: conn.assigns.current_user.id
  defp device(conn), do: MLSController.caller_device(conn)

  def index(conn, _params) do
    case Chats.list(me(conn), device(conn)) do
      {:ok, chats} -> json(conn, %{chats: chats})
      error -> GroupController.error(conn, error)
    end
  end

  def show(conn, %{"chat_id" => chat_id}) do
    case Chats.show(me(conn), device(conn), chat_id) do
      {:ok, chat} -> json(conn, %{chat: chat})
      error -> GroupController.error(conn, error)
    end
  end

  def create_official(conn, %{"chat_id" => chat_id}) do
    case Chats.create_official(me(conn), device(conn), chat_id) do
      {:ok, :created, group} -> conn |> put_status(201) |> json(%{group: group})
      {:ok, :existing, group} -> json(conn, %{group: group})
      error -> GroupController.error(conn, error)
    end
  end
end
