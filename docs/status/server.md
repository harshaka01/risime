# Server status — Release 0.2 (in progress)

**READY** — 0.1 (S1–S7), the 0.2 night-1 items, contract **v1.3** (Keycloak sign-in),
**v1.4** (one-time SMS phone verification), the **prod-mode pilot release** (decision 024) and
**v1.5** (push wake-ups, decision 028), **v1.6** (invites and friends, decision 030), **v1.7**
(E2EE routing with MLS, decision 034; **off until the attestation key exists**), **v1.8**
(reactions), **v1.9** (groups with MLS, §12, decision 041), **v1.10** (history after a
reinstall, §13, decision 043), **v1.11** (encrypted images, §14, decision 042) and **v1.12**
(deleting messages and chats, §15, decision 047), **v1.13** (1:1 voice calls, §16, decisions
046 and 051) and **v1.14** (members restore an existing member's devices, §12.4a) are done, plus the group-readiness hotfix and
the two §14 fixes root decided.
Gate green on `main`: `mix format --check-formatted && mix compile --warnings-as-errors && mix test`
(453 tests); `scripts/interop` (instance `_mr`) green after v1.14.

## v1.14 members restore an existing member's devices (§12.4a, §12.11) — READY
- **No migration.** `member_devices` joins `@known_capabilities` (stored per device).
- **Policy** (`Groups.Policy.check/1`, fixture v2, 27 cases): adds/removes are `{user, device}`
  plus `leaf_users` (the base epoch's leaf owners); a non-admin may add leaves of users with a
  leaf (never agents) and remove another user's leaf only together with the same device's re-add.
- **Ops** (`Groups.Ops`): `member_committable?/3` (one user, active `kind: user` member with a
  leaf, ≥1 add, every removal re-added), `gate_open?/3` (every in-group leaf not changed by the
  op, not superseded and still registered advertises `member_devices`), `member_path?/3`.
  `candidates/2` = own other devices → admins → (member path) other active non-agent members'
  non-superseded leaves; each tier by recency, member tier ties by device id (the server keeps no
  leaf index). `authorised?/3` and the join rule use the same path.
- **Commit** (`Groups.Commit`): a non-admin commit touching another user's leaves must carry the
  op id of a `devices` op (`403 not_admin` otherwise), then the op's `added` and the in-group part
  of its `removed` must match exactly (`400`). Accepted `devices` commits log
  `group commit: op=devices committer_role=own|admin|member` (no ids).
- **Wake** (GroupTimer `wake`, unique per op while available/scheduled): whenever a `devices` op
  is left with no committer; pushes the §8.2 inbox payload to the own token of the first 10
  candidates (online ones skipped), at most 4 per device per 24 h (`RateLimiter :group_wake`,
  in-node), then re-queues itself +6 h while the op waits. Logs `group_op_wake: candidates=n
  pushed=m`.
- **Recovery** `RisiMe.Release.name_pending_device_ops/1`: dry run by default (reads only; from
  `bin/risime eval` it starts only the repo). The release has no distribution (no `rpc`), so
  `dry_run: false` from `eval` queues a one-off GroupTimer `name_pending` job that the running
  server executes under each group's lock (its log line `pending device ops (dry_run=false): …`
  has the counts); inside the node it runs at once. Counts: `devices_ops`, `waiting`,
  `member_committable`, `gate_closed`, `with_candidates`, `named`, `still_waiting`. A
  `PUT /me/devices/{id}` that newly adds `member_devices` runs it (real) for the user's groups.
- **Pilot dry run (risime_dev, read-only, 2026-10-07 03:05 UTC):** 6 pending `devices` ops, all
  6 waiting, in 2 groups, oldest 2026-10-06 22:24 UTC; all 6 member-committable, all 6 gated
  (no app advertises `member_devices` yet), all 6 have candidates.
- **Pilot recovery after the deploy** (once the testers' apps advertise `member_devices`):
  `bash -c "set -a; . ~/development/risime/.env; . ~/development/risime/infra/pilot/pilot.env; set +a; ~/risime-run/current/bin/risime eval 'RisiMe.Release.name_pending_device_ops()'"`
  (dry; today: `waiting: 6`, `gate_closed: 6`; expect `gate_closed` → 0 as members update),
  then the same with `name_pending_device_ops(dry_run: false)` (queues the job; the server log
  shows `named` + `still_waiting` = waiting; the rest are named by the join rule or woken).
  Verify `select count(*) filter (where committer_device is null), count(*) from group_ops
  where type = 'devices'` → 0 within hours, and `committer_role=member` lines in the log.
- **Tests:** `test/risime_web/controllers/groups_member_devices_test.exs` (naming tiers, agents,
  gate incl. superseded and unregistered leaves, op id + exact lists, not-committable ops,
  wake pushes and caps, dry/real/idempotent recovery, PUT re-run), the policy fixture, and
  `device_put_member_devices.json` stored as sent.

## v1.13 1:1 voice calls (§16) — READY
- **Commit:** `25019ce` (implementation, tests, the 19 v1.13 examples; `@pending_v1_13` removed).
- **Cassandra migration `005_call_signals.cql`** (additive; `scripts/run-server` runs it before
  the switch, and the new code needs it): `call_signals ((user_id), event_id) payload`,
  `CLUSTERING ORDER BY event_id ASC`, `default_time_to_live = 120`, `gc_grace_seconds = 0`,
  TWCS 10-minute windows. Q9 `SELECT event_id, payload … WHERE user_id = ? AND event_id > ?
  LIMIT ?`, Q10 the insert (a ring row `USING TTL 60`). No `ALLOW FILTERING`, no index.
- **`call:signal`** (`RisiMe.Calls.signal/3`), order of checks: total **60/10 s** (`hit/4`,
  before parsing) → parse (`bad_request`: `grp:` target or `conversation_id`, `call_id` not a
  lowercase UUID, `ring` not a boolean, `generation`/`epoch` not integers, bad base64 or a
  non-empty AAD; `too_long` over 24 KiB decoded) → **idempotent resend** from an in-memory ETS
  map `{sender, client_msg_id} → reply`, 5 min, lost on restart (never `sent_dedupe`) →
  `unknown_recipient` (self) / `not_friends` → **`not_e2ee`** (no MLS group) / `bad_request` (no
  device id) / `stale_epoch` → **`calls_not_ready`** (ring only: no callee device with `calls`
  and a signature key; one Postgres query) → limits: ring = 1 per (caller, callee) per 5 s, 6
  per caller per minute, 20 per pair per hour (all `hit_if_allowed`, so refused rings don't
  extend the lockout; each refusal logged at `warning` with the two ids only); `ring: false` =
  30 per pair per 10 s (restarts never count toward ring limits) → store.
- **Event:** `call_signal`, the same `event_id` (= `message_id`) for sender and recipient, one
  unlogged batch to `call_signals` (one row at a time above 5 KiB). No `message_index`, no
  `sent_dedupe`, no `status`, no receipts: `msg:ack` naming one is ignored, `msg:delete` reports
  it `gone`, reactions can't target it. Never `Push.notify/1`.
- **Delivery filter:** the inbox channel's `:calls` assign (the device row at join, kept current
  by `{:device_calls, device_id, calls?}` broadcast from `Devices.register/4` and removals).
  Live `call_signal` events go only to `calls` sockets; join/sync of a `calls` socket calls
  `Store.list_events/4` with `include_calls? = true`: both stores with `event_id > since LIMIT
  n+1`, merged by TimeUUID time, `n` taken, `has_more` from the merged length. A non-`calls`
  socket never queries `call_signals`.
- **Call push (§16.8):** `{"type":"call","v":"1"}`, FCM `priority high`, `collapse_key call`,
  `ttl 45s` (`Push.FCM.message/2` picks the options by type). For every accepted `ring: true`:
  at once to each callee device with `calls` + push token and no live channel
  (`Presence.device_online?/1`); the rest armed in `RisiMe.Calls.State` (a GenServer timer,
  `:call_fallback_ms`, 3 s) and pushed unless any `call:signal` with that `call_id` arrives from
  the callee's user first. Entries dropped after 45 s. Sends go through the push task supervisor
  with the existing retry/unregistered cleanup.
- **`calls` capability** (`Devices` keeps it); **`calls_ready` / `missing_calls`** on DM views
  of `GET /mls/groups/{id}` only (groups get them in v1.14): ready = e2ee and **each** member
  has at least one census instance (30 days) on a registered MLS device advertising `calls`;
  `missing_calls` = the usual instance list (`device_id: null` for legacy instances).
- **`GET /api/v1/calls/turn`** (`RisiMeWeb.CallsController`, `RisiMe.Calls.Turn`): username
  `"<unix expiry>:<16 hex>"`, credential `base64(HMAC-SHA1(TURN_SECRET, username))`, `ttl`
  18 000 (`TURN_TTL` overrides), `expires_at`; `stun:` URLs in their own entry without
  credentials. 20 per user per hour with `hit_if_allowed` → `429 rate_limited` + `Retry-After`;
  **`503 calls_unavailable`** when `TURN_SECRET` (< 32 bytes counts as unset, boot warning
  without the value) or `TURN_URLS` is missing. Nothing about the credential is logged.
- **`call_end` / missed calls:** nothing new on the server: `call_end` is an ordinary e2ee
  `msg:send` (30 days, sender copy, the normal inbox push); the device builds the missed-call
  line and notification. A test pins that.
- **Config to set (Harsha / root, not committed):** `TURN_SECRET` (the coturn
  `static-auth-secret`, >= 32 bytes, e.g. `openssl rand -base64 48`) and `TURN_URLS`, e.g.
  `stun:risime.risicloud.ai:3478,turn:risime.risicloud.ai:3478?transport=udp,turn:risime.risicloud.ai:3478?transport=tcp`,
  in `.env` and `pilot.env`. Until then the endpoint answers 503 and clients call with STUN only.
- **Note:** root's `f5179f1` (test DB pool) also committed my `:call_fallback_ms` line in
  `server/config/test.exs` from the shared working tree; it belongs to this change.
- **Tests:** `test/risime_web/channels/calls_v113_test.exs` (25: every §16.16 server item) and
  the v1.13 describe in `test/contract/examples_test.exs`.
- **Not done (not the server's):** coturn reachability (decision 046 ports), `scripts/turn-smoke`.

## v1.12 deleting messages and chats (§15) — READY
- **Commits:** `539ead3` (§14 fixes), `d1c65ba` (implementation), `29ce11b` (§15.12 tests),
  `0f85d3b` (contract examples; `@pending_v1_12` removed), `793b307` (MLS header vectors).
- **Cassandra migration `004_delete.cql`** (run by `scripts/run-server` before the switch; the
  new code needs it): `message_refs ((message_id), user_id, event_id)` + `ref_id`, TWCS, 30-day
  TTL, `gc_grace_seconds = 86400`; `message_index` + `deleted_at`, `deleted_by`;
  `inbox_events` + `conversation_id` (written on every append from now on; older rows fall back
  to the payload); `sent_dedupe` + `kind`; `gc_grace_seconds = 86400` on `inbox_events` and
  `group_receipts`. No `ALLOW FILTERING`, no index (a test greps every CQL string).
- **`RisiMe.Messaging.Deletes`** (`msg:delete`, `chat:clear`), behind `Messaging.Store`.
  `everyone`, in order: `sent_dedupe` hit (a `kind = 'delete'` claim → crash recovery; any
  other claim → `bad_request`) → participant / active member (`not_member`) → e2ee checks →
  **under the `mls:<conv>` advisory lock** (groups and e2ee DMs): `stale_epoch`, the landed
  role (`group_members`), the planned `delete` event id/`server_ts` and the member list → the
  AAD binding → **rate limit** (`:msg_send`, 20/10 s, counted refused or not) → `message_index`
  reads (16 in parallel) → §15.4 per target (48 h = 172 800 000 ms against the planned
  `server_ts`; admins no limit; `gone` for absent/tombstoned/other conversation/`kind` set) →
  all-or-nothing `{reason, failures}` → blob owner check → claim (`kind = 'delete'`) → enqueue
  `RisiMe.Workers.DeleteFinish` (queue `messaging`, +15 s, unique on `message_id`) → Q3d
  tombstones with `TTL(sender_id)` → `GroupReceipts.drop/1` (cancels a pending coalesced
  receipt) → Q1d per partition (sender + `recipient_id`/`recipients`, one unlogged batch each)
  → the `delete` event (deleter's copy first and unpushed, others pushed; per-recipient
  `from`/`server_ts` only for users who had the target, R4; `server_ts` = the target's TimeUUID
  time in ms) → its own `message_index` row (`kind = 'delete'`, written last = "announced") →
  step 6 (refs → reaction rows + their index tombstones, Q5d, refs partition, `Blobs.remove/1`)
  inline **and** again in the job. Everything is idempotent; the job finishes a delete the
  client never retried. All-gone requests store nothing and claim nothing (null ids).
- **AAD (§15.3):** `RisiMe.MLS.Wire` parses the cleartext `MLSMessage`/PrivateMessage header
  (version 1, wire format 2, content type application) and compares `authenticated_data` with
  `0x01 'D'` + sorted distinct 16-byte UUIDs. Verified against real OpenMLS 0.9 output
  (`set_aad` + `create_message`; vectors in `test/risime/mls/wire_test.exs`). `msg:send` refuses
  a parseable PrivateMessage with non-empty AAD (`bad_request`); unparseable bytes pass as
  before. The header's `group_id`/`epoch` are **not** compared with the request (the request
  fields keep deciding `stale_epoch`).
- **Blobs:** `Blobs.media_ids_owned_by/3` (one query: `media`, this conversation, owner = a
  sender of a deleted or already tombstoned target); others are skipped with a `warning` log
  (ids only); removal is inline (file gone at once) and repeated by the job.
- **`scope: "me"`:** no claim (a reused id of any claim → `bad_request`), rate limit, one point
  read per target of the caller's own row (`kind ∈ message/reaction` and the conversation), one
  batch delete, plus the caller's refs rows. A retry reports already-removed targets as `gone`.
- **Tombstoned rows are absent** for `msg:ack` (no CAS, status or receipt; `delete` events are
  not ackable), plaintext reactions (`unknown_target`, also for `delete` ids), receipts (`404`)
  and resends (no re-delivery). `Store.get_message/1` returns `deleted_at`, `deleted_by`, `ttl`.
- **`chat:clear`:** validate → 10/min/user → `RisiMe.Workers.ChatClear` (unique on user,
  conversation, `upto`). Q1s `event_id <= maxTimeuuid(<upto ms + 1>)`, pages of 500, exact cut
  by the 100 ns TimeUUID time; kinds `message reaction status group_receipt delete` only; one
  unlogged batch per page; refs of cleared DM messages.
- **`deletes`** capability stored; `deletes_ready`/`missing_deletes` from the same census as
  `images_ready` (installs that can still receive), also for plaintext DMs (no e2ee condition).
- **§14 fixes:** upload idempotency (replay `200`, mismatch `400`, deleted `404`) now runs right
  after the purpose cap, **before** the quota, disk guard, rate and slot; `images_ready` counts
  only installs that can still receive (tested explicitly).
- **Not done / notes:** no admin-list history on the server (receivers use the core's
  per-epoch record); E2EE reaction ciphertext stays until its TTL (accepted); members removed
  after a message lose their copies without a `delete` event (§15.8). Learning log: none.

## v1.11 encrypted images (§14) — READY
The server never sees plaintext; it stores and serves opaque `application/octet-stream` blobs.
- **Commits:** `ded467a` (blob store), `8e253e3` (images capability, contract examples).
- **Migrations:** `20261006160000` (`blobs`: `client_blob_id` with a unique `(owner,
  client_blob_id)` index, `deleted_at`, **`expires_at` nullable**, indexes on `conversation_id`,
  `(owner, purpose) INCLUDE (size) WHERE deleted_at IS NULL`, `(owner, purpose, inserted_at)`;
  replaces the v1.10 `(owner, purpose)` index) and `20261006160100` (`group_member_intervals`,
  partial unique `gmi_one_open`, **backfilled with one open interval per current active member**,
  `active_from = coalesce(joined_at, inserted_at)`).
- **Upload** (`BlobController.create`, `Blobs.begin_upload/4` → `finish/5`): every pre-body check
  in the §14.2 order, answered with `Connection: close` (checked over real HTTP: Bandit closes
  without draining 16 MiB). Content-Length is now required for every purpose (`mls` too; OkHttp
  always sends it, interop passes). The body streams in 64 KiB reads to `BLOB_DIR/.tmp/<uuid>`
  (exclusive, mode 600), hashed on the way, aborted with `413 too_large` past Content-Length;
  15-minute overall deadline; `fsync`, rename to `BLOB_DIR/<2 hex>/<id>`, then the row in a
  transaction holding `pg_advisory_xact_lock(hashtext("blobs:" <> owner))` that rechecks
  idempotency and the quota. A concurrent same-id loser deletes its file and replays `200`.
- **Rates** count committed rows (`inserted_at`), so refusals and `200` replays never count;
  `Retry-After` is when the oldest counted upload leaves the window. `mls` 60/h moved from the ETS
  limiter to this. Downloads: 600/min (ETS, refused hits not counted) and 8 concurrent.
- **Concurrency:** `RisiMe.Blobs.Slots`, a duplicate-key Registry; slot value = declared bytes,
  so in-flight bytes feed the guard. Released after the response and on process death (tested by
  killing holders, and over HTTP with a client that disconnects mid-upload).
- **Disk guard** (`RisiMe.Blobs.DiskGuard`, `config :risime, :blob_guard`): `df -Pk BLOB_DIR`
  every 30 s; `media` → `507` under max(50 GiB, 10 %) free − in-flight, or over the global live
  `media` cap (`BLOB_MEDIA_MAX`, default 200 GiB, sum cached 60 s); `mls`/`icon` down to 20 GiB;
  `warning` log + `/health` `checks.blob_storage: "low"` under 100 GiB (never a 503).
- **Downloads:** strong ETag (sha256 hex), single range incl. suffix, `416 bytes */size`,
  `If-Range`, `If-None-Match` → 304, HEAD, nosniff/attachment/`private, max-age=86400,
  immutable`; `GET /blobs/:id` has its own route without JSON `Accept` negotiation. A file swept
  after the read check is `404`. Over HTTP: `206` with `Content-Length` and no `Content-Encoding`
  (Bandit; the Caddy edge test from the release checklist is still root's).
- **Readers:** `media` owner / both DM users (from the id, independent of friendship or block) /
  a group interval with `active_until` null or ≥ the upload; `icon` current active and
  pending_add members only; `mls` unchanged. A commit `*_ref` must now name an `mls` blob.
- **Intervals** (`RisiMe.Groups.Membership`): opened at create (creator), the epoch-0 commit and
  completed adds; closed in `mark_removing` (remove and leave), `delete_member` and the reset
  cleanup. Invariant test: open interval ⇔ `state = active`, across create, remove, re-add (two
  intervals), leave, reset and a timed-out `creating` group.
- **Cleanup:** `DELETE` = `Blobs.remove/1` (soft delete, file removed at once; an icon gets a
  7-day expiry so its row goes too). **`Blobs.remove/1` is the internal delete-by-id for v1.12**
  (no authorisation inside; callers check). Group reset and the `creating` timeout call
  `Blobs.expire_conversation/1`. `BlobCleanup` hourly: `DELETE … LIMIT 1000` batches then files,
  then `.tmp` files older than 1 h (also at boot); weekly (Sun 04:53 UTC) `orphans/1`.
- **`images`** (`RisiMe.MLS.Images`): the capability is stored; `images_ready`/`missing_images`
  on `GET /mls/groups/{id}`. Census = installs that can still receive, as in the §12.1 hotfix
  (registered devices; device-less instances seen after the latest registration), so a
  reinstall's dead device id doesn't block for 30 days. A member with no images device and no
  listed instance appears with `device_id: null`.
- **Contract tests:** `@pending_v1_11` is gone; server-produced replies/errors are compared with
  their examples; the envelopes and `group_meta_icon.json` are checked against §14.4 and the
  §14.3 size formula; `media_vectors.json` positives are uploaded and served byte-exact (size,
  SHA-256 = ETag, a segment-boundary range). Tests: `blobs_v111_test.exs` (21),
  `blobs_http_test.exs` (3, real Bandit), `examples_test.exs` "v1.11" (4).
- **Notes for root:**
  - No contract deviation. Two readings worth a sentence in §14: `images_ready` uses the
    "installs that can still receive" census of §12.1 (not every raw census row), and, by the
    §14.2 order, idempotency is checked last, so a replay of a lost `201` while the user is at the
    quota, guard or rate limit gets `413`/`507`/`429` instead of `200` (clients retry later).
  - Reset expires all of a group's blobs (§14.5), including not-yet-downloaded images.
  - Before `media` ships: incremental blob backups (decision 042, root) and the Caddy `206`
    edge test. Deploy needs no new env; `BLOB_MEDIA_MAX` is optional.

## v1.10 history after a reinstall (§13) — READY
- **Sender copy (§13.1):** every plaintext DM `message` and every `reaction` is written to the
  sender's inbox too (same `event_id`, payload and TTL, never pushed). DMs (plaintext and e2ee)
  write both rows in one store request (`Store.append_event_to_all/2`: one unlogged batch under
  5 KiB, else one insert each), then broadcast the **sender's copy first**, then the recipient's
  event, then push the recipient. An idempotent resend with the index missing rewrites both rows.
- **Self-acks ignored:** `Messaging.ack/3` drops any ack by the message's sender (DM: no
  `status`, stays `sent`; group: no receipt row, no `group_receipt`).
- **`history_before` (§13.2):** migration `20261006140000` adds `app_instances.first_seen_at`
  (nullable, existing rows stay null) and `history_reset`. `MLS.census/4` sets `first_seen_at`
  (app clock) on insert or on the first connect after a removal, returns it once per socket;
  join and every `sync` reply carry it (null without a `device_id`). Every device removal
  (DELETE, logout, eviction, 60-day prune, changed key) sets `history_reset`.
- **`mls` blob quota (§13.4):** 256 MiB live per user (`:risime, :mls_blob_quota`); order
  membership → `too_large` (Content-Length) → quota → rate limit → read body → size + quota again.
  `413 quota_exceeded {used, limit}`. Migration `20261006140100` indexes `blobs(owner, purpose)`.
- **Contract:** `@pending_v1_10` is gone; the three v1.10 examples are checked. §13.6 server tests:
  `test/risime_web/channels/history_v110_test.exs`, `test/risime/backfill_sender_copies_test.exs`.
- **Backfill (§13.5):** `RisiMe.Release.backfill_sender_copies(opts)` → `Store` callback
  `backfill_sender_copies/2`: paged full scan, copies plaintext DMs whose sender and recipient
  both exist, `USING TTL <remaining> AND TIMESTAMP <source writetime>`, skips < 60 s left and
  copies that already exist (idempotent), prints counts only. `dry_run: true` is the default.
  - **Dry run on risime_dev (2026-10-06 14:10, read-only):** 2 268 965 rows scanned in ~17 s,
    934 368 plaintext DMs, 934 233 skipped (deleted load-test users), **135 to copy**, 0 existing.
  - **Run on the pilot, after the v1.10 deploy and its backup** (load the env exactly as
    `scripts/run-server` does, i.e. `infra/pilot/pilot.env` plus the repo `.env`):

        cd /home/harsha/development/risime && set -a && . ./.env && . infra/pilot/pilot.env && set +a && \
          ERL_EPMD_ADDRESS=127.0.0.1 ~/risime-run/current/bin/risime eval \
          'RisiMe.Release.backfill_sender_copies(dry_run: true)'
        # then the same with dry_run: false; a second run must report copied: 0

- **Load test** (`mix risime.loadtest`, temp dev server on 127.0.0.1:4150 with its own store
  `RISIME_DEV_DB=RISIME_DEV_KEYSPACE=risime_load`; the same day an A/B against the parent commit
  `2eb5fd7` on :4151; send→reply ms):

  | run | parent p50 / p99 | v1.10 p50 / p99 | `loadtest.md` p50 / p99 |
  |---|---|---|---|
  | 200 × 1/s, 180 s | 4.84 / 7.43; 5.18 / 7.47 | 5.10 / 7.57; 5.20 / 7.61 | 4.28 / 6.29 |
  | 2000 × 1/0.6 s, 60 s | 6.26 / 171; 8.13 / 105 | 5.98 / 100; 6.21 / 98.8 | 3.6 / 44.9 |

  0 errors and 0 missing pushes in every row above. The same-day A/B shows no regression: p99
  +2 % (standard), not worse under stress. Against the older `loadtest.md` figures, today's
  *parent* is already about +19 % (standard) and about 3× (stress) because spark2 now also runs
  the other roles' builds. The first v1.10 variant (two sequential inserts, then two parallel
  inserts) gave p99 8.17 ms and saturated the stress run; the batched write replaced it.
  One stress rerun that started within 20 s of another run (Cassandra still compacting) hit
  p99 876 ms; on a quiet machine it reproduced at 98.8 ms.

