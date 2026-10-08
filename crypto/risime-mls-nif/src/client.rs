//! The Risi member client: a [`risime_mls::Client`] on a [`JournalStore`], restricted to Official
//! groups (contract v1.24 §24.0, §24.11). Plain Rust, so it is unit-tested without a BEAM; the
//! NIF layer ([`crate`]) only converts terms.

use std::sync::Arc;

use risime_mls::policy::Tab;
use risime_mls::{
    CatchUp, Client, DeviceId, GroupCommit, GroupMeta, JoinedGroup, KvStore, MemberInfo, MlsError,
    Processed, TrustAnchors,
};

use crate::journal::{JournalEntry, JournalStore, SealError};

/// Every error a call can return.
#[derive(Debug, PartialEq, Eq)]
pub enum Error {
    /// An error of the MLS core, unchanged.
    Mls(MlsError),
    /// The group is not an Official group (a `dm:` group, or `tab` absent or `private`). Risi never
    /// operates a Private conversation (§24.0); the call changed nothing.
    PrivateTab,
    /// Loading or sealing the store failed.
    Seal(SealError),
}

impl From<MlsError> for Error {
    fn from(e: MlsError) -> Self {
        Error::Mls(e)
    }
}

impl From<SealError> for Error {
    fn from(e: SealError) -> Self {
        Error::Seal(e)
    }
}

impl Error {
    /// The atom the NIF returns, and a message that never contains key material or plaintext.
    pub fn parts(&self) -> (&'static str, String) {
        match self {
            Error::PrivateTab => (
                "private_tab",
                "Risi never operates a Private or dm group".into(),
            ),
            Error::Seal(SealError::BadKek) => ("bad_kek", SealError::BadKek.to_string()),
            Error::Seal(e @ SealError::Tampered(_)) => ("tampered", e.to_string()),
            Error::Seal(e @ SealError::Internal(_)) => ("storage", e.to_string()),
            Error::Mls(e) => {
                let kind = match e {
                    MlsError::Malformed(_) => "malformed",
                    MlsError::InvalidKeyPackage(_) => "invalid_key_package",
                    MlsError::UntrustedCredential(_) => "untrusted_credential",
                    MlsError::MissingAttestation => "missing_attestation",
                    MlsError::UnknownGroup => "unknown_group",
                    MlsError::GroupExists => "group_exists",
                    MlsError::UnknownMember => "unknown_member",
                    MlsError::RemovedFromGroup => "removed_from_group",
                    MlsError::WrongEpoch => "wrong_epoch",
                    MlsError::DecryptionFailed(_) => "decryption_failed",
                    MlsError::NotApplicationMessage => "not_application_message",
                    MlsError::Welcome(_) => "welcome",
                    MlsError::CommitPending => "commit_pending",
                    MlsError::NoPendingCommit => "no_pending_commit",
                    MlsError::Storage(_) => "storage",
                    MlsError::Other(_) => "other",
                    MlsError::PolicyViolation(_) => "policy_violation",
                };
                (kind, e.to_string())
            }
        }
    }
}

pub type Result<T> = std::result::Result<T, Error>;

/// A successful call's value plus the rows to persist before acting on it.
pub type WithJournal<T> = (T, Vec<JournalEntry>);

/// One Risi device's MLS state.
pub struct RisiClient {
    client: Client,
    store: Arc<JournalStore>,
}

impl RisiClient {
    /// Load the device from its sealed rows (`(key, sealed)`), or create it on first use (the
    /// journal then holds the new identity). `anchors` are the pinned attestation JWKs.
    pub fn open(
        user_id: &str,
        device_id: &str,
        anchors: &[String],
        kek: &[u8],
        rows: Vec<(Vec<u8>, Vec<u8>)>,
    ) -> Result<WithJournal<Self>> {
        let device = DeviceId::new(user_id, device_id)?;
        let anchors = TrustAnchors::from_jwks(anchors)?;
        let store = Arc::new(JournalStore::load(kek, device_id, rows)?);
        let client = Client::open(store.clone(), device, anchors)?;
        let journal = store.drain()?;
        Ok((Self { client, store }, journal))
    }

    /// Run one core call and drain the journal. On an error the core has rolled its transaction
    /// back; anything still dirty goes out with the next successful call.
    fn run<T>(&mut self, f: impl FnOnce(&mut Client) -> Result<T>) -> Result<WithJournal<T>> {
        let value = f(&mut self.client)?;
        Ok((value, self.store.drain()?))
    }

