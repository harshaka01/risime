//! Encrypted backups (contract v1.22 §22, decision 059; crypto review C1–C9).
//!
//! Keys (§22.2):
//! - `BK`: 32 random bytes per account, kept in the app's sealed [`crate::KvStore`]
//!   (`risime/backup/bk`); older local `BK`s are kept by `bk_id` while local files need them.
//! - `R`: the 120-bit recovery key (`risime/backup/recovery`), shown as 7 groups of 4 Crockford
//!   characters with a 20-bit checksum ([`keys::recovery_display`]).
//! - The key record (`BackupKey` JSON, `risime/backup/record`): `BK` wrapped under
//!   `Argon2id(R)` and optionally `Argon2id(passphrase)`, with HKDF-separated wrap key and key
//!   check, AAD bound to user, kind and `bk_id`.
//! - A fresh `DEK` per backup, wrapped under `BK` into the file header.
//!
//! `BK`, `KEK` and `DEK` never leave the core: no public function returns them and none takes
//! them (except [`check_vectors`], which verifies the contract's test vectors and returns only a
//! count). All randomness comes from the OS CSPRNG here. `R` leaves only as its display string.
//!
//! Files (§22.4): [`Client::backup_writer`] streams the app's DEFLATE output into a `RISIMEBK`
//! file; [`Client::backup_reader`] opens one, [`BackupReader::verify`] runs the whole verify pass
//! and only then [`BackupReader::read`] returns the DEFLATE stream.

pub mod keys;
pub mod stream;
mod vectors;

#[cfg(test)]
mod tests;

use std::path::Path;

use base64::Engine;
use base64::engine::general_purpose::STANDARD as B64;
use serde::{Deserialize, Serialize};
use zeroize::Zeroizing;

use crate::aad::{format_uuid, parse_uuid};
use crate::{Client, MlsError};

pub use keys::{
    PassphraseFloor, SecretKind, parse_recovery_key, passphrase_floor, recovery_display,
};
pub use stream::{BackupReader, BackupVerified, BackupWriter, BackupWritten};
pub use vectors::check_vectors;

use keys::{BK_ID_LEN, KEY_LEN, NONCE_LEN, RECOVERY_LEN, SALT_LEN};
use stream::{DekHeader, FileHeader, StreamHeader};

/// Backup failures. Kotlin: `RisiBackupException`.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum BackupError {
    /// The recovery key's checksum doesn't match: a typo (before any key derivation).
    #[error("that recovery key has a typo")]
    Typo,
    /// The key check failed: "That recovery key or passphrase doesn't match".
    #[error("that recovery key or passphrase doesn't match")]
    WrongKey,
    /// A wrap, the DEK or a chunk failed to verify (tampering, truncation, reordering, another
    /// backup's chunk, a changed header, a mismatching `bk_id` or `backup_id`).
    #[error("backup integrity check failed: {0}")]
    Integrity(String),
    /// The file is malformed (magic, version, header length, chunk layout, DEFLATE end, padding).
    #[error("malformed backup file: {0}")]
    Format(String),
    /// Bad input (an id, a length, a key record shape, KDF parameters other than v1's, a
    /// recovery key that isn't 28 characters).
    #[error("malformed backup input: {0}")]
    Malformed(String),
    /// A newer file or record (`v`, `schema`, algorithms): "Update RisiMe to restore this backup".
    #[error("unsupported backup: {0}")]
    Unsupported(String),
    /// The file's `user_id` isn't the signed-in user: "This backup belongs to another account".
    #[error("this backup belongs to another account")]
    WrongAccount,
    /// No local `BK` for this `bk_id` (base64): unlock the key record first.
    #[error("no backup key {0} on this device")]
    NoKey(String),
    /// The passphrase is below the floor (§22.2).
    #[error("passphrase too weak: {0}")]
    WeakPassphrase(String),
    #[error("backup io: {0}")]
    Io(String),
    #[error("storage: {0}")]
    Storage(String),
}

pub type BackupResult<T> = std::result::Result<T, BackupError>;

fn malformed(s: impl Into<String>) -> BackupError {
    BackupError::Malformed(s.into())
}

fn st(e: impl std::fmt::Display) -> BackupError {
    BackupError::Storage(e.to_string())
}

