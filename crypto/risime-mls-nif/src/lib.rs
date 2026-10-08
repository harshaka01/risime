//! `risime-mls-nif`: the Rustler NIF of `risime-mls` for the server-side Risi member client
//! (contract v1.24 §24.11, decisions 065 and 066). Loaded by `RisiMe.Agent.Mls.Nif`.
//!
//! **Journal contract.** A handle keeps the device's whole MLS state in memory ([`JournalStore`]),
//! loaded at `open/5` from sealed `risi_mls_kv` rows. Every call returns
//! `{:ok, result, journal}` with `journal = [{key, sealed_value | :delete}]`: the rows the call
//! changed, already sealed. The caller persists the journal in **one** Postgres transaction
//! **before** acting on `result` (sending a commit, a message, a key package). If persisting
//! fails, the handle is ahead of the database: drop it and `open` again from the rows. An error is
//! `{:error, {kind :: atom, message :: String.t}}`, and the call changed nothing.
//!
//! **Sealing** (in Rust; the server never sees plaintext state): AES-256-GCM with `RISI_MLS_KEK`
//! (32 bytes, passed to `open` and never logged or returned), a random 96-bit nonce per value,
//! `AAD = "risi-kv-v1" ‖ u16be(len device_id) ‖ device_id ‖ key` ([`journal`]). A row that fails
//! authentication fails `open` with `:tampered`, never a panic.
//!
//! **Official only** (§24.0): `join_from_welcome` into a group whose `group_meta` is not
//! `tab: official` (a `dm:` group, a Private group) is refused with `:private_tab` and changes
//! nothing; `encrypt`, `process_detailed`, `process_commits` and `self_update` refuse such a group
//! too.
//!
//! Every NIF runs on a dirty CPU scheduler, and a handle serialises its calls with a mutex.

pub mod client;
pub mod journal;

use std::sync::Mutex;

use risime_mls::{
    ApplicationDetails, CatchUp, DeviceId, GroupCommit, GroupMeta, Incoming, JoinedGroup,
    MemberInfo, Processed,
};
use rustler::types::map::map_new;
use rustler::{Binary, Encoder, Env, OwnedBinary, ResourceArc, Term};

pub use client::{Error, RisiClient};
pub use journal::{JournalEntry, JournalStore};

mod atoms {
    rustler::atoms! {
        ok, error, delete, nil, user, agent, private, official,
        application, commit, own_echo, poisoned, bad_arg,
    }
}

/// The resource behind a handle.
pub struct Handle(Mutex<RisiClient>);

#[rustler::resource_impl]
impl rustler::Resource for Handle {}

// ---------------------------------------------------------------------------------------------
// Encoding helpers
// ---------------------------------------------------------------------------------------------

fn bin<'a>(env: Env<'a>, bytes: &[u8]) -> Term<'a> {
    match OwnedBinary::new(bytes.len()) {
        Some(mut b) => {
            b.as_mut_slice().copy_from_slice(bytes);
            b.release(env).encode(env)
        }
        // Allocation failure: the BEAM is out of memory anyway.
        None => atoms::error().encode(env),
    }
}

fn map<'a>(env: Env<'a>, pairs: Vec<(&str, Term<'a>)>) -> Term<'a> {
    let mut m = map_new(env);
    for (k, v) in pairs {
        let key = rustler::Atom::from_str(env, k)
            .map(|a| a.encode(env))
            .unwrap_or_else(|_| k.encode(env));
        m = m.map_put(key, v).unwrap_or(m);
    }
    m
}

fn err<'a>(env: Env<'a>, e: &Error) -> Term<'a> {
    let (kind, msg) = e.parts();
    let kind = rustler::Atom::from_str(env, kind).unwrap_or_else(|_| atoms::error());
    (atoms::error(), (kind, msg)).encode(env)
}

fn journal<'a>(env: Env<'a>, j: &[JournalEntry]) -> Term<'a> {
    j.iter()
        .map(|(k, v)| {
            let v = match v {
                Some(s) => bin(env, s),
                None => atoms::delete().encode(env),
            };
            (bin(env, k), v).encode(env)
        })
        .collect::<Vec<_>>()
        .encode(env)
}

fn device<'a>(env: Env<'a>, d: &DeviceId) -> Term<'a> {
    map(
        env,
        vec![
            ("user_id", d.user_id.encode(env)),
            ("device_id", d.device_id.encode(env)),
        ],
    )
}

fn devices<'a>(env: Env<'a>, ds: &[DeviceId]) -> Term<'a> {
    ds.iter()
        .map(|d| device(env, d))
        .collect::<Vec<_>>()
        .encode(env)
}

