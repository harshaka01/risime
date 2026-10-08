//! The journal store: an in-memory [`KvStore`] loaded from sealed rows, which records every key a
//! call changes so the caller can persist exactly those rows, sealed, in one database transaction.
//!
//! Sealing (contract v1.24 §24.11): AES-256-GCM under `RISI_MLS_KEK`, a fresh random 96-bit nonce
//! per value, `sealed = nonce (12) ‖ ciphertext ‖ tag (16)`, and
//! `AAD = "risi-kv-v1" ‖ u16be(len(device_id)) ‖ device_id ‖ key`. The AAD binds a row to its key
//! and device, so a row copied to another key (or another device's table) fails to open. The
//! length prefix keeps `device_id ‖ key` unambiguous for device ids of any length.
//!
//! Transactions nest with an undo log per level (no full-map snapshots: Risi's state grows with
//! the number of Official groups). Values live in memory in the clear and are wiped on drop.

use std::collections::{BTreeSet, HashMap};
use std::sync::{Mutex, MutexGuard};

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use risime_mls::{KvError, KvStore};
use zeroize::Zeroize;

/// The AAD label (and format version) of a sealed row.
pub const SEAL_LABEL: &[u8] = b"risi-kv-v1";
/// Bytes of the AES-GCM nonce at the start of every sealed value.
pub const NONCE_LEN: usize = 12;
/// Bytes of the AES-GCM tag at the end of every sealed value.
pub const TAG_LEN: usize = 16;
/// `RISI_MLS_KEK` is exactly this long.
pub const KEK_LEN: usize = 32;

/// One journal entry: the key and its new sealed value, or `None` for a delete.
pub type JournalEntry = (Vec<u8>, Option<Vec<u8>>);

/// Errors of the store itself (never contains key material or plaintext).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SealError {
    /// The KEK is not 32 bytes.
    BadKek,
    /// A sealed row failed authentication (wrong KEK, other device or key, or tampered bytes).
    /// The number is the row's position in the input.
    Tampered(usize),
    /// The OS random source failed, or a transaction was still open when the journal was drained.
    Internal(String),
}

impl std::fmt::Display for SealError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SealError::BadKek => write!(f, "RISI_MLS_KEK must be {KEK_LEN} bytes"),
            SealError::Tampered(i) => write!(f, "sealed row {i} failed authentication"),
            SealError::Internal(m) => write!(f, "{m}"),
        }
    }
}

/// AES-256-GCM sealing of one device's rows.
pub struct Sealer {
    cipher: Aes256Gcm,
    device_id: Vec<u8>,
}

impl Sealer {
    pub fn new(kek: &[u8], device_id: &str) -> Result<Self, SealError> {
        if kek.len() != KEK_LEN {
            return Err(SealError::BadKek);
        }
        let cipher = Aes256Gcm::new_from_slice(kek).map_err(|_| SealError::BadKek)?;
        Ok(Self {
            cipher,
            device_id: device_id.as_bytes().to_vec(),
        })
    }

    fn aad(&self, key: &[u8]) -> Vec<u8> {
        let mut aad = Vec::with_capacity(SEAL_LABEL.len() + 2 + self.device_id.len() + key.len());
        aad.extend_from_slice(SEAL_LABEL);
        // Device ids are UUIDs (36 bytes); anything longer than u16 is refused by DeviceId anyway.
        aad.extend_from_slice(&(self.device_id.len() as u16).to_be_bytes());
        aad.extend_from_slice(&self.device_id);
        aad.extend_from_slice(key);
        aad
    }

    /// Seal `value` stored under `key`.
    pub fn seal(&self, key: &[u8], value: &[u8]) -> Result<Vec<u8>, SealError> {
        let mut nonce = [0u8; NONCE_LEN];
        getrandom::getrandom(&mut nonce)
            .map_err(|e| SealError::Internal(format!("random nonce: {e}")))?;
        let aad = self.aad(key);
        let ct = self
            .cipher
            .encrypt(
                Nonce::from_slice(&nonce),
                Payload {
                    msg: value,
                    aad: &aad,
                },
            )
            .map_err(|_| SealError::Internal("seal failed".into()))?;
        let mut out = Vec::with_capacity(NONCE_LEN + ct.len());
        out.extend_from_slice(&nonce);
        out.extend_from_slice(&ct);
        Ok(out)
    }

