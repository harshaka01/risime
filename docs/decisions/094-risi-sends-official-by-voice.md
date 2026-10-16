# 094 · Risi sends Official messages on a voice yes during a Call Risi

**Date:** 2026-10-16. **Decided by:** Harsha (product owner), 15:25 SLT, via the architect's 15:35 entry (A).
**Contract:** v1.55 §41.11 (amends §41.9 and §42.0 for Official conversations during a call only).

**Decision.** On a Call Risi, after Risi reads a message back and the caller says yes, Risi sends it into the
Official 1:1 with that person, as its own visible post labelled "Sent by Risi for Harsha". "Sent." only after the
server stored it. Undo for 2 minutes. Private chats stay out of reach (Risi isn't a member), so they keep the §42
card and the tap. Money, HR, legal matters, accusations and deletion always keep the tap (decision 085).

**Why Risi's own post and not the user's.** §42's rule that the phone sends as the user stays true: the server never
forges a message from the user's leaf. Risi is already an encrypted, visible member of every Official chat (§24), so
its post needs no new trust, no phone round trip during the call, and it is honest about who wrote it.

**Switch.** `RISI_CALL_SEND` (default canary) plus the per-user `voice_send` setting (default on for canary users).
