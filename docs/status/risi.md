# Risi Brain status (risi role)

Role created 2026-10-11 (CLAUDE.md rule 12, decision 077). Ships through `scripts/risi-fastlane` (root deploys).

## Step 2: long-term memory and retrieval (contract v1.36 §35.1–§35.3)

**State:** green on `main` (server suite 1185 tests, 0 failures; live risi-l1 memory checks 4/4). Root deploys it through the fast lane; not live until root reports the `-srvN` tag.

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

## Step 3: always-on ingest worker (calendar proposals, quiet-period summaries + per-person Notes)
Not started.

## Step 4: cancellation propagation, nudges, 07:00 brief
Not started.

## Step 5: backfill
Not started; it goes through root's APK path.
