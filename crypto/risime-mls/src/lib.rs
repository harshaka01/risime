//! `risime-mls`: the RisiMe end-to-end encryption core (contract v1.7 §10, decisions 012, 032,
//! 033).
//!
//! A small API over [OpenMLS](https://openmls.tech) in which bytes go in and bytes come out. The
//! UniFFI crate `risime-mls-ffi` exposes it to Kotlin. Every MLS wire object crosses the API as
//! TLS-serialised `Vec<u8>`, and no OpenMLS type leaks out.
//!
//! One [`Client`] is one device:
//! - a persistent Ed25519 signature key;
//! - a basic credential whose identity is `"<user_id>/<device_id>"` ([`DeviceId`]);
//! - the server's attestation of that binding, carried in every leaf's `application_id`;
//! - all OpenMLS state, in the app's [`KvStore`].
//!
//! Each public call runs in one nested `KvStore` transaction (a savepoint), which is rolled back
//! on any error.
//!
//! Rules enforced here:
//! - Ciphersuite [`CIPHERSUITE`].
//! - DM commits use PublicMessage framing; `grp:` commits (contract v1.9 §12) and application
//!   messages always use PrivateMessage.
//! - [`MAX_PAST_EPOCHS`] past epochs are kept.
//! - Every leaf is checked by the [`CredentialValidator`]:
//!   - in key packages we add;
//!   - in every leaf of a group we join;
//!   - in every leaf a commit adds or replaces.
//! - Own commits are **pending** until the server accepts them: [`Client::commit_accepted`] or
//!   [`Client::commit_rejected`].
//! - `grp:` groups carry [`GroupMeta`] (extension 0xFA01), and every staged commit passes the
//!   admin policy ([`policy`]); see [`group`].

pub mod aad;
mod admins;
pub mod attestation;
pub mod backup;
pub mod call;
pub mod group;
pub mod history;
pub mod media;
pub mod meta;
pub mod policy;
pub mod storage;

use std::sync::Arc;

use openmls::framing::errors::{MessageDecryptionError, SecretTreeError};
use openmls::prelude::tls_codec::{Deserialize, Serialize};
use openmls::prelude::*;
use openmls_basic_credential::SignatureKeyPair;

pub use aad::{
    DELETE_AAD_PREFIX, HISTORY_AAD_LEN, HISTORY_AAD_PREFIX, MAX_DELETE_TARGETS, decode_delete_aad,
    decode_history_aad, encode_delete_aad, encode_history_aad, is_history_aad,
};
pub use attestation::{
    CredentialValidator, DeviceId, LeafKind, TestAttestor, TrustAnchors, attested_kind,
};
pub use call::{CALL_EXPORTER_LABEL, CallFrameKey, CallFrameKeys, EXPORTER_LABELS};
pub use group::{
    CatchUp, GroupCommit, MAX_COMMIT_BYTES, MAX_GROUP_LEAVES, MAX_GROUP_USERS, MAX_INLINE_BYTES,
    MAX_WELCOME_BYTES, key_package_supports_groups,
};
pub use history::{HistoryContext, HistoryError, HistorySender, SealedPart};
pub use meta::{GROUP_META_EXTENSION, GroupMeta};
pub use storage::{KvError, KvStore, MemoryKvStore, Provider};

/// X25519 + AES-128-GCM + SHA-256 + Ed25519 (RFC 9420 mandatory suite 0x0001).
pub const CIPHERSUITE: Ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

/// Device capabilities (contract §12.1) whose rules this core enforces on its own: an app may
/// advertise them as soon as it bundles this core. `member_devices` (v1.14 §12.4a): the staged-commit
/// policy accepts a member's commit that restores an existing member's devices.
pub const CORE_CAPABILITIES: &[&str] = &["member_devices"];

/// Past epochs whose secrets are kept for late application messages (contract §10.0).
pub const MAX_PAST_EPOCHS: usize = 3;

/// How far ahead of a sender's ratchet head a message may be (contract v1.12 §15.10; OpenMLS's
/// default is 1000). Deleted events, `scope: "me"`, `chat:clear` and old apps leave generation
/// gaps; this keeps them decryptable. Applied to new groups and, once on load, to stored ones.
pub const MAX_FORWARD_DISTANCE: u32 = 20_000;

/// Message keys kept per sender for out-of-order delivery (contract v1.13 §16.12, crypto R2; OpenMLS's
/// default is 5). A margin for bursty call signalling next to texts on the same leaf ratchet; up to
/// this many past message keys per sender per epoch are kept (the forward-secrecy cost is
/// accepted). Applied to new groups and, once on load, to stored ones.
pub const OUT_OF_ORDER_TOLERANCE: u32 = 32;

pub(crate) fn sender_ratchet_config() -> SenderRatchetConfiguration {
    SenderRatchetConfiguration::new(OUT_OF_ORDER_TOLERANCE, MAX_FORWARD_DISTANCE)
}

/// Maximum number of key packages generated in one call (the contract's upload limit).
pub const MAX_KEY_PACKAGE_BATCH: u16 = 100;

