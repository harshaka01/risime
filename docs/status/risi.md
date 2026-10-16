# Risi Brain status (risi role)

Role created 2026-10-11 (CLAUDE.md rule 12, decision 077). Ships through `scripts/risi-fastlane` (root deploys).

## P0 2026-10-16 (architect 15:35, 16:05, 19:15/19:20 SLT; Harsha's calls 15:2x and 22e016e0): tool coverage, dictation, call context, voice send

Golden #9 = the 15:2x call (4:57), #10 = call 22e016e0 (5:17), #11/#12 = RisiWork's two cases; Shenika → "Nimali",
Bhanu Senanayake → "Kasun Senaratne", Ozzy → "Kamal", Whisper's spellings → "Senamali"/"Senarali"/"Nimaly"/"Neemara".

- **Tools** (`CallRisi.Decider`, one decision a turn; `confidence`, `to`, `text`, `tab` always, the rest only when used):
  read_messages (first), message_draft (`tab` private/official), dictate, calendar_read, calendar_add, reminder_add,
  my_promises, promises_by, list_contacts, cancel_open, confirm_open, card_status, undo_send, capabilities and
  **unsupported** ("I can't do that yet. On a call I can …", never the nearest tool).
- **read_messages / promises_by / my_promises** (`CallRisi.Reads`, no model call): Risi's sealed memory of Official
  chats (`Memory.scope` + `Memory.messages`, the store `VoiceTranslate.source_text` reads); "did X reply?" = X's messages
  after the caller's own last one in the chats where X writes; "any new messages?" = 12 h by sender; Private is never
  read ("I can't see your Private chats", only when he says private). The ledger by owner (`Rest.open_commitments`).
  A day-named promises question stays the read of Risi's items, under the promises line (15:35 bug 3).
- **Dictation mode** (`CallRisi.Dictation` + the session): "the message is going to be…", "take this down", "listen
  until I say okay", or the answer to "What should I say to X?". Pauses start **no turn** (no `replaced`), nothing is
  said per piece; **one model check per pause** labels the new words `more | restart | end | instruction | noise` and
  gives that piece's message words (an instruction is never text, 16:05 item 1); the text is built from the pieces
  (each checked against the words said), never generated whole. At most one "Mm-hm." after 3 s, never twice in a row;
  ~6 s of silence ends it (2.5 s for the answer to "What should I say?"); an announced dictation ends only on an end
  signal or silence. The read-back is one line (a long one: its first 22 words and how many more); misheard words are
  offered first ("Some words may be misheard. Shall I fix them? It would say: …"). Words before the read-back is
  spoken go back to dictation.
- **Task close** (16:05 item 2): another tool request finishes the open task.
- **Call context** (16:05 items 3–4, bug B): work/official/office/private/home/the chat/the group are never a name;
  "her/him" = the person in focus; find_contact's near ties go to the pinned recipient (a correction pins it) or this
  call's people unless the name heard is exactly someone else's; the chat chosen for a person ("send it to work")
  stays for the call; a correction that gives the same card says "It's already going to …" (never the same line).
- **Bug C**: "that is the message I want sent" ends the dictation; the Private tap line only when he asks Risi to send
  now ("I can only draft it for your Private chat with X. Tap Send …"; with voice send: "Or say "send it to work" …").
- **A, v1.55 §41.11** (`RisiMe.Agent.CallSend`, 96e51350, built by a sub-agent; the post's `on_behalf_of` per root's
  585ce74c): an Official draft for a caller with voice send on → the card plus the guard (money/HR/legal/accusation/
  deletion) in parallel; "I'll send X on Official: '…'. Send it?"; a clear yes → Risi's own `sent_for` post, "Sent."
  only with its stored message_id, the card closed; "undo that" within 2 min; Official off → a Private card.
- **D-001 (19:15/19:20)**: `confidence` (0–1) in the decision, logged (number only); config slot `:risi_brain` (old
  `:risi_call_brain` still read); the decision is `local_only` (no automatic fallback); `CallRisi.Escalate` behind
  `RISI_BRAIN_ESCALATE` (default **off**, `escalate: [model: nil, below: 0.5, daily_cap: 200]`, **no model set**);
  escalations and the caller's corrections (recipient, cancel, card change) are `voice_call_training` rows, labels only.
- **Live:** v0.3.0-nightly.11-srv7 (fast lane 18:46Z, 21b05fcd). **Device test** (redroid pdt, srv7, 20:3xZ, after the
  swap tests; `pilot-call-test` calls 7 and 8, call 8 from the patch `~/risime-logs/pilot-device-test/pilot-call-test-call8.patch`
  for root): call 7 reaches the card as before; call 8 "I want to send David a long message. Take this down." → "Go
  ahead, I'm listening. Say "that's it" when you're done." → three pieces with 1.5 s pauses, no turn and nothing said
  between them → one read-back of the whole text → "Did David reply to me?" → "I don't see any Official messages from
  David in the last 3 days." (read_messages, no draft); 0 `replaced` turns. Found: an instruction during dictation was
  written to the transcript twice ("Hello." twice): fixed in 429edf41 (after srv7).
- **Gate round 2 (root 16 Oct evening), live on risi-l1:** golden 11/11 calls; paraphrases 339/340 (every tool 20/20,
  message-steps 19/20); shared RisiWork cases intent 10/10, contact 9/10 (N-04: "Tell Tharindi about the schedule"
  read the calendar), dictation 10/10; simulated callers 30/30, 0 repeated questions. Decision latency: turn p50
  1.5–1.65 s, p95 1.9–2.2 s; dictation check p50 0.7 s, p95 0.85 s. Shared D-08 is a yes now (root): "Can you just send it
  by yourself?" after an Official "Send it?" read-back sends (unguarded text, the same ack rule; Private keeps the tap).
- **Gate (one command):** `cd server && MIX_ENV=test mix test --only llm_live test/risime/agent/call_risi/golden`
  (golden calls, paraphrases per tool, the 30 shared RisiWork cases, simulated callers; each prints its decision
  latency). `PARA_SAMPLE=<n>` (first n per tool, fixed order) and `SIM_RUNS=<n>` (first n callers, round-robin over
  kinds, fixed seeds) shorten it for the model swap window; `GOLDEN_RECORD=1` re-records (a sampled run merges).

## P0 2026-10-16 (architect 11:45 SLT): v3 message loop fixed (Harsha's call 97edc028, 06:04Z)

