# 073: Risi Calendar and Risi Notes on the server; Google Calendar becomes optional sync

Date: 2026-10-09. Status: accepted (root). Proposal: `contract/proposals/2026-10-09-risi-calendar-notes.md`
(contract v1.29: §29 Risi Calendar, §30 Risi Notes, §31 Google Calendar later). Supersedes the
"Google Calendar as primary" plan of decision 072 (072's phone-only token and honesty rules stay for
§31).

## Context
Harsha (2026-10-09): RisiMe gets its own calendar and notes; Google Calendar must not be a
dependency (OAuth clients, Testing-mode 7-day grants, verification, no Play services on Redroid/Huawei).

## Decision
- **Risi Calendar** is a server-side, per-user store in Postgres (`risi_events`, participants with
  per-person status, a per-user change log as the sync cursor), titles and notes **sealed with
  `RISI_DATA_KEY`** (no plaintext column, CHECK constraints), synced to every device with a
  content-free stored inbox event + REST cursor fetch. Risi writes without any device permission.
- It is **sealed at rest, not end-to-end encrypted**; the app says so (decision 048 spirit).
  End-to-end encrypted calendar (e.g. events as MLS messages in the Risi chat) is a later option.
- Agreed Official appointments and §28 offers become **proposed events + invite cards**; user
  requests become one `risi_calendar_add` action card. Reminders reuse the §26 reminders path.
- The honesty rule (072/§29.3) is kept and extended: `risi_calendar` is a real source; every
  availability answer states what was checked.
- **Risi Notes** reuse the §27 extraction and ledger items (note id = summary id) and replace the
  quiet-rule summary for app versions that support them; sealed in `risi_notes`.
- No new framework or service; Elixir/Postgres/Oban only.
- Release: Calendar in nightly.47, Notes in nightly.48, Google sync later (still Needs Harsha G).

## Consequences
- The server (Risi) can read users' calendars and notes; protected by the data key and access rules,
  not by E2EE. Losing `RISI_DATA_KEY` makes them unreadable: the key must be backed up with `.env`.
- Calendar and notes are not in chat backups (server data); a restored phone re-syncs.
