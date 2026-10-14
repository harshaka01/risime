# 092 — Transcription and translation in PRIVATE chats, each only after the reader's yes

**Status:** accepted 2026-10-14 (Harsha, 09:42 SLT). Amends §37's and §24.0's "never Private" through contract
proposal `contract/proposals/2026-10-14-private-transcribe.md` (v1.50 candidate, new §37.12; root applies it to
PROTOCOL.md). Behind **`RISI_PRIVATE_TRANSCRIBE`** (`off` | `canary` | `on`, default `off`; `canary` =
`RISI_DEV_USERS`, decided on the server and sent as `/me` `private_transcribe`, never hard-coded in the app).
Rollout: canary (Harsha first) → on.

## Context
Private chats had no transcription or translation at all: §24.0 "never transcribed and never an input to any
model", §37.0 "no client, server or speech code path touches a Private note". Harsha wants both in Private. The
promise that has to survive: **the server and Risi never see Private content without an explicit per-action yes
from the user, and Risi is never a member of Private.**

## Decision
1. **English voice notes: on the reader's phone only** (`createOnDeviceSpeechRecognizer`; en packs are installed on
   the pilot phones). Automatic, no prompt, when the note is known to be English (the sender phone's
   `phone_transcript.lang`, else the reader's earlier choice for that sender). Label "Transcript (on this phone)".
2. **Sinhala / Tamil: Google from the reader's phone**, after "Sinhala/Tamil voice notes are transcribed by Google.
   This note's audio will be sent to Google. Yes / No" + "Don't ask again". Never via our server. Label "Transcript
   (Google)". On-device script check: never Tamil in Sinhala script; the other language is offered.
3. **Translate: RisiMe's server, after the yes** ("The text will be sent to RisiMe's server to translate, and not
   stored."). `POST /api/v1/translate` carries only the text and the target; risi-l1 on loopback, local only; the
   text is never logged, stored or enqueued; the learning log keeps languages and latency only.
4. **Sender control:** Settings → Privacy → "Let others transcribe my voice notes (Sinhala/Tamil go through
   Google)" (default on); a one-time notice on the first Private voice note ("Others can transcribe your voice
   notes. Change in Settings", tapping through to Settings), no per-use notice (Harsha 10:31). Off stamps `allow_transcribe: false` in
   the E2EE voice envelope (Private only): no Google, no server translate of that note's transcript; on-device
   English still works. A new field rather than §37.2 `transcribe: false`, whose meaning (Risi, Official, a
   server-checked setting) differs.
5. **Honesty (decision 048):** the chat keeps its lock; every label says where the work was done; Settings →
   "Private voice & translate" lists the "Don't ask again" choices, resettable. Results stay on the reader's phone
   (memory only), never sent to the chat. Settings → Risi's "Private chats are never transcribed" line is replaced
   by an accurate one while the flag is on.
6. **Official unchanged:** server transcription stays automatic; the Official translate is its own proposal
   (2026-10-14-voice-translate, `RISI_VOICE_TRANSLATE`) and reuses the same server function
   (`RisiMe.Agent.Translate.text/3`, `mode: :official`).

## Options considered
- **Server transcription for Private after a yes** (our whisper models): rejected. It would make the server hold
  Private audio, even briefly; Google from the reader's phone (with its own explicit yes) keeps our server out.
- **Risi in Private on request**: rejected outright (Risi is never a member of Private).
- **Reusing `transcribe: false`**: rejected (different meaning and a server twin; see 4).
- **Persisting reader transcripts in Room**: deferred. Memory only is the least that can leak; on-device English
  re-runs automatically.

## Agent design (decision 085)
- **Owner:** the **Voice agent** (transcription Index) for (1)–(2) and a **Translate tool** it and Risi share for
  (3). Hybrid: the recognisers and the translate call are deterministic tools; the decisions that matter here are
  the **user's**, per action, by design (a consent switch is one of the few places a form is legally needed,
  ARCHITECTURE-PRINCIPLES §2).
- **What it learns:** nothing from Private content. Signals are metadata only: per language, how often readers ask,
  say yes/no, hit a script mismatch, get no speech; translate latency and status (learning-log `private_translate`
  rows: languages + latency). L1 (choose the default language offer per sender, on the phone).
- **Measured:** the Voice agent's Index keeps scoring Official only (golden set = Harsha's labelled Official notes);
  Private adds an *availability* signal (share of Private notes with a usable reader transcript), never accuracy
  from Private text.
- **Why hybrid:** no model decides whether Private content leaves a phone; the user does, every time.

## Flags, rollout, gates
- Server: `RISI_PRIVATE_TRANSCRIBE` (`RisiMe.Agents.Flags` feature, env or `risi_agent_flags` row; default `off`).
  `scripts/risi-rpc 'RisiMe.Agents.Flags.put("RISI_PRIVATE_TRANSCRIBE", "canary")'`.
- Android: no app flag of its own; the server's `/me` field drives it.
- Tests: server `test/risime_web/controllers/private_translate_test.exs` (flag, canary, validation, rate limit,
  local only, and nothing kept: logs at debug, every Postgres table, `oban_jobs`, learning-log rows). Android
  `PrivateVoiceTest` (yes events, No sends nothing, the sender's opt-out, on-device English, parity) and
  `PhoneSttTest.theOnlineRecogniserIsNeverConstructed` (now: exactly one guarded construction site).

## Consequences
- The app now contains Android's online recogniser, at **one** site (`voice/OnlineRecogniser.kt`), behind
  `OnlineSttGuard`. The Official "Use Google for Sinhala and Tamil voice" opt-in (own notes) must add its own
  `OnlineSttRequest` kind there rather than a second construction site.
- Google receives a Private note's audio when, and only when, a reader says yes for it and its sender allows it.
  That is a third party: the dialog says so in plain words, and the sender can refuse for all their notes.
- Needs a device check (redroid has no recognition service): whether Google's online service accepts
  `EXTRA_AUDIO_SOURCE` with a segmented session on Harsha's phone; if not, the result is "Couldn't transcribe" and
  nothing else breaks.
