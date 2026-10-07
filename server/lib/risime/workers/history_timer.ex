defmodule RisiMe.Workers.HistoryTimer do
  @moduledoc """
  History sharing timers (contract v1.15 §17.4, §17.11), one Oban job per deadline, modelled on
  `RisiMe.Workers.GroupTimer`:

    * `own_end`: the 10-minute own phase is over (members are named for `sources: "any"`);
    * `member_window`: a named member user's 24 h ran out (args `user_id`);
    * `delivery`: the accepted provider's 30-minute delivery deadline;
    * `expire`: the request's 48 h expiry;
    * `prune` (daily cron): rows older than 8 days are deleted.

  Args hold only ids (never content). A job that is no longer current is a no-op.
  """
  use Oban.Worker, queue: :history, max_attempts: 5

  @impl Oban.Worker
  def perform(%Oban.Job{args: %{"kind" => "prune"}}) do
    _ = RisiMe.History.prune()
    :ok
  end

  def perform(%Oban.Job{args: %{"kind" => kind, "request_id" => rid} = args}),
    do: RisiMe.History.timer(kind, rid, args)
end
