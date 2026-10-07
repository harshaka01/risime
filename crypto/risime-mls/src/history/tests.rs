//! History unit tests and the vector generator
//! (`cargo test -p risime-mls history_vectors_write -- --ignored` writes
//! `contract/v1/history_vectors.json`).
//!
//! The generator seals with a **hand-written RFC 9180 base-mode HPKE** whose ephemeral key is
//! `DeriveKeyPair(ikm_e)` (test builds only; production seals draw it from the OS inside hpke-rs).
//! Every positive case is then opened with the production path (OpenMLS' provider → hpke-rs), so
//! the two HPKE implementations cross-check each other.

use std::fs;
use std::path::PathBuf;

use aes_gcm::aead::{Aead, Payload};
use aes_gcm::{Aes128Gcm, KeyInit};
use hkdf::Hkdf;
use sha2::{Digest, Sha256};

use super::*;

fn tmpdir(tag: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!(
        "risime-history-unit-{tag}-{}-{:?}",
        std::process::id(),
        std::thread::current().id()
    ));
    fs::create_dir_all(&d).unwrap();
    d
}

/// Deterministic bytes: SHA-256 in counter mode over a label (the test-only seeded RNG, as for
/// the media vectors).
fn seeded(label: &str, len: usize) -> Vec<u8> {
    let mut out = Vec::with_capacity(len + 32);
    let mut ctr = 0u64;
    while out.len() < len {
        let mut h = Sha256::new();
        h.update(label.as_bytes());
        h.update(ctr.to_be_bytes());
        out.extend_from_slice(&h.finalize());
        ctr += 1;
    }
    out.truncate(len);
    out
}

