# Proposal: Official never shows the Private lock or wording ("Encrypted · Risi can read") (2026-10-14, android worker)

**Status:** APPLIED as **v1.49 §10.4 / §24.9 / §25.2** (root, 2026-10-14). Was: proposal for root/Harsha (Harsha 2026-10-14, item 6 "honesty"). To be applied as a **§10.4 / §24.9 / §25.2
clarification** in the next free version. Client-only wording and icons: **no wire change**, no capability, no server
change. Android implements it against this text (commit `fix(android): Official never shows the Private lock…`).

## Why
Official chats (1:1 and group) and the Risi chat are MLS groups whose members include Risi, and Risi's keys live on
RisiMe's server. They are encrypted on the way and at rest, but **the server side can read them**. Today:
- §10.4 / decision 048 prescribe "a lock and 'Messages are end-to-end encrypted' for e2ee chats" without distinguishing
  tabs, and §24.9 says "Encryption state (decision 048) is per tab: each tab shows its own lock". The app drew the
  **same lock** in the header of a Private group ("🔒 3 members") and of an Official group, a 1:1 Official ("🔒 Official")
  and the Risi chat ("🔒 Risi").
- An Official **group call** (§20 on the Official `grp:`; Risi is a leaf and derives the frame keys, §27.7) showed
  "End-to-end encrypted" exactly like a Private call.
That reads as "as private as Private". It isn't, so it breaks decision 048's honesty rule.

## Proposed PROTOCOL.md text

### §10.4 UI, the "Encryption state per chat" bullet: append
- **Official and the Risi chat (v1.24+, §24/§25) never use the Private lock or the Private wording** (decision 048,
  Harsha 2026-10-14). Their marker is the **Official lock** (the lock with a small Risi badge, the Official accent dot)
  and their text is **"Encrypted · Risi can read"**. "End-to-end encrypted" and "Messages are end-to-end encrypted" are
  for Private conversations only.

### §24.9 Client rules, replace the "Encryption state" bullet with
- **Encryption state** (decision 048) is per tab:
  - **Private** (1:1 and group): the lock and "End-to-end encrypted" in the header (a group: "🔒 N members"), and
    "Messages are end-to-end encrypted" in chat info. Unchanged.
  - **Official** (1:1 and group): the **Official lock** (lock + Risi badge) and **"Encrypted · Risi can read"** in the
    header (a group: "Encrypted · Risi can read · N members"). It **must never** show the Private lock or the words
    "end-to-end encrypted".
  - **Chat info** (1:1 and group), in the Official section, the Official lock, "Encrypted · Risi can read" and:
    "Messages are encrypted on the way and on the server. Risi, whose keys are on RisiMe's server, can read Official.
    Private is only you and the other person." (a group: "…Private is only the people in this chat.")
  - Not encrypted yet (any tab): "Not end-to-end encrypted yet: <reason>", unchanged. Official is always e2ee (it is a
    `grp:`).
  - The tab labels "🔒 Private" | "● Official", the chat-list markers "🔒 " / "● ", the notification titles and the forward
    picker labels are unchanged (they are already distinct).
- **Calls** (adds to the Calls bullet): the call screen's encryption line is "End-to-end encrypted" (Private lock) only
  for a Private call. A group's **Official** call (§20 on the Official `grp:`): Official lock + **"Encrypted · Risi can
  listen"**, call info "Encrypted (MLS frame keys). Risi is in Official and holds its keys, so it can listen". A 1:1's
  Official call (§16/§19 on the `dm:`, Risi has no key; it hears the call only through §36 Transcribe, which everyone
  must allow): Official lock + **"End-to-end encrypted · Official"**, call info "End-to-end encrypted (MLS-bound
  DTLS-SRTP). Risi hears it only if everyone allows Transcribe". A Call Risi keeps its own banner (§41.4), never a lock.
- **Help texts:** Settings → About adds, after the decision-048 sentence: "Official tabs and your Risi chat are encrypted
  too, but Risi can read them: its keys are on RisiMe's server. Private is only the people in the chat." With tabs on,
  the create-group note reads "Private messages in this group are end-to-end encrypted. In Official, Risi can read them
  too. New members don't see earlier messages."

### §25.2 the Risi chat: add
- Its header shows the Official lock and "Encrypted · Risi can read" (never the Private lock).

## Compatibility
- No wire, storage or capability change; nothing for the server. Old apps keep their old wording.
- Release gates: `scripts/e2ee-clarity-test` and `scripts/two-phone-test` check the **Private** DM header
  (`risi_header_lock` / "End-to-end encrypted") and the "● " list markers, which are unchanged. The Official header's
  icon has its own tag `risi_header_official_lock`, so a gate can never count an Official header as the Private lock.
