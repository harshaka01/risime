# Risi models: survey, eval method and results

Root side track, 2026-10-10. It answers which open model should serve Risi next to the current
`risi-l1` (Qwen3.6-35B-A3B-FP8), and which speech model can handle Sinhala and Tamil.
**Nothing was switched.** Switching is a separate change that needs Harsha's yes.
Rerun everything with `scripts/risi-eval` (see "How to rerun").

## 0. Results (2026-10-10)

### Headline findings
1. **Most of Risi's failures come from the schema, not the model. Fixing it is a server change,
   not a model switch.**
   - The cause: the server sends the `risi_next_action` JSON schema Jason-encoded. That sorts the
     keys, so `args`/`answer` come before `tool` in every alternative.
   - vLLM's guided decoding writes properties in schema order. So the model has to start writing
     `{"a…` before it has named a tool, and almost always ends up in `final`.
   - The effect on live risi-l1, unchanged otherwise:
     - correct tool calls: **8%**;
     - false "I've set the alarm" claims with no tool call or card: **19**.
   - With `tool` first (`risi_eval.py --tool-first`), the same model gets **68%** correct and
     **0** false claims, at the same speed.
   - The fix belongs to the server role: emit `tool` first, e.g. with `Jason.OrderedObject` in
     `RisiMe.Agent.Tools.schema/2`. Every candidate was also run both ways.
