//! The verifier of `contract/v1/backup_vectors.json` (§22.9). Test support: it runs every case
//! through the production primitives and returns only the number of cases checked.
//!
//! Each array has one operation:
//! - `recovery_key`: parse `input` (→ `bytes`, `display`);
//! - `argon2`: the fixed-parameter check, then `password` from `secret` (hex for a recovery key,
//!   NFKC + trim for a passphrase) and `kek`;
//! - `wrap`: key check, AEAD open with `AAD_wrap(user_id, kind, bk_id)`, `bk_id` compare (→ `bk`);
//! - `dek`: AEAD open with `AAD_dek(backup_id, user_id)` (→ `dek`);
//! - `stream`: prefix, chunk layout, every chunk, then the padding with the given `d_len`
//!   (→ `padded`, `header_hash`, `prefix_and_header`).
//!
//! A `negative` case copies its `base` case, replaces the fields in `change` and must fail with
//! `expect`.

use serde_json::{Map, Value};

use super::keys::{self, KEY_LEN, NONCE_LEN, RECOVERY_LEN, SALT_LEN};
use super::stream;
use super::{BackupError, BackupResult};
use crate::aad::parse_uuid;

pub(crate) fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

pub(crate) fn unhex(s: &str) -> BackupResult<Vec<u8>> {
    if !s.len().is_multiple_of(2)
        || !s
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
    {
        return Err(BackupError::Malformed(format!(
            "not lowercase hex: {s:.16}"
        )));
    }
    (0..s.len())
        .step_by(2)
        .map(|i| {
            u8::from_str_radix(&s[i..i + 2], 16).map_err(|_| BackupError::Malformed("hex".into()))
        })
        .collect()
}

fn fixed<const N: usize>(c: &Map<String, Value>, f: &str) -> BackupResult<[u8; N]> {
    let b = unhex(text(c, f)?)?;
    b.try_into()
        .map_err(|_| BackupError::Malformed(format!("{f} is not {N} bytes")))
}

fn text<'a>(c: &'a Map<String, Value>, f: &str) -> BackupResult<&'a str> {
    c.get(f)
        .and_then(Value::as_str)
        .ok_or_else(|| BackupError::Malformed(format!("missing {f}")))
}

fn num(c: &Map<String, Value>, f: &str) -> BackupResult<u64> {
    c.get(f)
        .and_then(Value::as_u64)
        .ok_or_else(|| BackupError::Malformed(format!("missing {f}")))
}

/// The error's name in the vectors.
pub(crate) fn error_name(e: &BackupError) -> &'static str {
    match e {
        BackupError::Typo => "Typo",
        BackupError::WrongKey => "WrongKey",
        BackupError::Integrity(_) => "Integrity",
        BackupError::Format(_) => "Format",
        BackupError::Malformed(_) => "Malformed",
        BackupError::Unsupported(_) => "Unsupported",
        BackupError::WrongAccount => "WrongAccount",
        BackupError::NoKey(_) => "NoKey",
        BackupError::WeakPassphrase(_) => "WeakPassphrase",
        BackupError::Io(_) => "Io",
        BackupError::Storage(_) => "Storage",
    }
}

