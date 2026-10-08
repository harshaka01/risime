//! The `RISIMEBK` v1 file (§22.4) and the `A256GCM-STREAM64K` stream (§22.2, crypto C5).
//!
//! ```text
//! "RISIMEBK" ‖ 0x01 ‖ u32_be header_len ‖ header ‖ C_0 ‖ … ‖ C_{n−1}
//! ```
//! `P = D ‖ 0x00 × (Padmé(|D|) − |D|)` is cut into 65 536-byte chunks (the last holds 1–65 536);
//! chunk `i` is AES-256-GCM under `Ks` with `nonce_i = 0x00×7 ‖ u32_be(i) ‖ flag` and
//! `AAD_i = "risime-backup-v1" ‖ header_hash ‖ u32_be(i) ‖ flag` (flag 1 on the last chunk only),
//! `header_hash = SHA-256(prefix ‖ header)` over the bytes as written.

use std::fs::{self, File};
use std::io::{ErrorKind, Read, Write};
use std::path::{Path, PathBuf};

use aes_gcm::aead::AeadInPlace;
use aes_gcm::{Aes256Gcm, KeyInit, Nonce, Tag};
use miniz_oxide::inflate::stream::{InflateState, inflate};
use miniz_oxide::{DataFormat, MZError, MZFlush, MZStatus};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use zeroize::Zeroizing;

use super::keys::{BACKUP_LABEL, KEY_LEN, stream_key};
use super::{BackupError, BackupResult};
use crate::media::padme;

/// The file magic.
pub const MAGIC: &[u8; 8] = b"RISIMEBK";
/// The format version byte.
pub const FORMAT_VERSION: u8 = 1;
/// `"RISIMEBK" ‖ version ‖ u32 header_len`.
pub const PREFIX_LEN: u64 = 13;
/// The largest header.
pub const MAX_HEADER_LEN: u64 = 65_536;
/// Plaintext bytes per chunk.
pub const CHUNK: u64 = 65_536;
/// Tag bytes per chunk.
pub const TAG: u64 = 16;
/// Ciphertext bytes per full chunk.
pub const CIPHER_CHUNK: u64 = CHUNK + TAG;
/// `dek.alg`, `stream.alg`, `stream.compression`, `stream.pad`.
pub const DEK_ALG: &str = "A256GCM";
pub const STREAM_ALG: &str = "A256GCM-STREAM64K";
pub const COMPRESSION: &str = "deflate";
pub const PAD: &str = "padme";
/// The file format `v` and the bundle schema this core writes and reads.
pub const HEADER_V: u64 = 1;
pub const BUNDLE_SCHEMA: u64 = 1;
/// The newest bundle schema this core reads and may write (§24.10: 2 when the bundle holds an
/// Official conversation). A file header above this is `UnsupportedSchema`.
pub const MAX_BUNDLE_SCHEMA: u64 = 2;

fn io(e: std::io::Error) -> BackupError {
    BackupError::Io(e.to_string())
}

fn format(s: impl Into<String>) -> BackupError {
    BackupError::Format(s.into())
}

/// `header.dek`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub(crate) struct DekHeader {
    pub alg: String,
    pub nonce: String,
    pub wrapped: String,
}

/// `header.stream`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub(crate) struct StreamHeader {
    pub alg: String,
    pub compression: String,
    pub pad: String,
}

/// The file header (`backup_file_header.json`).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub(crate) struct FileHeader {
    pub v: u64,
    pub schema: u64,
    pub backup_id: String,
    pub user_id: String,
    pub created_at: String,
    pub app_version: String,
    pub bk_id: String,
    pub dek: DekHeader,
    pub stream: StreamHeader,
    /// The key record at backup time, or `null`.
    pub key: Option<serde_json::Value>,
}

