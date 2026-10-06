//! Core E2EE properties (a–d) with the v1.7 API: attested device identities, and commits pending
//! until the server accepts them.

mod common;

use common::*;
use openmls::prelude::tls_codec::Deserialize;
use openmls::prelude::{MlsMessageBodyIn, MlsMessageIn};
use risime_mls::{Incoming, MlsError};

// (a) group creation
#[test]
fn a_create_group() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let pc = alice.create_group(G, &[kp(&bob)]).unwrap();
    assert_eq!(pc.epoch, 0);
    assert_eq!(pc.added, vec![dev("bob", "b1")]);
    assert!(pc.removed.is_empty());
    assert!(pc.welcome.is_some());
    // Not merged until the server says 200.
    assert_eq!(alice.epoch(G).unwrap(), 0);
    assert!(alice.has_pending_commit(G).unwrap());
    assert_eq!(alice.commit_accepted(G).unwrap(), 1);
    assert!(!alice.has_pending_commit(G).unwrap());
    let ids: Vec<String> = alice
        .members(G)
        .unwrap()
        .iter()
        .map(|m| m.device().identity())
        .collect();
    assert_eq!(ids, vec!["alice/a1", "bob/b1"]);
    assert!(alice.is_active(G).unwrap());
    assert!(alice.has_group(G).unwrap());
}

// (b) join from the Welcome; both sides agree on the epoch and its secrets
#[test]
fn b_add_member_join_from_welcome_same_epoch() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let pc = alice.create_group(G, &[kp(&bob)]).unwrap();
    alice.commit_accepted(G).unwrap();
    let joined = bob.join_from_welcome(pc.welcome.as_ref().unwrap()).unwrap();
    assert_eq!(joined.group_id, G);
    assert_eq!(joined.epoch, 1);
    assert_eq!(joined.members, alice.members(G).unwrap());
    assert_eq!(bob.epoch(G).unwrap(), 1);
    assert_same_epoch(&[&alice, &bob]);
    let bob_leaf = &joined.members[1];
    assert_eq!(bob_leaf.signature_key, bob.signature_public_key());
}

// (c) encrypt -> decrypt in both directions, with the exact plaintext and sender
#[test]
fn c_encrypt_decrypt_both_directions() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    group(&alice, &[&bob]);

    let hello = "Hi Bob — commitment: ship 0.3 by Friday ✓".as_bytes();
    let ct = alice.encrypt(G, hello).unwrap();
    assert!(!ct.windows(hello.len()).any(|w| w == hello));
    assert_eq!(
        bob.process(G, &ct).unwrap(),
        Incoming::Application {
            sender: dev("alice", "a1"),
            plaintext: hello.to_vec(),
            epoch: 1
        }
    );
    let ct = bob.encrypt(G, b"Hi Alice, agreed.").unwrap();
    assert_eq!(alice.decrypt(G, &ct).unwrap(), b"Hi Alice, agreed.");
    for i in 0..5u8 {
        let m = vec![i; 1 + i as usize];
        assert_eq!(bob.decrypt(G, &alice.encrypt(G, &m).unwrap()).unwrap(), m);
    }
}

// Contract §10.0: commits are PublicMessages, application messages PrivateMessages.
#[test]
fn wire_formats_match_the_contract() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let pc = alice.create_group(G, &[kp(&bob)]).unwrap();
    let body = |b: &[u8]| MlsMessageIn::tls_deserialize_exact(b).unwrap().extract();
    assert!(matches!(
        body(&pc.commit),
        MlsMessageBodyIn::PublicMessage(_)
    ));
    assert!(matches!(
        body(pc.welcome.as_ref().unwrap()),
        MlsMessageBodyIn::Welcome(_)
    ));
    alice.commit_accepted(G).unwrap();
    let ct = alice.encrypt(G, b"x").unwrap();
    assert!(matches!(body(&ct), MlsMessageBodyIn::PrivateMessage(_)));
    // decrypt() refuses handshakes
    let carol = client("carol", "c1");
    bob.join_from_welcome(pc.welcome.as_ref().unwrap()).unwrap();
    let add = alice.add_members(G, &[kp(&carol)]).unwrap();
    assert_eq!(
        bob.decrypt(G, &add.commit),
        Err(MlsError::NotApplicationMessage)
    );
}

