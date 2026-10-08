//! Encrypted backups (contract v1.22 §22): key setup, unlock, rotation and files across devices,
//! through the public API only.

use std::fs;
use std::path::{Path, PathBuf};

use risime_mls::backup::{BackupError, SecretKind, file_info};
use risime_mls::{Client, DeviceId, TestAttestor};

const USER: &str = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3";
const USER2: &str = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e";
const PASSPHRASE: &str = "correct horse battery staple";

fn client(user: &str, device: &str) -> Client {
    let a = TestAttestor::from_seed([7; 32]);
    Client::in_memory(DeviceId::new(user, device).unwrap(), a.anchors()).unwrap()
}

fn tmpdir(tag: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("risime-bk-it-{tag}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&d);
    fs::create_dir_all(&d).unwrap();
    d
}

fn write(c: &Client, path: &Path, backup_id: &str, record: Option<&str>, data: &[u8]) {
    let mut w = c
        .backup_writer(
            USER,
            backup_id,
            "2026-10-08T02:00:00.000Z",
            "0.3.0",
            record,
            path,
        )
        .unwrap();
    w.write(data).unwrap();
    w.finish().unwrap();
}

/// A tiny valid raw DEFLATE stream (one stored block) carrying `s`.
fn stored(s: &[u8]) -> Vec<u8> {
    let n = s.len() as u16;
    let mut v = vec![0x01];
    v.extend_from_slice(&n.to_le_bytes());
    v.extend_from_slice(&(!n).to_le_bytes());
    v.extend_from_slice(s);
    v
}

fn read(c: &Client, path: &Path) -> Result<Vec<u8>, BackupError> {
    let mut r = c.backup_reader(USER, path, None, None)?;
    r.verify()?;
    let mut out = Vec::new();
    loop {
        let b = r.read()?;
        if b.is_empty() {
            return Ok(out);
        }
        out.extend_from_slice(&b);
    }
}

#[test]
fn setup_passphrase_and_restore_on_a_new_device() {
    let dir = tmpdir("restore");
    let phone = client(USER, "d1");
    let s = phone.backup_setup(USER).unwrap();
    assert_eq!(s.recovery_key.len(), 34);
    // Setup again reuses the pair (no new key, no Argon2id).
    assert_eq!(phone.backup_setup(USER).unwrap(), s);
    assert!(matches!(
        phone.backup_add_passphrase(USER, "too short"),
        Err(BackupError::WeakPassphrase(_))
    ));
    let rec = phone.backup_add_passphrase(USER, PASSPHRASE).unwrap();
    assert_eq!(
        phone.backup_key_record().unwrap().as_deref(),
        Some(rec.as_str())
    );
    let v: serde_json::Value = serde_json::from_str(&rec).unwrap();
    assert_eq!(v["wraps"].as_array().unwrap().len(), 2);
    assert!(v.get("updated_at").is_none());
    let path = dir.join("b.risimebk");
    write(
        &phone,
        &path,
        "6f1e2d3c-4b5a-4968-8776-a5b4c3d2e1f0",
        Some(&rec),
        &stored(b"chats"),
    );

    // A reinstall: unlock the record with the passphrase (NFKC, trimmed), then restore.
    let fresh = client(USER, "d2");
    assert!(matches!(read(&fresh, &path), Err(BackupError::NoKey(_))));
    for (secret, kind, want) in [
        (
            "correct horse battery stapler",
            SecretKind::Passphrase,
            BackupError::WrongKey,
        ),
        (
            "   ",
            SecretKind::Passphrase,
            BackupError::Malformed("empty passphrase".into()),
        ),
        (
            &s.recovery_key[..33],
            SecretKind::RecoveryKey,
            BackupError::Malformed(String::new()),
        ),
    ] {
        let e = fresh
            .backup_unlock(USER, &rec, secret, kind, true)
            .unwrap_err();
        assert_eq!(
            std::mem::discriminant(&e),
            std::mem::discriminant(&want),
            "{e}"
        );
    }
    let mut typo: Vec<char> = s.recovery_key.chars().collect();
    typo[0] = if typo[0] == 'A' { 'B' } else { 'A' };
    let typo: String = typo.into_iter().collect();
    assert_eq!(
        fresh.backup_unlock(USER, &rec, &typo, SecretKind::RecoveryKey, true),
        Err(BackupError::Typo)
    );
    fresh
        .backup_unlock(
            USER,
            &rec,
            &format!("\u{3000}{PASSPHRASE} "),
            SecretKind::Passphrase,
            true,
        )
        .unwrap();
    assert_eq!(read(&fresh, &path).unwrap(), stored(b"chats"));
    // A passphrase unlock can't know R.
    assert!(fresh.backup_recovery_key().unwrap().is_none());
    // Setup there makes a new R for the same BK and keeps the passphrase wrap.
    let s2 = fresh.backup_setup(USER).unwrap();
    assert_ne!(s2.recovery_key, s.recovery_key);
    let v2: serde_json::Value = serde_json::from_str(&s2.key_record).unwrap();
    assert_eq!(v2["bk_id"], v["bk_id"]);
    assert_eq!(v2["wraps"][1], v["wraps"][1]);

    // The file's own header carries the record: a recovery-key-only restore.
    let other = client(USER, "d3");
    let info = file_info(&path).unwrap();
    other
        .backup_unlock(
            USER,
            info.key_record.as_ref().unwrap(),
            &s.recovery_key.to_lowercase(),
            SecretKind::RecoveryKey,
            true,
        )
        .unwrap();
    assert_eq!(
        *other.backup_recovery_key().unwrap().unwrap(),
        *s.recovery_key
    );
    assert_eq!(read(&other, &path).unwrap(), stored(b"chats"));
}

#[test]
fn a_record_of_another_user_does_not_open() {
    let a = client(USER, "d1");
    let s = a.backup_setup(USER).unwrap();
    let b = client(USER2, "d1");
    // The key check passes (same secret), the AAD's user_id doesn't.
    assert!(matches!(
        b.backup_unlock(
            USER2,
            &s.key_record,
            &s.recovery_key,
            SecretKind::RecoveryKey,
            true
        ),
        Err(BackupError::Integrity(_))
    ));
    assert!(b.backup_key_ids().unwrap().current.is_none());
    // And a device can't act for another user.
    assert!(matches!(
        b.backup_setup(USER),
        Err(BackupError::Malformed(_))
    ));
}

#[test]
fn rotate_recovery_key() {
    let dir = tmpdir("rotate");
    let c = client(USER, "d1");
    let s1 = c.backup_setup(USER).unwrap();
    c.backup_add_passphrase(USER, PASSPHRASE).unwrap();
    let p_old = dir.join("old");
    write(
        &c,
        &p_old,
        "6f1e2d3c-4b5a-4968-8776-a5b4c3d2e1f0",
        None,
        &stored(b"old"),
    );
    let old_rec = c.backup_key_record().unwrap().unwrap();
    let s2 = c.backup_rotate_recovery_key(USER).unwrap();
    assert_ne!(s1.recovery_key, s2.recovery_key);
    assert_eq!(*c.backup_recovery_key().unwrap().unwrap(), *s2.recovery_key);
    let (v1, v2): (serde_json::Value, serde_json::Value) = (
        serde_json::from_str(&old_rec).unwrap(),
        serde_json::from_str(&s2.key_record).unwrap(),
    );
    assert_eq!(v1["bk_id"], v2["bk_id"], "same BK");
    assert_ne!(v1["wraps"][0], v2["wraps"][0]);
    assert_eq!(v1["wraps"][1], v2["wraps"][1], "passphrase wrap kept");
    let d = client(USER, "d2");
    // The new record opens with the new key only; the old record (older files) with the old.
    assert_eq!(
        d.backup_unlock(
            USER,
            &s2.key_record,
            &s1.recovery_key,
            SecretKind::RecoveryKey,
            true
        ),
        Err(BackupError::WrongKey)
    );
    d.backup_unlock(
        USER,
        &old_rec,
        &s1.recovery_key,
        SecretKind::RecoveryKey,
        true,
    )
    .unwrap();
    assert_eq!(read(&d, &p_old).unwrap(), stored(b"old"));
    d.backup_unlock(
        USER,
        &s2.key_record,
        &s2.recovery_key,
        SecretKind::RecoveryKey,
        true,
    )
    .unwrap();
    assert_eq!(*d.backup_recovery_key().unwrap().unwrap(), *s2.recovery_key);
    // A passphrase unlock with the same recovery wrap keeps the stored R.
    d.backup_unlock(
        USER,
        &s2.key_record,
        PASSPHRASE,
        SecretKind::Passphrase,
        true,
    )
    .unwrap();
    assert_eq!(*d.backup_recovery_key().unwrap().unwrap(), *s2.recovery_key);
    // ...but not after another device rotated it.
    let s3 = c.backup_rotate_recovery_key(USER).unwrap();
    d.backup_unlock(
        USER,
        &s3.key_record,
        PASSPHRASE,
        SecretKind::Passphrase,
        true,
    )
    .unwrap();
    assert!(d.backup_recovery_key().unwrap().is_none());
    // No key: rotate and add_passphrase need one.
    let e = client(USER, "d3");
    assert!(matches!(
        e.backup_rotate_recovery_key(USER),
        Err(BackupError::NoKey(_))
    ));
    assert!(matches!(
        e.backup_add_passphrase(USER, PASSPHRASE),
        Err(BackupError::NoKey(_))
    ));
}

/// Argon2id cost (`cargo test --release --test backup_v122 -- --nocapture`): `backup_setup` runs
/// it once.
#[test]
fn argon2id_timing() {
    let c = client(USER, "d1");
    let t = std::time::Instant::now();
    c.backup_setup(USER).unwrap();
    eprintln!(
        "backup_setup (one Argon2id, m=64 MiB t=3 p=1): {:?}",
        t.elapsed()
    );
}
