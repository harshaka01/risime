//! Key package batches and last-resort packages (contract §10.1).

mod common;

use std::collections::HashSet;

use common::*;
use openmls::prelude::tls_codec::Deserialize;
use openmls::prelude::{KeyPackageIn, OpenMlsProvider, ProtocolVersion};
use openmls_rust_crypto::OpenMlsRustCrypto;
use risime_mls::MlsError;

#[test]
fn batch_of_100_is_unique_and_valid() {
    let bob = client("bob", "b1");
    let kps = bob.generate_key_packages(100).unwrap();
    assert_eq!(kps.len(), 100);
    assert_eq!(kps.iter().collect::<HashSet<_>>().len(), 100);
    let p = OpenMlsRustCrypto::default();
    for k in &kps {
        assert!(k.len() <= 4096, "contract: at most 4 KiB");
        let kp = KeyPackageIn::tls_deserialize_exact(k)
            .unwrap()
            .validate(p.crypto(), ProtocolVersion::Mls10)
            .unwrap();
        assert!(!kp.last_resort());
    }
    assert!(matches!(
        bob.generate_key_packages(0),
        Err(MlsError::Malformed(_))
    ));
    assert!(matches!(
        bob.generate_key_packages(101),
        Err(MlsError::Malformed(_))
    ));
}

#[test]
fn last_resort_package_is_marked() {
    let bob = client("bob", "b1");
    let k = bob.generate_last_resort_key_package().unwrap();
    let kp = KeyPackageIn::tls_deserialize_exact(&k)
        .unwrap()
        .validate(
            OpenMlsRustCrypto::default().crypto(),
            ProtocolVersion::Mls10,
        )
        .unwrap();
    assert!(kp.last_resort());
}

#[test]
fn normal_key_package_is_single_use() {
    let bob = client("bob", "b1");
    let alice = client("alice", "a1");
    let carol = client("carol", "c1");
    let k = kp(&bob);
    let g1 = alice.create_group(b"g1", std::slice::from_ref(&k)).unwrap();
    let g2 = carol.create_group(b"g2", &[k]).unwrap();
    bob.join_from_welcome(g1.welcome.as_ref().unwrap()).unwrap();
    assert!(matches!(
        bob.join_from_welcome(g2.welcome.as_ref().unwrap()),
        Err(MlsError::Welcome(_))
    ));
}

#[test]
fn last_resort_key_package_serves_several_joins() {
    let bob = client("bob", "b1");
    let alice = client("alice", "a1");
    let carol = client("carol", "c1");
    let lr = bob.generate_last_resort_key_package().unwrap();
    let g1 = alice
        .create_group(b"g1", std::slice::from_ref(&lr))
        .unwrap();
    let g2 = carol.create_group(b"g2", &[lr]).unwrap();
    alice.commit_accepted(b"g1").unwrap();
    carol.commit_accepted(b"g2").unwrap();
    bob.join_from_welcome(g1.welcome.as_ref().unwrap()).unwrap();
    bob.join_from_welcome(g2.welcome.as_ref().unwrap()).unwrap();
    let ct = alice.encrypt(b"g1", b"one").unwrap();
    assert_eq!(bob.decrypt(b"g1", &ct).unwrap(), b"one");
    let ct = carol.encrypt(b"g2", b"two").unwrap();
    assert_eq!(bob.decrypt(b"g2", &ct).unwrap(), b"two");
}

#[test]
fn key_packages_and_groups_need_an_attestation() {
    let a = attestor();
    let bare = risime_mls::Client::in_memory(dev("bob", "b1"), a.anchors()).unwrap();
    assert_eq!(
        bare.generate_key_packages(1),
        Err(MlsError::MissingAttestation)
    );
    assert_eq!(
        bare.generate_last_resort_key_package(),
        Err(MlsError::MissingAttestation)
    );
    assert_eq!(
        bare.create_group(G, &[kp(&client("alice", "a1"))]),
        Err(MlsError::MissingAttestation)
    );
}
