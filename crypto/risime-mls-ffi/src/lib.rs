//! UniFFI binding of `risime-mls` (decisions 012, 033; contract v1.7 §10). A thin, FFI-only
//! layer: every MLS decision lives in the core crate. Kotlin package: `lk.codegen.risime.crypto`.
//!
//! Threading: each `MlsClient` serialises its calls with an internal mutex. Calls are blocking and
//! CPU-bound (a few milliseconds), so make them from a background thread. The client calls back
//! into the app's `KvStore` **on the calling thread**, so the app can wrap a call in its own Room
//! transaction (decrypt → insert → cursor). The store must never call back into the `MlsClient`.

use std::sync::{Arc, Mutex};

use risime_mls::{Client, Incoming, MlsError, TrustAnchors};

uniffi::setup_scaffolding!();

/// Mirrors `risime_mls::MlsError` 1:1; becomes the Kotlin sealed class `RisiMlsException`.
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum RisiMlsError {
    #[error("malformed input: {0}")]
    Malformed(String),
    #[error("invalid key package: {0}")]
    InvalidKeyPackage(String),
    #[error("untrusted credential: {0}")]
    UntrustedCredential(String),
    #[error("no attestation set for this device")]
    MissingAttestation,
    #[error("unknown group")]
    UnknownGroup,
    #[error("group already exists")]
    GroupExists,
    #[error("unknown member")]
    UnknownMember,
    #[error("removed from group")]
    RemovedFromGroup,
    #[error("message is for a different epoch")]
    WrongEpoch,
    #[error("decryption failed: {0}")]
    DecryptionFailed(String),
    #[error("not an application message")]
    NotApplicationMessage,
    #[error("welcome failed: {0}")]
    Welcome(String),
    #[error("a commit is already pending for this group")]
    CommitPending,
    #[error("no pending commit")]
    NoPendingCommit,
    #[error("storage: {0}")]
    Storage(String),
    #[error("mls: {0}")]
    Other(String),
    /// A group commit breaks the admin policy or the caps (contract v1.9 §12.4).
    #[error("policy violation: {0}")]
    PolicyViolation(String),
}

impl From<MlsError> for RisiMlsError {
    fn from(e: MlsError) -> Self {
        match e {
            MlsError::Malformed(s) => Self::Malformed(s),
            MlsError::InvalidKeyPackage(s) => Self::InvalidKeyPackage(s),
            MlsError::UntrustedCredential(s) => Self::UntrustedCredential(s),
            MlsError::MissingAttestation => Self::MissingAttestation,
            MlsError::UnknownGroup => Self::UnknownGroup,
            MlsError::GroupExists => Self::GroupExists,
            MlsError::UnknownMember => Self::UnknownMember,
            MlsError::RemovedFromGroup => Self::RemovedFromGroup,
            MlsError::WrongEpoch => Self::WrongEpoch,
            MlsError::DecryptionFailed(s) => Self::DecryptionFailed(s),
            MlsError::NotApplicationMessage => Self::NotApplicationMessage,
            MlsError::Welcome(s) => Self::Welcome(s),
            MlsError::CommitPending => Self::CommitPending,
            MlsError::NoPendingCommit => Self::NoPendingCommit,
            MlsError::Storage(s) => Self::Storage(s),
            MlsError::Other(s) => Self::Other(s),
            MlsError::PolicyViolation(s) => Self::PolicyViolation(s),
        }
    }
}

type Result<T> = std::result::Result<T, RisiMlsError>;

// ---------------------------------------------------------------------------------------------
// Storage callback
// ---------------------------------------------------------------------------------------------

/// Thrown by the app's [`KvStore`]. Kotlin: `KvStoreException.Failed(reason)`.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum KvStoreError {
    #[error("{reason}")]
    Failed { reason: String },
}

impl From<uniffi::UnexpectedUniFFICallbackError> for KvStoreError {
    fn from(e: uniffi::UnexpectedUniFFICallbackError) -> Self {
        Self::Failed { reason: e.reason }
    }
}

