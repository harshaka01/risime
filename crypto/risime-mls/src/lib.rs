//! `risime-mls` — the RisiMe end-to-end encryption core (Release 0.3 groundwork).
//!
//! A small, bytes-in / bytes-out wrapper around [OpenMLS](https://openmls.tech) that a later
//! UniFFI `ffi` crate can expose to Kotlin unchanged. Every value that crosses the API boundary
//! is either a primitive, a `Vec<u8>` (TLS-serialised MLS wire objects), or a plain struct/enum
//! of those, so no OpenMLS type leaks out.
//!
//! One [`Client`] is one device: it owns a signature key pair, a basic credential (the user's
//! identity bytes) and the OpenMLS storage provider that holds key packages and group state.
//!
//! Ciphersuite: [`CIPHERSUITE`] (`MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519`), see
//! `docs/decisions/012-e2ee-android-binding-plan.md`.
//!
//! Spike limitation: storage is OpenMLS' in-memory store (`OpenMlsRustCrypto`). Persistence on
//! Android is a custom `StorageProvider`, planned in the decision record.

use std::collections::HashMap;

use openmls::prelude::tls_codec::{Deserialize, Serialize};
use openmls::prelude::*;
use openmls_basic_credential::SignatureKeyPair;
use openmls_rust_crypto::OpenMlsRustCrypto;

/// The single ciphersuite RisiMe uses: X25519 + AES-128-GCM + SHA-256 + Ed25519 (RFC 9420
/// mandatory-to-implement suite, 0x0001).
pub const CIPHERSUITE: Ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

/// Errors returned by this crate. Variants are deliberately coarse and stable so that the
/// UniFFI layer can map them 1:1 to a Kotlin sealed exception hierarchy.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum MlsError {
    /// Input bytes were not a valid TLS-encoded MLS object of the expected kind.
    #[error("malformed input: {0}")]
    Malformed(String),
    /// A key package failed signature / lifetime / ciphersuite validation.
    #[error("invalid key package: {0}")]
    InvalidKeyPackage(String),
    /// No group with this id is known to this client.
    #[error("unknown group")]
    UnknownGroup,
    /// A group with this id already exists on this client.
    #[error("group already exists")]
    GroupExists,
    /// No member with this identity is in the group.
    #[error("unknown member")]
    UnknownMember,
    /// This client was removed from the group; it can no longer read or send.
    #[error("removed from group")]
    RemovedFromGroup,
    /// The message belongs to an epoch this client does not have keys for (for example a
    /// member that was removed but never processed its removal commit).
    #[error("message is for a different epoch")]
    WrongEpoch,
    /// AEAD decryption or signature verification failed (tampered or foreign ciphertext).
    #[error("decryption failed: {0}")]
    DecryptionFailed(String),
    /// A `decrypt` call received a handshake (commit/proposal) instead of an application
    /// message. Use [`Client::process`] for those.
    #[error("not an application message")]
    NotApplicationMessage,
    /// Joining from a Welcome failed (no matching key package, bad signature, ...).
    #[error("welcome failed: {0}")]
    Welcome(String),
    /// Any other OpenMLS failure.
    #[error("mls: {0}")]
    Other(String),
}

pub type Result<T> = std::result::Result<T, MlsError>;

fn other(e: impl std::fmt::Display) -> MlsError {
    MlsError::Other(e.to_string())
}

/// Output of [`Client::add_member`].
#[derive(Debug, Clone)]
pub struct AddMemberOutput {
    /// The commit, to be fanned out to every *existing* member (not the new one).
    pub commit: Vec<u8>,
    /// The Welcome, to be delivered to the new member only.
    pub welcome: Vec<u8>,
}

/// Result of processing one incoming MLS message with [`Client::process`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Incoming {
    /// A decrypted application message.
    Application {
        /// Identity bytes from the sender's credential.
        sender: Vec<u8>,
        plaintext: Vec<u8>,
    },
    /// A commit was verified and merged; the group moved to `epoch`.
    /// `removed_self` is true when this commit evicted this client.
    Commit { epoch: u64, removed_self: bool },
    /// A standalone proposal was stored (it takes effect with a later commit).
    Proposal,
    /// A message this client sent itself, echoed back by the server. Ignore it.
    OwnEcho,
}

/// One device's MLS state.
pub struct Client {
    provider: OpenMlsRustCrypto,
    signer: SignatureKeyPair,
    credential: CredentialWithKey,
    identity: Vec<u8>,
    groups: HashMap<Vec<u8>, MlsGroup>,
}

