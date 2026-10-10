# Proposal 2026-10-09: Google Calendar as Risi's primary calendar, honest calendar answers, the event card, the in-app Calendar (v1.29, §29)

> **Folded into v1.31 §31 (2026-10-10, root; decision 074).** The rest of this design (§29.0, §29.1,
> §29.4, §29.6, §29.8, §29.9) is now `contract/v1/PROTOCOL.md` **v1.31 §31 "Google Calendar link"**,
> rewritten for the Risi Calendar: one Google device per user, busy reads through `calendar_check`
> with `args.sources`, copies of accepted Risi events into one picked Google calendar, calendar names
> only on the phone. Not folded, and dropped: the `risime.gcal` build flag, `/auth/config`
> `risi_calendar: 2`, `skill_needed` `no_source`, the §29.5 Google/RisiMe invites (`event_invite`) and
> the §29.7 in-app Google agenda. PROTOCOL.md wins wherever the two differ.

> **Folded (2026-10-09, root):** the honesty parts — §29.2 (`calendar_check` result `sources`,
> `connected_sources`; names only there; the model sees counts only) and §29.3 (the honesty rule,
> `answer.sources` `calendar_source`) — are in `contract/v1/PROTOCOL.md` **v1.29 §29.7** as shipped
> (server 407606d, f063970; android 9c722e5, 339bb36): sent by every v1.29 phone (no
> `risi_calendar: 2` switch), the phone-only line reads "I checked: …", and the no-read text of
> §29.7 applies. The `skill_needed` `no_source` reason and the rest (§29.1, §29.4–§29.9) are **not**
> folded: they wait for §31.

> **Deferred (2026-10-09, Harsha; decision 073, `2026-10-09-risi-calendar-notes.md`).** RisiMe now has
> its own calendar (v1.29 §29 Risi Calendar). This design becomes **§31 "Google Calendar, optional
> sync (later)"**, not a dependency and not in nightly.47/48: first a **one-way mirror Risi Calendar
> → Google**, then **busy reads** as the `google_api` source. Token stays on the device; Testing mode.
> The honesty rule below (§29.3) is kept now as v1.29 §29.7, which also covers the source
> `risi_calendar`. Section numbers below (§29.x) read as §31.x.

Requested by Harsha 2026-10-09 after a real-phone result on nightly.45: "Check my calendar for Monday
2pm" → "Your calendar is clear", while his Google Calendar had events (the phone's CalendarContract
returned none to RisiMe). Root decided the design (decision 072); PROTOCOL.md §29 after the build
matches. Extends §25.3, §25.4, §26.1, §26.2, §26.5, §26.7, §28.1–§28.4 and overrides them where it
differs.

**Additive only:** `/auth/config` `risi_calendar`; `calendar_check` result `sources`,
`connected_sources`; optional `source` in `calendar_add` results and calendar refs; calendar skill
PATCH `sources`; `answer.sources` type `calendar_source`; `skill_needed` reason `no_source`; optional
`with`, `source_conversation_id` on `calendar_add` confirm cards; optional `edit` on
`calendar_accept`; optional `event_invite` on a human `text` envelope. No new device capability or
server switch. The Android Google flow is behind build flag `risime.gcal` until the OAuth clients
exist. **Google tokens, event titles and card-timeline busy blocks never reach the RisiMe server.**
The server makes no Google call (§25.7 egress unchanged).

## 29.0 Principles
- **Honesty:** every calendar answer names what was checked and what wasn't. Never free / clear /
  available unless at least one connected calendar was actually read for that window. Enforced by the
  server (§29.3), not by the prompt.
- **Token stays on the phone:** Google Play services holds the grant (Google Identity Services
  Authorization API); access tokens in memory only; never persisted by RisiMe, logged, or sent to the
  server, the model or a push.
- **The phone talks to Google** (Calendar REST v3) and returns only what §25.3 allows: busy blocks and
  the §29.2 source summary for a check; the created event's opaque id for an add.
