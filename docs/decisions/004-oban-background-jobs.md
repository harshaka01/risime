# 004 — Oban for background jobs and cron

## Context
Backlog 0.2 calls for Postgres-backed jobs. The first need is housekeeping: OTP challenges and
revoked tokens pile up in Postgres forever. Later uses include the Commitment Ledger reminders,
agent work and push retries.

## Decision
- **Oban 2.24** (`{:oban, "~> 2.24"}`, locked at 2.24.1), the open-source edition, with the
  `Oban.Engines.Basic` Postgres engine on `RisiMe.Repo`. It is in the stack already named in
  `CLAUDE.md` ("Oban jobs" in Postgres), so this note records the configuration.
- **Schema:** migration `20261005213409_add_oban_jobs_table` pins `Oban.Migration.up(version: 14)`.
  An Oban upgrade that needs a newer schema version gets its own migration.
- **Supervision:** `{Oban, Application.fetch_env!(:risime, Oban)}` right after the Repo.
- **Queues:** `maintenance: 1` (housekeeping, one at a time). New job families get their own
  queue rather than sharing this one.
- **Plugins:**
  - `Cron`: `RisiMe.Workers.PruneAccounts` daily at **03:17 UTC** (08:47 Sri Lanka, off the
    nightly release at 20:13 UTC and off the hour).
  - `Pruner`: finished jobs are deleted after 7 days.
  - `Lifeline`: jobs stuck in `executing` for 30 minutes (after a crash) are rescued.
- **First job (`PruneAccounts`)** calls two `RisiMe.Accounts` functions:
  - `prune_otp_challenges/1` deletes challenges created more than 24 h ago (they expire after
    5 min; OTP rate limiting is in memory, so it is unaffected);
  - `prune_revoked_tokens/1` deletes tokens revoked more than 30 days ago. Live tokens are
    never touched.
  - `max_attempts: 3`, unique for 1 hour.
- **Tests:** `testing: :manual`. Jobs are asserted with `Oban.Testing` (`perform_job`,
  `assert_enqueued`); no queues or plugins run in tests.

## Consequences
- `mix ecto.migrate` is required after pulling (it creates `oban_jobs` / `oban_peers`).
- Cron runs on every node. Oban's leadership (via `oban_peers`) makes sure only one node
  inserts the cron job when we cluster.
- Job args are stored in Postgres in plain text, so jobs must never carry secrets, OTPs or
  message bodies.
