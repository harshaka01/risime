# 042 — History after a reinstall (sender copy + history gap marker)

**Status:** proposed 2026-10-06 (root). Contract proposal:
`contract/proposals/2026-10-06-history-v1.10.md` (v1.10), awaiting the server and android reviews.

## Context
Harsha reinstalled the app. A chat then showed only the other person's messages; his own were
missing.
- **Replay, not "unacked".** A fresh install joins with `since: null`. The server replays every
  inbox event still inside the 30-day Cassandra TTL, acked or not. Acks only move status; they
  never delete events.
- **Plaintext DMs** are written only to the recipient's inbox
  (`server/lib/risime/messaging.ex:327-332`). Received plaintext therefore comes back. Sent
  plaintext existed only in the old install's Room database, which is excluded from backup
  (`allowBackup="false"`, §10.4), so it is lost.
- **E2EE DMs and groups** already put the same ciphertext in the sender's inbox for the sender's
  other devices (§10.3, §12.7). But a reinstall is a new `device_id`, so it is a new MLS leaf that
  joins later and holds no keys for earlier epochs. By design it can't decrypt older messages,
  sent or received (decision 032: no history for new members or devices).
  - The client parks these events (no group yet), then drops them after the Welcome, and only
    logs it (`MlsPipeline.kt:132-134`, `151-153`). The user sees no gap and no hint.
- So the received messages that came back were **plaintext**: sent before E2EE (nightly.8), or
  in a chat that hadn't upgraded. Any E2EE messages in that chat, both sides, were dropped
  silently.

## Decision
1. **Every stored `message`/`reaction` event goes to every member user, the sender included.**
   - Plaintext DMs gain the sender copy that e2ee DMs, reactions and groups already have.
   - It uses the same `event_id` and the same TTL, and it is never pushed.
   - Own copies are outgoing. They are never acked, and they get their ticks only from the stored
     `status`/`group_receipt` events.
   - Old apps already handle the copy correctly.
   - An optional one-off backfill copies plaintext DMs still inside the TTL.
2. **No silent half-history.**
   - The server returns `history_before` (when this `device_id` was first seen) on join/sync
     replies.
   - A client treats an e2ee message as pre-install when its `server_ts` is before that time, or
     its epoch is below the epoch the device joined at. Such a message is not parked and not
     decrypted. Instead the chat shows **one** system line, "Earlier messages are on your
     previous install" (a deterministic id per conversation, placed after the latest pre-install
     message).
3. **E2EE history is not restored by the server, by design.** The server keeps storing only
   ciphertext for E2EE chats (§10.0). Restoring it needs a client-encrypted backup/restore. That
   is the next step towards multi-device, in its own proposal with a crypto review.

## Tester guidance (until encrypted backup ships)
- **Never uninstall the app, and never "Clear storage".** Update in place (the in-app updater,
  or `scripts/install-apk`, which uses `adb install -r`). Both keep your history and keys.
- A reinstall or a new phone keeps plaintext chats (both sides once v1.10 is live, received only
  before that) for 30 days. It **loses the history of encrypted chats**, which then shows the
  "Earlier messages are on your previous install" line.
- If you must reinstall, expect that loss. The old install also stays listed as a device until
  it's removed or pruned after 60 days.

## Consequences
- One extra Cassandra write per plaintext DM; no extra push.
- `app_instances` gains `first_seen_at`.
- The Android marker reuses the group system-line row (`KIND_SYSTEM`).
- The dev banner rules (§10.4) are unchanged.
