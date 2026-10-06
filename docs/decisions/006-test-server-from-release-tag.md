# 006 — The test server runs from a release-tag worktree, not the shared checkout

**Amended by decision 023 (2026-10-06):** the server now runs as a **prod-mode release**
(`MIX_ENV=prod mix release`) built in the tag's worktree and started by `scripts/run-server <tag>`,
behind Caddy at https://risime.risicloud.ai. The worktree-per-tag rule and "only nightly-release
switches it" are unchanged.

## Context
On 2026-10-06, Harsha's smoke-test login (`POST /auth/verify`) returned HTTP 500. The dev server
ran in dev mode from the shared checkout. The nightly server session had changed `mix.lock` and
`config/config.exs` there (Oban). From then on, Phoenix's code reloader refused every request
with "you must restart your server". The verify code itself was fine; a regression test for
first-time verify was added anyway.

## Decision
- The server Harsha tests against (tmux `risime-server`, 127.0.0.1:4000) runs from a **separate
  git worktree of the latest release tag**: `~/risime-run/<tag>`, detached at the tag, with
  `.env` symlinked from the repo. The tag in use is in `~/risime-run/CURRENT`.
- `scripts/run-dev-server [<tag>]` creates the worktree if needed, runs `deps.get`, `compile`,
  `ecto.migrate` and the CQL migrate, restarts tmux `risime-server`, and waits for a 401 on
  `/api/v1/me`.
- **It is switched only by `scripts/nightly-release`,** after both gates pass and the tag is
  pushed. Sessions never restart it, and never run a server from the shared checkout on port 4000.
- Sessions that need a live server for their own testing (for example the load test) start a
  temporary instance from their checkout on another loopback port, such as 127.0.0.1:4100, and
  stop it afterwards.
- Migrations stay additive. The dev DB may be migrated ahead of the running tag, because sessions
  run `mix ecto.migrate` from `main`.

## Consequences
- The server Harsha tests always matches a tagged, gated build, and the matching APK is in
  `~/risime-releases/<tag>/`.
- Each new tag compiles once into its own worktree (about a minute). Old worktrees can be removed
  with `git worktree remove ~/risime-run/<old tag>`.