    fn require_official(client: &Client, group_id: &[u8]) -> Result<()> {
        match client.group_meta(group_id) {
            Ok(Some(m)) if m.tab() == Tab::Official => Ok(()),
            Ok(_) => Err(Error::PrivateTab),
            Err(MlsError::Malformed(_)) => Err(Error::PrivateTab),
            Err(e) => Err(e.into()),
        }
    }

    pub fn signature_public_key(&mut self) -> Result<WithJournal<Vec<u8>>> {
        self.run(|c| Ok(c.signature_public_key()))
    }

    pub fn set_attestation(&mut self, jws: &str) -> Result<WithJournal<()>> {
        self.run(|c| Ok(c.set_attestation(jws)?))
    }

    pub fn generate_key_packages(&mut self, count: u16) -> Result<WithJournal<Vec<Vec<u8>>>> {
        self.run(|c| Ok(c.generate_key_packages(count)?))
    }

    pub fn last_resort_key_package(&mut self) -> Result<WithJournal<Vec<u8>>> {
        self.run(|c| Ok(c.generate_last_resort_key_package()?))
    }

    /// Join from a Welcome, but only into an Official group: anything else is
    /// [`Error::PrivateTab`] and leaves the state exactly as it was (defense in depth, §24.0).
    pub fn join_from_welcome(&mut self, welcome: &[u8]) -> Result<WithJournal<JoinedGroup>> {
        let store = self.store.clone();
        self.run(|c| {
            store.begin().map_err(|e| SealError::Internal(e.0))?;
            let res = c
                .join_from_welcome(welcome)
                .map_err(Error::from)
                .and_then(|j| {
                    Self::require_official(c, &j.group_id)?;
                    Ok(j)
                });
            match res {
                Ok(j) => {
                    store.commit().map_err(|e| SealError::Internal(e.0))?;
                    Ok(j)
                }
                Err(e) => {
                    store.rollback().map_err(|e| SealError::Internal(e.0))?;
                    Err(e)
                }
            }
        })
    }

    pub fn process_detailed(
        &mut self,
        group_id: &[u8],
        message: &[u8],
    ) -> Result<WithJournal<Processed>> {
        self.run(|c| {
            Self::require_official(c, group_id)?;
            Ok(c.process_detailed(group_id, message)?)
        })
    }

    pub fn process_commits(
        &mut self,
        group_id: &[u8],
        commits: &[Vec<u8>],
    ) -> Result<WithJournal<CatchUp>> {
        self.run(|c| {
            Self::require_official(c, group_id)?;
            Ok(c.process_commits(group_id, commits)?)
        })
    }

    pub fn encrypt(
        &mut self,
        group_id: &[u8],
        plaintext: &[u8],
        aad: &[u8],
    ) -> Result<WithJournal<Vec<u8>>> {
        self.run(|c| {
            Self::require_official(c, group_id)?;
            Ok(c.encrypt_with_aad(group_id, plaintext, aad)?)
        })
    }

    pub fn self_update(&mut self, group_id: &[u8]) -> Result<WithJournal<GroupCommit>> {
        self.run(|c| {
            Self::require_official(c, group_id)?;
            Ok(c.self_update(group_id)?)
        })
    }

    pub fn commit_accepted(&mut self, group_id: &[u8]) -> Result<WithJournal<u64>> {
        self.run(|c| Ok(c.commit_accepted(group_id)?))
    }

    pub fn commit_rejected(&mut self, group_id: &[u8]) -> Result<WithJournal<()>> {
        self.run(|c| Ok(c.commit_rejected(group_id)?))
    }

    /// Remove all state of a group (Official turned off, Risi removed). Allowed for any group id.
    pub fn purge_group(&mut self, group_id: &[u8]) -> Result<WithJournal<()>> {
        self.run(|c| Ok(c.purge_group(group_id)?))
    }

    pub fn members(&mut self, group_id: &[u8]) -> Result<WithJournal<Vec<MemberInfo>>> {
        self.run(|c| Ok(c.members(group_id)?))
    }

    pub fn group_meta(&mut self, group_id: &[u8]) -> Result<WithJournal<Option<GroupMeta>>> {
        self.run(|c| Ok(c.group_meta(group_id)?))
    }

    pub fn epoch(&mut self, group_id: &[u8]) -> Result<WithJournal<u64>> {
        self.run(|c| Ok(c.epoch(group_id)?))
    }

