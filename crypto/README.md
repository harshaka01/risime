# crypto/ — RisiMe E2EE core (Rust)

Owned by the **root** session. Groundwork for Release 0.3 (MLS end-to-end encryption).
Plan for the Android binding: `docs/decisions/012-e2ee-android-binding-plan.md`.

## Layout
- `Cargo.toml`: Cargo workspace (`Cargo.lock` is committed).
- `risime-mls/`: the core crate. A small bytes-in/bytes-out API over
  [OpenMLS](https://openmls.tech) 0.9 (`openmls_rust_crypto` 0.6, `openmls_basic_credential` 0.6).
- `risime-mls-ffi/`: the UniFFI binding (`libuniffi_risime.so`, Kotlin `lk.codegen.risime.crypto`).
- `risime-mls-nif/`: the Rustler NIF for the server's Risi member client (`librisime_mls_nif.so`,
  Elixir `RisiMe.Agent.Mls.Nif`; contract v1.24 §24.11, decision 065).

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
| `group_meta(gid)` (`groupMeta`) | The current `GroupMeta {name, icon, admins, tab, chat_id, agents}` (`icon_json` over the FFI; the v1.24 fields are null when absent); `None` for DMs |
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
  Admins come from `group_meta.admins` of the commit's epoch. A violating peer commit fails with
  `PolicyViolation` and nothing is merged: the state is unrecoverable for that commit (§12.8).