fn member<'a>(env: Env<'a>, m: &MemberInfo) -> Term<'a> {
    let kind = if m.kind.is_agent() {
        atoms::agent()
    } else {
        atoms::user()
    };
    map(
        env,
        vec![
            ("user_id", m.user_id.encode(env)),
            ("device_id", m.device_id.encode(env)),
            ("leaf_index", m.leaf_index.encode(env)),
            ("signature_key", bin(env, &m.signature_key)),
            ("kind", kind.encode(env)),
        ],
    )
}

fn encode_members<'a>(env: Env<'a>, ms: &[MemberInfo]) -> Term<'a> {
    ms.iter()
        .map(|m| member(env, m))
        .collect::<Vec<_>>()
        .encode(env)
}

fn opt_bin<'a>(env: Env<'a>, b: &Option<Vec<u8>>) -> Term<'a> {
    match b {
        Some(b) => bin(env, b),
        None => atoms::nil().encode(env),
    }
}

fn group_commit<'a>(env: Env<'a>, gc: &GroupCommit) -> Term<'a> {
    map(
        env,
        vec![
            ("commit", bin(env, &gc.commit)),
            ("welcome", opt_bin(env, &gc.welcome)),
            ("epoch", gc.epoch.encode(env)),
            ("added", devices(env, &gc.added)),
            ("removed", devices(env, &gc.removed)),
            ("meta_changed", gc.meta_changed.encode(env)),
        ],
    )
}

fn incoming<'a>(env: Env<'a>, i: &Incoming, details: Option<&ApplicationDetails>) -> Term<'a> {
    match i {
        Incoming::Application {
            sender,
            plaintext,
            epoch,
        } => {
            let mut pairs = vec![
                ("type", atoms::application().encode(env)),
                ("sender", device(env, sender)),
                ("plaintext", bin(env, plaintext)),
                ("epoch", epoch.encode(env)),
            ];
            if let Some(d) = details {
                pairs.push(("sender_leaf", d.sender_leaf.encode(env)));
                pairs.push(("authenticated_data", bin(env, &d.authenticated_data)));
                pairs.push(("sender_is_admin", d.sender_is_admin.encode(env)));
            }
            map(env, pairs)
        }
        Incoming::Commit {
            epoch,
            committer,
            added,
            removed,
            removed_self,
            discarded_own_pending,
            meta_changed,
        } => map(
            env,
            vec![
                ("type", atoms::commit().encode(env)),
                ("epoch", epoch.encode(env)),
                ("committer", device(env, committer)),
                ("added", devices(env, added)),
                ("removed", devices(env, removed)),
                ("removed_self", removed_self.encode(env)),
                ("discarded_own_pending", discarded_own_pending.encode(env)),
                ("meta_changed", meta_changed.encode(env)),
            ],
        ),
        Incoming::OwnEcho => map(env, vec![("type", atoms::own_echo().encode(env))]),
    }
}

fn processed<'a>(env: Env<'a>, p: &Processed) -> Term<'a> {
    incoming(env, &p.incoming, p.application.as_ref())
}

fn catch_up<'a>(env: Env<'a>, c: &CatchUp) -> Term<'a> {
    let applied: Vec<Term<'a>> = c.applied.iter().map(|i| incoming(env, i, None)).collect();
    map(
        env,
        vec![
            ("epoch", c.epoch.encode(env)),
            ("applied", applied.encode(env)),
            ("skipped", c.skipped.encode(env)),
            ("removed_self", c.removed_self.encode(env)),
        ],
    )
}

fn joined<'a>(env: Env<'a>, j: &JoinedGroup) -> Term<'a> {
    map(
        env,
        vec![
            ("group_id", bin(env, &j.group_id)),
            ("epoch", j.epoch.encode(env)),
            ("members", encode_members(env, &j.members)),
        ],
    )
}

fn meta<'a>(env: Env<'a>, m: &Option<GroupMeta>) -> Term<'a> {
    let Some(m) = m else {
        return atoms::nil().encode(env);
    };
    let tab = match m.tab() {
        risime_mls::policy::Tab::Official => atoms::official(),
        risime_mls::policy::Tab::Private => atoms::private(),
    };
    let name = if m.name.is_empty() {
        atoms::nil().encode(env)
    } else {
        m.name.encode(env)
    };
    let json = serde_json::to_string(m).unwrap_or_default();
    map(
        env,
        vec![
            ("name", name),
            ("admins", m.admins.encode(env)),
            ("tab", tab.encode(env)),
            ("chat_id", m.chat_id.encode(env)),
            ("agents", m.agents().to_vec().encode(env)),
            ("json", json.encode(env)),
        ],
    )
}

