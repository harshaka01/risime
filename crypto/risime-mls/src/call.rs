//! Group call frame keys from the MLS exporter (contract v1.19 §20.6, §10.3; crypto review K1, K2,
//! K10).
//!
//! - The exporter is behind a **label registry**: [`Client::export_secret`] (crate-internal)
//!   accepts only [`EXPORTER_LABELS`] and a length of [`EXPORTER_LENGTH`].
//! - `call_secret = MLS-Exporter("risime-call-v1", call_id as its 16 raw bytes, 32)` at the
//!   group's current epoch; it never leaves this module and is wiped after use.
//! - `frame_key(identity) = HKDF-Expand-SHA256(call_secret, "risime-call-v1 frame" ‖ 0x00 ‖
//!   identity UTF-8, 32)` for every leaf; `key_index = epoch mod 16`.
//!
//! Only the frame keys cross the FFI ([`Client::call_frame_keys`]); they go to LiveKit's
//! `FrameCryptor`. [`check_vectors`] verifies `contract/v1/call_vectors.json` with a reference
//! RFC 9420 §8.5 exporter (test support; it only ever sees the vectors' own secrets).

use hkdf::Hkdf;
use openmls::prelude::*;
use sha2::{Digest, Sha256};
use zeroize::{Zeroize, Zeroizing};

use crate::aad::parse_uuid;
use crate::{Client, DeviceId, MlsError, Result, group};

/// The exporter label of group call frame keys (§20.6).
pub const CALL_EXPORTER_LABEL: &str = "risime-call-v1";
/// The registry of MLS exporter labels (§10.3). A new label needs a contract change.
pub const EXPORTER_LABELS: &[&str] = &[CALL_EXPORTER_LABEL];
/// The only exporter output length the core produces.
pub const EXPORTER_LENGTH: usize = 32;
/// HKDF info prefix of a frame key; followed by 0x00 and the identity.
pub const FRAME_KEY_INFO_PREFIX: &[u8] = b"risime-call-v1 frame";
/// Frame key length (AES-GCM key material for LiveKit's `FrameCryptor`).
pub const FRAME_KEY_LEN: usize = 32;
/// LiveKit key ring size: `key_index = epoch mod KEY_RING_SIZE` (§20.5 `keyRingSize`).
pub const KEY_RING_SIZE: u64 = 16;

/// One leaf's frame key. The key is wiped when the value is dropped.
#[derive(Clone, PartialEq, Eq)]
pub struct CallFrameKey {
    /// The leaf credential identity `"<user_id>/<device_id>"` (the LiveKit participant identity).
    pub identity: String,
    /// 32 bytes.
    pub key: Vec<u8>,
}

impl Drop for CallFrameKey {
    fn drop(&mut self) {
        self.key.zeroize();
    }
}

impl std::fmt::Debug for CallFrameKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "CallFrameKey {{ identity: {:?}, key: *** }}",
            self.identity
        )
    }
}

/// Result of [`Client::call_frame_keys`]: every leaf's frame key at `epoch`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CallFrameKeys {
    /// The group's current epoch, the one the keys were derived at.
    pub epoch: u64,
    /// `epoch mod 16`: `setKey(identity, key, key_index)` and `setKeyIndex(key_index)`.
    pub key_index: u8,
    /// One per leaf, in leaf order (this device included).
    pub keys: Vec<CallFrameKey>,
}

/// The registry check behind [`Client::export_secret`].
pub(crate) fn check_export(label: &str, length: usize) -> Result<()> {
    if !EXPORTER_LABELS.contains(&label) {
        return Err(MlsError::Malformed(format!(
            "exporter label {label:?} is not registered"
        )));
    }
    if length != EXPORTER_LENGTH {
        return Err(MlsError::Malformed(format!(
            "exporter length {length} (only {EXPORTER_LENGTH})"
        )));
    }
    Ok(())
}

/// `epoch mod 16`.
pub fn key_index(epoch: u64) -> u8 {
    (epoch % KEY_RING_SIZE) as u8
}

/// `"risime-call-v1 frame" ‖ 0x00 ‖ identity`.
fn frame_info(identity: &[u8]) -> Vec<u8> {
    let mut info = Vec::with_capacity(FRAME_KEY_INFO_PREFIX.len() + 1 + identity.len());
    info.extend_from_slice(FRAME_KEY_INFO_PREFIX);
    info.push(0);
    info.extend_from_slice(identity);
    info
}

