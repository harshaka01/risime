# 091: TTS candidates served for comparison (risime-tts-lab), Kokoro stays resident for Call Risi

**Status:** accepted 2026-10-14 (root, Harsha's TTS-candidates task). Infra plus server; no wire-protocol change.
Results and run-book: `docs/status/tts-candidates.md`. IndicF5 code review: `docs/status/tts-indicf5-code-review.md`.

## Context
Harsha wants to compare TTS voices on the console's Voices page (decision 089): Kokoro (already serving Call Risi),
Chatterbox, IndicF5, Indic Parler-TTS and Orpheus 3B on the server; Phone TTS on the phone; MMS shown as unavailable
for licence reasons. spark2's unified memory is shared with risi-l1 (never touched), whisper and omniASR, and
Call Risi needs Kokoro's ~100 ms first audio at all times.

## Decision
1. **A sibling container, not a change to risime-tts.** `risime-tts-lab` (`infra/docker-compose.tts-lab.yml`,
   `127.0.0.1:8231`, same NGC arm64 base, read-only models, no-new-privileges, cap_drop ALL, offline). Call Risi's
   Kokoro path (risime-tts on :8230, `RisiMe.Agent.TTS.Kokoro`, `CallRisi.voice/1` = Kokoro voices only) is
   unchanged; parity test `tts_lab_test.exs` "Call Risi speaks Kokoro whatever the Voices page chose".
2. **One engine at a time, each in its own process** (`tts_lab_worker.py <engine>`, `python3 -I`, its own pinned
   package directory `/opt/eng/<engine>`). Chatterbox and Parler pin transformers 4.46 while the base has 5.x;
   separate processes keep each engine's pins and make an unload free all of its memory (the process exits).
3. **Serving rules** (self-tested, `tts_lab_selftest.py`): load on demand; unload after `TTS_LAB_IDLE_UNLOAD_S`
   (300 s); refuse a load when MemAvailable minus the engine's estimate would fall below
   `TTS_LAB_MIN_AVAILABLE_MB` (4096); stop a load or synthesis if MemAvailable falls below the floor while it runs;
   unload and refuse while the speech-guard pause file is set (live speech first); one synthesis at a time (429).
   The app server refuses console tests while a Call Risi call is running (`:live_call`).
4. **Engines registered** in `:risi_tts_engines`: Kokoro plus `RisiMe.Agent.TTS.{Chatterbox, IndicF5, IndicParler,
   Orpheus}` (one module each through `RisiMe.Agent.TTS.Lab`). Weights missing → "downloading tonight"; IndicF5
   with its weights but no cleared review → "waiting for its code review".
5. **Licences on the page.** `RisiMe.Agent.TTS.catalogue_info/0` carries each engine's licence and a production flag:
   yes (Kokoro, Chatterbox (watermarked), Indic Parler), with conditions (Orpheus: Llama 3.2 licence; Phone: the
   phone's engine), no (IndicF5: voice cloning only; MMS: CC-BY-NC).
6. **Risi's voice is never a clone.** Only built-in or stock voices are offered; there is no upload or reference-audio
   path. IndicF5's stock prompts are labelled as dataset speakers and are "production: no".
7. **Downloaded code runs only when reviewed.** IndicF5 ships `custom_code` and `.pyc` files; it never loads
   through `trust_remote_code`, and runs only after the review clears the pinned revision
   (`TTS_LAB_INDICF5_CLEARED`). Every model file is read as safetensors or `torch.load(weights_only=True)`.
8. **GB10 workarounds** (as risime-tts): cuDNN off; FFT/STFT on CPU (the image's cuFFT is a stub); the base
   image's xformers is hidden from diffusers (its flash-attn version check fails); tensorboard is stubbed for
   descript-audiotools (training-time logging only).

## Agent design (decision 085)
- **Owner: the Voice agent.** The lab and its engines are infrastructure (exact, real-time synthesis), used as
  tools. Choosing which voice Risi uses per language is the Voice agent's decision; today Auto = Kokoro (decision 089).
- **Signals:** per-reply 👍/👎 on the Test page with that reply's first audio, total and load time
  (`RisiMe.Agent.TTS.Ratings`, audit `tts_rating`), summed daily into `risi_agent_scores` (`agent=voice`,
  `source=live_tts`). Later: live call signals (decision 090).
- **Index:** TTS Index = 0.7 × Eval + 0.3 × Live (decision 085). Live = 👍 share (live_tts). Eval (not built
  yet) = a golden set of fixed sentences per language, scored by humans (MOS-style) plus latency
  (first audio ≤ 300 ms for calls). No model grades itself.
- **Learning:** L1 now: Harsha's ratings choose the per-language manual voice. L2/L3 later: the promotion rule
  switches Auto only with an Eval at least as good as today's and latency within budget, and posts a note in
  Harsha's Risi chat.
- **Why a hybrid:** synthesis is deterministic infrastructure; choosing the voice is judgement that has to be measured.

## Consequences
- Python grows by four model-serving engines (allowed: "Python only for model serving"). New PyPI packages are
  pinned in `Dockerfile.tts-lab` (chatterbox-tts 0.1.4, resemble-perth 1.0.1, s3tokenizer 0.3.0, diffusers 0.29.0,
  parler-tts @ d108732c, descript-audio-codec 1.0.0, snac 1.2.1, transformers 4.46.x in the engine directories only).
- Small support downloads (SNAC decoder 79.5 MB, flan-t5 tokenizer files 3.2 MB) are logged in `docs/status/downloads.md`.
- On spark2's current memory (MemAvailable 1–4.5 GB by day), the 4 GB floor refuses every candidate load. The
  candidates can only be measured on a quieter machine. Kokoro is unaffected.