/// The app's storage for MLS state (decision 033). It is a sealed key-value table in the message
/// database.
/// - `begin` = `SAVEPOINT`, `commit` = `RELEASE`, `rollback` = `ROLLBACK TO` + `RELEASE`.
/// - Transactions nest, and the core never assumes it owns the outermost one.
/// - Keys are at most a few hundred bytes. Values can be up to a few hundred KiB.
#[uniffi::export(with_foreign)]
pub trait KvStore: Send + Sync {
    fn get(&self, key: Vec<u8>) -> std::result::Result<Option<Vec<u8>>, KvStoreError>;
    fn put(&self, key: Vec<u8>, value: Vec<u8>) -> std::result::Result<(), KvStoreError>;
    fn delete(&self, key: Vec<u8>) -> std::result::Result<(), KvStoreError>;
    fn begin(&self) -> std::result::Result<(), KvStoreError>;
    fn commit(&self) -> std::result::Result<(), KvStoreError>;
    fn rollback(&self) -> std::result::Result<(), KvStoreError>;
}

struct ForeignKv(Arc<dyn KvStore>);

fn kv_err(e: KvStoreError) -> risime_mls::KvError {
    risime_mls::KvError(e.to_string())
}

impl risime_mls::KvStore for ForeignKv {
    fn get(&self, key: &[u8]) -> std::result::Result<Option<Vec<u8>>, risime_mls::KvError> {
        self.0.get(key.to_vec()).map_err(kv_err)
    }
    fn put(&self, key: &[u8], value: &[u8]) -> std::result::Result<(), risime_mls::KvError> {
        self.0.put(key.to_vec(), value.to_vec()).map_err(kv_err)
    }
    fn delete(&self, key: &[u8]) -> std::result::Result<(), risime_mls::KvError> {
        self.0.delete(key.to_vec()).map_err(kv_err)
    }
    fn begin(&self) -> std::result::Result<(), risime_mls::KvError> {
        self.0.begin().map_err(kv_err)
    }
    fn commit(&self) -> std::result::Result<(), risime_mls::KvError> {
        self.0.commit().map_err(kv_err)
    }
    fn rollback(&self) -> std::result::Result<(), risime_mls::KvError> {
        self.0.rollback().map_err(kv_err)
    }
}

/// An in-memory `KvStore` with nestable transactions, implemented in Rust, for JVM tests and the
/// self-test. Kotlin: `InMemoryKvStore()`.
#[derive(uniffi::Object, Default)]
pub struct InMemoryKvStore {
    inner: risime_mls::MemoryKvStore,
}

#[uniffi::export]
impl InMemoryKvStore {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    /// The number of open transactions (it is 0 between calls).
    pub fn depth(&self) -> u32 {
        self.inner.depth() as u32
    }
}

fn mem_err(e: risime_mls::KvError) -> KvStoreError {
    KvStoreError::Failed { reason: e.0 }
}

#[uniffi::export]
impl KvStore for InMemoryKvStore {
    fn get(&self, key: Vec<u8>) -> std::result::Result<Option<Vec<u8>>, KvStoreError> {
        risime_mls::KvStore::get(&self.inner, &key).map_err(mem_err)
    }
    fn put(&self, key: Vec<u8>, value: Vec<u8>) -> std::result::Result<(), KvStoreError> {
        risime_mls::KvStore::put(&self.inner, &key, &value).map_err(mem_err)
    }
    fn delete(&self, key: Vec<u8>) -> std::result::Result<(), KvStoreError> {
        risime_mls::KvStore::delete(&self.inner, &key).map_err(mem_err)
    }
    fn begin(&self) -> std::result::Result<(), KvStoreError> {
        risime_mls::KvStore::begin(&self.inner).map_err(mem_err)
    }
    fn commit(&self) -> std::result::Result<(), KvStoreError> {
        risime_mls::KvStore::commit(&self.inner).map_err(mem_err)
    }
    fn rollback(&self) -> std::result::Result<(), KvStoreError> {
        risime_mls::KvStore::rollback(&self.inner).map_err(mem_err)
    }
}

