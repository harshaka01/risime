//! `group_meta`: the `risime.group_meta` GroupContext extension (contract v1.9 §12.2).
//!
//! UTF-8 JSON `{"v": 1, "name": "…", "icon": null, "admins": ["uuid", …]}`, carried as the
//! GroupContext extension of type [`GROUP_META_EXTENSION`] (0xFA01). Every leaf of a group must
//! list 0xFA01 in its capabilities, and the group's `required_capabilities` names it, so OpenMLS
//! accepts GroupContextExtensions proposals that change it. Unknown JSON fields are kept as they
//! are when the meta is rewritten, so a newer client's fields survive a rename by an older one.
//!
//! v1.24 (§24.1) adds `"tab": "private" | "official"`, `"chat_id"` and `"agents": [uuid]`. A meta
//! without `tab` (every pre-v1.24 group) is Private, with `chat_id` = the group's own conversation
//! id and `agents: []`. The 1:1 Official's `name` is `null` (read as `""`, written back as `null`).

use serde::{Deserialize, Serialize};
use unicode_segmentation::UnicodeSegmentation;

use crate::policy::Tab;
use crate::{MlsError, Result};

/// The extension type of `risime.group_meta` (also the leaf capability that marks a groups-capable
/// key package).
pub const GROUP_META_EXTENSION: u16 = 0xFA01;

/// The longest group name, in grapheme clusters.
pub const MAX_GROUP_NAME: usize = 100;

/// Upper bound on the encoded meta (sanity; a 256-admin list is about 10 KiB).
pub const MAX_GROUP_META_BYTES: usize = 32 * 1024;

/// The decoded `group_meta`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GroupMeta {
    pub v: u32,
    /// `""` stands for `null` (only a 1:1 Official has no name; clients show the peer's).
    #[serde(deserialize_with = "name_de", serialize_with = "name_se")]
    pub name: String,
    /// `null` until the images slice defines it (a blob reference with its key). Kept verbatim.
    #[serde(default)]
    pub icon: Option<serde_json::Value>,
    pub admins: Vec<String>,
    /// v1.24: `"private"` or `"official"`; `None` (absent) is Private. Use [`GroupMeta::tab`].
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tab: Option<String>,
    /// v1.24: the chat's id; `None` (absent) is the group's own conversation id.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub chat_id: Option<String>,
    /// v1.24: the agent users of the group; `None` (absent) is `[]`. Use [`GroupMeta::agents`].
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub agents: Option<Vec<String>>,
    /// v1.25 (§25.2): `"risi"` marks the user's Risi chat; `None` (absent) is not a Risi chat.
    /// Immutable after epoch 0. Use [`GroupMeta::is_risi`].
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub chat_kind: Option<String>,
    /// Fields this version doesn't know, preserved on rewrite.
    #[serde(flatten)]
    pub extra: serde_json::Map<String, serde_json::Value>,
}

impl GroupMeta {
    /// A v1 meta with no icon.
    pub fn new(name: impl Into<String>, admins: Vec<String>) -> Self {
        Self {
            v: 1,
            name: name.into(),
            icon: None,
            admins,
            tab: None,
            chat_id: None,
            agents: None,
            chat_kind: None,
            extra: Default::default(),
        }
    }

    /// The same meta with the v1.24 tab fields set.
    pub fn with_tab(mut self, tab: Tab, chat_id: impl Into<String>, agents: Vec<String>) -> Self {
        self.tab = Some(tab.as_str().to_string());
        self.chat_id = Some(chat_id.into());
        self.agents = Some(agents);
        self
    }

    /// The same meta marked with a `chat_kind` (`"risi"`, §25.2).
    pub fn with_chat_kind(mut self, kind: impl Into<String>) -> Self {
        self.chat_kind = Some(kind.into());
        self
    }

    pub fn is_risi(&self) -> bool {
        self.chat_kind.as_deref() == Some("risi")
    }

    /// The tab: absent (or anything [`GroupMeta::validate`] would reject) is Private.
    pub fn tab(&self) -> Tab {
        match self.tab.as_deref() {
            Some("official") => Tab::Official,
            _ => Tab::Private,
        }
    }

    /// The agent users (`[]` when absent).
    pub fn agents(&self) -> &[String] {
        self.agents.as_deref().unwrap_or(&[])
    }

    /// The chat id, or `own_conversation_id` when absent (a pre-v1.24 group is its own chat).
    pub fn chat_id_or<'a>(&'a self, own_conversation_id: &'a str) -> &'a str {
        self.chat_id.as_deref().unwrap_or(own_conversation_id)
    }

    pub fn is_agent(&self, user_id: &str) -> bool {
        self.agents().iter().any(|a| a == user_id)
    }

