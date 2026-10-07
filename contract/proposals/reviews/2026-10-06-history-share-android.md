# Android review: history sharing (v1.14 candidate, §17)

**Reviewer:** android role, 2026-10-07. **Proposal:** `contract/proposals/2026-10-06-history-share.md`
(§17.2, §17.5–§17.9, §17.13, open points A1–A5), decision 049 (accepted, consent option A). Read
against PROTOCOL v1.13 (§10.4, §12.8, §13.3, §14.7, §15.5–§15.7, §16.6) and the app:
`data/db/Entities.kt` (Room v8), `data/mls/MlsPipeline.kt`, `data/HistoryMarkers.kt`,
`data/media/*`, `data/deletes/*`, `data/auth/AuthManager.kt`, `AppContainer.syncAndNotify`,
`push/PushServiceAndWorker.kt`.

**Context change since the proposal:** decision 050 (plain logout keeps the database and MLS state).
Gap rows now appear only after "Log out and delete chats", a reinstall, a new phone, a §12.8 rejoin
or a reset. The provider is usually **the user's old phone**: §12.1-superseded until it is opened,
possibly logged out (no push token, decision 050), and behind the **fingerprint lock** when its
process was killed (`syncAndNotify` then only calls `notifier.postLocked()`; a locked app has no
bearer and can't talk to the server).

**Verdict:** fits the app's existing patterns (cursor-transaction writes in `MlsPipeline`, sealed
media state, the delete purge rules, WorkManager transfers). Merge after **R1–R9**. Blocking:
`from_device` can't be a match key (R1), the marker positioning needs an explicit exception to
§13.3's forward-only rule (R5), the locked/background provider path must be specified (R7), and
call lines can't be shared across perspectives (R3).

---

## Required changes (blocking)

**R1. The gap row and the match keys must fit what the provider stores.**
- The `messages` table has **no `from_device`** column (and no epoch). An honest provider can't
  fill `from_device`, so the import must not require it (crypto R6).
- Gap row (Room v9, `history_gap`): `conversation_id, message_id (PK), client_msg_id, from_id,
  from_device, server_ts, generation, epoch, created_at`, index `(conversation_id, server_ts)`.
  `client_msg_id` comes from the `message` event and **is** stored by the provider (the `messages`
  PK), so it is a strong second key.
- Match = `message_id` + `client_msg_id` + `from` + `server_ts` (as instants, via
  `HistoryMarkers.epochMs`). `from_device` is compared only when both sides have it. Add
  `from_device` to `messages` for **new** rows (cheap, nullable) so later bundles carry it.
- A gap row is written in the **same transaction** as the cursor and the marker upsert, in the
  pre-install branch of `MlsPipeline` (rule 1 and rule 2), for reset/rejoin discards, and for
  older-generation parked rows discarded on a newer Welcome.

**R2. Export filters, precisely (provider).**
Only rows of the one conversation that are, at export time:
- `kind` `text` or `image` with a non-null `message_id`, not `deleted`, `deleteState == null`,
  not `deleteUnverified`, not `system`, not outbox (`status` sending/failed), and with
  **TimeUUID time of `message_id` > the provider's `cleared_upto`** (§15.7 compares TimeUUID times,
  not `server_ts` strings);
- reactions from the `reactions` table: one entry per `(target, reactor, emoji)` with its
  `confirmed_message_id`, `confirmed_op` and `confirmed_ts`, and only for targets that are in the
  bundle (the table keeps the latest state, not every event; that is fine: the requester ends with
  the right state);
- inside `range`, inside the server's `intervals` **and** the provider's local view of the
  requester's membership. The local view must always exist, never "unknown":
  - start = the time of the `group_event` that added the requester's user, or, if the requester was
    already a member when this device joined, this device's own join time (nothing earlier is held
    anyway); end = a later `removed`/`left` line; several intervals for rejoins;
  - DMs: the whole range.
