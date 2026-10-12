"""Prepare the extra speech models for the whisper service (decision 071 amendment, 2026-10-12).

Local only: reads the verified HF checkpoints in ~/risime-eval-models (downloaded 2026-10-09/10,
see docs/status/downloads.md) and writes serving copies into ~/risime-models. Nothing is fetched.

  whisper-small-sinhala     HF Whisper -> CTranslate2 float16     (faster-whisper)
  whisper-large-v3-sinhala  HF Whisper -> CTranslate2 int8_float16 (faster-whisper, compare only)
  tamil-asr-qwen3           copied as is (Qwen3-ASR original layout; server.py loads it into
                            transformers' native Qwen3ASRForConditionalGeneration with a key map)
Added 2026-10-12 (amendment c, ASR eval):
  whisper-small-sinhala-185k  HF Whisper -> CTranslate2 float16 (janiduchamika, eval candidate)
  whisper-tamil-small         HF Whisper -> CTranslate2 float16 (vasista22, eval candidate)
  whisper-medium-si-exp10     CTranslate2 fp16 as published (SPEAK-ASR), copied
  whisper-tamil-medium        CTranslate2 int8 as published (mohankumars26 of vasista22), copied
  qwen3-asr-0.6b-sinhala      MLX 8-bit affine (Nerdstorm) -> dequantized bf16 safetensors in
                              Qwen's original layout (thinker.*), conv weights to torch layout
  omnilingual-ctc-1b          ONNX int8 (OpenVoiceOS export of Meta omniASR-CTC-1B), copied

Run inside the whisper image (it has ctranslate2, transformers, torch), CPU only, e.g.:
  docker run --rm --memory 7g --user 1000:1000 --entrypoint python3 \
    -e HF_HUB_OFFLINE=1 -e TRANSFORMERS_OFFLINE=1 -e HOME=/tmp \
    -v ~/risime-eval-models:/src:ro -v ~/risime-models:/out \
    -v $PWD/infra/whisper/convert_models.py:/convert_models.py:ro \
    risime/whisper:<tag> -I /convert_models.py whisper-small-sinhala
"""

import json
import os
import shutil
import sys
import tempfile

SRC = os.environ.get("SRC", "/src")
OUT = os.environ.get("OUT", "/out")

WHISPER = {
    # name: (source dir, output dir, ct2 quantization)
    "whisper-small-sinhala": ("whisper-small-sinhala", "faster-whisper-small-sinhala", "float16"),
    "whisper-large-v3-sinhala": ("whisper-large-v3-sinhala", "faster-whisper-large-v3-sinhala",
                                 "int8_float16"),
    "whisper-small-sinhala-185k": ("whisper-small-sinhala-185k",
                                   "faster-whisper-small-sinhala-185k", "float16"),
    "whisper-tamil-small": ("whisper-tamil-small", "faster-whisper-tamil-small", "float16"),
}
# Published CTranslate2 / ONNX models and tamil-asr-qwen3: copied as they are.
COPY = {
    "whisper-medium-si-exp10": ("faster-whisper-medium-si-exp10", "faster-whisper-medium-si-exp10"),
    "whisper-tamil-medium": ("faster-whisper-tamil-medium", "faster-whisper-tamil-medium"),
    "omnilingual-ctc-1b": ("omnilingual-ctc-1b-onnx", "omnilingual-ctc-1b"),
    "tamil-asr-qwen3": ("tamil-asr-qwen3", "tamil-asr-qwen3"),
}


def convert_whisper(name):
    import ctranslate2.converters as conv
    from transformers import WhisperTokenizerFast

    src_dir, out_dir, quant = WHISPER[name]
    src = os.path.join(SRC, src_dir)
    out = os.path.join(OUT, out_dir)
    if os.path.exists(os.path.join(out, "model.bin")):
        print(f"{name}: {out} exists, skipped")
        return
    with tempfile.TemporaryDirectory() as tmp:
        # faster-whisper needs tokenizer.json (else it would fetch one from the hub). The
        # checkpoints ship vocab.json/merges.txt only, so build the fast tokenizer locally.
        tok = WhisperTokenizerFast.from_pretrained(src, local_files_only=True)
        tok.save_pretrained(tmp)
        conv.TransformersConverter(src, load_as_float16=True, low_cpu_mem_usage=True).convert(
            out, quantization=quant, force=False)
        shutil.copy(os.path.join(tmp, "tokenizer.json"), out)
    shutil.copy(os.path.join(src, "preprocessor_config.json"), out)
    with open(os.path.join(out, "RISIME-SOURCE.json"), "w") as f:
        json.dump({"source": f"~/risime-eval-models/{src_dir}", "converter": "ctranslate2 "
                   "TransformersConverter", "quantization": quant}, f, indent=1)
    print(f"{name}: wrote {out}")


