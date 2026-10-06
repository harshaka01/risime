//! Media encryption (contract v1.11, decision 042): the `A256GCM-S64K` blob format.
//!
//! Every image (and group icon) is encrypted under a **fresh random 32-byte content key** `K`,
//! generated inside [`encrypt_file`]. No public API takes a caller-supplied key for encryption,
//! so a key can never be reused. The key, `plain_size` and the ciphertext's size and SHA-256
//! travel inside the MLS-encrypted envelope; the server only stores the opaque blob.
//!
//! Format (crypto review R1/R2 of the v1.11 proposal):
//! - payload key `Kp = HKDF-SHA256(salt = empty, IKM = K, info = "risime-media-v1 A256GCM-S64K")`;
//! - the plaintext `P` (`1 ≤ |P|`) is zero-padded to [`padme`]`(|P|)` bytes (`P'`);
//! - `P'` is split into 65 536-byte segments (the last holds 1..=65 536 bytes; no empty trailing
//!   segment);
//! - segment `i` is AES-256-GCM under `Kp` with nonce `0x00×7 ‖ BE32(i) ‖ final` (`final` = 1 only
//!   on the last segment) and AAD `risime-media-v1`;
//! - blob = `C_0 ‖ … ‖ C_{n-1}`, each `C_i` = ciphertext ‖ 16-byte tag, no header.
//!
//! Every call streams file to file with one 64 KiB buffer, so memory stays constant whatever the
//! size ([`decrypt_file`] alone holds the plaintext it returns). Decryption releases nothing
//! until the size, every segment tag, the final flag, the padding **and** the ciphertext SHA-256
//! have all verified. The SHA-256 is a corruption check that matches the envelope; the AEAD is the
//! security boundary, and neither replaces the other.

use std::fs::{self, File};
use std::io::{ErrorKind, Read, Write};
use std::path::{Path, PathBuf};

use aes_gcm::aead::AeadInPlace;
use aes_gcm::{Aes256Gcm, KeyInit, Nonce, Tag};
use hkdf::Hkdf;
use sha2::{Digest, Sha256};
use zeroize::{Zeroize, Zeroizing};

/// The only `enc.alg` value. Receivers accept exactly this string.
pub const MEDIA_ALG: &str = "A256GCM-S64K";
/// AAD of every segment.
pub const MEDIA_AAD: &[u8] = b"risime-media-v1";
/// HKDF `info` for the payload key.
pub const MEDIA_HKDF_INFO: &[u8] = b"risime-media-v1 A256GCM-S64K";
/// Plaintext bytes per segment.
pub const MEDIA_SEGMENT: u64 = 65_536;
/// GCM tag bytes per segment.
pub const MEDIA_TAG: u64 = 16;
/// Ciphertext bytes per full segment.
pub const MEDIA_CIPHER_SEGMENT: u64 = MEDIA_SEGMENT + MEDIA_TAG;
/// Content key length.
pub const MEDIA_KEY_LEN: usize = 32;
/// The §12.6 `media` cap, on the **ciphertext** size (16 MiB).
pub const MAX_MEDIA_CIPHER_SIZE: u64 = 16 * 1024 * 1024;
/// The `icon` cap, on the ciphertext size (512 KiB). Enforced by the caller for icons.
pub const MAX_ICON_CIPHER_SIZE: u64 = 512 * 1024;
/// The largest plaintext whose ciphertext fits [`MAX_MEDIA_CIPHER_SIZE`].
pub const MAX_MEDIA_PLAIN_SIZE: u64 = 16_515_072;

/// Media failures. Every one of them is "Couldn't open this photo" in the UI.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum MediaError {
    /// The blob doesn't match the envelope: wrong size or SHA-256, a failed segment tag, a
    /// missing or misplaced final segment, or the wrong key.
    #[error("media integrity check failed: {0}")]
    Integrity(String),
    /// The envelope or blob is malformed: key or digest length, `plain_size` inconsistent with
    /// `cipher_size`, a last segment shorter than 17 bytes, nonzero padding, an empty input.
    #[error("malformed media: {0}")]
    Format(String),
    /// `alg` is not [`MEDIA_ALG`].
    #[error("unsupported media alg: {0}")]
    Unsupported(String),
    /// The input is over the cap ([`MAX_MEDIA_PLAIN_SIZE`] / [`MAX_MEDIA_CIPHER_SIZE`]).
    #[error("media too large: {0}")]
    TooLarge(String),
    /// Reading or writing a file failed.
    #[error("media io: {0}")]
    Io(String),
}

pub type MediaResult<T> = std::result::Result<T, MediaError>;

