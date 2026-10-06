defmodule RisiMeWeb.BlobController do
  @moduledoc "Blobs (contract v1.9 §12.6): raw `application/octet-stream` bodies, at most 2 MiB."
  use RisiMeWeb, :controller

  alias RisiMe.Blobs
  alias RisiMeWeb.GroupController

  defp me(conn), do: conn.assigns.current_user.id

  def create(conn, params) do
    with {:ok, bytes, conn} <- read_all(conn, Blobs.max_bytes()),
         {:ok, reply} <- Blobs.upload(me(conn), params, bytes) do
      conn |> put_status(201) |> json(reply)
    else
      {:error, :too_large, conn} -> GroupController.error(conn, {:error, :too_large})
      error -> GroupController.error(conn, error)
    end
  end

  def show(conn, %{"id" => id}) do
    case Blobs.fetch(me(conn), id) do
      {:ok, path} ->
        conn
        |> put_resp_content_type("application/octet-stream", nil)
        |> send_file(200, path)

      error ->
        GroupController.error(conn, error)
    end
  end

  def delete(conn, %{"id" => id}) do
    case Blobs.delete(me(conn), id) do
      :ok -> send_resp(conn, 204, "")
      error -> GroupController.error(conn, error)
    end
  end

  # Reads the raw body (Plug.Parsers passes octet-stream through unread), refusing more than
  # `max` bytes without buffering them.
  defp read_all(conn, max, acc \\ []) do
    case Plug.Conn.read_body(conn, length: 256 * 1024, read_length: 256 * 1024) do
      {:ok, chunk, conn} -> done([acc, chunk], max, conn)
      {:more, chunk, conn} -> more([acc, chunk], max, conn)
      {:error, _} -> {:error, :bad_request}
    end
  end

  defp done(io, max, conn) do
    if IO.iodata_length(io) > max,
      do: {:error, :too_large, conn},
      else: {:ok, IO.iodata_to_binary(io), conn}
  end

  defp more(io, max, conn) do
    if IO.iodata_length(io) > max, do: {:error, :too_large, conn}, else: read_all(conn, max, io)
  end
end