/// `"RISIMEBK" ‖ 0x01 ‖ u32_be(len)`.
pub(crate) fn prefix(header_len: usize) -> BackupResult<[u8; PREFIX_LEN as usize]> {
    if header_len as u64 > MAX_HEADER_LEN {
        return Err(BackupError::Malformed("header over 64 KiB".into()));
    }
    let mut p = [0u8; PREFIX_LEN as usize];
    p[..8].copy_from_slice(MAGIC);
    p[8] = FORMAT_VERSION;
    p[9..].copy_from_slice(&(header_len as u32).to_be_bytes());
    Ok(p)
}

/// Checks the 13-byte prefix against the file length; returns `header_len`.
pub(crate) fn parse_prefix(p: &[u8], file_len: u64) -> BackupResult<u64> {
    if p.len() < PREFIX_LEN as usize || file_len < PREFIX_LEN {
        return Err(format("shorter than the file prefix"));
    }
    if &p[..8] != MAGIC {
        return Err(format("not a RisiMe backup (magic)"));
    }
    if p[8] != FORMAT_VERSION {
        return Err(format(format!("unknown format version {}", p[8])));
    }
    let hl = u64::from(u32::from_be_bytes(p[9..13].try_into().expect("4")));
    if hl > MAX_HEADER_LEN || PREFIX_LEN + hl > file_len {
        return Err(format(format!("header_len {hl} out of range")));
    }
    Ok(hl)
}

/// The number of chunks of a `payload_len`-byte ciphertext, with the last ≥ 17 bytes.
pub(crate) fn chunk_count(payload_len: u64) -> BackupResult<u64> {
    if payload_len == 0 {
        return Err(format("no chunks"));
    }
    let n = payload_len.div_ceil(CIPHER_CHUNK);
    let last = payload_len - (n - 1) * CIPHER_CHUNK;
    if last <= TAG {
        return Err(format(format!("last chunk is {last} bytes")));
    }
    if n > u64::from(u32::MAX) + 1 {
        return Err(format("more than 2^32 chunks"));
    }
    Ok(n)
}

fn chunk_nonce(i: u64, last: bool) -> [u8; 12] {
    let mut n = [0u8; 12];
    n[7..11].copy_from_slice(&(i as u32).to_be_bytes());
    n[11] = u8::from(last);
    n
}

fn chunk_aad(hh: &[u8; 32], i: u64, last: bool) -> Vec<u8> {
    let mut a = Vec::with_capacity(BACKUP_LABEL.len() + 32 + 5);
    a.extend_from_slice(BACKUP_LABEL);
    a.extend_from_slice(hh);
    a.extend_from_slice(&(i as u32).to_be_bytes());
    a.push(u8::from(last));
    a
}

pub(crate) fn stream_cipher(dek: &[u8; KEY_LEN]) -> Aes256Gcm {
    let ks = stream_key(dek);
    Aes256Gcm::new_from_slice(&ks[..]).expect("32-byte key")
}

/// Encrypts `buf[..len - 16]` in place and writes the tag into the last 16 bytes.
pub(crate) fn seal_chunk(
    c: &Aes256Gcm,
    hh: &[u8; 32],
    i: u64,
    last: bool,
    buf: &mut [u8],
) -> BackupResult<()> {
    let p = buf.len() - TAG as usize;
    let (body, tag) = buf.split_at_mut(p);
    let t = c
        .encrypt_in_place_detached(
            Nonce::from_slice(&chunk_nonce(i, last)),
            &chunk_aad(hh, i, last),
            body,
        )
        .map_err(|_| BackupError::Io("aead encrypt".into()))?;
    tag.copy_from_slice(&t);
    Ok(())
}

