defmodule RisiMe.Messaging.Deletes do
  @moduledoc """
  Deleting messages and chats (contract v1.12 §15, decision 047): the pushes `msg:delete`
  (`scope: "me" | "everyone"`) and `chat:clear`, the `delete` event, and the crash-safe finishing
  job. Storage goes through `RisiMe.Messaging.Store` only.

  The server acts only on cleartext metadata it already has (§15.0): targets are authorised
  against `message_index` (sender, conversation, the TimeUUID time) and the landed group role,
  and in E2EE chats the request's targets must be exactly the MLS `authenticated_data` of the
  control (§15.3). `gone` is success; real refusals are all-or-nothing with `failures`.

  `scope: "everyone"`, in order (§15.2): idempotent resend (`sent_dedupe`) → `not_member` /
  participant → e2ee checks, with the `stale_epoch` check, the caller's role and the planned
  `delete` event id read together under the conversation's advisory lock (§15.4) → shape (the
  `authenticated_data` binding) → rate limit → per-target authorisation → blob owner check →
  claim (`kind = 'delete'`) → the finishing Oban job → tombstones, inbox point deletes, the
  `delete` event, its index row → reply. The job (`RisiMe.Workers.DeleteFinish`) re-runs every
  step idempotently and removes refs, receipts and blobs, so the delete completes even if the
  client never retries.

  No learning-log entries: no model call is involved (§15.10).
  """
  require Logger

  alias RisiMe.{Blobs, Groups, MLS, Messaging, RateLimiter, Repo, TimeUUID}
  alias RisiMe.MLS.Wire
  alias RisiMe.Messaging.{GroupReceipts, Store}

  @max_targets 100
  @max_blob_ids 100
  @window_ms 172_800_000
  @send_limit 20
  @send_window :timer.seconds(10)
  @max_ciphertext 24 * 1024
  @finish_delay_s 15
  @clear_limit 10
  @clear_window :timer.minutes(1)
  @clear_kinds ~w(message reaction status group_receipt delete)

  @uuid ~r/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
  @timeuuid ~r/^[0-9a-f]{8}-[0-9a-f]{4}-1[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

  @type failure :: %{target: String.t(), reason: String.t()}
  @type result ::
          {:ok, map} | {:error, atom} | {:error, atom, [failure]}

  ## msg:delete

  @doc """
  `msg:delete` from `user_id` (the socket's `device_id` in `opts`). `{:ok, reply}`,
  `{:error, reason}` or `{:error, reason, failures}` (an authorisation refusal, §15.2).
  """
  @spec delete(String.t(), map, keyword) :: result
  def delete(user_id, params, opts \\ []) do
    :telemetry.span([:risime, :message, :delete], %{}, fn ->
      result =
        with {:ok, req} <- parse(params) do
          req = Map.put(req, :from_device, opts[:device_id])

          case req.scope do
            "me" -> delete_for_me(user_id, req)
            "everyone" -> delete_for_everyone(user_id, req)
          end
        end

      {result, %{result: result_tag(result)}}
    end)
  end

  defp result_tag({:ok, _}), do: :ok
  defp result_tag(error), do: elem(error, 1)

  defp parse(p) when is_map(p) do
    with {:ok, cmid} <- uuid(p["client_msg_id"]),
         {:ok, conv} <- conversation(p["conversation_id"]),
         true <- p["scope"] in ["me", "everyone"] || {:error, :bad_request},
         {:ok, targets} <- targets(p["targets"]),
         {:ok, blob_ids} <- blob_ids(p["blob_ids"]),
         :ok <- e2ee_fields(p) do
      req = %{
        client_msg_id: cmid,
        conv: conv,
        scope: p["scope"],
        targets: targets,
        blob_ids: blob_ids,
        ciphertext: p["ciphertext"],
        generation: p["generation"],
        epoch: p["epoch"]
      }

      # `me` carries no ciphertext and no blob ids (§15.2).
      if req.scope == "me" and (req.ciphertext != nil or blob_ids != []),
        do: {:error, :bad_request},
        else: {:ok, req}
    end
  end

  defp parse(_), do: {:error, :bad_request}

  defp uuid(s) when is_binary(s) do
    s = String.downcase(s)
    if Regex.match?(@uuid, s), do: {:ok, s}, else: {:error, :bad_request}
  end

  defp uuid(_), do: {:error, :bad_request}

  defp conversation("grp:" <> _ = conv) do
    if Groups.group_id?(conv), do: {:ok, conv}, else: {:error, :bad_request}
  end

  defp conversation("dm:" <> _ = conv) do
    if MLS.members(conv) == :error, do: {:error, :bad_request}, else: {:ok, conv}
  end

  defp conversation(_), do: {:error, :bad_request}

  # 1–100 distinct TimeUUIDs after removing duplicates; any malformed id is bad_request.
  defp targets(list) when is_list(list) and length(list) <= 10 * @max_targets do
    ids = for t <- list, is_binary(t), do: String.downcase(t)

    cond do
      length(ids) != length(list) -> {:error, :bad_request}
      not Enum.all?(ids, &Regex.match?(@timeuuid, &1)) -> {:error, :bad_request}
      true -> ids |> Enum.uniq() |> count_targets()
    end
  end

  defp targets(_), do: {:error, :bad_request}

  defp count_targets(ids) when length(ids) in 1..@max_targets, do: {:ok, ids}
  defp count_targets(_), do: {:error, :bad_request}

  defp blob_ids(nil), do: {:ok, []}

  defp blob_ids(list) when is_list(list) and length(list) <= @max_blob_ids do
    ids = for b <- list, is_binary(b), do: String.downcase(b)

    if length(ids) == length(list) and Enum.all?(ids, &Regex.match?(@uuid, &1)),
      do: {:ok, Enum.uniq(ids)},
      else: {:error, :bad_request}
  end

  defp blob_ids(_), do: {:error, :bad_request}

  defp e2ee_fields(%{"ciphertext" => ct} = p) when ct != nil do
    if is_binary(ct) and is_integer(p["generation"]) and is_integer(p["epoch"]),
      do: :ok,
      else: {:error, :bad_request}
  end

  defp e2ee_fields(_), do: :ok

  ## scope: "me"

  # Idempotent resend → shape → rate limit → delete, from the caller's own partition only:
  # `message`/`reaction` rows of this conversation (read in the same point read), plus the
  # caller's refs rows. No claim, no event, no push; a re-run is idempotent.
  defp delete_for_me(user_id, req) do
    with :not_found <- store().get_sent(user_id, req.client_msg_id) |> not_claimed(),
         :ok <- rate_limit(user_id) do
      conv = req.conv

      found =
        req.targets
        |> parallel(fn t -> {t, store().get_event(user_id, t)} end)
        |> Map.new()

      deleted =
        for t <- req.targets,
            match?(
              {:ok, %{kind: k, conversation_id: ^conv}} when k in ["message", "reaction"],
              found[t]
            ),
            do: t

      :ok = store().delete_events(user_id, deleted)

      for t <- deleted, {:ok, %{kind: "message"}} <- [found[t]] do
        case store().list_message_refs(t, user_id) do
          [] ->
            :ok

          refs ->
            :ok = store().delete_events(user_id, Enum.map(refs, & &1.event_id))
            :ok = store().delete_message_refs(t, user_id)
        end
      end

      {:ok, reply(nil, conv, nil, deleted, req.targets -- deleted)}
    end
  end

  # Any earlier claim of this client_msg_id (a msg:send or a delete for everyone) is a reuse.
  defp not_claimed(:not_found), do: :not_found
  defp not_claimed({:ok, _}), do: {:error, :bad_request}

  ## scope: "everyone"

  defp delete_for_everyone(user_id, req) do
    case store().get_sent(user_id, req.client_msg_id) do
      {:ok, %{kind: "delete"} = prior} -> recover(user_id, req, prior)
      {:ok, _} -> {:error, :bad_request}
      :not_found -> delete_new(user_id, req)
    end
  end

  defp delete_new(user_id, req) do
    with {:ok, kind, members} <- membership(user_id, req.conv),
         {:ok, e2ee?} <- check_e2ee(kind, req),
         {:ok, snap} <- snapshot(kind, user_id, req, e2ee?),
         :ok <- check_aad(req, e2ee?),
         :ok <- rate_limit(user_id) do
      metas = read_targets(req.targets)
      checks = for t <- req.targets, do: {t, authorise(metas[t], user_id, req.conv, kind, snap)}

      case for({t, {:error, r}} <- checks, do: %{target: t, reason: Atom.to_string(r)}) do
        [] ->
          members = snap[:members] || members
          plan = plan(user_id, req, kind, e2ee?, snap, members, checks)
          commit(user_id, req, plan, metas, checks)

        [first | _] = failures ->
          {:error, String.to_existing_atom(first.reason), failures}
      end
    end
  end

  # Group: an active member (unknown groups too, §12.9). DM: a participant; friendship and blocks
  # are not checked (§15.2).
  defp membership(user_id, "grp:" <> _ = conv) do
    if Groups.active_member?(conv, user_id),
      do: {:ok, :grp, nil},
      else: {:error, :not_member}
  end

  defp membership(user_id, conv) do
    {:ok, members} = MLS.members(conv)
    if user_id in members, do: {:ok, :dm, members}, else: {:error, :not_member}
  end

  defp check_e2ee(kind, req) do
    e2ee? = kind == :grp or MLS.e2ee?(req.conv)

    cond do
      e2ee? and req.ciphertext == nil -> {:error, :e2ee_required}
      not e2ee? and (req.ciphertext != nil or req.blob_ids != []) -> {:error, :bad_request}
      not e2ee? -> {:ok, false}
      req.from_device == nil -> {:error, :bad_request}
      true -> ciphertext_size(req.ciphertext)
    end
  end

  defp ciphertext_size(ct) do
    case Base.decode64(ct) do
      {:ok, bin} when byte_size(bin) > @max_ciphertext -> {:error, :too_long}
      {:ok, bin} when byte_size(bin) > 0 -> {:ok, true}
      _ -> {:error, :bad_request}
    end
  end

  # §15.4 "one consistent snapshot": the stale_epoch check, the caller's landed role and the
  # planned event id under the conversation's advisory lock (the commit path's), so a delete
  # accepted at epoch e is ordered before the commit e → e+1 in every inbox. Short: no Cassandra.
  defp snapshot(:grp, user_id, req, true) do
    Groups.locked(req.conv, fn ->
      g = Groups.get_group(req.conv)

      cond do
        g == nil ->
          {:error, :not_member}

        req.generation != g.generation or req.epoch != Groups.epoch(g.id) ->
          {:error, :stale_epoch}

        true ->
          m = Groups.member(req.conv, user_id)

          {:ok,
           planned(%{
             admin?: m != nil and m.state == "active" and m.role == "admin",
             members: Groups.active_member_ids(req.conv)
           })}
      end
    end)
  end

  defp snapshot(:dm, _user_id, req, true) do
    Repo.transaction(fn ->
      Repo.query!("SELECT pg_advisory_xact_lock(hashtext($1))", ["mls:" <> req.conv])

      case MLS.group(req.conv) do
        %{generation: g, epoch: e} when g == req.generation and e == req.epoch ->
          planned(%{admin?: false})

        _ ->
          Repo.rollback(:stale_epoch)
      end
    end)
  end

  defp snapshot(:dm, _user_id, _req, false), do: {:ok, planned(%{admin?: false})}

  defp planned(snap),
    do: Map.merge(snap, %{message_id: TimeUUID.generate(), server_ts: now()})

  # §15.3: the PrivateMessage's authenticated_data is the canonical encoding of the targets.
  defp check_aad(_req, false), do: :ok

  defp check_aad(req, true) do
    if Wire.delete_bound?(req.ciphertext, req.targets), do: :ok, else: {:error, :bad_request}
  end

  defp rate_limit(user_id),
    do: RateLimiter.hit(:msg_send, user_id, @send_limit, @send_window)

  defp read_targets(targets) do
    targets
    |> parallel(fn t -> {t, store().get_message(t)} end)
    |> Map.new()
  end

  defp parallel(list, fun) do
    list
    |> Task.async_stream(fun, max_concurrency: 16, timeout: 30_000)
    |> Enum.map(fn {:ok, v} -> v end)
  end

  # §15.4 server steps 1–3. `{:ok, m}` to delete, `:gone`, `{:gone, m}` (already tombstoned,
  # kept for the blob owner check), or `{:error, reason}`.
  defp authorise(meta, user_id, conv, kind, snap) do
    case meta do
      {:ok, %{conversation_id: ^conv, kind: nil} = m} ->
        cond do
          m.deleted_at != nil ->
            {:gone, m}

          m.sender_id == user_id ->
            if within_window?(m.message_id, snap.server_ts) or snap.admin?,
              do: {:ok, m},
              else: {:error, :too_old}

          kind == :dm ->
            {:error, :not_sender}

          snap.admin? ->
            {:ok, m}

          true ->
            {:error, :not_admin}
        end

      _ ->
        :gone
    end
  end

  defp within_window?(message_id, %DateTime{} = now),
    do: DateTime.to_unix(now, :millisecond) - TimeUUID.unix_ms(message_id) <= @window_ms

  defp plan(user_id, req, kind, e2ee?, snap, members, checks) do
    %{
      "conversation_id" => req.conv,
      "kind" => Atom.to_string(kind),
      "deleter" => user_id,
      "from_device" => if(e2ee?, do: req.from_device),
      "client_msg_id" => req.client_msg_id,
      "message_id" => snap.message_id,
      "server_ts" => Messaging.iso(snap.server_ts),
      "targets" => req.targets,
      "deleted" => for({t, {:ok, _}} <- checks, do: t),
      "members" => Enum.uniq([user_id | members]),
      "e2ee" => e2ee?,
      "ciphertext" => if(e2ee?, do: req.ciphertext),
      "generation" => if(e2ee?, do: req.generation),
      "epoch" => if(e2ee?, do: req.epoch),
      "blob_ids" => []
    }
  end

  defp commit(user_id, req, plan, metas, checks) do
    plan = Map.put(plan, "blob_ids", owned_blobs(req, checks))

    if plan["deleted"] == [] do
      # Nothing new to announce (§15.2 `msg_delete_reply_gone.json`); a blob of an already
      # tombstoned target still goes (idempotent).
      Enum.each(plan["blob_ids"], &Blobs.remove/1)
      {:ok, reply(nil, req.conv, nil, [], req.targets)}
    else
      sent = %{
        message_id: plan["message_id"],
        conversation_id: req.conv,
        server_ts: parse_ts(plan["server_ts"]),
        kind: "delete"
      }

      case store().claim_send(user_id, req.client_msg_id, sent) do
        :ok ->
          # Right after the claim (server R2): the delete completes even if the client never
          # retries.
          {:ok, _job} = enqueue_finish(plan)
          :ok = run(plan, metas)
          {:ok, reply(sent.message_id, req.conv, sent.server_ts, plan["deleted"], gone(plan))}

        {:exists, %{kind: "delete"} = prior} ->
          recover(user_id, req, prior)

        {:exists, _} ->
          {:error, :bad_request}
      end
    end
  end

  defp gone(plan), do: plan["targets"] -- plan["deleted"]

  # §15.2: blob ids must be `media` blobs of this conversation owned by a sender of a `deleted`
  # target or of one already tombstoned; anything else is skipped and logged, never refused.
  defp owned_blobs(%{blob_ids: []}, _checks), do: []

  defp owned_blobs(req, checks) do
    owners = Enum.uniq(for {_, {tag, m}} when tag in [:ok, :gone] <- checks, do: m.sender_id)
    owned = Blobs.media_ids_owned_by(req.blob_ids, req.conv, owners)
    skipped = req.blob_ids -- owned

    if skipped != [],
      do:
        Logger.warning(
          "msg:delete #{req.client_msg_id}: skipped #{length(skipped)} blob id(s) " <>
            "not owned by a target sender: #{Enum.join(skipped, ",")}"
        )

    owned
  end

  # §15.8 crash recovery (server R2): a retry that finds the claim re-runs steps 1 and 3–6 with
  # the claim's ids. A target tombstoned by the caller at the claim's server_ts is `deleted`;
  # one not yet tombstoned is authorised again against the claim's server_ts; one tombstoned by
  # someone else is `gone`. A concurrent duplicate lands here too.
  defp recover(user_id, req, prior) do
    with true <- prior.conversation_id == req.conv || {:error, :bad_request},
         {:ok, kind, members} <- recover_membership(user_id, req.conv),
         e2ee? = req.ciphertext != nil,
         :ok <- check_aad(req, e2ee?) do
      snap = %{
        admin?: kind == :grp and user_id in Groups.admin_ids(req.conv),
        message_id: prior.message_id,
        server_ts: prior.server_ts,
        members: members
      }

      metas = read_targets(req.targets)

      checks =
        for t <- req.targets do
          case metas[t] do
            {:ok, %{deleted_by: ^user_id, deleted_at: at} = m} when at != nil ->
              if same_ms?(at, prior.server_ts), do: {t, {:ok, m}}, else: {t, {:gone, m}}

            meta ->
              {t, authorise(meta, user_id, req.conv, kind, snap)}
          end
        end

      case for({t, {:error, r}} <- checks, do: %{target: t, reason: Atom.to_string(r)}) do
        [] ->
          plan = plan(user_id, req, kind, e2ee?, snap, members, checks)
          plan = Map.put(plan, "blob_ids", owned_blobs(req, checks))

          if plan["deleted"] != [] do
            {:ok, _job} = enqueue_finish(plan)
            :ok = run(plan, metas)
            {:ok, reply(prior.message_id, req.conv, prior.server_ts, plan["deleted"], gone(plan))}
          else
            {:ok, reply(nil, req.conv, nil, [], req.targets)}
          end

        [first | _] = failures ->
          {:error, String.to_existing_atom(first.reason), failures}
      end
    end
  end

  defp recover_membership(_user_id, "grp:" <> _ = conv),
    do: {:ok, :grp, Groups.active_member_ids(conv)}

  defp recover_membership(_user_id, conv) do
    {:ok, members} = MLS.members(conv)
    {:ok, :dm, members}
  end

  defp same_ms?(a, b),
    do: DateTime.to_unix(a, :millisecond) == DateTime.to_unix(b, :millisecond)

  defp reply(message_id, conv, server_ts, deleted, gone) do
    %{
      message_id: message_id,
      conversation_id: conv,
      server_ts: server_ts && Messaging.iso(server_ts),
      deleted: deleted,
      gone: gone
    }
  end

  ## Steps 3–6 (request path and the finishing job)

  defp enqueue_finish(plan) do
    plan
    |> RisiMe.Workers.DeleteFinish.new(schedule_in: @finish_delay_s)
    |> Oban.insert()
  end

  @doc """
  §15.8 steps 3–6 for a planned delete (the job's args). Idempotent: steps 3–5 are skipped once
  the `delete` event's own index row exists (it is written last); step 6 always runs. `metas`
  are the targets' `message_index` reads (re-read when nil).
  """
  def run(plan, metas \\ nil) do
    deleted = plan["deleted"]
    metas = metas || read_targets(deleted)
    rows = for t <- deleted, {:ok, m} <- [metas[t]], into: %{}, do: {t, m}
    server_ts = parse_ts(plan["server_ts"])

    if store().get_message(plan["message_id"]) == :not_found do
      tombstone(rows, plan["deleter"], server_ts)
      drop_receipts(plan, deleted)
      delete_target_events(rows)
      announce(plan, rows)
    end

    cleanup(plan, deleted, server_ts)
    :ok
  end

  # Step 3 (Q3d): the content-free index tombstone with the row's remaining TTL (server R6).
  defp tombstone(rows, deleter, server_ts) do
    for {t, %{deleted_at: nil, ttl: ttl}} <- rows,
        is_integer(ttl) and ttl > 0,
        do: :ok = store().tombstone_message(t, deleter, server_ts, ttl)
  end

  # A pending coalesced group_receipt must never be emitted for a deleted message (server R5).
  defp drop_receipts(%{"kind" => "grp"}, deleted), do: Enum.each(deleted, &GroupReceipts.drop/1)
  defp drop_receipts(_plan, _deleted), do: :ok

  # Step 4 (Q1d): each target's own event (event_id = message_id) in every partition that had
  # it, one unlogged batch per partition.
  defp delete_target_events(rows) do
    rows
    |> Enum.flat_map(fn {t, m} -> for u <- holders(m), do: {u, t} end)
    |> Enum.group_by(&elem(&1, 0), &elem(&1, 1))
    |> Enum.each(fn {u, ids} -> :ok = store().delete_events(u, ids) end)
  end

  defp holders(m),
    do: Enum.uniq([m.sender_id | List.wrap(m.recipient_id) ++ (m.recipients || [])])

  # Step 5: the `delete` event to every member inbox, the deleter's copy first and never pushed,
  # with per-recipient target metadata (server R4); then its own index row (`kind = 'delete'`).
  defp announce(plan, rows) do
    deleter = plan["deleter"]
    others = plan["members"] -- [deleter]

    Messaging.publish_batch([{deleter, event(plan, rows, deleter), [push: false]}])
    Messaging.publish_batch(for u <- others, do: {u, event(plan, rows, u), [push: true]})

    index = %{
      message_id: plan["message_id"],
      sender_id: deleter,
      client_msg_id: plan["client_msg_id"],
      conversation_id: plan["conversation_id"],
      status: "sent",
      kind: "delete"
    }

    index =
      case plan["kind"] do
        "grp" -> Map.merge(index, %{recipient_id: nil, recipients: others})
        "dm" -> Map.put(index, :recipient_id, dm_other(plan))
      end

    :ok = store().put_message(index)
  end

  defp dm_other(plan) do
    {:ok, members} = MLS.members(plan["conversation_id"])
    hd(members -- [plan["deleter"]])
  end

  defp event(plan, rows, user) do
    targets =
      for t <- plan["targets"] do
        case rows[t] do
          %{} = m ->
            if user in holders(m),
              do: %{
                "message_id" => t,
                "from" => m.sender_id,
                "server_ts" => Messaging.iso(TimeUUID.to_datetime(t))
              },
              else: null_target(t)

          nil ->
            null_target(t)
        end
      end

    data = %{
      "message_id" => plan["message_id"],
      "client_msg_id" => plan["client_msg_id"],
      "conversation_id" => plan["conversation_id"],
      "from" => plan["deleter"],
      "targets" => targets,
      "server_ts" => plan["server_ts"]
    }

    data = if plan["kind"] == "dm", do: Map.put(data, "to", dm_other(plan)), else: data

    data =
      if plan["e2ee"],
        do:
          Map.merge(data, %{
            "from_device" => plan["from_device"],
            "ciphertext" => plan["ciphertext"],
            "generation" => plan["generation"],
            "epoch" => plan["epoch"]
          }),
        else: data

    %{event_id: plan["message_id"], kind: "delete", data: data}
  end

  defp null_target(t), do: %{"message_id" => t, "from" => nil, "server_ts" => nil}

  # Step 6: plaintext reactions through message_refs (their inbox rows and index rows), the
  # group_receipts partition, the receipts cache, the refs partition, and the blobs.
  defp cleanup(plan, deleted, server_ts) do
    for t <- deleted do
      case store().list_message_refs(t, nil) do
        [] ->
          :ok

        refs ->
          refs
          |> Enum.group_by(& &1.user_id, & &1.event_id)
          |> Enum.each(fn {u, ids} -> :ok = store().delete_events(u, ids) end)

          for ref <- refs |> Enum.map(& &1.ref_id) |> Enum.reject(&is_nil/1) |> Enum.uniq() do
            case store().get_message(ref) do
              {:ok, %{deleted_at: nil, ttl: ttl}} when is_integer(ttl) and ttl > 0 ->
                :ok = store().tombstone_message(ref, plan["deleter"], server_ts, ttl)

              _ ->
                :ok
            end
          end

          :ok = store().delete_message_refs(t, nil)
      end

      if plan["kind"] == "grp", do: :ok = store().delete_group_receipts(t)
    end

    drop_receipts(plan, deleted)
    Enum.each(plan["blob_ids"] || [], &Blobs.remove/1)
  end

  defp parse_ts(%DateTime{} = dt), do: dt

  defp parse_ts(s) when is_binary(s) do
    {:ok, dt, _} = DateTime.from_iso8601(s)
    dt
  end

  ## chat:clear (§15.9)

  @doc """
  `chat:clear`: accepts `{conversation_id, upto}` and enqueues the clear of the caller's own
  partition (`RisiMe.Workers.ChatClear`, unique on user, conversation and `upto`). `{:ok, %{}}`
  only means "accepted". Membership isn't checked.
  """
  def clear(user_id, %{"conversation_id" => conv, "upto" => upto}) when is_binary(upto) do
    upto = String.downcase(upto)

    with {:ok, conv} <- conversation(conv),
         true <- Regex.match?(@timeuuid, upto) || {:error, :bad_request},
         :ok <- RateLimiter.hit(:chat_clear, user_id, @clear_limit, @clear_window) do
      # v1.15 §17.4: a clear by the requester's user cancels its open history requests there.
      RisiMe.History.chat_cleared(user_id, conv)

      {:ok, _} =
        %{"user_id" => user_id, "conversation_id" => conv, "upto" => upto}
        |> RisiMe.Workers.ChatClear.new()
        |> Oban.insert()

      {:ok, %{}}
    end
  end

  def clear(_user_id, _params), do: {:error, :bad_request}

  @doc "Runs a `chat:clear` (the job): returns the number of events removed."
  def run_clear(user_id, conv, upto) do
    {:ok, n} = store().clear_conversation(user_id, conv, upto, @clear_kinds)
    n
  end

  defp now, do: DateTime.utc_now() |> DateTime.truncate(:millisecond)
  defp store, do: Store.impl()
end
