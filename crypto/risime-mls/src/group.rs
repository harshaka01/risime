//! Groups with MLS (contract v1.9 §12, decision 041).
//!
//! A `grp:` group differs from a DM group in four ways:
//! - its handshakes (commits) use **PrivateMessage** framing (decided by the group id prefix
//!   [`GROUP_ID_PREFIX`], on creation and on join);
//! - its GroupContext carries [`GroupMeta`] (extension 0xFA01), named in `required_capabilities`,
//!   so every leaf must be groups-capable;
//! - every staged commit, ours or a peer's, passes the admin policy and the v1.24 tab rules
//!   ([`crate::policy`]), as does the tree of a Welcome we join;
//! - commits and Welcomes are sized for the blob-reference path ([`GroupCommit`]).
//!
//! Own commits stay pending until the server's verdict ([`Client::commit_accepted`] /
//! [`Client::commit_rejected`]), exactly as for DMs.

use openmls::prelude::tls_codec::Deserialize as _;
use openmls::prelude::*;
use sha2::{Digest, Sha256};

use crate::meta::{GROUP_META_EXTENSION, GroupMeta};
use crate::policy::{CommitSummary, MetaChange, Tab, TabContext, check_tab_policy};
use crate::{Client, DeviceId, Incoming, MemberInfo, MlsError, Result, other, storage};

/// Conversation ids of groups start with this; the MLS group id is `"grp:<uuid>#<generation>"`.
pub const GROUP_ID_PREFIX: &[u8] = b"grp:";

/// Commits and Welcomes larger than this go by blob reference (§12.4).
pub const MAX_INLINE_BYTES: usize = 64 * 1024;
/// The largest commit the server accepts (by reference).
pub const MAX_COMMIT_BYTES: usize = 1024 * 1024;
/// The largest Welcome the server accepts (by reference).
pub const MAX_WELCOME_BYTES: usize = 2 * 1024 * 1024;
/// At most this many member users per group, the creator included (§12.0).
pub const MAX_GROUP_USERS: usize = 256;
/// At most this many devices (leaves) per group (§12.0).
pub const MAX_GROUP_LEAVES: usize = 768;

pub(crate) fn is_group_id(group_id: &[u8]) -> bool {
    group_id.starts_with(GROUP_ID_PREFIX)
}

/// `grp:` handshakes are PrivateMessage (§12.0); DMs keep PublicMessage (§10.0).
pub(crate) fn wire_policy(group_id: &[u8]) -> WireFormatPolicy {
    if is_group_id(group_id) {
        PURE_CIPHERTEXT_WIRE_FORMAT_POLICY
    } else {
        PURE_PLAINTEXT_WIRE_FORMAT_POLICY
    }
}

pub(crate) fn meta_extension_type() -> ExtensionType {
    ExtensionType::Unknown(GROUP_META_EXTENSION)
}

/// The `group_meta` in a GroupContext, if any.
pub(crate) fn meta_of(ext: &Extensions<GroupContext>) -> Result<Option<GroupMeta>> {
    ext.unknown(GROUP_META_EXTENSION)
        .map(|u| GroupMeta::from_bytes(&u.0))
        .transpose()
}

fn requires_meta(ext: &Extensions<GroupContext>) -> bool {
    ext.required_capabilities()
        .is_some_and(|r| r.extension_types().contains(&meta_extension_type()))
}

fn meta_extension(meta: &GroupMeta) -> Result<Extension> {
    Ok(Extension::Unknown(
        GROUP_META_EXTENSION,
        UnknownExtension(meta.to_bytes()?),
    ))
}

/// The GroupContext extensions of a new group: `required_capabilities` (0xFA01) + `group_meta`.
fn initial_extensions(meta: &GroupMeta) -> Result<Extensions<GroupContext>> {
    Extensions::from_vec(vec![
        Extension::RequiredCapabilities(RequiredCapabilitiesExtension::new(
            &[meta_extension_type()],
            &[],
            &[],
        )),
        meta_extension(meta)?,
    ])
    .map_err(other)
}