impl From<MlsError> for BackupError {
    fn from(e: MlsError) -> Self {
        match e {
            MlsError::Storage(s) => Self::Storage(s),
            MlsError::Malformed(s) => Self::Malformed(s),
            other => Self::Malformed(other.to_string()),
        }
    }
}

// ------------------------------------------------------------------------------- key record

/// `kdf` of a wrap.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub(crate) struct KdfRecord {
    pub alg: String,
    pub m: u64,
    pub t: u64,
    pub p: u64,
    pub salt: String,
}

/// One wrap of `BK`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub(crate) struct WrapRecord {
    pub kind: String,
    pub kdf: KdfRecord,
    pub nonce: String,
    pub wrapped: String,
    pub check: String,
}

/// `BackupKey` (§22.3).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub(crate) struct KeyRecord {
    pub v: u64,
    pub bk_id: String,
    pub wraps: Vec<WrapRecord>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub updated_at: Option<String>,
}

pub(crate) fn b64_exact(s: &str, n: usize, what: &str) -> BackupResult<Vec<u8>> {
    let b = B64
        .decode(s)
        .map_err(|_| malformed(format!("{what} is not base64")))?;
    if b.len() != n || B64.encode(&b) != s {
        return Err(malformed(format!(
            "{what} is not {n} bytes of canonical base64"
        )));
    }
    Ok(b)
}

impl KeyRecord {
    /// Parses and checks the shape (§22.3): `v` 1, an 8-byte `bk_id`, 1–2 wraps, at most one per
    /// kind, exactly one `recovery_key`, the fixed v1 KDF (C1) and every length.
    pub(crate) fn parse(json: &str) -> BackupResult<Self> {
        let v: serde_json::Value =
            serde_json::from_str(json).map_err(|_| malformed("the key record is not JSON"))?;
        Self::from_value(v)
    }

    pub(crate) fn from_value(v: serde_json::Value) -> BackupResult<Self> {
        match v.get("v").and_then(serde_json::Value::as_u64) {
            Some(1) => {}
            Some(n) => return Err(BackupError::Unsupported(format!("key record v {n}"))),
            None => return Err(malformed("key record without v")),
        }
        let r: Self =
            serde_json::from_value(v).map_err(|e| malformed(format!("key record: {e}")))?;
        r.bk_id_bytes()?;
        if r.wraps.is_empty() || r.wraps.len() > 2 {
            return Err(malformed("a key record has 1-2 wraps"));
        }
        let mut kinds = Vec::new();
        for w in &r.wraps {
            let k = SecretKind::parse(&w.kind)?;
            if kinds.contains(&k) {
                return Err(malformed("two wraps of one kind"));
            }
            kinds.push(k);
            keys::check_kdf_params(&w.kdf.alg, w.kdf.m, w.kdf.t, w.kdf.p)?;
            b64_exact(&w.kdf.salt, SALT_LEN, "salt")?;
            b64_exact(&w.nonce, NONCE_LEN, "nonce")?;
            b64_exact(&w.wrapped, keys::WRAPPED_LEN, "wrapped")?;
            b64_exact(&w.check, keys::CHECK_LEN, "check")?;
        }
        if !kinds.contains(&SecretKind::RecoveryKey) {
            return Err(malformed("a key record needs a recovery_key wrap"));
        }
        Ok(r)
    }

    pub(crate) fn bk_id_bytes(&self) -> BackupResult<[u8; BK_ID_LEN]> {
        Ok(b64_exact(&self.bk_id, BK_ID_LEN, "bk_id")?
            .try_into()
            .expect("8"))
    }

    fn wrap(&self, kind: SecretKind) -> Option<&WrapRecord> {
        self.wraps.iter().find(|w| w.kind == kind.as_str())
    }

    fn to_json(&self) -> String {
        serde_json::to_string(self).expect("serialisable")
    }
}

