//! Backup key material (§22.2): the recovery key format, the passphrase input and floor, the
//! Argon2id KEK, the wrap of `BK`, the per-backup `DEK` wrap and the stream key.
//!
//! Every function that takes a key or a nonce is crate-private: production callers get their
//! randomness from [`random`] inside the core; tests pass seeded values to build the vectors.

use aes_gcm::aead::{Aead, Payload};
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use argon2::{Algorithm, Argon2, Params, Version};
use hkdf::Hkdf;
use sha2::{Digest, Sha256};
use unicode_normalization::UnicodeNormalization;
use zeroize::Zeroizing;

use super::{BackupError, BackupResult};

/// Every label starts with this (crypto C2); also the wrap AAD and chunk AAD prefix.
pub const BACKUP_LABEL: &[u8] = b"risime-backup-v1";
const BK_ID_LABEL: &[u8] = b"risime-backup-v1 bk-id";
const RECOVERY_LABEL: &[u8] = b"risime-recovery-v1";
const INFO_WRAP: &[u8] = b"risime-backup-v1 wrap";
const INFO_CHECK: &[u8] = b"risime-backup-v1 check";
const INFO_DEK_WRAP: &[u8] = b"risime-backup-v1 dek-wrap";
const DEK_AAD_LABEL: &[u8] = b"risime-backup-v1 dek";
const INFO_STREAM: &[u8] = b"risime-backup-v1 stream";

/// `BK`, `KEK`, `DEK` length.
pub const KEY_LEN: usize = 32;
/// `bk_id` length.
pub const BK_ID_LEN: usize = 8;
/// `R` length (120 bits).
pub const RECOVERY_LEN: usize = 15;
/// Argon2id salt length.
pub const SALT_LEN: usize = 16;
/// AES-GCM nonce length.
pub const NONCE_LEN: usize = 12;
/// A wrapped 32-byte key plus its tag.
pub const WRAPPED_LEN: usize = 48;
/// The key check length.
pub const CHECK_LEN: usize = 16;
/// Fixed v1 Argon2id parameters (crypto C1): memory in KiB, iterations, lanes.
pub const ARGON2_M: u32 = 65_536;
pub const ARGON2_T: u32 = 3;
pub const ARGON2_P: u32 = 1;
pub const ARGON2_ALG: &str = "argon2id";
/// The recovery key alphabet (Crockford base32).
pub const CROCKFORD: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";
/// Display length without separators: 24 characters of `R` plus 4 of checksum.
pub const RECOVERY_CHARS: usize = 28;
/// Passphrase floor: code points, or words of at least [`FLOOR_WORD_CHARS`].
pub const FLOOR_CHARS: usize = 14;
pub const FLOOR_WORDS: usize = 4;
pub const FLOOR_WORD_CHARS: usize = 3;
/// Phone-number digit runs of this length may not appear in a passphrase.
pub const FLOOR_PHONE_RUN: usize = 6;

/// Constant-time comparison (no extra dependency).
fn ct_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut d = 0u8;
    for (x, y) in a.iter().zip(b) {
        d |= x ^ y;
    }
    std::hint::black_box(d) == 0
}

/// OS randomness.
pub(crate) fn random<const N: usize>() -> BackupResult<Zeroizing<[u8; N]>> {
    let mut b = Zeroizing::new([0u8; N]);
    getrandom::getrandom(&mut b[..]).map_err(|e| BackupError::Io(format!("getrandom: {e}")))?;
    Ok(b)
}

pub(crate) fn sha256(parts: &[&[u8]]) -> [u8; 32] {
    let mut h = Sha256::new();
    for p in parts {
        h.update(p);
    }
    h.finalize().into()
}

/// `bk_id` = the first 8 bytes of `SHA-256("risime-backup-v1 bk-id" ‖ BK)`.
pub fn bk_id(bk: &[u8; KEY_LEN]) -> [u8; BK_ID_LEN] {
    sha256(&[BK_ID_LABEL, bk])[..BK_ID_LEN]
        .try_into()
        .expect("8 bytes")
}

// ------------------------------------------------------------------------------- recovery key

/// The 20-bit checksum: the first 20 bits of `SHA-256("risime-recovery-v1" ‖ R)`.
fn recovery_checksum(r: &[u8; RECOVERY_LEN]) -> u32 {
    let h = sha256(&[RECOVERY_LABEL, r]);
    (u32::from(h[0]) << 12) | (u32::from(h[1]) << 4) | (u32::from(h[2]) >> 4)
}