/// Whether a key package (TLS bytes) advertises the groups capability (0xFA01). It is validated
/// first (signature, lifetime), so a `true` is trustworthy. Usable without a [`Client`].
pub fn key_package_supports_groups(key_package: &[u8]) -> Result<bool> {
    let kp = KeyPackageIn::tls_deserialize_exact(key_package)
        .map_err(|e| MlsError::Malformed(e.to_string()))?
        .validate(
            &openmls_rust_crypto::RustCrypto::default(),
            ProtocolVersion::Mls10,
        )
        .map_err(|e| MlsError::InvalidKeyPackage(e.to_string()))?;
    Ok(leaf_supports_groups(kp.leaf_node()))
}

fn leaf_supports_groups(leaf: &LeafNode) -> bool {
    leaf.capabilities()
        .extensions()
        .contains(&meta_extension_type())
}

/// A group commit of ours, waiting for `POST /mls/groups/{id}/commit` (§12.4). Like
/// [`PendingCommit`], plus `meta_changed` and the sizes the caller needs to choose between an
/// inline value and a blob reference.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GroupCommit {
    /// A PrivateMessage commit (TLS bytes).
    pub commit: Vec<u8>,
    /// The Welcome, present exactly when `added` is not empty.
    pub welcome: Option<Vec<u8>>,
    /// The epoch the commit was built in (the request's `epoch`).
    pub epoch: u64,
    pub added: Vec<DeviceId>,
    pub removed: Vec<DeviceId>,
    /// The request's `meta_changed` (a rename or a role change).
    pub meta_changed: bool,
}

impl GroupCommit {
    pub fn commit_size(&self) -> usize {
        self.commit.len()
    }
    pub fn welcome_size(&self) -> usize {
        self.welcome.as_ref().map_or(0, Vec::len)
    }
    /// The commit is larger than [`MAX_INLINE_BYTES`]: upload it and send `commit_ref`.
    pub fn commit_needs_ref(&self) -> bool {
        self.commit_size() > MAX_INLINE_BYTES
    }
    /// The Welcome is larger than [`MAX_INLINE_BYTES`]: upload it and send `welcome_ref`.
    pub fn welcome_needs_ref(&self) -> bool {
        self.welcome_size() > MAX_INLINE_BYTES
    }
}

/// Result of [`Client::process_commits`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CatchUp {
    /// The group's epoch afterwards.
    pub epoch: u64,
    /// One [`Incoming::Commit`] per commit applied, in order. Our own accepted commit found in the
    /// log is merged and reported with `committer` = this device.
    pub applied: Vec<Incoming>,
    /// Commits below our epoch, already applied.
    pub skipped: u32,
    /// The batch removed this device; later commits were not looked at. Wipe the group.
    pub removed_self: bool,
}

/// Stored next to a pending commit so that a catch-up can recognise it after a restart.
#[derive(serde::Serialize, serde::Deserialize)]
struct PendingRecord {
    sha256: Vec<u8>,
    added: Vec<String>,
    removed: Vec<String>,
    meta_changed: bool,
}

fn pending_key(group_id: &[u8]) -> Vec<u8> {
    [b"risime/pending/v1/".as_slice(), group_id].concat()
}

fn ids(devices: &[DeviceId]) -> Vec<String> {
    devices.iter().map(DeviceId::identity).collect()
}

/// The policy input for a commit: devices by attested id, and the base epoch's leaf owners.
fn summary<'a>(
    committer_user: &'a str,
    added: &'a [DeviceId],
    removed: &'a [DeviceId],
    leaves: &'a [MemberInfo],
    meta: Option<MetaChange>,
) -> CommitSummary<'a> {
    let leaf = |d: &'a DeviceId| (d.user_id.as_str(), d.device_id.as_str());
    CommitSummary {
        committer_user,
        adds: added.iter().map(leaf).collect(),
        removes: removed.iter().map(leaf).collect(),
        leaf_users: leaves.iter().map(|m| m.user_id.as_str()).collect(),
        meta,
    }
}

