# crypto/ — RisiMe E2EE core (Rust)

Owned by the **root** session. Groundwork for Release 0.3 (MLS end-to-end encryption).
Plan for the Android binding: `docs/decisions/012-e2ee-android-binding-plan.md`.

## Layout
- `Cargo.toml`: Cargo workspace (`Cargo.lock` is committed).
- `risime-mls/`: the core crate. A small bytes-in/bytes-out API over
  [OpenMLS](https://openmls.tech) 0.9 (`openmls_rust_crypto` 0.6, `openmls_basic_credential` 0.6).
- `risime-mls-ffi/`: the UniFFI binding (`libuniffi_risime.so`, Kotlin `lk.codegen.risime.crypto`).

Ciphersuite: `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519` (0x0001, the RFC 9420
mandatory-to-implement suite). Pure Rust: there is no C code in the dependency tree.

## Toolchain (spark2, no sudo)
rustup in `~/.rustup` / `~/.cargo` (profile minimal plus `rustfmt` and `clippy`). Use
`~/.cargo/bin/cargo`, because non-interactive SSH does not put it on `PATH`.

```sh
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal --no-modify-path
~/.cargo/bin/rustup component add rustfmt clippy
~/.cargo/bin/rustup target add aarch64-linux-android x86_64-linux-android   # for the Android binding later
```

## Test gate
```sh
cd crypto
~/.cargo/bin/cargo fmt --check && ~/.cargo/bin/cargo clippy --all-targets --all-features -- -D warnings && ~/.cargo/bin/cargo test
```

## API (`risime_mls::Client`, one per device; contract v1.7 §10, decisions 033 and 036)
Every MLS object crosses the API as TLS-serialised `Vec<u8>`. The server and the app only
handle opaque bytes.

| Call | What it does |
|---|---|
| `Client::open(store, DeviceId, validator)` / `in_memory` | Opens or creates the device's state in the app's `KvStore`. The identity is `"<user_id>/<device_id>"` |
| `set_attestation(jws)` | Stores the server's JWS after verifying it against the pinned keys. It goes into every leaf (`application_id`) |
| `generate_key_packages(n)` / `generate_last_resort_key_package()` | 1..=100 single-use key packages; one reusable `last_resort` package |
| `create_group(gid, kps)` / `add_members` / `remove_members` | Returns a **`PendingCommit`** `{commit, welcome?, epoch (source), added, removed}`. Nothing is merged yet |
| `commit_accepted(gid)` / `commit_rejected(gid)` | Server `200` → merge; `409` → drop (at epoch 0 the local group is deleted) |
| `join_from_welcome(welcome)` | Returns `JoinedGroup`. Replaces a local group with the same id if it is removed or older |
| `encrypt` / `decrypt` / `process` | `process` returns `Application{sender, plaintext, epoch}` / `Commit{epoch, committer, added, removed, removed_self, discarded_own_pending}` / `OwnEcho` |
| `delete_group`, `has_group`, `has_pending_commit`, `epoch`, `epoch_authenticator`, `members`, `is_active` | Housekeeping and inspection |

- **Trust.** `CredentialValidator` (`TrustAnchors` = pinned Ed25519 JWKs, `kid` = the RFC 7638
  thumbprint) checks:
  - every key package we add;
  - every leaf of a group we join;
  - every leaf a commit adds or updates.

  A failure is `UntrustedCredential`, and the whole call is rolled back.
- **Storage.** `KvStore` has `get`/`put`/`delete` plus nestable `begin`/`commit`/`rollback`
  (SQLite `SAVEPOINT`/`RELEASE`/`ROLLBACK TO`). Every call runs in one nested transaction, and the
  core never closes the app's outer transaction.
- **Errors.** `MlsError` has the variants `Malformed`, `InvalidKeyPackage`, `UntrustedCredential`,
  `MissingAttestation`, `UnknownGroup`, `GroupExists`, `UnknownMember`, `RemovedFromGroup`,
  `WrongEpoch`, `DecryptionFailed`, `NotApplicationMessage`, `Welcome`, `CommitPending`,
  `NoPendingCommit`, `Storage` and `Other`.
- **Wire rules.** Commits use PublicMessage framing and application messages PrivateMessage.
  `MAX_PAST_EPOCHS` = 3. Standalone proposals are rejected.

## Tests (`cargo test`: 43 core + 4 FFI)
- **`group_lifecycle`:**
  - **(a) creation:** `a_create_group`.
  - **(b) join, same epoch:** `b_add_member_join_from_welcome_same_epoch`.
  - **(c) both directions:** `c_encrypt_decrypt_both_directions`.
  - **(d) removed members are locked out:** `d_removed_member_cannot_decrypt_new_messages`
    (`RemovedFromGroup`) and `d_removed_member_ignoring_commit_cannot_decrypt` (`WrongEpoch`).
  - **Other tests:** `wire_formats_match_the_contract`, `tampered_ciphertext_is_rejected`,
    `tampered_commit_is_rejected_and_state_unchanged`, `welcome_for_someone_else_is_rejected`,
    `invalid_key_package_is_rejected`, `unknown_group_and_member`.
- **`pending_commits`:**
  - not merged until accepted (and messaging continues meanwhile);
  - rejected keeps the epoch;
  - `CommitPending` / `NoPendingCommit`;
  - a concurrent-commit race converges;
  - a foreign commit discards our pending one;
  - in a creation race the loser joins the winner;
  - creating over an existing group fails.
- **`trust`:**
  - identity parsing;
  - the RFC 8037 thumbprint vector;
  - JWKS parsing;
  - JWS verification negatives;
  - rotated and named keys;
  - `set_attestation` checks;
  - unattested leaves are rejected at add, at join (with rollback, so the key package is still
    usable) and at commit.
- **`key_packages`:**
  - a batch of 100 is unique, valid and ≤4 KiB each;
  - the last-resort package is marked and serves several joins;
  - a normal package is single-use;
  - an attestation is required.
- **`persistence`:**
  - a reopened client continues;
  - a store belongs to one device;
  - a storage failure rolls back and a retry succeeds;
  - an **outer transaction rollback restores the ratchet** (decision 033);
  - no transaction is left open.
- **`multidevice`:**
  - each device is a leaf, and a removed device is locked out;
  - a new device is added by another member;
  - a desynced device resyncs by re-add;
  - a stale Welcome doesn't roll back;
  - late messages decrypt within 3 past epochs (older → `WrongEpoch`).
- **`robustness`:** random and truncated input never panics and never changes state.
- **`risime-mls-ffi/tests/self_test`:**
  - `self_test` passes;
  - error mapping;
  - a foreign `KvStore` failure maps to `Storage` and rolls back;
  - commit fields cross the FFI.

## Contract fixtures
```sh
cd crypto && ~/.cargo/bin/cargo run -q -p risime-mls --example gen-contract-fixtures > /tmp/fixtures.json
```
It prints real base64 blobs for the v1.7 examples, as JSON:
- `attestation_keys` (a fixed **test** key) and the per-device signature keys and attestation JWSs;
- `key_packages_upload`, `key_package` and `last_resort`;
- `mls_commit_request_create` (epoch 0 with the Welcome) and `mls_commit_request_add_device`;
- `event_mls_commit` and `event_mls_welcome`;
- `msg_send_e2ee` (the ciphertext) and its plaintext.

Every blob is checked before printing. MLS keys are fresh on each run. Root applies the blobs to
`contract/v1/examples/`.

## Android build (`risime-mls-ffi`, docs/decisions/012 §5a)
`risime-mls-ffi` is the UniFFI 0.32 binding (library `libuniffi_risime.so`, Kotlin package
`lk.codegen.risime.crypto`). It builds natively on spark2 (aarch64) with Rust's Android targets,
rustup's `rust-lld` and the official NDK sysroot. No NDK executables are used.
```bash
scripts/build-rust-android                  # arm64-v8a + x86_64, release, with ELF/ABI checks
scripts/build-rust-android --abis arm64-v8a --debug
```
- Output: `android/app/build/rustJniLibs/<abi>/libuniffi_risime.so` and `risime-mls-selftest`.
- Kotlin: `android/app/build/generated/uniffi/`.
- Needs: `rustup target add aarch64-linux-android x86_64-linux-android`,
  `rustup component add llvm-tools`, and an NDK in `~/Android/Sdk/ndk/` (or `ANDROID_NDK_HOME`).

### Device self-test (laptop, no app wiring needed)
```bash
# emulator (x86_64); use arm64-v8a and the phone's serial for the USB phone
scp spark2:development/risime/android/app/build/rustJniLibs/x86_64/risime-mls-selftest /tmp/
adb -s emulator-5554 push /tmp/risime-mls-selftest /data/local/tmp/
adb -s emulator-5554 shell chmod 755 /data/local/tmp/risime-mls-selftest
adb -s emulator-5554 shell /data/local/tmp/risime-mls-selftest; echo "exit=$?"
# expect: risime-mls self-test ok: epoch 3, risime-mls 0.1.0 (MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519)
adb -s emulator-5554 shell rm /data/local/tmp/risime-mls-selftest
```

Gate (workspace): `cargo fmt --check && cargo clippy --all-targets --all-features -- -D warnings && cargo test`
