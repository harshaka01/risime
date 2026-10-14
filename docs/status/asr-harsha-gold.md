# Harsha's Sinhala gold set and compare mode (2026-10-14)

Harsha, 08:35 SLT: his real 16-s Official Sinhala note came back as hallucination: Sinhala script, not one
correct word, one word repeated. The service log shows that note (03:05:33 UTC) was answered by
**faster-whisper-large-v3 as the memory fallback**, not by Qwen3-ASR-0.6B-Sinhala, which memory pressure had
evicted (docs/status/incident-2026-10-14-sinhala-slow.md). So this gold set decides which model is right on
**his real speech** (ARCHITECTURE-PRINCIPLES §3: real speech, not public sets).

## Agent design
- **Owner:** the Voice agent. The compare mode is its eval tool, and the route decision is its learning loop.
- **Signals:** his pick ("2") or correction ("fix: …") per note. Interim reference: his opt-in Google transcript
  (`engine: android-online-google`, "Google").
- **Eval:** CER and WER per model against his label. A model is "right" on a note at CER ≤ 20 %.
- **Promotion rule:** a per-user route for him only, recorded (below). Never anyone else's route.
- **Flags:** `RISI_SPEECH_COMPARE_DEV` (`off` | `canary` | `on`; default `canary` = `RISI_DEV_USERS` only; it never
  runs for anyone else); the hallucination guard's kill switch `RISI_SPEECH_HALLUCINATION_GUARD=off`.

## What happens to one of his Sinhala or Singlish notes
1. **Official chat:** the normal path, through the hallucination guard (`RisiMe.Agent.SpeechQuality`). It gets only
   the guarded best transcript, or "Risi couldn't hear … clearly". When ours fails the guard and he sent a Google
   transcript, that one is shown (`source: phone`, `engine: android-online-google`, rendered "Transcript (Google)").
2. **His Risi chat only:** one numbered message lists Qwen3-ASR Sinhala, omniASR-CTC-1B, omniASR-LLM-7B (streamed,
   only while MemAvailable ≥ 12 GB, else "skipped: memory"; "skipped: not configured" without the omniASR compare
   URL), and the phone transcript ("Google" or "Phone (on-device)"). Each line has its seconds and its guard code,
   and the message says what the chat got. Other members never see it.
3. **His reply** within 2 h, `1`…`n` or `fix: <the right words>` (also `correct:` / `නිවැරදි:`), labels the newest
   compared note. Risi answers "Saved …" with each model's CER, and the route decision if it changed. Any other
   text is a normal request.

## The gold set (eval only, NEVER training)
- **Where:** `~/risime-sealed/eval-real-harsha/` (`RISI_ASR_GOLD_DIR`), on spark2 only, never in git. The directory
  is mode 700 and the files mode 600.
- **Per note:** `<note_id>.audio.sealed` (the decrypted Ogg/Opus) and `<note_id>.json.sealed` (candidates' texts and
  timings, what was posted, the label, the scores, and `vs_google`, our models against Google). Both are
  AES-256-GCM under `RISI_DATA_KEY` (`.env`), with AAD `asr_gold:<note_id>:audio|json`.
- **Consent:** Harsha's, for his own notes, eval only (2026-10-14). This is the one exception to §37.6 "audio never on
  disk", limited to dev users' own notes while the flag is on.
- **`NO_TRAINING` marker** in the directory. Every training script must refuse a directory that contains it. None
  of our tools trains, and asr-eval and the compare tools only read it.
- **Retention:** until he deletes. Deleting the note for everyone deletes its gold files (`Voice.notes_deleted`).
  To drop the whole set: `RisiMe.Agent.SpeechGold.delete_all()` (rpc).
- **Logs:** ids, model names, codes and numbers only.