/// The conversation id of a group id `"<conversation_id>#<generation>"`.
fn conversation_id(group_id: &[u8]) -> String {
    let s = String::from_utf8_lossy(group_id);
    s.split_once('#').map_or(&*s, |(c, _)| c).to_string()
}

/// The v1.24 context of a commit in a `grp:` group (§24.1).
fn tab_context<'a>(
    meta: &'a GroupMeta,
    leaves: &'a [MemberInfo],
    added_agents: &'a [String],
) -> TabContext<'a> {
    let mut agent_users: Vec<&str> = leaves
        .iter()
        .filter(|m| m.kind.is_agent())
        .map(|m| m.user_id.as_str())
        .chain(added_agents.iter().map(String::as_str))
        .collect();
    agent_users.sort_unstable();
    agent_users.dedup();
    TabContext {
        dm: false,
        tab: meta.tab(),
        agents: meta.agents(),
        agent_users,
        leaves: leaves
            .iter()
            .map(|m| (m.user_id.as_str(), m.device_id.as_str()))
            .collect(),
    }
}

/// The `group_meta` change from `current` to `new` (`own` = the group's conversation id).
fn meta_change(current: &GroupMeta, new: &GroupMeta, own: &str) -> MetaChange {
    MetaChange {
        admins: (!current.same_admins(new)).then(|| new.admins.clone()),
        name_changed: current.name != new.name || current.icon != new.icon,
        tab: (current.tab() != new.tab()).then(|| new.tab()),
        chat_id_changed: current.chat_id_or(own) != new.chat_id_or(own),
        agents: (current.agents() != new.agents()).then(|| new.agents().to_vec()),
    }
}

fn meta_unchanged(m: &MetaChange) -> bool {
    m.admins.is_none()
        && !m.name_changed
        && m.tab.is_none()
        && !m.chat_id_changed
        && m.agents.is_none()
}

fn parse_ids(ids: &[String]) -> Result<Vec<DeviceId>> {
    ids.iter()
        .map(|s| DeviceId::parse(s.as_bytes()).map_err(|e| MlsError::Storage(e.to_string())))
        .collect()
}

impl Client {
    // ---------------------------------------------------------------------------------------
    // Pending-commit record (catch-up after a restart)
    // ---------------------------------------------------------------------------------------

    pub(crate) fn record_pending(
        &self,
        group_id: &[u8],
        commit: &[u8],
        added: &[DeviceId],
        removed: &[DeviceId],
        meta_changed: bool,
    ) -> Result<()> {
        let rec = PendingRecord {
            sha256: Sha256::digest(commit).to_vec(),
            added: ids(added),
            removed: ids(removed),
            meta_changed,
        };
        self.kv
            .put(
                &pending_key(group_id),
                &serde_json::to_vec(&rec).map_err(storage)?,
            )
            .map_err(storage)
    }

    pub(crate) fn clear_pending_record(&self, group_id: &[u8]) -> Result<()> {
        self.kv.delete(&pending_key(group_id)).map_err(storage)
    }

    fn pending_record(&self, group_id: &[u8]) -> Result<Option<PendingRecord>> {
        self.kv
            .get(&pending_key(group_id))
            .map_err(storage)?
            .map(|b| serde_json::from_slice(&b).map_err(storage))
            .transpose()
    }

    // ---------------------------------------------------------------------------------------
    // Building commits
    // ---------------------------------------------------------------------------------------

    /// Build and stage one commit with the given proposals. Returns (commit, welcome) bytes.
    fn build_commit(
        &self,
        group: &mut MlsGroup,
        adds: Vec<KeyPackage>,
        removes: Vec<LeafNodeIndex>,
        gce: Option<Extensions<GroupContext>>,
        force_self_update: bool,
    ) -> Result<(Vec<u8>, Option<Vec<u8>>)> {
        let mut builder = group
            .commit_builder()
            .force_self_update(force_self_update)
            .propose_adds(adds)
            .propose_removals(removes);
        if let Some(ext) = gce {
            builder = builder
                .propose_group_context_extensions(ext)
                .map_err(other)?;
        }
        let bundle = builder
            .load_psks(self.provider.storage())
            .map_err(other)?
            .build(
                self.provider.rand(),
                self.provider.crypto(),
                &self.signer,
                |_| true,
            )
            .map_err(other)?
            .stage_commit(&self.provider)
            .map_err(other)?;
        let (commit, welcome, _group_info) = bundle.into_messages();
        Ok((
            commit.to_bytes().map_err(other)?,
            welcome.map(|w| w.to_bytes()).transpose().map_err(other)?,
        ))
    }

