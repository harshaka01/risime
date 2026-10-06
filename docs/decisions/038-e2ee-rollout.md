# 038 — E2EE rollout on the pilot

**Status:** accepted 2026-10-06 (Harsha: "roll out E2EE").

## What was done
1. **The pilot attestation key** was created once with `mix risime.attestation.gen`:
   - private key `~/risime-keys/attestation_ed25519.jwk` (mode 600, outside git);
   - kid `x-nu0EBHzGv-zrSXLMG5dz-ncRW9Z-Wv7m6KWMPmGTM` (the RFC 7638 thumbprint).
   **Back it up with the release keystore.** Losing it means re-attesting every device under a new
   key, which needs an app release that pins the new one.
2. **Its public JWK is committed** at `infra/pilot/attestation-public.jwk` (public by design).
   `scripts/nightly-release` pins it into every build (`-Prisime.mlsPinnedKeys`) and refuses to
   publish a release APK that doesn't contain its kid.
3. **v0.2.0-nightly.8 is published as `required`.** Older apps are blocked until they update.
   The updater keeps "Update" and "Sign out" available.
4. The server picks up the key at boot, so with that deploy **E2EE is on**:
   - `/mls/attestation_keys` serves the key;
   - devices register their MLS identity;
   - each conversation upgrades automatically once the census shows every app instance of both
     members is MLS-capable (decision 033).
   Until then it stays plaintext, with "needs to update".

## Not yet
- **The dev banner stays.** It's removed only when every conversation of the user is e2ee,
  plaintext is no longer offered, every allowed app version speaks v1.7, the live gate covers
  E2EE (it does: 9 checks) and a decision records it.
- QR safety numbers (0.3.x) and local history at rest (0.3.x), per decision 032.

## Rollback
`scripts/rollback` reverts the server code. To switch E2EE off server-side, move the key file away
and restart: MLS endpoints then return `503 mls_unavailable`. **Conversations already e2ee stay
e2ee:** there is no downgrade, so only switch it off together with a fix-forward.
