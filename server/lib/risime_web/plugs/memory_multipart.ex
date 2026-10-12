defmodule RisiMeWeb.Plugs.MemoryMultipart do
  @moduledoc """
  A `Plug.Parsers` parser for `POST /api/v1/calls/{call_id}/transcribe/chunks` only (contract
  v1.39 §36.4): the multipart body is read **into memory**, never into a `Plug.Upload` temp
  file, because a call chunk must never touch the disk. Every other request goes on to the
  standard parsers (`{:next, conn}`).

  * `audio` is kept as a binary of at most 256 KiB; a bigger part is read and dropped and the
    param becomes `:too_large` (the controller answers `413 too_large`).
  * Any other part is a small text field (at most 1 KiB).
  * The whole body is capped at 1 MiB (`Plug.Parsers.RequestTooLargeError` beyond).
  """
  @behaviour Plug.Parsers

  @max_audio 262_144
  @max_field 1_024
  @max_total 1_048_576

  @impl true
  def init(opts), do: opts

  @impl true
  def parse(conn, "multipart", "form-data", _headers, _opts) do
    if chunk_path?(conn.path_info), do: read_parts(conn, %{}, 0), else: {:next, conn}
  end

  def parse(conn, _type, _subtype, _headers, _opts), do: {:next, conn}

  @doc "True for the chunk upload path."
  def chunk_path?(["api", "v1", "calls", _id, "transcribe", "chunks"]), do: true
  def chunk_path?(_), do: false

  defp read_parts(conn, acc, total) do
    case Plug.Conn.read_part_headers(conn) do
      {:ok, headers, conn} ->
        name = part_name(headers)
        limit = if name == "audio", do: @max_audio, else: @max_field

        case read_body(conn, [], 0, limit, total) do
          {:ok, body, size, conn} ->
            acc = if name, do: Map.put(acc, name, body), else: acc
            read_parts(conn, acc, total + size)

          {:over, size, conn} when name == "audio" ->
            read_parts(conn, Map.put(acc, "audio", :too_large), total + size)

          {:over, _size, conn} ->
            {:error, :too_large, conn}

          {:too_large, conn} ->
            {:error, :too_large, conn}
        end

      {:done, conn} ->
        {:ok, acc, conn}

      {:error, :too_large, conn} ->
        {:error, :too_large, conn}
    end
  end

  # Reads one part's body: `{:ok, binary, size, conn}` within `limit`, else `{:over, size, conn}`
  # (the rest read and dropped), or `{:too_large, conn}` past the whole-body cap.
  defp read_body(conn, chunks, size, limit, total) do
    {status, data, conn} =
      case Plug.Conn.read_part_body(conn, length: 64_000, read_length: 64_000) do
        {:ok, data, conn} -> {:ok, data, conn}
        {:more, data, conn} -> {:more, data, conn}
        {:done, conn} -> {:ok, "", conn}
      end

    size = size + byte_size(data)
    chunks = if size <= limit, do: [chunks | data], else: []

    cond do
      total + size > @max_total -> {:too_large, conn}
      status == :more -> read_body(conn, chunks, size, limit, total)
      size > limit -> {:over, size, conn}
      true -> {:ok, IO.iodata_to_binary(chunks), size, conn}
    end
  end

  defp part_name(headers) do
    with {_, disposition} <- List.keyfind(headers, "content-disposition", 0),
         [_, params] <- :binary.split(disposition, ";"),
         %{"name" => name} <- Plug.Conn.Utils.params(params) do
      name
    else
      _ -> nil
    end
  end
end
