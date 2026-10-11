# 078: Risi memory embeddings: a local feature-hashing model first (risi-hash-384-v1)

Date: 2026-10-11. Context: decision 077 (long-term memory), contract §35.1 (embeddings computed on spark2 only).

## Decision
- Memory messages and items are embedded by `risi-hash-384-v1`: multilingual feature hashing of words and character
  trigrams into 384 dimensions, computed inside the Phoenix server (no model server, no download, no third party).
- It sits behind `RisiMe.Agent.Embeddings.embed/1`; a neural multilingual embedding model can replace it later
  (needs a model download, so Harsha's OK under rule 10, and a re-embed job). The vector index holds no text.

## Why
spark2 had no embedding model; vLLM's GPU memory is taken by risi-l1 and the eval showed little headroom; Harsha
wanted step 2 live today. Hashing gives usable lexical/sub-word recall in Sinhala, Tamil and English, and retrieval
also uses the structured items, dates and people filters, so ranking quality is not the only signal.
