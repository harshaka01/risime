# Proposal: "I need your calendar to do that." [Allow calendar] (2026-10-14, Harsha 10:55 / 10:57)

**Status:** APPLIED as **v1.49 §43** (root, 2026-10-14; adds the example `device_put_risi_fixit.json`). Was: proposal for root/Harsha, to be applied as **v1.49 §43** (with §42 send-for-me). Amends §26.2,
§26.5, §28.3. Nothing in PROTOCOL.md changes until root applies it. It is built behind flags: the server
switch `RISI_PERMISSION_FIXIT` (`off | canary | on`, default `canary` = `RISI_DEV_USERS`) and the device
capability `risi_fixit`. Phones without `risi_fixit` keep `skill_needed` (§26.5) exactly.

## Why
- Risi says things like "Calendar permission is off on this phone" or "I don't have access to your calendar
  yet. Turn it on in Settings → Risi skills". That is a dead end: the user has to find a page and decode
  `READ_CALENDAR`.
- The app never asked for the calendar permission when Risi first needed it. Settings showed "permission not
  asked yet".
- Harsha's rule: Risi never just says "can't access". It says, in one sentence, what it needs, gives **one
  fix-it button**, and when the user allows it, **finishes the original request by itself**.

## 43.0 Principles
1. **One plain sentence:** "I need your calendar to do that." (in the request's language).
2. **One button:** **[Allow calendar]**. It shows the Android permission prompt directly. If Android blocks
   the prompt ("don't ask again"), it opens RisiMe's app-permissions page with the hint "Tap Calendar →
   Allow". If the skill was off, the same tap turns it on: `ask` ("Show me first") by default.
3. **After the user allows,** the phone tells Risi. **Risi retries the original request automatically** and
   confirms it as usual (the card, then "✓ Added to your calendar").
4. **After the user says no:** "OK, I won't use your calendar. You can change this in Risi skills." There is
   no nagging. Risi asks again only on the next request that needs the permission.
5. **This applies to every permission:** calendar, contacts, microphone, notifications, location, alarms, and
   any future one. **Ask at first need,** never from the background. The user's own tap is the consent.
6. **Never a false "off"** (§42.5 still holds): the card is shown only when the user's skill is off, or the
   phone's fresh report or its own tool result says the permission is not granted.

## 43.1 Capability and switch
- **Device capability `risi_fixit`** (`mls.capabilities`, kept only with `risi_skills`). A device advertises
  it once it handles this whole section.
- **`RISI_PERMISSION_FIXIT`** = `off | canary | on` (runtime, `RisiMe.Agents.Flags`; default `canary`).
- **The card is sent** when the switch is on for the asker **and** the asking device (or, when no device is
  known, any device of the asker) advertises `risi_fixit`. Otherwise the server sends the §26.5
  `skill_needed`, unchanged.

## 43.2 The card `needs_permission` (new `risi.kind`, in the asker's Risi chat)
```
risi: {"v": 1, "kind": "needs_permission", "request_id": uuid, "resume_id": uuid,
       "permission": "calendar" | "contacts" | "microphone" | "notifications" | "location" | "alarms",
       "skill_id": "calendar" | "reminders" | "alarm" | … | null,
       "turn_on": bool,          the skill is off: the tap also turns it on (state "ask")
       "text": "I need your calendar to do that.",
       "buttons": ["allow"], "expires_at": ts, "turn_ref": uuid | null, "call_ref": null,
       "made_by" (rule), "notify": [asker]}
body: "I need your calendar to do that. Update RisiMe to allow it here, or turn it on in Settings → Risi skills."
```
- `resume_id` names the server's sealed record of the original request (24 h; §43.4).
- `permission` → Android (informative): calendar = `READ_CALENDAR` + `WRITE_CALENDAR`; contacts =
  `READ_CONTACTS`; microphone = `RECORD_AUDIO`; notifications = `POST_NOTIFICATIONS` (API 33+); location =
  `ACCESS_COARSE_LOCATION`; alarms = no runtime permission (the tap only turns the skill on). The app labels the
  button "Allow calendar" / "Allow contacts" / …; an unknown `permission` shows "Open Risi skills".
- If the request came from a group, the group gets only the §25.1 pointer line, as for every personal card.
- Old apps (no `risi_fixit`) never get this kind.

## 43.3 The two answers (`risi_action`, from the asker's own leaf, in the Risi chat)
| `action` | `target` | when |
|---|---|---|
| **`permission_granted`** | `resume_id` | after Android says granted. The phone has already sent the §26.2 `PATCH` (`client_permission: "granted"`, and `state: "ask"` when `turn_on`) |
| **`permission_declined`** | `resume_id` | the user refused the prompt, or closed the settings page without allowing |
`edit: null` (`envelope_risi_action_permission_granted.json`). Anyone else's, a second one, or one after
`expires_at`, is ignored.

- **`permission_granted`:** Risi runs the original request again (a fresh turn with the same `request_id`, the
  same text and the same routing, §34). No model call is spent on the card itself.
- **`permission_declined`:** Risi posts the `answer` "OK, I won't use your calendar. You can change this in Risi
  skills." with `next_actions: [{"label": "Risi skills", "action": "open", "target": "settings.risi_skills"}]`.
  Nothing else: no reminder, no second card for that request.

## 43.4 Server (normative)
- **When:** instead of `skill_needed` (§26.5, §28.3) for a skill that is off or lacks the phone permission; and
  when a client tool step answers `no_permission` (§25.3) in a turn.
- **The resume record:** a `risi_pending_writes` row with `state: "resume"`, `tool: "needs_permission"`, the
  original request's `message_id`, `permission` and `skill_id` sealed with `RISI_DATA_KEY`, 24 h, one use. It
  holds ids only, never the request text: the text stays in the sealed 24-h buffer it is read from.
- Learning log: the step stays `need_skill` / `denied` in `risi_turn_steps` (hashes only).

## 43.5 The phone (android, normative)
- **The card:** the sentence and **[Allow calendar]**. The tap runs `requestPermissions` from the chat
  screen. If `shouldShowRequestPermissionRationale` is false after a refusal (Android blocks the prompt), the
  app opens `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` and shows "Tap Calendar → Allow". When the user
  comes back, the app checks the permission again.
- **Granted:** first the `PATCH`, then `permission_granted`. The card shows "✓ Risi can see your calendar".
  **Refused:** `permission_declined`; the card shows "Not allowed".
- **Settings → Risi skills → Calendar** (and contacts, notifications, microphone, the same pattern):
  - not granted: one line and one button, "Risi can't see your calendar yet." **[Allow calendar]** (the same
    prompt-or-settings logic);
  - granted: "✓ Risi can see your calendar", then the "Show me first" / "Do it without asking" choice. Nothing
    else.
  - `READ_CALENDAR`/`WRITE_CALENDAR`, auto-sync, the window, the calendar count, error text and Copy diagnostics
    are shown **only in developer mode**.
- **Ask at first need:** the first "Add to calendar" (or the morning brief's calendar read) on a phone that
  never asked shows the card's sentence and the prompt, with one line on why.

## 43.6 Gate
- Server tests, one per permission (calendar, notifications, alarms; contacts, microphone and location have
  the same shape): denied → `needs_permission` card with `resume_id` → `permission_granted` → the original
  request runs again (for the calendar, its card) → declined gives the one sentence. Old devices still get
  `skill_needed`.
- Android (JVM): the card decodes; the button picks the prompt or the settings page; granted sends the
  `PATCH` and then `permission_granted`; declined sends `permission_declined`; the Calendar settings screen
  shows only the line and the button until the permission is granted; developer details are hidden.
- Device gate (scripts/send-for-me-test grows a step): "add dentist Friday 10am" with the calendar permission
  revoked (`pm revoke`) → card → [Allow calendar] → `pm grant` stands in for the dialog → the calendar card
  appears without a new request.

## 43.7 Examples (to add when applied)
`envelope_risi_needs_permission.json`:
```json
{"v":1,"type":"text","body":"I need your calendar to do that. Update RisiMe to allow it here, or turn it on in Settings → Risi skills.","risi":{"v":1,"kind":"needs_permission","request_id":"6d1f2e3a-4b5c-4d6e-9f7a-8b9c0d1e2f3a","resume_id":"3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f","permission":"calendar","skill_id":"calendar","turn_on":true,"text":"I need your calendar to do that.","buttons":["allow"],"expires_at":"2026-10-15T05:30:00.000Z","turn_ref":"8a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d","call_ref":null,"made_by":{"model":null,"provider":"rule","at":"2026-10-14T05:30:00.000Z","also":[]},"notify":["7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"]}}
```
`envelope_risi_action_permission_granted.json`:
```json
{"v":1,"type":"risi_action","action":"permission_granted","target":"3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f","edit":null}
```

## 43.8 What ships where
- **Server only (fast lane):** the card, the resume record and the two actions, behind `RISI_PERMISSION_FIXIT`.
  It is inert until a phone advertises `risi_fixit`.
- **APK:** `risi_fixit`, the card, the prompt-or-settings button, the new Calendar settings screen, asking at
  first need, and developer-only diagnostics.