fn seeded32(label: &str) -> [u8; 32] {
    seeded(label, 32).try_into().unwrap()
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

// ---- RFC 9180, base mode, DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 / AES-128-GCM -------------

const KEM_SUITE: &[u8] = b"KEM\x00\x20";
const HPKE_SUITE: &[u8] = b"HPKE\x00\x20\x00\x01\x00\x01";

fn labeled_extract(suite: &[u8], salt: &[u8], label: &str, ikm: &[u8]) -> [u8; 32] {
    let ikm = [b"HPKE-v1".as_slice(), suite, label.as_bytes(), ikm].concat();
    let (prk, _) = Hkdf::<Sha256>::extract(Some(salt), &ikm);
    prk.into()
}

fn labeled_expand(suite: &[u8], prk: &[u8], label: &str, info: &[u8], len: usize) -> Vec<u8> {
    let info = [
        &(len as u16).to_be_bytes()[..],
        b"HPKE-v1",
        suite,
        label.as_bytes(),
        info,
    ]
    .concat();
    let mut out = vec![0u8; len];
    Hkdf::<Sha256>::from_prk(prk)
        .unwrap()
        .expand(&info, &mut out)
        .unwrap();
    out
}

/// DeriveKeyPair (RFC 9180 §7.1.3, X25519): `(sk, pk)`.
fn derive_key_pair(ikm: &[u8]) -> ([u8; 32], [u8; 32]) {
    let prk = labeled_extract(KEM_SUITE, b"", "dkp_prk", ikm);
    let sk: [u8; 32] = labeled_expand(KEM_SUITE, &prk, "sk", b"", 32)
        .try_into()
        .unwrap();
    let pk = x25519_dalek::x25519(sk, x25519_dalek::X25519_BASEPOINT_BYTES);
    (sk, pk)
}

/// Single-shot base-mode seal with the ephemeral key from `ikm_e`. `None` for an all-zero DH.
fn reference_seal(
    pk_r: &[u8; 32],
    ikm_e: &[u8],
    info: &[u8],
    aad: &[u8],
    pt: &[u8],
) -> Option<([u8; 32], Vec<u8>)> {
    let (sk_e, enc) = derive_key_pair(ikm_e);
    let dh = x25519_dalek::x25519(sk_e, *pk_r);
    if dh == [0u8; 32] {
        return None;
    }
    let kem_context = [&enc[..], &pk_r[..]].concat();
    let eae_prk = labeled_extract(KEM_SUITE, b"", "eae_prk", &dh);
    let shared = labeled_expand(KEM_SUITE, &eae_prk, "shared_secret", &kem_context, 32);
    let psk_id_hash = labeled_extract(HPKE_SUITE, b"", "psk_id_hash", b"");
    let info_hash = labeled_extract(HPKE_SUITE, b"", "info_hash", info);
    let ks_context = [&[0u8][..], &psk_id_hash, &info_hash].concat();
    let secret = labeled_extract(HPKE_SUITE, &shared, "secret", b"");
    let key = labeled_expand(HPKE_SUITE, &secret, "key", &ks_context, 16);
    let nonce = labeled_expand(HPKE_SUITE, &secret, "base_nonce", &ks_context, 12);
    let ct = Aes128Gcm::new_from_slice(&key)
        .unwrap()
        .encrypt(nonce.as_slice().into(), Payload { msg: pt, aad })
        .unwrap();
    Some((enc, ct))
}

// ---- The generator -------------------------------------------------------------------------

const RID_1: &str = "6f1c2a7e-3b4d-4e8f-9a0b-1c2d3e4f5a6b";
const RID_2: &str = "0b9e8d7c-6f5a-4b3c-8d2e-1f0a9b8c7d6e";
const RID_OTHER: &str = "d3c2b1a0-9f8e-4d7c-ab6a-594837261504";
const CONV_DM: &str = "7a3e1c52-9d04-4b6f-8e21-5c0f3a9b7d18";
const CONV_GRP: &str = "grp:2c7d9e41-6a3b-4f58-9c0e-1b2a3d4c5e6f";

struct Case {
    name: &'static str,
    request_id: &'static str,
    conversation_id: &'static str,
    requester: &'static str,
    provider: &'static str,
    part: u32,
    parts: u32,
    plain: Vec<u8>,
}

fn cases() -> Vec<Case> {
    let bundle = concat!(
        r#"{"v":1,"type":"history_bundle","request_id":"6f1c2a7e-3b4d-4e8f-9a0b-1c2d3e4f5a6b","#,
        r#""conversation_id":"7a3e1c52-9d04-4b6f-8e21-5c0f3a9b7d18","provider":"u-alice/d-old","#,
        r#""part":1,"parts":1,"count":1}"#,
        "\n",
        r#"{"message_id":"c1a2b3e1-a0b1-11f0-8000-0242ac120002","#,
        r#""client_msg_id":"5e4d3c2b-1a09-4f8e-8d7c-6b5a49382716","from":"u-bob","#,
        r#""from_device":"d-bob","server_ts":"2026-10-01T09:30:00.000000Z","#,
        r#""payload":{"v":1,"type":"text","body":"see you at 10"}}"#,
        "\n"
    );
    vec![
        Case {
            name: "own_single_part",
            request_id: RID_1,
            conversation_id: CONV_DM,
            requester: "u-alice/d-new",
            provider: "u-alice/d-old",
            part: 1,
            parts: 1,
            plain: bundle.as_bytes().to_vec(),
        },
        Case {
            name: "member_part_1_of_2",
            request_id: RID_2,
            conversation_id: CONV_GRP,
            requester: "u-alice/d-new",
            provider: "u-carol/d-phone",
            part: 1,
            parts: 2,
            plain: seeded("risime-history-vector plain member_part_1_of_2", 100),
        },
        Case {
            name: "member_part_2_of_2",
            request_id: RID_2,
            conversation_id: CONV_GRP,
            requester: "u-alice/d-new",
            provider: "u-carol/d-phone",
            part: 2,
            parts: 2,
            plain: seeded("risime-history-vector plain member_part_2_of_2", 1),
        },
    ]
}

fn ctx_for(c: &Case, sha256: &[u8], plain_size: u64) -> HistoryContext {
    HistoryContext {
        request_id: c.request_id.into(),
        conversation_id: c.conversation_id.into(),
        requester: c.requester.into(),
        provider: c.provider.into(),
        part: c.part,
        parts: c.parts,
        sha256: sha256.to_vec(),
        plain_size,
    }
}

fn positive(dir: &Path, c: &Case) -> serde_json::Value {
    let ikm_r = seeded32(&format!("risime-history-vector ikm_r {}", c.name));
    let ikm_e = seeded32(&format!("risime-history-vector ikm_e {}", c.name));
    let k = seeded32(&format!("risime-history-vector k {}", c.name));
    let (rsk, rpk) = derive_key_pair(&ikm_r);
    let out = dir.join(c.name);
    let mut info_seen = Vec::new();
    let mut aad_seen = Vec::new();
    let sealed = seal_with_key(
        &rpk,
        &ctx_for(c, &[], 0),
        &c.plain,
        &out,
        &k,
        |info, aad, k| {
            info_seen = info.to_vec();
            aad_seen = aad.to_vec();
            let (enc, ct) = reference_seal(&rpk, &ikm_e, info, aad, k).unwrap();
            Ok((enc.to_vec(), ct))
        },
    )
    .unwrap();
    let cipher = fs::read(&out).unwrap();
    assert_eq!(sealed.size, cipher.len() as u64);
    serde_json::json!({
        "name": c.name,
        "ikm_r": hex(&ikm_r),
        "rsk": hex(&rsk),
        "rpk": hex(&rpk),
        "ikm_e": hex(&ikm_e),
        "request_id": c.request_id,
        "conversation_id": c.conversation_id,
        "requester": c.requester,
        "provider": c.provider,
        "part": c.part,
        "parts": c.parts,
        "plain_size": sealed.plain_size,
        "sha256": hex(&sealed.sha256),
        "info": hex(&info_seen),
        "aad": hex(&aad_seen),
        "k": hex(&k),
        "hpke_enc": hex(&sealed.hpke_enc),
        "sealed_key": hex(&sealed.sealed_key),
        "plain": hex(&c.plain),
        "cipher": hex(&cipher),
    })
}

fn negative(
    name: &str,
    base: &str,
    change: serde_json::Value,
    expect: &str,
    why: &str,
) -> serde_json::Value {
    serde_json::json!({"name": name, "base": base, "change": change, "expect": expect, "why": why})
}

fn aad_case(name: &str, rid: &str, bytes: &[u8], expect: &str) -> serde_json::Value {
    serde_json::json!({"name": name, "request_id": rid, "bytes": hex(bytes), "expect": expect})
}

fn build_vectors() -> serde_json::Value {
    let dir = tmpdir("vectors");
    let pos: Vec<serde_json::Value> = cases().iter().map(|c| positive(&dir, c)).collect();
    fs::remove_dir_all(&dir).unwrap();
    let s = |i: usize, f: &str| pos[i][f].as_str().unwrap().to_string();

    let h = crate::aad::encode_history_aad(RID_1).unwrap();
    let mut d = h.clone();
    d[1] = b'D';
    let mut v2 = h.clone();
    v2[0] = 0x02;
    let aad_h = vec![
        aad_case("ok", RID_1, &h, "ok"),
        aad_case(
            "ok_second",
            RID_2,
            &crate::aad::encode_history_aad(RID_2).unwrap(),
            "ok",
        ),
        aad_case("17_bytes", RID_1, &h[..17], "Malformed"),
        aad_case("19_bytes", RID_1, &[&h[..], &[0u8]].concat(), "Malformed"),
        aad_case("delete_prefix", RID_1, &d, "Malformed"),
        aad_case("wrong_version", RID_1, &v2, "Malformed"),
        aad_case("prefix_only", RID_1, &h[..2], "Malformed"),
        aad_case("empty", RID_1, &[], "Malformed"),
    ];

    let mut other_sha = hex::decode_vec(&s(0, "sha256"));
    other_sha[0] ^= 0x01;
    let sealed_key = s(0, "sealed_key");
    let mut tampered = hex::decode_vec(&s(2, "cipher"));
    let last = tampered.len() - 1;
    tampered[last] ^= 0x01;
    let tampered = hex(&tampered);
    let neg = vec![
        negative(
            "wrong_request_id",
            "own_single_part",
            serde_json::json!({"request_id": RID_OTHER}),
            "OpenFailed",
            "info carries another request_id",
        ),
        negative(
            "wrong_provider",
            "member_part_1_of_2",
            serde_json::json!({"provider": "u-mallory/d-phone"}),
            "OpenFailed",
            "info carries another provider identity (a member copying an honest provider's sealed key)",
        ),
        negative(
            "wrong_requester",
            "own_single_part",
            serde_json::json!({"requester": "u-alice/d-other"}),
            "OpenFailed",
            "info carries another requester identity",
        ),
        negative(
            "wrong_conversation",
            "own_single_part",
            serde_json::json!({"conversation_id": CONV_GRP}),
            "OpenFailed",
            "info carries another conversation_id",
        ),
        negative(
            "wrong_part_in_aad",
            "member_part_1_of_2",
            serde_json::json!({"part": 2}),
            "OpenFailed",
            "aad says part 2 of 2 for part 1's sealed key",
        ),
        negative(
            "wrong_parts_in_aad",
            "member_part_2_of_2",
            serde_json::json!({"parts": 3}),
            "OpenFailed",
            "aad says part 2 of 3",
        ),
        negative(
            "wrong_sha256_in_aad",
            "own_single_part",
            serde_json::json!({"sha256": hex(&other_sha)}),
            "OpenFailed",
            "aad carries another ciphertext digest (one bit flipped)",
        ),
        negative(
            "wrong_plain_size_in_aad",
            "member_part_1_of_2",
            serde_json::json!({"plain_size": 101}),
            "OpenFailed",
            "aad carries another plain_size (same Padmé bucket, same blob size)",
        ),
        negative(
            "swapped_hpke_enc",
            "member_part_1_of_2",
            serde_json::json!({"hpke_enc": s(2, "hpke_enc")}),
            "OpenFailed",
            "hpke_enc of member_part_2_of_2",
        ),
        negative(
            "swapped_sealed_key",
            "member_part_1_of_2",
            serde_json::json!({"sealed_key": s(2, "sealed_key")}),
            "OpenFailed",
            "sealed_key of member_part_2_of_2 (same request, same rpk)",
        ),
        negative(
            "truncated_sealed_key",
            "own_single_part",
            serde_json::json!({"sealed_key": sealed_key[..94].to_string()}),
            "Malformed",
            "sealed_key of 47 bytes (must be 48)",
        ),
        negative(
            "long_hpke_enc",
            "own_single_part",
            serde_json::json!({"hpke_enc": s(0, "hpke_enc") + "00"}),
            "Malformed",
            "hpke_enc of 33 bytes (must be 32)",
        ),
        negative(
            "low_order_rpk",
            "own_single_part",
            serde_json::json!({"rpk": "00".repeat(32)}),
            "SealRefused",
            "seal plain to the all-zero X25519 point: the DH output is all zero (RFC 9180 §7.1.4)",
        ),
        negative(
            "low_order_hpke_enc",
            "own_single_part",
            serde_json::json!({"hpke_enc": "00".repeat(32)}),
            "OpenFailed",
            "open with the all-zero X25519 point as hpke_enc: the DH output is all zero",
        ),
        negative(
            "wrong_rsk",
            "own_single_part",
            serde_json::json!({"rsk": s(1, "rsk")}),
            "OpenFailed",
            "rsk of member_part_1_of_2",
        ),
        negative(
            "media_label",
            "own_single_part",
            serde_json::json!({"label": "risime-media-v1"}),
            "OpenFailed",
            "the right K, but the blob opened with the risime-media-v1 label (HKDF info and segment AAD)",
        ),
        negative(
            "tampered_cipher",
            "member_part_2_of_2",
            serde_json::json!({ "cipher": tampered }),
            "OpenFailed",
            "one tag bit flipped (sha256 still the original's: the blob check fails)",
        ),
    ];

    serde_json::json!({
        "v": 1,
        "comment": "History-sharing vectors (contract v1.15 §17.3, crypto review R1). Generated by \
                    `cargo test -p risime-mls history_vectors_write -- --ignored` with seeded \
                    inputs; ikm_e makes the HPKE ephemeral key deterministic (RFC 9180 \
                    DeriveKeyPair; test builds only). All binary fields are lowercase hex. \
                    Positive: (rsk, rpk) = DeriveKeyPair(ikm_r); hpke_enc = the public key of \
                    DeriveKeyPair(ikm_e); info and aad as §17.3 from the case's fields; \
                    sealed_key = HPKE base-mode seal of k; cipher = A256GCM-S64K of plain under k \
                    with the label. Negative: the base case with `change` applied must fail with \
                    `expect`: a seal of `plain` to `rpk` when `change` sets rpk, otherwise an open \
                    (rsk, hpke_enc, sealed_key, info/aad fields, then the blob; `label` overrides \
                    the blob label).",
        "suite": {"mode": "base", "kem": 32, "kdf": 1, "aead": 1},
        "label": HISTORY_LABEL,
        "aad_h": aad_h,
        "positive": pos,
        "negative": neg,
    })
}

/// Tiny hex decoder for the generator.
mod hex {
    pub fn decode_vec(s: &str) -> Vec<u8> {
        (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
            .collect()
    }
}

fn contract_path() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../contract/v1/history_vectors.json")
}

/// Writes `contract/v1/history_vectors.json`.
#[test]
#[ignore = "writes the contract vector file; run with --ignored"]
fn history_vectors_write() {
    let v = build_vectors();
    fs::write(
        contract_path(),
        serde_json::to_string_pretty(&v).unwrap() + "\n",
    )
    .unwrap();
}

/// `contract/v1/history_vectors.json` is exactly what the generator produces.
#[test]
fn history_vectors_match_the_contract() {
    let have: serde_json::Value =
        serde_json::from_str(&fs::read_to_string(contract_path()).expect("contract vectors"))
            .unwrap();
    assert!(
        have == build_vectors(),
        "contract/v1/history_vectors.json differs from the core's vectors: regenerate it"
    );
}

/// Every case of the contract file passes the verifier the FFI exposes.
#[test]
fn every_contract_vector_passes() {
    let dir = tmpdir("check");
    let json = fs::read_to_string(contract_path()).unwrap();
    let n = check_vectors(&json, &dir).unwrap();
    let doc: serde_json::Value = serde_json::from_str(&json).unwrap();
    let total = ["aad_h", "positive", "negative"]
        .iter()
        .map(|k| doc[*k].as_array().unwrap().len())
        .sum::<usize>();
    assert_eq!(n as usize, total);
    assert!(fs::read_dir(&dir).unwrap().next().is_none(), "scratch left");
    fs::remove_dir_all(&dir).unwrap();
}

/// The verifier catches a wrong vector (it isn't vacuous).
#[test]
fn the_verifier_rejects_altered_vectors() {
    let dir = tmpdir("altered");
    let mut doc = build_vectors();
    doc["positive"][0]["sealed_key"] = serde_json::json!(doc["positive"][1]["sealed_key"]);
    assert!(check_vectors(&doc.to_string(), &dir).is_err());
    let mut doc = build_vectors();
    doc["negative"][0]["expect"] = serde_json::json!("Malformed");
    assert!(check_vectors(&doc.to_string(), &dir).is_err());
    let mut doc = build_vectors();
    doc["aad_h"][2]["expect"] = serde_json::json!("ok");
    assert!(check_vectors(&doc.to_string(), &dir).is_err());
    fs::remove_dir_all(&dir).unwrap();
}

/// Production seal (OS randomness, hpke-rs) opens with the reference-derived keys, and two seals
/// of the same part differ (fresh K, fresh ephemeral).
#[test]
fn production_seal_round_trip_and_freshness() {
    let dir = tmpdir("prod");
    let (rsk, rpk) = derive_key_pair(&seeded32("prod ikm_r"));
    let c = &cases()[0];
    let plain = seeded("prod plain", 200_000); // four segments
    let a = seal(&rpk, &ctx_for(c, &[], 0), &plain, &dir.join("a")).unwrap();
    let b = seal(&rpk, &ctx_for(c, &[], 0), &plain, &dir.join("b")).unwrap();
    assert_ne!(a.hpke_enc, b.hpke_enc);
    assert_ne!(a.sealed_key, b.sealed_key);
    assert_ne!(a.sha256, b.sha256);
    assert_eq!(a.plain_size, 200_000);
    assert_eq!(Some(a.size), media::cipher_size_for(200_000));
    let ctx = ctx_for(c, &a.sha256, a.plain_size);
    let got = open_with_rsk(
        &rsk,
        &ctx,
        &a.hpke_enc,
        &a.sealed_key,
        &dir.join("a"),
        HISTORY_BLOB_LABEL,
    )
    .unwrap();
    assert_eq!(&got[..], &plain[..]);
    // b's sealed key doesn't open a's blob.
    assert!(matches!(
        open_with_rsk(
            &rsk,
            &ctx,
            &b.hpke_enc,
            &b.sealed_key,
            &dir.join("a"),
            HISTORY_BLOB_LABEL
        ),
        Err(HistoryError::OpenFailed(_))
    ));
    // A media decrypt can't open a history blob even with the right K (labels differ): shown by
    // the media_label vector; here the reverse, a media blob under the history label.
    fs::write(dir.join("p"), b"a photo").unwrap();
    let m = media::encrypt_file(&dir.join("p"), &dir.join("m")).unwrap();
    assert!(
        media::decrypt_file_labeled(
            &dir.join("m"),
            &MediaRef {
                key: &m.key,
                alg: media::MEDIA_ALG,
                plain_size: m.plain_size,
                cipher_size: m.cipher_size,
                sha256: &m.sha256,
            },
            HISTORY_BLOB_LABEL
        )
        .is_err()
    );
    fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn seal_input_checks_and_cleanup() {
    let dir = tmpdir("checks");
    let (_, rpk) = derive_key_pair(&seeded32("checks"));
    let c = &cases()[1];
    let out = dir.join("x");
    let ctx = ctx_for(c, &[], 0);
    let malformed = |r: HistoryResult<SealedPart>| matches!(r, Err(HistoryError::Malformed(_)));
    assert!(malformed(seal(&rpk[..31], &ctx, b"x", &out)));
    assert!(malformed(seal(&rpk, &ctx, b"", &out)));
    for (part, parts) in [(0, 1), (2, 1), (1, 21), (21, 21)] {
        let mut bad = ctx.clone();
        bad.part = part;
        bad.parts = parts;
        assert!(malformed(seal(&rpk, &bad, b"x", &out)), "{part}/{parts}");
    }
    let mut bad = ctx.clone();
    bad.provider = "no-slash".into();
    assert!(malformed(seal(&rpk, &bad, b"x", &out)));
    let mut bad = ctx.clone();
    bad.request_id = "not-a-uuid".into();
    assert!(malformed(seal(&rpk, &bad, b"x", &out)));
    let mut bad = ctx.clone();
    bad.conversation_id = String::new();
    assert!(malformed(seal(&rpk, &bad, b"x", &out)));
    let mut bad = ctx.clone();
    bad.conversation_id = "c".repeat(70_000);
    assert!(malformed(seal(&rpk, &bad, b"x", &out)));
    let big = vec![0u8; MAX_HISTORY_PLAIN_SIZE as usize + 1];
    assert!(malformed(seal(&rpk, &ctx, &big, &out)));
    // A low-order rpk is refused and leaves no blob behind.
    assert!(matches!(
        seal(&[0u8; 32], &ctx, b"x", &out),
        Err(HistoryError::SealRefused(_))
    ));
    assert!(!out.exists());
    // The largest part seals.
    let max = vec![7u8; MAX_HISTORY_PLAIN_SIZE as usize];
    let s = seal(&rpk, &ctx, &max, &out).unwrap();
    assert_eq!(s.size, 16_519_104);
    fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn info_and_aad_layout() {
    let ctx = HistoryContext {
        request_id: RID_1.to_uppercase(),
        conversation_id: "c".into(),
        requester: "u/a".into(),
        provider: "u/b".into(),
        part: 3,
        parts: 20,
        sha256: vec![0xab; 32],
        plain_size: 0x0001_0203,
    };
    let info = ctx.info().unwrap();
    let mut want = b"risime-history-v1".to_vec();
    want.extend_from_slice(&crate::aad::parse_uuid(RID_1).unwrap());
    want.extend_from_slice(b"\x00\x01c\x00\x03u/a\x00\x03u/b");
    assert_eq!(info, want);
    let aad = ctx.aad().unwrap();
    assert_eq!(aad.len(), 44);
    assert_eq!(&aad[..4], &[0, 3, 0, 20]);
    assert_eq!(&aad[4..36], &[0xab; 32]);
    assert_eq!(&aad[36..], &[0, 0, 0, 0, 0, 1, 2, 3]);
}
