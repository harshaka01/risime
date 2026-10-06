# 021 — Android: the phone verification client (contract v1.4)

## Context
Decision 020 and contract §7 add a one-time SMS confirmation of the allowlisted phone. This note
records the client choices.

## Decision
1. **No SMS Retriever, no User Consent API (for now).** The code field carries the Compose
   autofill content type `SmsOtpCode`, so the keyboard or the autofill service offers the code
   from the SMS. That adds no Google Play services dependency and no app hash in the message. The
   User Consent API can be added later if Harsha wants one-tap entry; it needs no message change.
2. **State lives in the session's `User`.** `phone_verified` is stored with the user in DataStore,
   so after a restart (and the fingerprint unlock) the app goes straight to "Confirm your phone".
   `GET /me` remains the source of truth. A pending code isn't persisted: leaving the screen means
   "Send code" again.
3. **Gate order:** update required → blocked (403/409) → locked → confirm phone → chats
   (`appGate`). The socket and the contacts fetch run only for a verified session
   (`shouldConnect`).
4. **`403 phone_unverified`** from REST, a refused socket upgrade, or the `auth:refresh` error
   all lead to `GET /me`. The client then marks the user unverified, stops the socket, and never
   wipes data or reconnects in a loop.
5. **Timer:** "Send code" or "Resend" is disabled for `Retry-After` seconds on a 429, else 60 s
   after a send. After a 410 or a 503 it is enabled at once. A 409 `already_verified` counts as
   success (re-fetch `/me`).
6. **Masking:** before the first send, the screen masks the user's own phone on the device in the
   server's style (first 5 characters, U+2022 bullets, last 2). After a send it shows the server's
   `to`.

## Consequences
- Until the "RisiMe" sender ID is approved, the server runs `SMS_MODE=log`, and testers read the
  code from the server log.
