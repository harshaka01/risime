//! History sharing between devices (contract v1.15 §17.3, crypto review R1, R3, R7).
//!
//! A provider device seals each bundle part for **one requesting device**:
//! - the part is an `A256GCM-S64K` blob ([`crate::media`]) under a fresh 32-byte `K` and the label
//!   [`HISTORY_LABEL`] (so media and history blobs aren't interchangeable, crypto S1);
//! - `K` is HPKE-sealed (RFC 9180 **base mode**, the HPKE triple of MLS suite 0x0001:
//!   DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-128-GCM) to the requester's per-request key
//!   `rpk`, with `info` binding the request, the conversation and both MLS identities and `aad`
//!   binding the one blob ([`HistoryContext`]).
//!
//! Key handling (R7): `(rsk, rpk)` is made by [`Client::history_keygen`] inside the core and `rsk`
//! is stored in the app's sealed [`crate::KvStore`] under `risime/history/<request_id>` in the
//! caller's transaction; only `rpk` is returned. [`seal`] draws `K` itself; [`Client::history_open`]
//! opens with the stored `rsk`. No public API returns `K` or `rsk`, and none takes a caller-chosen
//! `K` or `rsk` (except [`check_vectors`], a verifier for the contract's test vectors that returns
//! only a count).

use std::path::Path;

use openmls_rust_crypto::RustCrypto;
use openmls_traits::OpenMlsProvider;
use openmls_traits::crypto::OpenMlsCrypto;
use openmls_traits::types::{HpkeAeadType, HpkeCiphertext, HpkeConfig, HpkeKdfType, HpkeKemType};
use sha2::{Digest, Sha256};
use zeroize::Zeroizing;

use crate::aad::{format_uuid, parse_uuid};
use crate::media::{self, BlobLabel, MediaError, MediaRef};
use crate::{Client, DeviceId, MlsError};

/// `enc.label` of a history part, and the blob label (§17.3).
pub const HISTORY_LABEL: &str = "risime-history-v1";
/// The history blob label: segment AAD `risime-history-v1`, HKDF info
/// `risime-history-v1 A256GCM-S64K`.
pub(crate) const HISTORY_BLOB_LABEL: BlobLabel = BlobLabel {
    aad: b"risime-history-v1",
    hkdf_info: b"risime-history-v1 A256GCM-S64K",
};
/// `rpk` length (X25519).
pub const HISTORY_RPK_LEN: usize = 32;
/// `hpke_enc` length (the X25519 ephemeral public key).
pub const HISTORY_HPKE_ENC_LEN: usize = 32;
/// `sealed_key` length: the 32-byte `K` plus the 16-byte AES-128-GCM tag.
pub const HISTORY_SEALED_KEY_LEN: usize = 48;
/// At most this many parts per share (§17.6).
pub const MAX_HISTORY_PARTS: u32 = 20;
/// The largest plaintext of one part (the §14.3 `media` cap).
pub const MAX_HISTORY_PLAIN_SIZE: u64 = media::MAX_MEDIA_PLAIN_SIZE;

const KEY_LEN: usize = 32;
const RECORD_VERSION: u8 = 1;
const INDEX_KEY: &[u8] = b"risime/history-index/v1";

/// The pinned HPKE profile (MLS ciphersuite 0x0001's triple).
pub(crate) fn hpke_config() -> HpkeConfig {
    HpkeConfig(
        HpkeKemType::DhKem25519,
        HpkeKdfType::HkdfSha256,
        HpkeAeadType::AesGcm128,
    )
}

/// History failures. Kotlin: `RisiHistoryException`.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum HistoryError {
    /// Bad input: a length (`rpk`, `hpke_enc`, `sealed_key`, `sha256`), an id, `part`/`parts`,
    /// an empty or too large plaintext, a non-canonical AAD.
    #[error("malformed history input: {0}")]
    Malformed(String),
    /// The seal refused the recipient key (a low-order `rpk`: all-zero DH output, RFC 9180 §7.1.4).
    #[error("history seal refused: {0}")]
    SealRefused(String),
    /// The HPKE open or the blob failed to verify (wrong key, request, identity, part, digest,
    /// size or label; a tampered or truncated blob).
    #[error("history open failed: {0}")]
    OpenFailed(String),
    /// No `rsk` is stored for this request (never made, forgotten, or rolled back): close the
    /// request locally.
    #[error("no key for this history request")]
    UnknownRequest,
    /// Reading or writing a file failed.
    #[error("history io: {0}")]
    Io(String),
    /// The app's store failed.
    #[error("storage: {0}")]
    Storage(String),
}

