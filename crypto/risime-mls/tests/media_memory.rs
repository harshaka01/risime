//! Constant memory for media encryption: a max-size (16 MiB) image is encrypted and decrypted
//! file to file while the process's peak RSS (`VmHWM`) grows by well under 1 MiB. Its own test
//! binary (one test), so no other test disturbs the peak. Prints throughput:
//! `cargo test --release --test media_memory -- --nocapture`.

use std::fs;
use std::io::Write;
use std::time::Instant;

use risime_mls::media::{self, MAX_MEDIA_PLAIN_SIZE, MediaRef};

fn vm_hwm_kib() -> Option<u64> {
    let s = fs::read_to_string("/proc/self/status").ok()?;
    let line = s.lines().find(|l| l.starts_with("VmHWM:"))?;
    line.split_whitespace().nth(1)?.parse().ok()
}

#[test]
fn max_size_media_streams_in_constant_memory() {
    let dir = std::env::temp_dir().join(format!("risime-media-mem-{}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();
    let src = dir.join("plain");
    {
        let mut f = fs::File::create(&src).unwrap();
        let chunk: Vec<u8> = (0..64 * 1024).map(|i| (i * 13 + 1) as u8).collect();
        let mut left = MAX_MEDIA_PLAIN_SIZE as usize;
        while left > 0 {
            let n = left.min(chunk.len());
            f.write_all(&chunk[..n]).unwrap();
            left -= n;
        }
    }
    let before = vm_hwm_kib();

    let t = Instant::now();
    let s = media::encrypt_file(&src, &dir.join("blob")).unwrap();
    let enc = t.elapsed();
    let rf = MediaRef {
        key: &s.key,
        alg: &s.alg,
        plain_size: s.plain_size,
        cipher_size: s.cipher_size,
        sha256: &s.sha256,
    };
    let t = Instant::now();
    media::decrypt_file_to_file(&dir.join("blob"), &dir.join("out"), &rf).unwrap();
    let dec = t.elapsed();
    let t = Instant::now();
    let ok = media::verified_prefix(&dir.join("blob"), &s.key, &s.alg, s.cipher_size).unwrap();
    let ver = t.elapsed();
    assert_eq!(ok, s.cipher_size);

    let after = vm_hwm_kib();
    let mib = s.plain_size as f64 / (1024.0 * 1024.0);
    println!(
        "media {mib:.1} MiB: encrypt {:.0} ms ({:.0} MiB/s), decrypt to file {:.0} ms ({:.0} MiB/s), \
         verified_prefix {:.0} ms; peak RSS {:?} -> {:?} KiB",
        enc.as_secs_f64() * 1e3,
        mib / enc.as_secs_f64(),
        dec.as_secs_f64() * 1e3,
        mib / dec.as_secs_f64(),
        ver.as_secs_f64() * 1e3,
        before,
        after
    );
    if let (Some(b), Some(a)) = (before, after) {
        assert!(a - b < 1024, "peak RSS grew by {} KiB", a - b);
    }
    // Same bytes back, compared in chunks (no 16 MiB buffers here either).
    use std::io::Read;
    let (mut x, mut y) = (
        fs::File::open(&src).unwrap(),
        fs::File::open(dir.join("out")).unwrap(),
    );
    let (mut bx, mut by) = (vec![0u8; 1 << 16], vec![0u8; 1 << 16]);
    loop {
        let n = x.read(&mut bx).unwrap();
        y.read_exact(&mut by[..n]).unwrap();
        assert_eq!(bx[..n], by[..n]);
        if n == 0 {
            break;
        }
    }
    fs::remove_dir_all(&dir).unwrap();
}
