# Proposal: the message card shows both versions of a dictated text (§42.2)

**From:** risi role, 2026-10-16. **For:** root (contract owner), then android.
**Why:** architect 16:05 SLT item 5. When Harsha dictates a long message on a call, speech-to-text mishears words
("a receipt can send" for "Risi can send"). Risi now offers "Some words may be misheard. Shall I fix them? It would
say: …". The architect asks that the card show both versions, so he can see what was heard and what Risi changed.

**Change (additive, server → app):** the §42.2 `confirm` card for `send_message` gains an optional display field
`send.heard_text`: the text exactly as heard, present only when the card's `text` is Risi's speech-fixed version.

```
"send": {"to": {...}, "tab": "official", "language": "en", "heard_text": "… whether a receipt can send …"}
```

- `args` and `text` stay the message that [Send] sends (the fixed one, after the caller said yes to the fix).
- The app shows, under the text, a small grey line "As heard: <heard_text>" (collapsed to 2 lines, tap to expand).
  An app without it ignores the field (§0: unknown fields are ignored).
- No change when the caller said no to the fix: the card has the heard text and no `heard_text`.

**Server today:** the brain keeps both versions on the call (`pending.fixed`), asks the fix question, and drafts the
card with the version chosen. It does not send `heard_text` until this is in the contract.
