//! Contract v1.13 §16.12 / §16.16 (1:1 voice calls; crypto review R1, R2):
//! - `out_of_order_tolerance` 32 for new groups (create, Welcome) and for stored groups, which are
//!   migrated on load (both from the v1.12 configuration and from OpenMLS's defaults);
//! - the boundary: after a sender's generation `n`, the 31 earlier generations still decrypt and
//!   `n - 32` does not;
//! - 2 000 skipped generations (expired or filtered call signals) and then a text still decrypts.

mod common;

use std::sync::Arc;

use common::*;
use risime_mls::{MAX_FORWARD_DISTANCE, MemoryKvStore, OUT_OF_ORDER_TOLERANCE};

#[test]
fn constants() {
    assert_eq!(OUT_OF_ORDER_TOLERANCE, 32);
    assert_eq!(MAX_FORWARD_DISTANCE, 20_000);
}

#[test]
fn new_groups_use_tolerance_32() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    group(&alice, &[&bob]);
    for c in [&alice, &bob] {
        assert_eq!(c.stored_ratchet_config_for_tests(G).unwrap(), (32, 20_000));
    }
}

/// Alice sends generations 0..=32 (as a burst of call signals would); bob gets the last first.
fn assert_window_of_32(alice: &risime_mls::Client, bob: &risime_mls::Client) {
    let cts: Vec<Vec<u8>> = (0..=32u32)
        .map(|i| alice.encrypt(G, &i.to_be_bytes()).unwrap())
        .collect();
    assert_eq!(bob.decrypt(G, &cts[32]).unwrap(), 32u32.to_be_bytes());
    for i in (1..32).rev() {
        assert_eq!(bob.decrypt(G, &cts[i]).unwrap(), (i as u32).to_be_bytes());
    }
    assert!(
        bob.decrypt(G, &cts[0]).is_err(),
        "n - 32 is outside the window"
    );
    // A consumed key is never reused.
    assert!(bob.decrypt(G, &cts[5]).is_err());
}

#[test]
fn out_of_order_window_is_32() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    group(&alice, &[&bob]);
    assert_window_of_32(&alice, &bob);
}

/// A group stored by a v1.12 core (tolerance 5, forward distance 20 000) is migrated on load.
#[test]
fn v112_stored_group_is_migrated_on_load() {
    let alice = client("alice", "a1");
    let bob_store = Arc::new(MemoryKvStore::new());
    let bob = client_on(bob_store.clone(), "bob", "b1");
    group(&alice, &[&bob]);
    bob.downgrade_group_to_v112_for_tests(G).unwrap();
    assert_eq!(bob.stored_ratchet_config_for_tests(G).unwrap(), (5, 20_000));
    drop(bob);

    let bob = client_on(bob_store.clone(), "bob", "b1");
    assert_window_of_32(&alice, &bob);
    assert_eq!(
        bob.stored_ratchet_config_for_tests(G).unwrap(),
        (32, 20_000)
    );
    assert_eq!(bob_store.depth(), 0);
}

/// A group stored before v1.12 (OpenMLS defaults 5 / 1000) gets both values on load.
#[test]
fn pre_v112_stored_group_gets_both_values() {
    let alice = client("alice", "a1");
    let bob_store = Arc::new(MemoryKvStore::new());
    let bob = client_on(bob_store.clone(), "bob", "b1");
    group(&alice, &[&bob]);
    bob.downgrade_group_config_for_tests(G).unwrap();
    assert_eq!(bob.stored_ratchet_config_for_tests(G).unwrap(), (5, 1000));
    drop(bob);

    let bob = client_on(bob_store.clone(), "bob", "b1");
    let ct = alice.encrypt(G, b"hello").unwrap();
    assert_eq!(bob.decrypt(G, &ct).unwrap(), b"hello");
    assert_eq!(
        bob.stored_ratchet_config_for_tests(G).unwrap(),
        (32, 20_000)
    );
}

/// §16.12: 2 000 call signals that this device never got (expired, or filtered from a device
/// without `calls`), then a text: it still decrypts, also on a group migrated from v1.12.
#[test]
fn text_after_2000_missed_signals_decrypts() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    let tab_store = Arc::new(MemoryKvStore::new());
    let tablet = client_on(tab_store.clone(), "bob", "b2");
    group(&alice, &[&bob, &tablet]);
    tablet.downgrade_group_to_v112_for_tests(G).unwrap();
    drop(tablet);

    let first = alice.encrypt(G, b"before the calls").unwrap();
    assert_eq!(bob.decrypt(G, &first).unwrap(), b"before the calls");
    for i in 0..2000u32 {
        let signal = alice
            .encrypt(
                G,
                format!(r#"{{"v":1,"type":"call_ice","n":{i}}}"#).as_bytes(),
            )
            .unwrap();
        if i % 400 == 0 {
            // bob's phone got a few of them; the tablet got none.
            assert!(bob.decrypt(G, &signal).is_ok());
        }
    }
    let text = alice.encrypt(G, b"still readable").unwrap();
    assert_eq!(bob.decrypt(G, &text).unwrap(), b"still readable");
    let tablet = client_on(tab_store, "bob", "b2");
    assert_eq!(tablet.decrypt(G, &first).unwrap(), b"before the calls");
    assert_eq!(tablet.decrypt(G, &text).unwrap(), b"still readable");
}
