# Proposal: PROTOCOL v1.8 — emoji and reactions

**Status:** merged into `contract/v1` as **v1.8** on 2026-10-06, after review by the server and
android roles. It is additive: v1.7 apps ignore reactions (unknown envelope type, or unknown event
kind).

## 1. Message length (clarifies §4)
- A body is **1–4096 extended grapheme clusters** (UAX #29) **and at most 16 KiB of UTF-8**.
  Over either gives `too_long`; empty or whitespace-only gives `empty_body`.
- **The server's count is authoritative.** The server checks the byte cap first, then counts with
  `String.length/1`.
- Clients count with ICU `BreakIterator` (character instance; on the JVM, ICU4J) only to warn.
  They must accept `too_long`, and must never retry it.
- `limits_graphemes.json` contains only cases that ICU 60+ (Android 8) and the server agree on.
- The e2ee ciphertext cap is 24 KiB (§10.3).

## 2. Reactions
- A reaction is `{target, emoji, op}`:
  - **`op: "add"` sets** the reactor's `(target, emoji)`, and **`"remove"` clears** it. Both are
    idempotent.
  - Clients send `remove` when the user taps their own reaction again, and debounce rapid taps
    (about 500 ms; only the final state is sent).
  - **Every tap is a new `client_msg_id`; a retry reuses its id**, so a retried `add` can't come
    back after a later `remove`.
- **`target`** is the server `message_id` of a normal message, never of a reaction, and never a
  `client_msg_id`. Clients offer reactions only on messages that have a `message_id`.
- **`emoji`** is exactly one grapheme cluster, at most 32 bytes of UTF-8, with no control or
  whitespace characters except ZWJ and variation selectors.
- **Effective state:** for each `(reactor user, target, emoji)`, the latest op **by
  `(server_ts, message_id)`**, so every device agrees whatever order it synced in. A user counts
  once across all their devices.
- **E2EE conversations:** the MLS envelope
  `{"v":1,"type":"reaction","target":"<message_id>","emoji":"👍","op":"add"}` is sent with the
  normal e2ee `msg:send`. The server can't tell it apart from a message.
- **Plaintext conversations:**
  - **`msg:send` carries exactly one content field:** `body`, `reaction` or `ciphertext`. More than
    one is `bad_request`.
  - `"reaction": {"target", "emoji", "op"}` sent to an e2ee conversation is `e2ee_required`.
  - **Order of checks:** idempotent resend → `not_friends` → e2ee → reaction checks → rate limit →
    store.
  - **`unknown_target`:** the target isn't a TimeUUID, isn't a normal message of this conversation,
    or has expired. Every case returns the same error.
  - **`invalid_emoji`**; an `op` other than `add`/`remove` is `bad_request`.
  - **Event kind `reaction`:**
    `{"message_id", "client_msg_id", "conversation_id", "from", "to", "target", "emoji", "op", "server_ts"}`.
    It goes to the recipient's and the sender's inboxes **with the same `event_id`**. It never
    triggers push.
- **No acks or status for reactions:** the server emits no `status` events for reaction
  `message_id`s, and `msg:ack` entries naming one are silently ignored.
- **Rate limit:** reactions share the 20-per-10-s send limit.
- **Notifications** (clients): only an effective `add` on one of **your own** messages notifies
  ("<name> reacted 👍 to: …"); a `remove` never does.

## 3. UI (informative)
- **Composer:** an emoji button opens the `emoji2-emojipicker` (recent emojis, skin tones).
  EmojiCompat uses the downloadable font, with no bundled font (about 0 MB).
- **Long-press:** 👍 ❤️ 😂 😮 😢 🙏 plus "+", then Copy and Retry/Delete. Your own active reactions
  are highlighted.
- **Under the bubble:** chips like "👍 2 ❤️ 1"; tapping them opens "Reactions" with per-emoji tabs
  listing people.

## Examples
`reaction_payload.json`, `msg_send_reaction.json`, `msg_send_reaction_and_body.json` (→
`bad_request`), `event_reaction.json`, `error_unknown_target.json`, `error_invalid_emoji.json`,
`limits_graphemes.json`.