/// Errors. They map 1:1 to the Kotlin `RisiMlsException` hierarchy.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum MlsError {
    /// The input bytes or ids were not valid for the expected kind.
    #[error("malformed input: {0}")]
    Malformed(String),
    /// A key package failed signature, lifetime or ciphersuite validation.
    #[error("invalid key package: {0}")]
    InvalidKeyPackage(String),
    /// A leaf's identity or attestation was rejected by the credential validator.
    #[error("untrusted credential: {0}")]
    UntrustedCredential(String),
    /// [`Client::set_attestation`] must be called before creating key packages or groups.
    #[error("no attestation set for this device")]
    MissingAttestation,
    #[error("unknown group")]
    UnknownGroup,
    #[error("group already exists")]
    GroupExists,
    #[error("unknown member")]
    UnknownMember,
    /// This device was removed from the group; it can no longer read or send.
    #[error("removed from group")]
    RemovedFromGroup,
    /// This device has no keys for the message's epoch: a future epoch (commits missing), or
    /// older than [`MAX_PAST_EPOCHS`].
    #[error("message is for a different epoch")]
    WrongEpoch,
    /// AEAD decryption or signature verification failed (tampered or foreign ciphertext).
    #[error("decryption failed: {0}")]
    DecryptionFailed(String),
    /// `decrypt` got a handshake message; use [`Client::process`].
    #[error("not an application message")]
    NotApplicationMessage,
    /// Joining from a Welcome failed (no matching key package, bad signature, ...).
    #[error("welcome failed: {0}")]
    Welcome(String),
    /// A commit of ours is already waiting for the server's verdict.
    #[error("a commit is already pending for this group")]
    CommitPending,
    /// `commit_accepted` or `commit_rejected` was called without a pending commit.
    #[error("no pending commit")]
    NoPendingCommit,
    /// The app's [`KvStore`] failed, or stored state is unreadable.
    #[error("storage: {0}")]
    Storage(String),
    /// Any other OpenMLS failure.
    #[error("mls: {0}")]
    Other(String),
    /// A group commit breaks the admin policy or the caps (contract v1.9 §12.4).
    #[error("policy violation: {0}")]
    PolicyViolation(String),
}

pub type Result<T> = std::result::Result<T, MlsError>;

/// Any OpenMLS error. Storage failures are reported as [`MlsError::Storage`], whichever OpenMLS
/// error type wraps them, so the app can tell "retry later" from "bad input".
fn other(e: impl std::fmt::Display + std::fmt::Debug) -> MlsError {
    if format!("{e:?}").contains("StorageError") {
        MlsError::Storage(e.to_string())
    } else {
        MlsError::Other(e.to_string())
    }
}

fn storage(e: impl std::fmt::Display) -> MlsError {
    MlsError::Storage(e.to_string())
}

/// A group member: one leaf.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemberInfo {
    pub user_id: String,
    pub device_id: String,
    pub leaf_index: u32,
    pub signature_key: Vec<u8>,
    /// The leaf's attested kind (v1.24 §10.0): `"kind": "agent"` in its attestation, else user.
    pub kind: LeafKind,
}

impl MemberInfo {
    pub fn device(&self) -> DeviceId {
        DeviceId {
            user_id: self.user_id.clone(),
            device_id: self.device_id.clone(),
        }
    }
}

/// A commit of ours, waiting for `POST /mls/groups/{id}/commit` (contract §10.2).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PendingCommit {
    /// A PublicMessage commit.
    pub commit: Vec<u8>,
    /// The Welcome for the added devices (present exactly when `added` is not empty).
    pub welcome: Option<Vec<u8>>,
    /// The epoch the commit was built in (the request's `epoch`).
    pub epoch: u64,
    pub added: Vec<DeviceId>,
    pub removed: Vec<DeviceId>,
}

/// Result of [`Client::join_from_welcome`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JoinedGroup {
    pub group_id: Vec<u8>,
    pub epoch: u64,
    pub members: Vec<MemberInfo>,
}

/// Result of processing one incoming MLS message with [`Client::process`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Incoming {
    /// A decrypted application message. `sender` must match the event's `from`/`from_device`.
    Application {
        sender: DeviceId,
        plaintext: Vec<u8>,
        epoch: u64,
    },
    /// A verified commit was merged, and the group is now at `epoch`.
    Commit {
        epoch: u64,
        committer: DeviceId,
        added: Vec<DeviceId>,
        removed: Vec<DeviceId>,
        /// This commit evicted this device. Wipe the group (`delete_group`).
        removed_self: bool,
        /// Our own pending commit was dropped. Redo the change if it is still needed.
        discarded_own_pending: bool,
        /// The commit changed `group_meta` (`grp:` only); read it with [`Client::group_meta`].
        meta_changed: bool,
    },
    /// A message this device sent itself, echoed back. Ignore it.
    OwnEcho,
}

/// What [`Client::process_detailed`] adds for an application message (contract v1.12 §15).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ApplicationDetails {
    /// The sender's leaf index at the message's epoch. Diagnostics only: leaf indices are reused
    /// after a removal, so never store or compare it as an identity (compare `sender.user_id`).
    pub sender_leaf: u32,
    /// The message's MLS `authenticated_data` (signed and AEAD-covered). Empty for every message
    /// but a `delete` control, whose AAD is [`encode_delete_aad`] of its targets, and a
    /// `history_request`/`history_share` control, whose AAD is [`encode_history_aad`] (v1.15).
    pub authenticated_data: Vec<u8>,
    /// Whether the sender's user was an admin at the message's epoch, from the admin list the core
    /// recorded for that epoch. `None` in DM groups. In a `grp:` group without a record for that
    /// epoch, a message with a non-empty AAD fails with [`MlsError::Malformed`]; one with an
    /// empty AAD (not a delete) gets `None` (only epochs from before the v1.12 upgrade).
    /// Always `None` for the canonical `'H'` AAD: admin status is irrelevant to history and the
    /// lookup is skipped (v1.15 §17.3).
    pub sender_is_admin: Option<bool>,
}

/// Result of [`Client::process_detailed`]: the same [`Incoming`] as [`Client::process`], plus the
/// details of an application message.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Processed {
    pub incoming: Incoming,
    /// Present exactly when `incoming` is [`Incoming::Application`].
    pub application: Option<ApplicationDetails>,
}

/// What is stored about this device itself, next to the OpenMLS state.
#[derive(serde::Serialize, serde::Deserialize)]
struct SelfRecord {
    user_id: String,
    device_id: String,
    signature_public_key: Vec<u8>,
    attestation: Option<String>,
}

const SELF_KEY: &[u8] = b"risime/self/v1";

/// One device's MLS state.
pub struct Client {
    kv: Arc<dyn KvStore>,
    provider: Provider,
    signer: SignatureKeyPair,
    device: DeviceId,
    attestation: Option<String>,
    validator: Box<dyn CredentialValidator>,
}