- Never: tombstones, hidden-tombstoned ids, "deleted for me" rows (already purged), §13.3 markers
  and other system rows, control envelopes, rows whose media is in the `deleting` state.

**R3. Call lines: own-device shares only.**
A `call` row's body is "from this user's perspective" (§16.6) and the `call_end` envelope isn't
kept. A member provider's "Missed voice call" is the wrong line for the requester. Export `call`
rows only when the requester is **the same user** (the stored line is then right); member shares
skip them (their gap rows remain, see R6).

**R4. Bundle building and size limits.**
- Built **in memory, part by part**, newest first for the selection, then each part written
  oldest-first: when the 20-part cap cuts, it cuts the **oldest** end, as the proposal says.
- A part ends at **5 000 entries or 16 515 072 bytes of plaintext** (the §14.3 cap for 16 MiB
  ciphertext), whichever comes first. With image entries (thumbnail ≤ 4 096 B → ~5.5 KB base64 plus
  the envelope) 5 000 entries can exceed 16 MiB, so the byte bound is the real one for photo-heavy
  chats.
- The plaintext goes to the core **as bytes**; no plaintext temp file (crypto R7, §14.7).
- **Images by reference:** the entry is the image envelope **reconstructed** from `media`
  (`blob_id`, `blob_size`, `blob_sha256`, the unsealed `enc` and `thumb`, `mime`, `w`, `h`) and the
  caption (`messages.body`). Validate it with the same §14.4 rules before export. Images whose
  `expires_at_est` has passed are still exported (the thumbnail and caption are worth having; the
  requester shows "This photo is no longer available").
- The bundle header's `provider` is informative; the requester takes the provider from the MLS
  credential.

