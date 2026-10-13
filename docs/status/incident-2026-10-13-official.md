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

Repair time: the manual script was not needed in the end.
- **B (H ↔ V):** recovered on its own at **09:32 UTC**. V's phone opened and committed the re-add of H's new phone,
  at epoch 6.
- **A (H ↔ R):** repaired by the hotfix at **13:45 UTC**, one minute after v0.3.0-nightly.6 went live at 13:43. Risi
  committed the re-add of R's current phone at 13:45:01 (epoch 2) and of H's current phone at 13:45:06 (epoch 3),
  under §12.12.9 (decision 088). Neither person opened the app for it. One leftover `devices` op is for a device
  that no longer exists; the Health agent closes it.

## Permanent fixes (hotfix nightly)
See the "Permanent fixes" section below, written by the hotfix work. In short:
- the watchdog with Try again;
- race-safe `…/official`;
- Risi commits device re-adds in Official only (decision 088, amending §12.12.3);
- 1:1 wording;
- a Health agent that repairs stuck chats and alerts;
- a new blocking gate, `scripts/official-start-test`.

## Permanent fixes (written by the hotfix work)
Contract **v1.46 §12.12.9** (amends §12.12.3 and §24.2), decision **088**. Private groups, a group chat's Private
and DMs are unchanged (parity tests on both sides).

| Root cause | Fix | Where |
|---|---|---|
| Official needed a human phone to re-add a reinstalled phone | **Risi commits the re-add** of an existing member's new device in Official (member-committable `devices` ops only; first candidate; ordinary commit path with `op_id`; key package of that exact device from the server; strikes/exhaustion as any device). Never in Private or DMs, never add/remove/role/rebuild/cleanup | `Groups.Ops.risi_can_commit?/3`, `Groups.Commit` (agent exception), `Agent.Conversation` (`group_op` → `Nif.change_members/4`), `MLS.claim_for_readd/2`, NIF `change_members` |
| Risi's leaf (caps `groups`, `tabs`) closed the §12.4a member gate in every Official group: even an online member couldn't re-add | Risi's leaf never closes the gate (it runs the server's current core) | `Groups.Ops.gate_open?/3` |
| Dead devices (row gone) counted as candidates; `rejoin_guard` blocked the reset for 24 h with nobody able to commit | In Official, candidates leave out devices that can never commit; `candidates: 0` ⇒ the admin device may reset **at once** (server guard and app rule) | `Ops.candidates/2` (`drop_gone`), `Ops.rejoin_guard/2`, Android `RejoinRules.group(official=)` |
| A creator that never builds epoch 0 blocks everyone | `POST …/official` on a `creating` Official older than 60 s hands it to the caller (under the group lock); the app builds epoch 0 only as `created_by` (otherwise waits and re-POSTs). Concurrent POSTs already give one group (unique index), now tested | `Chats.take_over/2`, Android `officialEpoch0Mine` |
| "Starting Official…" spun forever | Watchdog: re-kick every 20 s; after 60 s the reason (offline / "Waiting for <name> to open RisiMe" / "Official isn't ready on this phone yet.") and **Try again** (`official_try_again`) | Android `ChatTabsController`, `ChatTabsUi`, `AppContainer.rekickOfficial` |
| "Waiting for a group member" in a 1:1; no way out | A 1:1 Official names the other person; every waiting strip has **Try again** (`e2ee_try_again`) | Android `groupE2eeStrip`, `GroupScreens` |
| Nobody noticed stuck chats | **Health agent** (`RisiMe.Agents.Health`, cron every 5 min, flag `RISI_AGENT_HEALTH` default `canary`): finds chats stuck > 5 min, repairs Official ones (Risi renamed; stale `creating` expired; reset by the waiting admin device when nobody can ever commit), alerts the rest to the `RISI_DEV_USERS` Risi chats (`ops_alert` `conversation_stuck`), records `risi_health_stuck`, daily scoreboard row `health` (reliability) | `server/lib/risime/agent/agents/health.ex` |

**Gate:** `scripts/official-start-test` (3 redroids; `OSTEST OK`), blocking in `scripts/nightly-release` after the
Official banner check. **Unit tests:** server `official_never_waits_v146_test.exs`, `health_agent_test.exs`, the NIF
end-to-end re-add in `agent_tree_test.exs`, Rust `risi_re_adds_a_members_new_device_in_official`; Android
`OfficialStartWatchdogTest`.

**For the live H↔R chat:** after the hotfix deploy, the Health agent (canary covers Harsha's chats) would
re-add both phones through Risi, because H and R each still hold an old leaf. Root's manual reset remains the
immediate repair.
