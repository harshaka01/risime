//! Contract v1.15 §17.3 (history sharing; crypto review R3, R4, R7) through the `Client`:
//! - `history_keygen` stores `rsk` transactionally; `history_forget`, `history_open_requests`;
//! - the provider seals, the requester opens with the stored key (own and member identities);
//! - the `'H'` AAD on `process_detailed`: no admin lookup (`None`, even without an admin record);
//! - own versus member from the MLS sender (`history_sender`).

mod common;

use std::path::PathBuf;
use std::sync::Arc;

use common::*;
use risime_mls::history::{self, HistoryContext, HistoryError};
use risime_mls::{
    Client, GroupMeta, Incoming, KvStore, MemoryKvStore, MlsError, decode_delete_aad,
    decode_history_aad, encode_delete_aad, encode_history_aad,
};

const GG: &[u8] = b"grp:2c7d9e41-6a3b-4f58-9c0e-1b2a3d4c5e6f#1";
const RID: &str = "6f1c2a7e-3b4d-4e8f-9a0b-1c2d3e4f5a6b";
const RID2: &str = "0b9e8d7c-6f5a-4b3c-8d2e-1f0a9b8c7d6e";

fn tmpdir(tag: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("risime-history-it-{tag}-{}", std::process::id()));
    std::fs::create_dir_all(&d).unwrap();
    d
}

fn make_group(creator: &Client, others: &[&Client]) {
    let kps: Vec<Vec<u8>> = others.iter().map(|c| kp(c)).collect();
    let gc = creator
        .create_group_with_meta(GG, &kps, &GroupMeta::new("History", vec!["alice".into()]))
        .unwrap();
    creator.commit_accepted(GG).unwrap();
    for o in others {
        o.join_from_welcome(gc.welcome.as_ref().unwrap()).unwrap();
    }
}

fn ctx(requester: &Client, provider: &Client, part: u32, parts: u32) -> HistoryContext {
    HistoryContext {
        request_id: RID.into(),
        conversation_id: "grp:2c7d9e41-6a3b-4f58-9c0e-1b2a3d4c5e6f".into(),
        requester: requester.identity(),
        provider: provider.identity(),
        part,
        parts,
        sha256: vec![],
        plain_size: 0,
    }
}

#[test]
fn keygen_seal_open_forget() {
    let dir = tmpdir("flow");
    let store = Arc::new(MemoryKvStore::new());
    let new_phone = client_on(store.clone(), "alice", "a-new");
    let old_phone = client("alice", "a-old");

    let rpk = new_phone.history_keygen(RID).unwrap();
    assert_eq!(rpk.len(), 32);
    assert_eq!(
        new_phone.history_public_key(RID).unwrap(),
        Some(rpk.clone())
    );
    assert_eq!(
        new_phone.history_public_key(&RID.to_uppercase()).unwrap(),
        Some(rpk.clone())
    );
    assert!(matches!(
        new_phone.history_keygen(&RID.to_uppercase()),
        Err(HistoryError::Malformed(_))
    ));
    assert_eq!(new_phone.history_open_requests().unwrap(), vec![RID]);
    assert_eq!(store.depth(), 0);

    // Two parts, each its own K and seal.
    let parts: Vec<Vec<u8>> = vec![vec![b'a'; 70_000], b"{\"v\":1}\n".to_vec()];
    let mut sealed = Vec::new();
    for (i, p) in parts.iter().enumerate() {
        let out = dir.join(format!("part{i}"));
        let s =
            history::seal(&rpk, &ctx(&new_phone, &old_phone, i as u32 + 1, 2), p, &out).unwrap();
        sealed.push((out, s));
    }
    assert_ne!(sealed[0].1.hpke_enc, sealed[1].1.hpke_enc);
    for (i, (path, s)) in sealed.iter().enumerate() {
        let mut c = ctx(&new_phone, &old_phone, i as u32 + 1, 2);
        c.sha256 = s.sha256.to_vec();
        c.plain_size = s.plain_size;
        let got = new_phone
            .history_open(RID, &c, &s.hpke_enc, &s.sealed_key, path)
            .unwrap();
        assert_eq!(&got[..], &parts[i][..]);
        // Another provider identity in the context (a cut-and-paste of the sealed key).
        let mut forged = c.clone();
        forged.provider = "mallory/m1".into();
        assert!(matches!(
            new_phone.history_open(RID, &forged, &s.hpke_enc, &s.sealed_key, path),
            Err(HistoryError::OpenFailed(_))
        ));
        // A context for another request is refused before any crypto.
        assert!(matches!(
            new_phone.history_open(RID2, &c, &s.hpke_enc, &s.sealed_key, path),
            Err(HistoryError::Malformed(_))
        ));
    }
    // Parts swapped: part 1's sealed key against part 2's blob and context.
    let mut c2 = ctx(&new_phone, &old_phone, 2, 2);
    c2.sha256 = sealed[1].1.sha256.to_vec();
    c2.plain_size = sealed[1].1.plain_size;
    assert!(matches!(
        new_phone.history_open(
            RID,
            &c2,
            &sealed[0].1.hpke_enc,
            &sealed[0].1.sealed_key,
            &sealed[1].0
        ),
        Err(HistoryError::OpenFailed(_))
    ));

    // Forget: the key is gone (also after reopening the store), opening says UnknownRequest.
    new_phone.history_forget(RID).unwrap();
    new_phone.history_forget(RID).unwrap();
    assert_eq!(new_phone.history_public_key(RID).unwrap(), None);
    assert!(new_phone.history_open_requests().unwrap().is_empty());
    drop(new_phone);
    let reopened = client_on(store.clone(), "alice", "a-new");
    let mut c = ctx(&reopened, &old_phone, 2, 2);
    c.sha256 = sealed[1].1.sha256.to_vec();
    c.plain_size = sealed[1].1.plain_size;
    assert_eq!(
        reopened.history_open(
            RID,
            &c,
            &sealed[1].1.hpke_enc,
            &sealed[1].1.sealed_key,
            &sealed[1].0
        ),
        Err(HistoryError::UnknownRequest)
    );
    // No history key material left in the store.
    let after = store.len();
    reopened.history_keygen(RID2).unwrap();
    reopened.history_forget(RID2).unwrap();
    assert_eq!(store.len(), after);
    std::fs::remove_dir_all(&dir).unwrap();
}

