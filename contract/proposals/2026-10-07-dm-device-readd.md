# Proposal 2026-10-07: DMs re-add a member's new device (DM self-heal, v1.16)

**Status: merged** into PROTOCOL §10.6 as v1.16 (2026-10-07).

**Status: proposed** (server + android, P0). Additive: one new event kind, one new REST call, an
optional `op_id` on the DM commit, a DM use of the existing reset path. No core (crypto) change.
From: server/android session for root, 2026-10-07.

## 1. Problem (pilot)
A reinstalled phone gets a new `device_id`. Nothing adds it to an existing **DM** group: §10.3's
`mls_membership` is a one-shot hint (the peer acts after 5–30 s, in memory, once; a missing key
package or a killed app loses it), and v1.14 §12.4a covers `grp:` only. On the pilot, Harsha's
current phone is missing from 3 of his 5 DM groups, which hold only his superseded installs and the
peer's devices. His phone has no group, so every send ends as `e2ee_not_ready`. Nothing heals it.

## 2. DM `devices` ops (server, normative)
The server keeps **at most one pending `devices` op per (DM, user)**, in the `PendingOp` shape of
§12.2 (`type: "devices"`, `actor` = the affected user, `user_ids: []`, `role: null`).
- **Created** (or widened) when, for an e2ee DM whose epoch is not null:
  1. a device uploads key packages (`POST /me/devices/{id}/key_packages`), and it is a current MLS
     device of a participant that can still receive (§12.1: registered, seen in 30 days, not
     superseded) and is not in the group;
  2. `POST /mls/groups/{dm}/rejoin` (§4) from such a device;
  3. the recovery task (§7).
- **Content:** `added` = that user's receiving MLS devices not in the group (the trigger's device
  included); `removed` = that user's in-group **superseded** devices (§12.1), plus, for a rejoin of
  a device that is still in the group (its MLS state was lost), that same device (it is then in
  both lists).
- **Kept current:** every naming and every accepted commit drops `added` devices that can no
  longer receive and `removed` devices that left the group; an op with nothing left is done.
- **Done** when every `added` device is in the group and no `removed` device is (a device in both
  lists counts as done once it was removed and is back).

### 2.1 Committer naming (reuses §12.4a)
Candidates, online only, minus the devices the op changes: the **in-group devices of either
participant** that are not superseded and advertise **`member_devices`** (§12.1); the affected
user's own devices first, then the peer's; each tier most recently seen first, ties by device id.
- Each naming sends the new stored event **`mls_dm_op`** `{"conversation_id", "generation", "op":
  PendingOp}` (`event_mls_dm_op.json`) to the named device's user, with the content-free inbox
  push (§8.2). Only the device in `op.committer` acts; other devices ignore it.
- 60 s per naming (`committer_until`), then the next untried candidate; when all were tried, the
  first again, for at most 6 namings in all. After that, or with no candidate online, the op waits
  (`committer` null) and the first candidate whose inbox joins is named. A widened op (§2) may
  name every candidate again.
- Pre-v1.16 apps that advertise `member_devices` don't know `mls_dm_op` (they ignore unknown
  kinds); their naming simply times out. Their §10.3 `mls_membership` path still adds the new
  device as before; the op then only removes the superseded leaves.