/// Makes one wrap of `BK` for `password` (fresh salt and nonce; Argon2id).
fn make_wrap(
    user_id: &str,
    kind: SecretKind,
    password: &[u8],
    bk: &[u8; KEY_LEN],
) -> BackupResult<WrapRecord> {
    let salt = keys::random::<SALT_LEN>()?;
    let nonce = keys::random::<NONCE_LEN>()?;
    let kek = keys::derive_kek(password, &salt)?;
    let (wrapped, check) = keys::seal_wrap(&kek, user_id, kind.as_str(), bk, &nonce)?;
    Ok(WrapRecord {
        kind: kind.as_str().into(),
        kdf: KdfRecord {
            alg: keys::ARGON2_ALG.into(),
            m: keys::ARGON2_M.into(),
            t: keys::ARGON2_T.into(),
            p: keys::ARGON2_P.into(),
            salt: B64.encode(&salt[..]),
        },
        nonce: B64.encode(&nonce[..]),
        wrapped: B64.encode(wrapped),
        check: B64.encode(check),
    })
}

/// Unwraps `BK` from `rec` with a secret of `kind` (§22.2 "Unwrap"): `Typo`/`Malformed` for
/// the recovery key's form, `Malformed` for the record, `WrongKey` for the key check,
/// `Integrity` for the AEAD or `bk_id`.
pub(crate) fn unwrap_record(
    user_id: &str,
    rec: &KeyRecord,
    secret: &str,
    kind: SecretKind,
) -> BackupResult<Zeroizing<[u8; KEY_LEN]>> {
    let w = rec
        .wrap(kind)
        .ok_or_else(|| malformed(format!("the key record has no {} wrap", kind.as_str())))?;
    let password: Zeroizing<Vec<u8>> = match kind {
        SecretKind::RecoveryKey => Zeroizing::new(parse_recovery_key(secret)?.to_vec()),
        SecretKind::Passphrase => keys::passphrase_bytes(secret)?,
    };
    keys::check_kdf_params(&w.kdf.alg, w.kdf.m, w.kdf.t, w.kdf.p)?;
    let salt: [u8; SALT_LEN] = b64_exact(&w.kdf.salt, SALT_LEN, "salt")?
        .try_into()
        .expect("16");
    let kek = keys::derive_kek(&password, &salt)?;
    keys::open_wrap(
        &kek,
        user_id,
        kind.as_str(),
        &rec.bk_id_bytes()?,
        &b64_exact(&w.nonce, NONCE_LEN, "nonce")?,
        &b64_exact(&w.wrapped, keys::WRAPPED_LEN, "wrapped")?,
        &b64_exact(&w.check, keys::CHECK_LEN, "check")?,
    )
}

// ------------------------------------------------------------------------------- public records

/// What [`Client::backup_setup`] and [`Client::backup_rotate_recovery_key`] return.
#[derive(Clone, PartialEq, Eq)]
pub struct BackupSetup {
    /// The display form (`XXXX-XXXX-…`): show it, never log it.
    pub recovery_key: Zeroizing<String>,
    /// The `BackupKey` JSON for `PUT /backup_key` (no `updated_at`).
    pub key_record: String,
}

impl std::fmt::Debug for BackupSetup {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("BackupSetup")
            .field("recovery_key", &"<redacted>")
            .field("key_record", &self.key_record)
            .finish()
    }
}

/// The `bk_id`s (base64) of the local `BK`s.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackupKeyIds {
    /// The account key that new backups use.
    pub current: Option<String>,
    /// Older keys kept for local files, oldest first.
    pub old: Vec<String>,
}

/// A file's header, read without any key (unauthenticated until the verify pass).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackupFileInfo {
    pub backup_id: String,
    pub user_id: String,
    pub created_at: String,
    pub app_version: String,
    /// base64.
    pub bk_id: String,
    pub schema: u64,
    /// The `BackupKey` JSON at backup time (unlock with it to restore a file), or `None`.
    pub key_record: Option<String>,
}

impl From<&FileHeader> for BackupFileInfo {
    fn from(h: &FileHeader) -> Self {
        Self {
            backup_id: h.backup_id.clone(),
            user_id: h.user_id.clone(),
            created_at: h.created_at.clone(),
            app_version: h.app_version.clone(),
            bk_id: h.bk_id.clone(),
            schema: h.schema,
            // In the record's own field order (as `PUT /backup_key` and the core write it).
            key_record: h.key.as_ref().map(|v| {
                KeyRecord::from_value(v.clone())
                    .map(|r| r.to_json())
                    .unwrap_or_else(|_| v.to_string())
            }),
        }
    }
}