/// The 28 symbols (5-bit values) of `R ‖ checksum`, most significant bit first.
fn recovery_symbols(r: &[u8; RECOVERY_LEN]) -> [u8; RECOVERY_CHARS] {
    let mut out = [0u8; RECOVERY_CHARS];
    let mut acc: u64 = 0;
    let mut bits = 0;
    let mut k = 0;
    for &b in r {
        acc = (acc << 8) | u64::from(b);
        bits += 8;
        while bits >= 5 {
            bits -= 5;
            out[k] = ((acc >> bits) & 31) as u8;
            k += 1;
        }
    }
    debug_assert_eq!((k, bits), (24, 0));
    let c = recovery_checksum(r);
    for i in 0..4 {
        out[24 + i] = ((c >> (15 - 5 * i)) & 31) as u8;
    }
    out
}

/// The display form: 7 groups of 4 Crockford characters joined by `-`.
pub fn recovery_display(r: &[u8; RECOVERY_LEN]) -> String {
    let sym = recovery_symbols(r);
    let mut s = String::with_capacity(34);
    for (i, v) in sym.iter().enumerate() {
        if i > 0 && i % 4 == 0 {
            s.push('-');
        }
        s.push(CROCKFORD[*v as usize] as char);
    }
    s
}

/// Parses a typed recovery key: case-insensitive; white space and `-` ignored; `O`→`0`,
/// `I`/`L`→`1`. A length other than 28 or a character outside the alphabet is `Malformed`; a
/// checksum mismatch is `Typo` (reported before any key derivation).
pub fn parse_recovery_key(input: &str) -> BackupResult<Zeroizing<[u8; RECOVERY_LEN]>> {
    let mut sym = Zeroizing::new(Vec::with_capacity(RECOVERY_CHARS));
    for c in input.chars() {
        if c == '-' || c.is_whitespace() {
            continue;
        }
        let u = match c.to_ascii_uppercase() {
            'O' => '0',
            'I' | 'L' => '1',
            u => u,
        };
        let v = u
            .is_ascii()
            .then(|| CROCKFORD.iter().position(|&a| a == u as u8))
            .flatten()
            .ok_or_else(|| {
                BackupError::Malformed("the recovery key has a character outside 0-9 A-Z".into())
            })?;
        sym.push(v as u8);
    }
    if sym.len() != RECOVERY_CHARS {
        return Err(BackupError::Malformed(format!(
            "a recovery key has {RECOVERY_CHARS} characters, not {}",
            sym.len()
        )));
    }
    let mut r = Zeroizing::new([0u8; RECOVERY_LEN]);
    let mut acc: u64 = 0;
    let mut bits = 0;
    let mut k = 0;
    for &v in &sym[..24] {
        acc = (acc << 5) | u64::from(v);
        bits += 5;
        while bits >= 8 {
            bits -= 8;
            r[k] = ((acc >> bits) & 0xff) as u8;
            k += 1;
        }
    }
    let have = sym[24..].iter().fold(0u32, |a, &v| (a << 5) | u32::from(v));
    if have != recovery_checksum(&r) {
        return Err(BackupError::Typo);
    }
    Ok(r)
}

// ------------------------------------------------------------------------------- passphrase

/// The passphrase bytes the KDF sees: NFKC, then leading and trailing white space removed
/// (Unicode `White_Space`). An empty result is `Malformed`.
pub fn passphrase_bytes(p: &str) -> BackupResult<Zeroizing<Vec<u8>>> {
    let n = Zeroizing::new(p.nfkc().collect::<String>());
    let t = n.trim();
    if t.is_empty() {
        return Err(BackupError::Malformed("empty passphrase".into()));
    }
    Ok(Zeroizing::new(t.as_bytes().to_vec()))
}

/// The client-side passphrase floor checks that the core can make (§22.2). The app adds the
/// bundled common-password list.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PassphraseFloor {
    Ok,
    /// Fewer than 14 code points and fewer than 4 words of at least 3 code points.
    TooShort,
    /// Contains a run of 6 or more digits of the user's phone number.
    PhoneNumber,
}

/// Checks the length/words rule and, with `phone` (any format; its digits are used), the
/// phone-number rule, on the normalised passphrase.
pub fn passphrase_floor(passphrase: &str, phone: Option<&str>) -> PassphraseFloor {
    let n = Zeroizing::new(passphrase.nfkc().collect::<String>());
    let t = n.trim();
    let words = t
        .split(char::is_whitespace)
        .filter(|w| w.chars().count() >= FLOOR_WORD_CHARS)
        .count();
    if t.chars().count() < FLOOR_CHARS && words < FLOOR_WORDS {
        return PassphraseFloor::TooShort;
    }
    if let Some(phone) = phone {
        let digits: Vec<u8> = phone.bytes().filter(u8::is_ascii_digit).collect();
        if digits.len() >= FLOOR_PHONE_RUN
            && digits
                .windows(FLOOR_PHONE_RUN)
                .any(|w| t.as_bytes().windows(FLOOR_PHONE_RUN).any(|x| x == w))
        {
            return PassphraseFloor::PhoneNumber;
        }
    }
    PassphraseFloor::Ok
}

