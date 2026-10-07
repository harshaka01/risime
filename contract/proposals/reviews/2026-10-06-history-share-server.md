# Server review: history sharing (v1.14 candidate, §17)

**Reviewer:** server role, 2026-10-07. **Proposal:** `contract/proposals/2026-10-06-history-share.md`
(§17.1, §17.4, §17.7, §17.9, §17.10, open points S1–S4), decision 049 (accepted, consent option A).
Read against PROTOCOL v1.13 (§8, §10.2–§10.5, §12.1 incl. the 2026-10-07 "superseded device" rule,
§12.4, §12.6, §13.2, §14.2–§14.8, §15.8–§15.9) and the code: `RisiMe.MLS` (`superseded_devices/1`,
`record_instance/4`), `RisiMe.MLS.Wire`, `RisiMe.Groups.Membership` (`group_member_intervals`),
`RisiMe.Workers.GroupTimer`, `RisiMe.RateLimiter`, `RisiMe.Push.Dispatcher`, `RisiMe.Blobs`,
`RisiMe.Messaging.Store`.

**Context change since the proposal:** decision 050 (plain logout keeps the MLS state; the device
stays in its groups and its inbox keeps filling; push is unregistered). So a requester is now a
device after "Log out and delete chats" (`DELETE /me/devices`), a reinstall, a new phone, a rejoin
or a reset. The best provider is the user's **old phone**, which §12.1 calls **superseded** (last
seen before the new device was first registered) until it connects again, and which, if the user
only logged out on it, has **no push token** until they sign in there again.

**Verdict:** implementable with the existing machinery (Postgres for state, the inbox for events,
Oban timers like `GroupTimer`, the `Wire` AAD parser, the blob store). Merge after **R1–R8**. The
blocking ones: own-device naming must work for superseded/locked old phones (R1), stale request
ciphertexts need a refresh path (R2), the `history` blob must not be deleted before the requester
fetched it (R5), and daily/weekly limits can't live in the ETS limiter (R6).

---

## Required changes (blocking)

**R1. Own-device naming: include superseded devices, wait long enough, then escalate.**
- **Eligible own devices** = the requester user's devices that are in `mls_group_devices` for the
  conversation at the current generation, advertise `history_share`, and are not the requester.
  **Do not apply the §12.1 superseded filter here**: a superseded device is exactly the old phone
  the user is about to open. Treat it as *dormant*: named when its inbox channel joins.