fn io(e: std::io::Error) -> MediaError {
    MediaError::Io(e.to_string())
}

/// The result of [`encrypt_file`]: what goes into the envelope's `enc` and `blob`.
/// The key is wiped when this value is dropped.
#[derive(Clone, PartialEq, Eq)]
pub struct SealedMedia {
    /// `enc.key`: the fresh 32-byte content key.
    pub key: Zeroizing<Vec<u8>>,
    /// `enc.alg`: always [`MEDIA_ALG`].
    pub alg: String,
    /// `enc.plain_size`.
    pub plain_size: u64,
    /// `blob.size`: the ciphertext size.
    pub cipher_size: u64,
    /// `blob.sha256`: SHA-256 of the ciphertext file.
    pub sha256: [u8; 32],
}

impl std::fmt::Debug for SealedMedia {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SealedMedia")
            .field("key", &"<redacted>")
            .field("alg", &self.alg)
            .field("plain_size", &self.plain_size)
            .field("cipher_size", &self.cipher_size)
            .finish_non_exhaustive()
    }
}

/// The envelope fields a receiver passes to decrypt a blob.
#[derive(Debug, Clone, Copy)]
pub struct MediaRef<'a> {
    pub key: &'a [u8],
    pub alg: &'a str,
    pub plain_size: u64,
    pub cipher_size: u64,
    pub sha256: &'a [u8],
}

/// Padmé (Nikitin et al., PURBs, PETS 2019): the padded length of `l` bytes.
pub fn padme(l: u64) -> u64 {
    if l < 2 {
        return l;
    }
    let e = 63 - u64::from(l.leading_zeros()); // floor(log2 l), >= 1
    let s = (63 - u64::from(e.leading_zeros())) + 1; // floor(log2 e) + 1
    let mask = (1u64 << (e - s)) - 1;
    (l + mask) & !mask
}

/// Number of segments for a padded plaintext of `padded` bytes (`padded ≥ 1`).
fn segment_count(padded: u64) -> u64 {
    padded.div_ceil(MEDIA_SEGMENT)
}

/// The ciphertext size of a `plain_size`-byte plaintext: `Padmé(L) + 16·n`. `None` for 0.
pub fn cipher_size_for(plain_size: u64) -> Option<u64> {
    if plain_size == 0 || plain_size > u64::MAX / 2 {
        return None;
    }
    let padded = padme(plain_size);
    Some(padded + MEDIA_TAG * segment_count(padded))
}

/// Parses a ciphertext size into the segment count, checking the last segment is ≥ 17 bytes.
fn parse_cipher_size(cipher_size: u64) -> MediaResult<u64> {
    if cipher_size == 0 {
        return Err(MediaError::Format("empty blob".into()));
    }
    let n = cipher_size.div_ceil(MEDIA_CIPHER_SEGMENT);
    let last = cipher_size - (n - 1) * MEDIA_CIPHER_SEGMENT;
    if last <= MEDIA_TAG {
        return Err(MediaError::Format(format!("last segment is {last} bytes")));
    }
    if n > u64::from(u32::MAX) {
        return Err(MediaError::TooLarge("more than 2^32 segments".into()));
    }
    Ok(n)
}

fn nonce(i: u64, last: bool) -> Nonce<aes_gcm::aead::consts::U12> {
    let mut n = [0u8; 12];
    n[7..11].copy_from_slice(&(i as u32).to_be_bytes());
    n[11] = u8::from(last);
    Nonce::from(n)
}

/// `Kp` from `K`. The returned cipher wipes its key schedule on drop.
fn payload_cipher(key: &[u8; MEDIA_KEY_LEN]) -> Aes256Gcm {
    let mut kp = Zeroizing::new([0u8; 32]);
    payload_key_into(key, &mut kp);
    Aes256Gcm::new_from_slice(&kp[..]).expect("32-byte key")
}

fn payload_key_into(key: &[u8; MEDIA_KEY_LEN], out: &mut [u8; 32]) {
    Hkdf::<Sha256>::new(Some(&[]), key)
        .expand(MEDIA_HKDF_INFO, out)
        .expect("32 <= 255 * 32");
}

/// A sibling temp path for atomic writes: `<dst>.risime-tmp`.
fn temp_path(dst: &Path) -> PathBuf {
    let mut s = dst.as_os_str().to_owned();
    s.push(".risime-tmp");
    PathBuf::from(s)
}

/// Removes the temp file when dropped unless disarmed (on success it has been renamed).
struct TempGuard(Option<PathBuf>);

impl Drop for TempGuard {
    fn drop(&mut self) {
        if let Some(p) = self.0.take() {
            let _ = fs::remove_file(p);
        }
    }
}