    /// Size checks, the pending record, and the result.
    #[allow(clippy::too_many_arguments)]
    fn finish_group_commit(
        &self,
        group_id: &[u8],
        epoch: u64,
        commit: Vec<u8>,
        welcome: Option<Vec<u8>>,
        added: Vec<DeviceId>,
        removed: Vec<DeviceId>,
        meta_changed: bool,
    ) -> Result<GroupCommit> {
        if commit.len() > MAX_COMMIT_BYTES {
            return Err(MlsError::PolicyViolation(format!(
                "commit of {} bytes exceeds {MAX_COMMIT_BYTES}",
                commit.len()
            )));
        }
        if let Some(w) = &welcome
            && w.len() > MAX_WELCOME_BYTES
        {
            return Err(MlsError::PolicyViolation(format!(
                "welcome of {} bytes exceeds {MAX_WELCOME_BYTES}",
                w.len()
            )));
        }
        self.record_pending(group_id, &commit, &added, &removed, meta_changed)?;
        Ok(GroupCommit {
            commit,
            welcome,
            epoch,
            added,
            removed,
            meta_changed,
        })
    }

    fn require_group_id(group_id: &[u8]) -> Result<()> {
        if is_group_id(group_id) {
            Ok(())
        } else {
            Err(MlsError::Malformed("not a grp: group id".into()))
        }
    }

    fn check_caps(members: &[MemberInfo], added: &[DeviceId], removed: &[DeviceId]) -> Result<()> {
        let mut leaves: Vec<DeviceId> = members
            .iter()
            .map(MemberInfo::device)
            .filter(|d| !removed.contains(d))
            .collect();
        leaves.extend(added.iter().cloned());
        if leaves.len() > MAX_GROUP_LEAVES {
            return Err(MlsError::PolicyViolation(format!(
                "more than {MAX_GROUP_LEAVES} devices"
            )));
        }
        let mut users: Vec<&str> = leaves.iter().map(|d| d.user_id.as_str()).collect();
        users.sort_unstable();
        users.dedup();
        if users.len() > MAX_GROUP_USERS {
            return Err(MlsError::PolicyViolation(format!(
                "more than {MAX_GROUP_USERS} users"
            )));
        }
        Ok(())
    }

    /// Run the admin policy and the tab rules for a commit of ours before building it.
    /// `added_agents`: the users of added leaves attested as agents.
    fn check_own_policy(
        &self,
        group: &MlsGroup,
        added: &[DeviceId],
        added_agents: &[String],
        removed: &[DeviceId],
        meta: Option<MetaChange>,
    ) -> Result<()> {
        let current = meta_of(group.extensions())?
            .ok_or_else(|| MlsError::PolicyViolation("group has no group_meta".into()))?;
        let leaves = Self::member_infos(group)?;
        let summary = summary(&self.device.user_id, added, removed, &leaves, meta);
        let ctx = tab_context(&current, &leaves, added_agents);
        check_tab_policy(&current.admins, &ctx, &summary).map_err(MlsError::PolicyViolation)
    }

    fn agent_users_of(kps: &[(KeyPackage, DeviceId)]) -> Vec<String> {
        kps.iter()
            .filter(|(k, _)| Self::leaf_kind(k.leaf_node()).is_agent())
            .map(|(_, d)| d.user_id.clone())
            .collect()
    }

