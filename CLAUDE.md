# RisiMe — Claude Code Operating Manual
Read this whole file at the start of every session. Then read the prompt file for your role.

## What RisiMe is
An agent-native communication network ("Talk. Connect. Act.").
- Phase 1: corporate messaging for the CodeGen and Rise teams.
- Identity: phone number, as on WhatsApp.
- Target design:
  - end-to-end encryption with MLS;
  - the "Risi" agent joins chats as a visible, encrypted member and tracks commitments
    (the Commitment Ledger);
  - an on-device client agent that learns its user's behaviour;
  - a digital twin;
  - an own-model cascade (commercial LLM first, own L1/L2 models take over task by task).

## Roles, hosts, directories
All three sessions run on **spark2** (DGX Spark, **ARM64**, 203.115.26.139) as user `harsha`,
in one checkout: `/home/harsha/development/risime`, branch `main`.

| Role | Owns (commit only these paths) |
|---|---|
| root / integration | `contract/`, `docs/`, `infra/`, `scripts/`, top-level files |
| server | `server/`, `docs/status/server.md` |
| android | `android/`, `docs/status/android.md` |

- **Laptop** (Deepin 25, x86_64) has the same path, `/home/harsha/development/risime`, as a Git
  mirror. It also runs the emulator, the USB phone and the `spark2-tunnel`.
- **Git origin:** GitHub, `git@github.com:harshaka01/risime.git` (spark2 and laptop).
  `spark2-backup` = the bare repo `/srv/git/risime.git` on spark2 (`spark2:/srv/git/risime.git`
  from the laptop); `scripts/risi-handoff` mirrors to it when present.
- **Handoff locks** stay in `/srv/git/locks/<role>.lock` on spark2 (not in Git).
- **Shared checkout:** stage only your own paths (`git add server/`, never `git add -A`), then
  `git pull --rebase` before every push. Root may touch other paths only to fix integration
  breakage, and must say so in the commit message.
- **Non-interactive SSH** does not load `.bashrc`. Use `~/.local/bin/mise exec -- <cmd>`.

### Android on ARM64 (spark2)
Google ships the Android build tools for linux-x86_64 only.
- spark2 uses drop-in arm64 binaries from `Commit451/android-arm-build-tools`, installed by
  `scripts/dev-tools.sh spark2`.
- AGP 9 fetches its own x86 aapt2, so `~/.gradle/gradle.properties` **on spark2 only** sets
  `android.aapt2FromMavenOverride=<sdk>/build-tools/<ver>/aapt2`.
- **Never** put this override in the repo's `gradle.properties`, because it would break laptop
  builds.
- There is no emulator on spark2. Run apps on the laptop emulator or a USB phone with
  `scripts/install-apk`.

## Golden rules
1. **Contract first.** `contract/v1/PROTOCOL.md` is the single source of truth for the wire
   protocol. Server and Android never diverge from it, and only the root session edits it.
   If you need a change, write `contract/proposals/<date>-<topic>.md`, commit, stop, and tell Harsha.
2. **Small green chunks.** For each chunk: implement, run the test gate, stage only your own
   paths, commit, `git pull --rebase`, push. Never hold more than 30 minutes of uncommitted work.
3. **Handoff.** On start, run `scripts/risi-resume <role>`. Before stopping, or when Harsha says
   "handoff", run `scripts/risi-handoff <role>`.
4. **No root, no sudo.** If something needs sudo, stop and give Harsha the exact command.
5. **Secrets live only in `.env`** (gitignored). Never commit keys, tokens, OTPs, or real phone
   numbers or emails.
6. **Bind every service to 127.0.0.1.** spark2 has a public IP, and Docker port publishing
   bypasses ufw, so compose ports must be written as `"127.0.0.1:PORT:PORT"`.
   This includes Erlang's epmd (port 4369), which listens on 0.0.0.0 by default. Run any named
   node or `bin/risime` release on the host with `ERL_EPMD_ADDRESS=127.0.0.1`.
