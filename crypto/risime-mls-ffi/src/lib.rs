//! UniFFI binding of `risime-mls` (docs/decisions/012). A thin, FFI-only layer: every MLS
//! decision lives in the core crate. Kotlin package: `lk.codegen.risime.crypto` (uniffi.toml).

use std::sync::{Arc, Mutex};

use risime_mls::{Client, Incoming, MlsError};

uniffi::setup_scaffolding!();

/// Mirrors `risime_mls::MlsError` 1:1; becomes a Kotlin sealed exception class.
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum RisiMlsError {
    #[error("malformed input: {0}")]
    Malformed(String),
    #[error("invalid key package: {0}")]
    InvalidKeyPackage(String),
    #[error("unknown group")]
    UnknownGroup,
    #[error("group already exists")]
    GroupExists,
    #[error("unknown member")]
    UnknownMember,
    #[error("removed from group")]
    RemovedFromGroup,
    #[error("message is for a different epoch")]
    WrongEpoch,
    #[error("decryption failed: {0}")]
    DecryptionFailed(String),
    #[error("not an application message")]
    NotApplicationMessage,
    #[error("welcome failed: {0}")]
    Welcome(String),
    #[error("mls: {0}")]
    Other(String),
}

impl From<MlsError> for RisiMlsError {
    fn from(e: MlsError) -> Self {
        match e {
            MlsError::Malformed(s) => Self::Malformed(s),
            MlsError::InvalidKeyPackage(s) => Self::InvalidKeyPackage(s),
            MlsError::UnknownGroup => Self::UnknownGroup,
            MlsError::GroupExists => Self::GroupExists,
            MlsError::UnknownMember => Self::UnknownMember,
            MlsError::RemovedFromGroup => Self::RemovedFromGroup,
            MlsError::WrongEpoch => Self::WrongEpoch,
            MlsError::DecryptionFailed(s) => Self::DecryptionFailed(s),
            MlsError::NotApplicationMessage => Self::NotApplicationMessage,
            MlsError::Welcome(s) => Self::Welcome(s),
            MlsError::Other(s) => Self::Other(s),
        }
    }
}

type Result<T> = std::result::Result<T, RisiMlsError>;

#[derive(uniffi::Record)]
pub struct AddMemberResult {
    /// To every existing member.
    pub commit: Vec<u8>,
    /// To the new member only.
    pub welcome: Vec<u8>,
}

#[derive(uniffi::Enum)]
pub enum IncomingMessage {
    Application { sender: Vec<u8>, plaintext: Vec<u8> },
    Commit { epoch: u64, removed_self: bool },
    Proposal,
    OwnEcho,
}

impl From<Incoming> for IncomingMessage {
    fn from(i: Incoming) -> Self {
        match i {
            Incoming::Application { sender, plaintext } => Self::Application { sender, plaintext },
            Incoming::Commit {
                epoch,
                removed_self,
            } => Self::Commit {
                epoch,
                removed_self,
            },
            Incoming::Proposal => Self::Proposal,
            Incoming::OwnEcho => Self::OwnEcho,
        }
    }
}

/// One device's MLS state. Calls are serialised by an internal mutex; call from a background
/// thread (they are CPU-bound, a few ms).
#[derive(uniffi::Object)]
pub struct MlsClient {
    inner: Mutex<Client>,
}

impl MlsClient {
    fn with<T>(&self, f: impl FnOnce(&mut Client) -> risime_mls::Result<T>) -> Result<T> {
        let mut c = self
            .inner
            .lock()
            .map_err(|_| RisiMlsError::Other("poisoned".into()))?;
        f(&mut c).map_err(Into::into)
    }
}

#[uniffi::export]
impl MlsClient {
    #[uniffi::constructor]
    pub fn new(identity: Vec<u8>) -> Result<Arc<Self>> {
        Ok(Arc::new(Self {
            inner: Mutex::new(Client::new(&identity)?),
        }))
    }

    pub fn create_key_package(&self) -> Result<Vec<u8>> {
        self.with(|c| c.create_key_package())
    }