impl Client {
    /// Open (or create, on first use) this device's MLS state in `store`. A store holds exactly
    /// one device. Opening it as another device fails with [`MlsError::Storage`].
    pub fn open(
        store: Arc<dyn KvStore>,
        device: DeviceId,
        validator: impl CredentialValidator + 'static,
    ) -> Result<Self> {
        DeviceId::new(device.user_id.clone(), device.device_id.clone())?;
        let provider = Provider::new(store.clone());
        store.begin().map_err(storage)?;
        let res = Self::load_or_create(&store, &provider, &device);
        match res {
            Ok((signer, attestation)) => {
                store.commit().map_err(storage)?;
                Ok(Self {
                    kv: store,
                    provider,
                    signer,
                    device,
                    attestation,
                    validator: Box::new(validator),
                })
            }
            Err(e) => {
                let _ = store.rollback();
                Err(e)
            }
        }
    }

    /// A client on a fresh [`MemoryKvStore`] (tests, fixtures, self-test).
    pub fn in_memory(
        device: DeviceId,
        validator: impl CredentialValidator + 'static,
    ) -> Result<Self> {
        Self::open(Arc::new(MemoryKvStore::new()), device, validator)
    }

    fn load_or_create(
        store: &Arc<dyn KvStore>,
        provider: &Provider,
        device: &DeviceId,
    ) -> Result<(SignatureKeyPair, Option<String>)> {
        let scheme = CIPHERSUITE.signature_algorithm();
        if let Some(bytes) = store.get(SELF_KEY).map_err(storage)? {
            let rec: SelfRecord = serde_json::from_slice(&bytes).map_err(storage)?;
            if rec.user_id != device.user_id || rec.device_id != device.device_id {
                return Err(MlsError::Storage(format!(
                    "store belongs to {}/{}, not {device}",
                    rec.user_id, rec.device_id
                )));
            }
            let signer =
                SignatureKeyPair::read(provider.storage(), &rec.signature_public_key, scheme)
                    .ok_or_else(|| MlsError::Storage("signature key missing".into()))?;
            return Ok((signer, rec.attestation));
        }
        let signer = SignatureKeyPair::new(scheme).map_err(other)?;
        signer.store(provider.storage()).map_err(storage)?;
        let rec = SelfRecord {
            user_id: device.user_id.clone(),
            device_id: device.device_id.clone(),
            signature_public_key: signer.to_public_vec(),
            attestation: None,
        };
        store
            .put(SELF_KEY, &serde_json::to_vec(&rec).map_err(storage)?)
            .map_err(storage)?;
        Ok((signer, None))
    }

    /// Run `f` inside one nested transaction. Commit on success, roll back on error.
    fn tx<T>(&self, f: impl FnOnce(&Self) -> Result<T>) -> Result<T> {
        self.kv.begin().map_err(storage)?;
        match f(self) {
            Ok(v) => match self.kv.commit() {
                Ok(()) => Ok(v),
                Err(e) => {
                    let _ = self.kv.rollback();
                    Err(storage(e))
                }
            },
            Err(e) => {
                let _ = self.kv.rollback();
                Err(e)
            }
        }
    }

    pub fn device(&self) -> &DeviceId {
        &self.device
    }

    /// `"<user_id>/<device_id>"`.
    pub fn identity(&self) -> String {
        self.device.identity()
    }

    /// The raw 32-byte Ed25519 public key (`PUT /me/devices/{id}` → `mls.signature_key`).
    pub fn signature_public_key(&self) -> Vec<u8> {
        self.signer.to_public_vec()
    }

    pub fn attestation(&self) -> Option<&str> {
        self.attestation.as_deref()
    }

    /// Store the server's attestation for this device. It is verified against the trust anchors
    /// first, so a wrong key or device id is caught here, not by peers.
    pub fn set_attestation(&mut self, jws: &str) -> Result<()> {
        self.validator.validate(
            &self.device,
            &self.signer.to_public_vec(),
            Some(jws.as_bytes()),
        )?;
        self.tx(|c| {
            let rec = SelfRecord {
                user_id: c.device.user_id.clone(),
                device_id: c.device.device_id.clone(),
                signature_public_key: c.signer.to_public_vec(),
                attestation: Some(jws.to_string()),
            };
            c.kv.put(SELF_KEY, &serde_json::to_vec(&rec).map_err(storage)?)
                .map_err(storage)
        })?;
        self.attestation = Some(jws.to_string());
        Ok(())
    }

    fn credential(&self) -> CredentialWithKey {
        CredentialWithKey {
            credential: BasicCredential::new(self.identity().into_bytes()).into(),
            signature_key: self.signer.to_public_vec().into(),
        }
    }

    fn leaf_extensions(&self) -> Result<Extensions<LeafNode>> {
        let jws = self
            .attestation
            .as_ref()
            .ok_or(MlsError::MissingAttestation)?;
        Extensions::single(Extension::ApplicationId(ApplicationIdExtension::new(
            jws.as_bytes(),
        )))
        .map_err(other)
    }

    /// Leaf capabilities: the defaults plus `last_resort` (so any of our key packages may carry
    /// it) and `risime.group_meta` (0xFA01, contract v1.9 §12.1: groups-capable).
    fn capabilities() -> Capabilities {
        Capabilities::new(
            None,
            None,
            Some(&[ExtensionType::LastResort, group::meta_extension_type()]),
            None,
            None,
        )
    }

    fn key_package(&self, last_resort: bool) -> Result<Vec<u8>> {
        let mut builder = KeyPackage::builder()
            .leaf_node_capabilities(Self::capabilities())
            .leaf_node_extensions(self.leaf_extensions()?);
        if last_resort {
            builder = builder.mark_as_last_resort();
        }
        let bundle = builder
            .build(CIPHERSUITE, &self.provider, &self.signer, self.credential())
            .map_err(other)?;
        bundle.key_package().tls_serialize_detached().map_err(other)
    }

