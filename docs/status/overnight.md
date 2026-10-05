# Overnight summary — 2026-10-06 (orchestrator, cc-root window 0)

**Released `v0.2.0-nightly.1`.**
- Both gates pass, and the live interop with v1.2 passes.
- The APK is signed with the stable dev key and is in `~/risime-releases/v0.2.0-nightly.1/`.
- The test server now runs that tag from `~/risime-run/v0.2.0-nightly.1`; `/health` is ok.
- Every planned item (priorities 0–3) is done.
- All agents and scheduled checks are **stopped**, including the nightly cron (see "Scheduling" below).

## Priority 0: the test server
- **The smoke-test 500 was not a verify bug.**
  - The dev server ran from the shared checkout, and Phoenix's code reloader refused every request
    after the server session changed `mix.lock` and `config.exs` (Oban).
  - The fix: `deps.get`, `ecto.migrate` and the CQL migrate, then a restart.
  - I checked request → code → first-time verify (user created) → 200 end to end, with a
    throwaway user that was deleted afterwards.
  - Regression test added: `api_test.exs`, "first-time verify creates the user from the allowlist".
- **Decision 006:** the test server runs from `~/risime-run/<tag>` (a git worktree of the release
  tag, `.env` symlinked), started by `scripts/run-dev-server <tag>`. Only `scripts/nightly-release`
  switches it, after the gates pass and the tag is pushed. The current tag is in
  `~/risime-run/CURRENT`.

## What was built (all on `main`, all in v0.2.0-nightly.1)
| Area | What | Decision |
|---|---|---|
| Contract | **v1.2**: `presence:watch`, `typing`, ephemeral `signal` push (proposal reviewed by both roles, merged by root) | 007 |
| Server | Presence (5 s offline grace, `last_seen`), typing (2/s limit on `true`, `false` never limited) | 007 |
| Server | Oban (Postgres) with daily jobs that prune old OTP challenges and revoked tokens | 004 |
| Server | JSON logs (`LOG_FORMAT=json`, prod default; sensitive params filtered), telemetry metrics, `GET /health` | 008 |
| Server | `mix risime.loadtest` (dev-only `mint_web_socket`) plus two Cassandra pool fixes | 011 |
| Android | Release plumbing: versions from `VERSION`, release signing, `.debug` id suffix, shared network config | 003, 005 |
| Android | Settings: server URL (every build, plus a link on the login screen), profile, about, log out | 005 |
| Android | Presence dot, "last seen", "typing…"; unread badges and bold rows; day separators; Copy; Retry/Delete for failed messages; Search | 009 |
| Android | Design system: light/dark tokens, shared components, ≥ 4.5:1 text contrast (tested), 48 dp targets, TalkBack labels. The light-mode read tick is now `#9C5F00` (seed saffron failed contrast) | 009 |
| Android | Room schemas exported, with a migration guard test. The DB is still v1 (no schema change was needed) | 009 |
| Ops | `infra/docker-compose.prod.yml`, `infra/Dockerfile.server`, systemd user units, `scripts/backup` and `scripts/restore`, `docs/PROD.md`, `RisiMe.Release`. **Not deployed** | 010 |
| Crypto | `crypto/risime-mls` on OpenMLS 0.9, plus the Android binding plan | 012 |
| Root | `scripts/nightly-release`, `scripts/fetch-latest-apk`, `scripts/run-dev-server`, `docs/NIGHTLY.md`, `docs/TAILSCALE.md` | 003, 006 |

## Test and load results
- **Server gate:** 93 tests, 0 failures.
- **Android gate:** `assembleDebug testDebugUnitTest assembleRelease`, 72 tests, 0 failures.
- **Crypto gate:** `cargo fmt --check`, `clippy -D warnings` and `cargo test`: 9 of 9 pass.
  - (a) `a_create_group`;
  - (b) `b_add_member_join_from_welcome_same_epoch`;
  - (c) `c_encrypt_decrypt_both_directions`;
  - (d) `d_removed_member_cannot_decrypt_new_messages` and
    `d_removed_member_ignoring_commit_cannot_decrypt`;
  - plus tampered-ciphertext and wrong-Welcome negative tests.
- **Live interop:** the Android JVM `ApiClient` and `PhoenixRealtimeClient` against the server
  built from `main` (temporary :4100), with throwaway users deleted afterwards. All pass:
  - REST;
  - delivery in **12 ms**;
  - delivered → read;
  - idempotent resend;
  - `empty_body`;
  - offline catch-up in order;
  - AuthFailed on a bad token;
  - the presence snapshot and the online signal;
  - typing true/false without moving the cursor;
  - **offline after 5019 ms** (the 5 s grace) with `last_seen`.
