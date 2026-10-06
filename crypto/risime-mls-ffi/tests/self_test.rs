use std::sync::Arc;

use uniffi_risime::{
    InMemoryKvStore, IncomingMessage, KvStore, KvStoreError, MlsClient, RisiMlsError, TestAttestor,
};

#[test]
fn self_test_passes_on_host() {
    let out = uniffi_risime::self_test().expect("self test");
    assert!(out.starts_with("ok: epoch "), "{out}");
}

#[test]
fn errors_cross_the_ffi_mapping() {
    let c = MlsClient::open(InMemoryKvStore::new(), "u".into(), "d".into(), vec![]).unwrap();
    assert!(matches!(
        c.encrypt(b"nope".to_vec(), b"m".to_vec()),
        Err(RisiMlsError::UnknownGroup)
    ));
    assert!(matches!(
        c.generate_key_packages(1),
        Err(RisiMlsError::MissingAttestation)
    ));
    assert!(matches!(
        MlsClient::open(InMemoryKvStore::new(), "u/x".into(), "d".into(), vec![]),
        Err(RisiMlsError::Malformed(_))
    ));
    assert!(matches!(
        MlsClient::open(
            InMemoryKvStore::new(),
            "u".into(),
            "d".into(),
            vec!["{}".into()]
        ),
        Err(RisiMlsError::Malformed(_))
    ));
}

/// A "foreign" store written against the FFI trait (what Kotlin implements), with a failure
/// switch: errors surface as `RisiMlsError::Storage` and roll back.
struct FlakyStore {
    inner: Arc<InMemoryKvStore>,
    fail: std::sync::atomic::AtomicBool,
}

impl KvStore for FlakyStore {
    fn get(&self, key: Vec<u8>) -> Result<Option<Vec<u8>>, KvStoreError> {
        self.inner.get(key)
    }
    fn put(&self, key: Vec<u8>, value: Vec<u8>) -> Result<(), KvStoreError> {
        if self.fail.load(std::sync::atomic::Ordering::SeqCst) {
            return Err(KvStoreError::Failed {
                reason: "SQLITE_FULL".into(),
            });
        }
        self.inner.put(key, value)
    }
    fn delete(&self, key: Vec<u8>) -> Result<(), KvStoreError> {
        self.inner.delete(key)
    }
    fn begin(&self) -> Result<(), KvStoreError> {
        self.inner.begin()
    }
    fn commit(&self) -> Result<(), KvStoreError> {
        self.inner.commit()
    }
    fn rollback(&self) -> Result<(), KvStoreError> {
        self.inner.rollback()
    }
}

#[test]
fn foreign_store_failures_map_to_storage_and_roll_back() {
    let a = TestAttestor::from_seed(vec![1; 32]).unwrap();
    let jwks = vec![a.public_jwk()];
    let mem = InMemoryKvStore::new();
    let store = Arc::new(FlakyStore {
        inner: mem.clone(),
        fail: false.into(),
    });
    let c = MlsClient::open(store.clone(), "u".into(), "d".into(), jwks).unwrap();
    c.set_attestation(
        a.attest("u".into(), "d".into(), c.signature_public_key().unwrap(), 1)
            .unwrap(),
    )
    .unwrap();
    store.fail.store(true, std::sync::atomic::Ordering::SeqCst);
    assert!(matches!(
        c.generate_key_packages(3),
        Err(RisiMlsError::Storage(_))
    ));
    assert_eq!(mem.depth(), 0);
    store.fail.store(false, std::sync::atomic::Ordering::SeqCst);
    assert_eq!(c.generate_key_packages(3).unwrap().len(), 3);
}

#[test]
fn incoming_commit_fields_cross_the_ffi() {
    let a = TestAttestor::from_seed(vec![2; 32]).unwrap();
    let jwks = vec![a.public_jwk()];
    let mk = |u: &str| {
        let c =
            MlsClient::open(InMemoryKvStore::new(), u.into(), "p".into(), jwks.clone()).unwrap();
        c.set_attestation(
            a.attest(u.into(), "p".into(), c.signature_public_key().unwrap(), 1)
                .unwrap(),
        )
        .unwrap();
        c
    };
    let (alice, bob, carol) = (mk("alice"), mk("bob"), mk("carol"));
    let g = b"g#1".to_vec();
    let pc = alice
        .create_group(g.clone(), bob.generate_key_packages(1).unwrap())
        .unwrap();
    alice.commit_accepted(g.clone()).unwrap();
    let j = bob.join_from_welcome(pc.welcome.unwrap()).unwrap();
    assert_eq!(j.members.len(), 2);
    let add = alice
        .add_members(g.clone(), carol.generate_key_packages(1).unwrap())
        .unwrap();
    alice.commit_accepted(g.clone()).unwrap();
    match bob.process(g.clone(), add.commit).unwrap() {
        IncomingMessage::Commit {
            epoch,
            committer,
            added,
            ..
        } => {
            assert_eq!(epoch, 2);
            assert_eq!(committer.user_id, "alice");
            assert_eq!(added[0].user_id, "carol");
        }
        o => panic!("{o:?}"),
    }
}