/// Reads a file's header without a key (for its `key` record and the restore screen). The
/// values are unauthenticated; [`BackupReader::verify`] authenticates them.
pub fn file_info(path: &Path) -> BackupResult<BackupFileInfo> {
    let o = stream::open_file(path)?;
    Ok((&stream::parse_header(&o.header_bytes)?).into())
}

/// Normalises a typed recovery key to its display form (`Typo` / `Malformed` otherwise), for the
/// input field's live check. No key derivation.
pub fn normalize_recovery_key(input: &str) -> BackupResult<String> {
    Ok(recovery_display(&*parse_recovery_key(input)?))
}

// ------------------------------------------------------------------------------- storage

const BK_KEY: &[u8] = b"risime/backup/bk";
const R_KEY: &[u8] = b"risime/backup/recovery";
const REC_KEY: &[u8] = b"risime/backup/record";
const OLD_INDEX: &[u8] = b"risime/backup/old-index";
const STORE_VERSION: u8 = 1;

fn old_key(id: &[u8; BK_ID_LEN]) -> Vec<u8> {
    let hex: String = id.iter().map(|b| format!("{b:02x}")).collect();
    [b"risime/backup/old/".as_slice(), hex.as_bytes()].concat()
}

fn versioned(b: &[u8]) -> Zeroizing<Vec<u8>> {
    let mut v = Zeroizing::new(Vec::with_capacity(1 + b.len()));
    v.push(STORE_VERSION);
    v.extend_from_slice(b);
    v
}

fn unversioned<const N: usize>(v: Option<Vec<u8>>) -> BackupResult<Option<Zeroizing<[u8; N]>>> {
    let Some(v) = v else { return Ok(None) };
    let v = Zeroizing::new(v);
    if v.len() != 1 + N || v[0] != STORE_VERSION {
        return Err(BackupError::Storage("backup key record is corrupt".into()));
    }
    Ok(Some(Zeroizing::new(v[1..].try_into().expect("N"))))
}

fn canonical_uuid(s: &str, what: &str) -> BackupResult<(String, [u8; 16])> {
    let raw = parse_uuid(s).map_err(|_| malformed(format!("{what} is not a UUID")))?;
    Ok((format_uuid(&raw), raw))
}

fn check_text(s: &str, what: &str) -> BackupResult<()> {
    if s.is_empty() || s.len() > 64 || s.chars().any(char::is_control) {
        return Err(malformed(format!("{what} must be 1-64 bytes of text")));
    }
    Ok(())
}

impl Client {
    /// The canonical `user_id`, which must be this device's user.
    fn backup_user(&self, user_id: &str) -> BackupResult<String> {
        let (u, raw) = canonical_uuid(user_id, "user_id")?;
        match parse_uuid(&self.device().user_id) {
            Ok(own) if own == raw => Ok(u),
            _ => Err(malformed("user_id is not this device's user")),
        }
    }

    fn backup_tx<T>(&self, f: impl FnOnce(&Self) -> BackupResult<T>) -> BackupResult<T> {
        let mut out = None;
        let r = self.tx(|c| match f(c) {
            Ok(v) => {
                out = Some(Ok(v));
                Ok(())
            }
            Err(e) => {
                out = Some(Err(e));
                Err(MlsError::Other("backup".into()))
            }
        });
        match (r, out) {
            (Ok(()), Some(v)) => v,
            (Err(_), Some(Err(e))) => Err(e),
            (Err(e), _) => Err(e.into()),
            (Ok(()), None) => unreachable!("tx ran f"),
        }
    }

    fn load_bk(&self) -> BackupResult<Option<Zeroizing<[u8; KEY_LEN]>>> {
        unversioned(self.kv.get(BK_KEY).map_err(st)?)
    }

    fn load_r(&self) -> BackupResult<Option<Zeroizing<[u8; RECOVERY_LEN]>>> {
        unversioned(self.kv.get(R_KEY).map_err(st)?)
    }

    fn load_record(&self) -> BackupResult<Option<KeyRecord>> {
        let Some(v) = self.kv.get(REC_KEY).map_err(st)? else {
            return Ok(None);
        };
        let s = String::from_utf8(v).map_err(|_| st("backup key record is corrupt"))?;
        KeyRecord::parse(&s)
            .map(Some)
            .map_err(|_| st("backup key record is corrupt"))
    }

