defmodule RisiMe.Workers.GroupTimer do
  @moduledoc """
  Group timers (contract v1.9 §12.3, §12.4), one Oban job per deadline:

    * `committer`: the named committer's 60 s ran out (args `op_id`, `naming`); a newer naming
      or a completed op makes the job a no-op;
    * `expire`: an `add` or `role` op reached its 24 h `expires_at`;
    * `creating`: a group still `creating` 10 minutes after `POST /groups` is deleted;
    * `wake` (v1.14 §12.4a): a `devices` op waits with no online candidate; push the candidates
      and check again in 6 h (unique per op);
    * `name_pending` (v1.14 §12.11): the one-off recovery queued by
      `RisiMe.Release.name_pending_device_ops(dry_run: false)` from `bin/risime eval`.

    * `dm_committer` (v1.16): a DM `devices` op's named committer's 60 s ran out;
    * `dm_ops` (v1.16): the one-off DM recovery queued by
      `RisiMe.Release.dm_device_ops(dry_run: false)`;
    * `dm_wake` (v1.21 §12.12.4): a DM `devices` op waits with no online candidate (unique per op);
    * `stale_device_ops` (v1.21 §12.12.7): the one-off recovery queued by
      `RisiMe.Release.stale_device_ops(dry_run: false)`.

  Args hold only ids and counters (never content).
  """
  use Oban.Worker, queue: :groups, max_attempts: 5

  import Ecto.Query

  require Logger

  alias RisiMe.{Groups, Repo}
  alias RisiMe.Groups.{Group, Ops}

  @impl Oban.Worker
  def perform(%Oban.Job{args: %{"kind" => "committer", "op_id" => op_id, "naming" => n}}),
    do: Ops.committer_timeout(op_id, n)

  def perform(%Oban.Job{args: %{"kind" => "dm_committer", "op_id" => op_id, "naming" => n}}),
    do: RisiMe.MLS.DmOps.committer_timeout(op_id, n)

  def perform(%Oban.Job{args: %{"kind" => "dm_wake", "op_id" => op_id}}),
    do: RisiMe.MLS.DmOps.wake(op_id)

  def perform(%Oban.Job{args: %{"kind" => "stale_device_ops"}}) do
    counts = RisiMe.Workers.StaleLeaves.recover(dry_run: false)
    Logger.info("stale device ops (dry_run=false): #{inspect(counts)}")
    :ok
  end

  def perform(%Oban.Job{args: %{"kind" => "dm_ops"}}) do
    counts = RisiMe.MLS.DmOps.recover(dry_run: false)
    Logger.info("dm device ops (dry_run=false): #{inspect(counts)}")
    :ok
  end

  def perform(%Oban.Job{args: %{"kind" => "expire", "op_id" => op_id}}), do: Ops.expire(op_id)

  def perform(%Oban.Job{args: %{"kind" => "wake", "op_id" => op_id}}), do: Ops.wake(op_id)

  def perform(%Oban.Job{args: %{"kind" => "name_pending"}}) do
    counts = Ops.name_waiting(dry_run: false)
    Logger.info("pending device ops (dry_run=false): #{inspect(counts)}")
    :ok
  end

  def perform(%Oban.Job{args: %{"kind" => "creating", "group_id" => id}}) do
    {:ok, _} =
      Groups.locked(id, fn ->
        {n, _} = Repo.delete_all(from g in Group, where: g.id == ^id and g.state == "creating")
        # v1.11 §14.5: deleting a group expires all its blobs.
        if n > 0, do: RisiMe.Blobs.expire_conversation(id)
        {:ok, n}
      end)

    :ok
  end
end
