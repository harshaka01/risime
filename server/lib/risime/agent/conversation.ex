defmodule RisiMe.Agent.Conversation do
  @moduledoc """
  Risi in one Official group (§24.11): handles that conversation's inbox events in order, and
  sends as Risi.

  * **Welcome** (`mls_welcome` naming Risi's device): `join_from_welcome`. The NIF refuses a
    non-Official group (`:private_tab`): logged and ignored. A joined group whose MLS id isn't
    this conversation's (`<conv>#<generation>`) is purged at once.
  * **Commits** (`mls_commit`): applied in epoch order; a gap is filled from the server's commit
    log (§12.8 catch-up, `RisiMe.MLS.commits_since/4`); a commit that removes Risi purges.
  * **Application messages** (`message`): `process_detailed`; the plaintext of an active human
    member's message goes **only** to `RisiMe.Agent.Transcript` (the sealed 24-h buffer); then
    `RisiMe.Agent.Secretary.on_message/2` enqueues id-only jobs (extraction, requests, actions).
  * **Removal:** `purge_group` and the conversation's buffer, then the process stops.
  * **§12.8 rejoin fallback:** when the log can't bridge the gap (or the state is unusable) the
    local group is purged and `RisiMe.Groups.rejoin/3` asks the members for a re-add (at most
    once per 10 minutes).
  * **Device re-adds (v1.46 §12.12.9, decision 088):** a `group_op` naming Risi's device as the
    committer of a `devices` op of this Official group: Risi claims the added devices' key
    packages from the server (`RisiMe.MLS.claim_for_readd/2`), stages `change_members` in the NIF
    and submits it through the ordinary commit path with the op's `op_id`
    (`RisiMe.Groups.Commit.commit/4`), then accepts or rejects its pending commit. Never anything
    else: the server names Risi only for member-committable re-adds in Official.
  * **Send:** `send_envelope/3` encrypts in `RisiMe.Agent.Mls` and sends through the normal
    `msg:send` path as Risi's device, so it is delivered like any member message.

  Every call first checks `groups.tab = 'official'` (`RisiMe.Agent.official?/1`); nothing here
  logs plaintext, and messages carrying it are redacted from crash reports.
  """
  use GenServer, restart: :transient

  require Logger

  alias RisiMe.Agent.{KeyPackages, Mls, Transcript}
  alias RisiMe.Agent.Mls.Nif
  alias RisiMe.{Blobs, Groups, MLS, Messaging, Risi}

  @rejoin_every_ms 600_000

  def start_link(conv),
    do:
      GenServer.start_link(__MODULE__, conv,
        name: {:via, Registry, {RisiMe.Agent.Registry, conv}}
      )

  @doc "Handles one inbox event of this conversation (in order). `:ok` or `{:error, reason}`."
  def handle_event(pid, event), do: GenServer.call(pid, {:event, fn -> event end}, 120_000)

  @doc "Risi is no longer in the group: purge its MLS state and buffer, then stop."
  def removed(pid), do: GenServer.call(pid, :removed, 60_000)

  @doc """
  Sends `envelope` (a §10.3/§24.11 map) as Risi: `{:ok, reply}` or `{:error, reason}`. The
  envelope travels inside a closure so it never shows in an exit reason or a crash report.
  """
  def send_envelope(pid, envelope, timeout \\ 60_000),
    do: GenServer.call(pid, {:send, fn -> envelope end}, timeout)

  @doc """
  v1.39 §36.5 "stop and delete": deletes Risi's own messages `targets` (1–100 TimeUUIDs) for
  everyone (§15.2 `msg:delete`, scope `everyone`, the §15.3 `delete` envelope with its `'D'`
  authenticated data). `{:ok, reply}` or `{:error, reason}`.
  """
  def delete_own(pid, targets, timeout \\ 60_000),
    do: GenServer.call(pid, {:delete, targets}, timeout)

  ## Server

  @impl true
  def init(conv), do: {:ok, %{conv: conv, last_rejoin: nil}}

  @impl true
  def handle_call(msg, _from, state) do
    if RisiMe.Agent.official?(state.conv) do
      {reply, state} = dispatch(msg, state)

      case reply do
        {:stop, reply} -> {:stop, :normal, reply, state}
        reply -> {:reply, reply, state}
      end
    else
      # §24.5 defence: never an MLS call or a buffer write for a non-Official conversation.
      Logger.warning("Risi refused a non-Official conversation #{state.conv}")
      {:stop, :normal, {:error, :private_tab}, state}
    end
  end

  @impl true
  def format_status(status) do
    Map.new(status, fn
      {:message, _} -> {:message, :redacted}
      other -> other
    end)
  end

  defp dispatch({:event, f}, state), do: event(f.(), state)
  defp dispatch(:removed, state), do: {{:stop, purge(state, "removed")}, state}
  defp dispatch({:send, f}, state), do: do_send(f.(), state, 1)
  defp dispatch({:delete, targets}, state), do: do_delete(targets, state, 1)

  ## Events

  defp event(%{kind: "mls_welcome", data: data}, state) do
    if Risi.device_id() in List.wrap(data["to_devices"]),
      do: welcome(data, state),
      else: {:ok, state}
  end

  defp event(%{kind: "mls_commit", data: data}, state), do: commit(data, state)

  defp event(%{kind: "group_op", data: %{"op" => %{"type" => "devices"} = op} = data}, state) do
    if get_in(op, ["committer", "device_id"]) == Risi.device_id(),
      do: readd(op["op_id"], data["generation"], state),
      else: {:ok, state}
  end

  defp event(%{kind: "message", data: data}, state), do: message(data, state)

  # §12.8: a reset starts a new generation; the old one's state is useless.
  defp event(%{kind: "group_event", data: %{"action" => "reset", "generation" => gen}}, state)
       when is_integer(gen) and gen > 1 do
    _ = Mls.call(&Nif.purge_group(&1, gid(state, gen - 1)))
    {:ok, state}
  end

  defp event(_event, state), do: {:ok, state}

  defp welcome(data, state) do
    gen = data["generation"]
    gid = gid(state, gen)

    cond do
      not is_integer(gen) or gen != current_generation(state) ->
        {:ok, state}

      match?({:ok, _}, Mls.epoch(gid)) ->
        # Already joined (a replay after a restart).
        {:ok, state}

      true ->
        with {:ok, bytes} <- payload(data["welcome"], data["welcome_ref"]) do
          case Mls.call(&Nif.join_from_welcome(&1, bytes)) do
            {:ok, %{group_id: ^gid, epoch: epoch}} ->
              Logger.info("Risi joined #{state.conv} at epoch #{epoch}")
              KeyPackages.refill()
              if gen > 1, do: Mls.call(&Nif.purge_group(&1, gid(state, gen - 1)))
              {:ok, state}

            {:ok, %{group_id: other}} ->
              # Never keep a group under another conversation's name.
              _ = Mls.call(&Nif.purge_group(&1, other))
              Logger.warning("Risi purged a Welcome for another group in #{state.conv}")
              {:ok, state}

            {:error, {:private_tab, _}} ->
              Logger.warning("Risi refused a non-Official Welcome in #{state.conv}")
              {:ok, state}

            {:error, {kind, _}} ->
              Logger.warning("Risi could not join #{state.conv}: #{kind}")
              {:ok, state}
          end
        else
          {:error, reason} ->
            Logger.warning("Risi Welcome unreadable in #{state.conv}: #{reason}")
            {:ok, state}
        end
    end
  end

  defp commit(data, state) do
    gen = data["generation"]
    gid = gid(state, gen)

    with true <- gen == current_generation(state),
         {:ok, local} <- Mls.epoch(gid) do
      base = data["epoch"]

      cond do
        not is_integer(base) or base < local ->
          {:ok, state}

        base == local ->
          with {:ok, bytes} <- payload(data["commit"], data["commit_ref"]),
               {:ok, result} <- Mls.call(&Nif.process_detailed(&1, gid, bytes)) do
            if result[:removed_self],
              do: {{:stop, purge(state, "removed by commit")}, state},
              else: {:ok, state}
          else
            _ -> catch_up(gid, local, state)
          end

        true ->
          catch_up(gid, local, state)
      end
    else
      # Not joined (yet): the Welcome comes first, or a rejoin is pending.
      _ -> {:ok, state}
    end
  end

  defp message(data, state) do
    gen = data["generation"]
    gid = gid(state, gen)
    risi = Risi.user_id()

    cond do
      data["from"] == risi or data["from_device"] == Risi.device_id() ->
        {:ok, state}

      gen != current_generation(state) ->
        {:ok, state}

      true ->
        state = ensure_epoch(gid, data["epoch"], state)

        with {:ok, ct} <- decode(data["ciphertext"]),
             {:ok, %{type: :application} = m} <- Mls.call(&Nif.process_detailed(&1, gid, ct)) do
          buffer(m, data, state)
        else
          {:ok, _other} ->
            {:ok, state}

          {:error, {kind, _}} ->
            Logger.info("Risi could not decrypt a message in #{state.conv}: #{kind}")
            {:ok, state}

          {:error, _} ->
            {:ok, state}
        end
    end
  end

  # Only an active human member's message, attributed by MLS to the sender the server named.
  defp buffer(%{sender: %{user_id: u, device_id: d}, plaintext: pt}, data, state) do
    member = Groups.member(state.conv, u)

    human? =
      u == data["from"] and member != nil and member.state == "active" and member.kind == "user"

    case human? and voice(pt) do
      # v1.41 §37.6: a voice note is never buffered (its envelope holds the blob key); it goes
      # to the in-memory transcription path, which buffers only the transcript.
      %{} = env ->
        RisiMe.Agent.Voice.received(
          state.conv,
          # P1 2026-10-14 (proposal send-for-me §41.5): the sending device travels with the note,
          # so a Risi-chat voice request is a request from that device (its skills, its tools).
          %{message_id: data["message_id"], sender_id: u, sender_device: d},
          env
        )

      _ ->
        buffer_human(human?, u, d, pt, data, state)
    end

    {:ok, state}
  end

  defp transcript_claim?(pt) do
    match?({:ok, %{"type" => "voice_transcript"}}, Jason.decode(pt))
  end

  defp voice(pt) do
    case Jason.decode(pt) do
      {:ok, %{"type" => "voice"} = env} -> env
      _ -> nil
    end
  end

  defp buffer_human(human?, u, d, pt, data, state) do
    # PART 9: `voice_transcript` is Risi's own working-copy type (never a client envelope); a
    # member's message claiming it is ignored, so only Risi's transcription feeds that path.
    if human? and not transcript_claim?(pt) do
      m = %{message_id: data["message_id"], sender_id: u, sender_device: d, plaintext: pt}
      :ok = Transcript.put(state.conv, m)
      # v1.36 §35.1: the long-term memory (sealed; opt-outs dropped before sealing).
      RisiMe.Agent.Memory.put_message(state.conv, m)
      # S6/S7: the secretary only enqueues id-only jobs here; no model call, no send.
      RisiMe.Agent.Secretary.on_message(state.conv, m)
    end

    :ok
  end

  # A message from a later epoch than ours: catch up first (commits may still be in flight).
  defp ensure_epoch(gid, epoch, state) when is_integer(epoch) do
    case Mls.epoch(gid) do
      {:ok, local} when local < epoch -> elem(catch_up(gid, local, state), 1)
      _ -> state
    end
  end

  defp ensure_epoch(_gid, _epoch, state), do: state

  ## Device re-adds (v1.46 §12.12.9)

  defp readd(op_id, gen, state) when is_binary(op_id) do
    conv = state.conv

    with %{generation: ^gen} = g <- Groups.get_group(conv),
         %{} = op <- named_op(conv, op_id, 50),
         gid = gid(state, g.generation),
         state = ensure_epoch(gid, Groups.epoch(conv), state),
         {:ok, epoch} <- Mls.epoch(gid),
         true <- epoch == Groups.epoch(conv) || {:error, :epoch},
         added = Groups.Ops.refs(op, "added"),
         removed = Groups.Ops.removed_now(op, Groups.in_group(conv)),
         {:ok, kps} <- claim_kps(conv, added, 20),
         {:ok, gc} <- Mls.call(&Nif.change_members(&1, gid, kps, removed)) do
      submit_readd(conv, gid, g, epoch, op_id, gc, added, removed)
      {:ok, state}
    else
      other ->
        Logger.info("Risi re-add skipped in #{conv}: #{readd_reason(other)}")
        {:ok, state}
    end
  end

  defp readd(_op_id, _gen, state), do: {:ok, state}

  # The `group_op` event may be read before the naming's transaction committed: wait briefly
  # (at most `tries` × 100 ms) for the op to name Risi's device.
  defp named_op(conv, op_id, tries) do
    case Groups.Ops.get(conv, op_id) do
      %{committer_device: nil} = op ->
        if tries > 0, do: Process.sleep(100) && named_op(conv, op_id, tries - 1), else: op

      %{} = op ->
        if op.committer_device == Risi.device_id(), do: op, else: {:error, :named_other}

      nil ->
        if tries > 0,
          do: Process.sleep(100) && named_op(conv, op_id, tries - 1),
          else: {:error, :no_op}
    end
  end

  # A new phone's key packages may still be on their way (the op is created at registration,
  # the upload follows): retry for up to `tries` × 500 ms before giving up (a strike, renamed).
  defp claim_kps(conv, added, tries) do
    case MLS.claim_for_readd(conv, added) do
      {:error, :no_key_package} when tries > 0 ->
        Process.sleep(500)
        claim_kps(conv, added, tries - 1)

      other ->
        other
    end
  end

  defp submit_readd(conv, gid, g, epoch, op_id, gc, added, removed) do
    refs = fn l -> Enum.map(l, fn {u, d} -> %{"user_id" => u, "device_id" => d} end) end

    params = %{
      "generation" => g.generation,
      "epoch" => epoch,
      "commit" => Base.encode64(gc.commit),
      "welcome" => gc.welcome && Base.encode64(gc.welcome),
      "added" => refs.(added),
      "removed" => refs.(removed),
      "op_id" => op_id
    }

    case Groups.Commit.commit(Risi.user_id(), Risi.device_id(), conv, params) do
      {:ok, new_epoch} ->
        _ = Mls.call(&Nif.commit_accepted(&1, gid))
        Logger.info("Risi re-added #{length(added)} device(s) in #{conv} (epoch #{new_epoch})")

      {:error, reason} ->
        _ = Mls.call(&Nif.commit_rejected(&1, gid))
        Logger.warning("Risi re-add refused in #{conv}: #{readd_reason({:error, reason})}")
    end
  end

  defp readd_reason({:error, {kind, _}}) when is_atom(kind), do: kind
  defp readd_reason({:error, kind}) when is_atom(kind), do: kind
  defp readd_reason(nil), do: :gone
  defp readd_reason(false), do: :epoch
  defp readd_reason(%{}), do: :generation
  defp readd_reason(_), do: :other

  ## Catch-up and rejoin (§12.8)

  defp catch_up(gid, local, state) do
    case MLS.commits_since(Risi.user_id(), state.conv, local, 50) do
      {:ok, [], _} ->
        {:ok, state}

      {:ok, commits, more?} ->
        with {:ok, bins} <- commit_bytes(commits),
             {:ok, result} <- Mls.call(&Nif.process_commits(&1, gid, bins)) do
          cond do
            result.removed_self ->
              {{:stop, purge(state, "removed by commit")}, state}

            more? and result.epoch > local ->
              catch_up(gid, result.epoch, state)

            true ->
              {:ok, state}
          end
        else
          _ -> rejoin(gid, state)
        end

      {:error, :log_expired} ->
        rejoin(gid, state)

      {:error, _} ->
        {:ok, state}
    end
  end

  defp commit_bytes(commits) do
    Enum.reduce_while(commits, {:ok, []}, fn c, {:ok, acc} ->
      case payload(c.commit, c.commit_ref) do
        {:ok, b} -> {:cont, {:ok, [b | acc]}}
        e -> {:halt, e}
      end
    end)
    |> case do
      {:ok, l} -> {:ok, Enum.reverse(l)}
      e -> e
    end
  end

  defp rejoin(gid, state) do
    now = System.monotonic_time(:millisecond)

    if state.last_rejoin && now - state.last_rejoin < @rejoin_every_ms do
      {:ok, state}
    else
      _ = Mls.call(&Nif.purge_group(&1, gid))

      case Groups.rejoin(Risi.user_id(), Risi.device_id(), state.conv) do
        {:ok, _} ->
          Logger.info("Risi asked to rejoin #{state.conv}")

        {:error, reason} ->
          Logger.warning("Risi rejoin of #{state.conv} failed: #{inspect(reason)}")
      end

      {:ok, %{state | last_rejoin: now}}
    end
  end

  ## Removal

  defp purge(state, why) do
    gen = current_generation(state)

    for g <- Enum.uniq([gen, max(gen - 1, 1)]),
        do: Mls.call(&Nif.purge_group(&1, gid(state, g)))

    # The buffer and everything derived from it (§24.4, §24.12).
    RisiMe.Agent.Secretary.forget(state.conv)
    Logger.info("Risi left #{state.conv} (#{why}): MLS state, buffer and derived data purged")
    :ok
  end

  ## Send (§24.11 Risi → chat)

  defp do_send(envelope, state, retries) do
    g = Groups.get_group(state.conv)
    gid = gid(state, g.generation)
    state = ensure_epoch(gid, Groups.epoch(state.conv), state)

    with {:ok, epoch} <- Mls.epoch(gid),
         {:ok, ct} <- Mls.call(&Nif.encrypt(&1, gid, Jason.encode!(envelope), <<>>)) do
      params = %{
        "conversation_id" => state.conv,
        "client_msg_id" => Ecto.UUID.generate(),
        "ciphertext" => Base.encode64(ct),
        "generation" => g.generation,
        "epoch" => epoch
      }

      case Messaging.send(Risi.user_id(), params, device_id: Risi.device_id()) do
        {:error, :stale_epoch} when retries > 0 -> do_send(envelope, state, retries - 1)
        result -> {result, state}
      end
    else
      {:error, {kind, _}} -> {{:error, kind}, state}
      {:error, _} = e -> {e, state}
    end
  end

  # §15.2/§15.3: the `delete` envelope, its AAD `0x01 'D' ‖ sorted 16-byte targets`.
  defp do_delete(targets, state, retries) do
    targets = targets |> Enum.map(&String.downcase/1) |> Enum.uniq()
    g = Groups.get_group(state.conv)
    gid = gid(state, g.generation)
    state = ensure_epoch(gid, Groups.epoch(state.conv), state)
    envelope = %{"v" => 1, "type" => "delete", "targets" => targets}

    aad =
      <<0x01, ?D>> <>
        (targets
         |> Enum.map(fn t ->
           {:ok, bin} = Ecto.UUID.dump(t)
           bin
         end)
         |> Enum.sort()
         |> Enum.join())

    with {:ok, epoch} <- Mls.epoch(gid),
         {:ok, ct} <- Mls.call(&Nif.encrypt(&1, gid, Jason.encode!(envelope), aad)) do
      params = %{
        "conversation_id" => state.conv,
        "client_msg_id" => Ecto.UUID.generate(),
        "scope" => "everyone",
        "targets" => targets,
        "ciphertext" => Base.encode64(ct),
        "generation" => g.generation,
        "epoch" => epoch,
        "client_ts" => Messaging.iso(DateTime.utc_now())
      }

      case Messaging.Deletes.delete(Risi.user_id(), params, device_id: Risi.device_id()) do
        {:error, :stale_epoch} when retries > 0 -> do_delete(targets, state, retries - 1)
        {:error, reason, _failures} -> {{:error, reason}, state}
        result -> {result, state}
      end
    else
      {:error, {kind, _}} -> {{:error, kind}, state}
      {:error, _} = e -> {e, state}
    end
  end

  ## Helpers

  defp gid(state, gen), do: RisiMe.Agent.group_id(state.conv, gen)

  defp current_generation(state) do
    case Groups.get_group(state.conv) do
      %{generation: g} -> g
      nil -> nil
    end
  end

  defp decode(b64) when is_binary(b64) do
    case Base.decode64(b64) do
      {:ok, bin} -> {:ok, bin}
      :error -> {:error, :bad_payload}
    end
  end

  defp decode(_), do: {:error, :bad_payload}

  # Inline bytes, or a §12.6 blob Risi may read (verified against its size and SHA-256).
  defp payload(b64, _ref) when is_binary(b64), do: decode(b64)

  defp payload(nil, %{"blob_id" => id, "sha256" => sha}) when is_binary(sha) do
    with {:ok, %{path: path}} <- Blobs.fetch(Risi.user_id(), id),
         {:ok, bin} <- File.read(path),
         true <- Base.encode64(:crypto.hash(:sha256, bin)) == sha do
      {:ok, bin}
    else
      _ -> {:error, :bad_payload}
    end
  end

  defp payload(_, _), do: {:error, :bad_payload}
end
