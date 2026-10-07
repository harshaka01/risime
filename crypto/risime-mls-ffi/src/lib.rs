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

/// What `processDetailed` adds for an application message (contract v1.12 §15).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ApplicationDetails {
    /// The sender's leaf index at the message's epoch. Diagnostics only: leaf indices are reused,
    /// so never store or compare it as an identity (compare `sender.userId`).
    pub sender_leaf: u32,
    /// The message's MLS `authenticated_data` (signed and AEAD-covered). Empty for everything but
    /// a `delete` control; decode it with `deleteAadDecode` and compare the target set.
    pub authenticated_data: Vec<u8>,
    /// The sender's user was an admin at the message's epoch (the core's per-epoch record).
    /// Null in DM groups. In a group without a record for that epoch, a message with a non-empty
    /// AAD throws `Malformed`; one with an empty AAD gets null (only pre-v1.12 epochs).
    pub sender_is_admin: Option<bool>,
}

impl From<risime_mls::ApplicationDetails> for ApplicationDetails {
    fn from(d: risime_mls::ApplicationDetails) -> Self {
        Self {
            sender_leaf: d.sender_leaf,
            authenticated_data: d.authenticated_data,
            sender_is_admin: d.sender_is_admin,
        }
    }
}

/// Result of `processDetailed`: the same `IncomingMessage` as `process`, plus `application`
/// exactly when it is an `Application`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ProcessedMessage {
    pub incoming: IncomingMessage,
    pub application: Option<ApplicationDetails>,
}

/// The canonical MLS `authenticated_data` of a `delete` control (contract v1.12 §15.3):
/// `0x01 0x44` + the targets as 16-byte UUIDs, sorted, distinct, 1..=100. Pass it to
/// `encryptWithAad`. Duplicates, an empty list, > 100 or a non-UUID throw `Malformed`.
#[uniffi::export]
pub fn delete_aad_encode(targets: Vec<String>) -> Result<Vec<u8>> {
    Ok(risime_mls::encode_delete_aad(&targets)?)
}

/// The targets of a delete control's AAD (lowercase UUIDs, ascending). Anything but the exact
/// canonical encoding throws `Malformed`: drop the control.
#[uniffi::export]
pub fn delete_aad_decode(aad: Vec<u8>) -> Result<Vec<String>> {
    Ok(risime_mls::decode_delete_aad(&aad)?)
}

// ---------------------------------------------------------------------------------------------
// Group records (contract v1.9 §12)
// ---------------------------------------------------------------------------------------------

/// `group_meta` (GroupContext extension 0xFA01). `icon_json` is the raw JSON of `icon` (null
/// until the images slice defines it). Fields unknown to this version are kept by the core when
/// it rewrites the meta.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GroupMeta {
    pub name: String,
    pub icon_json: Option<String>,
    pub admins: Vec<String>,
}

impl From<risime_mls::GroupMeta> for GroupMeta {
    fn from(m: risime_mls::GroupMeta) -> Self {
        Self {
            name: m.name,
            icon_json: m.icon.map(|v| v.to_string()),
            admins: m.admins,
        }
    }
}

impl TryFrom<GroupMeta> for risime_mls::GroupMeta {
    type Error = RisiMlsError;
    fn try_from(m: GroupMeta) -> Result<Self> {
        let mut meta = risime_mls::GroupMeta::new(m.name, m.admins);
        meta.icon = match m.icon_json {
            None => None,
            Some(j) => match serde_json::from_str::<serde_json::Value>(&j)
                .map_err(|e| RisiMlsError::Malformed(format!("icon_json: {e}")))?
            {
                serde_json::Value::Null => None,
                v => Some(v),
            },
        };
        meta.validate()?;
        Ok(meta)
    }
}

/// A group commit of ours waiting for `POST /mls/groups/{id}/commit` (§12.4). Send `epoch` as
/// the request's `epoch` and `meta_changed` as-is. If `commit_needs_ref` / `welcome_needs_ref`
/// (larger than 64 KiB), upload the bytes as a blob and send `commit_ref` / `welcome_ref`. On
/// `200` call `commitAccepted`; on a `409` call `commitRejected`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GroupCommit {
    pub commit: Vec<u8>,
    pub welcome: Option<Vec<u8>>,
    pub epoch: u64,
    pub added: Vec<DeviceId>,
    pub removed: Vec<DeviceId>,
    pub meta_changed: bool,
    pub commit_size: u64,
    pub welcome_size: u64,
    pub commit_needs_ref: bool,
    pub welcome_needs_ref: bool,
}

