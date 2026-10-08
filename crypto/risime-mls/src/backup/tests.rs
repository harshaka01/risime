//! Unit tests of the backup core, and the generator of `contract/v1/backup_vectors.json`
//! (`cargo test -p risime-mls backup_vectors_write -- --ignored`). `scripts/gen-backup-vectors`
//! is the independent Python reference; both write the same bytes.

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use aes_gcm::Nonce;
use aes_gcm::aead::{Aead, Payload};
use base64::Engine;
use base64::engine::general_purpose::STANDARD as B64;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

use super::keys::{self, CROCKFORD};
use super::stream::{self, CHUNK, CIPHER_CHUNK, PREFIX_LEN};
use super::vectors::{check_vectors, hex, unhex};
use super::*;
use crate::media::padme;
use crate::{Client, DeviceId, KvStore, MemoryKvStore, TestAttestor};

const USER: &str = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3";
const USER2: &str = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e";
const BACKUP_B: &str = "9a3f6c2d-7e1b-4c58-a0d4-3b2e1f0c9d87";
const CREATED: &str = "2026-10-08T02:00:00.000Z";
const APP: &str = "0.3.0-nightly.31";
const PASS: &str = "  Ｃｏｒｒｅｃｔ ﬁsh ｈｏｒｓｅ ｂａｔｔｅｒｙ ①\u{3000}";
const WRONG_PASS: &str = "Correct fish horse battery 2";
const STREAMS: [(&str, u64, &str); 4] = [
    ("stream_d1", 1, "6f1e2d3c-4b5a-4968-8776-a5b4c3d2e1f0"),
    (
        "stream_d65536",
        65_536,
        "2b8e4c1a-5d3f-4e6a-9b7c-8d1e2f3a4b5c",
    ),
    (
        "stream_d65537",
        65_537,
        "c3d4e5f6-a7b8-4c9d-8e0f-1a2b3c4d5e6f",
    ),
    (
        "stream_d200000",
        200_000,
        "8f7e6d5c-4b3a-4291-a0b1-c2d3e4f5a6b7",
    ),
];

/// SHA-256 in counter mode over a label (deterministic test bytes; as `scripts/gen-*-vectors`).
fn seeded(label: &str, n: usize) -> Vec<u8> {
    let mut out = Vec::with_capacity(n + 32);
    let mut ctr = 0u64;
    while out.len() < n {
        let mut h = Sha256::new();
        h.update(label.as_bytes());
        h.update(ctr.to_be_bytes());
        out.extend_from_slice(&h.finalize());
        ctr += 1;
    }
    out.truncate(n);
    out
}

fn arr<const N: usize>(v: &[u8]) -> [u8; N] {
    v.try_into().unwrap()
}

fn uuid_raw(s: &str) -> [u8; 16] {
    crate::aad::parse_uuid(s).unwrap()
}

// ------------------------------------------------------------------------------- generator

struct Gen {
    r1: [u8; 15],
    bk: [u8; 32],
    keks: Vec<(String, [u8; 32])>,
    wraps: Vec<(String, Value)>,
    record: Value,
}

fn display_sub(d: &str) -> String {
    let (mut k0, mut k1) = (0, 0);
    d.chars()
        .map(|c| match c {
            '0' => {
                k0 += 1;
                ['O', 'o'][(k0 - 1) % 2]
            }
            '1' => {
                k1 += 1;
                ['I', 'i', 'L', 'l'][(k1 - 1) % 4]
            }
            c => c,
        })
        .collect()
}

fn next_symbol(c: char) -> char {
    let i = CROCKFORD.iter().position(|&a| a as char == c).unwrap();
    CROCKFORD[(i + 1) % 32] as char
}

fn replace_at(s: &str, i: usize, c: char) -> String {
    let mut v: Vec<char> = s.chars().collect();
    v[i] = c;
    v.into_iter().collect()
}

fn seal_file(dek: &[u8; 32], header: &[u8], p: &[u8]) -> Vec<u8> {
    stream::seal_stream_bytes(dek, header, p)
}

/// The sealed chunk `i` of `p` with an explicit final flag.
fn sealed_chunk(dek: &[u8; 32], hh: &[u8; 32], i: u64, last: bool, p: &[u8]) -> Vec<u8> {
    let mut b = p.to_vec();
    b.extend_from_slice(&[0; 16]);
    stream::seal_chunk(&stream::stream_cipher(dek), hh, i, last, &mut b).unwrap();
    b
}

