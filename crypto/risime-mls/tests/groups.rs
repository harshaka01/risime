//! Groups with MLS (contract v1.9 §12): creation with `group_meta`, PrivateMessage handshakes,
//! membership changes, the admin policy on staged commits, rejoin, reset, catch-up and the
//! pending-commit rules.

mod common;

use std::sync::Arc;

use common::*;
use openmls::prelude::tls_codec::{Deserialize as _, Serialize as _};
use openmls::prelude::*;
use openmls_basic_credential::SignatureKeyPair;
use risime_mls::{
    Client, GROUP_META_EXTENSION, GroupCommit, GroupMeta, Incoming, MAX_INLINE_BYTES,
    MemoryKvStore, MlsError, Provider, key_package_supports_groups,
};

const GG: &[u8] = b"grp:3f0c3b1e-7c55-4c1a-9d3e-2f4f7c1b9a10#1";
const GG2: &[u8] = b"grp:3f0c3b1e-7c55-4c1a-9d3e-2f4f7c1b9a10#2";

struct Dev {
    c: Client,
    store: Arc<MemoryKvStore>,
}

fn devc(user: &str, device: &str) -> Dev {
    let store = Arc::new(MemoryKvStore::new());
    Dev {
        c: client_on(store.clone(), user, device),
        store,
    }
}

fn meta(name: &str, admins: &[&str]) -> GroupMeta {
    GroupMeta::new(name, admins.iter().map(|s| s.to_string()).collect())
}

/// Admin `alice` creates [`GG`] with everyone in `others`, the server accepts, all join.
fn make_group(creator: &Client, others: &[&Client]) -> GroupCommit {
    let kps: Vec<Vec<u8>> = others.iter().map(|c| kp(c)).collect();
    let gc = creator
        .create_group_with_meta(GG, &kps, &meta("Rise launch", &["alice"]))
        .unwrap();
    assert_eq!(gc.epoch, 0);
    assert_eq!(creator.commit_accepted(GG).unwrap(), 1);
    for o in others {
        let j = o.join_from_welcome(gc.welcome.as_ref().unwrap()).unwrap();
        assert_eq!(j.epoch, 1);
    }
    gc
}

/// Server-like delivery: the committer merges on `200`, everyone else processes.
fn deliver(committer: &Client, gc: &GroupCommit, to: &[&Client]) -> Vec<Incoming> {
    committer.commit_accepted(GG).unwrap();
    to.iter()
        .map(|c| c.process(GG, &gc.commit).unwrap())
        .collect()
}

fn same_epoch(clients: &[&Client]) {
    let first = clients[0].epoch_authenticator(GG).unwrap();
    for c in clients {
        assert_eq!(
            c.epoch_authenticator(GG).unwrap(),
            first,
            "{}",
            c.identity()
        );
    }
}

fn wire_format(msg: &[u8]) -> WireFormat {
    MlsMessageIn::tls_deserialize_exact(msg)
        .unwrap()
        .try_into_protocol_message()
        .unwrap()
        .wire_format()
}

/// A commit built straight with OpenMLS from a device's own state, **bypassing** the core's
/// own-commit policy check: what a modified client could send.
fn raw_commit(d: &Dev, removes: &[u32], new_meta: Option<&GroupMeta>) -> Vec<u8> {
    let provider = Provider::new(d.store.clone());
    let signer = SignatureKeyPair::read(
        provider.storage(),
        &d.c.signature_public_key(),
        SignatureScheme::ED25519,
    )
    .unwrap();
    let mut group = MlsGroup::load(provider.storage(), &GroupId::from_slice(GG))
        .unwrap()
        .unwrap();
    let mut b = group
        .commit_builder()
        .propose_removals(removes.iter().map(|i| LeafNodeIndex::new(*i)));
    if let Some(m) = new_meta {
        let mut ext = group_extensions(d);
        ext.add_or_replace(Extension::Unknown(
            GROUP_META_EXTENSION,
            UnknownExtension(serde_json::to_vec(m).unwrap()),
        ))
        .unwrap();
        b = b.propose_group_context_extensions(ext).unwrap();
    }
    let bundle = b
        .load_psks(provider.storage())
        .unwrap()
        .build(provider.rand(), provider.crypto(), &signer, |_| true)
        .unwrap()
        .stage_commit(&provider)
        .unwrap();
    bundle.into_messages().0.to_bytes().unwrap()
}

