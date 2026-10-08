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

// ---------------------------------------------------------------------------------------------
// History sharing (contract v1.15 §17.3). `rsk` and `K` never cross this boundary: no function
// here returns them, and none takes them (except `historyVectorsCheck`, a test-vector verifier
// that returns only a count).
// ---------------------------------------------------------------------------------------------

/// History failures. Kotlin: `RisiHistoryException`.
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum RisiHistoryError {
    /// Bad input (a length, an id, part/parts, an empty or too large plaintext, a non-canonical
    /// AAD): drop the envelope.
    #[error("malformed history input: {0}")]
    Malformed(String),
    /// A low-order `rpk`: don't share to it.
    #[error("history seal refused: {0}")]
    SealRefused(String),
    /// The sealed key or the blob didn't verify: reject the part (ack `rejected`).
    #[error("history open failed: {0}")]
    OpenFailed(String),
    /// No `rsk` stored for the request: close the request locally.
    #[error("no key for this history request")]
    UnknownRequest,
    #[error("history io: {0}")]
    Io(String),
    #[error("storage: {0}")]
    Storage(String),
}

impl From<risime_mls::HistoryError> for RisiHistoryError {
    fn from(e: risime_mls::HistoryError) -> Self {
        use risime_mls::HistoryError as E;
        match e {
            E::Malformed(s) => Self::Malformed(s),
            E::SealRefused(s) => Self::SealRefused(s),
            E::OpenFailed(s) => Self::OpenFailed(s),
            E::UnknownRequest => Self::UnknownRequest,
            E::Io(s) => Self::Io(s),
            E::Storage(s) => Self::Storage(s),
        }
    }
}

type HResult<T> = std::result::Result<T, RisiHistoryError>;

/// The one record behind the HPKE `info` and `aad` (§17.3). `requester` and `provider` are
/// `"<user_id>/<device_id>"` from MLS credentials only (requester: this device; provider: the
/// `history_share`'s MLS sender). `sha256`/`plain_size` are the share's `blob.sha256` and
/// `enc.plain_size` when opening; `historySeal` ignores them (pass empty / 0).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryContext {
    pub request_id: String,
    pub conversation_id: String,
    pub requester: String,
    pub provider: String,
    pub part: u32,
    pub parts: u32,
    pub sha256: Vec<u8>,
    pub plain_size: u64,
}

impl From<HistoryContext> for risime_mls::HistoryContext {
    fn from(c: HistoryContext) -> Self {
        Self {
            request_id: c.request_id,
            conversation_id: c.conversation_id,
            requester: c.requester,
            provider: c.provider,
            part: c.part,
            parts: c.parts,
            sha256: c.sha256,
            plain_size: c.plain_size,
        }
    }
}

/// What `historySeal` produced: `enc.hpke_enc` (32 bytes), `enc.sealed_key` (48 bytes),
/// `enc.plain_size`, `blob.size`, `blob.sha256`. No key material.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SealedPart {
    pub hpke_enc: Vec<u8>,
    pub sealed_key: Vec<u8>,
    pub plain_size: u64,
    pub size: u64,
    pub sha256: Vec<u8>,
}

/// Own versus member for a history envelope's MLS sender (§17.7, crypto R4).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistorySender {
    pub device: DeviceId,
    /// The sender's user is this device's user (own); otherwise member (always ask).
    pub own: bool,
    /// The sender leaf's signature key: with `device.deviceId`, the option-A approval key.
    pub signature_key: Vec<u8>,
}

/// History constants (§17.3, §17.6).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryLimits {
    /// `enc.label`: `"risime-history-v1"`.
    pub label: String,
    /// `enc.alg`: `"A256GCM-S64K"`.
    pub alg: String,
    pub rpk_len: u32,
    pub hpke_enc_len: u32,
    pub sealed_key_len: u32,
    pub max_parts: u32,
    /// The largest plaintext of one part (16 515 072).
    pub max_plain_size: u64,
}

