defmodule RisiMe.Workers.PruneAccounts do
  @moduledoc """
  Daily account housekeeping, scheduled by Oban's Cron plugin (see `config/config.exs`):
  deletes OTP challenges older than 24 h, tokens revoked more than 30 days ago and phone
  challenges older than 48 h.
  """
  use Oban.Worker, queue: :maintenance, max_attempts: 3, unique: [period: 3600]

  require Logger

  alias RisiMe.Accounts

  @impl Oban.Worker
  def perform(%Oban.Job{}) do
    challenges = Accounts.prune_otp_challenges()
    tokens = Accounts.prune_revoked_tokens()
    phone_challenges = Accounts.prune_phone_challenges()
    devices = RisiMe.Devices.prune()
    {invites, requests} = RisiMe.Social.expire()

    Logger.info(
      "PruneAccounts: deleted #{challenges} OTP challenge(s), #{tokens} revoked token(s), " <>
        "#{phone_challenges} phone challenge(s), #{devices} stale device(s); expired #{invites} " <>
        "invite(s), #{requests} friend request(s)"
    )

    {:ok,
     %{
       otp_challenges: challenges,
       revoked_tokens: tokens,
       phone_challenges: phone_challenges,
       devices: devices,
       expired_invites: invites,
       expired_requests: requests
     }}
  end
end
