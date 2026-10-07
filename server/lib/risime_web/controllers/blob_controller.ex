defmodule RisiMeWeb.BlobController do
  @moduledoc """
  Blobs (contract v1.9 §12.6, v1.11 §14.2): raw `application/octet-stream` bodies.

  Uploads are streamed to a unique temp file and hashed while they arrive, never buffered; every
  refusal before the body is read is answered with `Connection: close` so the server doesn't
  drain up to 16 MiB. Downloads serve a single byte range with `send_file/5` and a strong ETag
  (the stored SHA-256).
  """
  use RisiMeWeb, :controller

  alias RisiMe.Blobs
  alias RisiMeWeb.{ApiError, GroupController}

  @chunk 64 * 1024
  @read_timeout 30_000
  @upload_deadline_ms 15 * 60 * 1000

  defp me(conn), do: conn.assigns.current_user.id

  ## Upload

  def create(conn, params) do
    me = me(conn)

    # v1.15 §17.9: a `history` upload names the provider device by `X-Device-Id` (never a
    # query parameter).
    params = Map.put(params, "_device_id", List.first(get_req_header(conn, "x-device-id")))

    case Blobs.begin_upload(me, params, content_type(conn), content_length(conn)) do
      {:ok, up} ->
        try do
          stream(conn, me, up)
        after
          Blobs.release_upload(me)
        end

      {:replay, reply} ->
        conn |> close() |> put_status(200) |> json(reply)

      error ->
        conn |> close() |> error(error)
    end
  end

  defp stream(conn, me, up) do
    {tmp, io} = Blobs.open_tmp()
    deadline = System.monotonic_time(:millisecond) + @upload_deadline_ms

    try do
      case read_into(conn, io, up.length, :crypto.hash_init(:sha256), 0, deadline) do
        {:ok, conn, size, sha} ->
          :ok = :file.sync(io)
          :ok = :file.close(io)

          case Blobs.finish(me, up, tmp, size, sha) do
            {:created, reply} -> conn |> put_status(201) |> json(reply)
            {:replay, reply} -> conn |> put_status(200) |> json(reply)
            error -> error(conn, error)
          end

        {:error, reason, conn} ->
          conn |> close() |> error({:error, reason})
      end
    after
      _ = :file.close(io)
      _ = File.rm(tmp)
    end
  end

  # Writes the body to `io` in 64 KiB reads while hashing it. Aborts with `:too_large` the
  # moment more than `declared` (≤ the purpose cap) bytes arrive; a short body, a read error or
  # the overall deadline is `:bad_request` (nothing is stored).
  defp read_into(conn, io, declared, hash, total, deadline) do
    case Plug.Conn.read_body(conn,
           length: @chunk,
           read_length: @chunk,
           read_timeout: @read_timeout
         ) do
      {status, chunk, conn} when status in [:ok, :more] ->
        total = total + byte_size(chunk)

        cond do
          total > declared ->
            {:error, :too_large, conn}

          System.monotonic_time(:millisecond) > deadline ->
            {:error, :bad_request, conn}

          true ->
            :ok = :file.write(io, chunk)
            hash = :crypto.hash_update(hash, chunk)

            cond do
              status == :more -> read_into(conn, io, declared, hash, total, deadline)
              total == declared -> {:ok, conn, total, :crypto.hash_final(hash)}
              true -> {:error, :bad_request, conn}
            end
        end

      {:error, _} ->
        {:error, :bad_request, conn}
    end
  end

  defp close(conn), do: put_resp_header(conn, "connection", "close")

  ## Download

  def show(conn, %{"id" => id}) do
    me = me(conn)

    case Blobs.begin_download(me) do
      :ok ->
        try do
          case Blobs.fetch(me, id) do
            {:ok, blob} -> serve(conn, blob)
            error -> error(conn, error)
          end
        after
          Blobs.release_download(me)
        end

      error ->
        error(conn, error)
    end
  end

  defp serve(conn, %{path: path, size: size, sha256: sha}) do
    etag = ~s("#{Base.encode16(sha, case: :lower)}")

    conn =
      conn
      |> put_resp_content_type("application/octet-stream", nil)
      |> put_resp_header("x-content-type-options", "nosniff")
      |> put_resp_header("content-disposition", "attachment")
      |> put_resp_header("accept-ranges", "bytes")
      |> put_resp_header("etag", etag)
      |> put_resp_header("cache-control", "private, max-age=86400, immutable")

    if none_match?(conn, etag) do
      send_resp(conn, 304, "")
    else
      case range(conn, etag, size) do
        :full ->
          send_blob(conn, 200, path, 0, size)

        {a, b} ->
          conn
          |> put_resp_header("content-range", "bytes #{a}-#{b}/#{size}")
          |> send_blob(206, path, a, b - a + 1)

        :unsatisfiable ->
          conn
          |> put_resp_header("content-range", "bytes */#{size}")
          |> send_resp(416, "")
      end
    end
  end

  # The sweep may delete the file between the read check and here: that's a 404, not a 500.
  defp send_blob(conn, status, path, offset, length) do
    send_file(conn, status, path, offset, length)
  rescue
    _ in [File.Error, ErlangError, MatchError] -> error(conn, {:error, :not_found})
  end

  defp none_match?(conn, etag) do
    case get_req_header(conn, "if-none-match") do
      [] ->
        false

      values ->
        values
        |> Enum.flat_map(&String.split(&1, ","))
        |> Enum.map(&String.trim/1)
        |> Enum.any?(&(&1 == "*" or String.replace_prefix(&1, "W/", "") == etag))
    end
  end

  @doc false
  # A single range (`a-b`, `a-` or the suffix `-n`) → `{first, last}`; several ranges or one that
  # can't be satisfied → `:unsatisfiable`; no `Range`, another unit, an unparsable value or an
  # `If-Range` that isn't our ETag → `:full`.
  def range(conn, etag, size) do
    if_range = get_req_header(conn, "if-range")

    case get_req_header(conn, "range") do
      ["bytes=" <> spec] when if_range in [[], [etag]] -> parse_range(String.trim(spec), size)
      _ -> :full
    end
  end

  defp parse_range(spec, size) do
    if String.contains?(spec, ",") do
      :unsatisfiable
    else
      case String.split(spec, "-", parts: 2) do
        ["", n] -> suffix(int(n), size)
        [a, ""] -> span(int(a), size - 1, size)
        [a, b] -> span(int(a), int(b), size)
        _ -> :full
      end
    end
  end

  defp suffix(nil, _size), do: :full
  defp suffix(0, _size), do: :unsatisfiable
  defp suffix(n, size), do: {max(size - n, 0), size - 1}

  defp span(a, b, _size) when a == nil or b == nil, do: :full
  defp span(a, b, size) when a > b or a >= size, do: :unsatisfiable
  defp span(a, b, size), do: {a, min(b, size - 1)}

  defp int(s) do
    case Integer.parse(s) do
      {n, ""} when n >= 0 -> n
      _ -> nil
    end
  end

  ## Usage and delete

  def usage(conn, _params), do: json(conn, Blobs.usage(me(conn)))

  def delete(conn, %{"id" => id}) do
    case Blobs.delete(me(conn), id) do
      :ok -> send_resp(conn, 204, "")
      error -> error(conn, error)
    end
  end

  ## Helpers

  defp content_type(conn) do
    case get_req_header(conn, "content-type") do
      [v | _] -> v
      [] -> nil
    end
  end

  defp content_length(conn) do
    with [v | _] <- get_req_header(conn, "content-length"),
         {n, ""} when n >= 0 <- Integer.parse(v) do
      n
    else
      _ -> nil
    end
  end

  defp error(conn, {:error, :bad_media_type}), do: ApiError.send_error(conn, 415, :bad_media_type)
  defp error(conn, {:error, :not_e2ee}), do: ApiError.send_error(conn, 409, :not_e2ee)
  defp error(conn, {:error, :storage_full}), do: ApiError.send_error(conn, 507, :storage_full)

  defp error(conn, {:error, {:rate_limited, s}}),
    do: ApiError.send_error(conn, 429, :rate_limited, retry_after: s)

  defp error(conn, error), do: GroupController.error(conn, error)
end
