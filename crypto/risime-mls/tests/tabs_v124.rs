//! Contract v1.24 §24.1 on real MLS state: the attested `kind` read from each leaf, the tab rules
//! on the epoch-0 group, on our own commits, on peers' staged commits and on the Welcome we join.

mod common;

use std::sync::Arc;

use common::*;
use openmls::prelude::tls_codec::Deserialize as _;
use openmls::prelude::*;
use openmls_basic_credential::SignatureKeyPair;
use risime_mls::policy::Tab;
use risime_mls::{
    Client, GROUP_META_EXTENSION, GroupCommit, GroupMeta, Incoming, LeafKind, MemoryKvStore,
    MlsError, Provider, attested_kind,
};

const GG: &[u8] = b"grp:7d1c0e52-4a8b-4f6e-9a3b-1c2d3e4f5a6b#1";
const PRIVATE_CHAT: &str = "grp:0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";

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

/// Risi: a device whose attestation says `kind: "agent"`.
fn agent(user: &str, device: &str) -> Client {
    let a = attestor();
    let mut c = Client::open(
        Arc::new(MemoryKvStore::new()),
        dev(user, device),
        a.anchors(),
    )
    .unwrap();
    let jws = a.attest_agent(c.device(), &c.signature_public_key(), 1_760_000_000);
    c.set_attestation(&jws).unwrap();
    c
}

fn private_meta() -> GroupMeta {
    GroupMeta::new("Site team", vec!["alice".into()])
}

fn official_meta(agents: &[&str]) -> GroupMeta {
    GroupMeta::new("Site team", vec!["alice".into()]).with_tab(
        Tab::Official,
        PRIVATE_CHAT,
        agents.iter().map(|s| s.to_string()).collect(),
    )
}

fn deliver(committer: &Client, gc: &GroupCommit, to: &[&Client]) -> Vec<Incoming> {
    committer.commit_accepted(GG).unwrap();
    to.iter()
        .map(|c| c.process(GG, &gc.commit).unwrap())
        .collect()
}

fn kind_of(c: &Client, user: &str) -> LeafKind {
    c.members(GG)
        .unwrap()
        .into_iter()
        .find(|m| m.user_id == user)
        .unwrap()
        .kind
}

