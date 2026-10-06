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

#[test]
fn group_api_crosses_the_ffi() {
    use uniffi_risime::{GroupMeta, group_limits, key_package_supports_groups};
    let a = TestAttestor::from_seed(vec![3; 32]).unwrap();
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
    let limits = group_limits();
    assert_eq!(limits.max_inline_bytes, 64 * 1024);
    assert_eq!(limits.group_meta_extension, 0xFA01);
    let kp = bob.generate_key_packages(1).unwrap();
    assert!(key_package_supports_groups(kp[0].clone()).unwrap());

    let g = b"grp:ffi#1".to_vec();
    let meta = |name: &str, admins: &[&str]| GroupMeta {
        name: name.into(),
        icon_json: None,
        admins: admins.iter().map(|s| s.to_string()).collect(),
    };
    let gc = alice
        .create_group_with_meta(g.clone(), kp, meta("FFI", &["alice"]))
        .unwrap();
    assert_eq!(gc.commit_size, gc.commit.len() as u64);
    assert!(!gc.commit_needs_ref && !gc.welcome_needs_ref);
    alice.commit_accepted(g.clone()).unwrap();
    bob.join_from_welcome(gc.welcome.unwrap()).unwrap();
    assert_eq!(bob.group_meta(g.clone()).unwrap().unwrap().name, "FFI");

    // Non-admin Bob can't add Carol.
    assert!(matches!(
        bob.change_members(g.clone(), carol.generate_key_packages(1).unwrap(), vec![]),
        Err(RisiMlsError::PolicyViolation(_))
    ));
    let rn = alice
        .update_group_meta(g.clone(), meta("FFI 2", &["alice", "bob"]))
        .unwrap();
    assert!(rn.meta_changed);
    alice.commit_accepted(g.clone()).unwrap();
    let up = bob.process_commits(g.clone(), vec![rn.commit]).unwrap();
    assert!(matches!(
        up.applied[0],
        IncomingMessage::Commit {
            meta_changed: true,
            ..
        }
    ));
    assert_eq!(
        bob.group_meta(g.clone()).unwrap().unwrap().admins,
        vec!["alice", "bob"]
    );
    // Now an admin, Bob adds Carol.
    let add = bob
        .change_members(g.clone(), carol.generate_key_packages(1).unwrap(), vec![])
        .unwrap();
    bob.commit_accepted(g.clone()).unwrap();
    alice.process(g.clone(), add.commit).unwrap();
    carol.join_from_welcome(add.welcome.unwrap()).unwrap();
    let rm = alice.remove_users(g.clone(), vec!["carol".into()]).unwrap();
    alice.commit_rejected(g.clone()).unwrap();
    assert_eq!(rm.removed.len(), 1);
    let su = carol.self_update(g.clone()).unwrap();
    carol.commit_accepted(g.clone()).unwrap();
    alice.process(g.clone(), su.commit.clone()).unwrap();
    bob.process(g.clone(), su.commit).unwrap();
    assert_eq!(
        alice.epoch_authenticator(g.clone()).unwrap(),
        carol.epoch_authenticator(g.clone()).unwrap()
    );
    // Bad icon JSON is refused.
    let mut bad = meta("x", &["alice"]);
    bad.icon_json = Some("{".into());
    assert!(matches!(
        alice.update_group_meta(g, bad),
        Err(RisiMlsError::Malformed(_))
    ));
}

/// Contract v1.12 §15 through the FFI: delete AAD, `encryptWithAad`, `processDetailed`
/// (`senderIsAdmin`, AAD, leaf), `adminsAtEpoch`, `purgeGroup`.
#[test]
fn delete_controls_cross_the_ffi() {
    let attestor = TestAttestor::from_seed(vec![9; 32]).unwrap();
    let jwks = vec![attestor.public_jwk()];
    let open = |user: &str| {
        let c = MlsClient::open(
            InMemoryKvStore::new(),
            user.into(),
            "d1".into(),
            jwks.clone(),
        )
        .unwrap();
        let jws = attestor
            .attest(
                user.into(),
                "d1".into(),
                c.signature_public_key().unwrap(),
                1,
            )
            .unwrap();
        c.set_attestation(jws).unwrap();
        c
    };
    let (alice, bob) = (open("alice"), open("bob"));
    let g = b"grp:0b6d1c1e-2a3b-4c5d-8e9f-0a1b2c3d4e5f#1".to_vec();
    let meta = uniffi_risime::GroupMeta {
        name: "FFI".into(),
        icon_json: None,
        admins: vec!["alice".into()],
    };
    let gc = alice
        .create_group_with_meta(g.clone(), bob.generate_key_packages(1).unwrap(), meta)
        .unwrap();
    alice.commit_accepted(g.clone()).unwrap();
    bob.join_from_welcome(gc.welcome.unwrap()).unwrap();

    let t = vec![
        "C1A2B3F0-A0B1-11F0-8000-0242AC120002".to_string(),
        "c1a2b3e1-a0b1-11f0-8000-0242ac120002".to_string(),
    ];
    let aad = uniffi_risime::delete_aad_encode(t).unwrap();
    let ct = alice
        .encrypt_with_aad(g.clone(), b"{}".to_vec(), aad.clone())
        .unwrap();
    let p = bob.process_detailed(g.clone(), ct).unwrap();
    assert!(matches!(
        p.incoming,
        IncomingMessage::Application { epoch: 1, .. }
    ));
    let d = p.application.unwrap();
    assert_eq!(d.sender_is_admin, Some(true));
    assert_eq!(d.sender_leaf, 0);
    assert_eq!(
        uniffi_risime::delete_aad_decode(d.authenticated_data).unwrap(),
        vec![
            "c1a2b3e1-a0b1-11f0-8000-0242ac120002",
            "c1a2b3f0-a0b1-11f0-8000-0242ac120002"
        ]
    );
    assert!(matches!(
        uniffi_risime::delete_aad_decode(aad[..10].to_vec()),
        Err(RisiMlsError::Malformed(_))
    ));
    assert_eq!(
        bob.admins_at_epoch(g.clone(), 1).unwrap(),
        Some(vec!["alice".to_string()])
    );
    bob.purge_group(g.clone()).unwrap();
    bob.purge_group(g.clone()).unwrap();
    assert!(!bob.has_group(g).unwrap());
}