fn hkdf_expand(prk: &[u8], info: &[u8], len: usize) -> Result<Vec<u8>> {
    let h = Hkdf::<Sha256>::from_prk(prk).map_err(|e| MlsError::Other(format!("hkdf prk: {e}")))?;
    let mut out = vec![0u8; len];
    h.expand(info, &mut out)
        .map_err(|e| MlsError::Other(format!("hkdf expand: {e}")))?;
    Ok(out)
}

/// `HKDF-Expand-SHA256(call_secret, "risime-call-v1 frame" ‖ 0x00 ‖ identity, 32)`.
pub(crate) fn frame_key(call_secret: &[u8], identity: &str) -> Result<Vec<u8>> {
    hkdf_expand(call_secret, &frame_info(identity.as_bytes()), FRAME_KEY_LEN)
}

impl Client {
    /// The MLS exporter of the group's **current** epoch, restricted to the label registry
    /// (§10.3, §20.6): any other label or a length other than 32 is [`MlsError::Malformed`].
    /// Crate-internal: the exporter output never crosses the FFI.
    pub(crate) fn export_secret(
        &self,
        group: &MlsGroup,
        label: &str,
        context: &[u8],
        length: usize,
    ) -> Result<Zeroizing<Vec<u8>>> {
        check_export(label, length)?;
        if !group.is_active() {
            return Err(MlsError::RemovedFromGroup);
        }
        group
            .export_secret(self.provider.crypto(), label, context, length)
            .map(Zeroizing::new)
            .map_err(crate::other)
    }

    /// Every leaf's frame key for the group call `call_id` at the group's current epoch (§20.6).
    ///
    /// - `group_id`: a `grp:` group (DMs keep the P2P path of §16/§19: `Malformed`).
    /// - `call_id`: the UUID from the MLS-authenticated `call_offer` (or the device's own), never
    ///   from the server; either case (`Malformed` otherwise).
    ///
    /// Catch up the group's commits first (K4): this derives at the newest epoch the device
    /// knows. Call again after every merged commit (K3). `UnknownGroup` without local state;
    /// `RemovedFromGroup` once this device was removed. Nothing is stored; the keys are wiped
    /// when the result is dropped (K10).
    pub fn call_frame_keys(&self, group_id: &[u8], call_id: &str) -> Result<CallFrameKeys> {
        if !group::is_group_id(group_id) {
            return Err(MlsError::Malformed("call keys need a grp: group".into()));
        }
        let context = parse_uuid(call_id)?;
        let group = self.load(group_id)?;
        let call_secret = self.export_secret(&group, CALL_EXPORTER_LABEL, &context, 32)?;
        let mut keys = Vec::new();
        for m in group.members() {
            let raw = m.credential.serialized_content();
            // Validates the identity (it was validated when the leaf entered the group).
            let identity = DeviceId::parse(raw)?.identity();
            if identity.as_bytes() != raw {
                return Err(MlsError::Malformed("non-canonical leaf identity".into()));
            }
            let key = frame_key(&call_secret, &identity)?;
            keys.push(CallFrameKey { identity, key });
        }
        let epoch = group.epoch().as_u64();
        Ok(CallFrameKeys {
            epoch,
            key_index: key_index(epoch),
            keys,
        })
    }
}

// ------------------------------------------------------------------ RFC 9420 reference exporter

/// RFC 9000 variable-length integer (RFC 9420 §2.1.2 vector lengths).
fn varint(n: usize) -> Vec<u8> {
    if n < 1 << 6 {
        vec![n as u8]
    } else if n < 1 << 14 {
        ((n as u16) | 0x4000).to_be_bytes().to_vec()
    } else {
        ((n as u32) | 0x8000_0000).to_be_bytes().to_vec()
    }
}

/// `ExpandWithLabel(secret, label, context, len)` for SHA-256 (RFC 9420 §8).
fn expand_with_label(secret: &[u8], label: &[u8], context: &[u8], len: usize) -> Result<Vec<u8>> {
    let mut full = b"MLS 1.0 ".to_vec();
    full.extend_from_slice(label);
    let mut kdf_label = (len as u16).to_be_bytes().to_vec();
    kdf_label.extend(varint(full.len()));
    kdf_label.extend(&full);
    kdf_label.extend(varint(context.len()));
    kdf_label.extend_from_slice(context);
    hkdf_expand(secret, &kdf_label, len)
}

/// `MLS-Exporter` from a raw `exporter_secret` (RFC 9420 §8.5, ciphersuite 0x0001). Reference for
/// the vectors only: production goes through OpenMLS ([`Client::export_secret`]).
pub(crate) fn mls_exporter_reference(
    exporter_secret: &[u8],
    label: &[u8],
    context: &[u8],
    len: usize,
) -> Result<Vec<u8>> {
    let derived = Zeroizing::new(expand_with_label(exporter_secret, label, &[], 32)?);
    expand_with_label(&derived, b"exported", &Sha256::digest(context), len)
}