fn finish_temp(file: File, mut guard: TempGuard, dst: &Path) -> MediaResult<()> {
    file.sync_all().map_err(io)?;
    drop(file);
    let tmp = guard.0.take().expect("armed");
    if let Err(e) = fs::rename(&tmp, dst) {
        let _ = fs::remove_file(&tmp);
        return Err(io(e));
    }
    Ok(())
}

/// Reads exactly `buf.len()` bytes, or reports an input that shrank while being read.
fn read_full(r: &mut impl Read, buf: &mut [u8]) -> MediaResult<()> {
    r.read_exact(buf).map_err(|e| match e.kind() {
        ErrorKind::UnexpectedEof => MediaError::Io("file changed while being read".into()),
        _ => io(e),
    })
}

fn at_eof(r: &mut impl Read) -> MediaResult<bool> {
    let mut b = [0u8; 1];
    loop {
        match r.read(&mut b) {
            Ok(0) => return Ok(true),
            Ok(_) => return Ok(false),
            Err(e) if e.kind() == ErrorKind::Interrupted => continue,
            Err(e) => return Err(io(e)),
        }
    }
}

/// Encrypts the file `src` into the blob file `dst` under a **fresh random key** from the OS
/// CSPRNG, and returns what the envelope needs. `dst` is written atomically (temp file + fsync +
/// rename) and replaced if it exists; on any error nothing is left at `dst`.
///
/// The ciphertext is the unit of retry: re-upload the same `dst`. If it is lost, call this again,
/// which yields a new key (never re-encrypt under a stored key).
///
/// Fails with [`MediaError::TooLarge`] above [`MAX_MEDIA_PLAIN_SIZE`] bytes (before writing
/// anything), [`MediaError::Format`] for an empty file. Icons must also check
/// `cipher_size ≤` [`MAX_ICON_CIPHER_SIZE`].
pub fn encrypt_file(src: &Path, dst: &Path) -> MediaResult<SealedMedia> {
    let mut key = Zeroizing::new([0u8; MEDIA_KEY_LEN]);
    getrandom::getrandom(&mut key[..]).map_err(|e| MediaError::Io(format!("getrandom: {e}")))?;
    seal_file_with_key(src, dst, &key, MAX_MEDIA_PLAIN_SIZE)
}

/// The keyed core of [`encrypt_file`]. Private: only tests (vectors) choose the key.
fn seal_file_with_key(
    src: &Path,
    dst: &Path,
    key: &[u8; MEDIA_KEY_LEN],
    max_plain: u64,
) -> MediaResult<SealedMedia> {
    let mut input = File::open(src).map_err(io)?;
    let plain_size = input.metadata().map_err(io)?.len();
    if plain_size == 0 {
        return Err(MediaError::Format("empty input".into()));
    }
    if plain_size > max_plain {
        return Err(MediaError::TooLarge(format!(
            "{plain_size} bytes (max {max_plain})"
        )));
    }
    let padded = padme(plain_size);
    let n = segment_count(padded);
    let cipher = payload_cipher(key);

    let tmp = temp_path(dst);
    let mut out = File::create(&tmp).map_err(io)?;
    let guard = TempGuard(Some(tmp));
    let mut sha = Sha256::new();
    let mut buf = Zeroizing::new(vec![0u8; MEDIA_CIPHER_SEGMENT as usize]);
    let mut cipher_size = 0u64;

    for i in 0..n {
        let off = i * MEDIA_SEGMENT;
        let seg_len = (padded - off).min(MEDIA_SEGMENT) as usize;
        let data_len = plain_size.saturating_sub(off).min(seg_len as u64) as usize;
        read_full(&mut input, &mut buf[..data_len])?;
        buf[data_len..seg_len].fill(0);
        let tag = cipher
            .encrypt_in_place_detached(&nonce(i, i == n - 1), MEDIA_AAD, &mut buf[..seg_len])
            .map_err(|_| MediaError::Io("aead encrypt".into()))?;
        buf[seg_len..seg_len + MEDIA_TAG as usize].copy_from_slice(&tag);
        let c = &buf[..seg_len + MEDIA_TAG as usize];
        sha.update(c);
        out.write_all(c).map_err(io)?;
        cipher_size += c.len() as u64;
    }
    if !at_eof(&mut input)? {
        return Err(MediaError::Io("file changed while being read".into()));
    }
    finish_temp(out, guard, dst)?;
    debug_assert_eq!(Some(cipher_size), cipher_size_for(plain_size));
    Ok(SealedMedia {
        key: Zeroizing::new(key.to_vec()),
        alg: MEDIA_ALG.to_string(),
        plain_size,
        cipher_size,
        sha256: sha.finalize().into(),
    })
}