type Outputs = Vec<(&'static str, String)>;

fn op(array: &str, c: &Map<String, Value>) -> BackupResult<Outputs> {
    match array {
        "recovery_key" => {
            let r = keys::parse_recovery_key(text(c, "input")?)?;
            Ok(vec![
                ("bytes", hex(&r[..])),
                ("display", keys::recovery_display(&r)),
            ])
        }
        "argon2" => {
            let alg = c
                .get("alg")
                .and_then(Value::as_str)
                .unwrap_or(keys::ARGON2_ALG);
            keys::check_kdf_params(alg, num(c, "m")?, num(c, "t")?, num(c, "p")?)?;
            let secret = text(c, "secret")?;
            let password = match keys::SecretKind::parse(text(c, "kind")?)? {
                keys::SecretKind::RecoveryKey => {
                    let b = unhex(secret)?;
                    if b.len() != RECOVERY_LEN {
                        return Err(BackupError::Malformed("R is 15 bytes".into()));
                    }
                    b
                }
                keys::SecretKind::Passphrase => keys::passphrase_bytes(secret)?.to_vec(),
            };
            let kek = keys::derive_kek(&password, &fixed::<SALT_LEN>(c, "salt")?)?;
            Ok(vec![("password", hex(&password)), ("kek", hex(&kek[..]))])
        }
        "wrap" => {
            let kek = fixed::<KEY_LEN>(c, "kek")?;
            let (user, kind) = (text(c, "user_id")?, text(c, "kind")?);
            let bk_id = unhex(text(c, "bk_id")?)?;
            let nonce = unhex(text(c, "nonce")?)?;
            let bk = keys::open_wrap(
                &kek,
                user,
                kind,
                &bk_id,
                &nonce,
                &unhex(text(c, "wrapped")?)?,
                &unhex(text(c, "check")?)?,
            )?;
            let n: [u8; NONCE_LEN] = nonce.try_into().expect("checked by open_wrap");
            let (w, chk) = keys::seal_wrap(&kek, user, kind, &bk, &n)?;
            Ok(vec![
                ("bk", hex(&bk[..])),
                ("aad", hex(&keys::wrap_aad(user, kind, &bk_id)?)),
                ("wrapped", hex(&w)),
                ("check", hex(&chk)),
            ])
        }
        "dek" => {
            let bk = fixed::<KEY_LEN>(c, "bk")?;
            let bid = parse_uuid(text(c, "backup_id")?)
                .map_err(|_| BackupError::Malformed("backup_id".into()))?;
            let user = text(c, "user_id")?;
            let nonce = unhex(text(c, "nonce")?)?;
            let dek = keys::open_dek(&bk, &bid, user, &nonce, &unhex(text(c, "wrapped")?)?)?;
            let n: [u8; NONCE_LEN] = nonce.try_into().expect("checked by open_dek");
            Ok(vec![
                ("dek", hex(&dek[..])),
                ("aad", hex(&keys::dek_aad(&bid, user)?)),
                ("wrapped", hex(&keys::seal_dek(&bk, &bid, user, &dek, &n)?)),
            ])
        }
        "stream" => {
            let dek = fixed::<KEY_LEN>(c, "dek")?;
            let file = unhex(text(c, "file")?)?;
            let (p, hh, pl) = stream::open_stream_bytes(&dek, &file)?;
            stream::check_padding(&p, num(c, "d_len")?)?;
            Ok(vec![
                ("padded", hex(&p)),
                ("header_hash", hex(&hh)),
                ("prefix_and_header", hex(&file[..pl])),
            ])
        }
        other => Err(BackupError::Malformed(format!("unknown array {other}"))),
    }
}

const ARRAYS: [&str; 5] = ["recovery_key", "argon2", "wrap", "dek", "stream"];

/// Verifies every case of `backup_vectors.json`; returns the number of cases checked, or
/// `Malformed` naming the first failing case.
pub fn check_vectors(json: &str) -> BackupResult<u32> {
    let doc: Value =
        serde_json::from_str(json).map_err(|e| BackupError::Malformed(format!("json: {e}")))?;
    if doc.get("v").and_then(Value::as_u64) != Some(1) {
        return Err(BackupError::Malformed("vectors v".into()));
    }
    let fail = |name: &str, why: String| BackupError::Malformed(format!("case {name}: {why}"));
    let mut n = 0u32;
    for array in ARRAYS {
        let cases = doc
            .get(array)
            .and_then(Value::as_array)
            .ok_or_else(|| BackupError::Malformed(format!("missing {array}")))?;
        for case in cases {
            let c = case
                .as_object()
                .ok_or_else(|| BackupError::Malformed("case".into()))?;
            let name = text(c, "name")?;
            if c.get("expect").and_then(Value::as_str).unwrap_or("ok") != "ok" {
                return Err(fail(name, "positive cases expect ok".into()));
            }
            let outs = op(array, c).map_err(|e| fail(name, e.to_string()))?;
            for (f, v) in outs {
                if let Some(want) = c.get(f) {
                    if want.as_str() != Some(v.as_str()) {
                        return Err(fail(name, format!("{f} differs")));
                    }
                } else if !matches!(f, "wrapped" | "check") {
                    return Err(fail(name, format!("missing {f}")));
                }
            }
            n += 1;
        }
    }
    let negs = doc
        .get("negative")
        .and_then(Value::as_array)
        .ok_or_else(|| BackupError::Malformed("missing negative".into()))?;
    for neg in negs {
        let g = neg
            .as_object()
            .ok_or_else(|| BackupError::Malformed("case".into()))?;
        let name = text(g, "name")?;
        let base = text(g, "base")?;
        let (array, base_case) = ARRAYS
            .iter()
            .find_map(|a| {
                doc[*a].as_array().and_then(|cs| {
                    cs.iter()
                        .find(|c| c["name"].as_str() == Some(base))
                        .map(|c| (*a, c))
                })
            })
            .ok_or_else(|| fail(name, format!("unknown base {base}")))?;
        let mut c = base_case.as_object().expect("object").clone();
        let change = g
            .get("change")
            .and_then(Value::as_object)
            .ok_or_else(|| fail(name, "missing change".into()))?;
        if change.is_empty() {
            return Err(fail(name, "empty change".into()));
        }
        for (k, v) in change {
            c.insert(k.clone(), v.clone());
        }
        let expect = text(g, "expect")?;
        match op(array, &c) {
            Ok(_) => return Err(fail(name, format!("passed, expected {expect}"))),
            Err(e) if error_name(&e) == expect => {}
            Err(e) => return Err(fail(name, format!("{e}, expected {expect}"))),
        }
        n += 1;
    }
    Ok(n)
}
