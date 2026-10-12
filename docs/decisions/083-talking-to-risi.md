# 083 — Talking to Risi: never silent, addressing Risi, the Private hint

**Status:** accepted 2026-10-12 (Harsha, PART 4 "Talking to Risi"). Risi + android roles. No wire change: every
message below is an envelope the contract already has (§24.11, §25.2, §25.4).

## Decision
- **Never silent (server, `RisiMe.Agent.Offline`).** A request whose model is down, or whose model call has
  been pending 10 s (the clock runs only while the model is asked, never while a turn waits for the phone),
  gets one line in its conversation: **"Risi is offline, I'll reply when I'm
  back."** It is an ordinary `answer` for the request (`call_ref: null`, so `made_by` says a rule made it,
  `notify` = the asker), so every app shows it. The request stays queued: the job snoozes 15 s at a time for
  up to 30 minutes (was: 30 s, for 2 minutes, silently) and the real reply follows by itself. After 30 minutes
  the turn ends with the server-built "I couldn't reach my model" answer as before. One notice per request.
- **@Risi "what did we decide / agree / what's pending" in an Official chat** (the memory route, v1.36 §35.2)
  searches that chat only (`memory_search` with its `conversation_id`, its promises only) and is answered in that
  chat, visible to its members. It used to search all the asker's chats and answer "I've replied in your Risi
  chat." in the group. A memory search across chats stays personal (Risi chat), as §25.1 says.
- **"Risi is typing…"** is the progress bubble of a running turn (`risi_progress` `working`/`queued`); tool
  steps keep their labels ("Checking your calendar…").
- **Addressing Risi in Official (android).** The @Risi chip is unchanged. In addition:
  - typing "@" in the Official composer offers **Risi** (a picker chip); picking it turns on the chip;
  - a message holding the **"@Risi" token** typed out is the chip in text form: the rest of the text is the
    `risi_request` `ask`;
  - in a **1:1 Official**, "Risi, <question>" (also "Risi: …", "Hey Risi, …") is addressed to Risi by name and
    is an `ask` too. In a group's Official it stays an ordinary message (Risi stays quiet without @Risi).
  This amends the client rule of decision 066 ("@Risi typed by hand is an ordinary message"): addressing Risi by
  name is not intent parsing; the text itself is still never parsed for what to do.
- **Private.** Risi never reads or replies there (unchanged). "@Risi" typed in Private shows "Risi only works in
  Official. Move this to Official?" with **Move to Official**, which opens the chat's Official tab with the text
  in its composer. Nothing is sent until the user sends it from Official.
- **The empty Risi chat** shows "What can Risi do?" with four examples; a tap fills the composer and never sends
  (the reminder example stops the cursor at the name).

## Why
Harsha 2026-10-12: every message to Risi gets a reply within about 10 s, or a clear offline line and a reply
later; people type "@Risi" and "Risi, …" naturally and a silent drop reads as Risi being broken.

## Gate
`scripts/risi-chat-test` (two redroid phones, fake-llm by default, `--live` for risi-l1, the model taken down
through a local proxy). Root: please reword §24.11 "'@Risi' in the composer is a chip …" to include the typed
token and the 1:1 direct address (client behaviour only; the envelope is the same).
