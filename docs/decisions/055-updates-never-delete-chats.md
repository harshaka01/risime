# 055: Updates never delete chats; lost history comes back by itself

Date: 2026-10-07. Trigger: "nightly.25 wiped all local chat data on upgrade" (Harsha).

## Evidence
- The only app change in nightly.24 → nightly.25 is the call screen (00e0278); no migration
  (Room v9 since nightly.19), no wipe path touched. Release APKs n23–n25: same signer
  (da7b9824…), versionCode 20023→20025, package lk.codegen.risime: an in-place update keeps data.
- Server log: Harsha's install 560694e5 (nightly.24) connected last at 10:38:55 UTC; at 10:40:28 a
  **new device id** fc9cc84d (nightly.25) signed in and joined with `since: nil`. The device id lives
  in the app's DataStore, is created only on first use and is never removed by any app path (logout,
  wipe, server switch all keep it). A new id means the OS removed the app's data: an uninstall and
  reinstall (or "Clear storage"), not the update itself. The redroid upgrade gate for n25 passed
  (same device id, all messages kept).
- Nothing in the app deletes DataStore or the database file except the three confirmed wipes
  (Log out and delete chats, a confirmed different account, a confirmed server switch).
- History sharing (v1.15) existed but only on a tap: the pilot had **0** history requests ever, so a
  reinstalled phone stayed with "earlier messages" markers.

## Decision
- CLAUDE.md golden rule 9 (hard rule): an update never deletes local chats.
- The upgrade gate (`scripts/upgrade-test`) now records per-conversation, per-kind message counts
  (1:1, groups, photos, call records) before the update and requires equal or more after it, and
  again after a plain Log out + sign-in of the same user. Any loss = no release.
- A phone with history gaps (new install, lost data) asks for the missing history by itself
  (`HistoryManager.autoRequestAll`, sources `any`, once per conversation, 20 s after going live);
  own devices share, members share after their approval (decision 049 option A). The inbox replay
  (30-day TTL, sender copies) already restores what the server still holds; duplicates are dropped
  by message id.
- Tester note: never uninstall to update; install the new APK over the old one.