// ------------------------------------------------------------------ vectors

fn malformed(s: impl Into<String>) -> MlsError {
    MlsError::Malformed(s.into())
}

fn fail(name: &str, what: impl std::fmt::Display) -> MlsError {
    malformed(format!("vector {name}: {what}"))
}

fn vstr<'a>(v: &'a serde_json::Value, field: &str) -> Result<&'a str> {
    v[field]
        .as_str()
        .ok_or_else(|| malformed(format!("vector field {field} missing")))
}

fn vu64(v: &serde_json::Value, field: &str) -> Result<u64> {
    v[field]
        .as_u64()
        .ok_or_else(|| malformed(format!("vector field {field} missing")))
}

fn unhex(v: &serde_json::Value, field: &str) -> Result<Vec<u8>> {
    let s = vstr(v, field)?;
    if s.len() % 2 != 0 || s.bytes().any(|c| c.is_ascii_uppercase()) {
        return Err(malformed(format!(
            "vector field {field}: not lowercase hex"
        )));
    }
    (0..s.len())
        .step_by(2)
        .map(|i| {
            u8::from_str_radix(&s[i..i + 2], 16)
                .map_err(|_| malformed(format!("vector field {field}: bad hex")))
        })
        .collect()
}

#[cfg(test)]
fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// The `keys` and `key_index` of a case against `secret`.
fn check_keys(name: &str, c: &serde_json::Value, secret: &[u8]) -> Result<()> {
    use base64::Engine as _;
    let keys = c["keys"]
        .as_array()
        .ok_or_else(|| fail(name, "keys missing"))?;
    if keys.is_empty() {
        return Err(fail(name, "no keys"));
    }
    if vu64(c, "key_index")? != u64::from(key_index(vu64(c, "epoch")?)) {
        return Err(fail(name, "key_index"));
    }
    for k in keys {
        let identity = vstr(k, "identity")?;
        let want = frame_key(secret, identity)?;
        if unhex(k, "frame_key")? != want {
            return Err(fail(name, format!("frame_key of {identity}")));
        }
        if unhex(k, "info")? != frame_info(identity.as_bytes()) {
            return Err(fail(name, format!("info of {identity}")));
        }
        if !k["frame_key_base64"].is_null()
            && vstr(k, "frame_key_base64")?
                != base64::engine::general_purpose::STANDARD.encode(&want)
        {
            return Err(fail(name, format!("frame_key_base64 of {identity}")));
        }
    }
    Ok(())
}