    /// The admin policy and the tab rules (§12.4, §24.1) for a peer's staged commit in a `grp:`
    /// group, before it is merged. `added_agents`: the users of added leaves attested as agents.
    /// Returns whether the commit changes `group_meta`.
    pub(crate) fn check_staged_policy(
        &self,
        group: &MlsGroup,
        staged: &StagedCommit,
        committer: &DeviceId,
        added: &[DeviceId],
        added_agents: &[String],
        removed: &[DeviceId],
    ) -> Result<bool> {
        let mut gce = false;
        for q in staged.queued_proposals() {
            match q.proposal().proposal_type() {
                ProposalType::Add | ProposalType::Remove | ProposalType::Update => {}
                ProposalType::GroupContextExtensions => gce = true,
                t => {
                    return Err(MlsError::PolicyViolation(format!(
                        "proposal type {t:?} is not used in groups"
                    )));
                }
            }
        }
        let current = meta_of(group.extensions())?
            .ok_or_else(|| MlsError::PolicyViolation("group has no group_meta".into()))?;
        let meta = if gce {
            let new_ext = staged.group_context().extensions();
            if !requires_meta(new_ext) {
                return Err(MlsError::PolicyViolation(
                    "commit drops the 0xFA01 requirement".into(),
                ));
            }
            let new = meta_of(new_ext)?
                .ok_or_else(|| MlsError::PolicyViolation("commit drops group_meta".into()))?;
            Some(meta_change(
                &current,
                &new,
                &conversation_id(group.group_id().as_slice()),
            ))
        } else {
            None
        };
        // `group` is still at the base epoch: its leaves are the ones the rule is about.
        let leaves = Self::member_infos(group)?;
        let summary = summary(&committer.user_id, added, removed, &leaves, meta);
        let ctx = tab_context(&current, &leaves, added_agents);
        check_tab_policy(&current.admins, &ctx, &summary).map_err(MlsError::PolicyViolation)?;
        Ok(gce)
    }

    /// After a `grp:` Welcome: switch to PrivateMessage handshakes and check the group's meta.
    pub(crate) fn finish_group_join(&self, group: &mut MlsGroup) -> Result<()> {
        group
            .set_configuration(
                self.provider.storage(),
                &Self::join_config(group.group_id().as_slice()),
            )
            .map_err(storage)?;
        let meta = match meta_of(group.extensions())? {
            Some(m) if requires_meta(group.extensions()) => m,
            _ => return Err(MlsError::Welcome("group has no group_meta".into())),
        };
        if group.members().count() > MAX_GROUP_LEAVES {
            return Err(MlsError::Welcome("too many devices".into()));
        }
        Self::check_tree_tabs(&meta, &Self::member_infos(group)?).map_err(MlsError::Welcome)
    }

    /// §24.1 on a whole tree (a Welcome we join): a Private group has no agent leaf (and no
    /// agents, checked by [`GroupMeta::validate`]); in Official every agent leaf's user is in
    /// `agents` (which never names an admin).
    fn check_tree_tabs(
        meta: &GroupMeta,
        members: &[MemberInfo],
    ) -> std::result::Result<(), String> {
        for m in members.iter().filter(|m| m.kind.is_agent()) {
            match meta.tab() {
                Tab::Private => {
                    return Err(format!(
                        "a Private group never holds an agent leaf ({})",
                        m.device()
                    ));
                }
                Tab::Official if !meta.is_agent(&m.user_id) => {
                    return Err(format!("agent leaf {} is not in agents", m.device()));
                }
                Tab::Official => {}
            }
        }
        Ok(())
    }

    // ---------------------------------------------------------------------------------------
    // Public group API
    // ---------------------------------------------------------------------------------------