#[uniffi::export]
pub fn history_limits() -> HistoryLimits {
    use risime_mls::history as h;
    HistoryLimits {
        label: h::HISTORY_LABEL.into(),
        alg: risime_mls::media::MEDIA_ALG.into(),
        rpk_len: h::HISTORY_RPK_LEN as u32,
        hpke_enc_len: h::HISTORY_HPKE_ENC_LEN as u32,
        sealed_key_len: h::HISTORY_SEALED_KEY_LEN as u32,
        max_parts: h::MAX_HISTORY_PARTS,
        max_plain_size: h::MAX_HISTORY_PLAIN_SIZE,
    }
}

/// The canonical MLS `authenticated_data` of a `history_request` / `history_share` (§17.3):
/// `0x01 0x48` + the `request_id` as 16 raw bytes (18 bytes). Pass it to `encryptWithAad`.
#[uniffi::export]
pub fn history_aad_encode(request_id: String) -> Result<Vec<u8>> {
    Ok(risime_mls::encode_history_aad(&request_id)?)
}

/// The `request_id` (lowercase UUID) of an `'H'` AAD. Anything but exactly that form throws
/// `Malformed`: drop the envelope.
#[uniffi::export]
pub fn history_aad_decode(aad: Vec<u8>) -> Result<String> {
    Ok(risime_mls::decode_history_aad(&aad)?)
}

/// Seals one bundle part for the requester's `rpk` (from the decrypted `history_request` only):
/// a fresh `K` generated here, the part written to `out_path` as an `A256GCM-S64K` blob with the
/// history label (atomically), `K` HPKE-sealed with `ctx`. The plaintext crosses as bytes
/// (≤ 16 515 072), never as a file. Blocking: call it off the main thread.
#[uniffi::export]
pub fn history_seal(
    rpk: Vec<u8>,
    ctx: HistoryContext,
    plaintext: Vec<u8>,
    out_path: String,
) -> HResult<SealedPart> {
    let s = risime_mls::history::seal(&rpk, &ctx.into(), &plaintext, out_path.as_ref())?;
    Ok(SealedPart {
        hpke_enc: s.hpke_enc,
        sealed_key: s.sealed_key,
        plain_size: s.plain_size,
        size: s.size,
        sha256: s.sha256.to_vec(),
    })
}

/// **Test support:** verifies every case of `contract/v1/history_vectors.json` (passed as its
/// JSON text) with this core; returns the number of cases checked, or throws `Malformed` naming
/// the first failing case. Scratch blobs go to `work_dir` (e.g. the app's cache dir).
#[uniffi::export]
pub fn history_vectors_check(vectors_json: String, work_dir: String) -> HResult<u32> {
    Ok(risime_mls::history::check_vectors(
        &vectors_json,
        work_dir.as_ref(),
    )?)
}

impl MlsClient {
    fn with_history<T>(
        &self,
        f: impl FnOnce(&mut Client) -> risime_mls::history::HistoryResult<T>,
    ) -> HResult<T> {
        let mut c = self
            .inner
            .lock()
            .map_err(|_| RisiHistoryError::Storage("poisoned".into()))?;
        f(&mut c).map_err(Into::into)
    }
}

#[uniffi::export]
impl MlsClient {
    /// Makes the request's HPKE key pair inside the core, stores `rsk` (under
    /// `risime/history/<request_id>`) in the caller's transaction and returns only `rpk` (32
    /// bytes). Write the request row in the same Room transaction, before `history:request`.
    pub fn history_keygen(&self, request_id: String) -> HResult<Vec<u8>> {
        self.with_history(|c| c.history_keygen(&request_id))
    }

    /// The stored `rpk` of a request (resend it in a refreshed `history_request`); null when no
    /// key is stored.
    pub fn history_public_key(&self, request_id: String) -> HResult<Option<Vec<u8>>> {
        self.with_history(|c| c.history_public_key(&request_id))
    }

    /// Opens one part with the stored `rsk`: the HPKE open of `K`, then the blob at `in_path`
    /// (every segment, the final flag and the SHA-256 verified before any byte is returned).
    /// Blocking.
    pub fn history_open(
        &self,
        request_id: String,
        ctx: HistoryContext,
        hpke_enc: Vec<u8>,
        sealed_key: Vec<u8>,
        in_path: String,
    ) -> HResult<Vec<u8>> {
        let ctx = ctx.into();
        self.with_history(|c| {
            c.history_open(&request_id, &ctx, &hpke_enc, &sealed_key, in_path.as_ref())
                .map(|p| p.to_vec())
        })
    }

