# Review: any member restores an existing member's devices, crypto side

**Reviewer:** crypto (root acting as reviewer), 2026-10-07.
**Proposal:** `contract/proposals/2026-10-07-member-readd.md`.
**Verdict:** accept with the required changes below (all applied in the proposal, §9).

## What I checked
- `crypto/risime-mls/src/policy.rs` `check_commit_policy`: a non-admin may touch only leaves of
  its own user; it sees `committer_user`, `add_users`, `remove_users`, `meta`, and **no leaf set**.
- `group.rs`: the policy runs on our own commits (`check_own_policy`, before building) and on
  every staged peer commit (`check_staged_policy`, before merge), against `group_meta.admins` of
  the base epoch. A rejected staged commit is unrecoverable state (§12.8) → rejoin.
- `attestation.rs`: every leaf's `application_id` carries the server attestation JWS, verified
  offline against pinned keys; Kotlin never decides trust.

## Required changes
- **C1. Phrase "existing member" over leaves.** The core doesn't know server membership. The rule
  is: a non-admin may add leaves only of users with at least one leaf in the base epoch.
  `CommitSummary` gains the base epoch's leaf users. Consequence: a member with no leaf left is
  admin-only (the server must not name members for such an op).
- **C2. Removals are replacement-only.** The core can't know "dead" or "superseded" (server
  facts). Allowing "remove another user's leaf if they keep one" would let a malicious member,
  with a lying or buggy server, evict a member's live phone. Allow a non-admin to remove another
  user's leaf only when the same commit adds a leaf of that same user (rejoin, re-key, swap).
- **C3. Rollout gate.** An old core rejects a member's commit for another user's leaf by the old
  rule and every such device falls into rejoin (a storm). The server must name/accept members only
  when every non-superseded in-group leaf runs a core with the new rule → capability
  `member_devices`.
- **C4. Attestation of the added key package, committer side.** Receivers already verify the
  attestation of every added leaf. The committer's core must also verify, before building, that
  each claimed key package's attestation is valid and that its credential `user_id/device_id`
  equals the op's `added` entry (a test: a member's commit with a key package attested for a
  different user is refused locally).
- **C5. Agents.** A non-admin never adds or removes agent leaves (`agents` list), matching "agents'
  keys never live on the chat server".
- **C6. Fixture.** The existing case "member adds another user's device" (C already has `c1`)
  becomes accept; rename it and add the new cases (proposal §3). Both suites must run them.

## Answers
- *Does this weaken forward secrecy or PCS?* No. The added leaf is a fresh, attested key package
  of a user who already reads the group; the commit advances the epoch as any other.
- *Can the committer read more than before?* No: it was already a member.
- *Does the server gain power?* No. It could already attest a ghost device and have an admin's
  device (or the user's own other device) auto-commit it. The defence is unchanged: visible device
  lists, later key transparency.
- *Where is "dead device" handled?* Server only (op contents). The core treats all leaves alike.