    /// Create a `grp:` group at epoch 0 with `meta` and stage the commit that adds every claimed
    /// key package (§12.3; also the rebuild after a reset, §12.8, with a new generation in the
    /// id). Every key package must be groups-capable, and this device's user must be an admin in
    /// `meta`. Send it with `epoch` 0 and keep it pending until the server's verdict. A local
    /// epoch-0 group left over from a lost race is replaced. `key_packages` may be empty (a group
    /// of this device alone; other devices come later).
    pub fn create_group_with_meta(
        &self,
        group_id: &[u8],
        key_packages: &[Vec<u8>],
        meta: &GroupMeta,
    ) -> Result<GroupCommit> {
        Self::require_group_id(group_id)?;
        meta.validate()?;
        if !meta.is_admin(&self.device.user_id) {
            return Err(MlsError::PolicyViolation(
                "the creator must be an admin".into(),
            ));
        }
        self.tx(|c| {
            let ext = c.leaf_extensions()?;
            let kps = if key_packages.is_empty() {
                vec![]
            } else {
                c.validate_key_packages(key_packages)?
            };
            Self::require_groups_capable(&kps)?;
            let added: Vec<DeviceId> = kps.iter().map(|(_, d)| d.clone()).collect();
            let me = MemberInfo {
                user_id: c.device.user_id.clone(),
                device_id: c.device.device_id.clone(),
                leaf_index: 0,
                signature_key: vec![],
                kind: c.own_kind(),
            };
            Self::check_caps(std::slice::from_ref(&me), &added, &[])?;
            // §24.1 on the epoch-0 group: the tab rules as if `meta` were the base epoch's.
            let added_agents = Self::agent_users_of(&kps);
            let leaves = [me];
            let ctx = tab_context(meta, &leaves, &added_agents);
            let s = summary(&c.device.user_id, &added, &[], &leaves, None);
            check_tab_policy(&meta.admins, &ctx, &s).map_err(MlsError::PolicyViolation)?;
            let gid = GroupId::from_slice(group_id);
            if let Some(mut old) = MlsGroup::load(c.provider.storage(), &gid).map_err(storage)? {
                if old.epoch().as_u64() != 0 {
                    return Err(MlsError::GroupExists);
                }
                old.delete(c.provider.storage()).map_err(storage)?;
            }
            let mut group = MlsGroup::builder()
                .ciphersuite(crate::CIPHERSUITE)
                .with_group_id(gid)
                .use_ratchet_tree_extension(true)
                .with_wire_format_policy(wire_policy(group_id))
                .max_past_epochs(crate::MAX_PAST_EPOCHS)
                .sender_ratchet_configuration(crate::sender_ratchet_config())
                .with_capabilities(Self::capabilities())
                .with_group_context_extensions(initial_extensions(meta)?)
                .with_leaf_node_extensions(ext)
                .map_err(other)?
                .build(&c.provider, &c.signer, c.credential())
                .map_err(other)?;
            let kps: Vec<KeyPackage> = kps.into_iter().map(|(k, _)| k).collect();
            let force = kps.is_empty();
            let (commit, welcome) = c.build_commit(&mut group, kps, vec![], None, force)?;
            c.purge_admins(group_id)?;
            c.record_admins(&group)?;
            c.finish_group_commit(group_id, 0, commit, welcome, added, vec![], false)
        })
    }

    fn require_groups_capable(kps: &[(KeyPackage, DeviceId)]) -> Result<()> {
        match kps
            .iter()
            .find(|(k, _)| !leaf_supports_groups(k.leaf_node()))
        {
            Some((_, d)) => Err(MlsError::InvalidKeyPackage(format!(
                "{d}: key package lacks the groups capability (0xFA01)"
            ))),
            None => Ok(()),
        }
    }

