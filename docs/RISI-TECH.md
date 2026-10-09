# Risi: how it works today (technical facts)

Checked by the orchestrator on 2026-10-09 against the live pilot (spark2), the code on main, the
learning log and the GPU. Numbers come from commands run that day, not from design documents.

## 1. The model behind today's summaries

| | |
|---|---|
| Model | **Qwen3.6-35B-A3B**, served under the alias `risi-l1` |
| Architecture | Mixture of experts: 35B parameters in total, about 3B active per token (256 experts, 8 used per token), 40 layers |
| Quantisation | **FP8** (e4m3), 35 GB on disk at `~/risime-models/Qwen3.6-35B-A3B-FP8` |
| Serving software | **vLLM**, in NVIDIA's container `nvcr.io/nvidia/vllm:26.09-py3` (`infra/docker-compose.llm.yml`, decision 061) |
| Where | spark2, DGX Spark with a GB10 GPU and 121 GB of unified memory. It listens on `127.0.0.1:8100` only and runs offline (`HF_HUB_OFFLINE=1`, no usage stats) |
| Settings | 32k context, at most 8 parallel requests, a 0.34 GPU memory share. "Thinking" is turned off in every Risi call |
| GPU memory used | **47.4 GB** by the vLLM engine (measured with `nvidia-smi`) |
| Speed | **About 50 tokens/s** decode for a single request (`scripts/llm-smoke`); a summary takes about 9.5 s |

Speech-to-text (new, not yet connected to anything) is **faster-whisper large-v3** (CTranslate2
4.8.2, FP16, built for the GB10) on `127.0.0.1:8200`, using 6.9 GB of GPU memory (decision 071).

## 2. Is a commercial LLM configured or called?

**No.**
- **Router:** `RISI_COMMERCIAL` and `RISI_FALLBACK` are not set in `.env` or
  `infra/pilot/pilot.env`, so both are off. The fallback provider is a code stub that makes no
  network call. The local adapter refuses any address that isn't loopback.
- **`.env` keys:** the names are `POSTGRES_PASSWORD SECRET_KEY_BASE OTP_DEV_LOG SMTP_* NOTIFYLK_*
  TURN_SECRET LIVEKIT_API_KEY LIVEKIT_API_SECRET LLM_API_KEY RISI_MLS_KEK RISI_DATA_KEY
  WHISPER_API_KEY`.
  - `LLM_API_KEY` is the key for our own vLLM, and `WHISPER_API_KEY` for our own whisper.
  - There is **no key for any external LLM** (no OpenAI, Anthropic, Gemini, Groq, Mistral or
    similar).
- **Calls per model since Risi went live** (learning log, Cassandra `risi_llm_calls_by_day`):

| Day | Task | Provider | Model | Calls |
|---|---|---|---|---|
| 2026-10-09 | ask | risi_l1 | Qwen3.6-35B-A3B-FP8 | 21 |
| 2026-10-09 | commitment_extract | risi_l1 | Qwen3.6-35B-A3B-FP8 | 38 |
| 2026-10-09 | report | risi_l1 | Qwen3.6-35B-A3B-FP8 | 5 |
| 2026-10-09 | summarise | risi_l1 | Qwen3.6-35B-A3B-FP8 | 6 |
| | **Total** | | | **70, all risi-l1, all ok** |

## 3. The Risi pipeline

### How Official messages reach Risi
1. Risi is a real **MLS member** of each Official group, with its own device and identity. Its
   identity is attested by the server with `kind: "agent"`, and every member sees it in the member
   list.
2. Members' phones encrypt messages end to end. Risi's process on spark2, `RisiMe.Agent`, receives
   them like any other member and decrypts them with its own MLS keys. Those keys are sealed with
   `RISI_MLS_KEK` in `risi_mls_kv`, through a Rust module loaded into the server (decision 067).
3. **Private chats and 1:1 DMs never reach Risi.** It isn't a member of them, so it has no keys for
   them. Four layers enforce this:
   - the server refuses (`403 private_tab`);
   - the encryption core refuses;
   - the Rust module refuses;
   - the Risi process checks `groups.tab = 'official'` before any work.

   The release gate's canary test proves it on every release.

### What is stored, where, and for how long

| Data | Where | Encrypted? | Retention |
|---|---|---|---|
| Decrypted Official message text (the "buffer") | Cassandra `risi_buffer` | **Yes**, AES-256-GCM with `RISI_DATA_KEY` | **24 h** TTL; deleted early on "delete for everyone", on Official off, or on removal |
| Risi's MLS keys and state | Postgres `risi_mls_kv` | Yes, `RISI_MLS_KEK` | While Risi is a member |
| Commitments (owner, due date, text) | Postgres `risi_commitments` | **Text is plain today, being fixed now** (see below) | Until done, declined or expired; deleted with Official off / forget |
| Facts ("What Risi knows about me") | Postgres `risi_facts` | **Text is plain today, being fixed now** | Until the user deletes it, or Official off |
| Learning log (one row per model call) | Cassandra `risi_llm_calls_by_*` | **Input: none stored.** Output: **plain today, being fixed now** | 90 days |
| Per-step tool log (§25) | Cassandra `risi_turn_steps` | Holds no text, only hashes | 90 days |

