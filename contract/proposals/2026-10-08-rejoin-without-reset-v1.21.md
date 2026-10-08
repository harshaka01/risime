# Proposal: reinstalls without reset, and stale leaves (contract v1.21)
Date: 2026-10-08. Author: root session (P0-2, disappearing chats). Decision 060.
**Status: merged** into PROTOCOL §12.12 as v1.21 (2026-10-08), with the review changes below.

## Problem (P0-2 findings 3 and 4)
1. **The only admin resets instead of rejoining.** Android `GroupStore.rejoinPlan` returns `RESET`
   when the reinstalled user is the group's only admin, because "only admins may re-add another
   user's device". That was true in v1.9 and stopped being true in v1.14 (§12.4a: any member may
   re-add an existing member's device). `grp:c442` was reset at both of Harsha's reinstalls today;
   every member got the reset line and lost parked old-generation events, and open history
   requests were closed.
2. **DMs reset after 120 s** whenever the reinstalled side isn't re-added within 2 minutes, i.e.
   whenever the peer is offline. Waiting costs nothing (the peer can't read anything until it's
   online, and it commits the add first), but a reset closes history requests and shows a reset.
3. **Ops renamed forever.** 7 DM/group `devices` ops for reinstalled users' new devices had no
   committer and were re-assigned 18–313 times: the inbox-join rule re-names the same device at
   every connect, and nothing removes an op whose `added` device was itself superseded by a later
   reinstall (only a §10.1 removal pruned `added`).
4. **Stale leaves stay.** Group `devices` ops never remove superseded leaves; DMs remove them only
   inside an add op that may never complete.

## Proposal (normative text: PROTOCOL.md §12.12)
1. **Admin is a user role** (clarification, no change): the new device of an admin user is an
   admin device once added; the §12.4a member path re-adds it like anyone's.
2. **Every device rejoins.** Reset only as a last resort: DM when the rejoin reply has
   `candidates: 0` or `exhausted: true`; group admin when `exhausted`, or no candidate for 24 h;
   non-admins never. Manual "Reset encryption" for admins, confirmed.
3. **Rejoin replies** gain `op`, `candidates`, `exhausted` (groups) and `exhausted` (DMs).
4. **Server guard** `409 rejoin_pending` on `reset` while the calling device has a pending,
   non-exhausted add op with a candidate (groups: or younger than 24 h). This fixes shipped apps
   too, without waiting for the update.
5. **Naming budget:** 3 timed-out namings per device per op, cleared by a widened op, an app
   update/capability change, or 24 h. All candidates out of budget = `exhausted` (no namings, no
   wakes; the op stays). Replaces the DM 6-naming cap. DM ops get the §12.4a wake pushes.
6. **Pruning:** `devices` ops drop devices that can no longer receive (incl. superseded) at every
   naming and commit; empty ops are done.
7. **Stale leaves:** superseded, unseen for 24 h, user keeps a live leaf → an hourly sweep makes a
   cleanup op (groups) or widens the DM op; committed by the user's own device or an admin
   (never by members: H1 stays).
8. **Recovery task** `stale_device_ops` (dry run by default).

## Alternatives considered
- **Let any member re-add an admin user with no leaf** (core rule: users in `group_meta.admins`).
  Rejected for v1.21: `admins` isn't updated when an admin is removed (only a `role` op changes
  it), so a stale entry would let a member re-add a removed ex-admin in core terms; it needs a core
  change and a new gate. The case is rare (§12.12.1) and keeps the last-resort reset.
- **Remove superseded leaves at once** (as v1.16 DMs do). A second phone that was offline across
  the new registration would be removed and re-added with a gap. 24 h is enough for the pilot's
  accumulation problem.
- **Keep the 2-minute DM reset but only when the peer is online.** The client can't see presence of
  a non-friend device, and "online" doesn't mean "can commit" (an old app ignores `mls_dm_op`).
  `exhausted` says exactly "was asked and couldn't".
