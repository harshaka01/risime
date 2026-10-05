# Bootstrap session (laptop) — finish spark2 + laptop setup in one run
You run on Harsha's **laptop** (Deepin 25, x86_64) in `/home/harsha/development/risime`, which
holds the RisiMe starter files. Read `CLAUDE.md` first.

Two machines are reachable by SSH alias:
- `spark2` (203.115.26.139, `harsha`, ARM64, hostname `gx10-e23a`) is the RisiMe dev box.
- `spark` is NOT part of this project. Don't touch it.

## Already done on spark2 by Harsha (verify only, don't redo)
- apt packages, plus ufw (SSH only) and fail2ban
- Docker, with `harsha` in the docker group
- `/srv/git/risime.git` (bare) and `/srv/git/locks`
- mise with erlang 28.5, elixir 1.19.6-otp-28, java temurin-17 and gradle 8.14.5; Hex, Rebar,
  and `phx_new`
- Android SDK in `~/Android/Sdk`: cmdline-tools 23.0, platforms;android-36, build-tools 36.1.0
  with arm64 aapt2 (Commit451)
- `~/.gradle/gradle.properties` contains `android.aapt2FromMavenOverride`

Remote commands don't load `.bashrc`, so on spark2 always run:
`ssh spark2 'export ANDROID_HOME=$HOME/Android/Sdk; ~/.local/bin/mise exec -- <cmd>'`

## Rules
- You cannot type sudo passwords. If anything needs sudo (laptop or spark2), stop and print the
  exact command for Harsha.
- Never write secrets into git. `.env` stays only on spark2.
- Work through the tasks in order. At the end, write the report described in task 7.

## Task 1 — Verify spark2
Run a single SSH check that prints: `uname -m`, `groups`, `mise ls`, `elixir --version`,
`mix phx.new --version`, `java -version`, `aapt2 version`, the gradle.properties override,
`docker ps`, `ufw status` (with sudo, skip it if a password is needed), and `ls -la /srv/git`.
Fix anything missing using `scripts/dev-tools.sh spark2` (copy it over first), as long as no sudo
is needed.

## Task 2 — Laptop toolchain
1. Check whether `kvm-ok`, `adb` udev rules and the kvm/plugdev groups are in place. If not, tell
   Harsha to run `sudo bash scripts/laptop-root-setup.sh`, then log out and back in. Continue with
   the remaining tasks meanwhile.
2. Run `bash scripts/dev-tools.sh laptop`. This installs mise tools, the Android SDK,
   platform-tools, the emulator, the API 36 x86_64 image and the `risime_a` AVD.
3. Verify: `adb version`, `emulator -list-avds` shows `risime_a`, and `java -version` reports 17.

## Task 3 — Git: laptop → origin on spark2
1. In `/home/harsha/development/risime`, check that `.gitignore` exists, then run `git init -b main`
   (if it isn't a repo yet).
2. Commit: `chore(root): starter`.
3. `git remote add origin spark2:/srv/git/risime.git` (or set-url), then `git push -u origin main`.
4. On spark2: `git clone /srv/git/risime.git ~/development/risime` (if it already exists, make sure
   its origin is `/srv/git/risime.git` and run `git pull`), then `chmod +x scripts/*`.

## Task 4 — Databases on spark2
1. Create `~/development/risime/.env` from `.env.example` on spark2:
   - `SECRET_KEY_BASE=$(openssl rand -base64 48)`
   - `POSTGRES_PASSWORD=$(openssl rand -hex 24)`
   - `OTP_DEV_LOG=true`
   - `chmod 600 .env`
2. `docker compose --env-file .env -f infra/docker-compose.dev.yml up -d`
3. Wait until both containers are healthy (Cassandra can take about 2 minutes).
4. Verify:
   - both images are `arm64` (`docker image inspect -f '{{.Architecture}}'`);
   - `docker exec` runs `psql -U risime -d risime_dev -c 'select 1'` and
     `cqlsh -e 'describe keyspaces'` successfully;
   - `ss -tlnp` shows ports 5432 and 9042 bound **only to 127.0.0.1**.

## Task 5 — Claude Code on spark2
1. Check `ssh spark2 'command -v claude || ls ~/.local/bin/claude'`.
2. If it's missing, install it with the official native installer:
   `ssh spark2 'curl -fsSL https://claude.ai/install.sh | bash'`
3. Tell Harsha he must run `claude` **once interactively** on spark2 to log in, because login
   needs a browser link and you can't complete it.

## Task 6 — Laptop helpers
1. Confirm `scripts/install-apk` and the `spark2-tunnel` SSH alias exist. Test the tunnel briefly
   (`ssh -N -o ExitOnForwardFailure=yes spark2-tunnel & sleep 3; kill %1`).
2. `git pull` works on the laptop, and the laptop and spark2 are at the same commit.

## Task 7 — Report
Write `docs/status/bootstrap.md` (versions, checks passed, anything Harsha must still do), then
commit and push it. Finish by printing, for Harsha:
1. any sudo commands he still needs to run;
2. the `claude` login step on spark2;
3. the project start commands:
   ```
   ssh spark2
   tmux new -s cc-root
   cd ~/development/risime && claude     # "Read prompts/00-root.md and execute it."
   ```