fn group_extensions(d: &Dev) -> Extensions<GroupContext> {
    let provider = Provider::new(d.store.clone());
    MlsGroup::load(provider.storage(), &GroupId::from_slice(GG))
        .unwrap()
        .unwrap()
        .extensions()
        .clone()
}

fn leaf(c: &Client, user: &str, device: &str) -> u32 {
    c.members(GG)
        .unwrap()
        .into_iter()
        .find(|m| m.user_id == user && m.device_id == device)
        .unwrap()
        .leaf_index
}

#[test]
fn create_join_and_talk_with_private_handshakes() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let carol = client("carol", "c1");
    let gc = make_group(&alice, &[&bob, &carol]);
    assert_eq!(gc.added, vec![dev("bob", "b1"), dev("carol", "c1")]);
    assert!(!gc.meta_changed);
    assert_eq!(wire_format(&gc.commit), WireFormat::PrivateMessage);
    assert_eq!(gc.commit_size(), gc.commit.len());
    assert!(!gc.commit_needs_ref() && !gc.welcome_needs_ref());
    assert!(gc.welcome_size() > 0 && gc.welcome_size() <= MAX_INLINE_BYTES);
    same_epoch(&[&alice, &bob, &carol]);
    for c in [&alice, &bob, &carol] {
        let m = c.group_meta(GG).unwrap().unwrap();
        assert_eq!(m.name, "Rise launch");
        assert_eq!(m.admins, vec!["alice"]);
    }

    let ct = bob.encrypt(GG, b"hello group").unwrap();
    assert_eq!(wire_format(&ct), WireFormat::PrivateMessage);
    assert_eq!(alice.decrypt(GG, &ct).unwrap(), b"hello group");
    assert_eq!(carol.decrypt(GG, &ct).unwrap(), b"hello group");

    // A self-update is a PrivateMessage commit anyone may send.
    let su = carol.self_update(GG).unwrap();
    assert_eq!(wire_format(&su.commit), WireFormat::PrivateMessage);
    // The committer's own echo is recognised, not decrypted.
    assert_eq!(carol.process(GG, &su.commit).unwrap(), Incoming::OwnEcho);
    carol.commit_accepted(GG).unwrap();
    alice.process(GG, &su.commit).unwrap();
    bob.process(GG, &su.commit).unwrap();
    same_epoch(&[&alice, &bob, &carol]);

    // DMs keep PublicMessage commits and no meta.
    let pc = alice.create_group(G, &[kp(&bob)]).unwrap();
    assert_eq!(wire_format(&pc.commit), WireFormat::PublicMessage);
    alice.commit_accepted(G).unwrap();
    assert_eq!(alice.group_meta(G).unwrap(), None);
}

#[test]
fn group_ids_and_apis_do_not_mix() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    assert!(matches!(
        alice.create_group(GG, &[kp(&bob)]),
        Err(MlsError::Malformed(_))
    ));
    assert!(matches!(
        alice.create_group_with_meta(G, &[kp(&bob)], &meta("x", &["alice"])),
        Err(MlsError::Malformed(_))
    ));
    // The creator must be an admin.
    assert!(matches!(
        alice.create_group_with_meta(GG, &[kp(&bob)], &meta("x", &["bob"])),
        Err(MlsError::PolicyViolation(_))
    ));
    make_group(&alice, &[&bob]);
    assert!(matches!(
        alice.add_members(GG, &[kp(&client("carol", "c1"))]),
        Err(MlsError::Malformed(_))
    ));
    assert!(matches!(
        alice.remove_members(GG, &[dev("bob", "b1")]),
        Err(MlsError::Malformed(_))
    ));
}

