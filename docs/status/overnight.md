# Where work stopped (root's restart log)

Rule (Harsha 2026-10-14 11:50 SLT): if the session limit hits, commit what's green, note here where each piece
stopped, and continue after the reset. At most 2 agents at a time; Sonnet for routine helpers, Opus for contract,
crypto, architecture and tricky debugging.

## 2026-10-14 ~12:15 SLT (06:45 UTC), after the 11:20 SLT limit
main is clean: compile with warnings-as-errors and format pass; no half-done work on main.

| Work | State | Where it is | Next |
|---|---|---|---|
| Send-for-me + plain skills page + permission fix-it | running (slot 1) | main: 7d034fe3, ea2b2d44, 612dc5c0, 6599d5b6 | finish Android fork tests, fast-lane server part |
| Contract v1.49 bundle | running (slot 2) | its worktree | push v1.49 → then nightly.8 |
| Private transcribe/translate | stopped (waiting for a slot) | worktree agent-a0c45d2e… (uncommitted) | resume 3rd |
| si/ta text-brain eval | stopped | worktree agent-a2b43577… | resume 4th (GPU loads only in the night window) |
| Calendar V2 + Risi setup guide | stopped just after reading context | worktree agent-a6c51a23… (nothing written) | after the four above (Harsha's order) |
| Learning-log text (item 0) | server commit 5262d642 on its branch; push blocked by the permission system | branch worktree-agent-afa08236111e704d7 | Harsha ships it (command in the chat); then items 1, 3 |
| Google opt-in for si/ta (phone) | stopped mid-change | worktree agent-ae5a736e… (uncommitted) | resume after the slots free (needed for nightly.8) |
| Console pages 4–7 + Ops agent | stopped while adding the Health section | worktree agent-a30471d8… (rebased, uncommitted split) | resume later |
| Call Risi v1.48 | commits 7ce5b871, acb68a4e on its branch; push blocked | branch worktree-agent-ae8df0ae884bf3514 | Harsha runs the cherry-pick |
| Tamil translate / Wrong language? (Part B) | waiting on its gate | worktree agent-aacca3ea… | — |