/// Run `f` on the handle's client and encode `{:ok, value, journal}` or `{:error, …}`.
fn with<'a, T>(
    env: Env<'a>,
    h: &ResourceArc<Handle>,
    f: impl FnOnce(&mut RisiClient) -> client::Result<client::WithJournal<T>>,
    enc: impl FnOnce(Env<'a>, &T) -> Term<'a>,
) -> Term<'a> {
    // A poisoned lock means a call panicked half-way: the state can't be trusted. The caller must
    // drop the handle and open it again from the database.
    let Ok(mut c) = h.0.lock() else {
        return (
            atoms::error(),
            (atoms::poisoned(), "handle poisoned: reopen it"),
        )
            .encode(env);
    };
    match f(&mut c) {
        Ok((v, j)) => (atoms::ok(), enc(env, &v), journal(env, &j)).encode(env),
        Err(e) => err(env, &e),
    }
}

fn unit<'a>(env: Env<'a>, _: &()) -> Term<'a> {
    atoms::ok().encode(env)
}

fn bad_arg<'a>(env: Env<'a>, msg: &str) -> Term<'a> {
    (atoms::error(), (atoms::bad_arg(), msg)).encode(env)
}

// ---------------------------------------------------------------------------------------------
// NIFs
// ---------------------------------------------------------------------------------------------

#[rustler::nif(schedule = "DirtyCpu")]
fn open<'a>(
    env: Env<'a>,
    user_id: String,
    device_id: String,
    trust_anchors: Vec<String>,
    kek: Binary,
    sealed_rows: Vec<(Binary, Binary)>,
) -> Term<'a> {
    let rows = sealed_rows
        .iter()
        .map(|(k, v)| (k.as_slice().to_vec(), v.as_slice().to_vec()))
        .collect();
    match RisiClient::open(&user_id, &device_id, &trust_anchors, kek.as_slice(), rows) {
        Ok((c, j)) => (
            atoms::ok(),
            ResourceArc::new(Handle(Mutex::new(c))),
            journal(env, &j),
        )
            .encode(env),
        Err(e) => err(env, &e),
    }
}

#[rustler::nif(schedule = "DirtyCpu")]
fn signature_public_key<'a>(env: Env<'a>, h: ResourceArc<Handle>) -> Term<'a> {
    with(env, &h, |c| c.signature_public_key(), |e, v| bin(e, v))
}

#[rustler::nif(schedule = "DirtyCpu")]
fn set_attestation<'a>(env: Env<'a>, h: ResourceArc<Handle>, jws: String) -> Term<'a> {
    with(env, &h, |c| c.set_attestation(&jws), unit)
}

#[rustler::nif(schedule = "DirtyCpu")]
fn generate_key_packages<'a>(env: Env<'a>, h: ResourceArc<Handle>, count: u32) -> Term<'a> {
    let Ok(count) = u16::try_from(count) else {
        return bad_arg(env, "count must be 1..=100");
    };
    with(
        env,
        &h,
        |c| c.generate_key_packages(count),
        |e, kps: &Vec<Vec<u8>>| kps.iter().map(|k| bin(e, k)).collect::<Vec<_>>().encode(e),
    )
}

#[rustler::nif(schedule = "DirtyCpu")]
fn last_resort_key_package<'a>(env: Env<'a>, h: ResourceArc<Handle>) -> Term<'a> {
    with(env, &h, |c| c.last_resort_key_package(), |e, v| bin(e, v))
}

#[rustler::nif(schedule = "DirtyCpu")]
fn join_from_welcome<'a>(env: Env<'a>, h: ResourceArc<Handle>, welcome: Binary) -> Term<'a> {
    with(env, &h, |c| c.join_from_welcome(welcome.as_slice()), joined)
}

#[rustler::nif(schedule = "DirtyCpu")]
fn process_detailed<'a>(
    env: Env<'a>,
    h: ResourceArc<Handle>,
    group_id: Binary,
    message: Binary,
) -> Term<'a> {
    with(
        env,
        &h,
        |c| c.process_detailed(group_id.as_slice(), message.as_slice()),
        processed,
    )
}

#[rustler::nif(schedule = "DirtyCpu")]
fn process_commits<'a>(
    env: Env<'a>,
    h: ResourceArc<Handle>,
    group_id: Binary,
    commits: Vec<Binary>,
) -> Term<'a> {
    let commits: Vec<Vec<u8>> = commits.iter().map(|b| b.as_slice().to_vec()).collect();
    with(
        env,
        &h,
        |c| c.process_commits(group_id.as_slice(), &commits),
        catch_up,
    )
}

#[rustler::nif(schedule = "DirtyCpu")]
fn encrypt<'a>(
    env: Env<'a>,
    h: ResourceArc<Handle>,
    group_id: Binary,
    plaintext: Binary,
    aad: Binary,
) -> Term<'a> {
    with(
        env,
        &h,
        |c| c.encrypt(group_id.as_slice(), plaintext.as_slice(), aad.as_slice()),
        |e, v| bin(e, v),
    )
}

