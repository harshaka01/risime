# Incident 2026-10-14: Tamil voice notes transcribed in Sinhala script (P1)

**Reported:** Harsha, 09:29 SLT (03:59 UTC). A Tamil speaker's Official voice note (09:26 SLT,
03:56:15 UTC, 6.1 s) got a transcript whose content was right but whose language and script were
wrong: Tamil sounds written in Sinhala letters. `RISI_VOICE_TRANSCRIBE` had been `on` for everyone
since 09:22 SLT, so every Tamil speaker was affected.

## What happened (speech service log, codes and numbers only)
`transcribed 6.1s audio in 0.55s model=omnilingual-ctc-1b req=auto lang=si route=detected
detected=si p=0.7644 probs=si:0.7644,ta:0.0637,en:0.1718 top=si/0.4675 hint=None mode=prior
fallback=omnilingual-ctc-1b`

1. **No language hint for this sender at all** (`hint=None`): the speaker had no typed Official
   messages in Tamil script and no earlier transcribed speech, and the chat had none either. So the
   route was plain large-v3 detection.
2. **large-v3 confuses Tamil and Sinhala on short clips:** it said si (0.76 over si/ta/en; its own
   top language si at 0.47). The Tamil share ta/(si+ta) was 0.077: higher than on 95 % of Sinhala
   chunks (detect-1 eval: ≤ 0.038), but the old rule took the single best guess.
3. **The si models had no memory**, so the service's CPU fallback (omniASR-CTC-1B, not
   language-conditioned) answered, in Sinhala letters, and **nothing script-checked a Sinhala-script
   text for being Tamil**: the CTC fallback was only checked for "has Sinhala letters".
4. The "whisper-small-sinhala" label came from the developer compare, which forces the primary's
   language on every candidate (`SpeechCompare`); no whisper-small-sinhala answer exists in the log.

**A latent cause found on the way (worse than the incident):** the `prior` hint mode restricts
detection to the *hinted* languages + en. A chat whose hint is `si` (the chat's script and its
earlier calls: `Lang.speech_hint/2` includes the chat's language, not only the speaker's) leaves
Tamil out entirely. On 8 fleurs-ta clips with hint `si`, today's route gave **English (a
translation by large-v3) for 7** and Sinhala-labelled for 1; none came out as Tamil.

**The per-speaker table `risi_speech_langs`** existed (decision 071 d) but was only a hard
threshold (≥ 10 s and 10 % → that language joins the hint), it learned the *routed* language (a
wrong si guess reinforced itself: this speaker now has 6.1 s of "si"), and corrections had no
effect on it.

## Fix (server, fast lane; flag `RISI_VOICE_LANG_ID`, default `on`; `off` = the old route)
`RisiMe.Agent.SpeechLang` (the Voice agent's language decision), around the routed model in
`Speech.transcribe_note/2`:

- **Language ID before routing:** the service's `/v1/audio/detect` (large-v3, no hint) is asked
  first and combined with **the sender's own profile** as a bounded prior (si/ta milliseconds +
  60 s per correction, Dirichlet-smoothed, clamped 0.15–0.85). The chat's language no longer
  decides si vs ta for a speaker. Plan: ta share ≥ 0.9 → force ta; ≤ 0.04 → force si; in
  between → **both the si and the ta route run in parallel** and one is kept. English and unsure
  speech keep today's path.
- **Arbitration:** risi-l1's per-character log-likelihood of each text (vLLM prompt logprobs, one
  request each, ~0.1 s, nothing generated): Tamil wins when lm(ta) − lm(si) > −0.22. On 60
  public pairs (30 fleurs-ta, 30 yt-si, today's routes) every Tamil clip had ≥ −0.16 and every
  Sinhala clip ≤ −0.28. Without risi-l1: the detector posterior and the feature score below.
- **Script / phonotactic check on every Sinhala-script result** (`suspect?/4`): a Tamil-like
  feature score (few Sinhala words and Sinhala endings, many Tamil-like word endings -m/-l/-u;
  logistic, fitted on 1,615 Sinhala outputs vs Tamil speech through Sinhala models + transliterated
  Tamil), a sender profile with Tamil evidence, a detector Tamil share > 0.04, or (without a clear
  detection) a low risi-l1 likelihood → re-run as ta and arbitrate.
- **The language follows the final text's script**, always (a CTC text in Tamil letters is never
  labelled si any more), and a Sinhala-letter **CTC fallback text judged Tamil is transliterated
  into Tamil letters** (`si_to_ta/1`) instead of shown in Sinhala script.
- **Profile (L1):** `risi_speech_langs` gains `notes` and `corrections` counts (additive migration);
  a note counts in its *decided* language (`SpeechLang.learn/3`); `SpeechLang.correct/4` (for the
  "Wrong language?" correction, proposal 2026-10-14-voice-translate) moves the note's milliseconds
  and adds a correction. Codes and numbers only.
- Every decision writes a `risi_agent_runs` row (agent `voice`, mode `lang_id`: decision, first
  guess, plan:reason code, latency) for the Voice agent's language-ID Index.

No speech-service change was needed (the decision is the Voice agent's, the service is its tool),
so there is no container roll for this fix.

## Before / after (public clips only; no real note was used)
| Clip | Hint | Before (today's route) | After |
|---|---|---|---|
| fleurs-ta-1890 (Tamil; large-v3 detects si 0.64) | none | labelled **si**, CTC fallback | **ta**, Tamil script (both run, si result had no Sinhala text: `both_only_ta`), 1.8 s |
| 7 fleurs-ta clips | si (a Sinhala chat) | **English** (large-v3 translation) | **ta**, Tamil script (force ta), 1.0–21 s (one 56 s: the Tamil models don't fit in memory now, below) |
| 1 fleurs-ta clip | si | labelled si, Tamil-script CTC | ta |
| 8 yt-si clips (Sinhala) | si | si | si, identical text, 0.6–1.3 s |

## Open (not this fix)
- **Memory:** during the tests neither Sinhala route model nor tamil-asr-qwen3 could load
  (MemAvailable ~4.7 GB): every Sinhala note was served by the CPU CTC fallback and Tamil by
  large-v3 forced to ta, some taking 20–56 s at batch priority. That is the speech service's
  memory budget (the speech agent's area), not language ID.
- The 6.1 s "si" this speaker's profile learned from the wrong note stays until his next notes
  (weak: a 0.58 si prior, which a Tamil detection overrides) or a "Wrong language?" tap.
- Gold set: Tamil speakers' real notes (with consent) to score language ID on real speech
  (ARCHITECTURE-PRINCIPLES §3); the numbers above are public sets, labelled as such.
