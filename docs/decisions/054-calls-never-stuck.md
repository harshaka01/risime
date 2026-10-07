# 054: Calls never get stuck (nightly.16 P0 fix)

Date: 2026-10-07. Roles: android + server (P0). Contract proposal:
`contract/proposals/2026-10-07-calls-never-stuck.md` (busy rule, accept-wait notice, direct
connect timeout, the local missed-call line).

## Problem
nightly.16 (contract v1.13 §16): Harsha → Shirazi and Shirazi → Harsha, the callee couldn't
answer; afterwards the caller stayed "in call" even after killing the app, and every new call said
"You're already in a call". The server holds no call state (only TTL'd `call_signals` rows and a
45-s in-memory ring timer), so the stuck state was on the phones.

## Root causes (from the code; the pilot server logged no `call:signal` at all)
0. **The audio-capture bug (found on redroid, P0 safety).** core-telecom's `disconnect` accepts only
   `DisconnectCause` LOCAL/REMOTE/MISSED/REJECTED. nightly.16/17 passed ERROR ("Can't connect"),
   BUSY or ANSWERED_ELSEWHERE: the disconnect threw ("not a valid Disconnect code"), and the
   self-managed call stayed ACTIVE in Telecom, owning MODE_IN_COMMUNICATION and the mic/speaker route
   until a reboot (normal phone calls broken). It was reproduced with the nightly.16 APK: after the
   caller died mid-call, the callee kept a ghost ACTIVE call and answered every later offer `busy`.
   Fixed in e4685c5: valid causes only, a LOCAL retry, and a full audio release on every end path.
1. **Ghost core-telecom calls.** `CallManager` kept one Telecom slot (`telecomCallId`/`telecomScope`).
   A new call id (glare, a quick re-call, StateFlow conflation between "ended" and the next call)
   overwrote it without disconnecting the old self-managed call; ending a call before Telecom had
   added it disconnected nothing. A ghost call holds `MODE_IN_COMMUNICATION` while the process
   lives (the `phoneCall` service and the Telecom binding keep it alive after a swipe-away).
2. **"Busy" by audio mode.** `MODE_IN_COMMUNICATION` counted as "in another call" (android R9). With
   a ghost call (or any app's leftover mode) every new call said "You're already in a call" and every
   incoming offer was answered `call_busy`.
3. **Telecom callbacks not bound to their call.** `onDisconnect`/`onSetInactive` of any Telecom call
   hung up whatever call was current: answering a new call made Telecom try to hold the ghost, whose
   `onSetInactive` declined the ringing call — "the callee can't answer".
4. **Nothing bounded the setup.** The ring timer was armed only after the offer's push succeeded; media
   operations ran under the machine lock without a timeout, so Hang up/Cancel could wait forever; the
   accept wait ended silently ("Call ended").
5. **Mic permission on the lock screen.** The first answer asks for `RECORD_AUDIO`; that dialog can't
   show over the keyguard, so Answer looked dead on a locked phone.
6. Contributing: TURN still answers 503 and the call ports are filtered from outside (rechecked
   2026-10-07 01:58 UTC), so phones on different networks have host candidates only and fail ICE.

## Decision
- One core-telecom call per call id (a map). Every Telecom callback names its call id; a stale one is
  ignored. Any Telecom call whose id isn't the current call is disconnected on every state change;
  disconnect waits (≤ 3 s) for Telecom to have added the call.
- Busy = this device's own RisiMe call (which can no longer outlive its timers), or a cellular call
  (`MODE_IN_CALL`). `MODE_IN_COMMUNICATION` alone is not busy.
- Bounds: the caller's 45-s ring timer starts with the call; every `call:signal` ≤ 10 s; every WebRTC
  operation under the lock ≤ 10 s; a media watchdog ends any call without verified media 80 s after
  it started; the connect timeout is 10 s when no relay was offered (20 s with TURN); the socket down
  15 s during setup ends the call; the accept wait shows "Can't connect the call".
- Hang up / Cancel always works: after 2 s waiting for the machine it ends the call anyway, and it
  clears a screen with no call behind it.
- Process death: the current call is persisted (SharedPreferences). The next process start stops the
  call service and notification, gives this process's audio mode back (`MODE_NORMAL`), and sends the
  `call_end` the dead process owed (`cancelled` for a ringing caller, `failed` once answered, nothing
  for a ringing callee).
- A callee whose ring ran out and got no `call_end` 10 s later writes a local "Missed voice call"
  (one line per call id; the durable `call_end` is deduplicated against it) and notifies.
- Answer on a locked phone without the mic permission: dismiss the keyguard first, then ask.
- Server: one info line per `call:signal` (call id, user/device ids, ring flag, outcome), per ring
  (push now / live counts), per fallback push and per call push result; never ciphertext or tokens.
  Nothing call-related persists server-side beyond the existing TTLs (60/120 s rows, 45-s ring state,
  5-min reply cache), so no new expiry is needed.