- **Cards are personal:** the mini day timeline is computed on the viewing phone from its own sources
  and never sent anywhere.
- **Fallback:** CalendarContract stays as a second source; both are shown in Details.

## 29.1 Sources (phone)
| `source` | What | Connected when |
|---|---|---|
| `google_api` | Calendar REST v3 via the Authorization API | the user connected and `authorize()` returns a token with both §29.6 scopes |
| `phone_provider` | `CalendarContract` (`Instances`) | `READ_CALENDAR` granted |

- Reads cover every connected source; provider copies of the connected Google account's calendars are
  skipped while `google_api` is connected. Blocks are merged (§25.3).
- Writes go to the chosen calendar (default: the connected account's primary Google calendar, else the
  §28 provider target).
- Google busy reads: `events.list` `singleEvents=true`, window `timeMin/timeMax`,
  `fields=items(start,end,transparency,status,attendees(self,responseStatus)),nextPageToken` (no
  titles). Transparent, cancelled and self-declined are not busy. Calendars: those ticked in Details
  (default `selected: true`, at most 10), parallel, 8 s each; a failure makes the source
  `read_ok:false`.
- Base URL `https://www.googleapis.com/calendar/v3/` compiled in; only a debug build accepts the
  §29.8 override.
- No Play services (Huawei, Redroid): Connect disabled with "Google Play services isn't available on
  this phone. Risi uses this phone's calendar instead."; `reason: "no_play_services"`, skill PATCH
  `state: "unsupported"`.
- 401: clear the token, re-`authorize()` silently once; if it needs a resolution (revoked, or the
  7-day Testing grant ended) → `reauth_needed`. Never a background consent pop-up; Details and the next
  card show [Reconnect Google Calendar].
- Disconnect: `revokeAccess` (or the `oauth2.googleapis.com/revoke` call), delete local choice, list
  and caches, PATCH `sources`. Added events stay (§26.5).

## 29.2 `calendar_check` result v2 (amends §25.3) — folded into PROTOCOL.md v1.29 §29.7
Sent only when `/auth/config` `risi_calendar` is `2` (absent = 1). The server's schema stays strict.
```
{"blocks": [CalendarBlock],
 "sources": [{"source": "google_api" | "phone_provider",
              "calendars": [{"name": str, "account_type": str, "events": int}],
              "read_ok": bool,
              "reason": null | "not_connected" | "no_permission" | "reauth_needed" |
                        "no_play_services" | "network" | "timeout" | "api_error" | "no_calendars"}],
 "connected_sources": ["google_api" | "phone_provider"]}
```
- Both source kinds always present; `read_ok: true` ⇒ `reason: null`; ≤ 20 calendars per source;
  `name` 1–100 graphemes, `account_type` ≤ 100 chars, `events` ≥ 0.
- **Amends §25.3 "never calendar names":** names only in `sources[].calendars[].name`; an email-address
  name is sent as "Primary calendar". Never titles, descriptions, attendees, places, event ids.
- Status `no_permission` only when no source is connected; otherwise `ok` with `sources` (even with
  `blocks: []`). 16 KiB / 200 blocks as before.
- **Server:** the model sees per source `{source, read_ok, reason, calendars: <count>}`, never names.
  Names only in the server-built "Checked:" line and `answer.sources`; never in `risi_turn_steps`, the
  learning log or logs.

