# TTS candidates: installed, served and measured (Harsha 2026-10-14)

Where to compare them: console **Voices** page (`/admin/voices`): the Engines table (status, languages, licence,
production OK?) and **Test**. Test speaks a typed sentence with one voice per engine (or every voice), shows load,
first audio, total and RTF, gives a player, and takes a 👍/👎 per reply. Serving: decision 091 (`risime-tts-lab`).

## Status per engine (2026-10-14 04:10 UTC)
| Engine | Installed? | Languages (si / ta / en) | First audio | RTF | Memory | Licence | Production OK? |
|---|---|---|---|---|---|---|---|
| **Kokoro-82M** | **yes**, resident (risime-tts :8230, Call Risi) | – / – / **en** | **105 ms p50, 128 ms p95** (streamed, first chunk) | **0.05** | ~1.8 GB CUDA + 0.34 GB host | Apache-2.0 (weights), MIT (misaki) | **yes** |
| **Chatterbox** | image ready; **t3 weights arrive tonight** (`t3_cfg.safetensors`, 2.1 GB, night window) | – / – / **en** (its 23-language model has no si or ta) | tonight | tonight | est. ~4.5 GB | MIT; **PerTh watermark** (below) | **yes** (watermarked; built-in voice only) |
| **IndicF5** | **no**: weights + code arrive tonight; **code review pending** (`docs/status/tts-indicf5-code-review.md`) | – / **ta** / – (no Sinhala) | after review | after review | est. ~3 GB | MIT | **no**: every voice clones a reference recording (dataset speakers) |
| **Indic Parler-TTS** | image ready; **weights arrive tonight** (`model.safetensors`, 3.76 GB, night window) | – / **ta** / en (**no Sinhala**; its model card lists en, as, bn, gu, hi, kn, ks, or, ml, mr, ne, pa, sa, sd, ta, te, ur) | tonight | tonight | est. ~5 GB (bf16) | Apache-2.0 | **yes** (stock named speakers / described voice) |
| **Orpheus 3B 0.1 ft** | image ready + SNAC decoder here; **weights arrive tonight** (4 shards, ~6.6 GB) | – / – / **en** | tonight (+ streaming estimate) | tonight; expect > 1 (LLM decode bound) | est. ~9 GB (bf16) | Apache-2.0 **+ Llama 3.2 Community Licence** | **with conditions** ("Built with Llama", Meta AUP, 700 M MAU clause) |
| **Phone TTS** | phone-side only (Android `TextToSpeech`, on-device voices only); dev test screen | per phone: see `docs/status/phone-tts.md` | on the phone | – | – | the phone engine's | with conditions (the phone's engine, on-device) |
| **MMS-TTS** | **not available (licence)** | no si model; ta CC-BY-NC | – | – | – | CC-BY-NC-4.0 | **no** |

**No engine speaks Sinhala on the server.** Sinhala TTS today can only be the phone's own engine, if it has an
on-device si-LK voice (to check on Harsha's phone: `docs/status/phone-tts.md`).

**Memory, honestly:** host MemAvailable on spark2 was 1.3–4.5 GB this morning (risi-l1, whisper, omniASR,
Cassandra, many gradle/mix runs; the kernel's watermark_scale_factor 1024 also holds ~25 GB back from
MemAvailable). With the 4 GB floor, **every candidate load is refused right now** (`503 no_memory`, console
"refused: not enough free memory"). That is the rule working: Call Risi's Kokoro stays resident and safe. Tonight's
measurements need a quieter machine (after the gates and agents finish).

## Chatterbox's PerTh watermark: what it means
- Chatterbox runs every output through Resemble AI's **PerTh** ("Perceptual Threshold") watermarker
  (`resemble-perth`, MIT, the `perth.PerthImplicitWatermarker` call in `chatterbox/tts.py`). A small neural
  network hides a mark in the audio beneath the threshold of hearing (psychoacoustic masking).
- It is **inaudible**, and survives MP3/Opus compression, edits and common manipulations. Resemble claims near-100 %
  detection.
- **Anyone** with the open-source `perth` library can check a clip and see that it is AI-generated speech from
  Chatterbox. The mark does **not** identify RisiMe, the user or the text, and nothing is sent anywhere (no
  telemetry; it runs locally on the CPU).
- For RisiMe this fits the visible-agents principle: Risi's voice is labelled as AI. The cost is a little CPU per
  utterance (part of the measured time). Removing it is technically possible (MIT), but we keep it.

## Risi's voice: no cloning
Only built-in or stock voices: Kokoro's voices, Chatterbox's built-in `conds.pt` (the lab never passes
`audio_prompt_path`), Parler's stock named speakers with a fixed description, and Orpheus's stock voices (tara, leah,
jess, leo, dan, mia, zac, zoe). IndicF5 can only clone a reference recording, so it is "production: no" until a
licensed or synthetic reference voice exists. There is no upload or cloning path anywhere.

## Voice-quality ratings (the Voice agent's Index)
Each 👍/👎 on the Test page is an audit entry (`tts_rating`: voice id, score, first audio, total and load ms; never the
sentence). The day's ratings are summed into `risi_agent_scores` (`agent = voice`, `source = live_tts`: n, 👍 share,
p50/p95 first audio, per-voice detail). That is the *Live* half of the Voice agent's TTS Index (decision 085). Auto
still keeps today's route (Kokoro) until an Eval half exists and the promotion rule is built (decision 089).

## Tonight (after the night downloads, 16:30 UTC +): run-book
1. `tail ~/risime-eval-data/dl/downloads-2026-10-14-tts2.log` until Chatterbox t3, IndicF5, Indic Parler and
   Orpheus are `done`. Small files are also queued behind the big ones (the script is sequential).
2. `curl -s 127.0.0.1:8231/v1/engines`: Chatterbox, Indic Parler and Orpheus move from `downloading` to `ready`
   by themselves (no restart). IndicF5 shows `review_pending`.
3. `python3 -I infra/voice/tts_lab_bench.py --reps 2 --md` (when MemAvailable ≥ engine estimate + 4 GB): fill the
   table above. Estimates can then be set from the measured drop with `TTS_LAB_EST_<ENGINE>_MB`.
4. IndicF5: the review steps in `docs/status/tts-indicf5-code-review.md`; only then the adapter and
   `TTS_LAB_INDICF5_CLEARED`.
5. Harsha: Voices page → Test with an English and a Tamil sentence; rate the replies.

## Files
`infra/voice/tts_lab_server.py` (supervisor), `tts_lab_worker.py` (one engine per process), `Dockerfile.tts-lab`,
`tts_lab_check.py` (build: imports), `tts_lab_selftest.py` (serving rules, 14 checks, PASSED 2026-10-14),
`tts_lab_bench.py` (TTFA/RTF/memory), `infra/docker-compose.tts-lab.yml`. Server: `RisiMe.Agent.TTS.Lab` (+ the
Chatterbox, IndicF5, IndicParler and Orpheus engine modules), `RisiMe.Agent.TTS.Ratings`, `RisiMeWeb.Admin.VoicesLive`.