// ------------------------------------------------------------------------------- KDF and wraps

/// The secret kind of a wrap.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SecretKind {
    RecoveryKey,
    Passphrase,
}

impl SecretKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::RecoveryKey => "recovery_key",
            Self::Passphrase => "passphrase",
        }
    }

    pub fn parse(s: &str) -> BackupResult<Self> {
        match s {
            "recovery_key" => Ok(Self::RecoveryKey),
            "passphrase" => Ok(Self::Passphrase),
            _ => Err(BackupError::Malformed(format!("unknown wrap kind {s:?}"))),
        }
    }
}

/// v1 accepts exactly `argon2id`, m = 65536, t = 3, p = 1 (crypto C1).
pub(crate) fn check_kdf_params(alg: &str, m: u64, t: u64, p: u64) -> BackupResult<()> {
    if alg != ARGON2_ALG
        || m != u64::from(ARGON2_M)
        || t != u64::from(ARGON2_T)
        || p != u64::from(ARGON2_P)
    {
        return Err(BackupError::Malformed(format!(
            "kdf {alg} m={m} t={t} p={p} (v1 accepts only argon2id m=65536 t=3 p=1)"
        )));
    }
    Ok(())
}

/// `KEK = Argon2id(password, salt, m = 65536 KiB, t = 3, p = 1, v 0x13, L = 32)`. About 64 MiB
/// and up to a second on a phone: never on the main thread.
pub(crate) fn derive_kek(
    password: &[u8],
    salt: &[u8; SALT_LEN],
) -> BackupResult<Zeroizing<[u8; KEY_LEN]>> {
    let params = Params::new(ARGON2_M, ARGON2_T, ARGON2_P, Some(KEY_LEN))
        .map_err(|e| BackupError::Io(format!("argon2 params: {e}")))?;
    let a = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
    let mut out = Zeroizing::new([0u8; KEY_LEN]);
    a.hash_password_into(password, salt, &mut out[..])
        .map_err(|e| BackupError::Io(format!("argon2: {e}")))?;
    Ok(out)
}

fn hkdf<const N: usize>(ikm: &[u8], info: &[u8]) -> Zeroizing<[u8; N]> {
    let mut out = Zeroizing::new([0u8; N]);
    Hkdf::<Sha256>::new(Some(&[]), ikm)
        .expand(info, &mut out[..])
        .expect("N <= 255 * 32");
    out
}

/// `Kw = HKDF-SHA256(salt = empty, KEK, "risime-backup-v1 wrap", 32)`.
pub(crate) fn wrap_key(kek: &[u8; KEY_LEN]) -> Zeroizing<[u8; KEY_LEN]> {
    hkdf::<KEY_LEN>(kek, INFO_WRAP)
}

/// `check = HKDF-SHA256(salt = empty, KEK, "risime-backup-v1 check", 16)`.
pub(crate) fn key_check(kek: &[u8; KEY_LEN]) -> [u8; CHECK_LEN] {
    *hkdf::<CHECK_LEN>(kek, INFO_CHECK)
}

fn put_u16_str(out: &mut Vec<u8>, s: &[u8]) -> BackupResult<()> {
    let n = u16::try_from(s.len()).map_err(|_| BackupError::Malformed("field too long".into()))?;
    out.extend_from_slice(&n.to_be_bytes());
    out.extend_from_slice(s);
    Ok(())
}

/// `AAD_wrap = "risime-backup-v1" ‖ u16 len ‖ user_id ‖ u16 len ‖ kind ‖ bk_id`.
pub(crate) fn wrap_aad(user_id: &str, kind: &str, bk_id: &[u8]) -> BackupResult<Vec<u8>> {
    let mut a = BACKUP_LABEL.to_vec();
    put_u16_str(&mut a, user_id.as_bytes())?;
    put_u16_str(&mut a, kind.as_bytes())?;
    a.extend_from_slice(bk_id);
    Ok(a)
}

pub(crate) fn aes(key: &[u8; KEY_LEN]) -> Aes256Gcm {
    Aes256Gcm::new_from_slice(key).expect("32-byte key")
}