/// R7: the key is written in the caller's transaction; rolling that back leaves no `rsk`.
#[test]
fn keygen_rolls_back_with_the_callers_transaction() {
    let store = Arc::new(MemoryKvStore::new());
    let c = client_on(store.clone(), "alice", "a-new");
    let before = store.len();
    store.begin().unwrap();
    c.history_keygen(RID).unwrap();
    assert!(c.history_public_key(RID).unwrap().is_some());
    store.rollback().unwrap();
    assert_eq!(c.history_public_key(RID).unwrap(), None);
    assert!(c.history_open_requests().unwrap().is_empty());
    assert_eq!(store.len(), before);
    // Committed: kept, in keygen order.
    store.begin().unwrap();
    c.history_keygen(RID2).unwrap();
    c.history_keygen(RID).unwrap();
    store.commit().unwrap();
    assert_eq!(c.history_open_requests().unwrap(), vec![RID2, RID]);
    // A failing store write leaves nothing behind.
    let faulty = Arc::new(FaultyKv::new());
    let f = client_on(faulty.clone(), "alice", "a-x");
    faulty
        .fail_put
        .store(true, std::sync::atomic::Ordering::SeqCst);
    assert!(matches!(
        f.history_keygen(RID),
        Err(HistoryError::Storage(_))
    ));
    faulty
        .fail_put
        .store(false, std::sync::atomic::Ordering::SeqCst);
    assert_eq!(f.history_public_key(RID).unwrap(), None);
    assert_eq!(faulty.inner.depth(), 0);
}

/// R3: an `'H'` message skips the admin lookup; it gets `None` even at an epoch without an admin
/// record (where a `'D'` message fails `Malformed`), and its AAD decodes only as `'H'`.
#[test]
fn h_aad_skips_the_admin_check() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    make_group(&alice, &[&bob]);
    let h = encode_history_aad(RID).unwrap();
    let req = alice
        .encrypt_with_aad(GG, br#"{"v":1,"type":"history_request"}"#, &h)
        .unwrap();
    let del = alice
        .encrypt_with_aad(GG, b"d", &encode_delete_aad(&[RID.into()]).unwrap())
        .unwrap();
    // With a record: still None for 'H' (admin status is irrelevant), Some for 'D'.
    let early = alice.encrypt_with_aad(GG, b"h0", &h).unwrap();
    let d = bob
        .process_detailed(GG, &early)
        .unwrap()
        .application
        .unwrap();
    assert_eq!(d.sender_is_admin, None);

    let gc = alice.self_update(GG).unwrap();
    alice.commit_accepted(GG).unwrap();
    bob.process(GG, &gc.commit).unwrap();
    bob.downgrade_group_config_for_tests(GG).unwrap();

    let p = bob.process_detailed(GG, &req).unwrap();
    assert!(
        matches!(p.incoming, Incoming::Application { ref sender, .. } if *sender == dev("alice", "a1"))
    );
    let d = p.application.unwrap();
    assert_eq!(d.sender_is_admin, None);
    assert_eq!(decode_history_aad(&d.authenticated_data).unwrap(), RID);
    assert!(decode_delete_aad(&d.authenticated_data).is_err());
    assert!(matches!(
        bob.process_detailed(GG, &del),
        Err(MlsError::Malformed(_))
    ));
}

/// R4: own versus member from the MLS sender; S3: a sender that isn't a current leaf is refused.
#[test]
fn own_versus_member_from_the_mls_sender() {
    let a_old = client("alice", "a-old");
    let a_new = client("alice", "a-new");
    let bob = client("bob", "b1");
    let carol = client("carol", "c1");
    make_group(&a_old, &[&a_new, &bob]);

    let req = a_new
        .encrypt_with_aad(GG, b"req", &encode_history_aad(RID).unwrap())
        .unwrap();
    let Incoming::Application { sender, .. } = a_old.process(GG, &req).unwrap() else {
        panic!("not an application message");
    };
    let own = a_old.history_sender(GG, &sender).unwrap();
    assert!(own.own);
    assert_eq!(own.device, dev("alice", "a-new"));
    assert_eq!(own.signature_key, a_new.signature_public_key());
    let Incoming::Application { sender, .. } = bob.process(GG, &req).unwrap() else {
        panic!("not an application message");
    };
    let member = bob.history_sender(GG, &sender).unwrap();
    assert!(!member.own);
    assert_eq!(member.signature_key, a_new.signature_public_key());

    assert_eq!(
        a_old.history_sender(GG, carol.device()),
        Err(MlsError::UnknownMember)
    );
    assert!(matches!(
        a_old.history_sender(GG, a_old.device()),
        Err(MlsError::Malformed(_))
    ));
    assert_eq!(
        a_old.history_sender(b"grp:unknown#1", a_new.device()),
        Err(MlsError::UnknownGroup)
    );
}