## His default Sinhala route (promotion rule, recorded)
`SpeechCompare.route_decision/1` runs after each label. The choice is his per-user override (admin settings key
`speech_route_si_user_<uuid>`, set by Risi's account, audit-logged; it also wins over the console's global
choice for his notes):
- **From 5 labelled notes:** the candidate with the lowest mean CER (on ≥ 3 notes), if ≤ 60 %. If it is Google, the
  route is `phone`: his Google transcript is shown until one of our models beats it (the switch-back rule).
  If even the best is over 60 %, the route is `none` (show nothing).
- **Before that:** `omnilingual-ctc-1b` when it is ≥ 15 points less wrong than every other; `phone` when only Google
  is under 60 %; `none` when every model is over 60 %; else Auto.
- **Each change** writes a `risi_agent_runs` row (agent `voice`, mode `route`, the choice, the reason with numbers),
  an admin audit row, and a line in his Risi chat.

## Update (Harsha, later 2026-10-14)
- **Candidates:** Qwen3-ASR Sinhala, **Whisper small Sinhala**, omniASR-CTC-1B, omniASR-LLM-7B (streamed), plus the
  Google or phone transcript.
- **Reference = Google, in shadow:** every note is scored at once against his Google transcript (`vs_google`). His
  correction, when given, beats Google for that note. The Voice agent re-runs the route decision after **every**
  note (not only after a label), with no user-visible output except a one-line route change in his Risi chat.
  The stored CER is the source of truth.
- **Off switch:** "compare off" / "compare on" in his Risi chat (per-user setting
  `speech_compare_post_user_<uuid>`). While off, nothing is posted and the scoring continues.
- **Console:** Speech page, card "Sinhala on real speech": mean CER per model, number of notes scored and
  corrected, each dev user's current Sinhala route, and whether compare posts are on.
- **Server default for si:** the winner, through the rule above (his per-user route first). When nothing passes the
  hallucination guard, no transcript is shown (srv3).

## Update (Harsha 09:54 SLT)
- **Language first:** the candidates follow the note's language from the normal path. Sinhala: Qwen3-ASR Sinhala,
  Whisper small Sinhala, CTC-1B, LLM-7B. Tamil: tamil-asr-qwen3, Whisper Tamil medium, CTC-1B, LLM-7B. English:
  large-v3, CTC-1B. An English note never runs the Sinhala models.
- **Option 0** is always what the chat actually got; "0" confirms it.
- **Skipped for memory → night run** (`RisiMe.Agent.SpeechCompareNight`):
  - Oban cron 16:45 UTC. It runs only between 16:30 and 00:30 UTC, while the eval guard says go (no pause file, live
    service healthy, MemAvailable ≥ 5 GB). It holds the `scripts/heavy` flock while it works and snoozes 20 min otherwise.
  - It runs the queued models on the sealed audio, with the 7B streamed.
  - Delivery: cron 01:35 UTC (07:05 SLT), one "Overnight speech compare results" post per recipient right after the
    07:00 brief.
- **Other senders (e.g. the Tamil speaker), only with compare consent:**
  - The admin setting `speech_compare_consent_user_<uuid>`: default none, granted on the console's Speech page,
    audit-logged. Nobody is enabled automatically: Harsha adds the person once they agree.
  - Their notes are compared and stored sealed only while consent is on.
  - The numbered message goes only to dev users who are members of the same conversation, in their own Risi chats.
    Never to the sender, never to the Official chat.
  - The sender may correct their own note with `fix: …` (also `திருத்தம்:`) in their Risi chat. **Their correction
    beats a dev user's label**, and nobody overwrites it.
  - A consenting sender's route decision is recorded as a recommendation only (`risi_agent_runs` status
    `recommended`), never applied.
- The route decision is per sender and per language, over that sender's own notes.

## Report
`RisiMe.Agent.SpeechCompare.report(5)` (rpc on the pilot) gives the table of his last 5 labelled notes: per model,
status, guard code, CER, WER and right/wrong. Numbers only.