fn build_vectors() -> Value {
    let r1: [u8; 15] = arr(&seeded("risime-backup-vector R1", 15));
    let rc: [u8; 15] = arr(&unhex(&"0040100401".repeat(3)).unwrap());
    let d1 = keys::recovery_display(&r1);
    let dc = keys::recovery_display(&rc);
    let rk = |name: &str, r: &[u8; 15], input: String| {
        json!({"name": name, "bytes": hex(r), "display": keys::recovery_display(r),
               "input": input, "expect": "ok"})
    };
    let recovery = vec![
        rk("rk_round_trip", &r1, d1.clone()),
        rk(
            "rk_lowercase_spaces",
            &r1,
            d1.to_lowercase().replace('-', " "),
        ),
        rk("rk_no_separators", &r1, d1.replace('-', "")),
        rk("rk_confusables", &rc, display_sub(&dc)),
    ];

    let mut g = Gen {
        r1,
        bk: arr(&seeded("risime-backup-vector bk", 32)),
        keks: Vec::new(),
        wraps: Vec::new(),
        record: Value::Null,
    };
    let mut argon = Vec::new();
    for (name, kind, secret, salt_label) in [
        (
            "argon2_recovery_key",
            "recovery_key",
            hex(&g.r1),
            "recovery_key",
        ),
        (
            "argon2_passphrase_nfkc",
            "passphrase",
            PASS.to_string(),
            "passphrase",
        ),
        (
            "argon2_passphrase_wrong",
            "passphrase",
            WRONG_PASS.to_string(),
            "passphrase",
        ),
    ] {
        let salt: [u8; 16] = arr(&seeded(
            &format!("risime-backup-vector salt {salt_label}"),
            16,
        ));
        let password = if kind == "recovery_key" {
            g.r1.to_vec()
        } else {
            keys::passphrase_bytes(&secret).unwrap().to_vec()
        };
        let kek = keys::derive_kek(&password, &salt).unwrap();
        g.keks.push((name.into(), *kek));
        argon.push(
            json!({"name": name, "kind": kind, "alg": "argon2id", "secret": secret,
            "password": hex(&password), "salt": hex(&salt), "m": 65536, "t": 3, "p": 1,
            "kek": hex(&kek[..])}),
        );
    }
    let kek_of = |g: &Gen, n: &str| g.keks.iter().find(|(k, _)| k == n).unwrap().1;
    let bk_id = keys::bk_id(&g.bk);
    let mut wraps = Vec::new();
    let mut rec_wraps = Vec::new();
    for (kind, argon_name, salt_label) in [
        ("recovery_key", "argon2_recovery_key", "recovery_key"),
        ("passphrase", "argon2_passphrase_nfkc", "passphrase"),
    ] {
        let kek = kek_of(&g, argon_name);
        let nonce: [u8; 12] = arr(&seeded(
            &format!("risime-backup-vector wrap-nonce {kind}"),
            12,
        ));
        let (wrapped, check) = keys::seal_wrap(&kek, USER, kind, &g.bk, &nonce).unwrap();
        let w = json!({"name": format!("wrap_{kind}"), "user_id": USER, "kind": kind,
            "kek": hex(&kek), "bk": hex(&g.bk), "bk_id": hex(&bk_id), "nonce": hex(&nonce),
            "aad": hex(&keys::wrap_aad(USER, kind, &bk_id).unwrap()), "check": hex(&check),
            "wrapped": hex(&wrapped)});
        g.wraps.push((kind.into(), w.clone()));
        wraps.push(w);
        let salt = seeded(&format!("risime-backup-vector salt {salt_label}"), 16);
        rec_wraps.push(
            json!({"kind": kind, "kdf": {"alg": "argon2id", "m": 65536, "t": 3,
            "p": 1, "salt": B64.encode(&salt)}, "nonce": B64.encode(nonce),
            "wrapped": B64.encode(wrapped), "check": B64.encode(check)}),
        );
    }
    g.record = json!({"v": 1, "bk_id": B64.encode(bk_id), "wraps": rec_wraps});

    let dek_a: [u8; 32] = arr(&seeded("risime-backup-vector dek dek_a", 32));
    let dek_nonce: [u8; 12] = arr(&seeded("risime-backup-vector dek-nonce dek_a", 12));
    let dek_bid = STREAMS[0].2;
    let dek_wrapped = keys::seal_dek(&g.bk, &uuid_raw(dek_bid), USER, &dek_a, &dek_nonce).unwrap();
    let dek = vec![
        json!({"name": "dek_a", "bk": hex(&g.bk), "backup_id": dek_bid,
        "user_id": USER, "nonce": hex(&dek_nonce),
        "aad": hex(&keys::dek_aad(&uuid_raw(dek_bid), USER).unwrap()), "dek": hex(&dek_a),
        "wrapped": hex(&dek_wrapped)}),
    ];

    struct S {
        dek: [u8; 32],
        header: Vec<u8>,
        p: Vec<u8>,
        file: Vec<u8>,
        d_len: u64,
    }
    let mut ss: Vec<(&str, S)> = Vec::new();
    let mut streams = Vec::new();
    for (name, d_len, bid) in STREAMS {
        let dek: [u8; 32] = arr(&seeded(&format!("risime-backup-vector dek {name}"), 32));
        let nonce: [u8; 12] = arr(&seeded(
            &format!("risime-backup-vector dek-nonce {name}"),
            12,
        ));
        let wrapped = keys::seal_dek(&g.bk, &uuid_raw(bid), USER, &dek, &nonce).unwrap();
        let header = json!({"v": 1, "schema": 1, "backup_id": bid, "user_id": USER,
            "created_at": CREATED, "app_version": APP, "bk_id": B64.encode(bk_id),
            "dek": {"alg": "A256GCM", "nonce": B64.encode(nonce), "wrapped": B64.encode(wrapped)},
            "stream": {"alg": "A256GCM-STREAM64K", "compression": "deflate", "pad": "padme"},
            "key": if name == "stream_d1" { g.record.clone() } else { Value::Null }});
        let header = serde_json::to_vec(&header).unwrap();
        let mut p = seeded(&format!("risime-backup-vector d {name}"), d_len as usize);
        p.resize(padme(d_len) as usize, 0);
        let file = seal_file(&dek, &header, &p);
        let pl = PREFIX_LEN as usize + header.len();
        streams.push(json!({"name": name, "dek": hex(&dek),
            "prefix_and_header": hex(&file[..pl]),
            "header_hash": hex(&Sha256::digest(&file[..pl])), "d_len": d_len,
            "padded": hex(&p), "file": hex(&file)}));
        ss.push((
            name,
            S {
                dek,
                header,
                p,
                file,
                d_len,
            },
        ));
    }
    let s_of = |n: &str| &ss.iter().find(|(k, _)| *k == n).unwrap().1;

    let neg = |name: &str, base: &str, change: Value, expect: &str, why: &str| json!({"name": name, "base": base, "change": change, "expect": expect, "why": why});
    let mut negative = vec![
        neg(
            "rk_typo_one_char",
            "rk_round_trip",
            json!({"input": replace_at(&d1, 5, next_symbol(d1.chars().nth(5).unwrap()))}),
            "Typo",
            "one character of R changed: the checksum catches it",
        ),
        neg(
            "rk_typo_checksum",
            "rk_round_trip",
            json!({"input": replace_at(&d1, 33, next_symbol(d1.chars().nth(33).unwrap()))}),
            "Typo",
            "one checksum character changed",
        ),
        neg(
            "rk_27_chars",
            "rk_round_trip",
            json!({"input": &d1[..d1.len() - 1]}),
            "Malformed",
            "27 characters",
        ),
        neg(
            "rk_29_chars",
            "rk_round_trip",
            json!({"input": format!("{d1}0")}),
            "Malformed",
            "29 characters",
        ),
        neg(
            "rk_invalid_char",
            "rk_round_trip",
            json!({"input": format!("U{}", &d1[1..])}),
            "Malformed",
            "U is not in the Crockford alphabet",
        ),
        neg(
            "argon2_m_32768",
            "argon2_recovery_key",
            json!({"m": 32768}),
            "Malformed",
            "v1 accepts only m = 65536 (never weaker but accepted)",
        ),
        neg(
            "argon2_t_2",
            "argon2_recovery_key",
            json!({"t": 2}),
            "Malformed",
            "v1 accepts only t = 3",
        ),
        neg(
            "argon2_p_4",
            "argon2_recovery_key",
            json!({"p": 4}),
            "Malformed",
            "v1 accepts only p = 1",
        ),
        neg(
            "argon2_alg_argon2i",
            "argon2_recovery_key",
            json!({"alg": "argon2i"}),
            "Malformed",
            "v1 accepts only argon2id",
        ),
        neg(
            "wrap_wrong_passphrase",
            "wrap_passphrase",
            json!({"kek": hex(&kek_of(&g, "argon2_passphrase_wrong"))}),
            "WrongKey",
            "the KEK of another passphrase (argon2_passphrase_wrong): the key check fails",
        ),
        neg(
            "wrap_other_user_id",
            "wrap_recovery_key",
            json!({"user_id": USER2}),
            "Integrity",
            "opened with another user_id in the AAD",
        ),
        neg(
            "wrap_other_kind",
            "wrap_recovery_key",
            json!({"kind": "passphrase"}),
            "Integrity",
            "opened with the other kind in the AAD",
        ),
    ];
    {
        let other: [u8; 8] = arr(&seeded("risime-backup-vector other-bk-id", 8));
        let kek = kek_of(&g, "argon2_recovery_key");
        let nonce: [u8; 12] = arr(&seeded("risime-backup-vector wrap-nonce recovery_key", 12));
        let aad = keys::wrap_aad(USER, "recovery_key", &other).unwrap();
        let wrapped = keys::aes(&keys::wrap_key(&kek))
            .encrypt(
                Nonce::from_slice(&nonce),
                Payload {
                    msg: &g.bk,
                    aad: &aad,
                },
            )
            .unwrap();
        negative.push(neg(
            "wrap_bk_id_mismatch",
            "wrap_recovery_key",
            json!({"bk_id": hex(&other), "aad": hex(&aad), "wrapped": hex(&wrapped)}),
            "Integrity",
            "a wrap made with another bk_id in its AAD opens, but bk_id doesn't match the BK",
        ));
        let w = &g.wraps[0].1;
        let flip = |f: &str| {
            let mut b = unhex(w[f].as_str().unwrap()).unwrap();
            b[0] ^= 1;
            hex(&b)
        };
        negative.push(neg(
            "wrap_tampered_wrapped",
            "wrap_recovery_key",
            json!({"wrapped": flip("wrapped")}),
            "Integrity",
            "a flipped bit in wrapped",
        ));
        negative.push(neg(
            "wrap_tampered_check",
            "wrap_recovery_key",
            json!({"check": flip("check")}),
            "WrongKey",
            "a flipped bit in check",
        ));
    }
    negative.push(neg(
        "dek_other_backup_id",
        "dek_a",
        json!({"backup_id": BACKUP_B}),
        "Integrity",
        "the DEK wrap opened with another backup_id",
    ));
    negative.push(neg(
        "dek_other_user_id",
        "dek_a",
        json!({"user_id": USER2}),
        "Integrity",
        "the DEK wrap opened with another user_id",
    ));

    let s = s_of("stream_d65537");
    let pl = PREFIX_LEN as usize + s.header.len();
    let cc = CIPHER_CHUNK as usize;
    let (pre, c0, c1) = (&s.file[..pl], &s.file[pl..pl + cc], &s.file[pl + cc..]);
    let hh: [u8; 32] = Sha256::digest(pre).into();
    let other = s_of("stream_d200000");
    let opl = PREFIX_LEN as usize + other.header.len();
    let other_c0 = &other.file[opl..opl + cc];
    let flagged = sealed_chunk(&s.dek, &hh, 0, true, &s.p[..CHUNK as usize]);
    let mut bad_p = s.p.clone();
    bad_p[s.d_len as usize] = 1;
    let bad_pad_file = seal_file(&s.dek, &s.header, &bad_p);
    let d1s = s_of("stream_d1");
    let edit = |f: &dyn Fn(&mut Vec<u8>)| {
        let mut v = d1s.file.clone();
        f(&mut v);
        hex(&v)
    };
    let full = s_of("stream_d65536");
    let plus = |n: usize| {
        let mut v = full.file.clone();
        v.extend(std::iter::repeat_n(0u8, n));
        hex(&v)
    };
    negative.extend([
        neg(
            "stream_truncated_at_chunk_boundary",
            "stream_d65537",
            json!({"file": hex(&s.file[..pl + cc])}),
            "Integrity",
            "the last chunk removed: chunk 0 doesn't open as the final chunk",
        ),
        neg(
            "stream_chunks_swapped",
            "stream_d65537",
            json!({"file": hex(&[pre, c1, c0].concat())}),
            "Integrity",
            "chunks 0 and 1 swapped",
        ),
        neg(
            "stream_chunk_from_other_backup",
            "stream_d65537",
            json!({"file": hex(&[pre, other_c0, c1].concat())}),
            "Integrity",
            "chunk 0 replaced by chunk 0 of stream_d200000",
        ),
        neg(
            "stream_final_flag_on_non_final_chunk",
            "stream_d65537",
            json!({"file": hex(&[pre, &flagged, c1].concat())}),
            "Integrity",
            "chunk 0 sealed with the final flag",
        ),
        neg(
            "stream_header_byte_changed",
            "stream_d1",
            json!({"file": edit(&|v| {
                let i = v.windows(10).position(|w| w == b"nightly.31").unwrap();
                v[i + 9] = b'2';
            })}),
            "Integrity",
            "app_version 0.3.0-nightly.31 -> .32 in the header: header_hash changes",
        ),
        neg(
            "stream_bytes_appended",
            "stream_d65536",
            json!({"file": plus(32)}),
            "Integrity",
            "32 bytes after the last chunk (read as a 32-byte final chunk)",
        ),
        neg(
            "stream_last_chunk_16_bytes",
            "stream_d65536",
            json!({"file": plus(16)}),
            "Format",
            "16 bytes after the last chunk: a last chunk shorter than 17 bytes",
        ),
        neg(
            "stream_header_len_past_end",
            "stream_d1",
            json!({"file": edit(&|v| {
                let n = v.len() as u32;
                v[9..13].copy_from_slice(&n.to_be_bytes());
            })}),
            "Format",
            "header_len = the file length",
        ),
        neg(
            "stream_wrong_magic",
            "stream_d1",
            json!({"file": edit(&|v| v[7] = b'X')}),
            "Format",
            "magic RISIMEBX",
        ),
        neg(
            "stream_wrong_version",
            "stream_d1",
            json!({"file": edit(&|v| v[8] = 2)}),
            "Format",
            "format version 2",
        ),
        neg(
            "stream_no_chunks",
            "stream_d1",
            json!({"file": edit(&|v| v.truncate(PREFIX_LEN as usize + d1s.header.len()))}),
            "Format",
            "the header and no chunk",
        ),
        neg(
            "stream_nonzero_pad_byte",
            "stream_d65537",
            json!({"file": hex(&bad_pad_file), "padded": hex(&bad_p)}),
            "Format",
            "the first padding byte is 0x01 (validly encrypted)",
        ),
        neg(
            "stream_wrong_dek",
            "stream_d65537",
            json!({"dek": hex(&seeded("risime-backup-vector dek wrong", 32))}),
            "Integrity",
            "another DEK",
        ),
    ]);

    json!({
        "v": 1,
        "comment": "Encrypted backups (contract v1.22 §22.2, §22.4, §22.9). Generated by \
                    `cargo test -p risime-mls backup_vectors_write -- --ignored` and, independently, \
                    by scripts/gen-backup-vectors (Python cryptography + argon2-cffi); the two are \
                    byte-identical. All binary fields are lowercase hex. recovery_key: parse input \
                    -> bytes and display. argon2: check alg/m/t/p (only argon2id 65536/3/1), password \
                    = R (hex secret) or NFKC + trim of the passphrase, kek = Argon2id v0x13 L 32. \
                    wrap: key check (WrongKey), AES-256-GCM open with AAD_wrap(user_id, kind, bk_id) \
                    (Integrity), bk_id of the opened BK (Integrity). dek: open with \
                    AAD_dek(backup_id, user_id). stream: prefix (Format), chunk layout (Format), \
                    every chunk (Integrity), then |P| = Padme(d_len) and zero padding (Format); D is \
                    seeded bytes, not DEFLATE (d_len is given, so no inflate is needed). The stream \
                    sizes are |D| = 1, 65536, 65537, 200000 (|P| = 1, 65536, 67584, 200704). \
                    negative: copy the base case, replace the fields in change, expect that error.",
        "recovery_key": recovery,
        "argon2": argon,
        "wrap": wraps,
        "dek": dek,
        "stream": streams,
        "negative": negative,
    })
}

