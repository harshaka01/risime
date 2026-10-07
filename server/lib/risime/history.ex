defmodule RisiMe.History do
  @moduledoc """
  History sharing between devices (contract v1.15 §17, decision 049). The server routes and
  names; it never sees content or keys.

  * State and limits live in Postgres (`history_requests`, `history_candidates`, §17.11); every
    transition of a request runs under one advisory lock per `request_id` (`transition/2`), so
    accept races have one winner.
  * Events (`history_request`, `history_request_closed`, `history_status`, `history_share`) are
    ordinary inbox events with a 48 h TTL (`RisiMe.Messaging.publish_history/3`), each with the
    content-free inbox wake.
  * Timers are `RisiMe.Workers.HistoryTimer` jobs (own phase end, member window, delivery
    deadline, expiry, the daily prune); a job that is no longer current is a no-op.
  * Hooks: `device_joined/2` (dormant own devices), `device_gone/2`, `member_gone/2`,
    `conversation_reset/1`, `chat_cleared/2`.

  Naming (§17.4): every own candidate at once (superseded, offline ones when their inbox joins),
  an own phase of 10 minutes (or until every own candidate answered, or `history:escalate`), then
  up to 3 member users at a time for 24 h each. The first `accept` wins.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.{Groups, Messaging, MLS, Presence, RateLimiter, Repo, Social}
  alias RisiMe.Devices.Device
  alias RisiMe.History.{Candidate, Request}
  alias RisiMe.MLS.Wire
  alias RisiMe.Workers.HistoryTimer

  @open ~w(searching waiting_for_member refresh accepted receiving)
  @delivering ~w(accepted receiving)
  @own_phase_s 600
  @member_window_s 86_400
  @delivery_s 1_800
  @expiry_s 172_800
  @keep_days 8
  @window_days 30
  @future_s 300
  @max_ciphertext 24 * 1024
  @max_parts 20
  @member_slots 3
  @per_conv_day 3
  @per_user_day 30
  @member_per_requester_week 2
  @member_per_day 10
  @burst_limit 60
  @burst_window :timer.minutes(1)
  @capability "history_share"

  def open_states, do: @open
  def max_parts, do: @max_parts

  ## Pushes (§17.4)

  @doc """
  Handles a `history:*` push from `user_id`'s socket (`device_id`). `{:ok, reply}`,
  `{:error, reason}` or `{:error, :request_open, request_id}`.
  """
  def push(event, user_id, device_id, payload) when is_map(payload) do
    case RateLimiter.hit(:history_push, user_id, @burst_limit, @burst_window) do
      :ok -> do_push(event, user_id, device_id, payload)
      error -> error
    end
  end

  defp do_push("history:request", u, d, p), do: request(u, d, p)
  defp do_push("history:refresh", u, d, p), do: refresh(u, d, p)
  defp do_push("history:respond", u, d, p), do: respond(u, d, p)
  defp do_push("history:deliver", u, d, p), do: deliver(u, d, p)
  defp do_push("history:ack", u, d, p), do: ack(u, d, p)
  defp do_push("history:escalate", u, d, p), do: escalate(u, d, p)
  defp do_push("history:cancel", u, d, p), do: cancel(u, d, p)
  defp do_push(_event, _u, _d, _p), do: {:error, :bad_request}

  ## history:request

  @doc "`history:request` (§17.4): idempotent by `request_id` for the same device."
  def request(user_id, device_id, p) do
    with {:ok, r} <- parse_request(p) do
      case Repo.get(Request, r.request_id) do
        %Request{requester_device: ^device_id, requester_user: ^user_id} = req ->
          {:ok, reply(req)}

        %Request{} ->
          {:error, :bad_request}

        nil ->
          create(user_id, device_id, r)
      end
    end
  end

  defp parse_request(p) do
    with {:ok, rid} <- uuid(p["request_id"]),
         {:ok, conv} <- conversation(p["conversation_id"]),
         true <- p["sources"] in ["own", "any"] || {:error, :bad_request},
         {:ok, from, to} <- range(p["range"]),
         true <- (is_integer(p["gap_count"]) and p["gap_count"] >= 0) || {:error, :bad_request},
         true <-
           (is_binary(p["ciphertext"]) and is_integer(p["generation"]) and
              is_integer(p["epoch"])) || {:error, :bad_request} do
      {:ok,
       %{
         request_id: rid,
         conv: conv,
         sources: p["sources"],
         from: from,
         to: to,
         gap_count: p["gap_count"],
         ciphertext: p["ciphertext"],
         generation: p["generation"],
         epoch: p["epoch"]
       }}
    end
  end

  defp create(user_id, device_id, r) do
    now = now()

    with :ok <- membership(user_id, r.conv, r.sources),
         {:ok, g} <- e2ee(r.conv),
         :ok <- requester_device(r.conv, user_id, device_id),
         {:ok, bin} <- check_ciphertext(r, r.conv, r.request_id, g),
         {:ok, from, to} <- clip_range(r.from, r.to, now),
         r = %{r | from: from, to: to},
         [_ | _] <- requester_intervals(r.conv, user_id, {from, to}, g, now) do
      Repo.transaction(fn ->
        Repo.query!("SELECT pg_advisory_xact_lock(hashtext($1))", ["hist_user:" <> user_id])

        case Repo.get(Request, r.request_id) do
          %Request{requester_device: ^device_id, requester_user: ^user_id} = req ->
            {:ok, reply(req)}

          %Request{} ->
            {:error, :bad_request}

          nil ->
            with :ok <- no_open(device_id, r.conv),
                 :ok <- day_limits(user_id, r.conv, now) do
              req = insert_request(user_id, device_id, r, bin, g, now)

              Repo.query!("SELECT pg_advisory_xact_lock(hashtext($1))", [
                "hist:" <> req.request_id
              ])

              req = start(req, now)
              {:ok, reply(req)}
            end
        end
      end)
      |> unwrap()
    else
      [] -> {:error, :bad_request}
      error -> error
    end
  end

  defp unwrap({:ok, result}), do: result

  defp insert_request(user_id, device_id, r, bin, g, now) do
    Repo.insert!(%Request{
      request_id: r.request_id,
      requester_user: user_id,
      requester_device: device_id,
      conversation_id: r.conv,
      generation: g.generation,
      range_from: r.from,
      range_to: r.to,
      gap_count: r.gap_count,
      sources: r.sources,
      state: "searching",
      phase: "own",
      request_ciphertext: bin,
      request_epoch: r.epoch,
      created_at: now,
      expires_at: DateTime.add(now, @expiry_s, :second)
    })
  end

  defp no_open(device_id, conv) do
    case Repo.one(
           from r in Request,
             where:
               r.requester_device == ^device_id and r.conversation_id == ^conv and
                 r.state in @open,
             select: r.request_id,
             limit: 1
         ) do
      nil -> :ok
      id -> {:error, :request_open, id}
    end
  end

  # §17.10: counted from Postgres rows (a restart never forgets them).
  defp day_limits(user_id, conv, now) do
    since = DateTime.add(now, -1, :day)

    total =
      Repo.one(
        from r in Request,
          where: r.requester_user == ^user_id and r.created_at > ^since,
          select: count()
      )

    in_conv =
      Repo.one(
        from r in Request,
          where:
            r.requester_user == ^user_id and r.conversation_id == ^conv and
              r.created_at > ^since,
          select: count()
      )

    if total >= @per_user_day or in_conv >= @per_conv_day,
      do: {:error, :rate_limited},
      else: :ok
  end

  # Own candidates named at once (dormant ones on their inbox join); member phase at once when
  # there is no own candidate. Timers: own phase end and the 48 h expiry.
  defp start(req, now) do
    own = own_candidates(req)

    rows =
      for c <- own do
        Repo.insert!(%Candidate{
          request_id: req.request_id,
          device_id: c.device_id,
          user_id: req.requester_user,
          phase: "own",
          wait: if(c.dormant, do: "join")
        })
      end

    schedule(req.request_id, "expire", req.expires_at)

    if req.sources == "any" and own != [],
      do: schedule(req.request_id, "own_end", DateTime.add(now, @own_phase_s, :second))

    awake = Enum.filter(rows, &is_nil(&1.wait))
    req = if awake != [], do: name_rows(req, req.requester_user, awake, now), else: req

    req =
      if own == [] and req.sources == "any",
        do: update!(req, phase: "member"),
        else: req

    advance(req, now)
  end

  defp reply(req) do
    own =
      Repo.all(
        from c in Candidate,
          where: c.request_id == ^req.request_id and c.phase == "own",
          select: c.device_id
      )

    %{
      request_id: req.request_id,
      state: req.state,
      own_devices: own_devices(req.requester_user, own)
    }
  end

  # §17.4 `own_devices`: names from the device rows (the sign-in's device name), last seen from
  # the census or the PUT, whichever is later.
  defp own_devices(_user_id, []), do: []

  defp own_devices(user_id, device_ids) do
    Repo.all(
      from d in Device,
        left_join: t in RisiMe.Accounts.UserToken,
        on: t.id == d.user_token_id,
        left_join: i in "app_instances",
        on:
          i.user_id == d.user_id and
            i.instance_key == fragment("'device:' || ?::text", d.device_id),
        where: d.user_id == ^user_id and d.device_id in ^device_ids,
        order_by: [asc: d.inserted_at],
        select: %{
          device_id: d.device_id,
          device_name: t.device_name,
          put_seen: d.last_seen_at,
          census_seen: type(i.last_seen_at, :utc_datetime_usec)
        }
    )
    |> Enum.map(fn d ->
      %{
        device_id: d.device_id,
        device_name: d.device_name,
        last_seen_at: Messaging.iso(later(d.census_seen, d.put_seen)),
        online: Presence.device_online?(d.device_id)
      }
    end)
  end

  ## history:refresh

  @doc "`history:refresh` (§17.4): a new request ciphertext at the current epoch."
  def refresh(user_id, device_id, p) do
    with {:ok, rid} <- uuid(p["request_id"]),
         true <-
           (is_binary(p["ciphertext"]) and is_integer(p["generation"]) and
              is_integer(p["epoch"])) || {:error, :bad_request} do
      transition(rid, fn req, now ->
        with :ok <- requester?(req, user_id, device_id),
             true <- req.state in @open || {:error, :gone},
             {:ok, g} <- current(req),
             {:ok, bin} <- check_ciphertext(p, req.conversation_id, rid, g) do
          req = update!(req, request_ciphertext: bin, request_epoch: p["epoch"])

          waiting =
            Repo.all(
              from c in Candidate,
                where: c.request_id == ^rid and c.wait == "refresh" and is_nil(c.answer)
            )

          req =
            waiting
            |> Enum.group_by(& &1.user_id)
            |> Enum.reduce(req, fn {u, rows}, acc -> name_rows(acc, u, rows, now) end)

          _ = advance(req, now)
          {:ok, %{}}
        end
      end)
    end
  end

  ## history:respond

  @doc "`history:respond` (§17.4) from a named device."
  def respond(user_id, device_id, p) do
    decision = p["decision"]
    reason = p["reason"]

    with {:ok, rid} <- uuid(p["request_id"]),
         true <- decision in ~w(accept decline unable) || {:error, :bad_request},
         true <- reason in [nil, "stale", "no_data"] || {:error, :bad_request},
         true <- (decision == "unable" or reason == nil) || {:error, :bad_request} do
      transition(rid, fn req, now ->
        row = device_id && Repo.get_by(Candidate, request_id: rid, device_id: device_id)

        cond do
          req.state not in @open or row == nil or row.user_id != user_id ->
            {:error, :gone}

          decision == "accept" and req.provider_device == device_id ->
            {:ok, %{}}

          row.named_at == nil ->
            {:error, :gone}

          row.answer != nil ->
            if row.answer == decision, do: {:ok, %{}}, else: {:error, :gone}

          decision == "accept" and req.state in @delivering ->
            {:error, :gone}

          decision == "accept" ->
            _ = accept(req, row, now)
            {:ok, %{}}

          reason == "stale" ->
            # A stale answer is not a decline: named again after `history:refresh`.
            set_rows([row], wait: "refresh", named_at: nil, named_until: nil, answered_at: now)
            _ = advance(req, now)
            {:ok, %{}}

          true ->
            set_rows([row], answer: decision, answered_at: now)
            _ = advance(req, now)
            {:ok, %{}}
        end
      end)
    end
  end

  defp accept(req, row, now) do
    set_rows([row], answer: "accept", answered_at: now)

    others =
      Repo.all(
        from c in Candidate,
          where: c.request_id == ^req.request_id and is_nil(c.answer) and not is_nil(c.named_at)
      )

    set_rows(others, answer: "elsewhere", answered_at: now)

    for u <- others |> Enum.map(& &1.user_id) |> Enum.uniq(),
        u != row.user_id,
        do: closed_event(req, u, "accepted_elsewhere", now)

    req =
      update!(req,
        state: "accepted",
        provider_user: row.user_id,
        provider_device: row.device_id,
        accepted_at: now,
        last_part_at: nil,
        parts: nil,
        parts_delivered: [],
        parts_acked: []
      )

    status_event(req, now)
    schedule(req.request_id, "delivery", DateTime.add(now, @delivery_s, :second))
    req
  end

  ## history:deliver

  @doc "`history:deliver` (§17.4) from the accepted provider device."
  def deliver(user_id, device_id, p) do
    with {:ok, rid} <- uuid(p["request_id"]) do
      transition(rid, fn req, now ->
        with :ok <- provider?(req, user_id, device_id),
             {:ok, part, parts} <- parts(p, req),
             true <-
               (is_binary(p["ciphertext"]) and is_integer(p["generation"]) and
                  is_integer(p["epoch"])) || {:error, :bad_request},
             {:ok, g} <- current(req),
             {:ok, _bin} <- check_ciphertext(p, req.conversation_id, rid, g) do
          if part in req.parts_delivered do
            {:ok, %{}}
          else
            req =
              update!(req,
                parts: parts,
                parts_delivered: Enum.sort([part | req.parts_delivered]),
                last_part_at: now,
                state: "receiving"
              )

            if req.parts_delivered == [part], do: status_event(req, now)

            Messaging.publish_history(req.requester_user, "history_share", %{
              "request_id" => rid,
              "conversation_id" => req.conversation_id,
              "from" => user_id,
              "from_device" => device_id,
              "ciphertext" => p["ciphertext"],
              "generation" => p["generation"],
              "epoch" => p["epoch"],
              "part" => part,
              "parts" => parts,
              "to_devices" => [req.requester_device],
              "server_ts" => Messaging.iso(now)
            })

            schedule(rid, "delivery", DateTime.add(now, @delivery_s, :second))
            {:ok, %{}}
          end
        end
      end)
    end
  end

  defp parts(%{"part" => part, "parts" => parts}, req)
       when is_integer(part) and is_integer(parts) do
    cond do
      not (1 <= part and part <= parts and parts <= @max_parts) -> {:error, :bad_request}
      req.parts != nil and req.parts != parts -> {:error, :bad_request}
      true -> {:ok, part, parts}
    end
  end

  defp parts(_p, _req), do: {:error, :bad_request}

  ## history:ack

  @doc "`history:ack` (§17.4) from the requesting device; every part acked → `done`."
  def ack(user_id, device_id, p) do
    with {:ok, rid} <- uuid(p["request_id"]),
         true <- is_integer(p["part"]) || {:error, :bad_request},
         true <- p["result"] in ["imported", "rejected"] || {:error, :bad_request} do
      transition(rid, fn req, now ->
        with :ok <- requester?(req, user_id, device_id) do
          cond do
            req.state == "done" ->
              {:ok, %{}}

            req.state not in @delivering ->
              {:error, :gone}

            p["part"] not in req.parts_delivered ->
              {:error, :bad_request}

            true ->
              acked = Enum.sort(Enum.uniq([p["part"] | req.parts_acked]))
              req = update!(req, parts_acked: acked)

              if req.parts != nil and acked == Enum.to_list(1..req.parts),
                do: close(req, "done", now)

              {:ok, %{}}
          end
        end
      end)
    end
  end

  ## history:escalate and history:cancel

  @doc "`history:escalate` (§17.4): ends the own phase now."
  def escalate(user_id, device_id, p) do
    with {:ok, rid} <- uuid(p["request_id"]) do
      transition(rid, fn req, now ->
        with :ok <- requester?(req, user_id, device_id) do
          cond do
            req.sources == "own" ->
              {:error, :bad_request}

            req.state not in @open ->
              {:error, :gone}

            true ->
              req = if req.phase == "own", do: update!(req, phase: "member"), else: req
              _ = advance(req, now)
              {:ok, %{}}
          end
        end
      end)
    end
  end

  @doc "`history:cancel` (§17.4): closes the request everywhere (`cancelled`)."
  def cancel(user_id, device_id, p) do
    with {:ok, rid} <- uuid(p["request_id"]) do
      transition(rid, fn req, now ->
        with :ok <- requester?(req, user_id, device_id) do
          if req.state in @open, do: close(req, "cancelled", now)
          {:ok, %{}}
        end
      end)
    end
  end

  ## Timers (HistoryTimer)

  @doc "Runs one timer job. A job that is no longer current is a no-op."
  def timer("own_end", rid, _args) do
    transition(rid, fn req, now ->
      if req.state in @open and req.phase == "own" and req.sources == "any" do
        req = update!(req, phase: "member")
        _ = advance(req, now)
      end

      {:ok, :ok}
    end)

    :ok
  end

  def timer("member_window", rid, %{"user_id" => user_id}) do
    transition(rid, fn req, now ->
      rows =
        Repo.all(
          from c in Candidate,
            where:
              c.request_id == ^rid and c.user_id == ^user_id and is_nil(c.answer) and
                c.phase == "member" and c.named_until <= ^now
        )

      if req.state in @open and rows != [] do
        set_rows(rows, answer: "expired", answered_at: now)
        closed_event(req, user_id, "expired", now)
        _ = advance(req, now)
      end

      {:ok, :ok}
    end)

    :ok
  end

  def timer("delivery", rid, _args) do
    transition(rid, fn req, now ->
      last = req.last_part_at || req.accepted_at

      if req.state in @delivering and last != nil and
           DateTime.diff(now, last, :second) >= @delivery_s do
        Logger.info("history: provider dropped after the delivery deadline")
        _ = drop_provider(req, now)
      end

      {:ok, :ok}
    end)

    :ok
  end

  def timer("expire", rid, _args) do
    transition(rid, fn req, now ->
      if req.state in @open and DateTime.compare(now, req.expires_at) != :lt,
        do: close(req, "expired", now)

      {:ok, :ok}
    end)

    :ok
  end

  @doc "The daily prune (§17.11): rows are kept 8 days after creation. Returns the count."
  def prune(now \\ DateTime.utc_now()) do
    cutoff = DateTime.add(now, -@keep_days, :day)
    {n, _} = Repo.delete_all(from r in Request, where: r.created_at < ^cutoff)
    if n > 0, do: Logger.info("history: pruned #{n} request(s)")
    n
  end

  ## Hooks

  @doc "A device's inbox joined: name it for open requests that wait for it (dormant own)."
  def device_joined(user_id, device_id) do
    rids =
      Repo.all(
        from c in Candidate,
          join: r in Request,
          on: r.request_id == c.request_id,
          where:
            c.device_id == ^device_id and c.user_id == ^user_id and c.wait == "join" and
              is_nil(c.answer) and r.state in @open,
          select: r.request_id
      )

    for rid <- rids do
      transition(rid, fn req, now ->
        row = Repo.get_by(Candidate, request_id: rid, device_id: device_id)

        if req.state in @open and req.state not in @delivering and row != nil and
             row.wait == "join" and row.answer == nil do
          req = name_rows(req, user_id, [row], now)
          _ = advance(req, now)
        end

        {:ok, :ok}
      end)
    end

    :ok
  end

  @doc """
  A device was removed, lost `history_share` or changed its key: its open requests are
  cancelled and it is un-named at once.
  """
  def device_gone(user_id, device_id) do
    for rid <-
          open_ids(
            from r in Request,
              where: r.requester_user == ^user_id and r.requester_device == ^device_id
          ),
        do: close_open(rid, "cancelled")

    for rid <-
          named_ids(
            from c in Candidate, where: c.user_id == ^user_id and c.device_id == ^device_id
          ),
        do: unname(rid, [device_id])

    :ok
  end

  @doc """
  A user stopped being an active member of a group (left or removed): their requests in it are
  cancelled and their devices un-named.
  """
  def member_gone(conv, user_id) do
    for rid <-
          open_ids(
            from r in Request,
              where: r.requester_user == ^user_id and r.conversation_id == ^conv
          ),
        do: close_open(rid, "cancelled")

    rids =
      Repo.all(
        from c in Candidate,
          join: r in Request,
          on: r.request_id == c.request_id,
          where:
            c.user_id == ^user_id and r.conversation_id == ^conv and r.state in @open and
              (is_nil(c.answer) or c.answer in ["accept", "elsewhere"]),
          distinct: true,
          select: r.request_id
      )

    for rid <- rids do
      devices =
        Repo.all(
          from c in Candidate,
            where: c.request_id == ^rid and c.user_id == ^user_id,
            select: c.device_id
        )

      unname(rid, devices)
    end

    :ok
  end

  @doc "A §12.8 reset (new generation): every open request of the conversation is `expired`."
  def conversation_reset(conv) do
    for rid <- open_ids(from(r in Request, where: r.conversation_id == ^conv)),
        do: close_open(rid, "expired")

    :ok
  end

  @doc "A `chat:clear` by the requester's user (§15.9): its open requests there are `cancelled`."
  def chat_cleared(user_id, conv) do
    for rid <-
          open_ids(
            from r in Request,
              where: r.requester_user == ^user_id and r.conversation_id == ^conv
          ),
        do: close_open(rid, "cancelled")

    :ok
  end

  defp open_ids(query),
    do: Repo.all(from(r in query, where: r.state in @open, select: r.request_id))

  defp named_ids(query) do
    Repo.all(
      from c in query,
        join: r in Request,
        on: r.request_id == c.request_id,
        where: r.state in @open and (is_nil(c.answer) or c.answer in ["accept", "elsewhere"]),
        select: r.request_id
    )
  end

  defp close_open(rid, state) do
    transition(rid, fn req, now ->
      if req.state in @open, do: close(req, state, now)
      {:ok, :ok}
    end)
  end

  defp unname(rid, device_ids) do
    transition(rid, fn req, now ->
      if req.state in @open do
        rows =
          Repo.all(
            from c in Candidate,
              where:
                c.request_id == ^rid and c.device_id in ^device_ids and
                  (is_nil(c.answer) or c.answer == "elsewhere")
          )

        set_rows(rows, answer: "dropped", answered_at: now)

        if req.state in @delivering and req.provider_device in device_ids,
          do: drop_provider(req, now),
          else: advance(req, now)
      end

      {:ok, :ok}
    end)
  end

  ## Blobs (§17.9)

  @doc "True if `device_id` of `user_id` is the accepted provider of an open request of `conv`."
  def upload_allowed?(user_id, device_id, conv, request_id) do
    with {:ok, rid} <- uuid(request_id),
         {:ok, dev} <- uuid(device_id),
         %Request{} = req <- Repo.get(Request, rid) do
      req.conversation_id == conv and req.state in @delivering and
        req.provider_user == user_id and req.provider_device == dev
    else
      _ -> false
    end
  end

  @doc "True if `user_id` may read the request's `history` blobs (the requester, open or done)."
  def blob_reader?(_user_id, nil), do: false

  def blob_reader?(user_id, request_id) do
    case Repo.get(Request, request_id) do
      %Request{requester_user: ^user_id, state: s} -> s in @open or s == "done"
      _ -> false
    end
  end

  ## Naming

  # Names `rows` (all of one user): the event carries the stored request ciphertext, unless it
  # is more than 2 epochs behind the group (then the request goes to `refresh`).
  defp name_rows(req, user_id, rows, now) do
    g = MLS.group(req.conversation_id)

    cond do
      g == nil or g.generation != req.generation ->
        req

      g.epoch - req.request_epoch > 2 or req.request_ciphertext == nil ->
        set_rows(rows, wait: "refresh", named_at: nil, named_until: nil)
        req

      true ->
        member? = user_id != req.requester_user

        until =
          if member?,
            do: earlier(DateTime.add(now, @member_window_s, :second), req.expires_at),
            else: nil

        set_rows(rows, wait: nil, named_at: now, named_until: until, answer: nil)

        Messaging.publish_history(user_id, "history_request", %{
          "request_id" => req.request_id,
          "conversation_id" => req.conversation_id,
          "from" => req.requester_user,
          "from_device" => req.requester_device,
          "range" => %{
            "from" => Messaging.iso(req.range_from),
            "to" => Messaging.iso(req.range_to)
          },
          "intervals" => Enum.map(candidate_intervals(req, user_id, now), &interval_json/1),
          "gap_count" => req.gap_count,
          "ciphertext" => Base.encode64(req.request_ciphertext),
          "generation" => req.generation,
          "epoch" => req.request_epoch,
          "consent" => if(member?, do: "member", else: "own"),
          "expires_at" => Messaging.iso(until || req.expires_at),
          "to_devices" => Enum.map(rows, & &1.device_id),
          "server_ts" => Messaging.iso(now)
        })

        if member?,
          do: schedule(req.request_id, "member_window", until, %{"user_id" => user_id})

        req
    end
  end

  # After every change of an open, not yet accepted request: end the own phase when every own
  # candidate answered, fill member slots, and close as `unavailable` when nobody is left.
  defp advance(%Request{state: s} = req, now) when s in @open and s not in @delivering do
    rows = Repo.all(from c in Candidate, where: c.request_id == ^req.request_id)
    own_open? = Enum.any?(rows, &(&1.phase == "own" and &1.answer == nil))

    req =
      if req.phase == "own" and req.sources == "any" and not own_open?,
        do: update!(req, phase: "member"),
        else: req

    req =
      if req.phase == "member" and req.sources == "any",
        do: fill_members(req, rows, now),
        else: req

    rows = Repo.all(from c in Candidate, where: c.request_id == ^req.request_id)
    open = Enum.filter(rows, &(&1.answer == nil))

    cond do
      open == [] ->
        close(req, "unavailable", now)

      Enum.any?(open, &(&1.wait == "refresh")) ->
        set_state(req, "refresh", now)

      req.phase == "own" ->
        set_state(req, "searching", now)

      true ->
        set_state(req, "waiting_for_member", now)
    end
  end

  defp advance(req, _now), do: req

  defp fill_members(req, rows, now) do
    active =
      rows
      |> Enum.filter(&(&1.phase == "member" and &1.answer == nil))
      |> Enum.map(& &1.user_id)
      |> Enum.uniq()

    slots = @member_slots - length(active)

    if slots > 0 do
      asked = MapSet.new(rows, & &1.user_id)

      req
      |> member_pool(now)
      |> Enum.reject(&MapSet.member?(asked, &1.user_id))
      |> Enum.filter(&member_allowed?(req, &1.user_id, now))
      |> Enum.take(slots)
      |> Enum.reduce(req, fn c, acc ->
        new =
          for d <- c.devices do
            Repo.insert!(%Candidate{
              request_id: acc.request_id,
              device_id: d,
              user_id: c.user_id,
              phase: "member"
            })
          end

        name_rows(acc, c.user_id, new, now)
      end)
    else
      req
    end
  end

  # §17.10: at most twice per requester and conversation in 7 days, never within 24 h of a
  # decline of that requester, at most 10 times a day across all requesters.
  defp member_allowed?(req, user_id, now) do
    week = DateTime.add(now, -7, :day)
    day = DateTime.add(now, -1, :day)

    by_requester =
      Repo.one(
        from c in Candidate,
          join: r in Request,
          on: r.request_id == c.request_id,
          where:
            c.user_id == ^user_id and c.phase == "member" and c.named_at > ^week and
              r.requester_user == ^req.requester_user and
              r.conversation_id == ^req.conversation_id,
          select: count(c.request_id, :distinct)
      )

    declined? =
      Repo.exists?(
        from c in Candidate,
          join: r in Request,
          on: r.request_id == c.request_id,
          where:
            c.user_id == ^user_id and c.named_at > ^week and c.answer == "decline" and
              c.answered_at > ^day and r.requester_user == ^req.requester_user
      )

    today =
      Repo.one(
        from c in Candidate,
          where: c.user_id == ^user_id and c.phase == "member" and c.named_at > ^day,
          select: count(c.request_id, :distinct)
      )

    by_requester < @member_per_requester_week and not declined? and today < @member_per_day
  end

  # Own candidates: the requester user's other devices in the group with the capability. The
  # §12.1 superseded filter doesn't apply: a superseded, offline device is dormant (named when
  # its inbox joins).
  defp own_candidates(req) do
    devices = candidate_devices(req, [req.requester_user])
    superseded = MLS.superseded_devices([req.requester_user])

    for d <- devices, d.device_id != req.requester_device do
      %{
        device_id: d.device_id,
        dormant:
          MapSet.member?(superseded, {d.user_id, d.device_id}) and
            not Presence.device_online?(d.device_id)
      }
    end
  end

  # Member candidates (`sources: "any"`): other active members (a DM: the partner while friends
  # and not blocked) whose intervals overlap the requester's, with non-superseded capable
  # devices; by the largest overlap, the earliest first_seen_at, the most recently seen.
  defp member_pool(req, now) do
    users =
      case req.conversation_id do
        "grp:" <> _ = conv ->
          Groups.active_member_ids(conv) -- [req.requester_user]

        conv ->
          {:ok, members} = MLS.members(conv)
          [other] = members -- [req.requester_user]

          if Social.friends?(req.requester_user, other) and
               not Social.blocked_between?(req.requester_user, other),
             do: [other],
             else: []
      end

    devices = candidate_devices(req, users)
    superseded = MLS.superseded_devices(users)

    devices
    |> Enum.reject(&MapSet.member?(superseded, {&1.user_id, &1.device_id}))
    |> Enum.group_by(& &1.user_id)
    |> Enum.flat_map(fn {u, ds} ->
      case candidate_intervals(req, u, now) do
        [] ->
          []

        overlap ->
          [
            %{
              user_id: u,
              devices: Enum.map(ds, & &1.device_id),
              overlap:
                Enum.sum(Enum.map(overlap, fn {a, b} -> DateTime.diff(b, a, :millisecond) end)),
              first_seen:
                ds
                |> Enum.map(& &1.first_seen)
                |> Enum.reject(&is_nil/1)
                |> Enum.min(DateTime, fn -> nil end),
              seen: ds |> Enum.map(& &1.seen) |> Enum.max(DateTime)
            }
          ]
      end
    end)
    |> Enum.sort_by(fn c ->
      {-c.overlap, if(c.first_seen, do: DateTime.to_unix(c.first_seen, :microsecond), else: 0),
       -DateTime.to_unix(c.seen, :microsecond)}
    end)
  end

  # Devices of `users` in the group (current generation) with a key and `history_share`, whose
  # census first_seen_at isn't after `range.to` (they would hold nothing asked for).
  defp candidate_devices(_req, []), do: []

  defp candidate_devices(req, users) do
    Repo.all(
      from gd in "mls_group_devices",
        join: d in Device,
        on: d.device_id == gd.device_id and d.user_id == gd.user_id,
        left_join: i in "app_instances",
        on:
          i.user_id == gd.user_id and
            i.instance_key == fragment("'device:' || ?::text", gd.device_id),
        where:
          gd.conversation_id == ^req.conversation_id and
            gd.user_id in type(^users, {:array, :binary_id}) and
            not is_nil(d.mls_signature_key) and ^@capability in d.capabilities,
        select: %{
          user_id: d.user_id,
          device_id: d.device_id,
          first_seen: type(i.first_seen_at, :utc_datetime_usec),
          census_seen: type(i.last_seen_at, :utc_datetime_usec),
          put_seen: d.last_seen_at
        }
    )
    |> Enum.filter(
      &(&1.first_seen == nil or DateTime.compare(&1.first_seen, req.range_to) != :gt)
    )
    |> Enum.map(&Map.put(&1, :seen, later(&1.census_seen, &1.put_seen)))
  end

  ## Intervals (§17.4)

  # The requester's intervals clipped to the range; for a candidate user also to that user's own
  # intervals (a candidate gets only what it may share).
  defp candidate_intervals(req, user_id, now) do
    mine = requester_intervals(req.conversation_id, req.requester_user, req_range(req), nil, now)

    if user_id == req.requester_user or not String.starts_with?(req.conversation_id, "grp:"),
      do: mine,
      else: overlap_intervals(mine, user_intervals(req.conversation_id, user_id, now))
  end

  defp req_range(req), do: {req.range_from, req.range_to}

  defp requester_intervals("grp:" <> _ = conv, user_id, {from, to}, _g, now),
    do: overlap_intervals(user_intervals(conv, user_id, now), [{from, to}])

  defp requester_intervals(conv, _user_id, {from, to}, g, now) do
    g = g || MLS.group(conv)

    since =
      later(g && g.e2ee_since && to_utc(g.e2ee_since), DateTime.add(now, -@window_days, :day))

    overlap_intervals([{since, now}], [{from, to}])
  end

  defp user_intervals(conv, user_id, now) do
    Repo.all(
      from i in "group_member_intervals",
        where: i.group_id == ^conv and i.user_id == type(^user_id, :binary_id),
        order_by: [asc: i.active_from],
        select:
          {type(i.active_from, :utc_datetime_usec), type(i.active_until, :utc_datetime_usec)}
    )
    |> Enum.map(fn {a, b} -> {a, b || now} end)
  end

  @doc false
  def overlap_intervals(xs, ys) do
    for {a1, b1} <- xs,
        {a2, b2} <- ys,
        a = later(a1, a2),
        b = earlier(b1, b2),
        DateTime.compare(a, b) != :gt,
        do: {a, b}
  end

  defp interval_json({a, b}), do: %{"from" => Messaging.iso(a), "to" => Messaging.iso(b)}

  ## Close and events

  defp close(req, state, now) do
    req = update!(req, state: state, closed_at: now, request_ciphertext: nil)
    status_event(req, now)

    reason =
      case state do
        "done" -> "done"
        "cancelled" -> "cancelled"
        _ -> "expired"
      end

    named =
      Repo.all(
        from c in Candidate,
          where:
            c.request_id == ^req.request_id and
              (not is_nil(c.named_at) or not is_nil(c.answer)),
          distinct: true,
          select: c.user_id
      )

    for u <- named, do: closed_event(req, u, reason, now)

    # §17.9: deleted 1 h after the last ack; at once on any other close.
    at = if state == "done", do: DateTime.add(now, 3600, :second), else: now
    RisiMe.Blobs.expire_request(req.request_id, at)
    req
  end

  defp set_state(%Request{state: s} = req, s, _now), do: req

  defp set_state(req, state, now) do
    req = update!(req, state: state)
    status_event(req, now)
    req
  end

  # `history_status` to the requesting device: `provider` from `accepted` on (a display hint);
  # never who declined or who is being asked.
  defp status_event(req, now) do
    provider =
      if req.state in ["accepted", "receiving", "done"] and req.provider_device,
        do: %{"user_id" => req.provider_user, "device_id" => req.provider_device}

    Messaging.publish_history(req.requester_user, "history_status", %{
      "request_id" => req.request_id,
      "conversation_id" => req.conversation_id,
      "state" => req.state,
      "provider" => provider,
      "to_devices" => [req.requester_device],
      "server_ts" => Messaging.iso(now)
    })
  end

  defp closed_event(req, user_id, reason, now) do
    Messaging.publish_history(user_id, "history_request_closed", %{
      "request_id" => req.request_id,
      "conversation_id" => req.conversation_id,
      "reason" => reason,
      "server_ts" => Messaging.iso(now)
    })
  end

  # The accepted provider went silent (or was un-named): it is dropped (`expired` to it, unless
  # it is the requester's own user, whose devices see `history_status`), and naming resumes
  # without it: devices told `accepted_elsewhere` are named again.
  defp drop_provider(req, now) do
    rows =
      Repo.all(
        from c in Candidate,
          where: c.request_id == ^req.request_id and c.device_id == ^req.provider_device
      )

    set_rows(Enum.filter(rows, &(&1.answer in [nil, "accept"])),
      answer: "expired",
      answered_at: now
    )

    if req.provider_user != req.requester_user,
      do: closed_event(req, req.provider_user, "expired", now)

    req =
      update!(req,
        state: if(req.phase == "own", do: "searching", else: "waiting_for_member"),
        provider_user: nil,
        provider_device: nil,
        accepted_at: nil,
        last_part_at: nil,
        parts: nil,
        parts_delivered: [],
        parts_acked: []
      )

    status_event(req, now)

    again =
      Repo.all(
        from c in Candidate, where: c.request_id == ^req.request_id and c.answer == "elsewhere"
      )

    req =
      again
      |> Enum.group_by(& &1.user_id)
      |> Enum.reduce(req, fn {u, rs}, acc -> name_rows(acc, u, rs, now) end)

    advance(req, now)
  end

  ## Checks

  defp membership(user_id, "grp:" <> _ = conv, _sources) do
    if Groups.active_member?(conv, user_id), do: :ok, else: {:error, :not_member}
  end

  # A DM: a participant; `any` also needs friends and no block (`not_member`, so a block isn't
  # revealed). `own` doesn't involve the partner.
  defp membership(user_id, conv, sources) do
    {:ok, members} = MLS.members(conv)

    cond do
      user_id not in members ->
        {:error, :not_member}

      sources == "own" ->
        :ok

      true ->
        [other] = members -- [user_id]

        if Social.friends?(user_id, other) and not Social.blocked_between?(user_id, other),
          do: :ok,
          else: {:error, :not_member}
    end
  end

  defp e2ee(conv) do
    case MLS.group(conv) do
      nil ->
        if String.starts_with?(conv, "grp:"), do: {:error, :not_member}, else: {:error, :not_e2ee}

      g ->
        {:ok, g}
    end
  end

  defp current(req) do
    case MLS.group(req.conversation_id) do
      %{generation: gen} = g when gen == req.generation -> {:ok, g}
      _ -> {:error, :stale_epoch}
    end
  end

  # The calling device is in the group at the current generation (`not_member`) and advertises
  # `history_share` (`bad_request`).
  defp requester_device(_conv, _user_id, nil), do: {:error, :not_member}

  defp requester_device(conv, user_id, device_id) do
    in_group? =
      Repo.exists?(
        from gd in "mls_group_devices",
          where:
            gd.conversation_id == ^conv and gd.user_id == type(^user_id, :binary_id) and
              gd.device_id == type(^device_id, :binary_id)
      )

    cond do
      not in_group? -> {:error, :not_member}
      capable?(user_id, device_id) -> :ok
      true -> {:error, :bad_request}
    end
  end

  defp capable?(user_id, device_id) do
    Repo.exists?(
      from d in Device,
        where:
          d.user_id == ^user_id and d.device_id == ^device_id and
            not is_nil(d.mls_signature_key) and ^@capability in d.capabilities
    )
  end

  # §17.4: a PrivateMessage, application content, group id and epoch equal to the JSON fields,
  # the current generation and epoch (`stale_epoch`), at most 24 KiB (`too_long`), and the 'H'
  # AAD equal to `request_id` (§17.3; `bad_request`).
  defp check_ciphertext(p, conv, rid, g) do
    {ct, gen, epoch} = ciphertext_fields(p)

    with {:ok, bin} <- decode64(ct),
         true <- byte_size(bin) <= @max_ciphertext || {:error, :too_long},
         true <- (gen == g.generation and epoch == g.epoch) || {:error, :stale_epoch},
         {:ok, h} <- Wire.private_message(bin) |> ok_or(:bad_request),
         true <-
           (Wire.application?(h) and h.group_id == "#{conv}##{gen}" and h.epoch == epoch and
              Wire.history_aad?(h.authenticated_data, rid)) || {:error, :bad_request} do
      {:ok, bin}
    end
  end

  defp ciphertext_fields(%{ciphertext: ct, generation: g, epoch: e}), do: {ct, g, e}
  defp ciphertext_fields(p), do: {p["ciphertext"], p["generation"], p["epoch"]}

  defp decode64(ct) when is_binary(ct) do
    case Base.decode64(ct) do
      {:ok, bin} when byte_size(bin) > 0 -> {:ok, bin}
      _ -> {:error, :bad_request}
    end
  end

  defp decode64(_), do: {:error, :bad_request}

  defp ok_or({:ok, v}, _reason), do: {:ok, v}
  defp ok_or(_, reason), do: {:error, reason}

  defp requester?(req, user_id, device_id) do
    if req.requester_user == user_id and req.requester_device == device_id,
      do: :ok,
      else: {:error, :gone}
  end

  defp provider?(req, user_id, device_id) do
    if req.state in @delivering and req.provider_user == user_id and
         req.provider_device == device_id,
       do: :ok,
       else: {:error, :gone}
  end

  defp clip_range(from, to, now) do
    min = DateTime.add(now, -@window_days, :day)

    cond do
      DateTime.compare(to, min) == :lt -> {:error, :bad_request}
      DateTime.compare(from, to) == :gt -> {:error, :bad_request}
      DateTime.compare(to, DateTime.add(now, @future_s, :second)) == :gt -> {:error, :bad_request}
      true -> {:ok, later(from, min), to}
    end
  end

  defp range(%{"from" => from, "to" => to}) when is_binary(from) and is_binary(to) do
    with {:ok, f, _} <- DateTime.from_iso8601(from),
         {:ok, t, _} <- DateTime.from_iso8601(to) do
      {:ok, usec(f), usec(t)}
    else
      _ -> {:error, :bad_request}
    end
  end

  defp range(_), do: {:error, :bad_request}

  defp conversation(conv) when is_binary(conv) do
    if Groups.group_id?(conv) or MLS.members(conv) != :error,
      do: {:ok, conv},
      else: {:error, :bad_request}
  end

  defp conversation(_), do: {:error, :bad_request}

  defp uuid(id) when is_binary(id) do
    case Ecto.UUID.cast(id) do
      {:ok, u} -> {:ok, String.downcase(u)}
      :error -> {:error, :bad_request}
    end
  end

  defp uuid(_), do: {:error, :bad_request}

  ## Plumbing

  # Every transition of a request under its advisory lock. `fun.(request, now)` returns
  # `{:ok, value}` or `{:error, reason}`; errors are decided before any change, so nothing is
  # rolled back (hooks run inside other transactions).
  defp transition(rid, fun) do
    Repo.transaction(fn ->
      Repo.query!("SELECT pg_advisory_xact_lock(hashtext($1))", ["hist:" <> rid])

      case Repo.one(from r in Request, where: r.request_id == ^rid, lock: "FOR UPDATE") do
        nil -> {:error, :gone}
        req -> fun.(req, now())
      end
    end)
    |> unwrap()
  end

  defp update!(req, changes), do: req |> Ecto.Changeset.change(changes) |> Repo.update!()

  defp set_rows([], _changes), do: :ok

  defp set_rows(rows, changes) do
    for row <- rows do
      Repo.update_all(
        from(c in Candidate,
          where: c.request_id == ^row.request_id and c.device_id == ^row.device_id
        ),
        set: changes
      )
    end

    :ok
  end

  defp schedule(rid, kind, at, extra \\ %{}) do
    {:ok, _} =
      %{"kind" => kind, "request_id" => rid}
      |> Map.merge(extra)
      |> HistoryTimer.new(scheduled_at: at)
      |> Oban.insert()

    :ok
  end

  defp now, do: DateTime.utc_now()

  defp usec(%DateTime{microsecond: {us, _}} = dt), do: %{dt | microsecond: {us, 6}}

  defp later(nil, b), do: b
  defp later(a, nil), do: a
  defp later(a, b), do: if(DateTime.compare(a, b) == :gt, do: a, else: b)

  defp earlier(a, b), do: if(DateTime.compare(a, b) == :lt, do: a, else: b)

  defp to_utc(%DateTime{} = dt), do: dt
  defp to_utc(%NaiveDateTime{} = n), do: DateTime.from_naive!(n, "Etc/UTC")
end
