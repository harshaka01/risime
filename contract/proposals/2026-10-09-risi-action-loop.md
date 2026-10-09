# Proposal 2026-10-09: the Risi action loop (P0, server side)

**Status: FOLDED into PROTOCOL.md v1.28, §28** (root, 2026-10-09). PROTOCOL.md is normative; this
file is kept for history.

From the server role, for root to fold into PROTOCOL.md (§25/§26/§27). Every field below is
**optional and additive**: an app that ignores it keeps working as in v1.27. The server already
implements all of it (commit `fix(server): P0 Risi action loop …`).

## 1. `confirm.calendar` (§25.4, `calendar_add` cards only)
`"calendar": {"name": str, "account": str | null} | null`. The calendar the asker's phone last
reported (item 2 or 3 below); `null` until it reported one, so the phone asks on first use. A
hint for the card's display and the phone's choice, **not** part of `args` (the §26.5 exact
`args` check is unchanged). Always present on a `calendar_add` card (null or the object).

## 2. Calendar skill `PATCH` change: `calendar` (§26.2)
A change entry with `"id": "calendar"` may carry `"calendar": {"name": 1–100 chars,
"account": null | ≤200 chars} | null` (null forgets it). It counts as a change by itself (an
entry with only `id` and `calendar` is valid). On any other skill id → `422 bad_request`.
Stored per user, sealed with `RISI_DATA_KEY` (`risi_calendar_choices`).

## 3. `calendar_add` result: `calendar` (§25.3)
`{"event_id": str, "calendar"?: {"name", "account"} | null}`. The server remembers it as the
choice (item 2) and names it in the success line: **"Added to your Google Calendar: Interview
with Shenika · Mon 12 Oct, 2–3 PM"** (`skill_done` body, or the v1.25 `answer`); without it
"Added to your calendar: …". The 16-KiB limit and the strict schema otherwise stand (any other
key is still `422`).

## 4. `confirm_write` with `edit` (§25.4, [Edit] on the action card)
`risi_action` `confirm_write` may carry `"edit": {"title"?, "start"?, "end"?, "all_day"?}`
(the `calendar_add` wire shapes; timestamps as on the wire) instead of `null`, **for a
`calendar_add` card only**. The server checks it (title 1–200, `start < end` after the merge),
re-seals the write's args and sends the tool call with the edited args. A malformed edit, or
an edit on another tool, runs **nothing**.
- **Phone rule (amends §25.3/§26.5):** for a `calendar_add` call, the args (minus `write_id`)
  must equal the card's `args` **or** the card's `args` merged with the `edit` that a leaf of
  the phone's own user sent in the `confirm_write` for that `write_id`.

## 5. `risi_next_action` `final.draft` (§25.1; server and `fake-llm --script` only)
Optional `"draft": {"kind": "event" | "reminder", "new"?, "title"?, "date"?, "time"?,
"end_time"?, "duration_min"? (5–1440), "all_day"?, "item"? ("k<n>")} | null`. The model's
structured slots of an event or reminder; the server keeps a sealed pending draft per (user,
conversation) for 24 h, patches it from follow-ups, and posts the real `confirm` card itself
once it has a title and a time (defaults: the asker's zone, 1 hour). Not on the wire to apps.
`k<n>` refs name the asker's open promises (given to the model in the Risi chat only).

## 6. Behaviour (normative, server)
- In the Risi chat a turn's context holds the last 20 Risi-chat messages (the asker's requests
  and card taps, Risi's posts; Risi's own posts are now kept in the sealed 24-h buffer as
  `risi_post`, never a counted `text`), the asker's open promises, and the pending draft.
- `answer.next_steps` never holds a question or a confirm phrasing ("Confirm…", "Yes…", "Add
  it"): the server drops them. Confirming is only the card's [Add].
- At most one question per turn, only for a missing title, day or time; a question about
  something known is dropped and the card shown; after two questions or two turns without
  progress the card is shown prefilled (missing time → all-day; nothing → tomorrow 09:00), to
  edit or cancel.
- A skill that is off, or a phone without its permission → `skill_needed` (server-built when the
  model doesn't choose `need_skill`), never a text loop.

## 7. §27.6 amendment: the personal digest = My promises
The personal 09:00 digest lists exactly the open items of `GET /risi/commitments` (same query:
owner or counterpart, confirmed/edited, not proposed, in chats the user is still in), including
legacy v1.24 cards and items without a due or due later than 7 days (shown "no date" / the
date). Replaces "due within 7 days or overdue".

## 8. §27.2: quiet-rule thresholds from the environment
`RISI_QUIET_S` (600), `RISI_QUIET_MIN_MSGS` (6), `RISI_QUIET_MIN_PEOPLE` (2),
`RISI_QUIET_SPACING_S` (1800), `RISI_QUIET_PER_DAY` (8); a value that isn't a positive integer
is ignored. Ops/test only, no wire change.
