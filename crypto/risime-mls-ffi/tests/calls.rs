//! Group call frame keys (contract v1.19 §20.6) across the FFI surface, as Android calls them, the
//! contract vectors through `callVectorsCheck`, and the check that the MLS exporter output never
//! crosses the FFI (§20.11).

use std::sync::Arc;

use uniffi_risime::{
    GroupMeta, InMemoryKvStore, MlsClient, RisiMlsError, TestAttestor, call_vectors_check,
    exporter_labels,
};

const G: &[u8] = b"grp:7a3e1c52-9d04-4b6f-8e21-5c0f3a9b7d18#1";
const CALL: &str = "4b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10";

fn client(a: &TestAttestor, user: &str, device: &str) -> Arc<MlsClient> {
    let c = MlsClient::open(
        InMemoryKvStore::new(),
        user.into(),
        device.into(),
        vec![a.public_jwk()],
    )
    .unwrap();
    c.set_attestation(
        a.attest(
            user.into(),
            device.into(),
            c.signature_public_key().unwrap(),
            1,
        )
        .unwrap(),
    )
    .unwrap();
    c
}

#[test]
fn call_frame_keys_cross_the_ffi() {
    let a = TestAttestor::from_seed(vec![9; 32]).unwrap();
    let (alice, bob, carol) = (
        client(&a, "alice", "a1"),
        client(&a, "bob", "b1"),
        client(&a, "carol", "c1"),
    );
    let gc = alice
        .create_group_with_meta(
            G.to_vec(),
            vec![bob.generate_key_packages(1).unwrap().remove(0)],
            GroupMeta {
                name: "call".into(),
                icon_json: None,
                admins: vec!["alice".into()],
                tab: None,
                chat_id: None,
                agents: None,
                chat_kind: None,
            },
        )
        .unwrap();
    alice.commit_accepted(G.to_vec()).unwrap();
    bob.join_from_welcome(gc.welcome.unwrap()).unwrap();

    let ka = alice.call_frame_keys(G.to_vec(), CALL.into()).unwrap();
    assert_eq!(ka, bob.call_frame_keys(G.to_vec(), CALL.into()).unwrap());
    assert_eq!((ka.epoch, ka.key_index), (1, 1));
    let ids: Vec<&str> = ka.keys.iter().map(|k| k.identity.as_str()).collect();
    assert_eq!(ids, ["alice/a1", "bob/b1"]);
    assert!(ka.keys.iter().all(|k| k.key.len() == 32));
    assert!(!format!("{ka:?}").contains(&format!("{:?}", ka.keys[0].key)));

    // Adding carol moves the epoch: new keys at the new index, carol included.
    let gc = alice
        .change_members(
            G.to_vec(),
            vec![carol.generate_key_packages(1).unwrap().remove(0)],
            vec![],
        )
        .unwrap();
    alice.commit_accepted(G.to_vec()).unwrap();
    bob.process(G.to_vec(), gc.commit).unwrap();
    carol.join_from_welcome(gc.welcome.unwrap()).unwrap();
    let k2 = carol.call_frame_keys(G.to_vec(), CALL.into()).unwrap();
    assert_eq!(k2, alice.call_frame_keys(G.to_vec(), CALL.into()).unwrap());
    assert_eq!(k2, bob.call_frame_keys(G.to_vec(), CALL.into()).unwrap());
    assert_eq!((k2.epoch, k2.key_index, k2.keys.len()), (2, 2, 3));
    assert_ne!(k2.keys[0].key, ka.keys[0].key);

    // Errors.
    assert!(matches!(
        alice.call_frame_keys(G.to_vec(), "nope".into()),
        Err(RisiMlsError::Malformed(_))
    ));
    assert!(matches!(
        alice.call_frame_keys(
            b"grp:7a3e1c52-9d04-4b6f-8e21-5c0f3a9b7d18#2".to_vec(),
            CALL.into()
        ),
        Err(RisiMlsError::UnknownGroup)
    ));
    assert!(matches!(
        alice.call_frame_keys(b"dm:alice_bob#1".to_vec(), CALL.into()),
        Err(RisiMlsError::Malformed(_))
    ));
}

#[test]
fn contract_vectors_pass_through_the_ffi() {
    let json = include_str!("../../../contract/v1/call_vectors.json");
    assert_eq!(call_vectors_check(json.into()).unwrap(), 29);
    let mut bad: serde_json::Value = serde_json::from_str(json).unwrap();
    bad["frame_keys"][0]["keys"][0]["frame_key"] = serde_json::json!("00".repeat(32));
    assert!(matches!(
        call_vectors_check(bad.to_string()),
        Err(RisiMlsError::Malformed(_))
    ));
    assert_eq!(exporter_labels(), ["risime-call-v1"]);
}

/// §20.11 / K1: the exporter output never crosses the FFI. Every export of the call section is
/// listed with what it returns; nothing named like the exporter exists anywhere in the binding.
#[test]
fn exporter_output_never_crosses_the_ffi() {
    let src = include_str!("../src/lib.rs");
    let section = &src[src
        .find("// Group call frame keys (contract v1.19")
        .unwrap()..];
    // Up to the next section (encrypted backups, v1.22, checked in tests/backup.rs).
    let section = &section[..section
        .find("// Encrypted backups (contract v1.22")
        .unwrap_or(section.len())];
    let mut exported: Vec<&str> = section
        .lines()
        .filter_map(|l| {
            l.trim_start()
                .strip_prefix("pub fn ")
                .map(|r| r.split('(').next().unwrap())
        })
        .collect();
    exported.sort_unstable();
    assert_eq!(
        exported,
        [
            "call_frame_keys",    // -> epoch, key_index, per-leaf frame keys (no call_secret)
            "call_vectors_check", // -> a count
            "exporter_labels",    // -> the registry's labels
        ]
    );
    let fields: Vec<&str> = section
        .lines()
        .filter_map(|l| l.trim_start().strip_prefix("pub "))
        .filter(|l| !l.starts_with("fn ") && !l.starts_with("struct "))
        .map(|l| l.split(':').next().unwrap())
        .collect();
    assert_eq!(fields, ["identity", "key", "epoch", "key_index", "keys"]);
    for name in ["export_secret", "call_secret", "exporter_secret"] {
        assert!(
            !src.contains(&format!("pub fn {name}")),
            "{name} is exported"
        );
        assert!(
            !src.contains(&format!("pub {name}:")),
            "a record exposes {name}"
        );
    }
}
