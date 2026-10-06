# RisiCloud integration (spark / risicloud.ai)

> **2026-10-06, decision 023:** option 2b is dropped. The API is served from spark2 at
> `https://risime.risicloud.ai` (see `infra/caddy/`). On spark, **only the `/app/risime/` downloads
> route (§1) is used** (it is live). §2 and the tailnet steps below are history.

Prepared config for **option 2b** (decision 017). **Not switched on.**
Nothing in this folder is applied automatically. The RisiCloud lead applies the Caddy part on spark.

## What each side does
| Piece | Where | Owner | State |
|---|---|---|---|
| Keycloak client `risime` (public, PKCE S256, redirects `ai.risicloud.risime[.debug]://callback`, post-logout `…://logout`, `email` scope in access tokens, audience/azp `risime`) | spark, realm `aoa` | RisiCloud lead | **waiting** |
| `/app/risime/` downloads route (`Caddyfile.risime` §1) | spark Caddy | RisiCloud lead | **waiting** |
| rrsync-restricted key in `~/.ssh/authorized_keys` (decision 015) | spark | Harsha | **waiting** (spark refuses the key today) |
| `/risime/` API route (`Caddyfile.risime` §2, commented out) | spark Caddy | RisiCloud lead | prepared, **off** |
| spark joins the tailnet (to reach spark2's `tailscale serve`) | spark | RisiCloud lead / Harsha | not started |
| `tailscale serve --bg --https=443 http://127.0.0.1:4000` on spark2 | spark2 | Harsha (sudo install, `docs/TAILSCALE.md`) | not started |
| Server with `OIDC_ENABLED=true`, `DEV_LOCAL_AUTH=false` | spark2 test server | root (orchestrator) | after the Keycloak client exists |

## Switching option 2b on (when Harsha says so)
1. spark2: install Tailscale and run `tailscale serve` (`docs/TAILSCALE.md`), then note `spark2.<tailnet>.ts.net`.
2. spark: join the tailnet, then check `curl -sI https://spark2.<tailnet>.ts.net/health` from spark.
3. RisiCloud lead: uncomment §2 in `Caddyfile.risime`, set `RISIME_UPSTREAM`, and reload Caddy.
4. From any phone: `https://risicloud.ai/risime/health` → 200; the app's server URL is
   `https://risicloud.ai/risime` (the release default).
5. Roll back by commenting §2 out again. Nothing changes on spark2.
