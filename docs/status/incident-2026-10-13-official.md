# Incident 2026-10-13: two 1:1 Official conversations stuck (P0)

**Reported:** 12:46 SLT (07:16 UTC) by Harsha. Production pilot on v0.3.0-nightly.5. People are named by role
here (H = Harsha, R and V = the two colleagues); ids are in the pilot DB, not in this file.

## Symptoms
- **A (H ↔ R, Official):** "Starting Official…" spins forever on both phones. No timeout and no Try again.
  Private works.
- **B (H ↔ V, Official):** "Setting up encryption on this phone… Waiting for a group member to open RisiMe".
  The wording is for a group, though this is a 1:1, and the chat waits on a human.

## What the live server showed (07:10 UTC, read-only rpc)
- **No race.** Each chat has exactly one Official group (`chats.official_conversation_id`):
  - A was created 06:49:51 by H, generation 1, epoch 1.
  - B was created 2026-10-09 by V, generation 1, epoch 4.
- **A: no current device is in the MLS group.**
  - **Group devices (`mls_group_devices`):**
    - H's install of the old app (nightly.49, last seen 10-12);
    - H's nightly.1 install;
    - H's earlier nightly.5 install (last seen 06:56);
    - an R device that no longer exists in `devices`;
    - Risi.
  - **Reinstalls:** H reinstalled at 07:01 and R at 07:06, both new-app devices with key packages, so neither
    current device is in the group.
  - **Pending ops:** 4 `devices` (re-add) ops for H's and R's new devices, with naming 1–3, `tried` [] and no
    committer. No live member device can commit them. Risi is a member but never commits device re-adds
    (§12.12.3).
  - **Why nothing recovers:** `Ops.rejoin_guard` refuses the app's own §12.8 reset while a re-add op is
    younger than 24 h, and the app has no timeout. The result is an endless spinner on both phones.
- **B: H's new phone is not in the group.**
  - **Group devices:** H's old-app install, H's earlier nightly.5 install, V's current device, Risi.
  - **Pending ops:** 2 `devices` ops for H's new device (07:01). They named V's device as committer, which
    took one strike at 07:02 and didn't commit (V's app was not open). H's phone shows the generic "group
    member" wording.

## Root cause
1. **Official depends on a human device to re-add new devices.** After a reinstall or new phone, a 1:1
   Official needs the other person's phone to be open and commit the re-add, because Risi is excluded from
   committing (§12.12.3). When both sides reinstall (A), or the other side isn't online (B), nothing commits.
2. **No escape hatch.** `rejoin_guard` blocks the reset for 24 h while a re-add op is pending, even when no
   candidate can ever commit (A: zero live candidates). The `risi_alone?` exception covers only the Risi chat.
3. **No watchdog in the app.** "Starting Official…" has no timeout and no Try again, and the waiting text uses
   group wording in a 1:1.

## Repair (manual, Harsha-approved)
`~/risime-repair/official-2026-10-13.exs`, run with `~/risime-repair/rpc.sh`. For each group it drops only the
stuck `devices` ops, then runs `Groups.Commit.reset/4` (§12.8) with H's current phone as rebuilder:
- the generation goes up;
- the rebuild op makes H's phone create a fresh MLS group with every member's current device (R's or V's new
  device from its key packages, and Risi);
- nothing is deleted on any phone (rule 9), and old Official messages stay local.

Repair time: _to be filled when run_.

## Permanent fixes (hotfix nightly)
See the "Permanent fixes" section below, written by the hotfix work. In short:
- the watchdog with Try again;
- race-safe `…/official`;
- Risi commits device re-adds in Official only (decision 088, amending §12.12.3);
- 1:1 wording;
- a Health agent that repairs stuck chats and alerts;
- a new blocking gate, `scripts/official-start-test`.