    /// `count` (1..=100) fresh single-use key packages, ready to upload.
    pub fn generate_key_packages(&self, count: u16) -> Result<Vec<Vec<u8>>> {
        if count == 0 || count > MAX_KEY_PACKAGE_BATCH {
            return Err(MlsError::Malformed(format!(
                "count must be 1..={MAX_KEY_PACKAGE_BATCH}"
            )));
        }
        self.tx(|c| (0..count).map(|_| c.key_package(false)).collect())
    }

    /// A last-resort key package (RFC 9420 `last_resort` extension). It can be used for several
    /// joins and its private key is kept. Upload it as `last_resort`, and rotate it weekly.
    pub fn generate_last_resort_key_package(&self) -> Result<Vec<u8>> {
        self.tx(|c| c.key_package(true))
    }

    /// Check one leaf: basic credential, a well-formed identity, and an attestation the validator
    /// accepts.
    fn check_leaf(&self, leaf: &LeafNode) -> Result<DeviceId> {
        let cred = leaf.credential();
        if cred.credential_type() != CredentialType::Basic {
            return Err(MlsError::UntrustedCredential(
                "not a basic credential".into(),
            ));
        }
        let device = DeviceId::parse(cred.serialized_content())
            .map_err(|e| MlsError::UntrustedCredential(e.to_string()))?;
        let att = leaf.extensions().application_id().map(|a| a.as_slice());
        self.validator
            .validate(&device, leaf.signature_key().as_slice(), att)?;
        Ok(device)
    }

    /// A leaf's attested kind (v1.24). Only for leaves [`Client::check_leaf`] accepted.
    pub(crate) fn leaf_kind(leaf: &LeafNode) -> LeafKind {
        attested_kind(leaf.extensions().application_id().map(|a| a.as_slice()))
    }

    /// The kind of this device's own leaf, from its attestation.
    pub(crate) fn own_kind(&self) -> LeafKind {
        attested_kind(self.attestation.as_deref().map(str::as_bytes))
    }

    /// §24.1 rule 3: a `dm:` group never holds an agent leaf.
    fn reject_agent_key_packages(kps: &[(KeyPackage, DeviceId)]) -> Result<()> {
        match kps
            .iter()
            .find(|(k, _)| Self::leaf_kind(k.leaf_node()).is_agent())
        {
            Some((_, d)) => Err(MlsError::PolicyViolation(format!(
                "a dm: group never gains an agent leaf ({d})"
            ))),
            None => Ok(()),
        }
    }

    fn validate_key_packages(
        &self,
        key_packages: &[Vec<u8>],
    ) -> Result<Vec<(KeyPackage, DeviceId)>> {
        if key_packages.is_empty() {
            return Err(MlsError::Malformed("no key packages".into()));
        }
        let mut out: Vec<(KeyPackage, DeviceId)> = Vec::with_capacity(key_packages.len());
        for bytes in key_packages {
            let kp = KeyPackageIn::tls_deserialize_exact(bytes)
                .map_err(|e| MlsError::Malformed(e.to_string()))?
                .validate(self.provider.crypto(), ProtocolVersion::Mls10)
                .map_err(|e| MlsError::InvalidKeyPackage(e.to_string()))?;
            if kp.ciphersuite() != CIPHERSUITE {
                return Err(MlsError::InvalidKeyPackage("wrong ciphersuite".into()));
            }
            let device = self.check_leaf(kp.leaf_node())?;
            if device == self.device || out.iter().any(|(_, d)| *d == device) {
                return Err(MlsError::Malformed(format!("duplicate device {device}")));
            }
            out.push((kp, device));
        }
        Ok(out)
    }

    /// Load a group. A group stored with an older sender ratchet configuration (OpenMLS's default
    /// before v1.12, or v1.12's tolerance 5 before v1.13) is migrated once, in its own nested
    /// transaction: the current configuration ([`MAX_FORWARD_DISTANCE`],
    /// [`OUT_OF_ORDER_TOLERANCE`]), and for a `grp:` group the admin record of its current epoch
    /// if it is missing.
    fn load(&self, group_id: &[u8]) -> Result<MlsGroup> {
        let mut group = MlsGroup::load(self.provider.storage(), &GroupId::from_slice(group_id))
            .map_err(storage)?
            .ok_or(MlsError::UnknownGroup)?;
        let ratchet = group.configuration().sender_ratchet_configuration();
        if ratchet.maximum_forward_distance() != MAX_FORWARD_DISTANCE
            || ratchet.out_of_order_tolerance() != OUT_OF_ORDER_TOLERANCE
        {
            self.tx(|c| {
                group
                    .set_configuration(c.provider.storage(), &Self::join_config(group_id))
                    .map_err(storage)?;
                if group::is_group_id(group_id)
                    && c.admins_record(group_id, group.epoch().as_u64())?.is_none()
                {
                    c.record_admins(&group)?;
                }
                Ok(())
            })?;
        }
        Ok(group)
    }

    /// **Tests only:** put a stored group back on OpenMLS's default sender ratchet configuration
    /// (forward distance 1000) and drop its admin records, as a group stored before v1.12. The
    /// next load migrates it.
    #[doc(hidden)]
    pub fn downgrade_group_config_for_tests(&self, group_id: &[u8]) -> Result<()> {
        self.set_group_ratchet_for_tests(group_id, None)?;
        self.tx(|c| c.purge_admins(group_id))
    }

    /// **Tests only:** put a stored group on the v1.12 sender ratchet configuration (out-of-order
    /// tolerance 5, forward distance [`MAX_FORWARD_DISTANCE`]), keeping its admin records, as a
    /// group stored before v1.13. The next load migrates it.
    #[doc(hidden)]
    pub fn downgrade_group_to_v112_for_tests(&self, group_id: &[u8]) -> Result<()> {
        self.set_group_ratchet_for_tests(
            group_id,
            Some(SenderRatchetConfiguration::new(5, MAX_FORWARD_DISTANCE)),
        )
    }