- **find_contact** (`RisiMe.Agent.FindContact`): the caller's friends + chat members, scored 0..1 (exact,
  edit similarity of plain and loose forms, sound-alike key 0.8; spelled names joined; Sinhala/Tamil
  transliterated). One clear match is used at once ("Nima Lee" → Nimali Perera); 2–3 near ones (≥ 0.6) are
  asked once ("Did you mean A or B?"), the text kept; never "not found" at ≥ 0.6. **list_contacts** (names
  only, max 10). The author ("given by Harsha", the caller's own name) is not the recipient.
- **Pending task** `pending: %{task: "message", to, to_candidates, text}` in the call's dialogue; questions
  nest in it (`awaiting` + the `question` asked); off-topic talk keeps it; cancel or its card drops it.
- **The text:** while Risi waits for it, the caller's words are the text (only "tell her that" / "say" /
  "just say" stripped); the model's rewording is checked softly against the whole call (contractions,
  her ↔ you, politeness, stems; 70%); a failed check reads back and confirms ("I'll send X: '…'. Shall I put
  it on a card?"), never asks again. "I just said it" → the caller's earlier words.
- **Never the same question twice in a row:** the second time "I heard '…'." + a choice. A non-answer
  ("Hello?", "Sorry?") gets that too, keeping the task. Yes / the name confirms a read-back; a name picks.
- **Fillers:** no "One moment…" while the model decides; a tool says its own line when it starts.
- **Gate:** golden calls 7 (this call) and 8 (the 10:15 addendum's quoted lines; the full 10:0x transcript
  isn't in the repo) + 60 paraphrase variants (20 per task, written by Claude and reviewed) + 30 simulated
  callers (risi-l1 plays Harsha). Live on risi-l1: golden 7/7, paraphrases 59/60 (98%), simulated callers
  30/30 within 4 turns, 0 repeated questions. Run: `mix test --only llm_live test/risime/agent/call_risi/golden`
  (`GOLDEN_RECORD=1` re-records; `GOLDEN_ONLY=`, `PARA_ONLY=` filter).
- **Device test** (`scripts/pilot-call-test`, call 7, synthetic Kokoro voice): the pilot test account has **no
  friends** (find_contact finds nobody; shared data not changed), and Whisper loses the name from Kokoro's
  clip ("Ruwan." dropped, "It's for Ruwan." → "It's four.", "Send it to Ruwan." → "Send it to"). On srv4 the
  retry was one "I heard …" line three times (fixed in srv5: escalating lines; `dc46abf6` alternates later
  ones). No "One moment…" before the questions; no question repeated.
  **10:3xZ (srv6, architect OK 15:20 SLT):** test friend added through the app API (allowlist +94770000996
  pilot-friend-test@example.com, signed in by dev OTP, friend request + accept; display name "David Test": Whisper
  dropped "Ruwan" from Kokoro every time). Call 7 then reached the card: who → "Send it to David, please." →
  "What should I say to David Test?" → "Hello." → "I heard 'Hello.' Tell me what the message … should say" →
  the text → card "I called her a couple of times, …" (lead-in stripped, pronouns as said) → "I just said it …"
  → a new card. Undo: unfriend, delete the user and the allowlist row of +94770000996.
- **For root:** call 7's clips for `scripts/pilot-call-test` (scripts/ is root's): patch kept in the risi
  report, not committed.
- **Not fixed:** "Cancel that and say … instead" may correct the card instead of cancelling it (the old card is
  superseded anyway; paraphrase calendar-cancel-12). A one-word name Whisper is unsure of is kept only when it
  matches one of the caller's people.

## P0 2026-10-16: Call Risi brain v3 (architect 09:40 SLT; Harsha's call 75ec8683, 03:55Z)

**Flag** `RISI_CALL_BRAIN` = `off | canary | on` (+ `v3` = on, `legacy` = off), default **canary** = RISI_DEV_USERS
(Harsha + the pilot test account). Legacy brain unchanged, and it answers any turn whose decision fails.

- **A, one model decision per turn** (`CallRisi.Decider`, model = `config :risime, :risi_call_brain, model:`,
  default risi-l1; greedy, JSON schema, no retries, 8 s): the last 12 turns, the open state, now + zone, the
  tools → `{tool, action, cancel_open, request, say, text, to}`. Learning log task `voice_call_decision`
  (prompt hash + tool/action only, never words). Measured on risi-l1: 1.2–2.1 s a decision.
- **B, dialogue state** (`CallRisi.Dialogue`, per call in the session): the open card (message / calendar /
  reminder, live only while its row is `pending`) and what Risi waits for (message text, recipient, day, an
  answer). Message slots {to, text}: no text → "What should I say to <name>?"; a text the model gave is
  drafted only when the caller's words carry it (75% of content words: "Hi Shenika, how are you" fails).
  The draft goes to the §25 turn as `:call_send` (an atom key: no JSON envelope can carry it) =
  the `send_message` step itself, no model call. Read back: "Here's your message to X: "…". Tap Send on the
  card in your Risi chat to send it, or tell me what to change." A voice yes never sends (§41.9).
  "Cancel that" = `Writes.cancel_request/2` (the Cancel tap; silent `confirm_update` state `cancelled` for a
  risi_items device: shows "Closed"; **root: add `cancelled` to §34.2's states**), "cancel that and …" runs the
  new request too.
- **C, fragments:** a cut-off utterance (dangling word, comma, no final stop from Whisper) waits 1.2 s; the next
  words merge (the session restarts the turn with both); words said while a tool works restart it with all the
  words (v3 never decides a fragment alone; legacy's regex merge stays legacy-only).
- **D, fillers:** "One moment…" after 700 ms of deciding; the calendar line only after a calendar tool.
  Not pre-rendered (that is the voice worker, not touched).
- **E, echo:** an unclear utterance says Sorry only for clear speech (≥ 700 ms, not over Risi's voice, ≥ 1.5 s
  after Risi went quiet); else silence (server and phone STT paths).
- **F, claim guard:** the model's claiming sentences are dropped (logged `call_v3`); message cards get
  message wording everywhere (`ClaimGuard.honest`, `WriteTruth.card_line/status_line`: Send, never "Tap Add").
- **Gate:** `test/risime/agent/call_risi/golden/` = calls 1, 2, 3, 5 and 6 (this one), turn by turn; recorded
  decisions in plain mix test, `mix test --only llm_live test/risime/agent/call_risi/golden` live (GOLDEN_RECORD=1
  re-records). Live risi-l1: 5/5 calls, 0 turn failures. Call 6 is in eval/sets/call-risi (brain v3).
- **Live:** v0.3.0-nightly.11-srv1 (0955184c, d52cf42e), srv2 (9c721be5: "you"/"Risi" is no recipient).
  Device test (pilot-call-test calls 6 5 3 4, redroid, synthetic voice): no calendar misroute, no invented text,
  pause-split merged, cancel honest; voice-yes adds calendar cards (call 3/4). **Open:** Whisper drops the
  synthetic voice's contact names ("send a message to Ruwan" → "send a message too"), and the test account has
  no friends, so the live card path (Tap Send) is covered only by the golden/unit tests. Every v3 turn says
  "One moment…" (decisions take 1.2–2 s on risi-l1).
- **Agent design:** owner = the Voice agent (call brain); learns from the learning-log decision rows and every
  bad call as a golden file (L1); measured by the golden calls (live pass rate) + live acceptance; AOA because
  understanding a spoken dialogue is reasoning, not exactness (dates, cards and sends stay deterministic tools).

## P0 2026-10-16: v1.53 §41.10 server half (phone speech-to-text), srv16 (8848634b)

- `call_risi_stt` kept only with `call_risi`; `CallRisi.PhoneStt` + Session: phone mode only after the worker
  acks `stt_mode` (the RUNNING worker never acks, so srv16 behaves exactly as srv15 until root deploys
  `infra/voice/risi_voice_worker.hotfix.py` = the running copy + §41.10). Finals drive turns (no server STT),
  conf < 0.4 → one "Sorry, I didn't catch that.", `during_risi` without barge-in → no answer, `off` or 3 misses
  (clear speech, no final in 3 s) → server STT. Phone text never logged; runs row `;src=phone` + latency.
- `temperature_fallback=false` on Call Risi chunk transcriptions only (root's asr7 field).
- Harness: `CALLRISI_MIN_MEM_GB=0 VOICE_WORKER=<hotfix> scripts/call-risi-harness --phone-stt` passed
  (5 phone turns, one Sorry for conf 0.2, the phone's "tea" drove the turn, 6 speaking on/off, fallback to
  server after 3 un-finaled utterances).

## P0 2026-10-15 (evening): Harsha's 3rd Call Risi call (14:13–14:17Z, srv10) + the claim guard

**What happened (his call):** the all-day Risi Calendar item ("Free up calendar on 18th Oct whole day", stored
00:00–01:00 since 11 Oct) and the same block on the phone were spoken as "midnight to 1 am" twice; "anything from
2 am until 10 pm" went to the chat route ("just ask me directly"); the correction "From 9 to 10, discuss racing"
went to the chat route ("I can add that for you"); "That's correct" got "Was that for me?"; then the chat route
said "I have added the racing discussion" and "but I have added the entry" — **nothing was added.**
**The writes (risi_pending_writes, read-only):** each write turn first tried the phone `calendar_add` as an
Allowed write (14:15:31, 14:15:49): the phone answered the tool call within ~70 ms with a non-ok status (not
stored), the write went `void`; the model then made a Risi Calendar card: 14:15:35 "Appointment · Fri 16 Oct,
9–10 AM" (superseded at 14:15:47 by the next request) and 14:15:51 "Blocked out 10-12 · Fri 16 Oct, 9–10 AM"
(wrong title and time: he said 10 to 12) — still `pending`, never tapped. So: no event was added anywhere.

**Fixed (server only):**
- `RisiMe.Agent.ClaimGuard` (shared): "added / sent / saved / booked / scheduled / deleted …" only when the
  request holds a `done` write (its write_id = the result id); else the honest status ("I've drafted it. Tap
  Add to confirm.", "That didn't go through…"), logged `claim_violation` (warning with the path, telemetry
  `[:risime, :risi, :claim_violation]`, a `risi_agent_runs` row agent `claim_guard`; no text). Called on the §25
  turn's answer before `Audience.deliver` (Risi chat, Official @Risi, spoken "Risi, …", answer cards, the call's
  voice sink), Call Risi's chat route (an offer "I can add that" counts there), the call's tool answers and the
  model's rephrase of a spoken answer.
- `CallRisi.WriteTruth`: the call keeps its last write turn (request id); "that's correct / yes / add it" →
  the card's real state; "I can't see it / did you add it?" → re-reads the row and says what it finds (never
  insists); corrections ("from 9 to 10, discuss racing", "to … discuss about") go to the write turn ("Change
  the event I just asked you to add: …", the draft is patched and the old card superseded), also while held.
  The card is spoken from its summary ("I haven't added it yet. I've put a card in your Risi chat: Discuss
  racing, tomorrow, 9 to 10 am. Tap Add …"; `Writes` tells the call the card it made).
- §41.9 voice confirm (`CallRisi.VoiceConfirm`, flag `RISI_CALL_VOICE_CONFIRM`, default `canary`): with it,
  the card is read back with "Shall I add it to your Risi Calendar?" and a clear yes runs `Writes.act/4`
  `confirm_write` (same path as the Add tap, same write_id, ActionGuard), only the caller's own personal
  calendar/reminder card in the call's Risi chat; "Added to your Risi Calendar: …" only after the row is `done`.
- Spoken: all day for an item from midnight whose title says whole/all/full day or a block ≥ 23 h; a phone block
  at the same time as Risi's own item is said once; Risi Calendar titles lose date words ("on your Risi Calendar,
  free up calendar, all day"; "send SSH access for iPhone stuff, from 6 to 7 pm").
- Windows: "from 2 am until 10 pm", "between 9 and 5", "after lunch", "in the morning", "after 6 pm" → a calendar
  read of the topic's day (or the named day), only items in the window plus all-day ones.
- "Was that for me?" only when Risi's last line was no question/offer and the words are no reply.
- "The 16th?" on his call: the phone WAS read (card footer "From: your phone calendars · Risi"); a phone that
  doesn't answer is said first (test added).
- **srv11 device run (15:27Z) found the real cause of "failed to add":** the phone runs `calendar_add` only
  after its user's own tap on the card (§25.3 rule a) or a typed `risi_request` in its history (rule b). A
  call turn has neither, so the phone declines within ~20 ms: Harsha's Allowed attempts went `void`, and a
  voice yes on a phone card ends `confirmed` (failed). Fix (srv12): on a call a phone write is always a card
  (never "Allowed"); a caller with voice confirm and the Risi Calendar gets `risi_calendar_add` for adds on
  the call (not `calendar_add`); voice confirm only for server-side writes (`risi_calendar_add`,
  `set_reminder`); a phone card's yes is told "Your phone adds this one itself, so tap Add". For root: a voice
  yes for the phone's own calendar needs an app/contract change (the phone accepting a server-attested voice
  confirm) — not done here. Card readback of every summary kind fixed ("00 to 17:00" on srv11).
- **Live:** srv11 (15:16Z), srv12 (15:47Z). Device (redroid, test account, srv12): chat pass 10/10 + reminder card
  (on srv11, run 20261015T152108Z); calls 3+4 at 15:48Z: the 18th "busy all day … also 2 to 3 pm"; the 2 am–10 pm
  window answered; the RISD card then the racing correction read back ("Shall I add it to your Risi Calendar?");
  "That's correct" → "Added to your Risi Calendar: Discuss about racing, Sunday the 18th, 9 to 10 am" (row
  `done`, via confirm); "I can't see that you have added something" → "Yes, it's added …"; call 4 "Call with the
  bank, tomorrow, 4 to 5 pm" added by voice, "Did you add it?" → yes (row `done`). 0 claim_violation logged.
- **Not fixed:** the write turn's model still drops an end time ("from 10 to 12" → 10–11 am, as on Harsha's
  card) and, without a day, took the 18th (the earlier topic); the read-back now exposes both so the caller can
  correct. The voice yes can't run the phone's own `calendar_add` (needs an app/contract change).
- **srv13 (root 15 Oct):** `RisiMe.Agent.TimeRange` — the range in the user's own words ("from 10 to 12", "10–12",
  "9 to 10 am", "between 3 and 4:30", "at 3 for two hours"; am/pm from the other side, else working hours 7–19)
  sets the `calendar_add`/`risi_calendar_add` start/end before the tool runs and the action draft's slots (the day
  too when the words name one); a Call Risi write without a day asks "Which day should I put it on?" and never
  takes the day of an earlier read. Tests: `test/risime/agent/time_range_test.exs`, call_3_test.