- **Exclude devices that can't hold anything:** a candidate whose census `first_seen_at`
  (`app_instances`, the device's `history_before`) is **after `range.to`** joined after every
  message asked for. Prefer the candidate with the **earliest** `first_seen_at`, then the most
  recently seen. The same filter applies to member devices.
- **120 s per own device is too short.** A locked app (fingerprint lock) can't even sync: it posts
  the generic locked notification and waits for the user (Android review). Name **every** eligible
  own device at once (they are one user; the first `accept` wins), keep the own phase open for
  **10 minutes** or until every own candidate answered `decline`/`unable`, and keep own devices
  able to accept for the whole life of the request, also after members were named.
- Escalate to members (when `sources: "any"`) after the own phase, or at once when the user has no
  eligible own device. Add the push **`history:escalate {request_id}`** so the requester's "Ask
  group members now" button can end the own phase early.
- The `history:request` reply should carry what the UI needs to say "Open RisiMe on your other
  phone": `"own_devices": [{"device_id", "device_name", "last_seen_at", "online": bool}]` (names
  from the device rows; only the caller's own devices, so nothing new leaks).

**R2. Stale request ciphertexts: a refresh path** (crypto R2).
The server copies the stored `history_request` ciphertext into each candidate's inbox **at naming
time**. A candidate named hours later can't decrypt it once the group moved more than 3 epochs or
the requester sent more than 32 later messages. Required:
- `history:respond` gains `"reason": "stale"` for `unable`; it doesn't count as a decline.
- When the stored ciphertext's `epoch` is more than 2 behind the group's at naming time, or a
  provider answered `stale`, the server sets the request to `refresh`, sends `history_status`
  `{"state": "refresh"}` to the requester device (data push) and names that candidate only after
  **`history:refresh {request_id, ciphertext, generation, epoch}`** arrives (same checks as
  `history:request`: §10.3 epoch, 24 KiB, the `'H'` AAD equal to `request_id`). If the requester is
  offline, naming waits (the 48-h expiry still runs).
- A §12.8 reset of the conversation closes its open requests (`history_request_closed`
  `expired`); the requester may ask again after it rejoins.

**R3. One state machine, no contradictions in the events.**
- `history_status.state`: drop `"declined"` (the proposal also says declines are reported only as
  `unavailable`). Final list: `searching`, `waiting_for_member`, `refresh`, `accepted`,
  `receiving`, `done`, `unavailable`, `cancelled`, `expired`.
- An **accepted provider that never delivers** (crash, phone off) blocks the request for 48 h.
  Add a delivery deadline: no part within **30 minutes** of `accept`, or no new part for 30 minutes
  during delivery → the provider is dropped (`history_request_closed` `expired` to it) and naming
  resumes without it. A provider whose upload is merely slow keeps the request alive by delivering
  parts (each part resets the deadline).
- `done` = every part delivered **and** the requester acknowledged them (R5). The requester's
  `history:ack` closes it; without an ack, the request ends at `expires_at`.
- Closing events: `history_request_closed` to every user ever named (reasons `accepted_elsewhere`,
  `cancelled`, `expired`, `done`), so stale prompts disappear on every device.

**R4. Eligibility and authorisation rules, exactly.**
- **Requester** (`history:request`): an active member of the `grp:` (as §12.9 `not_member`), or a
  DM participant; the calling socket's `device_id` must be in `mls_group_devices` at the current
  generation (else `not_member`); `history_share` capability on that device (else `bad_request`).
- **DMs with `sources: "own"`** don't need friendship: the user's own history from their own
  devices doesn't involve the partner. `sources: "any"` in a DM requires friends and no block
  either way (`not_member`, like §10.3's `not_friends` folded in, so a block isn't revealed).
- **Range:** clip `from` to `now − 30 days` instead of refusing (gap rows sit right at the TTL
  edge); `bad_request` only when `to < now − 30 days`, `from > to`, or `to > now + 5 min`.
- **Member candidates:** active members whose `group_member_intervals` overlap the requester's
  intervals ∩ range; the overlap is computed from the **two users' interval lists**, and
  `intervals` in the event are the **requester's** intervals clipped to the range **and** to the
  candidate's own overlap (a candidate gets only what it may share).
- **Provider pushes** (`history:respond`, `history:deliver`) only from the named or accepted device
  (channel `device_id`), else `gone` (never reveal more). A removed member, a removed device or a
  device that lost `history_share` is un-named at once.
- Membership changes during a request: the requester removed/left or its device removed → close
  (`cancelled`); a `chat:clear` by the requester's user for that conversation → close.

**R5. The `history` blob purpose: tie its lifetime to the requester, not to delivery.**
| | `history` |
|---|---|
| Conversations | `dm:` (e2ee) and `grp:` |
| Upload | `POST /blobs?purpose=history&conversation_id=…&request_id=…&client_blob_id=…`; only the **accepted provider device** (`X-Device-Id`) of a request in `accepted`/`receiving` state (`404` otherwise); idempotent by `client_blob_id` as §14.2 |
| Cap | 16 MiB ciphertext (`413 too_large`) |
| Parts | at most 20 blobs per request (`409 too_many_parts`, or `bad_request`) |
| Readers | the uploader, and the **requester's user** while the request is open or `done` (`404` otherwise) |
| TTL | **48 h from upload**; deleted earlier by `history:ack` of the last part (+1 h), by cancel, by close without delivery |
| Rate | 40 uploads per user per hour; the 3-concurrent-uploads slot shared with `media` |
| Quota | 512 MiB live per user (`413 quota_exceeded`), separate from `media`; shown in `GET /blobs/usage` as `"history"` |
| Guard | `507 storage_full` like `media` |
- **Not "done + 1 h".** A requester phone that is offline for a day would lose its history. Add the
  push **`history:ack {request_id, part}`** (requester → server) after a part is imported (or
  rejected: the requester still acks so the blob goes).
- `blobs` gains `request_id` (nullable, indexed). Readers check `request_id` → request row →
  requester user. `msg:delete` `blob_ids` never match `history` blobs (they aren't `media`).

**R6. Durable limits, not the ETS limiter.**
`RisiMe.RateLimiter` is an in-memory ETS window; a restart (every nightly deploy) forgets it. The
day and week limits must be counted from Postgres rows:
- 3 requests per user per conversation per 24 h and 30 per user per 24 h: `count(*)` on
  `history_requests (requester_user, created_at)` / `(requester_user, conversation_id,
  created_at)`;
- a member user prompted by a given requester at most twice per conversation per 7 days, and never
  within 24 h of a decline: `count(*)` on `history_candidates` joined to its request;
- keep rows **8 days after creation** (not "7 days after closing"), so the 7-day window is always
  complete; a daily Oban prune.
- ETS stays for the burst limits (pushes per minute, like the other channel limits).

**R7. Push wake: no new push type.**
Every new event uses the existing content-free `{"type":"inbox","v":"1"}` wake through
`Push.notify/1` (decisions 026/028; §8.2). There is no "visible-notification push": the app decides
what to show after it syncs (a locked app shows only the generic locked notification). Pushes go to
the named users (all their tokens; non-named devices sync and ignore the event by `to_devices`) and
to the requester for `history_status`/`history_share`. A logged-out old phone has no token (decision
050) and is reached only when the user signs in on it; R1's `own_devices` list lets the UI say so.

**R8. AAD and routing checks (S4).**
- `history:request`, `history:refresh` and `history:deliver`: parse the PrivateMessage with
  `RisiMe.MLS.Wire.private_message/1`; `content_type` application; group id
  `"<conversation_id>#<generation>"` and `epoch` equal to the JSON fields; AAD exactly
  `0x01 0x48 ‖ request_id` (crypto R3) → else `bad_request`. `msg:send` and `msg:delete` refuse an
  `'H'` AAD (`msg:send` already refuses every non-empty AAD; keep that).
- The `stale_epoch` check as §10.3 (current generation and epoch). Ordering relative to commits as
  `msg:send` (no new critical section needed: these are application messages to one device).
- `history_*` events are **never** `message` events: no `message_index` row, no acks, no receipts,
  not `msg:delete`/`chat:clear` targets (`gone`), not counted for unread or push coalescing beyond
  the normal wake.

---

## Storage (queries first)

**Postgres** (state, limits, ciphertext of the request; no content):

| Q | Query | Table / index |
|---|---|---|
| H1 | the open request of `(requester_device, conversation_id)` (→ `request_open`) | `history_requests`, unique partial index `WHERE state IN (open states)` |
| H2 | a request by id, `FOR UPDATE` (every transition) | PK `request_id` |
| H3 | requests of a user in 24 h, per conversation and total (R6) | index `(requester_user, conversation_id, created_at)` + `(requester_user, created_at)` |
| H4 | candidates of a request, their answer and deadline | `history_candidates` PK `(request_id, device_id)` |
| H5 | times member `U` was named by requester `R` in `C` in 7 days, last decline (R6) | `history_candidates (user_id, named_at)` ⋈ H3's index |
| H6 | open requests to name on an inbox join of device `D` (dormant own devices, R1) | `history_candidates (device_id) WHERE answer IS NULL AND state = 'waiting'` |
| H7 | requests of a conversation / of a device to close (reset, removal, clear) | `history_requests (conversation_id) WHERE open`, `(requester_device) WHERE open` |
| H8 | eligibility: intervals of the requester and of every member | `group_member_intervals (group_id, user_id, active_until)` (exists) |
| H9 | `history` blob readers | `blobs (request_id)` |

```
history_requests(request_id uuid PK, requester_user, requester_device, conversation_id,
  generation, range_from, range_to, gap_count, sources 'own'|'any', state, phase 'own'|'member',
  provider_user, provider_device, accepted_at, parts, parts_delivered int, parts_acked int,
  request_ciphertext bytea (≤ 24 KiB), request_epoch, created_at, expires_at, closed_at)
history_candidates(request_id, user_id, device_id, phase, named_at, named_until,
  answer 'accept'|'decline'|'unable'|'stale'|null, answered_at)
```
- **Normalise the candidates** instead of `candidates jsonb`: H5 and H6 need indexed reads.
- The request ciphertext is stored once (bytea) and copied into each candidate's inbox event at
  naming; it is nulled at close.
- One advisory lock per `request_id` around transitions (accept races: first `accept` under the
  lock wins; the others get `gone`).

**Cassandra:** no new table. The four event kinds are ordinary `inbox_events` rows behind
`RisiMe.Messaging.Store` (cursor-ordered, replayed on a fresh install, written with
`conversation_id`). They need a **per-write TTL of 48 h**: add an option to `append_event/2`
(`USING TTL 172800`); mixing a shorter TTL into the TWCS daily windows is fine. No
`ALLOW FILTERING`, no secondary index.

**Timers (S1):** a new `RisiMe.Workers.HistoryTimer` (Oban, own queue), modelled on `GroupTimer`:
one job per deadline (`own_phase_end`, `member_until`, `delivery_deadline`, `expire`), args are ids
and a naming counter only; a stale job is a no-op. The inbox-join hook (where `group_op` namings
for offline committers already happen) also runs H6.

---

## Suggestions (non-blocking)
- **S1.** Return `gap_count` and the requester's `intervals` length in telemetry only; never log
  ranges with user ids at info level.
- **S2.** `history_status` `waiting_for_member` should not say *who* is being asked (the proposal
  shows "Waiting for <name>…"). Naming the asked member tells the requester who later said nothing
  (a soft decline). Prefer "Waiting for a member to share…".
- **S3.** Cap member prompts per member user per day across all requesters (e.g. 10), so one
  member in many groups isn't flooded after a pilot-wide reinstall.
- **S4.** Count `history` uploads in the free-space guard with `media`, not with `mls`.
- **S5.** A server-side metric "requests by outcome" (done / unavailable / expired / refresh) per
  day, to see whether member prompts are worth keeping (H3).

---

## Answers to the open points
- **S1 (naming state machine: reuse §12.4 or separate).** Separate: `RisiMe.History` with its own
  tables and a `HistoryTimer` Oban worker, modelled on `GroupTimer`/`Ops.committer_timeout`. Reusing
  `group_ops` doesn't fit (DMs too, several candidates at once, consent answers, a 24-h member
  window). The "visible-notification push" doesn't exist on the server: every push is the
  content-free inbox wake (R7); the app builds the notification.
- **S2 (eligibility with `group_member_intervals`; DMs).** Groups: one indexed read of all
  intervals of the group (≤ 256 users) and the overlap computed in Elixir (H8). DMs: no interval
  table needed: the requester's interval is `[max(mls_groups.e2ee_since, now − 30 d), now]` (before
  the upgrade the DM was plaintext and replays, §13.1); the only member candidate is the partner,
  only with `sources: "any"` and friendship (R4).
