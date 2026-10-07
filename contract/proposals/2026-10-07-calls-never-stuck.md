# Proposal 2026-10-07: calls never get stuck (§16.4, §16.5, §16.6, §16.9 clarifications)

From: android + server (P0, decision 054). Status: implemented in the app behind the existing
v1.13 wire format; no wire change. Root: please fold into PROTOCOL.md §16.

1. **§16.5 busy (android R9):** "In another call" = this device's own call is ringing out, connecting
   or active, **or `AudioManager.mode == MODE_IN_CALL` (a cellular call)**. `MODE_IN_COMMUNICATION`
   alone no longer counts (a stale mode or a leaked Telecom call made every call busy in nightly.16).
2. **§16.4 accept wait:** without a sibling's answer the callee shows "Can't connect the call"
   (was "Call ended"); still no `call_end`.
3. **§16.4 timers:** the caller's ring timeout starts when the call starts (the offer goes out ≤ 3 s
   later); a `call:signal` without a result in 10 s counts as unavailable; the connect timeout is
   10 s when no TURN server was offered (20 s with one); new: a media watchdog ends any call without
   verified media 80 s after it started; the socket down 15 s during setup → "Can't connect the call".
4. **§16.6 the caller died mid-ring:** a callee that rang and gets no `call_end` within 10 s after its
   ring validity writes a local "Missed voice call" line (one per `call_id`; a later `call_end` is
   deduplicated) and notifies.
5. **§16.9 process death:** the next process start stops the call service and notification, resets
   the process's own audio mode to `MODE_NORMAL`, and sends the `call_end` the dead process owed
   (`cancelled` while ringing out, `failed` after an answer, nothing for a ringing callee). This is
   the one place the app calls `setMode` (it only clears its own request).
6. **§16.9 Telecom:** one core-telecom call per `call_id`; every callback is bound to its call id.