pub type HistoryResult<T> = std::result::Result<T, HistoryError>;

fn malformed(s: impl Into<String>) -> HistoryError {
    HistoryError::Malformed(s.into())
}

impl From<MlsError> for HistoryError {
    fn from(e: MlsError) -> Self {
        match e {
            MlsError::Storage(s) => Self::Storage(s),
            MlsError::Malformed(s) => Self::Malformed(s),
            other => Self::Malformed(other.to_string()),
        }
    }
}

/// Media errors on the history path: a blob that doesn't verify is `OpenFailed`.
fn from_media(e: MediaError) -> HistoryError {
    match e {
        MediaError::Integrity(s) | MediaError::Format(s) | MediaError::Unsupported(s) => {
            HistoryError::OpenFailed(s)
        }
        MediaError::TooLarge(s) => HistoryError::Malformed(s),
        MediaError::Io(s) => HistoryError::Io(s),
    }
}

/// The one record behind the HPKE `info` and `aad` (§17.3). Both identities are
/// `"<user_id>/<device_id>"` from **MLS credentials** (requester: its own; provider: the MLS sender
/// of the `history_share`), never from JSON or the server.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HistoryContext {
    pub request_id: String,
    pub conversation_id: String,
    pub requester: String,
    pub provider: String,
    /// 1-based, `1 ≤ part ≤ parts ≤ 20`.
    pub part: u32,
    pub parts: u32,
    /// SHA-256 of the part's ciphertext (32 bytes). Ignored by [`seal`], which computes it.
    pub sha256: Vec<u8>,
    /// The part's plaintext size. Ignored by [`seal`], which computes it.
    pub plain_size: u64,
}

fn put_str(out: &mut Vec<u8>, what: &str, s: &str) -> HistoryResult<()> {
    let len = u16::try_from(s.len()).map_err(|_| malformed(format!("{what} is too long")))?;
    out.extend_from_slice(&len.to_be_bytes());
    out.extend_from_slice(s.as_bytes());
    Ok(())
}

impl HistoryContext {
    /// HPKE `info`: `"risime-history-v1"` ‖ `request_id` (16 raw bytes) ‖ `u16 len ‖
    /// conversation_id` ‖ `u16 len ‖ requester` ‖ `u16 len ‖ provider` (big-endian).
    pub fn info(&self) -> HistoryResult<Vec<u8>> {
        let rid = parse_uuid(&self.request_id)?;
        if self.conversation_id.is_empty() {
            return Err(malformed("empty conversation_id"));
        }
        DeviceId::parse(self.requester.as_bytes())?;
        DeviceId::parse(self.provider.as_bytes())?;
        let mut out = Vec::with_capacity(
            HISTORY_LABEL.len()
                + 16
                + 6
                + self.conversation_id.len()
                + self.requester.len()
                + self.provider.len(),
        );
        out.extend_from_slice(HISTORY_LABEL.as_bytes());
        out.extend_from_slice(&rid);
        put_str(&mut out, "conversation_id", &self.conversation_id)?;
        put_str(&mut out, "requester", &self.requester)?;
        put_str(&mut out, "provider", &self.provider)?;
        Ok(out)
    }

    /// HPKE `aad`: `u16 part ‖ u16 parts ‖ sha256 (32 raw bytes) ‖ u64 plain_size`.
    pub fn aad(&self) -> HistoryResult<Vec<u8>> {
        aad_bytes(self.part, self.parts, &self.sha256, self.plain_size)
    }
}

fn check_parts(part: u32, parts: u32) -> HistoryResult<()> {
    if part == 0 || part > parts || parts > MAX_HISTORY_PARTS {
        return Err(malformed(format!(
            "part {part} of {parts} (1 ≤ part ≤ parts ≤ {MAX_HISTORY_PARTS})"
        )));
    }
    Ok(())
}

