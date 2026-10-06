defmodule RisiMe.Blobs do
  @moduledoc """
  Generic store of opaque, client-encrypted bytes (contract v1.9 §12.6, decision 041).

  Metadata lives in Postgres (`blobs`, `blob_readers`); the bytes live on local disk under
  `blob_dir/0` (`BLOB_DIR`, default `~/risime-blobs/<env>`), always outside the repo, one file
  per blob (`<dir>/<first 2 hex chars>/<blob_id>`, mode 600). Blobs are immutable and expire 30
  days after upload; `RisiMe.Workers.BlobCleanup` deletes expired rows and files hourly.

  v1.9 uses `purpose=mls` for commits and Welcomes over 64 KiB; the encrypted-images slice
  extends this API (new purposes and caps) rather than adding a second store.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.{Groups, Messaging, RateLimiter, Repo}

  @max_bytes 2 * 1024 * 1024
  @ttl_days 30
  @uploads_per_hour 60
  @purposes ~w(mls)

  def max_bytes, do: @max_bytes

  @doc "The blob directory (created on first write)."
  def blob_dir do
    Application.get_env(:risime, :blob_dir) ||
      Path.expand("~/risime-blobs/#{Application.get_env(:risime, :env, :dev)}")
  end

  defp path(id), do: Path.join([blob_dir(), String.slice(id, 0, 2), id])

  @doc """
  `POST /blobs?purpose=mls&conversation_id=grp:…`: stores `bytes` for an active member of the
  conversation. Returns `{:ok, reply}` or `{:error, :not_found | :too_large | :rate_limited |
  :bad_request}`.
  """
  def upload(me, %{"purpose" => purpose, "conversation_id" => conv}, bytes)
      when purpose in @purposes and is_binary(conv) and is_binary(bytes) do
    with {:ok, _g, _m} <- Groups.visible(me, conv),
         true <- byte_size(bytes) in 1..@max_bytes || {:error, :too_large},
         :ok <- upload_limit(me) do
      id = Ecto.UUID.generate()
      sha = :crypto.hash(:sha256, bytes)
      now = DateTime.utc_now()
      expires_at = DateTime.add(now, @ttl_days, :day)
      :ok = write!(id, bytes)

      Repo.insert_all("blobs", [
        %{
          id: Ecto.UUID.dump!(id),
          owner: Ecto.UUID.dump!(me),
          purpose: purpose,
          conversation_id: conv,
          size: byte_size(bytes),
          sha256: sha,
          expires_at: expires_at,
          inserted_at: now
        }
      ])

      {:ok,
       %{
         blob_id: id,
         size: byte_size(bytes),
         sha256: Base.encode64(sha),
         expires_at: Messaging.iso(expires_at)
       }}
    end
  end

  def upload(_me, _params, _bytes), do: {:error, :bad_request}

  defp upload_limit(me) do
    case RateLimiter.hit(:blob_upload, me, @uploads_per_hour, :timer.hours(1)) do
      :ok -> :ok
      _ -> {:error, :rate_limited}
    end
  end

  defp write!(id, bytes) do
    final = path(id)
    File.mkdir_p!(Path.dirname(final))
    tmp = final <> ".tmp"
    File.write!(tmp, bytes)
    File.chmod!(tmp, 0o600)
    File.rename!(tmp, final)
    :ok
  end

  defp row(id) do
    with {:ok, id} <- Ecto.UUID.cast(id) do
      Repo.one(
        from b in "blobs",
          where: b.id == type(^id, :binary_id),
          select: %{
            id: type(b.id, :binary_id),
            owner: type(b.owner, :binary_id),
            purpose: b.purpose,
            conversation_id: b.conversation_id,
            size: b.size,
            sha256: b.sha256,
            expires_at: type(b.expires_at, :utc_datetime_usec)
          }
      )
    else
      _ -> nil
    end
  end

  @doc """
  `GET /blobs/{id}`: `{:ok, file_path}` for the owner, the conversation's active and
  `pending_add` members, and the users of devices named in a Welcome that references it.
  Everything else, expiry included, is `{:error, :not_found}`.
  """
  def fetch(me, id) do
    with %{} = b <- row(id),
         true <- DateTime.compare(b.expires_at, DateTime.utc_now()) == :gt,
         true <- readable?(me, b),
         true <- File.regular?(path(b.id)) do
      {:ok, path(b.id)}
    else
      _ -> {:error, :not_found}
    end
  end

  defp readable?(me, b) do
    b.owner == me or
      match?(
        %{state: s} when s in ["active", "pending_add"],
        Groups.member(b.conversation_id, me)
      ) or
      Repo.exists?(
        from r in "blob_readers",
          where: r.blob_id == type(^b.id, :binary_id) and r.user_id == type(^me, :binary_id)
      )
  end

  @doc "`DELETE /blobs/{id}` (owner only; idempotent): `:ok` or `{:error, :not_found}`."
  def delete(me, id) do
    case row(id) do
      nil ->
        if match?({:ok, _}, Ecto.UUID.cast(id)), do: :ok, else: {:error, :not_found}

      %{owner: ^me} = b ->
        remove(b.id)

      _ ->
        {:error, :not_found}
    end
  end

  defp remove(id) do
    Repo.delete_all(from b in "blobs", where: b.id == type(^id, :binary_id))
    _ = File.rm(path(id))
    :ok
  end

  @doc """
  True if `ref` (`%{"blob_id", "size", "sha256"}`) names an unexpired blob that `me` uploaded
  for `conv`, with that size and hash, at most `max` bytes (§12.6, §12.4 totals).
  """
  def ref_ok?(me, conv, %{"blob_id" => id, "size" => size, "sha256" => sha}, max) do
    case row(id) do
      %{owner: ^me, conversation_id: ^conv} = b ->
        b.size == size and size <= max and Base.encode64(b.sha256) == sha and
          DateTime.compare(b.expires_at, DateTime.utc_now()) == :gt

      _ ->
        false
    end
  end

  @doc "Lets the given users read the blob (a Welcome referencing it was routed to them)."
  def grant(blob_id, user_ids) do
    rows =
      for u <- Enum.uniq(user_ids),
          do: %{blob_id: Ecto.UUID.dump!(blob_id), user_id: Ecto.UUID.dump!(u)}

    Repo.insert_all("blob_readers", rows, on_conflict: :nothing)
    :ok
  end

  @doc "Deletes expired blobs (rows and files). Returns the count."
  def cleanup(now \\ DateTime.utc_now()) do
    {n, ids} =
      Repo.delete_all(
        from(b in "blobs", where: b.expires_at <= ^now, select: type(b.id, :binary_id))
      )

    for id <- ids, do: File.rm(path(id))
    if n > 0, do: Logger.info("BlobCleanup: deleted #{n} expired blob(s)")
    n
  end
end
