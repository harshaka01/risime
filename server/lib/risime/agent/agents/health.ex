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
  records and alerts but repairs nothing; `canary` repairs conversations with a `RISI_DEV_USERS`
  member (Harsha first) and is shadow elsewhere; `on` repairs everywhere.

  **Measured:** every stuck conversation is a row of `risi_health_stuck` (day, conversation, kind,
  action, repaired); the daily scoreboard row `health` has `n` = stuck conversations, `acceptance`
  = the share repaired, `accuracy` = the reliability (1 − stuck / active conversations with a
  pending op or Official groups). Each sweep is a run row (`risi_agent_runs`).
  """
  @behaviour RisiMe.Agents.Agent

  import Ecto.Query

  require Logger

  alias RisiMe.Agents.{Flags, Runs}
  alias RisiMe.Groups.{Group, Member, Op, Ops}
  alias RisiMe.MLS.{DmOp, DmOps}
  alias RisiMe.{Groups, Repo}

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
      %{stuck: 0, repaired: 0, alerted: 0, found: []}
    else
      cutoff = DateTime.add(DateTime.utc_now(), -stuck_s(), :second)

      found =
        (creating(cutoff) ++ readds(cutoff) ++ rebuilds(cutoff) ++ dm_readds(cutoff))
        |> Enum.map(&act(&1, mode))

      Enum.each(found, &record/1)
      alerted = alert(Enum.reject(found, & &1.repaired))

      result = %{
        stuck: found |> Enum.map(& &1.conv) |> Enum.uniq() |> length(),
        repaired: Enum.count(found, & &1.repaired),
        alerted: alerted,
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
      %{stuck: 0, repaired: 0, alerted: 0, found: []}
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
    |> Enum.filter(fn {o, _g} -> live_added?(Ops.refs(o, "added")) end)
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
    |> Enum.filter(&live_added?(DmOps.refs(&1, "added")))
    |> Enum.map(&%{conv: &1.conversation_id, kind: "dm_readd", tab: "dm", group: nil, op: &1})
  end

  # A device that can still receive (§12.12.5): someone is really waiting for it.
  defp live_added?([]), do: false

  defp live_added?(refs) do
    live = refs |> Enum.map(&elem(&1, 0)) |> Enum.uniq() |> DmOps.addable()
    Enum.any?(refs, &MapSet.member?(live, &1))
  end

  ## Repairs (Official only)

  defp act(%{tab: "official"} = f, mode) do
    if repair?(f, mode), do: repair(f), else: Map.merge(f, %{action: "shadow", repaired: false})
  end

  defp act(f, _mode), do: Map.merge(f, %{action: "alert", repaired: false})

  defp repair?(_f, :on), do: true

  defp repair?(f, :canary) do
    dev = Application.get_env(:risime, :risi_dev_users, [])

    dev != [] and
      Repo.exists?(
        from m in Member,
          where: m.group_id == ^f.conv and m.user_id in type(^dev, {:array, :binary_id})
      )
  end

  defp repair?(_f, _mode), do: false

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

  ## Record and alert

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

  # One `ops_alert` per conversation and day to each operator's Risi chat.
  defp alert([]), do: 0

  defp alert(found) do
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