/// Opens `buf` (ciphertext ‖ tag) in place; the plaintext is `buf[..len - 16]`.
pub(crate) fn open_chunk(
    c: &Aes256Gcm,
    hh: &[u8; 32],
    i: u64,
    last: bool,
    buf: &mut [u8],
) -> BackupResult<()> {
    let p = buf.len() - TAG as usize;
    let (body, tag) = buf.split_at_mut(p);
    c.decrypt_in_place_detached(
        Nonce::from_slice(&chunk_nonce(i, last)),
        &chunk_aad(hh, i, last),
        body,
        Tag::from_slice(tag),
    )
    .map_err(|_| BackupError::Integrity(format!("chunk {i} failed to verify")))
}

/// Seals a whole in-memory `P` (vectors and tests).
#[cfg(test)]
pub(crate) fn seal_stream_bytes(dek: &[u8; KEY_LEN], header: &[u8], p: &[u8]) -> Vec<u8> {
    let mut out = prefix(header.len()).unwrap().to_vec();
    out.extend_from_slice(header);
    let hh: [u8; 32] = Sha256::digest(&out).into();
    let c = stream_cipher(dek);
    let n = (p.len() as u64).div_ceil(CHUNK);
    for (i, chunk) in p.chunks(CHUNK as usize).enumerate() {
        let mut b = chunk.to_vec();
        b.extend_from_slice(&[0; 16]);
        seal_chunk(&c, &hh, i as u64, i as u64 == n - 1, &mut b).unwrap();
        out.extend_from_slice(&b);
    }
    out
}

/// Opens an in-memory file under a given `DEK` (vectors): prefix, chunk layout, every chunk.
/// Returns `(P, header_hash, prefix_len)`.
pub(crate) fn open_stream_bytes(
    dek: &[u8; KEY_LEN],
    file: &[u8],
) -> BackupResult<(Zeroizing<Vec<u8>>, [u8; 32], usize)> {
    let hl = parse_prefix(file, file.len() as u64)?;
    let pl = (PREFIX_LEN + hl) as usize;
    let hh: [u8; 32] = Sha256::digest(&file[..pl]).into();
    let payload = &file[pl..];
    let n = chunk_count(payload.len() as u64)?;
    let c = stream_cipher(dek);
    let mut p = Zeroizing::new(Vec::with_capacity(payload.len()));
    for (i, chunk) in payload.chunks(CIPHER_CHUNK as usize).enumerate() {
        let mut b = Zeroizing::new(chunk.to_vec());
        open_chunk(&c, &hh, i as u64, i as u64 == n - 1, &mut b)?;
        p.extend_from_slice(&b[..b.len() - TAG as usize]);
    }
    Ok((p, hh, pl))
}

/// The padding rule with a known `|D|` (vectors): `|P| = Padmé(|D|)` and the pad is zero.
pub(crate) fn check_padding(p: &[u8], d_len: u64) -> BackupResult<()> {
    if d_len == 0 || padme(d_len) != p.len() as u64 {
        return Err(format(format!(
            "{} bytes of P for a {d_len}-byte stream",
            p.len()
        )));
    }
    if p[d_len as usize..].iter().any(|&b| b != 0) {
        return Err(format("nonzero padding"));
    }
    Ok(())
}

/// Finds the end of the raw DEFLATE stream in `P` (fed in order), then checks that every later
/// byte is zero and that `|P| = Padmé(|D|)` (§22.2 "Opening"). The inflated output is discarded.
pub(crate) struct PadChecker {
    state: Box<InflateState>,
    out: Zeroizing<Vec<u8>>,
    total: u64,
    d_len: Option<u64>,
    nonzero: bool,
}

impl PadChecker {
    pub(crate) fn new() -> Self {
        Self {
            state: InflateState::new_boxed(DataFormat::Raw),
            out: Zeroizing::new(vec![0u8; 64 * 1024]),
            total: 0,
            d_len: None,
            nonzero: false,
        }
    }

