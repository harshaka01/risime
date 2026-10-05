//! Integration tests proving the E2EE properties the 0.3 design relies on.
//! Every message crosses the API as serialised bytes, exactly as it would through the server.

use risime_mls::{Client, Incoming, MlsError};

const GROUP: &[u8] = b"risime-test-group";

fn client(name: &str) -> Client {
    Client::new(name.as_bytes()).expect("client")
}

/// Alice creates the group and adds Bob; Bob joins from the Welcome.
fn alice_and_bob() -> (Client, Client) {
    let mut alice = client("alice");
    let mut bob = client("bob");
    alice.create_group(GROUP).unwrap();
    let kp = bob.create_key_package().unwrap();
    let out = alice.add_member(GROUP, &kp).unwrap();
    let joined = bob.join_from_welcome(&out.welcome).unwrap();
    assert_eq!(joined, GROUP);
    (alice, bob)
}

/// Alice, Bob and Carol in one group, all at the same epoch.
fn alice_bob_carol() -> (Client, Client, Client) {
    let (mut alice, mut bob) = alice_and_bob();
    let mut carol = client("carol");
    let kp = carol.create_key_package().unwrap();
    let out = alice.add_member(GROUP, &kp).unwrap();
    // The commit goes to existing members, the Welcome to the new one.
    assert_eq!(
        bob.process(GROUP, &out.commit).unwrap(),
        Incoming::Commit {
            epoch: 2,
            removed_self: false
        }
    );
    carol.join_from_welcome(&out.welcome).unwrap();
    for c in [&alice, &bob, &carol] {
        assert_eq!(c.epoch(GROUP).unwrap(), 2);
    }
    (alice, bob, carol)
}

// (a) group creation
#[test]
fn a_create_group() {
    let mut alice = client("alice");
    alice.create_group(GROUP).unwrap();
    assert_eq!(alice.epoch(GROUP).unwrap(), 0);
    assert_eq!(alice.members(GROUP).unwrap(), vec![b"alice".to_vec()]);
    assert!(alice.is_active(GROUP).unwrap());
    assert_eq!(alice.create_group(GROUP), Err(MlsError::GroupExists));
}

// (b) add a member; the member joins from the Welcome and both agree on the epoch
#[test]
fn b_add_member_join_from_welcome_same_epoch() {
    let (alice, bob) = alice_and_bob();
    assert_eq!(alice.epoch(GROUP).unwrap(), 1);
    assert_eq!(bob.epoch(GROUP).unwrap(), 1);
    // Same epoch number *and* the same epoch secrets.
    assert_eq!(
        alice.epoch_authenticator(GROUP).unwrap(),
        bob.epoch_authenticator(GROUP).unwrap()
    );
    let expected = vec![b"alice".to_vec(), b"bob".to_vec()];
    assert_eq!(alice.members(GROUP).unwrap(), expected);
    assert_eq!(bob.members(GROUP).unwrap(), expected);
}

// (c) encrypt -> decrypt in both directions with the exact plaintext
#[test]
fn c_encrypt_decrypt_both_directions() {
    let (mut alice, mut bob) = alice_and_bob();

    let hello = "Hi Bob — commitment: ship 0.3 by Friday ✓".as_bytes();
    let ct = alice.encrypt(GROUP, hello).unwrap();
    assert!(
        !ct.windows(hello.len()).any(|w| w == hello),
        "plaintext must not appear in the ciphertext"
    );
    assert_eq!(
        bob.process(GROUP, &ct).unwrap(),
        Incoming::Application {
            sender: b"alice".to_vec(),
            plaintext: hello.to_vec()
        }
    );

    let reply = b"Hi Alice, agreed.";
    let ct = bob.encrypt(GROUP, reply).unwrap();
    assert_eq!(alice.decrypt(GROUP, &ct).unwrap(), reply.to_vec());

    // Several messages in a row keep working (sender ratchet advances).
    for i in 0..5u8 {
        let m = vec![i; 1 + i as usize];
        let ct = alice.encrypt(GROUP, &m).unwrap();
        assert_eq!(bob.decrypt(GROUP, &ct).unwrap(), m);
    }
}

