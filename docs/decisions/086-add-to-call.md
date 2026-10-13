# 086 — Adding people to a call (1:1 → group), group voice first, then video

**Status:** accepted 2026-10-13 (root, Harsha PART 10), with contract v1.45 (PROTOCOL §40). Behind the
android build flag `ADD_TO_CALL_ENABLED` (Gradle `-Prisime.addToCall=true`), default off until
`scripts/group-call-test` is green.

## Context
"Add participant" in a 1:1 call has been shown as unavailable since v1.23. A 1:1 call is peer to peer
(§16/§19) on the `dm:`, whose MLS group can never hold a third member (§10.2, §24.1), and whose media keys
are the DTLS fingerprints in MLS. Group calls (§20) run through our LiveKit SFU with frame keys exported
from the **group's** MLS state (§20.6), ringing through MLS, no call table on the server, history as
durable MLS lines. Harsha's brief: the two people already talking must not be dropped, new people get a
ringing invite, E2EE keys per §20, voice first, then video; old clients defined.

## Decision
1. **A call with more than two people is always a §20 call on a `grp:` conversation.** Adding people to a
   1:1 call **creates a new group chat** of everyone in the call (the adder, the peer, the invitees) with
   the normal §12.3/§24.2 create flow, and **moves the call into it**. No new kind of group, no hidden
   group, no new key schedule:
   - frame keys come from the new group's MLS exporter exactly as §20.6 (K1–K10 unchanged);
   - rooms, tokens, removal and caps are §20.2/§23.6 unchanged;
   - the chat holds the call's history line and is where follow-ups (and, in Official, Risi's action
     items) land.
   Rejected: (a) distributing a random call key over each pairwise DM (Signal-style sender keys): a new key
   schedule outside MLS, a roster nobody can verify offline, rekey on leave owned by whoever "hosts", and
   it can't reuse §20's roster rule (K7) or removal (K5); (b) a hidden "call group" kind: a new field in
   `group_meta` that the core and old apps don't know, and a chat the user can't find afterwards.
   The cost: every escalated call leaves a group chat behind. That is the honest model (the people were
   in a call together; Teams does the same); the user can rename, mute or leave it.
2. **Tab follows the call.** A Private 1:1 call moves into the new group's **Private** conversation; a
   1:1 call placed from the **Official** tab moves into its **Official** conversation (Risi a visible
   member, §24). If Official can't be created in time, the call continues in Private and says so
   ("Continuing as a Private call"): failing toward more privacy, never toward Risi hearing.
3. **Make before break, audio switched once.** The adder starts the room and connects with keys
   installed but **nothing published**; the peer receives `call_escalate` (an MLS envelope on the 1:1
   path) and joins the same way; each side, the moment it sees the other's identity in the room as an MLS
   member, stops the peer-to-peer media and publishes its microphone (and camera) in the room. Only one
   capture runs at a time (two WebRTC audio modules capturing the mic at once is not reliable on Android);
   the expected gap is under a second. The P2P call ends with the durable `call_end` `reason: "moved"`.
   If the room doesn't come up in 30 s, nothing changes: the two stay on P2P ("Couldn't add people").
   The platform (Telecom) call and the audio route carry over to the group call.
4. **Ringing names who rings.** `call_offer` `mode: "sfu"` gains `ring_users`; devices of other users
   only note the call (Join on the line). The same offer is re-sent to ring someone later (late add), so
   "Add participant" works in any group call: members not in the call are rung; an admin can also add a
   friend to the group (confirmed: "they'll see new messages") and ring them.
5. **Per-call features gate the button** (`features: ["add"]` on `call_offer`/`call_answer`, as §23.1),
   plus the device capability `add_to_call` and `Friend.group_calls` for the picker. Old peers never see
   an escalation; old invitees can't be picked; old sibling devices ring briefly and stop on
   `call_member` `joined` (§20.3).
6. **Server:** no call table, no new store. Changes: the capability, `Friend.group_calls`, the group ring
   limit relaxed for re-rings (1 per user per group per 10 s, 20 per hour), and §36.9 activated for
   Official group calls (participants from the LiveKit room; a joiner pauses transcription until they
   allow; any decline stops it for everyone).
7. **Limits:** the §20.9 caps stay the contract (32 voice, 8 video); the measured spark2 limits are
   recorded below and in §40.10.

## Agent design (decision 085)
- **The call itself is infrastructure — no agent.** Media, the move from P2P to the SFU, keys, ringing
  and the room are exactness work (MLS crypto, call media, delivery), where a learned policy has no place:
  the same input must always give the same, auditable result. They are SOA services (§1 of the
  architecture principles) that agents may later call through the tool registry, never reimplement.
- **What is owned by agents, reused not forked:** in Official group calls, transcription consent and
  the transcript run on the §36 path and the follow-ups on §36.7/§38 (Voice agent, decision 085 Part 9;
  its Transcription and Voice-understanding Indexes count group calls from day one, scored on real
  speech). Nothing new is learned by the call layer.
- **Later specialist: the Meeting agent** (Part 10): suggests whom to add ("Shenika usually joins the
  Monday call"), drafts the agenda, keeps the minutes from the §38 items, follows up. It will read the
  call's MLS-visible facts (who joined, when) in Official only; it never adds or rings anyone itself —
  adding a person to a group is consequential (they read new messages), so the human taps "Add".
  Signals: suggestions accepted vs dismissed (L1), then per-team patterns (L2). Index: suggestion
  acceptance on a human-checked golden set of past calls. Not built in v1.45.
- **Why SOA here:** the brief's hard requirements (no drop, E2EE per §20, old clients defined) are
  exactness requirements; AOA would add risk without anything to learn.

## Consequences
- Contract v1.45 §40 (additive): `add_to_call`, `features` `add`, `call_escalate`, `call_end` `moved`
  with `moved_to`, `ring_users`, `Friend.group_calls`, the ring limits, §36.9 activated with the
  `paused` state, the group transcription rules.
- Android carries the move (two machines coordinated by the call manager, one Telecom call), the picker,
  the re-ring, the group call screen (grid, active speaker, camera switch) and the history line
  "continued as a group call".
- Gate: `scripts/group-call-test` (3 redroids, a test LiveKit on its own ports): 1:1 voice → add a third
  → all three receive decrypted audio from each other → one leaves → the call continues; the same with
  video. Server and android unit gates as usual.

## Measured participant limits on spark2 (2026-10-13)
Filled in by the measurement run (see §40.10 and `docs/status/server.md`).
