# Server session — Release 0.1
You run on **spark2** as `harsha` in `/home/harsha/development/risime` (shared checkout, branch
`main`), in tmux window 1 of `cc-root`. You own `server/` and `docs/status/server.md`, and you
stage only those paths.

## Start
1. Read `CLAUDE.md`, the Server part of `docs/RELEASE-0.1.md`, and `contract/v1/PROTOCOL.md`.
2. **Immediately ask Harsha once** for two phone + email + name + company sets to allowlist (his
   own and a test partner's). Keep the answers out of git.
3. Check the toolchain with `tail ~/setup.log` and `~/.local/bin/mise ls`. If Erlang is still
   compiling, draft migrations, CQL and module skeletons as plain files in the meantime.
4. Confirm the databases are healthy: `docker compose -f infra/docker-compose.dev.yml ps`.

## Build order
After each green step: `git add server/ docs/status/server.md`, commit, `git pull --rebase`, push.
1. **S1:** generate the project into `server/`. Configure `.env` loading, bind to
   127.0.0.1:4000, and add the dependencies.
2. **S2:** Postgres migrations and the `mix risime.allow` / `mix risime.allow.list` tasks. Then
   allowlist Harsha's two entries.
3. **S3:** Xandra pool, the CQL files, and `mix risime.cql.migrate` for both `risime_dev` and
   `risime_test`.
4. **S4/S5:** the Accounts context, OTP email with dev logging, tokens, and the REST controllers,
   each with tests.
5. **S4:** the `Messaging.Store` behaviour, the Cassandra implementation and the Messaging
   context, with tests.
6. `UserSocket` and `InboxChannel`, then every channel test listed in S6, plus the contract
   example tests.
7. **S7:** start the server in tmux session `risime-server`. Smoke-test the REST endpoints with
   curl on localhost.
8. Write `docs/status/server.md`: READY, how to run, how to read the dev OTP log, and known limits.

## Rules
- Never edit `contract/v1`. If you need a change, write a proposal under `contract/proposals/`
  and tell Harsha.
- Never stage `android/` or root files.
- Test gate before every commit:
  `mix format --check-formatted && mix compile --warnings-as-errors && mix test`
