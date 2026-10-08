defmodule RisiMe.Blobs do
  @moduledoc """
  Store of opaque, client-encrypted bytes (contract v1.9 §12.6, v1.10 §13.4, v1.11 §14;
  decisions 041 and 042). The server never sees plaintext.

  Metadata lives in Postgres (`blobs`, `blob_readers`); the bytes live on local disk under
  `blob_dir/0` (`BLOB_DIR`, default `~/risime-blobs/<env>`), always outside the repo, one file
  per blob (`<dir>/<first 2 hex chars>/<blob_id>`, mode 600), written through `<dir>/.tmp/` and
  renamed once the SHA-256 is known. Blobs are immutable.

  Purposes (§14.5):

  | purpose | conversations | max | TTL | rate | quota |
  |---|---|---|---|---|---|
  | `mls` | `grp:` | 2 MiB | 30 d | 60/h | 256 MiB |
  | `media` | `dm:` (e2ee), `grp:` | 16 MiB | 30 d | 120/h, 1000/day | 2 GiB |
  | `icon` | `grp:` | 512 KiB | none while current, 7 d after replaced | 3/h | none |
  | `history` | `dm:` (e2ee), `grp:` | 16 MiB | 48 h (earlier after the ack) | 40/h | 512 MiB |
  | `avatar` | none | 512 KiB | none while current, 24 h after replaced | 3/h, 10/day | none |
  | `backup` | none | 33 562 624 B | 24 h unreferenced, none current, 7 d replaced | 60/h, 200/day | 1.5 GiB |

  A `backup` part (v1.22 §22.3) has no conversation and carries its `backup_id`; only the owner
  reads it. Its quota counts current backups plus unreferenced parts (`backup_used/1`), not
  replaced backups. `RisiMe.Backups` sets the expiry when a backup commits or is replaced.

  An `avatar` (v1.17 §18.3) has no conversation (`conversation_id` null): one current avatar
  per user (the upload whose row commits last); readers are the owner, friends with no block
  either way, and users sharing a group with the owner in which both are `active` or
  `pending_add`.

  A `history` blob (v1.15 §17.9) carries its `request_id`: only the request's accepted provider
  device uploads (at most 20 per request), and the requester's user reads it while the request
  is open or `done`.

  Lifecycle: `DELETE` (and `remove/1`, the internal delete by reference) sets `deleted_at` and
  removes the file at once; the row stays until `expires_at`, so a replayed `client_blob_id`
  gets `404`. Deleting or resetting a group sets `expires_at = now` on its blobs. One batched
  sweep (`cleanup/1`) deletes rows with `expires_at <= now` and then their files, and stale temp
  files; a weekly pass (`orphans/1`) removes files without a row.

  Upload concurrency and download concurrency are counted in `RisiMe.Blobs.Slots` (a Registry
  bound to the request process). The free-space guard is `RisiMe.Blobs.DiskGuard`.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.{Groups, Messaging, MLS, RateLimiter, Repo, Social}
  alias RisiMe.Blobs.{DiskGuard, Slots}
  alias RisiMe.Groups.{Member, Membership}

  @mib 1024 * 1024
  @limits %{
    "mls" => %{max: 2 * @mib, ttl_days: 30, hourly: 60, daily: nil},
    "media" => %{max: 16 * @mib, ttl_days: 30, hourly: 120, daily: 1000},
    "icon" => %{max: 512 * 1024, ttl_days: nil, hourly: 3, daily: nil},
    "history" => %{max: 16 * @mib, ttl_days: 2, hourly: 40, daily: nil},
    "avatar" => %{max: 512 * 1024, ttl_days: nil, hourly: 3, daily: 10},
    # v1.22 §22.3: parts of a backup file; 24 h until a committed backup references them.
    "backup" => %{max: 33_562_624, ttl_days: nil, hourly: 60, daily: 200}
  }
  @purposes Map.keys(@limits)
  @icon_grace_days 7
  @avatar_grace_hours 24
  @backup_part_ttl_hours 24
  @deleted_keep_days 7
  @upload_slots 3
  @download_slots 8
  @downloads_per_minute 600
  @tmp_max_age_s 3600
  @sweep_batch 1000

  @doc "The per-purpose limits (`max`, `ttl_days`, `hourly`, `daily`)."
  def limits(purpose) when purpose in @purposes, do: Map.fetch!(@limits, purpose)

  @doc "The largest blob of `purpose` (ciphertext bytes)."
  def max_bytes(purpose \\ "mls"), do: limits(purpose).max

  @doc "The blob directory (created on first write)."
  def blob_dir do
    Application.get_env(:risime, :blob_dir) ||
      Path.expand("~/risime-blobs/#{Application.get_env(:risime, :env, :dev)}")
  end

  defp path(id), do: Path.join([blob_dir(), String.slice(id, 0, 2), id])
  defp tmp_dir, do: Path.join(blob_dir(), ".tmp")

  @doc "The per-user limit on live `mls` blob bytes (v1.10 §13.4, `:mls_blob_quota`)."
  def mls_quota, do: Application.get_env(:risime, :mls_blob_quota, 256 * @mib)

  @doc "The per-user limit on live `media` blob bytes (v1.11 §14.5, `:media_blob_quota`)."
  def media_quota, do: Application.get_env(:risime, :media_blob_quota, 2048 * @mib)

  @doc "The per-user limit on live `history` blob bytes (v1.15 §17.9, `:history_blob_quota`)."
  def history_quota, do: Application.get_env(:risime, :history_blob_quota, 512 * @mib)

  defp quota("history"), do: history_quota()
  defp quota("mls"), do: mls_quota()
  defp quota("media"), do: media_quota()
  defp quota("icon"), do: nil
  defp quota("avatar"), do: nil
  defp quota("backup"), do: RisiMe.Backups.quota()

  ## Upload (§14.2)

  @doc """
  Every check before the body of `POST /blobs` is read, in the contract's order: purpose and
  conversation (`:bad_request`) → rights (`:not_found`, `:not_admin`, `:not_e2ee`) →
  `Content-Type` (`:bad_media_type`) → `Content-Length` (`:bad_request` when missing) → the
  purpose cap (`:too_large`) → idempotency (a replay, a mismatch `:bad_request`, a deleted blob
  `:not_found`) → the quota (`{:quota_exceeded, used, limit}`) → the free-space guard
  (`:storage_full`) → the rate (`{:rate_limited, retry_after_s}`) → a concurrency slot
  (`{:rate_limited, 2}`). Idempotency comes before the quota, guard, rate and slot, so a retry
  whose `201` was lost always gets its `200` replay, even at the quota (root decision, §14.2).

  Returns `{:ok, upload}` (the caller now holds an upload slot and must call
  `release_upload/1`), `{:replay, reply}` for a completed earlier upload with the same
  `client_blob_id`, or `{:error, reason}`.
  """
  def begin_upload(me, params, content_type, declared) do
    with {:ok, up} <- parse(params),
         :ok <- authorize(me, up),
         :ok <- octet_stream(content_type),
         {:ok, len} <- declared_length(declared),
         true <- len <= limits(up.purpose).max || {:error, :too_large},
         :new <- existing(me, up, len),
         :ok <- check_quota(me, up.purpose, len),
         :ok <- DiskGuard.check(up.purpose, len),
         :ok <- check_rate(me, up.purpose),
         :ok <- Slots.acquire(:up, me, @upload_slots, len) do
      {:ok, Map.put(up, :length, len)}
    end
  end

  @doc "Releases the caller's upload slot (also released when the request process dies)."
  def release_upload(me), do: Slots.release(:up, me)

  # v1.17 §18.3: an `avatar` names no conversation (one present is `400`).
  defp parse(%{"purpose" => "avatar"} = params) do
    if Map.has_key?(params, "conversation_id") do
      {:error, :bad_request}
    else
      with {:ok, cbid} when cbid != nil <- client_blob_id("avatar", params["client_blob_id"]) do
        {:ok,
         %{
           purpose: "avatar",
           conv: nil,
           kind: :none,
           client_blob_id: cbid,
           request_id: nil,
           device_id: params["_device_id"]
         }}
      end
    end
  end

  # v1.22 §22.3: a `backup` part names no conversation (one present is `400`) and its
  # `backup_id`; `client_blob_id` is required.
  defp parse(%{"purpose" => "backup"} = params) do
    with false <- Map.has_key?(params, "conversation_id"),
         {:ok, bid} <- request_id("history", params["backup_id"]),
         {:ok, cbid} when cbid != nil <- client_blob_id("backup", params["client_blob_id"]) do
      {:ok,
       %{
         purpose: "backup",
         conv: nil,
         kind: :none,
         client_blob_id: cbid,
         request_id: nil,
         backup_id: bid,
         device_id: params["_device_id"]
       }}
    else
      _ -> {:error, :bad_request}
    end
  end

  defp parse(%{"purpose" => purpose, "conversation_id" => conv} = params)
       when purpose in @purposes and is_binary(conv) do
    with {:ok, kind} <- conversation_kind(purpose, conv),
         {:ok, cbid} <- client_blob_id(purpose, params["client_blob_id"]),
         {:ok, rid} <- request_id(purpose, params["request_id"]) do
      {:ok,
       %{
         purpose: purpose,
         conv: conv,
         kind: kind,
         client_blob_id: cbid,
         request_id: rid,
         device_id: params["_device_id"]
       }}
    end
  end

  defp parse(_), do: {:error, :bad_request}

  defp request_id("history", id) when is_binary(id) do
    case Ecto.UUID.cast(id) do
      {:ok, id} -> {:ok, id}
      :error -> {:error, :bad_request}
    end
  end

  defp request_id("history", _), do: {:error, :bad_request}
  defp request_id(_purpose, _), do: {:ok, nil}

  defp conversation_kind(purpose, "dm:" <> _ = conv) when purpose in ["media", "history"] do
    if MLS.members(conv) == :error, do: {:error, :bad_request}, else: {:ok, :dm}
  end

  defp conversation_kind(_purpose, conv) do
    if Groups.group_id?(conv), do: {:ok, :grp}, else: {:error, :bad_request}
  end

  defp client_blob_id(purpose, nil)
       when purpose in ["media", "icon", "history", "avatar", "backup"],
       do: {:error, :bad_request}

  defp client_blob_id(_purpose, nil), do: {:ok, nil}

  defp client_blob_id(_purpose, id) when is_binary(id) do
    case Ecto.UUID.cast(id) do
      {:ok, id} -> {:ok, id}
      :error -> {:error, :bad_request}
    end
  end

  defp client_blob_id(_purpose, _), do: {:error, :bad_request}

  # §14.2 "Who may upload". v1.17 §18.3: any signed-in user may upload an avatar.
  defp authorize(_me, %{purpose: "avatar"}), do: :ok

  # v1.22 §22.3: the switch (`503 backup_unavailable`), then `X-Device-Id` (`403`).
  defp authorize(me, %{purpose: "backup", device_id: dev}),
    do: RisiMe.Backups.upload_allowed(me, dev)

  defp authorize(me, %{purpose: "media", kind: :dm, conv: conv}) do
    {:ok, members} = MLS.members(conv)

    with true <- me in members || {:error, :not_found},
         [other] = members -- [me],
         true <-
           (Social.friends?(me, other) and not Social.blocked_between?(me, other)) ||
             {:error, :not_found} do
      if MLS.e2ee?(conv), do: :ok, else: {:error, :not_e2ee}
    end
  end

  # §17.9: only the accepted provider device (`X-Device-Id`) of an `accepted`/`receiving` request
  # of this conversation (`404` otherwise); at most 20 blobs per request (`400`).
  defp authorize(me, %{purpose: "history"} = up) do
    cond do
      not RisiMe.History.upload_allowed?(me, up.device_id, up.conv, up.request_id) ->
        {:error, :not_found}

      replay_of_request?(me, up) ->
        :ok

      request_blob_count(up.request_id) >= RisiMe.History.max_parts() ->
        {:error, :bad_request}

      true ->
        :ok
    end
  end

  defp authorize(me, %{purpose: "icon", conv: conv}) do
    case Groups.visible(me, conv) do
      {:ok, _g, %Member{role: "admin"}} -> :ok
      {:ok, _g, _m} -> {:error, :not_admin}
      error -> error
    end
  end

  defp authorize(me, %{conv: conv}) do
    with {:ok, _g, _m} <- Groups.visible(me, conv), do: :ok
  end

  defp replay_of_request?(me, up), do: by_client_id(me, up.client_blob_id) != nil

  defp request_blob_count(rid) do
    Repo.one(from b in "blobs", where: b.request_id == type(^rid, :binary_id), select: count())
  end

  defp octet_stream(content_type) do
    case content_type && Plug.Conn.Utils.media_type(content_type) do
      {:ok, "application", "octet-stream", _} -> :ok
      _ -> {:error, :bad_media_type}
    end
  end

  defp declared_length(n) when is_integer(n) and n > 0, do: {:ok, n}
  defp declared_length(_), do: {:error, :bad_request}

  # §13.4 / §14.5: the caller's live bytes of this purpose; `icon` isn't counted.
  defp check_quota(me, purpose, size) do
    case quota(purpose) do
      nil ->
        :ok

      limit ->
        used = if purpose == "backup", do: backup_used(me), else: used_bytes(me, purpose)
        if used + size > limit, do: {:error, {:quota_exceeded, used, limit}}, else: :ok
    end
  end

  @doc "Bytes of `owner`'s live (unexpired, undeleted) blobs of `purpose`."
  def used_bytes(owner, purpose) do
    Repo.one(
      from b in "blobs",
        where:
          b.owner == type(^owner, :binary_id) and b.purpose == ^purpose and
            is_nil(b.deleted_at) and (is_nil(b.expires_at) or b.expires_at > ^DateTime.utc_now()),
        select: coalesce(sum(b.size), 0)
    )
    |> to_int()
  end

  defp to_int(%Decimal{} = d), do: Decimal.to_integer(d)
  defp to_int(n) when is_integer(n), do: n

  # Only accepted uploads count (every committed row, deleted or not), so a refused attempt or an
  # idempotent replay never extends a lockout. `Retry-After` is when the oldest counted upload
  # leaves the window.
  defp check_rate(me, purpose) do
    %{hourly: hourly, daily: daily} = limits(purpose)

    with :ok <- window_rate(me, purpose, hourly, 3600) do
      if daily, do: window_rate(me, purpose, daily, 86_400), else: :ok
    end
  end

  defp window_rate(me, purpose, limit, window_s) do
    since = DateTime.add(DateTime.utc_now(), -window_s, :second)
    n = uploads_since(me, purpose, since)

    if n < limit do
      :ok
    else
      oldest =
        Repo.one(
          from b in "blobs",
            where:
              b.owner == type(^me, :binary_id) and b.purpose == ^purpose and
                b.inserted_at > ^since,
            order_by: [asc: b.inserted_at],
            offset: ^(n - limit),
            limit: 1,
            select: type(b.inserted_at, :utc_datetime_usec)
        )

      free_at = DateTime.add(oldest || DateTime.utc_now(), window_s, :second)
      {:error, {:rate_limited, max(1, DateTime.diff(free_at, DateTime.utc_now(), :second) + 1)}}
    end
  end

  defp uploads_since(me, purpose, since) do
    Repo.one(
      from b in "blobs",
        where:
          b.owner == type(^me, :binary_id) and b.purpose == ^purpose and b.inserted_at > ^since,
        select: count()
    )
  end

  # Idempotency by `(owner, client_blob_id)`: a completed upload replays its reply (200); a
  # mismatch is 400; a deleted (or expired) blob is 404, never a silent re-upload.
  defp existing(_me, %{client_blob_id: nil}, _size), do: :new

  defp existing(me, %{client_blob_id: cbid} = up, size) do
    case by_client_id(me, cbid) do
      nil -> :new
      row -> replay(row, up, size)
    end
  end

  defp replay(row, up, size) do
    cond do
      row.purpose != up.purpose or row.conversation_id != up.conv or row.size != size or
          row.backup_id != up[:backup_id] ->
        {:error, :bad_request}

      not live?(row) ->
        {:error, :not_found}

      true ->
        {:replay, reply(row)}
    end
  end

  defp by_client_id(me, cbid) do
    Repo.one(
      from(b in "blobs",
        where: b.owner == type(^me, :binary_id) and b.client_blob_id == type(^cbid, :binary_id)
      )
      |> select_row()
    )
  end

  @doc """
  A unique temp file under `BLOB_DIR/.tmp/` (mode 600), opened for raw exclusive writes.
  Returns `{path, io}`.
  """
  def open_tmp do
    dir = tmp_dir()
    File.mkdir_p!(dir)
    tmp = Path.join(dir, Ecto.UUID.generate())
    {:ok, io} = :file.open(tmp, [:write, :raw, :binary, :exclusive])
    :ok = File.chmod(tmp, 0o600)
    {tmp, io}
  end

  @doc """
  Commits a fully streamed upload: `tmp` (already synced and closed) holds `size` bytes with
  SHA-256 `sha`. The file is renamed into place, then the row is inserted in a transaction that
  holds the owner's lock and re-checks idempotency and the quota on the actual size. Returns
  `{:created, reply}`, `{:replay, reply}` (a concurrent upload with the same `client_blob_id`
  won) or `{:error, reason}`; the file is removed on anything but `:created`.
  """
  def finish(me, up, tmp, size, sha) do
    id = Ecto.UUID.generate()
    final = path(id)
    File.mkdir_p!(Path.dirname(final))
    File.rename!(tmp, final)

    result =
      try do
        insert(me, up, id, size, sha)
      rescue
        e ->
          _ = File.rm(final)
          reraise e, __STACKTRACE__
      end

    case result do
      {:ok, reply} ->
        {:created, reply}

      {:error, {:existing, row}} ->
        _ = File.rm(final)
        replay(row, up, size)

      {:error, reason} ->
        _ = File.rm(final)
        {:error, reason}
    end
  end

  defp insert(me, up, id, size, sha) do
    Repo.transaction(fn ->
      Repo.query!("SELECT pg_advisory_xact_lock(hashtext($1))", ["blobs:" <> me])
      now = DateTime.utc_now()
      ttl = limits(up.purpose).ttl_days

      expires_at =
        cond do
          up.purpose == "backup" -> DateTime.add(now, @backup_part_ttl_hours, :hour)
          ttl -> DateTime.add(now, ttl, :day)
          true -> nil
        end

      with nil <- up.client_blob_id && by_client_id(me, up.client_blob_id),
           :ok <- check_quota(me, up.purpose, size) do
        Repo.insert_all("blobs", [
          %{
            id: Ecto.UUID.dump!(id),
            owner: Ecto.UUID.dump!(me),
            purpose: up.purpose,
            conversation_id: up.conv,
            client_blob_id: up.client_blob_id && Ecto.UUID.dump!(up.client_blob_id),
            request_id: up[:request_id] && Ecto.UUID.dump!(up.request_id),
            backup_id: up[:backup_id] && Ecto.UUID.dump!(up.backup_id),
            size: size,
            sha256: sha,
            expires_at: expires_at,
            inserted_at: now
          }
        ])

        # v1.17 §18.3 (server S3): one current avatar per user, switched in this transaction
        # under the owner's lock, so the row that commits last is current; the previous one
        # gets 24 h. An idempotent replay never comes here, so it never becomes current again.
        if up.purpose == "avatar" do
          Repo.update_all(
            from(b in "blobs",
              where:
                b.owner == type(^me, :binary_id) and b.purpose == "avatar" and
                  is_nil(b.expires_at) and is_nil(b.deleted_at) and b.id != type(^id, :binary_id)
            ),
            set: [expires_at: DateTime.add(now, @avatar_grace_hours, :hour)]
          )
        end

        # §14.5: one current icon per group; the previous one gets 7 days.
        if up.purpose == "icon" do
          Repo.update_all(
            from(b in "blobs",
              where:
                b.conversation_id == ^up.conv and b.purpose == "icon" and is_nil(b.expires_at) and
                  b.id != type(^id, :binary_id)
            ),
            set: [expires_at: DateTime.add(now, @icon_grace_days, :day)]
          )
        end

        reply(%{id: id, size: size, sha256: sha, expires_at: expires_at})
      else
        {:error, reason} -> Repo.rollback(reason)
        %{} = row -> Repo.rollback({:existing, row})
      end
    end)
  end

  defp reply(b),
    do: %{
      blob_id: b.id,
      size: b.size,
      sha256: Base.encode64(b.sha256),
      expires_at: b.expires_at && Messaging.iso(b.expires_at)
    }

  ## Rows

  defp select_row(query) do
    select(query, [b], %{
      id: type(b.id, :binary_id),
      owner: type(b.owner, :binary_id),
      purpose: b.purpose,
      conversation_id: b.conversation_id,
      request_id: type(b.request_id, :binary_id),
      backup_id: type(b.backup_id, :binary_id),
      size: b.size,
      sha256: b.sha256,
      expires_at: type(b.expires_at, :utc_datetime_usec),
      deleted_at: type(b.deleted_at, :utc_datetime_usec),
      inserted_at: type(b.inserted_at, :utc_datetime_usec)
    })
  end

  defp row(id) do
    with {:ok, id} <- Ecto.UUID.cast(id) do
      Repo.one(from(b in "blobs", where: b.id == type(^id, :binary_id)) |> select_row())
    else
      _ -> nil
    end
  end

  defp live?(b, now \\ DateTime.utc_now()) do
    b.deleted_at == nil and (b.expires_at == nil or DateTime.compare(b.expires_at, now) == :gt)
  end

  ## Download (§14.2)

  @doc """
  The download limits: 600 per user per minute (`{:rate_limited, s}`), then a concurrency slot
  (at most 8 per user, `{:rate_limited, 2}`). On `:ok` the caller must `release_download/1`.
  """
  def begin_download(me) do
    case RateLimiter.hit_if_allowed(:blob_download, me, @downloads_per_minute, :timer.minutes(1)) do
      :ok -> Slots.acquire(:down, me, @download_slots)
      _ -> {:error, {:rate_limited, RateLimiter.retry_after_s(:timer.minutes(1))}}
    end
  end

  def release_download(me), do: Slots.release(:down, me)

  @doc """
  `GET /blobs/{id}`: `{:ok, %{path, size, sha256}}` for a reader of the blob (by purpose,
  §14.2). Everything else, expiry and deletion included, is `{:error, :not_found}`.
  """
  def fetch(me, id) do
    with %{} = b <- row(id),
         true <- live?(b),
         true <- readable?(me, b),
         true <- File.regular?(path(b.id)) do
      {:ok, %{path: path(b.id), size: b.size, sha256: b.sha256}}
    else
      _ -> {:error, :not_found}
    end
  end

  # `media`: the owner always; `dm:` either user (whatever the friendship or block state now);
  # `grp:` a user with an active-membership interval overlapping [uploaded_at, now].
  defp readable?(me, %{purpose: "media", owner: me}), do: true

  defp readable?(me, %{purpose: "media", conversation_id: "dm:" <> _ = conv}) do
    case MLS.members(conv) do
      {:ok, members} -> me in members
      :error -> false
    end
  end

  defp readable?(me, %{purpose: "media"} = b),
    do: Membership.active_since?(b.conversation_id, me, b.inserted_at)

  # `history` (§17.9): the uploader, and the requester's user while the request is open or done.
  defp readable?(me, %{purpose: "history", owner: me}), do: true

  defp readable?(me, %{purpose: "history"} = b),
    do: RisiMe.History.blob_reader?(me, b.request_id)

  # `avatar` (v1.17 §18.3, server S4): the owner; a friend with no block either way; a user who
  # shares a group with the owner in which both are `active` or `pending_add`. Checked on every
  # read, so losing the friendship and the last shared group cuts access at once.
  defp readable?(me, %{purpose: "avatar", owner: me}), do: true

  defp readable?(me, %{purpose: "avatar", owner: owner}) do
    (Social.friends?(me, owner) and not Social.blocked_between?(me, owner)) or
      share_group?(me, owner)
  end

  # `backup` (v1.22 §22.3): the owner only.
  defp readable?(me, %{purpose: "backup", owner: owner}), do: owner == me

  # `icon`: current `active` and `pending_add` members only (a removed member loses it at once).
  defp readable?(me, %{purpose: "icon"} = b), do: current_member?(b.conversation_id, me)

  # `mls` (§12.6): the owner, the group's active and pending_add members, and the users of the
  # devices named in a Welcome that references it.
  defp readable?(me, b) do
    b.owner == me or current_member?(b.conversation_id, me) or
      Repo.exists?(
        from r in "blob_readers",
          where: r.blob_id == type(^b.id, :binary_id) and r.user_id == type(^me, :binary_id)
      )
  end

  defp share_group?(a, b) do
    Repo.exists?(
      from m1 in Member,
        join: m2 in Member,
        on: m2.group_id == m1.group_id,
        where:
          m1.user_id == type(^a, :binary_id) and m2.user_id == type(^b, :binary_id) and
            m1.state in ["active", "pending_add"] and m2.state in ["active", "pending_add"]
    )
  end

  defp current_member?(conv, me),
    do: match?(%{state: s} when s in ["active", "pending_add"], Groups.member(conv, me))

  ## Usage

  @doc "`GET /blobs/usage` (§14.2)."
  def usage(me) do
    now = DateTime.utc_now()
    media = limits("media")

    %{
      media: %{
        used: used_bytes(me, "media"),
        limit: media_quota(),
        uploads_last_hour: uploads_since(me, "media", DateTime.add(now, -3600, :second)),
        hourly_limit: media.hourly,
        uploads_last_day: uploads_since(me, "media", DateTime.add(now, -86_400, :second)),
        daily_limit: media.daily
      },
      mls: %{used: used_bytes(me, "mls"), limit: mls_quota()},
      history: %{
        used: used_bytes(me, "history"),
        limit: history_quota(),
        uploads_last_hour: uploads_since(me, "history", DateTime.add(now, -3600, :second)),
        hourly_limit: limits("history").hourly
      },
      backup: %{used: backup_used(me), limit: RisiMe.Backups.quota()}
    }
  end

  ## Backup parts (v1.22 §22.3)

  @doc """
  Live backup bytes of `owner` that count against the quota: unreferenced parts and parts of
  current backups (replaced backups don't count).
  """
  def backup_used(owner) do
    now = DateTime.utc_now()

    Repo.one(
      from b in "blobs",
        left_join: k in "backups",
        on: k.backup_id == b.backup_id,
        where:
          b.owner == type(^owner, :binary_id) and b.purpose == "backup" and is_nil(b.deleted_at) and
            (is_nil(b.expires_at) or b.expires_at > ^now) and
            (is_nil(k.backup_id) or k.current == true),
        select: coalesce(sum(b.size), 0)
    )
    |> to_int()
  end

  @doc "The live `backup` blobs among `ids` that `owner` uploaded for `backup_id`."
  def backup_parts(owner, backup_id, ids) do
    now = DateTime.utc_now()

    Repo.all(
      from(b in "blobs",
        where:
          b.id in type(^ids, {:array, :binary_id}) and b.owner == type(^owner, :binary_id) and
            b.purpose == "backup" and b.backup_id == type(^backup_id, :binary_id) and
            is_nil(b.deleted_at) and (is_nil(b.expires_at) or b.expires_at > ^now)
      )
      |> select_row()
    )
  end

  @doc "Sets `expires_at` of the given blobs (a backup committed: nil; replaced: + 7 days)."
  def set_expiry([], _at), do: :ok

  def set_expiry(ids, at) do
    Repo.update_all(
      from(b in "blobs",
        where: b.id in type(^ids, {:array, :binary_id}) and is_nil(b.deleted_at)
      ),
      set: [expires_at: at]
    )

    :ok
  end

  @doc "Every undeleted `backup` blob of the owner (`DELETE /backups`)."
  def backup_blob_ids(owner) do
    Repo.all(
      from b in "blobs",
        where:
          b.owner == type(^owner, :binary_id) and b.purpose == "backup" and is_nil(b.deleted_at),
        select: type(b.id, :binary_id)
    )
  end

  ## Deletion and expiry

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

  @doc """
  Deletes a blob by id, with no authorisation (callers check it): the row gets `deleted_at`
  (it stays until its expiry, at most #{@deleted_keep_days} days for a blob that had none, so a
  replayed `client_blob_id` gets `404`) and the file is removed at once. Idempotent.
  """
  def remove(id) do
    now = DateTime.utc_now()

    Repo.update_all(
      from(b in "blobs", where: b.id == type(^id, :binary_id) and is_nil(b.deleted_at)),
      set: [deleted_at: now]
    )

    Repo.update_all(
      from(b in "blobs", where: b.id == type(^id, :binary_id) and is_nil(b.expires_at)),
      set: [expires_at: DateTime.add(now, @deleted_keep_days, :day)]
    )

    _ = File.rm(path(id))
    :ok
  end

  @doc """
  v1.12 §15.2: the ids among `ids` that are `media` blobs of `conv` owned by one of `owners`
  (the senders of the deleted targets), in one query. Deleted or expired rows still match, so
  a repeated delete stays idempotent (`remove/1` is a no-op for them).
  """
  def media_ids_owned_by([], _conv, _owners), do: []
  def media_ids_owned_by(_ids, _conv, []), do: []

  def media_ids_owned_by(ids, conv, owners) do
    ids = for id <- ids, {:ok, id} <- [Ecto.UUID.cast(id)], do: id

    Repo.all(
      from b in "blobs",
        where:
          b.id in type(^ids, {:array, :binary_id}) and b.conversation_id == ^conv and
            b.purpose == "media" and b.owner in type(^owners, {:array, :binary_id}),
        select: type(b.id, :binary_id)
    )
  end

  @doc "Expires every blob of a conversation now (a group deleted or reset, §14.5)."
  def expire_conversation(conv) do
    now = DateTime.utc_now()

    Repo.update_all(
      from(b in "blobs",
        where: b.conversation_id == ^conv and (is_nil(b.expires_at) or b.expires_at > ^now)
      ),
      set: [expires_at: now]
    )

    :ok
  end

  @doc """
  v1.15 §17.9: the `history` blobs of a request expire at `at` (1 h after the last ack, or now on
  any other close); a blob whose expiry is already earlier keeps it.
  """
  def expire_request(request_id, %DateTime{} = at) do
    {_, ids} =
      Repo.update_all(
        from(b in "blobs",
          where:
            b.request_id == type(^request_id, :binary_id) and b.purpose == "history" and
              (is_nil(b.expires_at) or b.expires_at > ^at),
          select: type(b.id, :binary_id)
        ),
        set: [expires_at: at]
      )

    # Gone now: the file goes at once (the row stays until the sweep, so a replay gets 404).
    if DateTime.compare(at, DateTime.utc_now()) != :gt, do: Enum.each(ids, &remove/1)
    :ok
  end

  @doc """
  True if `ref` (`%{"blob_id", "size", "sha256"}`) names a live `mls` blob that `me` uploaded
  for `conv`, with that size and hash, at most `max` bytes (§12.6, §12.4 totals).
  """
  def ref_ok?(me, conv, %{"blob_id" => id, "size" => size, "sha256" => sha}, max) do
    case row(id) do
      %{owner: ^me, conversation_id: ^conv, purpose: "mls"} = b ->
        b.size == size and size <= max and Base.encode64(b.sha256) == sha and live?(b)

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

  @doc """
  The expiry sweep (§14.8): deletes rows with `expires_at <= now` in batches of
  #{@sweep_batch}, removing each batch's files after its rows are gone; then removes temp files
  older than an hour. Returns the number of rows deleted.
  """
  def cleanup(now \\ DateTime.utc_now()) do
    n = sweep(now, 0)
    # v1.22 §22.8: replaced backups whose 7 days passed (their parts expire with them).
    RisiMe.Backups.sweep(now)
    sweep_tmp(@tmp_max_age_s)
    if n > 0, do: Logger.info("BlobCleanup: deleted #{n} expired blob(s)")
    n
  end

  defp sweep(now, acc) do
    %{num_rows: n, rows: rows} =
      Repo.query!(
        """
        DELETE FROM blobs WHERE id IN
          (SELECT id FROM blobs WHERE expires_at <= $1 LIMIT #{@sweep_batch})
        RETURNING id
        """,
        [now]
      )

    for [id] <- rows, do: File.rm(path(Ecto.UUID.cast!(id)))
    if n == @sweep_batch, do: sweep(now, acc + n), else: acc + n
  end

  @doc "Removes temp files older than `max_age_s` (crashed or killed uploads)."
  def sweep_tmp(max_age_s \\ @tmp_max_age_s) do
    cutoff = System.os_time(:second) - max_age_s

    case File.ls(tmp_dir()) do
      {:ok, names} ->
        for name <- names,
            file = Path.join(tmp_dir(), name),
            match?({:ok, %{mtime: m}} when m < cutoff, File.stat(file, time: :posix)),
            do: File.rm(file)

        :ok

      _ ->
        :ok
    end
  end

  @doc """
  The orphan pass (§14.8, weekly): removes blob files older than `min_age_s` that have no row,
  or whose row is deleted (crashes between commit and `File.rm`, user deletion, restores).
  Returns the number of files removed.
  """
  def orphans(min_age_s \\ 3600) do
    cutoff = System.os_time(:second) - min_age_s
    dir = blob_dir()

    with {:ok, shards} <- File.ls(dir) do
      shards
      |> Enum.filter(&Regex.match?(~r/^[0-9a-f]{2}$/, &1))
      |> Enum.flat_map(fn shard ->
        case File.ls(Path.join(dir, shard)) do
          {:ok, names} -> Enum.map(names, &{shard, &1})
          _ -> []
        end
      end)
      |> Enum.chunk_every(@sweep_batch)
      |> Enum.map(&remove_orphans(&1, dir, cutoff))
      |> Enum.sum()
      |> tap(&if(&1 > 0, do: Logger.info("BlobCleanup: removed #{&1} orphan file(s)")))
    else
      _ -> 0
    end
  end

  defp remove_orphans(files, dir, cutoff) do
    ids = for {_s, name} <- files, {:ok, id} <- [Ecto.UUID.cast(name)], do: id

    kept =
      Repo.all(
        from b in "blobs",
          where: b.id in type(^ids, {:array, :binary_id}) and is_nil(b.deleted_at),
          select: type(b.id, :binary_id)
      )
      |> MapSet.new()

    for {shard, name} <- files,
        not MapSet.member?(kept, name),
        file = Path.join([dir, shard, name]),
        match?({:ok, %{mtime: m, type: :regular}} when m < cutoff, File.stat(file, time: :posix)),
        reduce: 0 do
      n -> if File.rm(file) == :ok, do: n + 1, else: n
    end
  end
end
