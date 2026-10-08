//! Contract v1.19 §20.6 (group calls; crypto review K1–K4, K10): `call_frame_keys` on live groups.
//! - every member derives the same keys for every leaf, `key_index = epoch mod 16`;
//! - the keys are exactly the §20.6 derivation from the group's **OpenMLS** exporter secret (read
//!   from the test store and recomputed here with RFC 9420 §8.5 by hand);
//! - a new epoch after add, remove and self-update gives new keys; a removed device gets none;
//! - another `call_id` gives other keys; bad input and DMs are refused.

mod common;

use std::sync::Arc;

use common::*;
use hkdf::Hkdf;
use openmls::prelude::GroupId;
use openmls_traits::storage::CURRENT_VERSION;
use risime_mls::{CallFrameKeys, Client, GroupCommit, GroupMeta, KvStore, MemoryKvStore, MlsError};
use sha2::{Digest, Sha256};

const GG: &[u8] = b"grp:2c7d9e41-6a3b-4f58-9c0e-1b2a3d4c5e6f#1";
const CALL: &str = "4b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10";
const CALL2: &str = "9a3f6c2d-7e1b-4c58-a0d4-3b2e1f0c9d87";

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

fn make_group(creator: &Client, others: &[&Client]) {
    let kps: Vec<Vec<u8>> = others.iter().map(|c| kp(c)).collect();
    let gc = creator
        .create_group_with_meta(GG, &kps, &GroupMeta::new("Call", vec!["alice".into()]))
        .unwrap();
    creator.commit_accepted(GG).unwrap();
    for o in others {
        o.join_from_welcome(gc.welcome.as_ref().unwrap()).unwrap();
    }
}

fn deliver(committer: &Client, gc: &GroupCommit, to: &[&Client]) {
    committer.commit_accepted(GG).unwrap();
    for c in to {
        c.process(GG, &gc.commit).unwrap();
    }
}

// ---- an independent RFC 9420 §8.5 exporter and the §20.6 frame key, by hand

fn expand(prk: &[u8], info: &[u8], len: usize) -> Vec<u8> {
    let mut out = vec![0; len];
    Hkdf::<Sha256>::from_prk(prk)
        .unwrap()
        .expand(info, &mut out)
        .unwrap();
    out
}

fn expand_with_label(secret: &[u8], label: &[u8], context: &[u8], len: usize) -> Vec<u8> {
    let full = [b"MLS 1.0 ".as_slice(), label].concat();
    assert!(full.len() < 64 && context.len() < 64);
    let mut info = (len as u16).to_be_bytes().to_vec();
    info.push(full.len() as u8);
    info.extend(&full);
    info.push(context.len() as u8);
    info.extend(context);
    expand(secret, &info, len)
}

fn exporter(exporter_secret: &[u8], label: &str, context: &[u8]) -> Vec<u8> {
    let derived = expand_with_label(exporter_secret, label.as_bytes(), b"", 32);
    expand_with_label(&derived, b"exported", &Sha256::digest(context), 32)
}

fn uuid_bytes(s: &str) -> Vec<u8> {
    let h: String = s.chars().filter(|c| *c != '-').collect();
    (0..32)
        .step_by(2)
        .map(|i| u8::from_str_radix(&h[i..i + 2], 16).unwrap())
        .collect()
}

/// The current epoch's exporter secret, straight out of OpenMLS's stored `GroupEpochSecrets`.
fn stored_exporter_secret(store: &MemoryKvStore) -> Vec<u8> {
    let mut key = b"mls/EpochSecrets/".to_vec();
    key.extend(serde_json::to_vec(&GroupId::from_slice(GG)).unwrap());
    key.extend(CURRENT_VERSION.to_be_bytes());
    let v: serde_json::Value =
        serde_json::from_slice(&store.get(&key).unwrap().expect("epoch secrets")).unwrap();
    let value = &v["exporter_secret"]["secret"]["value"];
    let bytes = value
        .as_array()
        .or_else(|| value["vec"].as_array())
        .unwrap_or_else(|| panic!("unexpected secret encoding {value}"));
    let out: Vec<u8> = bytes.iter().map(|b| b.as_u64().unwrap() as u8).collect();
    assert_eq!(out.len(), 32);
    out
}

fn expected(store: &MemoryKvStore, call_id: &str, identity: &str) -> Vec<u8> {
    let call_secret = exporter(
        &stored_exporter_secret(store),
        "risime-call-v1",
        &uuid_bytes(call_id),
    );
    let info = [b"risime-call-v1 frame\x00".as_slice(), identity.as_bytes()].concat();
    expand(&call_secret, &info, 32)
}

fn identities(k: &CallFrameKeys) -> Vec<String> {
    k.keys.iter().map(|k| k.identity.clone()).collect()
}

/// All of `devs` derive identical keys, matching the stored OpenMLS exporter secret.
fn agreed(devs: &[&Dev], call_id: &str) -> CallFrameKeys {
    let first = devs[0].c.call_frame_keys(GG, call_id).unwrap();
    let epoch = devs[0].c.epoch(GG).unwrap();
    assert_eq!(first.epoch, epoch);
    assert_eq!(first.key_index as u64, epoch % 16);
    let members: Vec<String> = devs[0]
        .c
        .members(GG)
        .unwrap()
        .iter()
        .map(|m| format!("{}/{}", m.user_id, m.device_id))
        .collect();
    assert_eq!(identities(&first), members);
    for d in devs {
        let k = d.c.call_frame_keys(GG, call_id).unwrap();
        assert_eq!(k, first, "{}", d.c.identity());
        for fk in &k.keys {
            assert_eq!(fk.key.len(), 32);
            assert_eq!(fk.key, expected(&d.store, call_id, &fk.identity));
        }
    }
    let mut distinct: Vec<&Vec<u8>> = first.keys.iter().map(|k| &k.key).collect();
    distinct.sort();
    distinct.dedup();
    assert_eq!(distinct.len(), first.keys.len());
    first
}

