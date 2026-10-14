# Proposal: transcription and translation in PRIVATE chats, each only after the reader's yes (2026-10-14)

**Status:** proposal for root/Harsha, to be applied as **v1.50** (new **§37.12**, amendments to **§24.0** and
**§37.0**). Decision 092 (Harsha, 14 Oct 09:42 SLT). Implemented server-side and on Android **behind
`RISI_PRIVATE_TRANSCRIBE`** (`off` | `canary` | `on`, default `off`; `canary` = `RISI_DEV_USERS`, decided on the
server and sent through `/me`, never hard-coded in the app). With the flag off nothing changes on the wire or in any
UI (parity). Root applies this text to PROTOCOL.md; the implementation follows it as written here.

## Why
Harsha: Private chats need transcription and translation too. §37.0 and §24.0 say Private is "never transcribed and
never an input to any model". This proposal keeps the promise behind that rule (**the server and Risi never see
Private content without an explicit, per-action yes from the user**; Risi is still never a member of Private) and
replaces the absolute "never" with that rule.

## Amendments
- **§24.0** "It is never transcribed and never an input to any model…" becomes: "Risi never sees it, and it is
  never an input to the learning log, Risi's tables, buffer or memory (§24.12). A **reader** may transcribe or
  translate a Private message only by an explicit per-action yes on their own phone (§37.12); the result stays on
  that phone."
- **§37.0** "Private voice notes have no Risi path at all" stays; "no client, server or speech code path touches a
  Private note" becomes "…except the reader's own §37.12 actions".
- **§37.6** unchanged (Risi: Official only). **§37.10** unchanged except the reader-side use below.

## §37.12 Private transcription and translation (normative)

### 37.12.1 Switch
- `GET /api/v1/me` → `User` gains **`"private_transcribe": true`** while `RISI_PRIVATE_TRANSCRIBE` applies to the
  user (`on`, or `canary` and the user is in `RISI_DEV_USERS`); **absent** otherwise (so `off` is byte-identical).
  Example `me_reply_private_transcribe.json`:
  ```json
  {"user": {"id": "8d0c…", "phone": "+94770000001", "display_name": "Harsha", "company": "CodeGen",
            "phone_verified": true, "vouched_by": null, "phone_confirmed": true, "risi_dev": true,
            "private_transcribe": true}}
  ```
- Clients show §37.12 features only while it is `true` (re-read at most every 10 min; cached across restarts).

### 37.12.2 The `voice` envelope field `allow_transcribe` (sender control)
| field | type | rule |
|---|---|---|
| `allow_transcribe` | bool, optional | **`false` only** = the sender doesn't let readers send this note's audio, or text derived from it, off the reader's phone (no Google, no server translate of its transcript). On-device recognition on the reader's phone stays allowed (nothing leaves it). Absent (or `true`) = allowed |

- Written by the sender's phone **only in a Private tab**, while the sender's setting "Let others transcribe my
  voice notes" (default **on**) is off. It is part of the MLS-encrypted content.
- **Not** the §37.2 `transcribe`: that one is the Official/Risi opt-out (server-checked §37.7 setting, "never set in
  a Private tab"), has a server-side twin and means "Risi must not transcribe". `allow_transcribe` governs readers'
  off-phone actions in Private and is client-only. Keeping them apart keeps each switch's meaning exact.
- **Forward** (§37.3): copied (the voice is still the original speaker's); meaningful only in Private.
- **Receivers:** lenient. Absent, `null` or `true` = allowed; **any other value** (including malformed) is read as
  `false` (the privacy-safe reading). The note itself is always kept. Old apps ignore it (§0).
- Example `voice_payload_private_no_transcribe.json`: `voice_payload.json` plus `"allow_transcribe": false`.

### 37.12.3 Reader rules (Android, normative)
Every result below is **shown only on the reader's phone, kept in memory only, and never sent to the chat**. Labels
always say where the work was done (decision 048), and the chat keeps its lock.

1. **English notes: on this phone.** The reader's phone transcribes with the on-device recogniser only (Android:
   `createOnDeviceSpeechRecognizer`, `EXTRA_PREFER_OFFLINE`), **automatically, without a prompt**, when the note's
   language is known to be English (the note's `phone_transcript.lang`, else the language this reader chose for
   this sender before) and an English on-device pack is installed. Label **"Transcript (on this phone)"**. A phone
   without on-device English offers "Transcribe" behind the Google yes/no of (2).
2. **Sinhala / Tamil: Google, after the yes.** The reader taps **Transcribe** (choosing English / Sinhala / Tamil)
   and gets: **"Sinhala/Tamil voice notes are transcribed by Google. This note's audio will be sent to Google.
   Yes / No"** with **"Don't ask again"**. Only **Yes** (or an earlier "Don't ask again") lets the reader's phone
   build Android's online recogniser and stream the note's decrypted audio to it, **from the reader's phone, never
   via RisiMe's server**. Label **"Transcript (Google)"**. **No** (or dismissing) sends nothing. Blocked when the
   note carries `allow_transcribe: false`.
   - **Language check on the phone** (as the server's Tamil fix, §36.11): the result's script (Sinhala block
     U+0D80–U+0DFF vs Tamil block U+0B80–U+0BFF) is checked against the chosen language; a Tamil result in Sinhala
     script is **never shown as Tamil** (and vice versa): the reader is offered the other language instead ("Try
     Sinhala"), which is a new action (it asks again unless "Don't ask again").
3. **Translate: RisiMe's server, after the yes.** "Translate to English / Sinhala / Tamil" under a reader-side
   transcript, a sender-phone transcript (§37.10) and received typed messages. The dialog: **"The text will be sent
   to RisiMe's server to translate, and not stored. Yes / No"** with **"Don't ask again"**. Only Yes sends the text
   (§37.12.4). Label **"Translation (RisiMe server)"**. Blocked for text derived from a note with
   `allow_transcribe: false`.
4. **The yes event.** Each send off the phone needs a yes for *that* action (that note, that text and target), or
   the reader's "Don't ask again" for that kind. The phone has **exactly one construction site** of the online
   recogniser, behind a guard that requires the flag, the yes for this note and the sender's allow flag (a unit test
   scans the sources).
5. **Settings.** Settings → **Privacy**: **"Let others transcribe my voice notes (Sinhala/Tamil go through
   Google)"** (default on). Settings → "Private voice & translate": the two "Don't ask again" choices (Google
   transcription, server translation), each resettable ("Ask again").
   - **Sender notice (Harsha 10:31):** no per-use notice. A **one-time** notice the first time the user records a
     Private voice note while §37.12 applies: **"Others can transcribe your voice notes. Change in Settings"**, with
     [Settings] opening Settings (where the switch is) and [OK]. Never shown again on that phone.