// ---------------------------------------------------------------------------------------------
// Records
// ---------------------------------------------------------------------------------------------

/// One device of one user. Its leaf identity is `"<user_id>/<device_id>"`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DeviceId {
    pub user_id: String,
    pub device_id: String,
}

impl From<risime_mls::DeviceId> for DeviceId {
    fn from(d: risime_mls::DeviceId) -> Self {
        Self {
            user_id: d.user_id,
            device_id: d.device_id,
        }
    }
}

impl TryFrom<DeviceId> for risime_mls::DeviceId {
    type Error = RisiMlsError;
    fn try_from(d: DeviceId) -> Result<Self> {
        Ok(risime_mls::DeviceId::new(d.user_id, d.device_id)?)
    }
}

fn devices(v: Vec<risime_mls::DeviceId>) -> Vec<DeviceId> {
    v.into_iter().map(Into::into).collect()
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MemberInfo {
    pub user_id: String,
    pub device_id: String,
    pub leaf_index: u32,
    pub signature_key: Vec<u8>,
}

impl From<risime_mls::MemberInfo> for MemberInfo {
    fn from(m: risime_mls::MemberInfo) -> Self {
        Self {
            user_id: m.user_id,
            device_id: m.device_id,
            leaf_index: m.leaf_index,
            signature_key: m.signature_key,
        }
    }
}

/// A commit of ours waiting for `POST /mls/groups/{id}/commit`. Send `epoch` as the request's
/// `epoch`. On `200` call `commitAccepted`; on a `409` call `commitRejected`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PendingCommit {
    pub commit: Vec<u8>,
    pub welcome: Option<Vec<u8>>,
    pub epoch: u64,
    pub added: Vec<DeviceId>,
    pub removed: Vec<DeviceId>,
}