fn check_plain_size(n: u64) -> HistoryResult<()> {
    if n == 0 || n > MAX_HISTORY_PLAIN_SIZE {
        return Err(malformed(format!(
            "plain_size {n} (1..={MAX_HISTORY_PLAIN_SIZE})"
        )));
    }
    Ok(())
}

fn aad_bytes(part: u32, parts: u32, sha256: &[u8], plain_size: u64) -> HistoryResult<Vec<u8>> {
    check_parts(part, parts)?;
    if sha256.len() != 32 {
        return Err(malformed(format!("sha256 is {} bytes", sha256.len())));
    }
    check_plain_size(plain_size)?;
    let mut out = Vec::with_capacity(44);
    out.extend_from_slice(&(part as u16).to_be_bytes());
    out.extend_from_slice(&(parts as u16).to_be_bytes());
    out.extend_from_slice(sha256);
    out.extend_from_slice(&plain_size.to_be_bytes());
    Ok(out)
}

/// What [`seal`] produced for the `history_share` envelope: `enc.hpke_enc`, `enc.sealed_key`,
/// `enc.plain_size`, `blob.size`, `blob.sha256`. No key material.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SealedPart {
    pub hpke_enc: Vec<u8>,
    pub sealed_key: Vec<u8>,
    pub plain_size: u64,
    pub size: u64,
    pub sha256: [u8; 32],
}

/// Seals one bundle part for the requester's `rpk`: a fresh `K` from the OS CSPRNG, the part
/// written to `out_path` as an `A256GCM-S64K` blob with the history label (atomically), and `K`
/// HPKE-sealed with `ctx`'s `info` and this blob's `aad`. `ctx.sha256` and `ctx.plain_size` are
/// ignored (computed here). No `Client` state; `K` never leaves this call. On any error nothing is
/// left at `out_path`. A low-order `rpk` is [`HistoryError::SealRefused`].
pub fn seal(
    rpk: &[u8],
    ctx: &HistoryContext,
    plaintext: &[u8],
    out_path: &Path,
) -> HistoryResult<SealedPart> {
    let mut k = Zeroizing::new([0u8; KEY_LEN]);
    getrandom::getrandom(&mut k[..]).map_err(|e| HistoryError::Io(format!("getrandom: {e}")))?;
    seal_with_key(rpk, ctx, plaintext, out_path, &k, |info, aad, k| {
        let ct = RustCrypto::default()
            .hpke_seal(hpke_config(), rpk, info, aad, k)
            .map_err(|e| HistoryError::SealRefused(format!("{e:?}")))?;
        Ok((
            ct.kem_output.as_slice().to_vec(),
            ct.ciphertext.as_slice().to_vec(),
        ))
    })
}

/// The keyed core of [`seal`]. `hpke(info, aad, k)` returns `(hpke_enc, sealed_key)`.
fn seal_with_key(
    rpk: &[u8],
    ctx: &HistoryContext,
    plaintext: &[u8],
    out_path: &Path,
    k: &[u8; KEY_LEN],
    hpke: impl FnOnce(&[u8], &[u8], &[u8]) -> HistoryResult<(Vec<u8>, Vec<u8>)>,
) -> HistoryResult<SealedPart> {
    if rpk.len() != HISTORY_RPK_LEN {
        return Err(malformed(format!("rpk is {} bytes", rpk.len())));
    }
    let info = ctx.info()?;
    check_parts(ctx.part, ctx.parts)?;
    check_plain_size(plaintext.len() as u64)?;
    let mut input = plaintext;
    let sealed = media::seal_reader_with_key(
        &mut input,
        plaintext.len() as u64,
        out_path,
        k,
        MAX_HISTORY_PLAIN_SIZE,
        HISTORY_BLOB_LABEL,
    )
    .map_err(from_media)?;
    let res = aad_bytes(ctx.part, ctx.parts, &sealed.sha256, sealed.plain_size)
        .and_then(|aad| hpke(&info, &aad, &k[..]));
    let (hpke_enc, sealed_key) = match res {
        Ok(v) => v,
        Err(e) => {
            let _ = std::fs::remove_file(out_path);
            return Err(e);
        }
    };
    if hpke_enc.len() != HISTORY_HPKE_ENC_LEN || sealed_key.len() != HISTORY_SEALED_KEY_LEN {
        let _ = std::fs::remove_file(out_path);
        return Err(HistoryError::SealRefused(
            "unexpected HPKE output sizes".into(),
        ));
    }
    Ok(SealedPart {
        hpke_enc,
        sealed_key,
        plain_size: sealed.plain_size,
        size: sealed.cipher_size,
        sha256: sealed.sha256,
    })
}

