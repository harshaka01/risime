//! Encrypted backups (contract v1.22 §22) across the FFI surface, as Android calls them, and the
//! check that `BK`, `KEK` and `DEK` are not reachable from any exported backup function.

use std::fs;
use std::sync::Arc;

use uniffi_risime::{
    BackupPassphraseFloor, BackupSecretKind, InMemoryKvStore, MlsClient, RisiBackupError,
    TestAttestor, backup_file_info, backup_limits, backup_normalize_recovery_key,
    backup_passphrase_floor, backup_vectors_check,
};

const USER: &str = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3";

fn client(device: &str) -> Arc<MlsClient> {
    let a = TestAttestor::from_seed(vec![9; 32]).unwrap();
    MlsClient::open(
        InMemoryKvStore::new(),
        USER.into(),
        device.into(),
        vec![a.public_jwk()],
    )
    .unwrap()
}

/// One stored DEFLATE block carrying `s`.
fn stored(s: &[u8]) -> Vec<u8> {
    let n = s.len() as u16;
    let mut v = vec![0x01];
    v.extend_from_slice(&n.to_le_bytes());
    v.extend_from_slice(&(!n).to_le_bytes());
    v.extend_from_slice(s);
    v
}

#[test]
fn backup_round_trip_crosses_the_ffi() {
    let dir = std::env::temp_dir().join(format!("risime-backup-ffi-{}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();
    let path = dir.join("b.risimebk").to_str().unwrap().to_string();

    let phone = client("d1");
    let setup = phone.backup_setup(USER.into()).unwrap();
    assert!(!format!("{setup:?}").contains(&setup.recovery_key));
    assert_eq!(
        backup_normalize_recovery_key(setup.recovery_key.to_lowercase()).unwrap(),
        setup.recovery_key
    );
    assert_eq!(
        backup_passphrase_floor("short".into(), None),
        BackupPassphraseFloor::TooShort
    );
    let rec = phone
        .backup_add_passphrase(USER.into(), "a long enough passphrase".into())
        .unwrap();
    let w = phone
        .backup_writer(
            USER.into(),
            "6f1e2d3c-4b5a-4968-8776-a5b4c3d2e1f0".into(),
            "2026-10-08T02:00:00.000Z".into(),
            "0.3.0".into(),
            Some(rec.clone()),
            path.clone(),
        )
        .unwrap();
    let d = stored(b"{\"v\":1,\"type\":\"backup\"}\n");
    w.write(d[..5].to_vec()).unwrap();
    w.write(d[5..].to_vec()).unwrap();
    let out = w.finish().unwrap();
    assert_eq!(out.size, fs::metadata(&path).unwrap().len());
    assert_eq!(out.sha256.len(), 32);
    assert!(matches!(
        w.write(vec![1]),
        Err(RisiBackupError::Malformed(_))
    ));

    let info = backup_file_info(path.clone()).unwrap();
    assert_eq!(info.bk_id, out.bk_id);
    assert_eq!(info.key_record.as_deref(), Some(rec.as_str()));

    let fresh = client("d2");
    assert!(matches!(
        fresh.backup_reader(USER.into(), path.clone(), None, None),
        Err(RisiBackupError::NoKey(_))
    ));
    assert!(matches!(
        fresh.backup_unlock(
            USER.into(),
            rec.clone(),
            "wrong passphrase!!".into(),
            BackupSecretKind::Passphrase,
            true
        ),
        Err(RisiBackupError::WrongKey)
    ));
    let last = if setup.recovery_key.ends_with('0') {
        "1"
    } else {
        "0"
    };
    let bad = format!("{}{last}", &setup.recovery_key[..33]);
    assert!(matches!(
        fresh.backup_unlock(
            USER.into(),
            rec.clone(),
            bad,
            BackupSecretKind::RecoveryKey,
            true
        ),
        Err(RisiBackupError::Typo)
    ));
    fresh
        .backup_unlock(
            USER.into(),
            rec,
            setup.recovery_key.clone(),
            BackupSecretKind::RecoveryKey,
            true,
        )
        .unwrap();
    assert_eq!(
        fresh.backup_recovery_key().unwrap(),
        Some(setup.recovery_key.clone())
    );
    let r = fresh
        .backup_reader(
            USER.into(),
            path.clone(),
            Some(info.backup_id.clone()),
            Some(info.bk_id.clone()),
        )
        .unwrap();
    assert!(matches!(r.read(), Err(RisiBackupError::Malformed(_))));
    let v = r.verify().unwrap();
    assert_eq!(v.data_size, d.len() as u64);
    let mut got = Vec::new();
    loop {
        let b = r.read().unwrap();
        if b.is_empty() {
            break;
        }
        got.extend(b);
    }
    assert_eq!(got, d);

    // A tampered header byte: the verify pass fails, nothing is read.
    let mut bytes = fs::read(&path).unwrap();
    let i = bytes.windows(4).position(|w| w == b"2026").unwrap();
    bytes[i] = b'3';
    fs::write(&path, &bytes).unwrap();
    let r = fresh.backup_reader(USER.into(), path, None, None).unwrap();
    assert!(matches!(r.verify(), Err(RisiBackupError::Integrity(_))));
    assert!(r.read().is_err());

    let ids = fresh.backup_key_ids().unwrap();
    assert_eq!(ids.current.as_deref(), Some(out.bk_id.as_str()));
    fresh.backup_forget().unwrap();
    assert!(fresh.backup_key_ids().unwrap().current.is_none());
    assert_eq!(backup_limits().argon2_m_kib, 65_536);
    fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn contract_vectors_pass_through_the_ffi() {
    let json = include_str!("../../../contract/v1/backup_vectors.json").to_string();
    assert_eq!(
        backup_vectors_check(json).unwrap(),
        44,
        "14 positive + 30 negative"
    );
}

/// §22.10: `BK`, `KEK` and `DEK` never cross the FFI. Every backup export is listed with what it
/// returns; a new export (or field) fails this test until it is reviewed and added.
#[test]
fn backup_keys_are_not_reachable_from_the_ffi() {
    let src = include_str!("../src/lib.rs");
    let section = &src[src.find("// Encrypted backups (contract v1.22").unwrap()..];
    let mut exported: Vec<&str> = section
        .lines()
        .filter_map(|l| {
            l.trim_start()
                .strip_prefix("pub fn ")
                .map(|r| r.split('(').next().unwrap())
        })
        .collect();
    exported.sort_unstable();
    assert_eq!(
        exported,
        [
            "backup_add_passphrase",         // -> key record JSON (wraps only)
            "backup_drop_key",               // -> ()
            "backup_file_info",              // -> header fields
            "backup_forget",                 // -> ()
            "backup_key_ids",                // -> bk_ids
            "backup_key_record",             // -> key record JSON
            "backup_limits",                 // -> constants
            "backup_normalize_recovery_key", // -> the typed key, normalised
            "backup_passphrase_floor",       // -> a verdict
            "backup_reader",                 // -> BackupReader
            "backup_recovery_key",           // -> R's display form only
            "backup_rotate_recovery_key",    // -> display form + record
            "backup_setup",                  // -> display form + record
            "backup_unlock",                 // -> bk_id
            "backup_vectors_check",          // -> a count
            "backup_writer",                 // -> BackupWriter
            "finish",                        // -> size, sha256, ids
            "info",                          // -> header fields
            "read",                          // -> verified DEFLATE bytes
            "verify",                        // -> sizes
            "write",                         // -> ()
        ]
    );
    let fields: Vec<&str> = section
        .lines()
        .filter_map(|l| l.trim_start().strip_prefix("pub "))
        .filter(|l| !l.starts_with("fn ") && !l.starts_with("struct ") && !l.starts_with("enum "))
        .map(|l| l.split(':').next().unwrap())
        .collect();
    for f in &fields {
        assert!(
            !["bk", "kek", "dek", "ks", "kw", "key", "secret", "r"].contains(f),
            "a backup record exposes {f}"
        );
    }
}