#[test]
fn key_packages_carry_the_groups_capability() {
    let bob = client("bob", "b1");
    assert!(key_package_supports_groups(&kp(&bob)).unwrap());
    assert!(key_package_supports_groups(&bob.generate_last_resort_key_package().unwrap()).unwrap());
    assert!(matches!(
        key_package_supports_groups(b"junk"),
        Err(MlsError::Malformed(_))
    ));

    // A key package from an app before v1.9 (attested, but no 0xFA01) can't join a group.
    let old = legacy_key_package("dave", "d1");
    assert!(!key_package_supports_groups(&old).unwrap());
    let alice = client("alice", "a1");
    assert!(matches!(
        alice.create_group_with_meta(GG, std::slice::from_ref(&old), &meta("x", &["alice"])),
        Err(MlsError::InvalidKeyPackage(_))
    ));
    assert!(!alice.has_group(GG).unwrap());
    // It still works for a DM.
    alice.create_group(G, &[old]).unwrap();
}

/// An attested key package without the 0xFA01 capability, as a v1.7/v1.8 app made them.
fn legacy_key_package(user: &str, device: &str) -> Vec<u8> {
    let provider = Provider::new(Arc::new(MemoryKvStore::new()));
    let signer = SignatureKeyPair::new(SignatureScheme::ED25519).unwrap();
    signer.store(provider.storage()).unwrap();
    let d = dev(user, device);
    let jws = attestor().attest(&d, &signer.to_public_vec(), 1);
    let bundle = KeyPackage::builder()
        .leaf_node_capabilities(Capabilities::new(
            None,
            None,
            Some(&[ExtensionType::LastResort]),
            None,
            None,
        ))
        .leaf_node_extensions(
            Extensions::single(Extension::ApplicationId(ApplicationIdExtension::new(
                jws.as_bytes(),
            )))
            .unwrap(),
        )
        .build(
            risime_mls::CIPHERSUITE,
            &provider,
            &signer,
            CredentialWithKey {
                credential: BasicCredential::new(d.identity().into_bytes()).into(),
                signature_key: signer.to_public_vec().into(),
            },
        )
        .unwrap();
    bundle.key_package().tls_serialize_detached().unwrap()
}

#[test]
fn admin_adds_and_removes_users_and_removed_are_locked_out() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let bob2 = client("bob", "b2");
    let carol = client("carol", "c1");
    make_group(&alice, &[&bob, &bob2]);

    // Add a new user with one commit.
    let add = alice.change_members(GG, &[kp(&carol)], &[]).unwrap();
    assert_eq!(add.added, vec![dev("carol", "c1")]);
    assert!(add.welcome.is_some());
    for r in deliver(&alice, &add, &[&bob, &bob2]) {
        assert!(
            matches!(r, Incoming::Commit { epoch: 2, ref added, .. } if added == &vec![dev("carol", "c1")])
        );
    }
    carol
        .join_from_welcome(add.welcome.as_ref().unwrap())
        .unwrap();
    same_epoch(&[&alice, &bob, &bob2, &carol]);

    // Remove a user = all their leaves, in one commit.
    let rm = alice.remove_users(GG, &["bob".into()]).unwrap();
    assert_eq!(rm.removed, vec![dev("bob", "b1"), dev("bob", "b2")]);
    assert!(rm.welcome.is_none());
    alice.commit_accepted(GG).unwrap();
    carol.process(GG, &rm.commit).unwrap();
    for b in [&bob, &bob2] {
        assert!(matches!(
            b.process(GG, &rm.commit).unwrap(),
            Incoming::Commit {
                removed_self: true,
                ..
            }
        ));
    }
    let ct = alice.encrypt(GG, b"after bob").unwrap();
    assert_eq!(carol.decrypt(GG, &ct).unwrap(), b"after bob");
    assert_eq!(bob.decrypt(GG, &ct), Err(MlsError::RemovedFromGroup));

    // Own user/device can't be removed by this device.
    assert!(alice.remove_users(GG, &["alice".into()]).is_err());
    assert!(
        alice
            .change_members(GG, &[], &[dev("alice", "a1")])
            .is_err()
    );
    assert_eq!(
        alice.remove_users(GG, &["zed".into()]),
        Err(MlsError::UnknownMember)
    );
}

