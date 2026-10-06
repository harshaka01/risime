# Proposal: PROTOCOL v1.8 — emoji and reactions

**Status:** proposed by root on 2026-10-06 (Harsha's slice 1, "emojis"). It needs server and
android review before merge. It is additive: v1.7 apps ignore reactions (unknown envelope type, or
unknown event kind) and keep working.

## 1. Message length (clarifies §4)
- A body is **1–4096 extended grapheme clusters** (Unicode UAX #29), not bytes or code points. An
  emoji with skin tone or ZWJ sequence counts as 1.
- It also has a hard cap of **16 KiB of UTF-8**. Over either limit gives `too_long`.
- Empty or whitespace-only gives `empty_body`, as before.
- The server counts graphemes with `String.length/1`; Android counts with ICU `BreakIterator`
  (character instance). Both sides test the same fixtures (`limits_graphemes.json`).

## 2. Reactions
A reaction is `{target message, emoji, op}`. **`op: "add"` toggles a reaction on and
`"remove"` toggles it off.** The client sends `remove` when the user taps their own reaction again.
- **`emoji`** is exactly one grapheme cluster, at most 32 bytes of UTF-8, and must be an emoji
  (clients check against the emoji2 set; the server checks only the size in plaintext chats).
- **State:** per conversation, the effective set is the latest op for each
  `(reactor user, target, emoji)`, in inbox event order. A user may have several emojis on one
  message.
- **E2EE conversations** (the usual case): the reaction is an MLS application message with the
  envelope `{"v":1,"type":"reaction","target":"<message_id>","emoji":"👍","op":"add"}`.
  - It is sent with the normal e2ee `msg:send` (its own `client_msg_id`) and gets a `message_id`
    like any message.
  - The server can't tell it's a reaction. v1.7 apps ignore it (envelope rule).
- **Plaintext conversations:** `msg:send` takes `"reaction": {"target", "emoji", "op"}` **instead
  of** `body`.
  - The server checks that `target` is a message of this conversation (`unknown_target`) and the
    emoji size (`invalid_emoji`).
  - It stores a new event kind **`reaction`**:
    `{"message_id", "client_msg_id", "conversation_id", "from", "to", "target", "emoji", "op", "server_ts"}`.
  - It goes to the recipient's and the sender's inboxes. v1.7 apps ignore the unknown kind.
- **Acks and push:** reactions get **no** delivered/read acks (clients don't send them; the server
  ignores them).
  - In plaintext chats a `reaction` event never triggers push.
  - E2EE reactions are indistinguishable, so they may wake the app, which then shows "<name>
    reacted 👍 to your message" locally only if the target is the user's own message, and never
    for `remove`.
- **Rate limit:** reactions count toward the 20-per-10-s send limit.

## 3. UI (clients, informative)
- An emoji button in the composer opens the emoji2 picker, and EmojiCompat renders new emoji on
  older Android versions.
- **Long-press** a message → 6 quick reactions (👍 ❤️ 😂 😮 😢 🙏) plus "more" (the full picker),
  next to the existing Copy, Retry and Delete actions.
- Counts appear under the bubble (for example "👍 2 ❤️ 1"); tapping them opens "Reactions" (who
  reacted with what).

## Examples
`reaction_payload.json` (the envelope), `msg_send_reaction.json` (plaintext),
`event_reaction.json`, `error_unknown_target.json`, `error_invalid_emoji.json`,
`limits_graphemes.json` (strings with their expected grapheme counts: ZWJ family, flag, skin tone,
combining marks, 4096 and 4097 clusters).
