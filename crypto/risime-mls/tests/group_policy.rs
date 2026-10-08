//! Runs every case of the shared contract fixture `contract/v1/group_policy_cases.json`
//! (contract v1.9 §12.4, v1.14 §12.4a) through the core's admin policy, and every `tab_cases` case
//! (v1.24 §24.1) through the tab rules. The server suite runs the same file.

use risime_mls::policy::{
    CommitSummary, MetaChange, Tab, TabContext, check_commit_policy, check_tab_policy,
};
use serde::Deserialize;
use std::collections::BTreeMap;

#[derive(Deserialize)]
struct Fixture {
    v: u32,
    cases: Vec<Case>,
    tab_cases: Vec<TabCase>,
}

#[derive(Deserialize)]
struct TabCase {
    name: String,
    conversation: String,
    tab: String,
    admins: Vec<String>,
    agents: Vec<String>,
    agent_users: Vec<String>,
    leaves: BTreeMap<String, Vec<String>>,
    committer: String,
    adds: Vec<String>,
    removes: Vec<String>,
    meta: Option<TabMeta>,
    expect: String,
}

#[derive(Deserialize)]
struct TabMeta {
    admins: Option<Vec<String>>,
    name_changed: bool,
    tab: Option<String>,
    chat_id_changed: bool,
    agents: Option<Vec<String>>,
}

fn tab(s: &str) -> Tab {
    match s {
        "private" => Tab::Private,
        "official" => Tab::Official,
        other => panic!("unknown tab {other}"),
    }
}

#[derive(Deserialize)]
struct Case {
    name: String,
    admins: Vec<String>,
    agents: Vec<String>,
    leaves: BTreeMap<String, Vec<String>>,
    committer: String,
    adds: Vec<String>,
    removes: Vec<String>,
    meta: Option<Meta>,
    expect: String,
}

#[derive(Deserialize)]
struct Meta {
    admins: Option<Vec<String>>,
    name_changed: bool,
}

fn user(leaf: &str) -> &str {
    split(leaf).0
}

fn split(leaf: &str) -> (&str, &str) {
    leaf.split_once('/').expect("leaf is user/device")
}

#[test]
fn every_contract_policy_case() {
    let path = concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../contract/v1/group_policy_cases.json"
    );
    let text = std::fs::read_to_string(path).expect("contract fixture");
    let fixture: Fixture = serde_json::from_str(&text).expect("fixture parses");
    assert_eq!(fixture.v, 2);
    assert!(fixture.cases.len() >= 26, "fixture shrank?");
    let mut failures = vec![];
    for case in &fixture.cases {
        let summary = CommitSummary {
            committer_user: user(&case.committer),
            adds: case.adds.iter().map(|l| split(l)).collect(),
            removes: case.removes.iter().map(|l| split(l)).collect(),
            leaf_users: case
                .leaves
                .iter()
                .filter(|(_, ds)| !ds.is_empty())
                .map(|(u, _)| u.as_str())
                .collect(),
            meta: case
                .meta
                .as_ref()
                .map(|m| MetaChange::admins_or_name(m.admins.clone(), m.name_changed)),
        };
        let got = check_commit_policy(&case.admins, &case.agents, &summary);
        // v1.24: these are Official groups (they list an agent) and keep their expectations
        // under the tab rules too, with every listed agent's leaves attested as agent leaves.
        let leaves: Vec<(String, String)> = case
            .leaves
            .iter()
            .flat_map(|(u, ds)| ds.iter().map(move |d| (u.clone(), d.clone())))
            .collect();
        let ctx = TabContext {
            dm: false,
            tab: Tab::Official,
            agents: &case.agents,
            agent_users: case.agents.iter().map(String::as_str).collect(),
            leaves: leaves
                .iter()
                .map(|(u, d)| (u.as_str(), d.as_str()))
                .collect(),
        };
        let got_tab = check_tab_policy(&case.admins, &ctx, &summary);
        if got.is_ok() != got_tab.is_ok() {
            failures.push(format!(
                "{} (as Official): {got:?} but the tab rules say {got_tab:?}",
                case.name
            ));
        }
        let ok = match case.expect.as_str() {
            "accept" => got.is_ok(),
            "reject" => got.is_err(),
            other => panic!("unknown expectation {other}"),
        };
        if !ok {
            failures.push(format!(
                "{}: expected {}, got {got:?}",
                case.name, case.expect
            ));
        }
    }
    assert!(failures.is_empty(), "{failures:#?}");
}

#[test]
fn every_contract_tab_case() {
    let path = concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../contract/v1/group_policy_cases.json"
    );
    let text = std::fs::read_to_string(path).expect("contract fixture");
    let fixture: Fixture = serde_json::from_str(&text).expect("fixture parses");
    assert!(fixture.tab_cases.len() >= 18, "tab fixture shrank?");
    let mut failures = vec![];
    for case in &fixture.tab_cases {
        let leaves: Vec<(&str, &str)> = case
            .leaves
            .iter()
            .flat_map(|(u, ds)| ds.iter().map(move |d| (u.as_str(), d.as_str())))
            .collect();
        let summary = CommitSummary {
            committer_user: user(&case.committer),
            adds: case.adds.iter().map(|l| split(l)).collect(),
            removes: case.removes.iter().map(|l| split(l)).collect(),
            leaf_users: case
                .leaves
                .iter()
                .filter(|(_, ds)| !ds.is_empty())
                .map(|(u, _)| u.as_str())
                .collect(),
            meta: case.meta.as_ref().map(|m| MetaChange {
                admins: m.admins.clone(),
                name_changed: m.name_changed,
                tab: m.tab.as_deref().map(tab),
                chat_id_changed: m.chat_id_changed,
                agents: m.agents.clone(),
            }),
        };
        let ctx = TabContext {
            dm: match case.conversation.as_str() {
                "dm" => true,
                "grp" => false,
                other => panic!("unknown conversation {other}"),
            },
            tab: tab(&case.tab),
            agents: &case.agents,
            agent_users: case.agent_users.iter().map(String::as_str).collect(),
            leaves,
        };
        let got = check_tab_policy(&case.admins, &ctx, &summary);
        let ok = match case.expect.as_str() {
            "accept" => got.is_ok(),
            "reject" => got.is_err(),
            other => panic!("unknown expectation {other}"),
        };
        if !ok {
            failures.push(format!(
                "{}: expected {}, got {got:?}",
                case.name, case.expect
            ));
        }
    }
    assert!(failures.is_empty(), "{failures:#?}");
}

#[test]
fn agents_are_never_admins() {
    let admins = vec!["R".to_string()];
    let agents = vec!["R".to_string()];
    let s = CommitSummary {
        committer_user: "R",
        adds: vec![],
        removes: vec![],
        leaf_users: vec!["R", "X"],
        meta: Some(MetaChange::admins_or_name(None, true)),
    };
    assert!(check_commit_policy(&admins, &agents, &s).is_err());
}