- **srv13 live 16:35Z** (first fast-lane try failed on a flaky `ActionItemsTest` setup, `with_attestation_key`
  `{:error, :exists}`; the retry passed 1131/0). Device on srv13: chat pass 10/10 (run 20261015T163925Z); call 3
  drafts "Discuss about RISD, tomorrow, 10 am to 12 pm", the racing correction 9 to 10 am, "That's correct" →
  "Added …" (row done); call 4 added by voice. The turn path's guard fired once correctly (the correction turn's
  model said it had updated the event while its card was pending). Open: STT spelled "The Sixteenth" and the
  day parser doesn't read word ordinals ("Was that for me?").
- Tests: `test/risime/agent/call_risi/call_3_test.exs` (the replay, call 4 voice confirm, units),
  `test/risime/agent/claim_guard_test.exs` (10 adversarial prompts); question set 52/52 with
  `harsha-2026-10-15-call-3`. Device: `pilot-device-test` seeds an all-day "Family day" on the 18th and turns on the
  Call Risi developer flag; `PCT_CALLS="3 4" scripts/pilot-call-test` (call 3 = his words, call 4 = add by voice).

## P0 2026-10-15: Harsha's Call Risi call (07:11–07:13Z) fixed

**State:** green (agent + contract suites; question set 50/50 with the fake model). Server only.
Root causes (all confirmed in code and the pilot log, session `#PID<0.6556.0>`):
- **a)** `Turn.voice_sink` spoke the §25 card's `answer`; with v2 + local events that is only the
  head line + footer. Now the turn also hands the call `RisiItems.voice_facts/4` (phone busy
  times + Risi Calendar events, reminders, promises, follow-ups, scheduled messages; local times)
  and `CallRisi.Spoken` renders them; risi-l1 may rephrase, used only if it keeps every time, adds
  no number, names every title and doesn't refuse (`Spoken.checked/3`). The card is unchanged.