impl From<risime_mls::PendingCommit> for PendingCommit {
    fn from(p: risime_mls::PendingCommit) -> Self {
        Self {
            commit: p.commit,
            welcome: p.welcome,
            epoch: p.epoch,
            added: devices(p.added),
            removed: devices(p.removed),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct JoinedGroup {
    pub group_id: Vec<u8>,
    pub epoch: u64,
    pub members: Vec<MemberInfo>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum IncomingMessage {
    /// Check that `sender` matches the event's `from` / `from_device`.
    Application {
        sender: DeviceId,
        plaintext: Vec<u8>,
        epoch: u64,
    },
    /// If `removed_self` is set, wipe the group (`deleteGroup`). If `discarded_own_pending` is
    /// set, redo our change if it is still needed. `meta_changed` (groups): re-read `groupMeta`.
    Commit {
        epoch: u64,
        committer: DeviceId,
        added: Vec<DeviceId>,
        removed: Vec<DeviceId>,
        removed_self: bool,
        discarded_own_pending: bool,
        meta_changed: bool,
    },
    /// Our own message, echoed back. Ignore it.
    OwnEcho,
}

impl From<Incoming> for IncomingMessage {
    fn from(i: Incoming) -> Self {
        match i {
            Incoming::Application {
                sender,
                plaintext,
                epoch,
            } => Self::Application {
                sender: sender.into(),
                plaintext,
                epoch,
            },
            Incoming::Commit {
                epoch,
                committer,
                added,
                removed,
                removed_self,
                discarded_own_pending,
                meta_changed,
            } => Self::Commit {
                epoch,
                committer: committer.into(),
                added: devices(added),
                removed: devices(removed),
                removed_self,
                discarded_own_pending,
                meta_changed,
            },
            Incoming::OwnEcho => Self::OwnEcho,
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Client
// ---------------------------------------------------------------------------------------------

/// One device's MLS state, persisted in the app's `KvStore`.
#[derive(uniffi::Object)]
pub struct MlsClient {
    inner: Mutex<Client>,
}

impl MlsClient {
    fn with<T>(&self, f: impl FnOnce(&mut Client) -> risime_mls::Result<T>) -> Result<T> {
        let mut c = self
            .inner
            .lock()
            .map_err(|_| RisiMlsError::Other("poisoned".into()))?;
        f(&mut c).map_err(Into::into)
    }
}

#[uniffi::export]
impl MlsClient {
    /// Open, or create on first use, this device's MLS state.
    /// `trusted_keys_jwks` are the pinned attestation keys: public JWK JSON strings, as served by
    /// `GET /api/v1/mls/attestation_keys`.
    #[uniffi::constructor]
    pub fn open(
        store: Arc<dyn KvStore>,
        user_id: String,
        device_id: String,
        trusted_keys_jwks: Vec<String>,
    ) -> Result<Arc<Self>> {
        let anchors = TrustAnchors::from_jwks(&trusted_keys_jwks)?;
        let device = risime_mls::DeviceId::new(user_id, device_id)?;
        let client = Client::open(Arc::new(ForeignKv(store)), device, anchors)?;
        Ok(Arc::new(Self {
            inner: Mutex::new(client),
        }))
    }

    pub fn device(&self) -> Result<DeviceId> {
        self.with(|c| Ok(c.device().clone().into()))
    }

    /// `"<user_id>/<device_id>"`.
    pub fn identity(&self) -> Result<String> {
        self.with(|c| Ok(c.identity()))
    }

    /// The raw 32-byte Ed25519 key. Send it base64-encoded as `mls.signature_key` in
    /// `PUT /me/devices/{id}`.
    pub fn signature_public_key(&self) -> Result<Vec<u8>> {
        self.with(|c| Ok(c.signature_public_key()))
    }

    pub fn attestation(&self) -> Result<Option<String>> {
        self.with(|c| Ok(c.attestation().map(str::to_string)))
    }

    /// Store the server's attestation JWS. It is verified against the pinned keys first.
    pub fn set_attestation(&self, jws: String) -> Result<()> {
        self.with(|c| c.set_attestation(&jws))
    }

    /// 1..=100 fresh single-use key packages.
    pub fn generate_key_packages(&self, count: u16) -> Result<Vec<Vec<u8>>> {
        self.with(|c| c.generate_key_packages(count))
    }

    pub fn generate_last_resort_key_package(&self) -> Result<Vec<u8>> {
        self.with(|c| c.generate_last_resort_key_package())
    }

    /// Create the group at epoch 0 with every claimed key package. The commit stays pending.
    pub fn create_group(
        &self,
        group_id: Vec<u8>,
        key_packages: Vec<Vec<u8>>,
    ) -> Result<PendingCommit> {
        self.with(|c| c.create_group(&group_id, &key_packages))
            .map(Into::into)
    }

    pub fn add_members(
        &self,
        group_id: Vec<u8>,
        key_packages: Vec<Vec<u8>>,
    ) -> Result<PendingCommit> {
        self.with(|c| c.add_members(&group_id, &key_packages))
            .map(Into::into)
    }

    pub fn remove_members(
        &self,
        group_id: Vec<u8>,
        devices: Vec<DeviceId>,
    ) -> Result<PendingCommit> {
        let devices = devices
            .into_iter()
            .map(risime_mls::DeviceId::try_from)
            .collect::<Result<Vec<_>>>()?;
        self.with(|c| c.remove_members(&group_id, &devices))
            .map(Into::into)
    }

    /// The server answered `200`: merge the pending commit. Returns the new epoch.
    pub fn commit_accepted(&self, group_id: Vec<u8>) -> Result<u64> {
        self.with(|c| c.commit_accepted(&group_id))
    }

    /// The server refused the commit: drop it. At epoch 0 the local group is deleted too.
    pub fn commit_rejected(&self, group_id: Vec<u8>) -> Result<()> {
        self.with(|c| c.commit_rejected(&group_id))
    }

    pub fn has_pending_commit(&self, group_id: Vec<u8>) -> Result<bool> {
        self.with(|c| c.has_pending_commit(&group_id))
    }

    /// Join from a Welcome. A stale local group with the same id (removed, or at an older epoch)
    /// is replaced.
    pub fn join_from_welcome(&self, welcome: Vec<u8>) -> Result<JoinedGroup> {
        self.with(|c| c.join_from_welcome(&welcome))
            .map(|j| JoinedGroup {
                group_id: j.group_id,
                epoch: j.epoch,
                members: j.members.into_iter().map(Into::into).collect(),
            })
    }

    pub fn encrypt(&self, group_id: Vec<u8>, plaintext: Vec<u8>) -> Result<Vec<u8>> {
        self.with(|c| c.encrypt(&group_id, &plaintext))
    }

    pub fn decrypt(&self, group_id: Vec<u8>, message: Vec<u8>) -> Result<Vec<u8>> {
        self.with(|c| c.decrypt(&group_id, &message))
    }

    pub fn process(&self, group_id: Vec<u8>, message: Vec<u8>) -> Result<IncomingMessage> {
        self.with(|c| c.process(&group_id, &message))
            .map(Into::into)
    }

    pub fn delete_group(&self, group_id: Vec<u8>) -> Result<()> {
        self.with(|c| c.delete_group(&group_id))
    }

    pub fn has_group(&self, group_id: Vec<u8>) -> Result<bool> {
        self.with(|c| c.has_group(&group_id))
    }

    pub fn epoch(&self, group_id: Vec<u8>) -> Result<u64> {
        self.with(|c| c.epoch(&group_id))
    }

    pub fn epoch_authenticator(&self, group_id: Vec<u8>) -> Result<Vec<u8>> {
        self.with(|c| c.epoch_authenticator(&group_id))
    }

    pub fn members(&self, group_id: Vec<u8>) -> Result<Vec<MemberInfo>> {
        self.with(|c| c.members(&group_id))
            .map(|v| v.into_iter().map(Into::into).collect())
    }

    pub fn is_active(&self, group_id: Vec<u8>) -> Result<bool> {
        self.with(|c| c.is_active(&group_id))
    }
}

// ---------------------------------------------------------------------------------------------
// Test support
// ---------------------------------------------------------------------------------------------

/// A server-like attestation signer **for tests only**. Its key is derived from a public seed, so
/// it must never be among the pinned production keys.
#[derive(uniffi::Object)]
pub struct TestAttestor {
    inner: risime_mls::TestAttestor,
}

#[uniffi::export]
impl TestAttestor {
    /// `seed` must be 32 bytes.
    #[uniffi::constructor]
    pub fn from_seed(seed: Vec<u8>) -> Result<Arc<Self>> {
        let seed: [u8; 32] = seed
            .try_into()
            .map_err(|_| RisiMlsError::Malformed("seed must be 32 bytes".into()))?;
        Ok(Arc::new(Self {
            inner: risime_mls::TestAttestor::from_seed(seed),
        }))
    }

    pub fn public_jwk(&self) -> String {
        self.inner.public_jwk()
    }

    pub fn kid(&self) -> String {
        self.inner.kid()
    }

    pub fn attest(
        &self,
        user_id: String,
        device_id: String,
        signature_key: Vec<u8>,
        iat: u64,
    ) -> Result<String> {
        let d = risime_mls::DeviceId::new(user_id, device_id)?;
        Ok(self.inner.attest(&d, &signature_key, iat))
    }
}

/// Ciphersuite name and crate version, for Settings → About and bug reports.
#[uniffi::export]
pub fn mls_info() -> String {
    format!(
        "risime-mls {} ({:?})",
        env!("CARGO_PKG_VERSION"),
        risime_mls::CIPHERSUITE
    )
}

/// Runs the whole v1.7 lifecycle in-process through the FFI surface. One call verifies the
/// native library on a real device. It covers:
/// - attested devices;
/// - group creation with a pending commit, then accepted;
/// - a join, and encryption both ways;
/// - adding and removing a member, after which the removed member can't decrypt;
/// - reopening from the same store.
#[uniffi::export]
pub fn self_test() -> Result<String> {
    let attestor = TestAttestor::from_seed(vec![42; 32])?;
    let jwks = vec![attestor.public_jwk()];
    let g = b"dm:self-test#1".to_vec();
    let open = |store: Arc<InMemoryKvStore>, user: &str| -> Result<Arc<MlsClient>> {
        let c = MlsClient::open(store, user.into(), format!("{user}-phone"), jwks.clone())?;
        let jws = attestor.attest(
            user.into(),
            format!("{user}-phone"),
            c.signature_public_key()?,
            1,
        )?;
        c.set_attestation(jws)?;
        Ok(c)
    };
    let bob_store = InMemoryKvStore::new();
    let alice = open(InMemoryKvStore::new(), "alice")?;
    let bob = open(bob_store.clone(), "bob")?;
    let carol = open(InMemoryKvStore::new(), "carol")?;

    let pc = alice.create_group(g.clone(), bob.generate_key_packages(1)?)?;
    check(
        pc.epoch == 0 && alice.has_pending_commit(g.clone())?,
        "pending create",
    )?;
    alice.commit_accepted(g.clone())?;
    bob.join_from_welcome(pc.welcome.unwrap_or_default())?;
    check(
        alice.epoch_authenticator(g.clone())? == bob.epoch_authenticator(g.clone())?,
        "same epoch secrets",
    )?;

    let hi = alice.encrypt(g.clone(), b"hello bob".to_vec())?;
    check(
        bob.decrypt(g.clone(), hi)? == b"hello bob",
        "bob decrypts alice",
    )?;
    let back = bob.encrypt(g.clone(), b"hi alice".to_vec())?;
    check(
        alice.decrypt(g.clone(), back)? == b"hi alice",
        "alice decrypts bob",
    )?;

    let add = alice.add_members(g.clone(), vec![carol.generate_last_resort_key_package()?])?;
    alice.commit_accepted(g.clone())?;
    bob.process(g.clone(), add.commit)?;
    carol.join_from_welcome(add.welcome.unwrap_or_default())?;

    let rm = alice.remove_members(g.clone(), vec![carol.device()?])?;
    alice.commit_accepted(g.clone())?;
    bob.process(g.clone(), rm.commit.clone())?;
    let removed = matches!(
        carol.process(g.clone(), rm.commit)?,
        IncomingMessage::Commit {
            removed_self: true,
            ..
        }
    );
    check(removed, "carol learns she was removed")?;
    let secret = alice.encrypt(g.clone(), b"after carol left".to_vec())?;
    check(
        bob.decrypt(g.clone(), secret.clone())? == b"after carol left",
        "bob after removal",
    )?;
    check(
        matches!(
            carol.decrypt(g.clone(), secret),
            Err(RisiMlsError::RemovedFromGroup)
        ),
        "removed carol cannot decrypt",
    )?;

    drop(bob);
    let bob = MlsClient::open(
        bob_store.clone(),
        "bob".into(),
        "bob-phone".into(),
        jwks.clone(),
    )?;
    let again = alice.encrypt(g.clone(), b"after restart".to_vec())?;
    check(
        bob.decrypt(g.clone(), again)? == b"after restart",
        "bob after reopen",
    )?;
    check(bob_store.depth() == 0, "no open transaction")?;

    Ok(format!("ok: epoch {}, {}", alice.epoch(g)?, mls_info()))
}

fn check(ok: bool, what: &str) -> Result<()> {
    if ok {
        Ok(())
    } else {
        Err(RisiMlsError::Other(format!("self-test failed: {what}")))
    }
}
