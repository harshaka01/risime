# Proposal: Risi uses the sender phone's transcript as a fallback (§37.10 amendment, 2026-10-14)

**Status:** proposal for root/Harsha, to be applied as **v1.48** (§37.10 "Server / Risi" amendment). The server
implements it **behind the runtime flag `RISI_VOICE_PHONE_FALLBACK`** (`off` | `canary` | `on`, default `off`), so
nothing changes until root applies this text and flips the flag. No wire change: the field already exists (v1.47).

## Why
Incident 2026-10-14 (docs/status/incident-2026-10-14-sinhala-slow.md): when the speech service is slow (memory
pressure: the Sinhala model unloaded, large-v3 takes 40-60 s for a 6-s Sinhala note), Risi's transcript comes late or
`failed`. The sender's phone often already has an on-device transcript (§37.10, `phone_transcript`), inside the
note's MLS envelope that Risi's device decrypts in Official. **Harsha (2026-10-14) explicitly wants it used as the
fallback.** v1.47 says Risi ignores it.

## Proposal
Replace the v1.47 "Server / Risi" paragraph "In v1.47 Risi **ignores it**…" with:

- **Fallback (v1.48).** Risi's device in Official (and in the sender's Risi chat, where the field is never written,
  so this never applies there) may use a valid `phone_transcript` **only as a fallback**, when its own transcription
  (§37.6) times out or fails. The time budget is **3 × the note's duration, at least 15 s**. Order:
  1. Risi's routed model (§36.5) within the budget;
  2. the note's `phone_transcript`, if present and valid (§37.10 receiver rules);
  3. a fast local model (omniASR-CTC-1B, CPU int8) on the same audio;
  4. otherwise `status: "failed"` as today.
- **The post** is the normal §37.6 `voice_transcript` with `status: "ok"`, the phone's `text`, `language` = the
  primary subtag of `lang` when it is `si` / `ta` / `en` (else `null`), `model: "phone-on-device"`, and **`"source":
  "phone"`** (new optional field; absent = Risi's own model). `made_by` = `{"model": "phone-on-device", "provider":
  "phone"}`. Phones that render §37.10 show **one** transcript block for that note when `source` is `phone`
  ("Transcript (sender's phone) ▾"); old phones show the post as today.
- **Same rules as Risi's own transcript:** the opt-outs still win (a note with `transcribe: false` carries no
  `phone_transcript` anyway, §37.10 sender rules); memory, the Ledger and §38 see it like any `ok` transcript; the
  learning log row has `model: "phone-on-device"`, `output: null`; never logged.
- **Never Private** (unchanged: Risi isn't a member).
- **Never a primary path:** Risi never skips its own model because a phone transcript exists (the compare view and
  the Voice agent's transcription Index keep scoring both).

## Addendum (2026-10-14, later): Harsha's Google transcript
- Harsha opted in (his own notes, Official and the Risi chat) to a phone transcript from Google's **online**
  recogniser: `phone_transcript.engine = "android-online-google"`. Note: this relaxes the §37.10 sender rule
  "on-device only" **for his opt-in only**; that sender-side change needs its own line in §37.10 (root).
- A Risi post taken from it carries `"source": "phone"` **and `"engine": "android-online-google"`**, and
  `model: "phone-google"`. Phones render it as **"Transcript (Google)"** (any other engine: "Transcript (sender's
  phone)").
- For a dev user's own notes the server uses it as the fallback even while `RISI_VOICE_PHONE_FALLBACK` is off
  (his choice): when our model fails the hallucination guard, or when the Voice agent's route decision for him is
  `phone` (Google leads on his labelled notes; our model takes over once it beats Google through the promotion
  rule, docs/status/asr-harsha-gold.md).

## Example
`envelope_risi_voice_transcript_phone.json`: `envelope_risi_voice_transcript.json` with `"model":
"phone-on-device"`, `"source": "phone"`, `"made_by": {"model": "phone-on-device", "provider": "phone", "at": …,
"also": []}`.

## Gate
server: the fallback order (timeout → phone → CTC → failed), the budget formula, the flag off → v1.47 behaviour
(field ignored), no text in logs or `risi_agent_runs`. android: `source: "phone"` renders once.
