# Proposal 2026-10-12: the Risi chat after a reinstall (P0 bug D)

Status: implemented on server and android (no wire shape change); folded into PROTOCOL.md v1.38 (2026-10-12).

## Problem
A reinstall of the owner's only phone left the Risi chat at "Rejoining group…" / "Setting up
encryption on this phone…" for ever, its Official tab said "Couldn't start Official (risi_chat)",
and the real Risi chat was hidden behind a second "Risi" entry.

- Nobody can re-add the new device: the Risi chat's only human is the owner and Risi never
  commits, so the rejoin op has `candidates: 0`; the §12.12.3 guard answered `409 rejoin_pending`
  for 24 h and the app (admin rule) waited 24 h.
- After a reset, the rebuilder's `POST /mls/key_packages/claim` with the agent was `403` (agents
  were claimable only for epoch 0 of a `creating` group or a pending `add` op), so no Official
  group could be rebuilt with its agent.
- The app learns "Risi chat" only from MLS `group_meta`; with no MLS state it showed the chat as a
  Private group with a tab bar.

## Amendments (behaviour only; additive)
1. **§12.12.3 server guard**: no `409 rejoin_pending` for a **Risi chat** whose rejoin op has
   `candidates: 0` (the owner may reset at once, as a DM). With a candidate (another current
   `risi_tools` phone of the owner) the guard is unchanged.
2. **§12.12.3 client**: in a Risi chat (server `Group`: `chat_kind: "risi"`, `tab: "official"`,
   `chat_id` = its id) an admin device resets at once on `candidates: 0`. A group awaiting a
   rebuild (`epoch: null`) is never reset again from a rejoin reply.
3. **§12.5 claim**: an agent member's key package may be claimed for an Official group that awaits
   its rebuild (no MLS group) by the user named on its `rebuild` op.
4. **§12.8 rebuild of a Risi chat with no local state**: `group_meta` is the §25.2 Risi-chat meta
   (the core enforces its shape), never a Private group named "Group".
5. **§25.2 client display**: a group the server lists as the user's Risi chat, while the device holds
   no MLS state for it, is shown as the Risi chat (its list row and screen; no tab bar, no Start
   Official). MLS state wins as soon as it exists; the hint never makes a conversation Official for
   any agent path.

Gate: `scripts/ui-entry-test --risi-reinstall` (UITEST_REINSTALL_ONLY=1), step 20.