## Group-readiness hotfix (§12.1) — READY
- `Groups.readiness/1` counts only installs that can still receive: census rows with a
  `device_id` that is a **registered device** of the user, seen in the last 30 days. A row
  without a `device_id` blocks (`legacy_app`, `device_id: null`) only if it was seen **after** the
  user's latest `PUT /me/devices` (or the user has no registration). Seen before, it's superseded.
  (Tightened in 6163192 after interop 10c; 7cc60a0 was too lenient.) Tests:
  `test/risime/groups/readiness_test.exs`.
- **risime_dev (read-only counts):** 10 users; only **1** has any `devices` row (it is
  groups-capable, and the new rule makes it ready, where the old rule did not). The 2 other users on
  nightly.10 have **no registered device**, so they stay not-ready (`no_mls`) under any rule.
  The pilot log shows `PUT /me/devices` mostly answered **401 `invalid_token`** today (15 of 21)
  from outside IPs, while their sockets connect. So the app registers with a stale token. That
  is an android issue (or a token-refresh gap), not readiness.
- The DM e2ee readiness (`MLS.readiness/1`, §10.2) is unchanged.

## v1.9 groups with MLS (§12) — READY
- **Code:** `RisiMe.Groups` (REST, readiness, views, device churn), `Groups.Ops` (pending ops,
  committer naming, expiry), `Groups.Commit` (grp: commit authorisation and routing, paged
  catch-up, reset), `Groups.Policy` (the shared admin policy), `RisiMe.Blobs`,
  `Messaging.GroupReceipts`, workers `GroupTimer` and `BlobCleanup`; controllers `GroupController`,
  `BlobController`; `MLSController` dispatches `grp:` ids (commit, commits, claim, reset, view).
