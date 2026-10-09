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
//!
//! **v1.24 tab rules (§24.1, [`check_tab_policy`])**, on top of the rules above:
//! 1. `tab` and `chat_id` never change after epoch 0.
//! 2. A Private group (or one without `tab`) has `agents == []` and never gains an agent leaf.
//! 3. A `dm:` group never gains an agent leaf.
//! 4. In Official an agent leaf may be added only by an admin and only for a user in `agents`;
//!    `agents` never names an admin.
//! 5. Any member may remove **every** leaf of an agent user (never a part of them) in a commit that
//!    otherwise touches only the committer's own leaves and changes no `group_meta`.
//!
//! **v1.25 (§25.2)**, when the base epoch's `chat_kind` is `"risi"`: `chat_kind`, `admins` and
//! `agents` never change, and no commit adds a leaf of any user but the one admin and the agent.
//!
//! The fixture runs both: `cases` through [`check_commit_policy`], `tab_cases` through
//! [`check_tab_policy`].

/// The tab of a conversation (§24.1). An absent `group_meta.tab` is Private.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum Tab {
    #[default]
    Private,
    Official,
}

impl Tab {
    pub fn as_str(self) -> &'static str {
        match self {
            Tab::Private => "private",
            Tab::Official => "official",
        }
    }
}

/// A `group_meta` change carried by a commit.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MetaChange {
    /// The new admin list, or `None` if the admins are unchanged.
    pub admins: Option<Vec<String>>,
    /// The name or icon changed.
    pub name_changed: bool,
    /// v1.24: the new tab, or `None` if unchanged (absent counts as Private).
    pub tab: Option<Tab>,
    /// v1.24: the chat id changed (absent counts as the group's own conversation id).
    pub chat_id_changed: bool,
    /// v1.24: the new agent list, or `None` if unchanged (absent counts as `[]`).
    pub agents: Option<Vec<String>>,
    /// v1.25: `chat_kind` changed (absent counts as not `"risi"`).
    pub chat_kind_changed: bool,
}

impl MetaChange {
    /// A change of the admin list and/or the name or icon only (the pre-v1.24 fields).
    pub fn admins_or_name(admins: Option<Vec<String>>, name_changed: bool) -> Self {
        Self {
            admins,
            name_changed,
            tab: None,
            chat_id_changed: false,
            agents: None,
            chat_kind_changed: false,
        }
    }
}

/// The v1.24 context of a commit (§24.1): what the base epoch says about tabs and agents.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TabContext<'a> {
    /// A `dm:` group (any non-`grp:` group): no agent leaf, ever.
    pub dm: bool,
    /// The base epoch's `group_meta.tab` (Private for a `dm:` group).
    pub tab: Tab,
    /// v1.25 (§25.2): the base epoch's `group_meta.chat_kind` is `"risi"`.
    pub risi: bool,
    /// The base epoch's `group_meta.agents`.
    pub agents: &'a [String],
    /// The users with a leaf whose attestation says `kind: "agent"`, in the base epoch or among the
    /// commit's added leaves (a leaf of such a user is an agent leaf).
    pub agent_users: Vec<&'a str>,
    /// The base epoch's leaves.
    pub leaves: Vec<Leaf<'a>>,
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