impl Client {
    /// Create a new device identity: a fresh Ed25519 signature key pair and a basic
    /// credential carrying `identity` (for RisiMe, the stable user id, never a phone number).
    pub fn new(identity: &[u8]) -> Result<Self> {
        let provider = OpenMlsRustCrypto::default();
        let signer = SignatureKeyPair::new(CIPHERSUITE.signature_algorithm()).map_err(other)?;
        signer.store(provider.storage()).map_err(other)?;
        let credential = CredentialWithKey {
            credential: BasicCredential::new(identity.to_vec()).into(),
            signature_key: signer.to_public_vec().into(),
        };
        Ok(Self {
            provider,
            signer,
            credential,
            identity: identity.to_vec(),
            groups: HashMap::new(),
        })
    }

    /// The identity bytes in this client's credential.
    pub fn identity(&self) -> &[u8] {
        &self.identity
    }

    /// This device's public signature key (what a future key-transparency / safety-number
    /// check would compare).
    pub fn signature_public_key(&self) -> Vec<u8> {
        self.signer.to_public_vec()
    }

    /// Generate a fresh single-use key package and return it TLS-serialised, ready to upload
    /// to the server. The private half stays in this client's storage.
    pub fn create_key_package(&self) -> Result<Vec<u8>> {
        let bundle = KeyPackage::builder()
            .build(
                CIPHERSUITE,
                &self.provider,
                &self.signer,
                self.credential.clone(),
            )
            .map_err(other)?;
        bundle.key_package().tls_serialize_detached().map_err(other)
    }

    /// Create a new group with this client as its only member (epoch 0).
    pub fn create_group(&mut self, group_id: &[u8]) -> Result<()> {
        if self.groups.contains_key(group_id) {
            return Err(MlsError::GroupExists);
        }
        let group = MlsGroup::builder()
            .ciphersuite(CIPHERSUITE)
            .with_group_id(GroupId::from_slice(group_id))
            // Ship the ratchet tree inside the Welcome so joiners need no extra fetch.
            .use_ratchet_tree_extension(true)
            .build(&self.provider, &self.signer, self.credential.clone())
            .map_err(other)?;
        self.groups.insert(group_id.to_vec(), group);
        Ok(())
    }

    /// Add the owner of `key_package` to the group. The commit is merged locally at once
    /// (spike simplification: production waits for the server to accept the commit first).
    pub fn add_member(&mut self, group_id: &[u8], key_package: &[u8]) -> Result<AddMemberOutput> {
        let kp_in = KeyPackageIn::tls_deserialize_exact(key_package)
            .map_err(|e| MlsError::Malformed(e.to_string()))?;
        let kp = kp_in
            .validate(self.provider.crypto(), ProtocolVersion::Mls10)
            .map_err(|e| MlsError::InvalidKeyPackage(e.to_string()))?;
        if kp.ciphersuite() != CIPHERSUITE {
            return Err(MlsError::InvalidKeyPackage("wrong ciphersuite".into()));
        }
        let group = self
            .groups
            .get_mut(group_id)
            .ok_or(MlsError::UnknownGroup)?;
        let (commit, welcome, _group_info) = group
            .add_members(&self.provider, &self.signer, core::slice::from_ref(&kp))
            .map_err(other)?;
        group.merge_pending_commit(&self.provider).map_err(other)?;
        Ok(AddMemberOutput {
            commit: commit.to_bytes().map_err(other)?,
            welcome: welcome.to_bytes().map_err(other)?,
        })
    }

    /// Join a group from a Welcome produced by [`Client::add_member`]. Returns the group id.
    pub fn join_from_welcome(&mut self, welcome: &[u8]) -> Result<Vec<u8>> {
        let msg = MlsMessageIn::tls_deserialize_exact(welcome)
            .map_err(|e| MlsError::Malformed(e.to_string()))?;
        let MlsMessageBodyIn::Welcome(welcome) = msg.extract() else {
            return Err(MlsError::Malformed("not a Welcome".into()));
        };
        let join_config = MlsGroupJoinConfig::builder()
            .use_ratchet_tree_extension(true)
            .build();
        let group = StagedWelcome::new_from_welcome(&self.provider, &join_config, welcome, None)
            .map_err(|e| MlsError::Welcome(e.to_string()))?
            .into_group(&self.provider)
            .map_err(|e| MlsError::Welcome(e.to_string()))?;
        let id = group.group_id().as_slice().to_vec();
        if self.groups.contains_key(&id) {
            return Err(MlsError::GroupExists);
        }
        self.groups.insert(id.clone(), group);
        Ok(id)
    }

    /// Encrypt an application message for the current epoch of the group.
    pub fn encrypt(&mut self, group_id: &[u8], plaintext: &[u8]) -> Result<Vec<u8>> {
        let group = self
            .groups
            .get_mut(group_id)
            .ok_or(MlsError::UnknownGroup)?;
        if !group.is_active() {
            return Err(MlsError::RemovedFromGroup);
        }
        let out = group
            .create_message(&self.provider, &self.signer, plaintext)
            .map_err(other)?;
        out.to_bytes().map_err(other)
    }

