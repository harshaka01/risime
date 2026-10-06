//! Attestation and credential validation (contract §10.0/§10.1, decision 033: trust is decided
//! in Rust, from the leaf's `application_id`, against pinned keys).

mod common;

use common::*;
use risime_mls::{Client, DeviceId, MlsError, TestAttestor, TrustAnchors};

fn untrusted<T: std::fmt::Debug>(r: Result<T, MlsError>) {
    assert!(
        matches!(r, Err(MlsError::UntrustedCredential(_))),
        "expected UntrustedCredential, got {r:?}"
    );
}

#[test]
fn identity_format_and_parsing() {
    let d = DeviceId::parse(
        b"0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e/c0a80101-0000-4000-8000-000000000002",
    )
    .unwrap();
    assert_eq!(d.user_id, "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e");
    assert_eq!(d.device_id, "c0a80101-0000-4000-8000-000000000002");
    assert_eq!(DeviceId::parse(d.identity().as_bytes()).unwrap(), d);
    for bad in [
        &b"nouser"[..],
        b"/d",
        b"u/",
        b"u/d/e",
        b"u /d",
        b"\xff/d",
        b"",
    ] {
        assert!(
            matches!(DeviceId::parse(bad), Err(MlsError::Malformed(_))),
            "{bad:?}"
        );
    }
    assert!(DeviceId::new("u".repeat(65), "d").is_err());
    let c = client("alice", "a1");
    assert_eq!(c.identity(), "alice/a1");
}

/// RFC 8037 appendix A.3: the thumbprint of the example Ed25519 key.
#[test]
fn kid_is_the_rfc7638_thumbprint() {
    let a = TrustAnchors::from_jwks(&[
        r#"{"kty":"OKP","crv":"Ed25519","x":"11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo"}"#,
    ])
    .unwrap();
    assert_eq!(a.len(), 1);
    let x = base64_url("11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo");
    let key = ed25519_dalek::VerifyingKey::from_bytes(&x.try_into().unwrap()).unwrap();
    assert_eq!(
        risime_mls::attestation::thumbprint(&key),
        "kPrK_qmxVWaYVA9wwBF6Iuo3vVzz7TxHCTwXBygrS4k"
    );
}

fn base64_url(s: &str) -> Vec<u8> {
    use base64::Engine as _;
    base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(s)
        .unwrap()
}

#[test]
fn jwks_parsing_rejects_bad_keys() {
    for bad in [
        "not json",
        r#"{"kty":"RSA","crv":"Ed25519","x":"AA"}"#,
        r#"{"kty":"OKP","crv":"X25519","x":"11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo"}"#,
        r#"{"kty":"OKP","crv":"Ed25519","x":"AAAA"}"#,
    ] {
        assert!(TrustAnchors::from_jwks(&[bad]).is_err(), "{bad}");
    }
}

#[test]
fn attestation_verification() {
    let a = attestor();
    let anchors = a.anchors();
    let d = dev("alice", "a1");
    let key = [9u8; 32];
    let jws = a.attest(&d, &key, 1);
    anchors.verify(&jws, &d, &key).unwrap();
    // Wrong device, wrong key, untrusted signer, tampered parts.
    untrusted(anchors.verify(&jws, &dev("alice", "a2"), &key));
    untrusted(anchors.verify(&jws, &dev("bob", "a1"), &key));
    untrusted(anchors.verify(&jws, &d, &[8u8; 32]));
    let other = TestAttestor::from_seed([1; 32]);
    untrusted(anchors.verify(&other.attest(&d, &key, 1), &d, &key));
    untrusted(TrustAnchors::none().verify(&jws, &d, &key));
    let mut parts: Vec<String> = jws.split('.').map(String::from).collect();
    let sig = parts[2].clone();
    parts[2] = format!("A{}", &sig[1..]);
    untrusted(anchors.verify(&parts.join("."), &d, &key));
    untrusted(anchors.verify("a.b", &d, &key));
    untrusted(anchors.verify("", &d, &key));
}

/// Previous keys stay trusted during a rotation; a JWK's own `kid` (like the contract example's
/// "k1") is honoured as well as the thumbprint.
#[test]
fn rotated_and_named_keys_are_trusted() {
    let old = TestAttestor::from_seed([1; 32]);
    let new = attestor();
    let both = TrustAnchors::from_jwks(&[new.public_jwk(), old.public_jwk()]).unwrap();
    let d = dev("alice", "a1");
    let key = [9u8; 32];
    both.verify(&old.attest(&d, &key, 1), &d, &key).unwrap();
    both.verify(&new.attest(&d, &key, 1), &d, &key).unwrap();
    untrusted(new.anchors().verify(&old.attest(&d, &key, 1), &d, &key));
}

#[test]
fn set_attestation_checks_own_binding() {
    let a = attestor();
    let mut c = Client::in_memory(dev("alice", "a1"), a.anchors()).unwrap();
    untrusted(c.set_attestation(&a.attest(&dev("alice", "a2"), &c.signature_public_key(), 1)));
    untrusted(c.set_attestation(&a.attest(c.device(), &[0u8; 32], 1)));
    untrusted(c.set_attestation(&TestAttestor::from_seed([3; 32]).attest(
        c.device(),
        &c.signature_public_key(),
        1,
    )));
    assert_eq!(c.attestation(), None);
    let ok = a.attest(c.device(), &c.signature_public_key(), 1);
    c.set_attestation(&ok).unwrap();
    assert_eq!(c.attestation(), Some(ok.as_str()));
}

/// Point 1: key packages we add.
#[test]
fn unattested_key_package_is_rejected_on_add() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    untrusted(alice.create_group(G, &[kp(&rogue("mallory", "m1"))]));
    assert!(!alice.has_group(G).unwrap());
    group(&alice, &[&bob]);
    untrusted(alice.add_members(G, &[kp(&rogue("mallory", "m1"))]));
    assert!(!alice.has_pending_commit(G).unwrap());
    assert_eq!(alice.epoch(G).unwrap(), 1);
}

/// Point 2: every leaf of a group we join. The rejected join is rolled back completely: the key
/// package is still there for a legitimate Welcome.
#[test]
fn welcome_with_an_unattested_leaf_is_rejected_and_rolled_back() {
    let bob = client("bob", "b1");
    let mallory = rogue("mallory", "m1");
    let k = kp(&bob);
    let evil = mallory.create_group(G, std::slice::from_ref(&k)).unwrap();
    untrusted(bob.join_from_welcome(evil.welcome.as_ref().unwrap()));
    assert!(!bob.has_group(G).unwrap());
    // The same key package still works for an honest group.
    let alice = client("alice", "a1");
    let ok = alice.create_group(b"g2", &[k]).unwrap();
    bob.join_from_welcome(ok.welcome.as_ref().unwrap()).unwrap();
}

/// Point 3: every leaf a commit adds. The commit is refused and the epoch is unchanged.
#[test]
fn commit_adding_an_unattested_leaf_is_rejected() {
    // Alice is honest-but-lax (accepts anything); Bob is strict.
    let a = attestor();
    let mut alice = Client::in_memory(dev("alice", "a1"), AcceptAll).unwrap();
    alice
        .set_attestation(&a.attest(alice.device(), &alice.signature_public_key(), 1))
        .unwrap();
    let bob = client("bob", "b1");
    group(&alice, &[&bob]);
    let pc = alice
        .add_members(G, &[kp(&rogue("mallory", "m1"))])
        .unwrap();
    untrusted(bob.process(G, &pc.commit));
    assert_eq!(bob.epoch(G).unwrap(), 1);
}
