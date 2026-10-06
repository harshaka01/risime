//! Persistent MLS state behind a tiny key/value interface (decisions 012 §6 and 033).
//!
//! The app implements [`KvStore`] on its own database (Room/SQLite on Android, a sealed table).
//! [`KvStorage`] adapts it to OpenMLS' `StorageProvider`: every OpenMLS entity becomes one opaque
//! value under a namespaced key, so the app never needs to understand the contents.
//!
//! Transactions: `begin` / `commit` / `rollback` must be **nestable** (SQLite `SAVEPOINT`,
//! `RELEASE`, `ROLLBACK TO` + `RELEASE`). The MLS core wraps each public call in one of them and
//! never assumes it owns the outermost transaction: the app may open an outer transaction around
//! "decrypt → insert plaintext → move cursor" and roll the whole thing back.
//!
//! The entity encoding (label ‖ JSON key ‖ version, JSON values) follows
//! `openmls_memory_storage` (MIT, the OpenMLS authors), re-implemented over [`KvStore`].

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use openmls_rust_crypto::RustCrypto;
use openmls_traits::OpenMlsProvider;
use openmls_traits::storage::{CURRENT_VERSION, Entity, StorageProvider, traits};
use serde::Serialize;

/// Error raised by a [`KvStore`] implementation (I/O, constraint, closed database ...).
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("storage: {0}")]
pub struct KvError(pub String);

/// Storage the app provides. Keys and values are opaque bytes; keys are at most a few hundred
/// bytes, values up to a few hundred KiB (ratchet trees of large groups).
pub trait KvStore: Send + Sync {
    fn get(&self, key: &[u8]) -> Result<Option<Vec<u8>>, KvError>;
    fn put(&self, key: &[u8], value: &[u8]) -> Result<(), KvError>;
    fn delete(&self, key: &[u8]) -> Result<(), KvError>;
    /// Open a nested transaction (SQLite: `SAVEPOINT`).
    fn begin(&self) -> Result<(), KvError>;
    /// Keep the innermost transaction's writes (SQLite: `RELEASE`). They become durable only when
    /// every enclosing transaction commits too.
    fn commit(&self) -> Result<(), KvError>;
    /// Undo the innermost transaction's writes (SQLite: `ROLLBACK TO` + `RELEASE`).
    fn rollback(&self) -> Result<(), KvError>;
}

/// In-memory [`KvStore`] with nestable transactions (a stack of snapshots). For tests, the
/// fixture generator and `self_test`.
#[derive(Default)]
pub struct MemoryKvStore {
    inner: Mutex<MemInner>,
}

#[derive(Default)]
struct MemInner {
    map: HashMap<Vec<u8>, Vec<u8>>,
    savepoints: Vec<HashMap<Vec<u8>, Vec<u8>>>,
}

impl MemoryKvStore {
    pub fn new() -> Self {
        Self::default()
    }