- **b)** fragments / follow-ups went to the chat route (no tools). Now the session keeps the
  call's topic (last calendar range); a date-only fragment, "why can't you…", "what about…",
  "anything else", "the entire week" after a calendar turn is a calendar read (range passed to
  the turn as env `__schedule`). The chat prompt no longer lets the model say it can't see the
  calendar.
- **c)** the §25 turn ran in an unlinked `Task.start`: a cancelled brain left it holding the
  per-user lock → `{:snooze,_}` → "I'm still busy". Now `Task.start_link` (brain traps exits): a
  new question kills both and frees the lock; a held lock is retried every 0.7 s inside the 25 s
  budget; the "still busy" line is gone.
- **d)** `ScheduleIntent.relative/2` matched "tomorrow" before "today": "today and tomorrow" = Fri
  only. Now two days.
- **Date choices (recorded):** "the 18th" = this month's 18th when it is today or later, else next
  month's; "this week" / "the (entire|whole) week" / "rest of the week" = today → end of Sunday,
  except on a Sunday = the coming 7 days (keeps the normative §34 fixture). "anything <day>" is a
  schedule question.
- Fragments: a new utterance starting ≤ 1.5 s after the last one ended merges with it when no
  answer to it was heard yet (a filler may have been); the earlier turn is cancelled.
- "What can you do?": a true spoken list from `Tools.allowed/1` for this user (calendar, Risi's
  items, memory, reminders, calendar add, send/schedule message — only those offered).
- Titles: §41.8 `PhoneCalendar` wired (cal_req at the start of a calendar voice turn on a
  `call_risi_cal` device, ≤ 2 s); titles only in the one spoken answer; stored transcript line =
  times-only rendering, `titles_spoken: true`; learning log `log_fields` counts only; else times +
  "I can see the times; the titles stay on your phone." once a call.
- si/ta speech: "I can only speak English on calls for now, so I'll answer in English." once a
  call, then the answer.
- Question set: `eval/sets/call-risi/questions.jsonl` (50 cases incl. `harsha-2026-10-15-call`);
  runner `mix test --only call_questions` (fake model; `CALL_QUESTIONS_LIVE=1` = live risi-l1).

## PART 9: Risi reads every Official voice note, understands it and acts (2026-10-13)

**State:** green (`MIX_TEST_PARTITION=p9`, full suite). Server-only, no migration and no wire change,
so it is **fast-lane eligible**. Point 2 is a contract proposal and is not implemented. Root deploys.

**1. Same pipelines as a typed Official message.** Once an Official note is transcribed `ok`,
`Voice` hands its sealed working copy (`voice_transcript`, buffered under the note's `message_id`)
to the entry points `Agent.Conversation` uses for a `text`: `Memory.put_message/2` and
`Secretary.on_message/2`. There is no second pipeline.
- **Memory:** the transcript is stored as the speaker's own message, with the note's id as its
  source. Risi's transcript post is no longer remembered (`memory: false`), so nothing is stored
  twice. Memory opt-outs are respected, and an `unclear` or `failed` note is not remembered.
- **Context:** `Secretary.text_messages/3` returns the note (`voice: true`, `duration_ms`).
  Summaries, @Risi answers, daily and period summaries, and the memory seed and backfill all see it.
- **Ledger and calendar offers:**
  - With `RISI_LEDGER` off, the v1.24 extraction runs.
  - With it on, the §27.2 quiet rule counts the note, and calendar offers follow through the
    discussion summary (`Offers` → `CalendarOffers`).
  - When §38 makes the note's action items (`ActionItems.handles_voice?/3`: §38 on for the sender,
    the note is at least 5 s, the sender's `action_items` is on and memory isn't opted out), the
    quiet rule leaves the note out. Its promises, meetings and calendar come from the §38 card and
    `action_confirm`, so nothing is offered twice. This follows §38.1: for in-scope sources,
    `action_items` replaces the discussion card.
- **Policy agent:** doesn't exist yet. When it is built, it hooks into `Secretary.on_message/2` and
  so sees voice notes automatically.
- **Deletes:**
  - A note deleted for everyone already takes its memory row, facts and promises
    (`Secretary.message_deleted`).
  - `transcript_delete` by the sender now does the same, and removes the working copy too.
  - A note deleted while it was being transcribed is never buffered or learned.
- **Spoofing:** `voice_transcript` is Risi's own working-copy type. A member's MLS message that
  claims this type is ignored (`Conversation.buffer_human`).
- **Private and the Risi chat:** never (tests assert this). `Voice.decide` skips any non-Official
  conversation before touching a blob. Memory and the secretary check Official again. In the Risi
  chat a note stays a §37.8 request.

**2. "Risi, …" spoken = @Risi: proposal, not implemented.** I checked PROTOCOL.md:
- §25.0 says free text is "never parsed for intent by the server or by Risi". Its only exception,
  from decision 083, is client-side.
- The v1.42 clarification adds "the server still never parses free text".

For a voice note only the server sees the transcript, so the decision-083 client exception can't
cover it. Address detection on the transcript would be a server-side parse, which the contract
forbids as written. The amendment is in `contract/proposals/2026-10-13-voice-address-risi.md`: a
narrow §37.10 exception covering only the transcript's opening word and its variants, an `ask` with
a UUIDv5 `request_id`, no new wire fields, and the `RISI_VOICE_ADDRESS` off/canary/on switch. Stopped
there for Harsha and root.

**3. Canary switches.** `RISI_VOICE_TRANSCRIBE` and `RISI_ACTION_ITEMS` now take `off` | `canary` |
`on` and are read at runtime (`RisiMe.Agents.Flags.feature/1`). Resolution order: the
`risi_agent_flags` row, then the OS env when it says `canary`, then the application env from
`config/runtime.exs`. Defaults are unchanged (off), and `Flags.put` validates the value.
- `canary` means on only for `RISI_DEV_USERS`:
  - voice transcription: the sender of the note;
  - voice action items: the sender of the note;
  - call action items: any speaker in the call.
- **`/auth/config` reports `off` under `canary`.** It is unauthenticated, and saying "on" would tell
  every phone that Risi transcribes its notes (decision 048 honesty). So Harsha's phone doesn't
  show the one-time Official note or the "From calls & voice notes" chip during the canary. Cards
  and transcripts still render. A per-user value would need the server role to pass the caller to
  `config_on?`.
- Flip with no deploy (live within 5 s):
  `scripts/risi-rpc 'RisiMe.Agents.Flags.put("RISI_VOICE_TRANSCRIBE", "canary")'`
  (and `"RISI_ACTION_ITEMS"`; `Flags.clear/1` returns to env).

**4. Agent design (decision 084/085).**
- **Owner:** the **Voice** agent (deep). It wraps `Voice`, `CallTranscribe`, `ActionItems` and
  `Speech`. Its goal: every Official voice note and call becomes a faithful transcript plus
  understanding (memory, promises, meetings, action items) in the speaker's language. Downstream it
  uses the Ledger, Memory and Calendar agents as tools, through the same typed-message entry
  points; it doesn't reimplement them.
- **Why AOA (hybrid):**
  - What a note means, who owns an item, and when it is due are judgement calls that must improve
    with feedback, so they belong to the agent.
  - Blob fetch, in-memory decryption, the speech service and storage stay infrastructure (SOA).
    Exactness and privacy matter more there than judgement.
