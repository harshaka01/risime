//! Media encryption (`A256GCM-S64K`, contract v1.11) through the public API: round trips, every
//! committed vector, and the negatives a hostile server or a broken download can produce.

use std::fs;
use std::path::{Path, PathBuf};

use risime_mls::media::{
    self, MAX_MEDIA_PLAIN_SIZE, MEDIA_ALG, MEDIA_CIPHER_SEGMENT, MediaError, MediaRef, SealedMedia,
};
use sha2::{Digest, Sha256};

fn tmpdir(tag: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("risime-media-it-{tag}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&d);
    fs::create_dir_all(&d).unwrap();
    d
}

fn pattern(len: usize) -> Vec<u8> {
    (0..len).map(|i| (i * 31 + 7) as u8).collect()
}

fn r<'a>(s: &'a SealedMedia, cipher_size: u64, sha: &'a [u8]) -> MediaRef<'a> {
    MediaRef {
        key: &s.key,
        alg: &s.alg,
        plain_size: s.plain_size,
        cipher_size,
        sha256: sha,
    }
}

fn seal(dir: &Path, plain: &[u8]) -> (SealedMedia, Vec<u8>) {
    let src = dir.join("plain");
    let dst = dir.join("blob");
    fs::write(&src, plain).unwrap();
    let s = media::encrypt_file(&src, &dst).unwrap();
    (s, fs::read(&dst).unwrap())
}

/// Opens `cipher` with the envelope of `s`, but with the size and SHA-256 of `cipher` itself
/// (the server can't forge the envelope; this isolates the AEAD/format checks).
fn open_as(dir: &Path, s: &SealedMedia, cipher: &[u8]) -> Result<Vec<u8>, MediaError> {
    let p = dir.join("tampered");
    fs::write(&p, cipher).unwrap();
    let sha = Sha256::digest(cipher);
    media::decrypt_file(&p, &r(s, cipher.len() as u64, &sha)).map(|z| z.to_vec())
}

#[test]
fn round_trip_at_the_edges() {
    let dir = tmpdir("edges");
    for len in [
        1usize, 2, 100, 65_000, 65_536, 65_537, 131_072, 195_000, 1_300_000,
    ] {
        let plain = pattern(len);
        let (s, blob) = seal(&dir, &plain);
        assert_eq!(s.alg, MEDIA_ALG);
        assert_eq!(s.key.len(), 32);
        assert_eq!(s.plain_size, len as u64);
        assert_eq!(Some(s.cipher_size), media::cipher_size_for(len as u64));
        assert_eq!(blob.len() as u64, s.cipher_size);
        assert_eq!(s.sha256, <[u8; 32]>::from(Sha256::digest(&blob)));
        let rf = r(&s, s.cipher_size, &s.sha256);
        assert_eq!(
            &media::decrypt_file(&dir.join("blob"), &rf).unwrap()[..],
            &plain[..]
        );
        media::decrypt_file_to_file(&dir.join("blob"), &dir.join("out"), &rf).unwrap();
        assert_eq!(fs::read(dir.join("out")).unwrap(), plain);
        assert_eq!(
            media::verified_prefix(&dir.join("blob"), &s.key, MEDIA_ALG, s.cipher_size),
            Ok(s.cipher_size)
        );
    }
    assert_eq!(media::cipher_size_for(1_300_000), Some(1_311_040));
    fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn every_call_uses_a_fresh_key() {
    let dir = tmpdir("fresh");
    let (a, blob_a) = seal(&dir, b"same picture");
    let (b, blob_b) = seal(&dir, b"same picture");
    assert_ne!(a.key, b.key);
    assert_ne!(blob_a, blob_b);
    assert_ne!(a.sha256, b.sha256);
    fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn tampering_is_rejected() {
    let dir = tmpdir("tamper");
    let (s, blob) = seal(&dir, &pattern(200_000)); // 4 segments (pads to 200 704)
    let cs = MEDIA_CIPHER_SEGMENT as usize;
    assert_eq!(blob.len().div_ceil(cs), 4);
    let seg = |i: usize| &blob[i * cs..((i + 1) * cs).min(blob.len())];

    // A flipped bit anywhere: segment body, tag, last byte.
    for pos in [0, 70_000, cs - 1, blob.len() - 1] {
        let mut b = blob.clone();
        b[pos] ^= 0x04;
        assert!(
            matches!(open_as(&dir, &s, &b), Err(MediaError::Integrity(_))),
            "{pos}"
        );
    }
    // Reordered segments (sizes unchanged).
    let swapped = [seg(1), seg(0), seg(2), seg(3)].concat();
    assert!(matches!(
        open_as(&dir, &s, &swapped),
        Err(MediaError::Integrity(_))
    ));
    // Missing final segment: the envelope's plain_size no longer fits the blob.
    let no_final = [seg(0), seg(1), seg(2)].concat();
    assert!(matches!(
        open_as(&dir, &s, &no_final),
        Err(MediaError::Format(_))
    ));
    // ...and with a plain_size that does fit (196 608 = 3 full segments), the final flag fails.
    let p = dir.join("nofinal");
    fs::write(&p, &no_final).unwrap();
    let sha = Sha256::digest(&no_final);
    let rf = MediaRef {
        plain_size: 196_608,
        ..r(&s, no_final.len() as u64, &sha)
    };
    assert!(matches!(
        media::decrypt_file(&p, &rf),
        Err(MediaError::Integrity(_))
    ));
    // A segment duplicated in place of another.
    let dup = [seg(0), seg(0), seg(2), seg(3)].concat();
    assert!(matches!(
        open_as(&dir, &s, &dup),
        Err(MediaError::Integrity(_))
    ));
    // Truncation inside a segment.
    let mut short = blob.clone();
    short.truncate(blob.len() - 100);
    assert!(open_as(&dir, &s, &short).is_err());

    // The real envelope against a blob that is short, long or swapped: size / sha checks.
    let real = r(&s, s.cipher_size, &s.sha256);
    for b in [
        &blob[..blob.len() - 1],
        &[&blob[..], b"x"].concat()[..],
        &swapped[..],
    ] {
        fs::write(&p, b).unwrap();
        assert!(matches!(
            media::decrypt_file(&p, &real),
            Err(MediaError::Integrity(_))
        ));
    }
    fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn wrong_key_and_bad_envelopes() {
    let dir = tmpdir("envelope");
    let (s, _) = seal(&dir, &pattern(5000));
    let blob = dir.join("blob");
    let good = r(&s, s.cipher_size, &s.sha256);
    let other = [7u8; 32];
    assert!(matches!(
        media::decrypt_file(
            &blob,
            &MediaRef {
                key: &other,
                ..good
            }
        ),
        Err(MediaError::Integrity(_))
    ));
    assert!(matches!(
        media::decrypt_file(
            &blob,
            &MediaRef {
                key: &other[..31],
                ..good
            }
        ),
        Err(MediaError::Format(_))
    ));
    for alg in ["A256GCM", "a256gcm-s64k", ""] {
        assert!(matches!(
            media::decrypt_file(&blob, &MediaRef { alg, ..good }),
            Err(MediaError::Unsupported(_))
        ));
    }
    assert!(matches!(
        media::decrypt_file(
            &blob,
            &MediaRef {
                sha256: &s.sha256[..31],
                ..good
            }
        ),
        Err(MediaError::Format(_))
    ));
    assert!(matches!(
        media::decrypt_file(
            &blob,
            &MediaRef {
                plain_size: 0,
                ..good
            }
        ),
        Err(MediaError::Format(_))
    ));
    assert!(matches!(
        media::decrypt_file(
            &blob,
            &MediaRef {
                plain_size: s.plain_size + 999,
                ..good
            }
        ),
        Err(MediaError::Format(_))
    ));
    let bad_sha = [0u8; 32];
    assert!(matches!(
        media::decrypt_file(
            &blob,
            &MediaRef {
                sha256: &bad_sha,
                ..good
            }
        ),
        Err(MediaError::Integrity(_))
    ));
    // Nothing is written on failure.
    let out = dir.join("out.jpg");
    assert!(
        media::decrypt_file_to_file(
            &blob,
            &out,
            &MediaRef {
                sha256: &bad_sha,
                ..good
            }
        )
        .is_err()
    );
    assert!(!out.exists());
    assert!(!dir.join("out.jpg.risime-tmp").exists());
    fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn oversize_and_empty_inputs_are_refused() {
    let dir = tmpdir("oversize");
    let src = dir.join("big");
    let f = fs::File::create(&src).unwrap();
    f.set_len(MAX_MEDIA_PLAIN_SIZE + 1).unwrap();
    let dst = dir.join("blob");
    assert!(matches!(
        media::encrypt_file(&src, &dst),
        Err(MediaError::TooLarge(_))
    ));
    assert!(!dst.exists());
    fs::write(&src, b"").unwrap();
    assert!(matches!(
        media::encrypt_file(&src, &dst),
        Err(MediaError::Format(_))
    ));
    assert!(matches!(
        media::encrypt_file(&dir.join("missing"), &dst),
        Err(MediaError::Io(_))
    ));
    // Receivers refuse a blob over the cap before reading it.
    let k = [1u8; 32];
    let sha = [0u8; 32];
    let rf = MediaRef {
        key: &k,
        alg: MEDIA_ALG,
        plain_size: MAX_MEDIA_PLAIN_SIZE + 1,
        cipher_size: media::cipher_size_for(MAX_MEDIA_PLAIN_SIZE + 1).unwrap(),
        sha256: &sha,
    };
    assert!(matches!(
        media::decrypt_file(&src, &rf),
        Err(MediaError::TooLarge(_))
    ));
    fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn max_size_round_trip() {
    let dir = tmpdir("max");
    let plain = pattern(MAX_MEDIA_PLAIN_SIZE as usize);
    let (s, _) = seal(&dir, &plain);
    assert_eq!(s.cipher_size, 16_519_104);
    let out = dir.join("out");
    media::decrypt_file_to_file(&dir.join("blob"), &out, &r(&s, s.cipher_size, &s.sha256)).unwrap();
    assert!(fs::read(&out).unwrap() == plain);
    fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn verified_prefix_for_range_resume() {
    let dir = tmpdir("prefix");
    let (s, blob) = seal(&dir, &pattern(300_000)); // pads to 303 104: 5 segments
    let cs = MEDIA_CIPHER_SEGMENT;
    let part = dir.join("blob.part");
    let vp = |bytes: &[u8]| {
        fs::write(&part, bytes).unwrap();
        media::verified_prefix(&part, &s.key, MEDIA_ALG, s.cipher_size).unwrap()
    };
    assert_eq!(
        media::verified_prefix(&dir.join("nope"), &s.key, MEDIA_ALG, s.cipher_size),
        Ok(0)
    );
    assert_eq!(vp(&blob[..10]), 0);
    assert_eq!(vp(&blob[..cs as usize]), cs);
    assert_eq!(vp(&blob[..(2 * cs + 5) as usize]), 2 * cs);
    assert_eq!(vp(&blob[..blob.len() - 1]), 4 * cs);
    assert_eq!(vp(&blob), s.cipher_size);
    // A bad spliced range in segment 2 stops the prefix there.
    let mut bad = blob.clone();
    bad[(2 * cs + 3) as usize] ^= 1;
    assert_eq!(vp(&bad), 2 * cs);
    // A wrong key verifies nothing.
    fs::write(&part, &blob).unwrap();
    assert_eq!(
        media::verified_prefix(&part, &[9u8; 32], MEDIA_ALG, s.cipher_size),
        Ok(0)
    );
    assert!(matches!(
        media::verified_prefix(&part, &s.key, "A256GCM", s.cipher_size),
        Err(MediaError::Unsupported(_))
    ));
    fs::remove_dir_all(&dir).unwrap();
}

// -------------------------------------------------------------------------------------------
// Committed vectors (tests/media_vectors.json), as every client runs them.
// -------------------------------------------------------------------------------------------

fn unhex(s: &str) -> Vec<u8> {
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}

#[test]
fn committed_vectors_decrypt_or_fail_as_expected() {
    let dir = tmpdir("vectors");
    let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/media_vectors.json");
    let v: serde_json::Value = serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap();
    assert_eq!(v["alg"], MEDIA_ALG);
    let blob = dir.join("blob");
    let pos = v["positive"].as_array().unwrap();
    assert_eq!(pos.len(), 5);
    for t in pos {
        let cipher = unhex(t["cipher"].as_str().unwrap());
        let key = unhex(t["key"].as_str().unwrap());
        let sha = unhex(t["sha256"].as_str().unwrap());
        let plain_size = t["plain_size"].as_u64().unwrap();
        assert_eq!(media::padme(plain_size), t["padded_size"].as_u64().unwrap());
        assert_eq!(
            media::cipher_size_for(plain_size),
            Some(cipher.len() as u64)
        );
        fs::write(&blob, &cipher).unwrap();
        let rf = MediaRef {
            key: &key,
            alg: MEDIA_ALG,
            plain_size,
            cipher_size: cipher.len() as u64,
            sha256: &sha,
        };
        let plain = media::decrypt_file(&blob, &rf).unwrap();
        assert_eq!(
            &plain[..],
            &unhex(t["plain"].as_str().unwrap())[..],
            "{}",
            t["name"]
        );
    }
    let neg = v["negative"].as_array().unwrap();
    assert_eq!(neg.len(), 9);
    for t in neg {
        let cipher = unhex(t["cipher"].as_str().unwrap());
        let key = unhex(t["key"].as_str().unwrap());
        let sha = unhex(t["sha256"].as_str().unwrap());
        fs::write(&blob, &cipher).unwrap();
        let rf = MediaRef {
            key: &key,
            alg: MEDIA_ALG,
            plain_size: t["plain_size"].as_u64().unwrap(),
            cipher_size: t["cipher_size"].as_u64().unwrap(),
            sha256: &sha,
        };
        let got = media::decrypt_file(&blob, &rf).map(|_| ());
        let ok = match t["expect"].as_str().unwrap() {
            "Integrity" => matches!(got, Err(MediaError::Integrity(_))),
            "Format" => matches!(got, Err(MediaError::Format(_))),
            e => panic!("unknown expectation {e}"),
        };
        assert!(ok, "{}: expected {}, got {got:?}", t["name"], t["expect"]);
    }
    fs::remove_dir_all(&dir).unwrap();
}