#[test]
fn member_manages_only_own_devices() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let carol = client("carol", "c1");
    make_group(&alice, &[&bob, &carol]);

    // Bob (not an admin) adds his new phone: allowed.
    let bob2 = client("bob", "b2");
    let add = bob.change_members(GG, &[kp(&bob2)], &[]).unwrap();
    deliver(&bob, &add, &[&alice, &carol]);
    bob2.join_from_welcome(add.welcome.as_ref().unwrap())
        .unwrap();
    same_epoch(&[&alice, &bob, &carol, &bob2]);

    // ... and removes his other device: allowed.
    let rm = bob.change_members(GG, &[], &[dev("bob", "b2")]).unwrap();
    deliver(&bob, &rm, &[&alice, &carol]);

    // Another user's device or user: refused before anything is built.
    let dave = client("dave", "d1");
    assert!(matches!(
        bob.change_members(GG, &[kp(&dave)], &[]),
        Err(MlsError::PolicyViolation(_))
    ));
    assert!(matches!(
        bob.remove_users(GG, &["carol".into()]),
        Err(MlsError::PolicyViolation(_))
    ));
    assert!(matches!(
        bob.update_group_meta(GG, &meta("Renamed", &["alice"])),
        Err(MlsError::PolicyViolation(_))
    ));
    assert!(!bob.has_pending_commit(GG).unwrap());
}

/// A commit adding `adds` and removing leaves `removes`, built with OpenMLS **bypassing** the
/// core's own-commit policy check (a modified client).
fn raw_change(d: &Dev, adds: &[Vec<u8>], removes: &[u32]) -> Vec<u8> {
    let provider = Provider::new(d.store.clone());
    let signer = SignatureKeyPair::read(
        provider.storage(),
        &d.c.signature_public_key(),
        SignatureScheme::ED25519,
    )
    .unwrap();
    let mut group = MlsGroup::load(provider.storage(), &GroupId::from_slice(GG))
        .unwrap()
        .unwrap();
    let kps: Vec<KeyPackage> = adds
        .iter()
        .map(|b| {
            KeyPackageIn::tls_deserialize_exact(b)
                .unwrap()
                .validate(provider.crypto(), ProtocolVersion::Mls10)
                .unwrap()
        })
        .collect();
    let bundle = group
        .commit_builder()
        .propose_adds(kps)
        .propose_removals(removes.iter().map(|i| LeafNodeIndex::new(*i)))
        .load_psks(provider.storage())
        .unwrap()
        .build(provider.rand(), provider.crypto(), &signer, |_| true)
        .unwrap()
        .stage_commit(&provider)
        .unwrap();
    bundle.into_messages().0.to_bytes().unwrap()
}

