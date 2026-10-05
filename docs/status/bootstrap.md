# Bootstrap status — 2026-10-06

Run from the laptop by `prompts/00-bootstrap-from-laptop.md`. **Result: READY**, with two open items
for Harsha (see the end of this file).

## spark2 (gx10-e23a, aarch64)
| Check | Result |
|---|---|
| groups | `docker`, `sudo`, `plugdev` included |
| mise | erlang 28.5, elixir 1.19.6-otp-28, java temurin-17.0.20, gradle 8.14.5 |
| Phoenix installer | 1.8.15 |
| Android SDK | platforms;android-36, build-tools 36.1.0, cmdline-tools 23.0 |
| aapt2 | arm64 ELF (Commit451), `aapt2 version` OK |
| `~/.gradle/gradle.properties` | `android.aapt2FromMavenOverride=…/build-tools/36.1.0/aapt2`. **Was missing; added.** |
| ufw | not checked: `sudo` needs a password |
| `/srv/git` | `risime.git` (bare) and `locks`, owned by harsha |
| Checkout | `~/development/risime` cloned from `/srv/git/risime.git`; `scripts/*` executable |
| `.env` | created from `.env.example` with random secrets, mode 600, gitignored |
| Postgres | `pgvector/pgvector:pg17` (arm64), PostgreSQL 17.11, healthy, `select 1` OK |
| Cassandra | `cassandra:5.0` (arm64), Cassandra 5.0.9, healthy, `describe keyspaces` OK |
| Port binding | 5432 and 9042 listen on **127.0.0.1 only** |
| Claude Code | 2.1.289 installed in `~/.local/bin/claude`, found by login shells. **Not logged in yet.** |

## Laptop (Deepin 25, x86_64)
| Check | Result |
|---|---|
| KVM | `kvm-ok`: acceleration available; user is in `kvm` and `plugdev` |
| adb udev rules | `51-android.rules` (from `android-sdk-platform-tools-common`) |
| mise | same versions as spark2 (java 17.0.20) |
| adb | 1.0.41, platform-tools 37.0.1 |
| emulator | 37.2.12, AVD `risime_a` (pixel_8, android-36 google_apis x86_64) |
| Git | `origin = spark2:/srv/git/risime.git`; push and pull OK; laptop and spark2 are at the same commit |
| SSH tunnel | `spark2-tunnel` alias exists; SSH forwarding works (tested on spare port 14000) |

## Fixes made during bootstrap
- **`scripts/dev-tools.sh`, spark2:** cmdline-tools 23.0 `sdkmanager` wraps a native x86-64
  `android` binary and fails with `Exec format error` on aarch64. The script now skips
  sdkmanager on aarch64 when the platform and build-tools are already present. A *fresh* arm64
  box will still need an older cmdline-tools to bootstrap the SDK.
- **`scripts/dev-tools.sh`, laptop:** the laptop sets `XDG_CONFIG_HOME`, so avdmanager writes
  AVDs to `~/.config/.android/avd`, but the emulator only reads `~/.android/avd`. The script
  now exports `ANDROID_AVD_HOME` in `~/.bashrc` when that happens (already added on this laptop).
- **Git identity:** neither machine had one. Set repo-local `user.name=Harsha` and
  `user.email=harsha@codegen.co.uk` in both checkouts. Global config is untouched.

## Open items for Harsha
1. **Port 4000 clash on the laptop.** *Resolved: the tunnel now uses `LocalForward 4400`; docs
   and the Android debug default were updated to match.* Container `aoa-litellm-1` (another project) publishes
   `0.0.0.0:4000`, so `ssh -N spark2-tunnel` fails with `Address already in use`. Either stop
   that container while working on RisiMe (`docker stop aoa-litellm-1`), or move the tunnel's
   local port (for example `LocalForward 4400 127.0.0.1:4000`, then `adb reverse tcp:4000 tcp:4400`
   and emulator URL `http://10.0.2.2:4400`).
2. **Claude Code login on spark2:** `ssh spark2`, run `claude` once interactively, and complete
   the browser login.
3. **Optional:** verify ufw with `ssh -t spark2 'sudo ufw status verbose'`.