    fn old_index(&self) -> BackupResult<Vec<[u8; BK_ID_LEN]>> {
        let Some(b) = self.kv.get(OLD_INDEX).map_err(st)? else {
            return Ok(Vec::new());
        };
        if !b.len().is_multiple_of(BK_ID_LEN) {
            return Err(st("backup key index is corrupt"));
        }
        Ok(b.as_chunks::<BK_ID_LEN>().0.to_vec())
    }

    fn put_old_index(&self, ids: &[[u8; BK_ID_LEN]]) -> BackupResult<()> {
        if ids.is_empty() {
            self.kv.delete(OLD_INDEX).map_err(st)
        } else {
            self.kv.put(OLD_INDEX, &ids.concat()).map_err(st)
        }
    }

    fn put_old(&self, bk: &[u8; KEY_LEN]) -> BackupResult<()> {
        let id = keys::bk_id(bk);
        self.kv.put(&old_key(&id), &versioned(bk)).map_err(st)?;
        let mut idx = self.old_index()?;
        if !idx.contains(&id) {
            idx.push(id);
            self.put_old_index(&idx)?;
        }
        Ok(())
    }

    fn delete_old(&self, id: &[u8; BK_ID_LEN]) -> BackupResult<()> {
        self.kv.delete(&old_key(id)).map_err(st)?;
        let mut idx = self.old_index()?;
        let n = idx.len();
        idx.retain(|x| x != id);
        if idx.len() != n {
            self.put_old_index(&idx)?;
        }
        Ok(())
    }

    /// The local `BK` with this id: the current one or an older one.
    fn bk_by_id(&self, id: &[u8; BK_ID_LEN]) -> BackupResult<Option<Zeroizing<[u8; KEY_LEN]>>> {
        if let Some(bk) = self.load_bk()?
            && keys::bk_id(&bk) == *id
        {
            return Ok(Some(bk));
        }
        unversioned(self.kv.get(&old_key(id)).map_err(st)?)
    }

    /// Turns backups on (§22.7): makes `BK` and `R`, or reuses the local pair (also one made
    /// silently for local backups), and returns the recovery key's display form and the key
    /// record with its `recovery_key` wrap (plus a stored passphrase wrap of the same `BK`).
    /// Everything is stored in one nested transaction inside the caller's. Runs Argon2id
    /// (≈ 64 MiB, up to a second) unless the stored record already matches: never on the main
    /// thread.
    ///
    /// When a key record already exists on the server, call [`Client::backup_unlock`] instead.
    pub fn backup_setup(&self, user_id: &str) -> BackupResult<BackupSetup> {
        let u = self.backup_user(user_id)?;
        let bk = self.load_bk()?;
        let r = self.load_r()?;
        let rec = self.load_record()?;
        if let (Some(bk), Some(r), Some(rec)) = (&bk, &r, &rec)
            && rec.bk_id_bytes()? == keys::bk_id(bk)
        {
            return Ok(BackupSetup {
                recovery_key: Zeroizing::new(recovery_display(r)),
                key_record: rec.to_json(),
            });
        }
        let new_bk = bk.is_none();
        let bk = match bk {
            Some(b) => b,
            None => keys::random::<KEY_LEN>()?,
        };
        let new_r = r.is_none();
        let r = match r {
            Some(r) => r,
            None => keys::random::<RECOVERY_LEN>()?,
        };
        let rec = self.record_with_recovery(&u, &bk, &r, rec)?;
        self.backup_tx(|c| {
            if new_bk {
                c.kv.put(BK_KEY, &versioned(&bk[..])).map_err(st)?;
            }
            if new_r || new_bk {
                c.kv.put(R_KEY, &versioned(&r[..])).map_err(st)?;
            }
            c.kv.put(REC_KEY, rec.to_json().as_bytes()).map_err(st)?;
            Ok(())
        })?;
        Ok(BackupSetup {
            recovery_key: Zeroizing::new(recovery_display(&r)),
            key_record: rec.to_json(),
        })
    }