/// v1.14 §12.4a: a member restores an existing member's devices (a reinstall, a rejoin), but
/// can't bring in a new user, swap a device for another, or remove one on its own.
#[test]
fn member_restores_an_existing_members_devices() {
    let alice = client("alice", "a1");
    let bob = devc("bob", "b1");
    let carol = client("carol", "c1");
    make_group(&alice, &[&bob.c, &carol]);

    // Carol reinstalls: a new device id. Bob (not an admin) adds it; everyone accepts.
    let carol2 = client("carol", "c2");
    let add = bob.c.change_members(GG, &[kp(&carol2)], &[]).unwrap();
    assert_eq!(add.added, vec![dev("carol", "c2")]);
    for i in deliver(&bob.c, &add, &[&alice, &carol]) {
        assert!(matches!(i, Incoming::Commit { .. }));
    }
    carol2
        .join_from_welcome(add.welcome.as_ref().unwrap())
        .unwrap();
    same_epoch(&[&alice, &bob.c, &carol, &carol2]);

    // Carol's c1 rejoins (same device, a fresh key package): Bob removes and re-adds it.
    carol.delete_group(GG).unwrap();
    let re = bob
        .c
        .change_members(GG, &[kp(&carol)], &[dev("carol", "c1")])
        .unwrap();
    deliver(&bob.c, &re, &[&alice, &carol2]);
    carol
        .join_from_welcome(re.welcome.as_ref().unwrap())
        .unwrap();
    same_epoch(&[&alice, &bob.c, &carol, &carol2]);
    let ct = carol.encrypt(GG, b"back").unwrap();
    assert_eq!(bob.c.decrypt(GG, &ct).unwrap(), b"back");

    // Refused before anything is built: a user with no leaf (Dave's key package is validly
    // attested, but for a user who isn't in the group), a swap for another device, a standalone
    // removal of another user's device.
    let dave = client("dave", "d1");
    let carol3 = client("carol", "c3");
    for r in [
        bob.c.change_members(GG, &[kp(&dave)], &[]),
        bob.c
            .change_members(GG, &[kp(&carol3)], &[dev("carol", "c1")]),
        bob.c.change_members(GG, &[], &[dev("carol", "c2")]),
        bob.c
            .change_members(GG, &[kp(&carol3)], &[dev("alice", "a1")]),
    ] {
        assert!(matches!(r, Err(MlsError::PolicyViolation(_))), "{r:?}");
    }
    assert!(!bob.c.has_pending_commit(GG).unwrap());

    // The same from a modified client: peers reject the staged commit and stay put.
    let epoch = alice.epoch(GG).unwrap();
    let c1 = leaf(&alice, "carol", "c1");
    let c2 = leaf(&alice, "carol", "c2");
    let cases: [(Vec<Vec<u8>>, Vec<u32>); 3] = [
        (vec![kp(&dave)], vec![]),
        (vec![kp(&carol3)], vec![c1]),
        (vec![], vec![c2]),
    ];
    for (adds, removes) in cases {
        let evil = raw_change(&bob, &adds, &removes);
        for c in [&alice, &carol, &carol2] {
            assert!(matches!(
                c.process(GG, &evil),
                Err(MlsError::PolicyViolation(_))
            ));
        }
        bob.c.commit_rejected(GG).unwrap();
    }
    assert_eq!(alice.epoch(GG).unwrap(), epoch);
    same_epoch(&[&alice, &bob.c, &carol, &carol2]);
}

#[test]
fn peers_reject_commits_that_break_the_admin_policy() {
    let alice = client("alice", "a1");
    let bob = devc("bob", "b1");
    let carol = client("carol", "c1");
    make_group(&alice, &[&bob.c, &carol]);
    let epoch = alice.epoch(GG).unwrap();
    let auth = alice.epoch_authenticator(GG).unwrap();

    // A modified client: non-admin Bob removes Carol.
    let evil = raw_commit(&bob, &[leaf(&alice, "carol", "c1")], None);
    for c in [&alice, &carol] {
        assert!(matches!(
            c.process(GG, &evil),
            Err(MlsError::PolicyViolation(_))
        ));
        assert_eq!(c.epoch(GG).unwrap(), epoch);
    }
    assert_eq!(alice.epoch_authenticator(GG).unwrap(), auth);
    bob.c.commit_rejected(GG).unwrap();

    // Non-admin Bob renames the group, or makes himself admin.
    for m in [
        meta("Bob's group", &["alice"]),
        meta("Rise launch", &["alice", "bob"]),
    ] {
        let evil = raw_commit(&bob, &[], Some(&m));
        assert!(matches!(
            alice.process(GG, &evil),
            Err(MlsError::PolicyViolation(_))
        ));
        bob.c.commit_rejected(GG).unwrap();
    }
    assert_eq!(alice.epoch(GG).unwrap(), epoch);
    assert_eq!(carol.group_meta(GG).unwrap().unwrap().admins, vec!["alice"]);

    // Messaging is unaffected.
    let ct = bob.c.encrypt(GG, b"still here").unwrap();
    assert_eq!(alice.decrypt(GG, &ct).unwrap(), b"still here");
}