    /// Open a row sealed under `key`. `None` when it fails authentication or is too short.
    pub fn open(&self, key: &[u8], sealed: &[u8]) -> Option<Vec<u8>> {
        if sealed.len() < NONCE_LEN + TAG_LEN {
            return None;
        }
        let (nonce, ct) = sealed.split_at(NONCE_LEN);
        let aad = self.aad(key);
        self.cipher
            .decrypt(Nonce::from_slice(nonce), Payload { msg: ct, aad: &aad })
            .ok()
    }
}

struct Undo {
    key: Vec<u8>,
    old: Option<Vec<u8>>,
    was_dirty: bool,
}

#[derive(Default)]
struct State {
    map: HashMap<Vec<u8>, Vec<u8>>,
    /// Keys changed since the last [`JournalStore::drain`]. Ordered, so journals are deterministic.
    dirty: BTreeSet<Vec<u8>>,
    /// One undo log per open transaction level.
    levels: Vec<Vec<Undo>>,
}

impl Drop for State {
    fn drop(&mut self) {
        for v in self.map.values_mut() {
            v.zeroize();
        }
        for level in &mut self.levels {
            for u in level {
                if let Some(v) = &mut u.old {
                    v.zeroize();
                }
            }
        }
    }
}

impl State {
    fn record(&mut self, key: &[u8]) {
        let old = self.map.get(key).cloned();
        let was_dirty = self.dirty.contains(key);
        if let Some(level) = self.levels.last_mut() {
            level.push(Undo {
                key: key.to_vec(),
                old,
                was_dirty,
            });
        }
    }
}

/// The in-memory, journaling [`KvStore`] of one Risi device.
pub struct JournalStore {
    sealer: Sealer,
    state: Mutex<State>,
}

impl JournalStore {
    /// Load `rows` (`(key, sealed)`), opening each with `kek`. Any row that fails authentication
    /// fails the whole load with [`SealError::Tampered`].
    pub fn load(
        kek: &[u8],
        device_id: &str,
        rows: impl IntoIterator<Item = (Vec<u8>, Vec<u8>)>,
    ) -> Result<Self, SealError> {
        let sealer = Sealer::new(kek, device_id)?;
        let mut state = State::default();
        for (i, (key, sealed)) in rows.into_iter().enumerate() {
            let value = sealer.open(&key, &sealed).ok_or(SealError::Tampered(i))?;
            state.map.insert(key, value);
        }
        Ok(Self {
            sealer,
            state: Mutex::new(state),
        })
    }

    fn lock(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|p| p.into_inner())
    }

    /// The changes since the last drain, sealed, and clear them. Must be called outside any
    /// transaction.
    pub fn drain(&self) -> Result<Vec<JournalEntry>, SealError> {
        let mut st = self.lock();
        if !st.levels.is_empty() {
            return Err(SealError::Internal(
                "journal drained inside a transaction".into(),
            ));
        }
        let mut out = Vec::with_capacity(st.dirty.len());
        for key in &st.dirty {
            let sealed = match st.map.get(key) {
                Some(v) => Some(self.sealer.seal(key, v)?),
                None => None,
            };
            out.push((key.clone(), sealed));
        }
        st.dirty.clear();
        Ok(out)
    }

    /// Number of stored entries (tests).
    pub fn len(&self) -> usize {
        self.lock().map.len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Open transaction depth (tests: 0 after every call).
    pub fn depth(&self) -> usize {
        self.lock().levels.len()
    }
}

impl KvStore for JournalStore {
    fn get(&self, key: &[u8]) -> Result<Option<Vec<u8>>, KvError> {
        Ok(self.lock().map.get(key).cloned())
    }

