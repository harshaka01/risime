# Proposal 2026-10-14: Health nudges, a plain-language Health report, and the Official rebuild after a reinstall

**Status:** proposed (root, incident 2026-10-14, `docs/status/incident-2026-10-14-health.md`). Amends §12.12.9
(Health agent) and §32 (ops alerts). The server part ships behind flags in the Risi fast lane. The app part
needs an APK. **PROTOCOL.md is unchanged until Harsha accepts.**

## Why
On 14 Oct the Health agent told the operator "6 conversation(s) stuck … official readd grp:f1865a5c (shadow);
… dm dm_readd dm (alert)…". That alert had three problems:
- it was unreadable: ids and codes, no names, no actions;
- nobody acted on the Private 1:1s: Risi may never commit there, and the other person was never asked;
- one Official group (a `rebuild` after a reset) can't be finished by today's app on a reinstalled phone.

## 1. `secure_nudge` (new stored inbox event, server → the other person of a waiting DM)
A DM `devices` op (§10.6.2) that adds a live device and has been waiting for more than 5 minutes, with no
committer, means the user's new phone waits for the other person's phone to open RisiMe.

- **Sent by:** the Health sweep (`RisiMe.Agents.Health`, every 5 min). It goes to every DM member except the
  op's user (the "waiting user"). It is sent **at most once per DM per 24 h**, remembered in Postgres
  (`risi_health_stuck.nudged_at`), so a restart doesn't repeat it.
- **Event:** an ordinary stored inbox event (§8), with the 30-day TTL and **the ordinary content-free push**
  (`{"type":"inbox","v":"1"}`). No text, name or id is ever sent through FCM.

      {"event_id": "…", "kind": "secure_nudge", "server_ts": "…",
       "data": {"conversation_id": "dm:…", "waiting_user_id": "<uuid>", "reason": "device_readd", "at": "…"}}

  `reason` is always `device_readd` in v1, and clients ignore other values.
- **What a client that knows the kind does:**
  - It shows a local notification and, inside that chat, a one-line banner: **"Open RisiMe to finish
    securing your chat with <name>"**.
  - `<name>` comes from the phone's own contacts or the profile of `waiting_user_id`, never from the server's
    text. Tapping either one opens the chat, which joins the inbox. The §10.6.2 naming then names this device,
    and it commits the re-add.
  - The banner goes away once the DM's op is gone (the device is re-added) or after 24 h.
  - Nothing is ever deleted (rule 9).
- **Older apps** don't know the kind and ignore it, as they ignore every unknown kind. They still get the
  content-free wake and sync.
  - The old package `lk.codegen.risime` (0.2.x) syncs on that wake. It commits a DM re-add only when a
    `mls_dm_op` naming its device arrives while it runs. Its "RisiMe has moved, install the new RisiMe"
    prompt comes from its own update feed, and only when it is opened in the foreground. The server can't
    trigger that prompt.
  - A device with no push token can't be woken at all. The operator report names such people (§3) so that
    someone can ask them in person.
- **Waiting side (unchanged):** the phone being re-added already shows "Waiting for <name> to open RisiMe"
  (§12.12.9 wording, Android `dmWaitingText`). No wire change.
- **Never in Official** (Risi re-adds there, §12.12.9), never for a cleanup op, never for a Private group in
  v1.

## 2. Flags (server, `RisiMe.Agents.Flags`, table > env > default, live in 5 s)
| Flag | Values | Default | Meaning |
|---|---|---|---|
| `RISI_HEALTH_OFFICIAL_REPAIR` | off, canary, on | **on** | The Official scope of Health's `canary` mode: `on` means every Official conversation, `canary` means only those with a `RISI_DEV_USERS` member (the decision 088 behaviour), `off` means none. Health `on` always repairs everywhere; `shadow` never repairs. |
| `RISI_HEALTH_NUDGE` | off, canary, on | **canary** | §1. `canary` = only DMs with a `RISI_DEV_USERS` member (Harsha first). |
| `RISI_HEALTH_PLAIN` | off, canary, on | **on** | §3. `off` = the v1.46 one-line id summary. |

## 3. §32 `ops_alert` for `check: "conversation_stuck"`: plain text and `items`
- **`body` is plain English with names and actions, never an id, a code or a sweep count.** One line per
  group of chats, for example:

      2 Official chats (Asha's Risi chat, Bimal's Risi chat) were stuck: fixed.
      3 private chats are waiting for the other person to open RisiMe: C, D, E. I've reminded C and D. E can't get RisiMe notifications right now, so please ask them to open RisiMe.
      1 Official chat needs the next app update to finish securing: the group with F, G. Nothing on the phones is lost.

