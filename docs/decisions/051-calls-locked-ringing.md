# 051 — Calls: a locked app rings without the caller's name (option B)

**Status:** accepted 2026-10-06 (root), at the v1.13 contract merge. Contract: PROTOCOL §16.8;
android review R1 (`contract/proposals/reviews/2026-10-06-calls-v1.13-android.md`).

## Context
1:1 voice calls (PROTOCOL §16, v1.13) ring through a content-free high-priority FCM push
`{"type":"call","v":"1"}`. The app then has to sync, decrypt the MLS `call_offer` and look up the
caller. But on Android the session is fingerprint-locked (decision 014): the refresh token is sealed
by a Keystore key that needs user authentication on every use. After process death (Doze,
swipe-away, OEM battery managers) or while locked there is no access token, so no socket, no sync
and no MLS. The app can't learn who is calling, and it can't fetch TURN credentials. Messages
already live with this ("Open RisiMe to read them"), but a call 45 s later is useless.

The android review offered:
- **A.** A device-bound, narrowly scoped background token (inbox join/sync, `call:signal`,
  `GET /calls/turn`), sealed by a Keystore key without user authentication, so a locked app can
  sync and ring with the name. It weakens decision 014 for those scopes and needs a server change.
- **B.** Ring blind: a full-screen incoming-call UI with no name; unlock, sync, then answer.
- **C.** Don't ring; show the missed call after the next unlock.

## Decision
**Option B.** The fingerprint lock stays absolute.
- When the app process is dead or the session is locked and a `call` push arrives, the app starts
  its `phoneCall` foreground service and rings at once with a full-screen **"Incoming RisiMe
  call"** and **no caller name** (on the `calls` channel, Answer and Decline).
- **Answer** asks for the fingerprint. After unlocking, the app syncs, decrypts, shows the caller
  and **only then answers**, if the offer passes the §16.3 checks (fresh, bound, not answered
  elsewhere, not cancelled). Otherwise it shows "Call ended" or "Answered on another device" and
  stops, sending nothing.
- **Decline** while locked stops the local ring only; the caller times out and the named missed-call
  line arrives with the next unlocked sync.
- The blind ring stops after 45 s, or as soon as an unlocked sync finds nothing to ring for. It
  leaves no notification of its own.
- **Option A is recorded as a possible later step** (also useful for message notifications). It
  needs its own decision because it changes what decision 014 protects.

## Consequences
- Calls reach a locked or killed phone, and nothing about the caller is shown or stored before
  the user unlocks. The push stays content-free (Google learns only "a call attempt at T", §16.8).
- Costs, accepted for the pilot: false rings for calls that already ended or were answered on
  another device (FCM can't know), and a slower answer (fingerprint + sync, a few seconds).
- An unlocked but screen-locked session (a known caller) can still be answered from the lock
  screen without unlocking the device, as for phone calls (§16.9).
- If the pilot shows many false rings or slow answers, revisit with option A.
