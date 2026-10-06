# 035 — Android E2EE phase A: storage, pipeline and outbox against an `MlsEngine` interface

## Context
Contract v1.7 (§10) and decision 033 fix the rules. The crypto role is building the new
`risime-mls-ffi` API in parallel, so the client is built against an interface first and tested
with a fake. The real core plugs in during phase B.

## Decision
1. **`MlsEngine`** (`data/mls/MlsEngine.kt`) is the app's view of the core:
   - groups keyed by conversation + generation;
   - pending commits, merged only after `commitAccepted` (the server's `200`);
   - key packages; attestation set from the server's JWS;
   - `decrypt` returns the **authenticated sender** (`user_id`/`device_id`).

   `AppContainer.mlsEngine` stays **null** until phase B, and with null the app behaves exactly
   like v1.6: e2ee events are skipped and the cursor moves on.
2. **Storage: Room v3**, in the message database:
   - `mls_kv(namespace, key, value)`, with every value AES-256-GCM sealed and the AAD set to
     namespace + key;
   - `mls_pending` (e2ee events ahead of their epoch, ordered by arrival `seq`, because TimeUUID
     strings don't sort by time).

   `SealedKvStore` maps the core's begin/commit/rollback to **SQLite SAVEPOINTs inside the
   caller's Room transaction**. Phase B passes Room's connection inside `withTransaction`.
3. **The database key:** 32 random bytes, wrapped by a Keystore AES-GCM key **without user
   authentication** (background sync must decrypt), in `noBackupFilesDir/mls_dbkey.bin`.
   - A file instead of DataStore: it's excluded from backup anyway, and it can't be read before
     the key exists.
   - Logout wipes `mls_kv`, `mls_pending` and the key file.
4. **Backup:** `data_extraction_rules.xml` (Android 12+, cloud and device transfer) and
   `backup_rules.xml` (older versions) exclude every domain. `allowBackup` stays false.
5. **The pipeline runs inside each event's transaction**, so the decryption, insert, seen event,
   cursor and pending rows commit together. A group change replays the conversation's pending
   events in arrival order, restarting after each further change.
6. **Outbox:** plaintext is kept locally and encrypted at send time.
   - `stale_epoch` → catch up (`GET …/commits?since_epoch`, applied out of band without moving the
     cursor) → re-encrypt with the same `client_msg_id`, at most 3 times, then the rate-limit
     backoff. The message stays `PENDING` and is never `FAILED`.
   - `e2ee_required` → the same catch-up, then encrypt.
7. **Upgrade on chat open:** `GET group` → claim the peer **and** my own other devices → epoch-0
   commit with the Welcome → merge on `200`.
   - `epoch_conflict` at creation means the race was lost: discard, show "Setting up…", wait for
     the Welcome.
   - `not_ready` / `missing` drive the strip: "<name> needs to update" or "waiting for <name>'s
     phone".
   - `mls_unavailable` → plaintext, with no strip.
8. **Census:** `device_id` and `app_version` on every socket connect. Registration sends the MLS
   key with `push_token` null when Firebase is absent. A `503 mls_unavailable` falls back to a
   plain v1.5 push registration.
9. **Real crypto on the JVM:** `cargoBuildHost` (`scripts/build-rust-host`), with the JNA jar on
   the test classpath and `jna.library.path` set per test run. A failed host build only warns
   and those tests skip.

## Consequences
- Phase B:
  - implement `MlsEngine` over the new FFI;
  - pass the KvStore callback the Room connection inside `withTransaction`;
  - run the membership-commit executor;
  - top up key packages after a Welcome;
  - real-crypto JVM tests of the pipeline;
  - the E2EE block in `LiveInteropTest`.
- While the crypto role's uncommitted edits break `crypto/`, `cargoBuildAndroid` fails the debug
  build on spark2. `-Prisime.crypto=false` gives a green gate without the core.
