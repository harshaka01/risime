//! Runs every case of the shared contract fixture `contract/v1/group_policy_cases.json`
//! (contract v1.9 §12.4) through the core's admin policy. The server suite runs the same file.

use risime_mls::policy::{CommitSummary, MetaChange, check_commit_policy};
use serde::Deserialize;

#[derive(Deserialize)]
struct Fixture {
    v: u32,
    cases: Vec<Case>,
}

#[derive(Deserialize)]
struct Case {
    name: String,
    admins: Vec<String>,
    agents: Vec<String>,
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
    leaf.split_once('/').expect("leaf is user/device").0
}

#[test]
fn every_contract_policy_case() {
    let path = concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../contract/v1/group_policy_cases.json"
    );
    let text = std::fs::read_to_string(path).expect("contract fixture");
    let fixture: Fixture = serde_json::from_str(&text).expect("fixture parses");
    assert_eq!(fixture.v, 1);
    assert!(fixture.cases.len() >= 15, "fixture shrank?");
    let mut failures = vec![];
    for case in &fixture.cases {
        let summary = CommitSummary {
            committer_user: user(&case.committer),
            add_users: case.adds.iter().map(|l| user(l)).collect(),
            remove_users: case.removes.iter().map(|l| user(l)).collect(),
            meta: case.meta.as_ref().map(|m| MetaChange {
                admins: m.admins.clone(),
                name_changed: m.name_changed,
            }),
        };
        let got = check_commit_policy(&case.admins, &case.agents, &summary);
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
        add_users: vec!["X"],
        remove_users: vec![],
        meta: None,
    };
    assert!(check_commit_policy(&admins, &agents, &s).is_err());
}
