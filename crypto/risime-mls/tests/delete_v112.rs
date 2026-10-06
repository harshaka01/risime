//! Contract v1.12 §15 (delete messages and chats, decision 047; crypto review R1, R2, R5, S1):
//! - the attested sender, its leaf and the AAD on decrypt (`process_detailed`);
//! - the per-epoch admin record and `sender_is_admin` at the message's epoch;
//! - `encrypt_with_aad` and the canonical delete AAD (tampering fails decryption);
//! - `maximum_forward_distance` 20 000, including the migration of a stored group;
//! - `purge_group`.

mod common;

use std::sync::Arc;

use common::*;
use risime_mls::{
    Client, GroupMeta, Incoming, KvStore, MAX_PAST_EPOCHS, MemoryKvStore, MlsError,
    decode_delete_aad, encode_delete_aad,
};

const GG: &[u8] = b"grp:5b2f0a4e-1c2d-4e5f-8a9b-0c1d2e3f4a5b#1";
const T1: &str = "c1a2b3e1-a0b1-11f0-8000-0242ac120002";
const T2: &str = "c1a2b3f0-a0b1-11f0-8000-0242ac120002";

fn meta(admins: &[&str]) -> GroupMeta {
    GroupMeta::new(
        "Delete tests",
        admins.iter().map(|s| s.to_string()).collect(),
    )
}

fn delete_aad() -> Vec<u8> {
    encode_delete_aad(&[T2.into(), T1.into()]).unwrap()
}

/// `creator` (admin) creates [`GG`] with `others`; all join at epoch 1.
fn make_group(creator: &Client, others: &[&Client]) {
    let kps: Vec<Vec<u8>> = others.iter().map(|c| kp(c)).collect();
    let gc = creator
        .create_group_with_meta(GG, &kps, &meta(&["alice"]))
        .unwrap();
    assert_eq!(creator.commit_accepted(GG).unwrap(), 1);
    for o in others {
        o.join_from_welcome(gc.welcome.as_ref().unwrap()).unwrap();
    }
}

fn admin_flag(c: &Client, ct: &[u8]) -> Option<bool> {
    c.process_detailed(GG, ct)
        .unwrap()
        .application
        .unwrap()
        .sender_is_admin
}

#[test]
fn detailed_decrypt_reports_sender_leaf_and_aad() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    make_group(&alice, &[&bob]);

    let ct = alice
        .encrypt_with_aad(GG, br#"{"v":1,"type":"delete"}"#, &delete_aad())
        .unwrap();
    let p = bob.process_detailed(GG, &ct).unwrap();
    assert_eq!(
        p.incoming,
        Incoming::Application {
            sender: dev("alice", "a1"),
            plaintext: br#"{"v":1,"type":"delete"}"#.to_vec(),
            epoch: 1,
        }
    );
    let d = p.application.unwrap();
    assert_eq!(d.sender_leaf, 0);
    assert_eq!(d.sender_is_admin, Some(true));
    assert_eq!(
        decode_delete_aad(&d.authenticated_data).unwrap(),
        vec![T1, T2]
    );

    // A plain message carries an empty AAD; the old `process` is unchanged.
    let ct = bob.encrypt(GG, b"hi").unwrap();
    let d = alice
        .process_detailed(GG, &ct)
        .unwrap()
        .application
        .unwrap();
    assert!(d.authenticated_data.is_empty());
    assert_eq!((d.sender_leaf, d.sender_is_admin), (1, Some(false)));
    let ct = bob.encrypt(GG, b"again").unwrap();
    assert!(matches!(
        alice.process(GG, &ct).unwrap(),
        Incoming::Application { .. }
    ));

    // A commit has no application details.
    let gc = alice.self_update(GG).unwrap();
    alice.commit_accepted(GG).unwrap();
    let p = bob.process_detailed(GG, &gc.commit).unwrap();
    assert!(matches!(p.incoming, Incoming::Commit { .. }));
    assert!(p.application.is_none());
}

#[test]
fn dm_groups_have_no_admins() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    group(&alice, &[&bob]);
    let ct = alice.encrypt_with_aad(G, b"x", &delete_aad()).unwrap();
    let d = bob.process_detailed(G, &ct).unwrap().application.unwrap();
    assert_eq!(d.sender_is_admin, None);
    assert_eq!(d.authenticated_data, delete_aad());
    assert_eq!(bob.admins_at_epoch(G, 1).unwrap(), None);
}

