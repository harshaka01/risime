# 001 · A separable registry

**Date:** 2026-10-16. **Decided by:** Harsha 21:53 and 21:56 SLT, via the RisiMe architect's 22:05 and 22:10 entries
(RisiMe decision 095).

**Decision.** The Risi Marketplace registry is its own project, in the top-level folder `marketplace/` of the RisiMe
repository for now, built so that `git subtree split --prefix=marketplace` can move it to its own repository, root
and architect with no change to RisiMe.

- **Own app:** Elixir/Phoenix, OTP app `:risi_registry`, module `RisiRegistry`, its own `mix.exs`, lock file, config,
  tests and test gate (`mix format --check-formatted && mix compile --warnings-as-errors && mix test`).
- **No shared code:** it never imports from RisiMe's `server/`, and RisiMe never imports from it. They meet only over
  HTTP, as the RisiBridge contracts say (`CONTRACT.md`).
- **Own data:** Postgres databases `risi_registry_dev` / `risi_registry_test` (prod: `risi_registry_prod`), owned by
  their own role `risi_registry`, on the existing dev Postgres container. No tables in RisiMe's databases.
- **Own docs:** this folder's `CLAUDE.md`, `STATUS.md`, `CONTRACT.md` and `docs/decisions/` (numbered from 001).
- **Runtime:** loopback only (127.0.0.1:4600), its own systemd user unit (`rel/risi-registry.service`, not installed
  yet), no Erlang distribution (`RELEASE_DISTRIBUTION=none`, `ERL_EPMD_ADDRESS=127.0.0.1`), multi-arch only.

**Choices made inside the project.**
- **Pages without LiveView or a JS build.** The phase 1 page shell is server-rendered EEx through
  `Phoenix.HTML.Engine` (escaped by default), no JavaScript, no external assets, strict CSP. Fewer dependencies,
  nothing to build on ARM64, easy to audit. Revisit if the owner registration form needs live validation.
- **Card signatures in the A2A `signatures` array.** The card carries `signatures: [{protected, signature}]` (the A2A
  `AgentCardSignature` shape). The signed payload and the `card_hash` are both the RFC 8785 canonical form of the card
  without `signatures`. To be confirmed in the bridge (STATUS.md, open items).
- **Accepted signature algorithms:** `ES256` and `EdDSA` for cards; `RS256`, `ES256`, `PS256` for Keycloak ID tokens.
- **The allow-list is a table**, seeded once from the `ALLOW_LIST_SEED` environment variable, never written in code or
  docs.

**Consequences.** Two Phoenix apps on spark2 instead of one; a little duplicated boilerplate (the `.env` reader, the
release task). That's the price of a clean split.
