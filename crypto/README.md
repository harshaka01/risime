# crypto/ — RisiMe E2EE core (Rust)

Owned by the **root** session. Groundwork for Release 0.3 (MLS end-to-end encryption).
Plan for the Android binding: `docs/decisions/012-e2ee-android-binding-plan.md`.

## Layout
- `Cargo.toml`: Cargo workspace (`Cargo.lock` is committed).
- `risime-mls/`: the core crate. A small bytes-in/bytes-out API over
  [OpenMLS](https://openmls.tech) 0.9 (`openmls_rust_crypto` 0.6, `openmls_basic_credential` 0.6).
- Planned: `risime-mls-ffi/`, the UniFFI `cdylib` that Android loads (see the decision record).

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

## API (`risime_mls::Client`, one per device)
Every MLS object crosses the API as TLS-serialised `Vec<u8>`, so the server and the future FFI
layer only ever handle opaque bytes.

| Call | What it does |
|---|---|
| `Client::new(identity)` | New device: an Ed25519 signature key plus a basic credential carrying `identity` |
| `create_key_package()` | A fresh single-use key package (to be uploaded to the server) |
| `create_group(group_id)` | A new group at epoch 0 with this client as the only member |
| `add_member(group_id, key_package)` | Validates the key package, then returns `{commit, welcome}`; merged locally |
| `join_from_welcome(welcome)` | Joins the group; returns its group id (the ratchet tree comes in the Welcome) |
| `encrypt(group_id, plaintext)` | An application message (PrivateMessage) |
| `decrypt(group_id, msg)` | The plaintext; rejects handshake messages |
| `process(group_id, msg)` | Any incoming message → `Application{sender, plaintext}` / `Commit{epoch, removed_self}` / `Proposal` / `OwnEcho` |
| `remove_member(group_id, identity)` | Returns the commit; merged locally |
| `epoch`, `epoch_authenticator`, `members`, `is_active` | Inspection |

Errors are one coarse enum, `MlsError`: `Malformed`, `InvalidKeyPackage`, `UnknownGroup`,
`GroupExists`, `UnknownMember`, `RemovedFromGroup`, `WrongEpoch`, `DecryptionFailed`,
`NotApplicationMessage`, `Welcome` and `Other`. It maps 1:1 to a Kotlin exception hierarchy.

## What the tests prove (`risime-mls/tests/group_lifecycle.rs`)
- `a_create_group`: group creation, epoch 0, a single member.
- `b_add_member_join_from_welcome_same_epoch`: Bob joins from the Welcome; both sides are at
  epoch 1 with equal epoch authenticators and the same member list.
- `c_encrypt_decrypt_both_directions`: exact plaintext both ways (including UTF-8), and the
  plaintext is not visible in the ciphertext.
- `d_removed_member_cannot_decrypt_new_messages`: after his removal, Bob gets
  `MlsError::RemovedFromGroup` for new messages and cannot send, while Alice and Carol still
  can.
- `d_removed_member_ignoring_commit_cannot_decrypt`: a removed member that never applies its
  removal commit gets `MlsError::WrongEpoch` (it has no keys for the new epoch).
- Negative tests: `tampered_ciphertext_is_rejected` (`DecryptionFailed`),
  `welcome_for_someone_else_is_rejected`, `invalid_key_package_is_rejected` (garbage and a
  broken signature), and `unknown_group_and_member`.

## Spike limitations (to do in 0.3)
- Storage is OpenMLS' in-memory provider. On Android it becomes a persistent `StorageProvider`
  (see the decision record).
- Commits are merged as soon as they are created. Production must wait for the server to accept
  the commit (ordering per group), and roll back with `clear_pending_commit` if it is rejected.
- There is no credential validation beyond the MLS signatures. The server-attested binding of
  identity to signature key is an open question in the decision record.


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
