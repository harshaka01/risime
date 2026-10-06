//! `group_meta`: the `risime.group_meta` GroupContext extension (contract v1.9 §12.2).
//!
//! UTF-8 JSON `{"v": 1, "name": "…", "icon": null, "admins": ["uuid", …]}`, carried as the
//! GroupContext extension of type [`GROUP_META_EXTENSION`] (0xFA01). Every leaf of a group must
//! list 0xFA01 in its capabilities, and the group's `required_capabilities` names it, so OpenMLS
//! accepts GroupContextExtensions proposals that change it. Unknown JSON fields are kept as they
//! are when the meta is rewritten, so a newer client's fields survive a rename by an older one.

use serde::{Deserialize, Serialize};
use unicode_segmentation::UnicodeSegmentation;

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
    pub name: String,
    /// `null` until the images slice defines it (a blob reference with its key). Kept verbatim.
    #[serde(default)]
    pub icon: Option<serde_json::Value>,
    pub admins: Vec<String>,
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
            extra: Default::default(),
        }
    }

    /// Check the contract's rules: `v` ≥ 1, a name of 1–100 grapheme clusters, a non-empty admin
    /// list of distinct, well-formed user ids.
    pub fn validate(&self) -> Result<()> {
        if self.v == 0 {
            return Err(MlsError::Malformed("group_meta: v must be >= 1".into()));
        }
        let n = self.name.graphemes(true).count();
        if n == 0 || n > MAX_GROUP_NAME || self.name.trim().is_empty() {
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
}