7. **spark2 is aarch64.** Use multi-arch images only.
8. **Decide and record.** Don't add a framework outside the stack below without a short note in
   `docs/decisions/NNN-title.md`. Ask Harsha only when blocked (sudo, credentials, product
   decisions). Otherwise decide, record the decision, and continue.

## Stack
**Server**
- Elixir/Phoenix: app `:risime`, module `RisiMe`, in folder `server/`.
- Postgres 17 (pgvector image) for relational data: users, allowlist, sessions, and later the
  Commitment Ledger, Oban jobs and pgvector memory.
- Cassandra 5 via Xandra for event and message streams, and later the agent learning log.
- Later: Rust (OpenMLS crypto core, media, heavy processing) via Rustler NIFs or NATS services.
- Python only for model serving (vLLM), and only later.

**Android:** Kotlin, Jetpack Compose (Material 3), kotlinx.serialization, OkHttp, a Phoenix
channels client, Room, DataStore, coroutines/Flow. Folder `android/`.

**Agents (later):** our own Elixir harness, not LangGraph:
- a model router that chooses commercial or own L1/L2 models by confidence;
- an MCP tool registry;
- pgvector memory;
- a learning log in Cassandra.

## Architecture rules that must hold from day one
- **Storage boundary.** Message storage sits behind the `RisiMe.Messaging.Store` behaviour, with
  a Cassandra implementation. No other module touches Cassandra for messages.
- **Cassandra modelling.** Write the queries first, then the tables, one table per query.
  - Time-series tables use TimeWindowCompactionStrategy and TTLs.
  - Never use `ALLOW FILTERING` or secondary indexes.
- **Store-and-forward.** The server queues events for each user until they are fetched; the
  client keeps the history.
- **Encryption banner.** E2EE (MLS) arrives in 0.3. Until then the app shows
  "Dev build — not end-to-end encrypted". Never remove this banner before E2EE ships.
- **Learning log.** Every future model call is recorded in it (input, output, model, latency,
  cost, user feedback). Keep APIs shaped so this can be added.
- **On-device data stays on-device.** Behaviour data never leaves the device unless the user
  explicitly enables twin sync (later).
- **Visible agents.** Any agent that joins a chat is visible to every member.

## Environments
Dev only for now: Postgres DB `risime_dev`, Cassandra keyspace `risime_dev`.
Tests use `risime_test`. Prod comes later in separate containers; never point dev code at prod.

## Test gates (a chunk is not done until these pass)
- **Server:** `mix format --check-formatted && mix compile --warnings-as-errors && mix test`
- **Android:** `./gradlew assembleDebug testDebugUnitTest`
- **Contract:** the server tests cover every event in PROTOCOL.md, and the Android tests parse
  every JSON file in `contract/v1/examples/`.

## Commits and releases
- Commit messages: `feat(server): …`, `fix(android): …`, `chore(root): …`. `wip(...)` commits
  are allowed only for a handoff, and must still build.
- When a role is finished, it writes `READY` plus run notes to `docs/status/<role>.md`.
- Root runs the full gates on `main`, tags `vX.Y.Z`, writes `docs/releases/vX.Y.Z.md`, and
  copies the APK to `~/risime-releases/vX.Y.Z/` (outside git).

## Useful commands
- Databases (spark2): `docker compose --env-file .env -f infra/docker-compose.dev.yml up -d`
- Pilot server (spark2): `scripts/run-server [<tag>]` runs a prod-mode release of the latest green tag from `~/risime-run/<tag>` on 127.0.0.1:4000, behind Caddy at https://risime.risicloud.ai (docs/decisions/006, 023). Only `scripts/nightly-release` switches it.
- Android build (spark2): `cd android && ./gradlew assembleDebug testDebugUnitTest`
- Install on devices (laptop): `git pull && scripts/install-apk`
- Tunnel (laptop): `ssh -N spark2-tunnel` (`LocalForward 4400` → spark2 `127.0.0.1:4000`)
- Emulator server URL: `http://10.0.2.2:4400` (the Android debug default)
- USB phone: `adb reverse tcp:4000 tcp:4400`, then use `http://127.0.0.1:4000`
