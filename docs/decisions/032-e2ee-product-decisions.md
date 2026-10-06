# 032 — E2EE (0.3) product decisions

**Status:** accepted 2026-10-06 (Harsha: "as recommended"). It answers the open questions in
decision 012, numbers 3–6.

1. **Identity:** in 0.3 the **server attests each device's MLS signature key**. It signs
   `{user_id, device_id, signature_public_key, issued_at}` with a server key, and peers verify that
   binding before accepting a member. Safety-number / QR verification between users comes **later**
   (0.3.x), on top of the attestation.
2. **Multi-device:** **each device is its own MLS member** (leaf). The UI groups a user's devices
   as one person ("Harsha (phone)").
3. **History for new members:** **none by default** (MLS forward secrecy). "Add Risi" (0.5) will
   use an explicit, consented re-encrypt-and-send of a chosen range.
4. **Local history at rest** (beyond the MLS state store `mls.db`): **later, 0.3.x**, not in the
   first E2EE release.

## Consequences
- The server needs a long-lived attestation signing key, kept outside git (like `~/risime-keys`)
  and rotatable. Clients pin its public key, which the server publishes via an endpoint and which
  is also built into the app.
- Device registration (contract v1.5 `devices`) is extended with MLS key material, so one device id
  serves both push and MLS.
- The dev banner is removed only when decision 012's criteria and these four points are
  implemented, tested (including the live interop gate) and recorded.
