//! Shared helpers for the integration tests. Every MLS object crosses the API as bytes, exactly
//! as it would through the server.
#![allow(dead_code)]

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use risime_mls::{
    Client, CredentialValidator, DeviceId, KvError, KvStore, MemoryKvStore, Result, TestAttestor,
};

pub const G: &[u8] = b"dm:alice_bob#1";

pub fn attestor() -> TestAttestor {
    TestAttestor::from_seed([7; 32])
}

pub fn dev(user: &str, device: &str) -> DeviceId {
    DeviceId::new(user, device).unwrap()
}

/// An attested client on its own in-memory store, trusting [`attestor`].
pub fn client(user: &str, device: &str) -> Client {
    client_on(Arc::new(MemoryKvStore::new()), user, device)
}

pub fn client_on(store: Arc<dyn KvStore>, user: &str, device: &str) -> Client {
    let a = attestor();
    let mut c = Client::open(store, dev(user, device), a.anchors()).unwrap();
    let jws = a.attest(c.device(), &c.signature_public_key(), 1_760_000_000);
    c.set_attestation(&jws).unwrap();
    c
}

/// A validator that accepts anything: lets a test build a "malicious" client.
pub struct AcceptAll;

impl CredentialValidator for AcceptAll {
    fn validate(&self, _: &DeviceId, _: &[u8], _: Option<&[u8]>) -> Result<()> {
        Ok(())
    }
}

/// A client that trusts everything and carries a bogus attestation.
pub fn rogue(user: &str, device: &str) -> Client {
    let mut c = Client::in_memory(dev(user, device), AcceptAll).unwrap();
    c.set_attestation("not.a.jws").unwrap();
    c
}

pub fn kp(c: &Client) -> Vec<u8> {
    c.generate_key_packages(1).unwrap().remove(0)
}

/// `creator` creates [`G`] with everyone in `others`; all join; returns the epoch (1).
pub fn group(creator: &Client, others: &[&Client]) -> u64 {
    let kps: Vec<Vec<u8>> = others.iter().map(|c| kp(c)).collect();
    let pc = creator.create_group(G, &kps).unwrap();
    assert_eq!(pc.epoch, 0);
    let epoch = creator.commit_accepted(G).unwrap();
    for o in others {
        let j = o.join_from_welcome(pc.welcome.as_ref().unwrap()).unwrap();
        assert_eq!(j.epoch, epoch);
    }
    epoch
}

/// Server-like delivery of a pending commit: committer merges (200), everyone else processes.
pub fn deliver_commit(committer: &Client, commit: &[u8], to: &[&Client]) -> u64 {
    let epoch = committer.commit_accepted(G).unwrap();
    for c in to {
        c.process(G, commit).unwrap();
    }
    epoch
}

pub fn assert_same_epoch(clients: &[&Client]) {
    let first = clients[0].epoch_authenticator(G).unwrap();
    for c in clients {
        assert_eq!(c.epoch_authenticator(G).unwrap(), first, "{}", c.identity());
    }
}

/// A store whose `put`/`commit` can be made to fail, to prove rollback.
pub struct FaultyKv {
    pub inner: MemoryKvStore,
    pub fail_commit: AtomicBool,
    pub fail_put: AtomicBool,
}

impl FaultyKv {
    pub fn new() -> Self {
        Self {
            inner: MemoryKvStore::new(),
            fail_commit: AtomicBool::new(false),
            fail_put: AtomicBool::new(false),
        }
    }
}

impl KvStore for FaultyKv {
    fn get(&self, key: &[u8]) -> std::result::Result<Option<Vec<u8>>, KvError> {
        self.inner.get(key)
    }
    fn put(&self, key: &[u8], value: &[u8]) -> std::result::Result<(), KvError> {
        if self.fail_put.load(Ordering::SeqCst) {
            return Err(KvError("disk full".into()));
        }
        self.inner.put(key, value)
    }
    fn delete(&self, key: &[u8]) -> std::result::Result<(), KvError> {
        self.inner.delete(key)
    }
    fn begin(&self) -> std::result::Result<(), KvError> {
        self.inner.begin()
    }
    fn commit(&self) -> std::result::Result<(), KvError> {
        if self.fail_commit.load(Ordering::SeqCst) {
            return Err(KvError("database is locked".into()));
        }
        self.inner.commit()
    }
    fn rollback(&self) -> std::result::Result<(), KvError> {
        self.inner.rollback()
    }
}
