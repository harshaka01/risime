"""One TTS candidate engine in its own process, for risime-tts-lab (tts_lab_server.py).

Started by the lab server as `python3 -I tts_lab_worker.py <engine>`; it exits (freeing all of its
unified memory) when the server unloads the engine. Each engine has its own package directory
(/opt/eng/<engine>, pinned in Dockerfile.tts-lab) put first on sys.path, so engines that pin
different transformers versions never share a process.

Protocol (stdin/stdout, one JSON object per line):
  -> {"ready": true, "load_ms": n, "sr": hz}           after the model is loaded (or {"error": ...})
  <- {"text": "...", "voice": "<engine>:<voice>"}
  -> {"ok": true, "sr": hz, "first_ms": n, "total_ms": n, "pcm": "<base64 s16le mono>"[, "stream_first_ms": n]}
     or {"ok": false, "error": "bad_voice" | "failed"}

Offline only (HF_HUB_OFFLINE=1, local files under /models). Every model file is read as
safetensors or with torch.load(weights_only=True): no pickle of downloaded data. The text is never
logged or written; the audio exists only in memory. Built-in or stock voices only: no voice cloning
from an uploaded recording.
"""

import base64
import json
import os
import re
import sys
import time

ENGINE = sys.argv[1] if len(sys.argv) > 1 else ""
MODELS = os.environ.get("TTS_LAB_MODELS", "/models")
ENG_DIR = os.environ.get("TTS_LAB_ENG_DIR", "/opt/eng")

for k, v in {"HF_HUB_OFFLINE": "1", "TRANSFORMERS_OFFLINE": "1", "DO_NOT_TRACK": "1",
             "HF_HUB_DISABLE_TELEMETRY": "1", "TOKENIZERS_PARALLELISM": "false"}.items():
    os.environ[k] = v
# The engine's own pinned packages first (python3 -I ignores PYTHONPATH).
if os.path.isdir(os.path.join(ENG_DIR, ENGINE)):
    sys.path.insert(0, os.path.join(ENG_DIR, ENGINE))
# The base image's xformers does not import against its own flash-attn (version check), and
# diffusers (Chatterbox's flow decoder) imports it when present: hidden, so diffusers uses torch's
# own attention.
sys.modules["xformers"] = None

import numpy as np  # noqa: E402
import torch  # noqa: E402

if ENGINE == "indic-parler":
    # descript-audiotools (DAC's helper library) imports torch.utils.tensorboard for training-time
    # logging only; an inert stand-in avoids installing tensorboard (and its protobuf/grpc chain).
    import types

    _tb = types.ModuleType("torch.utils.tensorboard")

    class SummaryWriter:  # noqa: D101 - never used for inference
        def __init__(self, *a, **k):
            raise RuntimeError("tensorboard is not available in risime-tts-lab")

    _tb.SummaryWriter = SummaryWriter
    sys.modules["torch.utils.tensorboard"] = _tb

DEVICE = os.environ.get("TTS_DEVICE") or ("cuda" if torch.cuda.is_available() else "cpu")
# GB10 (sm_121) in the NGC image: cuDNN has no engine for some 1-D convolutions and cuFFT is a stub
# ("unknown error 50"), the same gaps as risime-tts (Kokoro). cuDNN off; FFT/STFT run on the CPU.
torch.backends.cudnn.enabled = os.environ.get("TTS_CUDNN", "0") == "1"


def _cpu_fft(fn):
    def wrapped(x, *a, **k):
        if isinstance(x, torch.Tensor) and x.is_cuda:
            dev = x.device
            k = {n: (v.cpu() if isinstance(v, torch.Tensor) else v) for n, v in k.items()}
            a = tuple(v.cpu() if isinstance(v, torch.Tensor) else v for v in a)
            return fn(x.cpu(), *a, **k).to(dev)
        return fn(x, *a, **k)
    return wrapped