/// HPKE-opens `K` with `rsk`, then opens the blob at `in_path` under `label`. Every segment, the
/// final flag, the padding and the SHA-256 are verified before any byte is returned.
fn open_with_rsk(
    rsk: &[u8],
    ctx: &HistoryContext,
    hpke_enc: &[u8],
    sealed_key: &[u8],
    in_path: &Path,
    label: BlobLabel,
) -> HistoryResult<Zeroizing<Vec<u8>>> {
    let k = open_key(rsk, ctx, hpke_enc, sealed_key)?;
    let cipher_size = media::cipher_size_for(ctx.plain_size)
        .ok_or_else(|| malformed("plain_size has no blob size"))?;
    media::decrypt_file_labeled(
        in_path,
        &MediaRef {
            key: &k[..],
            alg: media::MEDIA_ALG,
            plain_size: ctx.plain_size,
            cipher_size,
            sha256: &ctx.sha256,
        },
        label,
    )
    .map_err(from_media)
}

fn open_key(
    rsk: &[u8],
    ctx: &HistoryContext,
    hpke_enc: &[u8],
    sealed_key: &[u8],
) -> HistoryResult<Zeroizing<Vec<u8>>> {
    if hpke_enc.len() != HISTORY_HPKE_ENC_LEN {
        return Err(malformed(format!("hpke_enc is {} bytes", hpke_enc.len())));
    }
    if sealed_key.len() != HISTORY_SEALED_KEY_LEN {
        return Err(malformed(format!(
            "sealed_key is {} bytes",
            sealed_key.len()
        )));
    }
    if rsk.len() != KEY_LEN {
        return Err(malformed(format!("rsk is {} bytes", rsk.len())));
    }
    let info = ctx.info()?;
    let aad = ctx.aad()?;
    let input = HpkeCiphertext {
        kem_output: hpke_enc.to_vec().into(),
        ciphertext: sealed_key.to_vec().into(),
    };
    let k = Zeroizing::new(
        RustCrypto::default()
            .hpke_open(hpke_config(), &input, rsk, &info, &aad)
            .map_err(|e| HistoryError::OpenFailed(format!("hpke: {e:?}")))?,
    );
    if k.len() != KEY_LEN {
        return Err(HistoryError::OpenFailed(
            "sealed key is not 32 bytes".into(),
        ));
    }
    Ok(k)
}

/// Who sent a `history_request` / `history_share` (§17.6, §17.7), from the MLS credential.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HistorySender {
    /// The MLS sender (`process_detailed`'s `sender`).
    pub device: DeviceId,
    /// The sender's user is this device's user: **own** (option-A consent may apply); otherwise
    /// **member** (always ask). Never from the event's `consent` field.
    pub own: bool,
    /// The sender leaf's current signature key: with `device.device_id`, the key of the option-A
    /// approval record (crypto R4: a re-registered device with a new key asks again).
    pub signature_key: Vec<u8>,
}

/// `(rsk, rpk)` of a request.
type KeyRecord = (Zeroizing<Vec<u8>>, Vec<u8>);

fn record_key(request_id: &str) -> Vec<u8> {
    [b"risime/history/".as_slice(), request_id.as_bytes()].concat()
}

fn canonical_id(request_id: &str) -> HistoryResult<(String, [u8; 16])> {
    let id = parse_uuid(request_id)?;
    Ok((format_uuid(&id), id))
}

fn hs(e: impl std::fmt::Display) -> HistoryError {
    HistoryError::Storage(e.to_string())
}

