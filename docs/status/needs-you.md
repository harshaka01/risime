# Needs you: open questions from root

Harsha 2026-10-16 05:19 SLT. Every open question gets one entry here, committed and pushed at once.
- **[harsha]:** sudo, Keycloak or console clicks, money, on-phone tests, consent. Also sent to Harsha's Risi chat.
- **[architect]:** design, priority or approach. Only in this file.

Answered entries are marked "ANSWERED: <answer> (by architect|Harsha, time)" and move to the bottom. Answers can also go
in `docs/status/architect-answers.md`: root reads it on every pull and treats it like a message from Harsha.

## Open

### Q-002 · 2026-10-16 04:49 SLT · [harsha] · blocking: no
**Question:** keep Kokoro as the call voice, or switch to 2 (Chatterbox-Turbo)? His 05:00 plan for a switch: cached
greeting and fillers, streamed answers, Kokoro as the fallback.
**Options:** keep Kokoro / "Root: switch to 2".
**Default:** Kokoro (his pick "A, for now"). Architect 06:58 SLT: have the switch ready before he picks on the
Voices page: pinned, silent Kokoro fallback at call start and mid-call, greeting and fillers from cached audio. Note (16 Oct 07:10 SLT): calls can only use a lab voice once the
gap-free worker ships, because that worker has the lab route and the Kokoro fallback, and it waits on Q-004. The pin
(never stopped by the daytime rule) is ready.
Chatterbox samples resent to the Risi chat.

### Q-004 · 2026-10-16 05:2x SLT · [harsha] · blocking: no
**Question:** test barge-in (interrupting Risi) on your phone, so the gap-free voice worker can ship.
**Options:** ready now / later.
**Default:** the running worker stays (`~/risime-voice/KEEP`).

### Q-006 · 2026-10-16 07:10 SLT · [harsha] · blocking: no
**Question:** "Voices: try now". Does Prepare the samples work on the Voices page?
**Options:** Works / Problems.
**Default:** none needed. Root tested it on the live node with the page's own code (not through the browser, which
needs his Keycloak sign-in).
Sent to the Risi chat (topic `voices-try`).

## Answered
- ANSWERED: Problems (by Harsha, 2026-10-16 09:25 SLT). Question Q-003 / `hotfix11-try` (on-phone English speech). His
  09:25 SLT call: the phone sent no finished text, so the server took over after 25 s ("no_final"); the message
  flow also went wrong (invented text, "cancel + new text" sent to the calendar, a split sentence answered alone).
  Both are being fixed (server: fast lane; app: next build).
- ANSWERED: done by Harsha with kcadm at 06:08 SLT; signed in to /admin at 06:27 (by architect, 06:58 SLT). Q-001
  Keycloak admin redirect. Public client, so no ADMIN_OIDC_CLIENT_SECRET.
- ANSWERED: a), RisiWork did it (branch `keycloak-risime` @ 2dbf009, ships with or after their 0.3.33 when Harsha approves).
  Root changes nothing there. If a RisiWork deploy resets the client first, /admin shows "Invalid parameter:
  redirect_uri" again: then add a [harsha] entry pointing to this (by architect, 06:58 SLT). Q-005 keycloak-init.
- ANSWERED: Kokoro "for now" (by Harsha, 2026-10-16 04:49 SLT). Question: voice pick 1–4 (topic `voice-pick`).
- ANSWERED: Problems (by Harsha, 2026-10-16 04:46 SLT). Question: "Calls: try now" (topic `risi-calls-try`). It led to
  the hearing hotfix.
- ANSWERED: Risi Calendar is fine (by Harsha, 2026-10-15 21:34 SLT). Question: where voice adds on calls go.
