//! The MLS `authenticated_data` of a `delete` control (contract v1.12 §15.3, decision 047) and of
//! the `history_request` / `history_share` controls (v1.15 §17.3, the `'H'` form).
//!
//! Canonical binary encoding: `0x01 0x44` (`'D'`), then the targets as 16-byte UUIDs, **sorted
//! ascending by bytes, distinct**, 1..=[`MAX_DELETE_TARGETS`] of them (at most 1 602 bytes). Every
//! other application message carries an **empty** `authenticated_data`.
//!
//! The AAD travels in cleartext in the PrivateMessage but is covered by the sender's signature and
//! the content AEAD: the core reports it on decrypt ([`crate::ApplicationDetails`]), and the
//! receiver checks that its set equals the envelope's and the event's `targets`.

use crate::{MlsError, Result};

/// The two prefix bytes of a delete control's AAD: version 1, `'D'`.
pub const DELETE_AAD_PREFIX: [u8; 2] = [0x01, b'D'];

/// At most this many targets per `msg:delete` (§15.2).
pub const MAX_DELETE_TARGETS: usize = 100;

fn hex_val(c: u8) -> Option<u8> {
    match c {
        b'0'..=b'9' => Some(c - b'0'),
        b'a'..=b'f' => Some(c - b'a' + 10),
        b'A'..=b'F' => Some(c - b'A' + 10),
        _ => None,
    }
}

/// Parse a UUID string `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` (either case) into its 16 bytes.
pub fn parse_uuid(s: &str) -> Result<[u8; 16]> {
    let b = s.as_bytes();
    let bad = || MlsError::Malformed(format!("not a UUID: {s:?}"));
    if b.len() != 36 || [8, 13, 18, 23].iter().any(|&i| b[i] != b'-') {
        return Err(bad());
    }
    let mut out = [0u8; 16];
    let mut n = 0;
    let mut hi: Option<u8> = None;
    for (i, &c) in b.iter().enumerate() {
        if [8, 13, 18, 23].contains(&i) {
            continue;
        }
        let v = hex_val(c).ok_or_else(bad)?;
        match hi.take() {
            None => hi = Some(v),
            Some(h) => {
                out[n] = (h << 4) | v;
                n += 1;
            }
        }
    }
    Ok(out)
}

/// The canonical lowercase string of a 16-byte UUID.
pub fn format_uuid(u: &[u8; 16]) -> String {
    let mut s = String::with_capacity(36);
    for (i, byte) in u.iter().enumerate() {
        if [4, 6, 8, 10].contains(&i) {
            s.push('-');
        }
        s.push_str(&format!("{byte:02x}"));
    }
    s
}

/// Encode a delete control's targets (UUID strings, either case, any order) as the canonical AAD.
/// Duplicates (case-insensitive), an empty list or more than [`MAX_DELETE_TARGETS`] are
/// [`MlsError::Malformed`].
pub fn encode_delete_aad(targets: &[String]) -> Result<Vec<u8>> {
    if targets.is_empty() || targets.len() > MAX_DELETE_TARGETS {
        return Err(MlsError::Malformed(format!(
            "a delete names 1..={MAX_DELETE_TARGETS} targets, not {}",
            targets.len()
        )));
    }
    let mut ids = targets
        .iter()
        .map(|t| parse_uuid(t))
        .collect::<Result<Vec<_>>>()?;
    ids.sort_unstable();
    if ids.windows(2).any(|w| w[0] == w[1]) {
        return Err(MlsError::Malformed("duplicate delete target".into()));
    }
    let mut out = Vec::with_capacity(2 + 16 * ids.len());
    out.extend_from_slice(&DELETE_AAD_PREFIX);
    for id in &ids {
        out.extend_from_slice(id);
    }
    Ok(out)
}

/// Decode a delete control's AAD into its targets (lowercase canonical UUID strings, ascending).
/// Anything but the exact canonical encoding (wrong prefix, a partial UUID, unsorted or duplicate
/// targets, 0 or more than [`MAX_DELETE_TARGETS`]) is [`MlsError::Malformed`]: drop the control.
/// An `'H'` AAD is rejected here (wrong prefix), and a `'D'` AAD by [`decode_history_aad`].
pub fn decode_delete_aad(aad: &[u8]) -> Result<Vec<String>> {
    let bad = |why: &str| MlsError::Malformed(format!("delete aad: {why}"));
    let body = aad
        .strip_prefix(&DELETE_AAD_PREFIX[..])
        .ok_or_else(|| bad("wrong prefix"))?;
    if body.is_empty() || body.len() % 16 != 0 {
        return Err(bad("length is not a positive multiple of 16"));
    }
    let ids: &[[u8; 16]] = body.as_chunks::<16>().0;
    if ids.len() > MAX_DELETE_TARGETS {
        return Err(bad("too many targets"));
    }
    if ids.windows(2).any(|w| w[0] >= w[1]) {
        return Err(bad("targets not strictly ascending"));
    }
    Ok(ids.iter().map(format_uuid).collect())
}

