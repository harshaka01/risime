//! Own commits merge only after the server's 200 (contract §10.2, decision 033).

mod common;

use common::*;
use risime_mls::{Incoming, MlsError};

#[test]
fn commit_is_not_merged_until_accepted_and_messaging_continues() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let carol = client("carol", "c1");
    group(&alice, &[&bob]);
    let pc = alice.add_members(G, &[kp(&carol)]).unwrap();
    assert_eq!(pc.epoch, 1);
    assert_eq!(pc.added, vec![dev("carol", "c1")]);
    assert_eq!(alice.epoch(G).unwrap(), 1);
    // Sending while the commit is in flight stays in the current epoch.
    let ct = alice.encrypt(G, b"while pending").unwrap();
    assert_eq!(bob.decrypt(G, &ct).unwrap(), b"while pending");
    let ct = bob.encrypt(G, b"reply while pending").unwrap();
    assert_eq!(alice.decrypt(G, &ct).unwrap(), b"reply while pending");
    // A second commit must wait for the verdict on the first.
    assert_eq!(
        alice.remove_members(G, &[dev("bob", "b1")]),
        Err(MlsError::CommitPending)
    );
    assert_eq!(
        alice.add_members(G, &[kp(&client("dave", "d1"))]),
        Err(MlsError::CommitPending)
    );
    assert_eq!(deliver_commit(&alice, &pc.commit, &[&bob]), 2);
    carol
        .join_from_welcome(pc.welcome.as_ref().unwrap())
        .unwrap();
    assert_same_epoch(&[&alice, &bob, &carol]);
}

#[test]
fn rejected_commit_keeps_epoch_and_messaging() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    group(&alice, &[&bob]);
    alice.add_members(G, &[kp(&client("carol", "c1"))]).unwrap();
    alice.commit_rejected(G).unwrap();
    assert!(!alice.has_pending_commit(G).unwrap());
    assert_eq!(alice.epoch(G).unwrap(), 1);
    let ct = alice.encrypt(G, b"after 409").unwrap();
    assert_eq!(bob.decrypt(G, &ct).unwrap(), b"after 409");
    // And a new commit can be made.
    let pc = alice.add_members(G, &[kp(&client("carol", "c1"))]).unwrap();
    assert_eq!(deliver_commit(&alice, &pc.commit, &[&bob]), 2);
}

#[test]
fn verdict_without_pending_commit_is_an_error() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    group(&alice, &[&bob]);
    assert_eq!(alice.commit_accepted(G), Err(MlsError::NoPendingCommit));
    assert_eq!(alice.commit_rejected(G), Err(MlsError::NoPendingCommit));
}

/// A and B commit in the same epoch; the server accepts A (B gets 409). B processes A's commit,
/// learns its own was discarded, redoes it in the next epoch, and everyone converges.
#[test]
fn concurrent_commits_race_converges() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let carol = client("carol", "c1");
    let dave = client("dave", "d1");
    group(&alice, &[&bob]);

    let a = alice.add_members(G, &[kp(&carol)]).unwrap();
    let b = bob.add_members(G, &[kp(&dave)]).unwrap();
    assert_eq!((a.epoch, b.epoch), (1, 1));

    // Server: A wins epoch 1.
    alice.commit_accepted(G).unwrap();
    // B: 409 -> clear, catch up, re-evaluate (contract §10.2).
    bob.commit_rejected(G).unwrap();
    match bob.process(G, &a.commit).unwrap() {
        Incoming::Commit {
            epoch,
            added,
            discarded_own_pending,
            ..
        } => {
            assert_eq!(epoch, 2);
            assert_eq!(added, vec![dev("carol", "c1")]);
            assert!(!discarded_own_pending);
        }
        o => panic!("{o:?}"),
    }
    carol
        .join_from_welcome(a.welcome.as_ref().unwrap())
        .unwrap();

    let b2 = bob.add_members(G, &[kp(&dave)]).unwrap();
    assert_eq!(b2.epoch, 2);
    deliver_commit(&bob, &b2.commit, &[&alice, &carol]);
    dave.join_from_welcome(b2.welcome.as_ref().unwrap())
        .unwrap();
    assert_same_epoch(&[&alice, &bob, &carol, &dave]);
    let ct = dave.encrypt(G, b"hi all").unwrap();
    for c in [&alice, &bob, &carol] {
        assert_eq!(c.decrypt(G, &ct).unwrap(), b"hi all");
    }
}

/// The loser may also process the winner's commit *without* clearing first: its pending commit is
/// discarded and reported.
#[test]
fn foreign_commit_discards_own_pending() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let carol = client("carol", "c1");
    let dave = client("dave", "d1");
    group(&alice, &[&bob]);
    let a = alice.add_members(G, &[kp(&carol)]).unwrap();
    bob.add_members(G, &[kp(&dave)]).unwrap();
    alice.commit_accepted(G).unwrap();
    match bob.process(G, &a.commit).unwrap() {
        Incoming::Commit {
            discarded_own_pending,
            ..
        } => assert!(discarded_own_pending),
        o => panic!("{o:?}"),
    }
    assert!(!bob.has_pending_commit(G).unwrap());
    assert_eq!(bob.commit_accepted(G), Err(MlsError::NoPendingCommit));
    assert_same_epoch(&[&alice, &bob]);
}

/// Two creators race at epoch 0 (contract §10.2). The loser discards its group and joins from the
/// winner's Welcome, either after `commit_rejected` or directly (the local epoch-0 group is
/// replaced).
#[test]
fn creation_race_loser_joins_winner() {
    for reject_first in [true, false] {
        let alice = client("alice", "a1");
        let bob = client("bob", "b1");
        let a = alice.create_group(G, &[kp(&bob)]).unwrap();
        bob.create_group(G, &[kp(&alice)]).unwrap();
        alice.commit_accepted(G).unwrap();
        if reject_first {
            bob.commit_rejected(G).unwrap();
            assert!(!bob.has_group(G).unwrap());
        }
        let j = bob.join_from_welcome(a.welcome.as_ref().unwrap()).unwrap();
        assert_eq!(j.epoch, 1);
        assert_same_epoch(&[&alice, &bob]);
        let ct = bob.encrypt(G, b"won?").unwrap();
        assert_eq!(alice.decrypt(G, &ct).unwrap(), b"won?");
    }
}

/// A local group at epoch >= 1 is never silently replaced by creating again.
#[test]
fn create_over_existing_group_fails() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    group(&alice, &[&bob]);
    assert_eq!(
        alice.create_group(G, &[kp(&client("carol", "c1"))]),
        Err(MlsError::GroupExists)
    );
}