impl From<risime_mls::GroupCommit> for GroupCommit {
    fn from(g: risime_mls::GroupCommit) -> Self {
        Self {
            commit_size: g.commit_size() as u64,
            welcome_size: g.welcome_size() as u64,
            commit_needs_ref: g.commit_needs_ref(),
            welcome_needs_ref: g.welcome_needs_ref(),
            commit: g.commit,
            welcome: g.welcome,
            epoch: g.epoch,
            added: devices(g.added),
            removed: devices(g.removed),
            meta_changed: g.meta_changed,
        }
    }
}

/// Result of `processCommits` (catch-up, §12.8).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CatchUpResult {
    /// The group's epoch afterwards.
    pub epoch: u64,
    /// One `Commit` per applied commit, in order. Our own accepted commit (found in the log after
    /// a restart) is merged and reported with `committer` = this device.
    pub applied: Vec<IncomingMessage>,
    /// Commits below our epoch (already applied).
    pub skipped: u32,
    /// The batch removed this device; later commits were not looked at. Wipe the group.
    pub removed_self: bool,
}

/// The contract's group limits (v1.9 §12.0, §12.4).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GroupLimits {
    /// Inline commit/Welcome values above this go by blob reference.
    pub max_inline_bytes: u64,
    pub max_commit_bytes: u64,
    pub max_welcome_bytes: u64,
    pub max_group_users: u32,
    pub max_group_leaves: u32,
    /// The `risime.group_meta` extension type and key-package capability (0xFA01).
    pub group_meta_extension: u16,
}

#[uniffi::export]
pub fn group_limits() -> GroupLimits {
    GroupLimits {
        max_inline_bytes: risime_mls::MAX_INLINE_BYTES as u64,
        max_commit_bytes: risime_mls::MAX_COMMIT_BYTES as u64,
        max_welcome_bytes: risime_mls::MAX_WELCOME_BYTES as u64,
        max_group_users: risime_mls::MAX_GROUP_USERS as u32,
        max_group_leaves: risime_mls::MAX_GROUP_LEAVES as u32,
        group_meta_extension: risime_mls::GROUP_META_EXTENSION,
    }
}

/// The device capabilities (contract §12.1) whose rules this core enforces on its own, e.g.
/// `member_devices` (v1.14 §12.4a). An app advertises such a capability only when it is listed
/// here, so the advertisement never runs ahead of the bundled core.
#[uniffi::export]
pub fn core_capabilities() -> Vec<String> {
    risime_mls::CORE_CAPABILITIES
        .iter()
        .map(|c| c.to_string())
        .collect()
}

/// Whether a key package (TLS bytes) is groups-capable (carries capability 0xFA01). It is
/// validated first.
#[uniffi::export]
pub fn key_package_supports_groups(key_package: Vec<u8>) -> Result<bool> {
    Ok(risime_mls::key_package_supports_groups(&key_package)?)
}