impl Client {
    fn history_index(&self) -> HistoryResult<Vec<[u8; 16]>> {
        let Some(b) = self.kv.get(INDEX_KEY).map_err(hs)? else {
            return Ok(Vec::new());
        };
        if b.len() % 16 != 0 {
            return Err(HistoryError::Storage("history index is corrupt".into()));
        }
        Ok(b.as_chunks::<16>().0.to_vec())
    }

    fn put_history_index(&self, ids: &[[u8; 16]]) -> HistoryResult<()> {
        if ids.is_empty() {
            self.kv.delete(INDEX_KEY).map_err(hs)
        } else {
            self.kv.put(INDEX_KEY, &ids.concat()).map_err(hs)
        }
    }

    /// Reads `(rsk, rpk)` of a request.
    fn history_record(&self, id: &str) -> HistoryResult<Option<KeyRecord>> {
        let Some(v) = self.kv.get(&record_key(id)).map_err(hs)? else {
            return Ok(None);
        };
        let v = Zeroizing::new(v);
        if v.len() != 1 + 2 * KEY_LEN || v[0] != RECORD_VERSION {
            return Err(HistoryError::Storage(
                "history key record is corrupt".into(),
            ));
        }
        Ok(Some((
            Zeroizing::new(v[1..1 + KEY_LEN].to_vec()),
            v[1 + KEY_LEN..].to_vec(),
        )))
    }

    fn history_tx<T>(&self, f: impl FnOnce(&Self) -> HistoryResult<T>) -> HistoryResult<T> {
        let mut out = None;
        let r = self.tx(|c| match f(c) {
            Ok(v) => {
                out = Some(Ok(v));
                Ok(())
            }
            Err(e) => {
                out = Some(Err(e));
                Err(MlsError::Other("history".into()))
            }
        });
        match (r, out) {
            (Ok(()), Some(v)) => v,
            (Err(_), Some(Err(e))) => Err(e),
            (Err(e), _) => Err(e.into()),
            (Ok(()), None) => unreachable!("tx ran f"),
        }
    }

    /// Makes the per-request HPKE key pair (`derive_hpke_keypair` over 32 bytes of OS randomness),
    /// stores `rsk` under `risime/history/<request_id>` **in the caller's transaction** (one nested
    /// `KvStore` transaction; roll the outer one back and the key is gone) and returns only `rpk`
    /// (32 bytes). Write the request row in the same outer transaction, before `history:request`.
    /// A second keygen for the same request is [`HistoryError::Malformed`].
    pub fn history_keygen(&self, request_id: &str) -> HistoryResult<Vec<u8>> {
        let (id, raw) = canonical_id(request_id)?;
        self.history_tx(|c| {
            if c.kv.get(&record_key(&id)).map_err(hs)?.is_some() {
                return Err(malformed(format!("a key already exists for request {id}")));
            }
            let mut ikm = Zeroizing::new([0u8; KEY_LEN]);
            getrandom::getrandom(&mut ikm[..])
                .map_err(|e| HistoryError::Io(format!("getrandom: {e}")))?;
            let kp = c
                .provider
                .crypto()
                .derive_hpke_keypair(hpke_config(), &ikm[..])
                .map_err(|e| HistoryError::Io(format!("derive_hpke_keypair: {e:?}")))?;
            if kp.private.len() != KEY_LEN || kp.public.len() != HISTORY_RPK_LEN {
                return Err(HistoryError::Io("unexpected HPKE key sizes".into()));
            }
            let mut rec = Zeroizing::new(Vec::with_capacity(1 + 2 * KEY_LEN));
            rec.push(RECORD_VERSION);
            rec.extend_from_slice(&kp.private);
            rec.extend_from_slice(&kp.public);
            c.kv.put(&record_key(&id), &rec).map_err(hs)?;
            let mut idx = c.history_index()?;
            if !idx.contains(&raw) {
                idx.push(raw);
                c.put_history_index(&idx)?;
            }
            Ok(kp.public.clone())
        })
    }

    /// The stored `rpk` of a request (for a `history:refresh`, which resends the same `rpk`), or
    /// `None` when no key is stored.
    pub fn history_public_key(&self, request_id: &str) -> HistoryResult<Option<Vec<u8>>> {
        let (id, _) = canonical_id(request_id)?;
        Ok(self.history_record(&id)?.map(|(_, rpk)| rpk))
    }

