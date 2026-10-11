# Risi Brain status (risi role)

Role created 2026-10-11 (CLAUDE.md rule 12, decision 077). Ships through `scripts/risi-fastlane` (root deploys).

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
