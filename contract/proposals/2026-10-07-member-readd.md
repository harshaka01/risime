# Proposal 2026-10-07: any member restores an existing member's devices (groups after reinstall)

**Status: proposed, reviewed** (crypto, server, android: `reviews/2026-10-07-member-readd-*.md`;
their required changes are applied below, see §9). Additive change to PROTOCOL §12.1, §12.4,
§12.7 and `contract/v1/group_policy_cases.json`; the contract version is decided at merge.

From: root, 2026-10-07. Follows the open point in `2026-10-07-readiness-superseded-devices.md` §3.

## 1. Problem (pilot)
A reinstalled phone gets a new `device_id`. The server creates a `devices` op (`added: [new]`) in
each of the user's groups (§12.4). Today only the affected user's **other** in-group devices or an
**admin** device may commit it, and only an online one is named. A tester with one phone has no
other in-group device, so the op waits until an admin opens the app. On the pilot two groups have
waited for hours, and the reinstalled testers see neither the group nor its new messages.

## 2. The rule (normative, replaces the `devices` parts of §12.4)
**Any active member's in-group device may commit a `devices` op for an existing active member's
devices**, within these limits:
- **Adds:** the op adds devices of a user who is an **active member** and **already has at least
  one leaf** in the group at the epoch the commit is built from. (A member whose leaves are all
  gone, e.g. after a reset with no device, can only be re-added by an admin, because the MLS core
  sees leaves, not server membership.)
- **Removes:** a non-admin may remove another user's leaf **only in the same commit that adds a
  leaf of that user** (a replacement: a `rejoin`, a re-keyed device, or a dead device swapped for
  its successor). A standalone removal of another user's leaf, dead or not, stays with that user's
  own devices or an admin: the core can't tell a dead device from a live one, and removing a live
  device is a denial of service on its owner.
- **Never by a non-admin:** adding a user who has no leaf (a new user), removing a user's last
  leaf, any `role`/`add`/`remove`/`rebuild` op, any `group_meta` change. Those stay admin-only.
- **Agents:** a non-admin never adds or removes an agent's leaves, and an agent device is never
  named for another user's op.
- **Rollout gate (capability `member_devices`).** A v-next app advertises it in
  `PUT /me/devices/{id}` `"mls": {"capabilities": [..., "member_devices"]}` only when its MLS core
  enforces this rule. The member path is **open for a group** only while every in-group leaf that
  is not superseded (§12.1) and not being changed by the op advertises `member_devices`. While it
  is closed, the server neither names nor accepts a non-admin for another user's leaves (an old
  core would reject such a commit and fall into rejoin, §12.8). A superseded leaf that later comes
  back on an old app rejects the commit and rejoins; that is accepted.

### 2.1 Committer naming (replaces §12.4 "Candidates, in order")
For a `devices` op, the candidates are, online only, minus the devices the op changes:
1. the affected user's other in-group devices, most recently seen first;
2. in-group **admin** devices, most recently seen first;
3. if the member path is open (§2) and the op is member-committable: in-group devices of the
   **other active members** (never agents, never superseded), most recently seen first, ties by
   leaf index.

`add`/`remove`/`role`/`rebuild` keep today's admin-only list. The 60 s window, `tried` cycling and
"the first authorised device whose inbox joins" rule are unchanged; the last now includes member
devices for member-committable ops.

### 2.2 Server authorisation (replaces the `devices` bullets of §12.4)
- `devices` ops may be completed by: the affected user's own in-group device, any admin device,
  or, when member-committable and the gate is open, any active member's in-group device.
- A non-admin commit that touches another user's leaves **must carry `op_id`** of a pending
  `devices` op, and its `added` must equal the op's `added` and its `removed` the op's `removed`
  (the declared lists are matched against the op; no list-only matching for this case).
- Otherwise unchanged: `403 not_admin` for anything else touching another user, `bad_request`
  for lists that don't match the op, plus the §10.2 checks (member device, each added device
  current and groups-capable, Welcome present exactly when `added` isn't empty, caps, rate limit).

### 2.3 The MLS core (replaces the last bullet of §12.4)
The core applies the same rule from `group_meta.admins` and the leaf set of the commit's base
epoch: a non-admin commit may add leaves only of users who already have a leaf (and are not
agents), may remove another user's leaf only when the same commit adds a leaf of that user, and
may not change `group_meta`. The server-only parts are the pending-op matching, the gate, liveness
(superseded/current), and the caps.

## 3. Policy fixture changes (`contract/v1/group_policy_cases.json`)
Same base for every case: admins `[A]`, agents `[R]`, leaves `A:[a1] B:[b1,b2] C:[c1] R:[r1]`,
committer `B/b1` (a member) unless stated. The `about` text gains "leaves = the base epoch's leaf
set; a non-admin may add leaves of users already in `leaves` and replace another user's leaf".

| Case | adds | removes | meta | expect |
|---|---|---|---|---|
| **changed:** "member adds an existing member's new device" (was "member adds another user's device", reject) | `C/c2` | — | null | **accept** |
| new: "member adds a new user's device" | `D/d1` | — | null | reject |
| new: "member replaces an existing member's device (rejoin/swap)" | `C/c2` | `C/c1` | null | accept |
| new: "member removes another user's device without a replacement" (leaves `C:[c1,c2]`) | — | `C/c1` | null | reject |
| new: "member replaces one user's device with another user's" | `C/c2` | `A/a1` | null | reject |
| new: "member adds an existing member's device and renames" | `C/c2` | — | name | reject |
| new: "member adds an agent's device" | `R/r2` | — | null | reject |
| new: "member adds own and an existing member's devices" | `B/b3`, `C/c2` | — | null | accept |
| kept: "member removes another user" | — | `C/c1` | null | reject |
| kept: "member mixes own add with another user's removal" | `B/b3` | `C/c1` | null | reject |