- **Labels are relative to the recipient:**
  - a DM or 1:1 Official is the other person's name;
  - a group is "the group with A, B, C and n more";
  - a Risi chat is "your Risi chat" or "<owner>'s Risi chat";
  - a user without a display name is "a contact", never an invented name.
- **When it is said:** at most once per conversation, day and state.
  - `waiting_other`: Private; waiting for another person.
  - `needs_fix`: Official, not repaired.
  - `fixed`: a case that was reported, or that Health repaired in the last 30 min, **and is no longer found
    stuck**. This is verified, never assumed.
  - A repair in progress is not reported.
- **`detail`**: one plain line, for example "Chat health: 2 fixed, 3 waiting for the other person."
- **New optional field `items`** (additive). An app that knows it shows each item as a row that opens the chat
  when `open` is true (the recipient is a member). Old apps show `body`, which reads well on its own.

      "items": [{"conversation_id": "dm:…|grp:…", "chat_id": "dm:…|grp:…", "label": "Kamal",
                 "state": "waiting_other|needs_fix|fixed", "open": true}]

  `chat_id` is the chat to open: for an Official group it is the chat's Private anchor (§24.1), and the client
  opens its Official tab.
- **Technical detail** (short ids, kinds, actions, sweeps, nudge times) goes to the admin console's Servers page
  through `RisiMe.Agents.Health.console/1`, never to the chat.

## 4. Official `rebuild` after a reinstall (the APK fix; the server can't repair it today)
**Found** (pilot group "771e…", 3 people, Official, generation 2 after a 2026-10-11 reset, no MLS state):
- The server names the admin's current phone, which was reinstalled on 13 Oct, as rebuilder every time it is
  online (910 namings).
- That phone has no MLS state and no `chat_tabs` row for the group, so `GroupOps` REBUILD
  (`old == null`, not a Risi chat) builds **Private** meta.
- The core refuses it ("a Private group never gains an agent leaf"), because Risi's key package is part of
  the claim.
- `UniffiMlsEngine.createGroupWithMeta` isn't wrapped in `policy {}`, so the error is retried as transient
  8 times. The op row then becomes FAILED.
- The row is deduped by `op_id` (`IGNORE`), so no later naming re-queues it.
- The chat screen keeps calling `rejoin` (202) and shows "waiting to be re-added".

**Proposed (Android, needs an APK):**
1. REBUILD with no local Official state: when the server group says `tab: "official"` with a `chat_id` (and
   `chat_kind`), build the epoch-0 meta exactly as the §24.2 create path does (`officialEpoch0Meta`: Official
   tab, the chat id, agents). The creating path already takes the same server facts, so trust doesn't
   change. A Risi chat keeps `risiChatEpoch0Meta`.
2. `createGroupWithMeta` runs under `policy {}`, so a policy refusal is `Failed("policy: …")`, reported and
   logged, never 8 blind retries.
3. A server op row that is FAILED is queued again when a **new naming** of the same `op_id` arrives (a
   `group_op` with a higher `naming`, or a new `committer_until`), at most once per naming.
4. Unit tests: an Official rebuild on a phone with no MLS state builds Official meta with Risi's leaf. The
   Private rebuild is unchanged (parity). A failed op re-queues on a new naming.

**Alternatives not taken now:**
- Risi as rebuilder: §12.12.9 forbids it, it needs a NIF `create_group` in production, and Risi may not be
  the epoch-0 admin.
- Turning the stuck rebuild into a `creating` Official owned by the admin so the Start Official path rebuilds
  it: this needs a server change outside the Risi lane and a user tap.
- Until the APK ships, Health reports the case as "needs the next app update; nothing on the phones is lost"
  (action `needs_app`).

## 5. Tests
- **Server** (fast lane, `test/risime/agent`):
  - `health_report_test.exs`: the nudge once a day; never for the waiting user; `off`, `canary` and shadow;
    a peer without push is named; the plain body has no ids; `items`; `fixed` only after the case is gone;
    the `RISI_HEALTH_PLAIN=off` parity; labels.
  - `health_agent_test.exs`: the Official scope by `RISI_HEALTH_OFFICIAL_REPAIR`.
- **Android** (with the APK): §1 parsing and banner, §3 `items` rows, §4.
- **Device gate:** `scripts/health-heal-test` (the reinstall while the peer is old or offline; the peer comes
  back; the chats heal; Official heals without the peer).
