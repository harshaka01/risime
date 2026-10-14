# Proposal 2026-10-14: "Wrong language?" and Translate for Risi transcripts (v1.50, §37.13)

**Status:** proposal for root/Harsha, to be applied as **v1.50** (after the v1.49 bundle). Server and Android
implement it **behind the runtime flag `RISI_VOICE_TRANSLATE`** (`off` | `canary` | `on`, default **`canary`**:
dev users first). Additive only: old apps ignore the new kind fields and never call the new endpoint.

## Why
P1 2026-10-14 (docs/status/incident-2026-10-14-tamil-script.md): a Tamil speaker's Official note was transcribed as
Tamil sounds in Sinhala letters. The server hotfix (the Voice agent's language ID, `RISI_VOICE_LANG_ID`) makes it
rare, but a person must be able to fix a wrong language in one tap, and Official chats mix Sinhala, Tamil and
English speakers who need to read each other. Harsha (2026-10-14 09:29 SLT): "Wrong language?" under each transcript;
Translate under each transcript and each typed Tamil/Sinhala message; per viewer; Official only.

## Proposed PROTOCOL.md text

### 37.13 Wrong language and Translate (v1.50)
Official only (an Official `grp:` with Official on, §37.6), never Private, never the user's Risi chat. Available to a
user when `GET /api/v1/me/risi/voice` says `"translate_available": true` (the server's `RISI_VOICE_TRANSLATE` is on
for that user). Otherwise the phone shows neither link.

#### 37.13.1 "Wrong language?" (re-transcribe with a language)
Under every `voice_transcript` (§37.6) with `status` `ok` or `unclear`, every member (sender and receivers) sees
**"Wrong language?"**. It opens three choices, **Tamil / Sinhala / English**, without the transcript's own
`language`. One tap sends, in the same conversation, a **`risi_action` `transcript_language`**
(`envelope_risi_action_transcript_language.json`):
```
{"v": 1, "type": "risi_action", "action": "transcript_language", "target": "<the note's message_id>",
 "edit": null, "language": "si" | "ta" | "en",
 "voice": {"blob": {…the note's §37.2 blob…}, "enc": {…the note's §37.2 enc…}, "duration_ms": int}}
```
- `voice` is copied from the note's own envelope (the tapping member has it): Risi deletes a note's key right after
  transcribing it (§37.6), so the request carries it again, inside MLS like the note. Never sent outside MLS.
- **Server (normative):** the action is honoured only when the sender is an active human member, the conversation is
  Official, the flag is on for the sender, `language` is one of `si`/`ta`/`en`, Risi posted a transcript for
  `target`, and `voice.blob.blob_id` and `sha256` equal the note's (recorded when Risi transcribed it; a note
  transcribed before v1.50 has no record and is refused). At most **3** per note per 10 minutes (more are ignored).
  Then Risi fetches and decrypts the blob as §37.6 (in memory, deleted after), transcribes it **with that language
  forced** (§36.5 route for that language, no detection), and:
  1. posts a **new `voice_transcript`** for the same `message_id` with the new text and `language`, plus
     **`"relang": {"from": "si" | "ta" | "en" | null, "to": "ta", "by": uuid}`**
     (`envelope_risi_voice_transcript_relang.json`);
  2. deletes its earlier `voice_transcript` post (sender delete, §15) and everything learned from it (as
     `transcript_delete`, §37.6: memory, facts, promises, the working copy, §38 items), then the new transcript goes
     through the normal §37.6 understanding path (memory, Ledger, §38 action items);
  3. updates the **speaker's** language profile (the note's milliseconds move from `from` to `to`, one correction
     counted; codes and numbers only) and writes the learning-log `transcribe` row with `"relang": true`, the two
     language codes, `output: null`. Never text or audio.
  If the speech service fails, the earlier transcript stays and nothing is posted.
