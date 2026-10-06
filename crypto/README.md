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
- **Wire rules.** DM commits use PublicMessage framing; `grp:` commits and all application
  messages use PrivateMessage. `MAX_PAST_EPOCHS` = 3. Standalone proposals are rejected.
- **`PolicyViolation`** (v1.9) is the extra `MlsError` variant for group commits that break the
  admin policy or the caps.

## Group API (contract v1.9 §12, decision 041)
A group's MLS id is `"grp:<uuid>#<generation>"`; the `grp:` prefix selects the group rules
(PrivateMessage handshakes, `group_meta`, the admin policy). The DM calls above refuse `grp:` ids
(`create_group`, `add_members`, `remove_members`), and the group calls refuse DM ids. Every
group commit is a **`GroupCommit`** `{commit, welcome?, epoch (source), added, removed,
meta_changed}` and stays **pending** until `commit_accepted` / `commit_rejected`, as for DMs.

| Core call (Kotlin name) | What it does |
|---|---|
| `create_group_with_meta(gid, kps, meta)` (`createGroupWithMeta`) | Epoch 0 with `group_meta` set, adding every claimed key package (all must carry 0xFA01). The caller's user must be in `meta.admins`. Also the **rebuild** after a reset (new generation in the id). `kps` may be empty |
| `change_members(gid, kps, remove)` (`changeMembers`) | One commit for an `add`, `remove` or `devices` op. A device listed in both is **re-added** (a `rejoin`). Own device can't be removed |
| `remove_users(gid, user_ids)` (`removeUsers`) | Removes every leaf of those users (a removal or a member's leave, committed by an admin) |
| `update_group_meta(gid, meta)` (`updateGroupMeta`) | A GroupContextExtensions commit: rename, or a `role` op's admin list. `meta_changed` = true. Unknown meta fields are carried over |
| `self_update(gid)` (`selfUpdate`) | Empty commit with a fresh path (key rotation); anyone may send it |
| `group_meta(gid)` (`groupMeta`) | The current `GroupMeta {name, icon, admins}` (`icon_json` over the FFI); `None` for DMs |
| `process_commits(gid, commits)` (`processCommits`) | Catch-up from `GET …/commits`, all or nothing: skips commits below our epoch, `WrongEpoch` on a gap, **merges our own pending commit** found in the log (restart between `200` and `commit_accepted`), stops after `removed_self`. Returns `CatchUp {epoch, applied, skipped, removed_self}` |
| `key_package_supports_groups(kp)` (free fn) | Validates a key package and reports whether it carries 0xFA01 |
| `group_limits()` (FFI free fn) | 64 KiB inline, 1 MiB commit, 2 MiB Welcome, 256 users, 768 leaves, 0xFA01 |

- **`group_meta`** is the GroupContext extension 0xFA01 (JSON `{"v":1,"name","icon","admins"}`,
  name 1–100 grapheme clusters, non-empty distinct admins), named in the group's
  `required_capabilities`. Joiners get it in the Welcome; `Incoming::Commit.meta_changed` says
  when a commit changed it.
- **Key packages** now always advertise capability 0xFA01 (normal and last-resort). Upload them
  with `replace: true` the first time the device advertises `groups` (§12.1), and regenerate the
  last-resort package then too.
- **Admin policy** (`policy::check_commit_policy`, run on every staged `grp:` commit before merge
  and on our own commits before they are built): a non-admin may add/remove only its own user's
  leaves and may not touch `group_meta`; a new admin list must be non-empty and agent-free.
  Admins come from `group_meta.admins` of the commit's epoch. The core has no agent list yet
  (agents arrive in 0.5), so it passes none. A violating peer commit fails with
  `PolicyViolation` and nothing is merged: the state is unrecoverable for that commit (§12.8).
- **Caps** (256 users, 768 leaves) are checked before building; a commit over 1 MiB or a Welcome
  over 2 MiB is refused (`PolicyViolation`) and rolled back. `GroupCommit::commit_needs_ref` /
  `welcome_needs_ref` (FFI `commitNeedsRef` / `welcomeNeedsRef`, plus `commitSize` /
  `welcomeSize`) tell the caller to upload a blob (> 64 KiB). In a 256-user group the epoch-0
  commit (~173 KiB) and every Welcome (~150–180 KiB) need a ref.
- **Rejoin:** the committer calls `change_members(gid, [new kp], [that device])`; the broken
  device joins from the Welcome (a stale or missing local group is replaced).
  **Reset:** the rebuilder calls `create_group_with_meta("grp:…#<n+1>", kps, last meta with the
  event's admins)`; members `delete_group` the old generation and join from the Welcome.
- **Leave:** MLS can't commit its own removal. The leaver calls `POST …/leave`; an admin device
  commits `remove_users`; the leaver sees `removed_self` and wipes the group.

### 256-member timing (`tests/group_scale.rs`, spark2 aarch64)
`cargo test --test group_scale -- --nocapture` (add `--release` for optimised numbers):

| | debug | release |
|---|---|---|
| create + add 255 (stage) | 856 ms | 65 ms |
| join from Welcome | 254 ms | 18 ms |
| remove 1 user: build / process | 136 / 99 ms | 7.4 / 4.8 ms |
| add 1 user: build / process | 138 / 96 ms | 6.6 / 3.5 ms |
| self-update: process | 122 ms | 5.0 ms |
| decrypt (includes loading the state) | 42 ms | 2.6 ms |

Sizes: create commit 176,670 B, its Welcome 180,957 B; remove commit 21,930 B; add commit 898 B
with a 151,520 B Welcome. **State per device ≈ 0.93 MB; the largest single `KvStore` value (the
tree) ≈ 0.6 MB**, growing roughly linearly with leaves (so ~1.8 MB at 768 leaves): the app's
`mls_kv` chunking above 512 KB is needed for Android's 2 MB `CursorWindow`.

## Delete API (contract v1.12 §15, decision 047; crypto review R1, R2, R5, R6, S1)
All additive: every existing call (including `process` and `encrypt`) behaves as before.

| Core call (Kotlin name) | What it does |
|---|---|
| `process_detailed(gid, msg)` (`processDetailed`) | `process` in the same transaction, plus for an application message `ApplicationDetails {sender_leaf, authenticated_data, sender_is_admin}`. Compare the deleter with the original sender by **`sender.user_id`** (R1); `sender_leaf` is diagnostics only (leaf indices are reused) |
| `encrypt_with_aad(gid, plaintext, aad)` (`encryptWithAad`) | `encrypt` with the PrivateMessage `authenticated_data` (OpenMLS `set_aad`); signed and AEAD-covered, so a changed AAD fails as `DecryptionFailed` |
| `encode_delete_aad(targets)` / `decode_delete_aad(aad)` (FFI free fns `deleteAadEncode` / `deleteAadDecode`) | The canonical delete AAD: `0x01 0x44` + 16-byte UUIDs, sorted ascending, distinct, 1..=100 (≤ 1 602 bytes). Decoding refuses anything non-canonical (`Malformed`: drop the control) and returns lowercase UUIDs |
| `admins_at_epoch(gid, epoch)` (`adminsAtEpoch`) | The admin list recorded for that epoch; `None` for DMs, outside the window, or before the join |
| `purge_group(gid)` (`purgeGroup`) | Removes all core state of a group (OpenMLS state, pending record, admin records), known or not; idempotent. `delete_group` now drops the admin records too |

- **`sender_is_admin`** is "admin at the message's epoch `e`" (never "still admin now"), from
  `group_meta.admins` that the core records per epoch at **every** merge, in the same transaction:
  create (epoch 0), `commit_accepted`, Welcome, peer commit in `process`, each commit of
  `process_commits` (own or peer). Keys `risime/admins/<gid>/<epoch>` plus an index
  `risime/admins/<gid>`; kept for the current epoch plus `MAX_PAST_EPOCHS` (3), the same window as
  the past-epoch secrets. `None` in DM groups. Agents are never admins (`policy::is_admin`). A
  `grp:` message whose epoch has no record: a non-empty AAD (a delete) is `Malformed` (rolled back,
  nothing consumed); an empty AAD gets `None`.
- **Sender ratchet:** `SenderRatchetConfiguration::new(5, 20_000)` (`OUT_OF_ORDER_TOLERANCE`,
  `MAX_FORWARD_DISTANCE`) in the create, join and Welcome configs. `out_of_order_tolerance` stays 5
  until v1.13 decides it.

### Migration of stored groups (automatic, on load)
The first load of a group stored before v1.12 (forward distance 1000) runs, in one nested
transaction: `set_configuration` with the v1.12 join config, and for a `grp:` group the admin
record of the **current** epoch from its `group_meta`. Pre-upgrade past epochs (at most 3) have
no record: a late delete control from one of them fails with `Malformed` in `processDetailed`
(the app drops it, fail closed), while ordinary messages decrypt as before. No app action and no
schema change are needed. Test: `stored_group_is_migrated_and_accepts_a_5000_jump`.

### Purge notes for the app (R6, §15.6)
- The core keeps **no application plaintext**, and consumed generation keys are deleted, so a
  message delete needs no core call. Delete chat calls `purgeGroup` (or `deleteGroup`) only when
  the MLS group itself is dropped (left/removed); Clear chat never touches MLS state.
- Run the messages database (which also holds the MLS `KvStore` table, decision 033) with
  **`PRAGMA secure_delete = ON`**: deleted cells, including old epoch secrets and admin records,
  are zero-filled instead of lingering in free pages. Set it on every connection open.
- After a delete transaction, run **`PRAGMA wal_checkpoint(TRUNCATE)`** best-effort, off the UI
  thread (it can return busy while readers are active; retry later, never block on it), so the
  deleted rows don't linger in the WAL.
- Delete the cached blob file after the transaction commits; a start-up sweep removes orphans.

## Media API (contract v1.11 §14.3, decision 042): `risime_mls::media`
Encrypted images and group icons use the blob format **`A256GCM-S64K`**:
- a fresh 32-byte key `K` from the OS CSPRNG, generated **inside** the encrypt call;
- `Kp = HKDF-SHA256(salt = empty, IKM = K, info = "risime-media-v1 A256GCM-S64K")`;
- Padmé padding with zero bytes;
- 64 KiB AES-256-GCM segments, nonce `0x00×7 ‖ BE32(i) ‖ final`, AAD `risime-media-v1`, each
  tag appended;
- no header.

No API takes a caller-supplied key for encryption. The keyed form (`seal_file_with_key`) is
private and only the unit tests use it, so the Android `.so` can't reuse a key.

| Core (`media::`) | FFI (Kotlin) | What it does |
|---|---|---|
| `encrypt_file(src, dst)` | `mediaEncryptFile(src, dst): SealedMedia` | Streams `src` to the blob file `dst` (temp file + fsync + rename). Returns `{key, alg, plainSize, cipherSize, sha256}` for `enc` and `blob`. Refuses an empty input (`Format`) or more than 16 515 072 bytes (`TooLarge`) before writing |
| `decrypt_file(src, &MediaRef)` | `mediaDecryptFile(src, key, alg, plainSize, cipherSize, sha256): ByteArray` | Decrypt-on-display into memory |
| `decrypt_file_to_file(src, dst, &MediaRef)` | `mediaDecryptFileToFile(src, dst, …)` | Constant memory. `dst` appears only after every check passed |
| `verified_prefix(src, key, alg, cipher_size)` | `mediaVerifiedPrefix(src, key, alg, cipherSize): ULong` | `Range` resume: the bytes of leading whole segments of a `.part` file that verify in order. Truncate to it and resume |
| `cipher_size_for(plain_size)` | `mediaCipherSize(plainSize): ULong?` | `Padmé(L) + 16·n`. Receivers check that it equals `blob.size` |
| constants | `mediaLimits()` | alg, 64 KiB segment, 16 MiB media cap, 512 KiB icon cap (checked by the caller), max plaintext 16 515 072 |

- **Release of plaintext.** Decryption checks, in one streaming pass:
  - the file size against `cipher_size`;
  - every segment tag, with the final flag only on the last segment;
  - the zero padding;
  - the ciphertext SHA-256.

  Nothing is returned or renamed into place unless all of them pass. A segment's plaintext is
  produced only after its tag verified.
- **Envelope checks** (`check_ref`) come before any read:
  - `alg` is exactly `A256GCM-S64K` (`Unsupported`);
  - the key and the SHA-256 are 32 bytes;
  - `cipher_size ≤ 16 MiB` (`TooLarge`);
  - `cipher_size_for(plain_size) == cipher_size`;
  - the last segment is ≥ 17 bytes (`Format`).
- **Errors.** `MediaError` / `RisiMediaException` has the variants `Integrity`, `Format`,
  `Unsupported`, `TooLarge` and `Io`. Each one means "Couldn't open this photo".
- **Zeroizing.** `K`, `Kp`, the AES key schedule and every segment buffer are wiped
  (`zeroize`).
- **Vectors.** `risime-mls/tests/media_vectors.json` holds 5 positive and 9 negative cases, all
  lowercase hex. Regenerate it with
  `cargo test -p risime-mls media_vectors_write -- --ignored`.
  - A test fails if the file is stale.
  - Another test checks that it equals `contract/v1/media_vectors.json`, which root's independent
    Python reference `scripts/gen-media-vectors` writes. The two are byte-identical.
- **Hardware AES on aarch64.** `.cargo/config.toml` sets `--cfg aes_armv8 --cfg polyval_armv8`.
  Without these cfgs, RustCrypto's `aes` 0.8 and `polyval` 0.6 use software AES and GHASH on ARM.
  - With them, the crates still detect the CPU features at run time and fall back on cores that
    lack them.
  - The flags join with `scripts/build-rust-android`'s per-target `RUSTFLAGS`. The arm64 `.so`
    contains `aese`/`pmull`. A plain `RUSTFLAGS` in the environment would override them.
  - They also speed up the MLS AEAD.

### Media timing and memory (`tests/media_memory.rs`, spark2 aarch64, release)
`cargo test --release --test media_memory -- --nocapture` runs on a 15.75 MiB image, the largest
allowed:

| | software AES (no cfg) | ARMv8 AES + PMULL |
|---|---|---|
| `encrypt_file` (read, encrypt, SHA-256, fsync) | 130 ms (121 MiB/s) | 59 ms (267 MiB/s) |
| `decrypt_file_to_file` | 116 ms (135 MiB/s) | 59 ms (267 MiB/s) |
| `verified_prefix` (AEAD only) | 84 ms | 28 ms (~560 MiB/s) |

**Peak RSS grows by 4 KiB** over encrypt + decrypt + verify, because every call works through a
single 64 KiB buffer. The unit test `streams_beyond_the_cap` encrypts 20 MiB through the private
uncapped path. A 2 MiB photo takes about 8 ms. Benchmark on the oldest pilot phone before
release.

## Tests (`cargo test`: 89 core + 1 ignored generator, 7 FFI)
- **`groups`** (v1.9): create/join with PrivateMessage handshakes and meta; DM and group APIs
  don't mix; 0xFA01 key packages (a legacy key package is refused); admin adds/removes users and
  removed devices are locked out; members manage only their own devices; **peers reject
  policy-breaking commits** built straight with OpenMLS (non-admin removal, rename, self-promotion,
  empty admin list) with state unchanged; rename and promotion; pending until accepted, 409, a
  foreign commit discards ours, creation race; rejoin by re-add; reset to a new generation;
  catch-up (skip, gap, removal stops); own accepted commit merged after a restart.
- **`group_policy`:** every case of `contract/v1/group_policy_cases.json`.
- **`group_scale`:** 256 users (timings and sizes above).
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
- **`delete_v112`** (v1.12): sender, leaf and AAD from `process_detailed`; DM → `None`; a
  tampered AAD fails decryption; `sender_is_admin` at the message's epoch after a demotion
  (admin at `e`, not at `e+1`) across a catch-up; every merge path records its epoch, catch-up
  records each intermediate epoch, pruning keeps current + 3; a missing record (`Malformed` for a
  delete, `None` otherwise, nothing consumed); a 1 500-generation gap decrypts; a stored
  old-config group migrates on load and accepts a 5 000-generation jump; `purge_group`.
  Unit (`aad`): canonical encoding, sorting, case, duplicates, 0/101 targets, non-canonical AAD.
- **`media`** (v1.11):
  - unit: Padmé values and bounds, the nonce layout, crafted negatives (final flag on a
    non-final segment, nonzero padding, a 16-byte last segment, a skipped index), deterministic
    keyed sealing, streaming past the cap, vectors current and equal to the contract file;
  - `tests/media.rs`: round trips at the segment edges, a fresh key per call, a flipped bit,
    swapped, duplicated and missing segments, truncation, wrong key, bad envelopes, oversize and
    empty input, the max size, `verified_prefix`, and every committed vector;
  - `tests/media_memory.rs`: constant memory and throughput (above).
- **`risime-mls-ffi/tests/self_test`:**
  - `self_test` passes (it also runs a group lifecycle: `…, groups epoch 4`);
  - the group API crosses the FFI;
  - error mapping;
  - a foreign `KvStore` failure maps to `Storage` and rolls back;
  - commit fields cross the FFI;
  - v1.12: delete AAD, `encryptWithAad`, `processDetailed`, `adminsAtEpoch`, `purgeGroup`.
- **`risime-mls-ffi/tests/media`:** the media calls and their errors across the FFI.

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