**R4b. Import: no duplicates, one transaction per part, nothing live touched.**
Per part, in one Room transaction (serialised with the pipeline by Room's write lock), cursor
untouched:
1. The header `request_id`, `conversation_id`, `part`, `parts` match the verified envelope (else
   drop the part, still `history:ack` it).
2. Per entry: a gap row matches (R1) **and** the row was created before the request was sent;
   no `messages` row with that `message_id` (the unique index; `del:` placeholders carry the
   target's `message_id` too); no hidden tombstone for it (see R8); its `message_id` time is after
   `cleared_upto`; the payload passes the live validation (§11, §14.4).
3. Insert with **`client_msg_id` from the gap row** (the original, so later events dedupe by both
   ids), `origin = "shared"` (or `"own_device"`), `shared_by = <provider user_id>`, `local_ts`
   from `server_ts` (§13.3 replayed history), `outgoing = (from == me)` with status `sent` and **no
   ticks** (receipts for it were already passed by the cursor), never unread, never notified, never
   acked. Images: a `media` row in the remote state with `enc`/`thumb` **re-sealed** under this
   device's `KvSealer` with the AAD bound to the new row; nothing downloaded in the transaction.
4. Reactions: applied only to a target held after this part; never overwrite a local reaction
   state with a newer `confirmed_ts`.
5. Delete the matched gap rows; update the markers (R5); then `history:ack`.
After the transaction: enqueue thumbnails/auto-download as for live images (§14.7 Receiving 7); the
search index and chat-list preview pick the rows up through the normal queries (historical
`local_ts` keeps them out of "Today" and out of the preview unless the chat had nothing newer).

**R5. Markers: an explicit exception to "forward-only".**
§13.3 says the marker only moves later. After an import it must change, so specify:
- A new one-per-conversation row **`sys:history-shared:<conversation_id>`** (upsert), text
  "History shared by <name>" / "History restored from your other device", placed just before the
  oldest imported row (`local_ts = oldest − 1`). A second share from another provider updates the
  text to the latest provider and moves it only earlier.
- The gap marker `sys:history:<conversation_id>`: **deleted** when no gap rows remain; otherwise
  re-positioned at the **newest remaining gap row** (it may move earlier: the documented exception)
  with the text "Some earlier messages aren't available on this device", still offering "Request
  history".
- §12.8's reset line gains "Request history" when gap rows exist for the conversation. Markers
  from before v1.14 with no gap rows get **no** action (see A5).

**R6. Residual gap rows are normal; say so in the UI.**
Reaction events superseded on the provider, call lines in member shares, messages the provider
deleted for itself, images it never stored, entries the provider chose to omit: all leave gap rows.
After a `done` share, mark the remaining gap rows of that range `asked_from = <provider user>` and
word the residual line "Some earlier messages couldn't be restored", with "Request history" still
offered for **other** sources only (own devices if a member answered, members if an own device
answered). Don't promise completeness.

**R7. The provider while locked or in the background (fingerprint lock).**
- **Locked** (no bearer): the push wake can't sync; the existing `postLocked()` notification is all
  the device can show. Nothing about the request is known until the user unlocks. So option A's
  "automatic after one approval" means **automatic while unlocked**: on the next unlock the sync
  delivers the request and the share starts without a prompt. The requester UI says "Open RisiMe
  on your other phone" (server R1 gives the device name and last seen; a logged-out phone: "Sign
  in on your other phone").
- **Unlocked, in the background:** the 25-s `syncAndNotify` window is far too short for an export.
  After `accept`, run a **long-running WorkManager worker with `setForeground`** (foreground
  service type `dataSync`; add `FOREGROUND_SERVICE_DATA_SYNC` to the manifest) with an ongoing
  "Sharing chat history…" notification. The worker holds the realtime connection (like
  `backgroundSync`) for `history:respond`/`history:deliver`, and persists per-part progress
  (`client_blob_id`, `blob_id`, delivered flag) so a killed process resumes, not restarts.
- **Member prompts:** a notification on a "History requests" channel and an in-chat banner; tapping
  goes through the fingerprint gate like any notification tap (`MainActivity` already waits for
  `auth.unlocked`). "Share" in the banner is a user action in the foreground; the worker continues
  if the user leaves.
- The provider decides own vs member from the request's **MLS sender user**, never from the
  event's `consent` field (crypto R4).

**R8. §15 deletes and tombstones on the requester.**
- A **hidden tombstone** with `scope = me` always blocks the import of that id.
- A hidden tombstone with `scope = everyone`: re-run §15.4 step 4 with `S` = the gap row's `from`
  (as for server-placed metadata, §15.5): allowed → insert a tombstone instead of the content;
  not allowed → import normally and drop the hidden tombstone (so a non-admin member's bogus delete
  can't suppress others' shared messages).
- A `delete` that arrives after the import tombstones the imported row through the normal §15.6
  path, including the purge of the re-sealed image key and thumbnail.
- `chat:clear`/Clear chat/Delete chat delete the conversation's gap rows in the purge transaction
  and cancel an open request (`history:cancel`) and its parts.
- "Delete for me" of an imported row works as for any row (same `message_id`).

**R9. Request lifecycle on the requester.**
- `history_requests` table (Room v9): `request_id, conversation_id, sources, state, provider_user,
  parts, parts_done, created_at, expires_at`. Written **in the same transaction as the core's
  `history_keygen`** (crypto R7), before `history:request` is pushed.
- `history_share` events go through `MlsPipeline` in event order (decrypt, validate, store the
  part's `blob`/`hpke_enc`/`sealed_key` in the request row, cursor); the blob fetch, the core's
  `history_open` and the import run in a worker **outside** that transaction, like image downloads.
- On `history_status` `refresh` (server R2): encrypt a new `history_request` (same `request_id`,
  same `rpk`) through the serial per-conversation send lane and push `history:refresh`.
- On every terminal state and at 48 h: `history_forget` in the core, the row closed, partial parts
  kept (they are verified rows).

---

## Suggestions (non-blocking)
- **S1. UX wording** (A4), short and neutral:
  - marker action: **"Request history"** → sheet "Get earlier messages from: [Your other phone]
    [Your other phone or group members]" (DM: "[Your other phone] [Your other phone or <name>]");
  - progress on the marker line: "Looking for your other phone…", "Open RisiMe on <device name> to
    share", "Asking group members…" (never naming who), "Receiving history… 2 of 3", "No device
    could share this history right now" + "Try again";
  - own-device approval on the old phone (option A): **"Your new phone (<device name>, signed in
    <date>) wants your chat history. Allow?"** [Allow] [Not now], with the small print "If this
    isn't you, someone may be using your number. Tap Not now." Afterwards a quiet "Shared chat
    history with your new phone";
  - member prompt: "<name> wants the group history from when they were in the group. Share?"
    [Share] [Not now]; DM: "<name> wants your chat history. Share?";
  - imported bubbles from someone other than the provider: "Shared by <provider>" in the info
    sheet; no label for own-device restores.
- **S2.** Show a one-time hint in the provider prompt of how much will be shared ("about 400
  messages from the last 30 days"), from `gap_count`; never the content.
- **S3.** Persist the option-A approvals in the sealed store keyed by `(device_id, signature key)`
  and list them in Settings → Privacy ("Phones allowed to get your history") with Remove.
- **S4.** Auto-download imported images only on unmetered networks and only for the newest 50;
  older ones on tap (a 30-day restore could otherwise pull gigabytes).
- **S5.** Telemetry by count only: entries imported, skipped per reason (no gap row, mismatch,
  existing row, tombstone, malformed), parts, bytes.

---

## Answers to the open points
- **A1 (gap table, Room migration, in the `MlsPipeline` cursor transaction).** Yes: Room v9 adds
  `history_gap` (R1), `history_requests` (R9), `messages.origin`, `messages.shared_by`,
  `messages.from_device`. The write sits in the pre-install branch beside the marker upsert, in the
  same transaction. The upgrade test covers v8 → v9.
- **A2 (export cost).** 5 000 rows plus reaction state is a few indexed queries and < 16 MiB in
  memory; sealing 16 MiB in the core is well under a second on the pilot phones. The cost is the
  upload: a foreground `dataSync` worker (R7), resumable per part. Only after an explicit Share or
  an option-A approval, only while unlocked.
- **A3 (import transaction; chat list; search).** One transaction per part (R4b). The chat list
  and search read the `messages` table, so they see the rows without extra work; historical
  `local_ts` keeps the preview right. Notifications: none (the import never calls the notifier).
- **A4 (wording).** S1.
- **A5 (re-scan for installs past their gap).** **No re-scan in v1.14.** A metadata-only replay from
  `since: null` would have to bypass the whole MLS pipeline (commits, Welcomes, parked rows) and
  re-page 30 days of inbox; with decision 050 there will be few new gaps, and existing ones age out
  within 30 days. Markers without gap rows stay as they are, without "Request history".

## Chunk order (each green on `./gradlew assembleDebug testDebugUnitTest`)
1. **Gap index only** (no capability, no UI): Room v9 migration, gap rows in `MlsPipeline`,
   removal by `delete`/`chat:clear`/Clear chat, the 30-day prune. Ship first so gap rows exist by
   the time sharing works.
2. **Models and parsing:** the new events, pushes and envelopes, every new example in
   `contract/v1/examples/`, strict envelope validation, the `'H'` AAD helpers.
3. **Core integration** (after the crypto release): `history_keygen/seal/open/forget` through
   UniFFI, the vectors test.
4. **Provider:** request handling (decrypt, MLS-identity checks, own vs member), consent
   (option A record, member prompt, Settings switches), the export builder with all filters (R2,
   R3, R4), the foreground worker, upload and deliver, `unable`/`stale`.
5. **Requester:** marker action and source sheet, the request row with keygen, status/progress,
   refresh, share handling in the pipeline, the fetch/open/import worker (R4b), markers (R5, R6),
   "Shared by" labels, `history:ack`, cancel.
6. **Capability on:** advertise `history_share` only when 4 and 5 are both in (§17.1), live
   interop (own new phone, member share, refusal, refresh after epoch drift, a delete racing an
   import, Clear chat during a request).