    /// **Test support** (feature `test-peer`): create a `grp:` group as this (human) device and
    /// accept the creating commit at once, as a peer does after the server's `200`.
    #[cfg(any(test, feature = "test-peer"))]
    pub fn test_create_group(
        &mut self,
        group_id: &[u8],
        key_packages: &[Vec<u8>],
        meta: &GroupMeta,
    ) -> Result<WithJournal<GroupCommit>> {
        self.run(|c| {
            let gc = c.create_group_with_meta(group_id, key_packages, meta)?;
            c.commit_accepted(group_id)?;
            Ok(gc)
        })
    }

    /// The store's open transaction depth (tests).
    pub fn store_depth(&self) -> usize {
        self.store.depth()
    }
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;

    use risime_mls::{Incoming, LeafKind, MemoryKvStore, TestAttestor};

    use super::*;

    const KEK: [u8; 32] = [9; 32];
    const RISI: &str = "0b5e1f2a-3c4d-4e6f-8a9b-0c1d2e3f4a5b";
    const RISI_DEV: &str = "1c6f2a3b-4d5e-4f70-9bac-1d2e3f4a5b6c";
    const OFFICIAL: &[u8] = b"grp:7d1c0e52-4a8b-4f6e-9a3b-1c2d3e4f5a6b#1";
    const PRIVATE: &[u8] = b"grp:0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d#1";

    type Rows = HashMap<Vec<u8>, Vec<u8>>;

    fn attestor() -> TestAttestor {
        TestAttestor::from_seed([7; 32])
    }

    fn apply(rows: &mut Rows, journal: Vec<JournalEntry>) {
        for (k, v) in journal {
            match v {
                Some(v) => rows.insert(k, v),
                None => rows.remove(&k),
            };
        }
    }

    fn open(rows: &mut Rows, device: &str, agent: bool) -> RisiClient {
        let a = attestor();
        let snapshot: Vec<_> = rows.iter().map(|(k, v)| (k.clone(), v.clone())).collect();
        let fresh = snapshot.is_empty();
        let (mut c, j) = RisiClient::open(RISI, device, &[a.public_jwk()], &KEK, snapshot).unwrap();
        apply(rows, j);
        if fresh {
            let dev = DeviceId::new(RISI, device).unwrap();
            let (pk, _) = c.signature_public_key().unwrap();
            let jws = if agent {
                a.attest_agent(&dev, &pk, 1_760_000_000)
            } else {
                a.attest(&dev, &pk, 1_760_000_000)
            };
            let ((), j) = c.set_attestation(&jws).unwrap();
            apply(rows, j);
        }
        c
    }

    fn human(user: &str) -> Client {
        let a = attestor();
        let mut c = Client::open(
            Arc::new(MemoryKvStore::new()),
            DeviceId::new(user, "d1").unwrap(),
            a.anchors(),
        )
        .unwrap();
        let jws = a.attest(c.device(), &c.signature_public_key(), 1_760_000_000);
        c.set_attestation(&jws).unwrap();
        c
    }

    fn official_meta(agents: &[&str]) -> GroupMeta {
        GroupMeta::new("Site team", vec!["alice".into()]).with_tab(
            Tab::Official,
            "grp:0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
            agents.iter().map(|s| s.to_string()).collect(),
        )
    }

    /// Alice creates `gid` with Risi; Risi joins. Returns Alice.
    fn invite(rows: &mut Rows, risi: &mut RisiClient, gid: &[u8], meta: &GroupMeta) -> Client {
        let alice = human("alice");
        let (kps, j) = risi.generate_key_packages(1).unwrap();
        apply(rows, j);
        let gc = alice.create_group_with_meta(gid, &kps, meta).unwrap();
        alice.commit_accepted(gid).unwrap();
        let (joined, j) = risi
            .join_from_welcome(gc.welcome.as_ref().unwrap())
            .unwrap();
        apply(rows, j);
        assert_eq!(joined.group_id, gid);
        alice
    }