    /// Deletes a request's `rsk` (after the last part, cancel, any terminal state, the 48-h
    /// sweep). Idempotent.
    pub fn history_forget(&self, request_id: String) -> HResult<()> {
        self.with_history(|c| c.history_forget(&request_id))
    }

    /// Request ids that still have a stored `rsk` (for the start-up sweep).
    pub fn history_open_requests(&self) -> HResult<Vec<String>> {
        self.with_history(|c| c.history_open_requests())
    }

    /// Own versus member for the MLS `sender` of a decrypted history envelope (from
    /// `processDetailed`), plus the sender leaf's signature key. The sender must be a current leaf
    /// (`UnknownMember` otherwise).
    pub fn history_sender(&self, group_id: Vec<u8>, sender: DeviceId) -> Result<HistorySender> {
        let sender = risime_mls::DeviceId::try_from(sender)?;
        self.with(|c| c.history_sender(&group_id, &sender))
            .map(|s| HistorySender {
                device: s.device.into(),
                own: s.own,
                signature_key: s.signature_key,
            })
    }
}

// ---------------------------------------------------------------------------------------------
// Group call frame keys (contract v1.19 §20.6; crypto review K1, K2, K10)
// ---------------------------------------------------------------------------------------------
// The MLS exporter output (`call_secret`) never crosses the FFI: only the per-leaf frame keys do,
// for LiveKit's `FrameCryptor`. There is no exported `export_secret`.

/// One leaf's frame key: `setKey(identity, base64(key), key_index)` (§20.6 "Install").
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct CallFrameKey {
    /// The leaf identity `"<user_id>/<device_id>"` (= the LiveKit participant identity).
    pub identity: String,
    /// 32 bytes. Memory only: never persist or log; wipe on leaving and at call end (K10).
    pub key: Vec<u8>,
}

impl std::fmt::Debug for CallFrameKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "CallFrameKey {{ identity: {:?}, key: *** }}",
            self.identity
        )
    }
}

/// Every leaf's frame key for one call at the group's current epoch.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CallFrameKeys {
    /// The epoch the keys were derived at (the group's current epoch).
    pub epoch: u64,
    /// `epoch mod 16`: the key ring index for `setKey` and the device's own `setKeyIndex`.
    pub key_index: u32,
    /// One per leaf, in leaf order, this device included.
    pub keys: Vec<CallFrameKey>,
}

impl From<risime_mls::CallFrameKeys> for CallFrameKeys {
    fn from(mut k: risime_mls::CallFrameKeys) -> Self {
        Self {
            epoch: k.epoch,
            key_index: u32::from(k.key_index),
            // The core's keys are wiped when `k` drops; these copies belong to the caller.
            keys: k
                .keys
                .iter_mut()
                .map(|x| CallFrameKey {
                    identity: x.identity.clone(),
                    key: std::mem::take(&mut x.key),
                })
                .collect(),
        }
    }
}

#[uniffi::export]
impl MlsClient {
    /// Every leaf's frame key for the group call `call_id` at the group's **current** epoch
    /// (§20.6): `call_secret = MLS-Exporter("risime-call-v1", call_id's 16 bytes, 32)` and
    /// `HKDF-Expand-SHA256(call_secret, "risime-call-v1 frame" ‖ 0x00 ‖ identity, 32)` per leaf,
    /// all inside the core. `group_id` is the local MLS group id of the `grp:` conversation (the
    /// same bytes as for `encrypt`); `call_id` comes from the MLS-authenticated `call_offer` (or
    /// this device's own), never from the server.
    ///
    /// Catch up the group's commits first (K4) and call again after every merged commit (K3).
    /// Errors: `Malformed` (a DM group, or `call_id` not a UUID), `UnknownGroup` (no local state),
    /// `RemovedFromGroup`, `Storage`. Read-only: no state changes.
    pub fn call_frame_keys(&self, group_id: Vec<u8>, call_id: String) -> Result<CallFrameKeys> {
        self.with(|c| c.call_frame_keys(&group_id, &call_id))
            .map(Into::into)
    }
}

/// The exporter label registry (§10.3): the only labels the core exports with (`risime-call-v1`).
#[uniffi::export]
pub fn exporter_labels() -> Vec<String> {
    risime_mls::EXPORTER_LABELS
        .iter()
        .map(|s| s.to_string())
        .collect()
}

