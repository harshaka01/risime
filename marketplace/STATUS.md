# Marketplace status

**Role:** `marketplace` (RisiMe decision 095, this project's decision 001). **Updated:** 2026-10-16.

**Test gate (2026-10-16, spark2):** `mix format --check-formatted && mix compile --warnings-as-errors && mix test`: green, 58 tests, 0 failures. Dev smoke on 127.0.0.1:4600: `/health`, discover, status (Ask: `callable: false`, `not_active:proposed`), sign-in page 200, agents page redirects when signed out, login 503 until `OIDC_ISSUER` is set.

## Phase 0: the registry service (architect 22:05) — built

| Item | Where | State |
|---|---|---|
| Registration record and status (proposed/active/suspended/delisted) | `Registry.Agent`, `Registry.set_status/4` | done; transitions limited, activation needs an approved card, `private_chats` refused by changeset and DB check |
| Owner keys with rotation | `Cards.Keys` | done; `risi-keys.json` mirrored, several keys with `kid` + `expires`, dropped keys retired, private material refused |
| Card fetch, RFC 8785, JWS, `card_hash` (hash excludes the signature) | `Cards.JCS`, `Cards.Verifier`, `Cards.Signer` | done; RFC 8785 test vectors pass; re-signing with another listed key keeps the hash and stays verified; any content change drops to unverified |
| Status on every call, card cached 5 min | `Registry.check/1`, `Cards.Cache` | done; status read from the DB every call, verified card cached per `{agent, card_hash}` |
| Delisting with an audit row; content-free audit lines | `Registry.delist/3`, `Audit` | done; who + why on every status change; call lines whitelist who/agent/skill/company/result/latency/request id; no content column exists |
| Sandbox runner skeleton | `Sandbox`, `Sandbox.AnswerGuard` | done; cases `injection`, `oversize`, `malformed`, `slow`, `tool_steer` (tries to make Risi call another tool), plus `card_text` (hidden instructions in card text, contract section 9); failure suspends with audit rows. Default client is `DisabledClient`: **no calls to real agents in phase 0** |
| Read-only discover/get/status API | `AgentApiController` | done; `/api/v1/agents`, `/api/v1/agents/<domain>/<slug>`, `.../status` |
| Seed: RisiWork Ask as first Official entry, data only | `Seeds` | done; `proposed` + `unverified`, no `card_hash`, so not discoverable or callable until RisiWork publishes a signed card and a person approves and activates it |

## Phase 1, first piece: the signed-in page shell (architect 22:10) — built, loopback only

- OIDC code + PKCE (S256), public client `risi-marketplace`, realm `aoa`, redirect
  `https://risicloud.ai/marketplace/auth/callback`; issuer, client id and redirect from `OIDC_ISSUER`,
  `OIDC_CLIENT_ID`, `OIDC_REDIRECT_URI`. Tested against a stub Keycloak (discovery, JWKS, token endpoint). ID token
  checked for signature, issuer, audience/azp, expiry, nonce; `email_verified` required.
- Allow-list in the `allowed_accounts` table, seeded once from `ALLOW_LIST_SEED`; re-checked on every request.
  Non-listed accounts get a polite "Not open yet" page and no data.
- Every sign-in callback writes an audit row (`sign_in`, subject + result code; no address, no token).
- Read-only "Agents" page at `/marketplace/agents`.

## Setup done on spark2
- Postgres role `risi_registry` (LOGIN, no superuser, no CREATEDB) and databases `risi_registry_dev`,
  `risi_registry_test` on `risime-dev-postgres-1`, owned by that role, `CONNECT` revoked from PUBLIC. The password is
  only in `marketplace/.env` (gitignored).
- Nothing installed: `rel/risi-registry.service` is written, not copied or enabled.

## Open items
1. **Bridge confirmations** (listed in `CONTRACT.md`): where the signature lives (A2A `signatures` array), `ES256`/
   `EdDSA` only, the `risi-keys.json` shape. The contracts are still drafts until both architects write "agreed".
2. **Keycloak client `risi-marketplace`** doesn't exist yet (RisiWork, or Harsha with kcadm). Needs: public client,
   standard flow only, PKCE S256 required, redirect URI exactly `https://risicloud.ai/marketplace/auth/callback`,
   web origin `https://risicloud.ai`.
3. **The route from spark** (proposal below).
4. **`ALLOW_LIST_SEED`** must be set in the prod environment file before the first seed run.

## Phase 1: what's left
- The LAN listener for spark's Caddy (after Harsha's ufw step and the Caddy route), and a prod release + install.
- Admin actions on the page (approve card, activate, suspend, delist, add to allow-list), each audited with who and why.
- Owner registration form (name, card URL, what it does, what it needs) and the Verified tier: domain challenge by DNS
  TXT or `/.well-known/risi-challenge/<token>`, then the sandbox suite.
- A real sandbox client and nightly runs (after the transport is agreed and the brain gate passes).
- Rate limits and the circuit breaker (contract section 10).

## Proposal: the safest link from spark's Caddy to the marketplace on spark2
For root to copy into needs-you. Don't touch spark; the Caddy side is agreed by the architects with RisiWork.

**A (preferred): a LAN-only listener, firewalled to spark.**
- The marketplace gets a second listener on spark2's private address only, `10.20.20.15:4601`, that serves **only**
  `/marketplace/*` (the page shell). The registry API stays on 127.0.0.1:4600 and is never reachable from spark.
- ufw admits only spark's LAN address to that port. No new public port; nothing in Docker, so ufw applies.
- spark's Caddy: `handle /marketplace* { reverse_proxy 10.20.20.15:4601 }` (no prefix strip; the app serves under
  `/marketplace`). TLS ends at spark's Caddy; the LAN hop is plain HTTP inside the /26.
- **Check first (from spark):** decision 015 found `10.20.20.14` unreachable from spark2 on 2026-10-06. Harsha, on
  spark: `ping -c3 10.20.20.15 && nc -zv 10.20.20.15 22`. If that fails, the network team must allow spark↔spark2 on
  the LAN, or use B.
- **Harsha's exact command on spark2** (once the listener exists and the LAN check passes):
  ```
  sudo ufw allow proto tcp from 10.20.20.14 to 10.20.20.15 port 4601 comment 'risi-marketplace: spark Caddy only'
  ```
  To undo: `sudo ufw delete allow proto tcp from 10.20.20.14 to 10.20.20.15 port 4601`.

**B (fallback if the LAN stays closed): through spark2's existing public Caddy, no new port.** spark2's Caddy already
serves 443 for `risime.risicloud.ai`. Add a `/marketplace*` handle there that answers only spark's egress IP
(`remote_ip` matcher) and requires a shared header secret set by spark's Caddy; everything else gets 404. TLS end to
end. Weaker than A (the path sits on a public listener), so only if A is impossible.
