# Proposal: add `bad_request` as an error reason for channel pushes

**Status:** proposed (root, 2026-10-05). Needs Harsha's OK before it is merged into `contract/v1/PROTOCOL.md`.

## Problem
PROTOCOL.md §2.2 lists these `msg:send` error reasons: `unknown_recipient | empty_body | too_long | rate_limited`.
It doesn't say what happens when a payload is malformed: a `client_msg_id` that isn't a UUID, a
missing field, an unknown `msg:ack` status, a `since` that isn't a TimeUUID, or an unknown event.

## What both sides already do
- **Server:** replies `{"reason": "bad_request"}` to malformed `msg:send`, `msg:ack` and `sync`
  pushes and to unknown events. A join with a bad `since` is refused with `bad_request`.
- **Android:** treats `bad_request` as a permanent send failure (local `FAILED` state, not retried).

## Proposed change (additive, v1-compatible)
In §2.2:
- add `"bad_request"` to the `msg:send` reply-error reasons;
- state that `msg:ack` and `sync` may reply `{"reason": "bad_request"}`;
- in §2.1, state that a join whose `since` isn't a TimeUUID is refused with `{"reason": "bad_request"}`.

Clients must treat unknown reasons as permanent failures. No example file changes.