fn core_devices(v: Vec<DeviceId>) -> Result<Vec<risime_mls::DeviceId>> {
    v.into_iter().map(risime_mls::DeviceId::try_from).collect()
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

    /// `encrypt` with the PrivateMessage's `authenticated_data` set to `aad` (contract v1.12
    /// §15.3: a delete control sends `deleteAadEncode(targets)`; everything else uses `encrypt`).
    pub fn encrypt_with_aad(
        &self,
        group_id: Vec<u8>,
        plaintext: Vec<u8>,
        aad: Vec<u8>,
    ) -> Result<Vec<u8>> {
        self.with(|c| c.encrypt_with_aad(&group_id, &plaintext, &aad))
    }

    pub fn decrypt(&self, group_id: Vec<u8>, message: Vec<u8>) -> Result<Vec<u8>> {
        self.with(|c| c.decrypt(&group_id, &message))
    }

    pub fn process(&self, group_id: Vec<u8>, message: Vec<u8>) -> Result<IncomingMessage> {
        self.with(|c| c.process(&group_id, &message))
            .map(Into::into)
    }

    /// `process`, plus for an application message the sender's leaf, the `authenticated_data`
    /// and `senderIsAdmin` at the message's epoch (contract v1.12 §15.3, §15.4). Same transaction.
    pub fn process_detailed(
        &self,
        group_id: Vec<u8>,
        message: Vec<u8>,
    ) -> Result<ProcessedMessage> {
        self.with(|c| c.process_detailed(&group_id, &message))
            .map(|p| ProcessedMessage {
                incoming: p.incoming.into(),
                application: p.application.map(Into::into),
            })
    }

    /// The admin list recorded for `epoch` of a group (current epoch plus the 3 past ones); null
    /// for a DM, an epoch outside that window, or before this device joined.
    pub fn admins_at_epoch(&self, group_id: Vec<u8>, epoch: u64) -> Result<Option<Vec<String>>> {
        self.with(|c| c.admins_at_epoch(&group_id, epoch))
    }

    /// Remove all core state for the group (OpenMLS state, pending commit, admin records), known
    /// or not; idempotent. For Delete chat and the start-up orphan sweep.
    pub fn purge_group(&self, group_id: Vec<u8>) -> Result<()> {
        self.with(|c| c.purge_group(&group_id))
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

    // ---- Groups (contract v1.9 §12) ----

    /// Create a `grp:` group (id `"grp:<uuid>#<generation>"`) at epoch 0 with `meta`, adding every
    /// claimed key package (each must be groups-capable). This device's user must be in
    /// `meta.admins`. Also the rebuild after a reset (a new generation). The commit stays pending.
    pub fn create_group_with_meta(
        &self,
        group_id: Vec<u8>,
        key_packages: Vec<Vec<u8>>,
        meta: GroupMeta,
    ) -> Result<GroupCommit> {
        let meta = meta.try_into()?;
        self.with(|c| c.create_group_with_meta(&group_id, &key_packages, &meta))
            .map(Into::into)
    }

    /// One commit that adds the owners of `key_packages` and removes `remove` (an `add`, `remove`
    /// or `devices` op). A device in both lists is re-added (`rejoin`). Admin policy and caps are
    /// checked first (`PolicyViolation`).
    pub fn change_members(
        &self,
        group_id: Vec<u8>,
        key_packages: Vec<Vec<u8>>,
        remove: Vec<DeviceId>,
    ) -> Result<GroupCommit> {
        let remove = core_devices(remove)?;
        self.with(|c| c.change_members(&group_id, &key_packages, &remove))
            .map(Into::into)
    }

    /// One commit that removes every leaf of `user_ids` (a `remove` op or a member's leave).
    /// This device's own user can't be listed.
    pub fn remove_users(&self, group_id: Vec<u8>, user_ids: Vec<String>) -> Result<GroupCommit> {
        self.with(|c| c.remove_users(&group_id, &user_ids))
            .map(Into::into)
    }

    /// A GroupContextExtensions commit setting `group_meta` (rename, or a `role` op's admin
    /// list). Admins only. `meta_changed` is true.
    pub fn update_group_meta(&self, group_id: Vec<u8>, meta: GroupMeta) -> Result<GroupCommit> {
        let meta = meta.try_into()?;
        self.with(|c| c.update_group_meta(&group_id, &meta))
            .map(Into::into)
    }

    /// An empty commit with a fresh path (key rotation). Anyone may send it.
    pub fn self_update(&self, group_id: Vec<u8>) -> Result<GroupCommit> {
        self.with(|c| c.self_update(&group_id)).map(Into::into)
    }

    /// The group's current `group_meta`; null for a DM group.
    pub fn group_meta(&self, group_id: Vec<u8>) -> Result<Option<GroupMeta>> {
        self.with(|c| c.group_meta(&group_id))
            .map(|m| m.map(Into::into))
    }

    /// Apply a run of commits in log order (`GET …/commits`), all or nothing. Commits below our
    /// epoch are skipped; a gap is `WrongEpoch`; our own pending commit found in the log is
    /// merged; it stops after a commit that removes this device.
    pub fn process_commits(
        &self,
        group_id: Vec<u8>,
        commits: Vec<Vec<u8>>,
    ) -> Result<CatchUpResult> {
        self.with(|c| c.process_commits(&group_id, &commits))
            .map(|u| CatchUpResult {
                epoch: u.epoch,
                applied: u.applied.into_iter().map(Into::into).collect(),
                skipped: u.skipped,
                removed_self: u.removed_self,
            })
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
    let dm_epoch = alice.epoch(g)?;

    // Groups (v1.9): create with meta, add, rename, remove; PrivateMessage handshakes.
    let gg = b"grp:self-test#1".to_vec();
    let meta = GroupMeta {
        name: "Self-test".into(),
        icon_json: None,
        admins: vec!["alice".into()],
    };
    let gc = alice.create_group_with_meta(gg.clone(), bob.generate_key_packages(1)?, meta)?;
    alice.commit_accepted(gg.clone())?;
    bob.join_from_welcome(gc.welcome.unwrap_or_default())?;
    let add = alice.change_members(gg.clone(), carol.generate_key_packages(1)?, vec![])?;
    alice.commit_accepted(gg.clone())?;
    bob.process(gg.clone(), add.commit)?;
    carol.join_from_welcome(add.welcome.unwrap_or_default())?;
    let rn = alice.update_group_meta(
        gg.clone(),
        GroupMeta {
            name: "Renamed".into(),
            icon_json: None,
            admins: vec!["alice".into()],
        },
    )?;
    alice.commit_accepted(gg.clone())?;
    let up = carol.process_commits(gg.clone(), vec![rn.commit.clone()])?;
    bob.process(gg.clone(), rn.commit)?;
    check(
        up.epoch == 3 && carol.group_meta(gg.clone())?.map(|m| m.name) == Some("Renamed".into()),
        "group rename",
    )?;
    let rm = alice.remove_users(gg.clone(), vec!["carol".into()])?;
    alice.commit_accepted(gg.clone())?;
    bob.process(gg.clone(), rm.commit.clone())?;
    let removed = matches!(
        carol.process(gg.clone(), rm.commit)?,
        IncomingMessage::Commit {
            removed_self: true,
            ..
        }
    );
    check(removed, "carol removed from the group")?;
    let hi = bob.encrypt(gg.clone(), b"group hello".to_vec())?;
    check(
        alice.decrypt(gg.clone(), hi)? == b"group hello",
        "group message",
    )?;

    Ok(format!(
        "ok: epoch {dm_epoch}, {}, groups epoch {}",
        mls_info(),
        alice.epoch(gg)?
    ))
}

fn check(ok: bool, what: &str) -> Result<()> {
    if ok {
        Ok(())
    } else {
        Err(RisiMlsError::Other(format!("self-test failed: {what}")))
    }
}

// ---------------------------------------------------------------------------------------------
// Media (contract v1.11 §14.3, blob format `A256GCM-S64K`)
// ---------------------------------------------------------------------------------------------

/// Media failures; every one of them is "Couldn't open this photo". Kotlin: `RisiMediaException`.
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum RisiMediaError {
    /// The blob doesn't match the envelope (size, SHA-256, a segment tag, the final flag, the
    /// key).
    #[error("media integrity check failed: {0}")]
    Integrity(String),
    /// Malformed envelope or blob (key/digest length, `plain_size` vs size, padding, empty).
    #[error("malformed media: {0}")]
    Format(String),
    /// `alg` is not `A256GCM-S64K`.
    #[error("unsupported media alg: {0}")]
    Unsupported(String),
    /// Over the 16 MiB `media` cap.
    #[error("media too large: {0}")]
    TooLarge(String),
    /// A file couldn't be read or written.
    #[error("media io: {0}")]
    Io(String),
}

impl From<risime_mls::media::MediaError> for RisiMediaError {
    fn from(e: risime_mls::media::MediaError) -> Self {
        use risime_mls::media::MediaError as E;
        match e {
            E::Integrity(s) => Self::Integrity(s),
            E::Format(s) => Self::Format(s),
            E::Unsupported(s) => Self::Unsupported(s),
            E::TooLarge(s) => Self::TooLarge(s),
            E::Io(s) => Self::Io(s),
        }
    }
}

/// What [`media_encrypt_file`] produced: the envelope's `enc` (`key`, `alg`, `plain_size`) and
/// `blob` (`size` = `cipher_size`, `sha256`). Seal `key` at rest and drop it as soon as possible.
#[derive(uniffi::Record)]
pub struct SealedMedia {
    pub key: Vec<u8>,
    pub alg: String,
    pub plain_size: u64,
    pub cipher_size: u64,
    pub sha256: Vec<u8>,
}

/// Format constants and caps.
#[derive(uniffi::Record)]
pub struct MediaLimits {
    /// `"A256GCM-S64K"`.
    pub alg: String,
    /// Plaintext bytes per segment (65 536); ciphertext segments are 16 bytes longer.
    pub segment_size: u64,
    /// The `media` cap on `cipher_size` (16 MiB).
    pub max_media_cipher_size: u64,
    /// The `icon` cap on `cipher_size` (512 KiB); the caller checks it after encrypting.
    pub max_icon_cipher_size: u64,
    /// The largest plaintext under the `media` cap (16 515 072).
    pub max_media_plain_size: u64,
}

#[uniffi::export]
pub fn media_limits() -> MediaLimits {
    use risime_mls::media as m;
    MediaLimits {
        alg: m::MEDIA_ALG.into(),
        segment_size: m::MEDIA_SEGMENT,
        max_media_cipher_size: m::MAX_MEDIA_CIPHER_SIZE,
        max_icon_cipher_size: m::MAX_ICON_CIPHER_SIZE,
        max_media_plain_size: m::MAX_MEDIA_PLAIN_SIZE,
    }
}

/// The `cipher_size` (`blob.size`) of a `plain_size`-byte image: `Padmé(L) + 16·segments`.
/// `null` for 0. Receivers check `blob.size == mediaCipherSize(enc.plain_size)` before fetching.
#[uniffi::export]
pub fn media_cipher_size(plain_size: u64) -> Option<u64> {
    risime_mls::media::cipher_size_for(plain_size)
}

/// Encrypts the (re-encoded) image file `src` into the blob file `dst` under a **fresh random
/// key** generated here. `dst` is written atomically. Blocking: call it off the main thread.
/// Retries re-upload the same `dst`; if it is lost, call this again (new key, new
/// `client_blob_id`).
#[uniffi::export]
pub fn media_encrypt_file(
    src: String,
    dst: String,
) -> std::result::Result<SealedMedia, RisiMediaError> {
    let s = risime_mls::media::encrypt_file(src.as_ref(), dst.as_ref())?;
    Ok(SealedMedia {
        key: s.key.to_vec(),
        alg: s.alg.clone(),
        plain_size: s.plain_size,
        cipher_size: s.cipher_size,
        sha256: s.sha256.to_vec(),
    })
}

/// Decrypts the blob file `src` into memory (decrypt-on-display). Returns only after the size,
/// the SHA-256, every segment, the final flag and the padding verified. Blocking.
#[uniffi::export]
pub fn media_decrypt_file(
    src: String,
    key: Vec<u8>,
    alg: String,
    plain_size: u64,
    cipher_size: u64,
    sha256: Vec<u8>,
) -> std::result::Result<Vec<u8>, RisiMediaError> {
    let r = risime_mls::media::MediaRef {
        key: &key,
        alg: &alg,
        plain_size,
        cipher_size,
        sha256: &sha256,
    };
    Ok(risime_mls::media::decrypt_file(src.as_ref(), &r)?.to_vec())
}

/// As [`media_decrypt_file`], streaming into the file `dst` with constant memory (save to
/// gallery, share). `dst` appears only after every check passed. Blocking.
#[uniffi::export]
pub fn media_decrypt_file_to_file(
    src: String,
    dst: String,
    key: Vec<u8>,
    alg: String,
    plain_size: u64,
    cipher_size: u64,
    sha256: Vec<u8>,
) -> std::result::Result<(), RisiMediaError> {
    let r = risime_mls::media::MediaRef {
        key: &key,
        alg: &alg,
        plain_size,
        cipher_size,
        sha256: &sha256,
    };
    Ok(risime_mls::media::decrypt_file_to_file(
        src.as_ref(),
        dst.as_ref(),
        &r,
    )?)
}

/// For a `Range`-resumed download: the length of the leading whole segments of the partial file
/// `src` that verify in order. Truncate the `.part` file to it and resume from there. A missing
/// file is 0; a complete valid blob is `cipher_size`.
#[uniffi::export]
pub fn media_verified_prefix(
    src: String,
    key: Vec<u8>,
    alg: String,
    cipher_size: u64,
) -> std::result::Result<u64, RisiMediaError> {
    Ok(risime_mls::media::verified_prefix(
        src.as_ref(),
        &key,
        &alg,
        cipher_size,
    )?)
}