/// Wraps `BK` under `KEK` (crate-private: the nonce is chosen by the caller, random in
/// production). Returns `(wrapped, check)`.
pub(crate) fn seal_wrap(
    kek: &[u8; KEY_LEN],
    user_id: &str,
    kind: &str,
    bk: &[u8; KEY_LEN],
    nonce: &[u8; NONCE_LEN],
) -> BackupResult<([u8; WRAPPED_LEN], [u8; CHECK_LEN])> {
    let kw = wrap_key(kek);
    let aad = wrap_aad(user_id, kind, &bk_id(bk))?;
    let ct = aes(&kw)
        .encrypt(Nonce::from_slice(nonce), Payload { msg: bk, aad: &aad })
        .map_err(|_| BackupError::Io("aead encrypt".into()))?;
    Ok((ct.try_into().expect("48 bytes"), key_check(kek)))
}

/// Unwrap: the key check first, in constant time (`WrongKey`), then the AEAD open
/// (`Integrity`), then `bk_id` against the opened `BK` (`Integrity`).
pub(crate) fn open_wrap(
    kek: &[u8; KEY_LEN],
    user_id: &str,
    kind: &str,
    bk_id_expected: &[u8],
    nonce: &[u8],
    wrapped: &[u8],
    check: &[u8],
) -> BackupResult<Zeroizing<[u8; KEY_LEN]>> {
    if !ct_eq(&key_check(kek), check) {
        return Err(BackupError::WrongKey);
    }
    if nonce.len() != NONCE_LEN || wrapped.len() != WRAPPED_LEN {
        return Err(BackupError::Malformed("wrap nonce or length".into()));
    }
    let kw = wrap_key(kek);
    let aad = wrap_aad(user_id, kind, bk_id_expected)?;
    let pt = Zeroizing::new(
        aes(&kw)
            .decrypt(
                Nonce::from_slice(nonce),
                Payload {
                    msg: wrapped,
                    aad: &aad,
                },
            )
            .map_err(|_| BackupError::Integrity("the key wrap failed to open".into()))?,
    );
    let bk: Zeroizing<[u8; KEY_LEN]> = Zeroizing::new(pt[..].try_into().expect("32 bytes"));
    if bk_id(&bk)[..] != *bk_id_expected {
        return Err(BackupError::Integrity(
            "bk_id doesn't match the unwrapped key".into(),
        ));
    }
    Ok(bk)
}

/// `AAD_dek = "risime-backup-v1 dek" ‖ backup_id (16 raw bytes) ‖ u16 len ‖ user_id`.
pub(crate) fn dek_aad(backup_id: &[u8; 16], user_id: &str) -> BackupResult<Vec<u8>> {
    let mut a = DEK_AAD_LABEL.to_vec();
    a.extend_from_slice(backup_id);
    put_u16_str(&mut a, user_id.as_bytes())?;
    Ok(a)
}

/// `dek.wrapped = AES-256-GCM(Kd, dek.nonce, AAD_dek, DEK)`, `Kd` = HKDF(`BK`, "dek-wrap").
pub(crate) fn seal_dek(
    bk: &[u8; KEY_LEN],
    backup_id: &[u8; 16],
    user_id: &str,
    dek: &[u8; KEY_LEN],
    nonce: &[u8; NONCE_LEN],
) -> BackupResult<[u8; WRAPPED_LEN]> {
    let kd = hkdf::<KEY_LEN>(bk, INFO_DEK_WRAP);
    let aad = dek_aad(backup_id, user_id)?;
    let ct = aes(&kd)
        .encrypt(
            Nonce::from_slice(nonce),
            Payload {
                msg: dek,
                aad: &aad,
            },
        )
        .map_err(|_| BackupError::Io("aead encrypt".into()))?;
    Ok(ct.try_into().expect("48 bytes"))
}

pub(crate) fn open_dek(
    bk: &[u8; KEY_LEN],
    backup_id: &[u8; 16],
    user_id: &str,
    nonce: &[u8],
    wrapped: &[u8],
) -> BackupResult<Zeroizing<[u8; KEY_LEN]>> {
    if nonce.len() != NONCE_LEN || wrapped.len() != WRAPPED_LEN {
        return Err(BackupError::Format("dek nonce or length".into()));
    }
    let kd = hkdf::<KEY_LEN>(bk, INFO_DEK_WRAP);
    let aad = dek_aad(backup_id, user_id)?;
    let pt = Zeroizing::new(
        aes(&kd)
            .decrypt(
                Nonce::from_slice(nonce),
                Payload {
                    msg: wrapped,
                    aad: &aad,
                },
            )
            .map_err(|_| BackupError::Integrity("the DEK wrap failed to open".into()))?,
    );
    Ok(Zeroizing::new(pt[..].try_into().expect("32 bytes")))
}

/// `Ks = HKDF-SHA256(salt = empty, DEK, "risime-backup-v1 stream", 32)`.
pub(crate) fn stream_key(dek: &[u8; KEY_LEN]) -> Zeroizing<[u8; KEY_LEN]> {
    hkdf::<KEY_LEN>(dek, INFO_STREAM)
}