    #[test]
    fn journal_round_trip_keeps_the_epoch_and_the_keys() {
        let mut rows = Rows::new();
        let mut risi = open(&mut rows, RISI_DEV, true);
        let (pk, _) = risi.signature_public_key().unwrap();
        let alice = invite(&mut rows, &mut risi, OFFICIAL, &official_meta(&[RISI]));

        // Messages and a commit while running.
        let ct = alice.encrypt(OFFICIAL, b"ship it friday").unwrap();
        let (p, j) = risi.process_detailed(OFFICIAL, &ct).unwrap();
        apply(&mut rows, j);
        match p.incoming {
            Incoming::Application { plaintext, .. } => assert_eq!(plaintext, b"ship it friday"),
            other => panic!("{other:?}"),
        }
        let gc = alice.self_update(OFFICIAL).unwrap();
        alice.commit_accepted(OFFICIAL).unwrap();
        let (cu, j) = risi
            .process_commits(OFFICIAL, std::slice::from_ref(&gc.commit))
            .unwrap();
        apply(&mut rows, j);
        let epoch = cu.epoch;
        assert_eq!(epoch, alice.epoch(OFFICIAL).unwrap());
        assert_eq!(risi.store_depth(), 0);
        drop(risi);

        // Reload from the sealed rows only.
        let mut risi = open(&mut rows, RISI_DEV, true);
        assert_eq!(risi.signature_public_key().unwrap().0, pk);
        assert_eq!(risi.epoch(OFFICIAL).unwrap().0, epoch);
        let members = risi.members(OFFICIAL).unwrap().0;
        assert_eq!(members.len(), 2);
        assert!(
            members
                .iter()
                .any(|m| m.user_id == RISI && m.kind == LeafKind::Agent)
        );
        let meta = risi.group_meta(OFFICIAL).unwrap().0.unwrap();
        assert_eq!(meta.tab(), Tab::Official);
        assert_eq!(meta.agents(), [RISI.to_string()]);

        // Both directions still work after the reload.
        let ct = alice.encrypt(OFFICIAL, b"after reload").unwrap();
        let (p, j) = risi.process_detailed(OFFICIAL, &ct).unwrap();
        apply(&mut rows, j);
        assert!(matches!(p.incoming, Incoming::Application { .. }));
        let (out, j) = risi.encrypt(OFFICIAL, b"noted", b"").unwrap();
        apply(&mut rows, j);
        assert_eq!(alice.decrypt(OFFICIAL, &out).unwrap(), b"noted");

        // Risi's own self-update, accepted.
        let (gc, j) = risi.self_update(OFFICIAL).unwrap();
        apply(&mut rows, j);
        let (e, j) = risi.commit_accepted(OFFICIAL).unwrap();
        apply(&mut rows, j);
        alice.process(OFFICIAL, &gc.commit).unwrap();
        assert_eq!(e, alice.epoch(OFFICIAL).unwrap());
        drop(risi);
        let mut risi = open(&mut rows, RISI_DEV, true);
        assert_eq!(risi.epoch(OFFICIAL).unwrap().0, e);

        // Rejected self-update leaves the epoch.
        let (_, j) = risi.self_update(OFFICIAL).unwrap();
        apply(&mut rows, j);
        let ((), j) = risi.commit_rejected(OFFICIAL).unwrap();
        apply(&mut rows, j);
        assert_eq!(risi.epoch(OFFICIAL).unwrap().0, e);

        // Purge removes every row of the group.
        let before = rows.len();
        let ((), j) = risi.purge_group(OFFICIAL).unwrap();
        assert!(j.iter().all(|(_, v)| v.is_none()));
        apply(&mut rows, j);
        assert!(rows.len() < before);
        assert_eq!(
            risi.epoch(OFFICIAL).unwrap_err(),
            Error::Mls(MlsError::UnknownGroup)
        );
        drop(risi);
        let mut risi = open(&mut rows, RISI_DEV, true);
        assert!(risi.epoch(OFFICIAL).is_err());
    }

    #[test]
    fn a_tampered_row_fails_open() {
        let mut rows = Rows::new();
        let risi = open(&mut rows, RISI_DEV, true);
        drop(risi);
        let k = rows.keys().next().unwrap().clone();
        rows.get_mut(&k).unwrap()[3] ^= 1; // a nonce byte
        let a = attestor();
        let snapshot: Vec<_> = rows.into_iter().collect();
        let err = RisiClient::open(RISI, RISI_DEV, &[a.public_jwk()], &KEK, snapshot)
            .err()
            .unwrap();
        assert!(matches!(err, Error::Seal(SealError::Tampered(_))));
        assert_eq!(err.parts().0, "tampered");
    }

