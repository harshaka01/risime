# Cassandra tables are truncated once per run (TRUNCATE is slow on Cassandra); every test
# uses fresh random user ids, so partitions never overlap between tests.
RisiMe.Messaging.Store.Cassandra.truncate!()

ExUnit.start()

# Push.Test routes each push to the test that registered its token (see RisiMe.Fixtures.push_token/1).
{:ok, _} = Registry.start_link(keys: :unique, name: RisiMe.Push.Test.registry())
Ecto.Adapters.SQL.Sandbox.mode(RisiMe.Repo, :manual)

# Locally generated signing keys as the realm JWKS (contract v1.3 tests).
RisiMe.OIDCHelpers.install_jwks()

# config/runtime.exs loads the repo .env into this VM; replace the Notify.lk credentials with
# fakes so no real value can ever reach a stub, an assertion message or a log in tests.
for {k, v} <- [
      {"NOTIFYLK_USER_ID", "fake-user"},
      {"NOTIFYLK_API_KEY", "fake-key-never-shown"},
      {"NOTIFYLK_SENDER_ID", "RisiMeTest"}
    ],
    do: System.put_env(k, v)

System.delete_env("NOTIFYLK_ALLOW_DEMO_OTP")

# Fresh fail2ban auth log per run (tests match lines by client IP).
File.rm(RisiMe.AuthLog.path())