    pub(crate) fn feed(&mut self, data: &[u8]) -> BackupResult<()> {
        let mut input = data;
        let mut consumed_here = 0u64;
        while !input.is_empty() {
            if self.d_len.is_some() {
                self.nonzero |= input.iter().any(|&b| b != 0);
                break;
            }
            let r = inflate(&mut self.state, input, &mut self.out, MZFlush::None);
            input = &input[r.bytes_consumed..];
            consumed_here += r.bytes_consumed as u64;
            match r.status {
                Ok(MZStatus::StreamEnd) => self.d_len = Some(self.total + consumed_here),
                Ok(MZStatus::Ok) => {}
                Err(MZError::Buf) if r.bytes_consumed == 0 && r.bytes_written == 0 => {
                    return Err(format("the DEFLATE stream stalled"));
                }
                Err(MZError::Buf) => {}
                _ => return Err(format("the bundle is not a valid DEFLATE stream")),
            }
        }
        self.total += data.len() as u64;
        Ok(())
    }

    /// `|D|`.
    pub(crate) fn finish(self) -> BackupResult<u64> {
        let d = self
            .d_len
            .ok_or_else(|| format("the DEFLATE stream doesn't end"))?;
        if self.nonzero {
            return Err(format("nonzero padding"));
        }
        if d == 0 || padme(d) != self.total {
            return Err(format(format!(
                "{} bytes of P for a {d}-byte stream",
                self.total
            )));
        }
        Ok(d)
    }
}

// ------------------------------------------------------------------------------- writer

/// A sibling temp path: `<dst>.risime-tmp`.
fn temp_path(dst: &Path) -> PathBuf {
    let mut s = dst.as_os_str().to_owned();
    s.push(".risime-tmp");
    PathBuf::from(s)
}

/// What [`BackupWriter::finish`] produced: what `POST /backups` needs about the whole file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackupWritten {
    pub backup_id: String,
    /// base64 (8 bytes).
    pub bk_id: String,
    /// The file size.
    pub size: u64,
    /// SHA-256 of the whole file.
    pub sha256: [u8; 32],
    /// `|D|`: the DEFLATE bytes written.
    pub data_size: u64,
}

/// Streams the app's DEFLATE output into a `RISIMEBK` file under a fresh `DEK`. The file
/// appears at `out_path` only after [`finish`](Self::finish) (temp file, fsync, rename). A
/// writer that failed is unusable: start a new backup (new `backup_id`, new `DEK`).
pub struct BackupWriter {
    file: Option<File>,
    tmp: Option<PathBuf>,
    dst: PathBuf,
    cipher: Aes256Gcm,
    hh: [u8; 32],
    buf: Zeroizing<Vec<u8>>,
    index: u64,
    d_len: u64,
    size: u64,
    sha: Sha256,
    backup_id: String,
    bk_id: String,
    failed: bool,
}

impl std::fmt::Debug for BackupWriter {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("BackupWriter")
            .field("backup_id", &self.backup_id)
            .field("dst", &self.dst)
            .finish_non_exhaustive()
    }
}

impl BackupWriter {
    /// Writes the prefix and `header` to a temp file next to `dst`.
    pub(crate) fn create(
        dst: &Path,
        header: &[u8],
        dek: &[u8; KEY_LEN],
        backup_id: String,
        bk_id: String,
    ) -> BackupResult<Self> {
        let pre = prefix(header.len())?;
        let mut sha = Sha256::new();
        sha.update(pre);
        sha.update(header);
        let hh: [u8; 32] = sha.clone().finalize().into();
        let tmp = temp_path(dst);
        let mut file = File::create(&tmp).map_err(io)?;
        let mut w = Self {
            file: None,
            tmp: Some(tmp),
            dst: dst.to_path_buf(),
            cipher: stream_cipher(dek),
            hh,
            buf: Zeroizing::new(Vec::with_capacity(CIPHER_CHUNK as usize)),
            index: 0,
            d_len: 0,
            size: PREFIX_LEN + header.len() as u64,
            sha,
            backup_id,
            bk_id,
            failed: false,
        };
        file.write_all(&pre).map_err(io)?;
        file.write_all(header).map_err(io)?;
        w.file = Some(file);
        Ok(w)
    }

