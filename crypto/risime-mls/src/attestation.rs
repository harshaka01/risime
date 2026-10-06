//! Device identity and server attestation (contract v1.7 §10.0/§10.1, decisions 032/033).
//!
//! * Every leaf's credential identity is `"<user_id>/<device_id>"` ([`DeviceId`]).
//! * The server signs `{aud:"risime-mls", user_id, device_id, signature_key, iat, v:1}` as an
//!   EdDSA JWS (`typ` = `risime-attest+jwt`, `kid` = RFC 7638 thumbprint). The JWS travels in the
//!   leaf's RFC 9420 `application_id` extension and is verified here, offline, against keys pinned
//!   in the app ([`TrustAnchors`]). Kotlin never decides trust.

use base64::Engine as _;
use base64::engine::general_purpose::{STANDARD, STANDARD_NO_PAD, URL_SAFE_NO_PAD};
use ed25519_dalek::{Signature, Signer as _, SigningKey, VerifyingKey};
use serde::Deserialize;
use sha2::{Digest, Sha256};

use crate::{MlsError, Result};

/// One device of one user: the MLS leaf identity.
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct DeviceId {
    pub user_id: String,
    pub device_id: String,
}

const MAX_PART: usize = 64;

impl DeviceId {
    pub fn new(user_id: impl Into<String>, device_id: impl Into<String>) -> Result<Self> {
        let d = Self {
            user_id: user_id.into(),
            device_id: device_id.into(),
        };
        for part in [&d.user_id, &d.device_id] {
            if part.is_empty()
                || part.len() > MAX_PART
                || part.contains('/')
                || part.chars().any(|c| c.is_control() || c.is_whitespace())
            {
                return Err(MlsError::Malformed(format!("invalid id part {part:?}")));
            }
        }
        Ok(d)
    }

    /// `"<user_id>/<device_id>"`, the credential identity.
    pub fn identity(&self) -> String {
        format!("{}/{}", self.user_id, self.device_id)
    }

    /// Parse a credential identity. Exactly one `/`, both halves valid.
    pub fn parse(identity: &[u8]) -> Result<Self> {
        let s = std::str::from_utf8(identity)
            .map_err(|_| MlsError::Malformed("identity is not UTF-8".into()))?;
        let (u, d) = s
            .split_once('/')
            .ok_or_else(|| MlsError::Malformed(format!("identity {s:?} has no '/'")))?;
        Self::new(u, d)
    }
}

impl std::fmt::Display for DeviceId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.identity())
    }
}

/// Decides whether a leaf may be in a group. Called for every key package we add, every leaf of
/// a group we join, and every leaf a commit adds or replaces.
pub trait CredentialValidator: Send + Sync {
    /// `attestation` is the raw `application_id` of the leaf (`None` when absent).
    fn validate(
        &self,
        device: &DeviceId,
        signature_key: &[u8],
        attestation: Option<&[u8]>,
    ) -> Result<()>;
}

/// Pinned server attestation keys (active plus still-trusted previous ones).
#[derive(Debug, Clone, Default)]
pub struct TrustAnchors {
    keys: Vec<TrustedKey>,
}

#[derive(Debug, Clone)]
struct TrustedKey {
    /// RFC 7638 thumbprint, plus the JWK's own `kid` if it declared a different one.
    kids: Vec<String>,
    key: VerifyingKey,
}

#[derive(Deserialize)]
struct Jwk {
    kty: String,
    crv: String,
    x: String,
    kid: Option<String>,
}

impl TrustAnchors {
    /// No keys: every leaf is rejected. Useful only in tests.
    pub fn none() -> Self {
        Self::default()
    }

    /// From public JWKs (`{"kty":"OKP","crv":"Ed25519","x":…}`), as served by
    /// `GET /api/v1/mls/attestation_keys`.
    pub fn from_jwks<S: AsRef<str>>(jwks: &[S]) -> Result<Self> {
        let mut keys = Vec::new();
        for j in jwks {
            let jwk: Jwk = serde_json::from_str(j.as_ref())
                .map_err(|e| MlsError::Malformed(format!("jwk: {e}")))?;
            if jwk.kty != "OKP" || jwk.crv != "Ed25519" {
                return Err(MlsError::Malformed("jwk is not an Ed25519 OKP key".into()));
            }
            let x = URL_SAFE_NO_PAD
                .decode(jwk.x.trim_end_matches('='))
                .map_err(|e| MlsError::Malformed(format!("jwk x: {e}")))?;
            let key = verifying_key(&x)?;
            let mut kids = vec![thumbprint(&key)];
            if let Some(k) = jwk.kid.filter(|k| !kids.contains(k)) {
                kids.push(k);
            }
            keys.push(TrustedKey { kids, key });
        }
        Ok(Self { keys })
    }

    pub fn len(&self) -> usize {
        self.keys.len()
    }

    pub fn is_empty(&self) -> bool {
        self.keys.is_empty()
    }