## 29.3 Honesty rule (server; amends §25.1, §25.4) — folded into PROTOCOL.md v1.29 §29.7
A *read* = a source with `read_ok: true` and ≥ 1 calendar.
1. **No read** (all failed, none connected, `no_permission`/`timeout`/`error`, or a v1 `ok` with
   `blocks: []`): the model's final text is discarded; the server posts `answer` (`made_by.model:
   null`) "I couldn't check your calendar, so I can't tell whether you're free." plus one line per
   source ("Google Calendar: not connected", "Phone calendar: permission is off", …). With no source
   connected, also `skill_needed` `skill_id: "calendar"`, `reason: "no_source"`, `buttons:
   ["open_skills"]`.
2. **≥ 1 read:** the model's answer stands; the server appends "Checked: Google Calendar (Primary
   calendar, Team) · Phone calendar (Work). Not checked: …". A v1 `ok` with blocks: "Checked: your
   phone's calendar (update RisiMe to see which calendars)".
3. `answer.sources` gains `{"type": "calendar_source", "source", "names": [str], "read_ok",
   "reason"}`.
4. Audience unchanged (§25.1): the asker's Risi chat; a group gets only the pointer line.

## 29.4 Calendar skill (amends §26.1, §26.2, §26.5, §28.2)
- Registry permissions: android `READ_CALENDAR`, `WRITE_CALENDAR`; oauth `calendar.events`,
  `calendar.calendarlist.readonly` (all `runtime: true`).
- PATCH `calendar` may carry `"sources": [{"source", "state": "connected" | "not_connected" |
  "reauth_needed" | "no_permission" | "unsupported"}]` per device (no names or emails);
  `client_permission` is `granted` when any source is `connected`.
- `authorize/3`: tools offered when on and ≥ 1 source `connected` or `reauth_needed`; none →
  `need_skill` `reason: "no_source"`.
- Turning Calendar on: a sheet [Connect Google Calendar] (primary; with Play services) / [Use this
  phone's calendar]. Either turns it on; the other can be added in Details.
- Calendar ref (`confirm.calendar`, PATCH `calendar`, `calendar_add` result) gains optional
  `"source"`; for Google, `name` "Google Calendar (<calendar>)", `account: null`.
- `calendar_add` result `{"event_id", "calendar"?, "source"?}`; Google insert `POST
  calendars/{id}/events?sendUpdates=none` with `extendedProperties.private.risime_write_id`; read back
  and compare, `DELETE` on mismatch. Undo: `DELETE` only for events in the local write log.

## 29.5 Event card (amends §25.4 confirm, §26.7 calendar_offer, §28.4)
No new kind. `confirm` (`calendar_add`) and `calendar_offer` render one event card: title, date, time
range, "With: …", a mini day timeline (07:00–21:00, widened to include the event), [Add to Google
Calendar | Add to calendar] [Edit] [Not now].
- `confirm` gains optional `with: [uuid]` (≤ 8 active members of the source conversation) and
  `source_conversation_id` (null in the Risi chat), filled only from a known item or offer.
- `calendar_accept` may carry `edit` (§28.4 shape), changing only that person's add; §26.7 rule (c):
  args equal the card's, or the card's merged with that `edit`.
- Mapping: Add → `confirm_write` / `calendar_accept`; Edit → with `edit`; Not now → `cancel_write` /
  `calendar_decline`.
- **Timeline (phone only):** busy blocks grey, event in accent, overlap red with "Clashes with 1
  event"; no titles; 5-minute in-memory cache; **no read ⇒ "Calendar not connected · Connect" or
  "Couldn't read Google Calendar · Reconnect", never an empty day.** Nothing from a render is sent.
- **After adding:** "Added · Open in Google Calendar" (`htmlLink`, or `Events.CONTENT_URI`), and
  "Invite <name>" for each person not yet ✓:
  - Google invite: email from a local record, the system contact picker (`ACTION_PICK`, no
    `READ_CONTACTS`) or typed; confirm "Google will email an invitation to x@y from your Google
    account"; `PATCH …?sendUpdates=all`. The email goes only to Google.
  - RisiMe invite (no email or no `google_api`): an ordinary e2ee `text` from the user in the source
    conversation (Risi-chat card: the 1:1 with that person) with
    `"event_invite": {"v": 1, "invite_id", "title", "start", "end", "all_day", "tz", "for": [uuid],
    "ref": {"write_id"} | {"offer_id"} | null}`, readable `body` "📅 Invitation: …". Honoured only
    from a human leaf of an active member; only `for` users see [Add to my calendar] [Not now]; a
    local add on their phone, once per `invite_id`.

## 29.6 OAuth scopes
Exactly two, requested together: `https://www.googleapis.com/auth/calendar.events` (busy reads,
titled reads for the Calendar view, insert, delete, attendee patch) and
`https://www.googleapis.com/auth/calendar.calendarlist.readonly` (picker, checked calendars).
Not requested: `calendar.freebusy` (an extra consent line; `events.list` busy fields suffice),
`calendar` / `calendar.readonly` (broader), `calendar.events.owned` (misses shared calendars; kept as
the fallback if review asks), `email`/`openid`/`profile`. Both are *sensitive*, not restricted: no
CASA. A partial grant = not connected ("Google Calendar needs both permissions").

