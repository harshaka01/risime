defmodule RisiMe.Workers.PruneAccountsTest do
  use RisiMe.DataCase, async: true
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.Fixtures

  alias RisiMe.Accounts.{OtpChallenge, UserToken}
  alias RisiMe.Workers.PruneAccounts

  test "perform/1 prunes old OTP challenges and stale revoked tokens" do
    %{user: user} = logged_in_user()
    now = DateTime.utc_now()

    old_challenge =
      Repo.insert!(%OtpChallenge{
        phone: unique_phone(),
        code_hash: :crypto.strong_rand_bytes(32),
        expires_at: DateTime.add(now, -48, :hour),
        inserted_at: DateTime.add(now, -48, :hour)
      })

    old_token =
      Repo.insert!(%UserToken{
        user_id: user.id,
        token_hash: :crypto.strong_rand_bytes(32),
        revoked_at: DateTime.add(now, -40, :day)
      })

    assert {:ok, %{otp_challenges: 1, revoked_tokens: 1}} = perform_job(PruneAccounts, %{})
    refute Repo.get(OtpChallenge, old_challenge.id)
    refute Repo.get(UserToken, old_token.id)
  end

  test "jobs go to the maintenance queue" do
    {:ok, _} = %{} |> PruneAccounts.new() |> Oban.insert()
    assert_enqueued(worker: PruneAccounts, queue: :maintenance)
  end

  test "the cron plugin schedules the worker daily" do
    plugins = Application.fetch_env!(:risime, Oban)[:plugins]
    assert plugins, "Oban plugins are configured in config.exs"
    {Oban.Plugins.Cron, opts} = List.keyfind(plugins, Oban.Plugins.Cron, 0)
    assert {"17 3 * * *", PruneAccounts} in opts[:crontab]
  end
end
