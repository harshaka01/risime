# 065 — Two tabs per chat (Private | Official); Risi lives only in Official

**Status:** accepted 2026-10-08 (product model: Harsha; details: root). Contract proposal
`contract/proposals/2026-10-08-two-tabs-risi.md`, which becomes PROTOCOL §24 (v1.24).

## Decision
- **Two conversations per chat:** every chat (1:1 or group) has a Private and an Official
  conversation. Each is its own MLS group, linked by `chat_id`.
  - `chat_id` = the existing Private anchor id (`dm:` or `grp:`), so existing chats need no new ids.
  - Official is always a `grp:`, including for 1:1s, because §10.2 DMs can't hold a third member.
- **Private:** never an agent. The server returns `403 private_tab`, the MLS core rejects an agent
  leaf or a non-empty `agents`, and the UI offers nothing. Never transcribed or used as model input.
- **Official:** Risi is always a visible member while Official is on.
  - **Toggle rights:** either person in a 1:1, admins in a group.
  - **Off:** Risi is removed and its derived facts for the chat deleted; the conversation stays
    read-only. There is one Official conversation per chat, ever.
  - **Creation:** lazy for 1:1s and migrated chats (an intro card with [Start Official]); straight
    after the Private group for new groups.
- **Old apps never see Official:** the `tabs` capability filters delivery, GET and push.
- **Risi agent placement:** the same release, an isolated `RisiMe.Agent` tree.
  - MLS goes through a Rustler NIF on the crypto core with a journal store sealed under
    `RISI_MLS_KEK`.
  - §12.0 is amended to "agent keys are never readable by the delivery-service store". A separate
    process is a later hardening step.
- **Migration:** additive only (rule 9). All existing conversations become Private with
  `chat_id = id`. Backups containing Official are schema 2.

## Consequences
- **Upgrade gate:** counts per conversation (both tabs) and per chat.
- **Canary interop:** proves Private text never reaches a model, a table, a log or a push.

## Operational rule (privacy review, 2026-10-08)
After `RISI` has ever been on in an environment, never roll the server back past v1.24 (or the
two_tabs migration) without first deleting the Official groups and their rows. Older code treats
Official groups as plain groups and would deliver them to every app. `scripts/rollback` must refuse
to cross v1.24 while `groups.tab = 'official'` rows exist (root to-do).
