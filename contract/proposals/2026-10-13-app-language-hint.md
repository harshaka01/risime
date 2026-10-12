# Proposal: the phone's app language as a speech hint (2026-10-13, risi role)

**Status:** proposal for root/Harsha. Nothing on the wire changes until root edits PROTOCOL.md.

## Why
Decision 071 amendment d: the speech service takes `language_hint` (si/ta/en) and, in `prior`
mode, a hinted Sinhala/Tamil wins unless English is clearly dominant (Sinhala CER -1.8 to -2.3
points, Tamil -2.4, English unchanged). The server now sends a hint for calls (§36) and voice
notes (§37), built from what it already knows:
- each participant's `Lang.user_lang/1`: the script of their remembered Official messages;
- the chat's language: the script of its remembered messages.

That misses the most common case: people who type Singlish in Latin letters ("en") but speak
Sinhala. Their calls still depend on detection alone. The phone's UI language (si / ta / en) is the
best signal we don't have.

## Proposal (additive, optional, no version bump of existing fields)
- Device registration / device update (`PUT /api/v1/devices/{id}` or wherever capabilities are
  sent): optional `app_lang`, one of `"si" | "ta" | "en"`; absent = unknown. The server stores it on
  the device row; it is not shown to other users.
- The server adds a participant's `app_lang` (their current device's) to the hint's union. Only
  si/ta matter; "en" is ignored by the service anyway.
- Old clients send nothing and behave exactly as today.

## Privacy
The UI language is low-sensitivity, stays on the server, is never sent to other members and never
logged except as the hint's language codes.

## Not proposed
No per-call or per-message language field: the session-level union is enough for `prior` mode,
and `force` mode is not recommended (it destroys English spoken by a Sinhala/Tamil speaker).
