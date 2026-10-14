defmodule RisiMe.Agents.Health do
  @moduledoc """
  The **Health agent** (decision 088, contract v1.46 §12.12.9; decision 084 agent behaviour):
  conversations never stay stuck while starting or re-adding a phone.

  **Goal:** every conversation is readable on every live phone of its members; nothing waits more
  than 5 minutes for a human. **Run** (`sweep/1`, Oban cron every 5 minutes,
  `RisiMe.Workers.HealthSweep`): finds what is stuck for more than 5 minutes —

    * `creating` — an Official group still `creating` (no epoch 0);
    * `readd` — a pending `devices` op (not a cleanup op) that adds a device which can still
      receive (§12.12.5), in any group;
    * `rebuild` — a `rebuild` op (after a reset) not done;
    * `dm_readd` — a pending DM `devices` op adding a live device;

  and repairs **only what is safe, only in Official** (Private groups and DMs are never touched;
  they are counted and reported):

    * `creating` → expired at once (no MLS state exists yet; the app's watchdog starts it again);
    * `readd` that Risi can commit (`Ops.risi_can_commit?/2`, in budget) → Risi is named again;
    * `readd` that nobody can ever commit (`candidates: 0` or exhausted) whose added device is an
      admin's → the §12.8 reset through the ordinary path (`Groups.Commit.reset/4`, as if that
      device asked: the §12.12.3 guard, the admin check and the 1-per-hour limit all apply), with
      that device as the rebuilder.

  Everything else is **alerted** once per conversation and day to the operators' Risi chats
  (`RISI_DEV_USERS`) as an `ops_alert` (§32, `alert` / `conversation_stuck`): short ids and counts,
  never names, numbers or content.

  **Flag** `RISI_AGENT_HEALTH` (`off | shadow | canary | on`, default `canary`): `shadow` detects,
  records and alerts but repairs nothing; `canary` repairs the Official scope set by
  `RISI_HEALTH_OFFICIAL_REPAIR` (`canary`: conversations with a `RISI_DEV_USERS` member; `on`:
  every Official conversation; incident 2026-10-14); `on` repairs everywhere.

  **Incident 2026-10-14** (proposal 2026-10-14-health-nudge): a waiting Private 1:1's other person
  gets one `secure_nudge` a day (`RISI_HEALTH_NUDGE`), and the operators get a plain report with
  names and actions, never ids (`RISI_HEALTH_PLAIN`; `console/1` holds the technical rows).

  **Measured:** every stuck conversation is a row of `risi_health_stuck` (day, conversation, kind,
  action, repaired); the daily scoreboard row `health` has `n` = stuck conversations, `acceptance`
  = the share repaired, `accuracy` = the reliability (1 − stuck / active conversations with a
  pending op or Official groups). Each sweep is a run row (`risi_agent_runs`).
  """
  @behaviour RisiMe.Agents.Agent

  import Ecto.Query

  require Logger

  alias RisiMe.Agent.Clock
  alias RisiMe.Agents.{Flags, Runs}
  alias RisiMe.Groups.{Group, Member, Op, Ops}
  alias RisiMe.MLS.{DmOp, DmOps}
  alias RisiMe.{Groups, Messaging, MLS, Repo, TimeUUID}

  @stuck_s 300

  @impl true
  def spec,
    do: %{
      name: "health",
      type: :specialist,
      goal:
        "Every conversation is readable on every live phone of its members; Official never waits for a human.",
      tools: ~w(groups_ops risi_readd group_reset ops_alert),
      memory: :none,
      eval_set: nil,
      budget: %{p50_ms: 500, p95_ms: 2_000, hard_ms: 60_000},
      learned_version: "v1-rules",
      legacy: [],
      status: :live
    }

  @impl true
  def run(_input, ctx), do: {:ok, sweep(Map.get(ctx, :mode))}

  @impl true
  def label(%{stuck: s, repaired: r}), do: "stuck=#{s} repaired=#{r}"
  def label(_), do: nil

  @doc "The seconds after which a waiting conversation counts as stuck."
  def stuck_s, do: Application.get_env(:risime, :health_stuck_s, @stuck_s)

  @doc """
  One sweep. `mode` defaults to the flag. Returns
  `%{stuck, repaired, alerted, found: [%{conv, kind, tab, action, repaired}]}`.
  """
  def sweep(mode \\ nil) do
    mode = mode || Flags.mode("health")
    t0 = System.monotonic_time(:millisecond)

    if mode == :off do
      %{stuck: 0, repaired: 0, alerted: 0, nudged: 0, found: []}
    else
      cutoff = DateTime.add(DateTime.utc_now(), -stuck_s(), :second)

      found =
        (creating(cutoff) ++ readds(cutoff) ++ rebuilds(cutoff) ++ dm_readds(cutoff))
        |> Enum.map(&act(&1, mode))

      Enum.each(found, &record/1)
      nudged = nudges(found, mode)
      alerted = report(found, nudged)

      result = %{
        stuck: found |> Enum.map(& &1.conv) |> Enum.uniq() |> length(),
        repaired: Enum.count(found, & &1.repaired),
        alerted: alerted,
        nudged: Enum.count(nudged, fn {_conv, i} -> i.state == :now end),
        found: found
      }

      Runs.record(%{
        agent: "health",
        mode: mode,
        status: "ok",
        decision: label(result),
        latency_ms: System.monotonic_time(:millisecond) - t0,
        learned_version: spec().learned_version
      })

      if result.stuck > 0,
        do:
          Logger.info(
            "risi health: stuck=#{result.stuck} repaired=#{result.repaired} alerted=#{alerted}"
          )

      result
    end
  rescue
    e ->
      Logger.warning("risi health: sweep failed (#{inspect(e.__struct__)})")
      %{stuck: 0, repaired: 0, alerted: 0, nudged: 0, found: []}
  end

  ## Detection

  defp creating(cutoff) do
    Repo.all(
      from g in Group,
        where: g.state == "creating" and g.tab == "official" and g.created_at < ^cutoff
    )
    |> Enum.map(&%{conv: &1.id, kind: "creating", tab: "official", group: &1, op: nil})
  end

  defp readds(cutoff) do
    from(o in Op,
      join: g in Group,
      on: g.id == o.group_id,
      where: o.type == "devices" and o.created_at < ^cutoff and g.state == "active",
      select: {o, g}
    )
    |> Repo.all()
    |> Enum.reject(fn {o, _g} -> Ops.cleanup?(o) end)
    |> Enum.filter(fn {o, _g} ->
      live_added?(
        waiting_refs(Ops.refs(o, "added"), Ops.refs(o, "removed"), Groups.in_group(o.group_id))
      )
    end)
    |> Enum.map(fn {o, g} ->
      %{conv: g.id, kind: "readd", tab: g.tab || "private", group: g, op: o}
    end)
  end

  defp rebuilds(cutoff) do
    from(o in Op,
      join: g in Group,
      on: g.id == o.group_id,
      where: o.type == "rebuild" and o.created_at < ^cutoff,
      select: {o, g}
    )
    |> Repo.all()
    |> Enum.map(fn {o, g} ->
      %{conv: g.id, kind: "rebuild", tab: g.tab || "private", group: g, op: o}
    end)
  end

  defp dm_readds(cutoff) do
    Repo.all(from o in DmOp, where: o.created_at < ^cutoff)
    |> Enum.filter(
      &live_added?(
        waiting_refs(
          DmOps.refs(&1, "added"),
          DmOps.refs(&1, "removed"),
          DmOps.in_group(&1.conversation_id)
        )
      )
    )
    |> Enum.map(&%{conv: &1.conversation_id, kind: "dm_readd", tab: "dm", group: nil, op: &1})
  end

  # Incident 2026-10-14: the devices still waiting to be added. A device already in the group is
  # readable (the op lives on only to remove the old leaf), unless the op removes and re-adds it
  # (a rejoin of a phone that lost its state).
  defp waiting_refs(added, removed, in_group),
    do: Enum.filter(added, &(not MapSet.member?(in_group, &1) or &1 in removed))

  # A device that can still receive (§12.12.5): someone is really waiting for it.
  defp live_added?([]), do: false

  defp live_added?(refs) do
    live = refs |> Enum.map(&elem(&1, 0)) |> Enum.uniq() |> DmOps.addable()
    Enum.any?(refs, &MapSet.member?(live, &1))
  end

  ## Repairs (Official only)

  defp act(%{tab: "official"} = f, mode) do
    if repair?(f, official_scope(mode)),
      do: repair(f),
      else: Map.merge(f, %{action: "shadow", repaired: false})
  end

  defp act(f, _mode), do: Map.merge(f, %{action: "alert", repaired: false})

  @doc """
  Which Official conversations a sweep in `mode` repairs: `:all`, `:dev` (those with a
  `RISI_DEV_USERS` member) or `:none`. `shadow` and `off` never repair, `on` repairs everywhere,
  and `canary` (the agent's default) follows `RISI_HEALTH_OFFICIAL_REPAIR` (incident 2026-10-14,
  default `on`: Official must repair itself everywhere, decision 088). Private groups and DMs are
  never repaired, whatever the mode.
  """
  def official_scope(:on), do: :all

  def official_scope(:canary) do
    case Flags.switch("RISI_HEALTH_OFFICIAL_REPAIR") do
      :on -> :all
      :canary -> :dev
      :off -> :none
    end
  end

  def official_scope(_mode), do: :none

  defp repair?(_f, :all), do: true

  defp repair?(f, :dev) do
    dev = Application.get_env(:risime, :risi_dev_users, [])

    dev != [] and
      Repo.exists?(
        from m in Member,
          where: m.group_id == ^f.conv and m.user_id in type(^dev, {:array, :binary_id})
      )
  end

  defp repair?(_f, _scope), do: false

  defp repair(%{kind: "creating"} = f) do
    {n, _} =
      Repo.delete_all(from g in Group, where: g.id == ^f.conv and g.state == "creating")

    Map.merge(f, %{action: "expired_creating", repaired: n == 1})
  end

  defp repair(%{kind: "readd", group: g, op: op} = f) do
    {:ok, outcome} =
      Groups.locked(g.id, fn ->
        with %Group{} = g <- Groups.get_group(g.id),
             %Op{} = op <- Ops.get(g.id, op.op_id) do
          {n, exhausted?} = Ops.rejoin_status(g, op)

          cond do
            Ops.risi_can_commit?(g, op) and not exhausted? ->
              Ops.name_next(g, op)
              {:ok, :renamed_risi}

            n == 0 or exhausted? ->
              {:ok, :reset_needed}

            true ->
              {:ok, :waiting_member}
          end
        else
          _ -> {:ok, :done}
        end
      end)

    case outcome do
      :renamed_risi -> Map.merge(f, %{action: "renamed_risi", repaired: true})
      :done -> Map.merge(f, %{action: "done", repaired: true})
      :reset_needed -> reset(f)
      :waiting_member -> Map.merge(f, %{action: "waiting_member", repaired: false})
    end
  end

  # Incident 2026-10-14: an Official `rebuild` waits for the admin's phone, and an app up to
  # 0.3.0-nightly.7 that was reinstalled can't rebuild an Official group (it builds Private meta,
  # which the core refuses, and never retries the op). Risi may not rebuild (§12.12.9), so no
  # server-side repair exists today: proposal 2026-10-14-health-nudge §4 (the app fix and the
  # Start Official hand-over). Reported as needing the app update; nothing is lost.
  defp repair(%{kind: "rebuild"} = f), do: Map.merge(f, %{action: "needs_app", repaired: false})

  defp repair(f), do: Map.merge(f, %{action: "alert", repaired: false})

  # §12.8 through the ordinary path, as the waiting admin device (never bypassing a check).
  defp reset(%{group: g, op: op} = f) do
    admins = Groups.admin_ids(g.id)

    case Enum.find(Ops.refs(op, "added"), fn {u, _} -> u in admins end) do
      {u, d} ->
        case Groups.Commit.reset(u, d, g.id, %{"generation" => g.generation}) do
          {:ok, _gen} -> Map.merge(f, %{action: "reset", repaired: true})
          {:error, _} -> Map.merge(f, %{action: "reset_refused", repaired: false})
        end

      nil ->
        Map.merge(f, %{action: "no_admin_device", repaired: false})
    end
  end

  ## Record

  defp record(f) do
    now = DateTime.utc_now()

    Repo.insert_all(
      "risi_health_stuck",
      [
        %{
          day: Date.utc_today(),
          conversation_id: f.conv,
          kind: f.kind,
          tab: f.tab,
          action: f.action,
          repaired: f.repaired,
          sweeps: 1,
          updated_at: now
        }
      ],
      on_conflict: [
        set: [action: f.action, repaired: f.repaired, updated_at: now],
        inc: [sweeps: 1]
      ],
      conflict_target: [:day, :conversation_id, :kind]
    )
  end

  ## Nudge (Private 1:1s; proposal 2026-10-14-health-nudge)

  @nudge_every_s 86_400

  # A DM waits for the other person's phone to re-add this user's new phone (Risi never commits in
  # Private). At most once a day per chat, the other person gets a stored `secure_nudge` event and
  # the ordinary content-free inbox wake (§8): an app that knows the kind shows "Open RisiMe to
  # finish securing your chat with <name>" from its own contacts; older apps ignore the event and
  # only sync. `RISI_HEALTH_NUDGE`: `canary` = DMs with a RISI_DEV_USERS member (Harsha first).
  # Returns `%{conv => %{state: :now | :recent | :off, peers, reachable}}`.
  defp nudges(found, mode) do
    if mode in [:canary, :on] do
      sw = Flags.switch("RISI_HEALTH_NUDGE")

      found
      |> Enum.filter(&(&1.kind == "dm_readd" and not &1.repaired))
      |> Enum.uniq_by(& &1.conv)
      |> Map.new(fn f -> {f.conv, nudge(f, sw)} end)
    else
      %{}
    end
  end

  defp nudge(%{conv: conv, op: op}, sw) do
    {:ok, members} = MLS.members(conv)
    peers = Enum.reject(members, &(&1 == op.user_id))
    reachable = Enum.filter(peers, &(RisiMe.Devices.push_targets(&1) != []))
    info = %{peers: peers, reachable: reachable}

    allowed? =
      case sw do
        :on -> true
        :canary -> Enum.any?(members, &Flags.dev_user?/1)
        :off -> false
      end

    cond do
      not allowed? ->
        Map.put(info, :state, :off)

      nudged_within?(conv, @nudge_every_s) ->
        Map.put(info, :state, :recent)

      true ->
        now = DateTime.utc_now()

        Messaging.publish_batch(
          for p <- peers, do: {p, nudge_event(conv, op.user_id, now), [push: true]}
        )

        Repo.update_all(
          from(s in "risi_health_stuck",
            where:
              s.day == ^Date.utc_today() and s.conversation_id == ^conv and
                s.kind == "dm_readd"
          ),
          set: [nudged_at: now]
        )

        Logger.info("risi health: nudged peers=#{length(peers)} reachable=#{length(reachable)}")
        Map.put(info, :state, :now)
    end
  rescue
    e ->
      Logger.warning("risi health: nudge failed (#{inspect(e.__struct__)})")
      %{state: :off, peers: [], reachable: []}
  end

  @doc "The stored `secure_nudge` event (proposal 2026-10-14-health-nudge §1)."
  def nudge_event(conv, waiting_user, now) do
    %{
      event_id: TimeUUID.generate(),
      kind: "secure_nudge",
      data: %{
        "conversation_id" => conv,
        "waiting_user_id" => waiting_user,
        "reason" => "device_readd",
        "at" => Clock.ts(now)
      }
    }
  end

  defp nudged_within?(conv, seconds) do
    cutoff = DateTime.add(DateTime.utc_now(), -seconds, :second)

    Repo.exists?(
      from s in "risi_health_stuck",
        where:
          s.conversation_id == ^conv and not is_nil(s.nudged_at) and
            s.nudged_at > type(^cutoff, :utc_datetime_usec)
    )
  end

  ## Report (the operators' Risi chats)

  @fresh_fix_s 1_800

  # What the operators hear (§32 `ops_alert`, `alert` / `conversation_stuck`), at most once per
  # conversation, day and state: `waiting_other` (Private: waiting for another person's phone),
  # `needs_fix` (Official, not repaired yet) and `fixed` (a case we reported or just repaired
  # is no longer stuck: verified, never assumed). Being repaired right now is not reported.
  # `RISI_HEALTH_PLAIN` (default `on`): names and actions, no ids or codes, each chat an item the
  # app can open; `off`: the v1.46 one-line id summary.
  defp report(found, nudged) do
    case Flags.switch("RISI_HEALTH_PLAIN") do
      :off -> alert_ids(Enum.reject(found, & &1.repaired))
      _ -> report_plain(found, nudged)
    end
  end

  defp report_plain(found, nudged) do
    today = Date.utc_today()
    keys = MapSet.new(found, &{&1.conv, &1.kind})
    told = told_today(today)

    current =
      for f <- found,
          state = state_of(f),
          state != nil,
          Map.get(told, {f.conv, f.kind}) != state,
          do: Map.put(f, :state, state)

    fixed = fixed_since(today, keys)
    entries = current ++ fixed

    if entries == [] do
      0
    else
      sent = post_report(entries, nudged)

      if sent > 0 do
        for e <- current, do: mark(today, e, e.state)
        for e <- fixed, do: mark_fixed(e)
      end

      sent
    end
  rescue
    e ->
      Logger.warning("risi health: report failed (#{inspect(e.__struct__)})")
      0
  end

  defp state_of(%{repaired: true}), do: nil
  defp state_of(%{tab: "official"}), do: "needs_fix"
  defp state_of(_f), do: "waiting_other"

  defp told_today(today) do
    Repo.all(
      from s in "risi_health_stuck",
        where: s.day == ^today and not is_nil(s.reported),
        select: {{s.conversation_id, s.kind}, s.reported}
    )
    |> Map.new()
  end

  # Cases no longer stuck that someone was told about (since yesterday), or that this agent
  # repaired in the last 30 minutes: now verified healed.
  defp fixed_since(today, keys) do
    fresh = DateTime.add(DateTime.utc_now(), -@fresh_fix_s, :second)

    Repo.all(
      from s in "risi_health_stuck",
        where:
          s.day >= ^Date.add(today, -1) and
            (s.reported in ["waiting_other", "needs_fix"] or
               (s.repaired and is_nil(s.reported) and
                  s.updated_at > type(^fresh, :utc_datetime_usec))),
        select: %{conv: s.conversation_id, kind: s.kind, tab: s.tab}
    )
    |> Enum.reject(&MapSet.member?(keys, {&1.conv, &1.kind}))
    |> Enum.uniq_by(&{&1.conv, &1.kind})
    |> Enum.map(fn e ->
      Map.merge(e, %{state: "fixed", group: group_of(e.conv), op: nil})
    end)
  end

  defp group_of("grp:" <> _ = id), do: Repo.get(Group, id)
  defp group_of(_), do: nil

  defp mark(day, e, state) do
    Repo.update_all(
      from(s in "risi_health_stuck",
        where: s.day == ^day and s.conversation_id == ^e.conv and s.kind == ^e.kind
      ),
      set: [reported: state]
    )
  end

  defp mark_fixed(e) do
    Repo.update_all(
      from(s in "risi_health_stuck",
        where:
          s.day >= ^Date.add(Date.utc_today(), -1) and s.conversation_id == ^e.conv and
            s.kind == ^e.kind
      ),
      set: [reported: "fixed"]
    )
  end

  defp post_report(entries, nudged) do
    now = DateTime.utc_now()
    entries = Enum.uniq_by(entries, & &1.conv)

    sent =
      for uid <- Application.get_env(:risime, :risi_dev_users, []),
          RisiMe.Agent.Out.ready?(),
          rc = RisiMe.RisiChat.active_id(uid),
          rc != nil,
          {body, detail, items} = compose(entries, nudged, uid),
          risi =
            "alert"
            |> RisiMe.OpsAlert.risi("conversation_stuck", detail, uid, now)
            |> Map.put("items", items),
          match?({:ok, _}, RisiMe.Agent.Out.post(rc, body, risi, memory: false)),
          do: uid

    length(sent)
  end

  @doc """
  The plain-language report for operator `uid`: `{body, detail, items}`. `body` is what every app
  shows (one line per group of chats, names and actions, never an id or a code); `items` lets a
  newer app make each chat a row that opens it (proposal 2026-10-14-health-nudge §3).
  """
  def compose(entries, nudged, uid) do
    names = names_for(entries)
    labelled = Enum.map(entries, &Map.put(&1, :label, label(&1, uid, names)))
    by = Enum.group_by(labelled, & &1.state)

    lines =
      [
        fixed_line(by["fixed"] || []),
        waiting_line(by["waiting_other"] || [], nudged, names),
        needs_line(by["needs_fix"] || [])
      ]
      |> Enum.reject(&is_nil/1)

    counts =
      for {state, word} <- [
            {"fixed", "fixed"},
            {"waiting_other", "waiting for the other person"},
            {"needs_fix", "still stuck"}
          ],
          n = length(by[state] || []),
          n > 0,
          do: "#{n} #{word}"

    items =
      for e <- labelled do
        %{
          "conversation_id" => e.conv,
          "chat_id" => chat_of(e),
          "label" => e.label,
          "state" => e.state,
          "open" => member?(e, uid)
        }
      end

    {Enum.join(lines, "\n"), "Chat health: " <> Enum.join(counts, ", ") <> ".", items}
  end

  defp fixed_line([]), do: nil

  defp fixed_line(es),
    do: "#{count(es)} (#{list(es)}) #{if length(es) == 1, do: "was", else: "were"} stuck: fixed."

  defp needs_line([]), do: nil

  defp needs_line(es) do
    {app, rest} = Enum.split_with(es, &(&1[:action] == "needs_app"))

    [
      if(app != [],
        do:
          "#{count(app)} #{verb(app, "needs", "need")} the next app update to finish securing: #{list(app)}. Nothing on the phones is lost."
      ),
      if(rest != [],
        do:
          "#{count(rest)} still #{verb(rest, "needs", "need")} a fix: #{list(rest)}. #{rest_hint(rest)}"
      )
    ]
    |> Enum.reject(&is_nil/1)
    |> Enum.join("\n")
  end

  defp rest_hint(rest) do
    if Enum.all?(rest, &(&1[:action] == "shadow")),
      do: "Automatic repair is off for #{verb(rest, "it", "them")}.",
      else: "I'll keep trying."
  end

  defp waiting_line([], _nudged, _names), do: nil

  defp waiting_line(es, nudged, names) do
    head =
      "#{count(es)} #{verb(es, "is", "are")} waiting for the other person to open RisiMe: #{list(es)}."

    infos = for e <- es, i = nudged[e.conv], i != nil, do: i

    reminded =
      infos |> Enum.filter(&(&1.state in [:now, :recent])) |> Enum.flat_map(& &1.reachable)

    unreachable = infos |> Enum.flat_map(&(&1.peers -- &1.reachable)) |> Enum.uniq()

    [
      head,
      if(reminded != [],
        do: "I've reminded #{join(Enum.map(Enum.uniq(reminded), &name(names, &1)))}."
      ),
      if(unreachable != [],
        do:
          "#{join(Enum.map(unreachable, &name(names, &1)))} can't get RisiMe notifications right now, so please ask them to open RisiMe."
      )
    ]
    |> Enum.reject(&is_nil/1)
    |> Enum.join(" ")
  end

  defp count(es) do
    n = length(es)

    kind =
      case es |> Enum.map(&tab_word/1) |> Enum.uniq() do
        [w] -> w <> " "
        _ -> ""
      end

    "#{n} #{kind}#{if n == 1, do: "chat", else: "chats"}"
  end

  defp tab_word(%{tab: "official"}), do: "Official"
  defp tab_word(_), do: "private"

  defp verb(es, one, many), do: if(length(es) == 1, do: one, else: many)
  defp list(es), do: es |> Enum.map(& &1.label) |> Enum.join(", ")

  defp join([a]), do: a
  defp join(xs), do: Enum.join(Enum.drop(xs, -1), ", ") <> " and " <> List.last(xs)

  # The people of a chat, humans only (never Risi): DM members, or active human group members.
  defp humans(%{conv: "dm:" <> _ = conv}) do
    case MLS.members(conv) do
      {:ok, ms} -> ms
      _ -> []
    end
  end

  defp humans(%{conv: gid}) do
    Repo.all(
      from m in Member,
        where: m.group_id == ^gid and m.kind == "user" and m.state == "active",
        order_by: [asc: m.joined_at, asc: m.user_id],
        select: m.user_id
    )
  end

  defp member?(e, uid), do: uid in humans(e)

  defp chat_of(%{conv: "dm:" <> _ = conv}), do: conv
  defp chat_of(%{group: %Group{chat_id: c}}) when is_binary(c), do: c
  defp chat_of(e), do: e.conv

  defp names_for(entries) do
    ids =
      entries
      |> Enum.flat_map(&humans/1)
      |> Enum.concat(Enum.flat_map(entries, &op_users/1))
      |> Enum.uniq()

    Repo.all(from u in RisiMe.Accounts.User, where: u.id in ^ids, select: {u.id, u.display_name})
    |> Map.new()
  end

  defp op_users(%{op: %{user_id: u}}) when is_binary(u), do: [u]
  defp op_users(_), do: []

  # Never an invented name (decision 085 §4): a user without one is "a contact".
  defp name(names, id) do
    case names[id] do
      n when is_binary(n) and n != "" -> String.trim(n)
      _ -> "a contact"
    end
  end

  @doc false
  def label(e, uid, names) do
    people = humans(e)
    others = Enum.reject(people, &(&1 == uid))
    mine? = uid in people
    kind = if match?(%Group{}, e[:group]), do: e.group.chat_kind, else: nil

    cond do
      String.starts_with?(e.conv, "dm:") or kind == "dm" ->
        if mine? and others != [],
          do: name(names, hd(others)),
          else: join(Enum.map(people, &name(names, &1)) |> fallback())

      kind == "risi" ->
        case people do
          [^uid | _] -> "your Risi chat"
          [owner | _] -> "#{name(names, owner)}'s Risi chat"
          [] -> "a Risi chat"
        end

      others != [] ->
        # Stable order: named people first, alphabetically (members can join in the same instant).
        {shown, rest} =
          others
          |> Enum.sort_by(&{name(names, &1) == "a contact", name(names, &1), &1})
          |> Enum.split(3)

        more = if rest == [], do: "", else: " and #{length(rest)} more"
        "the group with #{Enum.map_join(shown, ", ", &name(names, &1))}#{more}"

      e[:group] == nil and String.starts_with?(e.conv, "grp:") ->
        "a chat that was starting"

      true ->
        "a group"
    end
  end

  defp fallback([]), do: ["a contact"]
  defp fallback(xs), do: xs

  # The v1.46 summary (ids and codes), kept behind `RISI_HEALTH_PLAIN=off`.
  defp alert_ids([]), do: 0

  defp alert_ids(found) do
    fresh =
      Enum.filter(found, fn f ->
        RisiMe.RateLimiter.hit_if_allowed(:health_alert, f.conv, 1, :timer.hours(24)) == :ok
      end)

    if fresh == [] do
      0
    else
      detail =
        fresh
        |> Enum.map(fn f -> "#{f.tab} #{f.kind} #{short(f.conv)} (#{f.action})" end)
        |> Enum.take(10)
        |> Enum.join("; ")
        |> String.slice(0, 300)

      text =
        "#{length(fresh)} conversation(s) stuck for more than #{div(stuck_s(), 60)} minutes that I couldn't repair: " <>
          detail

      now = DateTime.utc_now()

      sent =
        for uid <- Application.get_env(:risime, :risi_dev_users, []),
            rc = RisiMe.RisiChat.active_id(uid),
            rc != nil,
            RisiMe.Agent.Out.ready?(),
            match?(
              {:ok, _},
              RisiMe.Agent.Out.post(
                rc,
                text,
                RisiMe.OpsAlert.risi("alert", "conversation_stuck", detail, uid, now),
                memory: false
              )
            ),
            do: uid

      length(sent)
    end
  rescue
    _ -> 0
  end

  defp short("grp:" <> id), do: "grp:" <> String.slice(id, 0, 8)
  defp short("dm:" <> _), do: "dm"
  defp short(id), do: String.slice(id, 0, 12)

  ## Console (technical detail, admin console Servers page)

  @doc """
  The technical rows for the admin console's Servers page (never the chat): today's and
  yesterday's stuck conversations with short ids, kind, tab, last action, sweeps, whether
  repaired, what the operators were told and when the other person was last reminded. Newest
  first. Plus the mode and the Health switches.
  """
  def console(day \\ Date.utc_today()) do
    rows =
      Repo.all(
        from s in "risi_health_stuck",
          where: s.day >= ^Date.add(day, -1) and s.day <= ^day,
          order_by: [desc: s.updated_at],
          select: %{
            day: s.day,
            conversation_id: s.conversation_id,
            kind: s.kind,
            tab: s.tab,
            action: s.action,
            repaired: s.repaired,
            sweeps: s.sweeps,
            reported: s.reported,
            nudged_at: s.nudged_at,
            updated_at: s.updated_at
          }
      )
      |> Enum.map(&Map.put(&1, :conversation_id, short(&1.conversation_id)))

    %{
      mode: Flags.mode("health"),
      official_scope: official_scope(Flags.mode("health")),
      switches: Map.new(Flags.switches(), &{&1, Flags.switch(&1)}),
      rows: rows
    }
  rescue
    _ -> %{mode: :off, official_scope: :none, switches: %{}, rows: []}
  end

  ## Score

  @doc """
  The day's scoreboard numbers: `%{n, repaired, reliability}`. `reliability` = 1 − stuck /
  (Official groups active that day + conversations with a pending op), nil with none.
  """
  def day_score(%Date{} = day) do
    rows =
      Repo.all(
        from s in "risi_health_stuck",
          where: s.day == ^day,
          select: %{conv: s.conversation_id, repaired: s.repaired}
      )

    stuck = rows |> Enum.map(& &1.conv) |> Enum.uniq() |> length()
    repaired = Enum.count(rows, & &1.repaired)

    base =
      Repo.aggregate(from(g in Group, where: g.tab == "official" and g.state == "active"), :count) +
        stuck

    %{
      n: stuck,
      repaired: if(rows == [], do: nil, else: repaired / length(rows)),
      reliability: if(base == 0, do: nil, else: 1 - stuck / base)
    }
  rescue
    _ -> %{n: 0, repaired: nil, reliability: nil}
  end
end

defmodule RisiMe.Workers.HealthSweep do
  @moduledoc "Oban cron (every 5 minutes, queue `risi`): the Health agent's sweep (decision 088)."
  use Oban.Worker, queue: :risi, max_attempts: 1

  @impl true
  def perform(_job) do
    _ = RisiMe.Agents.Health.sweep()
    :ok
  end
end