    fn emit(&mut self, last: bool) -> BackupResult<()> {
        if self.index > u64::from(u32::MAX) {
            return Err(BackupError::Malformed("more than 2^32 chunks".into()));
        }
        // `buf` has capacity for a chunk and its tag, so this never reallocates (no stray copy).
        let mut b = std::mem::take(&mut *self.buf);
        b.extend_from_slice(&[0u8; TAG as usize]);
        let r = seal_chunk(&self.cipher, &self.hh, self.index, last, &mut b).and_then(|()| {
            self.sha.update(&b);
            self.file
                .as_mut()
                .ok_or_else(|| BackupError::Malformed("writer is closed".into()))?
                .write_all(&b)
                .map_err(io)
        });
        let written = b.len() as u64;
        b.clear();
        *self.buf = b;
        r?;
        self.size += written;
        self.index += 1;
        Ok(())
    }

    fn push(&mut self, mut data: &[u8]) -> BackupResult<()> {
        while !data.is_empty() {
            if self.buf.len() as u64 == CHUNK {
                self.emit(false)?;
            }
            let take = ((CHUNK as usize) - self.buf.len()).min(data.len());
            self.buf.extend_from_slice(&data[..take]);
            data = &data[take..];
        }
        Ok(())
    }

    fn guard<T>(&mut self, f: impl FnOnce(&mut Self) -> BackupResult<T>) -> BackupResult<T> {
        if self.failed || self.file.is_none() {
            return Err(BackupError::Malformed(
                "this backup writer failed or finished; start a new backup".into(),
            ));
        }
        let r = f(self);
        if r.is_err() {
            self.failed = true;
            self.file = None;
            if let Some(t) = self.tmp.take() {
                let _ = fs::remove_file(t);
            }
        }
        r
    }

    /// Appends DEFLATE output (any split).
    pub fn write(&mut self, data: &[u8]) -> BackupResult<()> {
        self.guard(|w| {
            w.push(data)?;
            w.d_len += data.len() as u64;
            Ok(())
        })
    }

    /// Pads (Padmé), writes the last chunk with the final flag, fsyncs and renames the file into
    /// place. `|D| = 0` is `Malformed` (no empty stream).
    pub fn finish(&mut self) -> BackupResult<BackupWritten> {
        self.guard(|w| {
            if w.d_len == 0 {
                return Err(BackupError::Malformed("empty bundle".into()));
            }
            let mut pad = padme(w.d_len) - w.d_len;
            let zeros = [0u8; 4096];
            while pad > 0 {
                let k = pad.min(zeros.len() as u64) as usize;
                w.push(&zeros[..k])?;
                pad -= k as u64;
            }
            w.emit(true)?;
            let file = w.file.take().expect("open");
            file.sync_all().map_err(io)?;
            drop(file);
            let tmp = w.tmp.take().expect("armed");
            if let Err(e) = fs::rename(&tmp, &w.dst) {
                let _ = fs::remove_file(&tmp);
                return Err(io(e));
            }
            Ok(BackupWritten {
                backup_id: w.backup_id.clone(),
                bk_id: w.bk_id.clone(),
                size: w.size,
                sha256: w.sha.clone().finalize().into(),
                data_size: w.d_len,
            })
        })
    }
}

impl Drop for BackupWriter {
    fn drop(&mut self) {
        self.file = None;
        if let Some(t) = self.tmp.take() {
            let _ = fs::remove_file(t);
        }
    }
}

// ------------------------------------------------------------------------------- reader

/// The prefix and header of a file, unauthenticated until the verify pass.
pub(crate) struct OpenedFile {
    pub file: File,
    pub len: u64,
    pub prefix_len: u64,
    pub hh: [u8; 32],
    pub header_bytes: Vec<u8>,
}

