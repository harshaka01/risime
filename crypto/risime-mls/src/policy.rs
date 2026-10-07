//! The admin policy for group commits (contract v1.9 §12.4, decision 041).
//!
//! The core runs it on every staged commit of a `grp:` group, against `group_meta.admins` of the
//! epoch the commit was built in, and on our own commits before they are built. The server runs
//! the same rules; both suites run `contract/v1/group_policy_cases.json`.
//!
//! - A commit by a **non-admin** may not change `group_meta`. It may add leaves of its own user and
//!   (v1.14 §12.4a) of users who **already have a leaf** in the base epoch and are not agents. It
//!   may remove leaves of its own user, and another user's leaf **only when the same commit
//!   re-adds that same device** (a rejoin or a re-keyed device; never a swap for another device,
//!   which could evict a live phone).
//! - An **admin** may do anything, but a new admin list must be non-empty and contain no agent.
//! - **Agents are never admins**, even if listed.
//!
//! The server-only parts (pending-op matching, the `member_devices` gate, liveness, caps) are not
//! here.

/// A `group_meta` change carried by a commit.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MetaChange {
    /// The new admin list, or `None` if the admins are unchanged.
    pub admins: Option<Vec<String>>,
    /// The name or icon changed.
    pub name_changed: bool,
}

/// A leaf by its attested credential: `(user_id, device_id)`.
pub type Leaf<'a> = (&'a str, &'a str);

/// What a commit does, by device, and the base epoch's leaf owners.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CommitSummary<'a> {
    pub committer_user: &'a str,
    /// The added leaves.
    pub adds: Vec<Leaf<'a>>,
    /// The removed leaves.
    pub removes: Vec<Leaf<'a>>,
    /// The users with at least one leaf in the epoch the commit is built in (duplicates allowed).
    pub leaf_users: Vec<&'a str>,
    pub meta: Option<MetaChange>,
}

/// Whether `user` is an admin: listed in `admins` and not an agent.
pub fn is_admin(admins: &[String], agents: &[String], user: &str) -> bool {
    !agents.iter().any(|a| a == user) && admins.iter().any(|a| a == user)
}

/// `Ok` if the commit is allowed, otherwise the reason.
pub fn check_commit_policy(
    admins: &[String],
    agents: &[String],
    commit: &CommitSummary<'_>,
) -> std::result::Result<(), String> {
    let is_agent = |u: &str| agents.iter().any(|a| a == u);
    let is_admin = |u: &str| is_admin(admins, agents, u);
    let me = commit.committer_user;

    if !is_admin(me) {
        if commit.meta.is_some() {
            return Err(format!("non-admin {me} may not change group_meta"));
        }
        for &(u, d) in &commit.adds {
            if u == me {
                continue;
            }
            if is_agent(u) {
                return Err(format!("non-admin {me} may not add leaves of agent {u}"));
            }
            if !commit.leaf_users.contains(&u) {
                return Err(format!(
                    "non-admin {me} may not add {u}/{d}: {u} has no leaf in the group"
                ));
            }
        }
        for &(u, d) in &commit.removes {
            if u == me {
                continue;
            }
            if is_agent(u) {
                return Err(format!("non-admin {me} may not remove leaves of agent {u}"));
            }
            if !commit.adds.contains(&(u, d)) {
                return Err(format!(
                    "non-admin {me} may remove {u}/{d} only together with its re-add"
                ));
            }
        }
        return Ok(());
    }
    if let Some(MetaChange {
        admins: Some(new), ..
    }) = &commit.meta
    {
        if new.is_empty() {
            return Err("the admin list may not become empty".into());
        }
        if let Some(a) = new.iter().find(|a| is_agent(a)) {
            return Err(format!("agent {a} can't be an admin"));
        }
    }
    Ok(())
}
