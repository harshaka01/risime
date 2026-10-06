//! Performance sanity for a full-size group (contract v1.9 caps: 256 users). Prints timings and
//! sizes; see them with `cargo test --test group_scale -- --nocapture` (add `--release` for
//! numbers closer to a phone's optimised build). The asserts are the contract's size caps only.

mod common;

use std::sync::Arc;
use std::time::Instant;

use common::*;
use risime_mls::{
    Client, GroupMeta, Incoming, MAX_COMMIT_BYTES, MAX_GROUP_USERS, MAX_WELCOME_BYTES,
    MemoryKvStore, MlsError,
};

const GG: &[u8] = b"grp:00000000-0000-4000-8000-000000000256#1";

fn ms(t: Instant) -> f64 {
    t.elapsed().as_secs_f64() * 1000.0
}

#[test]
fn group_of_256_users() {
    let t = Instant::now();
    let alice_store = Arc::new(MemoryKvStore::new());
    let alice = client_on(alice_store.clone(), "alice", "a1");
    let bob_store = Arc::new(MemoryKvStore::new());
    let bob = client_on(bob_store.clone(), "u000", "d1");
    let mut others: Vec<Client> = vec![bob];
    for i in 1..MAX_GROUP_USERS - 1 {
        others.push(client(&format!("u{i:03}"), "d1"));
    }
    let kps: Vec<Vec<u8>> = others.iter().map(kp).collect();
    let setup = ms(t);

    let meta = GroupMeta::new("All hands", vec!["alice".into()]);
    let t = Instant::now();
    let create = alice.create_group_with_meta(GG, &kps, &meta).unwrap();
    let t_create = ms(t);
    alice.commit_accepted(GG).unwrap();
    assert_eq!(alice.members(GG).unwrap().len(), MAX_GROUP_USERS);
    let welcome = create.welcome.as_ref().unwrap();
    assert!(create.commit.len() <= MAX_COMMIT_BYTES);
    assert!(welcome.len() <= MAX_WELCOME_BYTES);

    let t = Instant::now();
    others[0].join_from_welcome(welcome).unwrap();
    let t_join = ms(t);
    others[1].join_from_welcome(welcome).unwrap();
    let bob = &others[0];
    let carol = &others[1];

    // The user cap: a 257th user is refused before anything is built.
    let extra = client("u999", "d1");
    assert!(matches!(
        alice.change_members(GG, &[kp(&extra)], &[]),
        Err(MlsError::PolicyViolation(_))
    ));

    // An admin removes one user; members process the commit.
    let t = Instant::now();
    let rm = alice.remove_users(GG, &["u100".into()]).unwrap();
    let t_remove_build = ms(t);
    alice.commit_accepted(GG).unwrap();
    let t = Instant::now();
    assert!(matches!(
        bob.process(GG, &rm.commit).unwrap(),
        Incoming::Commit { epoch: 2, .. }
    ));
    let t_remove_process = ms(t);
    carol.process(GG, &rm.commit).unwrap();

    // An add (with a Welcome carrying the whole tree).
    let t = Instant::now();
    let add = alice.change_members(GG, &[kp(&extra)], &[]).unwrap();
    let t_add_build = ms(t);
    alice.commit_accepted(GG).unwrap();
    let t = Instant::now();
    bob.process(GG, &add.commit).unwrap();
    let t_add_process = ms(t);
    carol.process(GG, &add.commit).unwrap();

    // A member's self-update (a full path), processed by the admin.
    let su = bob.self_update(GG).unwrap();
    bob.commit_accepted(GG).unwrap();
    let t = Instant::now();
    alice.process(GG, &su.commit).unwrap();
    let t_update_process = ms(t);
    carol.process(GG, &su.commit).unwrap();
    assert_eq!(
        alice.epoch_authenticator(GG).unwrap(),
        bob.epoch_authenticator(GG).unwrap()
    );

    // Messages: every call loads the group from the store.
    let n = 10;
    let cts: Vec<Vec<u8>> = (0..n)
        .map(|i| carol.encrypt(GG, format!("m{i}").as_bytes()).unwrap())
        .collect();
    let t = Instant::now();
    for c in &cts {
        bob.decrypt(GG, c).unwrap();
    }
    let t_decrypt = ms(t) / n as f64;

    let alice_bytes = alice_store.size_bytes();
    let bob_bytes = bob_store.size_bytes();
    let bob_largest = bob_store.largest_value();
    eprintln!(
        "group_scale ({} build): {} users, {} leaves\n\
         \x20 setup (255 clients + key packages)  {setup:8.0} ms\n\
         \x20 create + add 255 (stage)            {t_create:8.0} ms  commit {} B, welcome {} B\n\
         \x20 join from Welcome                   {t_join:8.0} ms\n\
         \x20 remove 1 user: build / process      {t_remove_build:8.1} / {t_remove_process:.1} ms  commit {} B\n\
         \x20 add 1 user:    build / process      {t_add_build:8.1} / {t_add_process:.1} ms  commit {} B, welcome {} B\n\
         \x20 self-update:   process              {t_update_process:8.1} ms  commit {} B\n\
         \x20 decrypt (incl. state load)          {t_decrypt:8.1} ms per message\n\
         \x20 state: admin {alice_bytes} B, member {bob_bytes} B (largest value {bob_largest} B)",
        if cfg!(debug_assertions) {
            "debug"
        } else {
            "release"
        },
        MAX_GROUP_USERS,
        alice.members(GG).unwrap().len(),
        create.commit.len(),
        welcome.len(),
        rm.commit.len(),
        add.commit.len(),
        add.welcome.as_ref().unwrap().len(),
        su.commit.len(),
    );
}
