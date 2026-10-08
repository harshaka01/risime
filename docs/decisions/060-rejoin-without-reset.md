# 060 — Reinstalls rejoin; reset is the last resort; stale leaves are removed
**Status:** accepted 2026-10-08 (root), contract v1.21 §12.12. P0-2 (disappearing chats).

## Context
The P0-2 investigation found that a reinstalled **only admin** reset its groups (the client still
believed only admins may re-add another user's device, which v1.14 §12.4a lifted), that DMs reset
2 minutes after a reinstall whenever the peer was offline, that `devices` ops for phones that were
reinstalled again were renamed up to 313 times, and that superseded leaves were never removed.

## Decision
- **Admin is a user role.** A reinstalled admin's new device is re-added by any member (§12.4a) and
  is an admin device once in. No core change.
- **Every device rejoins.** Automatic reset only when nobody can re-add the device (DM
  `candidates: 0`) or everyone who could was asked and failed (`exhausted`); for a group admin
  also after 24 h with no candidate. Non-admins never reset. Admins keep a confirmed manual reset.
- **Server guard** `409 rejoin_pending` on reset while the device waits for a viable re-add. It
  protects against shipped apps (≤ v1.20) immediately, without waiting for the app update.
- **Naming budget:** 3 timed-out namings per device per op, cleared by an update, a widened op or
  24 h; all out of budget = `exhausted` (no namings or wakes, the op stays). DM ops get wake pushes.
- **Pruning:** ops drop devices that can no longer receive (including superseded) at every naming.
- **Stale leaves** (superseded, unseen 24 h, the user keeps a live leaf) are removed by the user's
  own device or an admin via cleanup ops from an hourly sweep; never by a member (H1 stays). 24 h
  (`STALE_LEAF_HOURS`) avoids churning a second phone that was briefly offline.

## Consequences
- A reinstalled device may wait (hours, if every candidate is offline) instead of resetting; its
  sends stay pending, and nothing is lost for the other members.
- An only admin whose last leaf was removed while another admin existed (rare) still needs the
  last-resort reset. "Members may re-add users listed in `admins`" was rejected (stale `admins`
  entries after an admin's removal); revisit with a core change that cleans `admins` on removal.
- One-off: `RisiMe.Release.stale_device_ops/1` after the server deploy (dry run first).