- **Load** (`docs/status/loadtest.md`):

  | Run | Result |
  |---|---|
  | 200 users, 1 msg/s each (~200 msg/s), 180 s | 0 errors; reply p50/p95/p99 **4.3 / 5.6 / 6.3 ms** |
  | 2000 users, ~3200 msg/s, 60 s, before fixes | channel crashes (Cassandra pool exhausted) |
  | 2000 users, ~3200 msg/s, 60 s, after fixes | **0 errors**, reply p99 45 ms, push p99 47 ms |

  Every load-test user was deleted. The allowlist is back to you and Shenika.
- **Not yet run on a device:** the new UI, Room, and the release-build install. The device
  checklist is steps 1–17 in `docs/status/android.md`.

## Decisions made tonight
003 nightly cycle · 004 Oban · 005 Android release and Settings · 006 test server from a
release tag · 007 presence/typing · 008 observability · 009 Android presence, search and Room
migrations · 010 prod environment · 011 load-test tool · 012 E2EE binding plan.

## Needs you
1. **Install on the phones** (steps below), then run the device checks for the new features
   (`docs/status/android.md` steps 10–17).
2. **Tailscale** (sudo, `docs/TAILSCALE.md`). It's needed for Shenika's phone to reach the server
   when it isn't plugged into your laptop:
   `curl -fsSL https://tailscale.com/install.sh | sudo sh` then
   `sudo tailscale up --hostname=spark2 --operator=harsha`, then enable MagicDNS and HTTPS in the
   admin console, then `tailscale serve --bg --https=443 http://127.0.0.1:4000`.
3. **Firebase project** for FCM push (0.2), and **SMTP credentials** for real OTP email (0.2).
4. **Prod** (when you want it, `docs/PROD.md`):
   - `sudo loginctl enable-linger harsha`;
   - fill in `.env.prod`;
   - an off-box backup host (user@host:path) plus an SSH key from spark2;
   - prod SMTP;
   - a Tailscale serve port for prod (for example 8443 → 4500).
5. **E2EE questions** (decision 012):
   - May I download the official NDK r30 (~700 MB, no sudo) to test the spark2-native `.so` link?
   - Is the community arm64 NDK acceptable as a fallback, or should we use CI?
   - Should the server sign the identity binding in 0.3, or are safety numbers enough?
   - Should each device be its own MLS member?
   - For Risi's history, is a re-encrypt-and-send flow what you expect?
   - Is on-device history encryption in scope for 0.3?
6. **Back up `~/risime-keys/`** (the dev release key). If it's lost, every phone must uninstall
   the app.
7. FYI: `sdkmanager` can't run on spark2 (its cmdline-tools call an x86_64 binary). Install SDK
   packages from the laptop, or by unpacking zips.

## Install on your phone and Shenika's (laptop)
The release build is `lk.codegen.risime`, signed with the dev release key. It **can't update**
the debug-signed v0.1.0 app, so each phone needs a one-time uninstall, which wipes that phone's
local chat history. The server keeps nothing after delivery.
```bash
cd ~/development/risime && git pull
ssh -N spark2-tunnel &                       # laptop :4400 -> spark2 :4000
scripts/fetch-latest-apk                     # -> ~/risime-releases/v0.2.0-nightly.1/ (checksum verified)
adb devices                                  # note each phone's serial
# for EACH phone (plug in with USB debugging on):
adb -s <serial> uninstall lk.codegen.risime  # once: removes the debug-signed v0.1.0
adb -s <serial> install -r ~/risime-releases/v0.2.0-nightly.1/risime-0.2.0-nightly.1.apk
adb -s <serial> reverse tcp:4000 tcp:4400    # phone's 127.0.0.1:4000 -> tunnel -> spark2
```
On each phone:
1. Open RisiMe.
2. On the login screen, tap **Server · Change** and set `http://127.0.0.1:4000`.
3. Log in:
   - you: +94770802222 / harsha@codegen.co.uk;
   - Shenika: +94777888717 / shenika@codegen.net.
4. Get the code on spark2:
   `tmux capture-pane -p -t risime-server -S -500 | grep "DEV OTP" | tail -3`
   or at `http://127.0.0.1:4400/dev/mailbox` on the laptop.

`scripts/fetch-latest-apk --install` installs on every connected device in one go, after the
one-time uninstalls.

**Shenika's phone without the USB cable:** once Tailscale is set up, install the Tailscale app on
her phone and join your tailnet. Send her the APK file (from `~/risime-releases/…`) and allow
"install unknown apps". Then set the server URL to `https://spark2.<tailnet>.ts.net`.
`adb reverse` only works while the phone is plugged into the laptop.

## Scheduling
- All background agents have finished, and the deadline timer is stopped.
- **The nightly cron is deleted too:** the queue of work that doesn't need you is empty, so it
  would only run idle.
- Once you answer any of the items above, tell me "re-arm the nightly". The runbook and scripts
  stay ready (`docs/NIGHTLY.md`).