/// **Test support:** verifies every case of a `call_vectors.json` document (§20.6) with this core
/// and returns the number of cases checked; the first failing case is `Malformed("vector <name>:
/// …")`. `frame_keys`: each key from `call_secret`. `exporter_cases`: the RFC 9420 exporter check
/// of the epoch, then `exporter_secret` + `call_id` → `call_secret` → keys. `negative`: `export`
/// cases through the core's label registry; `frame_key` / `call_secret` cases must reproduce
/// their wrong result and differ from the correct one. Returns nothing secret.
pub fn check_vectors(json: &str) -> Result<u32> {
    let doc: serde_json::Value =
        serde_json::from_str(json).map_err(|e| malformed(format!("vectors json: {e}")))?;
    if doc["v"] != 1
        || doc["label"] != CALL_EXPORTER_LABEL
        || doc["exporter_length"] != EXPORTER_LENGTH
        || doc["frame_key_info_prefix"] != std::str::from_utf8(FRAME_KEY_INFO_PREFIX).unwrap()
        || doc["registered_labels"] != serde_json::json!(EXPORTER_LABELS)
    {
        return Err(malformed(
            "vectors: wrong v, label, length, prefix or registry",
        ));
    }
    let mut n = 0u32;
    for c in doc["frame_keys"].as_array().into_iter().flatten() {
        let name = vstr(c, "name")?;
        parse_uuid(vstr(c, "call_id")?)?;
        let secret = Zeroizing::new(unhex(c, "call_secret")?);
        check_keys(name, c, &secret)?;
        n += 1;
    }
    for c in doc["exporter_cases"]["cases"]
        .as_array()
        .into_iter()
        .flatten()
    {
        let name = vstr(c, "name")?;
        let es = unhex(c, "exporter_secret")?;
        let r = &c["rfc_exporter"];
        let rfc = mls_exporter_reference(
            &es,
            vstr(r, "label")?.as_bytes(),
            &unhex(r, "context")?,
            vu64(r, "length")? as usize,
        )?;
        if rfc != unhex(r, "secret")? {
            return Err(fail(name, "RFC 9420 exporter check"));
        }
        let ctx = parse_uuid(vstr(c, "call_id")?)?;
        if unhex(c, "context")? != ctx {
            return Err(fail(name, "context"));
        }
        check_export(CALL_EXPORTER_LABEL, 32)?;
        let secret = mls_exporter_reference(&es, CALL_EXPORTER_LABEL.as_bytes(), &ctx, 32)?;
        if unhex(c, "call_secret")? != secret {
            return Err(fail(name, "call_secret"));
        }
        check_keys(name, c, &secret)?;
        n += 1;
    }
    for c in doc["negative"].as_array().into_iter().flatten() {
        let name = vstr(c, "name")?;
        match (vstr(c, "kind")?, vstr(c, "expect")?) {
            ("export", expect) => {
                let ok = check_export(vstr(c, "label")?, vu64(c, "length")? as usize).is_ok();
                if ok != (expect == "ok") || !matches!(expect, "ok" | "refused") {
                    return Err(fail(name, "registry"));
                }
            }
            ("frame_key", "mismatch") => {
                let secret = unhex(c, "call_secret")?;
                let wrong = hkdf_expand(&secret, &unhex(c, "info")?, FRAME_KEY_LEN)?;
                let right = frame_key(&secret, vstr(c, "identity")?)?;
                if wrong != unhex(c, "key")? || right != unhex(c, "correct_key")? || wrong == right
                {
                    return Err(fail(name, "frame_key mismatch case"));
                }
            }
            ("call_secret", "mismatch") => {
                let es = unhex(c, "exporter_secret")?;
                let wrong = mls_exporter_reference(
                    &es,
                    CALL_EXPORTER_LABEL.as_bytes(),
                    &unhex(c, "context")?,
                    32,
                )?;
                let ctx = parse_uuid(vstr(c, "call_id")?)?;
                let right = mls_exporter_reference(&es, CALL_EXPORTER_LABEL.as_bytes(), &ctx, 32)?;
                if wrong != unhex(c, "call_secret")?
                    || right != unhex(c, "correct_call_secret")?
                    || wrong == right
                {
                    return Err(fail(name, "call_secret mismatch case"));
                }
            }
            (k, e) => return Err(fail(name, format!("unknown kind/expect {k}/{e}"))),
        }
        n += 1;
    }
    if n == 0 {
        return Err(malformed("vectors: no cases"));
    }
    Ok(n)
}

#[cfg(test)]
mod tests {
    //! The vector generator mirrors `scripts/gen-call-vectors` (same cases, same seeded inputs):
    //! `cargo test -p risime-mls call_vectors_write -- --ignored` writes
    //! `contract/v1/call_vectors.json`; `call_vectors_match_the_contract` fails on any difference.

    use super::*;
    use serde_json::{Value, json};
    use std::path::{Path, PathBuf};

    const CALL_A: &str = "4b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10";
    const CALL_B: &str = "9a3f6c2d-7e1b-4c58-a0d4-3b2e1f0c9d87";
    const U1: &str = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3/c0a80101-0000-4000-8000-000000000001";
    const U2: &str = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e/c0a80101-0000-4000-8000-000000000002";
    const U3: &str = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e/c0a80101-0000-4000-8000-000000000003";
    const U4: &str = "5d2c8f1e-4a6b-4c3d-9e8f-7a6b5c4d3e2f/c0a80101-0000-4000-8000-000000000004";