fn contract_path() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../contract/v1/backup_vectors.json")
}

fn render(v: &Value) -> String {
    serde_json::to_string_pretty(v).unwrap() + "\n"
}

/// Writes `contract/v1/backup_vectors.json`; `scripts/gen-backup-vectors --check` then verifies
/// it independently.
#[test]
#[ignore]
fn backup_vectors_write() {
    let v = build_vectors();
    let s = render(&v);
    check_vectors(&s).expect("generator output verifies");
    fs::write(contract_path(), s).unwrap();
}

#[test]
fn backup_vectors_match_the_contract() {
    let have = fs::read_to_string(contract_path()).expect("contract/v1/backup_vectors.json");
    assert_eq!(
        have,
        render(&build_vectors()),
        "contract/v1/backup_vectors.json differs from the core's generator"
    );
    assert_eq!(check_vectors(&have).unwrap(), 4 + 3 + 2 + 1 + 4 + 30);
}

#[test]
fn verifier_rejects_altered_vectors() {
    let good = build_vectors();
    let alter = |f: &dyn Fn(&mut Value)| {
        let mut v = good.clone();
        f(&mut v);
        check_vectors(&v.to_string())
    };
    let flip = |v: &mut Value| {
        let mut b = unhex(v.as_str().unwrap()).unwrap();
        b[0] ^= 1;
        *v = Value::String(hex(&b));
    };
    for r in [
        alter(&|v| flip(&mut v["argon2"][1]["kek"])),
        alter(&|v| flip(&mut v["wrap"][0]["bk"])),
        alter(&|v| flip(&mut v["dek"][0]["dek"])),
        alter(&|v| flip(&mut v["stream"][1]["header_hash"])),
        alter(&|v| flip(&mut v["stream"][2]["padded"])),
        alter(&|v| v["recovery_key"][0]["display"] = json!("0000-0000-0000-0000-0000-0000-0000")),
        alter(&|v| v["negative"][0]["expect"] = json!("Malformed")),
        alter(&|v| v["negative"][5]["change"] = json!({"m": 65536})),
        alter(&|v| v["negative"][0]["base"] = json!("nope")),
    ] {
        assert!(matches!(r, Err(BackupError::Malformed(_))), "{r:?}");
    }
}