- **Receivers:** a `voice_transcript` with `relang` **replaces** any earlier transcript of the same `message_id` (the
  later envelope wins even if the delete hasn't arrived). It renders as §37.6 with a small line "Re-transcribed in
  Tamil" (the `to` language's name). Old phones: the delete removes the old card, the new one renders as today.

#### 37.13.2 Translate (per viewer)
- Every transcript keeps its **original language and script** (unchanged §37.6).
- Under a `voice_transcript` with `status: "ok"`, and under every **typed `text` message in Official whose text has
  Sinhala or Tamil letters**, the phone shows **"Translate to Sinhala / English / Tamil"** without the text's own
  language (§37.13.3 `language`).
- One tap calls **`POST /api/v1/risi/translate`** (auth; `risi_translate_request.json`):
  `{"conversation_id": "grp:…", "message_id": timeuuid, "target": "en" | "si" | "ta"}`
  → **`200`** (`risi_translate_reply.json`):
  `{"conversation_id", "message_id", "target", "source_language": "si" | "ta" | "en" | null, "text": str (≤ 4000),
  "label": "Translated by Risi", "made_by": {"model": alias, "provider": "risime", "at", "also": []}}`.
  Errors: `403 {"error": "not_available"}` (flag off for the user, not Official, not a member, a Risi chat or
  Private); `404 {"error": "not_found"}` (Risi doesn't hold that message's text: never seen, deleted, or its
  speaker opted out of memory); `422 {"error": "bad_request"}`; `429 {"error": "rate_limited"}` (30 per user per
  10 min); `503 {"error": "unavailable"}` (the model is down).
- The server translates **only text Risi already holds for that Official message** (the transcript's working copy,
  or Risi's sealed memory of a typed message); the request never carries message text. It runs on **risi-l1**,
  on demand. The translation is **not posted, not stored, not remembered and not logged** (the learning log row is
  `task: "translate"`, the two language codes, latency, `output: null`). Same request within 10 minutes: served from
  an in-memory cache (sealed, never on disk).
- **Rendering:** shown **only on the tapping person's phone**, under the original, labelled **"Translated by
  Risi"** (never a chat message, never sent to anyone). It isn't backed up and isn't in history shares.
- **Setting** (per user, §37.7): `GET`/`PUT /api/v1/me/risi/voice` gain `"always_translate": "off" | "en" | "si" |
  "ta"` (default `"off"`; `me_risi_voice_put_v150.json`, `me_risi_voice_reply_v150.json`) and the read-only
  `"translate_available": bool` (ignored on `PUT`). With a target set, the phone requests the translation itself
  when it shows a transcript (or a typed si/ta message) whose language isn't the target, and shows it expanded. A
  `PUT` without `always_translate` keeps the stored value. Settings → Risi: "Always translate voice notes to:
  Off / English / Sinhala / Tamil".

#### 37.13.3 The text's language
- A transcript's `language` (§37.6). After v1.50 the language always matches the text's script (Sinhala letters →
  `si`, Tamil letters → `ta`).
- A typed message: Sinhala letters (U+0D80–U+0DFF) → `si`, Tamil letters (U+0B80–U+0BFF) → `ta` (the one with
  more letters when both), else `en`. Latin-script Singlish counts as `en` and gets no Translate link (v1.50).

#### 37.13.4 Memory, the Ledger and action items
Risi's understanding of a Sinhala or Tamil note or message works from **the original plus an internal English
rendering** (risi-l1, made when the note is understood): §38 extraction sees both, so a Tamil note makes the same
action items an English one would. The rendering lives only in Risi's sealed working copy (24 h) and is never
posted, logged or written to the learning log; items keep §38's language rules.

## Amendments (one line each)
- §24.11 `risi_action`: `transcript_language` (fields above).
- §37.6 `voice_transcript`: optional `relang`; a later transcript of the same `message_id` replaces the earlier.
- §37.7: `always_translate`, `translate_available`.
- §2 REST list: `POST /api/v1/risi/translate`.

## Examples (for root to add to `contract/v1/examples/`)
`envelope_risi_action_transcript_language.json`:
```json
{"v":1,"type":"risi_action","action":"transcript_language","target":"d1e2f3a4-a6f2-11f1-8000-0242ac120002","edit":null,"language":"ta","voice":{"blob":{"blob_id":"5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8c","size":49168,"sha256":"q7FnzD0yS2bX5uJ8Kc3mT9vL1aP4eR6wY0hN2iO8gUs="},"enc":{"alg":"A256GCM-S64K","key":"Hc2Tj9wQ1mZ8rV4yK7pL3sX6aN0bF5gD2eU9iO1tR8c=","plain_size":49152},"duration_ms":12400}}
```
`envelope_risi_voice_transcript_relang.json`:
```json
{"v":1,"type":"text","body":"Voice note from Kumar (0:12): நான் நாளைக்கு காலையில வாறன், அந்த ரிப்போர்ட்ட முடிச்சு அனுப்புறன்","risi":{"v":1,"kind":"voice_transcript","conversation_id":"grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b","message_id":"d1e2f3a4-a6f2-11f1-8000-0242ac120002","speaker":"7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3","duration_ms":12400,"status":"ok","text":"நான் நாளைக்கு காலையில வாறன், அந்த ரிப்போர்ட்ட முடிச்சு அனுப்புறன்","language":"ta","model":"tamil-asr-qwen3","relang":{"from":"si","to":"ta","by":"0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"},"call_ref":null,"made_by":{"model":"tamil-asr-qwen3","provider":"risime","at":"2026-10-14T04:12:00.000Z","also":[]},"notify":[]}}
```
`risi_translate_request.json`:
```json
{"conversation_id":"grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b","message_id":"d1e2f3a4-a6f2-11f1-8000-0242ac120002","target":"en"}
```
`risi_translate_reply.json`:
```json
{"conversation_id":"grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b","message_id":"d1e2f3a4-a6f2-11f1-8000-0242ac120002","target":"en","source_language":"ta","text":"I'll come tomorrow morning, finish that report and send it.","label":"Translated by Risi","made_by":{"model":"risi-l1","provider":"risime","at":"2026-10-14T04:12:30.000Z","also":[]}}
```
`me_risi_voice_put_v150.json`:
```json
{"transcribe":true,"action_items":true,"always_translate":"en"}
```
`me_risi_voice_reply_v150.json`:
```json
{"transcribe":true,"action_items":true,"always_translate":"en","translate_available":true}
```
(All texts are synthetic.)

## Gate
- **server:** `transcript_language`: honoured for a member with a matching blob, refused for a mismatched blob, a
  note without a record, Private, a Risi chat, the flag off, a 4th request in 10 min; the forced language reaches the
  speech service; the new post carries `relang`, the old post and its learned rows are deleted, the speaker's
  profile moves; the learning-log row has codes only. `translate`: 200 for a held transcript and a held typed
  message, 403/404/422/429/503 as above, nothing posted or stored, no text in logs or the learning log (a test
  greps the log capture); §38 sees the English rendering for a Tamil note; every example decodes.
- **android (JVM):** decoding every new example; the "Wrong language?" choices hide the current language and build
  the action from the note's own blob/enc; a `relang` transcript replaces the earlier one; the Translate choices hide
  the text's language; the typed-message link only for si/ta script in Official (never Private, never the Risi
  chat); `always_translate` round trip; the translation is per viewer (never sent, never stored in Room).
- The full release gates apply (APK, protocol).

## Agent design (decision 085)
- **Owner:** the Voice agent (transcription, language ID, its profile) and the Translate tool it uses (risi-l1).
- **Learns (L1):** each speaker's spoken-language profile from notes and "Wrong language?" corrections (counts and
  milliseconds per language, `risi_speech_langs`); each correction is a labelled language-ID example for the Index.
- **Measured:** language-ID Index = share of notes whose language was not corrected within 24 h, by language
  (`risi_agent_runs` voice/lang_id rows + corrections); Translate: thumbs on the translation card (later) and the
  rate of re-translation to another target. Golden set: Tamil/Sinhala/English notes labelled by speakers with consent;
  public sets only as a labelled fallback.
- **Why AOA:** the language decision is a judgement (detector + speaker history + text plausibility), owned by the
  agent; the speech service and risi-l1 stay tools.
- **Flags:** `RISI_VOICE_LANG_ID` (on), `RISI_VOICE_TRANSLATE` (canary → on after Harsha's check).