#[test]
fn admin_policy_on_meta_commits() {
    let alice = devc("alice", "a1");
    let bob = client("bob", "b1");
    let carol = client("carol", "c1");
    make_group(&alice.c, &[&bob, &carol]);

    // Rename.
    let rn = alice
        .c
        .update_group_meta(GG, &meta("Rise 2.0", &["alice"]))
        .unwrap();
    assert!(rn.meta_changed && rn.added.is_empty() && rn.welcome.is_none());
    for r in deliver(&alice.c, &rn, &[&bob, &carol]) {
        assert!(matches!(
            r,
            Incoming::Commit {
                meta_changed: true,
                ..
            }
        ));
    }
    assert_eq!(bob.group_meta(GG).unwrap().unwrap().name, "Rise 2.0");
    assert!(matches!(
        alice.c.update_group_meta(GG, &meta("Rise 2.0", &["alice"])),
        Err(MlsError::Malformed(_))
    ));

    // Promote Bob; now Bob may remove Carol.
    let pr = alice
        .c
        .update_group_meta(GG, &meta("Rise 2.0", &["alice", "bob"]))
        .unwrap();
    deliver(&alice.c, &pr, &[&bob, &carol]);
    assert_eq!(
        carol.group_meta(GG).unwrap().unwrap().admins,
        vec!["alice", "bob"]
    );
    let rm = bob.remove_users(GG, &["carol".into()]).unwrap();
    bob.commit_accepted(GG).unwrap();
    alice.c.process(GG, &rm.commit).unwrap();
    same_epoch(&[&alice.c, &bob]);

    // An admin can't empty the admin list (own check and peer check).
    assert!(alice.c.update_group_meta(GG, &meta("x", &[])).is_err());
    let mut empty = meta("Rise 2.0", &["alice"]);
    empty.admins.clear();
    let evil = raw_commit(&alice, &[], Some(&empty));
    assert!(bob.process(GG, &evil).is_err());
    alice.c.commit_rejected(GG).unwrap();

    // Unknown meta fields survive a rename by this version.
    let mut m = alice.c.group_meta(GG).unwrap().unwrap();
    m.extra.insert("future".into(), serde_json::json!({"k": 1}));
    let with_future = raw_commit(&alice, &[], Some(&m));
    alice.c.commit_accepted(GG).unwrap();
    bob.process(GG, &with_future).unwrap();
    let rn = bob
        .update_group_meta(GG, &meta("Rise 3", &["alice", "bob"]))
        .unwrap();
    bob.commit_accepted(GG).unwrap();
    alice.c.process(GG, &rn.commit).unwrap();
    let got = alice.c.group_meta(GG).unwrap().unwrap();
    assert_eq!(got.name, "Rise 3");
    assert_eq!(got.extra["future"], serde_json::json!({"k": 1}));
}

#[test]
fn pending_commit_waits_for_the_server() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let carol = client("carol", "c1");
    make_group(&alice, &[&bob]);

    let add = alice.change_members(GG, &[kp(&carol)], &[]).unwrap();
    assert!(alice.has_pending_commit(GG).unwrap());
    assert_eq!(alice.epoch(GG).unwrap(), 1);
    assert!(matches!(
        alice.self_update(GG),
        Err(MlsError::CommitPending)
    ));
    // Messaging continues in the old epoch meanwhile.
    let ct = alice.encrypt(GG, b"while pending").unwrap();
    assert_eq!(bob.decrypt(GG, &ct).unwrap(), b"while pending");
    // 409: dropped, epoch unchanged.
    alice.commit_rejected(GG).unwrap();
    assert!(!alice.has_pending_commit(GG).unwrap());
    assert_eq!(alice.epoch(GG).unwrap(), 1);
    // (The server never delivers the Welcome of a refused commit.)
    drop((add, carol));

    // A foreign commit discards our pending one.
    alice.self_update(GG).unwrap();
    let theirs = bob.self_update(GG).unwrap();
    bob.commit_accepted(GG).unwrap();
    assert!(matches!(
        alice.process(GG, &theirs.commit).unwrap(),
        Incoming::Commit {
            discarded_own_pending: true,
            ..
        }
    ));
    assert!(!alice.has_pending_commit(GG).unwrap());
    same_epoch(&[&alice, &bob]);

    // Creation race at epoch 0: the loser's group is deleted.
    let x = client("xavier", "x1");
    x.create_group_with_meta(GG2, &[kp(&bob)], &meta("race", &["xavier"]))
        .unwrap();
    x.commit_rejected(GG2).unwrap();
    assert!(!x.has_group(GG2).unwrap());
}