## 29.7 In-app Calendar (Android only, no wire)
Entry: Settings → Risi skills → Calendar → Open calendar; Risi chat ⋮ → Calendar; tap a card's
timeline. Agenda (14 days, default), Day, Week; selected Google calendars (with titles) plus the
provider, calendar colours; Risi-created items badged; tap → sheet with "From chat: <name>" opening
the source chat. Read-mostly: edits open Google Calendar; Undo only for Risi items. Titled events in
memory only: never Room, backups, logs or the server; cleared on Disconnect.

## 29.8 Test seams (debug only)
- Broadcast `lk.codegen.risime.debug.GCAL_TEST` (receiver protected by `android.permission.DUMP`,
  registered only when `BuildConfig.DEBUG`), extras `base_url` (`http://127.0.0.1:<port>/…` only),
  `access_token`, `account`, `state` = `connected` | `reauth_needed` | `none`: installs a fake
  authorizer and base URL.
- `scripts/fake-gcal` (loopback): `calendarList`, `events` list (paging, `fields`), insert / patch /
  delete, `POST /_control` (`ok` | `revoked` | `error500` | `slow`), bearer check, JSON-lines record.

## 29.9 Gate
`ui-entry-test --gcal` on Redroid, screenshots each: (1) no source → no-read answer + `no_source`,
never free/clear/available; (2) no Play services → Connect disabled; (3) connected via seam → Details
lists both sources; (4) busy 15:00 → "busy 3–4 PM" + "Checked: …"; (5) add card with red clash →
Add → fake-gcal insert with `risime_write_id`, "Added · Open in Google Calendar", Invite →
`sendUpdates=all`, Undo deletes; (6) offer in a 1:1 Official on A (Google) and B (provider), each its
own timeline; (7) B adds a RisiMe `event_invite` locally; (8) `revoked` → "needs reconnecting", never
free; (9) Calendar view agenda/day/week, a Risi item opens its chat. Canaries: the seam's token and a
fake-gcal title never in server log, Postgres/Cassandra dumps, `risi_turn_steps`, fake-llm record,
logcat; calendar names never in the fake-llm record; no tool call while a timeline renders.
Server and Android unit tests per the implementation plan; examples:
`risi_tool_result_calendar_check_v2.json`, `…_v2_noread.json`, `risi_tool_result_calendar_add_google.json`,
`risi_skills_patch_calendar_sources.json`, `envelope_risi_answer_calendar_sources.json`,
`envelope_risi_skill_needed_no_source.json`, `envelope_risi_confirm_calendar_with.json`,
`envelope_risi_action_calendar_accept_edit.json`, `envelope_event_invite.json`, `auth_config_v129.json`.

**Rollout:** (1) server accepts v2 and applies the honesty rule with `risi_calendar: 1`; (2)
`risi_calendar: 2`; (3) app with `risime.gcal=true` once the OAuth clients exist (Needs Harsha G);
(4) pilot in Testing mode (re-consent every 7 days); (5) Google verification before any user outside
the test list.