- **Tabs and agents (v1.24 §24.1, `policy::check_tab_policy`)**, which wraps the admin policy for
  every `grp:` commit (ours and peers'), the epoch-0 group, and the tree of a Welcome we join:
  - `group_meta` gains `tab` (`private` | `official`, absent = Private), `chat_id` (absent = the
    group's own conversation id) and `agents` (absent = `[]`). A 1:1 Official's `name` is `null`
    (`""` in Rust and over the FFI).
  - `tab` and `chat_id` never change after epoch 0; a Private group has `agents == []` and never
    holds an agent leaf; a `dm:` group never holds one either (create, add, peer commit, Welcome);
    in Official an agent leaf is added only by an admin and only for a user in `agents`, and
    `agents` never names an admin.
  - Any member may remove **every** leaf of an agent (Official off), with only its own-user
    changes alongside and no meta change; a partial removal stays admin-only.
  - Agents are the users in `agents` plus every user with a leaf whose attestation carries
    `"kind": "agent"` (`attested_kind`, `MemberInfo.kind`; FFI `MemberInfo.kind` = `"user"` |
    `"agent"`). `TrustAnchors` refuses any other `kind`. An Update or path leaf can't change a
    leaf's kind.
  - `update_group_meta` carries `tab`/`chat_id`/`agents` over when the caller leaves them null.
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
- **Sender ratchet:** `SenderRatchetConfiguration::new(32, 20_000)` (`OUT_OF_ORDER_TOLERANCE`,
  `MAX_FORWARD_DISTANCE`) in the create, join and Welcome configs (tolerance 32 since v1.13, see
  "Calls" below).

### Migration of stored groups (automatic, on load)
The first load of a group whose stored sender ratchet differs from the current one (before v1.12:
5/1000; v1.12: 5/20 000) runs, in one nested transaction: `set_configuration` with the current
join config, and for a `grp:` group the admin record of the **current** epoch from its
`group_meta` if it is missing. Pre-upgrade past epochs (at most 3) have
no record: a late delete control from one of them fails with `Malformed` in `processDetailed`
(the app drops it, fail closed), while ordinary messages decrypt as before. No app action and no
schema change are needed. Test: `stored_group_is_migrated_and_accepts_a_5000_jump`.

## Calls (contract v1.13 §16.12; crypto review R1, R2)
- **`out_of_order_tolerance` 32** (from 5) for new groups and for stored groups (migrated on load,
  above). OpenMLS semantics: after a sender's generation `n` decrypts, the keys of the 31 earlier
  generations `n-1 … n-31` stay usable (once each); `n-32` and older fail with
  `DecryptionFailed`. So one burst of call signals encrypted before a text can't push that text
  out of the window, as long as the app keeps one encrypt-and-send lane per group (§16.3).
- **Forward gaps:** expired or filtered call signals leave gaps; `maximum_forward_distance`
  20 000 (v1.12) covers them.
- **Nothing else in the core** for 1:1 calls (§16.12): call envelopes are ordinary `encrypt`ed
  JSON; `call_id`/`ring` binding, `sent_at` freshness, dedupe and per-call sender pinning are app
  checks on the decrypted envelope plus `processDetailed`'s authenticated sender (no AAD).
- Tests: `tests/calls_v113.rs`.

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

## History API (contract v1.15 §17.3; crypto review R1, R3, R4, R7, S1): `risime_mls::history`
All additive: every existing call behaves as before. `rsk` and `K` never cross the FFI: no call
returns them and no production call takes them.

| Core call (Kotlin name) | What it does |
|---|---|
| `encode_history_aad(request_id)` / `decode_history_aad(aad)` (free fns `historyAadEncode` / `historyAadDecode`) | The canonical `'H'` AAD: `0x01 0x48` ‖ `request_id` (16 raw bytes), exactly 18 bytes. Decoding refuses anything else (`Malformed`); a `'D'` AAD never decodes as `'H'` and vice versa (the app pairs `'D'` with `delete` and `'H'` with `history_*`) |
| `process_detailed` | Unchanged, except that for the `'H'` form the admin lookup is skipped: `sender_is_admin` is `None`, even at an epoch without an admin record (a `'D'` there is still `Malformed`) |
| `Client::history_keygen(request_id)` (`historyKeygen`) | `(rsk, rpk)` = `derive_hpke_keypair` over 32 bytes of OS randomness; `rsk` (with `rpk`) stored under `risime/history/<request_id>` plus an index `risime/history-index/v1`, in one nested transaction inside the caller's (roll the outer one back and the key is gone); returns `rpk` (32 bytes). A second keygen for the same id is `Malformed` |
| `Client::history_public_key(request_id)` (`historyPublicKey`) | The stored `rpk` (a `history:refresh` resends it), or `None` |
| `history::seal(rpk, ctx, plaintext, out_path)` (free fn `historySeal`) | A fresh `K` (OS CSPRNG), the part written atomically as `A256GCM-S64K` with the label `risime-history-v1` (HKDF info `risime-history-v1 A256GCM-S64K`, segment AAD `risime-history-v1`), `K` HPKE-sealed (base mode, X25519/HKDF-SHA256/AES-128-GCM through the OpenMLS provider) with §17.3's `info`/`aad`. Returns `SealedPart {hpke_enc (32), sealed_key (48), plain_size, size, sha256}`. A low-order `rpk` is `SealRefused` and leaves no file. `ctx.sha256`/`plain_size` are ignored (computed) |
| `Client::history_open(request_id, ctx, hpke_enc, sealed_key, in_path)` (`historyOpen`) | HPKE-opens `K` with the stored `rsk`, then opens the blob; every segment, the final flag, the padding and the SHA-256 verify before any byte is returned. No key: `UnknownRequest` (close the request locally); any mismatch: `OpenFailed` |
| `Client::history_forget(request_id)` / `history_open_requests()` (`historyForget` / `historyOpenRequests`) | Delete `rsk` (idempotent); the ids that still have one, for the 48-h start-up sweep |
| `Client::history_sender(gid, sender)` (`historySender`) | Own versus member (R4): `own` = the MLS sender's user is this device's user; plus the sender leaf's signature key (the option-A approval record's key). The sender must be a current leaf (`UnknownMember`); this device itself is `Malformed` |
| `HistoryContext {request_id, conversation_id, requester, provider, part, parts, sha256, plain_size}` | The one record behind `info` = `"risime-history-v1"` ‖ request_id (16) ‖ u16‖conversation_id ‖ u16‖requester ‖ u16‖provider and `aad` = u16 part ‖ u16 parts ‖ sha256 ‖ u64 plain_size (big-endian). Identities come from MLS credentials only; `1 ≤ part ≤ parts ≤ 20`, plaintext 1..=16 515 072 bytes |
| `history_limits()` (`historyLimits`) | Label, alg, lengths, max parts, max part size |
| `history::check_vectors(json, work_dir)` (`historyVectorsCheck`) | **Test support:** verifies every case of `history_vectors.json`, returns the count (for the Android vectors test) |

Order on open (§17.3 clarification): wrong-length `hpke_enc`/`sealed_key`/`rsk` are `Malformed`
before any HPKE operation; after the HPKE open the blob's SHA-256 is checked **before any AEAD**
(mismatch: `OpenFailed("integrity: …")`). The vectors use one key pair per `request_id`.

Errors (`HistoryError`, Kotlin `RisiHistoryException`): `Malformed`, `SealRefused`, `OpenFailed`,
`UnknownRequest`, `Io`, `Storage`. The app's `rsk` deletion follows "Purge notes" below
(`secure_delete`, WAL checkpoint).

**Vectors:** `contract/v1/history_vectors.json` (8 `aad_h`, 3 positive, 17 negative cases) is
written by `cargo test -p risime-mls history_vectors_write -- --ignored` from seeded inputs; the
generator seals with a hand-written RFC 9180 base-mode HPKE whose ephemeral key is
`DeriveKeyPair(ikm_e)` (test builds only, `x25519-dalek` is a dev-dependency), and every case is
then checked through the production path (OpenMLS → hpke-rs), so the two implementations
cross-check. `history_vectors_match_the_contract` fails if the contract file differs from the
generator's output. Root's independent reference script (as `scripts/gen-media-vectors`) is still
to be written.

## Group call keys (contract v1.19 §20.6, §10.3; crypto review K1, K2, K10): `risime_mls::call`
All additive. The MLS exporter output (`call_secret`) never crosses the FFI; only frame keys do.

| Core call (Kotlin name) | What it does |
|---|---|
| `Client::export_secret(group, label, context, length)` (crate-internal, **not** exported) | OpenMLS `export_secret` at the current epoch, behind the **label registry** `EXPORTER_LABELS` = `["risime-call-v1"]` and `length = 32`; anything else is `Malformed` |
| `Client::call_frame_keys(gid, call_id)` (`callFrameKeys(groupId, callId): CallFrameKeys`) | `call_secret = MLS-Exporter("risime-call-v1", call_id's 16 bytes, 32)`, then for every leaf `HKDF-Expand-SHA256(call_secret, "risime-call-v1 frame" ‖ 0x00 ‖ identity, 32)`. Returns `{epoch, keyIndex = epoch mod 16, keys: [{identity, key}]}` in leaf order (own leaf included). `grp:` groups only (`Malformed` for a DM or a non-UUID `call_id`, either case accepted), `UnknownGroup`, `RemovedFromGroup`. Read-only; `call_secret` and the core's copies of the keys are wiped (`zeroize`) |
| `exporter_labels()` (`exporterLabels()`) | The registry |
| `call::check_vectors(json)` (`callVectorsCheck(json): UInt`) | **Test support:** verifies every case of `contract/v1/call_vectors.json`, returns the count (29) |

- **App use (§20.6):** catch up commits, then `callFrameKeys`; `setKey(identity, base64(key),
  keyIndex)` for each entry and `setKeyIndex(keyIndex)`; call again after **every** merged commit
  (keep the old index 10 s). The `groupId` is the conversation's local MLS group id
  (`grp:<uuid>#<generation>`), the same bytes as for `encrypt`. Keys stay in memory, never logged.
- **Vectors:** `contract/v1/call_vectors.json` (8 `frame_keys` cases from seeded `call_secret`s,
  incl. epochs 15/16/17/2^32+15, a non-ASCII identity and 8 members; 6 `exporter_cases` from the
  **RFC 9420 key-schedule test group**, cipher_suite 1, whose own exporter check runs first;
  15 `negative`: the label registry, and wrong identity / `call_id` encodings that must give other
  keys). Written by `cargo test -p risime-mls call_vectors_write -- --ignored`; root's independent
  Python reference `scripts/gen-call-vectors` produces the same bytes (`--check` verifies every
  case and compares the file). `call_vectors_match_the_contract` fails on any difference.
- **Tests:** unit (`call`): registry, `export_secret` on a real group refuses other labels and
  lengths, key index wrap, vectors and altered vectors; `tests/calls_v119.rs`: members agree, the
  keys equal a by-hand RFC 9420 §8.5 derivation from OpenMLS's stored exporter secret, rekey after
  add / self-update / remove (removed device gets none), wrap after 16 epochs, other `call_id`,
  bad input, no state change; `risime-mls-ffi/tests/calls`: the flow across the FFI, the vectors,
  and that no exporter output is exported.

## Backup API (contract v1.22 §22, decision 059; crypto review C1–C9): `risime_mls::backup`
All additive. `BK`, `KEK` and `DEK` never cross the FFI (no call returns or takes them); `R` crosses
only as its display string; all randomness (`BK`, `R`, `DEK`, salts, nonces) comes from the OS
CSPRNG inside the core. Argon2id (64 MiB, t 3, p 1; ≈ 85 ms on spark2, 0.5–1 s on a phone) runs in
`backupSetup` (unless the stored record already matches), `backupAddPassphrase`, `backupUnlock` and
`backupRotateRecoveryKey`: call them off the main thread (they hold the client's lock).

| Core call (Kotlin name) | What it does |
|---|---|
| `backup_setup(user_id)` (`backupSetup`) | Makes `BK` + `R`, or reuses the stored pair (also a silent one made for local backups): returns `{recoveryKey, keyRecord}` (record = `recovery_key` wrap, plus a stored passphrase wrap of the same `BK`). Stored in `mls_kv` in the caller's transaction. A stored `BK` without `R` (a passphrase unlock) gets a new `R` |
| `backup_add_passphrase(user_id, passphrase)` (`backupAddPassphrase`) | Adds/replaces the passphrase wrap; returns the record for `PUT` (same `bk_id`). Enforces 14 code points or 4 words of 3+ (`WeakPassphrase`) |
| `backup_unlock(user_id, key_record, secret, kind, make_current)` (`backupUnlock`) | Parses the record (`Malformed`: shape, lengths, KDF ≠ argon2id/65536/3/1; `Unsupported`: `v` ≠ 1), the secret (`Typo`/`Malformed`), Argon2id, key check (`WrongKey`), AEAD (`Integrity`), `bk_id` (`Integrity`). `make_current`: `BK` becomes the account key (the previous one is kept as an older key), the record is stored, and `R` for a recovery key (a passphrase unlock keeps a stored `R` only when the record's recovery wrap is the stored one). Without it the key is only kept as an older key (a file with another `bk_id`). Returns `bk_id` (base64) |
| `backup_recovery_key()` (`backupRecoveryKey`) | The stored `R`'s display form, or null |
| `backup_rotate_recovery_key(user_id)` (`backupRotateRecoveryKey`) | New `R`, same `BK`, passphrase wrap kept |
| `backup_forget()` (`backupForget`) | Deletes `BK`, older `BK`s, `R`, the record. Idempotent |
| `backup_key_record()` / `backup_key_ids()` / `backup_drop_key(bk_id)` | The stored record; `{current, old}` `bk_id`s; drop an older key once no local file needs it (the current one: `Malformed`) |
| `backup_writer(user_id, backup_id, created_at, app_version, key_record?, out_path)` (`backupWriter` → `BackupWriter.write(bytes)` / `finish()`) | A fresh `DEK`; the header carries `key_record` (null: the stored record, or `key: null` without one), whose `bk_id` picks the local `BK` (`NoKey`). `write` takes the app's DEFLATE output in any split; `finish` pads (Padmé), seals the final chunk, fsyncs and renames: `{backupId, bkId, size, sha256, dataSize}` for `POST /backups`. A failed writer is dead (start a new `backup_id`); an unfinished one leaves no file |
| `backup_reader(user_id, in_path, expected_backup_id?, expected_bk_id?)` (`backupReader` → `BackupReader.info()` / `verify()` / `read()`) | §22.4 opening order: magic/version/`header_len` (`Format`), `v`/`schema`/algs (`Unsupported`), `user_id` (`WrongAccount`, nothing more read), the listing's ids (`Integrity`), the local `BK` (`NoKey`: unlock first), the `DEK` (`Integrity`). `verify` = the whole pass (every chunk and flag: `Integrity`; DEFLATE end found with miniz_oxide, zero padding and `|P| = Padmé(|D|)`: `Format`). `read` (only after `verify`) returns **`D`** (the DEFLATE stream, padding stripped) in ≤ 64 KiB pieces, re-authenticating each chunk; a file changed since `verify` is `Integrity` |
| `file_info(path)` (`backupFileInfo`) | The header without a key (restore screen; `keyRecord` to unlock a file). Unauthenticated |
| `normalize_recovery_key(input)` / `passphrase_floor(p, phone?)` (`backupNormalizeRecoveryKey` / `backupPassphraseFloor`) | Live input checks: display form or `Typo`/`Malformed`; `Ok`/`TooShort`/`PhoneNumber` (6+ consecutive digits of the phone number). The app adds the common-password list |
| `check_vectors(json)` (`backupVectorsCheck`) | **Test support:** every case of `contract/v1/backup_vectors.json` (44) |

- **Format** exactly §22.2/§22.4: `RISIMEBK` ‖ 0x01 ‖ u32 `header_len` ‖ compact JSON header ‖
  chunks of 64 KiB of `P` under `Ks = HKDF(DEK, "risime-backup-v1 stream")`, nonce `0×7 ‖ u32 i ‖
  flag`, AAD `"risime-backup-v1" ‖ header_hash ‖ u32 i ‖ flag`. Keys in `mls_kv`:
  `risime/backup/{bk,recovery,record}`, older keys `risime/backup/old/<bk_id hex>` + index
  `risime/backup/old-index`.
- **Recovery key:** 7 groups of 4 Crockford characters; input case-insensitive, white space and
  `-` ignored, `O`→0, `I`/`L`→1. **Passphrase bytes:** NFKC, then Unicode `White_Space` trimmed.
- **Errors** (`BackupError`, Kotlin `RisiBackupException`): `Typo`, `WrongKey`, `Integrity`,
  `Format`, `Malformed`, `Unsupported`, `WrongAccount`, `NoKey`, `WeakPassphrase`, `Io`, `Storage`.
- **Vectors:** `contract/v1/backup_vectors.json` (4 recovery-key, 3 Argon2id, 2 wrap, 1 DEK, 4
  stream, 30 negative) is written by `cargo test -p risime-mls backup_vectors_write -- --ignored`
  and, independently, by `scripts/gen-backup-vectors` (Python `cryptography` + `argon2-cffi`;
  `--check` verifies every case and compares the file byte for byte). `backup_vectors_match_the_contract`
  fails on any difference. The §22.9 stream sizes 1/65 536/65 537/200 000 are `|D|` (`|P|` = 1,
  65 536, 67 584, 200 704: 65 537 and 200 000 aren't Padmé values).

### Decision note: new core dependencies (for root, decision 059)
Contract v1.22 §22 adds three pure-Rust crates to `risime-mls` (no C code, no `build.rs`
toolchain needs, all already maintained by large ecosystems):
- **`argon2` 0.5.3** (RustCrypto; with `blake2` 0.10, `base64ct`), `default-features = false`,
  features `alloc` + `zeroize`: Argon2id v0x13 for the `KEK`. Cross-checked against argon2-cffi
  (the C reference implementation) by the vectors. The workspace builds `argon2`/`blake2` at
  `opt-level = 3` in dev profiles so debug tests stay fast.
- **`miniz_oxide` 0.8** (with `adler2`): raw DEFLATE inflate in the verify pass, only to find the
  end of `D` and check the zero padding (output discarded, constant memory). Tests also use it to
  deflate. The app keeps `java.util.zip.Deflater/Inflater` for the bundle itself.
- **`unicode-normalization` 0.1.25** (with `tinyvec`): NFKC of passphrases inside the core, so
  every platform derives the same `KEK`.
Rejected: `scrypt`/PBKDF2 (the contract fixes Argon2id), the C `libargon2` (C in the tree), `flate2`
(a wrapper over the same `miniz_oxide`).

## Risi NIF (contract v1.24 §24.11, decision 065): `risime-mls-nif`
The server-side Risi device as a Rustler 0.38 NIF, loaded by `RisiMe.Agent.Mls.Nif` (plain
`:erlang.load_nif`, no Elixir `:rustler` dependency). Build: `scripts/build-mls-nif` (with the
test-only NIFs, feature `test-peer`) or `scripts/build-mls-nif --prod`; it copies the library to
`server/priv/native/` (gitignored). Without it the server compiles and runs, and the NIF test skips.
- **Journal store** (`journal.rs`): the whole state in memory, loaded at `open/5` from sealed
  rows; nested transactions with an undo log per level. Every call returns
  `{:ok, result, journal}`, `journal = [{key, sealed | :delete}]`; the server persists it in one
  Postgres transaction before acting on the result.
- **Sealing:** AES-256-GCM under `RISI_MLS_KEK` (32 bytes), random 96-bit nonce per value,
  `sealed = nonce ‖ ct ‖ tag`, `AAD = "risi-kv-v1" ‖ u16be(len device_id) ‖ device_id ‖ key`.
  A bad row fails `open` with `:tampered`; a wrong-length KEK is `:bad_kek` (never echoed).
- **Official only:** `join_from_welcome` into a non-Official group (a `dm:` group, a Private
  group) is `:private_tab` and is rolled back; `encrypt`, `process_detailed`, `process_commits`
  and `self_update` refuse such a group too.
- **Calls** (all `DirtyCpu`, one mutex per handle): `open`, `signature_public_key`,
  `set_attestation`, `generate_key_packages`, `last_resort_key_package`, `join_from_welcome`,
  `process_detailed`, `process_commits`, `encrypt` (with AAD), `self_update`, `commit_accepted`,
  `commit_rejected`, `purge_group`, `members` (with `kind`), `group_meta` (with `tab`, `chat_id`,
  `agents`), `epoch`. Errors are `{:error, {kind, message}}` (the `MlsError` variants in
  snake_case, plus `private_tab`, `tampered`, `bad_kek`, `bad_arg`, `poisoned`).
- **Tests:** 12 unit tests (seal round trip and binding, nonce uniqueness, tampered rows, the
  journal under nested commit/rollback, a full join → decrypt → reload → send → self-update →
  purge round trip, private_tab refusal); `server/test/risime/agent/mls/nif_test.exs` over the
  real NIF.

## Tests (`cargo test`: 136 core + 4 ignored generators, 16 FFI)
- **`groups`** (v1.9): create/join with PrivateMessage handshakes and meta; DM and group APIs
  don't mix; 0xFA01 key packages (a legacy key package is refused); admin adds/removes users and
  removed devices are locked out; members manage only their own devices; **peers reject
  policy-breaking commits** built straight with OpenMLS (non-admin removal, rename, self-promotion,
  empty admin list) with state unchanged; rename and promotion; pending until accepted, 409, a
  foreign commit discards ours, creation race; rejoin by re-add; reset to a new generation;
  catch-up (skip, gap, removal stops); own accepted commit merged after a restart.
- **`group_policy`:** every case of `contract/v1/group_policy_cases.json` (`cases` through the
  admin policy and, as Official groups, through the tab rules; `tab_cases` through the tab rules).
- **`tabs_v124`:** the attested `kind` read from the leaf; Official with Risi (member removal of
  every agent leaf, admin-only re-add, partial removal admin-only); Private/`dm:`/unlisted agents
  refused on creation and own commits; a modified client's agent add refused by peers and by the
  Welcome check; `tab`/`chat_id` immutable; the unnamed 1:1 Official.
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
- **`calls_v113`** (v1.13): tolerance 32 stored for creator and joiner; the window boundary
  (generation 32 first, then 31…1 decrypt, 0 fails, no key reuse); a v1.12-stored group (5/20 000)
  and a pre-v1.12 one (5/1000) migrate to 32/20 000 on load; 2 000 missed call signals, then a
  text decrypts on a device that got none of them (also after migration).
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
- **`history`** (v1.15):
  - unit (`history`): the contract vectors equal the generator's and every case passes the
    verifier, the verifier rejects altered vectors, production seal round trip (four segments)
    with fresh `K` and ephemeral per seal, a media blob doesn't open under the history label,
    input checks (lengths, ids, part/parts, sizes, a low-order `rpk` leaves no file), the `info`
    and `aad` layout; (`aad`) the `'H'` round trip and `'D'`/`'H'` swaps refused;
  - `tests/history_v115.rs`: keygen/seal/open/forget for two parts (a forged provider, swapped
    parts, `UnknownRequest` after forget and after reopening, no key material left), `rsk` absent
    after the caller's rollback and after a failed write, `process_detailed` on `'H'` at an epoch
    without an admin record returns `None` (a `'D'` there is `Malformed`), own versus member from
    the MLS sender;
  - `risime-mls-ffi/tests/history`: the flow across the FFI, the contract vectors through
    `historyVectorsCheck`, and a check that no exported history function or record carries `rsk`
    or `K`.

- **`backup`** (v1.22):
  - unit (`backup`): the contract vectors equal the generator's and all 44 cases pass, altered
    vectors are rejected, recovery-key format (every single-character substitution is a `Typo`,
    confusables, lengths), NFKC + trim and the floor, key-record shapes (the contract examples
    parse; KDF/kind/length/count violations), writer/reader round trips at chunk edges with real
    DEFLATE (0 B … 1.1 MB, odd write sizes), an empty bundle and a failed or dropped writer leave
    no file, **every tamper case** (header byte, swapped/dropped chunks, truncation at a boundary
    and mid-chunk, appended bytes, wrong magic, last flag stripped, final flag early or on chunk 0,
    a wrong `user_id` in the DEK AAD, another account's header, nonzero padding, non-DEFLATE,
    over-padding), `read` before `verify` and a file changed after it, listing checks and
    `NoKey`, **a caller rollback leaves no `BK`**, older keys by `bk_id` and `make_current`;
  - `tests/backup_v122.rs`: setup reuse, passphrase add (floor) and restore on a new device
    (`WrongKey`, `Typo`, `Malformed`), restore from the file header's record alone, a record of
    another user (`Integrity`), rotation (same `bk_id`, passphrase wrap kept, old record still
    opens old files), `R` kept or dropped on a passphrase unlock, Argon2id timing;
  - `risime-mls-ffi/tests/backup`: the flow across the FFI, the contract vectors through
    `backupVectorsCheck`, and the export list (no `BK`/`KEK`/`DEK` field or function).

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
