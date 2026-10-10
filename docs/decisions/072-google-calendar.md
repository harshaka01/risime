# 072: Google Calendar via the Android Authorization API, phone-side only

Date: 2026-10-09. Status: accepted (root); deferred by 073; built as contract v1.31 §31 (decision 074). Proposal:
`contract/proposals/2026-10-09-google-calendar.md` (contract v1.29 §29).

## Context
On nightly.45 Risi told Harsha "Your calendar is clear" for a time his Google Calendar had events:
the phone's CalendarContract returned nothing to RisiMe. Answers must be honest about what was read,
and Google Calendar must be readable even where provider sync is off.

## Decision
- **Google Identity Services Authorization API** (`com.google.android.gms:play-services-auth`) on the
  phone, scopes `calendar.events` + `calendar.calendarlist.readonly` only. Calendar REST v3 is called
  with the existing OkHttp + kotlinx.serialization; **no Google API client library**.
- The grant lives in Play services; access tokens are memory-only on the phone. The RisiMe server
  never holds, sees or logs a Google token and makes no Google call.
- The phone returns only busy blocks and source summaries (calendar names, never titles) to Risi; the
  model never sees calendar names.
- CalendarContract (`Instances`) stays as a fallback source.
- The server enforces the honesty rule: no successful read ⇒ the model's text is replaced by a
  server-built "couldn't check" answer; never "free/clear" without a read.
- Behind the Android build flag `risime.gcal` until Harsha creates the OAuth clients (Needs Harsha G).

## Consequences
- Testing mode: sensitive-scope grants expire after 7 days → a visible [Reconnect Google Calendar].
- Public launch needs Google verification (privacy policy, home page, verified domain, scope
  justification, demo video); no CASA assessment (sensitive, not restricted scopes).
- Redroid has no Play services: gate tests use a debug-only seam and `scripts/fake-gcal`.
