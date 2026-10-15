defmodule RisiMe.Agent.CallTranscribe do
  @moduledoc """
  The call-transcription fast path (contract v1.39 §36, decision 080): 1:1 Official calls only;
  each phone uploads its own microphone in 10–15 s Ogg/Opus chunks; transcription runs only while
  **every** participant has allowed, and any participant stops it for everyone.

  * **Switch** (§36.1): `RISI_CALL_TRANSCRIBE=on` (default off). `/auth/config` says `on` only
    while the switch is on, Risi can post and the speech service is healthy (`config_on?/0`).
    Off answers every push with `transcribe_off` and every upload with `503`, and stops live
    sessions (`switch_off`, at the next tick or at once through `switch_off/0`).
  * **Developers** (§36.1): `RISI_DEV_USERS` lists the ids with `User.risi_dev`; their
    `transcribe_compare` setting (`risi_dev_settings`) turns compare mode on for the sessions
    they request (decided on each request).
  * **Participants** (§36.2): from the server's own record of the call (`Calls`, the
    `ring: true` `call_signal`, 4 h in memory). The named Official conversation must be an
    Official `grp:` with Official on whose `chat_id` is the call's `dm:`.
  * **State machine and runtime:** `RisiMe.Agent.CallTranscribe.Call` (one process per call).
  * **Storage** (§36.8): `Session` rows (90 days, `prune/0`), the transcript working set in the
    sealed 24-h buffer, never audio. **Learning log:** one `transcribe` row per normal session,
    one `transcribe_compare` row per chunk per model in compare mode (text sealed, 30 days).
  * **After the call** (§36.7): `RisiMe.Agent.CallTranscribe.Summary` (an Oban job).
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Clock, LearningLog, Out, Speech}
  alias RisiMe.Agent.CallTranscribe.{Call, Calls, Session}
  alias RisiMe.{Messaging, RateLimiter, Repo, TimeUUID}

  @timing %{
    request_ttl_ms: 30_000,
    left_ms: 90_000,
    max_ms: 4 * 3600 * 1000,
    turn_open_ms: 30_000,
    turn_max_ms: 60_000,
    tick_ms: 5_000,
    unavailable_ms: 60_000,
    # §36.4: 8 chunks per participant per 60 s, a burst of 3 (a token bucket).
    chunk_per_min: 8,
    chunk_burst: 3,
    # Proposal 2026-10-15-live-call-latency (RISI_LIVE_FAST): a fast session's 1-4 s chunks.
    fast_chunk_per_min: 40,
    fast_chunk_burst: 8,
    fast_queue_max: 4,
    # The speech-guard pause file is held while a fast session is on (evals, the night run and
    # the TTS lab stand back), extended every minute.
    fast_guard_s: 120,
    # v1.50 §36.12: a fast session's Sinhala/Tamil chunks of one speaker are joined until 8 s of
    # audio, a pause of >= 600 ms after >= 4 s, or 6 s after the first one arrived.
    join_max_ms: 8_000,
    join_min_ms: 4_000,
    join_pause_ms: 600,
    join_wait_ms: 6_000,
    # A chunk shorter than this was cut at a pause by the phone: a piece that hits the 4-s limit is
    # cut after its quietest frame of the last second (VadChunker, 3-4 s), so it never looks shorter.
    join_cut_ms: 3_000,
    # P0-1b English context: the speaker's previous line as the prompt of the next English piece
    # (off: on the bench it raised WER 7.0 % -> 10.5 % even with the guard; docs/status/ci.md).
    fast_en_prompt: false,
    fast_en_prompt_gap_ms: 1_000
  }

  @max_live 4
  @max_bytes 262_144
  @retention_days 90

  ## Switches

  @doc "True while `RISI_CALL_TRANSCRIBE=on`."
  def enabled?, do: Application.get_env(:risime, :risi_call_transcribe, false) == true

  @doc "`/auth/config` `call_transcribe` (§36.1): the switch, Risi up, the speech service healthy."
  def config_on? do
    enabled?() and RisiMe.Risi.enabled?() and Out.ready?() and Speech.healthy?()
  rescue
    _ -> false
  end

  @doc "The timers (tests shorten them with `config :risime, :risi_call_transcribe_timing`)."
  def timing,
    do:
      Map.merge(@timing, Map.new(Application.get_env(:risime, :risi_call_transcribe_timing, [])))

  defp max_live, do: Application.get_env(:risime, :risi_call_transcribe_max_live, @max_live)

  @doc "True while Risi may act in the Official conversation `conv` (Official on, Risi in)."
  def official_on?(conv), do: RisiMe.Agent.may_act?(conv)

  ## Developer flag (§36.1)

  @doc "True when `user` is listed in `RISI_DEV_USERS`."
  def dev?(user) when is_binary(user),
    do: String.downcase(user) in Application.get_env(:risime, :risi_dev_users, [])

  def dev?(_), do: false

  @doc "The developer's `transcribe_compare` setting (default false)."
  def dev_setting(user) do
    Repo.one(
      from d in "risi_dev_settings",
        where: d.user_id == type(^user, Ecto.UUID),
        select: d.transcribe_compare
    ) == true
  end

  @doc "Stores the developer's `transcribe_compare` setting."
  def put_dev_setting(user, on?) when is_boolean(on?) do
    Repo.insert_all(
      "risi_dev_settings",
      [
        %{user_id: Ecto.UUID.dump!(user), transcribe_compare: on?, updated_at: DateTime.utc_now()}
      ],
      on_conflict: {:replace, [:transcribe_compare, :updated_at]},
      conflict_target: [:user_id]
    )

    :ok
  end

  @doc "Compare mode for a request of `user`: listed now and the setting on."
  def compare?(user), do: dev?(user) and dev_setting(user)

  @doc """
  Proposal 2026-10-15-live-call-latency: true when a new session of these participants takes the
  live fast path (`RISI_LIVE_FAST` on, or canary with a `RISI_DEV_USERS` participant). A compare
  session never does (it runs every model on each chunk).
  """
  def live_fast?(users, compare?) do
    not compare? and RisiMe.Agents.Flags.feature_for("RISI_LIVE_FAST", users)
  rescue
    _ -> false
  end

  ## Devices

  @doc "True if `device_id` names a current `call_transcribe` device of `user`."
  def transcribe_device?(_user, nil), do: false

  def transcribe_device?(user, device_id) do
    case RisiMe.Devices.get(user, device_id) do
      %{mls_signature_key: k, capabilities: caps} when is_binary(k) ->
        "call_transcribe" in (caps || [])

      _ ->
        false
    end
  end

  # §36.3 `peer_not_ready`: the user has a live socket of a `call_transcribe` device.
  defp live_transcribe_device?(user) do
    user
    |> RisiMe.Presence.connections()
    |> Enum.any?(fn {_pid, device_id, _t} -> transcribe_device?(user, device_id) end)
  end

  ## The call record (from `RisiMe.Calls`)

  @doc "A `ring: true` 1:1 `call_signal` was accepted: remembers its participants (4 h)."
  def note_ring(call_id, from, to, conv, media) do
    Calls.put(call_id, %{from: from, to: to, conv: conv, media: media})
  end

  @doc """
  v1.45 §40.9: a `ring: true` **group** `call_signal` was accepted: remembers the call's
  conversation (the room's `grp:`), its starter and start media (4 h; the first ring wins, so a
  re-ring of a running call changes nothing).
  """
  def note_group_ring(call_id, from, conv, media) do
    Calls.put(call_id, %{from: from, to: nil, conv: conv, media: media, group: true})
  end

  @doc """
  v1.45 §40.9: the human users in the call's LiveKit room (the room named by §20.2 from the
  record's conversation, call id and start media; agents excluded), in join order, and whether
  the §27.7 listener is on for the room (metadata `r: 1`). `{:ok, %{users, listen}}`,
  `{:ok, :gone}` when the room doesn't exist, `{:ok, nil}` for a 1:1 record, or
  `{:error, :transcribe_unavailable}` when LiveKit can't be asked.
  """
  def room_users(%{group: true, conv: conv, media: media} = _rec, call_id) do
    alias RisiMe.Calls.LiveKit

    with {:ok, config} <- LiveKit.config(),
         room = LiveKit.room_name(config.api_secret, conv, call_id, media),
         {:ok, rooms} <- LiveKit.api().list_rooms(config, [room]) do
      case Enum.find(rooms, &(&1.name == room)) do
        nil ->
          {:ok, :gone}

        r ->
          with {:ok, ps} <- LiveKit.api().list_participants(config, room) do
            users =
              ps
              |> Enum.map(&(&1.identity |> String.split("/") |> hd() |> String.downcase()))
              |> Enum.uniq()

            users = users -- RisiMe.Risi.agents(users)
            {:ok, %{users: users, listen: listen?(r)}}
          end
      end
    else
      _ -> {:error, :transcribe_unavailable}
    end
  end

  def room_users(_rec, _call_id), do: {:ok, nil}

  defp listen?(%{metadata: m}) when is_binary(m) do
    match?({:ok, %{"r" => 1}}, Jason.decode(m))
  end

  defp listen?(_), do: false

  ## The signal (§36.3)

  @doc "`call_transcribe` with the Session to every participant's `call_transcribe` devices."
  def signal(%Session{} = s) do
    signal = %{kind: "call_transcribe", data: Session.to_json(s)}
    for u <- Session.user_ids(s), do: Messaging.signal(u, signal)
    :ok
  rescue
    e -> Logger.warning("call transcribe: signal failed: #{inspect(e.__struct__)}")
  end

  ## Push `call:transcribe` (§36.3)

  @push_limit 30
  @push_window 60_000

  @doc """
  One `call:transcribe` push of `user` from `device_id`: `{:ok, %{session: Session}}` or
  `{:error, reason}` (§36.3 reasons, or `bad_request`).
  """
  def push(user, device_id, p) when is_map(p) do
    with :ok <- if(enabled?(), do: :ok, else: {:error, :transcribe_off}),
         {:ok, action, call_id, conv} <- parse_push(p),
         :ok <- RateLimiter.hit(:call_transcribe_push, user, @push_limit, @push_window),
         {:ok, rec} <- call_record(call_id),
         {:ok, room} <- room_users(rec, call_id),
         :ok <- participant(rec, user, room, call_id),
         :ok <- official(rec, conv),
         :ok <- request_checks(action, user, device_id, rec, room),
         {:ok, pid} <- Call.ensure(call_id, call_rec(rec, conv)) do
      case Call.push(pid, user, device_id, action, p, room) do
        {:ok, %Session{} = s} -> {:ok, %{session: Session.to_json(s)}}
        {:error, _} = e -> e
      end
    end
  catch
    :exit, _ -> {:error, :transcribe_unavailable}
  end

  def push(_user, _device_id, _p), do: {:error, :bad_request}

  defp parse_push(%{"action" => a, "call_id" => c, "conversation_id" => conv} = p)
       when a in ~w(request allow decline stop) and is_binary(c) and is_binary(conv) do
    cond do
      not uuid?(c) -> {:error, :bad_request}
      not String.starts_with?(conv, "grp:") -> {:error, :not_official}
      a != "request" and not uuid?(p["session_id"]) -> {:error, :bad_request}
      a == "stop" and p["reason"] not in [nil, "stopped", "ended"] -> {:error, :bad_request}
      a == "stop" and not is_boolean(Map.get(p, "discard", false)) -> {:error, :bad_request}
      a == "decline" and p["reason"] not in [nil, "private_call"] -> {:error, :bad_request}
      true -> {:ok, a, String.downcase(c), conv}
    end
  end

  defp parse_push(_), do: {:error, :bad_request}

  defp call_record(call_id) do
    case Calls.get(call_id) do
      nil -> {:error, :unknown_call}
      rec -> {:ok, rec}
    end
  end

  # v1.45 §40.9: in a group call, a user in the room, or one who already is a participant of
  # the call's newest session (left the room since: may still stop).
  defp participant(%{group: true}, user, room, call_id) do
    in_room? = is_map(room) and user in room.users

    if in_room? or session_participant?(call_id, user),
      do: :ok,
      else: {:error, :not_participant}
  end

  defp participant(rec, user, _room, _call_id),
    do: if(user in [rec.from, rec.to], do: :ok, else: {:error, :not_participant})

  defp session_participant?(call_id, user) do
    case newest_session(call_id) do
      %Session{} = s -> user in Session.user_ids(s)
      nil -> false
    end
  end

  defp newest_session(call_id) do
    Repo.one(
      from s in Session,
        where: s.call_id == ^call_id,
        order_by: [desc: s.requested_at],
        limit: 1
    )
  end

  # The record the call's process keeps: the Official conversation, and for a group its chat.
  defp call_rec(%{group: true} = rec, conv) do
    chat = (RisiMe.Groups.get_group(conv) || %{}) |> Map.get(:chat_id) || conv
    Map.merge(rec, %{official: conv, chat: chat})
  end

  defp call_rec(rec, conv), do: Map.put(rec, :official, conv)

  # v1.45 §40.9: a group call's own conversation, an Official `grp:` with Official on.
  defp official(%{group: true} = rec, conv) do
    case RisiMe.Groups.get_group(conv) do
      %{tab: "official"} when conv == rec.conv ->
        if official_on?(conv), do: :ok, else: {:error, :not_official}

      _ ->
        {:error, :not_official}
    end
  end

  # §36.0/§36.2: an Official `grp:` with Official on whose chat is the call's `dm:`.
  defp official(rec, conv) do
    case RisiMe.Groups.get_group(conv) do
      %{tab: "official", chat_id: chat} when chat == rec.conv ->
        if official_on?(conv), do: :ok, else: {:error, :not_official}

      _ ->
        {:error, :not_official}
    end
  end

  # v1.45 §40.9: the requester in the room; every other human in it with a live
  # `call_transcribe` device (and at least one); never while the §27.7 listener runs.
  defp request_checks("request", user, device_id, %{group: true}, room) do
    others = if is_map(room), do: room.users -- [user], else: []

    cond do
      not transcribe_device?(user, device_id) -> {:error, :invalid_device}
      not is_map(room) or user not in room.users -> {:error, :not_participant}
      room.listen -> {:error, :busy}
      others == [] -> {:error, :peer_not_ready}
      not Enum.all?(others, &live_transcribe_device?/1) -> {:error, :peer_not_ready}
      not (Out.ready?() and Speech.healthy?()) -> {:error, :transcribe_unavailable}
      live_count() >= max_live() -> {:error, :transcribe_unavailable}
      true -> :ok
    end
  end

  defp request_checks(action, user, device_id, rec, _room),
    do: request_checks(action, user, device_id, rec)

  defp request_checks("request", user, device_id, rec) do
    other = if rec.from == user, do: rec.to, else: rec.from

    cond do
      not transcribe_device?(user, device_id) -> {:error, :invalid_device}
      not live_transcribe_device?(other) -> {:error, :peer_not_ready}
      not (Out.ready?() and Speech.healthy?()) -> {:error, :transcribe_unavailable}
      live_count() >= max_live() -> {:error, :transcribe_unavailable}
      true -> :ok
    end
  end

  defp request_checks(_action, _user, _device_id, _rec), do: :ok

  defp live_count,
    do:
      Repo.aggregate(from(s in Session, where: s.state in ["requested", "on", "paused"]), :count)

  @doc false
  # §36.3: not within 10 s of this user's last request, and 10 requests per hour.
  def request_limits(user) do
    with :ok <- RateLimiter.hit_if_allowed(:call_transcribe_request_gap, user, 1, 10_000) do
      RateLimiter.hit_if_allowed(:call_transcribe_request_hour, user, 10, 3_600_000)
    end
  end

  ## GET /calls/{call_id}/transcribe (§36.3)

  @doc "The newest session of the call for a participant: `{:ok, json | nil}` or `{:error, :not_found}`."
  def current(user, call_id) do
    if uuid?(call_id) do
      call_id = String.downcase(call_id)

      newest =
        Repo.one(
          from s in Session,
            where: s.call_id == ^call_id,
            order_by: [desc: s.requested_at],
            limit: 1
        )

      cond do
        newest != nil and user in Session.user_ids(newest) ->
          {:ok, Session.to_json(newest)}

        newest == nil ->
          case Calls.get(call_id) do
            %{group: true} = rec ->
              case room_users(rec, call_id) do
                {:ok, %{users: users}} ->
                  if user in users, do: {:ok, nil}, else: {:error, :not_found}

                _ ->
                  {:error, :not_found}
              end

            %{from: f, to: t} when user in [f, t] ->
              {:ok, nil}

            _ ->
              {:error, :not_found}
          end

        true ->
          {:error, :not_found}
      end
    else
      {:error, :not_found}
    end
  end

  ## Upload (§36.4)

  @doc """
  One chunk upload. `params`: the multipart fields (`audio` a binary held in memory, or
  `:too_large`). `{:ok, reply}` or `{:error, status, code}`.
  """
  def upload(user, device_id, call_id, params) do
    with :ok <- if(enabled?(), do: :ok, else: {:error, 503, :transcribe_off}),
         {:ok, s} <- upload_session(call_id, params["session_id"]),
         :ok <- if(user in Session.user_ids(s), do: :ok, else: {:error, 403, :not_participant}),
         :ok <- if(official_on?(s.conversation_id), do: :ok, else: {:error, 403, :not_official}),
         :ok <- upload_state(s),
         :ok <-
           if(Session.bound_device(s, user) == device_id,
             do: :ok,
             else: {:error, 403, :invalid_device}
           ),
         :ok <- if(params["audio"] == :too_large, do: {:error, 413, :too_large}, else: :ok),
         {:ok, meta, audio} <- chunk_fields(s, params),
         {:ok, pid} <- Call.ensure(s.call_id, rec_of(s)) do
      Call.chunk(pid, user, device_id, meta, audio)
    end
  catch
    :exit, _ -> {:error, 503, :transcribe_unavailable}
  end

  defp upload_session(call_id, sid) do
    with true <- uuid?(call_id) and uuid?(sid),
         %Session{} = s <- Repo.get(Session, sid),
         true <- s.call_id == String.downcase(call_id) do
      {:ok, s}
    else
      _ -> {:error, 404, :not_found}
    end
  end

  defp upload_state(%Session{state: "on"}), do: :ok
  defp upload_state(%Session{state: "requested"}), do: {:error, 409, :not_consented}
  # v1.45 §40.9: paused until a joiner allows.
  defp upload_state(%Session{state: "paused"}), do: {:error, 409, :not_consented}
  defp upload_state(_), do: {:error, 409, :stopped}

  defp chunk_fields(s, p) do
    with {:ok, seq} <- int(p["seq"]),
         {:ok, offset} <- int(p["offset_ms"]),
         {:ok, duration} <- int(p["duration_ms"]),
         true <- seq >= 0 and offset >= 0 and duration in 1..20_000,
         true <- p["codec"] == "ogg_opus",
         audio when is_binary(audio) and byte_size(audio) > 0 <- p["audio"],
         true <- byte_size(audio) <= @max_bytes do
      {:ok, %{session_id: s.session_id, seq: seq, offset_ms: offset, duration_ms: duration},
       audio}
    else
      _ -> {:error, 422, :bad_request}
    end
  end

  defp int(v) when is_integer(v), do: {:ok, v}

  defp int(v) when is_binary(v) do
    case Integer.parse(String.trim(v)) do
      {n, ""} -> {:ok, n}
      _ -> :error
    end
  end

  defp int(_), do: :error

  defp rec_of(%Session{} = s) do
    case Calls.get(s.call_id) do
      %{group: true} = rec ->
        Map.merge(rec, %{official: s.conversation_id, chat: s.chat_id})

      _ ->
        # A group session has more than two participants (v1.45 §40.9); the 1:1 shape is kept.
        [a | rest] = Session.user_ids(s)
        b = List.first(rest) || a
        %{from: a, to: b, conv: s.chat_id, official: s.conversation_id, media: "audio"}
    end
  end

  ## Stops from outside

  @doc "§36.2: Official was turned off in `conv`: its live session stops (`official_off`)."
  def official_off(conv) do
    live =
      Repo.all(
        from s in Session,
          where: s.conversation_id == ^conv and s.state in ["requested", "on", "paused"],
          select: s.call_id
      )

    for call_id <- live, pid = Call.whereis(call_id), do: safe_stop(pid, "official_off")
    :ok
  rescue
    e -> Logger.warning("call transcribe: official_off failed: #{inspect(e.__struct__)}")
  end

  @doc "§36.1 kill switch: every live session stops (`switch_off`)."
  def switch_off do
    live =
      Repo.all(
        from s in Session, where: s.state in ["requested", "on", "paused"], select: s.call_id
      )

    for call_id <- live, pid = Call.whereis(call_id), do: safe_stop(pid, "switch_off")
    :ok
  end

  defp safe_stop(pid, reason) do
    Call.stop_live(pid, reason)
  catch
    :exit, _ -> :ok
  end

  @doc """
  At boot: a session left `requested`/`on` by a restart can't resume (its runtime was in
  memory): it becomes `stopped` (`switch_off` while the switch is off, else `unavailable`).
  """
  def recover do
    reason = if enabled?(), do: "unavailable", else: "switch_off"
    now = DateTime.utc_now()

    Repo.update_all(
      from(s in Session, where: s.state in ["requested", "on", "paused"]),
      set: [state: "stopped", reason: reason, ended_at: now, expires_at: nil, updated_at: now]
    )

    :ok
  rescue
    e -> Logger.warning("call transcribe: recovery skipped: #{inspect(e.__struct__)}")
  end

  @doc "§36.8: session rows are kept 90 days."
  def prune do
    cutoff = DateTime.add(DateTime.utc_now(), -@retention_days * 86_400, :second)
    Repo.delete_all(from s in Session, where: s.inserted_at < ^cutoff)
    :ok
  end

  ## The end of a session (from `Call`)

  @doc false
  def finished(%Session{} = s, rt) do
    if not s.compare and rt.chunks > 0, do: log_session(s, rt)
    if not s.discard, do: note_speech(s, rt)

    # P0 2026-10-14 (never silent): the speech service failed for 60 s and the session stopped.
    if not s.discard and s.reason == "unavailable",
      do:
        RisiMe.Agent.CallTranscribe.Summary.say(
          s.conversation_id,
          s,
          RisiMe.Agent.CallTranscribe.Summary.unavailable_text(rt.speech_ms > 0)
        )

    if not s.discard and s.state in ["ended", "stopped"] and rt.speech_ms > 0 do
      %{
        "kind" => "call_transcribe_summary",
        "conv" => s.conversation_id,
        "session_id" => s.session_id
      }
      |> RisiMe.Workers.Risi.new(queue: :risi)
      |> Oban.insert()
    end

    :ok
  end

  # Decision 071 amendment d: each speaker's languages (codes and ms) feed their next hint,
  # unless they stopped Risi learning in this chat.
  defp note_speech(s, rt) do
    for {user, lang_ms} <- Map.get(rt, :spk_ms, %{}),
        is_binary(user),
        RisiMe.Agent.Memory.learning_enabled?(user, s.conversation_id),
        do: RisiMe.Agent.Lang.note_speech(user, lang_ms)

    :ok
  rescue
    e -> Logger.warning("call transcribe: speech languages not noted: #{inspect(e.__struct__)}")
  end

  # §36.8: one `transcribe` row per normal session (models, audio seconds per language, chunk
  # count, latency; no output).
  defp log_session(s, rt) do
    models = rt.models |> MapSet.to_list() |> Enum.sort()

    LearningLog.record(%{
      call_id: TimeUUID.generate(),
      conversation_id: s.conversation_id,
      chat_id: s.chat_id,
      task: "transcribe",
      model_alias: List.first(models) || Speech.route("en"),
      model: Enum.join(models, ","),
      provider: "risime",
      status: "ok",
      latency_ms: div(rt.latency_ms, max(rt.transcribed, 1)),
      source_message_ids: [],
      meta: %{
        "session_id" => s.session_id,
        "models" => models,
        "chunks" => rt.chunks,
        "audio_s" => Map.new(rt.lang_ms, fn {l, ms} -> {l, Float.round(ms / 1000, 1)} end)
      },
      output: nil
    })
  rescue
    e -> Logger.warning("call transcribe: session row not logged: #{inspect(e.__struct__)}")
  end

  ## Helpers

  defp uuid?(s) when is_binary(s), do: match?({:ok, _}, Ecto.UUID.cast(s))
  defp uuid?(_), do: false

  @doc false
  def now, do: Clock.now()
end
