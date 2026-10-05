# Root / integration session — Release 0.1
You run on **spark2** as `harsha` in `/home/harsha/development/risime`, on branch `main`, in
tmux window 0 of session `cc-root`. The server and android sessions run in windows 1 and 2 of
the same checkout. First read `CLAUDE.md`, `docs/RELEASE-0.1.md`, `contract/v1/PROTOCOL.md` and
`BACKLOG.md` in full.

## 1–3. Verify the bootstrap (don't redo it)
The laptop bootstrap session already set up the toolchain, the repo and the databases (see
`docs/status/bootstrap.md`). Check quickly:
- `git remote -v` shows origin `git@github.com:harshaka01/risime.git` and `spark2-backup`
  `/srv/git/risime.git`, and `git status` is clean.
- `mise ls` lists erlang, elixir, java and gradle; `aapt2 version` runs; the gradle.properties
  override exists.
- `docker compose -f infra/docker-compose.dev.yml ps` shows both containers healthy, and `.env`
  exists.

If anything is missing, fix it with `scripts/dev-tools.sh spark2`, or tell Harsha if it needs sudo.

## 4. Hand Harsha the next steps
Print a short block telling him to:
- open tmux window 1 (`Ctrl+B` then `C`), run `claude`, and say
  "Read prompts/10-server.md and execute it.";
- open window 2 the same way, and say "Read prompts/20-android.md and execute it.";
- on the laptop, once you have pushed: `git clone git@github.com:harshaka01/risime.git ~/development/risime`,
  or switch an existing mirror (SETUP.md "Git remotes").

## 5. Integration loop (about every 10 minutes until release)
- `git pull --rebase`, then review new `server/` and `android/` commits against the contract.
  - If either side diverges, record it in `docs/integration-notes.md`, commit it, and tell Harsha
    which window must fix what.
  - If a contract proposal appears, evaluate it. If it's sound, apply it to `contract/v1`, bump
    the minor version, commit, and tell both sessions.
- Watch for one session staging the other's files. Fix it, and remind that session of the
  path-scoped staging rule.
- Run both gates yourself:
  - `cd server && ~/.local/bin/mise exec -- mix test`
  - `cd android && ./gradlew assembleDebug testDebugUnitTest`

## 6. Release gate
When both `docs/status/server.md` and `docs/status/android.md` say READY, work through the
"Definition of done" in `docs/RELEASE-0.1.md`:
1. Rerun both gates on `main`, and restart `risime-server` from the latest `main`.
2. Walk Harsha through the laptop side (SETUP.md step 9): start the tunnel, `git pull`, run
   `scripts/install-apk`, set up the emulator and USB phone, then go through the smoke test
   checklist one item at a time.
3. Tag `v0.1.0` and push the tag. Write `docs/releases/v0.1.0.md`, and copy the APK to
   `~/risime-releases/v0.1.0/`.