    /// Number of stored entries (tests).
    pub fn len(&self) -> usize {
        self.lock().map.len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Total bytes of keys and values (tests: state size).
    pub fn size_bytes(&self) -> usize {
        self.lock().map.iter().map(|(k, v)| k.len() + v.len()).sum()
    }

    /// Open transaction depth (tests: must be back to 0 after every call).
    pub fn depth(&self) -> usize {
        self.lock().savepoints.len()
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, MemInner> {
        self.inner.lock().unwrap_or_else(|p| p.into_inner())
    }
}

impl KvStore for MemoryKvStore {
    fn get(&self, key: &[u8]) -> Result<Option<Vec<u8>>, KvError> {
        Ok(self.lock().map.get(key).cloned())
    }
    fn put(&self, key: &[u8], value: &[u8]) -> Result<(), KvError> {
        self.lock().map.insert(key.to_vec(), value.to_vec());
        Ok(())
    }
    fn delete(&self, key: &[u8]) -> Result<(), KvError> {
        self.lock().map.remove(key);
        Ok(())
    }
    fn begin(&self) -> Result<(), KvError> {
        let mut g = self.lock();
        let snapshot = g.map.clone();
        g.savepoints.push(snapshot);
        Ok(())
    }
    fn commit(&self) -> Result<(), KvError> {
        self.lock()
            .savepoints
            .pop()
            .map(|_| ())
            .ok_or_else(|| KvError("commit without begin".into()))
    }
    fn rollback(&self) -> Result<(), KvError> {
        let mut g = self.lock();
        let snapshot = g
            .savepoints
            .pop()
            .ok_or_else(|| KvError("rollback without begin".into()))?;
        g.map = snapshot;
        Ok(())
    }
}

/// Errors surfaced to OpenMLS from [`KvStorage`].
#[derive(Debug, thiserror::Error)]
pub enum KvStorageError {
    #[error(transparent)]
    Kv(#[from] KvError),
    #[error("serialization: {0}")]
    Serialization(String),
}

impl From<serde_json::Error> for KvStorageError {
    fn from(e: serde_json::Error) -> Self {
        Self::Serialization(e.to_string())
    }
}

/// OpenMLS `StorageProvider` over a [`KvStore`].
pub struct KvStorage {
    kv: Arc<dyn KvStore>,
}

/// Every OpenMLS key starts with this prefix so the app's table can hold other things too.
const PREFIX: &[u8] = b"mls/";

const KEY_PACKAGE_LABEL: &[u8] = b"KeyPackage";
const PSK_LABEL: &[u8] = b"Psk";
const ENCRYPTION_KEY_PAIR_LABEL: &[u8] = b"EncryptionKeyPair";
const SIGNATURE_KEY_PAIR_LABEL: &[u8] = b"SignatureKeyPair";
const EPOCH_KEY_PAIRS_LABEL: &[u8] = b"EpochKeyPairs";
const TREE_LABEL: &[u8] = b"Tree";
const GROUP_CONTEXT_LABEL: &[u8] = b"GroupContext";
const INTERIM_TRANSCRIPT_HASH_LABEL: &[u8] = b"InterimTranscriptHash";
const CONFIRMATION_TAG_LABEL: &[u8] = b"ConfirmationTag";
const JOIN_CONFIG_LABEL: &[u8] = b"MlsGroupJoinConfig";
const OWN_LEAF_NODES_LABEL: &[u8] = b"OwnLeafNodes";
const GROUP_STATE_LABEL: &[u8] = b"GroupState";
const QUEUED_PROPOSAL_LABEL: &[u8] = b"QueuedProposal";
const PROPOSAL_QUEUE_REFS_LABEL: &[u8] = b"ProposalQueueRefs";
const OWN_LEAF_NODE_INDEX_LABEL: &[u8] = b"OwnLeafNodeIndex";
const EPOCH_SECRETS_LABEL: &[u8] = b"EpochSecrets";
const RESUMPTION_PSK_STORE_LABEL: &[u8] = b"ResumptionPsk";
const MESSAGE_SECRETS_LABEL: &[u8] = b"MessageSecrets";

type R<T> = Result<T, KvStorageError>;

impl KvStorage {
    pub fn new(kv: Arc<dyn KvStore>) -> Self {
        Self { kv }
    }

    fn storage_key(label: &[u8], key: &[u8]) -> Vec<u8> {
        let mut k = Vec::with_capacity(PREFIX.len() + label.len() + 1 + key.len() + 2);
        k.extend_from_slice(PREFIX);
        k.extend_from_slice(label);
        k.push(b'/');
        k.extend_from_slice(key);
        k.extend_from_slice(&CURRENT_VERSION.to_be_bytes());
        k
    }

    fn json_key<K: Serialize + ?Sized>(key: &K) -> R<Vec<u8>> {
        Ok(serde_json::to_vec(key)?)
    }

    fn write_raw(&self, label: &[u8], key: &[u8], value: &[u8]) -> R<()> {
        Ok(self.kv.put(&Self::storage_key(label, key), value)?)
    }

    fn write<K: Serialize + ?Sized, V: Serialize + ?Sized>(
        &self,
        label: &[u8],
        key: &K,
        value: &V,
    ) -> R<()> {
        self.write_raw(label, &Self::json_key(key)?, &serde_json::to_vec(value)?)
    }

    fn read_raw(&self, label: &[u8], key: &[u8]) -> R<Option<Vec<u8>>> {
        Ok(self.kv.get(&Self::storage_key(label, key))?)
    }

    fn read<K: Serialize + ?Sized, V: Entity<CURRENT_VERSION>>(
        &self,
        label: &[u8],
        key: &K,
    ) -> R<Option<V>> {
        match self.read_raw(label, &Self::json_key(key)?)? {
            Some(bytes) => Ok(Some(serde_json::from_slice(&bytes)?)),
            None => Ok(None),
        }
    }

    fn delete<K: Serialize + ?Sized>(&self, label: &[u8], key: &K) -> R<()> {
        Ok(self
            .kv
            .delete(&Self::storage_key(label, &Self::json_key(key)?))?)
    }

    /// Lists are stored as one JSON array of JSON-encoded items.
    fn read_list_raw(&self, label: &[u8], key: &[u8]) -> R<Vec<Vec<u8>>> {
        match self.read_raw(label, key)? {
            Some(bytes) => Ok(serde_json::from_slice(&bytes)?),
            None => Ok(vec![]),
        }
    }

    fn read_list<K: Serialize + ?Sized, V: Entity<CURRENT_VERSION>>(
        &self,
        label: &[u8],
        key: &K,
    ) -> R<Vec<V>> {
        self.read_list_raw(label, &Self::json_key(key)?)?
            .iter()
            .map(|item| serde_json::from_slice(item).map_err(Into::into))
            .collect()
    }

    fn append<K: Serialize + ?Sized, V: Serialize + ?Sized>(
        &self,
        label: &[u8],
        key: &K,
        value: &V,
    ) -> R<()> {
        let key = Self::json_key(key)?;
        let mut list = self.read_list_raw(label, &key)?;
        list.push(serde_json::to_vec(value)?);
        self.write_raw(label, &key, &serde_json::to_vec(&list)?)
    }

    fn remove_item<K: Serialize + ?Sized, V: Serialize + ?Sized>(
        &self,
        label: &[u8],
        key: &K,
        value: &V,
    ) -> R<()> {
        let key = Self::json_key(key)?;
        let item = serde_json::to_vec(value)?;
        let mut list = self.read_list_raw(label, &key)?;
        if let Some(pos) = list.iter().position(|i| *i == item) {
            list.remove(pos);
        }
        self.write_raw(label, &key, &serde_json::to_vec(&list)?)
    }
}

fn epoch_key_pairs_id(
    group_id: &impl traits::GroupId<CURRENT_VERSION>,
    epoch: &impl traits::EpochKey<CURRENT_VERSION>,
    leaf_index: u32,
) -> R<Vec<u8>> {
    let mut key = serde_json::to_vec(group_id)?;
    key.extend_from_slice(&serde_json::to_vec(epoch)?);
    key.extend_from_slice(&serde_json::to_vec(&leaf_index)?);
    Ok(key)
}

impl StorageProvider<CURRENT_VERSION> for KvStorage {
    type Error = KvStorageError;

    fn write_mls_join_config<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        MlsGroupJoinConfig: traits::MlsGroupJoinConfig<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        config: &MlsGroupJoinConfig,
    ) -> R<()> {
        self.write(JOIN_CONFIG_LABEL, group_id, config)
    }

    fn append_own_leaf_node<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        LeafNode: traits::LeafNode<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        leaf_node: &LeafNode,
    ) -> R<()> {
        self.append(OWN_LEAF_NODES_LABEL, group_id, leaf_node)
    }

    fn queue_proposal<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        ProposalRef: traits::ProposalRef<CURRENT_VERSION>,
        QueuedProposal: traits::QueuedProposal<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        proposal_ref: &ProposalRef,
        proposal: &QueuedProposal,
    ) -> R<()> {
        self.write(QUEUED_PROPOSAL_LABEL, &(group_id, proposal_ref), proposal)?;
        self.append(PROPOSAL_QUEUE_REFS_LABEL, group_id, proposal_ref)
    }