/// **Test support:** verifies every case of `contract/v1/call_vectors.json` (passed as its JSON
/// text) with this core; returns the number of cases checked, or throws `Malformed` naming the
/// first failing case. Returns nothing secret.
#[uniffi::export]
pub fn call_vectors_check(vectors_json: String) -> Result<u32> {
    Ok(risime_mls::call::check_vectors(&vectors_json)?)
}

// ---------------------------------------------------------------------------------------------
// Encrypted backups (contract v1.22 §22, decision 059; crypto review C1–C9)
// ---------------------------------------------------------------------------------------------
// `BK`, `KEK` and `DEK` never cross the FFI: no export returns or takes them. `R` crosses only as
// its display string. The bundle crosses as DEFLATE bytes (`write` / `read`), never as a
// plaintext file. Argon2id (≈ 64 MiB, up to a second) runs in `backupSetup`,
// `backupAddPassphrase`, `backupUnlock` and `backupRotateRecoveryKey`: call them off the main
// thread (they hold this client's lock meanwhile).

/// Backup failures. Kotlin: `RisiBackupException`.
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum RisiBackupError {
    /// The recovery key's checksum doesn't match: "Check the recovery key for a typo".
    #[error("that recovery key has a typo")]
    Typo,
    /// "That recovery key or passphrase doesn't match".
    #[error("that recovery key or passphrase doesn't match")]
    WrongKey,
    /// Tampered, truncated, reordered, from another backup, or a mismatching listing.
    #[error("backup integrity check failed: {0}")]
    Integrity(String),
    /// Not a valid backup file.
    #[error("malformed backup file: {0}")]
    Format(String),
    /// Bad input (ids, lengths, record shape, KDF parameters, a recovery key that isn't 28
    /// characters, read before verify, a finished writer).
    #[error("malformed backup input: {0}")]
    Malformed(String),
    /// "Update RisiMe to restore this backup".
    #[error("unsupported backup: {0}")]
    Unsupported(String),
    /// "This backup belongs to another account".
    #[error("this backup belongs to another account")]
    WrongAccount,
    /// No local key for this `bk_id` (base64): unlock the server's record or the file's
    /// `keyRecord` first.
    #[error("no backup key {0} on this device")]
    NoKey(String),
    /// Below the passphrase floor.
    #[error("passphrase too weak: {0}")]
    WeakPassphrase(String),
    #[error("backup io: {0}")]
    Io(String),
    #[error("storage: {0}")]
    Storage(String),
}

impl From<risime_mls::backup::BackupError> for RisiBackupError {
    fn from(e: risime_mls::backup::BackupError) -> Self {
        use risime_mls::backup::BackupError as E;
        match e {
            E::Typo => Self::Typo,
            E::WrongKey => Self::WrongKey,
            E::Integrity(s) => Self::Integrity(s),
            E::Format(s) => Self::Format(s),
            E::Malformed(s) => Self::Malformed(s),
            E::Unsupported(s) => Self::Unsupported(s),
            E::WrongAccount => Self::WrongAccount,
            E::NoKey(s) => Self::NoKey(s),
            E::WeakPassphrase(s) => Self::WeakPassphrase(s),
            E::Io(s) => Self::Io(s),
            E::Storage(s) => Self::Storage(s),
        }
    }
}

type BResult<T> = std::result::Result<T, RisiBackupError>;

/// The secret a key record is unlocked with.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum BackupSecretKind {
    RecoveryKey,
    Passphrase,
}

impl From<BackupSecretKind> for risime_mls::backup::SecretKind {
    fn from(k: BackupSecretKind) -> Self {
        match k {
            BackupSecretKind::RecoveryKey => Self::RecoveryKey,
            BackupSecretKind::Passphrase => Self::Passphrase,
        }
    }
}

/// `backupSetup` / `backupRotateRecoveryKey`: the recovery key to show (never log it) and the
/// `BackupKey` JSON for `PUT /backup_key`.
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct BackupSetup {
    pub recovery_key: String,
    pub key_record: String,
}

impl std::fmt::Debug for BackupSetup {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "BackupSetup {{ recovery_key: ***, key_record: {:?} }}",
            self.key_record
        )
    }
}

