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

## How to sign in (first time): Harsha's steps (one block)
Status 2026-10-14: the seed line is in `.env` and the first admin was seeded at the nightly.7 boot (01:43 UTC).
The repo Caddyfile (`infra/caddy/Caddyfile`) already routes `/admin`; the live `/etc/caddy/Caddyfile` doesn't yet
(it is also missing the repo's `/livekit` route, which this copy adds too).

**A. Caddy (sudo, on spark2):**
```
sudo caddy validate --config /home/harsha/development/risime/infra/caddy/Caddyfile --adapter caddyfile \
  && sudo cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.bak-2026-10-14 \
  && sudo cp /home/harsha/development/risime/infra/caddy/Caddyfile /etc/caddy/Caddyfile \
  && sudo systemctl reload caddy && curl -sI https://risime.risicloud.ai/admin | head -1
```
Expect `HTTP/2 302` (a redirect to sign in), not 404. Rollback:
`sudo cp /etc/caddy/Caddyfile.bak-2026-10-14 /etc/caddy/Caddyfile && sudo systemctl reload caddy`.

**B. Keycloak (admin console at https://risicloud.ai/admin, realm `aoa`):**
1. Clients → `risime` → Settings.
2. **Valid redirect URIs:** add `https://risime.risicloud.ai/admin/auth/callback` (keep the existing app entries).
3. **Web origins:** add `https://risime.risicloud.ai`.
4. **Capability config:** Standard flow **On**. Client authentication: leave as it is. If it is **On**
   (confidential client), copy the client secret (Credentials tab) into `.env` as `ADMIN_OIDC_CLIENT_SECRET=…`
   and tell root (a restart picks it up).
5. Save.

**C. Try it:** open https://risime.risicloud.ai/admin and sign in with your RisiMe account. You land on Speech
models. Anyone who isn't an admin gets a plain 403.

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

- **Models table:** Qwen-Sinhala, omniASR 1B/3B/7B, Whisper, Tamil models and Phone (on-device). Each shows
  the aliases the speech service serves, or "not installed". Phone is on the phone only.

## Page 2: Voices (text-to-speech), `/admin/voices`
- For each language (Sinhala, Mixed / Singlish, Tamil, English), choose Auto or an installed voice. Saving
  applies within 5 s, with no deploy, through the same settings table and cache.
- Engines: Kokoro, Orpheus, Chatterbox, MMS, Indic Parler and Phone TTS. The page lists only engines
  registered in `config :risime, :risi_tts_engines` as installed. Today none are registered, so all show
  "not installed".
- **For the Kokoro service builder:** implement the `RisiMe.Agent.TTS` behaviour (`engine/0`, `voices/0`,
  `synthesize/3` returning `%{audio, content_type, first_audio_ms, total_ms}`) and add the module to
  `:risi_tts_engines`. Before speaking, call `RisiMe.Agent.TTS.voice_for(lang)`: `:auto` means use your own
  default voice, and `{:manual, id}` means use that voice.
- **Test:** type a sentence. Every installed voice speaks it, with the time to first audio and a player. The
  sentence is not stored.

## Auto (both pages)
Auto is the default. With no per-model Intelligence Index yet, it is today's route exactly (parity tests). The
plan is in decision 089: the Voice agent writes `risi_agent_scores` rows (`voice` / `index_stt`,
`index_tts`), switches only through the promotion rule, and posts in Harsha's Risi chat when it switches.
Each page shows Auto's current basis.

## Operating notes
- Add or remove admins over rpc until the Users and admins page exists (both are audit-logged; the last admin
  can never be removed):
  `scripts/risi-rpc 'RisiMe.Admin.add_admin("<user id>", "<your user id>")'`
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