#[test]
fn members_agree_and_keys_follow_the_openmls_exporter() {
    let (a, b1, b2) = (devc("alice", "a1"), devc("bob", "b1"), devc("bob", "b2"));
    make_group(&a.c, &[&b1.c, &b2.c]);
    let k = agreed(&[&a, &b1, &b2], CALL);
    assert_eq!(k.epoch, 1);
    assert_eq!(k.key_index, 1);
    assert_eq!(identities(&k), ["alice/a1", "bob/b1", "bob/b2"]);

    // Another call in the same epoch: other keys. The call_id's case doesn't matter.
    let k2 = agreed(&[&a, &b1, &b2], CALL2);
    for (x, y) in k.keys.iter().zip(&k2.keys) {
        assert_ne!(x.key, y.key);
    }
    assert_eq!(a.c.call_frame_keys(GG, &CALL.to_uppercase()).unwrap(), k);
}

#[test]
fn every_epoch_change_rekeys() {
    let (a, b, c) = (devc("alice", "a1"), devc("bob", "b1"), devc("carol", "c1"));
    make_group(&a.c, &[&b.c]);
    let mut seen = vec![agreed(&[&a, &b], CALL)];

    // Add carol (epoch 2).
    let gc = a.c.change_members(GG, &[kp(&c.c)], &[]).unwrap();
    deliver(&a.c, &gc, &[&b.c]);
    c.c.join_from_welcome(gc.welcome.as_ref().unwrap()).unwrap();
    let k = agreed(&[&a, &b, &c], CALL);
    assert_eq!((k.epoch, k.key_index), (2, 2));
    assert_eq!(identities(&k), ["alice/a1", "bob/b1", "carol/c1"]);
    seen.push(k);

    // Self-update by bob (epoch 3): same members, new keys.
    let gc = b.c.self_update(GG).unwrap();
    deliver(&b.c, &gc, &[&a.c, &c.c]);
    let k = agreed(&[&a, &b, &c], CALL);
    assert_eq!(k.epoch, 3);
    assert_eq!(identities(&k), identities(&seen[1]));
    seen.push(k);

    // Remove carol (epoch 4): she gets no key, and can't derive any more.
    let gc = a.c.remove_users(GG, &["carol".into()]).unwrap();
    deliver(&a.c, &gc, &[&b.c, &c.c]);
    let k = agreed(&[&a, &b], CALL);
    assert_eq!(k.epoch, 4);
    assert_eq!(identities(&k), ["alice/a1", "bob/b1"]);
    assert!(matches!(
        c.c.call_frame_keys(GG, CALL),
        Err(MlsError::RemovedFromGroup | MlsError::UnknownGroup)
    ));
    seen.push(k);

    // No frame key repeats across epochs, even for the same identity.
    let mut all: Vec<&Vec<u8>> = seen
        .iter()
        .flat_map(|k| k.keys.iter().map(|x| &x.key))
        .collect();
    let n = all.len();
    all.sort();
    all.dedup();
    assert_eq!(all.len(), n);
}

#[test]
fn key_index_wraps_after_16_epochs() {
    let (a, b) = (devc("alice", "a1"), devc("bob", "b1"));
    make_group(&a.c, &[&b.c]);
    let first = agreed(&[&a, &b], CALL);
    for _ in 0..16 {
        let gc = a.c.self_update(GG).unwrap();
        deliver(&a.c, &gc, &[&b.c]);
    }
    let k = agreed(&[&a, &b], CALL);
    assert_eq!((k.epoch, k.key_index), (17, 1));
    assert_eq!(k.key_index, first.key_index);
    assert_ne!(k.keys[0].key, first.keys[0].key);
}

#[test]
fn refuses_bad_input() {
    let (a, b) = (devc("alice", "a1"), devc("bob", "b1"));
    make_group(&a.c, &[&b.c]);
    for bad in [
        "",
        "not-a-uuid",
        "4b7e1c1e3c0e4b559f430b8f8a1f2d10",
        &CALL[..35],
    ] {
        assert!(matches!(
            a.c.call_frame_keys(GG, bad),
            Err(MlsError::Malformed(_))
        ));
    }
    // No local state.
    assert_eq!(
        a.c.call_frame_keys(b"grp:2c7d9e41-6a3b-4f58-9c0e-1b2a3d4c5e6f#9", CALL),
        Err(MlsError::UnknownGroup)
    );
    // DMs keep the P2P path (§16, §19).
    let (x, y) = (client("alice", "a1"), client("bob", "b1"));
    group(&x, &[&y]);
    assert!(matches!(
        x.call_frame_keys(G, CALL),
        Err(MlsError::Malformed(_))
    ));
}

#[test]
fn deriving_changes_no_state() {
    let (a, b) = (devc("alice", "a1"), devc("bob", "b1"));
    make_group(&a.c, &[&b.c]);
    let before = a.c.epoch_authenticator(GG).unwrap();
    let k1 = a.c.call_frame_keys(GG, CALL).unwrap();
    let k2 = a.c.call_frame_keys(GG, CALL).unwrap();
    assert_eq!(k1, k2);
    assert_eq!(a.c.epoch_authenticator(GG).unwrap(), before);
    // Messaging continues unaffected.
    let ct = a.c.encrypt(GG, b"hi").unwrap();
    assert_eq!(b.c.decrypt(GG, &ct).unwrap(), b"hi");
}
