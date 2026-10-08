# Cassandra tables are truncated once per run (TRUNCATE is slow on Cassandra); every test
# uses fresh random user ids, so partitions never overlap between tests.
RisiMe.Messaging.Store.Cassandra.truncate!()

# `:livekit` talks to the real local LiveKit (v1.19 §20.2): `mix test --only livekit`.
# `:llm_live` calls the real self-hosted risi-l1 (decision 061): `mix test --only llm_live`.
ExUnit.start(exclude: [:livekit, :llm_live])

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

# Per-VM fail2ban auth log and blob dir: concurrent runs (partitions, worktrees) never share or
# delete each other's. Tests match auth-log lines by client IP.
vm_tmp = Path.join(System.tmp_dir!(), "risime-test-#{System.pid()}")
Application.put_env(:risime, :auth_log_path, Path.join(vm_tmp, "auth.log"))
Application.put_env(:risime, :blob_dir, Path.join(vm_tmp, "blobs"))
File.rm_rf!(vm_tmp)
File.mkdir_p!(vm_tmp)
ExUnit.after_suite(fn _ -> File.rm_rf(vm_tmp) end)

# Per-VM random bases for unique phones and IPs (RisiMe.Fixtures).
RisiMe.Fixtures.init_bases!()
