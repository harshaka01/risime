defmodule RisiMe.Backups.Backup do
  @moduledoc "A committed backup (v1.22 §22.3, §22.8)."
  use Ecto.Schema

  @primary_key {:backup_id, :binary_id, autogenerate: false}
  schema "backups" do
    field :user_id, :binary_id
    field :device_id, :binary_id
    field :device_name, :string
    field :created_at, :utc_datetime_usec
    field :uploaded_at, :utc_datetime_usec
    field :size, :integer
    field :sha256, :string
    field :schema, :integer
    field :app_version, :string
    field :bk_id, :string
    field :parts, {:array, :map}
    field :current, :boolean, default: true
    field :expires_at, :utc_datetime_usec
  end
end

defmodule RisiMe.Backups.Key do
  @moduledoc "A user's wrapped backup-key record (v1.22 §22.3, §22.8)."
  use Ecto.Schema

  @primary_key {:user_id, :binary_id, autogenerate: false}
  schema "backup_keys" do
    field :v, :integer
    field :bk_id, :string
    field :record, :map
    field :updated_at, :utc_datetime_usec
  end
end

defmodule RisiMe.Backups do
  @moduledoc """
  Encrypted backups (contract v1.22 §22, decision 059). The server stores ciphertext parts
  (blobs of purpose `backup`, `RisiMe.Blobs`) and one wrapped-key record per user; it never sees
  a key, a recovery secret or content.

  - `POST /backups` commits a backup from its parts (checks in the §22.3 order), keeps the newest 2
    current, makes the one that drops out replaced for 7 days (at most 5 replaced), all in one
    transaction under the per-owner blob lock. One backup device per account.
  - `GET /backups`, `DELETE /backups`, `PUT`/`GET /backup_key` (no silent key change).
  - The switch `BACKUPS` (`:backups`, default on): off stops the writes only (`503
    backup_unavailable`); reads, downloads and `DELETE` keep working.

  Logs carry counts and sizes only.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.{Blobs, Messaging, MLS, RateLimiter, Repo}
  alias RisiMe.Backups.{Backup, Key}
  alias RisiMe.Devices.Device

  @mib 1024 * 1024
  @max_parts 16
  @max_size 512 * @mib
  @keep_current 2
  @keep_replaced 5
  @replaced_days 7
  @commits_per_day 6
  @receive_days 30

  @doc "The `BACKUPS` switch (`config :risime, :backups`, default true)."
  def enabled?, do: Application.get_env(:risime, :backups, true) != false

  @doc "`\"on\"` or `\"off\"` for `GET /auth/config`."
  def switch, do: if(enabled?(), do: "on", else: "off")

  @doc "The per-user limit on live backup bytes (current backups plus unreferenced parts)."
  def quota, do: Application.get_env(:risime, :backup_quota, 1536 * @mib)

  defp writable, do: if(enabled?(), do: :ok, else: {:error, :backup_unavailable})

  @doc "`X-Device-Id` of a write: a registered device of the caller (`403 invalid_device`)."
  def caller_device(me, device_id) do
    with {:ok, d} <- Ecto.UUID.cast(device_id || ""),
         %Device{} <- Repo.get_by(Device, user_id: me, device_id: d) do
      {:ok, d}
    else
      _ -> {:error, :invalid_device}
    end
  end

  @doc """
  The blob-upload rights for purpose `backup` (§22.3): the switch, then `X-Device-Id`.
  """
  def upload_allowed(me, device_id) do
    with :ok <- writable(),
         {:ok, _} <- caller_device(me, device_id),
         do: :ok
  end

  ## POST /backups

  @doc """
  `POST /backups`. Checks, in order: the switch, `X-Device-Id`, the body, the key record
  (`no_backup_key`, `backup_key_conflict`), the backup device (`backup_device_mismatch`), the
  parts (`bad_request`), the caps (`too_large`), the rate (6 per 24 h). A repeat of a committed
  `backup_id` replays it (`{:ok, :replay, json}`). Returns `{:ok, :created | :replay, json}`.
  """
  def commit(me, device_id, params) do
    with :ok <- writable(),
         {:ok, dev} <- caller_device(me, device_id),
         {:ok, req} <- parse(params) do
      case Repo.get(Backup, req.backup_id) do
        %Backup{user_id: ^me} = b ->
          {:ok, :replay, json(b)}

        %Backup{} ->
          {:error, :bad_request}

        nil ->
          {:ok, result} =
            Repo.transaction(fn ->
              Repo.query!("SELECT pg_advisory_xact_lock(hashtext($1))", ["blobs:" <> me])
              locked_commit(me, dev, req)
            end)

          result
      end
    end
  end

  # Under the per-owner lock: a concurrent commit of the same `backup_id` replays.
  defp locked_commit(me, dev, req) do
    case Repo.get(Backup, req.backup_id) do
      %Backup{user_id: ^me} = b ->
        {:ok, :replay, json(b)}

      %Backup{} ->
        {:error, :bad_request}

      nil ->
        with :ok <- check_key(me, req.bk_id),
             :ok <- check_device(me, dev, req.replace_device),
             {:ok, parts} <- check_parts(me, req),
             :ok <- check_caps(req),
             :ok <- check_rate(me),
             do: insert(me, dev, req, parts)
    end
  end

  defp parse(%{"backup_id" => id, "parts" => parts} = p) when is_list(parts) and parts != [] do
    with {:ok, id} <- uuid(id),
         {:ok, created_at} <- ts(p["created_at"]),
         size when is_integer(size) and size > 0 <- p["size"],
         sha when is_binary(sha) <- p["sha256"],
         true <- b64_len?(sha, 32),
         schema when is_integer(schema) and schema >= 1 <- p["schema"],
         ver when is_binary(ver) and byte_size(ver) in 1..64 <- p["app_version"],
         bk when is_binary(bk) <- p["bk_id"],
         true <- b64_len?(bk, 8),
         replace when is_boolean(replace) <- Map.get(p, "replace_device", false),
         {:ok, parts} <- parse_parts(parts) do
      {:ok,
       %{
         backup_id: id,
         created_at: created_at,
         size: size,
         sha256: sha,
         schema: schema,
         app_version: ver,
         bk_id: bk,
         parts: parts,
         replace_device: replace
       }}
    else
      _ -> {:error, :bad_request}
    end
  end

  defp parse(_), do: {:error, :bad_request}

  defp parse_parts(parts) do
    parsed =
      for %{"blob_id" => id, "size" => size, "sha256" => sha} <- parts,
          {:ok, id} <- [uuid(id)],
          is_integer(size) and size > 0 and is_binary(sha),
          do: %{"blob_id" => id, "size" => size, "sha256" => sha}

    ids = Enum.map(parsed, & &1["blob_id"])

    if length(parsed) == length(parts) and ids == Enum.uniq(ids),
      do: {:ok, parsed},
      else: {:error, :bad_request}
  end

  defp uuid(id) when is_binary(id) do
    case Ecto.UUID.cast(id) do
      {:ok, id} -> {:ok, id}
      :error -> :error
    end
  end

  defp uuid(_), do: :error

  defp ts(s) when is_binary(s) do
    case DateTime.from_iso8601(s) do
      {:ok, t, _} -> {:ok, %{t | microsecond: {elem(t.microsecond, 0), 6}}}
      _ -> :error
    end
  end

  defp ts(_), do: :error

  defp b64_len?(s, n), do: match?({:ok, <<_::binary-size(n)>>}, Base.decode64(s))

  defp check_key(me, bk_id) do
    case Repo.get(Key, me) do
      nil -> {:error, :no_backup_key}
      %Key{bk_id: ^bk_id} -> :ok
      %Key{bk_id: other} -> {:error, {:backup_key_conflict, other}}
    end
  end

  # §22.3 one backup device: the device of the newest current backup, unless it can no longer
  # receive (§12.1) or the caller confirmed `replace_device`.
  defp check_device(me, dev, replace?) do
    case newest_current(me) do
      %Backup{device_id: ^dev} ->
        :ok

      %Backup{device_id: other} = b ->
        if replace? or not can_receive?(me, other),
          do: :ok,
          else: {:error, {:backup_device_mismatch, other, device_name(other) || b.device_name}}

      nil ->
        :ok
    end
  end

  defp newest_current(me),
    do:
      Repo.one(
        from b in Backup,
          where: b.user_id == ^me and b.current,
          order_by: [desc: b.uploaded_at],
          limit: 1
      )

  @doc "§12.1: the device is registered, not superseded, and seen (census or `PUT`) in 30 days."
  def can_receive?(me, device_id) do
    since = DateTime.add(DateTime.utc_now(), -@receive_days, :day)

    case Repo.get_by(Device, user_id: me, device_id: device_id) do
      nil ->
        false

      %Device{last_seen_at: seen} ->
        census =
          Repo.one(
            from i in "app_instances",
              where: i.instance_key == ^("device:" <> device_id),
              select: type(i.last_seen_at, :utc_datetime_usec)
          )

        recent? = Enum.any?([seen, census], &(&1 != nil and DateTime.compare(&1, since) == :gt))
        recent? and not MapSet.member?(MLS.superseded_devices([me]), {me, device_id})
    end
  end

  defp device_name(device_id) do
    Repo.one(
      from d in Device,
        join: t in RisiMe.Accounts.UserToken,
        on: t.id == d.user_token_id,
        where: d.device_id == ^device_id,
        select: t.device_name,
        limit: 1
    )
  end

  # Each part is the caller's own live `backup` blob of this `backup_id`, with the server's size
  # and hash; the sizes add up to `size`.
  defp check_parts(me, req) do
    ids = Enum.map(req.parts, & &1["blob_id"])
    rows = Map.new(Blobs.backup_parts(me, req.backup_id, ids), &{&1.id, &1})

    ok? =
      Enum.all?(req.parts, fn p ->
        case rows[p["blob_id"]] do
          %{size: size, sha256: sha} -> size == p["size"] and Base.encode64(sha) == p["sha256"]
          nil -> false
        end
      end)

    if ok? and Enum.sum(Enum.map(req.parts, & &1["size"])) == req.size,
      do: {:ok, req.parts},
      else: {:error, :bad_request}
  end

  defp check_caps(req) do
    if length(req.parts) <= @max_parts and req.size <= @max_size,
      do: :ok,
      else: {:error, :too_large}
  end

  # §22.3: 6 committed backups per user per 24 h, counted in Postgres. `Retry-After` is when the
  # oldest counted one leaves the window.
  defp check_rate(me) do
    since = DateTime.add(DateTime.utc_now(), -86_400, :second)

    times =
      Repo.all(
        from b in Backup,
          where: b.user_id == ^me and b.uploaded_at > ^since,
          order_by: [asc: b.uploaded_at],
          select: b.uploaded_at
      )

    if length(times) < @commits_per_day do
      :ok
    else
      oldest = Enum.at(times, length(times) - @commits_per_day)
      free = DateTime.add(oldest, 86_400, :second)
      {:error, {:rate_limited, max(1, DateTime.diff(free, DateTime.utc_now()) + 1)}}
    end
  end

  defp insert(me, dev, req, parts) do
    now = DateTime.utc_now()

    b =
      Repo.insert!(%Backup{
        backup_id: req.backup_id,
        user_id: me,
        device_id: dev,
        device_name: device_name(dev),
        created_at: req.created_at,
        uploaded_at: now,
        size: req.size,
        sha256: req.sha256,
        schema: req.schema,
        app_version: req.app_version,
        bk_id: req.bk_id,
        parts: parts,
        current: true,
        expires_at: nil
      })

    # A current backup's parts don't expire.
    Blobs.set_expiry(Enum.map(parts, & &1["blob_id"]), nil)
    retention(me, now)
    Logger.info("backup committed: parts=#{length(parts)} size=#{req.size}")
    {:ok, :created, json(b)}
  end

  # §22.3 retention: the newest 2 committed backups stay current; older current ones become
  # replaced for 7 days; at most 5 replaced are kept (the oldest go at once).
  defp retention(me, now) do
    current =
      Repo.all(
        from b in Backup,
          where: b.user_id == ^me and b.current,
          order_by: [desc: b.uploaded_at, desc: b.backup_id]
      )

    until = DateTime.add(now, @replaced_days, :day)

    for b <- Enum.drop(current, @keep_current) do
      b |> Ecto.Changeset.change(current: false, expires_at: until) |> Repo.update!()
      Blobs.set_expiry(part_ids(b), until)
    end

    replaced =
      Repo.all(
        from b in Backup,
          where: b.user_id == ^me and not b.current,
          order_by: [desc: b.uploaded_at, desc: b.backup_id]
      )

    for b <- Enum.drop(replaced, @keep_replaced), do: drop(b)
    :ok
  end

  defp part_ids(%Backup{parts: parts}), do: Enum.map(parts || [], & &1["blob_id"])

  defp drop(%Backup{} = b) do
    Repo.delete!(b)
    Enum.each(part_ids(b), &Blobs.remove/1)
  end

  ## GET /backups

  @doc "`GET /backups`: current and replaced (unexpired) backups, newest first; key; quota."
  def list(me) do
    now = DateTime.utc_now()

    backups =
      Repo.all(
        from b in Backup,
          where: b.user_id == ^me and (is_nil(b.expires_at) or b.expires_at > ^now),
          order_by: [desc: b.uploaded_at, desc: b.backup_id]
      )

    %{
      backups: Enum.map(backups, &json/1),
      key: Repo.get(Key, me) != nil,
      quota: %{used: Blobs.backup_used(me), limit: quota()}
    }
  end

  @doc "The `Backup` object."
  def json(%Backup{} = b) do
    %{
      backup_id: b.backup_id,
      device_id: b.device_id,
      device_name: b.device_name,
      created_at: Messaging.iso(b.created_at),
      uploaded_at: Messaging.iso(b.uploaded_at),
      size: b.size,
      sha256: b.sha256,
      schema: b.schema,
      app_version: b.app_version,
      bk_id: b.bk_id,
      parts: Enum.map(b.parts, &Map.take(&1, ~w(blob_id size sha256))),
      current: b.current,
      expires_at: b.expires_at && Messaging.iso(b.expires_at)
    }
  end

  ## DELETE /backups

  @doc """
  `DELETE /backups`: every backup of the user (current, replaced, unreferenced parts; files
  removed at once) and the key record. Idempotent; works with the switch off.
  """
  def delete_all(me) do
    {:ok, n} =
      Repo.transaction(fn ->
        Repo.query!("SELECT pg_advisory_xact_lock(hashtext($1))", ["blobs:" <> me])
        {n, _} = Repo.delete_all(from b in Backup, where: b.user_id == ^me)
        Repo.delete_all(from k in Key, where: k.user_id == ^me)
        Enum.each(Blobs.backup_blob_ids(me), &Blobs.remove/1)
        n
      end)

    Logger.info("backups deleted: backups=#{n}")
    :ok
  end

  ## Key record

  @doc """
  `PUT /backup_key`: the switch, `X-Device-Id`, the shape (`bad_request`), no silent key change
  (`backup_key_conflict` while the user has any backup), then 10 per user per day.
  """
  def put_key(me, device_id, params) do
    with :ok <- writable(),
         {:ok, _} <- caller_device(me, device_id),
         {:ok, rec} <- parse_key(params) do
      {:ok, result} =
        Repo.transaction(fn ->
          Repo.query!("SELECT pg_advisory_xact_lock(hashtext($1))", ["blobs:" <> me])

          with :ok <- key_conflict(me, rec["bk_id"]),
               :ok <- limit(:backup_key_put, me, 10, :timer.hours(24)) do
            now = DateTime.utc_now()

            key =
              Repo.insert!(
                %Key{user_id: me, v: rec["v"], bk_id: rec["bk_id"], record: rec, updated_at: now},
                on_conflict: {:replace, [:v, :bk_id, :record, :updated_at]},
                conflict_target: :user_id
              )

            {:ok, key_json(key)}
          end
        end)

      result
    end
  end

  defp key_conflict(me, bk_id) do
    case Repo.get(Key, me) do
      %Key{bk_id: other} when other != bk_id ->
        if has_backups?(me), do: {:error, {:backup_key_conflict, other}}, else: :ok

      _ ->
        :ok
    end
  end

  defp has_backups?(me) do
    now = DateTime.utc_now()

    Repo.exists?(
      from b in Backup,
        where: b.user_id == ^me and (is_nil(b.expires_at) or b.expires_at > ^now)
    )
  end

  @doc "`GET /backup_key`: `{:ok, json}`, `{:error, :no_backup_key}`; 30 per user per hour."
  def get_key(me) do
    with :ok <- limit(:backup_key_get, me, 30, :timer.hours(1)) do
      case Repo.get(Key, me) do
        nil -> {:error, :no_backup_key}
        key -> {:ok, key_json(key)}
      end
    end
  end

  defp key_json(%Key{} = k),
    do: Map.put(k.record, "updated_at", Messaging.iso(k.updated_at))

  defp limit(bucket, me, n, window) do
    case RateLimiter.hit(bucket, me, n, window) do
      :ok -> :ok
      _ -> {:error, {:rate_limited, RateLimiter.retry_after_s(window)}}
    end
  end

  @doc "True if `record` (a `BackupKey`, `updated_at` ignored) passes the §22.3 shape checks."
  def valid_key?(record) when is_map(record),
    do: match?({:ok, _}, parse_key(Map.delete(record, "updated_at")))

  def valid_key?(_), do: false

  # §22.3 BackupKey: v 1; bk_id 8 bytes; 1–2 wraps, at most one per kind, exactly one
  # `recovery_key`; each with the argon2id KDF (positive m/t/p, 16-byte salt), a 12-byte nonce,
  # a 48-byte wrapped key and a 16-byte check. The server can't check the crypto.
  defp parse_key(%{"v" => 1, "bk_id" => bk, "wraps" => wraps})
       when is_binary(bk) and is_list(wraps) and length(wraps) in 1..2 do
    parsed = for w <- wraps, {:ok, w} <- [parse_wrap(w)], do: w
    kinds = Enum.map(parsed, & &1["kind"])

    if b64_len?(bk, 8) and length(parsed) == length(wraps) and kinds == Enum.uniq(kinds) and
         Enum.count(kinds, &(&1 == "recovery_key")) == 1,
       do: {:ok, %{"v" => 1, "bk_id" => bk, "wraps" => parsed}},
       else: {:error, :bad_request}
  end

  defp parse_key(_), do: {:error, :bad_request}

  defp parse_wrap(%{
         "kind" => kind,
         "kdf" => %{"alg" => "argon2id", "m" => m, "t" => t, "p" => p, "salt" => salt},
         "nonce" => nonce,
         "wrapped" => wrapped,
         "check" => check
       })
       when kind in ["recovery_key", "passphrase"] and is_integer(m) and m > 0 and
              is_integer(t) and t > 0 and is_integer(p) and p > 0 and is_binary(salt) and
              is_binary(nonce) and is_binary(wrapped) and is_binary(check) do
    if b64_len?(salt, 16) and b64_len?(nonce, 12) and b64_len?(wrapped, 48) and
         b64_len?(check, 16),
       do:
         {:ok,
          %{
            "kind" => kind,
            "kdf" => %{"alg" => "argon2id", "m" => m, "t" => t, "p" => p, "salt" => salt},
            "nonce" => nonce,
            "wrapped" => wrapped,
            "check" => check
          }},
       else: :error
  end

  defp parse_wrap(_), do: :error

  ## Sweep

  @doc "Deletes backup rows whose `expires_at` passed (replaced ones; their blobs expire too)."
  def sweep(now \\ DateTime.utc_now()) do
    {n, _} = Repo.delete_all(from b in Backup, where: b.expires_at <= ^now)
    if n > 0, do: Logger.info("BackupCleanup: deleted #{n} expired backup(s)")
    n
  end
end
