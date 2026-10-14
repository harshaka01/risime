# Proposal: a spoken "Risi, …" in an Official voice note counts as @Risi (2026-10-14, root worker)

**Status:** proposal for root/Harsha, to be applied as the next free version (v1.49 if call-risi takes v1.48),
as a new **§37.11** plus one-line amendments to §24.11, §25.0 and §37.6. It supersedes
`2026-10-13-voice-address-risi.md` (risi role, never applied). It records Harsha's decision of 2026-10-14 06:09 SLT.
The server is implemented against this text **behind `RISI_SPOKEN_MENTION`** (default `canary`: only
`RISI_DEV_USERS` act; everybody else is shadow-logged). Root applies the PROTOCOL.md text.

**Wire change: none is required.** No new envelope, `kind` or `risi_request` field. One **optional, additive** field
(`voice_ref`) is added on Risi's reply so a phone can draw the reply as an answer to the note. Phones that ignore it
render the reply exactly as today (an ordinary `answer` card with `refs` that hold the note). No capability, no
readiness, no `/auth/config` switch.

## Why the contract needs it
§24.11 ("free text is never parsed for intent") and §25.0 (the only exception is the sender's own typed `@Risi`,
client-side, decision 083) stop the server from reading a transcript for intent. In a voice note only the server
ever has the words (§37.6: Risi transcribes in memory), so decision 083's client exception can't apply. Harsha
2026-10-14: a spoken "Risi, …" is the voice form of the @Risi chip and counts as @Risi, **in Official only**, and
**only when it is addressed to Risi**, not when Risi is just mentioned.

## Proposed PROTOCOL.md text

### 37.11 Spoken @Risi in Official (vN)
**Scope (server, normative).** Only an Official `grp:` with Official `on` (1:1 and group Official alike, as decision 083's typed
"Risi, …"). **Never Private, never a `dm:` conversation, never the user's Risi chat** (there every note already is a request,
§37.8). It runs only on Risi's own §37.6 transcript of a note that was transcribed (`status: "ok"`), so every §37.6 condition
holds: a human member's own note, not forwarded, no `transcribe: false`, the sender's §37.7 `transcribe` not `false`, ≤ 5 min.
`phone_transcript` (§37.10) is never used for this.

**Addressed vs mentioned.** The note counts as @Risi only when Risi is **addressed**: a vocative at the start of the utterance
or of a sentence, followed by a request. Merely mentioning Risi never counts. Decision order (the first that applies):
1. **Name forms** (whole word; case-insensitive Latin): `risi`, `rishi`, `risee`, `reesi`, `reesee`, `risie`, `rizi`, `risy`;
   Sinhala `රිසි`, `රිසී`, `රීසි`, `රිශි`; Tamil `ரிசி`, `ரிஷி`, `ரீசி`. A form followed by a letter, a combining mark or
   an apostrophe is not the name in vocative use (`Risi's`, `රිසිට`, `ரிசிக்கு` are mentions).
2. **A member's name.** If an active human member of the conversation has a first name equal to the form heard (e.g. a
   colleague called Rishi), the note is **not** @Risi.
3. **Sentence start.** Sentences are split at `.` `!` `?` `।` `…` and line breaks. A sentence qualifies only when, after
   optional greetings (`hey`, `hi`, `hello`, `ok`, `okay`, `oh`, `so`, `aney`, `අනේ`, `හලෝ`, `ஏய்`, `ஹலோ`, …), it **starts**
   with a name form.
