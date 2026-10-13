# 088: Official never waits for a human (Risi re-adds devices, watchdog, Health agent)

**Status:** accepted 2026-10-13. Root-authorised P0 hotfix for incident 2026-10-13
(`docs/status/incident-2026-10-13-official.md`). Contract v1.46 §12.12.9, which amends §12.12.3 and §24.2.

## Context
On nightly.5, two 1:1 Official conversations got stuck:

- **A.** Both people reinstalled, so no live member device was left in the MLS group. Risi was a member but
  never commits device re-adds (§12.12.3, §24.5). `Ops.rejoin_guard` refused the app's reset for 24 h, and
  the app's "Starting Official…" had no timeout. The result was an endless spinner on both phones.
- **B.** One person reinstalled. The only candidate was the other person's phone, which was closed, so the
  chat waited on a human, worded as a group ("a group member") in a 1:1.

Two more server facts made it worse:

- Risi's device advertises only `groups` and `tabs`, so in every Official group it **closed the §12.4a
  member gate**. Even an online member could not re-add another member's phone there.
- A device whose row was gone still counted as a candidate.

## Decision
1. **Risi commits re-adds in Official only.** Risi's device is the first committer candidate for a
   member-committable `devices` op of an Official group (a Risi chat included):
   - the op adds devices of one active human member who still has a leaf, and removes only devices it
     re-adds;
   - Risi is available, its device is in the group, and Official is on.

   It uses the ordinary commit path with the op's `op_id`, through the server MLS client:
   `RisiMe.Agent.Conversation` handles the `group_op` naming it, `MLS.claim_for_readd/2` takes the
   server-held key package of that exact device, `Nif.change_members/4` builds the commit, then
   `Groups.Commit.commit/4` and `commit_accepted/commit_rejected`.

   The core's §12.4a/§24.1 rules are unchanged: Risi is a non-admin, non-agent-target committer, so it can
   only re-add a user who keeps a leaf. Strikes and exhaustion (§12.12.4) apply as for any device. **Never
   in Private groups or DMs** (`risi_can_commit?/3` requires `tab: official`; the NIF refuses non-Official
   groups too). Never `add`, `remove`, `role`, `rebuild`, cleanup or `group_meta`.
2. **Risi's leaf never closes the §12.4a gate.** It runs the server's own current core.
3. **Candidates in Official** leave out devices that can never commit: row gone, unseen for 30 days or
   superseded, unless online now. With Risi counted, `candidates: 0` means nobody can ever re-add. The
   §12.12.3 guard and the app then allow an admin device's reset **at once**. This generalises the
   `risi_alone?` exception of 2026-10-12. Private groups keep the 24 h floor.
4. **Race-safe creation.** The unique `(chat_id, tab)` index already made concurrent `POST …/official`
   calls yield one group. New: a `creating` Official without epoch 0 after **60 s** is handed to the next
   caller (`created_by`, under the group lock shared with the epoch-0 commit), and the app builds epoch 0
   only as `created_by`. This covers "the creator never delivered a Welcome".
5. **App watchdog.** "Starting Official…":
   - re-kicks every 20 s (`POST …/official`, plus rejoin when the group is active);
   - after 60 s shows why (offline / "Waiting for <name> to open RisiMe" / "Official isn't ready on this
     phone yet.") and **Try again**.

   Every waiting strip has Try again, and a 1:1 Official names the other person. Nothing is ever deleted
   (rule 9).
6. **Health agent** (below), the safety net for everything the rules above don't cover.

## Agent design
Decision 085 asks every capability to name its owning agent.

- **Risi re-adds (1–3): no agent, infrastructure.** This is a protocol committer rule (MLS crypto and
  delivery, exactness required, decision 085 §1): deterministic, contract-specified, tested on both sides.
  Risi's *device* commits as a member. No model and no judgement are involved.
- **The Health agent** (`RisiMe.Agents.Health`, registered in `RisiMe.Agents`, type `:specialist`, status
  `:live`) owns **conversation reliability**.
  - **Goal:** every conversation is readable on every live phone of its members, and Official never waits
    for a human.
  - **Tools:** the ops/naming engine (`Ops.name_next`), the ordinary reset path (`Groups.Commit.reset/4`
    as the waiting admin device, so every guard and limit applies), and `ops_alert` posts (§32,
    `alert` / `conversation_stuck`) to the `RISI_DEV_USERS` Risi chats.
  - **Memory:** none beyond `risi_health_stuck` (day, conversation, kind, action, repaired). Ids and codes
    only, never content, names or numbers.
  - **Run:** an Oban cron every 5 minutes (`RisiMe.Workers.HealthSweep`). Stuck means waiting more than
    5 minutes: `creating`, a re-add op for a live device, a `rebuild`, or a DM re-add.
  - **Safe repairs, Official only:**
    - expire a stale `creating` group;
    - name Risi again when it can commit;
    - reset with the waiting admin device as rebuilder when nobody can ever commit.

    Private groups and DMs are only counted and alerted. A reset is the §12.8 last resort, which the
    guard already permits in exactly that case. It drops only undecryptable old-generation state and never
    a local message (rule 9), so it is not a "consequential action" in the sense of decision 085 §4.
  - **Signals and learning:** L1 rules today. Signals are the kinds and actions of stuck cases, and which
    repairs held (the case does not reappear the next day). A later L2 can rank repair order.
  - **Eval:** the server suite `test/risime/agent/health_agent_test.exs` covers each kind, each mode, and
    Private untouched. The device gate is `scripts/official-start-test`.
  - **Index (scoreboard row `health`, daily):**
    - `accuracy` = **reliability** = 1 − stuck / (active Official groups + stuck);
    - `acceptance` = share repaired;
    - `n` = stuck conversations.

    The Index rises as stuck chats fall.
- **Why AOA here.** A hybrid. The repair *rules* are deterministic, and the protocol engine stays the
  tool. Deciding *what* to repair, *when*, and *whom* to alert is a capability with a goal and a measured
  Index, so it belongs to an agent (decision 085 §1). A cron script would have no eval, no Index and no
  flag.
- **Flag `RISI_AGENT_HEALTH`: default `canary`.** It repairs conversations with a `RISI_DEV_USERS` member
  (Harsha first) and runs in shadow elsewhere: detect, record, alert, no repair.

  Why not shadow first (decision 084 says 3 days of shadow before canary)? That rule compares an agent
  with today's path. Here today's path is "do nothing, stuck forever". The agent can't be less accurate
  than that, and its repairs only call paths the protocol already allows. Canary still keeps everyone
  else's chats untouched until Harsha flips it to `on`
  (`RisiMe.Agents.Flags.put("RISI_AGENT_HEALTH", "on")`, no deploy). `RISI_AGENTS=legacy` turns it off.

## Consequences
- Contract v1.46 (no new fields, events or errors). `PendingOp.committer` may name Risi's device, and only
  Risi receives that `group_op`.
- The NIF gains `change_members/4` (a binding only; no core change).
- One additive Risi migration (`risi_health_stuck`) and one cron line.
- A new blocking device gate: `scripts/official-start-test` (`OSTEST OK`).
- Parity: Private groups, a group chat's Private and DMs behave exactly as before (server and Android
  tests).