if DEVICE == "cuda" and os.environ.get("TTS_LAB_FFT_CPU", "1") == "1":
    torch.stft = _cpu_fft(torch.stft)
    torch.istft = _cpu_fft(torch.istft)
    torch.Tensor.stft = lambda self, *a, **k: torch.stft(self, *a, **k)
    torch.Tensor.istft = lambda self, *a, **k: torch.istft(self, *a, **k)
    for name in ("fft", "ifft", "rfft", "irfft"):
        setattr(torch.fft, name, _cpu_fft(getattr(torch.fft, name)))

SENTENCE = re.compile(r"(?<=[.!?…।])\s+")


def sentences(text):
    return [p.strip() for p in SENTENCE.split(text) if p.strip()] or [text.strip()]


def to_i16(a):
    if isinstance(a, torch.Tensor):
        a = a.detach().float().cpu().numpy()
    a = np.asarray(a, dtype=np.float32).reshape(-1)
    return (np.clip(a, -1.0, 1.0) * 32767.0).astype(np.int16)


def safe_torch_load(path):
    return torch.load(path, map_location="cpu", weights_only=True)


# ---------------------------------------------------------------- Chatterbox (MIT, Resemble AI)
class Chatterbox:
    """English, the built-in default voice (conds.pt) only; PerTh watermark on every output."""
    dir = os.path.join(MODELS, "chatterbox")

    def __init__(self):
        from chatterbox.tts import ChatterboxTTS
        # from_local reads ve/t3_cfg/s3gen as safetensors and the built-in voice conds.pt with
        # torch.load(weights_only=True) (chatterbox-tts 0.1.4, checked): no pickle of model data.
        self.m = ChatterboxTTS.from_local(self.dir, DEVICE)
        self.sr = self.m.sr

    def voices(self):
        return {"chatterbox:default": None}

    def say(self, text, voice):
        # Default conditionals only (no audio_prompt_path: no cloning). Watermarked by the library.
        return self.m.generate(text)


# ---------------------------------------------------------- Indic Parler-TTS (Apache-2.0, AI4Bharat)
PARLER_SPEAKERS = {"ta": ["Jaya", "Kavitha"], "en": ["Mary", "Thoma"]}
PARLER_DESC = ("{name} speaks at a moderate pace with a clear, expressive voice. The recording is of "
               "very high quality, with the speaker's voice sounding clear and very close up.")


class IndicParler:
    dir = os.path.join(MODELS, "indic-parler-tts")
    desc_tok_dir = os.path.join(MODELS, "flan-t5-large-tokenizer")

    def __init__(self):
        from parler_tts import ParlerTTSForConditionalGeneration
        from transformers import AutoTokenizer
        dtype = torch.bfloat16 if DEVICE == "cuda" else torch.float32
        self.m = ParlerTTSForConditionalGeneration.from_pretrained(
            self.dir, torch_dtype=dtype, use_safetensors=True).to(DEVICE).eval()
        self.tok = AutoTokenizer.from_pretrained(self.dir)
        self.dtok = AutoTokenizer.from_pretrained(self.desc_tok_dir)
        self.sr = self.m.config.sampling_rate

    def voices(self):
        return {f"indic-parler:{lang}:{n.lower()}": n for lang, ns in PARLER_SPEAKERS.items() for n in ns}

    def say(self, text, voice):
        name = self.voices()[voice]
        d = self.dtok(PARLER_DESC.format(name=name), return_tensors="pt").to(DEVICE)
        p = self.tok(text, return_tensors="pt").to(DEVICE)
        with torch.inference_mode():
            out = self.m.generate(input_ids=d.input_ids, attention_mask=d.attention_mask,
                                  prompt_input_ids=p.input_ids, prompt_attention_mask=p.attention_mask)
        return out[0]


