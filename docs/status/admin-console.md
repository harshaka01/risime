# Admin console: status

Decision 089. Server only (Phoenix LiveView in the existing release). Not live on the pilot until the next
nightly release, the Caddy change, the Keycloak redirect URI and the `.env` line below.

## What exists
- **https://risime.risicloud.ai/admin**: the shell, with nav entries for Speech models, Intelligence, Agents,
  Servers, and Users and admins. Only Speech models is built; the others say "Coming soon."
- **Sign-in:** the app's Keycloak (realm `aoa`, client `risime`), browser authorization code + PKCE. The
  account must already be a RisiMe account (signed in once in the app) and an active admin. Anyone else gets a
  plain `403 Forbidden`. The session is an encrypted cookie (Secure, HttpOnly, SameSite=Lax) that lasts 12 h.
- **Tables** (migration `20261107120000_admin_console`, additive only):
  - `admins`: user_id, added_by, added_at, removed_at, removed_by.
  - `admin_audit_log`: append-only; a database trigger refuses UPDATE and DELETE.
  - `admin_settings`: runtime settings, cached for 5 s.
- **Audit log:** sign-in, sign-out, every setting change (before → after), every speech test (clip name), and
  admin seed, add and remove. The last 15 entries are shown on the Speech models page.

## How to sign in (first time)
1. Harsha: add this line to `~/development/risime/.env`, using the email of your RisiMe (Keycloak) account:
   `RISIME_ADMIN_SEED_EMAIL=<your RisiMe account email>`
   Optional, for the Test button: `RISI_ADMIN_TEST_CLIPS_DIR=/home/harsha/development/risime/infra/whisper/testdata`
2. Root (needs sudo): in `/etc/caddy/Caddyfile` (and `infra/caddy/Caddyfile`), change
   `@app path /api/* /socket/* /health` to `@app path /api/* /socket/* /health /admin /admin/*`, then
   `sudo systemctl reload caddy`.
3. Keycloak admin, client `risime`:
   - add the valid redirect URI `https://risime.risicloud.ai/admin/auth/callback`;
   - add the web origin `https://risime.risicloud.ai`;
   - make sure the standard flow is on.
4. After the nightly release restarts the pilot, open `/admin` and sign in. On boot, or at this first sign-in,
   your account becomes the first admin (audit-logged). From then on the seed line does nothing; it can stay
   or be removed.

## Page 1: Speech models
- For each language (Sinhala, Tamil, English, Mixed / Singlish), choose **Auto** or an installed model (the
  list comes live from the speech service's `/v1/models`). Saving applies within 5 s with no deploy. A manual
  choice beats Auto until it is set back to Auto.
- **Auto** is today's production route, byte-identical: the speech service detects the language and routes
  (`WHISPER_ROUTE_SI/TA`, hint mode `prior`). Parity tests cover this. Once the Voice agent has a
  per-model transcription Intelligence Index, Auto will follow the Index and live corrections. It will switch
  only through the promotion rule and post a note in Harsha's Risi chat (decision 089, "Agent design").
- **Mixed / Singlish** covers the chunks the detector is unsure about (low confidence). Today these go to
  faster-whisper-large-v3.
- **Test:** pick a dev clip from `RISI_ADMIN_TEST_CLIPS_DIR` and every candidate (the service's compare set)
  runs side by side, showing its text and seconds. The page never reads customer voice notes.

## Operating notes
- Add or remove admins over rpc until the Users and admins page exists (both are audit-logged; the last admin
  can never be removed):
  `ERL_EPMD_ADDRESS=127.0.0.1 bin/risime rpc 'RisiMe.Admin.add_admin("<user id>", "<your user id>")'`
- Each setting can also come from the environment (`RISI_SPEECH_ROUTE_SI=<model>`). The table wins over the
  environment.
- A manual model adds one language detection per chunk (the detector is already resident). If the model
  is missing, the chunk falls back to Auto.

## What's next
- Intelligence: the per-capability Index (decision 085) and the scoreboard.
- Agents: flags (`RisiMe.Agents.Flags`), shadow/canary state and runs.
- Servers: health, releases, backups.
- Users and admins: add/remove admins, allowlist, disable.
- Voice agent Auto: score each speech model per language and switch through the promotion rule.
- LiveView test coverage of the connected socket (needs the `lazy_html` test dep); today the page's events
  are tested directly and the pages through HTTP.