    /// A record for `bk` with a fresh `recovery_key` wrap of `r`, keeping the passphrase wrap of
    /// `old` when it wraps the same `BK`.
    fn record_with_recovery(
        &self,
        user_id: &str,
        bk: &[u8; KEY_LEN],
        r: &[u8; RECOVERY_LEN],
        old: Option<KeyRecord>,
    ) -> BackupResult<KeyRecord> {
        let id = keys::bk_id(bk);
        let mut wraps = vec![make_wrap(user_id, SecretKind::RecoveryKey, r, bk)?];
        if let Some(old) = old
            && old.bk_id_bytes()? == id
            && let Some(p) = old.wrap(SecretKind::Passphrase)
        {
            wraps.push(p.clone());
        }
        Ok(KeyRecord {
            v: 1,
            bk_id: B64.encode(id),
            wraps,
            updated_at: None,
        })
    }

    /// Adds (or replaces) the passphrase wrap of the current `BK` and returns the new key record
    /// for `PUT /backup_key` (same `bk_id`). Enforces the core part of the floor (14 code points
    /// or 4 words of 3+; `WeakPassphrase`); the app checks the common-password list and the
    /// phone number first ([`passphrase_floor`]). Runs Argon2id.
    pub fn backup_add_passphrase(&self, user_id: &str, passphrase: &str) -> BackupResult<String> {
        let u = self.backup_user(user_id)?;
        if passphrase_floor(passphrase, None) != PassphraseFloor::Ok {
            return Err(BackupError::WeakPassphrase(
                "at least 14 characters or 4 words of 3 or more".into(),
            ));
        }
        let bk = self
            .load_bk()?
            .ok_or_else(|| BackupError::NoKey("no backup key".into()))?;
        let mut rec = self
            .load_record()?
            .filter(|r| r.bk_id_bytes().ok() == Some(keys::bk_id(&bk)))
            .ok_or_else(|| BackupError::NoKey("no key record for the current key".into()))?;
        let pw = keys::passphrase_bytes(passphrase)?;
        let w = make_wrap(&u, SecretKind::Passphrase, &pw, &bk)?;
        rec.wraps
            .retain(|x| x.kind != SecretKind::Passphrase.as_str());
        rec.wraps.push(w);
        rec.updated_at = None;
        self.backup_tx(|c| c.kv.put(REC_KEY, rec.to_json().as_bytes()).map_err(st))?;
        Ok(rec.to_json())
    }

    /// Unlocks a key record (the server's `GET /backup_key`, or a file header's `key`) with the
    /// recovery key or the passphrase and stores `BK` (§22.2 "Unwrap"): `Typo` / `Malformed` /
    /// `WrongKey` / `Integrity`. Runs Argon2id before touching the store.
    ///
    /// With `make_current` (the account's key: turning backups on when a record exists, a
    /// restore from the server) `BK` becomes the current key, the previous one is kept as an
    /// older local key, the record is stored, and `R` too for a recovery key (a passphrase
    /// unlock keeps a stored `R` only when the record's recovery wrap is the one already
    /// stored). Without it (a file with another `bk_id`) `BK` is only kept as an older key.
    /// Returns the `bk_id` (base64).
    pub fn backup_unlock(
        &self,
        user_id: &str,
        key_record: &str,
        secret: &str,
        kind: SecretKind,
        make_current: bool,
    ) -> BackupResult<String> {
        let u = self.backup_user(user_id)?;
        let rec = KeyRecord::parse(key_record)?;
        let bk = unwrap_record(&u, &rec, secret, kind)?;
        let r = match kind {
            SecretKind::RecoveryKey => Some(parse_recovery_key(secret)?),
            SecretKind::Passphrase => None,
        };
        let id = keys::bk_id(&bk);
        self.backup_tx(|c| {
            let cur = c.load_bk()?;
            let same = cur.as_ref().is_some_and(|b| keys::bk_id(b) == id);
            if !make_current {
                if !same {
                    c.put_old(&bk)?;
                }
                return Ok(());
            }
            if let Some(cur) = &cur
                && !same
            {
                c.put_old(cur)?;
            }
            c.delete_old(&id)?;
            c.kv.put(BK_KEY, &versioned(&bk[..])).map_err(st)?;
            match &r {
                Some(r) => c.kv.put(R_KEY, &versioned(&r[..])).map_err(st)?,
                None => {
                    let keep = same
                        && c.load_record()?.is_some_and(|old| {
                            old.wrap(SecretKind::RecoveryKey) == rec.wrap(SecretKind::RecoveryKey)
                        });
                    if !keep {
                        c.kv.delete(R_KEY).map_err(st)?;
                    }
                }
            }
            c.kv.put(REC_KEY, rec.to_json().as_bytes()).map_err(st)?;
            Ok(())
        })?;
        Ok(B64.encode(id))
    }