    #[test]
    fn the_wrong_kek_or_device_fails_open() {
        let mut rows = Rows::new();
        drop(open(&mut rows, RISI_DEV, true));
        let a = attestor();
        let snapshot: Vec<_> = rows.iter().map(|(k, v)| (k.clone(), v.clone())).collect();
        let err = RisiClient::open(
            RISI,
            RISI_DEV,
            &[a.public_jwk()],
            &[8; 32],
            snapshot.clone(),
        )
        .err()
        .unwrap();
        assert!(matches!(err, Error::Seal(SealError::Tampered(_))));
        let other = "2c6f2a3b-4d5e-4f70-9bac-1d2e3f4a5b6c";
        let err = RisiClient::open(RISI, other, &[a.public_jwk()], &KEK, snapshot)
            .err()
            .unwrap();
        assert!(matches!(err, Error::Seal(SealError::Tampered(_))));
        let err = RisiClient::open(RISI, RISI_DEV, &[a.public_jwk()], &[1; 16], vec![])
            .err()
            .unwrap();
        assert_eq!(err.parts().0, "bad_kek");
        assert!(!err.parts().1.contains("[1"));
    }

    #[test]
    fn private_and_dm_groups_are_refused() {
        // A user-attested device (an agent leaf would already be refused by the core), so the
        // Welcome itself is valid and only the NIF's own check stands in the way.
        let mut rows = Rows::new();
        let mut c = open(&mut rows, RISI_DEV, false);
        let alice = human("alice");

        let (kps, j) = c.generate_key_packages(2).unwrap();
        apply(&mut rows, j);
        let private = GroupMeta::new("Site team", vec!["alice".into()]);
        let gc = alice
            .create_group_with_meta(PRIVATE, &kps[..1], &private)
            .unwrap();
        alice.commit_accepted(PRIVATE).unwrap();
        let before = rows.clone();
        let err = c
            .join_from_welcome(gc.welcome.as_ref().unwrap())
            .unwrap_err();
        assert_eq!(err, Error::PrivateTab);
        assert_eq!(err.parts().0, "private_tab");
        assert_eq!(c.store_depth(), 0);
        assert!(matches!(
            c.epoch(PRIVATE).unwrap_err(),
            Error::Mls(MlsError::UnknownGroup)
        ));
        // Nothing to persist: the join was rolled back as a whole.
        let ((), j) = c.set_attestation_noop();
        assert!(j.is_empty());
        assert_eq!(rows, before);

        // A dm: group.
        let dm = b"dm:alice_risi#1";
        let pc = alice.create_group(dm, &kps[1..]).unwrap();
        alice.commit_accepted(dm).unwrap();
        assert_eq!(
            c.join_from_welcome(pc.welcome.as_ref().unwrap())
                .unwrap_err(),
            Error::PrivateTab
        );
        assert!(c.epoch(dm).is_err());

        // A Private group that got into the store anyway (core join, bypassing the check): sending,
        // processing and self-updates are refused too.
        let (kps, j) = c.generate_key_packages(1).unwrap();
        apply(&mut rows, j);
        let gid = b"grp:1a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d#1";
        let gc = alice.create_group_with_meta(gid, &kps, &private).unwrap();
        alice.commit_accepted(gid).unwrap();
        c.client
            .join_from_welcome(gc.welcome.as_ref().unwrap())
            .unwrap();
        assert_eq!(c.encrypt(gid, b"x", b"").unwrap_err(), Error::PrivateTab);
        assert_eq!(c.self_update(gid).unwrap_err(), Error::PrivateTab);
        let ct = alice.encrypt(gid, b"private text").unwrap();
        assert_eq!(c.process_detailed(gid, &ct).unwrap_err(), Error::PrivateTab);
        let su = alice.self_update(gid).unwrap();
        assert_eq!(
            c.process_commits(gid, &[su.commit]).unwrap_err(),
            Error::PrivateTab
        );
        // Unknown groups stay unknown.
        assert_eq!(
            c.encrypt(dm, b"x", b"").unwrap_err(),
            Error::Mls(MlsError::UnknownGroup)
        );
    }

    #[test]
    fn an_agent_is_refused_by_the_core_in_private() {
        let mut rows = Rows::new();
        let mut risi = open(&mut rows, RISI_DEV, true);
        let alice = human("alice");
        let (kps, _) = risi.generate_key_packages(1).unwrap();
        let private = GroupMeta::new("Site team", vec!["alice".into()]);
        // The creator's own core refuses to add an agent to a Private group.
        assert!(
            alice
                .create_group_with_meta(PRIVATE, &kps, &private)
                .is_err()
        );
    }

    impl RisiClient {
        /// A call that changes nothing (tests: the journal is empty).
        fn set_attestation_noop(&mut self) -> WithJournal<()> {
            self.run(|_| Ok(())).unwrap()
        }
    }
}