    fn write_tree<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        TreeSync: traits::TreeSync<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        tree: &TreeSync,
    ) -> R<()> {
        self.write(TREE_LABEL, group_id, tree)
    }

    fn write_interim_transcript_hash<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        InterimTranscriptHash: traits::InterimTranscriptHash<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        interim_transcript_hash: &InterimTranscriptHash,
    ) -> R<()> {
        self.write(
            INTERIM_TRANSCRIPT_HASH_LABEL,
            group_id,
            interim_transcript_hash,
        )
    }

    fn write_context<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        GroupContext: traits::GroupContext<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        group_context: &GroupContext,
    ) -> R<()> {
        self.write(GROUP_CONTEXT_LABEL, group_id, group_context)
    }

    fn write_confirmation_tag<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        ConfirmationTag: traits::ConfirmationTag<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        confirmation_tag: &ConfirmationTag,
    ) -> R<()> {
        self.write(CONFIRMATION_TAG_LABEL, group_id, confirmation_tag)
    }

    fn write_group_state<
        GroupState: traits::GroupState<CURRENT_VERSION>,
        GroupId: traits::GroupId<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        group_state: &GroupState,
    ) -> R<()> {
        self.write(GROUP_STATE_LABEL, group_id, group_state)
    }

    fn write_message_secrets<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        MessageSecrets: traits::MessageSecrets<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        message_secrets: &MessageSecrets,
    ) -> R<()> {
        self.write(MESSAGE_SECRETS_LABEL, group_id, message_secrets)
    }

    fn write_resumption_psk_store<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        ResumptionPskStore: traits::ResumptionPskStore<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        resumption_psk_store: &ResumptionPskStore,
    ) -> R<()> {
        self.write(RESUMPTION_PSK_STORE_LABEL, group_id, resumption_psk_store)
    }

    fn write_own_leaf_index<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        LeafNodeIndex: traits::LeafNodeIndex<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        own_leaf_index: &LeafNodeIndex,
    ) -> R<()> {
        self.write(OWN_LEAF_NODE_INDEX_LABEL, group_id, own_leaf_index)
    }

    fn write_group_epoch_secrets<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        GroupEpochSecrets: traits::GroupEpochSecrets<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        group_epoch_secrets: &GroupEpochSecrets,
    ) -> R<()> {
        self.write(EPOCH_SECRETS_LABEL, group_id, group_epoch_secrets)
    }

    fn write_signature_key_pair<
        SignaturePublicKey: traits::SignaturePublicKey<CURRENT_VERSION>,
        SignatureKeyPair: traits::SignatureKeyPair<CURRENT_VERSION>,
    >(
        &self,
        public_key: &SignaturePublicKey,
        signature_key_pair: &SignatureKeyPair,
    ) -> R<()> {
        self.write(SIGNATURE_KEY_PAIR_LABEL, public_key, signature_key_pair)
    }

    fn write_encryption_key_pair<
        EncryptionKey: traits::EncryptionKey<CURRENT_VERSION>,
        HpkeKeyPair: traits::HpkeKeyPair<CURRENT_VERSION>,
    >(
        &self,
        public_key: &EncryptionKey,
        key_pair: &HpkeKeyPair,
    ) -> R<()> {
        self.write(ENCRYPTION_KEY_PAIR_LABEL, public_key, key_pair)
    }

    fn write_encryption_epoch_key_pairs<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        EpochKey: traits::EpochKey<CURRENT_VERSION>,
        HpkeKeyPair: traits::HpkeKeyPair<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        epoch: &EpochKey,
        leaf_index: u32,
        key_pairs: &[HpkeKeyPair],
    ) -> R<()> {
        let key = epoch_key_pairs_id(group_id, epoch, leaf_index)?;
        self.write_raw(EPOCH_KEY_PAIRS_LABEL, &key, &serde_json::to_vec(key_pairs)?)
    }

    fn write_key_package<
        HashReference: traits::HashReference<CURRENT_VERSION>,
        KeyPackage: traits::KeyPackage<CURRENT_VERSION>,
    >(
        &self,
        hash_ref: &HashReference,
        key_package: &KeyPackage,
    ) -> R<()> {
        self.write(KEY_PACKAGE_LABEL, hash_ref, key_package)
    }

    fn write_psk<
        PskId: traits::PskId<CURRENT_VERSION>,
        PskBundle: traits::PskBundle<CURRENT_VERSION>,
    >(
        &self,
        psk_id: &PskId,
        psk: &PskBundle,
    ) -> R<()> {
        self.write(PSK_LABEL, psk_id, psk)
    }

    fn mls_group_join_config<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        MlsGroupJoinConfig: traits::MlsGroupJoinConfig<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> R<Option<MlsGroupJoinConfig>> {
        self.read(JOIN_CONFIG_LABEL, group_id)
    }

    fn own_leaf_nodes<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        LeafNode: traits::LeafNode<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> R<Vec<LeafNode>> {
        self.read_list(OWN_LEAF_NODES_LABEL, group_id)
    }

    fn queued_proposal_refs<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        ProposalRef: traits::ProposalRef<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> R<Vec<ProposalRef>> {
        self.read_list(PROPOSAL_QUEUE_REFS_LABEL, group_id)
    }

    fn queued_proposals<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        ProposalRef: traits::ProposalRef<CURRENT_VERSION>,
        QueuedProposal: traits::QueuedProposal<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> R<Vec<(ProposalRef, QueuedProposal)>> {
        let refs: Vec<ProposalRef> = self.read_list(PROPOSAL_QUEUE_REFS_LABEL, group_id)?;
        refs.into_iter()
            .map(|r| {
                let p = self
                    .read(QUEUED_PROPOSAL_LABEL, &(group_id, &r))?
                    .ok_or_else(|| {
                        KvStorageError::Serialization("queued proposal missing".into())
                    })?;
                Ok((r, p))
            })
            .collect()
    }

    fn tree<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        TreeSync: traits::TreeSync<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> R<Option<TreeSync>> {
        self.read(TREE_LABEL, group_id)
    }

    fn group_context<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        GroupContext: traits::GroupContext<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> R<Option<GroupContext>> {
        self.read(GROUP_CONTEXT_LABEL, group_id)
    }

    fn interim_transcript_hash<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        InterimTranscriptHash: traits::InterimTranscriptHash<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> R<Option<InterimTranscriptHash>> {
        self.read(INTERIM_TRANSCRIPT_HASH_LABEL, group_id)
    }

    fn confirmation_tag<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        ConfirmationTag: traits::ConfirmationTag<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> R<Option<ConfirmationTag>> {
        self.read(CONFIRMATION_TAG_LABEL, group_id)
    }

    fn group_state<
        GroupState: traits::GroupState<CURRENT_VERSION>,
        GroupId: traits::GroupId<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> R<Option<GroupState>> {
        self.read(GROUP_STATE_LABEL, group_id)
    }

    fn message_secrets<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        MessageSecrets: traits::MessageSecrets<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> R<Option<MessageSecrets>> {
        self.read(MESSAGE_SECRETS_LABEL, group_id)
    }

    fn resumption_psk_store<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        ResumptionPskStore: traits::ResumptionPskStore<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> R<Option<ResumptionPskStore>> {
        self.read(RESUMPTION_PSK_STORE_LABEL, group_id)
    }

    fn own_leaf_index<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        LeafNodeIndex: traits::LeafNodeIndex<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> R<Option<LeafNodeIndex>> {
        self.read(OWN_LEAF_NODE_INDEX_LABEL, group_id)
    }

    fn group_epoch_secrets<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        GroupEpochSecrets: traits::GroupEpochSecrets<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> R<Option<GroupEpochSecrets>> {
        self.read(EPOCH_SECRETS_LABEL, group_id)
    }

    fn signature_key_pair<
        SignaturePublicKey: traits::SignaturePublicKey<CURRENT_VERSION>,
        SignatureKeyPair: traits::SignatureKeyPair<CURRENT_VERSION>,
    >(
        &self,
        public_key: &SignaturePublicKey,
    ) -> R<Option<SignatureKeyPair>> {
        self.read(SIGNATURE_KEY_PAIR_LABEL, public_key)
    }

    fn encryption_key_pair<
        HpkeKeyPair: traits::HpkeKeyPair<CURRENT_VERSION>,
        EncryptionKey: traits::EncryptionKey<CURRENT_VERSION>,
    >(
        &self,
        public_key: &EncryptionKey,
    ) -> R<Option<HpkeKeyPair>> {
        self.read(ENCRYPTION_KEY_PAIR_LABEL, public_key)
    }

    fn encryption_epoch_key_pairs<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        EpochKey: traits::EpochKey<CURRENT_VERSION>,
        HpkeKeyPair: traits::HpkeKeyPair<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        epoch: &EpochKey,
        leaf_index: u32,
    ) -> R<Vec<HpkeKeyPair>> {
        let key = epoch_key_pairs_id(group_id, epoch, leaf_index)?;
        match self.read_raw(EPOCH_KEY_PAIRS_LABEL, &key)? {
            Some(bytes) => Ok(serde_json::from_slice(&bytes)?),
            None => Ok(vec![]),
        }
    }

    fn key_package<
        KeyPackageRef: traits::HashReference<CURRENT_VERSION>,
        KeyPackage: traits::KeyPackage<CURRENT_VERSION>,
    >(
        &self,
        hash_ref: &KeyPackageRef,
    ) -> R<Option<KeyPackage>> {
        self.read(KEY_PACKAGE_LABEL, hash_ref)
    }

    fn psk<PskBundle: traits::PskBundle<CURRENT_VERSION>, PskId: traits::PskId<CURRENT_VERSION>>(
        &self,
        psk_id: &PskId,
    ) -> R<Option<PskBundle>> {
        self.read(PSK_LABEL, psk_id)
    }

    fn remove_proposal<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        ProposalRef: traits::ProposalRef<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        proposal_ref: &ProposalRef,
    ) -> R<()> {
        self.remove_item(PROPOSAL_QUEUE_REFS_LABEL, group_id, proposal_ref)?;
        self.delete(QUEUED_PROPOSAL_LABEL, &(group_id, proposal_ref))
    }

    fn delete_own_leaf_nodes<GroupId: traits::GroupId<CURRENT_VERSION>>(
        &self,
        group_id: &GroupId,
    ) -> R<()> {
        self.delete(OWN_LEAF_NODES_LABEL, group_id)
    }

    fn delete_group_config<GroupId: traits::GroupId<CURRENT_VERSION>>(
        &self,
        group_id: &GroupId,
    ) -> R<()> {
        self.delete(JOIN_CONFIG_LABEL, group_id)
    }

    fn delete_tree<GroupId: traits::GroupId<CURRENT_VERSION>>(&self, group_id: &GroupId) -> R<()> {
        self.delete(TREE_LABEL, group_id)
    }

    fn delete_confirmation_tag<GroupId: traits::GroupId<CURRENT_VERSION>>(
        &self,
        group_id: &GroupId,
    ) -> R<()> {
        self.delete(CONFIRMATION_TAG_LABEL, group_id)
    }

    fn delete_group_state<GroupId: traits::GroupId<CURRENT_VERSION>>(
        &self,
        group_id: &GroupId,
    ) -> R<()> {
        self.delete(GROUP_STATE_LABEL, group_id)
    }

    fn delete_context<GroupId: traits::GroupId<CURRENT_VERSION>>(
        &self,
        group_id: &GroupId,
    ) -> R<()> {
        self.delete(GROUP_CONTEXT_LABEL, group_id)
    }

    fn delete_interim_transcript_hash<GroupId: traits::GroupId<CURRENT_VERSION>>(
        &self,
        group_id: &GroupId,
    ) -> R<()> {
        self.delete(INTERIM_TRANSCRIPT_HASH_LABEL, group_id)
    }

    fn delete_message_secrets<GroupId: traits::GroupId<CURRENT_VERSION>>(
        &self,
        group_id: &GroupId,
    ) -> R<()> {
        self.delete(MESSAGE_SECRETS_LABEL, group_id)
    }

    fn delete_all_resumption_psk_secrets<GroupId: traits::GroupId<CURRENT_VERSION>>(
        &self,
        group_id: &GroupId,
    ) -> R<()> {
        self.delete(RESUMPTION_PSK_STORE_LABEL, group_id)
    }

    fn delete_own_leaf_index<GroupId: traits::GroupId<CURRENT_VERSION>>(
        &self,
        group_id: &GroupId,
    ) -> R<()> {
        self.delete(OWN_LEAF_NODE_INDEX_LABEL, group_id)
    }

    fn delete_group_epoch_secrets<GroupId: traits::GroupId<CURRENT_VERSION>>(
        &self,
        group_id: &GroupId,
    ) -> R<()> {
        self.delete(EPOCH_SECRETS_LABEL, group_id)
    }

    fn clear_proposal_queue<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        ProposalRef: traits::ProposalRef<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
    ) -> R<()> {
        let refs: Vec<ProposalRef> = self.read_list(PROPOSAL_QUEUE_REFS_LABEL, group_id)?;
        for r in refs {
            self.delete(QUEUED_PROPOSAL_LABEL, &(group_id, &r))?;
        }
        self.delete(PROPOSAL_QUEUE_REFS_LABEL, group_id)
    }

    fn delete_signature_key_pair<
        SignaturePublicKey: traits::SignaturePublicKey<CURRENT_VERSION>,
    >(
        &self,
        public_key: &SignaturePublicKey,
    ) -> R<()> {
        self.delete(SIGNATURE_KEY_PAIR_LABEL, public_key)
    }

    fn delete_encryption_key_pair<EncryptionKey: traits::EncryptionKey<CURRENT_VERSION>>(
        &self,
        public_key: &EncryptionKey,
    ) -> R<()> {
        self.delete(ENCRYPTION_KEY_PAIR_LABEL, public_key)
    }

    fn delete_encryption_epoch_key_pairs<
        GroupId: traits::GroupId<CURRENT_VERSION>,
        EpochKey: traits::EpochKey<CURRENT_VERSION>,
    >(
        &self,
        group_id: &GroupId,
        epoch: &EpochKey,
        leaf_index: u32,
    ) -> R<()> {
        let key = epoch_key_pairs_id(group_id, epoch, leaf_index)?;
        Ok(self
            .kv
            .delete(&Self::storage_key(EPOCH_KEY_PAIRS_LABEL, &key))?)
    }

    fn delete_key_package<KeyPackageRef: traits::HashReference<CURRENT_VERSION>>(
        &self,
        hash_ref: &KeyPackageRef,
    ) -> R<()> {
        self.delete(KEY_PACKAGE_LABEL, hash_ref)
    }

    fn delete_psk<PskKey: traits::PskId<CURRENT_VERSION>>(&self, psk_id: &PskKey) -> R<()> {
        self.delete(PSK_LABEL, psk_id)
    }
}

/// The OpenMLS provider: RustCrypto for crypto and randomness, [`KvStorage`] for state.
pub struct Provider {
    crypto: RustCrypto,
    storage: KvStorage,
}

impl Provider {
    pub fn new(kv: Arc<dyn KvStore>) -> Self {
        Self {
            crypto: RustCrypto::default(),
            storage: KvStorage::new(kv),
        }
    }
}

impl OpenMlsProvider for Provider {
    type CryptoProvider = RustCrypto;
    type RandProvider = RustCrypto;
    type StorageProvider = KvStorage;

    fn storage(&self) -> &Self::StorageProvider {
        &self.storage
    }
    fn crypto(&self) -> &Self::CryptoProvider {
        &self.crypto
    }
    fn rand(&self) -> &Self::RandProvider {
        &self.crypto
    }
}