    /// Opens one part: HPKE-opens `K` with the stored `rsk` (`ctx` built from this request, this
    /// device and the share's **MLS sender**) and AEAD-opens the blob at `in_path`. Every segment
    /// and the final flag are verified before any byte is returned. No key: `UnknownRequest`.
    pub fn history_open(
        &self,
        request_id: &str,
        ctx: &HistoryContext,
        hpke_enc: &[u8],
        sealed_key: &[u8],
        in_path: &Path,
    ) -> HistoryResult<Zeroizing<Vec<u8>>> {
        let (id, raw) = canonical_id(request_id)?;
        if parse_uuid(&ctx.request_id)? != raw {
            return Err(malformed("ctx.request_id differs from request_id"));
        }
        let (rsk, _) = self
            .history_record(&id)?
            .ok_or(HistoryError::UnknownRequest)?;
        open_with_rsk(&rsk, ctx, hpke_enc, sealed_key, in_path, HISTORY_BLOB_LABEL)
    }

    /// Deletes a request's `rsk` (after the last part, on cancel, on any terminal state, and by
    /// the 48-h start-up sweep). Idempotent.
    pub fn history_forget(&self, request_id: &str) -> HistoryResult<()> {
        let (id, raw) = canonical_id(request_id)?;
        self.history_tx(|c| {
            c.kv.delete(&record_key(&id)).map_err(hs)?;
            let mut idx = c.history_index()?;
            let before = idx.len();
            idx.retain(|x| *x != raw);
            if idx.len() != before {
                c.put_history_index(&idx)?;
            }
            Ok(())
        })
    }

    /// The request ids (lowercase UUIDs) that still have a stored `rsk`, oldest keygen first; for
    /// the start-up sweep (forget whatever the app's request rows say is older than 48 h or gone).
    pub fn history_open_requests(&self) -> HistoryResult<Vec<String>> {
        Ok(self.history_index()?.iter().map(format_uuid).collect())
    }

    /// Own versus member for a history envelope's MLS `sender` (from `process_detailed`): `own`
    /// when its user is this device's user. The sender must be a current leaf of the group
    /// (`UnknownMember` otherwise: its user is no longer a member, drop the envelope); this device
    /// itself is `Malformed`.
    pub fn history_sender(
        &self,
        group_id: &[u8],
        sender: &DeviceId,
    ) -> crate::Result<HistorySender> {
        if *sender == self.device {
            return Err(MlsError::Malformed("the sender is this device".into()));
        }
        let m = self
            .members(group_id)?
            .into_iter()
            .find(|m| m.device() == *sender)
            .ok_or(MlsError::UnknownMember)?;
        Ok(HistorySender {
            own: sender.user_id == self.device.user_id,
            device: sender.clone(),
            signature_key: m.signature_key,
        })
    }
}

// ---------------------------------------------------------------------------------------------
// Test vectors (contract/v1/history_vectors.json)
// ---------------------------------------------------------------------------------------------

fn unhex(v: &serde_json::Value, field: &str) -> HistoryResult<Vec<u8>> {
    let s = v[field]
        .as_str()
        .ok_or_else(|| malformed(format!("vector field {field} missing")))?;
    if s.len() % 2 != 0 {
        return Err(malformed(format!("vector field {field}: odd hex")));
    }
    (0..s.len())
        .step_by(2)
        .map(|i| {
            u8::from_str_radix(&s[i..i + 2], 16)
                .map_err(|_| malformed(format!("vector field {field}: bad hex")))
        })
        .collect()
}

fn vstr(v: &serde_json::Value, field: &str) -> HistoryResult<String> {
    v[field]
        .as_str()
        .map(str::to_string)
        .ok_or_else(|| malformed(format!("vector field {field} missing")))
}

fn vu64(v: &serde_json::Value, field: &str) -> HistoryResult<u64> {
    v[field]
        .as_u64()
        .ok_or_else(|| malformed(format!("vector field {field} missing")))
}

