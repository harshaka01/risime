# Proposal: §26 Risi skills (v1.26): registry, Ask/Allowed, activity log, undo, alarm, scheduled messages, calendar offer

**Status:** requested by Harsha (2026-10-09, "Risi Skills"; overnight.md, Risi queue item 4). Root
folds it into PROTOCOL as §26. The details are root decisions (rule 8), recorded in
docs/decisions/069-risi-skills.md.

## Harsha's requirements (binding)
- **Settings → "Risi skills"** lists the skills. Each skill shows:
  - what it can do and its exact permissions;
  - a switch, off until turned on (turning it on asks for its Android/OAuth permission right
    then);
  - "Ask me each time" vs "Allowed";
  - an activity log ("Added 'Board meeting' to calendar, Tue 10:02");
  - undo where possible, and Revoke.
- **Skills, in order:**
  - **(a) Alarm:** set on the asker's phone, confirm card first.
  - **(b) Reminders:** §25 `set_reminder`, with Me too.
  - **(c) Calendar:** the phone client tool. An agreed meeting gets "Add to calendar for:
    Harsha ✓ Kumu ☐", each person confirms on their own phone, with an optional reminder.
  - **(d) Scheduled messages:** stored and sent **by the asker's phone**, encrypted at send time,
    never by the server. A clock icon, edit/cancel until sent, "sent late", a list in chat ⋮, and
    "every day". The recipient sees a normal message.
  - **(e) Email**, later: Gmail/Outlook read-only first; sending only with a full-draft confirm.
- **Rules:**
  - Risi acts only for the asker.
  - Anything sent or created in someone's name needs that person's confirm or "Allowed".
  - Other people's messages never trigger a skill.
  - Every action is logged, and undoable where possible.
  - Skills run from the Risi chat and @Risi in Official, never Private.
  - Partner agents later appear as skills.

## Proposed wire (summary; normative text in PROTOCOL §26)
1. **Registry + state.** `GET /api/v1/risi/skills` returns each `Skill`:
   - `id`, `title`, `description`, `can`, `cannot`;
   - `permissions[]` (scope, exact name, label, runtime);
   - `tools`, `modes`, `undo`, `available`;
   - `state` `off|ask|allowed`;
   - `client`, the permission this device reported.

   `PATCH /api/v1/risi/skills` takes `{"changes": [{id, state?, client_permission?}],
   "cancel_pending"?}` from a `risi_skills` device.
2. **Ask / Allowed.**
   - `ask` means a confirm card for every write.
   - `allowed` skips the card only for writes that affect the asker alone: an alarm, an own
     reminder, an own calendar add.
   - The phone accepts an allowed write only when its own user's `risi_request` is in MLS history.
   - **Always confirm:** sends to other people (scheduled messages, email later), reminders posted
     to a conversation, and cancels by request.
3. **Activity log.**
   - `GET /api/v1/risi/skills/{id}/activity` returns entries with `summary`, `via`, `undo`
     {kind, state, until, hint} and a single-use `undo_token`; `DELETE` clears it.
   - It is sealed with `RISI_MEMORY_KEY`, kept 90 days, and never in the learning log.
   - It never holds a scheduled message's text.
4. **Undo.** `POST …/activity/{entry}/undo {undo_token}`:
   - a server action gives `200` (cancel a reminder);
   - a client tool call to the device that did it gives `202` (`calendar_remove`,
     `cancel_scheduled`, with `undo_entry_id`);
   - an alarm can't be undone by an app, so its undo is `manual`: "Open Clock to remove it".
5. **Revoke** = `state: off`.
   - Open cards are void. Pending reminders and schedules are cancelled only on request.
   - Risi answers with `skill_needed`: "I no longer have access to X; turn it on in Settings → Risi
     skills". This comes from the pseudo-tool `need_skill`.
6. **Client tools:**
   - `set_alarm {write_id, time, label, days?}` → `{alarm_set: true}` (`ACTION_SET_ALARM`);
   - `schedule_message {write_id, conversation_id, text, at, repeat?: "daily"}` → `{schedule_id}`;
   - `cancel_scheduled {write_id?, schedule_id}` → `{cancelled, reason}`;
   - `calendar_remove {target_write_id}` → `{removed, reason}`.
7. **Meeting card** `calendar_offer {offer_id, title, start, end, all_day, for, reminder_before_min,
   …}` in Official.
   - Each person in `for` sends their own `risi_action` `calendar_accept` (optionally
     `options.reminder`) or `calendar_decline`.
   - An accept leads to a `calendar_add` on **that person's** phone only, after their own tap.
8. **Confirm cards** gain `skill_id` and the exact `args`. There is a new result card,
   `skill_done`, with [Undo].
9. **Capability** `risi_skills` (with `risi_tools`); `/auth/config` `risi_skills: on|off`
   (default off).
   - Users without a `risi_skills` device keep v1.25.
   - New errors: `409 skill_unavailable`, `409 undo_unavailable`.

## Examples
- **Skills REST:** `risi_skills_reply.json`, `risi_skills_patch.json`,
  `risi_skills_patch_reply.json`, `risi_skill_activity_reply.json`,
  `risi_skill_activity_scheduled_reply.json`, `risi_skill_undo.json`,
  `risi_skill_undo_reply.json`.
- **Tool calls and results:** `event_risi_tool_call_set_alarm.json`,
  `risi_tool_result_set_alarm.json`, `event_risi_tool_call_schedule_message.json`,
  `risi_tool_result_schedule_message.json`, `event_risi_tool_call_cancel_scheduled.json`,
  `risi_tool_result_cancel_scheduled.json`, `event_risi_tool_call_calendar_remove.json`,
  `risi_tool_result_calendar_remove.json`.
- **Envelopes:** `envelope_risi_confirm_set_alarm.json`,
  `envelope_risi_confirm_schedule_message.json`, `envelope_risi_calendar_offer.json`,
  `envelope_risi_action_calendar_accept.json`, `envelope_risi_action_calendar_decline.json`,
  `envelope_risi_skill_done.json`, `envelope_risi_skill_needed.json`.
- **Config, device and errors:** `auth_config_v126.json`, `device_put_risi_skills.json`,
  `error_skill_unavailable.json`, `error_undo_unavailable.json`.

## Open (Harsha)
1. **Email (e):** Google OAuth test-users mode and a Microsoft Graph app registration (Needs
   Harsha E). Email gets its own proposal when these exist; until then it is listed as
   `available: false`.
2. **Scheduled messages in backups:** pending schedules are local and kept across updates, but not
   in the §22 backup bundle in v1.26. Add a bundle line later?
3. **Composer "Schedule send"** without Risi: the same phone scheduler, with no wire change. Add it
   when Harsha wants it.
