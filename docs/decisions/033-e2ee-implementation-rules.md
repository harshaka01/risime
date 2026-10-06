# 033 — E2EE implementation rules (from the v1.7 reviews)

**Status:** accepted 2026-10-06 (root, merging the crypto, server and android reviews of contract
v1.7).

- **Readiness by census, not by push registration.** Every socket connect reports `device_id` and
  `app_version`. A conversation upgrades only if no non-MLS app instance of either member was seen
  in 30 days. Pre-v1.7 apps count as "legacy app". A `required` update precedes switching E2EE on.
- **MLS state lives in the message database.** It is a sealed key-value table in the app's SQLite
  database, not a separate `mls.db`; this amends decision 012 §6.
  - The MLS core's `KvStore` begin/commit map to **SQLite savepoints inside the outer Room
    transaction** that Kotlin opens around decrypt → insert → cursor, so a crash can't lose a
    message whose ratchet key is already used.
  - The database key is wrapped by a Keystore key that needs no user authentication, so background
    sync can decrypt.
  - Backup and device-to-device transfer exclude the databases.
- **Attestation is verified in Rust,** from the leaf's `application_id`, between staging and
  merging, against the pinned keys. Kotlin never decides trust.
- **The group id carries a generation**, so a re-created group never collides with stale events.
- **Commits use the PublicMessage framing.** The server routes on the declared lists; clients
  verify the proposals.
- **Fan-out is one inbox event per member user.** Welcomes list `to_devices`; the committing device
  skips its own commit.
- **Pending commits** merge only after the server's `200`. An `epoch_conflict` clears the pending
  commit, then the client catches up and re-evaluates.
- **The server keeps a commit log** (`GET …/commits?since_epoch`), because the Postgres
  compare-and-set and the Cassandra fan-out aren't atomic.
- **Testing:** a host build of `risime-mls-ffi`, loaded through JNA, lets JVM unit tests and the
  live interop gate run real MLS (`scripts/build-rust-host`, root).