impl From<risime_mls::backup::BackupSetup> for BackupSetup {
    fn from(s: risime_mls::backup::BackupSetup) -> Self {
        Self {
            recovery_key: s.recovery_key.to_string(),
            key_record: s.key_record,
        }
    }
}

/// The local backup keys by `bk_id` (base64).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BackupKeyIds {
    /// The account key new backups use.
    pub current: Option<String>,
    /// Older keys kept for local files (drop each with `backupDropKey` once no file needs it).
    pub old: Vec<String>,
}

/// A backup file's header. Unauthenticated until `verify` succeeded.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BackupFileInfo {
    pub backup_id: String,
    pub user_id: String,
    pub created_at: String,
    pub app_version: String,
    /// base64 (8 bytes).
    pub bk_id: String,
    pub schema: u64,
    /// The `BackupKey` JSON at backup time (unlock it to restore a file), or null.
    pub key_record: Option<String>,
}

impl From<risime_mls::backup::BackupFileInfo> for BackupFileInfo {
    fn from(i: risime_mls::backup::BackupFileInfo) -> Self {
        Self {
            backup_id: i.backup_id,
            user_id: i.user_id,
            created_at: i.created_at,
            app_version: i.app_version,
            bk_id: i.bk_id,
            schema: i.schema,
            key_record: i.key_record,
        }
    }
}

/// What a finished backup file is, for `POST /backups` (`size`, `sha256` of the whole file,
/// `bk_id`).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BackupWritten {
    pub backup_id: String,
    pub bk_id: String,
    pub size: u64,
    pub sha256: Vec<u8>,
    /// The DEFLATE bytes written.
    pub data_size: u64,
}

/// The verify pass's result.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BackupVerified {
    /// The total bytes `read` will return (the DEFLATE stream).
    pub data_size: u64,
    pub padded_size: u64,
    pub chunks: u64,
}

/// The core's part of the passphrase floor (§22.2). The app also checks its bundled list of the
/// 10 000 most common passwords (case-insensitive).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum BackupPassphraseFloor {
    Ok,
    /// Fewer than 14 characters and fewer than 4 words of 3+ characters.
    TooShort,
    /// Contains 6 or more consecutive digits of the user's phone number.
    PhoneNumber,
}

/// Backup constants (§22.2, §22.4).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BackupLimits {
    pub magic: String,
    pub format_version: u32,
    pub stream_alg: String,
    pub chunk_size: u64,
    pub max_header_len: u64,
    pub schema: u64,
    pub argon2_m_kib: u32,
    pub argon2_t: u32,
    pub argon2_p: u32,
    pub recovery_key_chars: u32,
    pub passphrase_min_chars: u32,
    pub passphrase_min_words: u32,
}

#[uniffi::export]
pub fn backup_limits() -> BackupLimits {
    use risime_mls::backup::{keys as k, stream as s};
    BackupLimits {
        magic: String::from_utf8_lossy(s::MAGIC).into(),
        format_version: s::FORMAT_VERSION.into(),
        stream_alg: s::STREAM_ALG.into(),
        chunk_size: s::CHUNK,
        max_header_len: s::MAX_HEADER_LEN,
        schema: s::BUNDLE_SCHEMA,
        argon2_m_kib: k::ARGON2_M,
        argon2_t: k::ARGON2_T,
        argon2_p: k::ARGON2_P,
        recovery_key_chars: k::RECOVERY_CHARS as u32,
        passphrase_min_chars: k::FLOOR_CHARS as u32,
        passphrase_min_words: k::FLOOR_WORDS as u32,
    }
}

/// A file's header without any key (for the restore screen and its `keyRecord`). `Format` /
/// `Unsupported` / `Io`.
#[uniffi::export]
pub fn backup_file_info(path: String) -> BResult<BackupFileInfo> {
    Ok(risime_mls::backup::file_info(path.as_ref())?.into())
}

/// The display form of a typed recovery key (`Typo` / `Malformed` otherwise): the input field's
/// live check. No key derivation.
#[uniffi::export]
pub fn backup_normalize_recovery_key(input: String) -> BResult<String> {
    Ok(risime_mls::backup::normalize_recovery_key(&input)?)
}

