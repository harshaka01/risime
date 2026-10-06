# 034 — Server: E2EE routing with MLS (contract v1.7)

## Context
Contract v1.7 §10 and decisions 012, 032 and 033: the server attests MLS device keys, stores key
packages, orders commits and fans out opaque MLS messages. It must never see content, and E2EE
must stay **off on the pilot** until root creates the attestation key at rollout.

## Decision
- **Off switch:** `RisiMe.MLS.Attestation` loads `ATTESTATION_KEY_FILE` (default
  `~/risime-keys/attestation_ed25519.jwk`) at boot. Without it:
  - every MLS endpoint, and `PUT /me/devices` with `mls`, answers `503 mls_unavailable`;
  - `GET /mls/groups/{id}` reports `ready: false`;
  - push-only devices and plaintext chat are unchanged.
- **Attestation key:**
  - created by `mix risime.attestation.gen [path]` as an Ed25519 private JWK, mode 600; it
    refuses to overwrite and prints only the kid;
  - `kid` is the RFC 7638 thumbprint;
  - `ATTESTATION_PREVIOUS_KEYS` is a file of still-trusted public JWKs, published with any `d`
    stripped;
  - the private key sits in a struct whose `inspect` shows only the kid;
  - JWS header `{alg: EdDSA, kid, typ: risime-attest+jwt}`; claims
    `{aud: risime-mls, user_id, device_id, signature_key, iat, v: 1}`.
- **Devices:** `devices` gains `mls_signature_key`, `mls_attestation` and `mls_attested_at`, and
  `push_token` becomes nullable.
  - A `PUT` needs a push token or an MLS key (32 bytes).
  - A changed key counts as `removed` plus `added`, at most 5 per day.
  - FCM cleanup and a token moving to another install **clear only the token** on MLS devices.
  - Real removals (DELETE, dev logout, eviction, the 60-day prune, a changed key) emit
    `mls_membership` to both members of every e2ee conversation of that user.
- **Census** (`app_instances`, keyed on `(user_id, instance_key)`):
  - every socket connect upserts `device:<device_id>`, with `app_version` and `last_seen_at`;
  - a connect without a valid `device_id` (a pre-v1.7 app) upserts **one legacy instance per dev
    token (`legacy:token:<id>`) or per user for Keycloak tokens (`legacy:jwt`)**;
  - readiness looks at instances seen in the last 30 days: legacy → `legacy_app`; a device_id
    without an MLS identity → `no_mls`; a member without any MLS device → `no_mls`.
- **Key packages** (`mls_key_packages`):
  - upload: at most 100 per request, each 1 byte to 4 KiB; the newest 100 normal packages are
    kept; one last-resort package per device, which a new one replaces;
  - the claim runs in one transaction: `DELETE … WHERE id = (SELECT … FOR UPDATE SKIP LOCKED)`
    per device, falling back to the last-resort package, which is never consumed;
  - all or nothing for friends and yourself; the calling device is excluded;
  - 30 claims per minute per user;
  - `mls_key_packages_low {count}` goes to the owner's channels when a device drops below 20.
- **Calling device on REST:** the contract doesn't say how a REST call names its device, so the
  server reads the **`X-Device-Id` header**. It is required for commits (to name `from_device` and
  check group membership) and optional for claims (to exclude the caller). **Root should add it
  to PROTOCOL §10.2.**
- **Commits:**
  - Validation and the compare-and-set run in one Postgres transaction under
    `pg_advisory_xact_lock(hashtext('mls:' <> conversation_id))`. The commit-log row and every
    inbox event (Cassandra, `Messaging.publish_mls/3`) are written inside that critical section,
    before the `200`.
  - The commit log keeps the last 1000 epochs, or 30 days.
  - Epoch 0 creates the group at epoch 1 and must add exactly all current MLS devices of both
    members except the caller's. It returns `not_ready` when readiness fails.
  - `generation` stays 1; re-creation isn't specified yet.
- **Fan-out, one event per member user:** `mls_commit` to both members, `mls_welcome` to each
  user owning added devices (with `to_devices`), `mls_membership` to both members. **No push for
  `mls_*` events.**
- **`msg:send`:** resend → `not_friends` → e2ee checks (plaintext to e2ee → `e2ee_required`;
  ciphertext to plaintext, or from a socket without `device_id` → `bad_request`;
  generation/epoch mismatch → `stale_epoch`) → rate limit → store. Ciphertext over 16 KiB is
  `too_long`.
  - The e2ee message event carries no body and is written to the recipient's **and** the
    sender's inbox; only the recipient is pushed.
  - The optional check of the PrivateMessage cleartext header isn't implemented: the server
    doesn't parse MLS.
- **Fixtures:** `mls_device!/2`, `e2ee_group!/2`, `with_attestation_key/1` (a temp key), and
  `mix risime.loadtest --e2ee` (MLS devices plus e2ee groups for the ring, random opaque
  ciphertext; cleanup removes the groups).

## Consequences
- **E2EE send cost.** At 200 users and 1 msg/s on a temporary :4100 server: plaintext p95/p99
  6.7/7.4 ms; e2ee p95/p99 14/21 ms, 0 errors. The e2ee path adds a group lookup and a second
  inbox write (the sender copy). An ETS cache for the group row, updated in the commit critical
  section, is the obvious next step if it matters.
- The pilot stays on plaintext until root runs `mix risime.attestation.gen` and restarts the
  server with the key, after the required app update has brought every app to v1.7.

**Amended 2026-10-06:** the e2ee ciphertext cap is **24 KiB** decoded (PROTOCOL §10.3), not 16 KiB; 16 KiB couldn't hold a max-size text in the envelope plus MLS framing.
