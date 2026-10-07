# Review: any member restores an existing member's devices, server side

**Reviewer:** server (root acting as reviewer), 2026-10-07.
**Proposal:** `contract/proposals/2026-10-07-member-readd.md`.
**Verdict:** accept with the required changes below (all applied in the proposal, §9).

## What I checked
- `groups/ops.ex`: `online_candidates/2` = the affected user's other in-group devices, then admin
  devices, online only, by recency; `assign(_, nil)` leaves the op waiting with no push;
  `device_joined/2` names a joining device for every waiting op it is `authorised?` for;
  `authorised?/3` for `devices` = in-group and (admin or affected user).
- `groups.ex` `device_changed/3`: a reinstall yields `devices` `added: [new]` (no removal; the dead
  leaf stays until the 60-day prune); `:replaced` and `rejoin` yield remove+add of one device.
- `groups/policy.ex`: pure, mirrors the core, no leaf input.
- `push.ex`: per-user dispatch for stored events; a per-token send exists.

## Required changes
- **S1. Gate.** Add `member_devices` to `@known_capabilities`. The member path is open for a group
  only while every in-group leaf that isn't superseded and isn't changed by the op advertises it.
  Evaluate it in both `online_candidates` and `authorised?`.
- **S2. Naming.** Append in-group devices of other active members (no agents, no superseded
  devices, none the op changes) after the admins, by recency then leaf index; only for
  member-committable ops (every added user already has a leaf; removals only paired with an add
  of the same user).
- **S3. Authorisation.** A non-admin commit touching another user's leaves must carry `op_id`
  and match the op's `added`/`removed` exactly; `Policy.check` gains the base leaf set and the
  C1/C2 rules so the shared fixture runs unchanged on both sides.
- **S4. Wake push.** When `assign(_, nil)` happens for a `devices` op, schedule an Oban `wake` job:
  the §8.2 payload to each of the first 10 candidates' own tokens, skipping live inbox channels,
  repeated every 6 h while waiting, at most 4 per device per day. Log `group_op_wake` with counts,
  no phone numbers.
- **S5. Recovery.** `RisiMe.Release.rename_waiting_ops/0` (idempotent, `name_next` for every
  waiting `devices` op under the group lock), run once after migrate; a device `PUT` that newly
  adds `member_devices` re-runs it for that user's groups.

## Answers
- *Does `group_op` leak anything new to a member?* It names another user's device ids, which the
  member already sees as MLS leaves. Acceptable.
- *Candidate list size?* Up to 768 devices; `tried` cycling is unchanged and fine.
- *Cassandra?* Untouched; ops live in Postgres.
- *Verification* (proposal §6): counts of waiting `devices` ops before and after, and no new
  rejoin ops caused by policy rejections.