2. **Speech:** whisper large-v3, which is live, is unusable for Sinhala (108% WER) and weak for
   Tamil (58% WER).
   - **SinhaSpeech/whisper-small-sinhala** gets **14.5% WER / 5.7% CER** on YouTube Sinhala
     (possible overlap with its training data: confirm on Harsha's recordings).
   - **lemuralabs/tamil-asr-qwen3**: see the ASR table.
3. **Memory decides what can be tested at all.**
   - With risi-l1 (47.4 GB), whisper (6.9 GB), Cassandra (6.8 GB) and the gates' Gradle daemons
     resident, the kernel's MemAvailable stayed at **22–38 GB** all day.
   - A candidate costs its `--gpu-memory-utilization` share **plus ~7 GB** (CUDA context, torch
     compile workers, the profile pass; measured with `eval/risi/memlog.py`, files in
     `results/loadtest/`).
   - With the 10 GB headroom rule, only models with a share of 20 GB or less ever fitted beside
     risi-l1 today.

### LLMs: the eval set (140 items) against Risi's real prompts
<!-- LLM-TABLE -->

### ASR (50 Sinhala clips from SPEAK-ASR/youtube-sinhala-asr test, 50 Tamil clips from FLEURS ta_in test)
<!-- ASR-TABLE -->

<!-- RECOMMENDATION -->

## 1. Hugging Face survey (checked 2026-10-10 against the public HF API, no token)
Notes on the numbers:
- **Disk** is the sum of the weight files in GB.
- **Serve** is weights plus KV for 8k context × 4 sequences plus about 3 GB of activations. It's
  the `mem_gb` used for launch decisions.
- **vLLM** means `config.json` `architectures` is in the registry of our image
  (`nvcr.io/nvidia/vllm:26.09-py3` = vLLM 0.29.0, transformers 5.16.1).
- Every repo listed is public and ungated unless marked.

### LLMs
| Repo | Date | Licence | Params (active) | Disk GB | Serve GB | vLLM arch | Worth testing |
|---|---|---|---|---|---|---|---|
| Qwen/Qwen3.6-35B-A3B-FP8 (**live risi-l1**) | 2026-04 | apache-2.0 | 36B (3B) MoE | 37.5 | 47.4 measured (0.34 share) | Qwen3_5MoeForConditionalGeneration ✓ | baseline |
| **Qwen/Qwen3.8-27B-FP8** | 2026-08-13 | apache-2.0 | 27.8B dense | 30.9 | ~38 | Qwen3_5ForConditionalGeneration ✓ | **yes**: the newest official Qwen text model that fits |
| RadixArk/Qwen3.8-27B-NVFP4 | 2026-08-14 | apache-2.0 | 27.8B | 21.9 | ~29 | same ✓ (NVFP4 on GB10 unverified) | fallback if memory is tight |
| Qwen/Qwen3.8-Flash-Next-FP8 | 2026-08-24 | qwen-community | 180B (6B) | 185.5 | — | Qwen4ExpForConditionalGeneration ✓ | no: doesn't fit |
| Qwen/Qwen3.8-2.4T-A95B-FP8 | 2026-08-08 | other | 2.45T (95B) | 2496 | — | ✓ | no |
| "Qwen3.7", "Qwen4" | — | — | — | — | — | — | **don't exist** as official weights. The `Qwen4Exp` arch is only Flash-Next; other Qwen4 repos are test fixtures |
| **RedHatAI/gemma-4-26B-A4B-it-FP8-dynamic** | 2026-04-06 | apache-2.0 | 25.8B (3.8B) MoE | 28.6 | ~34 | Gemma4ForConditionalGeneration ✓ | **yes**: fast MoE, "140+ languages" pre-training |
| **RedHatAI/gemma-4-31B-it-FP8-block** | 2026-04-03 | apache-2.0 | 31.3B dense | 33.3 | ~42 | ✓ | **yes**: the strongest Gemma that fits at rest |
| **RedHatAI/gemma-4-12B-it-FP8-Dynamic** | 2026-06-08 | apache-2.0 | 13B dense (+audio) | 15.0 | ~20 | Gemma4UnifiedForConditionalGeneration ✓ | **yes**: small, and has audio input |
| google/gemma-4-31B-it-qat-w4a16-ct | 2026-06-04 | apache-2.0 | 31.3B | 23.3 | ~29 | ✓ | alternative for a 22 GB budget |
| Qwen/Qwen3-Omni-30B-A3B-Instruct (+ FP8/AWQ community builds) | 2025-09 | other | 35B (3B) | 70.5 (FP8 37.4) | ~40 | Qwen3OmniMoeForConditionalGeneration ✓ | no: its 19 speech-input languages don't include si or ta. No newer Qwen Omni exists |
| nvidia/Nemotron-3-Nano-Omni-30B-A3B-Reasoning-FP8 | 2026-04-24 | other | 33B | 35.2 | ~37 | ✓ | low: si/ta unverified |
| sarvamai/sarvam-30b-fp8 | 2026-03-26 | apache-2.0 | 32B (2.4B) | 38.6 | ~40 | SarvamMoEForCausalLM ✓ (card wants a fork) | low: 22 Indian languages incl. Tamil, **no Sinhala** |
| polyglots/SinLlama_v01 | 2025-08 | Llama 3 licence | LoRA on Llama-3-8B (gated base) | 7.5 adapter | — | LoRA | no: a **base** model (not instruct) with Sinhala classification evals only; merged copies (SAWithanage/…-Merged, 16 GB) are the same |
| openai/gpt-oss-120b / -20b | 2025-08 | apache-2.0 | 117B (5.1B) / 21B (3.6B) | 65.3 / 13.8 | ~68 / ~15 | GptOssForCausalLM ✓ | 120b doesn't fit beside risi-l1; 20b has no si/ta claim |
| CohereLabs/tiny-aya-* | 2026-02 | cc-by-nc-4.0 | 3.4B | 6.7 | — | ? | **gated** + non-commercial: skipped |

There is no recent, instruct-tuned, Sinhala-specific LLM in the 15–55 GB range on HF. The
realistic options are general multilingual models.

### Speech (ASR)
| Repo | Date | Licence | Size | Serving | Reported |
|---|---|---|---|---|---|
| openai/whisper-large-v3 (**live** as faster-whisper on :8200) | 2023-11 | apache-2.0 | 1.5B, 3.1 GB fp16 | CTranslate2 image / vLLM Whisper ✓ | see results |
| openai/whisper-large-v3-turbo (local CT2 copy) | 2024-10 | mit | 0.8B | same | — |
| **SinhaSpeech/whisper-small-sinhala-v6-e6-run11-best** | 2026-09-30 | apache-2.0 | 0.24B, 0.97 GB | vLLM Whisper ✓ | 16.4 WER / 4.4 CER on its own test |
| kasunw/whisper-large-v3-sinhala | 2024-12 / 2026-09 | apache-2.0 | 1.5B, 6.2 GB fp32 | vLLM Whisper ✓ | none (trained on OpenSLR 52) |
| Nerdstorm/Qwen3-ASR-0.6B-Sinhala-8bit | 2026-09-26 | cc-by-sa-4.0 | 1.0 GB MLX | **MLX/OpenVINO only**: needs an FP16 export | 6.9 CER (SLR52), 20.5 CER (YouTube) |
| **lemuralabs/tamil-asr-qwen3** | 2026-07 | apache-2.0 | 2B, 4.1 GB | Qwen3ASRForConditionalGeneration ✓ | FLEURS-ta 25.3 WER / 8.0 CER |
| vasista22/whisper-tamil-large-v2 | 2023-01 | apache-2.0 | 6.2 GB | Whisper ✓ | FLEURS-ta 7.5 WER (different normaliser) |
| Qwen/Qwen3-ASR-1.7B | 2026-01 | apache-2.0 | 4.7 GB | ✓ | si/ta **not** among its 30 languages |
| ai4bharat/indic-conformer-600m-multilingual | 2025-03 | mit | — | NeMo/ONNX | **gated**: skipped; Tamil only |
| facebook/mms-1b-all, seamless-m4t-v2 | 2023 | cc-by-nc-4.0 | — | not vLLM | non-commercial; MMS has no Sinhala adapter |

**Test sets:**
- **FLEURS has no Sinhala** (`si_lk` is 404). Tamil is `google/fleurs` `ta_in` test (CC BY 4.0).
- **Common Voice has left HF** (Mozilla Data Collective since 2025-10).
- **Sinhala:** `SPEAK-ASR/youtube-sinhala-asr` test (405 rows, YouTube speech, code-mixed flags;
  the repo states no licence: OK for internal eval, not for redistribution).

<!-- METHOD -->
## 2. Method

### Eval set (`eval/risi/`)
| File | Items | What |
|---|---|---|
| `si.jsonl`, `ta.jsonl`, `en.jsonl` | 40 each | The same 40 scenarios in native Sinhala, Tamil and English (names localised) |
| `mixed.jsonl` | 20 | Singlish and Tanglish: romanised, and native script mixed with English |

Each line holds:
- `id`, `lang`, `category`;
- `messages`: the real system prompt and the user message, rendered the way the server renders
  it;
- `schema`: a path into `prompts.json`;
- `expect`: an expected tool and args, or a rubric;
- `review`: for wording Harsha should check.

**The prompts are Risi's own.** `eval/risi/prompts.json` is dumped from the compiled server
(`scripts/risi-eval dump`, `RisiMe.Agent.Turn.system/2`, `Tools.schema/2`, `Prompts.*`):
- the §25 `risi_next_action` turn with the real tool registry (capabilities, set_reminder,
  calendar_check/add, risi_calendar_check/add, set_alarm, schedule_message, cancel_scheduled)
  and its JSON schema;
- the `ask` answer prompt;
- the chat summary;
- the commitment extraction.

Requests use the server's settings: guided JSON (`response_format: json_schema`, strict),
temperature 0.2, top_p 0.8, thinking off. The fixed clock is Monday 2026-10-12 09:15,
Asia/Colombo.

| Category (per language) | n | Checks |
|---|---|---|
| Tool calls (T01–T15): alarm, "wake me at 6 and message Kumu good morning", reminders (me / the chat / relative "in 20 minutes"), calendar check/add (Risi and phone calendars), weekday alarm, scheduled and daily messages, cancel | 15 | Tool name and args. Times are resolved like the server's `TimePhrase` (ISO or an English phrase; a bare "6" is ambiguous) and compared with the expected local time. A `final` whose `draft` fills the action card also counts (§25.1) |
| Honesty (T16 search, T17 booking, F04 unreadable calendar) | 3 | No false claim; says what it can't do. F04: `read_ok: false` must never become "you're free" |
| Missing info (T12 "remind me at 6") | 1 | One short question, no guessed reminder |
| Capabilities / small talk (T11, C1) | 2 | Tool `capabilities` or a correct list; short reply |
| Follow-ups (F01 second step after the alarm card, F02 "no, make it 7" over a pending draft, F03 a calendar result with a busy block) | 3 | Next tool or args; correct use of the tool result |
| Summaries (SU1–SU6): release chat, team lunch, invoice error, greetings only, a prompt injection, website launch | 6 | Key facts present (day, owner, amount); no decisions or action items invented for small talk; injection not followed |
| Commitment extraction (E1–E6) | 6 | Owner + due date exactly; no commitment from questions, wishes, things already done or old lines |
| Questions about the chat / unknowns / injection (A1–A6) | 6 | Right fact + refs; "Wi-Fi password", "who won the cricket" → honest, nothing invented; no system-prompt leak |

### Metrics
- **JSON valid**: parses and validates against the schema.
- **Tool-call ok**: on items that expect a tool, the right tool with the right args (or an
  equivalent draft).
- **Task ok**: the summary, extraction and ask checks.
- **Hallucinations**: counted automatically.
  - a false action claim ("I've set an alarm" without a tool call or card);
  - invented refs/sources;
  - extra (invented) commitments;
  - decisions or action items invented for small talk;
  - forbidden content (an invented score or amount, "booked", a followed injection).

  The blind judge also flags hallucinations.
- **Script correctness** for si/ta answers is computed over the letters of the answer.
  - ok: at least 50% in the target script.
  - Latin: at least 80% Latin, i.e. an English or romanised reply.
  - wrong script: at least 5% of another Indic or foreign script, e.g. Tamil in a Sinhala answer.
  - mixed: anything else.
- **Speed**: decode tokens/s = (completion tokens − 1) / (total − TTFT), single stream, median;
  TTFT median; streaming.
- **Peak memory**: MemAvailable just before the launch, minus its minimum while the candidate was
  loaded and running, sampled every 2 s. For unified memory this is the real cost to the box.
- **Quality**: a blind side-by-side against risi-l1 on every item. A and B are shuffled with a
  fixed seed (`risi_eval.py pairs`), and the key is kept away from the judge. The rubric:
  correctness against the expected outcome, faithfulness (no invention), language and script
  match, helpfulness and brevity, 1–5 per side, and a winner. **The judge is Claude (Opus 5.5)
  subagents, not a human,** recorded as such.

### Safety during the runs (Harsha's rules)
- One candidate at a time: `risi-eval-<name>` on 127.0.0.1:8101, same image as risi-l1.
- Stopped and removed right after its test (a trap).
- Never while `nightly-release` or `ui-entry-test` runs; a candidate also yields at once if one
  starts.
- Launched only if MemAvailable ≥ serve GB + 10 GB. `--gpu-memory-utilization` = serve GB /
  MemTotal, `--max-model-len 8192`, `--max-num-seqs 4`. Killed if MemAvailable < 6 GB.
- Live risi-l1 is only read: sequential requests.
- `scripts/risi-eval probe` (risi-l1 `/health` + a 3-token completion + pilot `/health`) runs
  before, every 60 s during, and after every candidate. A failure kills the candidate.
- `scripts/risi-canary` (its own instance `_eval`, ports 4390/8490; it never touches :4000 or
  :8100) runs before the chain, once while each candidate is loaded, and after.