fn ctx_of(v: &serde_json::Value) -> HistoryResult<HistoryContext> {
    let small = |f: &str| -> HistoryResult<u32> {
        u32::try_from(vu64(v, f)?).map_err(|_| malformed(format!("vector field {f} too big")))
    };
    Ok(HistoryContext {
        request_id: vstr(v, "request_id")?,
        conversation_id: vstr(v, "conversation_id")?,
        requester: vstr(v, "requester")?,
        provider: vstr(v, "provider")?,
        part: small("part")?,
        parts: small("parts")?,
        sha256: unhex(v, "sha256")?,
        plain_size: vu64(v, "plain_size")?,
    })
}

fn expect_name(e: &HistoryError) -> &'static str {
    match e {
        HistoryError::Malformed(_) => "Malformed",
        HistoryError::SealRefused(_) => "SealRefused",
        HistoryError::OpenFailed(_) => "OpenFailed",
        HistoryError::UnknownRequest => "UnknownRequest",
        HistoryError::Io(_) => "Io",
        HistoryError::Storage(_) => "Storage",
    }
}

fn label_of(v: &serde_json::Value) -> HistoryResult<BlobLabel> {
    match v.get("label").and_then(|l| l.as_str()) {
        None | Some(HISTORY_LABEL) => Ok(HISTORY_BLOB_LABEL),
        Some("risime-media-v1") => Ok(media::MEDIA_LABEL),
        Some(l) => Err(malformed(format!("unknown label {l}"))),
    }
}

/// Runs one case's operation: a seal when the case changes `rpk`, an open otherwise.
fn run_case(v: &serde_json::Value, work_dir: &Path, tag: &str) -> HistoryResult<Vec<u8>> {
    let blob = work_dir.join(format!("risime-history-vector-{tag}.blob"));
    let res = (|| {
        let ctx = ctx_of(v)?;
        if v.get("seal").and_then(|s| s.as_bool()) == Some(true) {
            let plain = unhex(v, "plain")?;
            seal(&unhex(v, "rpk")?, &ctx, &plain, &blob)?;
            return Ok(Vec::new());
        }
        std::fs::write(&blob, unhex(v, "cipher")?).map_err(|e| HistoryError::Io(e.to_string()))?;
        open_with_rsk(
            &unhex(v, "rsk")?,
            &ctx,
            &unhex(v, "hpke_enc")?,
            &unhex(v, "sealed_key")?,
            &blob,
            label_of(v)?,
        )
        .map(|p| p.to_vec())
    })();
    let _ = std::fs::remove_file(&blob);
    res
}

fn fail(name: &str, what: impl std::fmt::Display) -> HistoryError {
    malformed(format!("vector {name}: {what}"))
}