    /// RFC 9420 key-schedule test vectors, cipher_suite 1 (see `scripts/gen-call-vectors`):
    /// (exporter_secret, exporter.label as text, exporter.context hex, exporter.secret).
    const RFC_GROUP_ID: &str = "a897b53575b4dd35fed4466e4e714bfa949eaa72e616a9c68a47b39cb7a60d2e";
    const RFC_EPOCHS: [(&str, &str, &str, &str); 5] = [
        (
            "5a097e149f2a375d0b9e1d1f4dc3a9c6c1788df888e5441f41a8791f4dc56cea",
            "9ba13d54ecdec7cbefcb47b4268d7b1990fabc6d6e67681e167959389d84e4e4",
            "884f1af892ab002f5be4c5d5081ade9e0e6418c6ea7a9a92e90534f19dcef785",
            "dbce4e25e59ab4dfa6f6200f113ed08393cf6e7286d024811141c6a4dd11c0cb",
        ),
        (
            "047d983048b132b79ea4e2e578afd02a0f4717d166cefe46e43e2e965b5c9f4e",
            "ed66d7f1da52171ac9448f0f902edcfefa4ebbda843a43bd3d173cb7c5b4331e",
            "02dc18fc5bc4d9093cf41fa0053521653775b123784d40ac7d46cc5a72ef4d46",
            "a702c3e70a89c06eae51aec3675918a4ee7698ae88c596dfb7abd1ed3a9ecf5e",
        ),
        (
            "69cb23d2ff46b36c466ad48911fd56ee108ab8d4ec1c91028622e9a93c067710",
            "06f549e9bf966d7b7135b6ef6d4e3032a1c720adf0281c3ef1c0beac0da88621",
            "b0fa7e3f0f2199278a55267d551d43946bbfc6d847632867dd86abd1217982a5",
            "07dd60ff9acb0278f80cae4c6f4ee69c745481f5bf967b21d237c18e48d78b4b",
        ),
        (
            "9f2a260a99b352c9c9c90f081050380c9e8b5f400b41461f4e40690a2f111b03",
            "b76193af29eadc6e16c66493e2a4ef8219aad79986b6d4911493741ec1e666cc",
            "941db06073e76050679d33cf1f0ec33de3e5a2cd00cb738b54c0dd90de251cdd",
            "d379ee776bbaadb55b2bf74ffb4e53b96307611a115b6a67f165192b6c481bce",
        ),
        (
            "7e6f8cedec75018d137c1d08444c8695e52fe73efe0650f928007dcbcfa48b2b",
            "cc9c4b25b0bd69250b6e4f9908d1b170bf62fe8cc11ad36d33e602324ac0662a",
            "a0f762fc82d5e421d8fdf8a317b2fd463008c625bf19db7fbfa4ac778b5106a2",
            "f4698636cc032717011a186a14a42cc49e95aeeb4d9bc8ab82295fc1543735ac",
        ),
    ];

    fn unh(s: &str) -> Vec<u8> {
        unhex(&json!({ "x": s }), "x").unwrap()
    }