    /// Decrypt an application message. Handshake messages are rejected with
    /// [`MlsError::NotApplicationMessage`]; feed those to [`Client::process`].
    pub fn decrypt(&mut self, group_id: &[u8], message: &[u8]) -> Result<Vec<u8>> {
        match self.process(group_id, message)? {
            Incoming::Application { plaintext, .. } => Ok(plaintext),
            _ => Err(MlsError::NotApplicationMessage),
        }
    }

    /// Process any incoming group message: decrypts application messages, verifies and merges
    /// commits (including one that removes this client), stores proposals.
    pub fn process(&mut self, group_id: &[u8], message: &[u8]) -> Result<Incoming> {
        let group = self
            .groups
            .get_mut(group_id)
            .ok_or(MlsError::UnknownGroup)?;
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
        let processed = group
            .process_message(&self.provider, protocol)
            .map_err(map_process_error)?;
        let sender = processed.credential().serialized_content().to_vec();
        match processed.into_content() {
            ProcessedMessageContent::ApplicationMessage(app) => Ok(Incoming::Application {
                sender,
                plaintext: app.into_bytes(),
            }),
            ProcessedMessageContent::StagedCommitMessage(staged) => {
                let removed_self = staged.self_removed();
                group
                    .merge_staged_commit(&self.provider, *staged)
                    .map_err(other)?;
                Ok(Incoming::Commit {
                    epoch: group.epoch().as_u64(),
                    removed_self,
                })
            }
            ProcessedMessageContent::ProposalMessage(p) => {
                group
                    .store_pending_proposal(self.provider.storage(), *p)
                    .map_err(other)?;
                Ok(Incoming::Proposal)
            }
            ProcessedMessageContent::ExternalJoinProposalMessage(_) => Ok(Incoming::Proposal),
            // The server fanned our own message back to us; nothing to do (commits are merged
            // locally when they are created).
            ProcessedMessageContent::OwnPendingCommit
            | ProcessedMessageContent::OwnPrivateMessage => Ok(Incoming::OwnEcho),
        }
    }

    /// Remove the member whose credential identity is `identity`. Returns the commit for the
    /// remaining members (and the removed one, so it learns it was removed). Merged locally.
    pub fn remove_member(&mut self, group_id: &[u8], identity: &[u8]) -> Result<Vec<u8>> {
        let group = self
            .groups
            .get_mut(group_id)
            .ok_or(MlsError::UnknownGroup)?;
        let index = group
            .members()
            .find(|m| m.credential.serialized_content() == identity)
            .map(|m| m.index)
            .ok_or(MlsError::UnknownMember)?;
        let (commit, _welcome, _group_info) = group
            .remove_members(&self.provider, &self.signer, &[index])
            .map_err(other)?;
        group.merge_pending_commit(&self.provider).map_err(other)?;
        commit.to_bytes().map_err(other)
    }

    /// Current epoch of the group.
    pub fn epoch(&self, group_id: &[u8]) -> Result<u64> {
        let group = self.groups.get(group_id).ok_or(MlsError::UnknownGroup)?;
        Ok(group.epoch().as_u64())
    }

    /// Epoch authenticator: equal on all members iff they share the same epoch secrets.
    pub fn epoch_authenticator(&self, group_id: &[u8]) -> Result<Vec<u8>> {
        let group = self.groups.get(group_id).ok_or(MlsError::UnknownGroup)?;
        Ok(group.epoch_authenticator().as_slice().to_vec())
    }

    /// Identities of all current members, in leaf order.
    pub fn members(&self, group_id: &[u8]) -> Result<Vec<Vec<u8>>> {
        let group = self.groups.get(group_id).ok_or(MlsError::UnknownGroup)?;
        Ok(group
            .members()
            .map(|m| m.credential.serialized_content().to_vec())
            .collect())
    }

    /// Whether this client is still a member of the group.
    pub fn is_active(&self, group_id: &[u8]) -> Result<bool> {
        let group = self.groups.get(group_id).ok_or(MlsError::UnknownGroup)?;
        Ok(group.is_active())
    }
}

fn map_process_error<E: std::fmt::Display>(e: ProcessMessageError<E>) -> MlsError {
    match e {
        ProcessMessageError::GroupStateError(MlsGroupStateError::UseAfterEviction) => {
            MlsError::RemovedFromGroup
        }
        ProcessMessageError::ValidationError(ValidationError::WrongEpoch) => MlsError::WrongEpoch,
        ProcessMessageError::ValidationError(
            v @ (ValidationError::UnableToDecrypt(_) | ValidationError::InvalidSignature),
        ) => MlsError::DecryptionFailed(v.to_string()),
        other_err => MlsError::Other(other_err.to_string()),
    }
}