#[test]
fn rejoin_re_adds_a_broken_device() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let carol = client("carol", "c1");
    make_group(&alice, &[&bob, &carol]);

    // Carol misses a commit, then loses it for good (log expired): her state is unrecoverable.
    let su = bob.self_update(GG).unwrap();
    deliver(&bob, &su, &[&alice]);
    let su2 = alice.self_update(GG).unwrap();
    deliver(&alice, &su2, &[&bob]);
    assert_eq!(carol.process(GG, &su2.commit), Err(MlsError::WrongEpoch));

    // POST /rejoin → a `devices` op; the committer removes and re-adds her in one commit.
    let re = alice
        .change_members(GG, &[kp(&carol)], &[dev("carol", "c1")])
        .unwrap();
    assert_eq!(re.added, vec![dev("carol", "c1")]);
    assert_eq!(re.removed, vec![dev("carol", "c1")]);
    deliver(&alice, &re, &[&bob]);
    let j = carol
        .join_from_welcome(re.welcome.as_ref().unwrap())
        .unwrap();
    assert_eq!(j.epoch, alice.epoch(GG).unwrap());
    same_epoch(&[&alice, &bob, &carol]);
    let ct = carol.encrypt(GG, b"back again").unwrap();
    assert_eq!(bob.decrypt(GG, &ct).unwrap(), b"back again");

    // Re-adding without listing the old leaf is refused.
    assert!(matches!(
        alice.change_members(GG, &[kp(&carol)], &[]),
        Err(MlsError::Malformed(_))
    ));
    // A device that lost its state entirely also joins from a rejoin Welcome.
    carol.delete_group(GG).unwrap();
    let re = alice
        .change_members(GG, &[kp(&carol)], &[dev("carol", "c1")])
        .unwrap();
    deliver(&alice, &re, &[&bob]);
    carol
        .join_from_welcome(re.welcome.as_ref().unwrap())
        .unwrap();
    same_epoch(&[&alice, &bob, &carol]);
}

#[test]
fn reset_rebuilds_a_new_generation() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let carol = client("carol", "c1");
    make_group(&alice, &[&bob, &carol]);
    let pr = alice
        .update_group_meta(GG, &meta("Rise launch", &["alice", "carol"]))
        .unwrap();
    deliver(&alice, &pr, &[&bob, &carol]);

    // POST …/reset by admin Carol → generation 2. Carol rebuilds from scratch with her last local
    // name and the admin list from the `reset` event.
    let last = carol.group_meta(GG).unwrap().unwrap();
    let gc = carol
        .create_group_with_meta(GG2, &[kp(&alice), kp(&bob)], &last)
        .unwrap();
    assert_eq!(gc.epoch, 0);
    carol.commit_accepted(GG2).unwrap();
    for c in [&alice, &bob] {
        c.delete_group(GG).unwrap();
        let j = c.join_from_welcome(gc.welcome.as_ref().unwrap()).unwrap();
        assert_eq!(j.group_id, GG2);
        assert_eq!(
            c.group_meta(GG2).unwrap().unwrap().admins,
            vec!["alice", "carol"]
        );
    }
    carol.delete_group(GG).unwrap();
    let ct = bob.encrypt(GG2, b"new generation").unwrap();
    assert_eq!(alice.decrypt(GG2, &ct).unwrap(), b"new generation");
    assert_eq!(carol.decrypt(GG2, &ct).unwrap(), b"new generation");
    assert!(!alice.has_group(GG).unwrap());

    // A rebuild with only the rebuilder's device (members without devices come later).
    let z = client("zoe", "z1");
    let alone = z
        .create_group_with_meta(b"grp:solo#1", &[], &meta("solo", &["zoe"]))
        .unwrap();
    assert!(alone.welcome.is_none());
    assert_eq!(z.commit_accepted(b"grp:solo#1").unwrap(), 1);
}

