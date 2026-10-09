# 069 — Risi skills: the permission layer for tools, Ask/Allowed, a sealed activity log, undo, phone-sent scheduled messages

**Status:** accepted 2026-10-09 (requirements: Harsha, "Risi Skills"; details: root).
Proposal `contract/proposals/2026-10-09-risi-skills.md`, which becomes PROTOCOL §26 (v1.26).

## Decision
- **Skills are the permission layer every tool runs under.**
  - The registry is server-defined (`RisiMe.Agent.Skills`): `alarm`, `reminders`, `calendar`,
    `scheduled_messages`, and `email` (`available: false` until OAuth).
  - Per-user state is `off` (the default) / `ask` / `allowed`.
  - Each device reports its Android permission state, and the server uses it to route tool calls
    and to say why something is not possible.
  - `authorize/3` offers a skill tool only while its skill is on.
- **Turning a skill on asks for the permission right then.** The switch stays off if the
  permission is refused. `ask` is the default when a skill is turned on; `allowed` is a separate,
  explicit choice.
- **What "Allowed" skips.** "Allowed" skips the confirm card **only for writes that affect the
  asker alone**: an alarm, a "me" reminder, and a calendar add from their own request.
  - **Always confirmed:** anything sent to other people (scheduled messages; email later, with a
    full-draft card), reminders posted to a conversation, and cancels by request. So
    `scheduled_messages` and `email` offer only `ask`.
  - **Why:** "Allowed" is a convenience for the user's own world. Speaking for the user to others
    stays a per-message decision.
- **The phone checks allowed writes itself.** It runs an allowed write only if its own user's
  `risi_request` for that `request_id` is in its MLS history (at most 10 min old). The server can't
  forge that request, so even a compromised server can only act in answer to a real request.
- **Activity log.**
  - Postgres `risi_skill_activity`, with `summary` and the undo data sealed with `RISI_MEMORY_KEY`.
  - Kept 90 days; the user can clear it.
  - **Never** in the learning log, `risi_turn_steps`, server logs or pushes.
  - **What is logged:** only successful actions and state changes.
  - **What is never logged:** a scheduled message's text, or anything from Private.
- **Undo** uses a single-use `undo_token`:
  - **server:** a reminder before it fires;
  - **client:** a calendar event RisiMe added (within 30 days), or a pending schedule. Undo goes
    through a `risi_tool_call` with `undo_entry_id` **to the device that did it**. The phone
    accepts it only against its own local record of what RisiMe created, so an undo can never
    touch anything else.
  - **manual:** alarms. Android has no API for an app to delete an alarm it set through
    `ACTION_SET_ALARM`. Risi and the app say "Open Clock to remove it".
- **Revoke** sets the state to `off`.
  - Open confirm cards become void.
  - Things already made (alarms, calendar events) stay; they are the user's.
  - Pending reminders and schedules are cancelled only if the user ticks it.
  - Risi says "I no longer have access to X; turn it on in Settings → Risi skills". This comes
    from the `need_skill` pseudo-tool in the next-action schema, so the model can name only a skill
    that is really off.
- **Scheduled messages are a phone feature, not a server one.**
  - The phone stores them in Room and arms an exact alarm, or uses WorkManager when
    `SCHEDULE_EXACT_ALARM` is not granted.
  - At send time it encrypts with the current MLS epoch and sends through the normal `msg:send`.
    The recipient sees a normal message.
  - The server sees the text once in the tool call (a 2-minute TTL row, deleted on the result),
    and only because it was in the asker's own request. It stores it nowhere.
  - **Late sends:**
    - more than 2 min late: marked "Sent late" for the sender;
    - a one-off more than 12 h late: asks the user before sending;
    - daily: missed occurrences are skipped.
  - An update keeps pending schedules. They are not in backups yet (open question).
  - **Target:** a scheduled message may go into Private. It is the user's own message, typed in
    the confirm card and sent by the user's phone, and Risi learns nothing back. This does not
    break "Risi is never in Private": Risi reads nothing there.
- **Meeting card (`calendar_offer`).** Risi posts an agreed meeting in Official as a suggestion
  card. Nothing happens until each person in `for` taps [Add] on their own phone.
  - The accept is that person's `risi_action` from their own leaf.
  - The `calendar_add` goes only to that person's accepting device. The phone verifies the offer,
    its own accept and the exact values (rule (c)).
  - Results go to each person's Risi chat, never the group.
  - The ✓ marks come from the visible accepts (like Me too). A decline is not shown to others.
  - At most 3 offers per chat per day.
  - Detection from conversation content is allowed because the card is not an action. "Other
    people's messages never trigger a skill" still holds: only each person's own tap does.
- **Confirm cards carry the exact client-tool `args`.** The phone compares them exactly. This
  generalises §25.3's title/when match to every tool. The result card is `skill_done`, with
  [Undo].
- **Compatibility.**
  - Capability `risi_skills` (only with `risi_tools`); server switch `RISI_SKILLS` (default off).
  - Gates apply only to users with at least one `risi_skills` device; others keep v1.25 exactly.
  - Gated users start with every skill off ("off until turned on"), including reminders and
    calendar, which v1.25 offered behind confirm cards. The first request gets a `skill_needed`
    card with a turn-on button.

## Consequences
- **Server:** the registry and state tables, the REST, `authorize/3` skill gates, `need_skill`,
  the activity log and undo, four new client tools, `calendar_offer` detection and the accept flow,
  and the new examples in `examples_test.exs`.
- **Android:**
  - Settings → Risi skills (the turn-on flow, Ask/Allowed, activity, undo, Revoke with "also
    cancel");
  - the new phone acceptance rules;
  - the scheduler (exact alarm or WorkManager, re-arm on boot and update, late rules), the
    clock-icon bubble and the chat ⋮ list;
  - `ACTION_SET_ALARM`;
  - the calendar map from `write_id` to event id, for undo;
  - the new examples in `ContractExamplesTest`.
- **Email** needs Harsha's OAuth set-up (Needs Harsha E) and its own contract proposal.
- **Partner agents** (Lia, eDrop) will join the registry as `kind: "partner"` skills, with their
  own permissions.