- **Signals (learning):**
  - L1 (per user): the language tally from each note (`risi_speech_langs`).
  - Live outcome signals (`action_feedback`), which are also the L2 training signal (extraction
    prompts and few-shot examples from accepted items):
    - item confirm, edit, Not mine, claim and expiry;
    - `transcript_delete`;
    - ledger card outcomes for notes counted by the quiet rule.
  - L3 (later): ASR fine-tunes on consented clips, in the GPU window and through the Coach.
- **Index: Voice understanding (decision 085)** = 0.7 × Eval + 0.3 × Live.
  - Eval: item F1 on golden calls and notes. Owner, kind and due must match, and an invented name,
    amount or date scores 0 for that case.
  - Live: items confirmed ÷ (confirmed + Not mine + edited) over 7 days. Below 20 live signals it is
    shown as "Eval only (n)".
  - Transcription en/si/ta keeps its own Index.
- **Rollout:** shadow isn't needed (the pipelines are existing and measured). The path is canary
  (Harsha first, via the flags above), then on. A drop in the Index rolls back under the promotion
  rule.

## Speech language hints (decision 071 amendment d, 2026-10-13)

**State:** green (`MIX_TEST_PARTITION=lh`). Server-only, no migration, no wire change: fast-lane eligible.
- `Speech.Http` sends `language_hint=si,ta` on transcribe, compare and the per-model compare fallback
  when `opts[:language_hint]` is given; omitted otherwise.
- `Lang.speech_hint/2`: the si/ta among the participants' `user_lang/1` and the chat's new
  `conv_lang/1` (script of its last 40 remembered human messages); `[]` when none, so all-English
  calls send nothing (behaviour unchanged for them).
- `CallTranscribe.Call` computes it once per session (`start_on`) for every chunk; voice notes use the
  sender's and the chat's. Logs: the codes only (`language hint si,ta`), never text.
- App language: `contract/proposals/2026-10-13-app-language-hint.md` (for root/Harsha).
- Rollback without deploy: `WHISPER_HINT_MODE=off` on the speech service.

