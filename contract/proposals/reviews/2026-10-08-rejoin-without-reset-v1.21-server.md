# Review: reinstalls without reset v1.21, server side

**Reviewer:** server (root acting as reviewer), 2026-10-08.
**Proposal:** `contract/proposals/2026-10-08-rejoin-without-reset-v1.21.md`.
**Verdict:** accept with the changes below (all folded into §12.12).

## What I checked
`RisiMe.Groups.Ops` (`name_next`, `assign`, `device_joined`, `wake`, `forget_device`,
`settle_devices`), `RisiMe.Groups.device_changed/3`, `RisiMe.MLS.DmOps`, the reset handlers.

## Findings and required changes
- **S1. The renaming loop is real.** `device_joined/2` names the joining device for every waiting op
  it is authorised for, the 60 s pass, `name_next` finds nobody online and parks the op, and the
  next connect names the same device again. A device that can't commit (no key package for the
  target, an old app) is named at every connect. Required: strikes per (op, device) stored on the
  op (`strikes jsonb {device_id: [n, last_at]}`), checked in `name_next`, `device_joined` and
  `wake`. Cleared on a widened op and on `PUT /me/devices` with changed capabilities or a new
  `app_version` on connect. **Folded in (§12.12.4).**
- **S2. `forget_device` prunes only §10.1 removals.** A device superseded by a second reinstall stays
  in `added` forever. Required: prune by the §12.1 "can still receive" predicate (the DM code has
  it: `DmOps.receiving_devices/1`) at every naming and commit. **Folded in (§12.12.5).**
- **S3. Guard placement.** The `rejoin_pending` check must run inside the group lock, after
  `not_admin`/`not_found`/`bad_request` (so it doesn't reveal ops to non-members) and before
  `generation_conflict` (an old app retrying a reset with a stale generation should still learn it
  must wait). **Folded in.**
- **S4. `exhausted` must not delete the op.** Otherwise the device's `candidates`/`exhausted` reply
  flips to `op: null` and the client can't tell "done" from "given up". Keep it pending, no
  namings, no wakes. **Folded in.**
- **S5. Cleanup ops and the member tier.** `member_committable?/3` is already false for a removal
  without a re-add; still, say "never tier 3" in the text so nobody "fixes" it. Cleanup ops get no
  wake pushes (they aren't urgent and would spend the 4/day budget). **Folded in.**
- **S6. Sweep cost.** Hourly over `mls_group_devices` ⨝ `devices` ⨝ `app_instances`: at pilot scale
  (hundreds of leaves) trivial; it reuses `MLS.superseded_devices/1`. No Cassandra.
- **S7. Old apps.** An app ≤ v1.20 that picks `RESET` gets `409 rejoin_pending`; its op was
  already created by its key-package upload (`device_changed :added`), so a member re-adds it. Its
  failed reset op must not loop: the reset rate limit (1/h groups, 3/h DMs) bounds it. Acceptable.

## Questions answered
- *Why count candidates online or not?* The client must distinguish "nobody can ever" (reset now,
  DMs) from "nobody is online" (wait). Online state changes by the second; the count doesn't.