4. **Addressed (rules)** when the name is followed by
   - a vocative separator (`,` `:` `!` `-` `–` `—`, or the sentence ends there, e.g. "Risi? Remind me…") and the remainder is
     a request: it contains a request cue or ends with `?`; or
   - directly (speech often has no comma) by a request cue as its first word(s).
   Request cues (en/si/ta/Singlish): `can/could/would/will you`, `please`, `remind`, `tell me`, question words
   (`what`, `when`, `where`, `who`, `how`, `which`, `why`), `set`, `add`, `schedule`, `book`, `send`, `summarise`, `find`,
   `show`, `check`, `note`, `list`, `cancel`, `help`; `මට`, `කරුණාකර`, `මතක් කරන්න`, `කියන්න`, `බලන්න`, `පුළුවන්ද`,
   `මොකක්ද`, `කවදාද`, …; `எனக்கு`, `தயவு`, `நினைவூட்டு`, `சொல்லு`, `என்ன`, `எப்போ`, `முடியுமா`, …; `mata`, `mage`,
   `mathak karanna`, `kiyanna`, `balanna`, `puluwanda`, `monada`, `kawadada`, `enakku`, `sollu`, …
5. **Mentioned (rules)** when the name is followed by a reporting or descriptive word (`said`, `says`, `told`, `is`, `was`,
   `has`, `did`, `knows`, `and`, `about`, `කිව්වා`, `කියනවා`, `ගැන`, `சொன்னது`, `சொன்னார்`, `பற்றி`, `kiwwa`, `gana`, …), or
   appears anywhere but a sentence start.
6. **Unsure**: a vocative separator with no request in the remainder ("Risi, the venue is booked."), a request cue later in
   the sentence but not first ("Risi හෙට meeting එක මතක් කරන්න"), or a trailing vocative ("Can you remind me, Risi?").
   Unsure goes to the Fast router's model (`RISI_ROUTER_MODEL`, the local loopback model; never a commercial fallback) with a
   ≤ 40-token yes/no prompt; it counts as addressed only with **confidence ≥ 0.9**. No model configured, an error, a timeout
   (1.5 s) or a lower confidence: **not @Risi**.

When unsure, the answer is always **not @Risi**: the note stays an ordinary Official message.

**Effect when addressed (and the flag is on for the sender).** The note is handled as a §24.11 `risi_request` `ask` from its
sender, exactly as if the @Risi chip had been used: the same §24.13/§25.6 limits and queue, the §25 loop, §34 routing and the
"never silent" progress (`risi_progress` with the `request_id` below).
- `request_id` = a deterministic UUID (version 4 layout) derived from the note's `message_id`, so a repeat (a re-delivery, a
  restarted job) is idempotent: one request, one answer.
- `text` = the transcript from the word after the vocative to the end (1–1000 grapheme clusters). The rest of the transcript
  is context only.
- The answer is in the transcript's `language` (as §37.8). It is the ordinary Risi reply of that request (`answer`,
  `confirm`, `draft`, `error`, …) in the **same Official conversation**, visible to all members, `notify: [sender]`.
- An addressed note is a request, so (as §37.8 for the Risi chat) it gets **no §38 action-items extraction and is not counted
  by the §27.2 quiet rule**: its promises come from the request's own confirm cards, never twice. The §37.6 transcript is
  still posted as usual.
- Risi's memory opt-out (§35.3) does not block the request (a request is not learning, as typed @Risi).

**Linking the reply to the note (additive, optional).** Every Risi post made for a spoken request carries in its `risi` object
**`"voice_ref": {"message_id": timeuuid (the note), "speaker": uuid (its sender)}`**, and an `answer`'s `refs` contains the
note's `message_id`.
- Phones that know `voice_ref` draw the card as a reply to the note (the §33.9 quote block, rendered from the local copy of the
  note: "🎤 Voice note 0:12", tap scrolls to it), and may show a "Risi is typing…" bubble for a `risi_progress` whose
  `request_id` they did not create when it arrives right after their own Official voice note.
- Phones that don't know it ignore it (unknown fields are ignored, §1) and show the card as today.
- `voice_ref` is honoured only from a leaf whose attested kind is `agent` and only in Official (§24.11 rule for `risi`).

**Learning log and shadow.** One run row per decision (`risi_agent_runs`, agent `spoken_mention`): mode
(`shadow`/`canary`/`on`), decision (`addressed`/`mentioned`/`unsure_no`/`unsure_yes`), by (`rules`/`model`), language,
latency, reason code, hashed user. **Never text** (the no-raw-prompt-text rule, §24.12). The model check, when it runs, is an
ordinary `RisiMe.Agent.LLM` call (task `spoken_mention`): its learning-log row holds the prompt's SHA-256 and the note id,
never the transcript.