**What's deleted:**
- raw message text after 24 h at most;
- everything derived from a chat when Official is turned off in it, when Risi is removed, or when
  a message is deleted for everyone;
- a fact when the user deletes it.

**What the learning log holds:**
- **Never the input text.** Only the message ids and a SHA-256 of the prompt.
- Also: the task, model, provider, latency, tokens, confidence, status and feedback.
- **The model's output:** the validated JSON, i.e. summaries, answers and commitment texts derived
  from Official chats.

**Finding (2026-10-09), and the fix:** the learning-log `output`, `risi_commitments.text` /
`due_text` and `risi_facts.text` are stored **in plain text** on the pilot today. The pilot has
70 log rows, 5 commitments and 10 facts. This is derived content, not raw messages, but it is
still chat content. A server fix is in progress: seal all three with `RISI_DATA_KEY`, existing rows
included, and check every other `risi_*` column. It ships in the next release.

### The prompts
They are in `server/lib/risime/agent/prompts.ex`. Every prompt treats chat text as **untrusted
data**:
- each message goes in as one JSON line inside `<chat>…</chat>`, HTML-escaped, so it can't break
  out;
- the model sees short labels (`u1`, `m1`), never phone numbers or ids;
- the server maps them back and checks them;
- the output is forced into a JSON schema (vLLM guided decoding) and checked again on the server.

| Prompt | Purpose |
|---|---|
| `extract_system` | Find commitments: owner, counterparts, short text, due time in the owner's zone, source messages, confidence. Never invent; questions, wishes and vague plans don't count |
| `summary_system` | A short factual summary in the chat's language: decisions, action items, open questions |
| `answer_system` | Risi's identity, what it can do now and what's coming (built from what is actually enabled). Never "the transcript does not contain"; say what it can do and offer an alternative. Never "Message 1" in the text; the question is data too |
| `report_system` | A short factual report of a period from the transcript plus tracked commitments |

## 4. Calls: was the Harsha–Shenika call summarised from audio?

**No. It came from Official chat text.**
- **No speech-to-text anywhere until today:** until 2026-10-09 the code had none. The whisper
  service that started today isn't connected to anything yet.
- **Risi can't reach call audio:**
  - the contract (§20, §24) keeps agents out of calls ("never rung, never join, no room token");
  - Risi has no LiveKit participant;
  - the call media between the phones is end-to-end encrypted with keys Risi never receives.
- **The learning log** shows only `ask`, `commitment_extract`, `summarise` and `report` calls on
  text. There is no audio task.

So there was no contract breach. Call transcription is specified in §27, decision 070: Official calls
only, "Risi is listening" visible to everyone from the start, anyone can stop it, audio never
stored. It isn't built yet.

## 5. Hardware headroom and model recommendation

**Unified memory (GB10): 121 GB.**
- **In use at rest:** vLLM 47.4 GB, whisper 6.9 GB, and about 8–10 GB for Postgres, Cassandra,
  LiveKit, coturn and the server.
- **Free at rest:** about 50 GB. Release gates temporarily take 20–30 GB more (Redroid phones,
  Gradle).

**Options for a bigger or better local model:**
- **Bigger MoE models don't fit.** Qwen3-235B-A22B is about 235 GB even in FP8. Qwen3-Next-80B at
  FP8 is about 80 GB, which doesn't fit next to the current set.
- **gpt-oss-120b** (MXFP4, about 65 GB) would fit **instead of** risi-l1, not beside it. It's a
  candidate for tool use. Its Sinhala/Tamil quality is unproven.
- **Gemma 3 27B-it** (FP8, about 28 GB) would fit **beside** risi-l1. It's a candidate for
  Sinhala/Tamil, because Gemma 3 was trained for broad multilingual coverage.

**Recommendation:**
1. **Keep risi-l1 (Qwen3.6-35B-A3B)** as the main model. It's fast (about 3B active parameters,
   50 tok/s), does well on English commitments, summaries and the live smoke test, and leaves room
   for whisper.
2. **Measure before switching.** Run the §25 ten-question gate (tool use) and a Sinhala/Tamil
   summary set against two candidates: Gemma 3 27B-it (multilingual, alongside) and gpt-oss-120b
   (tool use, as a replacement). Route per task (`RISI_ROUTE_<TASK>`) only where a candidate wins
   on the gate.
3. **For Sinhala speech, whisper large-v3 is unusable.** A Sinhala-tuned speech model is needed
   before Sinhala calls can be summarised. Tamil works, at about 12% character error.
4. **A commercial fallback** stays off until a provider and a zero-retention agreement exist
   (Needs Harsha T).
