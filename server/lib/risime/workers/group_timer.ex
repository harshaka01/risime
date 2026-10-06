defmodule RisiMe.Workers.GroupTimer do
  @moduledoc """
  Group timers (contract v1.9 §12.3, §12.4), one Oban job per deadline:

    * `committer`: the named committer's 60 s ran out (args `op_id`, `naming`); a newer naming
      or a completed op makes the job a no-op;
    * `expire`: an `add` or `role` op reached its 24 h `expires_at`;
    * `creating`: a group still `creating` 10 minutes after `POST /groups` is deleted.

  Args hold only ids and counters (never content).
  """
  use Oban.Worker, queue: :groups, max_attempts: 5

  import Ecto.Query

  alias RisiMe.{Groups, Repo}
  alias RisiMe.Groups.{Group, Ops}

  @impl Oban.Worker
  def perform(%Oban.Job{args: %{"kind" => "committer", "op_id" => op_id, "naming" => n}}),
    do: Ops.committer_timeout(op_id, n)

  def perform(%Oban.Job{args: %{"kind" => "expire", "op_id" => op_id}}), do: Ops.expire(op_id)

  def perform(%Oban.Job{args: %{"kind" => "creating", "group_id" => id}}) do
    {:ok, _} =
      Groups.locked(id, fn ->
        {n, _} = Repo.delete_all(from g in Group, where: g.id == ^id and g.state == "creating")
        {:ok, n}
      end)

    :ok
  end
end
