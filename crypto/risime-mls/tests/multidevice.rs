//! Each device is a leaf (decision 032); resync by re-add; late messages (contract §10.3).

mod common;

use common::*;
use risime_mls::{Incoming, MlsError};

#[test]
fn every_device_is_a_leaf_and_removing_one_locks_it_out() {
    let a1 = client("alice", "a1");
    let a2 = client("alice", "a2");
    let b1 = client("bob", "b1");
    group(&a1, &[&a2, &b1]);
    assert_same_epoch(&[&a1, &a2, &b1]);
    assert_eq!(a1.members(G).unwrap().len(), 3);

    let ct = b1.encrypt(G, b"to both of alice's devices").unwrap();
    assert_eq!(a1.decrypt(G, &ct).unwrap(), b"to both of alice's devices");
    assert_eq!(a2.decrypt(G, &ct).unwrap(), b"to both of alice's devices");

    // Alice logs out a2: her other device commits the removal.
    let pc = a1.remove_members(G, &[dev("alice", "a2")]).unwrap();
    a1.commit_accepted(G).unwrap();
    match b1.process(G, &pc.commit).unwrap() {
        Incoming::Commit {
            committer, removed, ..
        } => {
            assert_eq!(committer, dev("alice", "a1"));
            assert_eq!(removed, vec![dev("alice", "a2")]);
        }
        o => panic!("{o:?}"),
    }
    assert!(matches!(
        a2.process(G, &pc.commit).unwrap(),
        Incoming::Commit {
            removed_self: true,
            ..
        }
    ));
    let ct = b1.encrypt(G, b"after logout").unwrap();
    assert_eq!(a1.decrypt(G, &ct).unwrap(), b"after logout");
    assert_eq!(a2.decrypt(G, &ct), Err(MlsError::RemovedFromGroup));
}

#[test]
fn new_device_is_added_by_another_member() {
    let a1 = client("alice", "a1");
    let b1 = client("bob", "b1");
    group(&a1, &[&b1]);
    let b2 = client("bob", "b2");
    // mls_membership "added": bob's other device commits first.
    let pc = b1.add_members(G, &[kp(&b2)]).unwrap();
    deliver_commit(&b1, &pc.commit, &[&a1]);
    b2.join_from_welcome(pc.welcome.as_ref().unwrap()).unwrap();
    assert_same_epoch(&[&a1, &b1, &b2]);
    let ct = a1.encrypt(G, b"hi bob's tablet").unwrap();
    assert_eq!(b2.decrypt(G, &ct).unwrap(), b"hi bob's tablet");
}

/// A device that missed commits gets WrongEpoch; a member re-adds it (remove + add with a fresh
/// key package), and its Welcome replaces the stale local group with the same id.
#[test]
fn desynced_device_resyncs_by_re_add() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let carol = client("carol", "c1");
    group(&alice, &[&bob]);
    let pc = alice.add_members(G, &[kp(&carol)]).unwrap();
    alice.commit_accepted(G).unwrap(); // bob never gets this commit
    carol
        .join_from_welcome(pc.welcome.as_ref().unwrap())
        .unwrap();
    let ct = alice.encrypt(G, b"you missed something").unwrap();
    assert_eq!(bob.decrypt(G, &ct), Err(MlsError::WrongEpoch));

    let rm = alice.remove_members(G, &[dev("bob", "b1")]).unwrap();
    deliver_commit(&alice, &rm.commit, &[&carol]);
    let add = alice.add_members(G, &[kp(&bob)]).unwrap();
    deliver_commit(&alice, &add.commit, &[&carol]);
    let j = bob
        .join_from_welcome(add.welcome.as_ref().unwrap())
        .unwrap();
    assert_eq!(j.epoch, 4);
    assert_same_epoch(&[&alice, &bob, &carol]);
    let ct = alice.encrypt(G, b"welcome back").unwrap();
    assert_eq!(bob.decrypt(G, &ct).unwrap(), b"welcome back");
}

/// An old Welcome cannot roll an active group back.
#[test]
fn stale_welcome_does_not_replace_a_current_group() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let carol = client("carol", "c1");
    let pc = alice.create_group(G, &[kp(&bob)]).unwrap();
    alice.commit_accepted(G).unwrap();
    bob.join_from_welcome(pc.welcome.as_ref().unwrap()).unwrap();
    let add = alice.add_members(G, &[kp(&carol)]).unwrap();
    deliver_commit(&alice, &add.commit, &[&bob]);
    // Replaying the epoch-1 Welcome (its key package is gone anyway) fails without damage.
    assert!(bob.join_from_welcome(pc.welcome.as_ref().unwrap()).is_err());
    assert_eq!(bob.epoch(G).unwrap(), 2);
}

/// A message from epoch n still decrypts after up to 3 later commits; older is refused.
#[test]
fn late_messages_within_three_past_epochs() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    group(&alice, &[&bob]);
    let late_ok = alice.encrypt(G, b"sent in epoch 1").unwrap();
    // Three epoch changes: add carol, remove carol, add dave.
    let carol = client("carol", "c1");
    let pc = alice.add_members(G, &[kp(&carol)]).unwrap();
    deliver_commit(&alice, &pc.commit, &[&bob]);
    let too_late = alice.encrypt(G, b"sent in epoch 2").unwrap();
    let pc = alice.remove_members(G, &[dev("carol", "c1")]).unwrap();
    deliver_commit(&alice, &pc.commit, &[&bob]);
    let pc = alice.add_members(G, &[kp(&client("dave", "d1"))]).unwrap();
    deliver_commit(&alice, &pc.commit, &[&bob]);
    assert_eq!(bob.epoch(G).unwrap(), 4);
    assert_eq!(bob.decrypt(G, &late_ok).unwrap(), b"sent in epoch 1");
    // Two more epochs push epoch 2 out of the window.
    let pc = alice.remove_members(G, &[dev("dave", "d1")]).unwrap();
    deliver_commit(&alice, &pc.commit, &[&bob]);
    let pc = alice.add_members(G, &[kp(&client("erin", "e1"))]).unwrap();
    deliver_commit(&alice, &pc.commit, &[&bob]);
    assert_eq!(bob.epoch(G).unwrap(), 6);
    assert_eq!(bob.decrypt(G, &too_late), Err(MlsError::WrongEpoch));
}
