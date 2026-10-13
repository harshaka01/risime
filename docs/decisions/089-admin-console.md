# 089: Admin console (Phoenix LiveView at /admin), page 1: Speech models

**Status:** accepted 2026-10-13 (Harsha, Part 12). Server only; no wire-protocol change.

## Context
Harsha needs one place to run RisiMe without a deploy or an rpc: first the speech models per language,
later Intelligence, Agents, Servers, and Users and admins. It has to sit on the existing pilot (one
release on 127.0.0.1:4000 behind Caddy) and the existing Keycloak, and must never show customer content.

## Decision
1. **Phoenix LiveView inside the existing release** (framework note, CLAUDE.md rule 8).
   `phoenix_live_view` and `phoenix_html` were already in the release through LiveDashboard (decision 075);
   they are now direct deps. No asset pipeline: `/admin/assets/app.js` serves the release's own prebuilt
   `phoenix.min.js` + `phoenix_live_view.min.js` plus a few lines that connect `/admin/live`.
   Nothing new listens anywhere (rule 6): same endpoint, same port, loopback only.
2. **Routes:** `/admin` and `/admin/speech` (Speech models), `/admin/{intelligence,agents,servers,users}`
   ("coming soon"), `/admin/sign-in`, `/admin/auth/login`, `/admin/auth/callback`, `POST /admin/sign-out`,
   `/admin/assets/app.js`, the LiveView socket `/admin/live`.
3. **Sign-in: the app's Keycloak, browser authorization code + PKCE (S256).** Same realm and client
   (`OIDC_ISSUER`, `OIDC_CLIENT_ID=risime`). `state` and the PKCE verifier wait in the session (single use,
   10 min). The code is exchanged on the server and the access token is verified by the app's own rules
   (`RisiMe.Auth.JWT`: realm JWKS, issuer, client, expiry, verified email), then dropped (never stored or
   logged). The identity must map to an existing RisiMe account by `keycloak_sub`. Keycloak only: no
   Google token ever reaches the server. Redirect URI: `<public URL>/admin/auth/callback`.
4. **Session:** the endpoint's cookie session, now signed **and encrypted**, `Secure`, `HttpOnly`,
   `SameSite=Lax`, 12 h. It holds only the admin's user id and the sign-in time; renewed at sign-in (no
   fixation). CSRF: `protect_from_forgery` on every console route and the LiveView socket's `_csrf_token`.
   CSP: scripts only from `'self'`, socket same origin, `frame-ancestors 'none'`; `Cache-Control: no-store`.
   The app itself still uses Bearer tokens only, so nothing else uses this cookie.
5. **Admins live in the database, never in code.** `admins` (user id, added_by, added_at, removed_at,
   removed_by; one active row per user). `admin?` is checked on every request, every LiveView mount and
   before every LiveView event, so a removal takes effect at once. Non-admins get a plain `403 Forbidden`.
6. **One-time seed:** `RISIME_ADMIN_SEED_EMAIL` (`.env`). At boot, and at console sign-in, while **no admin
   row has ever existed**, the RisiMe account with that email becomes the first admin (`admin_seed` in the
   audit log, actor = server). Once any admin row exists (even a removed one), the setting does nothing.
7. **Append-only audit log** `admin_audit_log` (actor, action, target, before, after, at). A database
   trigger refuses UPDATE and DELETE. Every admin action is logged: sign-in, sign-out, each setting change
   (before → after), each speech test (clip name only), admin add/remove/seed.
8. **Runtime settings** `admin_settings` (`RisiMe.Admin.Settings`): table **>** env `RISI_<KEY>` **>**
   default, 5-s ETS cache per node, like `RisiMe.Agents.Flags`. A change applies within 5 s, no deploy.
9. **No customer message content anywhere in the console.** The speech Test reads only the dev test-clip
   directory (`RISI_ADMIN_TEST_CLIPS_DIR`, top-level audio files chosen by exact name, no symlinks, ≤ 25 MB),
   never voice notes. The audit log stores labels (setting values, model aliases, clip names), never text.