// ------------------------------------------------------------------------------- keys

#[test]
fn recovery_key_format_and_parsing() {
    for i in 0..50u8 {
        let r: [u8; 15] = arr(&seeded(&format!("rk {i}"), 15));
        let d = keys::recovery_display(&r);
        assert_eq!(d.len(), 34);
        assert_eq!(d.split('-').count(), 7);
        assert!(d.split('-').all(|g| g.len() == 4));
        assert_eq!(*keys::parse_recovery_key(&d).unwrap(), r);
        assert_eq!(*keys::parse_recovery_key(&d.to_lowercase()).unwrap(), r);
        assert_eq!(
            *keys::parse_recovery_key(&format!(" {} \n", d.replace('-', "  "))).unwrap(),
            r
        );
        // Every single-character substitution is a typo.
        for pos in [0, 4 + 1, 17, 28, 33] {
            let c = d.chars().nth(pos).unwrap();
            let bad = replace_at(&d, pos, next_symbol(c));
            assert_eq!(
                keys::parse_recovery_key(&bad).unwrap_err(),
                BackupError::Typo
            );
        }
    }
    let rc: [u8; 15] = arr(&unhex(&"0040100401".repeat(3)).unwrap());
    let d = keys::recovery_display(&rc);
    assert!(d.starts_with("0101-0101-0101-0101-0101-0101-"));
    assert_eq!(*keys::parse_recovery_key(&display_sub(&d)).unwrap(), rc);
    for bad in [
        "",
        "0101",
        "0101-0101-0101-0101-0101-0101-01010",
        "ü101-0101-0101-0101-0101-0101-0101",
    ] {
        assert!(matches!(
            keys::parse_recovery_key(bad),
            Err(BackupError::Malformed(_))
        ));
    }
    assert_eq!(normalize_recovery_key(&d.to_lowercase()).unwrap(), d);
}

