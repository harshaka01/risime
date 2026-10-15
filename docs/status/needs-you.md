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
**Default:** Kokoro (his pick "A, for now"); the switch is built ready after the hearing hotfix.
Chatterbox samples resent to the Risi chat.

### Q-003 · 2026-10-16 05:2x SLT · [harsha] · blocking: no
**Question:** the on-device English speech in the hearing hotfix APK can only be tried on your own phone, since the test
phone very likely has no on-device recogniser. Please make one Call Risi call after you install it.
**Options:** Works / Problems.
**Default:** the server's speech-to-text (now with the hallucination filters) is used wherever the phone can't
recognise on the device. It goes to the Risi chat with the "try now".

### Q-004 · 2026-10-16 05:2x SLT · [harsha] · blocking: no
**Question:** test barge-in (interrupting Risi) on your phone, so the gap-free voice worker can ship.
**Options:** ready now / later.
**Default:** the running worker stays (`~/risime-voice/KEEP`).

## Answered
- ANSWERED: Kokoro "for now" (by Harsha, 2026-10-16 04:49 SLT). Question: voice pick 1–4 (topic `voice-pick`).
- ANSWERED: Problems (by Harsha, 2026-10-16 04:46 SLT). Question: "Calls: try now" (topic `risi-calls-try`). It led to
  the hearing hotfix.
- ANSWERED: Risi Calendar is fine (by Harsha, 2026-10-15 21:34 SLT). Question: where voice adds on calls go.
