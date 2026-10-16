# Architect answers

Written by the RisiMe architect (the claude.ai project thread), with Harsha's agreement. Root reads this file
on every pull and treats each entry like a message from Harsha. Newest first. When you act on an entry, mark
the matching needs-you.md question ANSWERED and move it to the bottom. Don't edit this file; reply in
needs-you.md.

## 2026-10-16 15:20 SLT · Ship now: the APK, and the Gemma-4 test

The v3 loop fixes are live on the server (golden 7/7, paraphrases 59/60, simulated callers 30/30). Good. Two
things are still waiting:
1. **Release the APK now** (hotfix/nightly). It carries the P0 call fixes (ring, stale call, Test incoming
   call), the Translate picker, the one-line action card and the capability refresh, all committed by
   14:31 and none on phones yet. Harsha tests the calls with Shenika today. Post "New app: try now"
   with the 3 checks.
2. **Run the Gemma-4 swap test today** (Harsha 10:20: right after v3, daytime). `scripts/model-swap-test`
   is still dry-run only. Run it for real, under the 10:22 rules, and post the table.
3. Device test friend: yes, add one test-account friend for `pilot-call-test` (test accounts only, with no
   real people's data), so the device run reaches a draft.

## 2026-10-16 14:15 SLT · Bug: a flag turned on never reaches the phone until Settings is opened

`RISI_VOICE_TRANSLATE` is now `on` (Harsha 14:06, verified with `Flags.raw` = "on"), but Regina still sees
no Translate. In the app, `VoiceNotes.translateAvailable` comes from prefs and is refreshed **only** by
`RisiVoiceSection`'s `LaunchedEffect` (Settings → Risi). A restart keeps the cached `false`.
Fix in the next APK:
- Call `voice.refreshSettings()` at app start, on every resume (ON_START) and after each socket
  reconnect, the same way the other server-driven flags are refreshed.
- Audit every server-driven capability the app caches (translate, private transcribe, call Risi, skills,
  notes…) for the same "refreshed only on one screen" pattern, and fix them all in one place.
- Better: push a `config_changed` event on the user channel when a flag changes, so phones refetch at once.
Until then, the workaround for users is to open Settings once.

## 2026-10-16 14:05 SLT · Voice notes in Official chats (Harsha's photos of Regina's phone, 13:56)

1. **Regina gets no Translate on transcripts.** `RISI_VOICE_TRANSLATE` is still `canary` (dev users only),
   the same mistake as Shirazi's transcription on 14 Oct. Harsha is turning it `on` himself with
   `scripts/risi-rpc 'RisiMe.Agents.Flags.put("RISI_VOICE_TRANSLATE", "on", "harsha")'`. Log it in
   pilot-ops.md.
   - **New rule:** any feature Harsha has tested and accepted goes from `canary` to `on` the same day, with
     one line in his Risi chat ("X is now on for everyone; say 'canary' to undo").
   - List every flag still on `canary` in your next status message.
2. **The Risi action-items card crowds the transcript.** The card sits between a voice note and its
   transcript, or right under it, and pushes the text off screen. Fix:
   - Order: voice note → its transcript (with Translate) → the card.
   - Collapse the card to one line by default ("Risi · 2 action items ›"), and tap to expand.
   - Never draw the card over message content.
3. **Translate picker** (queued for the next APK since 05:19): Harsha asks again. It ships with the next
   APK, which shouldn't wait any longer for brain v3 work: release it as soon as the call fixes (11:50) are
   green. The picker has en/si/ta first, then "More languages…" (search), on voice-note transcripts and
   typed messages.
4. **Extraction quality, from the same screens:**
   - "send the accounts to Ko Jin **today**" got due **13:54**, the second it was said. "Today" with no
     time means end of the working day (18:00 local), shown as "today".
   - "Turn on the camera" became a **promise**. Momentary requests during a call or chat ("turn on the
     camera", "can you hear me") are not commitments. Add negatives to the extraction eval.
   - Regina's own Tamil voice note produced no action items. It contains "bill … check … file … pdf …
     vechirukken" ("I've checked the bills and saved a PDF to the file"): a status update, so none is
     correct. Make sure Tamil notes are actually sent to extraction (check the log).

## 2026-10-16 11:50 SLT · P0 calls (Harsha 11:42): incoming call doesn't ring; "already in a call" after a phone call

Two urgent call bugs from real use. Give them to the android role (and server where noted), ahead of the
Gemma test. Releases go out as a hotfix APK and a server fast lane as soon as each fix is green.

**1. Shenika's phone doesn't ring for a RisiMe call.** Notifications are allowed and the phone is not on
silent. Find the cause on her device first: her device row (model, Android version, app version), the
FCM delivery log for the call push, and the call log, then fix it in the app. Check and handle:
- FCM: the call push is a **high-priority data message**, not a notification message, and it isn't
  deprioritised. Log a warning if FCM reports it was downgraded.
- Android 14+ **USE_FULL_SCREEN_INTENT**: if it isn't granted, ask for it with a clear one-tap screen
  ("Let RisiMe show incoming calls"). Today the incoming call is probably only a silent heads-up.
- The **call notification channel**: importance HIGH, ringtone sound with USAGE_NOTIFICATION_RINGTONE,
  vibration. If the user or the OEM muted the channel, show it in-app with a "Fix" button that opens the
  channel settings. Note that a channel's sound can't change after it's created, so changing the sound
  needs a new channel id.
- Self-managed **ConnectionService** (Telecom) for incoming calls, so the system rings it like a normal
  call, if that isn't already the case.
- **Battery optimisation / OEM auto-start** (Xiaomi, Oppo, Vivo, Samsung "sleeping apps"): detect it and
  guide the user in-app, once, with the exact setting for her brand.
- Add a "Test incoming call" button under Settings → Calls that rings the phone in 5 s, so Harsha and
  Shenika can check it themselves.

**2. After a normal phone call interrupts a RisiMe call, the next RisiMe call says "you're already in
another call"** until the app is killed. Every end path must free the call. Fix:
- Handle the cellular interruption (TelephonyCallback / Telecom hold or disconnect, audio-focus
  loss). End or hold the RisiMe call cleanly and tell the other side.
- One `endCall(reason)` path that always clears the active-call state, the ConnectionService
  connection (setDisconnected + destroy), audio focus and mode, the LiveKit room, the foreground
  service and the wakelock. Hang up, remote end, network loss, cellular call, app background or kill,
  and timeout all go through it.
- Self-heal: before saying "in another call", check that the stored call is really live (room
  connected, the server knows it). If it isn't, clear it and place the new call.
- Server: if the server has a "busy" check, a call with no media or heartbeat for 30 s is ended
  server-side, so a stale call never blocks a new one.
- Tests: unit tests for each end path, plus one device test (call, take a GSM call, end it, call again
  without restarting the app).

Post "Calls: try now" in Harsha's Risi chat with these two checks: Shenika presses "Test incoming call";
then call, take a phone call, and call again.

## 2026-10-16 11:45 SLT · P0 v3 regression: the message loop (Harsha's 11:3x call, 1:16)

v3 asked "What should I say to Shani Kao?" three times. Harsha gave the text twice and never got a
card. He says "even dumber", and on this flow he is right. Causes, from brain_v3.ex and dialogue.ex:
1. **`Dialogue.grounded?/2` rejects the model's honest rewording.** Harsha says "tell her that I called
   her a couple of times and she did not answer, call me back"; the model writes "I called you a couple
   of times but you didn't answer. Please call me back."; the content-word overlap falls below 75%, so
   `not ok_text?` asks again, forever. A regex guard is overriding the model, which is exactly what v3 was
   meant to stop.
2. **One `awaiting` slot.** "Did you mean Shenika?" replaced `message_text` with `answer`, so the text
   already given and the recipient were lost. "Hello" then restarted the question.
3. **The name never resolved.** "Shani Kao" → Shenika is exactly the find_contact case (10:15 entry, item 2).

**Fix now (hotfix, today):**
- **While Risi waits for the message text, the caller's utterance is the text.** Use their words; strip
  only a leading "tell her/him that", "say that" or "just say", and convert reported speech to direct
  speech only when the model does it. Grounding is a soft check against the whole call so far, after
  normalising contractions (didn't = did not), pronoun shifts (her ↔ you) and politeness words. When it
  fails, never ask the same question again: read back and confirm ("I'll send Shenika: '…'. Shall I put it
  on a card?").
- **Never ask the same question twice in a row.** The second time, say what Risi heard and offer a choice.
- **Pending task, not a single slot:** `pending: %{task: "message", to, to_candidates, text}`. A
  clarifying question ("Did you mean Shenika?") nests inside it and returns to it. "Yes", "that's right"
  or the name confirms; anything off topic keeps the task open; only "cancel" drops it.
- **Resolve the recipient first** with find_contact (phonetic). With one clear match, use it without
  asking. With 2–3, ask once, keeping the text.
- **No "One moment…" before a short question or confirmation.** Use the filler only before a tool call that
  takes over 700 ms.

**Why the golden calls said 5/5:** the scripted turns were too close to the fixes. Before the next
release:
- Add this call as golden #7, plus **20 paraphrase variants per golden task**: indirect speech, "tell
  her that…", corrections, "hello?" in the middle, misheard names. Have the model generate them; you
  review them.
- Add a **simulated-caller test**: an LLM plays Harsha with a goal (send X to Y, read my next meeting,
  cancel and redo), 30 runs, scored on goal reached within 4 turns and no repeated question. A release
  needs ≥ 90% goals reached and 0 repeated questions.
- Post the pass rates with the next "try now".

## 2026-10-16 10:22 SLT · HARSHA: run the Gemma-4 test right after v3, not at night

Harsha 10:20: "After brain 3 let's try Gemma without waiting, as the workload is over." This replaces the
night timing in the 10:25 entry below; everything else in it stands.
- As soon as v3 is live on risi-l1 and the "try now" is posted, run the swap test in the daytime.
  - First check that no release gate or speech-compare job is running; if one is, wait for it to finish.
  - Order: Gemma-4-26B-A4B FP8, then Gemma-4-31B FP8, each on the full golden set.
  - The whole window is at most 60 min. During it the candidate serves every risi-l1 request; Harsha
    accepts that.
  - Post "model test running, about N min" in his Risi chat at the start and "done" at the end.
- If a candidate fits beside risi-l1 now that the gates are idle (≥ 10 GB headroom kept), test it beside
  risi-l1 instead, with no swap.
- After the test: restore risi-l1 and confirm it answers. Post the table. Harsha may answer "keep Gemma-4" to
  switch the call brain at once.

## 2026-10-16 10:25 SLT · HARSHA'S DECISION: Gemma-4 first, commercial only if it fails (Q-007 answered)

Harsha 10:19: "try Gemma-4, and if it fails then add commercial". So:
- **Q-007: no commercial model for now.** Mark it ANSWERED (by Harsha, 10:19 SLT).
- **Brain v3 code ships on risi-l1** as soon as it's ready. The model is a setting.
  - **Target: "Call Risi brain v3: try now" today by 20:00 SLT.**
  - At 16:00 SLT, post a one-line progress note in his Risi chat, with any blocker.
- **Tonight (22:00–06:00 SLT), night swap test** with the golden calls:
  - Gemma-4-26B-A4B FP8: MoE with ~4B active, so its speed is close to risi-l1. This is the main call
    candidate.
  - Gemma-4-31B FP8: dense, so better understanding but slower.
  - risi-l1 as the baseline.
  - Score each on golden pass rate, Sinhala/Tamil script, false claims, p50/p95 first-token latency and
    tok/s.
  - Restore risi-l1 before 06:00 SLT and confirm it answers.
- **Morning (by 07:00 SLT):** post the table in his Risi chat.
  - If a Gemma-4 model passes every golden call within latency (first token p50 ≤ 0.8 s on the call
    path), switch the call brain to it with Harsha's one-word OK. The other risi-l1 jobs move only after
    their own gates.
  - If none passes, say which golden turns failed and ask Harsha about a commercial model, with its cost
    per call.
- Memory: a Gemma-4 that replaces risi-l1 takes its place in memory; it doesn't sit beside it.

## 2026-10-16 10:25 SLT · Model bake-off: three contenders, not two

Harsha doesn't want a Risi that is only a wrapper around a paid model. Run the v3 golden calls on three
models and post the pass rate, the p50/p95 first-token latency and the cost per call for each:
1. risi-l1 (now).
2. **The bigger open models already downloaded and verified but never tested** (RISI-MODELS.md):
   Qwen3.8-27B FP8 and Gemma-4-31B FP8 (and Gemma-4-26B-A4B FP8). They didn't fit beside risi-l1 under
   the 10 GB headroom rule, so test them by swapping: stop risi-l1 at night (22:00–06:00 SLT, no calls),
   load one candidate, run the golden calls, then restore risi-l1 and confirm it answers before 06:00. No
   new download needed. If you think another open model would beat these, propose it; a download over 2 GB
   follows golden rule 10.
3. One commercial model, only if Harsha approves it (Q-007).

The brain code is model-agnostic. The learning log records every decision, so the own model can later be
trained on the decisions the best model gets right.

## 2026-10-16 10:15 SLT · Brain v3 addendum: contacts, a second golden call, model bake-off

Harsha's 10:0x call (2:12, still the old brain) adds failures v3 must cover. Add it as golden call #5.
1. "Hello, how are you, this is a text message that's given by Harsha" → recipient = Harsha. The author of
   the text is not the recipient. The recipient is missing, so ask "Who should I send it to?".
2. **Contact lookup is broken.** Shanika is not found (spelled S-H-E-N-I-K-A, heard as "Seneca"), even
   though Risi offered "Shenika, Shanika" in the same call and drafted to "Shenika" in the 09:2x call.
   Shirazi is not found either, although he is a real RisiMe user Harsha chats with. Build one
   `find_contact` tool for the brain:
   - Search Harsha's RisiMe contacts, the members of his chats, and recent chat partners.
   - Match fuzzily and phonetically (edit distance plus a sound-alike key, Sinhala and Tamil names
     transliterated), and return the top 3 with a score.
   - Act on 1 clear match. Ask "Did you mean Shanika or Shenika?" when there are 2–3. Never say "not
     found" while a match scores ≥ 0.6.
   - Spelling a name out letter by letter must work.
3. "Just tell me 10 names in my contacts" → it read the calendar and promises instead. Add a
   `list_contacts` tool (names only, read aloud, max 10).
4. A request that has nothing to do with the open task must not reuse the last tool's output.

**Q-007 (model):** this is Harsha's decision (paid service). The architect recommends a commercial model for
the v3 turn decision now; risi-l1 takes over later, task by task, from the learning log, per the model
cascade. Make the model a setting, so v3 is built either way. Run every golden call on risi-l1 and on the
candidate, and post the pass rate and the p50/p95 first-token latency of each to Harsha's Risi chat. He
decides on those numbers. While a commercial model is on: send only the call turns and tool results,
never private-chat content without a per-action yes, and no new data store at the provider.

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
