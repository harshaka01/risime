# Proposal: §24 Two tabs per chat (Private | Official) and Risi stage 1 (v1.24)

**Status:** approved in principle by Harsha (2026-10-08 overnight brief: "proceed and record the
decision"; product model his). Root folds it into PROTOCOL.md as §24 (v1.24). It replaces
`2026-10-08-risi-agent.md`. Every **[D]** is a root decision (rule 8), recorded in
docs/decisions/065-two-tabs.md and 066-risi-stage1.md; Harsha may veto any of them.

Facts checked first:
- A DM is the implicit id `dm:<a>_<b>` (§2.4). It is upgraded to MLS by §10, and there is no server
  conversations table for DMs.
- §10.2 lets a DM's MLS group hold only the two users' current devices.
- `policy.rs` already takes an `agents` list. `group_members.kind` exists.
- Android has no conversations entity: chats are derived from `messages` and `groups`.
- An old app's backup importer would import an unknown chat as a plain conversation, hence §24.10.

## 24.0 Principles
- **A chat is a set of people with two conversations:** Private and Official. Each is its own MLS
  group. They share one `chat_id` and always have the same human members.
- **Private is absolutely private.**
  - No agent leaf, ever. The server, the MLS core and the client UI each enforce it.
  - It is never transcribed, and never an input to any model or to the learning log.
- **Official always contains Risi while Official is on.**
  - Risi is a visible member, `kind: "agent"`, never an admin.
  - Risi only ever sees Official.
- **Old apps never see Official** [D]. They see exactly what they see today. Rationale: a label an
  old app doesn't render can't be relied on.
- **Rule 9:** migration only adds data. Nothing moves to Official automatically.

## 24.1 Ids and objects
- **`chat_id`:** opaque [D]; clients never parse it. It equals the chat's anchor conversation id:
  `dm:<a>_<b>` for a 1:1, or the Private group's `grp:` id. So every existing conversation already
  is its chat's Private tab, with no new id to assign.
- **The Official conversation is always `grp:<uuid>`, even for a 1:1** [D]. Reasons:
  - §10.2 forbids a third member in a `dm:` group;
  - the §12 machinery (ops, committers, `agents` in policy, §12.5 claim) already fits an agent
    member.
- **A 1:1 Official** is a `grp:` with `chat_kind: "dm"`:
  - the two users plus Risi, both users `admin`;
  - `group_meta.name: null`, so clients show the peer's name;
  - member, leave and role calls on it return `422 dm_chat`.
- **`Group` gains** `chat_id`, `tab` (`"private"|"official"`; absent means private), `chat_kind`
  (`"dm"|"group"`) and `agents: [uuid]` (`group_reply_v124.json`).
- **`Chat` object** (`chat_reply.json`):
  `{"chat_id","kind","private","official":{"state":"on"|"off"|"none","conversation_id"|null,"changed_by"|null,"changed_at"|null},"official_ready","missing":[{"user_id","device_id"|null,"reason"}],"can_toggle"}`.
  `none` means Official is on by setting but its conversation isn't created yet.
- **Encrypted `group_meta`** gains `tab`, `chat_id` and `agents`. The core enforces:
  - `tab` and `chat_id` are immutable after epoch 0;
  - Private (or absent) requires `agents == []` and rejects any leaf whose attestation says
    `kind: "agent"`;
  - `dm:` groups reject agent leaves.
- The client takes Private-ness from MLS state, never from server JSON.

## 24.2 Creation [D]
Lazy for 1:1 and migrated chats; prompt for new groups.
- **Endpoint:** `POST /api/v1/chats/{chat_id}/official` (with `X-Device-Id`) → `201 {"group"}`,
  or `200` with the same group if it already exists. A unique index on `(chat_id, tab)` resolves
  races (`chat_official_create.json` and its reply).
  - **Who may call:** any active human member, while Official is on.
  - **Readiness:** every member must be tabs-ready, else `409 not_ready {missing, tab}`.
  - **Risi missing:** no Risi key package, or the agent is down → `503 agent_unavailable`.
- **Epoch 0** is made by the caller's device. It claims through §12.5; the claim of the Risi user is
  allowed only for this Official id. The commit adds every tabs device of every member plus Risi's
  device. `group_meta` carries `tab: "official"` and `agents: [risi]`.
- **When it's created:**
  - **New 1:1:** opens on Private; Official is created on the first tap of the Official tab.
  - **New group:** `POST /groups` (Private), then `…/official` straight away, and the app opens on
    Official. On `409` the app shows "Official needs everyone on the latest app".
  - **Migrated chats:** the Official tab shows an intro card ("Risi listens in Official and helps
    with follow-ups. Use Private for anything Risi shouldn't see.") with **[Start Official]**.

## 24.3 Membership is per chat
- Add, remove, leave and role calls may target either tab's id. They are applied to the chat: one
  §12.4 op per existing tab, with roles mirrored.
- While Official is on, a user being added must be tabs-ready [D].
- Removes and leaves act on both tabs in one transaction.
- Risi is never a valid `user_ids` member (`422 invalid_member`). Its membership changes only
  through §24.4.

## 24.4 Official off and on
- **Endpoint:** `PATCH /api/v1/chats/{chat_id}` `{"official":"off"|"on"}` → `200 {"chat"}`.
- **Who may toggle [D]:**
  - **1:1:** either person.
  - **Group:** admins only (`403 not_admin`).
- **No "remove Risi but keep Official"** (`409 risi_required`).
- **Off, in one critical section:**
  - `state = off`;
  - Risi's membership becomes `pending_remove`, and the server stops delivering to Risi at once;
  - a `remove` op for Risi is created, which any active member's device may commit (an agent target
    only; §12.4 and the core policy are widened accordingly);
  - sends to the Official conversation return `official_off`;
  - a `chat_event` `official_off` is sent.
- **After off,** Risi posts one final line, "Official was turned off by X. I've deleted what I
  learned in this chat.". Within 1 h it deletes that chat's derived facts and cancels its jobs [D].
- **The Official conversation stays, read-only** (rule 9): "Official history (read-only)" in chat
  info.
- **On again:** the same conversation gets an `add` op for Risi. There is one Official conversation
  per chat, ever [D].

## 24.5 Server rules (normative)
- **No agent in Private:** `403 private_tab` for any agent in a `dm:` or Private `grp:`. That covers
  REST adds, §12.5 claims of an agent against a Private id, `group_meta`, and commits whose `added`
  names an agent device.
- **Risi's key packages** may be claimed only for an Official epoch 0 or an Official add op.
- **The Risi tree** subscribes only to Official conversations whose chat has Official on. This is
  enforced by a query on `groups.tab = 'official'`, never by a client claim.
- **Calls:** a `call_id` and its room belong to one conversation.
- **History sharing:** agent devices are never §17 providers or requesters.

## 24.6 Server storage
- **`groups`** gains `chat_id` (not null, backfilled `= id`), `tab` (default `'private'`) and
  `chat_kind`.
- **New table** `chats(chat_id PK, kind, official, official_conversation_id, changed_by,
  changed_at)`. A missing row means on, not created yet.
- **`users.kind`.**
- No Cassandra change for messages.

## 24.7 Capability and old apps
- **`tabs` capability:** v1.24 apps advertise `"tabs"` in `mls.capabilities` (`device_put_tabs.json`).
- **Tabs-ready:** the §12.1 rule with `tabs` in place of `groups`.
- **Delivery filter:** events of Official conversations and `chat_event` go only to sockets with
  `tabs`. `GET /groups[/id]` omits or `404`s Official groups for non-tabs devices. Push wake-ups for
  Official go only to tabs devices.
- **Result:** an old app never sees an Official group.

## 24.8 Events, REST, errors
- **`chat_event`** (stored): `{"chat_id","action":"official_created"|"official_off"|"official_on","actor","official_conversation_id","server_ts"}`.
- **`group_event`** `created`/`added` carry `chat_id`/`tab` and `members[].kind: "agent"`.
- **`GET /api/v1/chats`** and **`GET /api/v1/chats/{chat_id}`**.
- **Errors:** `403 private_tab`, `403 not_admin`, `409 not_ready`, `409 risi_required`,
  `422 dm_chat`, `422 invalid_member`, `503 agent_unavailable`, `429 rate_limited`, and the send
  error `official_off`.

## 24.9 Client rules (Android)
- **Header tabs:** "🔒 Private" | "● Official", each with its own unread badge.
  - The default tab is the last used one. A new 1:1 opens on Private; a new group opens on Official.
  - Official uses a subtly different accent, a banner, and "Risi is listening" in the composer.
  - A per-chat Official off switch is in chat info.
- **Chat list:** one row per `chat_id`, showing the newest message of either tab with a tab icon.
  Unread is the sum of both tabs.
- **Per tab:** search, media, calls and call records. Chat info shows both tabs' media in separate
  sections.
- **Notifications:** titled "Kamal · Official" or "Kamal · 🔒 Private". The push payload stays
  content-free (§8.2). Risi messages notify only users in `risi.notify`; otherwise they are silent.
- **Calls:**
  - **Private 1:1:** §16/§19 on the `dm:`.
  - **Official:** §20 LiveKit on the `grp:`.
  - **Transcription:** never in Private, and not in stage 1.
- **Room (additive):** `chat_tabs(conversation_id PK, chat_id, tab, chat_kind)`, filled for every
  existing conversation as Private with `chat_id = conversation_id`; and
  `chat_prefs(chat_id PK, last_tab, official_state)`.

## 24.10 History sharing and backups
- **§17** is unchanged, per conversation.
- **Backup lines** gain `chat_id`, `tab` and `chat_kind`. A bundle containing any Official
  conversation is written as **schema 2** [D], so older apps answer "Update RisiMe to restore this
  backup". Risi data is never in a backup.

## 24.11 Risi stage 1 envelopes (MLS application messages in Official)
**Identity**
- Risi is a user with `kind: "agent"`, a fixed UUID and device `risi-1`. Its attestation gains
  `"kind": "agent"`.
- §12.0 is amended: agent keys are never readable by the delivery-service store. They are sealed with
  `RISI_MLS_KEK` in `risi_mls_kv` inside the `RisiMe.Agent` tree (decision 065).

**Risi → chat**
`type: "text"` with a plain `body` plus `risi: {"v":1,"kind",…,"call_ref","notify":[uuid]}`:

| kind | fields |
|---|---|
| `commitment` | `commitment_id, state:"proposed", text, owner, counterpart[], due\|null, due_text, source_message_ids, confidence` |
| `commitment_update` | `commitment_id, state: confirmed\|edited\|declined\|done\|cancelled, by, text, due` |
| `reminder` | `commitment_id, due, notify:[owner]` |
| `escalation` | `commitment_id, overdue_by, notify:[counterparts]` |
| `digest` | `date, items:[{commitment_id, text, owner, due, state}]` |
| `answer` | `request_id, answer, refs, confidence` |
| `summary` | `request_id, summary, decisions, action_items, open_questions, partial` |
| `report` | `request_id, title, period, sections` |
| `offer` (stage 2, defined now) | `offer_id, topic, question, buttons:["yes","not_now"]` |
| `error` | `request_id, code: model_unavailable\|rate_limited\|nothing_to_summarise\|out_of_window` |

Each has an example: `envelope_risi_<kind>.json`.

**Members → Risi** (shown as small system lines; ignored if the sender isn't an active human member)
- `risi_request`: `{"v":1,"type":"risi_request","request_id","action":"ask"|"summarise"|"report","text"|null,"scope":{"since"}}`.
  "@Risi" in the composer is a chip; free text is never parsed for intent [D].
- `risi_action`: `{"v":1,"type":"risi_action","target","action":"confirm"|"decline"|"edit"|"done"|"offer_yes"|"offer_not_now","edit"|null}`.
  The owner or a counterpart may act [D]. An unanswered card expires after 48 h, and nothing is
  tracked without ✓.

**Feedback** [D] (private REST, not a visible reaction)
- `POST /api/v1/risi/feedback {"call_ref","rating":"up"|"down","reason"|null}` → `204`. Only members
  of the conversation holding `call_ref` may send it.

**What Risi knows about me**
- `GET /api/v1/risi/facts` lists facts.
- `DELETE /api/v1/risi/facts/{id}` is a hard delete, embedding included.
- `DELETE /api/v1/risi/facts` deletes everything.
- `GET /api/v1/risi/commitments?state=open` backs the "My promises" screen.

**Timing** [D]
- **Reminder:** at due − 1 h in the owner's timezone (`PATCH /me {"tz"}`); 09:00 local when only a
  date is given.
- **Escalation:** 24 h after due, at most 2, 24 h apart, in the same Official chat.
- **Digest:** 09:00 local, only when there are open items, at most once per chat per day.

**Offers** (binding now)
- Only as an `offer` with [Yes] / [Not now].
- At most 1 per chat per day.
- 2 "Not now"s mute that topic in that chat for 90 days [D].
- Nothing happens without Yes. No product or partner is named before a Yes. No advertising, ever.

## 24.12 Data: learn and delete; the learning log [D]
- **Raw buffer:** Official plaintext is held only in `risi_buffer` (Cassandra, sealed with
  `RISI_DATA_KEY`, TWCS, **TTL 24 h**), for windowed summaries and answers.
  - A row is deleted as soon as extraction ran and it falls outside the window.
  - A §15 delete removes the buffer row and any fact derived only from it.
  - Summaries cover at most 24 h, else `out_of_window`.
- **Derived facts** go to Postgres: `risi_commitments`, `risi_facts` (subject_user_id, chat_id,
  source_message_ids), and `risi_fact_embeddings` (pgvector over the fact text only).
- **Learning log** (Cassandra `risi_llm_calls_by_day` / `_by_chat`, TTL 90 d): call_ref, task, model
  alias, real model, provider, latency, tokens, cost, confidence, output (derived JSON) and feedback.
  **The input is stored as source_message_ids plus a SHA-256 of the prompt, never the raw text.**
- **Cascade:** `risi-l1` on loopback first. A commercial fallback runs only when confidence is below
  the threshold **and** `RISI_FALLBACK=on`, which defaults to **off** until a zero-retention
  agreement exists. When on, the intro card says "Hard cases may use a commercial AI service".

## 24.13 Rate limits
| Limit | Value |
|---|---|
| `risi_request` | 10 per user per hour; 1 per chat per minute; 20 per chat per day |
| Commitment cards | 10 per chat per day |
| Offers | 1 per chat per day |
| Global Risi queue | 16 |
| Official toggles | 6 per chat per day |

Requests over a limit get the `rate_limited` envelope, or `429` over REST.

## 24.14 Gate additions
- **Upgrade gate:** counts per `conversation_id` (both tabs) and per `chat_id`, before and after the
  update and after re-sign-in. After migration, every old conversation is Private with
  `chat_id = conversation_id`.
- **Server:**
  - `403 private_tab` on every §24.5 path;
  - the delivery and GET filters;
  - membership fan-out;
  - toggle rights, and immediate stop to Risi;
  - `official_off`;
  - every example.
- **Crypto:** policy cases for an agent in Private, a tab change, an agent as admin, and a member
  removing an agent.
- **Canary** (interop with `RISI=1` and `scripts/fake-llm`): `CANARY-<uuid>` is sent in Private
  (1:1 and group), in Official before Risi joined, and after Official was turned off. It must appear
  in none of these:
  - fake-llm inputs;
  - decrypted `risi_*` tables;
  - the learning log;
  - server logs;
  - FCM payloads.

  An egress check confirms agent HTTP goes to loopback only.
- **Android:** tab mapping, per-tab unread, the chat-list merge, notification labels, the `notify`
  filter, schema-2 backups, and parsing every example.

## 24.15 Rollout
Additive. The server ships with `RISI=off` (`official_ready: false`, reason `agent_unavailable`).
Apps show tabs only when `/auth/config` reports `"tabs":"on"` (`auth_config_v124.json`).

## Work breakdown
Ordered chunks of about 2–3 h.

| Role | Chunks |
|---|---|
| Root | R1 §24 into PROTOCOL.md, plus examples and policy cases; R2 upgrade-test per tab and per chat, fake-llm, canary interop |
| Crypto | C1 meta and tab rules, agent leaf, attestation kind; C2 Rustler NIF with a journal store; C3 sealing and NIF tests |
| Server | S1 migration and Risi seed; S2 tabs capability and filters; S3 `POST …/official` and `private_tab`; S4 membership fan-out, toggle, `chat_event`; S5 Agent tree (NIF client, join, decrypt into the buffer); S6 LLM router, learning log, commitment extraction and card; S7 `risi_action`, Oban reminders, escalation, digest, `risi_request`; S8 facts, commitments, feedback, delete paths, rate limits |
| Android | A1 Room migration and test; A2 tabs capability and core update, Official create/toggle, `chat_event`; A3 header tabs, unread, chat-list merge, default tab; A4 Official styling, banner, hint, intro card, toggle, read-only history; A5 notification labels, per-tab calls, search and media, schema-2 backups; A6 Risi cards, @Risi chip, feedback, "What Risi knows about me", "My promises" |

## Decisions recorded [D], for Harsha to veto
1. `chat_id` = the Private anchor id.
2. Official is always a `grp:`.
3. Official is created lazily, except for new groups.
4. Toggle: either person in a 1:1, admins in a group. No removing Risi without turning Official off.
   One Official conversation per chat, ever.
5. Old apps never see Official.
6. New members must be tabs-ready while Official is on.
7. Raw text is kept 24 h, sealed; the log stores ids and hashes.
8. The commercial fallback is off until a zero-retention agreement exists.
9. Backups containing Official are schema 2.
10. Confirm and decline are visible MLS messages; feedback is private REST.
11. A topic is muted for 90 days after 2 "Not now"s.
