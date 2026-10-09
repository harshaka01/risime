//! History sharing (contract v1.15 §17.3) across the FFI surface, as Android calls it, and the
//! check that `rsk` and `K` are not reachable from any exported history function.

use std::fs;

use uniffi_risime::{
    DeviceId, HistoryContext, InMemoryKvStore, KvStore, MlsClient, RisiHistoryError, RisiMlsError,
    TestAttestor, history_aad_decode, history_aad_encode, history_limits, history_seal,
    history_vectors_check,
};

const RID: &str = "6f1c2a7e-3b4d-4e8f-9a0b-1c2d3e4f5a6b";

fn client(
    store: std::sync::Arc<InMemoryKvStore>,
    a: &TestAttestor,
    user: &str,
    device: &str,
) -> std::sync::Arc<MlsClient> {
    let c = MlsClient::open(store, user.into(), device.into(), vec![a.public_jwk()]).unwrap();
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
fn history_round_trip_crosses_the_ffi() {
    let dir = std::env::temp_dir().join(format!("risime-history-ffi-{}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();
    let p = |n: &str| dir.join(n).to_str().unwrap().to_string();
    let a = TestAttestor::from_seed(vec![9; 32]).unwrap();
    let store = InMemoryKvStore::new();
    let new_phone = client(store.clone(), &a, "alice", "a-new");
    let old_phone = client(InMemoryKvStore::new(), &a, "alice", "a-old");
    let bob = client(InMemoryKvStore::new(), &a, "bob", "b1");

    // The request: keygen inside the app's transaction, the 'H' AAD on the envelope.
    store.begin().unwrap();
    let rpk = new_phone.history_keygen(RID.into()).unwrap();
    store.commit().unwrap();
    assert_eq!(rpk.len(), history_limits().rpk_len as usize);
    assert_eq!(
        new_phone.history_public_key(RID.into()).unwrap(),
        Some(rpk.clone())
    );
    let aad = history_aad_encode(RID.to_uppercase()).unwrap();
    assert_eq!(aad.len(), 18);
    assert_eq!(history_aad_decode(aad.clone()).unwrap(), RID);
    assert!(matches!(
        history_aad_decode(aad[..17].to_vec()),
        Err(RisiMlsError::Malformed(_))
    ));

    // Own versus member from the MLS sender.
    let g = b"grp:7a3e1c52-9d04-4b6f-8e21-5c0f3a9b7d18#1".to_vec();
    let gc = old_phone
        .create_group_with_meta(
            g.clone(),
            vec![
                new_phone.generate_key_packages(1).unwrap().remove(0),
                bob.generate_key_packages(1).unwrap().remove(0),
            ],
            uniffi_risime::GroupMeta {
                name: "h".into(),
                icon_json: None,
                admins: vec!["alice".into()],
                tab: None,
                chat_id: None,
                agents: None,
                chat_kind: None,
            },
        )
        .unwrap();
    old_phone.commit_accepted(g.clone()).unwrap();
    new_phone
        .join_from_welcome(gc.welcome.clone().unwrap())
        .unwrap();
    bob.join_from_welcome(gc.welcome.unwrap()).unwrap();
    let req = new_phone
        .encrypt_with_aad(g.clone(), b"{}".to_vec(), aad.clone())
        .unwrap();
    let pm = old_phone.process_detailed(g.clone(), req.clone()).unwrap();
    let d = pm.application.unwrap();
    assert_eq!(d.sender_is_admin, None);
    assert_eq!(history_aad_decode(d.authenticated_data).unwrap(), RID);
    let sender = DeviceId {
        user_id: "alice".into(),
        device_id: "a-new".into(),
    };
    let s = old_phone.history_sender(g.clone(), sender.clone()).unwrap();
    assert!(s.own);
    assert_eq!(s.signature_key, new_phone.signature_public_key().unwrap());
    bob.process(g.clone(), req).unwrap();
    assert!(!bob.history_sender(g.clone(), sender).unwrap().own);

    // The provider seals; the requester opens.
    let mut ctx = HistoryContext {
        request_id: RID.into(),
        conversation_id: "grp:7a3e1c52-9d04-4b6f-8e21-5c0f3a9b7d18".into(),
        requester: new_phone.identity().unwrap(),
        provider: old_phone.identity().unwrap(),
        part: 1,
        parts: 1,
        sha256: vec![],
        plain_size: 0,
    };
    let plain = b"{\"v\":1,\"type\":\"history_bundle\"}\n".to_vec();
    let sealed = history_seal(rpk.clone(), ctx.clone(), plain.clone(), p("part1")).unwrap();
    assert_eq!((sealed.hpke_enc.len(), sealed.sealed_key.len()), (32, 48));
    assert_eq!(fs::metadata(p("part1")).unwrap().len(), sealed.size);
    ctx.sha256 = sealed.sha256.clone();
    ctx.plain_size = sealed.plain_size;
    let got = new_phone
        .history_open(
            RID.into(),
            ctx.clone(),
            sealed.hpke_enc.clone(),
            sealed.sealed_key.clone(),
            p("part1"),
        )
        .unwrap();
    assert_eq!(got, plain);
    let mut wrong = ctx.clone();
    wrong.provider = "bob/b1".into();
    assert!(matches!(
        new_phone.history_open(
            RID.into(),
            wrong,
            sealed.hpke_enc.clone(),
            sealed.sealed_key.clone(),
            p("part1")
        ),
        Err(RisiHistoryError::OpenFailed(_))
    ));
    assert!(matches!(
        history_seal(vec![0; 32], ctx.clone(), plain.clone(), p("x")),
        Err(RisiHistoryError::SealRefused(_))
    ));

    assert_eq!(new_phone.history_open_requests().unwrap(), vec![RID]);
    new_phone.history_forget(RID.into()).unwrap();
    assert!(matches!(
        new_phone.history_open(
            RID.into(),
            ctx,
            sealed.hpke_enc,
            sealed.sealed_key,
            p("part1")
        ),
        Err(RisiHistoryError::UnknownRequest)
    ));
    assert_eq!(store.depth(), 0);
    fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn contract_vectors_pass_through_the_ffi() {
    let dir = std::env::temp_dir().join(format!("risime-history-ffi-v-{}", std::process::id()));
    fs::create_dir_all(&dir).unwrap();
    let json = fs::read_to_string(
        std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../contract/v1/history_vectors.json"),
    )
    .unwrap();
    let n = history_vectors_check(json, dir.to_str().unwrap().into()).unwrap();
    assert_eq!(n, 28, "8 aad_h + 3 positive + 17 negative");
    fs::remove_dir_all(&dir).unwrap();
}

/// R7: no exported history function or record carries `rsk` or `K`. Every history export is
/// listed here with what it may return; a new export (or a new field) fails this test until it is
/// reviewed and added.
#[test]
fn rsk_and_k_are_not_reachable_from_the_ffi() {
    let src = include_str!("../src/lib.rs");
    let section = &src[src.find("// History sharing (contract v1.15").unwrap()..];
    // Up to the next section (group call keys, v1.19, checked in tests/calls.rs).
    let section = &section[..section
        .find("// Group call frame keys")
        .unwrap_or(section.len())];
    let mut exported: Vec<&str> = section
        .lines()
        .filter_map(|l| {
            let l = l.trim_start();
            l.strip_prefix("pub fn ")
                .map(|r| r.split('(').next().unwrap())
        })
        .collect();
    exported.sort_unstable();
    assert_eq!(
        exported,
        [
            "history_aad_decode",    // -> request_id
            "history_aad_encode",    // -> 18 public bytes
            "history_forget",        // -> ()
            "history_keygen",        // -> rpk only
            "history_limits",        // -> constants
            "history_open",          // -> verified plaintext
            "history_open_requests", // -> request ids
            "history_public_key",    // -> rpk only
            "history_seal",          // -> SealedPart (no K)
            "history_sender",        // -> identity + public signature key
            "history_vectors_check", // -> a count
        ]
    );
    let fields: Vec<&str> = section
        .lines()
        .filter_map(|l| l.trim_start().strip_prefix("pub "))
        .filter(|l| !l.starts_with("fn ") && !l.starts_with("struct ") && !l.starts_with("enum "))
        .map(|l| l.split(':').next().unwrap())
        .collect();
    for f in &fields {
        assert!(
            !["rsk", "k", "key", "secret", "private"].contains(f),
            "a history record exposes {f}"
        );
    }
}