    pub fn create_group(&self, group_id: Vec<u8>) -> Result<()> {
        self.with(|c| c.create_group(&group_id))
    }

    pub fn add_member(&self, group_id: Vec<u8>, key_package: Vec<u8>) -> Result<AddMemberResult> {
        self.with(|c| c.add_member(&group_id, &key_package))
            .map(|o| AddMemberResult {
                commit: o.commit,
                welcome: o.welcome,
            })
    }

    /// Returns the group id.
    pub fn join_from_welcome(&self, welcome: Vec<u8>) -> Result<Vec<u8>> {
        self.with(|c| c.join_from_welcome(&welcome))
    }

    pub fn encrypt(&self, group_id: Vec<u8>, plaintext: Vec<u8>) -> Result<Vec<u8>> {
        self.with(|c| c.encrypt(&group_id, &plaintext))
    }

    pub fn decrypt(&self, group_id: Vec<u8>, message: Vec<u8>) -> Result<Vec<u8>> {
        self.with(|c| c.decrypt(&group_id, &message))
    }

    pub fn process(&self, group_id: Vec<u8>, message: Vec<u8>) -> Result<IncomingMessage> {
        self.with(|c| c.process(&group_id, &message))
            .map(Into::into)
    }

    /// Returns the commit for the remaining members (and the removed one).
    pub fn remove_member(&self, group_id: Vec<u8>, identity: Vec<u8>) -> Result<Vec<u8>> {
        self.with(|c| c.remove_member(&group_id, &identity))
    }

    pub fn epoch(&self, group_id: Vec<u8>) -> Result<u64> {
        self.with(|c| c.epoch(&group_id))
    }

    pub fn members(&self, group_id: Vec<u8>) -> Result<Vec<Vec<u8>>> {
        self.with(|c| c.members(&group_id))
    }
}

/// Ciphersuite name and crate version, for Settings → About and bug reports.
#[uniffi::export]
pub fn mls_info() -> String {
    format!(
        "risime-mls {} ({:?})",
        env!("CARGO_PKG_VERSION"),
        risime_mls::CIPHERSUITE
    )
}

/// Runs the whole lifecycle in-process (create, add, both-way encrypt/decrypt, remove, removed
/// member can't read). One call verifies the native library on a real device.
#[uniffi::export]
pub fn self_test() -> Result<String> {
    let g = b"self-test".to_vec();
    let alice = MlsClient::new(b"alice".to_vec())?;
    let bob = MlsClient::new(b"bob".to_vec())?;
    let carol = MlsClient::new(b"carol".to_vec())?;

    alice.create_group(g.clone())?;
    let add_bob = alice.add_member(g.clone(), bob.create_key_package()?)?;
    bob.join_from_welcome(add_bob.welcome)?;
    let add_carol = alice.add_member(g.clone(), carol.create_key_package()?)?;
    bob.process(g.clone(), add_carol.commit)?;
    carol.join_from_welcome(add_carol.welcome)?;

    let hi = alice.encrypt(g.clone(), b"hello bob".to_vec())?;
    check(
        bob.decrypt(g.clone(), hi)? == b"hello bob",
        "bob decrypts alice",
    )?;
    let back = bob.encrypt(g.clone(), b"hi alice".to_vec())?;
    check(
        alice.decrypt(g.clone(), back)? == b"hi alice",
        "alice decrypts bob",
    )?;

    let rm = alice.remove_member(g.clone(), b"carol".to_vec())?;
    bob.process(g.clone(), rm.clone())?;
    carol.process(g.clone(), rm)?;
    let secret = alice.encrypt(g.clone(), b"after carol left".to_vec())?;
    check(
        bob.decrypt(g.clone(), secret.clone())? == b"after carol left",
        "bob after removal",
    )?;
    check(
        carol.decrypt(g.clone(), secret).is_err(),
        "removed carol cannot decrypt",
    )?;

    Ok(format!("ok: epoch {}, {}", alice.epoch(g)?, mls_info()))
}

fn check(ok: bool, what: &str) -> Result<()> {
    if ok {
        Ok(())
    } else {
        Err(RisiMlsError::Other(format!("self-test failed: {what}")))
    }
}