    /// **Tests only:** the stored group's sender ratchet configuration as
    /// `(out_of_order_tolerance, maximum_forward_distance)`, read without the migrating load.
    #[doc(hidden)]
    pub fn stored_ratchet_config_for_tests(&self, group_id: &[u8]) -> Result<(u32, u32)> {
        let group = MlsGroup::load(self.provider.storage(), &GroupId::from_slice(group_id))
            .map_err(storage)?
            .ok_or(MlsError::UnknownGroup)?;
        let r = group.configuration().sender_ratchet_configuration();
        Ok((r.out_of_order_tolerance(), r.maximum_forward_distance()))
    }

    fn set_group_ratchet_for_tests(
        &self,
        group_id: &[u8],
        ratchet: Option<SenderRatchetConfiguration>,
    ) -> Result<()> {
        self.tx(|c| {
            let mut group = MlsGroup::load(c.provider.storage(), &GroupId::from_slice(group_id))
                .map_err(storage)?
                .ok_or(MlsError::UnknownGroup)?;
            let mut b = MlsGroupJoinConfig::builder()
                .use_ratchet_tree_extension(true)
                .wire_format_policy(group::wire_policy(group_id))
                .max_past_epochs(MAX_PAST_EPOCHS);
            if let Some(r) = ratchet {
                b = b.sender_ratchet_configuration(r);
            }
            group
                .set_configuration(c.provider.storage(), &b.build())
                .map_err(storage)
        })
    }

    fn join_config(group_id: &[u8]) -> MlsGroupJoinConfig {
        MlsGroupJoinConfig::builder()
            .use_ratchet_tree_extension(true)
            .wire_format_policy(group::wire_policy(group_id))
            .max_past_epochs(MAX_PAST_EPOCHS)
            .sender_ratchet_configuration(sender_ratchet_config())
            .build()
    }

    fn member_infos(group: &MlsGroup) -> Result<Vec<MemberInfo>> {
        group
            .members()
            .map(|m| {
                let d = DeviceId::parse(m.credential.serialized_content())?;
                let kind = group
                    .public_group()
                    .leaf(m.index)
                    .map(Self::leaf_kind)
                    .unwrap_or_default();
                Ok(MemberInfo {
                    user_id: d.user_id,
                    device_id: d.device_id,
                    leaf_index: m.index.u32(),
                    signature_key: m.signature_key,
                    kind,
                })
            })
            .collect()
    }

    fn kind_at(group: &MlsGroup, index: LeafNodeIndex) -> LeafKind {
        group
            .public_group()
            .leaf(index)
            .map(Self::leaf_kind)
            .unwrap_or_default()
    }

    fn device_at(group: &MlsGroup, index: LeafNodeIndex) -> Result<DeviceId> {
        let leaf = group
            .public_group()
            .leaf(index)
            .ok_or_else(|| MlsError::Other(format!("no leaf at {}", index.u32())))?;
        DeviceId::parse(leaf.credential().serialized_content())
    }

    fn stage_add(
        &self,
        group: &mut MlsGroup,
        kps: Vec<(KeyPackage, DeviceId)>,
    ) -> Result<PendingCommit> {
        let existing = Self::member_infos(group)?;
        if let Some((_, d)) = kps
            .iter()
            .find(|(_, d)| existing.iter().any(|m| m.device() == *d))
        {
            return Err(MlsError::Malformed(format!("{d} is already a member")));
        }
        let epoch = group.epoch().as_u64();
        let (key_packages, added): (Vec<KeyPackage>, Vec<DeviceId>) = kps.into_iter().unzip();
        let (commit, welcome, _group_info) = group
            .add_members(&self.provider, &self.signer, &key_packages)
            .map_err(other)?;
        let commit = commit.to_bytes().map_err(other)?;
        self.record_pending(group.group_id().as_slice(), &commit, &added, &[], false)?;
        Ok(PendingCommit {
            commit,
            welcome: Some(welcome.to_bytes().map_err(other)?),
            epoch,
            added,
            removed: vec![],
        })
    }

    /// Create the group at epoch 0 and stage the commit that adds `key_packages` (all current MLS
    /// devices of both members, contract §10.2). Send it with `epoch` 0. A local epoch-0 group
    /// left over from a lost creation race is replaced.
    /// `grp:` ids need [`Client::create_group_with_meta`].
    pub fn create_group(&self, group_id: &[u8], key_packages: &[Vec<u8>]) -> Result<PendingCommit> {
        if group::is_group_id(group_id) {
            return Err(MlsError::Malformed(
                "grp: groups are created with create_group_with_meta".into(),
            ));
        }
        self.tx(|c| {
            let ext = c.leaf_extensions()?;
            let kps = c.validate_key_packages(key_packages)?;
            Self::reject_agent_key_packages(&kps)?;
            let gid = GroupId::from_slice(group_id);
            if let Some(mut old) = MlsGroup::load(c.provider.storage(), &gid).map_err(storage)? {
                if old.epoch().as_u64() != 0 {
                    return Err(MlsError::GroupExists);
                }
                old.delete(c.provider.storage()).map_err(storage)?;
            }
            let mut group = MlsGroup::builder()
                .ciphersuite(CIPHERSUITE)
                .with_group_id(gid)
                .use_ratchet_tree_extension(true)
                .with_wire_format_policy(PURE_PLAINTEXT_WIRE_FORMAT_POLICY)
                .max_past_epochs(MAX_PAST_EPOCHS)
                .sender_ratchet_configuration(sender_ratchet_config())
                .with_capabilities(Self::capabilities())
                .with_leaf_node_extensions(ext)
                .map_err(other)?
                .build(&c.provider, &c.signer, c.credential())
                .map_err(other)?;
            c.stage_add(&mut group, kps)
        })
    }