/// Checks the envelope fields (crypto review R4) and returns the key and the segment count.
pub fn check_ref(r: &MediaRef<'_>) -> MediaResult<(Zeroizing<[u8; MEDIA_KEY_LEN]>, u64)> {
    if r.alg != MEDIA_ALG {
        return Err(MediaError::Unsupported(r.alg.chars().take(64).collect()));
    }
    let key: [u8; MEDIA_KEY_LEN] = r
        .key
        .try_into()
        .map_err(|_| MediaError::Format(format!("key is {} bytes", r.key.len())))?;
    let key = Zeroizing::new(key);
    if r.sha256.len() != 32 {
        return Err(MediaError::Format(format!(
            "sha256 is {} bytes",
            r.sha256.len()
        )));
    }
    if r.cipher_size > MAX_MEDIA_CIPHER_SIZE {
        return Err(MediaError::TooLarge(format!(
            "blob is {} bytes (max {MAX_MEDIA_CIPHER_SIZE})",
            r.cipher_size
        )));
    }
    let n = parse_cipher_size(r.cipher_size)?;
    if cipher_size_for(r.plain_size) != Some(r.cipher_size) {
        return Err(MediaError::Format(format!(
            "plain_size {} doesn't fit a {}-byte blob",
            r.plain_size, r.cipher_size
        )));
    }
    Ok((key, n))
}

/// The streaming open: verifies size, every segment, the final flag, the padding and the SHA-256,
/// handing AEAD-verified plaintext to `sink` segment by segment. The caller must discard what the
/// sink received unless this returns `Ok`.
fn open_stream(
    src: &Path,
    r: &MediaRef<'_>,
    mut sink: impl FnMut(&[u8]) -> MediaResult<()>,
) -> MediaResult<()> {
    let (key, n) = check_ref(r)?;
    let mut input = File::open(src).map_err(io)?;
    let len = input.metadata().map_err(io)?.len();
    if len != r.cipher_size {
        return Err(MediaError::Integrity(format!(
            "blob file is {len} bytes, envelope says {}",
            r.cipher_size
        )));
    }
    let cipher = payload_cipher(&key);
    let mut sha = Sha256::new();
    let mut buf = Zeroizing::new(vec![0u8; MEDIA_CIPHER_SEGMENT as usize]);
    let mut pad_or = 0u8;

    for i in 0..n {
        let off = i * MEDIA_CIPHER_SEGMENT;
        let c_len = (r.cipher_size - off).min(MEDIA_CIPHER_SEGMENT) as usize;
        read_full(&mut input, &mut buf[..c_len])?;
        sha.update(&buf[..c_len]);
        let p_len = c_len - MEDIA_TAG as usize;
        let (body, tag) = buf[..c_len].split_at_mut(p_len);
        cipher
            .decrypt_in_place_detached(&nonce(i, i == n - 1), MEDIA_AAD, body, Tag::from_slice(tag))
            .map_err(|_| MediaError::Integrity(format!("segment {i} failed to verify")))?;
        let p_off = i * MEDIA_SEGMENT;
        let real = r.plain_size.saturating_sub(p_off).min(p_len as u64) as usize;
        pad_or |= body[real..].iter().fold(0, |a, b| a | b);
        if real > 0 {
            sink(&body[..real])?;
        }
    }
    if !at_eof(&mut input)? {
        return Err(MediaError::Integrity(
            "blob file grew while being read".into(),
        ));
    }
    let digest: [u8; 32] = sha.finalize().into();
    if digest[..] != *r.sha256 {
        return Err(MediaError::Integrity("ciphertext sha256 mismatch".into()));
    }
    if pad_or != 0 {
        return Err(MediaError::Format("nonzero padding".into()));
    }
    Ok(())
}

/// Decrypts the blob file `src` into memory (decrypt-on-display). Memory: the returned
/// plaintext plus one 64 KiB buffer. Nothing is returned unless every check passed.
pub fn decrypt_file(src: &Path, r: &MediaRef<'_>) -> MediaResult<Zeroizing<Vec<u8>>> {
    check_ref(r)?;
    let mut out = Zeroizing::new(Vec::with_capacity(r.plain_size as usize));
    open_stream(src, r, |p| {
        out.extend_from_slice(p);
        Ok(())
    })?;
    Ok(out)
}

