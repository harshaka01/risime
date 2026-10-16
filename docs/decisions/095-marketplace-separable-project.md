# 095 · The marketplace is a separable top-level project

**Date:** 2026-10-16. **Decided by:** Harsha 21:53 SLT ("build the marketplace now but in a different folder structure
where we can take it out from risime project in the future and get a different root and architect to handle it"),
via the architect's 22:05 and 22:10 entries.

**Decision.** The agent marketplace / registry lives entirely under `marketplace/`: its own Phoenix app (`:risi_registry`,
`RisiRegistry`), mix project, tests, Postgres database (`risi_registry_dev` / `_test`), docs, decisions (from 001),
STATUS.md, CONTRACT.md and CLAUDE.md, and its own session role `marketplace`. It never imports `server/` and RisiMe
never imports it: they meet only over HTTP, per the RisiBridge contracts (`contracts/agent-connector.md`,
`contracts/ask-skill.md`). Loopback only (golden rules 6–7) until the public route is agreed. So `git subtree split
--prefix=marketplace` can move it to its own repo, root and architect without changes to RisiMe.

**Outside the folder, root-owned only:** the role row in CLAUDE.md and this note.

**Public address (22:10):** risicloud.ai/marketplace, sign-in via Keycloak realm `aoa` with its own client
`risi-marketplace` (authorization code + PKCE, public client), allow-listed accounts only (the list in the
marketplace's own data, never hard-coded). risicloud.ai is RisiWork's Caddy on spark; the link to spark2 is proposed
in needs-you.
