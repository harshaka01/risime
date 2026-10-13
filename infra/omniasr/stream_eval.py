"""omniASR eval with the wav2vec2 encoder streamed layer by layer (2026-10-14, eval only).

Why: the 7B checkpoints need ~15 GB (LLM-7B) / ~12 GB (CTC-7B) in bf16, and the GB10's unified
memory has only 6-13 GB available next to risi-l1, minus the 4 GB floor. So the model is built
with zero encoder layers (frontend, encoder layer norm, projection and the LLaMA decoder resident:
~3.5 GB for the LLM-7B), plus ONE encoder-layer module that receives layer i's weights from the
memory-mapped fp32 checkpoint in turn. Every chunk of a group goes through layer i before layer
i+1 is loaded, so the checkpoint is read once per group. The encoder output then goes through
Meta's normal path (ASRInferencePipeline._apply_model: stacking, projection, beam search or CTC,
tokenizer) via a stand-in encoder module. Same numerics as the resident model (bf16 per layer);
`--check` runs the resident model on the same chunks and reports exact-match texts.

Output rows match scripts/asr-eval.py `run` (scorable with `scripts/asr-eval score`); chunking is
that script's own (`chunks()`, 15 s, cut at the quietest 20 ms). processing_s is the GPU compute
of the chunk (encoder layers + decoding) WITHOUT the weight streaming: what a resident model would
take. The streaming wall time is logged separately and is not a deployable speed.

  python3 -I /app/stream_eval.py --manifest M --model omniasr-llm-7b --out R.jsonl \
      [--sets a,b] [--langs si,ta] [--limit N] [--group 96] [--check]
"""

import argparse
import collections
import copy
import importlib.util
import io
import json
import os
import sys
import time

sys.path.insert(0, "/app")

import numpy as np
import soundfile as sf
import torch

import server as S  # the service module: catalogue, cards, infer(), memory helpers