- **Migrations:** Postgres `20261006120000_create_groups` (`devices.capabilities`, `groups`,
  `group_members`, `group_ops`, `blobs`, `blob_readers`, `mls_commits.commit` nullable +
  `commit_ref`); CQL `003_group_receipts.cql` (`message_index.recipients set<uuid>`, table
  `group_receipts` per message, TWCS + 30-day TTL). Deploy runs both (`RisiMe.Release.migrate`).
- **Blobs:** bytes on disk at `BLOB_DIR` (default `~/risime-blobs/<env>`, e.g.
  `~/risime-blobs/prod` for the pilot release; tests use a temp dir), `<dir>/<2 hex>/<blob_id>`,
  mode 600, written via tmp + rename. 2 MiB cap (413), 60 uploads/user/hour, 30-day TTL with an
  hourly Oban cleanup (`:41`). A 256-member create uses 2 uploads (commit + Welcome by ref), so
  the contract limits leave plenty of room for re-adds.
- **Oban:** new queue `groups` (5). Jobs: committer timeout (60 s; a `naming` counter makes a
  superseded timer a no-op), op expiry (24 h, add/role), creating-group deletion (10 min).
  Args are ids only.
- **Committer naming:** first candidate = the requesting admin's device (or, for `devices` ops,
  the user's other in-group device); then online admin devices by recency (`app_instances`).
  Online = an inbox channel joined with that `device_id` (per node, `Presence.device_online?`).
  With no candidate online the op waits with `committer: null` / `committer_until: null`
  (clients must accept null there) and the first authorised device whose inbox joins is named.
  A `remove` op never names the removed user's devices (MLS can't commit its own removal), so
  a leave is always committed by another admin.