    /// Check the contract's rules: `v` ≥ 1, a name of 1–100 grapheme clusters, a non-empty admin
    /// list of distinct, well-formed user ids. v1.24: `tab` is `private` or `official`; a Private
    /// meta has no agents; `agents` are distinct, well-formed and never name an admin; only an
    /// Official meta may have no name (the 1:1 Official).
    pub fn validate(&self) -> Result<()> {
        if self.v == 0 {
            return Err(MlsError::Malformed("group_meta: v must be >= 1".into()));
        }
        if let Some(t) = &self.tab
            && t != "private"
            && t != "official"
        {
            return Err(MlsError::Malformed(format!(
                "group_meta: unknown tab {t:?}"
            )));
        }
        if let Some(c) = &self.chat_id
            && (c.is_empty() || c.len() > 256 || c.chars().any(char::is_control))
        {
            return Err(MlsError::Malformed("group_meta: bad chat_id".into()));
        }
        let unnamed_official = self.name.is_empty() && self.tab() == Tab::Official;
        let n = self.name.graphemes(true).count();
        if !unnamed_official && (n == 0 || n > MAX_GROUP_NAME || self.name.trim().is_empty()) {
            return Err(MlsError::Malformed(format!(
                "group_meta: name must be 1..={MAX_GROUP_NAME} grapheme clusters"
            )));
        }
        if self.admins.is_empty() {
            return Err(MlsError::PolicyViolation(
                "group_meta: the admin list may not be empty".into(),
            ));
        }
        for (i, a) in self.admins.iter().enumerate() {
            if a.is_empty() || a.contains('/') || a.len() > 128 {
                return Err(MlsError::Malformed(format!(
                    "group_meta: bad admin id {a:?}"
                )));
            }
            if self.admins[..i].contains(a) {
                return Err(MlsError::Malformed(format!(
                    "group_meta: duplicate admin {a}"
                )));
            }
        }
        let agents = self.agents();
        if self.tab() == Tab::Private && !agents.is_empty() {
            return Err(MlsError::PolicyViolation(
                "group_meta: a Private group has no agents".into(),
            ));
        }
        for (i, a) in agents.iter().enumerate() {
            if a.is_empty() || a.contains('/') || a.len() > 128 {
                return Err(MlsError::Malformed(format!(
                    "group_meta: bad agent id {a:?}"
                )));
            }
            if agents[..i].contains(a) {
                return Err(MlsError::Malformed(format!(
                    "group_meta: duplicate agent {a}"
                )));
            }
            if self.is_admin(a) {
                return Err(MlsError::PolicyViolation(format!(
                    "group_meta: agent {a} can't be an admin"
                )));
            }
        }
        if let Some(k) = &self.chat_kind
            && (k.is_empty() || k.len() > 32 || k.chars().any(char::is_control))
        {
            return Err(MlsError::Malformed("group_meta: bad chat_kind".into()));
        }
        if self.is_risi() {
            if self.tab() != Tab::Official {
                return Err(MlsError::PolicyViolation(
                    "group_meta: a Risi chat is in the Official tab".into(),
                ));
            }
            if self.admins.len() != 1 || agents.len() != 1 {
                return Err(MlsError::PolicyViolation(
                    "group_meta: a Risi chat has one admin (its user) and one agent".into(),
                ));
            }
        }
        Ok(())
    }

    /// A Risi chat's `chat_id` is the group's own conversation id (§25.2); other metas pass.
    pub fn validate_chat_id(&self, own_conversation_id: &str) -> Result<()> {
        if self.is_risi() && self.chat_id_or(own_conversation_id) != own_conversation_id {
            return Err(MlsError::PolicyViolation(
                "group_meta: a Risi chat's chat_id is its own conversation id".into(),
            ));
        }
        Ok(())
    }