/// A commit (and its Welcome) built straight with OpenMLS, **bypassing** the core's own-commit
/// checks: what a modified client could send.
fn raw_change(
    d: &Dev,
    adds: &[Vec<u8>],
    new_meta: Option<&GroupMeta>,
) -> (Vec<u8>, Option<Vec<u8>>) {
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
    let current = group.extensions().clone();
    let mut b = group.commit_builder().propose_adds(kps);
    if let Some(m) = new_meta {
        let mut ext = current;
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
    let (commit, welcome, _) = bundle.into_messages();
    (
        commit.to_bytes().unwrap(),
        welcome.map(|w| w.to_bytes().unwrap()),
    )
}

#[test]
fn the_kind_claim_is_read_and_checked() {
    let a = attestor();
    let risi = agent("risi", "r1");
    let alice = client("alice", "a1");
    assert_eq!(
        attested_kind(risi.attestation().map(str::as_bytes)),
        LeafKind::Agent
    );
    assert_eq!(
        attested_kind(alice.attestation().map(str::as_bytes)),
        LeafKind::User
    );
    assert_eq!(attested_kind(None), LeafKind::User);
    // An explicit "user" is a user; an unknown kind is refused by the pinned-key check.
    let d = dev("x", "x1");
    let key = alice.signature_public_key();
    let user = a.attest_kind(&d, &key, 1, Some("user"));
    assert_eq!(attested_kind(Some(user.as_bytes())), LeafKind::User);
    let odd = a.attest_kind(&d, &key, 1, Some("robot"));
    assert_eq!(attested_kind(Some(odd.as_bytes())), LeafKind::Agent);
    assert!(a.anchors().verify(&user, &d, &key).is_ok());
    assert!(matches!(
        a.anchors().verify(&odd, &d, &key),
        Err(MlsError::UntrustedCredential(_))
    ));
}

#[test]
fn official_holds_risi_and_any_member_may_remove_it() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let risi = agent("risi", "r1");

    let gc = alice
        .create_group_with_meta(GG, &[kp(&bob), kp(&risi)], &official_meta(&["risi"]))
        .unwrap();
    alice.commit_accepted(GG).unwrap();
    for c in [&bob, &risi] {
        let j = c.join_from_welcome(gc.welcome.as_ref().unwrap()).unwrap();
        let r = j.members.iter().find(|m| m.user_id == "risi").unwrap();
        assert_eq!(r.kind, LeafKind::Agent);
        assert!(
            j.members
                .iter()
                .filter(|m| m.user_id != "risi")
                .all(|m| m.kind == LeafKind::User)
        );
    }
    let meta = bob.group_meta(GG).unwrap().unwrap();
    assert_eq!(meta.tab(), Tab::Official);
    assert_eq!(meta.chat_id.as_deref(), Some(PRIVATE_CHAT));
    assert_eq!(meta.agents(), ["risi".to_string()]);
    assert_eq!(kind_of(&alice, "risi"), LeafKind::Agent);
    assert_eq!(kind_of(&alice, "bob"), LeafKind::User);

    // A rename by an app that doesn't know the v1.24 fields keeps them.
    let rn = alice
        .update_group_meta(GG, &GroupMeta::new("Renamed", vec!["alice".into()]))
        .unwrap();
    deliver(&alice, &rn, &[&bob, &risi]);
    let meta = bob.group_meta(GG).unwrap().unwrap();
    assert_eq!((meta.name.as_str(), meta.tab()), ("Renamed", Tab::Official));
    assert_eq!(meta.agents(), ["risi".to_string()]);

    // Bob (not an admin) can't add an agent leaf, nor make Risi an admin.
    let risi2 = agent("risi", "r2");
    assert!(matches!(
        bob.change_members(GG, &[kp(&risi2)], &[]),
        Err(MlsError::PolicyViolation(_))
    ));
    let mut m = official_meta(&["risi"]);
    m.admins.push("risi".into());
    assert!(alice.update_group_meta(GG, &m).is_err());

    // Bob removes every leaf of Risi (Official turned off): everyone accepts it.
    let rm = bob.remove_users(GG, &["risi".into()]).unwrap();
    for i in deliver(&bob, &rm, &[&alice, &risi]) {
        assert!(matches!(i, Incoming::Commit { .. }));
    }
    assert!(!risi.is_active(GG).unwrap());
    assert!(
        alice
            .members(GG)
            .unwrap()
            .iter()
            .all(|m| m.user_id != "risi")
    );

    // Official on again: only an admin re-adds Risi.
    let risi3 = agent("risi", "r3");
    assert!(matches!(
        bob.change_members(GG, &[kp(&risi3)], &[]),
        Err(MlsError::PolicyViolation(_))
    ));
    let add = alice.change_members(GG, &[kp(&risi3)], &[]).unwrap();
    deliver(&alice, &add, &[&bob]);
    risi3
        .join_from_welcome(add.welcome.as_ref().unwrap())
        .unwrap();
    same(&[&alice, &bob, &risi3]);
}

#[test]
fn a_partial_agent_removal_stays_admin_only() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let (r1, r2) = (agent("risi", "r1"), agent("risi", "r2"));
    let gc = alice
        .create_group_with_meta(GG, &[kp(&bob), kp(&r1), kp(&r2)], &official_meta(&["risi"]))
        .unwrap();
    alice.commit_accepted(GG).unwrap();
    bob.join_from_welcome(gc.welcome.as_ref().unwrap()).unwrap();
    assert!(matches!(
        bob.change_members(GG, &[], &[dev("risi", "r1")]),
        Err(MlsError::PolicyViolation(_))
    ));
    // Every leaf: allowed.
    bob.change_members(GG, &[], &[dev("risi", "r1"), dev("risi", "r2")])
        .unwrap();
    bob.commit_rejected(GG).unwrap();
    // An admin may remove a part.
    alice.change_members(GG, &[], &[dev("risi", "r1")]).unwrap();
}

#[test]
fn private_and_unlisted_agents_are_refused_on_creation() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let risi = agent("risi", "r1");
    for meta in [
        private_meta(),
        private_meta().with_tab(Tab::Private, PRIVATE_CHAT, vec![]),
        official_meta(&["someone-else"]),
    ] {
        assert!(matches!(
            alice.create_group_with_meta(GG, &[kp(&bob), kp(&risi)], &meta),
            Err(MlsError::PolicyViolation(_))
        ));
    }
    // A Private meta naming agents is invalid in itself.
    assert!(
        alice
            .create_group_with_meta(
                GG,
                &[kp(&bob)],
                &private_meta().with_tab(Tab::Private, PRIVATE_CHAT, vec!["risi".into()])
            )
            .is_err()
    );
}

