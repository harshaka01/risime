# Proposal: §25 Risi with tools (v1.25): tool loop, client tools, confirm cards, memory, Risi chat

**Status:** requested by Harsha (2026-10-09, "Risi's intelligence" v2). Root folds it into PROTOCOL as
§25. Details are root decisions (rule 8), recorded in docs/decisions/068-risi-tools.md.

## 25.0 Principles
- Risi becomes an agent with a **bounded tool loop** on the server. Every tool call is
  permission-checked, the steps show in the bubble, and the sources show with the answer.
- **Where Risi is asked:** @Risi in an Official tab, or the user's personal **Risi chat**. Risi is
  never in Private.
- **Only the asker's own request starts a turn.** Other people's messages are data, never commands.
  Risi acts only for the asker: reminders and calendar entries are the asker's own, and group
  reminders go only to people who tap "Me too".
- **Every write** (`set_reminder`, `calendar_add`) shows a **confirm card** [Add] [Cancel] to the asker
  first. A write without a confirmed `write_id` is impossible.
- **Private never reaches the server.** A Private search runs on the phone and only the phone shows its
  results.

## 25.1 Loop (server, `RisiMe.Agent.Turn`)
- **Next-action protocol:** each step is one strict-JSON choice from the tools `authorize/3` allows
  for this asker, device and conversation (`{tool, args}` or `{tool: "final", answer, sources,
  next_steps}`). A commercial provider maps the same registry to native tools.
- **Bounds:**
  - at most 6 tool steps and 8 model calls;
  - 20 s per model call, 15 s per client tool, 60 s per turn;
  - 24k prompt tokens;
  - at most 2 write proposals.
  When a bound is hit, the turn ends with a `final` answer that says what was done and the next step,
  never an error.
- **System prompt:** Risi's identity and the capability list built from the registry. "Say what you
  did and the next step." Never "the transcript does not contain" (a server post-check retries once).
- **Audience:** an answer built only from the conversation's own content goes to that conversation.
  Anything built from personal sources, drafts and personal confirms goes to the asker's Risi chat, and
  the group gets one line.
- **Learning log:** one row per step in `risi_turn_steps` (tool, `args_sha256`, authorize result,
  status, latency, size). No text (§24.12).

## 25.2 Risi chat
- One per user: a `grp:` with `tab: "official"`, `chat_kind: "risi"`, `chat_id` = its own id,
  `private: null`. Members are the user (admin) and Risi.
- `POST /api/v1/risi/chat` → 200/201 `{chat}`. `GET /chats` lists it with `kind: "risi"`.
- It has no Official toggle. Leaving it deletes Risi's data for that chat.
- It is delivered only to `risi_tools` devices.

## 25.3 Client tools (calendar)
- **Event `risi_tool_call`** `{tool_call_id, turn_id, device_id, tool, args, expires_at}`: sent only to
  the asking device's sockets, TTL 2 min, with a content-free FCM wake if it has no socket.
- **Result:** `POST /api/v1/risi/tool_calls/{id}/result`
  `{status: "ok"|"no_permission"|"declined"|"error", result}` (TLS, ≤16 KB).
  - 404 if the caller isn't that user and device; 409 if expired, answered, or a write without a
    confirmed `write_id`.
  - The result is kept only in the waiting turn's memory, never stored or logged.
- **`calendar_check {from, to}`** → `{blocks: [{start, end, busy, all_day}]}`: no titles, attendees or
  places.
- **`calendar_add {write_id, title, start, end}`** → `{event_id}`. The phone only accepts it after the
  asker tapped [Add].
- **Timeout (15 s):** the step is `failed`, and the answer offers [Retry].
- Over TLS, not MLS, because Risi shares the server process (§24.11). It moves to MLS in the Risi chat
  when the agent becomes its own process.

## 25.4 Bubble on the wire
- **Signal `risi_progress`** (ephemeral, not stored, ignored by old apps), sent to the asker's
  `risi_tools` sockets:
  `{request_id, conversation_id, state: queued|working|step|waiting_confirm|done, position, step: {n, tool, status}}`.
  The app localises the step label from the tool code ("Checking your calendar…").
- **`answer` v2** (optional fields): `steps: [{tool, status}]`, `sources` (message refs, calendar
  blocks, links, notes), `next_steps`, `turn_ref`.
- **New `risi.kind` values:**
  - `confirm {write_id, tool, summary, when, text, for: [asker], buttons: ["add", "cancel"]}`;
  - `reminder_set {reminder_id, when, text, participants, me_too}`;
  - `draft {text, language, target_conversation_id}`: [Use] fills the composer and never sends.
  - `reminder` gains `reminder_id`.
