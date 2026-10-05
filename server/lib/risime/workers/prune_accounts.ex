defmodule RisiMe.Workers.PruneAccounts do
  @moduledoc """
  Daily account housekeeping, scheduled by Oban's Cron plugin (see `config/config.exs`):
  deletes OTP challenges older than 24 h and tokens revoked more than 30 days ago.
  """
  use Oban.Worker, queue: :maintenance, max_attempts: 3, unique: [period: 3600]

  require Logger

  alias RisiMe.Accounts

  @impl Oban.Worker
  def perform(%Oban.Job{}) do
    challenges = Accounts.prune_otp_challenges()
    tokens = Accounts.prune_revoked_tokens()

    Logger.info(
      "PruneAccounts: deleted #{challenges} OTP challenge(s), #{tokens} revoked token(s)"
    )

    {:ok, %{otp_challenges: challenges, revoked_tokens: tokens}}
  end
end
