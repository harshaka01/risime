//! State survives restarts; every call is one nested transaction (decisions 012 §6 and 033).

mod common;

use std::sync::Arc;
use std::sync::atomic::Ordering;

use common::*;
use risime_mls::{Client, KvStore, MemoryKvStore, MlsError};

#[test]
fn reopened_client_continues_the_conversation() {
    let store = Arc::new(MemoryKvStore::new());
    let alice = client("alice", "a1");
    let (bob_key, bob_att) = {
        let bob = client_on(store.clone(), "bob", "b1");
        group(&alice, &[&bob]);
        let ct = alice.encrypt(G, b"before restart").unwrap();
        assert_eq!(bob.decrypt(G, &ct).unwrap(), b"before restart");
        (
            bob.signature_public_key(),
            bob.attestation().unwrap().to_string(),
        )
    };
    // "Process restart": a new Client on the same store.
    let bob = Client::open(store.clone(), dev("bob", "b1"), attestor().anchors()).unwrap();
    assert_eq!(bob.signature_public_key(), bob_key);
    assert_eq!(bob.attestation(), Some(bob_att.as_str()));
    assert_eq!(bob.epoch(G).unwrap(), 1);
    let ct = alice.encrypt(G, b"after restart").unwrap();
    assert_eq!(bob.decrypt(G, &ct).unwrap(), b"after restart");
    let ct = bob.encrypt(G, b"back").unwrap();
    assert_eq!(alice.decrypt(G, &ct).unwrap(), b"back");
    // Key packages made before the restart are still usable.
    assert!(!bob.generate_key_packages(2).unwrap().is_empty());
    assert_eq!(store.depth(), 0);
}

#[test]
fn a_store_belongs_to_one_device() {
    let store = Arc::new(MemoryKvStore::new());
    client_on(store.clone(), "bob", "b1");
    assert!(matches!(
        Client::open(store, dev("bob", "b2"), attestor().anchors()),
        Err(MlsError::Storage(_))
    ));
}

/// A storage failure mid-call rolls back: the ratchet is not advanced, so the same message can be
/// processed again once storage works.
#[test]
fn storage_failure_rolls_back_and_retry_succeeds() {
    let faulty = Arc::new(FaultyKv::new());
    let alice = client("alice", "a1");
    let bob = client_on(faulty.clone(), "bob", "b1");
    group(&alice, &[&bob]);
    let ct = alice.encrypt(G, b"retry me").unwrap();

    faulty.fail_commit.store(true, Ordering::SeqCst);
    assert!(matches!(bob.decrypt(G, &ct), Err(MlsError::Storage(_))));
    faulty.fail_commit.store(false, Ordering::SeqCst);
    assert_eq!(faulty.inner.depth(), 0);

    faulty.fail_put.store(true, Ordering::SeqCst);
    assert!(bob.decrypt(G, &ct).is_err());
    faulty.fail_put.store(false, Ordering::SeqCst);
    assert_eq!(faulty.inner.depth(), 0);

    assert_eq!(bob.decrypt(G, &ct).unwrap(), b"retry me");
}

/// Decision 033: Kotlin opens the outer transaction (decrypt → insert → cursor). If it rolls
/// back, the MLS state rolls back with it, and the message decrypts again. Once committed, a
/// replay fails (the key was used).
#[test]
fn outer_transaction_controls_durability() {
    let store = Arc::new(MemoryKvStore::new());
    let alice = client("alice", "a1");
    let bob = client_on(store.clone(), "bob", "b1");
    group(&alice, &[&bob]);
    let ct = alice.encrypt(G, b"exactly once").unwrap();

    store.begin().unwrap();
    assert_eq!(bob.decrypt(G, &ct).unwrap(), b"exactly once");
    assert_eq!(
        store.depth(),
        1,
        "the core never closes the outer transaction"
    );
    store.rollback().unwrap(); // e.g. the plaintext insert failed

    store.begin().unwrap();
    assert_eq!(bob.decrypt(G, &ct).unwrap(), b"exactly once");
    store.commit().unwrap();

    assert!(
        bob.decrypt(G, &ct).is_err(),
        "replay after commit must fail"
    );
    assert_eq!(store.depth(), 0);
}

#[test]
fn failed_calls_leave_no_open_transaction() {
    let store = Arc::new(MemoryKvStore::new());
    let bob = client_on(store.clone(), "bob", "b1");
    let _ = bob.encrypt(b"nope", b"x");
    let _ = bob.join_from_welcome(b"garbage");
    let _ = bob.create_group(G, &[b"garbage".to_vec()]);
    let _ = bob.commit_accepted(G);
    assert_eq!(store.depth(), 0);
    assert!(!bob.has_group(G).unwrap());
}
