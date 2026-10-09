# Proposal 2026-10-09: Risi Calendar and Risi Notes (v1.29: §29 Risi Calendar, §30 Risi Notes, §31 Google Calendar as optional sync)

> **Folded (2026-10-09, root):** §29 Risi Calendar is in `contract/v1/PROTOCOL.md` **v1.29 §29**, as
> shipped (server 5ea7902..5497692, android 2ba351b, 94172af; examples written from the server's real
> output). PROTOCOL.md wins where they differ (e.g. `version` counts only owner changes, the
> suggestion "keep" is an `event_update` `status`, a Risi-chat card's source is the Risi chat, the
> draft slot `with`, `risi_events` absent while off). **§30 Risi Notes is not folded yet** (it stays
> here until built); §31 later.

Requested by Harsha 2026-10-09 ("RisiMe gets its OWN calendar and notes. Google Calendar becomes an
optional sync, not a dependency"). Root decided the design (decision 073). It replaces the plan of
`2026-10-09-google-calendar.md` as §29: that proposal becomes **§31 "Google Calendar, optional sync
(later)"**, and only its honesty rule (its §29.3) is taken over now, as §29.7 below, extended to the
source `risi_calendar`. PROTOCOL.md §29/§30/§31 after the build match this file.

It extends §22, §24.11, §25.1–§25.5, §26.7, §27.2–§27.6, §27.13 and §28.2–§28.8 and overrides them
where it differs (§29.13 and §30.10 list every amendment). **Additive only:** two device
capabilities (`risi_events`, `risi_notes`), two `/auth/config` switches, new REST under
`/api/v1/risi/calendar…` and `/api/v1/risi/notes…`, one stored event kind
(`risi_calendar_changed`), new `risi.kind`s and `risi_action` values, one server tool
(`risi_calendar_check`) and one server-side confirm tool (`risi_calendar_add`), two error codes. An
app without the new capabilities sees v1.28 exactly. **No device calendar permission is needed for
anything in §29.**

---

## 29. Risi Calendar (v1.29) — folded into PROTOCOL.md v1.29 §29

### 29.0 Principles
- **RisiMe's own calendar.** Events live on the RisiMe server, per user, sealed with
  `RISI_DATA_KEY` like commitments and facts (§24.12, §28.2). Risi writes there directly; no
  Android or Google permission is involved. Google Calendar is optional and later (§31).
- **Honest about where it lives.** Risi Calendar is *sealed at rest on the server*, **not**
  end-to-end encrypted: the server (Risi) can read it. The Calendar tab says so once in its info
  sheet: "Your Risi Calendar is stored on the RisiMe server, encrypted at rest. Risi can read it to
  answer you. It is not end-to-end encrypted." (decision 048 spirit: never claim E2EE we don't have.)
- **Two steps, one tap each.** A write the user asks for is one native action card
  ([Add] [Edit] [Cancel]; Add creates at once). An appointment agreed in an Official chat becomes a
  **proposed** event plus one **invite card** per participant ([Accept] [Decline]
  [Suggest another time]). Nothing reaches anyone's calendar as *accepted* without their own tap.
- **Never from Private.** Events are made only from the user's own Risi-chat requests, the
  Calendar tab, and Official items/agreements (§24.5: Risi receives nothing from Private).
- **The user is always in charge:** edit and delete are always possible (§29.4); a participant can
  always decline or remove an event from their calendar.
- **Native cards, never text chips** for events (§29.9). The mini day timeline is computed on the
  viewing phone from its own synced Risi Calendar and never sent anywhere.
- KEEP (§28): conversation memory, the action card, the loop guard, defaults (phone tz, 1 h), and
  the honesty rule.

### 29.1 Switch, capability, old apps
- `GET /auth/config` gains **`"risi_events": "on" | "off"`** (server env `RISI_EVENTS`, default
  `off`; absent = `off`). Off: every `/risi/calendar…` call answers `503 agent_unavailable`, Risi
  uses the v1.28 flows (device `calendar_add` cards, §28.5 offers). (The key `risi_calendar: 1|2`
  stays reserved for §31.)
- Device capability **`"risi_events"`** in `mls.capabilities`, only together with `risi_tools`,
  `risi_skills` and `risi_ledger` (`device_put_risi_events.json`). A user is a **calendar user**
  when the switch is on and any of their devices advertises it; Risi then uses §29 for that user
  (action card target, offers → invites, reminders, digest).
- Old apps: new kinds show `body` (always a readable line ending "Update RisiMe to answer." where a
  tap is needed). A participant who is not a calendar user is still a participant; their invite is
  held (§29.6) until they become one or the event starts.

### 29.2 The event model
```
Event = {
  "event_id": uuid, "version": int (≥ 1, +1 per change of any field below),
  "owner": uuid,                       who may change title/time/participants/notes, or cancel
  "title": str,                        1–200 grapheme clusters
  "notes": str | null,                 ≤ 2000 grapheme clusters
  "start": ts, "end": ts,              start < end; ≤ 14 days long
  "all_day": bool,                     all-day: start = 00:00 and end = 00:00 of the next day, in tz
  "tz": IANA zone,                     the creator's phone zone (PATCH /me tz, else RISI_DEFAULT_TZ)
  "participants": [{"user_id": uuid,
                    "status": "proposed" | "accepted" | "declined",
                    "responded_at": ts | null}],          1–20, owner included, distinct
  "my_status": "proposed" | "accepted" | "declined",       the caller's own
  "my_reminder_min": int | null,       the caller's own reminder (0–10080 min before start; null = none)
  "source": {"conversation_id": "grp:…" | null,           an Official conversation or the Risi chat
             "message_ids": [timeuuid],                   ≤ 20
             "item_id": uuid | null,                      §27/§28 item (commitment) it came from
             "note_id": uuid | null},                     §30 note it came from
  "created_by": "risi" | "user",
  "state": "active" | "cancelled",
  "created_at": ts, "updated_at": ts}
```
- **Owner:** the creator for `created_by: "user"`. For a Risi-made event: the owner of the item it
  came from (§27/§28), else the member whose message proposed the agreed time.
- **Statuses:** a user-created event starts `accepted` for the owner and `proposed` for the others;
  a Risi-made event starts `proposed` for **everyone**, owner included (each accepts their own).
- **What the caller's calendar holds:** every event where the caller is a participant and has not
  removed it (§29.4). `declined` events are hidden in the Calendar tab unless "Show declined" is on
  (a phone setting); `proposed` ones are shown dashed with "Proposed".
- **Risi-made titles are neutral:** they must read right for every participant (no "with <name>";
  participants are shown separately on every card). The extraction is instructed so; the server
  strips a leading "<Name>:" and a trailing "with <participant display name>" from item text
  (`"Shenika: interview with Harsha"` → `"Interview"`).
- **Invitable:** a participant must share at least one active chat (a DM friend, §9, or any group)
  with the owner; otherwise `422 not_invitable` (REST) or the person is dropped from a card's
  `with` with a line "Couldn't invite <name>".

### 29.3 REST: `/api/v1/risi/calendar` (auth; `X-Device-Id` of a `risi_events` device, else `403 invalid_device`)
| Method, path | Body | Reply |
|---|---|---|
| `GET /risi/calendar/events?from=ts&to=ts` | — (`to − from` ≤ 92 days) | `200 {"events": [Event], "cursor": str}` sorted by `start` (`risi_calendar_events_reply.json`) |
| `GET /risi/calendar/changes?since=<cursor>&limit=1–500` | — | `200 {"changes": [Change], "cursor": str, "has_more": bool}` (`risi_calendar_changes_reply.json`) |
| `GET /risi/calendar/events/{id}` | — | `200 {"event": Event}` |
| `POST /risi/calendar/events` | `{"client_event_id": uuid, "title", "notes"?, "start", "end", "all_day", "tz", "with": [uuid], "reminder_min"?: int \| null, "source"?: {"conversation_id", "message_ids"}}` | `201 {"event": Event}`; a repeated `client_event_id` (24 h) returns the same event with `200` (`risi_calendar_event_create.json`, `…_reply.json`) |
| `PATCH /risi/calendar/events/{id}` | `{"version": int, "title"?, "notes"?, "start"?, "end"?, "all_day"?, "tz"?, "add"?: [uuid], "remove"?: [uuid], "reminder_min"?: int \| null}` | `200 {"event": Event}` (`risi_calendar_event_patch.json`) |
| `DELETE /risi/calendar/events/{id}` | — | `204` (§29.4) |
| `POST /risi/calendar/events/{id}/respond` | `{"response": "accept" \| "decline" \| "suggest", "version": int, "suggest": {"start", "end", "all_day"} \| null, "reminder_min"?: int \| null}` | `200 {"event": Event}` (`risi_calendar_respond_accept.json`, `risi_calendar_respond_suggest.json`) |
| `POST /risi/calendar/suggestions/{suggestion_id}/resolve` | `{"action": "use" \| "keep"}` (owner only) | `200 {"event": Event}` (`risi_calendar_suggestion_resolve.json`) |
| `GET` / `PATCH /risi/calendar/settings` | `{"default_reminder_min": int \| null (default 30), "default_duration_min": 5–1440 (default 60), "digest_events": bool (default true)}` | `200 {"settings": …}` (`risi_calendar_settings.json`) |
| `DELETE /risi/calendar` | — | `204`: removes the caller from every event, cancels the events they own (§29.4), deletes settings; "Delete my Risi Calendar" in Settings → Risi |

- `Change = {"event": Event}` (created or changed; the caller's view) or `{"event_id": uuid,
  "removed": true}` (deleted, cancelled-and-purged, or the caller was removed). Changes are
  coalesced per event (only the latest view), ordered by the cursor.
- **Cursor:** opaque string over a per-user change sequence. `410 cursor_expired`
  (`error_cursor_expired.json`) when older than the 30-day change log: the phone then re-lists
  (`GET /events` for its window) and continues from that reply's `cursor`.
- **`PATCH` rules:** owner only for `title`, `notes`, `start`, `end`, `all_day`, `tz`, `add`,
  `remove` (else `403 not_owner`); anyone for their own `reminder_min`. A stale `version` →
  `409 version_conflict` with the current `{"event"}` (`error_version_conflict.json`). A change of
  `start`/`end`/`all_day` by the owner sets every **other** participant back to `proposed` and
  sends them a new invite (`reason: "time_changed"`); a title/notes change keeps statuses and sends
  `event_update` only.
- **`respond` rules:** participants only (`404 not_found` otherwise). `accept`/`decline` are
  idempotent and may be changed later. `suggest` keeps the responder's status `proposed`, stores a
  suggestion (≤ 3 open per event per user) and posts a `calendar_suggestion` card to the owner;
  the owner's `use` moves the event (version +1, others back to `proposed`, new invites,
  the suggester `accepted`), `keep` tells the suggester "<Owner> kept the original time".
- **Validation:** `422 bad_request` for a malformed body, `start ≥ end`, a title out of range, an
  unknown zone, > 20 participants, a `source.conversation_id` that is not an Official conversation
  or the caller's Risi chat where the caller is an active member (a Private or `dm:` id is always
  refused). `404 not_found` for an event the caller is not a participant of.
- Errors otherwise as §24.11: `503 agent_unavailable` when the rows can't be opened (key missing or
  wrong) and the caller has rows; `429 rate_limited` (§29.12).

### 29.4 Edit and delete (always possible)
- **Owner, `DELETE`:** the event is `cancelled` for everyone: each other participant gets an
  `event_update` (`state: "cancelled"`) on their cards and a `risi_calendar_changed`; reminders are
  cancelled. The row is purged 7 days later (titles gone); until then participants see "Cancelled".
- **Participant, `DELETE`:** = decline + remove from *their* calendar (a `removed` change for their
  devices only); the owner sees them as `declined`.
- **Owner leaves RisiMe / account deleted:** their events are cancelled as above.
- **Official turned off** (§24.4) for the source chat: Risi-made events of that chat that **no one
  accepted** are cancelled; accepted events stay (they are the users' own); only ids remain in
  `source`.
- Leaving the Risi chat (§25.2) does **not** delete the calendar (it is not chat data).

### 29.5 Storage (server, normative)
Queries first: (1) a user's events in a time range; (2) a user's changes since a cursor; (3) one
event with its participants; (4) due reminders.
```
risi_events(event_id uuid PK, owner uuid NOT NULL, title_sealed bytea NOT NULL,
            notes_sealed bytea NULL, start_at timestamptz NOT NULL, end_at timestamptz NOT NULL,
            all_day bool NOT NULL, tz text NOT NULL, created_by text NOT NULL,
            state text NOT NULL DEFAULT 'active', version int NOT NULL DEFAULT 1,
            source_conversation_id text NULL, source_message_ids uuid[] NOT NULL DEFAULT '{}',
            source_item_id uuid NULL, source_note_id uuid NULL,
            created_at, updated_at, cancelled_at NULL)
  CHECK (start_at < end_at AND end_at - start_at <= interval '14 days')
  CHECK (created_by IN ('risi','user')) CHECK (state IN ('active','cancelled'))
  CHECK (octet_length(title_sealed) BETWEEN 29 AND 4096)          -- nonce 12 + tag 16 + ≥ 1
  CHECK (notes_sealed IS NULL OR octet_length(notes_sealed) BETWEEN 29 AND 32768)
risi_event_participants(event_id, user_id, status, responded_at, reminder_min int NULL,
            removed bool NOT NULL DEFAULT false, PRIMARY KEY (event_id, user_id))
  CHECK (status IN ('proposed','accepted','declined'))
  CHECK (reminder_min IS NULL OR reminder_min BETWEEN 0 AND 10080)
  INDEX (user_id, removed) ; risi_events INDEX (start_at)
risi_event_suggestions(suggestion_id PK, event_id, user_id, start_at, end_at, all_day, state, created_at)
risi_calendar_log(user_id, seq bigint, event_id, at, PRIMARY KEY (user_id, seq))   -- ids only; 30 days
risi_calendar_settings(user_id PK, default_reminder_min, default_duration_min, digest_events)
risi_calendar_invites_pending(event_id, user_id, reason, PRIMARY KEY (event_id, user_id))  -- until start
```
- **There is no plaintext `title` or `notes` column at all**; the migration test asserts that no
  column of `risi_events` is named `title`/`notes`/`text` and that the CHECKs exist. Sealing is
  `RisiMe.Agent.Seal.seal_text/4` with AAD `risi_events:<event_id>:title` / `…:notes`.
- Titles never appear in `inbox_events` (the `risi_calendar_changed` event is content-free), job
  args, logs, the learning log, `risi_turn_steps`, push payloads or metrics. The only places a title
  appears outside the sealed row are the MLS-encrypted Risi cards (§29.8–§29.10) and the REST
  replies over TLS.
- `risi_item_offers` (§28.5) gains the kind **`risi_event`**: one Risi-made event per item, ever.

### 29.6 Multi-device sync
- After any change to an event, every affected participant gets the stored inbox event
  **`risi_calendar_changed`** (§2.3; cursor-ordered, delivered offline; only to that user's
  `risi_events` devices; `event_risi_calendar_changed.json`):
  `{"event_id": "timeuuid", "kind": "risi_calendar_changed", "data": {"cursor": str, "server_ts": ts}}`.
  At most one per user per 5 s (coalesced; the latest cursor wins). **No push wake** for it; invites
  and reminders arrive as Risi-chat messages, which wake as usual.
- The phone then calls `GET /changes?since=<its cursor>` until `has_more` is false, and applies
  them to its local cache (Room table `risi_calendar_cache`, in the encrypted DB). The phone also
  syncs on app start and when the Calendar tab opens.
- The local cache is a **cache**: never in a backup bundle (§22.5 "never" list gains it), never
  shared by history sharing (§17); a new or restored device lists from the server.

### 29.7 Honesty rule (server; from the Google proposal's §29.3, extended)
Sources, in this order: **`risi_calendar`** (server, always for a calendar user), `phone_provider`
(the device `calendar_check` of §25.3, when the Calendar skill is on), `google_api` (§31, later;
reported `not_connected` until then).
- **Server tool `risi_calendar_check`** `{from, to}` (≤ 14 days, clamped): the server reads the
  asker's own events (accepted and proposed; declined and removed excluded) and gives the model
  `{"source": "risi_calendar", "read_ok": bool, "blocks": [{"start", "end", "all_day", "status":
  "accepted" | "proposed", "ref": "e<n>"}]}`. Titles go to the model **only when the turn runs on a
  RisiMe model** (`provider: "risime"`), never to a commercial one. When the asker's phone Calendar
  skill is on, the server runs the phone `calendar_check` for the same window in the same step, so
  every answer covers every connected source.
- *A read* = `risi_calendar` opened successfully (an empty range is a real read: "Nothing in your
  Risi Calendar"), or a phone source with `read_ok: true` and ≥ 1 calendar.
- **No read** (e.g. `RISI_DATA_KEY` missing, the phone failed and Risi Calendar unavailable): the
  model's text is discarded; the server posts the `answer` "I couldn't check your calendar, so I
  can't tell whether you're free." plus one line per source (`made_by.model: null`).
- **≥ 1 read:** the model's answer stands and the server **always** appends
  **"Checked: Risi Calendar · Phone calendar (Work). Not checked: Google Calendar (not
  connected)."** The words "free", "clear", "available" may only refer to the checked calendars.
  A proposed event is reported as "tentative".
- `answer.sources` gains `{"type": "calendar_source", "source": "risi_calendar" | "phone_provider"
  | "google_api", "names": [str], "read_ok": bool, "reason": str | null}`
  (`envelope_risi_answer_calendar_sources_risi.json`).

### 29.8 The action card: `confirm` with `tool: "risi_calendar_add"` (amends §28.2–§28.4)
For a calendar user, every user-asked event write (the §28.2 draft `kind: "event"`, or the model's
`risi_calendar_add` step) ends in **one** §25.4 `confirm` card in the asker's Risi chat
(`envelope_risi_confirm_risi_calendar_add.json`):
```
risi: {"v": 1, "kind": "confirm", "request_id", "write_id", "tool": "risi_calendar_add",
       "skill_id": null, "summary": "Add to your Risi Calendar: Interview · Mon 12 Oct, 2–3 PM · with Shenika",
       "text": "Interview", "when": {"start", "end", "all_day"},
       "args": {"title", "start", "end", "all_day", "tz", "with": [uuid], "reminder_min": int | null,
                "notes": null, "source": {"conversation_id", "message_ids", "item_id"} | null},
       "for": [asker], "buttons": ["add", "edit", "cancel"], "calendar": null,
       "origin": null | "offer", "item_id": uuid | null, "expires_at", "turn_ref", "made_by"}
```
- Defaults never asked (§28.2): the phone's tz, `default_duration_min` (60), `default_reminder_min`
  (30), `with` from the item's counterparts or the people named in the request (only invitables).
- **[Add]** = `confirm_write`: the server creates the event **at once** (no client tool, no model
  call, no device permission) and posts an **`event_card`** (`mode: "added"`) in the Risi chat;
  `with` people get invites (§29.10). **[Edit]** = `confirm_write` with `edit` (§28.4 shape plus
  optional `with` and `reminder_min`), checked as §28.4, then created. **[Cancel]** =
  `cancel_write`. Loop guard, one question per turn, next-step rules: unchanged (§28.3).
- The device `calendar_add` stays for an explicit "add to my phone calendar" while the Calendar
  skill is on; the default target is Risi Calendar.

### 29.9 The event card: `event_card` (native; in the Risi chat and in Official)
```
risi: {"v": 1, "kind": "event_card", "mode": "added" | "official",
       "event_id", "version", "title", "start", "end", "all_day", "tz",
       "owner", "participants": [{"user_id", "status"}],
       "source_conversation_id": "grp:…" | null, "item_id": uuid | null,
       "buttons": ["open", "edit", "delete"] | ["accept", "decline", "suggest"],
       "made_by", "notify": [...]}
```
- `mode: "added"` (`envelope_risi_event_card_added.json`): after [Add], in the asker's Risi chat,
  `buttons: ["open", "edit", "delete"]`, `notify: []`.
- `mode: "official"` (`envelope_risi_event_card_official.json`): when Risi detects an appointment
  agreed in an Official conversation (the §26.7 conditions: a concrete time agreed by ≥ 2 human
  members, confidence ≥ 0.8, or "@Risi add this meeting for us"), **one** card in that Official
  conversation, `buttons: ["accept", "decline", "suggest"]` shown **only to participants whose
  status is `proposed`**; others see the participants line and [Open in Calendar]. `notify: []`
  (invites notify). It **replaces** the §26.7 `calendar_offer` for chats where every participant
  is a calendar user (otherwise §26.7 continues for the others). At most 3 per chat per day, one per
  (title, start).
- **The phone renders** (android, normative): title; date and time range in the viewer's zone;
  "With: Shenika ✓, Kamal ?" (✓ accepted, ? proposed, ✗ declined; names from the phone's own data);
  "From: <chat>"; and the **mini day timeline** (07:00–21:00, widened to the event): the viewer's
  own Risi-Calendar events of that day from the local cache in grey (proposed hatched), this event
  in accent, overlaps red with "Clashes with 1 event". No titles of other events on the timeline.
  Computed locally; nothing from a render is sent anywhere. Never shown as text chips.
- Card buttons send `risi_action`s (§29.11) or call REST; both have the same effect. Every later
  change arrives as `event_update` and updates the card in place.

### 29.10 Invites, updates, suggestions, reminders (Risi-chat kinds)
**`calendar_invite`** (in each participant's own Risi chat, `notify: [participant]`;
`envelope_risi_calendar_invite.json`):
```
{"kind": "calendar_invite", "event_id", "version", "title", "start", "end", "all_day", "tz",
 "owner", "participants": [{"user_id", "status"}], "from": uuid | null (null = Risi),
 "reason": "new" | "time_changed" | "added", "source_conversation_id", "item_id": uuid | null,
 "note_id": uuid | null, "buttons": ["accept", "decline", "suggest"], "expires_at": ts (= start),
 "made_by", "notify": [participant]}
```
`body`: "Invitation: Interview · Mon 12 Oct, 2–3 PM · with Harsha. Accept, decline or suggest
another time in RisiMe." [Suggest another time] opens a date/time picker (prefilled +1 h) and sends
`event_suggest`. After `expires_at` the card greys out.

**`event_update`** (`envelope_risi_event_update.json`) — to every participant's Risi chat after any
accepted change: `{"kind": "event_update", "event_id", "version", "by": uuid | null, "change":
"status" | "time" | "title" | "participants" | "cancelled", "title", "start", "end", "all_day",
"participants", "state", "made_by" (model null), "notify": []}`. Phones apply it to every card of
that `event_id` (invite, event card, note) and show **no bubble**; a phone without such a card shows
a small line ("Shenika accepted 'Interview'").

**`calendar_suggestion`** (to the owner; `envelope_risi_calendar_suggestion.json`):
`{"kind": "calendar_suggestion", "suggestion_id", "event_id", "by": uuid, "start", "end",
"all_day", "buttons": ["use", "keep"], "notify": [owner]}`; body "Shenika suggests Tue 13 Oct,
3–4 PM for 'Interview'."

**`calendar_reminder`** (`envelope_risi_calendar_reminder.json`): at `start − reminder_min` for
each participant whose status is **`accepted`**, in their Risi chat: `{"kind": "calendar_reminder",
"event_id", "title", "start", "end", "all_day", "reminder_min", "buttons": ["open"], "notify":
[user]}`; body "In 30 min: Interview (2:00 PM) with Shenika.". The push is the content-free wake
(§8.2). Implemented on the §26 Reminders path (Oban job per (event, user), args ids and
`schedule_v` only, rescheduled on every time/reminder change, cancelled on decline/cancel/remove).
Default 30 min; per event (`reminder_min` on accept, PATCH, or the card's Edit) and in settings.
All-day events: 09:00 local the day before when `reminder_min` ≥ 60, else 09:00 on the day.
These reminders are part of the user's own calendar and are **not** gated by the Reminders skill
state (open question Q1); they appear in the Reminders activity log (`action:
"calendar_reminder"`, `via: "risi_calendar"`).

### 29.11 `risi_action` values (§24.11 envelope; from a human leaf; in the actor's Risi chat or the event's Official conversation)
| `action` | `target` | `edit` | Who |
|---|---|---|---|
| `event_accept` | `event_id` | `{"reminder_min"?: int \| null}` \| null | a participant |
| `event_decline` | `event_id` | null | a participant |
| `event_suggest` | `event_id` | `{"start", "end", "all_day"}` | a participant other than the owner |
| `suggestion_use` / `suggestion_keep` | `suggestion_id` | null | the owner |

(`envelope_risi_action_event_accept.json`, `envelope_risi_action_event_suggest.json`.) Risi checks
the attested user of the leaf, ignores others, and is idempotent. The card's `version` is not
needed: an action on a superseded invite applies to the current event only if the times still match
the card, else Risi posts the current invite again.

### 29.12 Offers, backfill, digest, rate limits
- **Offers become invites (amends §28.5).** For an item with a concrete future time, when the
  owner (and, once tracked, the counterparts) are calendar users, Risi creates **one proposed
  event** (`created_by: "risi"`, owner = the item owner, participants = owner ∪ counterparts,
  title per §29.2, 1 h or the agreed end, `source.item_id`, claim `risi_item_offers` kind
  `risi_event`) and sends each participant a `calendar_invite` — the owner at extraction, the
  counterparts once the item is tracked (§27.5 rule). The §28.5 "Add to calendar?" `calendar_add`
  card and "Remind me?" card are **not** sent to calendar users (the event carries the reminder).
  Users who are not calendar users keep §28.5.
- **Backfill** (`RisiMe.Release.risi_calendar_backfill/1`, dry-run by default, queued on deploy like
  `risi_offers_backfill`, idempotent via the claim): every live tracked item (or v1.24 commitment)
  with a concrete future `due` gets its proposed event and invites. **Gate case:** the commitment
  "Shenika: interview with Harsha, Mon 12 Oct 2:00 PM" → event "Interview", 14:00–15:00
  Asia/Colombo, participants Shenika (owner) and Harsha, both `proposed`, an invite card in each
  Risi chat; after both accept, the event is accepted in both calendars.
- **Digest (amends §28.8).** When `digest_events` is on, the personal 09:00 digest gains
  `"events": {"today": [EventRef], "tomorrow": [EventRef], "pending": [EventRef]}` (pending =
  invites awaiting the user's answer, events in the next 14 days), `EventRef = {"event_id", "title",
  "start", "end", "all_day", "my_status"}`; the digest is posted when there are items **or** events.
  `items` and `totals` stay exactly the §28.7 query (`envelope_risi_digest_personal_v129.json`).
- **Rate limits (amends §24.13):**

  | Limit | Value |
  |---|---|
  | Calendar REST writes (create, patch, delete, respond, resolve) | 60 per user per minute |
  | Calendar REST reads | 120 per user per minute |
  | Events per user | 5000 not cancelled; a create over it → `422 bad_request` |
  | Risi-made events (official cards + offers) | 3 per Official chat per day; 1 per (title, start) |
  | Suggestions | 3 open per event per user |
  | `risi_calendar_changed` | ≤ 1 per user per 5 s (coalesced) |

### 29.13 Amendments, backups, wording, learning log
- **§22 backups:** Risi Calendar is **on the server**, so it is **not in a local or server
  backup bundle** (it is not chat history); a restored phone lists it again from the server. The
  Risi-chat cards about events are ordinary messages and are backed up as such. Losing
  `RISI_DATA_KEY` makes the calendar unreadable (`503 agent_unavailable`), so ops must keep the key
  backed up with spark2's `.env`, next to the server DB backup taken by `scripts/run-server`.
- **§25.4/§25.5:** `confirm.tool` `risi_calendar_add`; server tool `risi_calendar_check`;
  `answer.sources` `calendar_source`. **§26.7:** replaced by `event_card` official for calendar
  users. **§28.2–§28.5, §28.8:** as above.
- **Learning log:** `risi_turn_steps` records `risi_calendar_check` / `risi_calendar_add` steps with
  hashes only; event titles, notes and blocks never reach the learning log.
- **Sinhala / Tamil:** the app localises every label, button and status from the codes (`accept`,
  `decline`, `suggest`, `proposed`, …), and formats dates with the phone's locale; strings use
  neutral forms (no gendered or honorific variants: "Invitation", "Accepted", "Suggest another
  time"). The server's `body` is English fallback for old apps only. Titles stay in the language
  they were written in.
- **Errors (new):** `403 not_owner`, `409 version_conflict`, `410 cursor_expired`,
  `422 not_invitable`.

---

## 30. Risi Notes (v1.29)

### 30.0 Principles
- A **Note** is the record of an Official discussion: a title, key points, the agreed action items
  (tick-boxes that *are* the §27 ledger items / My promises) and the meetings it produced (proposed
  Risi Calendar events), with a link back to the chat.
- **Per person, in the Risi chat** (as §27.3). The Official conversation gets one short card.
- **One discussion, one note:** a note replaces the §27.3/§27.4 quiet-rule summary for people who
  have the feature (same id, same extraction); never both.
- Never from Private; never from a Risi chat. Sealed at rest; not end-to-end encrypted (stated in the
  Notes list's info line, as §29.0).

### 30.1 Switch and capability
`/auth/config` **`"risi_notes": "on" | "off"`** (`RISI_NOTES`, default `off`; only `on` with
`risi_ledger` on). Capability **`"risi_notes"`**, only with `risi_events` (`device_put_risi_notes.json`).
A **notes user** = switch on and a device advertising it.

### 30.2 Triggers
1. **Quiet:** the §27.2 quiet rule (600 s, ≥ 6 counted messages, ≥ 2 people, spacing 30 min,
   ≤ 8/day; the `RISI_QUIET_*` env overrides of §28.9 apply).
2. **Call end:** a call Risi listened to ends (§27.2 call trigger, §27.7), `source: "call"`; any
   other Official call's end runs the quiet check at once (no 10-min wait) over the typed messages.
3. **`@Risi summarise`:** a `risi_request` `summarise` **without** a period scope from a notes user
   in an Official conversation makes a note now (`source: "request"`) over the uncovered window
   (since the cutoff, ≤ 24 h). It replaces the §24.11 `summary` reply for that request (the Official
   conversation gets `notes_saved`, §30.4). A repeat within 10 min returns the same note
   (`notes_saved` re-posted to the asker only). A period scope (`today`/`7d`/`30d`, §27.13) is
   unchanged.
- **Made when** the extraction returns ≥ 1 item, or ≥ 1 agreed meeting, or ≥ 3 key points;
  otherwise nothing is posted (the cutoff still moves). Every trigger moves the §27.2 cutoff, so the
  quiet rule never re-summarises a window a note covered.
- Extraction is the §27.2 `discussion_summarise` task, extended with `topic` (≤ 80 chars, the
  discussion's own language) and `meetings` (concrete agreed times, → §29.12 proposed events with
  `source.note_id`).

### 30.3 The note model
```
Note = {"note_id": uuid (= the §27 summary_id), "conversation_id": "grp:…", "chat_id",
        "source": "chat" | "call" | "request", "call_id": uuid | null,
        "media": "audio" | "video" | null, "duration_s": int | null,
        "started_at", "ended_at", "with": [uuid] (all participants),
        "topic": str (1–80), "language": "en" | "si" | "ta",
        "key_points": [str] (1–8, ≤ 200 chars each),
        "items": [Item],                    §27.3 Item, live state; ≤ 10
        "events": [{"event_id", "title", "start", "end", "all_day", "my_status"}],
        "created_at", "call_ref", "made_by"}
```
- **Title** is rendered by the phone, per viewer: **"<viewer> × <others> · <topic> · <date>"**
  ("Harsha × Shenika · interview planning · Fri 9 Oct"), names from the phone's data, more than 3
  people as "Harsha × Shenika +2", the date of `ended_at` in the viewer's zone and locale. The
  server's `body` uses display names.
- **Items are the ledger items** (`risi_commitments` with `summary_id = note_id`): ✓ ✗ ✎ on the
  viewer's own `proposed` items until `expires_at` (48 h, §27.3); confirmed/edited items show as
  **tick-boxes** (owner and counterparts may tick, §27.5 `done`); others' proposed items read-only
  ("waiting"). Each shows owner and due.

### 30.4 Delivery
- **`note_card`** in each recipient's own Risi chat (`envelope_risi_note_card.json`):
  `risi: {"v": 1, "kind": "note_card", "for": R, …Note…, "expires_at", "notify": [R]}`; `body` =
  "Notes: Harsha × Shenika · interview planning · Fri 9 Oct", the key points, "Agreed:" with items
  and dues, "Meetings:" with events, and "Open in RisiMe". Tap → the note screen (§30.6). Recipients
  as §27.2; held 24 h for a recipient without an active Risi chat (§27.3 pending copies).
- **`notes_saved`** in the Official conversation (`envelope_risi_notes_saved.json`):
  `{"kind": "notes_saved", "note_id", "source", "with", "summary": str (≤ 280, no items/owners/dues,
  as §27.4), "items_count", "events_count", "made_by", "notify": []}`; body "Notes saved · open in
  your Risi chat". Shows [Open] for participants (opens their note), the line for others. It
  replaces `discussion_card`.
- **Mixed audiences:** a recipient without `risi_notes` gets the §27.3 `discussion_summary` from the
  same extraction (same id); the Official conversation gets only `notes_saved` (its `body` reads
  fine on old apps). Never a `discussion_summary` and a `note_card` to the same person.

### 30.5 Ticking ↔ promises (both ways)
- Ticking an item in a note sends §27.5 `done` (`target` = `item_id`) in the actor's Risi chat;
  un-ticking sends the new **`item_reopen`** (owner or counterpart, `done` → its previous
  `confirmed`/`edited`, within 7 days of done; `envelope_risi_action_item_reopen.json`).
- Marking done (or reopening) in My promises or on an `item_due` card is the same action. Every
  accepted change posts §27.5 `item_update` to every recipient's Risi chat; phones apply it to the
  note card and the note screen. `GET /risi/notes/{id}` always returns live states.
- `GET /api/v1/risi/commitments` items gain `"note_id": uuid | null` (`risi_commitments_reply_v129.json`);
  My promises shows "From note: <title>" and opens it.

### 30.6 Notes REST (auth; `X-Device-Id` of a `risi_notes` device)
| Method, path | Reply |
|---|---|
| `GET /risi/notes?q=<1–100 chars>&before=<note_id>&limit=1–50` | `200 {"notes": [NoteSummary], "has_more"}` newest first (`risi_notes_reply.json`); `NoteSummary = {"note_id", "conversation_id", "with", "topic", "ended_at", "source", "items_count", "open_items_count", "events_count"}` |
| `GET /risi/notes/{id}` | `200 {"note": Note}` (`risi_note_reply.json`) |
| `DELETE /risi/notes/{id}` | `204`: removes it from the **caller's** list only; items, promises and events are unaffected; the row is purged when no recipient keeps it |
| `DELETE /risi/notes` | `204`: all of the caller's notes |

- **Search** (server): case-insensitive substring over topic, key points, item texts and
  participants' display names, over the caller's 500 most recent notes (opened in memory; no
  plaintext index, no embedding). The phone may also search its local note cards.
- **Share into a chat** is phone-only: the user picks a chat; the phone sends an ordinary message
  of its own (the note rendered as text, ending "— shared from Risi Notes") through the normal send
  path. Risi is not involved; in an Official chat it is an ordinary message like any other.
- Entry points: Risi chat ⋮ → Notes; Settings → Risi → Notes.

### 30.7 Storage
```
risi_notes(note_id uuid PK, conversation_id text, chat_id uuid, source text, call_id uuid NULL,
           started_at, ended_at, participants uuid[], body_sealed bytea NOT NULL,   -- topic, language, key_points
           made_by jsonb, call_ref uuid NULL, created_at)
  CHECK (source IN ('chat','call','request')) CHECK (octet_length(body_sealed) >= 29)
risi_note_recipients(note_id, user_id, deleted bool NOT NULL DEFAULT false, PRIMARY KEY (note_id, user_id))
```
- Sealed with `RISI_DATA_KEY`, AAD `risi_notes:<note_id>:body`; no plaintext text column (test as
  §29.5). Items stay in `risi_commitments` (`summary_id = note_id`), events in `risi_events`
  (`source_note_id`). `risi_discussions` rows are still written for §27 compatibility.
- **Kept** until every recipient deleted it, or **365 days**; deleted with the chat's Risi data
  when Official is turned off (§24.4) — the note cards already on phones stay (hard rule 9).
  Never in the learning log, logs, push, metrics. Not in backups (server data, as §29.13); the
  `note_card` messages are backed up as messages.
- **Rate limits:** quiet/call notes as §27.2 spacing; `@Risi summarise` notes 1 per conversation
  per 10 min; Notes REST 60 reads and 30 deletes per user per minute.

### 30.8 Relation to §27.13 summaries
Day/week summaries (§27.13) keep being built and stay internal; a period `summarise` may use notes
of the period as extra stored input. Notes do not change the 24-h raw-buffer rule.

### 30.9 Wording
As §29.13: the app localises "Notes", "Agreed", "Meetings", "Notes saved · open", status words
and dates; key points and topic stay in the discussion's language (`language`); neutral forms only.

### 30.10 Amendments
§24.11 (`summarise` without a period → note for notes users), §27.2 (call-end check; note
thresholds), §27.3/§27.4 (replaced by `note_card`/`notes_saved` for notes users), §27.5
(`item_reopen`), §28.7 (`note_id`), §22.5 (never-list: `risi_calendar_cache`).

---

## 31. Google Calendar, optional sync (later)
The design of `2026-10-09-google-calendar.md` (its §29.0–§29.9) becomes §31, **not a dependency and
not in nightly.47/48**: token on the device, Testing mode, (1) one-way mirror **Risi Calendar →
Google** first (accepted events inserted/patched/deleted with `extendedProperties.private.risime_event_id`),
(2) then busy reads as the `google_api` source of §29.7. Its honesty rule is already §29.7. Its
example names move to `…_v131…` when it is scheduled. Needs Harsha G (OAuth clients) stays open.

---

## Examples (contract/v1/examples/, added with the build)
**Calendar (§29, written with the v1.29 fold):** `auth_config_v129.json`, `device_put_risi_events.json`,
`risi_calendar_events_reply.json`, `risi_calendar_changes_reply.json` (a changed and a `removed`
entry), `risi_calendar_event_create.json`, `risi_calendar_event_create_reply.json`,
`risi_calendar_event_patch.json`, `risi_calendar_respond_accept.json`,
`risi_calendar_respond_suggest.json`, `risi_calendar_suggestion_resolve.json`,
`risi_calendar_settings.json`, `event_risi_calendar_changed.json`,
`envelope_risi_confirm_risi_calendar_add.json`, `envelope_risi_action_confirm_write_edit_risi_calendar.json`,
`envelope_risi_event_card_added.json`, `envelope_risi_event_card_official.json`,
`envelope_risi_calendar_invite.json`, `envelope_risi_event_update.json`,
`envelope_risi_calendar_suggestion.json`, `envelope_risi_calendar_reminder.json`,
`envelope_risi_action_event_accept.json`, `envelope_risi_action_event_suggest.json`,
`envelope_risi_answer_calendar_sources_risi.json`, `envelope_risi_digest_personal_v129.json`,
`error_version_conflict.json`, `error_cursor_expired.json`, `error_not_invitable.json`.
**Notes (§30):** `device_put_risi_notes.json`, `envelope_risi_note_card.json`,
`envelope_risi_notes_saved.json`, `risi_notes_reply.json`, `risi_note_reply.json`,
`envelope_risi_action_item_reopen.json`, `risi_commitments_reply_v129.json`.

## Gate (Harsha's GATE, made checkable)
**Calendar — nightly.47** (`scripts/ui-entry-test --risi-calendar`, emulator + Redroid, fake-llm):
1. **Replay** Harsha's conversation ("add my interview with Shenika on Monday 12 Oct at 2pm…", the
   nightly.43/45 transcript) → the `risi_calendar_add` card **within 2 turns**; [Add] → the event in
   `GET /risi/calendar/events` and in the Calendar tab — with the phone's calendar permission
   **revoked** (proves no device permission).
2. **Shenika backfill** → one proposed event, `calendar_invite` in **both** Risi chats; both
   [Accept] → `accepted` in both calendars; Harsha's second device shows it after
   `risi_calendar_changed` without a restart.
3. Event card in the Official chat with the mini timeline and a red clash (a seeded overlapping
   event); no text chips anywhere.
4. "Am I free Monday 2pm?" → answer with "Checked: Risi Calendar · … Not checked: …"; with
   `RISI_DATA_KEY` unset → "I couldn't check your calendar…", never free/clear/available.
5. Suggest another time → owner's `calendar_suggestion` → Use → other participants re-invited
   (`time_changed`); owner delete → "Cancelled" on every card; participant delete → `declined`.
6. Reminder at start − 30 min → `calendar_reminder` + wake; changing it to 10 min reschedules.
7. Canaries: a `CANARY-<uuid>` event title never appears in plaintext in a Postgres dump,
   Cassandra (`inbox_events`, buffer), server log, learning log, `risi_turn_steps`, fake-llm record
   (commercial route) or push; a canary typed in **Private** creates no event. Migration test: no
   plaintext title/notes column; CHECKs present.
8. Upgrade gate (hard rule 9): per-conversation counts unchanged across the update.
9. Screens in `docs/status/screens/`: `calendar-agenda.png`, `calendar-day.png`,
   `calendar-week.png`, `calendar-month.png`, `risi-calendar-action-card.png`,
   `event-card-official.png`, `calendar-invite-card.png`.

**Notes — nightly.48** (`scripts/ui-entry-test --risi-notes`, `RISI_QUIET_S=60`):
10. A test discussion in a 1:1 Official goes quiet → a `note_card` in **both** Risi chats with
    the action items (owner + due) and the proposed meeting; `notes_saved` in Official; no
    `discussion_summary` to either.
11. Confirm an item → it is in My promises; tick it in the note → done in My promises; reopen in My
    promises → unticked in the note (both phones).
12. `@Risi summarise` → a note; a listened call's end → a note with `source: "call"`.
13. Notes list: search by a key-point word, delete (gone from the list only), share into a chat
    (an ordinary message).
14. Screens: `note-card.png`, `note-screen.png`, `notes-list.png`, `notes-saved-official.png`.

Server and Android unit tests: every example key for key (server) and decoded (Android); the
honesty rule cases; sealing/AAD; version conflicts; cursor expiry; invitable checks; the Private
refusal; offer → invite routing per audience; mixed note/summary audiences.

## Release staging
1. **nightly.47 — Risi Calendar:** server §29 with `RISI_EVENTS=off`, migrations, then the app
   advertising `risi_events`; `RISI_EVENTS=on` after gate 1–8 pass on fake-llm; the backfill runs on
   that deploy (Shenika). Calendar tab, cards, sync, reminders, digest, honesty.
2. **nightly.48 — Risi Notes:** server §30 with `RISI_NOTES=off`, app `risi_notes`; on after gate
   10–13.
3. Later: §31 Google sync (after Needs Harsha G).

## Open product questions (defaults chosen; Harsha may override)
- **Q1** Calendar reminders are **not** gated by the Reminders skill (they are the user's own
  calendar; only POST_NOTIFICATIONS matters). Alternative: require Reminders on.
- **Q2** Risi-made titles are neutral ("Interview") with participants shown separately. Alternative:
  a per-person title ("Interview with Shenika" for Harsha).
- **Q3** Official off cancels Risi-made events nobody accepted and deletes that chat's notes on the
  server (cards on phones stay). Alternative: keep notes until deleted.
- **Q4** Notes are kept 365 days (or until every recipient deletes). Alternative: forever.
- **Q5** A note needs ≥ 1 item, a meeting, or ≥ 3 key points; otherwise nothing is posted.
- **Q6** Anyone sharing a chat with the owner can be invited (≤ 20); no invites to strangers.