# ------------------------------------- Orpheus 3B 0.1 ft (Apache-2.0 + Llama 3.2 Community Licence)
ORPHEUS_VOICES = ["tara", "leah", "jess", "leo", "dan", "mia", "zac", "zoe"]


class Orpheus:
    dir = os.path.join(MODELS, "orpheus-3b-0.1-ft")
    snac_dir = os.path.join(MODELS, "snac_24khz")
    sr = 24000

    def __init__(self):
        from snac import SNAC
        from transformers import AutoModelForCausalLM, AutoTokenizer
        self.tok = AutoTokenizer.from_pretrained(self.dir)
        self.m = AutoModelForCausalLM.from_pretrained(
            self.dir, torch_dtype=torch.bfloat16, use_safetensors=True).to(DEVICE).eval()
        with open(os.path.join(self.snac_dir, "config.json")) as f:
            cfg = json.load(f)
        self.snac = SNAC(**cfg)
        # SNAC ships pytorch_model.bin: loaded as tensors only, never unpickled as objects.
        self.snac.load_state_dict(safe_torch_load(os.path.join(self.snac_dir, "pytorch_model.bin")))
        self.snac = self.snac.to(DEVICE).eval()
        self.stream_first_ms = None

    def voices(self):
        return {f"orpheus:{v}": v for v in ORPHEUS_VOICES}

    def say(self, text, voice):
        from transformers import StoppingCriteria, StoppingCriteriaList
        v = self.voices()[voice]
        ids = self.tok(f"{v}: {text}", return_tensors="pt").input_ids
        ids = torch.cat([torch.tensor([[128259]]), ids, torch.tensor([[128009, 128260]])], dim=1).to(DEVICE)
        t0, n0, outer = time.monotonic(), ids.shape[1], self

        class FirstFrames(StoppingCriteria):
            """Never stops; notes when 4 SNAC frames (28 audio tokens) exist: a streaming server's
            first audio (the decode of 4 frames adds a few ms)."""
            def __call__(self, input_ids, scores, **kw):
                if outer.stream_first_ms is None:
                    new = input_ids[0, n0:]
                    starts = (new == 128257).nonzero()
                    if len(starts) and new.shape[0] - int(starts[-1]) - 1 >= 28:
                        outer.stream_first_ms = int((time.monotonic() - t0) * 1000)
                return torch.zeros(input_ids.shape[0], dtype=torch.bool, device=input_ids.device)

        with torch.inference_mode():
            out = self.m.generate(ids, max_new_tokens=int(os.environ.get("ORPHEUS_MAX_TOKENS", "1600")),
                                  do_sample=True, temperature=0.6, top_p=0.8, repetition_penalty=1.1,
                                  eos_token_id=128258, pad_token_id=128263,
                                  stopping_criteria=StoppingCriteriaList([FirstFrames()]))
        gen = out[0, n0:]
        starts = (gen == 128257).nonzero()
        if len(starts):
            gen = gen[int(starts[-1]) + 1:]
        codes = [int(t) - 128266 for t in gen if int(t) != 128258]
        codes = codes[: len(codes) // 7 * 7]
        if not codes:
            return np.zeros(0, dtype=np.float32)
        l1, l2, l3 = [], [], []
        for i in range(len(codes) // 7):
            c = codes[7 * i: 7 * i + 7]
            l1.append(c[0])
            l2.append(c[1] - 4096)
            l3 += [c[2] - 2 * 4096, c[3] - 3 * 4096]
            l2.append(c[4] - 4 * 4096)
            l3 += [c[5] - 5 * 4096, c[6] - 6 * 4096]
        layers = [torch.tensor(x, device=DEVICE).clamp(0, 4095).unsqueeze(0) for x in (l1, l2, l3)]
        with torch.inference_mode():
            return self.snac.decode(layers)[0, 0]


# ------------------------------------------------------------- IndicF5 (MIT, AI4Bharat; custom code)
class IndicF5:
    """Runs only after docs/status/tts-indicf5-code-review.md cleared the pinned revision; the lab
    server never starts this engine otherwise (status review_pending). The adapter is written in that
    review session, against the reviewed files."""

    def __init__(self):
        raise RuntimeError("indicf5: review pending")


class SelfTest:
    """tts_lab_selftest.py only: 0.4 s of tone per sentence, after a short 'synthesis' delay."""
    sr = 24000

    def __init__(self):
        time.sleep(0.3)

    def voices(self):
        return {"selftest:tone": None}

    def say(self, text, voice):
        time.sleep(0.05)
        t = np.arange(int(self.sr * 0.4)) / self.sr
        return 0.2 * np.sin(2 * np.pi * 440 * t)


ENGINES = {"chatterbox": Chatterbox, "indic-parler": IndicParler, "orpheus": Orpheus, "indicf5": IndicF5}
if os.environ.get("TTS_LAB_SELFTEST") == "1":
    ENGINES["selftest"] = SelfTest


def send(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


WARMUP = {"ta": "வணக்கம்.", "en": "Hello."}

# `--check`: import an engine's packages only (no model), for the image build.
CHECK_IMPORTS = {
    "chatterbox": ["chatterbox.tts", "perth"],
    "indic-parler": ["parler_tts", "dac.model"],
    "orpheus": ["snac", "transformers"],
    "indicf5": [],
}


def check():
    import importlib
    for mod in CHECK_IMPORTS[ENGINE]:
        importlib.import_module(mod)
    import transformers
    print(f"{ENGINE}: imports ok (transformers {transformers.__version__})")
    return 0


def main():
    cls = ENGINES.get(ENGINE)
    if cls is None:
        send({"error": "unknown_engine"})
        return 2
    if sys.argv[2:] == ["--check"]:
        return check()
    t0 = time.monotonic()
    try:
        eng = cls()
        voices = eng.voices()
        # Warm-up on a fixed sentence (kernels, caches), never on a user's text.
        first = next(iter(voices))
        lang = first.split(":")[1] if first.count(":") == 2 else "en"
        eng.say(WARMUP.get(lang, "Hello."), first)
    except Exception as e:  # noqa: BLE001 - reported, no text involved
        print(f"tts-lab-worker {ENGINE}: load failed: {type(e).__name__}: {e}", file=sys.stderr)
        send({"error": "load_failed"})
        return 1
    send({"ready": True, "load_ms": int((time.monotonic() - t0) * 1000), "sr": eng.sr,
          "device": DEVICE})
    for line in sys.stdin:
        try:
            req = json.loads(line)
        except json.JSONDecodeError:
            send({"ok": False, "error": "bad_request"})
            continue
        text, voice = (req.get("text") or "").strip(), req.get("voice") or ""
        if voice not in voices or not text:
            send({"ok": False, "error": "bad_voice"})
            continue
        try:
            t0 = time.monotonic()
            first, parts = None, []
            if hasattr(eng, "stream_first_ms"):
                eng.stream_first_ms = None
            for s in sentences(text):
                parts.append(to_i16(eng.say(s, voice)))
                if first is None:
                    first = int((time.monotonic() - t0) * 1000)
            pcm = np.concatenate(parts) if parts else np.zeros(0, dtype=np.int16)
            out = {"ok": True, "sr": eng.sr, "first_ms": first or 0,
                   "total_ms": int((time.monotonic() - t0) * 1000),
                   "pcm": base64.b64encode(pcm.tobytes()).decode()}
            if getattr(eng, "stream_first_ms", None) is not None:
                out["stream_first_ms"] = eng.stream_first_ms
            send(out)
        except Exception as e:  # noqa: BLE001 - only the type is logged, never the text
            print(f"tts-lab-worker {ENGINE}: synthesis failed: {type(e).__name__}", file=sys.stderr)
            send({"ok": False, "error": "failed"})
    return 0


if __name__ == "__main__":
    sys.exit(main())