**Fix 2026-10-13 (empty hint on the pilot).** Pilot rpc (counts/codes only): every call participant's
remembered Official messages are Latin script (Harsha 40/40 "en", no Singlish tokens either), his Risi-chat
requests too, and call transcripts are stored as Risi posts (`from_risi`), so `user_lang`/`conv_lang` were
always "en" and the hint `[]`. The speech service then labelled most Sinhala speech "en" (learning log: 216 s
"en" for the 02:51 call). New speech signals in `Lang.speech_hint/2`:
- `speech_lang/1`: each user's own transcribed speech per language (new table `risi_speech_langs`,
  migration `20261105100000_risi_speech_langs`: codes + ms, no text), fed per speaker at the end of every
  non-discarded call session and by every voice note (romanised Sinhala counts; silence and low-confidence
  don't; learning opt-out respected; halves past 2 h; "Forget everything" deletes it).
- `chat_speech_lang/1`: the learning log's `transcribe` rows of the chat (this + last month, `audio_s`).
- si/ta when ≥ 10 s and ≥ 10 % of the speech (prior mode leaves clear English unchanged); English-only → `[]`.
On today's data this gives `si` for Harsha's chat with fb81 (84 s si of 264 s) and `ta` for the chat with
62ee (12 s of 96 s), but **still nothing for the 02:51 call's chat**, where the service never detected any
Sinhala. For a Sinhala speaker who types English/Singlish and whose speech was never once detected as si/ta,
the only reliable signal is the phone's app language (the proposal above). The tally starts empty on deploy.

## PART 6 steps 0–2: deep agents (decision 084)

**State:** green on `main` (full server suite, `MIX_TEST_PARTITION=da`). Server-only, fast-lane eligible
(one additive migration `20261103100000_risi_agents`). Root deploys. **No behaviour change by default:**
`RISI_AGENTS=on`, `RISI_AGENT_ROUTER=shadow` (rules only, no model call), every other agent `off`.
Design, flags, budgets, GPU memory budget: `docs/decisions/084-deep-agents.md`. Scores: `docs/status/risi-scores.md`.

### What is in
- **Agents** (`server/lib/risime/agent/agents/`): behaviour `RisiMe.Agents.Agent`; registry `RisiMe.Agents`
  (Fast router built; Planner, Ledger, Voice, Calendar, Memory, Summary, Marketplace, Twin declared,
  `run` refuses → legacy); `RisiMe.Agents.Supervisor` (last child of the Risi tree: flag cache,
  `Task.Supervisor`, one `Agents.Server` per agent); `Orchestrator.call/3` (off/shadow/canary/on, hard
  budget, auto-fallback + incident); `Activity` (agent-activity-v1 on PubSub `risi:agent_activity` +
  telemetry, no text); `Runs` (`risi_agent_runs`); `ToolRegistry` (MCP-style descriptors over today's
  `Agent.Tools`, `call/3` = authorize + the same `run`).
- **Flags without deploy:** `risi_agent_flags` table > env > default, 5-s cache.
  `scripts/risi-rpc 'RisiMe.Agents.Flags.put("RISI_AGENTS", "legacy")'`
  (or the psql upsert in decision 084). `RISI_AGENTS=legacy` = every agent off, no shadow, no timing rows.
- **Shadow (step 2):** `Agent.Turn` calls `RisiMe.Agents.Shadow.turn/4` once per turn after choosing its
  route (an observer: rescue-wrapped, never changes the route). It logs the router's route vs today's
  (`agreed`), and `TurnTiming` writes one `legacy_turn` row per finished turn (duration, typing time
  `first_ms`, route, language). Router model: `RISI_ROUTER_MODEL=risi-l1` (flag table or env) asks
  risi-l1 (tiny non-thinking prompt, ≤ 40 tokens, `risi_route` schema) only for low-confidence asks,
  in the background; `RISI_ROUTER_URL` swaps in a loopback risi-l2 later (`LLM` takes `model_alias`,
  `base_url`; defaults unchanged).
- **Eval (step 1):** golden sets `server/test/risime/agent/golden/{router,ledger,summary}.jsonl`
  (en/si/ta/Singlish; hallucination traps). `scripts/risi-eval` (fake) / `scripts/risi-eval --live`
  (risi-l1, read-only, sequential). Daily scoreboard: `RisiMe.Agents.ScoreboardJob` (cron 00:20 UTC) →
  `risi_agent_scores`; `scripts/risi-eval scores` → `docs/status/risi-scores.md`;
  `GET /api/v1/risi/agents` (RISI_DEV_USERS only, 404 otherwise).
- **Streaming:** typing/progress already exist (`risi_progress`); partial answer text needs a wire
  change → `contract/proposals/2026-10-13-risi-streaming.md` (stopped there).
- **Fix found by the parity tests:** `ActionItems.from_call` crashed when the item owner was the call's
  only speaker (`LedgerOut.name_list([])`); the confirm card now says "From your call (HH:MM)".

### Parity tests (tag `:risi_parity`, part of plain `mix test` → the blocking server gate)
`server/test/risime/agent/parity/`, fake risi-l1, each with a < 3 s server-side timing bound:
- `turn_parity_test.exs`: direct `final` (answer body, key set, message source, one call); tool step then
  `final` (capabilities, two calls); schedule question forces `risi_calendar_check`; `risi_items` answered by
  the server with no model call; `ScheduleIntent.classify` on today's phrases.
- `ledger_parity_test.exs`: quiet discussion → one `discussion_summary` call, rows, copies, card; no items →
  nothing posted; not quiet yet → no call.
- `calendar_parity_test.exs`: event draft → `risi_calendar_add` confirm card, [Add] creates it with no model
  call; model-chosen `risi_calendar_check` → the "Checked:" line and source.
- `memory_parity_test.exs`: summarise this month (forced `summarise_range`, memory sources); what did I agree
  with Upali (`memory_search` with people/range); nothing stored; nothing about that.
- `summary_parity_test.exs`: summarise this chat; summarise today; nothing to summarise; the 23:35 day summary.
- `action_items_parity_test.exs`: voice note; call; unclear note; clear note without items; owner as the only
  speaker (the fix).
- `call_summary_parity_test.exs`: ≥ 30 s from 2 speakers → summary, card, item; < 30 s → nothing.
- Plus `server/test/risime/agent/agents/`: `agents_test.exs` (flags, orchestrator, fallback/incidents,
  activity, tool registry, router, shadow beside a real turn, legacy switch), `eval_test.exs` (golden sets,
  traps caught), `scoreboard_test.exs` (rows, Markdown, job, admin view); `eval_live_test.exs` (`:llm_live`).

### Needs Harsha / root
- Root: fast-lane deploy; then `scripts/risi-eval --live` at a quiet hour for the router's model numbers.
- Root: the admin route (`GET /api/v1/risi/agents`, 1 router line + controller) and the cron line in
  `config/config.exs` are outside the risi paths (authorised for this task); not app wire protocol.
- Harsha: the streaming proposal (open questions inside). A risi-l2 model (≤ 10 GB all-in per the GPU
  budget) needs a download OK (> 2 GB likely) — not needed yet.
- Harsha: after 3 days of shadow rows, decide router canary (`RISI_AGENT_ROUTER=canary`).

## Step 2: long-term memory and retrieval (contract v1.36 §35.1–§35.3)

**State:** green on `main` (`6edec63`; server suite 1185 tests, 0 failures; live risi-l1 memory checks 4/4). Root deployed it through the fast lane. The example now carries `turn_ref` (9ca479b) and is compared exactly. The hashing embeddings are recorded as decision 078.

### What it does
- **Memory (§35.1).**
  - What is stored: every Official `text` that Risi decrypts as a member, and Risi's own posts in those chats.
  - Sealing: each row is sealed under `RISI_DATA_KEY` (AAD `risi_memory_messages:<id>:body`) in `risi_memory_messages`.
  - Retention: rows are kept until they are deleted. They are also purged when Risi leaves the chat or Official is turned off.
  - Never stored: the user's own Risi chat and Private.
  - Every row gets a vector in `risi_memory_vectors`, which holds no text.
  - Buffer seeding: the hourly prune job copies the 24-h buffer in once per chat (`Memory.seed_all`). Reads also include buffered texts, so memory reaches back about 24 h before the deploy.
- **Opt-out.** A sender who switched off learning (in one chat or everywhere) is dropped before sealing.
- **Ingest (`memory_ingest`).** An Oban job per chat (queue `risi`, 90-s debounce, args are ids only) does the following:
  - sends the new messages to risi-l1 (task `memory_extract`);
  - stores sealed items of kind person, project, decision, promise, meeting, deadline or open_question, with people resolved to user ids and the source message ids;
  - adds a vector per item.
- **Embeddings.** The model is `risi-hash-384-v1`: deterministic multilingual feature hashing over words and character trigrams, 384 dimensions, computed in the BEAM. There is no download and no service.
  - Why: no embedding model exists on spark2, the vLLM container is off-limits, and downloads need Harsha's OK.
  - A neural model on loopback can replace it behind `Embeddings.embed/1`; the `model` column tells the two apart.
  - Root: please record this as a decision.
- **Tools (§35.2).**
  - `memory_search {query, range, people, kinds, conversation_id}`: the nearest stored messages, recent messages with the named people, items, and open promises with those people.
  - `summarise_range {range, people, project}`:
    - It gathers messages, items, the existing 30-day chat summaries and the ledger promises in the range.
    - When the messages are over about 14 000 characters, it map-reduces them: chunks of about 10 000 characters each go to one model call (`memory_map`, giving cited points; at most 6 chunks, newest first), and the final answer is the reduce.
  - Every line has an `r<n>` ref. Each answer source is `{"type":"memory","conversation_id","message_ids","at"}`. When the model cites nothing, every chat it was given is listed as a source.
  - Scope: only the asker's own Official chats. A named person narrows the search to the chats that person is in.
- **Router (§35.2).** `MemoryIntent` runs inside `ScheduleIntent.classify`, after Risi's items and before writes and schedule questions. It handles "summarise <range|person|work>", "what did I agree/decide with X", "what's pending with X", "last week", "this month" and so on, in English, Sinhala, Tamil and Singlish.
  - The server runs the tool itself as step 1, with no write tools offered for the rest of the turn.
  - Nothing found: the server answers "I don't have anything about that…" itself, with no model call.
  - A range that starts before memory: the server appends "My memory of your Official chats starts on <date>, so I don't know what was said before that." This is in English, Sinhala and Tamil.
  - "summarise this chat" (the §27 path) is unchanged.
- **Language fix.** `Lang.detect` uses the script of the request.
  - The system prompt now names the answer's language and script.
  - When a Sinhala or Tamil request gets an answer with under 20 % of its letters in that script, the model is asked once to rewrite it in that script.
  - This applies to every turn except the server-built schedule and items answers.
- **REST (§35.3).**
  - `DELETE /api/v1/risi/memory?conversation_id=` removes the caller's own messages, Risi's posts addressed to the caller, the items derived from them and their vectors.
  - `DELETE /api/v1/risi/memory` needs `X-Confirm: delete-all`; without it the reply is 400.
  - `PUT` and `GET /api/v1/risi/learning`.
  - The existing facts endpoints now also list and delete memory items.
- **Flag.** `RISI_MEMORY=off` stops storing. It is on by default.

### Test phrases for Harsha (in your Risi chat)
Memory only starts at the deploy. Before that, Risi only has the last 24 h of the buffer, plus the chat summaries and promises it already holds. Write a few Official messages first, for example with Upali or Shenika.
1. `summarise the work done this month`: one step, `summarise_range`, then a summary with sources and the line "My memory … starts on <date>…".
2. `what did I agree with Upali last week`: `memory_search` over your chats with Upali only. If nothing is stored, you get the honest "I don't have anything about that…".
3. `what's pending with Shenika`: your messages with her, plus open promises with her.
4. Sinhala: `මේ මාසේ කරපු වැඩ සාරාංශ කරන්න`, `ගිය සතියේ Upali එක්ක මම මොනවද එකඟ වුණේ?`, `Shenika එක්ක ඉතිරි වෙලා තියෙන වැඩ මොනවද?`. The answer comes in Sinhala script.
5. Tamil: `இந்த மாதம் செய்த வேலையைச் சுருக்கமாகச் சொல்லுங்கள்`, `கடந்த வாரம் Upali உடன் நான் என்ன ஒப்புக்கொண்டேன்?`, `Shenika உடன் நிலுவையில் உள்ளவை என்ன?`. The answer comes in Tamil script.
6. Singlish: `Upali ekka giya sathiye mokakda agree une?`
7. Controls (API, until the app has the UI): `PUT /api/v1/risi/learning {"conversation_id":null,"enabled":false}`. After that, your new messages are not remembered.

### Notes for root
- `envelope_risi_answer_memory_sources.json` has no `turn_ref`, but every turn answer carries one (§25.4). The example test drops that single key. Please add `turn_ref` to the example.
- The live-model checks are `mix test --only llm_live test/risime/agent/memory_live_test.exs`: extraction, plus the Sinhala, Tamil and English summaries. `scripts/risi-canary` uses fake-llm.
- Authorised edits outside the risi paths:
  - `RisiMe.Workers.Risi`: the `memory_ingest` kind and `seed_all` in the prune job;
  - the router: 3 lines;
  - `RisiMeWeb.RisiMemoryController`;
  - `test/contract/examples_test.exs`: the coverage list.

## Step 3: always-on ingest worker (contract v1.36 §35.4)

**State:** green on `main` (server suite 1191 tests, 0 failures; live risi-l1 checks 5/5, which include the note translations). Root deploys it through the fast lane.

**The autonomy runs inside the Commitment Ledger and Notes pipeline. The pilot has `RISI_LEDGER` and `RISI_NOTES` off** (`infra/pilot/pilot.env`). That is why Risi "never acts on its own" today.
- For step 3 to be live, root (Harsha's call) must set `RISI_LEDGER=on` and `RISI_NOTES=on`.
- `RISI_QUIET_S` can stay unset: the default is now 1800.
- Memory (step 2) does not depend on these flags.

### What it does
- **Quiet period (§35.4).** The default `RISI_QUIET_S` is now **1800 s** (30 min after the last message). The ≥ 6 messages from ≥ 2 people, the 30-min spacing and the 8-a-day caps are unchanged.
- **Chat summary.** A discussion with **≥ 3 key points** is now summarised **to the chat** even without items or notes users. The chat gets a `discussion_card` with `items_count: 0`, the `summary` and `key_points`, and the body `Summary: …` plus bullets. Fewer than 3 key points and no item still posts nothing.
- **Per-participant Risi Note in their language.** Each recipient's language is the script of their own remembered Official messages (`Lang.user_lang`: si or ta when at least 30 % of their messages are in that script, else en).
  - When it differs from the note's language, the topic and key points are translated once per language by risi-l1 (task `note_translate`, with a weekday glossary) and sealed in `risi_note_translations`.
  - The note card and `GET /risi/notes/{id}` show each viewer their own language.
  - A failed translation, or one in the wrong script, falls back to the original.
- **Calendar proposals per participant, by the Calendar skill.**
  - **Risi Calendar users** (item offers and note meetings): under **Allowed** the participant accepts at once, the status is read back from the event, and they get `event_card mode: "added"` ("Added to your Risi Calendar: …"). Under **Ask** they get the `calendar_invite` as before.
  - **Phone calendar users** (item offers): under **Allowed** the server runs `calendar_add` on the phone at once (`Writes.offer_allowed`). It counts as added **only after the phone's read-back** (`verified: true`, then "Added to your calendar: …"). A phone that does not confirm falls back to the "Add to calendar?" card, so an item is never offered twice. Under **Ask**, the card is posted as before.

### Test phrases and steps for Harsha (after root turns the ledger and notes on)
1. Settings → Risi skills → Calendar → **Allowed**. In an Official group with Risi, agree a meeting with at least one other person, for example "Site visit on Wednesday 2pm with Shenika?" / "Yes, Wednesday 2pm works". Send 6 or more messages, then wait 30 min. You get "Added to your Risi Calendar: Site visit · Wed …" in your Risi chat. Shenika, on Ask, gets an invite.
2. With Calendar on **Ask**: the same chat gives you an invite or an "Add to calendar?" card instead.
3. Talk about work in a group without promises (3 or more distinct points), then wait 30 min. The group gets "Summary: …" with bullets.
4. A Sinhala-writing participant gets the Risi Note card in Sinhala, and an English writer gets it in English. Open Notes in the app to see the same.
5. A summary right away still works: `@Risi summarise`.

### Notes for root
- Authorised edits outside the risi paths:
  - `config/test.exs`: `risi_quiet_s 600` keeps the §27/§30 suites on their timeline;
  - `RisiMe.Agent.*` modules Ledger, LedgerOut, Offers, CalendarOffers, Notes, NotesOut and Writes, which are under `server/lib/risime/agent` (my path).
- New migration: `20261028100000_risi_note_translations.exs` (additive).
- Live-model finding: the Sinhala translation once rendered "installed" as "sent", and before the glossary it rendered Thursday as Wednesday. Weekdays are now in the glossary; general translation quality still depends on risi-l1.

## Step 4: cancellation and change propagation, deadline nudges, the 07:00 brief (contract v1.36 §35.4)

**State:** green on `main` (server suite 1198 tests, 0 failures; live risi-l1 checks 6/6). Root deploys it through the fast lane.

Two of the three parts do not need `RISI_LEDGER` or `RISI_NOTES`:
- **The brief.** It reads Risi Calendar, Risi's items, promises as stored, and memory.
- **Propagation.** It runs from the memory ingest.

The nudges work in both modes.

### What it does
- **07:00 daily brief** (`RisiMe.Agent.Brief`, run by the `digest_sweep` job every 15 min). At 07:xx in the user's zone, once a day, it posts a `digest` with `scope: "brief"` in the user's Risi chat. It has these sections:
  - **Today:** Risi Calendar events and Risi's items of the day.
  - **Due today** and **Overdue:** the user's own open promises.
  - **Waiting on others:** promises owed to the user, due within 7 days or overdue.
  - **From your chats:** today's deadlines and meetings found by memory that are not already listed.
  - **Yesterday's decisions:** decisions memory found yesterday.

  More details:
  - Every line comes from a stored row; no model writes it. The "Checked:" line says the phone calendar isn't read.
  - Labels are in the user's language (si, ta or en).
  - Nothing to say means no post. `RISI_BRIEF=off` turns it off.
  - The ledger's 09:00 personal digest is skipped on a day the brief went out.
- **Deadline nudges.**
  - Before the deadline: the existing reminder at due − 1 h, or the ledger's `item_due`.
  - After a missed deadline, ledger off: the first escalation (24 h after due) now also asks the owner in their Risi chat: "\"X\" was due …. Is it done? …". The counterparts are told in the chat as before.
  - After a missed deadline, ledger on: `item_overdue` goes to the owner and `item_nudge` to the counterparts, as before.
- **Cancellation and change propagation** (`RisiMe.Agent.Propagation`). The memory ingest now marks a meeting as `status: cancelled` or `moved` (with `new_at`). Then:
  - **Matching:** the one active, future Risi Calendar event made from that chat is found. The time is matched within 1 h, or the date when only a date is given; otherwise the clear best title match is used. When nothing matches clearly, nothing changes.
  - **Cancelled:**
    - The event is cancelled for everyone and the cancellation is read back.
    - The participants get the "was cancelled" update.
    - Each participant with a Risi chat gets **"Reschedule?"** with up to 3 slots free for everyone in Risi Calendar: the next 5 days, 09:00–18:00, no Sundays, the original length, as `ask` chips such as "Add \"Site visit\" with Shenika on Tue 13 Oct 10:00".
  - **Moved:** the event is moved as its owner and the new start is read back. The others are asked again.

### Test phrases and steps for Harsha
1. **Brief.** Tomorrow at 07:00, your Risi chat gets "Good morning. Your day, …", provided you have a Risi Calendar event, a promise, or something from your chats. A Sinhala writer gets Sinhala labels. Nothing to report means no message.
2. **Cancel.** Agree a meeting in an Official group so that Risi makes a Risi Calendar event for it (from a promise or a note meeting). Then write in the group "Sorry, we have to cancel Monday's site visit". About 2 minutes later:
   - the event is cancelled;
   - both of you get the update;
   - each of you gets "Reschedule? Free for everyone in Risi Calendar: …" with tap-to-ask chips.
3. **Move.** Write "the site visit moves to Tuesday 10am". The event moves, and the others are asked to accept again.
4. **Overdue.** A promise 24 h past due gives you, the owner, "Is it done?" in your Risi chat.

### Notes for root
- Authorised edits outside the risi paths:
  - `RisiMe.Workers.Risi`: `Brief.sweep` in `digest_sweep`.
  - Inside `server/lib/risime/agent` (my path): Commitments, LedgerReminders, Memory and MemoryIngest.
- New migration: `20261029100000_risi_brief.exs` (additive).
- New wire use:
  - `digest` with `scope: "brief"`, with the fields `events`, `risi_items`, `items`, `decisions` and `language`;
  - `reminder` with `phase: "overdue"`.

  Old apps show the body. Root may want to note both in §35.4.
- Slots count only Risi Calendar busy time, because the server can't read phone calendars. The message says so.

## Step 5: backfill
Not started; it goes through root's APK path.

## PART 4: Talking to Risi (12 Oct; decision 083; gate `scripts/risi-chat-test`)

**State:** green on `main` (483b14d server, 9e7d3d7 android, cfda1d8 gate). The server part is server-only and
can ship through the fast lane (483b14d); the client part needs the next APK. Gate run on cfda1d8:
`RCTEST OK` (fake-llm), every check passed. Shots: `docs/status/screens/66-risi-*.png`.

### What failed before (baseline: nightly.4 code, the same gate with `--soft`)
- **Offline: silent.** `Turn.loop` (turn.ex:625, `{:error, _}` → `{:snooze, 30}`) and `Requests.complete`
  (requests.ex:337) snoozed a request whose model was down without a word, for up to 2 min (Turn) / 10 min (v1.24
  path). The reply did come later (later_reply passed), but nothing told the asker.
- **@Risi in a group's Official answered "I've replied in your Risi chat."** "what did we decide" takes the memory
  route (`ScheduleIntent.classify` → `MemoryIntent`, turn.ex `forced_memory`); `memory_search` is a personal tool
  and its result meta is always `personal: true` (memory_tools.ex:419), so §25.1 sent the answer to the asker's
  Risi chat and the other members saw only the pointer line.
- **Client (android):** no empty-chat card; the bubble said "Working…" (RisiTools.kt `RisiStepLabels.of`); no
  "@" picker; typed "@Risi …" and 1:1 "Risi, …" were plain messages (`sendFromComposer`, RisiComposerUi.kt:56,
  by design of decision 066); no Private hint.
- Passing already: Risi chat reply in 6.8 s, quiet without @Risi, nothing reaches Risi from Private, logcat clean.

### Fixes
- `RisiMe.Agent.Offline`: "Risi is offline, I'll reply when I'm back." once per request (an `answer`, `call_ref`
  null, notify the asker), when a model call fails or has been pending 10 s (the clock runs only while the
  model is asked, never during a client tool). The request then snoozes 15 s at a time for up to 30 min
  (`Turn.bounds` `wait_s` 1800, `retry_s` 15) and answers by itself.
- The memory route in an Official chat searches that chat only (`conversation_id`, its promises only, meta
  `personal: false`) and is answered there; across chats it stays personal.
- Gate timings (fake-llm, 3 s model delay on the first question): Risi chat reply 6.7 s with "Risi is typing…"
  on screen; offline notice 11.7 s after send (the model proxy down: 1 call + 3 backoff retries, then the notice);
  the later reply within the 15-s retry after the model came back.

### TODO
- **(d) Voice to Risi: done (PART 4d, below).**

## PART 4d: a voice note in the Risi chat gets a reply (12 Oct; contract §37.8, no wire change)

**State:** green; server-only (fast lane), no APK needed (the app already sends Risi-chat notes with a
`request_id`, 663c43f9). Server gate 1337 tests, 0 failures. `scripts/risi-chat-test` step 4b (debug app
09f4e4b5, fake-llm, speech service 127.0.0.1:8200): `RCTEST OK`, every check passed; after the 6-s hold,
"Risi is typing…" in 2.2 s, the transcript under the note, the model's reply in 3.5 s.

- **Already there (§37.8):** transcribe (model=auto, §36.11 script check), the `voice_transcript` card, the text
  buffered as a `risi_request` `ask` (`via: voice`, `lang`), so the §25 turn answers in the note's language;
  the model down → the request job's offline notice (same as typed).
- **What was missing:** no "Risi is typing" while the note was transcribed; and silence in the Risi chat when
  `RISI_VOICE_TRANSCRIBE` was off, the speech health check failed, the note was over 5 min, or the task crashed.
- **Fixes (`Agent.Voice`):** `risi_progress` `working` as soon as the note arrives; the switch off → "I can't
  listen to voice notes right now. Please type it."; over 5 min → a "too long" answer; an unhealthy-looking speech
  service is still tried (down → the §37.8 `failed` card + "couldn't hear" answer); an exception or a missing
  working copy → "couldn't hear". Each fixed answer ends the bubble (`done`). Audio handling unchanged (§37.6).
- **Gate:** `scripts/risi-chat-test` now needs the speech service, `WHISPER_API_KEY` in `.env` and gst-launch
  (`--no-voice` skips 4b; `RC_SPEECH_URL`, `RC_CLIP_PORT` default 8169).
- **Backlog:** spoken replies from Risi (BACKLOG.md).
- `--live` (risi-l1 through the same proxy) is implemented but not run in this session.

## Decision 088 (2026-10-13, P0 hotfix)
- **Risi's device commits** the re-add of a member's new phone, in Official only.
- **The Health agent** (`RisiMe.Agents.Health`, flag `RISI_AGENT_HEALTH`, default `canary`):
  - sweeps every 5 min;
  - makes safe Official repairs;
  - posts `ops_alert` `conversation_stuck` to the `RISI_DEV_USERS` Risi chats;
  - writes the daily scoreboard row `health` (accuracy = reliability).
- **Device gate:** `scripts/official-start-test`.

## 2026-10-15 13:20Z — device test fixes (calls + chat), srv7 → srv9

Live: v0.3.0-nightly.10-srv7 (11:47Z), -srv8 (12:32Z), -srv9 (13:01Z). Redroid test phone, test account:
- Chat pass on srv9: 10/10 PASS + skills + reminder card (PDT OK, run 20261015T131121Z). "What did I promise?" with no
  commitments lists "Reminders I'm keeping for you:"; "What can you do?" in the Risi chat reads `capabilities` first.
- Calls on srv9 (13:07Z): the fragments merge ("Do you have anything? On the 18th." → Sunday 2–3 pm; "Give me the entire
  week and tell me the important things" → today→Sunday with phone events + the reminder); promises: "You have no
  promises or reminders for today. Tomorrow at 10 am you have a reminder to call Shakya."
- Session: an utterance still at STT is transcribed with the next one; a fragment after a restart merges (root utt); an
  "and …" fragment joins a calendar question; the caller starting to speak before Risi is audible holds Risi's pieces.
- Spoken: phone calendar unread → said first, days are "no promises or reminders", never "nothing scheduled".
- Harness: pilot-call-test waits for Risi's answer (turn log + 2.5 s quiet, 45 s cap), foregrounds the app, trims Kokoro's
  padding (the "1 s" pause was 1.9 s; a comma was 0.94 s).
- Committed, NOT live (deploy budget): the phrasing guard against commentary ("You have access and should add the notes").
- For root (media worker, infra/voice/risi_voice_worker.py): caller speech that starts as Risi starts speaking is lost
  (no barge, utterance not emitted) — a barge-in run needs 300 ms of strictly consecutive voiced windows above the echo
  level, and the utterance keeps only `run + PREROLL_WINS` windows of `pre`.
