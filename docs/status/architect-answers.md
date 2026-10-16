# Architect answers

Written by the RisiMe architect (the claude.ai project thread), with Harsha's agreement. Root reads this file
on every pull and treats each entry like a message from Harsha. Newest first. When you act on an entry, mark
the matching needs-you.md question ANSWERED and move it to the bottom. Don't edit this file; reply in
needs-you.md.

## 2026-10-16 09:40 SLT · P0 Call Risi brain: understanding by the model, not keyword rules

Harsha's 09:2x call (2:58, transcript below) shows the call brain still routes by regex
(`Brain.decide/2` → `Agents.Router.rules/2` + ~15 special-case conds). Each fix adds a rule; the next
sentence breaks another. Stop patching rules. This is the only work until it ships.

**Failures in that call:**
1. "…just say good morning, Shanika…" → calendar ("morning" = time word). Twice.
2. "Can you send a message to Shanika?" → invented the text "Hi Shenika, how are you" instead of asking.
3. "No, just cancel that and start a new message… say good morning Shanika…" → cancel ignored, calendar read.
4. "receive and send a message to you." (an utterance fragment) → acted on as a request.
5. "Sorry, I didn't catch that" right after Risi's own question.
6. Risi asked "What message would you like to send to Shanika?", and the answer went to the calendar: the
   pending question was forgotten.

**Build (one chunk, ~1 day max, ship behind RISI_CALL_BRAIN=v3, canary Harsha):**
A. **One model decision per turn.** Give the model: the last ~12 turns of the call, the open state
   (pending draft or card, the question Risi just asked, the slot it is waiting for), today's date and time
   zone, and the tools. The model returns either a spoken reply, one or more tool calls, or one clarifying
   question. Regex keeps only these jobs: barge-in "Risi, stop", a hint for which filler to play, and
   safety. It never chooses the action. Use the strongest model available for this decision (commercial
   first, per the model cascade). Log every decision to the learning log.
B. **Dialogue state.** When Risi asks a question, the next utterance answers it unless it clearly changes
   the subject. A message has slots {to, text}: if the text is missing, ask "What should I say to
   Shanika?". Never invent the message text. Read the draft back ("I'll send: '…'. Shall I send it?").
   "Cancel that" cancels the open card, and a new request in the same sentence still runs.
C. **Utterance merging.** Hold a fragment that doesn't end a thought (trailing "and", "to", a cut-off
   clause) for up to 1.2 s and merge it with what follows. Never act on a fragment alone.
D. **Neutral fillers.** Play the calendar filler only after the model picked a calendar tool; otherwise
   "One moment…". Pre-rendered audio.
E. **No "didn't catch that"** when the transcript is just an echo of Risi's own voice. Drop it silently.
F. **Claim guard stays:** no "sent" or "added" without a tool result.

**Gate: conversation tests from Harsha's real calls.** Make `test/call_risi/golden/` with this call and the
12:45, 14:00 and 2:11 calls as scripted turn-by-turn conversations, with the expected action per turn. They run
against the real model (tag :live, run before every release) plus a recorded-response version in mix test.
Ship only when every golden turn passes. Every new bad call Harsha reports becomes a new golden test,
not a new regex.

**When live:** post "Call Risi brain v3: try now" in Harsha's Risi chat, with 3 things to try: send a
message to Shanika (it asks for the text), "cancel that and say good morning instead", "what's my
next meeting".

Transcript (09:2x SLT): "Can you send a message to Shanika?" → drafted "Hi Shenika, how are you" → "No,
just cancel that and then start a new message… Just say, good morning, Shanika. How are you doing?" → "let
me check your calendar" → "I want to send a message to Shanika. So you should ask what the message
should be" → "What message would you like to send to Shanika?" → "Good morning, and I'm just testing
whether you can send a message on behalf of me." → "One moment, I'll look at your calendar."

## 2026-10-16 06:58 SLT

**Q-001 (Keycloak admin redirect): DONE.** At 06:08 SLT Harsha changed it on spark with kcadm inside `aoa-keycloak-1`.
The admin console isn't reachable from outside: risicloud.ai/admin gives 404. Client `risime` now has:
- redirectUris: the three app URIs plus `https://risime.risicloud.ai/admin/auth/callback`
- webOrigins: `https://risicloud.ai` and `https://risime.risicloud.ai`
- public client, standard flow on, so no `ADMIN_OIDC_CLIENT_SECRET` is needed.

Harsha signed in to /admin at 06:27. Mark Q-001 answered.

**Q-005 (configure.py): answer a).** That message was meant for RisiWork, and they've done it: branch
`keycloak-risime` @ 2dbf009 in their repo, shipping with or after their 0.3.33 once Harsha approves. Root changes nothing in
RisiCloud or RisiWork. If a RisiWork deploy resets the client before then, /admin will give "Invalid
parameter: redirect_uri" again. In that case, add a [harsha] entry pointing to this answer; don't try to fix it yourself.

**Q-002 (Call Risi voice):** Kokoro stays until Harsha picks on the Voices page. Have the switch ready
before he picks:
- If he picks Chatterbox-Turbo, keep it pinned (loaded at all times), so the daytime idle-memory rule never stops it.
- Fall back to Kokoro automatically and silently if it isn't ready, at the start of a call or partway through one.
- Play the greeting and fillers from cached audio.
- Confirm all three in the "Voices: try now" Risi message.

**Good catch on the hotfix release:** you blocked it from redeploying an older server build over today's fixes. Make that a
rule for every release: a release never deploys a server build older than the one running. Add the check to
the release script so it stops and posts a "Root needs you" card instead.
