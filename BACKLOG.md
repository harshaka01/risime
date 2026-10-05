# RisiMe Backlog (ordered)

- **0.1:** one-to-one chat (docs/RELEASE-0.1.md)
- **0.2:**
  - FCM push (needs Harsha's Firebase project);
  - presence and last seen, typing indicator;
  - real SMTP for OTP email;
  - prod environment (separate containers, systemd, nightly off-box backups);
  - Tailscale access for team phones;
  - Oban;
  - a design system pass.
- **0.3:** E2EE:
  - a Rust core with OpenMLS, connected to Android via UniFFI;
  - key packages and device linking;
  - the server stores only ciphertext;
  - remove the dev banner.
- **0.4:**
  - groups (MLS groups);
  - files and media (Rust processing), voice notes;
  - monthly bucketing of inbox partitions.
- **0.5:** "Add Risi":
  - the agent as a visible MLS member, with a consent banner and consented history share;
  - direct chat with Risi;
  - agent harness v0 (model router with commercial and own models, MCP tools, pgvector memory,
    learning log in Cassandra).
- **0.6:** Commitment Ledger:
  - detection, then ✓ / ✗ / edit confirmation;
  - Oban follow-ups;
  - verification connectors (Git, Risi ERP, calendar);
  - daily open-promises digest.
- **0.7:** on-device client agent:
  - Gemini Nano, or llama.cpp/ExecuTorch as fallback;
  - learning from the behaviour log;
  - the twin as a linked device (opt-in).
- **0.8:**
  - a projects view built from commitments;
  - twin in draft mode, with per-category autonomy levels.
- **Later:** SMS OTP via Notify.lk · iOS (Kotlin Multiplatform) · calls via LiveKit ·
  A2A/MCP agent marketplace.