#[test]
fn private_never_gains_an_agent_leaf() {
    let alice = devc("alice", "a1");
    let bob = client("bob", "b1");
    let gc = alice
        .c
        .create_group_with_meta(GG, &[kp(&bob)], &private_meta())
        .unwrap();
    alice.c.commit_accepted(GG).unwrap();
    bob.join_from_welcome(gc.welcome.as_ref().unwrap()).unwrap();

    // Our own commit: refused before it is built.
    let risi = agent("risi", "r1");
    assert!(matches!(
        alice.c.change_members(GG, &[kp(&risi)], &[]),
        Err(MlsError::PolicyViolation(_))
    ));
    // Nor can the admin turn the tab to Official or list an agent.
    let mut m = private_meta().with_tab(Tab::Official, PRIVATE_CHAT, vec!["risi".into()]);
    assert!(alice.c.update_group_meta(GG, &m).is_err());
    m = private_meta();
    m.chat_id = Some("grp:another".into());
    assert!(alice.c.update_group_meta(GG, &m).is_err());

    // A modified client's commit adding Risi and Carol: Bob refuses it, and neither Carol nor
    // Risi can join from its Welcome.
    let carol = client("carol", "c1");
    let (commit, welcome) = raw_change(&alice, &[kp(&carol), kp(&risi)], None);
    assert!(matches!(
        bob.process(GG, &commit),
        Err(MlsError::PolicyViolation(_))
    ));
    for c in [&carol, &risi] {
        assert!(matches!(
            c.join_from_welcome(welcome.as_ref().unwrap()),
            Err(MlsError::Welcome(_))
        ));
        assert!(!c.has_group(GG).unwrap());
    }
}

#[test]
fn tab_and_chat_id_never_change() {
    let alice = devc("alice", "a1");
    let bob = client("bob", "b1");
    let risi = agent("risi", "r1");
    let gc = alice
        .c
        .create_group_with_meta(GG, &[kp(&bob), kp(&risi)], &official_meta(&["risi"]))
        .unwrap();
    alice.c.commit_accepted(GG).unwrap();
    bob.join_from_welcome(gc.welcome.as_ref().unwrap()).unwrap();

    let mut to_private = official_meta(&[]);
    to_private.tab = Some("private".into());
    let mut new_chat = official_meta(&["risi"]);
    new_chat.chat_id = Some("grp:elsewhere".into());
    for m in [&to_private, &new_chat] {
        assert!(alice.c.update_group_meta(GG, m).is_err());
        let (commit, _) = raw_change(&alice, &[], Some(m));
        assert!(matches!(
            bob.process(GG, &commit),
            Err(MlsError::PolicyViolation(_))
        ));
        alice.c.commit_rejected(GG).unwrap();
    }
}

#[test]
fn a_dm_group_never_holds_an_agent_leaf() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let risi = agent("risi", "r1");
    assert!(matches!(
        alice.create_group(G, &[kp(&bob), kp(&risi)]),
        Err(MlsError::PolicyViolation(_))
    ));
    group(&alice, &[&bob]);
    assert!(matches!(
        alice.add_members(G, &[kp(&risi)]),
        Err(MlsError::PolicyViolation(_))
    ));
    assert!(
        alice
            .members(G)
            .unwrap()
            .iter()
            .all(|m| m.kind == LeafKind::User)
    );
}

#[test]
fn a_one_to_one_official_has_no_name() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let risi = agent("risi", "r1");
    let meta = GroupMeta::from_bytes(
        br#"{"v":1,"name":null,"icon":null,"admins":["alice","bob"],"tab":"official","chat_id":"dm:alice_bob","agents":["risi"]}"#,
    )
    .unwrap();
    let gc = alice
        .create_group_with_meta(GG, &[kp(&bob), kp(&risi)], &meta)
        .unwrap();
    alice.commit_accepted(GG).unwrap();
    bob.join_from_welcome(gc.welcome.as_ref().unwrap()).unwrap();
    let m = bob.group_meta(GG).unwrap().unwrap();
    assert_eq!((m.name.as_str(), m.tab()), ("", Tab::Official));
    assert_eq!(m.chat_id.as_deref(), Some("dm:alice_bob"));
}

fn same(clients: &[&Client]) {
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
