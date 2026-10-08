# Review: reinstalls without reset v1.21, crypto side

**Reviewer:** crypto (root acting as reviewer), 2026-10-08.
**Proposal:** `contract/proposals/2026-10-08-rejoin-without-reset-v1.21.md`.
**Verdict:** accept. No core change.

## What I checked
The §12.4a staged-commit policy in `risime-mls` (admin set from `group_meta.admins` by user id,
"adds only for users with a leaf at the base epoch", H1), `group_policy_cases.json` v2, the DM
path (no core membership policy, §10.6.2.3).

## Findings
- **K1. The only-admin re-add is already allowed by the core.** The new device's user holds a leaf
  (the superseded one), so a member's add passes "user already has a leaf"; nothing in the core
  requires an admin for that. The reset was a client decision, not a core constraint. No fixture
  change needed: the existing case "member adds an admin's new device" (A is the only admin)
  already accepts it, in the core and on the server. **Required (done at merge):** two cleanup
  cases in `group_policy_cases.json` (still `"v": 2`): the only admin's new device removes its own
  old leaf → accept; a member removes the only admin's old leaf → reject. Both suites
  (`policy_test.exs`, `tests/group_policy.rs`) pass with them.
- **K2. Admin follows the user** in the core already (`admins` are user ids; the leaf credential is
  `<user_id>/<device_id>`). A new leaf of an admin user may commit admin ops at once. Correct, and
  it is what the spec now says.
- **K3. Cleanup removals.** Own-user removal (any device of U removes U's other leaves) and admin
  removal are already allowed; members can't (H1). Unchanged; good.
- **K4. Removing stale leaves is a security improvement.** A leaf whose device is gone still holds
  keys for every new epoch until removed; if the "reinstall" was really a stolen phone, removal
  ends its access to new messages (PCS). The 24 h floor is a usability trade, acceptable.
- **K5. Rejected alternative is right.** "Members may re-add users listed in `admins`" would turn
  a stale `admins` entry (admins aren't removed from `group_meta` when the user is removed) into a
  way for any member to re-add a removed ex-admin in core terms. Not without a core change that
  also cleans `admins` on removal.
- **K6. Trust.** The server already decides who is named and can always force or refuse a reset by
  withholding commits; `exhausted` and the guard add no new power.
