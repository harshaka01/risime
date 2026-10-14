# Incident 2026-10-14: Health alert "6 conversation(s) stuck" (P1)

**Reported:** 07:16 SLT (01:46 UTC) by Harsha, from the Health agent's alert in his Risi chat. The pilot was on
v0.3.0-nightly.7. People are named by role here:
- H = Harsha, the operator (`RISI_DEV_USERS`);
- peers A, B, C = H's three 1:1 contacts;
- owners D and E = two other users;
- G1 and G2 = the two other members of H's 3-person group.

Ids live only in the pilot DB.

## What the alert said, and what was behind it (read-only checks, 02:00–03:00 UTC)
| # | Health kind / action | What it really was |
|---|---|---|
| 1 | Official `readd`, `shadow` | **D's own Risi chat** (chat kind `risi`). Its members are D plus Risi, which is correct for a Risi chat. The op re-adds D's phone (nightly.1, which lost its state on 10-13 04:20). The server holds 49 key packages for that phone; Risi can commit (`risi_can_commit?` true). |
| 2 | Official `readd`, `shadow` | **E's own Risi chat**, same shape. E reinstalled at 10-13 07:06 (nightly.5). There are two duplicate re-add ops for the new phone (a race between key-package upload and rejoin) and one leftover remove-only op. |
| 3 | Official `rebuild`, `alert` | **H's 3-person group (H, G1, G2)**, reset on 10-11 03:30 (generation 2, no MLS state). The rebuild op is named to H's current phone (reinstalled 10-13 07:01, nightly.7), 910 times, and that phone never commits. |
| 4–6 | DM `dm_readd`, `alert` | H's new phone waits in three Private 1:1s for A, B and C to open RisiMe. |

**Was the "only one human member" of 1 and 2 a bug? No.** Both are other users' Risi chats: one human plus Risi
is exactly their shape. H isn't a member and doesn't need to be. He received the alert as the operator.

## Why six got stuck
1. **Official repair was limited to the canary.** Health ran in `canary` (decision 088), which repairs only
   conversations with a `RISI_DEV_USERS` member. Rows 1 and 2 are other users' Risi chats, so they stayed
   `shadow` for ~21 h (sweeps 123/246 on 10-13), although Risi could have re-added both phones at any time.
   Decision 088 says Official must repair itself. The canary scope was the wrong default for Official
   repairs, whose path the protocol already allows.
2. **Their ops predate the hotfix.** Both re-add ops were created on 10-13 (04:20 and 07:06), before
   nightly.6 (13:43) let Risi commit. They waited with no committer (`naming` 1). Only a Health
   re-naming would ever pick Risi.
3. **The rebuild can't finish on a reinstalled phone (an app bug).** Found by code reading (proposal
   2026-10-14-health-nudge §4):
   - The reinstalled phone has no MLS state and no Official `chat_tabs` row, so its REBUILD builds
     **Private** meta.
   - The core refuses a Private group with Risi's leaf. The error isn't mapped to a policy failure, so the
     job retries 8 times and then FAILED.
   - The op row is deduped by `op_id`, so 910 later namings never re-queue it.
   - The chat keeps calling `rejoin` (202) and shows "waiting to be re-added".

   No server-only fix works with nightly.7. Risi may not rebuild (§12.12.9).
4. **Private 1:1s wait on the other person, and nobody asked them.** Risi never commits in Private (right).
   The candidates are correct: in each DM the only candidate is the peer's current phone. **Nothing was
   excluded wrongly:**
   - old-app phones advertise `member_devices` and are candidates;
   - offline phones count;
   - the new-app peer's phone is the candidate of its DM.

   `naming=139/140, tried=0` only means that every naming (each sweep, wake job or inbox join) found the
   candidate offline. No candidate phone was online since H's reinstall:
   - **Peer A:** old app 0.2.0-nightly.43, last seen 10-13 03:02, **no push token**. The wake pushes went
     nowhere.
   - **Peer B:** old app 0.2.0-nightly.27, last seen 10-12 16:20. It has a push token (old Firebase
     project), so it got the 6-hourly content-free wakes (http 200). The old app only syncs on a wake. It
     commits a DM re-add only if the `mls_dm_op` naming arrives while it runs, and a background wake rarely
     catches it online.
   - **Peer C:** new app 0.3.0-nightly.1, last seen 10-13 04:20, **no push token** (nightly.1 shipped
     before the new package's Firebase config).
5. **Reinstalls multiply this.** Every reinstall creates a new device that must be re-added in every
   conversation. Official is covered by Risi. Private still needs the peer's phone, and peers on the old
   app or without push are the slowest.
6. **The alert was unreadable:** short ids, kinds and codes, no names, no action. It said
   "couldn't repair" without saying whose action was needed.

## Fixes (Risi fast lane, server only; proposal 2026-10-14-health-nudge)
- **Official repairs everywhere:** a new switch, `RISI_HEALTH_OFFICIAL_REPAIR` (off|canary|on, default
  **on**), sets the Official scope of Health's `canary`. Shadow still repairs nothing, and Private and DMs are
  never repaired.
  - v0.3.0-nightly.7-srv2 (live 03:10 UTC) shipped the code default `canary` (H's conversations). Its 03:15 sweep ran cleanly: it nudged the three peers and sent one plain report. In scope, H's only Official chat is the rebuild (`needs_app`).
  - Once that sweeps cleanly on the pilot, the next fast-lane release ships the default `on`.
- **Private nudge:** at most once a day per waiting DM, the other person gets a stored `secure_nudge` event
  and the ordinary content-free wake (`RISI_HEALTH_NUDGE`, default `canary`). New apps (with the APK) show
  "Open RisiMe to finish securing your chat with <name>". Old apps ignore the event and only sync.
- **Plain report** (`RISI_HEALTH_PLAIN`, default on):
  - Names and actions, never ids. "Fixed" is said only once the case is verified gone.
  - It names people whom no push can reach.
  - `items` let a new app open each chat.
  - "Once per conversation, day and state" is kept in Postgres (`reported`, `nudged_at`), so a deploy no
    longer repeats the alert.
  - Technical rows go to `RisiMe.Agents.Health.console/1` for the admin console's Servers page.
- **The rebuild:** reported as "needs the next app update; nothing on the phones is lost" (action
  `needs_app`). The app fix is in the proposal (§4) and needs an APK.

## Gate
`scripts/health-heal-test` (`HHTEST OK`) runs a server-level scenario:
1. H reinstalls while one peer is on the old app with no push token and another is offline.
2. Official heals without either peer (Risi re-adds).
3. Each peer is nudged once, and the operator gets one plain report.
4. The peers come back, and their phones re-add H's phone.
5. The next report says "fixed" for all three.

It also runs the Health suites. A redroid device step (old-app peer, then new app) can follow once the APK
part of the proposal lands.