    fn put(&self, key: &[u8], value: &[u8]) -> Result<(), KvError> {
        let mut st = self.lock();
        st.record(key);
        // The undo log (if any) holds its own copy.
        if let Some(mut old) = st.map.insert(key.to_vec(), value.to_vec()) {
            old.zeroize();
        }
        st.dirty.insert(key.to_vec());
        Ok(())
    }

    fn delete(&self, key: &[u8]) -> Result<(), KvError> {
        let mut st = self.lock();
        st.record(key);
        if let Some(mut old) = st.map.remove(key) {
            old.zeroize();
        }
        st.dirty.insert(key.to_vec());
        Ok(())
    }

    fn begin(&self) -> Result<(), KvError> {
        self.lock().levels.push(Vec::new());
        Ok(())
    }

    fn commit(&self) -> Result<(), KvError> {
        let mut st = self.lock();
        let level = st
            .levels
            .pop()
            .ok_or_else(|| KvError("commit without begin".into()))?;
        match st.levels.last_mut() {
            Some(parent) => parent.extend(level),
            None => {
                for mut u in level {
                    if let Some(v) = &mut u.old {
                        v.zeroize();
                    }
                }
            }
        }
        Ok(())
    }

    fn rollback(&self) -> Result<(), KvError> {
        let mut st = self.lock();
        let level = st
            .levels
            .pop()
            .ok_or_else(|| KvError("rollback without begin".into()))?;
        for u in level.into_iter().rev() {
            match u.old {
                Some(v) => {
                    if let Some(mut cur) = st.map.insert(u.key.clone(), v) {
                        cur.zeroize();
                    }
                }
                None => {
                    if let Some(mut cur) = st.map.remove(&u.key) {
                        cur.zeroize();
                    }
                }
            }
            if u.was_dirty {
                st.dirty.insert(u.key);
            } else {
                st.dirty.remove(&u.key);
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const KEK: [u8; 32] = [42; 32];
    const DEV: &str = "6f1d2c3b-4a59-4e68-9d7c-0b1a2c3d4e5f";

    fn apply(rows: &mut HashMap<Vec<u8>, Vec<u8>>, j: Vec<JournalEntry>) {
        for (k, v) in j {
            match v {
                Some(v) => rows.insert(k, v),
                None => rows.remove(&k),
            };
        }
    }

    #[test]
    fn seal_round_trip_and_binding() {
        let s = Sealer::new(&KEK, DEV).unwrap();
        let sealed = s.seal(b"k1", b"value").unwrap();
        assert_eq!(sealed.len(), NONCE_LEN + 5 + TAG_LEN);
        assert_eq!(s.open(b"k1", &sealed).unwrap(), b"value");
        // Another key, another device, another KEK, a flipped bit, a short row: all refused.
        assert!(s.open(b"k2", &sealed).is_none());
        let other_dev = Sealer::new(&KEK, "7f1d2c3b-4a59-4e68-9d7c-0b1a2c3d4e5f").unwrap();
        assert!(other_dev.open(b"k1", &sealed).is_none());
        let other_kek = Sealer::new(&[43; 32], DEV).unwrap();
        assert!(other_kek.open(b"k1", &sealed).is_none());
        for i in 0..sealed.len() {
            let mut t = sealed.clone();
            t[i] ^= 1;
            assert!(s.open(b"k1", &t).is_none(), "bit flip at {i}");
        }
        assert!(s.open(b"k1", &sealed[..NONCE_LEN + TAG_LEN - 1]).is_none());
        assert!(s.open(b"k1", &[]).is_none());
    }

    #[test]
    fn the_device_id_length_is_bound() {
        // "ab" ‖ "c…" and "a" ‖ "bc…" must not share an AAD.
        let a = Sealer::new(&KEK, "ab").unwrap();
        let b = Sealer::new(&KEK, "a").unwrap();
        let sealed = a.seal(b"ck", b"v").unwrap();
        assert!(b.open(b"bck", &sealed).is_none());
    }

    #[test]
    fn bad_kek_is_refused() {
        assert_eq!(Sealer::new(&[0; 31], DEV).err(), Some(SealError::BadKek));
        assert_eq!(Sealer::new(&[0; 33], DEV).err(), Some(SealError::BadKek));
        assert!(JournalStore::load(&[], DEV, vec![]).is_err());
    }

    #[test]
    fn nonces_are_unique() {
        let s = Sealer::new(&KEK, DEV).unwrap();
        let mut seen = std::collections::HashSet::new();
        for _ in 0..10_000 {
            let sealed = s.seal(b"same key", b"same value").unwrap();
            assert!(seen.insert(sealed[..NONCE_LEN].to_vec()), "nonce reused");
        }
    }

    #[test]
    fn tampered_rows_fail_the_load() {
        let s = Sealer::new(&KEK, DEV).unwrap();
        let good = s.seal(b"a", b"1").unwrap();
        let mut bad = s.seal(b"b", b"2").unwrap();
        *bad.last_mut().unwrap() ^= 0x80;
        let err = JournalStore::load(
            &KEK,
            DEV,
            vec![(b"a".to_vec(), good.clone()), (b"b".to_vec(), bad)],
        )
        .err();
        assert_eq!(err, Some(SealError::Tampered(1)));
        // A row moved to another key.
        let err = JournalStore::load(&KEK, DEV, vec![(b"z".to_vec(), good)]).err();
        assert_eq!(err, Some(SealError::Tampered(0)));
    }

    #[test]
    fn journal_tracks_puts_deletes_and_rollbacks() {
        let st = JournalStore::load(&KEK, DEV, vec![]).unwrap();
        st.put(b"a", b"1").unwrap();
        st.put(b"b", b"2").unwrap();
        let mut rows = HashMap::new();
        apply(&mut rows, st.drain().unwrap());
        assert_eq!(rows.len(), 2);
        assert!(st.drain().unwrap().is_empty());

        // Nested: inner rolled back, outer committed.
        st.begin().unwrap();
        st.put(b"c", b"3").unwrap();
        st.begin().unwrap();
        st.put(b"a", b"changed").unwrap();
        st.delete(b"b").unwrap();
        st.put(b"d", b"4").unwrap();
        st.rollback().unwrap();
        st.commit().unwrap();
        assert_eq!(st.depth(), 0);
        let j = st.drain().unwrap();
        assert_eq!(j.len(), 1, "only c changed");
        assert_eq!(j[0].0, b"c");
        apply(&mut rows, j);

        // Nested commit into a parent that rolls back: nothing changes.
        st.begin().unwrap();
        st.begin().unwrap();
        st.delete(b"a").unwrap();
        st.commit().unwrap();
        st.rollback().unwrap();
        assert!(st.drain().unwrap().is_empty());

        // A key dirty before a rolled-back level stays dirty.
        st.put(b"a", b"5").unwrap();
        st.begin().unwrap();
        st.put(b"a", b"6").unwrap();
        st.rollback().unwrap();
        st.delete(b"c").unwrap();
        let j = st.drain().unwrap();
        assert_eq!(j.len(), 2);
        apply(&mut rows, j);

        let reloaded = JournalStore::load(&KEK, DEV, rows).unwrap();
        assert_eq!(reloaded.get(b"a").unwrap().unwrap(), b"5");
        assert_eq!(reloaded.get(b"b").unwrap().unwrap(), b"2");
        assert!(reloaded.get(b"c").unwrap().is_none());
        assert!(reloaded.get(b"d").unwrap().is_none());
        assert_eq!(reloaded.len(), 2);
    }

    #[test]
    fn drain_inside_a_transaction_is_refused() {
        let st = JournalStore::load(&KEK, DEV, vec![]).unwrap();
        st.begin().unwrap();
        assert!(st.drain().is_err());
        st.commit().unwrap();
        assert!(st.drain().is_ok());
        assert!(st.commit().is_err());
        assert!(st.rollback().is_err());
    }
}