#[test]
fn tampered_aad_fails_decryption() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    make_group(&alice, &[&bob]);
    let aad = delete_aad();
    let ct = alice.encrypt_with_aad(GG, b"delete", &aad).unwrap();
    let pos = ct
        .windows(aad.len())
        .position(|w| w == aad.as_slice())
        .expect("the AAD is cleartext in the PrivateMessage");
    let mut bad = ct.clone();
    bad[pos + 5] ^= 0x01;
    assert!(matches!(
        bob.process_detailed(GG, &bad),
        Err(MlsError::DecryptionFailed(_))
    ));
    // The genuine message still decrypts (the failure consumed nothing).
    let d = bob.process_detailed(GG, &ct).unwrap().application.unwrap();
    assert_eq!(d.authenticated_data, aad);
}

/// R2: "admin at the message's epoch", not "still admin now".
#[test]
fn sender_is_admin_is_evaluated_at_the_message_epoch() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let carol = client("carol", "c1");
    make_group(&alice, &[&bob, &carol]);

    // Epoch 1: alice is the admin.
    let d1 = alice.encrypt_with_aad(GG, b"d1", &delete_aad()).unwrap();
    let b1 = bob.encrypt(GG, b"b1").unwrap();
    // Epoch 1 → 2: alice hands the admin role to bob (demoting herself).
    let gc = alice.update_group_meta(GG, &meta(&["bob"])).unwrap();
    alice.commit_accepted(GG).unwrap();
    bob.process(GG, &gc.commit).unwrap();
    let d2 = alice.encrypt_with_aad(GG, b"d2", &delete_aad()).unwrap();
    let b2 = bob.encrypt(GG, b"b2").unwrap();
    // Epoch 2 → 3: a key rotation.
    let su = bob.self_update(GG).unwrap();
    bob.commit_accepted(GG).unwrap();
    alice.process(GG, &su.commit).unwrap();

    // Carol catches up first, then reads the parked messages of epochs 1 and 2.
    let up = carol
        .process_commits(GG, &[gc.commit.clone(), su.commit.clone()])
        .unwrap();
    assert_eq!(up.epoch, 3);
    assert_eq!(admin_flag(&carol, &d1), Some(true), "alice was admin at 1");
    assert_eq!(admin_flag(&carol, &d2), Some(false), "alice not admin at 2");
    assert_eq!(admin_flag(&carol, &b1), Some(false));
    assert_eq!(admin_flag(&carol, &b2), Some(true));

    // Every epoch on every path was recorded: creator, Welcome, peer commit, catch-up, own.
    for c in [&alice, &bob, &carol] {
        assert_eq!(
            c.admins_at_epoch(GG, 1).unwrap(),
            Some(vec!["alice".into()])
        );
        assert_eq!(c.admins_at_epoch(GG, 2).unwrap(), Some(vec!["bob".into()]));
        assert_eq!(c.admins_at_epoch(GG, 3).unwrap(), Some(vec!["bob".into()]));
    }
    assert_eq!(
        alice.admins_at_epoch(GG, 0).unwrap(),
        Some(vec!["alice".into()])
    );
    assert_eq!(carol.admins_at_epoch(GG, 0).unwrap(), None, "joined at 1");
}

#[test]
fn catch_up_records_each_intermediate_epoch_and_prunes() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    make_group(&alice, &[&bob]);
    // Bob is offline for three meta commits, then catches up with all of them at once.
    let mut commits = Vec::new();
    for admins in [&["alice", "bob"][..], &["alice"], &["bob", "alice"]] {
        let gc = alice.update_group_meta(GG, &meta(admins)).unwrap();
        alice.commit_accepted(GG).unwrap();
        commits.push(gc.commit);
    }
    let up = bob.process_commits(GG, &commits).unwrap();
    assert_eq!(up.epoch, 4);
    for (e, admins) in [
        (2u64, vec!["alice", "bob"]),
        (3, vec!["alice"]),
        (4, vec!["bob", "alice"]),
    ] {
        let want: Vec<String> = admins.into_iter().map(String::from).collect();
        assert_eq!(
            bob.admins_at_epoch(GG, e).unwrap(),
            Some(want.clone()),
            "bob @{e}"
        );
        assert_eq!(
            alice.admins_at_epoch(GG, e).unwrap(),
            Some(want),
            "alice @{e}"
        );
    }

    // Pruning: the current epoch plus MAX_PAST_EPOCHS.
    for _ in 0..4 {
        let gc = alice.self_update(GG).unwrap();
        alice.commit_accepted(GG).unwrap();
        bob.process(GG, &gc.commit).unwrap();
    }
    let now = alice.epoch(GG).unwrap();
    assert_eq!(now, 8);
    for c in [&alice, &bob] {
        for e in 0..=now {
            let kept = c.admins_at_epoch(GG, e).unwrap().is_some();
            assert_eq!(kept, e >= now - MAX_PAST_EPOCHS as u64, "epoch {e}");
        }
    }
}