/// The core's passphrase floor checks; `phone` is the user's own number (any format).
#[uniffi::export]
pub fn backup_passphrase_floor(passphrase: String, phone: Option<String>) -> BackupPassphraseFloor {
    use risime_mls::backup::PassphraseFloor as F;
    match risime_mls::backup::passphrase_floor(&passphrase, phone.as_deref()) {
        F::Ok => BackupPassphraseFloor::Ok,
        F::TooShort => BackupPassphraseFloor::TooShort,
        F::PhoneNumber => BackupPassphraseFloor::PhoneNumber,
    }
}

/// **Test support:** verifies every case of `contract/v1/backup_vectors.json` (passed as its JSON
/// text); returns the number of cases checked (44), or throws `Malformed` naming the first
/// failing case. Returns nothing secret. Runs Argon2id 3 times.
#[uniffi::export]
pub fn backup_vectors_check(vectors_json: String) -> BResult<u32> {
    Ok(risime_mls::backup::check_vectors(&vectors_json)?)
}

/// Streams the app's DEFLATE output (`Deflater(nowrap = true)`) into a backup file. Discard it on
/// any error and start a new backup (new `backup_id`); never resume.
#[derive(uniffi::Object)]
pub struct BackupWriter {
    inner: Mutex<risime_mls::backup::BackupWriter>,
}

#[uniffi::export]
impl BackupWriter {
    /// Appends DEFLATE bytes (any split).
    pub fn write(&self, data: Vec<u8>) -> BResult<()> {
        self.lock()?.write(&data).map_err(Into::into)
    }

    /// Pads, writes the final chunk, fsyncs and renames the file into place.
    pub fn finish(&self) -> BResult<BackupWritten> {
        let w = self.lock()?.finish()?;
        Ok(BackupWritten {
            backup_id: w.backup_id,
            bk_id: w.bk_id,
            size: w.size,
            sha256: w.sha256.to_vec(),
            data_size: w.data_size,
        })
    }
}

impl BackupWriter {
    fn lock(&self) -> BResult<std::sync::MutexGuard<'_, risime_mls::backup::BackupWriter>> {
        self.inner
            .lock()
            .map_err(|_| RisiBackupError::Malformed("poisoned".into()))
    }
}

/// Opens a backup file: `verify` (the whole verify pass) first, then `read` until it returns an
/// empty array. Copy a picked file into app-private storage before opening it.
#[derive(uniffi::Object)]
pub struct BackupReader {
    inner: Mutex<risime_mls::backup::BackupReader>,
}

#[uniffi::export]
impl BackupReader {
    /// The header (authenticated once `verify` succeeded).
    pub fn info(&self) -> BResult<BackupFileInfo> {
        Ok(self.lock()?.info().into())
    }

    /// Every chunk, the final flag, the DEFLATE end and the zero padding. Nothing may be imported
    /// before this succeeded. Blocking: about the time of one read of the file.
    pub fn verify(&self) -> BResult<BackupVerified> {
        let v = self.lock()?.verify()?;
        Ok(BackupVerified {
            data_size: v.data_size,
            padded_size: v.padded_size,
            chunks: v.chunks,
        })
    }

    /// The next piece of the DEFLATE stream (≤ 64 KiB), in order; empty at the end. Throws
    /// `Malformed` before a successful `verify`, `Integrity` if the file changed since.
    pub fn read(&self) -> BResult<Vec<u8>> {
        Ok(self.lock()?.read()?.to_vec())
    }
}

impl BackupReader {
    fn lock(&self) -> BResult<std::sync::MutexGuard<'_, risime_mls::backup::BackupReader>> {
        self.inner
            .lock()
            .map_err(|_| RisiBackupError::Malformed("poisoned".into()))
    }
}

impl MlsClient {
    fn with_backup<T>(
        &self,
        f: impl FnOnce(&mut Client) -> risime_mls::backup::BackupResult<T>,
    ) -> BResult<T> {
        let mut c = self
            .inner
            .lock()
            .map_err(|_| RisiBackupError::Storage("poisoned".into()))?;
        f(&mut c).map_err(Into::into)
    }
}