/// Decrypts the blob file `src` into the file `dst` with constant memory. The plaintext goes to a
/// temp file next to `dst` that is renamed to `dst` only after every check passed, and deleted
/// otherwise.
pub fn decrypt_file_to_file(src: &Path, dst: &Path, r: &MediaRef<'_>) -> MediaResult<()> {
    check_ref(r)?;
    let tmp = temp_path(dst);
    let mut out = File::create(&tmp).map_err(io)?;
    let guard = TempGuard(Some(tmp));
    open_stream(src, r, |p| out.write_all(p).map_err(io))?;
    finish_temp(out, guard, dst)
}

/// For a `Range`-resumed download: how many leading bytes of the partial blob file `src` are
/// whole segments that verify under `key`, in order (the last segment with the final flag).
/// Truncate the `.part` file to this offset and resume from it; a mismatch is found within one
/// segment instead of after the whole blob. A complete, valid blob returns `cipher_size`. The
/// SHA-256 and the padding are still checked by the full decrypt.
pub fn verified_prefix(src: &Path, key: &[u8], alg: &str, cipher_size: u64) -> MediaResult<u64> {
    if alg != MEDIA_ALG {
        return Err(MediaError::Unsupported(alg.chars().take(64).collect()));
    }
    let key: Zeroizing<[u8; MEDIA_KEY_LEN]> = Zeroizing::new(
        key.try_into()
            .map_err(|_| MediaError::Format(format!("key is {} bytes", key.len())))?,
    );
    if cipher_size > MAX_MEDIA_CIPHER_SIZE {
        return Err(MediaError::TooLarge(format!("blob is {cipher_size} bytes")));
    }
    let n = parse_cipher_size(cipher_size)?;
    let mut input = match File::open(src) {
        Ok(f) => f,
        Err(e) if e.kind() == ErrorKind::NotFound => return Ok(0),
        Err(e) => return Err(io(e)),
    };
    let have = input.metadata().map_err(io)?.len().min(cipher_size);
    let cipher = payload_cipher(&key);
    let mut buf = Zeroizing::new(vec![0u8; MEDIA_CIPHER_SEGMENT as usize]);
    let mut ok = 0u64;
    for i in 0..n {
        let off = i * MEDIA_CIPHER_SEGMENT;
        let c_len = (cipher_size - off).min(MEDIA_CIPHER_SEGMENT);
        if off + c_len > have {
            break;
        }
        let c_len = c_len as usize;
        read_full(&mut input, &mut buf[..c_len])?;
        let (body, tag) = buf[..c_len].split_at_mut(c_len - MEDIA_TAG as usize);
        if cipher
            .decrypt_in_place_detached(&nonce(i, i == n - 1), MEDIA_AAD, body, Tag::from_slice(tag))
            .is_err()
        {
            break;
        }
        body.zeroize();
        ok = off + c_len as u64;
    }
    Ok(ok)
}

#[cfg(test)]
mod tests {
    //! Format edges, negatives with raw segments, and the vector generator
    //! (`cargo test -p risime-mls media_vectors -- --ignored` writes `tests/media_vectors.json`).
    use super::*;

