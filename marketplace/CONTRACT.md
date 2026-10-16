# Contract

The source of truth for this project's wire is the **RisiBridge** repository (private, `harshaka01/risibridge`), not
this file:

- `contracts/agent-connector.md`: the registration record, the card and `x-risi` block, signing and re-checking,
  tiers, discovery, calling, "data, never instructions", the sandbox suite, limits, audit and delisting. Version 0.1
  plus the RisiMe architect's review of 2026-10-16 21:55 (changes 1–9).
- `contracts/ask-skill.md`: RisiWork Ask as the first Official agent. Version 0.1 plus the review of 2026-10-16 21:50.

Both are drafts until both architects write "agreed". Where this code had to pick a detail the drafts leave open, it
is listed below so the bridge can confirm or change it. Anything here that disagrees with the bridge is a bug here.

## Interpretations awaiting confirmation in the bridge

1. **Where the signature lives.** Top-level `signatures` array in the A2A `AgentCardSignature` shape:
   `[{"protected": "<base64url JWS header>", "signature": "<base64url>"}]`. Header must carry `alg` and `kid`;
   `b64: false` is refused. At most 4 signatures are tried.
2. **What is signed and hashed.** RFC 8785 canonical form of the card with `signatures` removed. `card_hash` is the
   lowercase hex SHA-256 of exactly those bytes, so re-signing keeps the hash (review change 2).
3. **Algorithms.** `ES256` and `EdDSA` (Ed25519) only.
4. **Key set.** `https://<domain>/.well-known/risi-keys.json` is a JWK set (`{"keys": [...]}`); each key has `kid`,
   optional `alg`, optional `expires` (RFC 3339). Keys must be `EC` or `OKP` public keys; a set that contains
   private members is refused whole. A key that disappears from the set is retired and never used again.
5. **Card location.** `card_url` must be https and on the record's `domain`.
6. **Size caps.** Cards and key sets: 64 KB. Sandbox answers: 32 KB.
7. **Read-only API (loopback):**
   - `GET /api/v1/agents?skill=&tier=&q=`: discovery; only `active` + `verified` agents; registry records only.
   - `GET /api/v1/agents/<domain>/<slug>`: one record (any status).
   - `GET /api/v1/agents/<domain>/<slug>/status`: the check a caller runs **before every call**:
     `{"agent_id", "callable", "status", "verification", "reason"?, "checked_at"}`. Status is read from the database on
     every request; the card's signature and hash are re-checked through a 5-minute cache.
8. **Sandbox probes** use the Ask request shape (`ask-skill.md` section 3) with `"channel": "sandbox"`; answers are
   judged against the Ask answer shape (`answer_text`, `cards[].type`, `sources[]`).
