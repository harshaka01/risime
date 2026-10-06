//! The per-epoch admin record (contract v1.12 §15.4, crypto review R2).
//!
//! OpenMLS keeps no GroupContext extensions of past epochs, so "was the sender an admin at the
//! message's epoch `e`" can't be read back from it. The core therefore writes `group_meta.admins`
//! for every epoch of a `grp:` group, in the same transaction as the merge that produced the
//! epoch, on every path: create, Welcome, peer commit, each commit of a catch-up, own commits.
//!
//! Keys (in the app's [`crate::KvStore`]):
//! - `risime/admins/<gid>/<epoch>` (decimal epoch): the JSON list of admin user ids;
//! - `risime/admins/<gid>`: the JSON list of epochs recorded, for pruning and the purge.
//!
//! Retention: the current epoch plus [`crate::MAX_PAST_EPOCHS`], the same window as the past-epoch
//! secrets. An application message from an older epoch can't be decrypted anyway.

use openmls::prelude::*;

use crate::group::{is_group_id, meta_of};
use crate::policy::is_admin;
use crate::{Client, MAX_PAST_EPOCHS, MlsError, Result, storage};

fn index_key(group_id: &[u8]) -> Vec<u8> {
    [b"risime/admins/".as_slice(), group_id].concat()
}

fn epoch_key(group_id: &[u8], epoch: u64) -> Vec<u8> {
    [
        index_key(group_id).as_slice(),
        b"/",
        epoch.to_string().as_bytes(),
    ]
    .concat()
}

impl Client {
    fn admin_epochs(&self, group_id: &[u8]) -> Result<Vec<u64>> {
        self.kv
            .get(&index_key(group_id))
            .map_err(storage)?
            .map(|b| serde_json::from_slice(&b).map_err(storage))
            .transpose()
            .map(Option::unwrap_or_default)
    }

    /// Record `group_meta.admins` for the group's current epoch and prune records outside the
    /// retention window. A no-op for DM groups. Call it right after every merge (same transaction).
    pub(crate) fn record_admins(&self, group: &MlsGroup) -> Result<()> {
        let gid = group.group_id().as_slice();
        if !is_group_id(gid) {
            return Ok(());
        }
        let meta = meta_of(group.extensions())?
            .ok_or_else(|| MlsError::PolicyViolation("group has no group_meta".into()))?;
        let epoch = group.epoch().as_u64();
        self.kv
            .put(
                &epoch_key(gid, epoch),
                &serde_json::to_vec(&meta.admins).map_err(storage)?,
            )
            .map_err(storage)?;
        let low = epoch.saturating_sub(MAX_PAST_EPOCHS as u64);
        let mut keep = Vec::new();
        for e in self.admin_epochs(gid)? {
            if e == epoch {
                continue;
            }
            if e < low || e > epoch {
                self.kv.delete(&epoch_key(gid, e)).map_err(storage)?;
            } else {
                keep.push(e);
            }
        }
        keep.push(epoch);
        keep.sort_unstable();
        self.kv
            .put(
                &index_key(gid),
                &serde_json::to_vec(&keep).map_err(storage)?,
            )
            .map_err(storage)
    }

    /// Remove every admin record of the group (the group is deleted or replaced).
    pub(crate) fn purge_admins(&self, group_id: &[u8]) -> Result<()> {
        for e in self.admin_epochs(group_id)? {
            self.kv.delete(&epoch_key(group_id, e)).map_err(storage)?;
        }
        self.kv.delete(&index_key(group_id)).map_err(storage)
    }

    pub(crate) fn admins_record(&self, group_id: &[u8], epoch: u64) -> Result<Option<Vec<String>>> {
        self.kv
            .get(&epoch_key(group_id, epoch))
            .map_err(storage)?
            .map(|b| serde_json::from_slice(&b).map_err(storage))
            .transpose()
    }

    /// The admin list the core recorded for `epoch` of a `grp:` group, or `None` if there is no
    /// record (a DM group, an epoch outside the retention window, or before this device joined).
    pub fn admins_at_epoch(&self, group_id: &[u8], epoch: u64) -> Result<Option<Vec<String>>> {
        self.load(group_id)?;
        self.admins_record(group_id, epoch)
    }

    /// "Was `user_id` an admin at `epoch`": `None` for a DM group; [`MlsError::Malformed`] if a
    /// `grp:` group has no record for that epoch. Agents are never admins (the core has no agent
    /// list yet).
    pub(crate) fn admin_at(
        &self,
        group_id: &[u8],
        epoch: u64,
        user_id: &str,
    ) -> Result<Option<bool>> {
        if !is_group_id(group_id) {
            return Ok(None);
        }
        let admins = self
            .admins_record(group_id, epoch)?
            .ok_or_else(|| MlsError::Malformed(format!("no admin record for epoch {epoch}")))?;
        Ok(Some(is_admin(&admins, &[], user_id)))
    }
}
