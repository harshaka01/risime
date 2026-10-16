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

### Q-009 · 2026-10-16 15:45 SLT · [harsha] · blocking: no
**Question:** "Call Risi brain v3, message fixes: try now" (srv5/srv6, canary). Golden 7/7, paraphrases 59/60, simulated
callers 30/30. **Options:** Works / Problems. **Default:** stays canary. On "Works": RISI_CALL_BRAIN stays canary
until Call Risi itself (RISI_CALL_RISI) is accepted, since v3 only runs on Call Risi. Topic `brain-v3-loop-try`.

### Q-010 · 2026-10-16 20:20 SLT · [architect] · blocking: no
**For the bridge (19:15 item 3):** `eval/shared/risime-cases.md` is done: 30 cases (I-01..I-10 intent, N-01..N-10 contact
name, D-01..D-10 dictation vs instruction), invented names only (checked: no real names, no numbers), shaped on
Harsha's real calls. Ready to copy to risibridge. They run in tonight's model test (risi-l1, Gemma-4-26B-A4B,
Gemma-4-31B, Nemotron 3 Nano) with first-token latency.
**Default:** copy as is.

### Q-011 · 2026-10-16 23:30 SLT · [architect] · blocking: no
**Marketplace phase 0: started** (architect 22:05/22:10). A `marketplace` role session builds it under `marketplace/`
only (decision 095; CLAUDE.md role row added), loopback, its own DB, plus the signed-in page shell (Keycloak client
`risi-marketplace`, allow-list from its own data). It pauses builds during tonight's model swaps. The spark → spark2
link proposal follows in marketplace/STATUS.md; root copies it here with any exact ufw command for Harsha.

### Q-012 · 2026-10-17 00:00 SLT · [harsha] · blocking: no (marketplace only)
**Question:** push the marketplace phase 0 commit. It's built and green (58 tests, 0 failures, only `marketplace/`, 62
files, no secrets in it) but the auto-mode permission check refused the helper's push to main, so root doesn't push it
for the helper. If you're happy with it, run on spark2:
`git -C /home/harsha/development/risime/.claude/worktrees/agent-a295187c0f8bc4739 push origin HEAD:main`
Its DB password and keys are kept in `~/secrets/marketplace.env` (600). **Options:** pushed / not yet.
**Default:** it waits; nothing else depends on it tonight.

### Q-013 · 2026-10-17 00:00 SLT · [harsha] · blocking: no (public marketplace route)
**Question:** the spark → spark2 link for risicloud.ai/marketplace. Proposal A (preferred): a second marketplace
listener on spark2's LAN address only (`10.20.20.15:4601`, `/marketplace/*` only), spark's Caddy `handle /marketplace* {
reverse_proxy 10.20.20.15:4601 }` (RisiWork), and ufw open to spark only. First check the LAN works (decision 015 found it
closed on 10-06), on spark: `ping -c3 10.20.20.15 && nc -zv 10.20.20.15 22`. Then on spark2 (gx10-e23a):
`sudo ufw allow proto tcp from 10.20.20.14 to 10.20.20.15 port 4601 comment 'risi-marketplace: spark Caddy only'`
(undo: `sudo ufw delete allow proto tcp from 10.20.20.14 to 10.20.20.15 port 4601`). B (fallback): a path on spark2's own
Caddy that answers only spark's IP with a shared header secret (weaker). Plus the Keycloak client `risi-marketplace`
(public, standard flow, PKCE S256, redirect https://risicloud.ai/marketplace/auth/callback).
**Options:** A / B / later. **Default:** later; it stays on loopback.

### Q-014 · 2026-10-17 04:50 SLT · [architect] · blocking: no · TRACE (04:25 items a–d), before any fix
**Shakya Adikari** (Harsha's 04:0x call):
- a) find_contact: one account (20ae82c3…), picked right ("Shakya Adhikari" → Shakya Adikari 0.93; Swan Adikari 0.67 second).
- b) Memory is complete for him: his Official 1:1 (grp:757e3a6f…, Risi an active member) holds 459 rows. His last **texts**:
  6 in the 3 days before the call, the latest at 16 Oct 16:29 SLT. Since then he sent only an **emoji reaction** (16:40 SLT),
  which memory rightly doesn't keep. So nothing was lost; there is no later text from him on the server.
- c) Harsha's last text in that same 1:1 was 16:35 SLT, 6 minutes after Shakya's, so `official_line` said "Not yet … hasn't
  written since". True for "did he reply", wrong for "read me his last message" (04:20 item 4 / 04:25 fault 1).
- d) **The limit cuts:** 584 memory rows in his 24 Official chats in 3 days; `messages(…, 300)` keeps the newest 300, so the
  window really started 14 Oct 22:10 UTC and **5 of his 6 texts were outside it**. Must filter by sender in the query.
**Shenika** ("I don't see any Official messages…"):
- a) one account (fb81ee92…), picked right ("Shenika" 1.0; "Shanika" → Shenika 0.86; "Seneca" → Bhanu Senanayake 0.80 vs
  Shenika 0.79, the near tie in item 9/10).
- b) Her last **Official** text is 13 Oct 22:07 SLT (34 in memory, complete). Her two messages since yesterday (16 Oct 20:14 and
  20:34 SLT) are in the **Private** 1:1 with Harsha, where Risi is not a member, by design. So "none in 3 days" was literally
  true and useless; the 04:25 rule applies: say "Her recent messages are in your Private chat, which I can't read. Her last
  Official one was on 13 Oct: …".
- Not a memory or ingestion fault for either person. Memory intake overall is low (24 rows on 16 Oct) because Official
  traffic is low; Harsha's inbox shows 36 group messages from others since 16:35 SLT, most in Official groups without memory
  or in Private groups (not checked one by one; can do if wanted).
**Also found:** during the call Risi **sent** a message to Shenika in Official (risi_call_sends `sent`, 04:01 SLT, labelled
"Sent by Risi for Harsha"). Per 04:20 item 2 its text likely carried the instruction words. The 2-minute undo has passed.
Harsha may want to check it in his Official chat with Shenika.
**Fix order** (04:20): 4 (`last_from` vs `replied_since`, sender-filtered query, no 300 cut), 6, 7, 5 (30-day look-back + "in your
Private chat" line), then 1, 2, 3, 9, 8, 10, 12, 11. The real-call replay gate comes first. Started.

## Answered
- ANSWERED: "Ok C is good but after if we fail tonight" (by Harsha, 2026-10-16 18:45 SLT, in the architect chat; architect
  19:20). D-001 brain model. Local only through tonight's model tests; the escalation path is built behind a flag that is
  off, with no paid provider set. If no local model passes the call gate tonight: post the failing turns and the cost per
  call, and ask Harsha to add the key himself (`nano .env`). Not posted to the Risi chat (architect 19:20).
- ANSWERED: "try Gemma-4, and if it fails then add commercial" (by Harsha, 2026-10-16 10:19 SLT, via the architect).
  Q-007 commercial model. No commercial model for now; Gemma-4 swap test right after v3 (architect 10:22).
- ANSWERED: Problems (by Harsha, 2026-10-16 11:35 SLT). Q-008 brain v3 try now. His 06:04Z call: the recipient
  wasn't matched to his contact and the message text he gave was dropped and asked for again (fix in progress, golden
  call 7). Phone speech: 31 partials, no final (the end-of-speech bug fixed in nightly.12).
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
