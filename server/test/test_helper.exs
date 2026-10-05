# Cassandra tables are truncated once per run (TRUNCATE is slow on Cassandra); every test
# uses fresh random user ids, so partitions never overlap between tests.
RisiMe.Messaging.Store.Cassandra.truncate!()

ExUnit.start()
Ecto.Adapters.SQL.Sandbox.mode(RisiMe.Repo, :manual)