    /// Stage a commit that adds the owners of `key_packages`. It is not merged; see
    /// [`Client::commit_accepted`].
    pub fn add_members(&self, group_id: &[u8], key_packages: &[Vec<u8>]) -> Result<PendingCommit> {
        self.tx(|c| {
            if group::is_group_id(group_id) {
                return Err(MlsError::Malformed("grp: groups use change_members".into()));
            }
            let mut group = c.load(group_id)?;
            c.check_can_commit(&group)?;
            let kps = c.validate_key_packages(key_packages)?;
            Self::reject_agent_key_packages(&kps)?;
            c.stage_add(&mut group, kps)
        })
    }

    /// Stage a commit that removes `devices`. It is not merged; see [`Client::commit_accepted`].
    pub fn remove_members(&self, group_id: &[u8], devices: &[DeviceId]) -> Result<PendingCommit> {
        if group::is_group_id(group_id) {
            return Err(MlsError::Malformed("grp: groups use change_members".into()));
        }
        self.tx(|c| {
            let mut group = c.load(group_id)?;
            c.check_can_commit(&group)?;
            if devices.is_empty() {
                return Err(MlsError::Malformed("nothing to remove".into()));
            }
            if devices.contains(&c.device) {
                return Err(MlsError::Malformed("cannot remove own device".into()));
            }
            let members = Self::member_infos(&group)?;
            let indices = devices
                .iter()
                .map(|d| {
                    members
                        .iter()
                        .find(|m| m.device() == *d)
                        .map(|m| LeafNodeIndex::new(m.leaf_index))
                        .ok_or(MlsError::UnknownMember)
                })
                .collect::<Result<Vec<_>>>()?;
            let epoch = group.epoch().as_u64();
            let (commit, _welcome, _gi) = group
                .remove_members(&c.provider, &c.signer, &indices)
                .map_err(other)?;
            let commit = commit.to_bytes().map_err(other)?;
            c.record_pending(group_id, &commit, &[], devices, false)?;
            Ok(PendingCommit {
                commit,
                welcome: None,
                epoch,
                added: vec![],
                removed: devices.to_vec(),
            })
        })
    }

    fn check_can_commit(&self, group: &MlsGroup) -> Result<()> {
        if !group.is_active() {
            return Err(MlsError::RemovedFromGroup);
        }
        if group.pending_commit().is_some() {
            return Err(MlsError::CommitPending);
        }
        Ok(())
    }

    /// The server answered `200`: merge our pending commit. Returns the new epoch.
    pub fn commit_accepted(&self, group_id: &[u8]) -> Result<u64> {
        self.tx(|c| {
            let mut group = c.load(group_id)?;
            if group.pending_commit().is_none() {
                return Err(MlsError::NoPendingCommit);
            }
            group.merge_pending_commit(&c.provider).map_err(other)?;
            c.clear_pending_record(group_id)?;
            c.record_admins(&group)?;
            Ok(group.epoch().as_u64())
        })
    }

    /// The server refused the commit (`409` and similar): drop it. If it was the group's creating
    /// commit (epoch 0), the local group is deleted too. The winner's Welcome will arrive.
    pub fn commit_rejected(&self, group_id: &[u8]) -> Result<()> {
        self.tx(|c| {
            let mut group = c.load(group_id)?;
            if group.pending_commit().is_none() {
                return Err(MlsError::NoPendingCommit);
            }
            c.clear_pending_record(group_id)?;
            if group.epoch().as_u64() == 0 {
                c.purge_admins(group_id)?;
                group.delete(c.provider.storage()).map_err(storage)
            } else {
                group
                    .clear_pending_commit(c.provider.storage())
                    .map_err(storage)
            }
        })
    }

    pub fn has_pending_commit(&self, group_id: &[u8]) -> Result<bool> {
        Ok(self.load(group_id)?.pending_commit().is_some())
    }

    /// Join from a Welcome. Every leaf of the group must pass the credential validator.
    ///
    /// If the group id is already known locally, the local group is **replaced** when it is
    /// inactive (we were removed) or at an older epoch (resync, or a lost creation race).
    /// Otherwise the call fails with [`MlsError::GroupExists`].
    pub fn join_from_welcome(&self, welcome: &[u8]) -> Result<JoinedGroup> {
        self.tx(|c| {
            let msg = MlsMessageIn::tls_deserialize_exact(welcome)
                .map_err(|e| MlsError::Malformed(e.to_string()))?;
            let MlsMessageBodyIn::Welcome(welcome) = msg.extract() else {
                return Err(MlsError::Malformed("not a Welcome".into()));
            };
            let processed =
                ProcessedWelcome::new_from_welcome(&c.provider, &Self::join_config(b""), welcome)
                    .map_err(|e| MlsError::Welcome(e.to_string()))?;
            // Unverified until staged, but any replacement below is rolled back if staging or the
            // leaf checks fail.
            let gid = processed.unverified_group_info().group_id().clone();
            let new_epoch = processed.unverified_group_info().epoch().as_u64();
            if let Some(mut old) = MlsGroup::load(c.provider.storage(), &gid).map_err(storage)? {
                if old.is_active() && old.epoch().as_u64() >= new_epoch {
                    return Err(MlsError::GroupExists);
                }
                old.delete(c.provider.storage()).map_err(storage)?;
                c.purge_admins(gid.as_slice())?;
            }
            let staged = processed
                .into_staged_welcome(&c.provider, None)
                .map_err(|e| MlsError::Welcome(e.to_string()))?;
            if staged.group_context().group_id() != &gid
                || staged.group_context().epoch().as_u64() != new_epoch
            {
                return Err(MlsError::Welcome("group info mismatch".into()));
            }
            let mut group = staged
                .into_group(&c.provider)
                .map_err(|e| MlsError::Welcome(e.to_string()))?;
            for m in group.members() {
                let leaf = group
                    .public_group()
                    .leaf(m.index)
                    .ok_or_else(|| MlsError::Other("missing leaf".into()))?;
                c.check_leaf(leaf)?;
            }
            let members = Self::member_infos(&group)?;
            if group::is_group_id(gid.as_slice()) {
                c.finish_group_join(&mut group)?;
                c.record_admins(&group)?;
            } else if let Some(m) = members.iter().find(|m| m.kind.is_agent()) {
                // §24.1 rule 3.
                return Err(MlsError::Welcome(format!(
                    "a dm: group never holds an agent leaf ({})",
                    m.device()
                )));
            }
            Ok(JoinedGroup {
                group_id: gid.as_slice().to_vec(),
                epoch: new_epoch,
                members,
            })
        })
    }