#[rustler::nif(schedule = "DirtyCpu")]
fn self_update<'a>(env: Env<'a>, h: ResourceArc<Handle>, group_id: Binary) -> Term<'a> {
    with(
        env,
        &h,
        |c| c.self_update(group_id.as_slice()),
        group_commit,
    )
}

#[rustler::nif(schedule = "DirtyCpu")]
fn commit_accepted<'a>(env: Env<'a>, h: ResourceArc<Handle>, group_id: Binary) -> Term<'a> {
    with(
        env,
        &h,
        |c| c.commit_accepted(group_id.as_slice()),
        |e, v| v.encode(e),
    )
}

#[rustler::nif(schedule = "DirtyCpu")]
fn commit_rejected<'a>(env: Env<'a>, h: ResourceArc<Handle>, group_id: Binary) -> Term<'a> {
    with(env, &h, |c| c.commit_rejected(group_id.as_slice()), unit)
}

#[rustler::nif(schedule = "DirtyCpu")]
fn purge_group<'a>(env: Env<'a>, h: ResourceArc<Handle>, group_id: Binary) -> Term<'a> {
    with(env, &h, |c| c.purge_group(group_id.as_slice()), unit)
}

#[rustler::nif(schedule = "DirtyCpu")]
fn members<'a>(env: Env<'a>, h: ResourceArc<Handle>, group_id: Binary) -> Term<'a> {
    with(
        env,
        &h,
        |c| c.members(group_id.as_slice()),
        |e, v: &Vec<MemberInfo>| encode_members(e, v),
    )
}

#[rustler::nif(schedule = "DirtyCpu")]
fn group_meta<'a>(env: Env<'a>, h: ResourceArc<Handle>, group_id: Binary) -> Term<'a> {
    with(env, &h, |c| c.group_meta(group_id.as_slice()), meta)
}

#[rustler::nif(schedule = "DirtyCpu")]
fn epoch<'a>(env: Env<'a>, h: ResourceArc<Handle>, group_id: Binary) -> Term<'a> {
    with(
        env,
        &h,
        |c| c.epoch(group_id.as_slice()),
        |e, v| v.encode(e),
    )
}

/// Whether this build carries the test-only NIFs (feature `test-peer`).
#[rustler::nif]
fn test_peer_enabled() -> bool {
    cfg!(feature = "test-peer")
}

#[cfg(feature = "test-peer")]
mod test_peer {
    //! **Test support only** (feature `test-peer`, never in a production build): a test attestor
    //! with a seed, and group creation by a human peer handle.

    use super::*;
    use risime_mls::TestAttestor;

    fn seed(b: &Binary) -> Option<[u8; 32]> {
        b.as_slice().try_into().ok()
    }

    #[rustler::nif]
    fn test_attestor_jwk<'a>(env: Env<'a>, seed_bin: Binary) -> Term<'a> {
        match seed(&seed_bin) {
            Some(s) => (atoms::ok(), TestAttestor::from_seed(s).public_jwk()).encode(env),
            None => bad_arg(env, "seed must be 32 bytes"),
        }
    }

    #[rustler::nif]
    fn test_attest<'a>(
        env: Env<'a>,
        seed_bin: Binary,
        user_id: String,
        device_id: String,
        signature_key: Binary,
        agent: bool,
    ) -> Term<'a> {
        let Some(s) = seed(&seed_bin) else {
            return bad_arg(env, "seed must be 32 bytes");
        };
        let Ok(d) = DeviceId::new(user_id, device_id) else {
            return bad_arg(env, "bad device");
        };
        let a = TestAttestor::from_seed(s);
        let kind = if agent { Some("agent") } else { None };
        let jws = a.attest_kind(&d, signature_key.as_slice(), 1_760_000_000, kind);
        (atoms::ok(), jws).encode(env)
    }

    #[rustler::nif(schedule = "DirtyCpu")]
    fn test_create_group<'a>(
        env: Env<'a>,
        h: ResourceArc<Handle>,
        group_id: Binary,
        key_packages: Vec<Binary>,
        meta_json: String,
    ) -> Term<'a> {
        let Ok(meta) = serde_json::from_str::<GroupMeta>(&meta_json) else {
            return bad_arg(env, "meta_json");
        };
        let kps: Vec<Vec<u8>> = key_packages.iter().map(|b| b.as_slice().to_vec()).collect();
        with(
            env,
            &h,
            |c| c.test_create_group(group_id.as_slice(), &kps, &meta),
            group_commit,
        )
    }
}

rustler::init!("Elixir.RisiMe.Agent.Mls.Nif");
