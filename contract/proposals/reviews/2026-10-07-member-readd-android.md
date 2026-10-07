# Review: any member restores an existing member's devices, android side

**Reviewer:** android (root acting as reviewer), 2026-10-07.
**Proposal:** `contract/proposals/2026-10-07-member-readd.md`.
**Verdict:** accept with the required changes below (all applied in the proposal, §9).

## What I checked
- `GroupStore.applyOp`: queues any `group_op` whose `committer` is this device; no role check.
- `GroupOps.commitServerOp` `DEVICES`: claims with `conversation_id` (§12.5 allows co-members),
  filters to the op's `added`, removes present `removed`, `changeGroupMembers`. Role-agnostic, so
  a non-admin named by the server already executes it, but the bundled core's
  `check_own_policy` refuses it today.
- `InboxSyncWorker` → `syncAndNotify()`; `Notifier.postLocked()` ("New messages / Open RisiMe to
  read them") when fingerprint-locked.

## Required changes
- **A1. Advertise only with the new core.** Add `member_devices` to the `PUT /me/devices/{id}`
  capabilities only in the build whose core has the C1/C2 rule; re-advertise on change (existing
  behaviour).
- **A2. Errors stay silent.** A background server op that fails with `not_admin` or `bad_request`
  must re-fetch `GET /groups/{id}` and drop or retry; it must never show
  `groupOpErrorText` ("Only group admins can do that") to a member who didn't ask for anything.
- **A3. Background commit.** `syncAndNotify()` must run `GroupOpsExecutor.runDue()` after the
  sync, inside the worker, so a woken unlocked phone commits before the worker ends.
- **A4. Tests.** `GroupOpsExecutorTest`: a non-admin named for another user's `devices` op
  commits; a non-admin named for an op adding a new user refuses locally and retries later; the
  Android contract test parses the new example.

## UX
- No new screen or line: device-only changes emit no `group_event` (§12.7). The restored member
  just sees the group return through the Welcome.
- Locked phones keep the content-free "Open RisiMe" notification; recommended (not required)
  neutral wording, since the same push may now only mean "a group needs this phone".

## Answers
- *Does a non-admin need any new UI permission?* No; the executor acts only on server namings.
- *Battery?* At most 4 wake pushes per device per day per waiting op, only while nobody online
  can commit.