    fn seeded(label: &str, len: usize) -> Vec<u8> {
        let mut out = Vec::new();
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

    fn keys_json(secret: &[u8], identities: &[&str]) -> Value {
        use base64::Engine as _;
        Value::Array(
            identities
                .iter()
                .map(|id| {
                    let k = frame_key(secret, id).unwrap();
                    json!({
                        "identity": id,
                        "info": hex(&frame_info(id.as_bytes())),
                        "frame_key": hex(&k),
                        "frame_key_base64": base64::engine::general_purpose::STANDARD.encode(&k),
                    })
                })
                .collect(),
        )
    }

    fn frame_case(name: &str, call_id: &str, epoch: u64, identities: &[&str]) -> Value {
        let s = seeded(&format!("risime-call-vector call_secret {name}"), 32);
        json!({
            "name": name, "call_id": call_id, "epoch": epoch, "key_index": epoch % 16,
            "call_secret": hex(&s), "keys": keys_json(&s, identities),
        })
    }

    fn call_secret_ref(es: &[u8], call_id: &str) -> Vec<u8> {
        mls_exporter_reference(
            es,
            CALL_EXPORTER_LABEL.as_bytes(),
            &parse_uuid(call_id).unwrap(),
            32,
        )
        .unwrap()
    }

    fn exporter_case(epoch: usize, call_id: &str, identities: &[&str]) -> Value {
        let (es, rl, rc, rs) = RFC_EPOCHS[epoch];
        let es = unh(es);
        assert_eq!(
            hex(&mls_exporter_reference(&es, rl.as_bytes(), &unh(rc), 32).unwrap()),
            rs,
            "RFC 9420 exporter vector, epoch {epoch}"
        );
        let s = call_secret_ref(&es, call_id);
        let suffix = if call_id == CALL_A { "" } else { "_other_call" };
        json!({
            "name": format!("rfc9420_epoch_{epoch}{suffix}"),
            "epoch": epoch, "key_index": epoch % 16, "exporter_secret": hex(&es),
            "rfc_exporter": {"label": rl, "context": rc, "length": 32, "secret": rs},
            "call_id": call_id, "context": hex(&parse_uuid(call_id).unwrap()),
            "call_secret": hex(&s), "keys": keys_json(&s, identities),
        })
    }

    fn info_with(identity: &[u8], sep: bool, trailer: &[u8]) -> Vec<u8> {
        let mut i = FRAME_KEY_INFO_PREFIX.to_vec();
        if sep {
            i.push(0);
        }
        i.extend_from_slice(identity);
        i.extend_from_slice(trailer);
        i
    }

    fn mismatch(name: &str, why: &str, secret: &[u8], identity: &str, info: Vec<u8>) -> Value {
        let good = frame_key(secret, identity).unwrap();
        let bad = hkdf_expand(secret, &info, 32).unwrap();
        assert_ne!(bad, good);
        json!({
            "name": name, "why": why, "kind": "frame_key", "call_secret": hex(secret),
            "identity": identity, "info": hex(&info), "key": hex(&bad), "correct_key": hex(&good),
            "expect": "mismatch",
        })
    }

    fn export_neg(name: &str, why: &str, label: &str, length: u64, expect: &str) -> Value {
        json!({"name": name, "why": why, "kind": "export", "label": label, "length": length,
               "expect": expect})
    }

    pub(super) fn build_vectors() -> Value {
        let frames = vec![
            frame_case("voice_three_members", CALL_A, 1, &[U1, U2, U3]),
            frame_case("single_member", CALL_A, 2, &[U1]),
            frame_case("epoch_15", CALL_A, 15, &[U1, U2]),
            frame_case("epoch_16_wraps_to_0", CALL_A, 16, &[U1, U2]),
            frame_case("epoch_17", CALL_B, 17, &[U1, U2]),
            frame_case("large_epoch", CALL_B, 4_294_967_311, &[U1, U4]),
            frame_case("non_ascii_identity", CALL_B, 3, &["usér-ü/dévice-ß", U2]),
            frame_case(
                "video_eight_members",
                CALL_B,
                40,
                &[
                    U1,
                    U2,
                    U3,
                    U4,
                    "5d2c8f1e-4a6b-4c3d-9e8f-7a6b5c4d3e2f/c0a80101-0000-4000-8000-000000000005",
                    "6e3d9a2f-5b7c-4d4e-af90-8b7c6d5e4f30/c0a80101-0000-4000-8000-000000000006",
                    "7f4eab30-6c8d-4e5f-b0a1-9c8d7e6f5041/c0a80101-0000-4000-8000-000000000007",
                    "8a5fbc41-7d9e-4f60-81b2-ad9e8f706152/c0a80101-0000-4000-8000-000000000008",
                ],
            ),
        ];
        let mut exporter: Vec<Value> = (0..RFC_EPOCHS.len())
            .map(|e| exporter_case(e, CALL_A, &[U1, U2]))
            .collect();
        exporter.push(exporter_case(4, CALL_B, &[U1, U2]));

        let base = unh(frames[0]["call_secret"].as_str().unwrap());
        let utf16: Vec<u8> = U1.encode_utf16().flat_map(|u| u.to_le_bytes()).collect();
        let user_only = U1.split('/').next().unwrap();
        let mut negative = vec![
            export_neg(
                "unregistered_label",
                "a label outside the registry",
                "risime-call-v2",
                32,
                "refused",
            ),
            export_neg("empty_label", "the empty label", "", 32, "refused"),
            export_neg(
                "label_case",
                "labels compare byte for byte",
                "RISIME-CALL-V1",
                32,
                "refused",
            ),
            export_neg(
                "label_with_frame_suffix",
                "the frame-key info prefix is not an exporter label",
                "risime-call-v1 frame",
                32,
                "refused",
            ),
            export_neg(
                "length_16",
                "length != 32",
                CALL_EXPORTER_LABEL,
                16,
                "refused",
            ),
            export_neg(
                "length_64",
                "length != 32",
                CALL_EXPORTER_LABEL,
                64,
                "refused",
            ),
            export_neg(
                "length_0",
                "length != 32",
                CALL_EXPORTER_LABEL,
                0,
                "refused",
            ),
            export_neg(
                "registered",
                "the one accepted export (control case)",
                CALL_EXPORTER_LABEL,
                32,
                "ok",
            ),
            mismatch(
                "identity_utf16le",
                "identity encoded as UTF-16LE instead of UTF-8",
                &base,
                U1,
                info_with(&utf16, true, b""),
            ),
            mismatch(
                "no_separator",
                "the 0x00 between prefix and identity left out",
                &base,
                U1,
                info_with(U1.as_bytes(), false, b""),
            ),
            mismatch(
                "nul_terminated_identity",
                "identity followed by a trailing 0x00",
                &base,
                U1,
                info_with(U1.as_bytes(), true, b"\x00"),
            ),
            mismatch(
                "identity_uppercase",
                "identity case changed (identities compare byte for byte)",
                &base,
                U1,
                info_with(U1.to_uppercase().as_bytes(), true, b""),
            ),
            mismatch(
                "user_id_only",
                "only the user_id half of the identity",
                &base,
                U1,
                info_with(user_only.as_bytes(), true, b""),
            ),
        ];
        let es0 = unh(RFC_EPOCHS[0].0);
        let good = call_secret_ref(&es0, CALL_A);
        for (name, why, ctx) in [
            (
                "call_id_as_text",
                "context = the call_id's UTF-8 text instead of its 16 raw bytes",
                CALL_A.as_bytes(),
            ),
            (
                "call_id_empty_context",
                "an empty exporter context",
                &b""[..],
            ),
        ] {
            let bad =
                mls_exporter_reference(&es0, CALL_EXPORTER_LABEL.as_bytes(), ctx, 32).unwrap();
            assert_ne!(bad, good);
            negative.push(json!({
                "name": name, "why": why, "kind": "call_secret", "exporter_secret": hex(&es0),
                "call_id": CALL_A, "context": hex(ctx), "call_secret": hex(&bad),
                "correct_call_secret": hex(&good), "expect": "mismatch",
            }));
        }

        json!({
            "v": 1,
            "comment": "Group call frame keys (contract v1.19 §20.6, crypto review K1/K2). Generated by \
                        `cargo test -p risime-mls call_vectors_write -- --ignored` and, independently, by \
                        scripts/gen-call-vectors; the two are byte-identical. All binary fields are \
                        lowercase hex. frame_keys: call_secret -> per-identity frame_key and key_index. \
                        exporter_cases: the RFC 9420 key-schedule test group (cipher_suite 1): check \
                        rfc_exporter first, then exporter_secret + call_id -> call_secret -> keys. \
                        negative: kind export = the label registry (refused/ok); kind frame_key / \
                        call_secret = a wrong encoding whose result (key / call_secret) must differ \
                        from the correct one.",
            "label": CALL_EXPORTER_LABEL,
            "registered_labels": EXPORTER_LABELS,
            "exporter_length": EXPORTER_LENGTH,
            "exporter": "MLS-Exporter(label, call_id as 16 raw bytes, 32) = ExpandWithLabel(\
                         DeriveSecret(exporter_secret, label), \"exported\", SHA-256(context), 32) \
                         (RFC 9420 §8.5, ciphersuite 0x0001)",
            "frame_key": "HKDF-Expand-SHA256(call_secret, info, 32), info = frame_key_info_prefix || \
                          0x00 || identity (UTF-8, \"<user_id>/<device_id>\")",
            "frame_key_info_prefix": std::str::from_utf8(FRAME_KEY_INFO_PREFIX).unwrap(),
            "key_index": "epoch mod 16",
            "frame_keys": frames,
            "exporter_cases": {
                "source": "RFC 9420 key-schedule test vectors (mlswg/mls-implementations \
                           test-vectors/key-schedule.json), cipher_suite 1; rfc_exporter.label is \
                           used as text (its ASCII bytes), rfc_exporter.context is hex",
                "cipher_suite": 1,
                "group_id": RFC_GROUP_ID,
                "cases": exporter,
            },
            "negative": negative,
        })
    }

    fn contract_path() -> PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR")).join("../../contract/v1/call_vectors.json")
    }

