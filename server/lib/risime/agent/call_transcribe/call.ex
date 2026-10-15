defmodule RisiMe.Agent.CallTranscribe.Call do
  @moduledoc """
  One call's transcription (contract v1.39 §36.2–§36.5): every push and upload of a `call_id` is
  serialised through this process (one per call, in `RisiMe.Agent.CallTranscribe.Registry`), so
  the state machine has a single writer. The session row (`Session`) is written on every
  transition and the `call_transcribe` signal goes to both participants.

  **Runtime of the live session** (memory only): per participant the seen `seq`s, the chunk
  queue (**at most 2** waiting or in flight; audio held only here and in the transcription task,
  dropped as soon as the result arrives, on any error and on a discard), the last chunk time
  (the 90-s `left` rule) and a token bucket (8 per 60 s, burst 3). Results are merged into
  speaker turns (≤ 60 s, posted when closed or after 30 s open).

  **Posts** (consent lines, turns, compare lines, the discard's deletes, the end-of-session
  bookkeeping) run one at a time, in order, in tasks, so a slow post never blocks an upload.
  """
  use GenServer, restart: :transient

  require Logger

  import Ecto.Query

  alias RisiMe.Agent.{Clock, Lang, LearningLog, Out, Speech, SpeechGuard, Transcript}
  alias RisiMe.Agent.CallTranscribe
  alias RisiMe.Agent.CallTranscribe.{Lines, OggJoin, Overlap, Session}
  alias RisiMe.{Repo, TimeUUID}

  @max_sessions 3
  # v1.50 §36.12: the languages whose fast-session chunks are joined before transcription.
  @joined ["si", "ta"]
  @queue_max 2
  @compare_ttl_s 30 * 86_400
  @idle_ms 300_000

  ## API

  def start_link({call_id, rec}),
    do: GenServer.start_link(__MODULE__, {call_id, rec}, name: via(call_id))

  defp via(call_id), do: {:via, Registry, {CallTranscribe.Registry, call_id}}

  @doc "The process of `call_id`, started when needed."
  def ensure(call_id, rec) do
    case Registry.lookup(CallTranscribe.Registry, call_id) do
      [{pid, _}] ->
        {:ok, pid}

      [] ->
        case DynamicSupervisor.start_child(
               CallTranscribe.CallSup,
               {__MODULE__, {call_id, rec}}
             ) do
          {:ok, pid} -> {:ok, pid}
          {:error, {:already_started, pid}} -> {:ok, pid}
          other -> other
        end
    end
  end

  @doc "The running process of `call_id`, or nil."
  def whereis(call_id) do
    case Registry.lookup(CallTranscribe.Registry, call_id) do
      [{pid, _}] -> pid
      [] -> nil
    end
  end

  @doc """
  One push. `room` (v1.45 §40.9, group calls): the room's human users as read for this push
  (`%{users, listen}`, or `:gone`); nil for a 1:1 call. Joiners are synced before the action.
  """
  def push(pid, user, device, action, p, room \\ nil),
    do: GenServer.call(pid, {:push, user, device, action, p, room}, 15_000)

  def chunk(pid, user, device, meta, audio),
    do: GenServer.call(pid, {:chunk, user, device, meta, fn -> audio end}, 15_000)

  @doc "Stops the live session (if any) with `reason` (`official_off`, `switch_off`, …)."
  def stop_live(pid, reason), do: GenServer.call(pid, {:stop_live, reason}, 15_000)

  ## Server

  @impl true
  def init({call_id, rec}) do
    Process.flag(:trap_exit, false)

    st = %{
      call_id: call_id,
      rec: rec,
      session: nil,
      rt: nil,
      draining: %{},
      tasks: %{},
      post_q: :queue.new(),
      posting: nil,
      posted: %{},
      buffered: %{},
      timers: %{}
    }

    # A live session left by a crash can't be resumed (its runtime is gone): it stops.
    st =
      case newest(call_id) do
        %Session{} = s ->
          if Session.live?(s),
            do: finalize(%{st | session: s}, "stopped", "unavailable", nil, false),
            else: %{st | session: s}

        nil ->
          st
      end

    {:ok, schedule_idle(st)}
  end

  @impl true
  def handle_call({:push, user, device, action, p, room}, _from, st) do
    st = sync_room(st, room)
    {reply, st} = push_action(action, user, device, p, room, st)
    {:reply, reply, st}
  end

  def handle_call({:chunk, user, device, meta, audio}, _from, st) do
    {reply, st} = take_chunk(user, device, meta, audio.(), st)
    {:reply, reply, st}
  end

  def handle_call({:stop_live, reason}, _from, st) do
    st =
      case st.session do
        %Session{} = s ->
          if Session.live?(s), do: finalize(st, "stopped", reason, nil, false), else: st

        nil ->
          st
      end

    {:reply, :ok, st}
  end

  @impl true
  def handle_info({:expire, sid}, %{session: %Session{session_id: sid, state: "requested"}} = st),
    do: {:noreply, finalize(st, "expired", "expired", nil, false)}

  # v1.45 §40.9: a joiner didn't answer within 30 s of the pause.
  def handle_info({:expire, sid}, %{session: %Session{session_id: sid, state: "paused"}} = st),
    do: {:noreply, finalize(st, "stopped", "expired", nil, false)}

  def handle_info({:expire, _sid}, st), do: {:noreply, st}

  def handle_info({:tick, sid}, %{session: %Session{session_id: sid, state: state} = s} = st)
      when state in ["on", "paused"] do
    now = mono()
    t = CallTranscribe.timing()

    cond do
      not CallTranscribe.enabled?() ->
        {:noreply, finalize(st, "stopped", "switch_off", nil, false)}

      not CallTranscribe.official_on?(s.conversation_id) ->
        {:noreply, finalize(st, "stopped", "official_off", nil, false)}

      now - st.rt.started_mono >= t.max_ms ->
        {:noreply, finalize(st, "ended", "max_length", nil, false)}

      left?(st, now, t.left_ms) ->
        {:noreply, finalize(st, "ended", "left", nil, false)}

      st.rt.fail_since != nil and now - st.rt.fail_since >= t.unavailable_ms ->
        {:noreply, finalize(st, "stopped", "unavailable", nil, false)}

      true ->
        st = %{st | rt: guard(st.rt)}
        {:noreply, put_timer(st, :tick, {:tick, sid}, t.tick_ms)}
    end
  end

  def handle_info({:tick, _sid}, st), do: {:noreply, st}

  # v1.45 §40.9: a group session reads the room on every tick while it is live (joiners, the
  # room gone). A LiveKit error skips this tick.
  def handle_info({:room, sid}, %{session: %Session{session_id: sid} = s} = st) do
    if Session.live?(s) do
      st =
        case CallTranscribe.room_users(st.rec, st.call_id) do
          {:ok, room} -> sync_room(st, room)
          _ -> st
        end

      st =
        if st.session && Session.live?(st.session),
          do: put_timer(st, :room, {:room, sid}, CallTranscribe.timing().tick_ms),
          else: st

      {:noreply, st}
    else
      {:noreply, st}
    end
  end

  def handle_info({:room, _sid}, st), do: {:noreply, st}

  def handle_info({:flush_pending, sid, user, ref}, st) do
    st =
      with_rt(st, sid, fn st, rt ->
        case rt.parts[user] do
          %{pend_ref: ^ref} -> {st, flush(rt, user)}
          _ -> {st, rt}
        end
      end)

    {:noreply, run_next(st, sid, user)}
  end

  def handle_info({:flush_turn, sid, ref}, st) do
    {:noreply, with_rt(st, sid, &flush_turn(&1, &2, ref))}
  end

  def handle_info(:idle, st) do
    busy? =
      (st.session && Session.live?(st.session)) or st.draining != %{} or st.tasks != %{} or
        st.posting != nil or not :queue.is_empty(st.post_q)

    if busy?, do: {:noreply, schedule_idle(st)}, else: {:stop, :normal, st}
  end

  # A transcription or post task finished.
  def handle_info({ref, result}, st) when is_reference(ref) do
    Process.demonitor(ref, [:flush])
    {:noreply, task_done(ref, {:ok, result}, st)}
  end

  def handle_info({:DOWN, ref, :process, _pid, reason}, st) do
    {:noreply, task_done(ref, {:down, reason}, st)}
  end

  def handle_info(_msg, st), do: {:noreply, st}

  @impl true
  def format_status(status) do
    Map.new(status, fn
      {:state, _} -> {:state, :redacted}
      {:message, _} -> {:message, :redacted}
      other -> other
    end)
  end

  # §36.2 `left`: a 1:1 ends when one participant sent nothing for 90 s; a group call (v1.45
  # §40.9) only when no participant has uploaded for 90 s (people leave and the rest go on).
  defp left?(%{rec: %{group: true}} = st, now, left_ms),
    do: Enum.all?(st.rt.parts, fn {_u, p} -> now - p.last_chunk_at >= left_ms end)

  defp left?(st, now, left_ms),
    do: Enum.any?(st.rt.parts, fn {_u, p} -> now - p.last_chunk_at >= left_ms end)

  defp group?(st), do: Map.get(st.rec, :group) == true

  ## The room (v1.45 §40.9, group calls)

  # The room's human users as read now: joiners become `pending` (a `requested` session waits
  # for them too; an `on` one is `paused` until they allow, 30 s); the room gone ends it.
  defp sync_room(st, nil), do: st

  defp sync_room(%{session: %Session{} = s} = st, :gone) do
    if Session.live?(s), do: finalize(st, "ended", "ended", nil, false), else: st
  end

  defp sync_room(%{session: %Session{} = s} = st, %{users: users}) do
    joiners = users -- Session.user_ids(s)

    if joiners == [] or not Session.live?(s) or not group?(st) do
      st
    else
      new =
        for u <- joiners,
            do: %{"user_id" => u, "consent" => "pending", "at" => nil, "device_id" => nil}

      s = %{s | participants: s.participants ++ new}

      case s.state do
        "on" ->
          t = CallTranscribe.timing()
          now = Clock.usec(Clock.now())

          s = %{
            s
            | state: "paused",
              expires_at: DateTime.add(now, t.request_ttl_ms, :millisecond)
          }

          st
          |> add_parts(joiners)
          |> save(s)
          |> put_timer(:expire, {:expire, s.session_id}, t.request_ttl_ms)

        _ ->
          save(st, s)
      end
    end
  end

  defp sync_room(st, _room), do: st

  defp add_parts(%{rt: nil} = st, _users), do: st

  defp add_parts(%{rt: rt} = st, users) do
    parts = Enum.reduce(users, rt.parts, &Map.put_new(&2, &1, new_part(mono(), rt.fast)))
    %{st | rt: %{rt | parts: parts}}
  end

  defp new_part(now, fast?) do
    %{
      seen: MapSet.new(),
      max_seq: nil,
      queue: :queue.new(),
      inflight: nil,
      last_chunk_at: now,
      tokens: limits(fast?).burst / 1,
      tokens_at: now,
      # Fast sessions: the speaker's last words and line end (the overlap de-duplication).
      tail: [],
      tail_end: nil,
      last_text: nil,
      # v1.50 §36.12: Sinhala/Tamil chunks waiting to be joined (audio in memory only).
      pending: [],
      pend_ms: 0,
      pend_ref: nil
    }
  end

  # §36.4 per-participant limits; a fast session's 1-4 s chunks get their own (proposal
  # 2026-10-15-live-call-latency).
  defp limits(fast?) do
    t = CallTranscribe.timing()

    if fast?,
      do: %{per_min: t.fast_chunk_per_min, burst: t.fast_chunk_burst, queue: t.fast_queue_max},
      else: %{per_min: t.chunk_per_min, burst: t.chunk_burst, queue: @queue_max}
  end

  ## Pushes (§36.3)

  defp push_action("request", user, device, _p, room, st) do
    s = st.session

    cond do
      s != nil and Session.live?(s) ->
        {{:error, :busy}, st}

      session_count(st.call_id) >= @max_sessions ->
        {{:error, :busy}, st}

      true ->
        case CallTranscribe.request_limits(user) do
          :ok -> start_session(user, device, st, room)
          {:error, _} = e -> {e, st}
        end
    end
  end

  defp push_action(action, user, device, p, _room, st)
       when action in ["allow", "decline", "stop"] do
    sid = p["session_id"]

    case st.session do
      %Session{session_id: ^sid} = s ->
        if Session.final?(s),
          do: {{:ok, s}, st},
          else: live_action(action, s, user, device, p, st)

      _ ->
        # An older session of this call: final, so idempotent; anything else is unknown.
        case Repo.get(Session, sid) do
          %Session{call_id: cid} = s when cid == st.call_id -> {{:ok, s}, st}
          _ -> {{:error, :bad_state}, st}
        end
    end
  end

  defp push_action(_action, _user, _device, _p, _room, st), do: {{:error, :bad_request}, st}

  defp live_action("allow", %Session{state: state} = s, user, device, _p, st)
       when state in ["requested", "paused"] do
    case Session.participant(s, user) do
      %{"consent" => "pending"} ->
        if CallTranscribe.transcribe_device?(user, device) do
          s = consent(s, user, "allowed", device)

          cond do
            not Enum.all?(s.participants, &(&1["consent"] == "allowed")) ->
              {{:ok, s}, save(st, s)}

            state == "paused" ->
              {:ok, resume_on(st, s)} |> wrap()

            true ->
              {:ok, start_on(st, s)} |> wrap()
          end
        else
          {{:error, :invalid_device}, st}
        end

      %{"consent" => "allowed"} ->
        {{:ok, s}, st}

      _ ->
        {{:error, :bad_state}, st}
    end
  end

  defp live_action("decline", %Session{state: state} = s, user, _device, p, st)
       when state in ["requested", "paused"] do
    case Session.participant(s, user) do
      %{"consent" => "pending"} ->
        s = consent(s, user, "declined", nil)
        reason = if p["reason"] == "private_call", do: "private_call", else: "declined"
        st = finalize(%{st | session: s}, "declined", reason, user, false)
        {{:ok, st.session}, st}

      _ ->
        {{:error, :bad_state}, st}
    end
  end

  defp live_action("stop", %Session{} = s, user, device, p, st) do
    bound = Session.bound_device(s, user)

    if bound != nil and bound != device do
      {{:error, :invalid_device}, st}
    else
      discard = p["discard"] == true and s.state in ["on", "paused"]

      {state, reason} =
        case {s.state, p["reason"]} do
          {"on", "ended"} -> {"ended", "ended"}
          {"paused", "ended"} -> {"ended", "ended"}
          _ -> {"stopped", "stopped"}
        end

      # A hang-up never discards; "stop and delete" is a stop.
      discard = discard and state == "stopped"
      st = finalize(st, state, reason, user, discard)
      {{:ok, st.session}, st}
    end
  end

  defp live_action(_action, _s, _user, _device, _p, st), do: {{:error, :bad_state}, st}

  defp wrap({:ok, st}), do: {{:ok, st.session}, st}

  defp start_session(user, device, st, room) do
    now = Clock.usec(Clock.now())
    rec = st.rec
    t = CallTranscribe.timing()
    at = Clock.ts(now)

    # v1.45 §40.9: a group call asks every other human in the room; a 1:1 call the peer.
    others =
      cond do
        group?(st) and is_map(room) -> room.users -- [user]
        rec.from == user -> [rec.to]
        true -> [rec.from]
      end

    s = %Session{
      session_id: Ecto.UUID.generate(),
      call_id: st.call_id,
      conversation_id: rec.official,
      chat_id: Map.get(rec, :chat, rec.conv),
      state: "requested",
      requested_by: user,
      requested_at: now,
      expires_at: DateTime.add(now, t.request_ttl_ms, :millisecond),
      participants: [
        %{"user_id" => user, "consent" => "allowed", "at" => at, "device_id" => device}
        | for(
            o <- others,
            do: %{"user_id" => o, "consent" => "pending", "at" => nil, "device_id" => nil}
          )
      ],
      compare: CallTranscribe.compare?(user),
      discard: false
    }

    s = %{s | fast: CallTranscribe.live_fast?(Session.user_ids(s), s.compare)}

    s = Repo.insert!(s)
    CallTranscribe.signal(s)

    st =
      %{st | session: s}
      |> put_timer(:expire, {:expire, s.session_id}, t.request_ttl_ms)

    st = if group?(st), do: put_timer(st, :room, {:room, s.session_id}, t.tick_ms), else: st
    {{:ok, s}, st}
  end

  defp consent(s, user, consent, device) do
    at = Clock.ts(Clock.now())

    ps =
      Enum.map(s.participants, fn
        %{"user_id" => ^user} = p ->
          p
          |> Map.merge(%{"consent" => consent, "at" => at})
          |> then(&if(device, do: Map.put(&1, "device_id", device), else: &1))

        p ->
          p
      end)

    %{s | participants: ps}
  end

  defp start_on(st, s) do
    t = CallTranscribe.timing()
    s = %{s | state: "on", started_at: Clock.usec(Clock.now()), expires_at: nil}
    now = mono()

    parts = Map.new(Session.user_ids(s), fn u -> {u, new_part(now, s.fast)} end)

    rt = %{
      sid: s.session_id,
      started_mono: now,
      parts: parts,
      turn: nil,
      turn_n: 0,
      fail_since: nil,
      final: false,
      discard: false,
      models: MapSet.new(),
      lang_ms: %{},
      spk_ms: %{},
      hint: hint(s),
      fast: s.fast,
      guard_at: nil,
      chunks: 0,
      speech_ms: 0,
      latency_ms: 0,
      transcribed: 0,
      posts: 0
    }

    rt = guard(rt)
    st = cancel_timer(st, :expire)
    st = save(%{st | rt: rt}, s)
    st = enqueue_post(st, {:consent, s})
    put_timer(st, :tick, {:tick, s.session_id}, t.tick_ms)
  end

  # v1.45 §40.9: every joiner allowed: the paused session is `on` again (same runtime).
  defp resume_on(st, s) do
    s = %{s | state: "on", expires_at: nil}
    st |> cancel_timer(:expire) |> save(s)
  end

  # The speech service's `language_hint`, once per session (decision 071 amendment d): the
  # participants' and the Official chat's languages. Only the codes are logged.
  defp hint(s) do
    hint = Lang.speech_hint(Session.user_ids(s), s.conversation_id)
    Logger.info("call transcribe: language hint #{Enum.join(hint, ",")}")
    hint
  rescue
    e ->
      Logger.warning("call transcribe: no language hint: #{inspect(e.__struct__)}")
      []
  end

  # The session reaches a final state: row, signal, consent line; then the runtime drains
  # (or, on a discard, is dropped and the session's posts are deleted).
  defp finalize(st, state, reason, by, discard) do
    s = st.session

    s = %{
      s
      | state: state,
        reason: reason,
        by: by,
        discard: discard,
        ended_at: Clock.usec(Clock.now()),
        expires_at: nil
    }

    st = st |> cancel_timer(:expire) |> cancel_timer(:tick) |> cancel_timer(:room)

    st =
      case st.rt do
        nil ->
          save(st, s)

        rt ->
          s = %{s | chunks: rt.chunks, speech_ms: rt.speech_ms}
          st = save(%{st | rt: nil}, s)
          rt = %{rt | final: true, discard: discard}

          rt =
            if discard,
              do: %{
                rt
                | parts:
                    Map.new(rt.parts, fn {u, p} ->
                      {u, %{p | queue: :queue.new(), pending: [], pend_ms: 0, pend_ref: nil}}
                    end)
              },
              else: rt

          drain(%{st | draining: Map.put(st.draining, rt.sid, {s, rt})}, rt.sid)
      end

    # `requested` → `stopped` is a cancel: still a consent line, like every final transition.
    st = enqueue_post(st, {:consent, s})
    if discard, do: enqueue_post(st, {:discard, s}), else: st
  end

  @saved ~w(state reason expires_at started_at ended_at by discard participants chunks
            speech_ms posts)a

  defp save(st, %Session{} = s) do
    s = %{s | updated_at: Clock.usec(DateTime.utc_now())}

    try do
      Repo.update_all(
        from(x in Session, where: x.session_id == ^s.session_id),
        set: Enum.map([:updated_at | @saved], &{&1, Map.fetch!(s, &1)})
      )
    rescue
      e -> Logger.warning("call transcribe: session not saved: #{inspect(e.__struct__)}")
    end

    CallTranscribe.signal(s)
    %{st | session: s}
  end

  # The drained session's final counters: `finalize/5` saves the row before the in-flight
  # chunks and the open turn are done, so `posts` (and the speech of the last chunks) would stay
  # at their values of that moment. Row only; the clients' session signal has no counters.
  defp save_counts(%Session{} = s) do
    Repo.update_all(
      from(x in Session, where: x.session_id == ^s.session_id),
      set: [
        posts: s.posts,
        chunks: s.chunks,
        speech_ms: s.speech_ms,
        updated_at: Clock.usec(DateTime.utc_now())
      ]
    )
  rescue
    e -> Logger.warning("call transcribe: session counts not saved: #{inspect(e.__struct__)}")
  end

  ## Chunks (§36.4)

  defp take_chunk(user, device, m, audio, st) do
    case st.session do
      %Session{session_id: sid, state: "on"} = s when sid == m.session_id ->
        cond do
          Session.bound_device(s, user) != device ->
            {{:error, 403, :invalid_device}, st}

          true ->
            accept(user, m, audio, st)
        end

      %Session{session_id: sid, state: state}
      when sid == m.session_id and state in ["requested", "paused"] ->
        {{:error, 409, :not_consented}, st}

      _ ->
        {{:error, 409, :stopped}, st}
    end
  end

  defp accept(user, m, audio, st) do
    rt = st.rt
    p = rt.parts[user]
    now = mono()
    lim = limits(rt.fast)
    {tokens, p} = refill(p, now, lim)

    cond do
      MapSet.member?(p.seen, m.seq) ->
        {{:ok, %{"seq" => m.seq, "accepted" => true, "duplicate" => true}}, st}

      p.max_seq != nil and m.seq < p.max_seq ->
        {{:error, 422, :bad_request}, st}

      tokens < 1.0 ->
        {{:error, 429, :rate_limited}, st}

      queued(p) >= lim.queue ->
        {{:error, 503, :transcribe_unavailable}, st}

      true ->
        start = DateTime.add(st.session.started_at, m.offset_ms, :millisecond)

        chunk = %{
          seq: m.seq,
          speaker: user,
          start: start,
          end: DateTime.add(start, m.duration_ms, :millisecond),
          duration_ms: m.duration_ms,
          sha256: :crypto.hash(:sha256, audio) |> Base.encode16(case: :lower),
          audio: audio
        }

        seen = p.seen |> MapSet.put(m.seq) |> trim_seen(m.seq)

        p = %{
          p
          | tokens: tokens - 1.0,
            seen: seen,
            max_seq: max(p.max_seq || m.seq, m.seq),
            last_chunk_at: now,
            queue: :queue.in(chunk, p.queue)
        }

        rt = %{rt | parts: Map.put(rt.parts, user, p), chunks: rt.chunks + 1}
        st = %{st | rt: rt}
        st = run_next(st, rt.sid, user)
        {{:ok, %{"seq" => m.seq, "accepted" => true, "duplicate" => false}}, st}
    end
  end

  defp refill(p, now, lim) do
    tokens = min(lim.burst / 1, p.tokens + (now - p.tokens_at) * lim.per_min / 60_000)
    {tokens, %{p | tokens: tokens, tokens_at: now}}
  end

  defp trim_seen(seen, seq) do
    if MapSet.size(seen) > 256,
      do: seen |> Enum.filter(&(&1 > seq - 200)) |> MapSet.new(),
      else: seen
  end

  defp queued(p), do: :queue.len(p.queue) + if(p.inflight, do: 1, else: 0)

  # Starts the participant's next transcription when none is in flight.
  defp run_next(st, sid, user) do
    with_rt(st, sid, fn st, rt ->
      p = rt.parts[user]

      if p.inflight == nil and not :queue.is_empty(p.queue) do
        {{:value, chunk}, q} = :queue.out(p.queue)
        {audio, meta} = Map.pop(chunk, :audio)
        compare? = st_session(st, sid).compare
        opts = Speech.hint_opts(Map.get(rt, :hint))
        p = rt.parts[user]

        # v1.50 §36.12: live GPU priority and the fast English model; Sinhala and Tamil chunks
        # are only detected here and transcribed joined (`lang`: a joined item).
        opts =
          cond do
            # A joined item: the service detects its language on the whole of it and routes it.
            Map.get(meta, :joined) ->
              opts ++ [joined: true, priority: "live"]

            # The same utterance as the speaker's waiting si/ta chunks (no pause between): it
            # joins them whatever a short chunk's own detection says.
            Map.get(rt, :fast) and continues?(p, meta) ->
              opts ++ [fast: true, priority: "live", defer_langs: @joined ++ ["en"]]

            Map.get(rt, :fast) ->
              opts ++ [fast: true, priority: "live", defer_langs: @joined] ++ en_prompt(p, meta)

            true ->
              opts
          end

        task =
          Task.Supervisor.async_nolink(CallTranscribe.TaskSup, fn ->
            t0 = System.monotonic_time(:millisecond)

            r =
              if compare?,
                do: Speech.compare(audio, opts),
                else: Speech.transcribe(audio, opts)

            # A deferred (si/ta) chunk keeps its audio for the join (memory only).
            r =
              case r do
                {:ok, %{deferred: true} = d} -> {:ok, Map.put(d, :audio, audio)}
                other -> other
              end

            {r, System.monotonic_time(:millisecond) - t0}
          end)

        p = %{p | queue: q, inflight: task.ref}
        rt = %{rt | parts: Map.put(rt.parts, user, p)}
        st = %{st | tasks: Map.put(st.tasks, task.ref, {:speech, sid, user, meta})}
        {st, rt}
      else
        {st, rt}
      end
    end)
  end

  defp st_session(st, sid) do
    case st.draining do
      %{^sid => {s, _}} -> s
      _ -> st.session
    end
  end

  # Runs `f.(st, rt)` → `{st, rt}` (or `st` for a flush) on the runtime of session `sid`,
  # wherever it is (live or draining).
  defp with_rt(st, sid, f) do
    cond do
      st.rt != nil and st.rt.sid == sid ->
        {st, rt} = norm(f.(st, st.rt), st.rt)
        %{st | rt: rt}

      Map.has_key?(st.draining, sid) ->
        {s, rt0} = st.draining[sid]
        {st, rt} = norm(f.(st, rt0), rt0)
        %{st | draining: Map.put(st.draining, sid, {s, rt})}

      true ->
        st
    end
  end

  defp norm({st, rt}, _), do: {st, rt}

  ## Task results

  defp task_done(ref, result, st) do
    case Map.pop(st.tasks, ref) do
      {nil, _} ->
        st

      {{:speech, sid, user, meta}, tasks} ->
        st = %{st | tasks: tasks}

        st =
          with_rt(st, sid, fn st, rt ->
            p = %{rt.parts[user] | inflight: nil}
            rt = %{rt | parts: Map.put(rt.parts, user, p)}
            speech_result(st, rt, meta, result)
          end)

        st |> run_next(sid, user) |> drain(sid)

      {{:post, job}, tasks} ->
        st = %{st | tasks: tasks, posting: nil}
        st |> post_done(job, result) |> next_post()
    end
  end

  defp speech_result(st, %{discard: true} = rt, _meta, _result), do: {st, rt}

  defp speech_result(st, rt, meta, {:ok, {{:ok, %{results: _} = c}, elapsed}}) do
    # Compare mode: one line per chunk with speech; each result is a learning-log row.
    rt = %{
      rt
      | fail_since: nil,
        latency_ms: rt.latency_ms + elapsed,
        transcribed: rt.transcribed + 1
    }

    s = st_session(st, rt.sid)
    st = enqueue_post(st, {:compare_log, s, meta, c})

    case Enum.find(c.results, &(is_binary(&1.text) and String.trim(&1.text) != "")) do
      nil ->
        {st, rt}

      _ ->
        chosen =
          Enum.find(c.results, &(&1.model == c.chosen and is_binary(&1.text) and &1.text != "")) ||
            Enum.find(c.results, &(is_binary(&1.text) and &1.text != ""))

        rt = count_speech(rt, meta, chosen.model, c.language_detected)

        line =
          Map.merge(meta, %{
            language_detected: c.language_detected,
            chosen: c.chosen,
            results: c.results
          })

        seg = %{
          speaker: meta.speaker,
          start: meta.start,
          end: meta.end,
          text: chosen.text,
          language: c.language_detected,
          model: chosen.model
        }

        {enqueue_post(st, {:compare, s, line, seg}), %{rt | posts: rt.posts + 1}}
    end
  end

  defp speech_result(st, rt, meta, {:ok, {{:ok, %{deferred: true, audio: audio} = r}, _ms}}) do
    {st, pend(%{rt | fail_since: nil}, meta, audio, r.language)}
  end

  defp speech_result(st, rt, meta, {:ok, {{:ok, r}, elapsed}}) do
    # v1.50 §36.12: another language from a speaker with joined chunks waiting: they go first.
    rt = if Map.get(meta, :joined), do: rt, else: flush(rt, meta.speaker)

    rt = %{
      rt
      | fail_since: nil,
        latency_ms: rt.latency_ms + elapsed,
        transcribed: rt.transcribed + 1
    }

    {r, rt} = overlap(rt, meta, r)

    if r.speech do
      rt = count_speech(rt, meta, r.model, r.language)

      seg = %{
        speaker: meta.speaker,
        start: meta.start,
        end: meta.end,
        text: r.text,
        language: r.language,
        model: r.model,
        ms: meta.duration_ms,
        romanised: Map.get(r, :romanised) == true,
        # v1.42 §38.2: the detection probability (the quality gate).
        p: Map.get(r, :language_probability)
      }

      add_segment(st, rt, seg)
    else
      {st, rt}
    end
  end

  # An error or a crashed task: the chunk is dropped (never retried here); the service counts
  # as down from the first failure.
  defp speech_result(st, rt, _meta, _other) do
    {st, %{rt | fail_since: rt.fail_since || mono()}}
  end

  defp count_speech(rt, meta, model, lang) do
    %{
      rt
      | speech_ms: rt.speech_ms + meta.duration_ms,
        models: MapSet.put(rt.models, model),
        lang_ms: Map.update(rt.lang_ms, lang, meta.duration_ms, &(&1 + meta.duration_ms)),
        # Each speaker's languages, for their next calls' hint (`Lang.note_speech/2`).
        spk_ms:
          Map.update(
            Map.get(rt, :spk_ms, %{}),
            meta.speaker,
            %{lang => meta.duration_ms},
            &Map.update(&1, lang, meta.duration_ms, fn ms -> ms + meta.duration_ms end)
          )
    }
  end

  ## Fast sessions (proposal 2026-10-15-live-call-latency)

  # The chunk overlaps the speaker's previous one (the phone's 0.5 s): drop the repeated words.
  defp overlap(%{fast: true} = rt, meta, %{speech: true} = r) do
    p = rt.parts[meta.speaker]

    text =
      if p.tail_end != nil and DateTime.compare(meta.start, p.tail_end) == :lt,
        do: Overlap.strip(p.tail, r.text),
        else: r.text

    p = %{
      p
      | tail: Overlap.tail(p.tail, text),
        tail_end: max_dt(p.tail_end || meta.end, meta.end),
        last_text: if(String.trim(text) != "", do: text, else: p.last_text)
    }

    rt = %{rt | parts: Map.put(rt.parts, meta.speaker, p)}

    if String.trim(text) == "",
      do: {%{r | text: "", speech: false}, rt},
      else: {%{r | text: text}, rt}
  end

  defp overlap(rt, _meta, r), do: {r, rt}

  # While a fast session is on, the speech-guard pause file says "live call": the evals, the
  # night compare run and the TTS lab don't start GPU work (extended every minute).
  defp guard(%{fast: true} = rt) do
    now = mono()

    if rt.guard_at == nil or now - rt.guard_at >= 60_000 do
      SpeechGuard.hold("live_call", CallTranscribe.timing().fast_guard_s)
      %{rt | guard_at: now}
    else
      rt
    end
  end

  defp guard(rt), do: rt

  ## Joined Sinhala/Tamil chunks (v1.50 §36.12)

  # A deferred chunk joins the speaker's waiting ones: a new language, or a pause of ≥ 600 ms
  # after ≥ 4 s, flushes the waiting ones first; ≥ 8 s of audio, or ≥ 4 s ending at a pause cut,
  # flushes; the first one starts the 6-s clock.
  defp pend(rt, meta, audio, lang) do
    t = CallTranscribe.timing()
    user = meta.speaker
    p = rt.parts[user]

    # A contiguous chunk deferred whatever its language continues the waiting ones.
    lang =
      case p.pending do
        [%{lang: l} | _] when lang not in @joined -> l
        _ -> lang
      end

    rt =
      case p.pending do
        [%{lang: l, meta: last} | _] ->
          gap = DateTime.diff(meta.start, last.end, :millisecond)

          if l != lang or (gap >= t.join_pause_ms and p.pend_ms >= t.join_min_ms),
            do: flush(rt, user),
            else: rt

        [] ->
          rt
      end

    p = rt.parts[user]

    drop =
      case p.pending do
        [%{meta: last} | _] -> max(DateTime.diff(last.end, meta.start, :millisecond), 0)
        [] -> 0
      end

    ms = max(meta.duration_ms - drop, 0)

    p =
      if p.pending == [] do
        ref = make_ref()
        Process.send_after(self(), {:flush_pending, rt.sid, user, ref}, t.join_wait_ms)
        %{p | pend_ref: ref}
      else
        p
      end

    p = %{
      p
      | pending: [%{meta: meta, audio: audio, lang: lang, drop: drop} | p.pending],
        pend_ms: p.pend_ms + ms
    }

    rt = %{rt | parts: Map.put(rt.parts, user, p)}

    # ≥ 8 s, or ≥ 4 s ending at a pause: a chunk under `join_cut_ms` (3 s) was cut at a pause (a
    # 4-s-limit piece is cut at its quietest frame, 3-4 s), so the utterance may have ended.
    cond do
      p.pend_ms >= t.join_max_ms -> flush(rt, user)
      p.pend_ms >= t.join_min_ms and meta.duration_ms < t.join_cut_ms -> flush(rt, user)
      true -> rt
    end
  end

  # P0-1b English context (timing `fast_en_prompt`, off by default: on the bench it raised WER,
  # see docs/status/ci.md): the speaker's previous line as the prompt of a piece that continues it
  # (its line ended ≤ 1 s before); the last 200 characters, cut at a word.
  defp en_prompt(p, meta) do
    t = CallTranscribe.timing()
    last = p.last_text

    if t.fast_en_prompt and is_binary(last) and p.tail_end != nil and
         DateTime.diff(meta.start, p.tail_end, :millisecond) <= t.fast_en_prompt_gap_ms do
      [en_prompt: last |> String.slice(-200, 200) |> cut_word()]
    else
      []
    end
  end

  defp cut_word(text) do
    if String.length(text) < 200,
      do: text,
      else: text |> String.split(" ", parts: 2) |> List.last()
  end

  # The chunk follows the speaker's waiting si/ta chunks without a pause.
  defp continues?(%{pending: [%{meta: last} | _]}, meta),
    do: DateTime.diff(meta.start, last.end, :millisecond) < CallTranscribe.timing().join_pause_ms

  defp continues?(_p, _meta), do: false

  # The speaker's waiting chunks become one queued item (first in line): one Ogg/Opus stream
  # without the overlaps, transcribed with their language.
  defp flush(rt, user) do
    case rt.parts[user] do
      %{pending: [_ | _] = pending} = p ->
        [first | _] = items = Enum.reverse(pending)
        last = hd(pending)

        p = %{p | pending: [], pend_ms: 0, pend_ref: nil}

        p =
          if rt.discard do
            p
          else
            audio =
              OggJoin.join(
                Enum.with_index(items, fn it, i ->
                  {it.audio, if(i == 0, do: 0, else: it.drop)}
                end)
              )

            item =
              Map.merge(last.meta, %{
                start: first.meta.start,
                duration_ms: rt.parts[user].pend_ms,
                sha256: :crypto.hash(:sha256, audio) |> Base.encode16(case: :lower),
                joined: true,
                audio: audio
              })

            %{p | queue: :queue.in_r(item, p.queue)}
          end

        %{rt | parts: Map.put(rt.parts, user, p)}

      _ ->
        rt
    end
  end

  ## Turns (§36.5 grouping)

  # Proposal 2026-10-15-live-call-latency: a fast session posts every chunk's line at once (the
  # phone already cut it at a pause); a turn is one chunk.
  defp add_segment(st, %{fast: true} = rt, seg) do
    {st, rt} = close_turn(st, rt)
    {st, rt} = open_turn(st, rt, seg, false)
    close_turn(st, rt)
  end

  defp add_segment(st, rt, seg) do
    t = CallTranscribe.timing()

    case rt.turn do
      %{speaker: sp} = turn when sp == seg.speaker ->
        if DateTime.diff(seg.end, turn.start, :millisecond) <= t.turn_max_ms do
          turn = %{
            turn
            | end: max_dt(turn.end, seg.end),
              texts: turn.texts ++ [seg.text],
              romanised: turn.romanised or seg.romanised,
              parts: turn.parts ++ [part(turn.start, seg)],
              p_ms: add_p(turn.p_ms, seg),
              lang_ms: Map.update(turn.lang_ms, seg.language, seg.ms, &(&1 + seg.ms)),
              model_ms: Map.update(turn.model_ms, seg.model, seg.ms, &(&1 + seg.ms))
          }

          {st, %{rt | turn: turn}}
        else
          {st, rt} = close_turn(st, rt)
          open_turn(st, rt, seg)
        end

      nil ->
        open_turn(st, rt, seg)

      _other ->
        {st, rt} = close_turn(st, rt)
        open_turn(st, rt, seg)
    end
  end

  defp open_turn(st, rt, seg, flush? \\ true) do
    ref = make_ref()

    if flush?,
      do:
        Process.send_after(
          self(),
          {:flush_turn, rt.sid, ref},
          CallTranscribe.timing().turn_open_ms
        )

    turn = %{
      ref: ref,
      speaker: seg.speaker,
      start: seg.start,
      end: seg.end,
      texts: [seg.text],
      romanised: seg.romanised,
      parts: [part(seg.start, seg)],
      p_ms: add_p({0.0, 0}, seg),
      lang_ms: %{seg.language => seg.ms},
      model_ms: %{seg.model => seg.ms}
    }

    {st, %{rt | turn: turn}}
  end

  defp flush_turn(st, %{turn: %{ref: ref}} = rt, ref), do: close_turn(st, rt)
  defp flush_turn(st, rt, _ref), do: {st, rt}

  defp close_turn(st, %{turn: nil} = rt), do: {st, rt}
  defp close_turn(st, %{discard: true} = rt), do: {st, %{rt | turn: nil}}

  defp close_turn(st, rt) do
    turn = rt.turn
    n = rt.turn_n + 1

    line = %{
      n: n,
      speaker: turn.speaker,
      start: turn.start,
      end: turn.end,
      texts: turn.texts,
      romanised: turn.romanised,
      parts: turn.parts,
      p: avg_p(turn.p_ms),
      language: top(turn.lang_ms, "en"),
      model: top(turn.model_ms, Speech.route("en"))
    }

    s = st_session(st, rt.sid)
    st = enqueue_post(st, {:turn, s, line})
    {st, %{rt | turn: nil, turn_n: n, posts: rt.posts + 1}}
  end

  # v1.42 §38.3: each chunk's offset in its turn (the item's `offset_ms`) and text.
  defp part(turn_start, seg),
    do: %{"o" => max(DateTime.diff(seg.start, turn_start, :millisecond), 0), "t" => seg.text}

  # v1.42 §38.2: the turn's detection probability, weighted by speech.
  defp add_p({sum, ms}, %{p: p, ms: n}) when is_number(p) and is_integer(n),
    do: {sum + p * n, ms + n}

  defp add_p(acc, _seg), do: acc

  defp avg_p({_sum, 0}), do: nil
  defp avg_p({sum, ms}), do: Float.round(sum / ms, 3)

  defp top(m, default) when map_size(m) == 0, do: default
  defp top(m, _), do: m |> Enum.max_by(fn {_k, v} -> v end) |> elem(0)

  defp max_dt(a, b), do: if(DateTime.compare(a, b) == :lt, do: b, else: a)

  ## Draining a final session

  defp drain(st, sid) do
    # v1.50 §36.12: joined chunks still waiting are transcribed before the session closes.
    waiting =
      case st.draining do
        %{^sid => {_s, rt}} -> for {u, %{pending: [_ | _]}} <- rt.parts, do: u
        _ -> []
      end

    if waiting == [] do
      drain_idle(st, sid)
    else
      st
      |> with_rt(sid, fn st, rt -> {st, Enum.reduce(waiting, rt, &flush(&2, &1))} end)
      |> then(fn st -> Enum.reduce(waiting, st, &run_next(&2, sid, &1)) end)
    end
  end

  defp drain_idle(st, sid) do
    case st.draining do
      %{^sid => {s, rt}} ->
        idle? =
          Enum.all?(rt.parts, fn {_u, p} -> p.inflight == nil and :queue.is_empty(p.queue) end)

        if idle? do
          {st, rt} = close_turn(st, rt)
          s = %{s | posts: rt.posts, chunks: rt.chunks, speech_ms: rt.speech_ms}
          save_counts(s)
          st = %{st | draining: Map.delete(st.draining, sid)}
          enqueue_post(st, {:finish, s, Map.drop(rt, [:parts, :turn])})
        else
          st
        end

      _ ->
        st
    end
  end

  ## Posts, one at a time and in order

  defp enqueue_post(st, job), do: next_post(%{st | post_q: :queue.in(job, st.post_q)})

  defp next_post(%{posting: nil} = st) do
    case :queue.out(st.post_q) do
      {{:value, job}, q} ->
        job = prepare(job, st)
        st = record_buffered(st, job)
        task = Task.Supervisor.async_nolink(CallTranscribe.TaskSup, fn -> run_post(job) end)
        %{st | post_q: q, posting: task.ref, tasks: Map.put(st.tasks, task.ref, {:post, job})}

      {:empty, _} ->
        st
    end
  end

  defp next_post(st), do: st

  # The discard needs the ids posted so far (known only now, in order).
  defp prepare({:discard, s}, st),
    do:
      {:discard, s, Map.get(st.posted, s.session_id, []), Map.get(st.buffered, s.session_id, [])}

  defp prepare({:turn, s, line}, _st),
    do: {:turn, s, line, TimeUUID.at(line.start)}

  defp prepare({:compare, s, line, seg}, _st),
    do: {:compare, s, line, seg, TimeUUID.at(seg.start)}

  defp prepare(job, _st), do: job

  defp record_buffered(st, {:turn, s, _line, buf_id}), do: add_buffered(st, s.session_id, buf_id)

  defp record_buffered(st, {:compare, s, _l, _seg, buf_id}),
    do: add_buffered(st, s.session_id, buf_id)

  defp record_buffered(st, _job), do: st

  defp add_buffered(st, sid, id),
    do: %{st | buffered: Map.update(st.buffered, sid, [id], &[id | &1])}

  defp post_done(st, {kind, s, _, _} = _job, {:ok, {:posted, mid}}) when kind == :turn,
    do: %{st | posted: Map.update(st.posted, s.session_id, [mid], &[mid | &1])}

  defp post_done(st, {:compare, s, _, _, _}, {:ok, {:posted, mid}}),
    do: %{st | posted: Map.update(st.posted, s.session_id, [mid], &[mid | &1])}

  defp post_done(st, {:discard, s, _, _}, _result),
    do: %{
      st
      | posted: Map.delete(st.posted, s.session_id),
        buffered: Map.delete(st.buffered, s.session_id)
    }

  # A discarded session keeps its ids for the `:discard` job queued after this one.
  defp post_done(st, {:finish, %{discard: false} = s, _}, _result),
    do: %{
      st
      | posted: Map.delete(st.posted, s.session_id),
        buffered: Map.delete(st.buffered, s.session_id)
    }

  defp post_done(st, _job, _result), do: st

  # In the task: never raises into the call process.
  defp run_post({:consent, s}) do
    {body, risi} = Lines.consent(s)
    post(s.conversation_id, body, risi, [])
  end

  defp run_post({:turn, s, line, buf_id}) do
    buffer(s, buf_id, line.speaker, %{
      "start" => Clock.ts(line.start),
      "end" => Clock.ts(line.end),
      "text" => line.texts |> Enum.join(" ") |> String.slice(0, 2000),
      "language" => line.language,
      "model" => line.model,
      # v1.42 §38: the posted turn number, its chunks (offset, text), the gate's facts.
      "turn" => line.n,
      "parts" => Map.get(line, :parts, []),
      "p" => Map.get(line, :p),
      "romanised" => Map.get(line, :romanised) == true
    })

    {body, risi} = Lines.transcript(s, line)
    memory? = RisiMe.Agent.Memory.learning_enabled?(line.speaker, s.conversation_id)
    post(s.conversation_id, body, risi, memory: memory?)
  end

  defp run_post({:compare, s, line, seg, buf_id}) do
    buffer(s, buf_id, seg.speaker, %{
      "start" => Clock.ts(seg.start),
      "end" => Clock.ts(seg.end),
      "text" => String.slice(seg.text, 0, 2000),
      "language" => seg.language,
      "model" => seg.model
    })

    {body, risi} = Lines.compare(s, line)
    post(s.conversation_id, body, risi, [])
  end

  defp run_post({:compare_log, s, meta, c}) do
    for r <- c.results do
      LearningLog.record(%{
        call_id: TimeUUID.generate(),
        conversation_id: s.conversation_id,
        chat_id: s.chat_id,
        task: "transcribe_compare",
        model_alias: r.model,
        model: r.model,
        provider: "risime",
        status: r.error || "ok",
        latency_ms: r.elapsed_ms,
        prompt_sha256: meta.sha256,
        source_message_ids: [],
        meta: %{
          "language_detected" => c.language_detected,
          "audio_ms" => meta.duration_ms,
          "input" => %{"session_id" => s.session_id, "seq" => meta.seq, "speaker" => meta.speaker}
        },
        output: if(r.text, do: %{"text" => r.text}),
        ttl_s: @compare_ttl_s
      })
    end

    :ok
  rescue
    e -> Logger.warning("call transcribe: compare rows not logged: #{inspect(e.__struct__)}")
  end

  defp run_post({:discard, s, posted, buffered}) do
    if buffered != [], do: Transcript.delete(s.conversation_id, buffered)

    case Out.delete(s.conversation_id, Enum.reverse(posted)) do
      :ok ->
        :ok

      {:error, reason} ->
        Logger.warning("call transcribe: discard not completed: #{inspect(reason)}")
    end
  rescue
    e -> Logger.warning("call transcribe: discard failed: #{inspect(e.__struct__)}")
  end

  defp run_post({:finish, s, rt}) do
    CallTranscribe.finished(s, rt)
  rescue
    e -> Logger.warning("call transcribe: finish failed: #{inspect(e.__struct__)}")
  end

  defp post(conv, body, risi, opts, tries \\ 5) do
    case Out.post(conv, body, risi, opts) do
      {:ok, %{message_id: mid}} ->
        {:posted, mid}

      {:error, :rate_limited} when tries > 0 ->
        Process.sleep(1_000)
        post(conv, body, risi, opts, tries - 1)

      {:error, reason} ->
        Logger.warning("call transcribe: Risi post not sent: #{inspect(reason)}")
        {:error, reason}
    end
  rescue
    e ->
      Logger.warning("call transcribe: Risi post failed: #{inspect(e.__struct__)}")
      {:error, :exception}
  end

  # §36.8: the transcript working set, sealed in the 24-h buffer (never a `text`: nothing else
  # counts it).
  defp buffer(s, id, speaker, fields) do
    env =
      Map.merge(
        %{"v" => 1, "type" => "call_seg", "call_id" => s.call_id, "session_id" => s.session_id},
        fields
      )

    Transcript.put(s.conversation_id, %{
      message_id: id,
      sender_id: speaker,
      sender_device: nil,
      plaintext: Jason.encode!(env)
    })
  rescue
    e -> Logger.warning("call transcribe: segment not buffered: #{inspect(e.__struct__)}")
  end

  ## Helpers

  defp newest(call_id) do
    Repo.one(
      from s in Session,
        where: s.call_id == ^call_id,
        order_by: [desc: s.requested_at],
        limit: 1
    )
  end

  defp session_count(call_id),
    do: Repo.aggregate(from(s in Session, where: s.call_id == ^call_id), :count)

  defp put_timer(st, name, msg, ms) do
    st = cancel_timer(st, name)
    %{st | timers: Map.put(st.timers, name, Process.send_after(self(), msg, ms))}
  end

  defp cancel_timer(st, name) do
    case Map.pop(st.timers, name) do
      {nil, _} ->
        st

      {ref, timers} ->
        Process.cancel_timer(ref)
        %{st | timers: timers}
    end
  end

  defp schedule_idle(st) do
    Process.send_after(self(), :idle, @idle_ms)
    st
  end

  defp mono, do: System.monotonic_time(:millisecond)
end
