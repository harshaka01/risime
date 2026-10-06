# 036 — MLS core API for contract v1.7 (`risime-mls`, `risime-mls-ffi`)

**Status:** accepted on 2026-10-06 (crypto). It implements the crypto review of v1.7, and the
rules in decisions 012 §6, 032 and 033.

## Decisions
- **Storage is our own `KvStorage`** (an OpenMLS `StorageProvider`) over a 6-method `KvStore`
  trait:
  - `get`, `put` and `delete`;
  - nestable `begin`, `commit` and `rollback`.

  We don't use `openmls_sqlite_storage`: it pulls in `rusqlite`/C SQLite, which breaks the
  pure-Rust Android build (decision 012 §2), and it would own its connection, which breaks
  decision 033's single outer transaction.
  - The entity encoding (label ‖ JSON key ‖ version, JSON values) follows
    `openmls_memory_storage` (MIT).
  - Every key starts with `mls/`. The device record is at `risime/self/v1`.
- **One nested transaction per public call.** It is rolled back on any error, including a
  rejected credential after `into_group` or after staging a commit. A failed call therefore leaves
  no trace: the consumed key package comes back, and so does the old group.
  - No `MlsGroup` cache is kept in memory, so a rollback can't leave stale objects behind.
  - Groups are loaded per call, which costs about a millisecond.
- **Trust lives in Rust.** `TrustAnchors` verifies the server's EdDSA JWS (`typ`
  `risime-attest+jwt`, `aud` `risime-mls`, `v` 1):
  - from the leaf's `application_id`;
  - against pinned JWKs, matched by RFC 7638 thumbprint or the JWK's own `kid`;
  - it requires `user_id`/`device_id` to equal the credential identity, and `signature_key`
    (standard base64) to equal the leaf key.

  OpenMLS has no validation hook, so the core checks:
  - added key packages before the commit;
  - all leaves after `into_group`, before the transaction commits;
  - added and updated leaves of a staged commit before merging;
  - that an update or update path never changes a leaf's identity.
- **Leaf capabilities list `last_resort`.** OpenMLS rejects a last-resort key package otherwise.
- **Commits:**
  - `create_group`/`add_members`/`remove_members` return a `PendingCommit`;
  - `commit_accepted` merges and `commit_rejected` clears;
  - at epoch 0, `commit_rejected` deletes the group (creation-race loser);
  - a foreign commit merged over our pending one reports `discarded_own_pending`;
  - `create_group` replaces a leftover epoch-0 group, and otherwise returns `GroupExists`.
- **Welcome re-join.** The core reads the group id and epoch from the `ProcessedWelcome`. It
  replaces a local group only if that group is inactive or at an older epoch. Otherwise it
  returns `GroupExists`.
- **Errors:**
  - Messages from epochs older than `MAX_PAST_EPOCHS` (3) map to `WrongEpoch`, like future
    epochs.
  - Any OpenMLS error wrapping a storage error maps to `Storage`.
  - Standalone proposals are rejected (`Malformed`), because RisiMe never sends them and a
    stored foreign proposal would block sending.
- **FFI extras for tests:**
  - `InMemoryKvStore` (a Rust store that Kotlin can pass as `KvStore`);
  - `TestAttestor` (a signer with a seeded key, never pinned in production).

  JVM tests and the interop gate use them to run real MLS without the server.
- **Threading.** `MlsClient` holds a mutex and calls `KvStore` on the caller's thread, so Kotlin
  can wrap a call in its Room transaction. The store must not call back into `MlsClient`.

## Consequences
- `MlsClient(identity)`, `createKeyPackage`, `addMember` and `removeMember` are gone. The android
  role uses `MlsClient.open(store, userId, deviceId, trustedKeysJwks)` and the
  `PendingCommit` flow. `selfTest()` and `mlsInfo()` are unchanged (still `"ok: epoch 3, …"`).
- Values are stored unencrypted by the core. Sealing is the app's table (decision 033).
- Server interop: the server's JOSE-signed attestation must verify in `TrustAnchors.verify`. The
  fixture binary signs exactly the way the server does. A server-signed JWS checked through the
  host library is the next interop test.