- **Socket filter:** each inbox channel tracks whether its device has `groups` (updated live when
  the device is re-registered or removed). `grp:` events and typing signals are left out of live
  pushes and join/sync pages; a page that filters down to nothing is skipped so old clients'
  cursors keep advancing.
- **Receipts:** per-message aggregation in a `PartitionSupervisor` of GenServers (loaded from
  `group_receipts` on a miss, idle entries dropped after 30 min). One row write per real
  change; a `group_receipt` at once on an all_delivered/all_read flip, otherwise coalesced to
  one per 10 s. Per node, like presence.
- **Fan-out cost (256 members, `test/risime/groups/fanout_cost_test.exs`, spark2, dev Cassandra
  in Docker):** create 90–190 ms; epoch-0 commit (256 `mls_commit` + 255 `mls_welcome` + 256
  `group_event` inbox writes) 110–160 ms; one group message to 256 inboxes 12–23 ms; 255
  concurrent acks 29–36 ms producing 2 `group_receipt` events. Inbox writes run per user in
  parallel (32 at a time) inside the group lock.
- **Tests:** every §12 example (`@pending_v1_9` is gone), all 16 cases of
  `contract/v1/group_policy_cases.json` (read in place, not copied), plus REST/ops/commit/reset,
  messaging/receipts/filter and blob suites.
