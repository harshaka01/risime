# 039 — The MLS application payload envelope

## Context
Encrypted reactions come next, then groups and images, all carried inside MLS application
messages. Every app from nightly.8 on (the first with E2EE) must handle those future types
safely instead of showing them as text.

## Decision
- **Sending:** the MLS plaintext is UTF-8 JSON `{"v":1,"type":"text","body":"…"}`.
- **Receiving** (`MlsPayload.decode`):
  - **A JSON object with a string `type`:**
    - `"text"` → its `body` (extra fields and other `v` values are tolerated);
    - **any other `type` → store nothing visible and ignore it** (logged). Reactions, group
      events and images arrive later as new types.
  - **Anything else** (not JSON, not an object, no string `type`) → legacy: the whole plaintext is
    the text body.
- The outbox wraps every encrypted send in the envelope, and the pipeline decodes every decrypted
  message through it.
- Root adds the envelope to contract §10.3 as part of v1.7.

## Consequences
- A pre-envelope sender's messages still read as text.
- A message that is literally JSON with a string `type` field would be read as an envelope. This
  is accepted; such text is vanishingly rare, and all senders from nightly.8 on use the envelope.