fn read_full(r: &mut impl Read, buf: &mut [u8]) -> BackupResult<()> {
    r.read_exact(buf).map_err(|e| match e.kind() {
        ErrorKind::UnexpectedEof => {
            BackupError::Integrity("the file changed while being read".into())
        }
        _ => io(e),
    })
}

/// Reads and checks the prefix (`Format`) and reads the header bytes.
pub(crate) fn open_file(path: &Path) -> BackupResult<OpenedFile> {
    let mut file = File::open(path).map_err(io)?;
    let len = file.metadata().map_err(io)?.len();
    let mut pre = [0u8; PREFIX_LEN as usize];
    if len < PREFIX_LEN {
        return Err(format("shorter than the file prefix"));
    }
    read_full(&mut file, &mut pre)?;
    let hl = parse_prefix(&pre, len)?;
    let mut header_bytes = vec![0u8; hl as usize];
    read_full(&mut file, &mut header_bytes)?;
    let mut sha = Sha256::new();
    sha.update(pre);
    sha.update(&header_bytes);
    Ok(OpenedFile {
        file,
        len,
        prefix_len: PREFIX_LEN + hl,
        hh: sha.finalize().into(),
        header_bytes,
    })
}

/// Parses the header JSON: `v` and `schema` first (`Unsupported` when newer), then the fields
/// (`Format`).
pub(crate) fn parse_header(bytes: &[u8]) -> BackupResult<FileHeader> {
    let v: serde_json::Value =
        serde_json::from_slice(bytes).map_err(|_| format("the header is not JSON"))?;
    let ver = v
        .get("v")
        .and_then(serde_json::Value::as_u64)
        .ok_or_else(|| format("header without v"))?;
    let schema = v
        .get("schema")
        .and_then(serde_json::Value::as_u64)
        .ok_or_else(|| format("header without schema"))?;
    if ver != HEADER_V {
        return Err(BackupError::Unsupported(format!(
            "header v {ver}, schema {schema}"
        )));
    }
    if schema > MAX_BUNDLE_SCHEMA {
        return Err(BackupError::UnsupportedSchema(schema));
    }
    let h: FileHeader =
        serde_json::from_value(v).map_err(|e| format(format!("header fields: {e}")))?;
    if h.stream.alg != STREAM_ALG
        || h.stream.compression != COMPRESSION
        || h.stream.pad != PAD
        || h.dek.alg != DEK_ALG
    {
        return Err(BackupError::Unsupported(format!(
            "stream {}/{}/{}, dek {}",
            h.stream.alg, h.stream.compression, h.stream.pad, h.dek.alg
        )));
    }
    Ok(h)
}

/// Authenticated facts after the verify pass.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BackupVerified {
    /// `|D|`: the DEFLATE stream length that [`BackupReader::read`] returns in total.
    pub data_size: u64,
    /// `|P|`.
    pub padded_size: u64,
    pub chunks: u64,
}

/// Opens a `RISIMEBK` file: [`verify`](Self::verify) runs the whole verify pass (every chunk,
/// the final flag, the DEFLATE end and the zero padding); only then does [`read`](Self::read)
/// return `D` in order, re-authenticating every chunk as it goes.
pub struct BackupReader {
    path: PathBuf,
    len: u64,
    prefix_len: u64,
    hh: [u8; 32],
    header: FileHeader,
    cipher: Aes256Gcm,
    n: u64,
    verified: Option<BackupVerified>,
    pos: Option<ReadPos>,
}

struct ReadPos {
    file: File,
    i: u64,
    emitted: u64,
    done: bool,
}

impl std::fmt::Debug for BackupReader {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("BackupReader")
            .field("path", &self.path)
            .field("backup_id", &self.header.backup_id)
            .finish_non_exhaustive()
    }
}