- **S3 (`history` blob purpose).** R5: readers by `request_id`, 16 MiB, ≤ 20 parts, 48-h TTL,
  512 MiB quota, 40/h, deleted on the requester's `history:ack` (+1 h), cancel or close.
- **S4 (routing to one device; AAD).** `to_devices` on every `history_*` event (one device for
  `history_share`/`history_status`, the named devices for `history_request`); the event still
  lands in the user's partition, so other devices advance past it and ignore it. AAD as R8 with
  the existing `Wire` parser.

## Test coverage (server gate, additions to §17.13)
Own phase names every eligible own device at once, including a superseded one when it joins, and
skips devices whose `first_seen_at` is after `range.to`; escalation after 10 minutes, after all own
answers, or on `history:escalate`; an own device accepting during the member phase wins; the
refresh path (`stale` answer, epoch drift > 2, naming waits for `history:refresh`); reset closes
requests; delivery deadline drops a silent provider; `history:ack` deletes blobs, no ack keeps them
until 48 h; the blob reader matrix (requester user `200`, the requester's other devices `200`,
another member `404`, after close `404`); upload refused for a non-accepted device; limits survive
an application restart (counted from Postgres); DM `sources: "own"` without friendship, `"any"` with
a block → `not_member`; range clipping; `history_status` never names a decliner; `'H'` AAD on
`msg:send` → `bad_request`; events carry a 48-h TTL; no `ALLOW FILTERING`.