    fn render(v: &Value) -> String {
        serde_json::to_string_pretty(v).unwrap() + "\n"
    }

    /// Writes `contract/v1/call_vectors.json` (root then checks it with `scripts/gen-call-vectors
    /// --check`).
    #[test]
    #[ignore]
    fn call_vectors_write() {
        std::fs::write(contract_path(), render(&build_vectors())).unwrap();
    }

    /// The committed contract file is exactly the generator's output (byte for byte), and every
    /// case passes the verifier.
    #[test]
    fn call_vectors_match_the_contract() {
        let have = std::fs::read_to_string(contract_path()).expect("contract/v1/call_vectors.json");
        assert_eq!(
            have,
            render(&build_vectors()),
            "contract/v1/call_vectors.json differs from the core's generator"
        );
        assert_eq!(check_vectors(&have).unwrap(), 8 + 6 + 15);
    }

    #[test]
    fn verifier_rejects_altered_vectors() {
        let good = build_vectors();
        let alter = |f: &dyn Fn(&mut Value)| {
            let mut v = good.clone();
            f(&mut v);
            check_vectors(&v.to_string())
        };
        let flip = |s: &str| {
            let mut b = unh(s);
            b[0] ^= 1;
            hex(&b)
        };
        for r in [
            alter(&|v| {
                let k = v["frame_keys"][0]["keys"][1]["frame_key"]
                    .as_str()
                    .unwrap()
                    .to_owned();
                v["frame_keys"][0]["keys"][1]["frame_key"] = json!(flip(&k));
            }),
            alter(&|v| v["frame_keys"][3]["key_index"] = json!(16)),
            alter(&|v| v["frame_keys"][0]["keys"][0]["identity"] = json!(U4)),
            alter(&|v| {
                let s = v["exporter_cases"]["cases"][2]["call_secret"]
                    .as_str()
                    .unwrap()
                    .to_owned();
                v["exporter_cases"]["cases"][2]["call_secret"] = json!(flip(&s));
            }),
            alter(&|v| v["exporter_cases"]["cases"][0]["call_id"] = json!(CALL_B)),
            alter(&|v| v["negative"][0]["expect"] = json!("ok")),
            alter(&|v| v["negative"][7]["expect"] = json!("refused")),
            alter(&|v| v["negative"][8]["key"] = v["negative"][8]["correct_key"].clone()),
            alter(&|v| v["registered_labels"] = json!(["risime-call-v1", "other"])),
        ] {
            assert!(matches!(r, Err(MlsError::Malformed(_))), "{r:?}");
        }
    }