- **Known limits:** committer online state, receipts aggregation and the reset rate limit
  (1/group/hour) are per node and in memory. A removed user can't fetch a `commit_ref` blob of
  their own removal commit (they're no longer a member, §12.6); harmless since they drop the
  group anyway. Intermittent, pre-existing: `reactions_test` "no push" can see a stray trailing
  push from the previous test's user under heavy machine load (cross-test push timer);
  not reproduced in 8 quiet runs.

## 0.2 progress
- [x] Version from the repo `VERSION` file: `Application.spec(:risime, :vsn)` matches it, and the
  server logs `RisiMe server <version> starting` on boot. A compile alias in `mix.exs` rebuilds
  `risime.app` when only VERSION changed.
- [x] Oban 2.24 (Postgres): queue `maintenance`. The daily cron job `RisiMe.Workers.PruneAccounts`
  (03:17 UTC) deletes OTP challenges older than 24 h and tokens revoked more than 30 days ago.
  See `docs/decisions/004-oban-background-jobs.md`.
- [x] Presence / last seen + typing (contract v1.2): `RisiMe.Presence` (ETS + monitors, 5 s
  offline grace), `users.last_seen_at` written on inbox join and leave, `presence:watch`
  (replaces the list, max 200 ids, registered users only, self allowed), `typing` forwarded as an
  ephemeral `signal` (`true` limited to 2/s, `false` never limited). Signals are never stored or
  replayed. The contract tests check all 5 v1.2 examples. Decision 007.
- [x] Observability (decision 008):
  - JSON logs with `LOG_FORMAT=json` (the default in prod); `LOG_LEVEL` overrides the level;
  - Phoenix param filtering for `code`, `token`, `body` and `secret`;
  - `:telemetry` events plus `Telemetry.Metrics` for sends (count, latency), acks, socket
    connect/disconnect/open, and inbox joins;
  - `GET /health`: Postgres + Cassandra, 200/503, no auth.
- [x] Load test (`mix risime.loadtest`, decision 011, results in `docs/status/loadtest.md`):
  - 200 users at 1 msg/s: p99 6.3 ms, 0 errors.
  - It found two bottlenecks, both fixed: the Cassandra pool rejected bursts, which crashed
    channels; and every query made two trips through the Xandra cluster process.
  - Now 3200 msg/s gives p99 47 ms with 0 errors.
- [x] Prod config: `CASSANDRA_NODES` / `CASSANDRA_KEYSPACE` / `CASSANDRA_POOL_SIZE`.
  `mix risime.cql.migrate` delegates to `RisiMe.Release.migrate_cql/1`.
- [x] **v1.3 RisiCloud Keycloak sign-in** (decision 018):
  - JOSE verification against the realm JWKS (cached, 10 min refresh, unknown-`kid` refetch at
    most once per 60 s, fail closed), with every §6.0 claim rule;
  - mapping to phone-first users, `403 not_allowlisted`, `409 identity_conflict`;
  - `GET /api/v1/auth/config`; `/auth/request` and `/auth/verify` exist only with
    `DEV_LOCAL_AUTH`;
  - socket `Authorization: Bearer` (or `token=`), with `auth:expired` and a disconnect at
    `exp + 60 s`, and `auth:refresh`;
  - prod endpoint for `https://risicloud.ai/risime/` (no `force_ssl`; `check_origin`).
  - It needs `mix deps.get` and `mix ecto.migrate` (`users.keycloak_sub`, unique
    `lower(allowlist.email)`).
- [x] **v1.4 one-time SMS phone verification** (decision 022):
  - `OtpSender` behaviour (Email, NotifyLk, DevLog, Test); the NotifyDEMO guard;
  - `POST /me/phone/verify/request` and `/confirm`, with `Retry-After` on every 429 and
    `attempts_left`; SMS budgets counted in Postgres;
  - the `PHONE_VERIFICATION` gate (default off) on REST and the socket; `auth:refresh`
    `phone_unverified`; `Contact.registered`; sockets closed on reset (Postgres trigger +
    `LISTEN`);
  - `checks.sms` in `/health`.
  - It needs `mix ecto.migrate` (`phone_challenges`, `users.phone_verified_for`, the reset
    trigger).

## Prod pilot release (`https://risime.risicloud.ai`, decisions 023 and 024)
Caddy on spark2 terminates TLS and proxies to `127.0.0.1:4000`. The server runs as a prod-mode
**release** against the existing `risime_dev` databases.

Build (root, from the latest green tag; arm64 on spark2 is verified):
```bash
cd server
MIX_ENV=prod ~/.local/bin/mise exec -- mix deps.get
MIX_ENV=prod ~/.local/bin/mise exec -- mix release --overwrite   # → _build/prod/rel/risime
```

Environment for the pilot. Secrets (`SECRET_KEY_BASE`, `POSTGRES_PASSWORD`, `NOTIFYLK_*`) come
from the repo `.env`, loaded with `set -a; . .env; set +a`, never printed.
| Var | Pilot value | Notes |
|---|---|---|
| `SECRET_KEY_BASE` | from `.env` | required |
| `POSTGRES_PASSWORD` | from `.env` | or `DATABASE_URL` instead of the five `POSTGRES_*` |
| `POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_USER` | `127.0.0.1` / `5432` / `risime` | defaults |
| `POSTGRES_DB` | `risime_dev` | default `risime_prod` |
| `CASSANDRA_NODES` | `127.0.0.1:9042` | default |
| `CASSANDRA_KEYSPACE` | `risime_dev` | default `risime_prod` |
| `PHX_HOST` | `risime.risicloud.ai` | default |
| `PHX_PATH` | `/` | default |
| `PHX_ORIGINS` | (unset → `https://risime.risicloud.ai`) | comma-separated `check_origin` list |
| `PHX_BIND` | (unset → `127.0.0.1`) | loopback only; anything else makes boot fail |
| `PORT` | `4000` | default |
| `DEV_LOCAL_AUTH` | `true` until Keycloak is live | boot warning in prod |
| `OTP_DEV_LOG` | `true` while `DEV_LOCAL_AUTH` is on | codes appear only in the server log (`[DEV OTP]`) |
| `SMS_MODE` | `log` until the RisiMe sender ID is approved | prod default is `notifylk` |
| `OIDC_ENABLED`, `PHONE_VERIFICATION` | `false`, `off` for now | see the sections below |
| `RISIME_AUTH_LOG` | (unset → `~/risime-logs/auth.log`) | fail2ban file, see below |
| `LOG_FORMAT` | (unset → `json` in prod) | |

Run:
```bash
cd server/_build/prod/rel/risime
bin/risime eval "RisiMe.Release.migrate()"   # Ecto + CQL, before every start of a new build
bin/risime start                             # foreground; under tmux or systemd
```
- **Stop with SIGTERM** (systemd's default, or `kill -TERM <beam pid>`). The release runs
  without Erlang distribution (`RELEASE_DISTRIBUTION=none`, no epmd), so `bin/risime stop` and
  `remote` don't work by design.
- Checked on :4100 (2026-10-06):
  - it listens on `127.0.0.1:4100` only, and no epmd runs;
  - `/health` returns `ok`;
  - `/dev/mailbox` and unknown routes return a 404 JSON error; a bad body returns a 400 JSON error;
  - tokens show as `[FILTERED]` in the log;
  - SIGTERM shuts it down cleanly.

### Emoji and reactions (v1.8)
- **Length:** a body is at most 16 KiB of UTF-8 (checked first) and 1–4096 extended grapheme
  clusters (`String.length/1`, Unicode 17 on Elixir 1.19; the server's count is
  authoritative).
- **Plaintext reactions:** `msg:send` with exactly one of `body`, `reaction` or `ciphertext`.
  - Order of checks: resend → `not_friends` → `e2ee_required` → `invalid_emoji` /
    `unknown_target` → the shared 20-per-10-s limit.
  - The target needs one primary-key read on `message_index`.
  - Reactions are indexed with `kind = 'reaction'` (CQL `002_message_kind.cql`, additive; the
    deploy's `migrate()` applies it). Acks naming them are ignored, and a reaction can't be a
    target.
  - The `reaction` event goes to both inboxes with the same `event_id`, with no push and no status.
- **E2EE reactions** are ordinary e2ee `msg:send` (an MLS envelope); the server can't tell them
  apart.

### E2EE with MLS (v1.7, decision 034)
- **Off on the pilot.** With no attestation key, every MLS endpoint answers
  `503 mls_unavailable` and groups report `ready: false`; plaintext chat is unchanged. Don't
  create the key until rollout.
- **Turning it on (root, at rollout, after the required app update brings everyone to v1.7):**
  1. `mix risime.attestation.gen` (or `bin/risime eval
     'RisiMe.MLS.Attestation.generate("/home/harsha/risime-keys/attestation_ed25519.jwk")'`).
     It writes `~/risime-keys/attestation_ed25519.jwk` with mode 600, refuses to overwrite, and
     prints the kid. **Back the file up with the release keystore; never commit it.**
  2. Restart the server. `ATTESTATION_KEY_FILE` defaults to that path.
     `curl …/api/v1/mls/attestation_keys` should return one key.
  3. Pin the public key (`x`, `kid`) in the app build.
  4. **Rotation:** generate a new key at a new path, put the old public JWK into a file
     `{"keys":[…]}` named by `ATTESTATION_PREVIOUS_KEYS`, point `ATTESTATION_KEY_FILE` at the new
     key, and restart. Both keys are then published.
- **Census:** the socket connect carries `device_id` and `app_version`. Pre-v1.7 apps count as
  `legacy_app`: one instance per dev token, or per user for Keycloak tokens. A conversation is
  ready only when every instance of both members seen in the last 30 days is MLS-capable.
- **Ciphertext cap:** 24 KiB decoded (PROTOCOL §10.3, raised from 16 KiB so a max-size text fits
  in the envelope plus MLS framing; decision 034 still says 16 KiB and is superseded on this
  point).
- **REST calling device:** commits need the `X-Device-Id` header; claims accept it to exclude the
  caller.
- **Load test:** `mix risime.loadtest --e2ee` runs the e2ee send path with opaque ciphertext. It
  needs a server started with `ATTESTATION_KEY_FILE` pointing at a temp key.

### Invites and friends (v1.6, decision 030)
- **Deploy:** `bin/risime eval "RisiMe.Release.migrate()"` runs Ecto, then CQL, then
  **`RisiMe.Release.migrate_friendships/0`**. The last turns every existing conversation pair
  into friends (Harsha ↔ Shenika on the pilot data), idempotently; it takes about 8 s on today's
  dev data. It was already run once on `risime_dev`, creating 1 friendship.
- **Admin:**
  - `mix risime.friends --pair <phoneA> <phoneB>` makes two users friends (fixtures, interop);
  - `mix risime.friends --migrate` runs the backfill by hand;
  - `mix risime.user.disable <phone>` removes an invited (or any) member: sign-in stops and
    their sockets close.
- **Config:** `INVITE_LINK` (default `https://risicloud.ai/app/risime/`) is the link in
  invites. The server never emails or texts invitees.
- Only friends can message, type or see presence. `/contacts` returns friends only.

### Push notifications (v1.5, decision 028)
- **Off** until Harsha provides the Firebase service-account key. Devices register
  (`PUT /api/v1/me/devices/{id}`) regardless, so tokens are stored the moment push turns on.
- **What a push is:** a data-only FCM message `{"type":"inbox","v":"1"}`, sent only when the
  recipient has no live inbox channel. At most one push now and one trailing push per user per
  10 s. Never any content, sender, phone or name.

**Enabling push** (needs Harsha's key):
1. In the Firebase console (project with the Android app `lk.codegen.risime`): Project settings →
   Service accounts → "Generate new private key". This downloads a JSON file.
2. Put it on spark2 at `~/risime-keys/fcm-service-account.json`, outside the repo, and run
   `chmod 600` on it. **Never commit it, and never paste its contents anywhere.**
3. Set in the server's environment (the pilot unit's env file, or the shell for a temp server):
   - `FCM_ENABLED=true`
   - `FCM_SERVICE_ACCOUNT_FILE=/home/harsha/risime-keys/fcm-service-account.json` (this is the
     default, so it can be left out)

   `project_id` is read from the file.
4. Restart the server. The boot log must **not** show "FCM_ENABLED=true but
   FCM_SERVICE_ACCOUNT_FILE is missing".
5. Test: sign in on a phone (an app built with `google-services.json`, decision 026), close the
   app, and send it a message from another account. A wake-up should arrive within seconds.
6. Rollback: `FCM_ENABLED=false` and restart.
- FCM failures are logged with the HTTP status and FCM's error code only, never a push token,
  access token or key. `UNREGISTERED` / `INVALID_ARGUMENT` tokens delete their device.
- Devices unseen for 60 days are pruned daily (Oban).

### fail2ban auth log
One line per authentication failure, in `RISIME_AUTH_LOG` (and in the normal log at info):
```
2026-10-06T08:15:30Z risime auth_failure ip=203.0.113.9 kind=invalid_code path=/api/v1/auth/verify
```
- **Format:** `<UTC ISO-8601 to the second>Z risime auth_failure ip=<client IP> kind=<kind> path=<route path>`.
  Fields contain only `[A-Za-z0-9.:/_-]`; there is never a phone, email, token, code or query
  string.
- **Kinds:** `invalid_code`, `too_many_attempts`, `rate_limited`, `invalid_token`,
  `not_allowlisted`, `identity_conflict`, `socket_refused`, `phone_code_invalid`.
- **Client IP:** `X-Forwarded-For` (its last entry) is used only when the TCP peer is loopback,
  which is Caddy on the same host; otherwise the peer address is used.
- **fail2ban:** `failregex = ^.* risime auth_failure ip=<HOST> `, and `ignoreip = 127.0.0.1/8 ::1`
  (direct local requests are logged with the loopback IP).
- **Per-IP limits** (on top of the existing ones), each answering `429 rate_limited` with
  `Retry-After`: `POST /auth/request` 10 per IP per 15 min, `POST /auth/verify` 20 per IP per
  15 min. Refused socket upgrades are logged for fail2ban, not limited in the app.

## How to run (spark2)
```bash
# databases (once)
docker compose --env-file .env -f infra/docker-compose.dev.yml up -d
# first time / after every pull (new deps, new migrations)
cd server
~/.local/bin/mise exec -- mix deps.get
~/.local/bin/mise exec -- mix ecto.migrate   # required after pulling 0.2: creates the Oban tables
~/.local/bin/mise exec -- mix risime.cql.migrate                         # risime_dev
~/.local/bin/mise exec -- mix risime.cql.migrate --keyspace risime_test  # the test alias also runs this
```
**The test server** (tmux `risime-server`, 127.0.0.1:4000) runs from a release-tag worktree
`~/risime-run/<tag>` (decision 006). Start or switch it only with `scripts/run-server [<tag>]`;
`scripts/nightly-release` does this after the gates pass. Never start a server from the shared
checkout on :4000.

For your own live testing, use a temporary instance from the checkout on another loopback port,
and stop it afterwards:
```bash
cd server && PORT=4100 LOG_LEVEL=warning ~/.local/bin/mise exec -- mix phx.server
curl -s http://127.0.0.1:4100/health        # {"status":"ok",...}
~/.local/bin/mise exec -- mix risime.loadtest --users 200 --duration 180 --interval 1000
~/.local/bin/mise exec -- mix risime.loadtest --cleanup   # only after a killed run
```
The load test puts throwaway `+999…` users in the dev allowlist while it runs and deletes them
afterwards. Don't run it while someone is testing against the dev DB.

Env: `LOG_FORMAT=json|text`, `LOG_LEVEL`, `CASSANDRA_POOL_SIZE`; prod also needs
`CASSANDRA_NODES` and `CASSANDRA_KEYSPACE` (docs/PROD.md).

Auth env (decision 018):
| Var | Default | Meaning |
|---|---|---|
| `OIDC_ENABLED` | `false` (test: on) | Accept Keycloak JWTs; `"oidc"` appears in `/auth/config` |
| `OIDC_ISSUER` | `https://risicloud.ai/realms/aoa` | Exact `iss`; discovery base |
| `OIDC_CLIENT_ID` | `risime` | `azp` / `aud` check; returned by `/auth/config` |
| `OIDC_JWKS_URL` | (discovery) | Skip discovery and use this JWKS URL |
| `DEV_LOCAL_AUTH` | dev/test `true`, prod `false` | Dev OTP login + opaque tokens. Prod logs a warning if on |
| `PHX_HOST` / `PHX_PATH` | `risicloud.ai` / `/risime` | Prod public URL; `check_origin` is `https://<PHX_HOST>` |

Phone verification env (decision 022). The `NOTIFYLK_*` values live only in the repo `.env`;
the server reads them from the OS environment when sending and never logs or prints them.
| Var | Default | Meaning |
|---|---|---|
| `PHONE_VERIFICATION` | `off` | `required` turns on the gate (`/auth/config` advertises it) |
| `SMS_MODE` | prod `notifylk`, else `log` | `log` delivers codes only to the `[DEV OTP]` log (needs `OTP_DEV_LOG=true`) |
| `NOTIFYLK_USER_ID` / `NOTIFYLK_API_KEY` / `NOTIFYLK_SENDER_ID` | (in `.env`) | Notify.lk credentials. While the sender ID is `NotifyDEMO`, OTP SMS are **blocked** (503) |
| `NOTIFYLK_ALLOW_DEMO_OTP` | unset | `true` lets OTPs go out from NotifyDEMO. Harsha's call only; it risks the Notify.lk account |
| `SMS_BALANCE_WARN` | `100` | Below this balance, `checks.sms` is `low_balance` and a warning is logged |

**Turning phone verification on once the `RisiMe` sender ID is approved:**
1. In `.env`, set `NOTIFYLK_SENDER_ID=RisiMe` (no code change).
2. Start the server with `SMS_MODE=notifylk` and `PHONE_VERIFICATION=required`. The boot log
   must show neither "SMS OTP disabled…" nor "PHONE_VERIFICATION=required but no SMS can be
   sent".
3. `curl -s http://127.0.0.1:4000/health`: `checks.sms` should be `ok` within 10 min (the
   background poll), not `demo_sender_blocked`, `inactive` or `low_balance`.
4. Have one tester sign in through Keycloak, tap "Send code", and confirm.
5. Rollback: `PHONE_VERIFICATION=off` (users read as verified; nothing is lost).

Before that, the whole flow can be tried with `PHONE_VERIFICATION=required SMS_MODE=log
OTP_DEV_LOG=true`. The code appears as `[DEV OTP] +9477•••••01: <code>` in the server log.

**Enabling RisiCloud sign-in once the `risime` client exists:**
1. The RisiCloud lead creates client `risime`: public, standard flow + PKCE (S256), the redirect
   URI from the Android build (decision 014), and the default `email` client scope (so `email`
   and `email_verified` are in the *access* token).
2. Verify one real access token (for example from the Android debug build), with
   `RisiMe.Auth.JWT.verify(token)` in `iex -S mix`. Expect `{:ok, %{sub, email, exp}}`. If it
   fails, the error atom names the rule: `:bad_typ`, `:bad_audience`, `:email_not_verified`,
   `:kty_mismatch`, …
3. Make sure every tester's email is on the allowlist (`mix risime.allow`; emails are unique,
   case-insensitive).
4. Start the server with `OIDC_ENABLED=true`. Keep `DEV_LOCAL_AUTH=true` on the test server
   during the switch: both modes then work at once.
5. A tester who gets **409** (their phone is bound to another Keycloak account) needs
   `mix risime.allow --rebind <phone>`, after which they sign in again.
Config comes from the repo `.env`: `POSTGRES_PASSWORD`, `SECRET_KEY_BASE`, `OTP_DEV_LOG`, `SMTP_*`.

## Allowlist
```bash
cd server
~/.local/bin/mise exec -- mix risime.allow --phone +94… --email … --name "…" --company Rise
~/.local/bin/mise exec -- mix risime.allow.list
```
`risime_dev` has 2 allowlist entries (Harsha and the test partner) and 1 registered user
(checked 2026-10-05 after the load tests; no `+999` load-test rows left).

## Reading the dev OTP
With `OTP_DEV_LOG=true`, each code is logged as `[DEV OTP] <phone>: <code>`:
```bash
tmux capture-pane -p -t risime-server -S -500 | grep "DEV OTP" | tail -3
```
The OTP email is also in the Swoosh dev mailbox at `http://127.0.0.1:4000/dev/mailbox`
(from the laptop through the tunnel: `http://127.0.0.1:4400/dev/mailbox`).
Codes are never logged unless `OTP_DEV_LOG=true`.

## Smoke test done (S7, on localhost)
- `/auth/request`: 200 for both allowlisted and unknown pairs; 422 `invalid_phone`.
- `/auth/verify`: 401 `invalid_code` for a wrong code; 200 with token + user for the right one.
- `/me`, `/contacts` (empty, because only one person is allowlisted), and `/auth/logout` 204,
  after which the token gets 401.
- WebSocket: 101 with a valid token and 403 with a bad one. Over a raw V2 WebSocket: join
  `inbox:<own id>` returns `{events, has_more, server_time}`; another user's inbox returns
  `unauthorized`; a self-send returns `unknown_recipient`.

## Notes for Android / root
- A `message` event's `event_id` equals its `message_id`. This makes re-delivery idempotent.
  Clients must not rely on it.
- A `msg:send` / `sync` / `msg:ack` payload that is malformed (for example a `client_msg_id` that
  isn't a UUID, or an unknown ack status) gets error reason `bad_request`, as specified in
  PROTOCOL.md v1.1.
- Sending to yourself is `unknown_recipient`.
- After logout the server sends a `disconnect` to that token's sockets.
- Rate limits: OTP requests 3 per phone per 15 min (counted whether or not the pair is
  allowlisted); sends 20 per 10 s per user. Idempotent resends of an already-sent `client_msg_id`
  don't count. `docs/decisions/001-rate-limiter.md` explains the limiter.

## Known limits
- **E2EE (v1.7):**
  - `generation` is always 1, because group re-creation isn't specified yet.
  - The optional PrivateMessage header check isn't done (the server doesn't parse MLS).
  - The e2ee send path costs about 2–3× plaintext at p99 (a second inbox write plus a group
    lookup); see decision 034.
  - The MLS blobs in the contract examples are placeholders, until crypto supplies real ones.
- **Invites and friends:**
  - The friend-request rate limit (30 per 24 h) is in memory and resets on restart. The invite
    limits are counted in Postgres.
  - Replies are identical, but the per-path timing isn't perfectly constant; the rate limits
    are the real defence against probing.
- **Push:**
  - Never tested against real FCM (no key yet). The token exchange and send are tested against
    `Req.Test` stubs with a generated key, shaped like Google's documented responses.
  - Coalescing and the online check are per node and in memory: a restart can drop one pending
    trailing push, and the app catches up on next open.
- **Prod pilot:**
  - It shares the `risime_dev` databases with the test server (:4000) until the prod
    environment exists (decision 010).
  - The in-app limiter is in memory and resets on restart; fail2ban's firewall bans persist.
- **v1.4 phone verification:**
  - No real SMS has been sent: the sender is still NotifyDEMO, the guard blocks it, and the
    override was never used. Notify.lk success parsing and the status endpoint are tested only
    against `Req.Test` stubs shaped like their documentation.
  - While the gate is on, users who only ever used the dev login count as unregistered to
    Keycloak users (dev verification isn't stored).
  - The SMS budgets count, then insert, so concurrent requests can overshoot a cap by one or
    two.
  - The reset NOTIFY fires on commit. Tests drive the listener directly; the trigger was checked
    live on :4100 (a `--rebind` from another VM closed the open socket).
- **v1.3 auth:**
  - Not yet checked against a real token from the realm, because the `risime` client doesn't
    exist yet. Every rule is tested only with locally generated RSA/EC/Ed25519 keys. The temp
    server did fetch the real realm JWKS.
  - JWT mappings are cached per token until expiry, so allowlist removal and `--rebind` take
    effect at the next token (about 5 min) and, on other nodes, only then.
  - A dev-token socket that refreshes with a JWT gets a deadline; its expiry disconnect then
    closes every socket of that dev token.
  - The expiry disconnect relies on Bandit running `connect/3` in the WebSocket process (true
    for HTTP/1 WebSockets).
- **TimeUUIDs:** `uniq` 0.6's `uuid1` timestamps wrap every 0.1 s, which breaks timeuuid
  ordering, so ids come from `RisiMe.TimeUUID` (strictly increasing per node). `uniq` is only
  used for v4 in tests.
- **Cursor race:** event ids are generated just before the write. Two concurrent writers to the
  same inbox (a message plus a status) can commit in the opposite order, a few ms apart. A
  connected client still gets both live. A client that reconnects in that window with a cursor
  past the earlier event would miss it. Fix later with per-inbox write serialisation, or with
  a server-side overlap re-read on join.
- The rate limiter is in-memory and per node, so it resets on restart.
- `/auth/request` takes slightly longer for allowlisted pairs (DB insert + email), a timing
  signal. That's acceptable for the internal 0.1 pilot.
- Tests truncate the Cassandra tables once per run (`test_helper.exs`), not between tests,
  because TRUNCATE is slow. Every test uses fresh user ids, so partitions never overlap.
- SMTP is wired up (`RISIME_MAILER=smtp` plus `SMTP_*`), but dev uses the local mailbox.
- **Presence is per node** and in memory. After a restart everyone shows offline (with their
  stored `last_seen`) until they reconnect. Clustering needs a Tracker-backed `RisiMe.Presence`.
  A hard crash skips the leave write, so `last_seen` falls back to the last join.
- Typing checks the recipient with one Postgres PK lookup per push.
- No metrics reporter is attached yet: the metrics are defined, and Prometheus or LiveDashboard
  comes with prod monitoring.
- Throughput is bounded by Cassandra LWTs (send idempotency, ack CAS) and by per-channel work.
  The dev compose routes Cassandra through `docker-proxy`. See `docs/status/loadtest.md`.
- Oban job args are stored in plain text in Postgres: never put secrets, OTPs or message bodies
  in them.
- The `inbox_events` partition grows per user. Monthly bucketing is in the backlog (0.4).