**Switch (server env / flag table, read at runtime).** `RISI_SPOKEN_MENTION` = `off` | `shadow` | `canary` | `on`, default
**`canary`**:
- `off`: no detection at all.
- `shadow`: detect and log for everyone; nobody's note becomes a request.
- `canary`: notes from `RISI_DEV_USERS` (Harsha first) act; everybody else is shadow.
- `on`: everybody acts.

**Eval.** Golden set `spoken_mention` (`server/test/risime/agent/golden/spoken_mention.jsonl`): addressed and merely
mentioned examples in English, Sinhala, Tamil and Singlish (romanised forms included), each labelled `addressed: true|false`.
Gate: **zero false positives** (a mention never becomes @Risi) and ≥ 85 % recall on the addressed cases by rules alone; the
model path is scored with `scripts/risi-eval --live`.

## Amendments (one line each)
- **§24.11** "free text is never parsed for intent": add "…except §37.11 (a spoken vocative in an Official voice note)".
- **§25.0** after the decision-083 exception: "A second, server-side exception is §37.11: Risi's own transcript of an
  Official voice note that addresses Risi by name is the voice form of the chip. Only the vocative is detected; the rest is
  the question, never parsed for commands."
- **§37.6** "Learning log": add the `spoken_mention` run row (codes only).
- **§37.8**: unchanged (the Risi chat keeps treating every note as a request; §37.11 never runs there).

## Examples (for root to add to `contract/v1/examples/`)
- `envelope_risi_answer_voice_ref.json`:
```json
{"v":1,"type":"text","body":"Kamal's invoice is due on Friday.","risi":{"v":1,"kind":"answer","request_id":"8a6f0d3e-5b1c-4f7a-9e2d-1c3b4a5d6e7f","answer":"Kamal's invoice is due on Friday.","refs":["3f2a1b0c-a0b1-11f0-8000-0242ac120002"],"confidence":0.86,"voice_ref":{"message_id":"3f2a1b0c-a0b1-11f0-8000-0242ac120002","speaker":"6f1d2c3b-4a5e-4f60-8b7c-9d0e1f2a3b4c"},"call_ref":"0c1d2e3f-4a5b-4c6d-8e7f-8091a2b3c4d5","notify":["6f1d2c3b-4a5e-4f60-8b7c-9d0e1f2a3b4c"]}}
```

## Gate
Server (fake speech service and fake LLM): addressed forms in all four languages → exactly one request job with the remainder
as `text`; merely mentioned → none; the `spoken_mention` golden set (0 false positives); unsure without a model → none, with a
model under 0.9 → none, ≥ 0.9 → one; Private / `dm:` → no path; the Risi chat → the §37.8 path only (no second request);
`unclear`/`failed` transcripts → none; a repeat → the same `request_id`; `shadow` and `canary` for a non-dev user → a run row and
no request; no transcript text in run rows or logs; an addressed note gets no §38 extraction. Fast lane (rule 12): server-only.
Android (`voice_ref` rendering) is optional and ships with a later APK.

## Agent design (decision 085)
- **Owner:** the Voice agent (planned, decision 084) owns "who is Risi spoken to"; the Fast router's model is its tool for
  unsure cases.
- **Learns:** from run rows (decision codes) joined with outcomes: a spoken request the user deletes, cancels or never reads,
  and notes that were not detected but were followed within 2 minutes by a typed @Risi with similar intent (L1: thresholds and
  word lists; L2: the model's prompt/fine-tune on the golden set plus Harsha-checked shadow cases).
- **Measured:** golden set (precision first: false positives are the expensive error), live acceptance in canary, latency
  (rules < 5 ms; model < 1.5 s). Index = 60 × precision + 30 × recall + 10 × (budget met).
- **Why AOA hybrid:** a deterministic rule is exact and explainable for the clear cases; the model only breaks ties, and
  "unsure = no" keeps the honesty guard.