#[test]
fn passphrase_normalisation_and_floor() {
    assert_eq!(
        &keys::passphrase_bytes(PASS).unwrap()[..],
        "Correct fish horse battery 1".as_bytes()
    );
    assert!(matches!(
        keys::passphrase_bytes(" \u{3000}\t"),
        Err(BackupError::Malformed(_))
    ));
    use PassphraseFloor::*;
    assert_eq!(passphrase_floor("short one", None), TooShort);
    assert_eq!(passphrase_floor("   13 chars ok   ", None), TooShort);
    assert_eq!(passphrase_floor("fourteen chars", None), Ok);
    assert_eq!(passphrase_floor("ｆｏｕｒｔｅｅｎ ｃｈａｒｓ", None), Ok);
    assert_eq!(passphrase_floor("abc def ghi jkl", None), Ok);
    let phone = Some("+94 77 123 4567");
    assert_eq!(passphrase_floor("my secret 7712345 ok", phone), PhoneNumber);
    assert_eq!(
        passphrase_floor("my secret ７７１２３４ ok", phone),
        PhoneNumber
    );
    assert_eq!(passphrase_floor("my secret 77123 ok!!", phone), Ok);
    assert_eq!(passphrase_floor("my secret 234567 ok", phone), PhoneNumber);
}

#[test]
fn key_record_shapes() {
    let ex = include_str!("../../../../contract/v1/examples/backup_key_put.json");
    let r = KeyRecord::parse(ex).unwrap();
    assert_eq!(r.wraps.len(), 2);
    let reply = include_str!("../../../../contract/v1/examples/backup_key_reply.json");
    let v: Value = serde_json::from_str(reply).unwrap();
    KeyRecord::from_value(v["backup_key"].clone()).unwrap();
    let base: Value = serde_json::from_str(ex).unwrap();
    let alter = |f: &dyn Fn(&mut Value)| {
        let mut v = base.clone();
        f(&mut v);
        KeyRecord::parse(&v.to_string())
    };
    assert!(matches!(
        alter(&|v| v["v"] = json!(2)),
        Err(BackupError::Unsupported(_))
    ));
    for r in [
        alter(&|v| v["wraps"][0]["kdf"]["m"] = json!(32768)),
        alter(&|v| v["wraps"][1]["kdf"]["alg"] = json!("argon2i")),
        alter(&|v| v["wraps"][1]["kind"] = json!("recovery_key")),
        alter(&|v| v["wraps"][0]["kind"] = json!("passphrase")),
        alter(&|v| v["wraps"][0]["kind"] = json!("pin")),
        alter(&|v| v["wraps"] = json!([])),
        alter(&|v| {
            let w = v["wraps"][1].clone();
            v["wraps"].as_array_mut().unwrap().push(w);
        }),
        alter(&|v| v["bk_id"] = json!("AAAA")),
        alter(&|v| v["wraps"][0]["check"] = json!("AAAA")),
        alter(&|v| v["wraps"][0]["kdf"]["salt"] = json!("not base64!")),
        alter(&|v| v["wraps"][0]["nonce"] = json!(B64.encode([0u8; 16]))),
    ] {
        assert!(matches!(r, Err(BackupError::Malformed(_))), "{r:?}");
    }
}

// ------------------------------------------------------------------------------- files

