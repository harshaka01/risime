# RisiMe server (Elixir/Phoenix)

The wire protocol is defined in `../contract/v1/PROTOCOL.md`. How to run it, the allowlist,
the dev OTP log and known limits are in `../docs/status/server.md`.

- `RisiMe.Accounts`: allowlist, users, OTP and tokens (Postgres).
- `RisiMe.Messaging`: send, ack and the inbox. `RisiMe.Messaging.Store.Cassandra` is the only
  Cassandra access for messages.
- `RisiMeWeb`: REST under `/api/v1`, plus `UserSocket` and `InboxChannel` at `/socket`.

Test gate: `mix format --check-formatted && mix compile --warnings-as-errors && mix test`
