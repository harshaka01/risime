# Needs you: open questions from root

Harsha 2026-10-16 05:19 SLT. Every open question gets one entry here, committed and pushed at once.
- **[harsha]:** sudo, Keycloak or console clicks, money, on-phone tests, consent. Also sent to Harsha's Risi chat.
- **[architect]:** design, priority or approach. Only in this file.

Answered entries are marked "ANSWERED: <answer> (by architect|Harsha, time)" and move to the bottom. Answers can also go
in `docs/status/architect-answers.md`: root reads it on every pull and treats it like a message from Harsha.

## Open

### Q-001 · 2026-10-16 04:58 SLT · [harsha] · blocking: yes (for /admin only)
**Question:** add the admin redirect in Keycloak. It was checked at 04:58 SLT: Keycloak answers "Invalid parameter: redirect_uri".
1. Open https://risicloud.ai/admin, realm `aoa`.
2. Go to Clients → `risime` → Settings.
3. Valid redirect URIs: add `https://risime.risicloud.ai/admin/auth/callback`.
4. Web origins: add `https://risime.risicloud.ai`.
5. Make sure Standard flow is On.
6. If Client authentication is On, put the secret in `.env` as `ADMIN_OIDC_CLIENT_SECRET`.
7. Save.

**Options:** It's done / Did it differently.
**Default:** wait; /admin stays unreachable. Nothing else is blocked.
Sent to the Risi chat (topic `keycloak-redirect`).

### Q-002 · 2026-10-16 04:49 SLT · [harsha] · blocking: no
**Question:** keep Kokoro as the call voice, or switch to 2 (Chatterbox-Turbo)? His 05:00 plan for a switch: cached
greeting and fillers, streamed answers, Kokoro as the fallback.
**Options:** keep Kokoro / "Root: switch to 2".
**Default:** Kokoro (his pick "A, for now"). Note (16 Oct 07:10 SLT): calls can only use a lab voice once the
gap-free worker ships, because that worker has the lab route and the Kokoro fallback, and it waits on Q-004. The pin
(never stopped by the daytime rule) is ready.
Chatterbox samples resent to the Risi chat.

### Q-003 · 2026-10-16 05:2x SLT · [harsha] · blocking: no
**Question:** the on-device English speech in the hearing hotfix APK can only be tried on your own phone, since the test
phone very likely has no on-device recogniser. Please make one Call Risi call after you install it.
**Options:** Works / Problems.
**Default:** the server's speech-to-text (now with the hallucination filters) is used wherever the phone can't
recognise on the device. Sent 07:12 SLT with the nightly.11 "try now" (topic `hotfix11-try`).

### Q-004 · 2026-10-16 05:2x SLT · [harsha] · blocking: no
**Question:** test barge-in (interrupting Risi) on your phone, so the gap-free voice worker can ship.
**Options:** ready now / later.
**Default:** the running worker stays (`~/risime-voice/KEEP`).

### Q-005 · 2026-10-16 05:4x SLT · [harsha] · blocking: no (Q-001 still works by hand)
**Question:** you asked me to change `deploy/keycloak-init/configure.py` so the `risime` client keeps its web
origins and redirects on every deploy, always with web origin `https://risime.risicloud.ai` and redirect
`https://risime.risicloud.ai/admin/auth/callback`, then add a line to `claude/RisiCloud-shared-status.md`. Neither
file is on spark2. Searched: the whole disk, and harshaka01 on GitHub under risicloud, RisiCloud, risi-cloud, aoa and
risicloud-infra. Keycloak runs on another host (risicloud.ai = 203.189.69.77). Was that message meant for the
RisiCloud session?
**Options:** a) send it to the RisiCloud session; b) give root the repo URL (and SSH access) and root makes the change.
**Default:** a). Root changes nothing in RisiCloud.

### Q-006 · 2026-10-16 07:10 SLT · [harsha] · blocking: no
**Question:** "Voices: try now". Does Prepare the samples work on the Voices page?
**Options:** Works / Problems.
**Default:** none needed. Root tested it on the live node with the page's own code (not through the browser, which
needs his Keycloak sign-in).
Sent to the Risi chat (topic `voices-try`).

## Answered
- ANSWERED: Kokoro "for now" (by Harsha, 2026-10-16 04:49 SLT). Question: voice pick 1–4 (topic `voice-pick`).
- ANSWERED: Problems (by Harsha, 2026-10-16 04:46 SLT). Question: "Calls: try now" (topic `risi-calls-try`). It led to
  the hearing hotfix.
- ANSWERED: Risi Calendar is fine (by Harsha, 2026-10-15 21:34 SLT). Question: where voice adds on calls go.