    /// Stage one commit that adds the owners of `key_packages` and removes `remove` (§12.4: an
    /// `add`, `remove` or `devices` op). A device listed in both is **re-added** (a `rejoin`:
    /// its old leaf goes, a fresh one from its new key package comes). This device can't remove
    /// itself. The admin policy and the caps are checked first.
    pub fn change_members(
        &self,
        group_id: &[u8],
        key_packages: &[Vec<u8>],
        remove: &[DeviceId],
    ) -> Result<GroupCommit> {
        Self::require_group_id(group_id)?;
        self.tx(|c| {
            let mut group = c.load(group_id)?;
            c.check_can_commit(&group)?;
            if key_packages.is_empty() && remove.is_empty() {
                return Err(MlsError::Malformed("nothing to change".into()));
            }
            if remove.contains(&c.device) {
                return Err(MlsError::Malformed(
                    "cannot remove own device (an admin commits a leave)".into(),
                ));
            }
            let members = Self::member_infos(&group)?;
            let mut removed: Vec<DeviceId> = Vec::new();
            let mut indices = Vec::new();
            for d in remove {
                if removed.contains(d) {
                    continue;
                }
                let m = members
                    .iter()
                    .find(|m| m.device() == *d)
                    .ok_or(MlsError::UnknownMember)?;
                indices.push(LeafNodeIndex::new(m.leaf_index));
                removed.push(d.clone());
            }
            let kps = if key_packages.is_empty() {
                vec![]
            } else {
                c.validate_key_packages(key_packages)?
            };
            Self::require_groups_capable(&kps)?;
            if let Some((_, d)) = kps
                .iter()
                .find(|(_, d)| members.iter().any(|m| m.device() == *d) && !removed.contains(d))
            {
                return Err(MlsError::Malformed(format!(
                    "{d} is already a member (list it in remove too to re-add it)"
                )));
            }
            let added: Vec<DeviceId> = kps.iter().map(|(_, d)| d.clone()).collect();
            Self::check_caps(&members, &added, &removed)?;
            c.check_own_policy(&group, &added, &Self::agent_users_of(&kps), &removed, None)?;
            let epoch = group.epoch().as_u64();
            let kps = kps.into_iter().map(|(k, _)| k).collect();
            let (commit, welcome) = c.build_commit(&mut group, kps, indices, None, false)?;
            c.finish_group_commit(group_id, epoch, commit, welcome, added, removed, false)
        })
    }

    /// Stage one commit that removes every leaf of `user_ids` (a `remove` op, including a member's
    /// leave). This device's own user can't be listed: the leave of a user is committed by an
    /// admin's device (§12.3). [`MlsError::UnknownMember`] if none of them has a leaf.
    pub fn remove_users(&self, group_id: &[u8], user_ids: &[String]) -> Result<GroupCommit> {
        Self::require_group_id(group_id)?;
        if user_ids.contains(&self.device.user_id) {
            return Err(MlsError::Malformed(
                "cannot remove own user (the leave is committed by an admin)".into(),
            ));
        }
        let devices: Vec<DeviceId> = self
            .members(group_id)?
            .into_iter()
            .filter(|m| user_ids.contains(&m.user_id))
            .map(|m| m.device())
            .collect();
        if devices.is_empty() {
            return Err(MlsError::UnknownMember);
        }
        self.change_members(group_id, &[], &devices)
    }

    /// Stage a GroupContextExtensions commit that sets `group_meta` to `meta` (a rename, or a
    /// `role` op's new admin list). Fields unknown to this version are carried over from the
    /// current meta. Admins only (§12.4); a no-op change is [`MlsError::Malformed`].
    pub fn update_group_meta(&self, group_id: &[u8], meta: &GroupMeta) -> Result<GroupCommit> {
        Self::require_group_id(group_id)?;
        meta.validate()?;
        self.tx(|c| {
            let mut group = c.load(group_id)?;
            c.check_can_commit(&group)?;
            let current = meta_of(group.extensions())?
                .ok_or_else(|| MlsError::PolicyViolation("group has no group_meta".into()))?;
            let mut new = meta.clone();
            for (k, v) in &current.extra {
                new.extra.entry(k.clone()).or_insert_with(|| v.clone());
            }
            // v1.24 fields the caller left out (an older app's rename) are carried over.
            if new.tab.is_none() {
                new.tab.clone_from(&current.tab);
            }
            if new.chat_id.is_none() {
                new.chat_id.clone_from(&current.chat_id);
            }
            if new.agents.is_none() {
                new.agents.clone_from(&current.agents);
            }
            new.validate()?;
            let change = meta_change(&current, &new, &conversation_id(group_id));
            if meta_unchanged(&change) && current.extra == new.extra {
                return Err(MlsError::Malformed("group_meta unchanged".into()));
            }
            c.check_own_policy(&group, &[], &[], &[], Some(change))?;
            let mut ext = group.extensions().clone();
            ext.add_or_replace(meta_extension(&new)?).map_err(other)?;
            let epoch = group.epoch().as_u64();
            let (commit, welcome) = c.build_commit(&mut group, vec![], vec![], Some(ext), false)?;
            c.finish_group_commit(group_id, epoch, commit, welcome, vec![], vec![], true)
        })
    }