    /// Encrypt an application message (a PrivateMessage) for the group's current epoch.
    pub fn encrypt(&self, group_id: &[u8], plaintext: &[u8]) -> Result<Vec<u8>> {
        self.encrypt_with_aad(group_id, plaintext, &[])
    }

    /// [`Client::encrypt`] with the PrivateMessage's `authenticated_data` set to `aad` (OpenMLS
    /// `set_aad`). It is cleartext on the wire but signed and AEAD-covered. Contract v1.12 §15.3:
    /// a `delete` control sends [`encode_delete_aad`] of its targets; everything else sends an
    /// empty AAD (plain [`Client::encrypt`]).
    pub fn encrypt_with_aad(
        &self,
        group_id: &[u8],
        plaintext: &[u8],
        aad: &[u8],
    ) -> Result<Vec<u8>> {
        self.tx(|c| {
            let mut group = c.load(group_id)?;
            if !group.is_active() {
                return Err(MlsError::RemovedFromGroup);
            }
            group.set_aad(aad.to_vec());
            let out = group
                .create_message(&c.provider, &c.signer, plaintext)
                .map_err(|e| match e {
                    CreateMessageError::GroupStateError(MlsGroupStateError::UseAfterEviction) => {
                        MlsError::RemovedFromGroup
                    }
                    e => other(e),
                })?;
            out.to_bytes().map_err(other)
        })
    }

    /// Decrypt an application message. Handshakes are rejected with
    /// [`MlsError::NotApplicationMessage`]. Feed them to [`Client::process`].
    pub fn decrypt(&self, group_id: &[u8], message: &[u8]) -> Result<Vec<u8>> {
        match self.process(group_id, message)? {
            Incoming::Application { plaintext, .. } => Ok(plaintext),
            _ => Err(MlsError::NotApplicationMessage),
        }
    }

    /// Process any incoming group message:
    /// - decrypt application messages;
    /// - verify commits, check their new leaves with the validator, and merge them;
    /// - reject standalone proposals, which RisiMe doesn't use.
    pub fn process(&self, group_id: &[u8], message: &[u8]) -> Result<Incoming> {
        self.tx(|c| {
            c.process_inner(group_id, message, false)
                .map(|p| p.incoming)
        })
    }

    /// [`Client::process`], plus for an application message its [`ApplicationDetails`]: the
    /// sender's leaf, the `authenticated_data`, and `sender_is_admin` at the message's epoch
    /// (contract v1.12 §15.3, §15.4). Same transaction, same effects.
    pub fn process_detailed(&self, group_id: &[u8], message: &[u8]) -> Result<Processed> {
        self.tx(|c| c.process_inner(group_id, message, true))
    }

    /// `admin_check`: evaluate `sender_is_admin` (only [`Client::process_detailed`]; the plain
    /// `process` never fails on a missing admin record).
    pub(crate) fn process_inner(
        &self,
        group_id: &[u8],
        message: &[u8],
        admin_check: bool,
    ) -> Result<Processed> {
        let mut group = self.load(group_id)?;
        if !group.is_active() {
            return Err(MlsError::RemovedFromGroup);
        }
        let msg = MlsMessageIn::tls_deserialize_exact(message)
            .map_err(|e| MlsError::Malformed(e.to_string()))?;
        let protocol = msg
            .try_into_protocol_message()
            .map_err(|e| MlsError::Malformed(e.to_string()))?;
        if protocol.group_id().as_slice() != group_id {
            return Err(MlsError::Malformed("message is for another group".into()));
        }
        let had_pending = group.pending_commit().is_some();
        let processed = group
            .process_message(&self.provider, protocol)
            .map_err(map_process_error)?;
        let sender = processed.sender().clone();
        let epoch = processed.epoch().as_u64();
        let credential = processed.credential().clone();
        let aad = processed.aad().to_vec();
        let incoming = match processed.into_content() {
            ProcessedMessageContent::ApplicationMessage(app) => {
                let sender_device = DeviceId::parse(credential.serialized_content())?;
                let Sender::Member(leaf) = sender else {
                    return Err(MlsError::UntrustedCredential(
                        "application message from a non-member".into(),
                    ));
                };
                let sender_is_admin = if !admin_check || aad::is_history_aad(&aad) {
                    None
                } else {
                    match self.admin_at(group_id, epoch, &sender_device.user_id) {
                        Err(MlsError::Malformed(_)) if aad.is_empty() => None,
                        r => r?,
                    }
                };
                return Ok(Processed {
                    incoming: Incoming::Application {
                        sender: sender_device,
                        plaintext: app.into_bytes(),
                        epoch,
                    },
                    application: Some(ApplicationDetails {
                        sender_leaf: leaf.u32(),
                        authenticated_data: aad,
                        sender_is_admin,
                    }),
                });
            }
            ProcessedMessageContent::StagedCommitMessage(staged) => {
                let Sender::Member(committer_index) = sender else {
                    return Err(MlsError::UntrustedCredential(
                        "commit from a non-member".into(),
                    ));
                };
                let committer = Self::device_at(&group, committer_index)?;
                let mut added = Vec::new();
                let mut added_agents = Vec::new();
                for add in staged.add_proposals() {
                    let leaf = add.add_proposal().key_package().leaf_node();
                    let d = self.check_leaf(leaf)?;
                    if Self::leaf_kind(leaf).is_agent() {
                        added_agents.push(d.user_id.clone());
                    }
                    added.push(d);
                }
                for q in staged.queued_proposals() {
                    if let Proposal::Update(update) = q.proposal() {
                        let Sender::Member(i) = q.sender() else {
                            return Err(MlsError::UntrustedCredential(
                                "update from a non-member".into(),
                            ));
                        };
                        let new = self.check_leaf(update.leaf_node())?;
                        if new != Self::device_at(&group, *i)?
                            || Self::leaf_kind(update.leaf_node()) != Self::kind_at(&group, *i)
                        {
                            return Err(MlsError::UntrustedCredential(
                                "update changes a leaf's identity or kind".into(),
                            ));
                        }
                    }
                }
                if let Some(leaf) = staged.update_path_leaf_node()
                    && (self.check_leaf(leaf)? != committer
                        || Self::leaf_kind(leaf) != Self::kind_at(&group, committer_index))
                {
                    return Err(MlsError::UntrustedCredential(
                        "commit changes the committer's identity or kind".into(),
                    ));
                }
                let removed = staged
                    .remove_proposals()
                    .map(|r| Self::device_at(&group, r.remove_proposal().removed()))
                    .collect::<Result<Vec<_>>>()?;
                let meta_changed = if group::is_group_id(group_id) {
                    self.check_staged_policy(
                        &group,
                        &staged,
                        &committer,
                        &added,
                        &added_agents,
                        &removed,
                    )?
                } else {
                    if let Some(a) = added_agents.first() {
                        // §24.1 rule 3.
                        return Err(MlsError::PolicyViolation(format!(
                            "a dm: group never gains an agent leaf ({a})"
                        )));
                    }
                    false
                };
                let removed_self = staged.self_removed();
                group
                    .merge_staged_commit(&self.provider, *staged)
                    .map_err(other)?;
                if had_pending {
                    self.clear_pending_record(group_id)?;
                }
                self.record_admins(&group)?;
                Incoming::Commit {
                    epoch: group.epoch().as_u64(),
                    committer,
                    added,
                    removed,
                    removed_self,
                    discarded_own_pending: had_pending,
                    meta_changed,
                }
            }
            ProcessedMessageContent::ProposalMessage(_)
            | ProcessedMessageContent::ExternalJoinProposalMessage(_) => {
                return Err(MlsError::Malformed(
                    "standalone proposals are not used".into(),
                ));
            }
            ProcessedMessageContent::OwnPendingCommit
            | ProcessedMessageContent::OwnPrivateMessage => Incoming::OwnEcho,
        };
        Ok(Processed {
            incoming,
            application: None,
        })
    }