### 2.2 Commit authorisation (extends §10.2 for DMs)
The DM commit request gains an optional **`"op_id": "uuid" | null`** (`mls_commit_request_dm_op.json`).
Adds are unchanged (each a current MLS device of a member, not in the group). `removed` may now
also list a leaf of **the other participant that is still current**, only when the request
carries the `op_id` of this DM's pending op whose `removed` lists that leaf, and:
- the leaf is also in the op's `added` (the same device re-added: a rejoin), or
- the leaf is **superseded** (§12.1) **and its user keeps at least one non-superseded leaf** in the
  group after this commit (in practice: the op's new device was added first).

Anything else stays `bad_request` (as today). The DM core can't build one commit that both adds and
removes, so an op may take up to three commits, each with its `op_id`, in this order: removals of
same-device rejoins, then the adds (with the Welcome), then the superseded removals.

### 2.3 The MLS core and the v1.14 H1 rule (no core change)
The core has **no membership policy for DM groups** (`check_staged_policy` runs for `grp:` only;
every added leaf's credential and attestation are still verified, §10.1), so every shipped core
accepts these commits. H1 (a non-admin may remove another user's leaf only together with the
re-add of that same device) stays a `grp:` rule and is **not** widened for groups. Why a DM may
drop the peer's *different*, superseded device:
- H1 protects a **third party** from a member evicting their live phone. In a DM the only other
  party is the committer's own conversation partner: an eviction can only cut the committer's own
  chat, which the committer can already do (stop sending, block).
- The core can't tell a dead leaf from a live one; the **server** decides, from census facts, and
  allows the removal only for a superseded leaf, only with an op it created, and never of the
  user's last non-superseded leaf.
- A wrongly evicted live device is **self-healing**: it is current and not in the group, so it
  gets a new op (and its own client asks, §5) and is re-added. The worst case of a lying server is
  a short outage of one chat, the same trust as today (the server already names committers and
  attests devices).

## 3. DM reset (extends §12.8 to DMs)
**`POST /api/v1/mls/groups/{dm}/reset`** `{"generation": n}` with `X-Device-Id` (a current MLS
device of a participant; friends, no block) → `200 {"generation": n + 1}` (`group_reset.json`,
`group_reset_reply.json`).
- `409 generation_conflict {"generation": current}` if it already moved; `404 not_found` (not a
  participant, or not e2ee); `400 bad_request` (no `generation`, or `X-Device-Id` isn't a current
  MLS device of the caller, as for the DM commit); `not_friends`; `429 rate_limited` at more than
  3 resets per DM per hour.
- In the per-conversation critical section: `generation` + 1, `epoch` null, the group's device
  list cleared, the DM's ops dropped, open history requests closed (`expired`, §17.4). The DM
  **stays e2ee**: plaintext is still `e2ee_required`, ciphertext is `stale_epoch` until rebuilt.
  No event: the peer's devices learn of it from the new generation's Welcome.
- `GET /mls/groups/{dm}` then shows `e2ee: true`, the new `generation`, `epoch: null`, `devices: []`.
- **Rebuild:** any participant's current MLS device makes epoch 0 of the new generation with the
  §10.2 creation commit (`generation` = n + 1, readiness required); the first wins, others get
  `epoch_conflict` and join from the Welcome.
- **Epoch 0 (clarified for every DM):** `added` must contain every current, **non-superseded** MLS
  device of both members except the caller, and may contain superseded ones (each must still be a
  current MLS device). Before, superseded installs without a key package blocked the creation.

## 4. Rejoin (new)
**`POST /api/v1/mls/groups/{dm}/rejoin`** (no body) with `X-Device-Id` (a current MLS device of the
caller) → `202 {"op": PendingOp | null, "candidates": n}` (`mls_dm_rejoin_reply.json`).
- Creates or widens the caller's op (§2): adds the calling device; if the calling device is in the
  group (its state was lost), it is removed and re-added. Idempotent.
- `candidates` counts the op's candidates (§2.1), **online or not**. `0` means nobody can re-add
  this device: the client resets (§3) at once.
- `op: null, candidates: 0` while the DM awaits a rebuild (`epoch` null).
- Errors: `404 not_found` (not a participant, or not e2ee), `400 bad_request` (`X-Device-Id`
  isn't a current MLS device of the caller), `not_friends`, `429 rate_limited` (10 per user per
  minute).

## 5. Client (android, normative where it says "must")
- **When to check:** on chat open and resume, on reconnect while the chat is open, and when an e2ee
  DM send can't be encrypted (no local group, or `stale_epoch` that catch-up doesn't fix), the
  client calls `GET /mls/groups/{dm}`.
- With `e2ee: true`:
  - `epoch: null` → rebuild (§3);
  - this device is not in `devices`, or the local group is missing or of an older generation →
    `POST …/rejoin`, show **"Setting up encryption on this phone…"** (not a failed send; messages
    stay pending and go out when the Welcome is applied). If this device *is* in `devices` but has
    no local group, it first waits 60 s for a Welcome already on its way;
  - `candidates: 0`, or still not set up **2 minutes** after it first saw the problem → reset (§3),
    then rebuild. A `generation_conflict` means someone else reset: check again.
- The **named committer** runs `mls_dm_op` once its earlier events are applied: claims the added
  devices (§10.2), commits in the §2.2 order with `op_id`, merges only on `200`; `epoch_conflict`
  → catch up and re-derive; a device without a key package → leave it (the server re-names).
- Older messages: the §13.3 marker and §17 history sharing, unchanged.

## 6. Storage (server)
`mls_dm_ops(op_id PK, conversation_id, user_id, payload, committer_user, committer_device,
committer_until, naming, tried, created_at)`, unique `(conversation_id, user_id)`. Queries: by
conversation (settle after a commit, reset), by conversation and user (create/widen), waiting ops
of a user's DMs (inbox join). `mls_groups.epoch` becomes nullable (a reset DM). Committer timers
reuse the `GroupTimer` Oban queue (kind `dm_committer`).

## 7. One-off pilot recovery (server release task)
`RisiMe.Release.dm_device_ops/1` scans every e2ee DM and creates (§2) the missing ops: participants'
receiving MLS devices not in the group. **Dry run by default** (counts only, changes nothing);
`dry_run: false` creates and names (from `eval`, via a one-off `GroupTimer` job run by the server,
like §12.11). Idempotent. Logs and returns counts only: `dms`, `affected_dms`, `missing_devices`,
`superseded_leaves`, `with_candidates`, `without_candidates` (those heal by the client reset, §5),
and in a real run `ops`, `named`.

    bin/risime eval "RisiMe.Release.dm_device_ops()"                # dry run
    bin/risime eval "RisiMe.Release.dm_device_ops(dry_run: false)"  # creates + names

## 8. Tests
- Server: op creation on key-package upload and rejoin; naming order, `member_devices` filter,
  superseded exclusion, rotation and the inbox-join rule; the commit rule (with/without `op_id`,
  same-device re-add, superseded removal only while a live leaf stays); settle; DM reset (stays
  e2ee, rebuild at the new generation, conflicts, rate limit); epoch-0 superseded clarification;
  the recovery task (dry run changes nothing).
- Android (JVM): the repair decisions (rejoin / wait / reset / rebuild), the strip text, the
  `mls_dm_op` executor (order, `op_id`, 409), pending sends while repairing.
- Live interop (gate): A and B in an e2ee DM; A reinstalls; B's device re-adds A's new device and
  drops A's old leaf; A sends, B receives, and back. Both reinstall: the reset path, then both
  ways again.