/// The two prefix bytes of a history control's AAD: version 1, `'H'` (contract v1.15 §17.3).
pub const HISTORY_AAD_PREFIX: [u8; 2] = [0x01, b'H'];

/// A history AAD is exactly the prefix and one 16-byte `request_id`.
pub const HISTORY_AAD_LEN: usize = 18;

/// The canonical AAD of a `history_request` / `history_share` envelope: `0x01 0x48` ‖ the
/// `request_id` as 16 raw bytes (either case in, the bytes are the same).
pub fn encode_history_aad(request_id: &str) -> Result<Vec<u8>> {
    let id = parse_uuid(request_id)?;
    let mut out = Vec::with_capacity(HISTORY_AAD_LEN);
    out.extend_from_slice(&HISTORY_AAD_PREFIX);
    out.extend_from_slice(&id);
    Ok(out)
}

/// The `request_id` (lowercase canonical UUID) of a history AAD. Anything but exactly 18 bytes
/// with the `0x01 0x48` prefix is [`MlsError::Malformed`]: drop the envelope.
pub fn decode_history_aad(aad: &[u8]) -> Result<String> {
    let bad = |why: &str| MlsError::Malformed(format!("history aad: {why}"));
    if aad.len() != HISTORY_AAD_LEN {
        return Err(bad("not 18 bytes"));
    }
    let body = aad
        .strip_prefix(&HISTORY_AAD_PREFIX[..])
        .ok_or_else(|| bad("wrong prefix"))?;
    let id: [u8; 16] = body.try_into().map_err(|_| bad("not 18 bytes"))?;
    Ok(format_uuid(&id))
}

/// Whether `aad` is in the canonical `'H'` form.
pub fn is_history_aad(aad: &[u8]) -> bool {
    decode_history_aad(aad).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    const A: &str = "c1a2b3e1-a0b1-11f0-8000-0242ac120002";
    const B: &str = "c1a2b3f0-a0b1-11f0-8000-0242ac120002";

    #[test]
    fn round_trip_sorts_and_lowercases() {
        let aad = encode_delete_aad(&[B.to_uppercase(), A.into()]).unwrap();
        assert_eq!(aad.len(), 2 + 32);
        assert_eq!(&aad[..2], &[0x01, 0x44]);
        assert_eq!(decode_delete_aad(&aad).unwrap(), vec![A, B]);
        assert_eq!(format_uuid(&parse_uuid(A).unwrap()), A);
    }

    #[test]
    fn rejects_non_canonical() {
        assert!(encode_delete_aad(&[]).is_err());
        assert!(encode_delete_aad(&[A.into(), A.to_uppercase()]).is_err());
        assert!(encode_delete_aad(&["nope".into()]).is_err());
        assert!(encode_delete_aad(&["c1a2b3e1-a0b1-11f0-8000-0242ac12000g".into()]).is_err());
        let many: Vec<String> = (0..101u32)
            .map(|i| format!("{i:08x}-0000-1000-8000-000000000000"))
            .collect();
        assert!(encode_delete_aad(&many).is_err());
        assert_eq!(encode_delete_aad(&many[..100]).unwrap().len(), 1602);

        let good = encode_delete_aad(&[A.into(), B.into()]).unwrap();
        let mut swapped = good[..2].to_vec();
        swapped.extend_from_slice(&good[18..]);
        swapped.extend_from_slice(&good[2..18]);
        assert!(decode_delete_aad(&swapped).is_err());
        let mut dup = good[..18].to_vec();
        dup.extend_from_slice(&good[2..18]);
        assert!(decode_delete_aad(&dup).is_err());
        assert!(decode_delete_aad(&good[..good.len() - 1]).is_err());
        assert!(decode_delete_aad(&[]).is_err());
        assert!(decode_delete_aad(&good[..2]).is_err());
        let mut v2 = good.clone();
        v2[0] = 2;
        assert!(decode_delete_aad(&v2).is_err());
    }

    #[test]
    fn history_aad_round_trip_and_type_swaps() {
        let h = encode_history_aad(&A.to_uppercase()).unwrap();
        assert_eq!(h.len(), HISTORY_AAD_LEN);
        assert_eq!(&h[..2], &[0x01, 0x48]);
        assert_eq!(decode_history_aad(&h).unwrap(), A);
        assert!(is_history_aad(&h));
        assert!(encode_history_aad("nope").is_err());
        // Lengths, prefixes, versions.
        assert!(decode_history_aad(&h[..17]).is_err());
        let mut long = h.clone();
        long.push(0);
        assert!(decode_history_aad(&long).is_err());
        let mut v2 = h.clone();
        v2[0] = 2;
        assert!(decode_history_aad(&v2).is_err());
        // A 'D' AAD with one target is also 18 bytes: neither parser accepts the other's form.
        let d = encode_delete_aad(&[A.into()]).unwrap();
        assert_eq!(d.len(), 18);
        assert!(decode_history_aad(&d).is_err());
        assert!(decode_delete_aad(&h).is_err());
        assert!(!is_history_aad(&[]));
    }
}