"Dead or superseded" is not a fixture input: it is a server-only fact, and the core can't verify
it, so the fixture treats every leaf alike (see §2 Removes). The one existing case that flips is
the reason the fixture change is not purely additive.

## 4. Wake-up when nobody can commit
When a `devices` op has no online candidate (the server sets no committer), the server sends the
§8.2 push `{"type":"inbox","v":"1"}` (data-only, high priority, no content) **to each candidate
device's own push token**, in §2.1 order, at most the first 10 candidates, skipping devices with a
live inbox channel. It repeats every **6 h** while the op still waits, at most 4 times per device
per day. When a woken device syncs, it joins its inbox channel, is named (§12.4 join rule), and
commits.

**Honest limits.**
- A fingerprint-locked app can't sync or commit in the background (no bearer, no MLS keys). It
  shows the usual content-free "Open RisiMe" notification, and the op is committed the next time
  someone opens the app. The push brings the op to a person's attention; it does not guarantee a
  background repair.
- Android throttles high-priority FCM for apps whose pushes show no notification, and Doze can
  defer work; a wake may arrive late or not at all. Any member opening the app still fixes it.
- No new push type and no content: the push can't say which group or why.

## 5. Safety and trust
**Can a malicious member add a device for another member?** Only one the server attests as that
member's:
- every added leaf carries the server's attestation JWS (`aud risime-mls`, `user_id`, `device_id`,
  `signature_key`) in its `application_id`, checked by every receiving core against the pinned
  attestation keys (§10.0/§10.1); a key package the member made up fails it;
- key packages come only from the §12.5 claim, which returns the registered devices of active
  members;
- the server accepts the commit only if `added` equals a pending `devices` op that the server
  itself created for that user (§2.2);
- the core refuses adds for users with no leaf, so a member can't bring in a new user, and refuses
  standalone removals of others' leaves, so a member can't evict a member's live phone.
The added device belongs to someone who already reads the group, so no reader is added who
wasn't entitled before.

**Limits.** The server can lie about a user's devices: it can attest a device it controls as
"Kamal's" and have it added. This is **the same trust as today**, where the server can name an
admin device that auto-commits a `devices` op without any human check, or name the user's own
other device. The change widens who commits, not what the server can make happen. The defence
remains the same for both paths: device lists visible in the app and, later, key transparency /
safety numbers. A member could also claim a co-member's key packages to drain them; that is
already possible under §12.5 and is rate-limited.

## 6. One-off pilot recovery
After the server deploy **and** the testers' apps advertise `member_devices`:
- a release task `RisiMe.Release.rename_waiting_ops/0` (run once by `scripts/run-server` after
  migrate, idempotent) calls `name_next` for every pending `devices` op with no committer; a
  `PUT /me/devices/{id}` that newly adds `member_devices` re-runs it for that user's groups (the
  gate may have just opened);
- any member device that joins its inbox afterwards is named for the waiting ops it may now
  commit (the existing join rule), and the wake pushes (§4) go out for the rest.

**Verify (read-only, on the pilot DB):**
```sql
-- before deploy and again after (expect waiting → 0 within hours of members opening the app)
select count(*) filter (where committer_device is null) as waiting, count(*) as total,
       min(created_at) as oldest
from group_ops where type = 'devices';
select group_id, count(*) from group_ops where type = 'devices' group by 1;
-- no collateral rejoins: `devices` ops created by rejoin after deploy (expect 0 new)
select count(*) from group_ops where type = 'devices' and created_at > '<deploy time>'
  and payload->'removed' = payload->'added';
```
Plus: each reinstalled tester's new device appears in `mls_group_devices` for those groups, and
the server log shows the accepted commits with `committer_role=member`. Record the before/after
counts in `docs/status/server.md`.

## 7. Wire summary
- New capability string `member_devices` in `mls.capabilities` (§12.1); unknown to old servers,
  which ignore it.
- `PendingOp.committer` may name a non-admin device for another user's `devices` op; the
  `group_op` event and its payload are unchanged.
- No new events, fields or errors. New example: `device_put_member_devices.json`.

## 8. Out of scope (follow-ups)
- Removing superseded leaves together with the new device's add (the 2026-10-07 superseded rule
  says a superseded device counts again when seen; a removed second phone would need an automatic
  re-add first).
- Re-adding a member who has no leaf without an admin (needs the member list in `group_meta`).

## 9. Review changes applied
- crypto C1–C2: the rule is phrased over the base epoch's leaves; removals by non-admins are
  replacement-only; standalone removal of another user's leaf is refused even if "dead".
- crypto C3 / server S1: rollout gate `member_devices` over every non-superseded leaf.
- crypto C4: the committer's core verifies each claimed key package's attestation and that its
  credential user is the op's user before building (§5).
- crypto C5: agents excluded both ways.
- server S2–S3: naming order with exclusions; `op_id` required for a non-admin's commit on
  another user's leaves, exact list match.
- server S4: wake push per device token, 6 h cadence, cap 10 candidates.
- server S5: the recovery task and the PUT re-evaluation (§6).
- android A1–A3: the app advertises `member_devices` only with the new core; background ops never
  show "Only group admins can do that"; the push-woken sync worker runs due group ops (§4).
- android A4: executor tests for a non-admin named committer.
