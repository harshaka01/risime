# 061 — Self-hosted LLM for the Risi agent (vLLM + Qwen3.6-35B-A3B-FP8 as `risi-l1`)
**Status:** accepted 2026-10-08 (root). Stage 0.5 of the agent: summarise a chat, extract decisions
and action items with owners; later commitment detection (the Commitment Ledger).

## Context
The Risi agent needs an LLM, and no chat text may leave spark2. spark2 is a DGX Spark (GB10,
aarch64, sm_121, 121 GB unified CPU/GPU memory) that also runs the pilot (Phoenix, Postgres,
Cassandra, coturn, LiveKit) and dev builds/emulators, so the model must leave ≥ 24 GB RAM free.

## Decision
- **Serving:** vLLM, OpenAI-compatible API, in NVIDIA's container `nvcr.io/nvidia/vllm:26.09-py3`
  (vLLM 0.29, arm64, built for GB10; public on NGC, pulled anonymously). GPU via Docker `gpus: all`
  (nvidia-container-toolkit already on the host; no sudo). `infra/docker-compose.llm.yml`.
- **Model:** `Qwen/Qwen3.6-35B-A3B-FP8`, **Apache-2.0** (commercial use allowed), ungated. MoE,
  35 B total / 3 B active, hybrid Gated-DeltaNet + attention (tiny KV cache), 262k native context,
  201 languages incl. Sinhala and Tamil. Why: the newest official Qwen at a size that fits; the
  3 B-active MoE decodes ~50 tok/s on GB10 where a dense 27–32 B model would do ~10–15; FP8 keeps
  quality close to BF16 at half the memory. Rejected: gpt-oss-120b (~65 GB, no headroom),
  Llama-3.3-70B (dense, slow, Llama licence), Gemma 4 31B (dense, slower), the NVFP4 build (less
  memory but a third-party quant; revisit if memory gets tight). Served with
  `--language-model-only` (vision encoder not loaded) and thinking **off** by default
  (`--default-chat-template-kwargs {"enable_thinking": false}`); a request can turn it on per call
  with `chat_template_kwargs`. Recommended non-thinking sampling (pass per request): temperature
  0.7, top_p 0.8, top_k 20, presence_penalty 1.5; use `response_format: json_object` for extraction.
- **Weights** live in `~/risime-models/<dir>` (not the repo), mounted read-only.
- **Alias:** served as `risi-l1`. Clients never name the real model.
- **Network:** `127.0.0.1:8100` only (rule 6), no Caddy route; only the app server calls it, with
  `Authorization: Bearer $LLM_API_KEY` (`.env`, generated, never committed or printed).
- **Memory budget:** `--gpu-memory-utilization 0.34` (≈ 41 GB of 121.6 GB): weights 33.6 GB,
  activations ~1.4 GB, KV/state cache ~11.75 GB (≈ 500k tokens, ≥ 15 concurrent 32k requests;
  `--max-num-seqs 8`), max context 32k. Measured `free -g` available: 87 GB before, 52 GB after
  (an earlier try at 0.40 left 33 GB and pushed ~8 GB of idle Cassandra heap to swap). Override with
  `LLM_GPU_MEM_UTIL` in `.env`; re-measure before raising it.
- **No chat text leaves spark2:** local weights, `HF_HUB_OFFLINE=1`, `VLLM_NO_USAGE_STATS=1`,
  `DO_NOT_TRACK=1`; vLLM doesn't log prompts (request logging off, uvicorn access log off).
- **Learning log:** every model call will be recorded by the app server in the learning log
  (Cassandra): input, output, model alias and real model id, latency, tokens, cost (0 for local),
  user feedback. vLLM itself keeps nothing.

## Swapping models
Download the new weights into `~/risime-models/<newdir>`, set `LLM_MODEL_DIR=<newdir>` (and if the
family changes, the reasoning parser in the compose file) in `.env`, then
`docker compose --env-file .env -f infra/docker-compose.llm.yml up -d` and `scripts/llm-smoke`.
The alias stays `risi-l1` (a second tier would be `risi-l2`), so the app server doesn't change;
the learning log records which real model answered.

## Measured (2026-10-08, `scripts/llm-smoke`)
- JSON extraction from a 9-line mixed English/Sinhala/Tamil chat: valid JSON, 2 decisions, 3 action
  items with the right owners, 5 s.
- ~4.9k-token chat summary: TTFT 1.3 s (prefill ~3.7k tok/s), decode ~50 tok/s, 8.6 s total.
- Cold start ~6 min (weights 3.5 min from disk + compile + CUDA graphs); `restart: unless-stopped`.

## Consequences
- The pilot shares the box: if memory gets tight, stop the LLM first
  (`docker compose -f infra/docker-compose.llm.yml stop`); the app must degrade gracefully when
  `risi-l1` is down.
- The image is ~20 GB, the weights ~35 GB on disk.
