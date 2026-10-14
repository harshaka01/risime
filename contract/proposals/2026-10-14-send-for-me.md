# Proposal: "Send a message for me", scheduled messages only with a time, the skill-state report (2026-10-14)

**Status:** APPLIED as **v1.49 §42** (root, 2026-10-14). Was: proposal for root/Harsha, to be applied as **v1.49 §42** (§41 is taken by the Call Risi proposal) (amends §25.4, §26.2, §26.3, §26.6,
§26.8, §28.4, §37.8). Nothing in PROTOCOL.md changes until root applies it. Server and Android implement it
**behind flags**: server `RISI_SEND_FOR_ME` = `off | canary | on` (default `canary` = `RISI_DEV_USERS`,
Harsha first); the app advertises the new capability only while its developer flag `send_for_me` is on
(default on for `risi_dev` accounts, off for everyone else).

## Why (P1, Harsha's phone, 07:53 SLT, v0.3.0-nightly.7)
In his Risi chat Harsha sent the voice note "Risi, can you send a message to Upali asking to update his
version…". Risi answered **"Scheduled messages permission is off on this phone"** with "Open Risi skills",
while Settings → Risi skills showed Scheduled messages **On** and the permission **granted**.

Two problems:
1. **There is no "send now" action.** The only tool that sends for the user is `schedule_message` (§26.6), a
   scheduled-message skill with a time. "Send Upali a message" has no time, so it went to the wrong tool.
2. **The "off" was false (root cause, server).** A Risi-chat voice note reaches the turn **without the
   sending device**: `Agent.Conversation` hands a `voice` envelope to `Agent.Voice.received/3` with only
   `message_id` and `sender_id`, and the transcript is buffered as the `risi_request` with
   `sender_device: nil`. The turn's `ctx.device_id` is therefore `nil`, so `Skills.phone_ok?/2`
   (`Devices.risi_skills_device?(asker, nil)` → false) makes **every phone skill `no_permission`**, and the
   `skill_needed` card says "<Skill> permission is off on this phone" whatever the phone reported. (Typed
   requests carry the device and were not affected.) Two smaller contributors: the server trusted a
   `denied` report of any age, and §26.2's phone reports only on start and on a change seen on the
   Risi skills screen, never when the app comes back to the foreground; and `SCHEDULE_EXACT_ALARM`, which
   §26.1 marks **optional** for scheduled messages (WorkManager fallback), still blocked the skill when denied.

## 42.0 Principles
- **One card, one tap** (§28.0). "Send Upali a message asking him to update" ends in **one** card:
  **To: Upali · the text · [Send] [Edit]**. [Send] is the only confirmation. No skills page, no second card.