### Page 1: Speech models
- Per language: **Sinhala, Tamil, English, Mixed / Singlish**, choose **Auto** or an installed model (the
  speech service's `GET /v1/models`, filtered to that language plus multilingual models; Mixed lists all).
  Setting key `speech_route_<lang>`; env fallback `RISI_SPEECH_ROUTE_<LANG>`.
- **Mixed / Singlish** = the chunks the service's detector is not sure about (`reason=low_confidence`),
  which today go to the default model (faster-whisper-large-v3) with no forced language.
- **Where it is applied (one hook):** `RisiMe.Agent.Speech.Http.transcribe/2` asks
  `RisiMe.Agent.SpeechRoutes.pick/2` first. All on Auto → `:auto` with **no I/O** and today's request is
  sent byte for byte (`model=auto`, same fields; parity tests). With a manual model somewhere, `pick/2`
  first asks the service's `POST /v1/audio/detect` (same `language_hint`, so the `prior` hint mode is kept)
  for the routing decision; if that language has a manual model, the chunk goes to that model by name
  (with `language` forced for si/ta/en, unforced for mixed). Otherwise, on no speech, a failed detect, or a
  manual model the service no longer serves, the chunk takes the Auto path unchanged. Cost of a manual
  choice: one extra detection per chunk (detector already resident). Compare mode is unchanged.
- **Manual beats Auto** until set back to Auto.
- **Test:** pick a dev clip and run `RisiMe.Agent.Speech.compare/2`: every candidate side by side with its
  text and seconds, marking the model Auto would use (the service's own compare set, including any
  candidates added there later, e.g. omniASR).

- **Model families (Part 12):** the page also lists Qwen-Sinhala, omniASR 1B/3B/7B, Whisper, the Tamil
  models and Phone (on-device). A family counts as installed only when the speech service serves an alias
  that matches it. Phone is the phone's own recogniser (developer flag `phone_stt`) and is never a server model.

### Page 2: Voices (text-to-speech), `/admin/voices` (amendment 2026-10-14, Part 12 item 2)
- `RisiMe.Agent.TTS` is a **behaviour** (`engine/0`, `voices/0`, `synthesize/3` returning audio plus
  `first_audio_ms`) with a **registry**, `config :risime, :risi_tts_engines, [modules]` (default `[]`). No
  engine is built here: the Kokoro service for Call Risi plugs in by implementing the behaviour. The catalogue
  shows Kokoro, Orpheus, Chatterbox, MMS, Indic Parler and Phone TTS, and any engine that isn't registered
  shows "not installed".
- Per language (si, mixed/Singlish, ta, en): Auto or an installed voice, stored in the same runtime-settings
  table and 5-s cache (key `tts_voice_<lang>`, env `RISI_TTS_VOICE_<LANG>`).
- **The hook:** `TTS.voice_for(lang)` returns `:auto` (the caller keeps today's route, its own default voice)
  or `{:manual, voice_id}`. With nothing set it is always `:auto` (parity test).
- **Test:** the admin types a sentence (up to 300 characters). Every installed voice speaks it in turn, and the
  page shows the time to first audio and a player (the audio sits in the page as a `data:` URI; CSP
  `media-src 'self' data:`). The sentence is not stored: the audit log records only its length.

### Auto on both pages (Part 12 item 3)
`RisiMe.Agent.VoiceAuto.status/1`. Auto is the default; a manual choice beats it until it is set back. Today
no per-model Index exists, so **Auto = today's route exactly**. The Voice agent will write daily per-model
rows to `risi_agent_scores` (`agent = "voice"`, `source = "index_stt"` / `"index_tts"`). The page reports
whether such rows exist, but Auto still never switches by itself until the switcher ships (the promotion
rule plus the note in Harsha's Risi chat, described below).

## Agent design
- **Owning agent: Voice** (decision 084/085: calls and voice notes). The console is a tool for the human
  admin; the routing choice belongs to the Voice agent.
- **Auto today = today's production route exactly** (the service's `WHISPER_ROUTE_SI/TA`, `WHISPER_HINT_MODE=prior`),
  because the per-model transcription Index does not exist yet (`risi_agent_scores` has no per-model
  speech rows; the Voice agent is still `Planned`).
- **Auto once the Index exists:** the Voice agent scores each installed model per language with the
  decision-085 transcription Index (100 − WER on real speech: Harsha/Shenika recordings + consented pilot
  clips, public sets only as a labelled fallback) plus live acceptance (transcript lines kept unedited ÷
  shown; corrections and language fixes count against). It proposes a switch only through the promotion
  rule (decision 084: held-out golden set ≥ current, p50/p95 within budget, auto-rollback over the first
  200 runs / 24 h), writes the new route as the Auto value with a `learned_version`, and posts a short note
  in Harsha's Risi chat when it switches. A manual choice still wins.
- **Measured by:** the Index on the scoreboard; the console shows it on the Intelligence page (later).
- **Why a hybrid:** the routing is infrastructure-fast (no model call on the hot path); the decision of
  *which* model is the agent's, made offline from the Index.

## Consequences
- **Caddy:** only `/api/*`, `/socket/*` and `/health` reach Phoenix today, so `/admin` and `/admin/*` must
  be added to the `@app` matcher in `infra/caddy/Caddyfile` (root/Harsha; needs sudo to install).
- **Keycloak:** the `risime` client needs `https://risime.risicloud.ai/admin/auth/callback` as a valid
  redirect URI and `https://risime.risicloud.ai` as a web origin, with the standard flow on.
- Adding or removing admins has no page yet (Users and admins comes later); `RisiMe.Admin.add_admin/2` and
  `remove_admin/2` (audit-logged, never the last admin) are callable over rpc.