// (d) after removal the removed member cannot decrypt; the others still can
#[test]
fn d_removed_member_cannot_decrypt_new_messages() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let carol = client("carol", "c1");
    group(&alice, &[&bob, &carol]);

    let pc = alice.remove_members(G, &[dev("bob", "b1")]).unwrap();
    assert_eq!(pc.removed, vec![dev("bob", "b1")]);
    assert!(pc.welcome.is_none());
    alice.commit_accepted(G).unwrap();
    match carol.process(G, &pc.commit).unwrap() {
        Incoming::Commit {
            epoch,
            committer,
            removed,
            removed_self,
            ..
        } => {
            assert_eq!(epoch, 2);
            assert_eq!(committer, dev("alice", "a1"));
            assert_eq!(removed, vec![dev("bob", "b1")]);
            assert!(!removed_self);
        }
        other => panic!("{other:?}"),
    }
    match bob.process(G, &pc.commit).unwrap() {
        Incoming::Commit { removed_self, .. } => assert!(removed_self),
        other => panic!("{other:?}"),
    }
    assert!(!bob.is_active(G).unwrap());

    let ct = alice.encrypt(G, b"post-removal secret").unwrap();
    assert_eq!(carol.decrypt(G, &ct).unwrap(), b"post-removal secret");
    assert_eq!(bob.decrypt(G, &ct), Err(MlsError::RemovedFromGroup));
    assert_eq!(
        bob.encrypt(G, b"still here?"),
        Err(MlsError::RemovedFromGroup)
    );
    let ct = carol.encrypt(G, b"just us now").unwrap();
    assert_eq!(alice.decrypt(G, &ct).unwrap(), b"just us now");

    // The removed device wipes the group.
    bob.delete_group(G).unwrap();
    assert!(!bob.has_group(G).unwrap());
    assert_eq!(bob.decrypt(G, &ct), Err(MlsError::UnknownGroup));
}

// (d') a removed member that ignores its removal commit has no keys for the new epoch
#[test]
fn d_removed_member_ignoring_commit_cannot_decrypt() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let carol = client("carol", "c1");
    group(&alice, &[&bob, &carol]);
    let pc = alice.remove_members(G, &[dev("bob", "b1")]).unwrap();
    deliver_commit(&alice, &pc.commit, &[&carol]);
    let ct = alice.encrypt(G, b"post-removal secret").unwrap();
    assert_eq!(carol.decrypt(G, &ct).unwrap(), b"post-removal secret");
    assert_eq!(bob.epoch(G).unwrap(), 1);
    assert_eq!(bob.decrypt(G, &ct), Err(MlsError::WrongEpoch));
}

#[test]
fn tampered_ciphertext_is_rejected() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    group(&alice, &[&bob]);
    let mut ct = alice.encrypt(G, b"integrity matters").unwrap();
    let last = ct.len() - 1;
    ct[last] ^= 0x01;
    assert!(matches!(
        bob.decrypt(G, &ct),
        Err(MlsError::DecryptionFailed(_))
    ));
    let ct = alice.encrypt(G, b"next").unwrap();
    assert_eq!(bob.decrypt(G, &ct).unwrap(), b"next");
}

#[test]
fn tampered_commit_is_rejected_and_state_unchanged() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let carol = client("carol", "c1");
    group(&alice, &[&bob]);
    let pc = alice.add_members(G, &[kp(&carol)]).unwrap();
    let mut bad = pc.commit.clone();
    let last = bad.len() - 1;
    bad[last] ^= 0x01;
    assert!(bob.process(G, &bad).is_err());
    assert_eq!(bob.epoch(G).unwrap(), 1);
    alice.commit_accepted(G).unwrap();
    bob.process(G, &pc.commit).unwrap();
    assert_same_epoch(&[&alice, &bob]);
}

#[test]
fn welcome_for_someone_else_is_rejected() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let mallory = client("mallory", "m1");
    let pc = alice.create_group(G, &[kp(&bob)]).unwrap();
    assert!(matches!(
        mallory.join_from_welcome(pc.welcome.as_ref().unwrap()),
        Err(MlsError::Welcome(_))
    ));
    assert!(!mallory.has_group(G).unwrap());
}

#[test]
fn invalid_key_package_is_rejected() {
    let alice = client("alice", "a1");
    assert!(matches!(
        alice.create_group(G, &[b"not a key package".to_vec()]),
        Err(MlsError::Malformed(_))
    ));
    let mut k = kp(&client("bob", "b1"));
    let last = k.len() - 1;
    k[last] ^= 0x01;
    assert!(matches!(
        alice.create_group(G, &[k]),
        Err(MlsError::InvalidKeyPackage(_))
    ));
    assert!(matches!(
        alice.create_group(G, &[]),
        Err(MlsError::Malformed(_))
    ));
    // Own key package, and the same device twice.
    assert!(matches!(
        alice.create_group(G, &[kp(&alice)]),
        Err(MlsError::Malformed(_))
    ));
    let bob = client("bob", "b1");
    assert!(matches!(
        alice.create_group(G, &[kp(&bob), kp(&bob)]),
        Err(MlsError::Malformed(_))
    ));
    assert!(!alice.has_group(G).unwrap());
}

#[test]
fn unknown_group_and_member() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    assert_eq!(alice.encrypt(b"nope", b"x"), Err(MlsError::UnknownGroup));
    assert_eq!(alice.epoch(b"nope"), Err(MlsError::UnknownGroup));
    group(&alice, &[&bob]);
    assert_eq!(
        alice.remove_members(G, &[dev("nobody", "x")]),
        Err(MlsError::UnknownMember)
    );
    assert!(matches!(
        alice.remove_members(G, &[dev("alice", "a1")]),
        Err(MlsError::Malformed(_))
    ));
    // Adding someone who is already a member.
    let again = client("bob", "b1");
    assert!(matches!(
        alice.add_members(G, &[kp(&again)]),
        Err(MlsError::Malformed(_))
    ));
}