    /// The stored recovery key's display form (show it again behind the device credential), or
    /// `None` (never set up, or unlocked with a passphrase: rotate to get a new one).
    pub fn backup_recovery_key(&self) -> BackupResult<Option<Zeroizing<String>>> {
        Ok(self.load_r()?.map(|r| Zeroizing::new(recovery_display(&r))))
    }

    /// "Change recovery key": a new `R` for the same `BK` (same `bk_id`), a new record with the
    /// stored passphrase wrap kept. `PUT` it; older files still open with the old key. Runs
    /// Argon2id.
    pub fn backup_rotate_recovery_key(&self, user_id: &str) -> BackupResult<BackupSetup> {
        let u = self.backup_user(user_id)?;
        let bk = self
            .load_bk()?
            .ok_or_else(|| BackupError::NoKey("no backup key".into()))?;
        let r = keys::random::<RECOVERY_LEN>()?;
        let rec = self.record_with_recovery(&u, &bk, &r, self.load_record()?)?;
        self.backup_tx(|c| {
            c.kv.put(R_KEY, &versioned(&r[..])).map_err(st)?;
            c.kv.put(REC_KEY, rec.to_json().as_bytes()).map_err(st)
        })?;
        Ok(BackupSetup {
            recovery_key: Zeroizing::new(recovery_display(&r)),
            key_record: rec.to_json(),
        })
    }

    /// Deletes every backup key of this device (`BK`, older `BK`s, `R`, the record): the
    /// confirmed wipe, or "Reset backup key". Idempotent.
    pub fn backup_forget(&self) -> BackupResult<()> {
        self.backup_tx(|c| {
            for id in c.old_index()? {
                c.kv.delete(&old_key(&id)).map_err(st)?;
            }
            for k in [OLD_INDEX, BK_KEY, R_KEY, REC_KEY] {
                c.kv.delete(k).map_err(st)?;
            }
            Ok(())
        })
    }

    /// The stored key record (JSON), or `None`.
    pub fn backup_key_record(&self) -> BackupResult<Option<String>> {
        Ok(self.load_record()?.map(|r| r.to_json()))
    }

    /// The local keys' `bk_id`s.
    pub fn backup_key_ids(&self) -> BackupResult<BackupKeyIds> {
        Ok(BackupKeyIds {
            current: self.load_bk()?.map(|b| B64.encode(keys::bk_id(&b))),
            old: self.old_index()?.iter().map(|i| B64.encode(i)).collect(),
        })
    }

    /// Drops an older local key once no local file uses it (the current key: `Malformed`).
    /// Idempotent.
    pub fn backup_drop_key(&self, bk_id: &str) -> BackupResult<()> {
        let id: [u8; BK_ID_LEN] = b64_exact(bk_id, BK_ID_LEN, "bk_id")?.try_into().expect("8");
        if self.load_bk()?.is_some_and(|b| keys::bk_id(&b) == id) {
            return Err(malformed("that is the current backup key"));
        }
        self.backup_tx(|c| c.delete_old(&id))
    }