def copy_model(name):
    src_dir, out_dir = COPY[name]
    src, out = os.path.join(SRC, src_dir), os.path.join(OUT, out_dir)
    if os.path.exists(out):
        print(f"{name}: {out} exists, skipped")
        return
    os.makedirs(out)
    for f in sorted(os.listdir(src)):
        p = os.path.join(src, f)
        if os.path.isfile(p) and not f.startswith(".") and f not in ("logo.png", "args.json"):
            shutil.copy(p, out)
    print(f"{name}: copied to {out}")


def dequant_mlx(w, scales, biases, bits, group):
    """MLX affine quantization -> float: packed uint32 words hold 32/bits values, low bits first;
    each group of `group` values along the last axis has its own scale and bias."""
    import torch

    per = 32 // bits
    words = w.view(torch.int32)
    q = torch.stack([(words >> (bits * i)) & ((1 << bits) - 1) for i in range(per)], dim=-1)
    q = q.reshape(*w.shape[:-1], w.shape[-1] * per).float()
    q = q.reshape(*q.shape[:-1], -1, group)
    out = q * scales.float().unsqueeze(-1) + biases.float().unsqueeze(-1)
    return out.reshape(*w.shape[:-1], -1)


def convert_qwen3_mlx(name="qwen3-asr-0.6b-sinhala", src_dir="qwen3-asr-0.6b-sinhala-8bit"):
    """Nerdstorm/Qwen3-ASR-0.6B-Sinhala-8bit (mlx-audio) -> bf16 safetensors in Qwen's original
    layout (thinker.audio_tower.*, thinker.model.*), which qwen3_asr.py already loads."""
    import torch
    from safetensors import safe_open
    from safetensors.torch import save_file

    src, out = os.path.join(SRC, src_dir), os.path.join(OUT, name)
    if os.path.exists(os.path.join(out, "model.safetensors")):
        print(f"{name}: {out} exists, skipped")
        return
    with open(os.path.join(src, "config.json")) as f:
        cfg = json.load(f)
    qc = cfg.get("quantization") or cfg["quantization_config"]
    bits, group = qc["bits"], qc["group_size"]
    raw = {}
    with safe_open(os.path.join(src, "model.safetensors"), framework="pt") as f:
        for k in f.keys():
            raw[k] = f.get_tensor(k)
    tensors = {}
    for k, t in raw.items():
        if k.endswith(".scales") or k.endswith(".biases"):
            continue
        base = k[:-len(".weight")] if k.endswith(".weight") else None
        if base and base + ".scales" in raw:
            t = dequant_mlx(t, raw[base + ".scales"], raw[base + ".biases"], bits, group)
        elif t.dim() == 4:  # MLX conv2d (out, kh, kw, in) -> torch (out, in, kh, kw)
            t = t.permute(0, 3, 1, 2)
        tensors["thinker." + k] = t.to(torch.bfloat16).contiguous()
    os.makedirs(out, exist_ok=True)
    save_file(tensors, os.path.join(out, "model.safetensors"), metadata={"format": "pt"})
    cfg.pop("quantization", None)
    cfg.pop("quantization_config", None)
    with open(os.path.join(out, "config.json"), "w") as f:
        json.dump(cfg, f, indent=1)
    for fn in sorted(os.listdir(src)):
        if fn not in ("config.json", "model.safetensors", "model.safetensors.index.json") \
                and os.path.isfile(os.path.join(src, fn)) and not fn.startswith("."):
            shutil.copy(os.path.join(src, fn), out)
    with open(os.path.join(out, "RISIME-SOURCE.json"), "w") as f:
        json.dump({"source": f"~/risime-eval-models/{src_dir}", "converter": "convert_models.py "
                   f"dequant MLX affine {bits}-bit g{group} -> bf16, conv OHWI -> OIHW"}, f,
                  indent=1)
    print(f"{name}: wrote {out} ({len(tensors)} tensors)")


if __name__ == "__main__":
    for n in sys.argv[1:] or ["whisper-small-sinhala", "tamil-asr-qwen3"]:
        if n in COPY:
            copy_model(n)
        elif n == "qwen3-asr-0.6b-sinhala":
            convert_qwen3_mlx(n)
        else:
            convert_whisper(n)
