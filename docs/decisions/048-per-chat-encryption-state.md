# 048 — Replace the global dev banner with the real per-chat encryption state

- **Status:** accepted (2026-10-06)
- **Decided by:** Harsha (product owner). He asked for Settings → About and the banner to
  "reflect the real per-chat state".
- **Supersedes:** the CLAUDE.md rule "Never remove this banner before E2EE ships", and the banner
  part of decisions 012 and 038.

## Context
- E2EE with MLS has been on since nightly.8, for 1:1 chats, and for groups since nightly.10.
- The app still showed a global "Dev build — not end-to-end encrypted" banner on every screen,
  and About still said "MLS arrives in 0.3". For encrypted chats both are false, and they confuse
  testers.

## Decision
- **Banner:** remove the global banner.
- **Each chat shows its real state:**
  - **E2EE:** a lock in the header, and "Messages are end-to-end encrypted" in chat info.
  - **Not E2EE yet:** the existing strip "Not end-to-end encrypted yet: …", with the *real* reason
    (an old app, a phone not online yet, encryption setup not finished, …).
- **About** says: "Chats are end-to-end encrypted (MLS) once everyone in them runs a current
  RisiMe. Each chat shows its state."
- **Honesty rule:** the app never claims encryption for a chat that isn't encrypted. Any
  plaintext chat must show the strip.

## Consequences
- CLAUDE.md's architecture rule changes to the per-chat rule above.
- Plaintext legacy chats remain until everyone updates. They're always labelled.