/// **Test support:** verifies every case of a `history_vectors.json` document (§17.3) with this
/// core and returns the number of cases checked; the first failing case is reported as
/// `Malformed("vector <name>: …")`. Scratch blobs go to `work_dir` and are removed. Positive cases:
/// the key pairs from `ikm_r`/`ikm_e`, `info`, `aad`, `sha256`, the HPKE open to `k`, the blob
/// re-sealed under `k` byte for byte, and the open to `plain`. Negative cases: the base case with
/// `change` applied must fail with `expect` (a seal of `plain` when `change` sets `rpk`, an open
/// otherwise). `aad_h`: [`crate::decode_history_aad`] and the encoder. Returns nothing secret.
pub fn check_vectors(json: &str, work_dir: &Path) -> HistoryResult<u32> {
    let doc: serde_json::Value =
        serde_json::from_str(json).map_err(|e| malformed(format!("vectors json: {e}")))?;
    if doc["v"] != 1
        || doc["label"] != HISTORY_LABEL
        || doc["suite"] != serde_json::json!({"mode": "base", "kem": 32, "kdf": 1, "aead": 1})
    {
        return Err(malformed("vectors: wrong v, label or suite"));
    }
    let crypto = RustCrypto::default();
    let mut n = 0u32;

    for c in doc["aad_h"].as_array().into_iter().flatten() {
        let name = vstr(c, "name")?;
        let bytes = unhex(c, "bytes")?;
        let decoded = crate::aad::decode_history_aad(&bytes);
        match c["expect"].as_str() {
            Some("ok") => {
                let rid = vstr(c, "request_id")?;
                if decoded.as_deref() != Ok(rid.as_str())
                    || crate::aad::encode_history_aad(&rid).as_deref() != Ok(&bytes[..])
                {
                    return Err(fail(&name, "round trip"));
                }
            }
            Some("Malformed") => {
                if !matches!(decoded, Err(MlsError::Malformed(_))) {
                    return Err(fail(&name, "decoded a malformed AAD"));
                }
            }
            _ => return Err(fail(&name, "unknown expect")),
        }
        n += 1;
    }

    let positives = doc["positive"].as_array().cloned().unwrap_or_default();
    for p in &positives {
        let name = vstr(p, "name")?;
        let kp = crypto
            .derive_hpke_keypair(hpke_config(), &unhex(p, "ikm_r")?)
            .map_err(|e| fail(&name, format!("{e:?}")))?;
        if kp.private.to_vec() != unhex(p, "rsk")? || kp.public != unhex(p, "rpk")? {
            return Err(fail(&name, "ikm_r doesn't give rsk/rpk"));
        }
        let ke = crypto
            .derive_hpke_keypair(hpke_config(), &unhex(p, "ikm_e")?)
            .map_err(|e| fail(&name, format!("{e:?}")))?;
        if ke.public != unhex(p, "hpke_enc")? {
            return Err(fail(&name, "ikm_e doesn't give hpke_enc"));
        }
        let ctx = ctx_of(p)?;
        if ctx.info()? != unhex(p, "info")? || ctx.aad()? != unhex(p, "aad")? {
            return Err(fail(&name, "info or aad"));
        }
        let cipher = unhex(p, "cipher")?;
        let plain = unhex(p, "plain")?;
        if Sha256::digest(&cipher)[..] != ctx.sha256[..] || plain.len() as u64 != ctx.plain_size {
            return Err(fail(&name, "sha256 or plain_size"));
        }
        let k = open_key(
            &unhex(p, "rsk")?,
            &ctx,
            &unhex(p, "hpke_enc")?,
            &unhex(p, "sealed_key")?,
        )
        .map_err(|e| fail(&name, e))?;
        if k[..] != unhex(p, "k")?[..] {
            return Err(fail(&name, "the HPKE open doesn't give k"));
        }
        let k: [u8; KEY_LEN] = k[..].try_into().expect("32");
        let reseal = work_dir.join(format!("risime-history-vector-{n}.reseal"));
        let sealed = media::seal_reader_with_key(
            &mut &plain[..],
            plain.len() as u64,
            &reseal,
            &k,
            MAX_HISTORY_PLAIN_SIZE,
            HISTORY_BLOB_LABEL,
        );
        let again = std::fs::read(&reseal);
        let _ = std::fs::remove_file(&reseal);
        sealed.map_err(|e| fail(&name, e))?;
        if again.map_err(|e| fail(&name, e))? != cipher {
            return Err(fail(&name, "the blob re-sealed under k differs"));
        }
        let opened = run_case(p, work_dir, &n.to_string()).map_err(|e| fail(&name, e))?;
        if opened != plain {
            return Err(fail(&name, "opened plaintext differs"));
        }
        n += 1;
    }

    for c in doc["negative"].as_array().into_iter().flatten() {
        let name = vstr(c, "name")?;
        let base_name = vstr(c, "base")?;
        let base = positives
            .iter()
            .find(|p| p["name"] == base_name.as_str())
            .ok_or_else(|| fail(&name, "unknown base"))?;
        let mut v = base.clone();
        let change = c["change"]
            .as_object()
            .ok_or_else(|| fail(&name, "no change"))?;
        for (f, val) in change {
            v[f.as_str()] = val.clone();
        }
        if change.contains_key("rpk") {
            v["seal"] = serde_json::Value::Bool(true);
        }
        let want = vstr(c, "expect")?;
        match run_case(&v, work_dir, &n.to_string()) {
            Ok(_) => return Err(fail(&name, "succeeded")),
            Err(e) if expect_name(&e) == want => {}
            Err(e) => return Err(fail(&name, format!("expected {want}, got {e}"))),
        }
        n += 1;
    }
    Ok(n)
}

#[cfg(test)]
mod tests;