    fn tmpdir(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!(
            "risime-media-unit-{tag}-{}-{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        fs::create_dir_all(&d).unwrap();
        d
    }

    /// Deterministic bytes: SHA-256 in counter mode over a label (the test-only seeded RNG).
    pub(super) fn seeded(label: &str, len: usize) -> Vec<u8> {
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

    fn seeded_key(label: &str) -> [u8; 32] {
        seeded(&format!("risime-media-vector key {label}"), 32)
            .try_into()
            .unwrap()
    }

    /// Encrypts raw padded segments with chosen final flags (for crafted negatives).
    fn raw_segments(key: &[u8; 32], segs: &[(&[u8], u32, bool)]) -> Vec<u8> {
        let c = payload_cipher(key);
        let mut out = Vec::new();
        for (p, i, fin) in segs {
            let mut b = p.to_vec();
            let tag = c
                .encrypt_in_place_detached(&nonce(u64::from(*i), *fin), MEDIA_AAD, &mut b)
                .unwrap();
            out.extend_from_slice(&b);
            out.extend_from_slice(&tag);
        }
        out
    }

    fn hex(b: &[u8]) -> String {
        b.iter().map(|x| format!("{x:02x}")).collect()
    }

    fn sha(b: &[u8]) -> [u8; 32] {
        Sha256::digest(b).into()
    }

    fn open_bytes(dir: &Path, cipher: &[u8], key: &[u8], plain_size: u64) -> MediaResult<Vec<u8>> {
        let p = dir.join("blob");
        fs::write(&p, cipher).unwrap();
        let d = sha(cipher);
        decrypt_file(
            &p,
            &MediaRef {
                key,
                alg: MEDIA_ALG,
                plain_size,
                cipher_size: cipher.len() as u64,
                sha256: &d,
            },
        )
        .map(|z| z.to_vec())
    }

    #[test]
    fn padme_matches_the_paper_and_the_review() {
        assert_eq!(padme(0), 0);
        assert_eq!(padme(1), 1);
        assert_eq!(padme(2), 2);
        assert_eq!(padme(3), 3);
        assert_eq!(padme(9), 10);
        assert_eq!(padme(100), 104);
        assert_eq!(padme(1_300_000), 1_310_720);
        assert_eq!(cipher_size_for(1_300_000), Some(1_311_040));
        assert_eq!(padme(65_000), 65_536);
        assert_eq!(padme(195_000), 196_608);
        assert_eq!(cipher_size_for(MAX_MEDIA_PLAIN_SIZE), Some(16_519_104));
        assert!(cipher_size_for(MAX_MEDIA_PLAIN_SIZE + 1).unwrap() > MAX_MEDIA_CIPHER_SIZE);
        assert_eq!(cipher_size_for(0), None);
        // Padmé is monotonic, never shrinks, and costs ≤ 12% (≤ ~6% above 1 MiB).
        let mut prev = 0;
        for l in (1..20_000_000u64).step_by(9_973) {
            let p = padme(l);
            assert!(p >= l && p >= prev);
            assert!((p - l) * 100 <= l * 12, "{l} -> {p}");
            if l > 1 << 20 {
                assert!((p - l) * 100 <= l * 7, "{l} -> {p}");
            }
            prev = p;
        }
    }

    #[test]
    fn nonce_layout() {
        let n = nonce(0x0102_0304, true);
        assert_eq!(&n[..], &[0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 1]);
        assert_eq!(&nonce(5, false)[..], &[0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5, 0]);
    }

    #[test]
    fn crafted_negatives() {
        let dir = tmpdir("crafted");
        let k = seeded_key("crafted");
        let s0 = seeded("crafted s0", 65_536);
        let s1 = vec![0x41u8; 2048];
        // plain 67000 bytes -> padme 67584 = 65536 + 2048.
        assert_eq!(padme(67_000), 67_584);
        let mut tail = s1.clone();
        tail[67_000 - 65_536..].fill(0);
        let good = raw_segments(&k, &[(&s0, 0, false), (&tail, 1, true)]);
        assert_eq!(open_bytes(&dir, &good, &k, 67_000).unwrap().len(), 67_000);

        // Final flag on a non-final segment.
        let bad = raw_segments(&k, &[(&s0, 0, true), (&tail, 1, true)]);
        assert!(matches!(
            open_bytes(&dir, &bad, &k, 67_000),
            Err(MediaError::Integrity(_))
        ));
        // Nonzero padding, correctly encrypted.
        let bad = raw_segments(&k, &[(&s0, 0, false), (&s1, 1, true)]);
        assert!(matches!(
            open_bytes(&dir, &bad, &k, 67_000),
            Err(MediaError::Format(_))
        ));
        // A last segment of exactly 16 bytes (a bare tag).
        let mut bad = raw_segments(&k, &[(&s0, 0, false)]);
        bad.extend_from_slice(&[0u8; 16]);
        assert!(matches!(
            parse_cipher_size(bad.len() as u64),
            Err(MediaError::Format(_))
        ));
        // Index skipped (segment 1 encrypted as index 2).
        let bad = raw_segments(&k, &[(&s0, 0, false), (&tail, 2, true)]);
        assert!(matches!(
            open_bytes(&dir, &bad, &k, 67_000),
            Err(MediaError::Integrity(_))
        ));
        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn keyed_seal_is_deterministic_and_opens() {
        let dir = tmpdir("keyed");
        let src = dir.join("p");
        fs::write(&src, seeded("keyed", 70_000)).unwrap();
        let k = seeded_key("keyed");
        let a = seal_file_with_key(&src, &dir.join("a"), &k, MAX_MEDIA_PLAIN_SIZE).unwrap();
        let b = seal_file_with_key(&src, &dir.join("b"), &k, MAX_MEDIA_PLAIN_SIZE).unwrap();
        assert_eq!(a, b);
        assert_eq!(
            fs::read(dir.join("a")).unwrap(),
            fs::read(dir.join("b")).unwrap()
        );
        fs::remove_dir_all(&dir).unwrap();
    }

    /// Bigger than the media cap, to show memory is independent of size (the cap is lifted
    /// through the private keyed path).
    #[test]
    fn streams_beyond_the_cap() {
        let dir = tmpdir("big");
        let src = dir.join("p");
        {
            let mut f = File::create(&src).unwrap();
            let chunk = seeded("big", 1 << 20);
            for _ in 0..20 {
                f.write_all(&chunk).unwrap();
            }
        }
        let k = seeded_key("big");
        let s = seal_file_with_key(&src, &dir.join("c"), &k, u64::MAX / 4).unwrap();
        assert_eq!(s.plain_size, 20 << 20);
        assert_eq!(fs::metadata(dir.join("c")).unwrap().len(), s.cipher_size);
        assert_eq!(
            verified_prefix(&dir.join("c"), &k[..], MEDIA_ALG, s.cipher_size),
            Err(MediaError::TooLarge(format!(
                "blob is {} bytes",
                s.cipher_size
            )))
        );
        fs::remove_dir_all(&dir).unwrap();
    }

    // -----------------------------------------------------------------------------------------
    // Vector generator
    // -----------------------------------------------------------------------------------------

    struct Pos {
        name: &'static str,
        key: [u8; 32],
        plain: Vec<u8>,
        cipher: Vec<u8>,
    }

    fn positive(dir: &Path, name: &'static str, len: usize) -> Pos {
        let plain = seeded(&format!("risime-media-vector plain {name}"), len);
        let key = seeded_key(name);
        let src = dir.join(format!("{name}.plain"));
        let dst = dir.join(format!("{name}.blob"));
        fs::write(&src, &plain).unwrap();
        seal_file_with_key(&src, &dst, &key, MAX_MEDIA_PLAIN_SIZE).unwrap();
        let cipher = fs::read(&dst).unwrap();
        Pos {
            name,
            key,
            plain,
            cipher,
        }
    }

    fn seg(c: &[u8], i: usize) -> &[u8] {
        let s = MEDIA_CIPHER_SEGMENT as usize;
        &c[i * s..((i + 1) * s).min(c.len())]
    }

    fn pos_json(p: &Pos) -> serde_json::Value {
        let mut kp = [0u8; 32];
        payload_key_into(&p.key, &mut kp);
        let padded = padme(p.plain.len() as u64);
        serde_json::json!({
            "name": p.name,
            "key": hex(&p.key),
            "payload_key": hex(&kp),
            "plain_size": p.plain.len(),
            "padded_size": padded,
            "segments": segment_count(padded),
            "cipher_size": p.cipher.len(),
            "sha256": hex(&sha(&p.cipher)),
            "plain": hex(&p.plain),
            "cipher": hex(&p.cipher),
        })
    }

    fn neg_json(
        name: &str,
        why: &str,
        key: &[u8],
        plain_size: u64,
        cipher: &[u8],
        expect: &str,
    ) -> serde_json::Value {
        serde_json::json!({
            "name": name,
            "why": why,
            "key": hex(key),
            "plain_size": plain_size,
            "cipher_size": cipher.len(),
            "sha256": hex(&sha(cipher)),
            "cipher": hex(cipher),
            "expect": expect,
        })
    }

    fn build_vectors() -> serde_json::Value {
        let dir = tmpdir("vectors");
        let pos = [
            positive(&dir, "len_1", 1),
            positive(&dir, "len_100", 100),
            positive(&dir, "one_full_segment", 65_000),
            positive(&dir, "two_segments", 70_000),
            positive(&dir, "three_full_segments", 195_000),
        ];
        let small = &pos[1];
        let two = &pos[3];
        let other = &pos[2];
        let mut neg = Vec::new();
        let two_size = two.plain.len() as u64;

        neg.push(neg_json(
            "truncated_last_segment",
            "two_segments with its last segment dropped (truncation at a boundary); plain_size \
             65536 makes the sizes consistent, so only the final flag catches it",
            &two.key,
            65_536,
            seg(&two.cipher, 0),
            "Integrity",
        ));
        let swapped = [seg(&two.cipher, 1), seg(&two.cipher, 0)].concat();
        neg.push(neg_json(
            "segments_swapped",
            "two_segments with segments 0 and 1 swapped",
            &two.key,
            two_size,
            &swapped,
            "Integrity",
        ));
        // Appended segment, envelope consistent with the longer blob (so it passes the size
        // checks and fails on the nonces/keys).
        let appended = [seg(&two.cipher, 0), seg(&other.cipher, 0)].concat();
        neg.push(neg_json(
            "segment_from_other_blob",
            "segment 0 of two_segments followed by segment 0 of one_full_segment (another key); \
             plain_size 131072 makes the sizes consistent",
            &two.key,
            131_072,
            &appended,
            "Integrity",
        ));
        let k = seeded_key("final_flag_on_non_final");
        let s0 = seeded("risime-media-vector plain final_flag_on_non_final", 65_536);
        let mut s1 = seeded("risime-media-vector plain final_flag_on_non_final 1", 2048);
        s1[67_000 - 65_536..].fill(0);
        neg.push(neg_json(
            "final_flag_on_non_final",
            "plain_size 67000; segment 0 encrypted with the final flag set",
            &k,
            67_000,
            &raw_segments(&k, &[(&s0, 0, true), (&s1, 1, true)]),
            "Integrity",
        ));
        let mut short = seg(&two.cipher, 0).to_vec();
        short.extend_from_slice(&seg(&two.cipher, 1)[..16]);
        neg.push(neg_json(
            "last_segment_16_bytes",
            "a full segment followed by a 16-byte last segment (no plaintext byte)",
            &two.key,
            two_size,
            &short,
            "Format",
        ));
        let mut flipped = small.cipher.clone();
        let last = flipped.len() - 1;
        flipped[last] ^= 0x01;
        neg.push(neg_json(
            "tag_bit_flipped",
            "len_100 with one bit of its tag flipped (sha256 is of the flipped blob)",
            &small.key,
            100,
            &flipped,
            "Integrity",
        ));
        let k = seeded_key("nonzero_padding");
        let mut p = seeded("risime-media-vector plain nonzero_padding", 10);
        assert_eq!(padme(9), 10);
        p[9] = 0x80;
        neg.push(neg_json(
            "nonzero_padding",
            "plain_size 9 padded to 10 with a 0x80 pad byte, correctly encrypted",
            &k,
            9,
            &raw_segments(&k, &[(&p, 0, true)]),
            "Format",
        ));
        neg.push(neg_json(
            "plain_size_inconsistent",
            "len_100 with plain_size 120 (Padmé(120) + 16 = 136 != cipher_size 120; 101..=104 share len_100's bucket)",
            &small.key,
            120,
            &small.cipher,
            "Format",
        ));
        neg.push(neg_json(
            "wrong_key",
            "len_100 opened with len_1's key",
            &pos[0].key,
            100,
            &small.cipher,
            "Integrity",
        ));
        fs::remove_dir_all(&dir).unwrap();

        serde_json::json!({
            "comment": "A256GCM-S64K media vectors (contract v1.11, crypto review R6). Generated by \
                        `cargo test -p risime-mls media_vectors -- --ignored`. All binary fields are \
                        lowercase hex. A negative case must fail with `expect` when opened with \
                        alg A256GCM-S64K and its key, plain_size, cipher_size and sha256.",
            "alg": MEDIA_ALG,
            "hkdf": "HKDF-SHA256, salt empty, info = hkdf_info, L = 32",
            "hkdf_info": String::from_utf8(MEDIA_HKDF_INFO.to_vec()).unwrap(),
            "aad": String::from_utf8(MEDIA_AAD.to_vec()).unwrap(),
            "segment_size": MEDIA_SEGMENT,
            "tag_size": MEDIA_TAG,
            "nonce": "0x00 x 7 || uint32_be(i) || (0x01 if last segment else 0x00)",
            "padding": "Padmé(L), zero bytes",
            "positive": pos.iter().map(pos_json).collect::<Vec<_>>(),
            "negative": neg,
        })
    }

    fn vectors_path() -> PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/media_vectors.json")
    }

    /// Writes `tests/media_vectors.json`.
    #[test]
    #[ignore = "writes the vector file; run with --ignored"]
    fn media_vectors_write() {
        let v = build_vectors();
        fs::write(
            vectors_path(),
            serde_json::to_string_pretty(&v).unwrap() + "\n",
        )
        .unwrap();
    }

    /// The committed vectors are exactly what the generator produces.
    #[test]
    fn media_vectors_are_current() {
        let want = build_vectors();
        let have: serde_json::Value =
            serde_json::from_str(&fs::read_to_string(vectors_path()).expect("vectors file"))
                .unwrap();
        assert!(
            have == want,
            "tests/media_vectors.json is stale: regenerate it"
        );
    }

    /// `contract/v1/media_vectors.json` (written by root's independent Python reference,
    /// `scripts/gen-media-vectors`) is the same JSON value as ours.
    #[test]
    fn media_vectors_match_the_contract() {
        let p = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../contract/v1/media_vectors.json");
        let have: serde_json::Value =
            serde_json::from_str(&fs::read_to_string(&p).expect("contract vectors")).unwrap();
        assert!(
            have == build_vectors(),
            "contract/v1/media_vectors.json differs from the core's vectors"
        );
    }
}