def load_harness(path):
    spec = importlib.util.spec_from_file_location("asr_eval", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def floor_guard():
    """Never hold host MemAvailable under the floor (risi-l1 and the release gates share it):
    stop; the results file is resumable."""
    avail = S.mem_available_mb()
    if avail < S.MIN_AVAILABLE_MB:
        sys.exit(f"MemAvailable {avail} MB < floor {S.MIN_AVAILABLE_MB} MB: stopping")


class Precomputed(torch.nn.Module):
    """Stands in for the encoder: returns the streamed encoder output of the current chunk."""

    def __init__(self):
        super().__init__()
        self.out = None

    def forward(self, seqs, seqs_layout):
        assert self.out is not None and self.out.shape[1] == seqs.shape[1], "frame mismatch"
        return self.out


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--manifest", required=True)
    p.add_argument("--model", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--harness", default="/scripts/asr-eval.py")
    p.add_argument("--sets")
    p.add_argument("--langs")
    p.add_argument("--limit", type=int)
    p.add_argument("--group", type=int, default=64)
    p.add_argument("--chunk-s", type=float, default=15.0)
    p.add_argument("--check", action="store_true",
                   help="also run the resident model (must fit) and compare texts")
    a = p.parse_args()

    H = load_harness(a.harness)
    sets = set(a.sets.split(",")) if a.sets else None
    langs = set(a.langs.split(",")) if a.langs else None
    clips = [c for c in H.load_manifest(a.manifest)
             if (not sets or c.get("set") in sets)
             and (not langs or c["lang"] in langs or H.base_lang(c["lang"]) in langs)]
    if a.limit:
        clips = clips[:a.limit]
    done = set()
    if os.path.exists(a.out):
        for line in open(a.out):
            r = json.loads(line)
            if "error" not in r:
                done.add((r["model"], r["id"]))
    clips = [c for c in clips if (a.model, c["id"]) not in done]
    print(f"{len(clips)} clips to run", file=sys.stderr, flush=True)
    if not clips:
        return

    # Chunks exactly like the harness.
    pieces = []  # (clip, index, audio)
    for c in clips:
        for i, (_name, data) in enumerate(H.chunks(c["audio"], a.chunk_s)):
            audio, sr = sf.read(io.BytesIO(data), dtype="float32")
            assert sr == 16000, c["id"]
            pieces.append((c, i, audio))

    spec = S.served()[a.model]
    S.cards()
    from fairseq2 import init_fairseq2
    from fairseq2.data.tokenizers.hub import load_tokenizer
    from fairseq2.models.transformer.attention_bias import AttentionBiasCache
    from fairseq2.nn.batch_layout import BatchLayout
    from omnilingual_asr.datasets.utils.audio import postprocess_waveform
    init_fairseq2()
    S._fs2 = True
    llm = spec["family"] == "wav2vec2_llama"
    if llm:
        from omnilingual_asr.models.wav2vec2_llama.hub import get_wav2vec2_llama_model_hub as G
    else:
        from fairseq2.models.wav2vec2.asr.hub import get_wav2vec2_asr_model_hub as G
    hub = G()
    cfg = hub.get_arch_config(spec["arch"])
    enc_cfg = (cfg.wav2vec2_asr_config if llm else cfg).encoder_config
    n_layers = enc_cfg.num_encoder_layers
    dev, dtype = torch.device("cuda"), S.DTYPE

    avail0 = S.mem_available_mb()
    t_load = time.monotonic()
    sd = torch.load(spec["path"], map_location="cpu", mmap=True, weights_only=True)["model"]
    cfg0 = copy.deepcopy(cfg)
    (cfg0.wav2vec2_asr_config if llm else cfg0).encoder_config.num_encoder_layers = 0
    model = hub.create_new_model(cfg0, device=dev, dtype=dtype)
    rest = {k: v for k, v in sd.items() if not k.startswith("encoder.layers.")}
    missing, unexpected = model.load_state_dict(rest, strict=False)
    assert not missing and not unexpected, (missing, unexpected)
    cfg1 = copy.deepcopy(cfg)
    (cfg1.wav2vec2_asr_config if llm else cfg1).encoder_config.num_encoder_layers = 1
    layer = hub.create_new_model(cfg1, device=dev, dtype=dtype, meta=True).encoder.layers[0]
    layer = layer.to_empty(device=dev).to(dtype).eval()
    enc_norm = model.encoder.layer_norm
    stand_in = Precomputed()
    model.encoder = stand_in
    model.eval()
    tok = load_tokenizer(a.model)
    pipe = S.make_pipeline(model, tok, spec["tokenizer"], dev)
    S.trim()
    floor_guard()
    load_s = time.monotonic() - t_load
    print(f"resident part loaded in {load_s:.1f}s: CUDA {torch.cuda.memory_allocated() >> 20} MB, "
          f"MemAvailable {avail0} -> {S.mem_available_mb()} MB, {n_layers} encoder layers",
          file=sys.stderr, flush=True)

    texts = collections.defaultdict(dict)
    compute = collections.Counter()
    n_pieces = collections.Counter(c["id"] for c, _i, _a in pieces)
    written = set()
    peak, min_avail, t_wall = 0, S.mem_available_mb(), time.monotonic()

    def flush_done():
        """Append every clip whose chunks are all transcribed (so a stop loses one group)."""
        runner = (f"stream_eval.py: encoder streamed layer by layer from the mmap'd checkpoint, "
                  f"{dtype}, GPU; peak CUDA {peak} MB; min MemAvailable {min_avail} MB")
        with open(a.out, "a") as out:
            for c in clips:
                parts = texts[c["id"]]
                if c["id"] in written or len(parts) < n_pieces[c["id"]]:
                    continue
                rec = {"model": a.model, "id": c["id"], "lang": c["lang"],
                       "forced_language": H.base_lang(c["lang"]),
                       "duration_s": c.get("duration_s"), "chunks": len(parts),
                       "text": " ".join(parts[k] for k in sorted(parts)).strip(),
                       "served_by": a.model, "language": H.base_lang(c["lang"]),
                       "processing_s": round(compute[c["id"]], 3), "wall_s": None,
                       "runner": runner}
                out.write(json.dumps(rec, ensure_ascii=False) + "\n")
                written.add(c["id"])
    torch.cuda.reset_peak_memory_stats()
    with torch.inference_mode():
        for g in range(0, len(pieces), a.group):
            group = pieces[g:g + a.group]
            hs, layouts, langs_ = [], [], []
            for c, _i, audio in group:
                floor_guard()
                torch.cuda.synchronize()
                t0 = time.monotonic()
                wav = postprocess_waveform(torch.from_numpy(np.ascontiguousarray(audio)),
                                           normalize_audio=True, dtype=dtype, spec_aug_p=None,
                                           spec_aug_freq_mask_param=0, spec_aug_time_mask_param=0)
                seqs = wav[None, :].to(dev, dtype)
                lay = BatchLayout.of(batch=seqs, seq_lens=[seqs.shape[1]])
                h, hl, _ = model.encoder_frontend.extract_features(seqs, lay)
                h, _ = model.encoder_frontend.process_features(h, hl, None)
                hs.append(h)
                layouts.append(hl)
                torch.cuda.synchronize()
                compute[c["id"]] += time.monotonic() - t0
            for li in range(n_layers):
                pre = f"encoder.layers.{li}."
                layer.load_state_dict({k[len(pre):]: v for k, v in sd.items() if k.startswith(pre)},
                                      strict=True)
                for j, (c, _i, _audio) in enumerate(group):
                    torch.cuda.synchronize()
                    t0 = time.monotonic()
                    hs[j] = layer(hs[j], layouts[j], AttentionBiasCache())
                    torch.cuda.synchronize()
                    compute[c["id"]] += time.monotonic() - t0
                min_avail = min(min_avail, S.mem_available_mb())
                floor_guard()
                if li % 8 == 0:
                    print(f"  group {g // a.group}: layer {li + 1}/{n_layers} "
                          f"(MemAvailable {S.mem_available_mb()} MB)", file=sys.stderr, flush=True)
            for j, (c, i, audio) in enumerate(group):
                floor_guard()
                torch.cuda.synchronize()
                t0 = time.monotonic()
                stand_in.out = enc_norm(hs[j]) if enc_norm is not None else hs[j]
                lang = S.LANG.get(H.base_lang(c["lang"]))
                texts[c["id"]][i] = S.infer(pipe, audio, lang).strip()
                torch.cuda.synchronize()
                compute[c["id"]] += time.monotonic() - t0
                hs[j] = None
            peak = max(peak, torch.cuda.max_memory_allocated() >> 20)
            flush_done()
            print(f"group {g // a.group} done, {time.monotonic() - t_wall:.0f}s wall",
                  file=sys.stderr, flush=True)

    wall = time.monotonic() - t_wall
    runner = f"peak CUDA {peak} MB, min MemAvailable {min_avail} MB, resident load {load_s:.0f}s"
    audio_s = sum(c.get("duration_s") or 0 for c in clips)
    print(f"done: {len(clips)} clips, {audio_s:.0f}s audio, compute {sum(compute.values()):.0f}s "
          f"(RTF {sum(compute.values()) / audio_s:.3f}), streaming wall {wall:.0f}s; {runner}",
          file=sys.stderr, flush=True)

    if a.check:
        del model, pipe, layer, sd
        S.trim()
        rpipe, _ = S.pool.get(a.model)
        same = n = 0
        for c, i, audio in pieces:
            t = S.infer(rpipe, audio, S.LANG.get(H.base_lang(c["lang"]))).strip()
            n += 1
            same += t == texts[c["id"]][i]
        print(f"check vs resident model: {same}/{n} chunks identical", file=sys.stderr, flush=True)


if __name__ == "__main__":
    main()
