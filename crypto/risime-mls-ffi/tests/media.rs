//! The media API (contract v1.11 §14.3) across the FFI surface: paths in and out, as Android
//! calls it.

use std::fs;

use uniffi_risime::{
    RisiMediaError, media_cipher_size, media_decrypt_file, media_decrypt_file_to_file,
    media_encrypt_file, media_limits, media_verified_prefix,
};

#[test]
fn media_round_trip_and_errors_cross_the_ffi() {
    let dir = std::env::temp_dir().join(format!("risime-media-ffi-{}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();
    let p = |n: &str| dir.join(n).to_str().unwrap().to_string();
    let plain: Vec<u8> = (0..150_000u32).map(|i| (i % 251) as u8).collect();
    fs::write(p("img.jpg"), &plain).unwrap();

    let s = media_encrypt_file(p("img.jpg"), p("img.enc")).unwrap();
    assert_eq!(s.alg, "A256GCM-S64K");
    assert_eq!((s.key.len(), s.sha256.len()), (32, 32));
    assert_eq!(s.plain_size, 150_000);
    assert_eq!(media_cipher_size(150_000), Some(s.cipher_size));
    assert_eq!(fs::metadata(p("img.enc")).unwrap().len(), s.cipher_size);
    assert_eq!(media_cipher_size(0), None);
    let l = media_limits();
    assert_eq!(l.alg, s.alg);
    assert_eq!(l.max_media_cipher_size, 16 << 20);
    assert_eq!(l.max_media_plain_size, 16_515_072);

    let got = media_decrypt_file(
        p("img.enc"),
        s.key.clone(),
        s.alg.clone(),
        s.plain_size,
        s.cipher_size,
        s.sha256.clone(),
    )
    .unwrap();
    assert!(got == plain);
    media_decrypt_file_to_file(
        p("img.enc"),
        p("out.jpg"),
        s.key.clone(),
        s.alg.clone(),
        s.plain_size,
        s.cipher_size,
        s.sha256.clone(),
    )
    .unwrap();
    assert!(fs::read(p("out.jpg")).unwrap() == plain);
    assert_eq!(
        media_verified_prefix(p("img.enc"), s.key.clone(), s.alg.clone(), s.cipher_size).unwrap(),
        s.cipher_size
    );

    // A second encryption of the same file uses a new key.
    let s2 = media_encrypt_file(p("img.jpg"), p("img2.enc")).unwrap();
    assert_ne!(s2.key, s.key);

    let open = |key: Vec<u8>, alg: &str, sha: Vec<u8>| {
        media_decrypt_file(
            p("img.enc"),
            key,
            alg.into(),
            s.plain_size,
            s.cipher_size,
            sha,
        )
    };
    assert!(matches!(
        open(s2.key.clone(), &s.alg, s.sha256.clone()),
        Err(RisiMediaError::Integrity(_))
    ));
    assert!(matches!(
        open(s.key.clone(), "A256GCM", s.sha256.clone()),
        Err(RisiMediaError::Unsupported(_))
    ));
    assert!(matches!(
        open(vec![1; 16], &s.alg, s.sha256.clone()),
        Err(RisiMediaError::Format(_))
    ));
    assert!(matches!(
        open(s.key.clone(), &s.alg, s2.sha256.clone()),
        Err(RisiMediaError::Integrity(_))
    ));
    assert!(matches!(
        media_encrypt_file(p("missing"), p("x.enc")),
        Err(RisiMediaError::Io(_))
    ));
    fs::remove_dir_all(&dir).unwrap();
}