    pub fn to_bytes(&self) -> Result<Vec<u8>> {
        self.validate()?;
        let bytes = serde_json::to_vec(self).map_err(|e| MlsError::Malformed(e.to_string()))?;
        if bytes.len() > MAX_GROUP_META_BYTES {
            return Err(MlsError::Malformed("group_meta too large".into()));
        }
        Ok(bytes)
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self> {
        if bytes.len() > MAX_GROUP_META_BYTES {
            return Err(MlsError::Malformed("group_meta too large".into()));
        }
        let meta: Self = serde_json::from_slice(bytes)
            .map_err(|e| MlsError::Malformed(format!("group_meta: {e}")))?;
        meta.validate()?;
        Ok(meta)
    }

    pub fn is_admin(&self, user_id: &str) -> bool {
        self.admins.iter().any(|a| a == user_id)
    }

    /// Whether `other` has the same admins, as a set.
    pub fn same_admins(&self, other: &GroupMeta) -> bool {
        self.admins.len() == other.admins.len() && self.admins.iter().all(|a| other.is_admin(a))
    }
}

fn name_de<'de, D: serde::Deserializer<'de>>(d: D) -> std::result::Result<String, D::Error> {
    Ok(Option::<String>::deserialize(d)?.unwrap_or_default())
}

fn name_se<S: serde::Serializer>(name: &str, s: S) -> std::result::Result<S::Ok, S::Error> {
    if name.is_empty() {
        s.serialize_none()
    } else {
        s.serialize_str(name)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn contract_example_round_trips() {
        let json = br#"{"v":1,"name":"Rise launch","icon":null,"admins":["a","b"],"later":7}"#;
        let m = GroupMeta::from_bytes(json).unwrap();
        assert_eq!(m.name, "Rise launch");
        assert_eq!(m.admins, vec!["a", "b"]);
        let again = GroupMeta::from_bytes(&m.to_bytes().unwrap()).unwrap();
        assert_eq!(again, m);
        assert_eq!(again.extra["later"], 7);
    }

    #[test]
    fn names_count_grapheme_clusters() {
        let family = "👨‍👩‍👧‍👦"; // one cluster, many code points
        assert!(
            GroupMeta::new(family.repeat(100), vec!["a".into()])
                .validate()
                .is_ok()
        );
        assert!(
            GroupMeta::new(family.repeat(101), vec!["a".into()])
                .validate()
                .is_err()
        );
        assert!(GroupMeta::new("", vec!["a".into()]).validate().is_err());
        assert!(GroupMeta::new("  ", vec!["a".into()]).validate().is_err());
    }

    #[test]
    fn admins_must_be_present_and_distinct() {
        assert!(matches!(
            GroupMeta::new("x", vec![]).validate(),
            Err(MlsError::PolicyViolation(_))
        ));
        assert!(
            GroupMeta::new("x", vec!["a".into(), "a".into()])
                .validate()
                .is_err()
        );
        assert!(GroupMeta::new("x", vec!["a/b".into()]).validate().is_err());
        assert!(GroupMeta::from_bytes(b"{\"v\":1}").is_err());
    }

    #[test]
    fn v124_official_example_parses() {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../contract/v1/examples/group_meta_official.json"
        );
        let m = GroupMeta::from_bytes(&std::fs::read(path).unwrap()).unwrap();
        assert_eq!(m.tab(), Tab::Official);
        assert_eq!(m.agents().len(), 1);
        assert!(m.chat_id.as_deref().unwrap().starts_with("grp:"));
        assert!(m.extra.is_empty());
        let again = GroupMeta::from_bytes(&m.to_bytes().unwrap()).unwrap();
        assert_eq!(again, m);
    }

    #[test]
    fn v124_rules() {
        let pre =
            GroupMeta::from_bytes(br#"{"v":1,"name":"x","icon":null,"admins":["a"]}"#).unwrap();
        assert_eq!(pre.tab(), Tab::Private);
        assert!(pre.agents().is_empty());
        assert_eq!(pre.chat_id_or("grp:1"), "grp:1");
        // Private: no agents.
        let p =
            GroupMeta::new("x", vec!["a".into()]).with_tab(Tab::Private, "grp:1", vec!["r".into()]);
        assert!(matches!(p.validate(), Err(MlsError::PolicyViolation(_))));
        // Official: agents never admins.
        let o = GroupMeta::new("x", vec!["a".into()]).with_tab(
            Tab::Official,
            "grp:1",
            vec!["a".into()],
        );
        assert!(matches!(o.validate(), Err(MlsError::PolicyViolation(_))));
        // Unknown tab.
        assert!(
            GroupMeta::from_bytes(br#"{"v":1,"name":"x","admins":["a"],"tab":"secret"}"#).is_err()
        );
        // 1:1 Official: name null round-trips as null; a Private meta needs a name.
        let dm = br#"{"v":1,"name":null,"icon":null,"admins":["a","b"],"tab":"official","chat_id":"dm:a_b","agents":["r"]}"#;
        let m = GroupMeta::from_bytes(dm).unwrap();
        assert_eq!(m.name, "");
        let out: serde_json::Value = serde_json::from_slice(&m.to_bytes().unwrap()).unwrap();
        assert!(out["name"].is_null());
        assert!(GroupMeta::from_bytes(br#"{"v":1,"name":null,"admins":["a"]}"#).is_err());
    }

    #[test]
    fn v125_risi_rules() {
        let risi = |tab, admins: &[&str], agents: &[&str]| {
            GroupMeta::new("", admins.iter().map(|s| s.to_string()).collect())
                .with_tab(tab, "grp:1", agents.iter().map(|s| s.to_string()).collect())
                .with_chat_kind("risi")
        };
        let ok = risi(Tab::Official, &["a"], &["r"]);
        ok.validate().unwrap();
        ok.validate_chat_id("grp:1").unwrap();
        assert!(ok.validate_chat_id("grp:2").is_err());
        let again = GroupMeta::from_bytes(&ok.to_bytes().unwrap()).unwrap();
        assert!(again.is_risi());
        assert!(risi(Tab::Private, &["a"], &[]).validate().is_err());
        assert!(risi(Tab::Official, &["a", "b"], &["r"]).validate().is_err());
        assert!(risi(Tab::Official, &["a"], &["r", "s"]).validate().is_err());
        assert!(risi(Tab::Official, &["a"], &[]).validate().is_err());
        // Absent means not a Risi chat.
        let m = GroupMeta::from_bytes(br#"{"v":1,"name":"x","admins":["a"]}"#).unwrap();
        assert!(!m.is_risi());
    }
}