    #[test]
    fn registry_accepts_only_the_call_label_and_32_bytes() {
        assert_eq!(EXPORTER_LABELS, &["risime-call-v1"]);
        assert!(check_export("risime-call-v1", 32).is_ok());
        for (l, n) in [
            ("risime-call-v2", 32),
            ("", 32),
            ("risime-call-v1 ", 32),
            ("risime-history-v1", 32),
            ("risime-call-v1", 31),
            ("risime-call-v1", 33),
            ("risime-call-v1", 0),
        ] {
            assert!(matches!(check_export(l, n), Err(MlsError::Malformed(_))));
        }
    }

    /// `export_secret` on a real group: only the registered label and length 32 (§20.6, K1).
    #[test]
    fn export_secret_refuses_other_labels_and_lengths() {
        use crate::{DeviceId, GroupMeta, TestAttestor};
        let a = TestAttestor::from_seed([7; 32]);
        let mk = |u: &str, d: &str| {
            let mut c = Client::in_memory(DeviceId::new(u, d).unwrap(), a.anchors()).unwrap();
            let jws = a.attest(c.device(), &c.signature_public_key(), 1_760_000_000);
            c.set_attestation(&jws).unwrap();
            c
        };
        let (alice, bob) = (mk("alice", "a1"), mk("bob", "b1"));
        let g = b"grp:2c7d9e41-6a3b-4f58-9c0e-1b2a3d4c5e6f#1";
        let kp = bob.generate_key_packages(1).unwrap();
        alice
            .create_group_with_meta(g, &kp, &GroupMeta::new("x", vec!["alice".into()]))
            .unwrap();
        alice.commit_accepted(g).unwrap();
        let group = alice.load(g).unwrap();
        let ok = alice
            .export_secret(&group, "risime-call-v1", b"ctx", 32)
            .unwrap();
        assert_eq!(ok.len(), 32);
        for (l, n) in [
            ("risime-call-v2", 32),
            ("", 32),
            ("risime-history-v1", 32),
            ("risime-call-v1", 16),
            ("risime-call-v1", 64),
        ] {
            assert!(matches!(
                alice.export_secret(&group, l, b"ctx", n),
                Err(MlsError::Malformed(_))
            ));
        }
        // The context separates calls.
        assert_ne!(
            *alice
                .export_secret(&group, "risime-call-v1", b"other", 32)
                .unwrap(),
            *ok
        );
    }

    #[test]
    fn key_index_wraps_at_16() {
        assert_eq!(
            [0, 1, 15, 16, 17, 31, 32, u64::MAX].map(key_index),
            [0, 1, 15, 0, 1, 15, 0, 15]
        );
    }

    #[test]
    fn varint_lengths() {
        assert_eq!(varint(63), [63]);
        assert_eq!(varint(64), [0x40, 0x40]);
        assert_eq!(varint(16383), [0x7f, 0xff]);
        assert_eq!(varint(16384), [0x80, 0, 0x40, 0]);
    }

    #[test]
    fn frame_key_debug_hides_the_key() {
        let k = CallFrameKey {
            identity: "u/d".into(),
            key: vec![0xab; 32],
        };
        assert!(!format!("{k:?}").contains("ab"));
    }
}