// (d) after removal the removed member cannot decrypt new messages; the others still can
#[test]
fn d_removed_member_cannot_decrypt_new_messages() {
    let (mut alice, mut bob, mut carol) = alice_bob_carol();

    let commit = alice.remove_member(GROUP, b"bob").unwrap();
    assert_eq!(
        carol.process(GROUP, &commit).unwrap(),
        Incoming::Commit {
            epoch: 3,
            removed_self: false
        }
    );
    // Bob processes his own removal and learns about it.
    assert_eq!(
        bob.process(GROUP, &commit).unwrap(),
        Incoming::Commit {
            epoch: 3,
            removed_self: true
        }
    );
    assert!(!bob.is_active(GROUP).unwrap());
    assert_eq!(
        alice.members(GROUP).unwrap(),
        vec![b"alice".to_vec(), b"carol".to_vec()]
    );

    let secret = b"post-removal secret";
    let ct = alice.encrypt(GROUP, secret).unwrap();

    // Remaining member still decrypts.
    assert_eq!(carol.decrypt(GROUP, &ct).unwrap(), secret.to_vec());
    // Removed member cannot.
    assert_eq!(bob.decrypt(GROUP, &ct), Err(MlsError::RemovedFromGroup));
    // ... and cannot send either.
    assert_eq!(
        bob.encrypt(GROUP, b"still here?"),
        Err(MlsError::RemovedFromGroup)
    );

    // Carol -> Alice also still works in the new epoch.
    let ct = carol.encrypt(GROUP, b"just us now").unwrap();
    assert_eq!(alice.decrypt(GROUP, &ct).unwrap(), b"just us now".to_vec());
}

// (d') a removed member that *ignores* its removal commit (e.g. a modified client that never
// applies it) still cannot read: it has no keys for the new epoch.
#[test]
fn d_removed_member_ignoring_commit_cannot_decrypt() {
    let (mut alice, mut bob, mut carol) = alice_bob_carol();

    let commit = alice.remove_member(GROUP, b"bob").unwrap();
    carol.process(GROUP, &commit).unwrap();
    // Bob never processes `commit`; he stays at epoch 2.

    let secret = b"post-removal secret";
    let ct = alice.encrypt(GROUP, secret).unwrap();
    assert_eq!(carol.decrypt(GROUP, &ct).unwrap(), secret.to_vec());
    assert_eq!(bob.epoch(GROUP).unwrap(), 2);
    assert_eq!(bob.decrypt(GROUP, &ct), Err(MlsError::WrongEpoch));
}

// Negative: tampered ciphertext is rejected, not silently mis-decrypted.
#[test]
fn tampered_ciphertext_is_rejected() {
    let (mut alice, mut bob) = alice_and_bob();
    let mut ct = alice.encrypt(GROUP, b"integrity matters").unwrap();
    // Flip one bit in the AEAD ciphertext/tag at the end of the message.
    let last = ct.len() - 1;
    ct[last] ^= 0x01;
    match bob.decrypt(GROUP, &ct) {
        Err(MlsError::DecryptionFailed(_)) => {}
        other => panic!("expected DecryptionFailed, got {other:?}"),
    }
    // The untampered stream still works afterwards.
    let ct = alice.encrypt(GROUP, b"next").unwrap();
    assert_eq!(bob.decrypt(GROUP, &ct).unwrap(), b"next".to_vec());
}

// Negative: an outsider who was never added cannot join with someone else's Welcome.
#[test]
fn welcome_for_someone_else_is_rejected() {
    let mut alice = client("alice");
    let bob = client("bob");
    let mut mallory = client("mallory");
    alice.create_group(GROUP).unwrap();
    let out = alice
        .add_member(GROUP, &bob.create_key_package().unwrap())
        .unwrap();
    assert!(matches!(
        mallory.join_from_welcome(&out.welcome),
        Err(MlsError::Welcome(_))
    ));
}

// Negative: garbage and forged key packages are rejected.
#[test]
fn invalid_key_package_is_rejected() {
    let mut alice = client("alice");
    alice.create_group(GROUP).unwrap();
    assert!(matches!(
        alice.add_member(GROUP, b"not a key package"),
        Err(MlsError::Malformed(_))
    ));
    let mut kp = client("bob").create_key_package().unwrap();
    let last = kp.len() - 1;
    kp[last] ^= 0x01; // breaks the signature
    assert!(matches!(
        alice.add_member(GROUP, &kp),
        Err(MlsError::InvalidKeyPackage(_))
    ));
}

#[test]
fn unknown_group_and_member() {
    let mut alice = client("alice");
    assert_eq!(alice.encrypt(b"nope", b"x"), Err(MlsError::UnknownGroup));
    alice.create_group(GROUP).unwrap();
    assert_eq!(
        alice.remove_member(GROUP, b"nobody"),
        Err(MlsError::UnknownMember)
    );
}