- **New `risi_action` values:** `confirm_write`, `cancel_write`, `me_too`, `not_me`. Only the asker
  may confirm or cancel.

## 25.5 Tools
| Tool | Where | What it can do | What it cannot do |
|---|---|---|---|
| `capabilities` | server | List the tools available to this asker | — |
| `set_reminder(when, text)` | server | Resolve relative times from the request's `server_ts` and the asker's tz; confirm card; fire a thread message plus push; "Me too" in groups | Remind anyone who didn't tap; recurring reminders; guess an ambiguous date (it asks) |
| `calendar_check` / `calendar_add` | phone | Read free/busy, or add an event to the primary writable calendar (fallback: a local "RisiMe" calendar) after [Add] | See other people's calendars; work while the phone is unreachable |
| `search_chats(text, range)` | server: Official / phone: Private | Search the asker's Official conversations (24 h buffer plus derived facts); Private only as a local card on the phone | Search Official older than 24 h; send anything from Private |
| `summarise(thread, range)` / `draft_reply(tone, language)` | server | Official only, ≤24 h, in Sinhala, Tamil or English | Private (waits for the on-device model); send on the user's behalf |
| `remember(text)` / `forget(ref)` | server | Per-user notes, sealed with `RISI_MEMORY_KEY`, shown in "What Risi knows about me", hard delete | Use notes in group answers |
| `ask_risiwork(question)` | server → risicloud.ai | The asker's verbatim question, after a one-time consent card, with a Keycloak token-exchange token scoped to RisiWork | Work without RisiWork access ("ask your admin"); send chat text |

## 25.6 Queue
- **Per user:** a 20 per minute sliding window and a FIFO queue, with one running turn per user.
  `risi_progress queued` shows the position.
- **Global:** dispatch is round-robin across users, with 16 model calls in flight.
- **Errors** only for more than 50 pending or more than 10 min waiting (§24.13).

## 25.7 Model routing
- `route(task, lang)`: the tool loop, Sinhala/Tamil and drafts go to the **commercial** model while
  `RISI_COMMERCIAL=on` and a provider is configured, otherwise to risi-l1.
- `RISI_ROUTE_<TASK>=local` moves a task back once risi-l1 passes the gate questions.
- The commercial host has its own egress allowlist, and the egress canary allows exactly that host.

## 25.8 Capability and old apps
- **Capability:** `risi_tools`, and `/auth/config` `"risi_tools": "on"|"off"`.
- **Apps without `risi_tools`:** never offered client tools or the Risi chat. They render `body` for
  unknown kinds.
- **Facts:** `kind: "note"` maps to `"preference"` for them.

## 25.9 Gate
- **Ten questions** (scripted with fake-llm, then the real model):
  1. "What can you do"
  2. "Remind us about Tuesday 2pm" (group, confirm, me-too, firing)
  3. "Am I free Tuesday 2pm"
  4. "Add dentist Friday 10am"
  5. "Swan is my dentist"
  6. "Who is my dentist"
  7. "Forget that"
  8. A budget-decision search
  9. "Summarise today in Sinhala"
  10. "Draft a reply in Tamil", plus RisiWork granted and denied

  Also three questions at once: all queued, none refused.
- **Three safety checks:**
  1. Another member's message, or an injected line, can't trigger a tool, and a forged confirm is
     ignored.
  2. A Private canary, including a local search for it, reaches no model, table, REST body, log or
     push.
  3. A write without confirm is impossible: the server answers 409 and the phone refuses.
- **Permission denials:** calendar permission refused, and a thread the asker isn't a member of.

## Examples
- `envelope_risi_answer_v2.json`
- `envelope_risi_confirm.json`
- `envelope_risi_reminder_set.json`
- `envelope_risi_draft.json`
- `envelope_risi_action_me_too.json`
- `signal_risi_progress.json`
- `event_risi_tool_call_calendar_check.json`
- `event_risi_tool_call_calendar_add.json`
- `risi_tool_result.json`
- `chat_reply_risi.json`
- `risi_facts_reply_v125.json`
- `auth_config_v125.json`
- `error_tool_call_expired.json`

## Open (Harsha)
1. **The commercial provider and a zero-retention agreement.** Until then everything runs on
   risi-l1.
2. **"Every call logged for learning" vs decision 066 ("never the raw text"):**
   - **(A, default)** hashes only, so the logs can evaluate risi-l1 but not train it;
   - **(B)** sealed prompts with a 30-day TTL, Official only.
3. **The Keycloak token-exchange settings for the RisiCloud lead** (in overnight.md "Needs Harsha").
4. **Older Official search** beyond the 24 h buffer would need phone-assisted search, which is a later
   contract change.