    /// Stage a self-update (an empty commit with a fresh path: key rotation). Anyone may send it.
    pub fn self_update(&self, group_id: &[u8]) -> Result<GroupCommit> {
        Self::require_group_id(group_id)?;
        self.tx(|c| {
            let mut group = c.load(group_id)?;
            c.check_can_commit(&group)?;
            let epoch = group.epoch().as_u64();
            let (commit, welcome) = c.build_commit(&mut group, vec![], vec![], None, true)?;
            c.finish_group_commit(group_id, epoch, commit, welcome, vec![], vec![], false)
        })
    }

    /// The group's current `group_meta` (`None` for a DM group).
    pub fn group_meta(&self, group_id: &[u8]) -> Result<Option<GroupMeta>> {
        meta_of(self.load(group_id)?.extensions())
    }

    /// Catch up on a sequence of commits in log order (`GET …/commits`, §12.8), all in one
    /// transaction: any failure rolls back the whole batch.
    /// - Commits below our epoch are skipped (already applied).
    /// - A commit above our epoch is [`MlsError::WrongEpoch`] (a gap in the log).
    /// - **Our own pending commit** (recognised by its hash) means the server accepted it: it is
    ///   merged (the restart between `200` and `commit_accepted` case).
    /// - Processing stops after a commit that removes this device.
    pub fn process_commits(&self, group_id: &[u8], commits: &[Vec<u8>]) -> Result<CatchUp> {
        self.tx(|c| {
            let mut out = CatchUp {
                epoch: 0,
                applied: vec![],
                skipped: 0,
                removed_self: false,
            };
            for bytes in commits {
                let mut group = c.load(group_id)?;
                if !group.is_active() {
                    return Err(MlsError::RemovedFromGroup);
                }
                let msg = MlsMessageIn::tls_deserialize_exact(bytes)
                    .map_err(|e| MlsError::Malformed(e.to_string()))?;
                let protocol = msg
                    .try_into_protocol_message()
                    .map_err(|e| MlsError::Malformed(e.to_string()))?;
                if protocol.group_id().as_slice() != group_id {
                    return Err(MlsError::Malformed("commit is for another group".into()));
                }
                if protocol.content_type() != ContentType::Commit {
                    return Err(MlsError::Malformed("not a commit".into()));
                }
                let (e, current) = (protocol.epoch().as_u64(), group.epoch().as_u64());
                if e < current {
                    out.skipped += 1;
                    continue;
                }
                if e > current {
                    return Err(MlsError::WrongEpoch);
                }
                if group.pending_commit().is_some()
                    && let Some(rec) = c.pending_record(group_id)?
                    && rec.sha256 == Sha256::digest(bytes).as_slice()
                {
                    group.merge_pending_commit(&c.provider).map_err(other)?;
                    c.clear_pending_record(group_id)?;
                    c.record_admins(&group)?;
                    out.applied.push(Incoming::Commit {
                        epoch: group.epoch().as_u64(),
                        committer: c.device.clone(),
                        added: parse_ids(&rec.added)?,
                        removed: parse_ids(&rec.removed)?,
                        removed_self: false,
                        discarded_own_pending: false,
                        meta_changed: rec.meta_changed,
                    });
                    continue;
                }
                match c.process_inner(group_id, bytes, false)?.incoming {
                    i @ Incoming::Commit { removed_self, .. } => {
                        out.applied.push(i);
                        if removed_self {
                            out.removed_self = true;
                            break;
                        }
                    }
                    Incoming::OwnEcho => {
                        return Err(MlsError::Other(
                            "own commit in the log, but no matching pending commit".into(),
                        ));
                    }
                    Incoming::Application { .. } => {
                        return Err(MlsError::Malformed("not a commit".into()));
                    }
                }
            }
            out.epoch = c.load(group_id)?.epoch().as_u64();
            Ok(out)
        })
    }
}
