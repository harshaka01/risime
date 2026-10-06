//! Garbage in, errors out: never a panic, never a state change.

mod common;

use common::*;

struct XorShift(u64);
impl XorShift {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn bytes(&mut self, n: usize) -> Vec<u8> {
        (0..n).map(|_| self.next() as u8).collect()
    }
}

#[test]
fn random_and_truncated_input_never_panics() {
    let alice = client("alice", "a1");
    let bob = client("bob", "b1");
    group(&alice, &[&bob]);
    let ct = alice.encrypt(G, b"a real message").unwrap();
    let pc = alice.add_members(G, &[kp(&client("carol", "c1"))]).unwrap();
    let welcome = pc.welcome.clone().unwrap();
    let k = kp(&client("dave", "d1"));

    let mut rng = XorShift(0x9e37_79b9_7f4a_7c15);
    for i in 0..300 {
        let n = (rng.next() % 600) as usize;
        let junk = rng.bytes(n);
        assert!(bob.process(G, &junk).is_err(), "junk {i}");
        assert!(bob.join_from_welcome(&junk).is_err());
        assert!(bob.add_members(G, &[junk]).is_err());
    }
    for input in [&ct, &pc.commit, &welcome, &k] {
        for len in 0..input.len() {
            let cut = &input[..len];
            assert!(bob.process(G, cut).is_err());
            assert!(bob.join_from_welcome(cut).is_err());
            assert!(bob.add_members(G, &[cut.to_vec()]).is_err());
        }
    }
    // Still intact.
    assert_eq!(bob.decrypt(G, &ct).unwrap(), b"a real message");
}