/// `Ok` if the commit is allowed under the v1.24 tab rules (§24.1) and the admin rules of
/// [`check_commit_policy`], otherwise the reason.
pub fn check_tab_policy(
    admins: &[String],
    ctx: &TabContext<'_>,
    commit: &CommitSummary<'_>,
) -> std::result::Result<(), String> {
    let me = commit.committer_user;
    let new_agents: &[String] = commit
        .meta
        .as_ref()
        .and_then(|m| m.agents.as_deref())
        .unwrap_or(ctx.agents);
    // Every user that is an agent by any account: listed (before or after) or attested.
    let mut agents: Vec<String> = ctx.agents.to_vec();
    for a in new_agents
        .iter()
        .map(String::as_str)
        .chain(ctx.agent_users.iter().copied())
    {
        if !agents.iter().any(|x| x == a) {
            agents.push(a.to_string());
        }
    }
    let is_agent = |u: &str| agents.iter().any(|a| a == u);
    let committer_is_admin = is_admin(admins, &agents, me);
    let is_agent_leaf = |u: &str| ctx.agent_users.contains(&u);

    // Rule 1: tab and chat_id are immutable.
    if let Some(m) = &commit.meta {
        if m.tab.is_some_and(|t| t != ctx.tab) {
            return Err("tab can't change after epoch 0".into());
        }
        if m.chat_id_changed {
            return Err("chat_id can't change after epoch 0".into());
        }
    }

    // Risi chat (§25.2): the roster is fixed to its one human and its one agent.
    if ctx.risi {
        if ctx.dm || ctx.tab != Tab::Official {
            return Err("a Risi chat is a grp: group in the Official tab".into());
        }
        if let Some(m) = &commit.meta {
            if m.chat_kind_changed {
                return Err("chat_kind can't change after epoch 0".into());
            }
            if m.admins.as_ref().is_some_and(|new| {
                new.len() != admins.len() || !new.iter().all(|a| admins.contains(a))
            }) {
                return Err("a Risi chat's only admin is its user".into());
            }
            if m.agents.as_deref().is_some_and(|new| new != ctx.agents) {
                return Err("a Risi chat's agents can't change".into());
            }
        }
        for &(u, d) in &commit.adds {
            if !admins.iter().any(|a| a == u) && !ctx.agents.iter().any(|a| a == u) {
                return Err(format!(
                    "a Risi chat has no second user: may not add {u}/{d}"
                ));
            }
        }
    }

    // Rules 2 and 3: no agents in Private or dm:.
    if ctx.dm || ctx.tab == Tab::Private {
        let what = if ctx.dm {
            "a dm: group"
        } else {
            "a Private group"
        };
        if !new_agents.is_empty() {
            return Err(format!("{what} has no agents"));
        }
        if let Some((u, d)) = commit.adds.iter().find(|(u, _)| is_agent_leaf(u)) {
            return Err(format!("{what} never gains an agent leaf ({u}/{d})"));
        }
        if ctx.dm {
            // The admin rules are for grp: groups; a dm: group has no group_meta.
            return Ok(());
        }
        return check_commit_policy(admins, &agents, commit);
    }

    // Official. Rule 4: agents never names an admin (after the commit).
    let new_admins: &[String] = commit
        .meta
        .as_ref()
        .and_then(|m| m.admins.as_deref())
        .unwrap_or(admins);
    if let Some(a) = new_agents.iter().find(|a| new_admins.contains(a)) {
        return Err(format!("agent {a} can't be an admin"));
    }
    // Rule 4: an agent leaf only by an admin, only for a listed agent.
    for &(u, d) in &commit.adds {
        if !is_agent_leaf(u) {
            continue;
        }
        if !committer_is_admin {
            return Err(format!("non-admin {me} may not add agent leaf {u}/{d}"));
        }
        if !ctx.agents.iter().chain(new_agents).any(|a| a == u) {
            return Err(format!("{u} is not in agents: may not add {u}/{d}"));
        }
    }

    // Rule 5: any member may remove every leaf of an agent.
    if !committer_is_admin {
        let removed_agents: Vec<&str> = commit
            .removes
            .iter()
            .map(|&(u, _)| u)
            .filter(|u| *u != me && is_agent(u))
            .collect();
        if !removed_agents.is_empty() {
            for &a in &removed_agents {
                let partial = ctx
                    .leaves
                    .iter()
                    .any(|l| l.0 == a && !commit.removes.contains(l));
                if partial {
                    return Err(format!(
                        "non-admin {me} may remove agent {a} only with all of its leaves"
                    ));
                }
            }
            if commit.meta.is_some() {
                return Err(format!(
                    "non-admin {me} removing an agent may not change group_meta"
                ));
            }
            let other = commit
                .adds
                .iter()
                .chain(commit.removes.iter().filter(|(u, _)| !is_agent(u)))
                .find(|(u, _)| *u != me);
            if let Some((u, d)) = other {
                return Err(format!(
                    "non-admin {me} removing an agent may change only its own leaves, not {u}/{d}"
                ));
            }
            return Ok(());
        }
    }
    check_commit_policy(admins, &agents, commit)
}