#[uniffi::export]
impl MlsClient {
    /// Turns backups on, or makes the silent local pair (§22.7): `BK` and `R` made here, or the
    /// stored pair reused; returns the recovery key and the record with its `recovery_key` wrap.
    /// Stored in the caller's transaction. When a server record exists, `backupUnlock` instead.
    pub fn backup_setup(&self, user_id: String) -> BResult<BackupSetup> {
        self.with_backup(|c| c.backup_setup(&user_id))
            .map(Into::into)
    }

    /// Adds or replaces the passphrase wrap; returns the record to `PUT` (same `bk_id`). Check
    /// `backupPassphraseFloor` and the common-password list first (`WeakPassphrase` here).
    pub fn backup_add_passphrase(&self, user_id: String, passphrase: String) -> BResult<String> {
        self.with_backup(|c| c.backup_add_passphrase(&user_id, &passphrase))
    }

    /// Unlocks a key record (server or file header) and stores its `BK`; `makeCurrent` for the
    /// account key (turn-on with an existing record, server restore), false for a file with
    /// another `bk_id`. Returns the `bk_id`. `Typo` / `Malformed` / `WrongKey` / `Integrity`.
    pub fn backup_unlock(
        &self,
        user_id: String,
        key_record: String,
        secret: String,
        kind: BackupSecretKind,
        make_current: bool,
    ) -> BResult<String> {
        self.with_backup(|c| {
            c.backup_unlock(&user_id, &key_record, &secret, kind.into(), make_current)
        })
    }

    /// The stored recovery key (show it behind the device credential), or null.
    pub fn backup_recovery_key(&self) -> BResult<Option<String>> {
        self.with_backup(|c| c.backup_recovery_key())
            .map(|r| r.map(|s| s.to_string()))
    }

    /// "Change recovery key": a new `R` for the same `BK`; `PUT` the returned record.
    pub fn backup_rotate_recovery_key(&self, user_id: String) -> BResult<BackupSetup> {
        self.with_backup(|c| c.backup_rotate_recovery_key(&user_id))
            .map(Into::into)
    }

    /// Deletes every backup key of this device (confirmed wipe, "Reset backup key").
    pub fn backup_forget(&self) -> BResult<()> {
        self.with_backup(|c| c.backup_forget())
    }

    /// The stored key record JSON, or null.
    pub fn backup_key_record(&self) -> BResult<Option<String>> {
        self.with_backup(|c| c.backup_key_record())
    }

    /// The local keys' `bk_id`s.
    pub fn backup_key_ids(&self) -> BResult<BackupKeyIds> {
        self.with_backup(|c| c.backup_key_ids())
            .map(|k| BackupKeyIds {
                current: k.current,
                old: k.old,
            })
    }

    /// Drops an older local key once no local file uses it.
    pub fn backup_drop_key(&self, bk_id: String) -> BResult<()> {
        self.with_backup(|c| c.backup_drop_key(&bk_id))
    }

    /// Starts a backup file at `out_path` (created only by `finish`). `key_record`: the record
    /// for the header (normally `GET /backup_key`); null uses the stored one.
    pub fn backup_writer(
        &self,
        user_id: String,
        backup_id: String,
        created_at: String,
        app_version: String,
        key_record: Option<String>,
        out_path: String,
    ) -> BResult<Arc<BackupWriter>> {
        let w = self.with_backup(|c| {
            c.backup_writer(
                &user_id,
                &backup_id,
                &created_at,
                &app_version,
                key_record.as_deref(),
                out_path.as_ref(),
            )
        })?;
        Ok(Arc::new(BackupWriter {
            inner: Mutex::new(w),
        }))
    }

    /// Opens a backup file for restore. For a server backup pass the listing's `backup_id` and
    /// `bk_id` (`Integrity` on a mismatch). `NoKey`: unlock first.
    pub fn backup_reader(
        &self,
        user_id: String,
        in_path: String,
        expected_backup_id: Option<String>,
        expected_bk_id: Option<String>,
    ) -> BResult<Arc<BackupReader>> {
        let r = self.with_backup(|c| {
            c.backup_reader(
                &user_id,
                in_path.as_ref(),
                expected_backup_id.as_deref(),
                expected_bk_id.as_deref(),
            )
        })?;
        Ok(Arc::new(BackupReader {
            inner: Mutex::new(r),
        }))
    }
}