    /// Verify an attestation JWS for `device` and its leaf `signature_key`.
    pub fn verify(&self, jws: &str, device: &DeviceId, signature_key: &[u8]) -> Result<()> {
        let untrusted = |why: &str| MlsError::UntrustedCredential(format!("{device}: {why}"));
        let mut parts = jws.split('.');
        let (Some(h64), Some(p64), Some(s64), None) =
            (parts.next(), parts.next(), parts.next(), parts.next())
        else {
            return Err(untrusted("attestation is not a compact JWS"));
        };
        let header: serde_json::Value = decode_json(h64).ok_or_else(|| untrusted("bad header"))?;
        if header["alg"] != "EdDSA" || header["typ"] != "risime-attest+jwt" {
            return Err(untrusted("wrong alg/typ"));
        }
        let kid = header["kid"].as_str().ok_or_else(|| untrusted("no kid"))?;
        let key = self
            .keys
            .iter()
            .find(|k| k.kids.iter().any(|x| x == kid))
            .ok_or_else(|| untrusted("unknown attestation key"))?;
        let sig = URL_SAFE_NO_PAD
            .decode(s64)
            .ok()
            .and_then(|b| Signature::from_slice(&b).ok())
            .ok_or_else(|| untrusted("bad signature encoding"))?;
        let signing_input = &jws[..h64.len() + 1 + p64.len()];
        key.key
            .verify_strict(signing_input.as_bytes(), &sig)
            .map_err(|_| untrusted("signature does not verify"))?;

        let claims: serde_json::Value = decode_json(p64).ok_or_else(|| untrusted("bad claims"))?;
        if claims["aud"] != "risime-mls" || claims["v"] != 1 || !claims["iat"].is_number() {
            return Err(untrusted("wrong aud/v/iat"));
        }
        if claims["user_id"] != device.user_id.as_str()
            || claims["device_id"] != device.device_id.as_str()
        {
            return Err(untrusted("attested device differs from the credential"));
        }
        let attested_key = claims["signature_key"]
            .as_str()
            .and_then(|s| {
                STANDARD
                    .decode(s)
                    .or_else(|_| STANDARD_NO_PAD.decode(s))
                    .ok()
            })
            .ok_or_else(|| untrusted("bad signature_key claim"))?;
        if attested_key != signature_key {
            return Err(untrusted(
                "attested key differs from the leaf signature key",
            ));
        }
        Ok(())
    }
}

impl CredentialValidator for TrustAnchors {
    fn validate(
        &self,
        device: &DeviceId,
        signature_key: &[u8],
        attestation: Option<&[u8]>,
    ) -> Result<()> {
        let jws = attestation
            .ok_or_else(|| MlsError::UntrustedCredential(format!("{device}: no attestation")))?;
        let jws = std::str::from_utf8(jws).map_err(|_| {
            MlsError::UntrustedCredential(format!("{device}: attestation is not UTF-8"))
        })?;
        self.verify(jws, device, signature_key)
    }
}

fn decode_json(b64: &str) -> Option<serde_json::Value> {
    let bytes = URL_SAFE_NO_PAD.decode(b64).ok()?;
    serde_json::from_slice(&bytes).ok()
}

fn verifying_key(x: &[u8]) -> Result<VerifyingKey> {
    let bytes: [u8; 32] = x
        .try_into()
        .map_err(|_| MlsError::Malformed("Ed25519 key must be 32 bytes".into()))?;
    VerifyingKey::from_bytes(&bytes).map_err(|e| MlsError::Malformed(format!("ed25519: {e}")))
}

/// RFC 7638 JWK thumbprint of an Ed25519 public key (what the server uses as `kid`).
pub fn thumbprint(key: &VerifyingKey) -> String {
    let x = URL_SAFE_NO_PAD.encode(key.as_bytes());
    let canonical = format!(r#"{{"crv":"Ed25519","kty":"OKP","x":"{x}"}}"#);
    URL_SAFE_NO_PAD.encode(Sha256::digest(canonical.as_bytes()))
}

/// An attestation signer, exactly like the server's (`RisiMe.MLS.Attestation`). For tests and
/// contract fixtures only; production attestations come from the server.
pub struct TestAttestor {
    key: SigningKey,
}

impl TestAttestor {
    /// Deterministic key from a 32-byte seed.
    pub fn from_seed(seed: [u8; 32]) -> Self {
        Self {
            key: SigningKey::from_bytes(&seed),
        }
    }

    pub fn kid(&self) -> String {
        thumbprint(&self.key.verifying_key())
    }

    /// Public JWK, as `GET /api/v1/mls/attestation_keys` serves it.
    pub fn public_jwk(&self) -> String {
        format!(
            r#"{{"kty":"OKP","crv":"Ed25519","x":"{}","kid":"{}","use":"sig","alg":"EdDSA"}}"#,
            URL_SAFE_NO_PAD.encode(self.key.verifying_key().as_bytes()),
            self.kid()
        )
    }

    pub fn anchors(&self) -> TrustAnchors {
        TrustAnchors::from_jwks(&[self.public_jwk()]).expect("own jwk")
    }

    /// Sign a device binding (`signature_key` = raw 32-byte Ed25519 public key).
    pub fn attest(&self, device: &DeviceId, signature_key: &[u8], iat: u64) -> String {
        let header =
            serde_json::json!({"alg": "EdDSA", "kid": self.kid(), "typ": "risime-attest+jwt"});
        let claims = serde_json::json!({
            "aud": "risime-mls",
            "user_id": device.user_id,
            "device_id": device.device_id,
            "signature_key": STANDARD.encode(signature_key),
            "iat": iat,
            "v": 1,
        });
        let input = format!(
            "{}.{}",
            URL_SAFE_NO_PAD.encode(header.to_string()),
            URL_SAFE_NO_PAD.encode(claims.to_string())
        );
        let sig = self.key.sign(input.as_bytes());
        format!("{input}.{}", URL_SAFE_NO_PAD.encode(sig.to_bytes()))
    }
}