/// A `grp:` group without a record for the message's epoch: a delete (non-empty AAD) fails
/// loudly with `Malformed`; a plain message gets `None`. Nothing is consumed by the failure.
#[test]
fn missing_admin_record() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    make_group(&alice, &[&bob]);
    let d1 = alice.encrypt_with_aad(GG, b"d1", &delete_aad()).unwrap();
    let m1 = alice.encrypt(GG, b"m1").unwrap();
    let gc = alice.self_update(GG).unwrap();
    alice.commit_accepted(GG).unwrap();
    bob.process(GG, &gc.commit).unwrap();
    // As if bob's state predates v1.12: no admin records; the load migrates epoch 2 only.
    bob.downgrade_group_config_for_tests(GG).unwrap();
    assert!(matches!(
        bob.process_detailed(GG, &d1),
        Err(MlsError::Malformed(_))
    ));
    assert_eq!(
        bob.admins_at_epoch(GG, 2).unwrap(),
        Some(vec!["alice".into()])
    );
    let d = bob.process_detailed(GG, &m1).unwrap().application.unwrap();
    assert_eq!(d.sender_is_admin, None);
    assert_eq!(bob.decrypt(GG, &d1).unwrap(), b"d1");
}

/// R5: a sender's later message still decrypts after a gap of more than 1000 generations.
#[test]
fn forward_gap_over_1000_decrypts() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    make_group(&alice, &[&bob]);
    for i in 0..1500u32 {
        alice.encrypt(GG, &i.to_be_bytes()).unwrap();
    }
    let ct = alice.encrypt(GG, b"after the gap").unwrap();
    assert_eq!(bob.decrypt(GG, &ct).unwrap(), b"after the gap");
}

/// R5 migration: a group stored under the old configuration (forward distance 1000) is migrated
/// on load and accepts a 5 000-generation jump.
#[test]
fn stored_group_is_migrated_and_accepts_a_5000_jump() {
    let alice = client("alice", "a1");
    let bob_store = Arc::new(MemoryKvStore::new());
    let bob = client_on(bob_store.clone(), "bob", "b1");
    group(&alice, &[&bob]);
    bob.downgrade_group_config_for_tests(G).unwrap();
    drop(bob);
    for i in 0..5000u32 {
        alice.encrypt(G, &i.to_be_bytes()).unwrap();
    }
    let ct = alice.encrypt(G, b"5000 later").unwrap();
    let bob = client_on(bob_store.clone(), "bob", "b1");
    assert_eq!(bob.decrypt(G, &ct).unwrap(), b"5000 later");
    assert_eq!(bob_store.depth(), 0);
}

#[test]
fn purge_group_removes_all_core_state() {
    let alice = client("alice", "a1");
    let bob_store = Arc::new(MemoryKvStore::new());
    let bob = client_on(bob_store.clone(), "bob", "b1");
    make_group(&alice, &[&bob]);
    let admins_index = [b"risime/admins/".as_slice(), GG].concat();
    let admins_e1 = [admins_index.as_slice(), b"/1"].concat();
    assert!(bob_store.get(&admins_e1).unwrap().is_some());

    bob.purge_group(GG).unwrap();
    assert!(!bob.has_group(GG).unwrap());
    assert!(bob_store.get(&admins_index).unwrap().is_none());
    assert!(bob_store.get(&admins_e1).unwrap().is_none());
    assert!(matches!(
        bob.admins_at_epoch(GG, 1),
        Err(MlsError::UnknownGroup)
    ));
    bob.purge_group(GG).unwrap(); // idempotent

    // delete_group drops the admin records too.
    alice.delete_group(GG).unwrap();
    assert!(!alice.has_group(GG).unwrap());
}