    /// Forget a group entirely (after `removed_self`, or when the conversation is deleted),
    /// including the core's own records for it (pending commit, per-epoch admins).
    pub fn delete_group(&self, group_id: &[u8]) -> Result<()> {
        self.tx(|c| {
            let mut group = c.load(group_id)?;
            c.clear_pending_record(group_id)?;
            c.purge_admins(group_id)?;
            group.delete(c.provider.storage()).map_err(storage)
        })
    }

    /// Remove **all** core state for `group_id`, whether or not the group is still known: the
    /// OpenMLS group (secrets, past-epoch secrets, tree), the pending-commit record and the
    /// per-epoch admin records. Idempotent: `Ok` when there is nothing left. For Delete chat
    /// (contract v1.12 §15.7) and the start-up sweep of orphaned conversations. The core keeps no
    /// application plaintext, so messages themselves need no core purge (§15.6).
    pub fn purge_group(&self, group_id: &[u8]) -> Result<()> {
        self.tx(|c| {
            let gid = GroupId::from_slice(group_id);
            if let Some(mut group) = MlsGroup::load(c.provider.storage(), &gid).map_err(storage)? {
                group.delete(c.provider.storage()).map_err(storage)?;
            }
            c.clear_pending_record(group_id)?;
            c.purge_admins(group_id)
        })
    }

    pub fn has_group(&self, group_id: &[u8]) -> Result<bool> {
        Ok(
            MlsGroup::load(self.provider.storage(), &GroupId::from_slice(group_id))
                .map_err(storage)?
                .is_some(),
        )
    }

    /// Current epoch of the group.
    pub fn epoch(&self, group_id: &[u8]) -> Result<u64> {
        Ok(self.load(group_id)?.epoch().as_u64())
    }

    /// Equal on all members if and only if they share the same epoch secrets.
    pub fn epoch_authenticator(&self, group_id: &[u8]) -> Result<Vec<u8>> {
        Ok(self
            .load(group_id)?
            .epoch_authenticator()
            .as_slice()
            .to_vec())
    }

    /// All current members, in leaf order.
    pub fn members(&self, group_id: &[u8]) -> Result<Vec<MemberInfo>> {
        Self::member_infos(&self.load(group_id)?)
    }

    /// Whether this device is still a member of the group.
    pub fn is_active(&self, group_id: &[u8]) -> Result<bool> {
        Ok(self.load(group_id)?.is_active())
    }
}

fn map_process_error<E: std::fmt::Display>(e: ProcessMessageError<E>) -> MlsError {
    match e {
        ProcessMessageError::GroupStateError(MlsGroupStateError::UseAfterEviction) => {
            MlsError::RemovedFromGroup
        }
        ProcessMessageError::ValidationError(
            ValidationError::WrongEpoch | ValidationError::NoPastEpochData,
        ) => MlsError::WrongEpoch,
        // An epoch older than MAX_PAST_EPOCHS: its secrets are gone.
        ProcessMessageError::ValidationError(ValidationError::UnableToDecrypt(
            MessageDecryptionError::SecretTreeError(SecretTreeError::TooDistantInThePast),
        )) => MlsError::WrongEpoch,
        ProcessMessageError::ValidationError(
            v @ (ValidationError::UnableToDecrypt(_) | ValidationError::InvalidSignature),
        ) => MlsError::DecryptionFailed(v.to_string()),
        ProcessMessageError::IncompatibleWireFormat => {
            MlsError::Malformed("wrong wire format for this group's handshakes".into())
        }
        ProcessMessageError::StorageError(s) => MlsError::Storage(s.to_string()),
        other_err => MlsError::Other(other_err.to_string()),
    }
}
