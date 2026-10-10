| model | peak memory GB | tok/s | TTFT s | JSON valid | tool calls ok (as served / tool first) | false action claims (as served / tool first) | summary+extract+ask ok | other hallucinations | si / ta script ok | blind A/B vs risi-l1, all 140 (W-T-L, judge 1-5) | blind A/B tool-first turns (W-T-L) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| risi-l1 (Qwen3.6-35B-A3B-FP8, live) | 47.4 (resident, nvidia-smi per RISI-TECH.md) | 50.9 | 0.229 | 100% | 8% / 68% | 19 / 0 | 93% | 3 | 78% / 67% | baseline | baseline |
| gemma4-12b-fp8 (62 errors) | 27.3 | 16.1 | 0.104 | 54% | 35% / 0% | 4 / 0 | 97% | 2 | 86% / 86% | not judged | not judged |
| gemma4-12b-qat | 20.2 | 24.9 | 0.122 | 99% | 67% / 68% | 0 / 0 | 95% | 3 | 83% / 90% | 80-32-28 (4.06 vs 2.88) | 18-35-27 (3.58 vs 3.77) |

| ASR model | Sinhala WER / CER | Tamil WER / CER | real-time factor (si / ta) | peak memory GB |
|---|---|---|---|---|
| whisper-large-v3-live | 107.9% / 92.1% | 57.9% / 23.4% | 3.237 / 0.826 | resident 6.9 (nvidia-smi) |
| whisper-large-v3-sinhala | 62.3% / 37.6% | - | 0.232 / - | 11.3 |
| whisper-large-v3-turbo | 124.5% / 97.2% | 81.1% / 37.8% | 0.49 / 0.116 | 3.1 |
| whisper-small-sinhala | 14.5% / 5.7% | - | 0.046 / - | 9.4 |