impl BackupReader {
    pub(crate) fn new(
        path: &Path,
        opened: OpenedFile,
        header: FileHeader,
        dek: &[u8; KEY_LEN],
    ) -> BackupResult<Self> {
        let n = chunk_count(opened.len - opened.prefix_len)?;
        Ok(Self {
            path: path.to_path_buf(),
            len: opened.len,
            prefix_len: opened.prefix_len,
            hh: opened.hh,
            header,
            cipher: stream_cipher(dek),
            n,
            verified: None,
            pos: None,
        })
    }

    pub(crate) fn header(&self) -> &FileHeader {
        &self.header
    }

    /// Re-opens the file and checks it still has the size and header that were opened.
    fn reopen(&self) -> BackupResult<File> {
        let o = open_file(&self.path).map_err(|e| match e {
            BackupError::Io(s) => BackupError::Io(s),
            _ => BackupError::Integrity("the file changed".into()),
        })?;
        if o.len != self.len || o.hh != self.hh {
            return Err(BackupError::Integrity("the file changed".into()));
        }
        Ok(o.file)
    }

    fn chunk_len(&self, i: u64) -> usize {
        (self.len - self.prefix_len - i * CIPHER_CHUNK).min(CIPHER_CHUNK) as usize
    }

    /// The verify pass. Nothing is returned by [`read`](Self::read) before it succeeded.
    /// `Integrity` for a failed chunk (truncation, extension, reordering, another backup's
    /// chunk, a changed header), `Format` for the DEFLATE end or the padding.
    pub fn verify(&mut self) -> BackupResult<BackupVerified> {
        if let Some(v) = self.verified {
            return Ok(v);
        }
        let mut file = self.reopen()?;
        let mut buf = Zeroizing::new(vec![0u8; CIPHER_CHUNK as usize]);
        let mut pc = PadChecker::new();
        for i in 0..self.n {
            let l = self.chunk_len(i);
            read_full(&mut file, &mut buf[..l])?;
            open_chunk(&self.cipher, &self.hh, i, i == self.n - 1, &mut buf[..l])?;
            pc.feed(&buf[..l - TAG as usize])?;
        }
        let padded_size = self.len - self.prefix_len - self.n * TAG;
        let data_size = pc.finish()?;
        let v = BackupVerified {
            data_size,
            padded_size,
            chunks: self.n,
        };
        self.verified = Some(v);
        Ok(v)
    }

    /// The next piece of `D` (at most 64 KiB), in order; empty when everything was returned.
    /// Fails unless [`verify`](Self::verify) succeeded first.
    pub fn read(&mut self) -> BackupResult<Zeroizing<Vec<u8>>> {
        let v = self
            .verified
            .ok_or_else(|| BackupError::Malformed("read before a successful verify".into()))?;
        if self.pos.is_none() {
            let file = self.reopen()?;
            self.pos = Some(ReadPos {
                file,
                i: 0,
                emitted: 0,
                done: false,
            });
        }
        loop {
            let (i, emitted, done) = {
                let p = self.pos.as_ref().expect("set");
                (p.i, p.emitted, p.done)
            };
            if done || i >= self.n {
                return Ok(Zeroizing::new(Vec::new()));
            }
            let l = self.chunk_len(i);
            let mut buf = Zeroizing::new(vec![0u8; l]);
            let r = {
                let p = self.pos.as_mut().expect("set");
                read_full(&mut p.file, &mut buf)
            }
            .and_then(|()| open_chunk(&self.cipher, &self.hh, i, i == self.n - 1, &mut buf));
            if let Err(e) = r {
                self.pos.as_mut().expect("set").done = true;
                return Err(e);
            }
            let plain = &buf[..l - TAG as usize];
            let want = (v.data_size - emitted).min(plain.len() as u64) as usize;
            let p = self.pos.as_mut().expect("set");
            p.i += 1;
            p.emitted += want as u64;
            if want > 0 {
                return Ok(Zeroizing::new(plain[..want].to_vec()));
            }
        }
    }
}