- **The phone sends, as the user, end-to-end encrypted.** The server never sends a message on the user's
  behalf and never stores the text (it passes once in the tool call's args, as §26.6).
- **Private unless the user says Official.** A friend's name → that 1:1's Private conversation. Official only
  when the request says so ("in Official", "on the official tab").
- **Not a consequential action.** Sending the user's own chat message is not money, pay, HR, deletion or legal
  (principle 4, decision 085). The consequential-action guard registers `send_message` as
  **non-consequential**; the user's own [Send] tap (or their per-contact standing choice) is the approval.
- **Scheduled messages only with a time.** `schedule_message` is chosen only when the request gives a time or a
  repeat ("tomorrow 9am", "every day"). Without one it is `send_message`.
- **Never a false "off".** The server says a skill or permission is off only when the user turned the skill off
  (server state) or the phone **freshly** reported the permission denied. Unknown or stale: it proceeds with
  the card, and the phone's own answer at run time decides.

## 42.1 Capability and switch
- **Device capability `risi_send`** (in `mls.capabilities`, kept only with `risi_tools`;
  `device_put_risi_send.json`). A device advertises it once it handles everything in this section.
- **Server switch `RISI_SEND_FOR_ME`** = `off | canary | on` (`RisiMe.Agents.Flags`, runtime; default
  `canary`: on for `RISI_DEV_USERS`). Not in `/auth/config` (the capability is what the phone needs; the
  server decides per asker).
- **Phase 1 (server only, works with nightly.7 unchanged):** for an asker with the switch on but **no**
  `risi_send` device, `send_message` ends in the §25.4 **`draft`** card the app already renders: the text, in
  the user's language, `target_conversation_id` = the recipient's conversation; [Use] opens that chat with the
  composer filled (it never sends). The answer says: "Here's the message for Upali. Tap Use to open your chat
  with it ready, then send." Honest: nothing is claimed sent.
- **Phase 2 (APK with `risi_send`):** the card, the phone send and the "✓ Sent" reply below.

## 42.2 The tool `send_message` (client tool, write, no skill)
Model-facing args (server schema, informative): `{"to": str (a friend's name, or "this chat"),
"text": str (1–4096 graphemes; the message in the user's language and style), "tab": "private" | "official"}`.
`tab` defaults to `private`.

**Offered** (`authorize/3`) when: the switch is on for the asker, the asker has an active Risi chat, the asking
device advertises `risi_tools`, and (Phase 2) the device advertises `risi_send`. **No skill** (§26.1 is
unchanged; it never needs Settings → Risi skills).

**Resolving the recipient** (server, deterministic, before any card):
- a whole display name or first name of the asker's **friends** (§9) or chat co-members, normalised as §34.5;
- **several matches** ("Upali" names Upali Perera and Upali Silva): no card; the §34.5 `clarify` question
  "Which Upali?" with one chip per person (up to 3, `keep: false`, no [Keep]); a chip resolves it without a
  model call, exactly as §34.5;
- **a close but not exact match** ("Upaly"): the §34.5 name question as for every named write;
- **a contact with no 1:1 chat** (a co-member who isn't a friend, or no MLS 1:1 yet): no card; "You don't have a
  1:1 chat with Upali yet. Open Contacts → Upali to start one, then ask me again.";
- **no match**: "I couldn't find Upali in your contacts." (the model may ask who it is);
- `tab: "official"`: the chat's Official conversation when it is `on`; otherwise "Official is off in your chat
  with Upali." and no card;
- "this chat" from an Official conversation: that conversation; from the Risi chat: ask who it is for.
- Never a Risi chat.

**The card** (`confirm`, §25.4, in the asker's Risi chat; `envelope_risi_confirm_send_message.json`):
```
risi: {"v": 1, "kind": "confirm", "request_id", "write_id", "tool": "send_message",
       "summary": "Send to Upali Perera (Private): Hi Upali, please update RisiMe to the latest version.",
       "when": null, "text": "<the message>",
       "args": {"conversation_id": "dm:…", "text": "<the message>"},
       "send": {"to": {"name": "Upali Perera", "user_id": uuid | null}, "tab": "private" | "official",
                "language": "en" | "si" | "ta"},
       "for": [asker], "buttons": ["send", "edit", "cancel"], "expires_at", "turn_ref", "call_ref",
       "made_by", "notify": [asker]}
body: "Send to Upali Perera (Private): <text>? Update RisiMe to answer."
```
- `args` is the tool call's exact args without `write_id` (§26.5 rule). `send` is display data.
- The app shows **To: Upali Perera · 🔒 Private**, the text, **[Send]** (filled) and **[Edit]**; Cancel is a small
  text button. An old app shows `summary` and no buttons (an unknown `tool`, §26.9).
- At most 1 `send_message` card per turn; the §25.1 bound of 2 writes still holds.

**[Edit]** = the §28.4 `confirm_write` with **`edit: {"text": str}`** (1–4096 graphemes, trimmed, not
empty). The server re-seals the args with the edited text and sends the tool call with it, no model call. Any
other key, or an edit on another tool, runs nothing (unchanged §28.4 rule). The phone's rule (§42.3) accepts the
card's args merged with **its own user's** `edit`.

**[Send]** = `confirm_write` (no `edit`). The server sends the `risi_tool_call` to the confirming device
(if it advertises `risi_send`, else the turn's device), as §25.4.

**Tool call** (`event_risi_tool_call_send_message.json`):
`"tool": "send_message", "args": {"write_id": uuid, "conversation_id": str, "text": str}`.

**Result** (`risi_tool_result_send_message.json`): `{"status": "ok", "result": {"state": "sent" | "queued"}}`
(exactly this key). `sent`: the server acknowledged the message (it has a `message_id`) within 10 s; `queued`: it
is in the phone's outbox. Errors (`status: "error"`): `{"code": "not_member"}` (the phone doesn't hold that
conversation or the user isn't an active member), `{"code": "bad_args"}`. `declined`: the §42.3 rule failed.

**The reply** (new `risi.kind` **`message_sent`**, in the asker's Risi chat, silent for others;
`envelope_risi_message_sent.json`):
```
risi: {"v": 1, "kind": "message_sent", "request_id", "write_id", "conversation_id": "dm:…",
       "to": {"name": "Upali Perera", "user_id": uuid | null}, "tab": "private" | "official",
       "state": "sent" | "queued", "turn_ref", "call_ref": null, "made_by" (rule), "notify": [asker]}
body: "✓ Sent to Upali Perera" | "Queued for Upali Perera: it goes as soon as your phone is online."
```
- **Never the text** (it is in the user's own chat already). Honesty: "Sent" only for the phone's `sent`.
- The app shows the body as a tappable line; a tap opens `conversation_id` (that tab). Other apps show `body`.
- `not_member`: "I couldn't send it: you're not in a chat with Upali on this phone." Timeout (15 s): the §25.4
  `tool_timeout` error with [Retry] ("I couldn't reach your phone to send it.").
- Activity: no §26.4 entry (no skill). No `risi_items` row (nothing pending). Nothing in the learning log
  beyond the hashes-only `risi_turn_steps` row.

## 42.3 The phone's rule for `send_message` (android, normative)
The phone runs a `send_message` call only when **all** hold (the §25.3/§26.5 card rule, rule (a)):
- this phone holds the agent-leaf `confirm` with the call's `write_id` and `tool: "send_message"`, with its
  own user in `for`, not expired;
- the first answer to it from **its own user's leaf** is `confirm_write`;
- the call's args minus `write_id` equal the card's `args`, or the card's `args` merged with the `edit` of that
  `confirm_write` (only `text`);
- the `write_id` is unused on this phone; the conversation is one this phone holds, the user an active member,
  never a Risi chat.
Then it sends the text through the normal send path (encrypted at that moment, a fresh `client_msg_id`
persisted first), exactly as if typed, and answers `sent`/`queued` as above. The recipient sees a normal message.

**"Send without asking next time" (per contact, stored on the phone only).** Under [Send] the card offers the
tick **"Send to Upali without asking next time"** (off by default). When it is on for that contact (user id and
tab) and this phone is the device that sent the request (its history holds its own leaf's `risi_request` or
voice note with the card's `request_id`, at most 10 minutes earlier), the phone sends the `confirm_write` itself
when the card arrives, and the card shows "Sent without asking (your choice for Upali) · Ask me next time".
The wire is unchanged: it is the user's own `confirm_write`, sent on the user's standing choice. The setting
never leaves the phone and is listed in Settings → Risi skills → "Send a message for me", where each contact
can be switched back to "Ask me".

## 42.4 Scheduled messages only with a time (amends §26.6)
- `schedule_message` is described to the model as "only when the request gives a time or a repeat". The server
  refuses a `schedule_message` step whose `at` resolves to less than 2 minutes ahead or is not a time
  ("now", "") with `failed` reason `no_time: use send_message`; when `send_message` is offered the turn then
  continues with it.
- The card follows the same one-tap pattern for a `risi_send` device: `buttons: ["schedule", "edit",
  "cancel"]`, the `send` display object, [Edit] = `confirm_write` `edit: {"text"}`. Devices without `risi_send`
  keep `["add", "cancel"]`.
- The success line (`skill_done` body, §26.5) reads **"✓ Scheduled for Upali, Thu 09:00"** (or "…, every day at
  09:00"). The activity summary is unchanged (recipient and time, never the text).

## 42.5 The skill and permission state (amends §26.2, §26.8)
**Phone (normative):** the app sends the §26.2 `PATCH` with `client_permission` for every skill with an Android
permission **on start, whenever the app comes to the foreground, and on every change** it sees. A report whose
values didn't change is sent at most every 30 minutes (so `reported_at` stays fresh); a change is sent at once.

**Server (normative):**
- **The device of a voice request** is the device that sent the note (Agent.Voice carries it to the buffered
  request). When a request has no device (a note retried from the voice queue), the turn uses the asker's
  `risi_tools` device seen most recently.
- **`no_permission`** (the `skill_needed` reason, and the `authorize/3` gate) only when the device's report is
  `denied` or `unsupported` **and** was reported within the last **6 hours**. `unknown`, `not_asked`, a stale
  report, or no device: the tool is offered; the phone answers at run time (`no_permission`), and Risi says so
  then, with the [Open Risi skills] chip.
- **Scheduled messages never need `SCHEDULE_EXACT_ALARM`** (§26.1 "optional"): a `denied` report for it is never
  `no_permission`; the card says "may be a few minutes late".
- The skill's own **state** (`off` / `ask` / `allowed`) stays the server's record of the user's choice.

## 42.6 Speech hints (server ↔ speech service, loopback; no client wire change)
- `POST /v1/audio/transcriptions` gains an optional multipart field **`hotwords`** (comma-separated, at most 60
  entries and 1000 characters). The service passes them to faster-whisper's `hotwords`, appends them to the
  Qwen3 ASR context, and to Whisper's initial prompt after the Sinhala-script prompt (never replacing it). The
  CTC model ignores them. An old service ignores the unknown field.
- **Always:** `Risi`, `රිසි`, `ரிசி`. A Risi-chat request transcript that starts with a common mis-hearing of the
  name ("Rishi,", "Risky,", "Reesi,", "රිෂි,") is normalised to "Risi," (only at the start, only in the user's own
  Risi chat).
- **The user's own contacts:** for a note in the **sender's own Risi chat** only, the display names the server
  already holds for that user's friends and chat co-members (at most 50, first names and full names) are added,
  **per request**. They are never logged, never stored, never in the
  learning log, never used for another user's note. The phone-side recogniser (the Voice agent's L1) is the
  preferred place for contact names; this is the server fallback.

## 42.7 Agent design (decision 085)
- **Owner:** the Risi Brain (`RisiMe.Agent.Turn`) chooses the action; `RisiMe.Agent.SendForMe` is its tool
  (recipient resolution, the card, the reply). Sending itself is infrastructure (the phone's MLS send path).
- **Signals:** card shown → [Send] / [Edit] / Cancel / superseded; edit distance of edits (count only); auto-send
  per contact (phone-local, never reported). **Eval:** the gate's four utterances (en/si × voice/text) plus
  "message Upali tomorrow 9am" (must schedule), "message Upali" with two Upalis (must ask), "send Upali" with no
  1:1 chat (must say so). **Index:** share of send requests that reach [Send] in one card without an edit.
- **Flags:** shadow is not meaningful (the card is the action); `canary` (Harsha) → `on`.
- **Consequential-action guard:** `send_message` and `schedule_message` are registered **non-consequential**.

## 42.8 Gate
`scripts/send-for-me-test` (redroid, two phones): "send a message to <name> …" by **text and by voice**, in
**English and Sinhala** → one card → one [Send] → the message arrives (decrypted) on the second phone →
"✓ Sent to <name>" shown in the Risi chat. Also: [Edit] then [Send] delivers the edited text; a request with a
time gives the scheduled card. Server tests: the tool, the card, the edit, the reply, the recipient cases, the
skill-state rules (voice device, staleness, optional exact alarm), the hotwords never in logs. Android (JVM):
decoding the new examples, the §42.3 rule (card, edit, own leaf, unused write_id), auto-send only on the asking
device, the report on resume.

## 42.9 Examples (to add when applied)
`device_put_risi_send.json`:
```json
{"mls":{"signature_key":"…","capabilities":["groups","tabs","risi_tools","risi_skills","risi_send"]}}
```
`envelope_risi_confirm_send_message.json`:
```json
{"v":1,"type":"text","body":"Send to Upali Perera (Private): Hi Upali, please update RisiMe to the latest version.? Update RisiMe to answer.","risi":{"v":1,"kind":"confirm","request_id":"6d1f2e3a-4b5c-4d6e-9f7a-8b9c0d1e2f3a","write_id":"2b3c4d5e-6f70-4812-93a4-b5c6d7e8f901","tool":"send_message","summary":"Send to Upali Perera (Private): Hi Upali, please update RisiMe to the latest version.","when":null,"text":"Hi Upali, please update RisiMe to the latest version.","args":{"conversation_id":"dm:0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e_7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3","text":"Hi Upali, please update RisiMe to the latest version."},"send":{"to":{"name":"Upali Perera","user_id":"0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"},"tab":"private","language":"en"},"for":["7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"],"buttons":["send","edit","cancel"],"expires_at":"2026-10-15T02:23:30.456Z","turn_ref":"8a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d","call_ref":"7e2a3f4b-5c6d-4e7f-8a9b-0c1d2e3f4a5b","notify":["7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"]}}
```
`envelope_risi_action_confirm_write_send_edit.json`:
```json
{"v":1,"type":"risi_action","action":"confirm_write","target":"2b3c4d5e-6f70-4812-93a4-b5c6d7e8f901","edit":{"text":"Hi Upali, please update RisiMe today."}}
```
`event_risi_tool_call_send_message.json`:
```json
{"event_id":"e5b2c3d4-a5b0-11f1-8000-0242ac120002","kind":"risi_tool_call","data":{"tool_call_id":"4c5d6e7f-8a9b-4c0d-9e1f-2a3b4c5d6e7f","turn_id":"8a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d","request_id":"6d1f2e3a-4b5c-4d6e-9f7a-8b9c0d1e2f3a","conversation_id":"grp:2f3a4b5c-6d7e-4f8a-9b0c-1d2e3f4a5b6c","device_id":"c0a80101-0000-4000-8000-000000000001","tool":"send_message","args":{"write_id":"2b3c4d5e-6f70-4812-93a4-b5c6d7e8f901","conversation_id":"dm:0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e_7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3","text":"Hi Upali, please update RisiMe to the latest version."},"undo_entry_id":null,"expires_at":"2026-10-14T02:25:30.000Z","to_devices":["c0a80101-0000-4000-8000-000000000001"],"server_ts":"2026-10-14T02:23:30.000Z"}}
```
`risi_tool_result_send_message.json`:
```json
{"status":"ok","result":{"state":"sent"}}
```
`envelope_risi_message_sent.json`:
```json
{"v":1,"type":"text","body":"✓ Sent to Upali Perera","risi":{"v":1,"kind":"message_sent","request_id":"6d1f2e3a-4b5c-4d6e-9f7a-8b9c0d1e2f3a","write_id":"2b3c4d5e-6f70-4812-93a4-b5c6d7e8f901","conversation_id":"dm:0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e_7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3","to":{"name":"Upali Perera","user_id":"0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"},"tab":"private","state":"sent","turn_ref":"8a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d","call_ref":null,"made_by":{"model":null,"provider":"rule","at":"2026-10-14T02:23:33.000Z","also":[]},"notify":["7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"]}}
```

## 42.10 What ships where
- **Server only (fast lane, works with nightly.7):** the voice-device fix and the §42.5 server rules (the P1's
  false "off"), `schedule_message` only with a time, `send_message` Phase 1 (the `draft` card), the hotwords
  field and the "Risi," normalisation, the flag.
- **Needs the APK:** `risi_send` (the [Send] [Edit] card, the phone send, `message_sent`, per-contact auto-send),
  the report on resume, the plain-words Risi skills page.
- **Needs the speech service rebuilt** (root, `infra/whisper`): `hotwords` taking effect.