fn client(user: &str, device: &str) -> Client {
    client_on(Arc::new(MemoryKvStore::new()), user, device)
}

fn client_on(store: Arc<MemoryKvStore>, user: &str, device: &str) -> Client {
    let a = TestAttestor::from_seed([7; 32]);
    Client::open(store, DeviceId::new(user, device).unwrap(), a.anchors()).unwrap()
}

fn tmpdir(tag: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("risime-backup-{tag}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&d);
    fs::create_dir_all(&d).unwrap();
    d
}

fn deflate(data: &[u8]) -> Vec<u8> {
    miniz_oxide::deflate::compress_to_vec(data, 6)
}

fn inflate(d: &[u8]) -> Vec<u8> {
    miniz_oxide::inflate::decompress_to_vec(d).unwrap()
}

/// Writes `bundle` (deflated, in odd-sized pieces) as a backup; returns the file and `D`.
fn write_backup(c: &Client, dir: &Path, name: &str, bundle: &[u8]) -> (PathBuf, Vec<u8>) {
    let d = deflate(bundle);
    let path = dir.join(name);
    let bid = format_uuid(&arr(&seeded(&format!("bid {name}"), 16)));
    let mut w = c
        .backup_writer(USER, &bid, CREATED, APP, None, &path)
        .unwrap();
    assert!(!path.exists(), "nothing at the path before finish");
    for piece in d.chunks(7_777) {
        w.write(piece).unwrap();
    }
    let out = w.finish().unwrap();
    let file = fs::read(&path).unwrap();
    assert_eq!(out.size, file.len() as u64);
    assert_eq!(out.sha256, <[u8; 32]>::from(Sha256::digest(&file)));
    assert_eq!(out.data_size, d.len() as u64);
    assert_eq!(out.backup_id, bid);
    (path, d)
}

fn read_all(c: &Client, path: &Path) -> BackupResult<Vec<u8>> {
    let mut r = c.backup_reader(USER, path, None, None)?;
    let v = r.verify()?;
    let mut out = Vec::new();
    loop {
        let b = r.read()?;
        if b.is_empty() {
            break;
        }
        out.extend_from_slice(&b);
    }
    assert_eq!(out.len() as u64, v.data_size);
    Ok(out)
}

#[test]
fn round_trips_at_chunk_edges() {
    let dir = tmpdir("rt");
    let c = client(USER, "d1");
    c.backup_setup(USER).unwrap();
    for (i, n) in [0usize, 1, 100, 65_530, 65_536, 65_537, 200_000, 1_100_000]
        .iter()
        .enumerate()
    {
        // Incompressible data gives |D| just above n (stored blocks); text compresses.
        let bundle: Vec<u8> = if i % 2 == 0 {
            seeded(&format!("bundle {n}"), *n)
        } else {
            (0..*n)
                .map(|k| b"{\"type\":\"message\"}\n"[k % 19])
                .collect()
        };
        let (path, d) = write_backup(&c, &dir, &format!("b{i}"), &bundle);
        let got = read_all(&c, &path).unwrap();
        assert_eq!(got, d, "size {n}");
        assert_eq!(inflate(&got), bundle);
        let file = fs::read(&path).unwrap();
        let pl = PREFIX_LEN + u64::from(u32::from_be_bytes(file[9..13].try_into().unwrap()));
        let pad = padme(d.len() as u64);
        assert_eq!(file.len() as u64, pl + pad + 16 * pad.div_ceil(CHUNK));
        let info = file_info(&path).unwrap();
        assert_eq!(info.user_id, USER);
        assert!(info.key_record.is_some());
    }
}

#[test]
fn empty_bundle_and_failed_writer() {
    let dir = tmpdir("empty");
    let c = client(USER, "d1");
    c.backup_setup(USER).unwrap();
    let p = dir.join("x");
    let mut w = c
        .backup_writer(USER, STREAMS[0].2, CREATED, APP, None, &p)
        .unwrap();
    assert!(matches!(w.finish(), Err(BackupError::Malformed(_))));
    // A failed writer is unusable and leaves no file.
    assert!(matches!(w.write(b"x"), Err(BackupError::Malformed(_))));
    assert!(!p.exists());
    assert!(fs::read_dir(&dir).unwrap().next().is_none());
    // Dropping an unfinished writer leaves nothing either.
    let mut w = c
        .backup_writer(USER, STREAMS[0].2, CREATED, APP, None, &p)
        .unwrap();
    w.write(&deflate(b"abc")).unwrap();
    drop(w);
    assert!(fs::read_dir(&dir).unwrap().next().is_none());
}

/// Crafts a file under `c`'s current `BK` with chosen chunks (each `(plaintext, final flag)`)
/// and DEK-wrap user.
fn craft(c: &Client, header_user: &str, aad_user: &str, chunks: &[(&[u8], bool)]) -> Vec<u8> {
    let bk = c.load_bk().unwrap().unwrap();
    let bid = STREAMS[1].2;
    let dek = [9u8; 32];
    let nonce = [3u8; 12];
    let wrapped = keys::seal_dek(&bk, &uuid_raw(bid), aad_user, &dek, &nonce).unwrap();
    let header = json!({"v": 1, "schema": 1, "backup_id": bid, "user_id": header_user,
        "created_at": CREATED, "app_version": APP, "bk_id": B64.encode(keys::bk_id(&bk)),
        "dek": {"alg": "A256GCM", "nonce": B64.encode(nonce), "wrapped": B64.encode(wrapped)},
        "stream": {"alg": "A256GCM-STREAM64K", "compression": "deflate", "pad": "padme"},
        "key": null});
    let header = serde_json::to_vec(&header).unwrap();
    let mut out = stream::prefix(header.len()).unwrap().to_vec();
    out.extend_from_slice(&header);
    let hh: [u8; 32] = Sha256::digest(&out).into();
    for (i, (p, last)) in chunks.iter().enumerate() {
        out.extend(sealed_chunk(&dek, &hh, i as u64, *last, p));
    }
    out
}

fn padded(d: &[u8]) -> Vec<u8> {
    let mut p = d.to_vec();
    p.resize(padme(d.len() as u64) as usize, 0);
    p
}

#[test]
fn every_tamper_case_fails_the_verify_pass() {
    let dir = tmpdir("tamper");
    let c = client(USER, "d1");
    c.backup_setup(USER).unwrap();
    let bundle = seeded("tamper bundle", 150_000);
    let (path, d) = write_backup(&c, &dir, "good", &bundle);
    let good = fs::read(&path).unwrap();
    let hl = u32::from_be_bytes(good[9..13].try_into().unwrap()) as usize;
    let pl = 13 + hl;
    let cc = CIPHER_CHUNK as usize;
    let n = (good.len() - pl).div_ceil(cc);
    assert_eq!(n, 3);
    let chunk = |i: usize| good[pl + i * cc..(pl + (i + 1) * cc).min(good.len())].to_vec();
    let check = |name: &str, bytes: Vec<u8>, want: &str| {
        let p = dir.join(name);
        fs::write(&p, &bytes).unwrap();
        let r = c.backup_reader(USER, &p, None, None).and_then(|mut r| {
            let v = r.verify();
            // Nothing is readable after a failed verify.
            if v.is_err() {
                assert!(r.read().is_err());
            }
            v
        });
        match r {
            Err(e) => assert_eq!(super::vectors::error_name(&e), want, "{name}: {e}"),
            Ok(_) => panic!("{name} verified"),
        }
    };
    // Header: one byte of created_at.
    let mut h = good.clone();
    let i = h.windows(4).position(|w| w == b"2026").unwrap();
    h[i + 3] = b'7';
    check("header", h, "Integrity");
    check(
        "swap",
        [&good[..pl], &chunk(1), &chunk(0), &chunk(2)].concat(),
        "Integrity",
    );
    check(
        "truncated_boundary",
        good[..pl + 2 * cc].to_vec(),
        "Integrity",
    );
    check(
        "truncated_mid",
        good[..good.len() - 100].to_vec(),
        "Integrity",
    );
    check(
        "dropped_middle",
        [&good[..pl], &chunk(0), &chunk(2)].concat(),
        "Integrity",
    );
    check("appended", [&good[..], &[0u8; 40]].concat(), "Integrity");
    check("appended_16", [&good[..], &[0u8; 16]].concat(), "Integrity");
    let mut bad_magic = good.clone();
    bad_magic[0] = b'X';
    check("magic", bad_magic, "Format");
    // Final flag stripped from the last chunk / set on a non-final one (validly sealed).
    let p = padded(&d);
    let pc: Vec<&[u8]> = p.chunks(CHUNK as usize).collect();
    let ok = craft(
        &c,
        USER,
        USER,
        &[(pc[0], false), (pc[1], false), (pc[2], true)],
    );
    let f = dir.join("crafted_ok");
    fs::write(&f, &ok).unwrap();
    assert_eq!(read_all(&c, &f).unwrap(), d);
    check(
        "last_flag_stripped",
        craft(
            &c,
            USER,
            USER,
            &[(pc[0], false), (pc[1], false), (pc[2], false)],
        ),
        "Integrity",
    );
    // Sealed by a BK holder with the final flag one chunk early: authentic, but the DEFLATE
    // stream doesn't end.
    check(
        "early_final",
        craft(&c, USER, USER, &[(pc[0], false), (pc[1], true)]),
        "Format",
    );
    check(
        "final_first",
        craft(
            &c,
            USER,
            USER,
            &[(pc[0], true), (pc[1], false), (pc[2], true)],
        ),
        "Integrity",
    );
    // A wrong user id in the DEK wrap's AAD.
    check(
        "dek_aad_user",
        craft(
            &c,
            USER,
            USER2,
            &[(pc[0], false), (pc[1], false), (pc[2], true)],
        ),
        "Integrity",
    );
    // A header of another account: nothing more is read.
    check(
        "other_account",
        craft(
            &c,
            USER2,
            USER2,
            &[(pc[0], false), (pc[1], false), (pc[2], true)],
        ),
        "WrongAccount",
    );
    // Nonzero padding and a non-DEFLATE stream.
    let mut bad = p.clone();
    *bad.last_mut().unwrap() = 1;
    let bc: Vec<&[u8]> = bad.chunks(CHUNK as usize).collect();
    check(
        "nonzero_pad",
        craft(
            &c,
            USER,
            USER,
            &[(bc[0], false), (bc[1], false), (bc[2], true)],
        ),
        "Format",
    );
    let junk = padded(&seeded("junk", 1000));
    check(
        "not_deflate",
        craft(&c, USER, USER, &[(&junk, true)]),
        "Format",
    );
    // |P| != Padmé(|D|): a valid stream followed by too much zero padding.
    let mut long = d.clone();
    long.resize(padme(d.len() as u64) as usize + 65_536, 0);
    let lc: Vec<&[u8]> = long.chunks(CHUNK as usize).collect();
    let n = lc.len();
    let chunks: Vec<(&[u8], bool)> = lc
        .iter()
        .enumerate()
        .map(|(i, x)| (*x, i == n - 1))
        .collect();
    check("over_padded", craft(&c, USER, USER, &chunks), "Format");
}

#[test]
fn read_needs_verify_and_detects_a_changed_file() {
    let dir = tmpdir("read");
    let c = client(USER, "d1");
    c.backup_setup(USER).unwrap();
    let (path, d) = write_backup(&c, &dir, "a", &seeded("read bundle", 100_000));
    let mut r = c.backup_reader(USER, &path, None, None).unwrap();
    assert!(matches!(r.read(), Err(BackupError::Malformed(_))));
    r.verify().unwrap();
    // The file is replaced by another valid backup after the verify pass.
    let (other, _) = write_backup(&c, &dir, "b", &seeded("read bundle", 100_000));
    fs::copy(&other, &path).unwrap();
    assert!(matches!(r.read(), Err(BackupError::Integrity(_))));
    // A fresh reader of an intact file returns exactly D.
    let (path, d2) = write_backup(&c, &dir, "c", &seeded("read bundle", 100_000));
    assert_eq!(read_all(&c, &path).unwrap(), d2);
    assert_ne!(d, Vec::<u8>::new());
}

#[test]
fn listing_checks_and_missing_key() {
    let dir = tmpdir("listing");
    let c = client(USER, "d1");
    c.backup_setup(USER).unwrap();
    let (path, _) = write_backup(&c, &dir, "a", b"hello");
    let info = file_info(&path).unwrap();
    c.backup_reader(
        USER,
        &path,
        Some(&info.backup_id.to_uppercase()),
        Some(&info.bk_id),
    )
    .unwrap();
    assert!(matches!(
        c.backup_reader(USER, &path, Some(BACKUP_B), None),
        Err(BackupError::Integrity(_))
    ));
    assert!(matches!(
        c.backup_reader(USER, &path, None, Some("AAAAAAAAAAA=")),
        Err(BackupError::Integrity(_))
    ));
    // Another device without the key: NoKey until it unlocks the header's record.
    let rk = c.backup_recovery_key().unwrap().unwrap();
    let c2 = client(USER, "d2");
    assert_eq!(
        c2.backup_reader(USER, &path, None, None).unwrap_err(),
        BackupError::NoKey(info.bk_id.clone())
    );
    c2.backup_unlock(
        USER,
        info.key_record.as_ref().unwrap(),
        &rk,
        SecretKind::RecoveryKey,
        true,
    )
    .unwrap();
    assert_eq!(read_all(&c2, &path).unwrap(), deflate(b"hello"));
    // Not a user of this device.
    assert!(matches!(
        c.backup_reader(USER2, &path, None, None),
        Err(BackupError::Malformed(_))
    ));
}

#[test]
fn rollback_leaves_no_key() {
    let store = Arc::new(MemoryKvStore::new());
    let c = client_on(store.clone(), USER, "d1");
    let before = store.len();
    store.begin().unwrap();
    c.backup_setup(USER).unwrap();
    assert!(c.load_bk().unwrap().is_some());
    store.rollback().unwrap();
    assert_eq!(store.len(), before);
    assert!(c.load_bk().unwrap().is_none());
    assert!(c.backup_recovery_key().unwrap().is_none());
    assert_eq!(store.depth(), 0);
}

#[test]
fn writer_uses_the_key_named_by_the_record() {
    let dir = tmpdir("keyring");
    let c = client(USER, "d1");
    let first = c.backup_setup(USER).unwrap();
    let (old_file, _) = write_backup(&c, &dir, "old", b"old");
    // The account key from another device replaces the local one; the old one stays by bk_id.
    let other = client(USER, "d2");
    let acct = other.backup_setup(USER).unwrap();
    let id = c
        .backup_unlock(
            USER,
            &acct.key_record,
            &acct.recovery_key,
            SecretKind::RecoveryKey,
            true,
        )
        .unwrap();
    let ids = c.backup_key_ids().unwrap();
    assert_eq!(ids.current.as_deref(), Some(id.as_str()));
    let first_id = KeyRecord::parse(&first.key_record).unwrap().bk_id;
    assert_eq!(ids.old, vec![first_id.clone()]);
    assert_eq!(
        *c.backup_recovery_key().unwrap().unwrap(),
        *acct.recovery_key
    );
    // Old files still open; new ones use the account key.
    assert_eq!(read_all(&c, &old_file).unwrap(), deflate(b"old"));
    let (new_file, _) = write_backup(&c, &dir, "new", b"new");
    assert_eq!(file_info(&new_file).unwrap().bk_id, id);
    assert_eq!(read_all(&other, &new_file).unwrap(), deflate(b"new"));
    // An explicit record selects its key; an unknown one is NoKey.
    let p = dir.join("explicit");
    let mut w = c
        .backup_writer(USER, BACKUP_B, CREATED, APP, Some(&first.key_record), &p)
        .unwrap();
    w.write(&deflate(b"x")).unwrap();
    w.finish().unwrap();
    assert_eq!(file_info(&p).unwrap().bk_id, first_id);
    c.backup_drop_key(&first_id).unwrap();
    assert!(matches!(
        c.backup_writer(USER, BACKUP_B, CREATED, APP, Some(&first.key_record), &p),
        Err(BackupError::NoKey(_))
    ));
    assert!(matches!(
        c.backup_reader(USER, &old_file, None, None),
        Err(BackupError::NoKey(_))
    ));
    assert!(matches!(
        c.backup_drop_key(&id),
        Err(BackupError::Malformed(_))
    ));
    // make_current = false keeps the unlocked key as an older one only.
    c.backup_unlock(
        USER,
        &first.key_record,
        &first.recovery_key,
        SecretKind::RecoveryKey,
        false,
    )
    .unwrap();
    let ids = c.backup_key_ids().unwrap();
    assert_eq!(ids.current.as_deref(), Some(id.as_str()));
    assert_eq!(ids.old, vec![first_id]);
    assert_eq!(read_all(&c, &old_file).unwrap(), deflate(b"old"));
    // forget wipes everything.
    c.backup_forget().unwrap();
    assert_eq!(
        c.backup_key_ids().unwrap(),
        BackupKeyIds {
            current: None,
            old: vec![]
        }
    );
    assert!(c.backup_key_record().unwrap().is_none());
}
