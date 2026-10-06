//! The admin policy for group commits (contract v1.9 §12.4, decision 041).
//!
//! The core runs it on every staged commit of a `grp:` group, against `group_meta.admins` of the
//! epoch the commit was built in, and on our own commits before they are built. The server runs
//! the same rules; both suites run `contract/v1/group_policy_cases.json`.
//!
//! - A commit by a **non-admin** may add or remove only leaves of the committer's own user, and
//!   may not change `group_meta`.
//! - An **admin** may do anything, but a new admin list must be non-empty and contain no agent.
//! - **Agents are never admins**, even if listed.
//!
//! The server-only parts (pending-op matching, "all devices of a user", caps) are not here.

/// A `group_meta` change carried by a commit.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MetaChange {
    /// The new admin list, or `None` if the admins are unchanged.
    pub admins: Option<Vec<String>>,
    /// The name or icon changed.
    pub name_changed: bool,
}

/// What a commit does, by user.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CommitSummary<'a> {
    pub committer_user: &'a str,
    /// The users of the added leaves.
    pub add_users: Vec<&'a str>,
    /// The users of the removed leaves.
    pub remove_users: Vec<&'a str>,
    pub meta: Option<MetaChange>,
}

/// `Ok` if the commit is allowed, otherwise the reason.
pub fn check_commit_policy(
    admins: &[String],
    agents: &[String],
    commit: &CommitSummary<'_>,
) -> std::result::Result<(), String> {
    let is_agent = |u: &str| agents.iter().any(|a| a == u);
    let is_admin = |u: &str| !is_agent(u) && admins.iter().any(|a| a == u);
    let me = commit.committer_user;

    if !is_admin(me) {
        if let Some(u) = commit
            .add_users
            .iter()
            .chain(&commit.remove_users)
            .find(|u| **u != me)
        {
            return Err(format!(
                "non-admin {me} may not add or remove leaves of {u}"
            ));
        }
        if commit.meta.is_some() {
            return Err(format!("non-admin {me} may not change group_meta"));
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
