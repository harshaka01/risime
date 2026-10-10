# 074: The Google Calendar link: the token on one phone, busy times in, Risi events out

Date: 2026-10-10. Status: accepted (root). Contract: `contract/v1/PROTOCOL.md` **v1.31 §31**.
Folds `contract/proposals/2026-10-09-google-calendar.md`. **Supersedes the deferral** in 072 and
073. Their phone-only token rule and the honesty rule stay.

## Context
Harsha (2026-10-10) wants Google Calendar connected from Settings → Risi skills → Calendar.
- The connection uses the Google Authorization API on the phone, and the token stays on the
  device.
- Risi reads busy times from the calendars he picks.
- Risi adds and updates his Risi events in one Google calendar he picks.
- Every answer says which calendars were checked.
- The tests run against a mocked Google API, followed by a real-phone checklist.

The Google Cloud steps are in `docs/GOOGLE-CALENDAR-SETUP.md`: Testing mode, an Android client
with no secret, and the scopes `calendar.events` and `calendar.calendarlist.readonly`.

## Decision
1. **One Google device per user**, the device that connected. Only it talks to Google: it reads
   busy times and writes copies. "Connect here instead" moves the link to another device.
2. **The token never leaves that phone and is never written to disk.** It is kept in memory, and
   `authorize()` is called again silently when it expires. There is no Keystore copy, because
   Play services already holds the grant. The server gets no token, no auth code and no email.
3. **The server stores almost nothing**, in `risi_gcal_links`: the device id, the state
   (`connected` / `reauth_needed`), the number of read calendars, whether a write calendar is
   set, the mirror switch, and timestamps. It stores no calendar names, no Google ids and no
   event ids. It makes no Google call.
4. **Busy reads reuse the §25.3 client tool `calendar_check`**, sent to the Google device with
   `args.sources: ["google_api"]` inside the §29.7 `risi_calendar_check` step. The 15-s
   deadline gives `no_answer` ("phone didn't answer"). A known `reauth_needed` link, or the
   Calendar skill turned off, sends no call at all.
5. **Calendar names stay on the phone.** The result lists Google calendars as random local
   `ref`s with counts. The server writes "Google Calendar (2 calendars)". The Google phone
   replaces that text with the real names when it renders the message, and the stored message
   is unchanged. We chose this over sealing the names with `RISI_DATA_KEY` because the server
   then never has them at all. The model sees only counts, as in §29.7.
6. **Honesty, extended.** A connected source that was not read is always named under "Not
   checked" with its reason, and a free claim made while it is unread is rewritten by the
   server. "Free" or "clear" is never said about an unread source.
7. **Copies, not sync.** The Google device copies accepted Risi events (never proposed ones)
   into the picked write calendar with `sendUpdates=none` and no attendees, so Google sends no
   emails.
   - Each copy's id is fixed: `risi` + the event id in hex. A private extended property
     `risime=1` and `risi_event_id` tags it, so inserts are idempotent and a new device adopts
     the existing copies.
   - Updates and cancellations follow the event.
   - Edits made in Google are not copied back.
   - A copy the user deletes in Google is not added again.
   - The server knows nothing about copies.
8. **Loop guard.** Tagged copies are never counted as Google busy times. Provider events with a
   `risi<hex>` sync id are not counted either, and the Google device skips the provider's copy of
   the connected account. Each event therefore counts once, in the Risi Calendar.
9. **Switch, capability, error and card.**
   - `RISI_GCAL=on` (off by default) works only together with `RISI_EVENTS`. It appears as the
     `/auth/config` key `google_calendar`; the key `risi_calendar` stays reserved.
   - The capability `google_calendar` is accepted only together with `risi_events`.
   - The new error is `409 not_google_device`.
   - The new card is `google_reconnect`, posted once per 24 h when the 7-day Testing grant ends.
   - There is no Android build flag: the Android OAuth client needs no id in the app.
10. **Disconnect revokes on the phone** with `revokeAccess` (the revoke endpoint is the
    fallback). Disconnecting from another device makes the Google device revoke when it hears of
    it. A *replaced* device never revokes, because that would also end the new device's grant.
    Copies are kept unless the user chooses Remove.
11. **Test seams.** A debug-only broadcast `GCAL_TEST` sets the base URL and a fake authorizer,
    protected by `DUMP`; the release dex is checked to have none of it. `scripts/fake-gcal`
    serves the mocked API. `ui-entry-test --google` runs the gate on Redroid, which has no Play
    services. Harsha runs a real-phone checklist on a release build.

## Consequences
- Google reads depend on the Google phone being reachable. When it is offline, answers say so
  rather than guess.
- In Testing mode, Harsha reconnects every 7 days, with one tap from the card or Settings.
- Public launch still needs Google verification (072). The scopes are sensitive, not
  restricted, so no CASA assessment is needed.
- No new framework. Android uses `play-services-auth` (already chosen in 072), OkHttp and
  WorkManager. The server uses Postgres and the existing tool-call path.
