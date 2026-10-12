"""Prepare the extra speech models for the whisper service (decision 071 amendment, 2026-10-12).

Local only: reads the verified HF checkpoints in ~/risime-eval-models (downloaded 2026-10-09/10,
see docs/status/downloads.md) and writes serving copies into ~/risime-models. Nothing is fetched.

  whisper-small-sinhala     HF Whisper -> CTranslate2 float16     (faster-whisper)
  whisper-large-v3-sinhala  HF Whisper -> CTranslate2 int8_float16 (faster-whisper, compare only)
  tamil-asr-qwen3           copied as is (Qwen3-ASR original layout; server.py loads it into
                            transformers' native Qwen3ASRForConditionalGeneration with a key map)

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


def copy_tamil():
    src = os.path.join(SRC, "tamil-asr-qwen3")
    out = os.path.join(OUT, "tamil-asr-qwen3")
    if os.path.exists(os.path.join(out, "model.safetensors")):
        print(f"tamil-asr-qwen3: {out} exists, skipped")
        return
    os.makedirs(out, exist_ok=True)
    for f in sorted(os.listdir(src)):
        p = os.path.join(src, f)
        if os.path.isfile(p) and not f.startswith(".") and f not in ("logo.png", "args.json"):
            shutil.copy(p, out)
    print(f"tamil-asr-qwen3: copied to {out}")


if __name__ == "__main__":
    for n in sys.argv[1:] or ["whisper-small-sinhala", "tamil-asr-qwen3"]:
        copy_tamil() if n == "tamil-asr-qwen3" else convert_whisper(n)