    /// Starts a backup file at `out_path` (it appears there only after
    /// [`BackupWriter::finish`]). `key_record`: the record to copy into the header (normally the
    /// server's `GET /backup_key`); its `bk_id` picks the local `BK` (`NoKey` if absent). `None`
    /// uses the stored record, or the current `BK` with `key: null` when there is none. A fresh
    /// `DEK` and nonce come from the OS here; a failed writer is never resumed.
    pub fn backup_writer(
        &self,
        user_id: &str,
        backup_id: &str,
        created_at: &str,
        app_version: &str,
        key_record: Option<&str>,
        out_path: &Path,
    ) -> BackupResult<BackupWriter> {
        let u = self.backup_user(user_id)?;
        let (bid, bid_raw) = canonical_uuid(backup_id, "backup_id")?;
        check_text(created_at, "created_at")?;
        check_text(app_version, "app_version")?;
        let rec = match key_record {
            Some(j) => Some(KeyRecord::parse(j)?),
            None => self.load_record()?,
        };
        let (bk, id) = match &rec {
            Some(r) => {
                let id = r.bk_id_bytes()?;
                let bk = self
                    .bk_by_id(&id)?
                    .ok_or_else(|| BackupError::NoKey(r.bk_id.clone()))?;
                (bk, id)
            }
            None => {
                let bk = self
                    .load_bk()?
                    .ok_or_else(|| BackupError::NoKey("no backup key".into()))?;
                let id = keys::bk_id(&bk);
                (bk, id)
            }
        };
        let dek = keys::random::<KEY_LEN>()?;
        let nonce = keys::random::<NONCE_LEN>()?;
        let wrapped = keys::seal_dek(&bk, &bid_raw, &u, &dek, &nonce)?;
        let header = FileHeader {
            v: stream::HEADER_V,
            schema: stream::BUNDLE_SCHEMA,
            backup_id: bid.clone(),
            user_id: u,
            created_at: created_at.into(),
            app_version: app_version.into(),
            bk_id: B64.encode(id),
            dek: DekHeader {
                alg: stream::DEK_ALG.into(),
                nonce: B64.encode(&nonce[..]),
                wrapped: B64.encode(wrapped),
            },
            stream: StreamHeader {
                alg: stream::STREAM_ALG.into(),
                compression: stream::COMPRESSION.into(),
                pad: stream::PAD.into(),
            },
            key: rec.map(|r| serde_json::to_value(r).expect("serialisable")),
        };
        let bytes = serde_json::to_vec(&header).expect("serialisable");
        BackupWriter::create(out_path, &bytes, &dek, bid, B64.encode(id))
    }

    /// Opens a backup file (§22.4 "Opening a file"): magic, version and header length
    /// (`Format`); `v`/`schema` (`Unsupported`); `user_id` = this user (`WrongAccount`, nothing
    /// more is read); for a server backup the listed `backup_id`/`bk_id` (`Integrity`); the
    /// local `BK` for its `bk_id` (`NoKey`: unlock the server's record or the header's `key`
    /// from [`file_info`] first); the `DEK` (`Integrity`). Then call
    /// [`BackupReader::verify`].
    pub fn backup_reader(
        &self,
        user_id: &str,
        in_path: &Path,
        expected_backup_id: Option<&str>,
        expected_bk_id: Option<&str>,
    ) -> BackupResult<BackupReader> {
        let u = self.backup_user(user_id)?;
        let opened = stream::open_file(in_path)?;
        let header = stream::parse_header(&opened.header_bytes)?;
        match parse_uuid(&header.user_id) {
            Ok(raw) if format_uuid(&raw) == u => {}
            _ => return Err(BackupError::WrongAccount),
        }
        let bid_raw = parse_uuid(&header.backup_id)
            .map_err(|_| BackupError::Format("header backup_id".into()))?;
        if let Some(e) = expected_backup_id
            && parse_uuid(e).ok() != Some(bid_raw)
        {
            return Err(BackupError::Integrity(
                "backup_id differs from the listing".into(),
            ));
        }
        if let Some(e) = expected_bk_id
            && e != header.bk_id
        {
            return Err(BackupError::Integrity(
                "bk_id differs from the listing".into(),
            ));
        }
        let id: [u8; BK_ID_LEN] = b64_exact(&header.bk_id, BK_ID_LEN, "bk_id")
            .map_err(|_| BackupError::Format("header bk_id".into()))?
            .try_into()
            .expect("8");
        let bk = self
            .bk_by_id(&id)?
            .ok_or_else(|| BackupError::NoKey(header.bk_id.clone()))?;
        let nonce = B64
            .decode(&header.dek.nonce)
            .map_err(|_| BackupError::Format("dek.nonce".into()))?;
        let wrapped = B64
            .decode(&header.dek.wrapped)
            .map_err(|_| BackupError::Format("dek.wrapped".into()))?;
        let dek = keys::open_dek(&bk, &bid_raw, &u, &nonce, &wrapped)?;
        BackupReader::new(in_path, opened, header, &dek)
    }
}

impl BackupReader {
    /// The header (authenticated only after [`verify`](Self::verify) succeeded).
    pub fn info(&self) -> BackupFileInfo {
        self.header().into()
    }
}