#[test]
fn catch_up_applies_a_sequence_of_commits() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let carol = client("carol", "c1");
    let dave = client("dave", "d1");
    let create = make_group(&alice, &[&bob, &carol]);

    // Carol is offline for three commits.
    let mut log = vec![];
    let c1 = bob.self_update(GG).unwrap();
    deliver(&bob, &c1, &[&alice]);
    log.push(c1.commit);
    let c2 = alice.change_members(GG, &[kp(&dave)], &[]).unwrap();
    deliver(&alice, &c2, &[&bob]);
    dave.join_from_welcome(c2.welcome.as_ref().unwrap())
        .unwrap();
    log.push(c2.commit);
    let c3 = alice
        .update_group_meta(GG, &meta("Renamed", &["alice"]))
        .unwrap();
    deliver(&alice, &c3, &[&bob, &dave]);
    log.push(c3.commit);

    // A gap is WrongEpoch, and nothing is applied.
    assert_eq!(
        carol.process_commits(GG, &log[1..]),
        Err(MlsError::WrongEpoch)
    );
    assert_eq!(carol.epoch(GG).unwrap(), 1);

    // GET …/commits?since_epoch=0 also returns the creation commit she already has.
    let mut all = vec![create.commit.clone()];
    all.extend(log.iter().cloned());
    let up = carol.process_commits(GG, &all).unwrap();
    assert_eq!(up.skipped, 1);
    assert_eq!(up.applied.len(), 3);
    assert_eq!(up.epoch, 4);
    assert!(!up.removed_self);
    assert!(matches!(
        up.applied[2],
        Incoming::Commit {
            meta_changed: true,
            ..
        }
    ));
    same_epoch(&[&alice, &bob, &carol, &dave]);
    // Running it again is a no-op.
    assert_eq!(carol.process_commits(GG, &all).unwrap().skipped, 4);

    // A batch that removes us stops there.
    let rm = alice.remove_users(GG, &["dave".into()]).unwrap();
    alice.commit_accepted(GG).unwrap();
    let after = alice.self_update(GG).unwrap();
    alice.commit_accepted(GG).unwrap();
    let up = dave
        .process_commits(GG, &[rm.commit.clone(), after.commit.clone()])
        .unwrap();
    assert!(up.removed_self);
    assert_eq!(up.applied.len(), 1);
}

#[test]
fn catch_up_merges_our_own_accepted_commit_after_a_restart() {
    let store = Arc::new(MemoryKvStore::new());
    let alice = client_on(store.clone(), "alice", "a1");
    let bob = client("bob", "b1");
    let carol = client("carol", "c1");
    make_group(&alice, &[&bob]);

    // Alice commits; the server accepts (bob processes), but the app dies before the 200 is
    // handled.
    let add = alice.change_members(GG, &[kp(&carol)], &[]).unwrap();
    bob.process(GG, &add.commit).unwrap();
    drop(alice);
    let alice = Client::open(store.clone(), dev("alice", "a1"), attestor().anchors()).unwrap();
    assert!(alice.has_pending_commit(GG).unwrap());

    // On restart she finds her own commit in GET …/commits and merges it.
    let up = alice
        .process_commits(GG, std::slice::from_ref(&add.commit))
        .unwrap();
    assert_eq!(up.epoch, 2);
    match &up.applied[0] {
        Incoming::Commit {
            committer, added, ..
        } => {
            assert_eq!(committer, &dev("alice", "a1"));
            assert_eq!(added, &vec![dev("carol", "c1")]);
        }
        o => panic!("{o:?}"),
    }
    assert!(!alice.has_pending_commit(GG).unwrap());
    carol
        .join_from_welcome(add.welcome.as_ref().unwrap())
        .unwrap();
    same_epoch(&[&alice, &bob, &carol]);
    assert_eq!(store.depth(), 0);
}

#[test]
fn empty_and_duplicate_changes_are_refused() {
    // The user cap is exercised in tests/group_scale.rs. Here: empty and duplicate changes.
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    make_group(&alice, &[&bob]);
    assert!(matches!(
        alice.change_members(GG, &[], &[]),
        Err(MlsError::Malformed(_))
    ));
    let k = kp(&client("carol", "c1"));
    assert!(matches!(
        alice.change_members(GG, &[k.clone(), k], &[]),
        Err(MlsError::Malformed(_))
    ));
}