6. **Official is unchanged:** Risi transcribes on the server (§37.6); the Official translate is its own proposal
   (2026-10-14-voice-translate, flag `RISI_VOICE_TRANSLATE`).

### 37.12.4 `POST /api/v1/translate` (auth)
The phone sends **only** the text it wants translated and the target language. No conversation, no message id; no
conversation access is needed or checked.

`translate_request.json`:
```json
{"text": "Meet at the office at 10 tomorrow", "target": "si", "source": "en"}
```
- `text`: string, 1–4000 characters after trimming, ≤ 12 000 UTF-8 bytes. `target`: `"en" | "si" | "ta"`.
  `source` (optional): `"en" | "si" | "ta"`; when it equals `target` the text is returned as is (no model call).

`translate_reply.json` (`200`):
```json
{"text": "හෙට උදේ 10ට කාර්යාලයේදී හමුවෙමු", "target": "si", "source": "en", "engine": "risime-server"}
```
- `source`: the given `source`, else `null` (v1.50 doesn't detect it). `engine` is always
  `"risime-server"` (clients label the result "Translation (RisiMe server)").

**Errors:** `404 not_found` while `private_transcribe` doesn't apply to the caller; `422 bad_request` (empty, too
long, a bad language, not a string); `429 rate_limited` (30 per user per minute, `Retry-After`); `503
agent_unavailable` (the model failed).

**Server rules (normative):**
- The model is RisiMe's own (risi-l1 over loopback) **only**: never the commercial fallback, whatever
  `RISI_FALLBACK` says.
- **The text is never logged or stored:** no request-parameter logging for this route, no Oban job, no table row,
  no crash dump or trace with it (the handling runs as an Erlang `sensitive` process).
- **The learning log** gets one `private_translate` row with **metadata only**: `meta` = `{"source", "target",
  "mode": "private"}`, latency, model, status. `output: null`, `prompt_sha256: null`, `source_message_ids: []`,
  `conversation_id` / `chat_id` = `"private"`.
- The same server translate function serves the Official translate (proposal 2026-10-14-voice-translate,
  `RisiMe.Agent.VoiceTranslate.translate/5`; Private calls it with `private: true`).

### 37.12.5 Events
None on the wire: no WebSocket event, no Risi kind, no stored event. The only wire changes are the `User` field,
the envelope field and the REST call above. The "yes event" is a client-side action (§37.12.3 (4)).

### 37.12.6 Examples, gate
- **Examples (root adds):** `me_reply_private_transcribe.json`, `voice_payload_private_no_transcribe.json`,
  `translate_request.json`, `translate_reply.json`.
- **Gate:** server: the flag (`off` → 404 and no model call; `canary` → dev users only; `/me` field absent while
  off), validation, the rate limit, local-only, and **nothing kept** (a test scans every log line at debug level,
  every Postgres table including `oban_jobs`, and the learning-log rows after a translate). Android: no Private audio
  or text reaches the online recogniser or the server without the yes event; No sends nothing; the sender's opt-out
  blocks Google (even with "Don't ask again"); on-device English never touches the network; the online recogniser
  has one guarded construction site (scan test); `allow_transcribe` absent → envelope bytes identical (parity).
