"""Meta Omnilingual ASR CTC (omniASR-CTC-1B, OpenVoiceOS ONNX int8 export) for the whisper service.

Raw 16 kHz waveform in ([batch, samples]), logits out ([batch, frames, 10288], 20 ms per frame);
greedy CTC: argmax, collapse repeats, drop blank (index 0). The vocabulary holds real spaces.
The CTC family is not language-conditioned (a short clip can land in another script), so callers
check the script. Runs on the CPU (onnxruntime in the image has no CUDA provider on aarch64).
"""

import os

import numpy as np
import onnxruntime as ort


class OmniCTC:
    def __init__(self, model_dir, threads=None):
        model = os.path.join(model_dir, "model.int8.onnx")
        if not os.path.exists(model):
            model = os.path.join(model_dir, "model.onnx")
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads or int(os.environ.get("OMNI_CTC_THREADS", "8"))
        so.inter_op_num_threads = 1
        self.sess = ort.InferenceSession(model, so, providers=["CPUExecutionProvider"])
        self.input = self.sess.get_inputs()[0].name
        self.tokens = {}
        with open(os.path.join(model_dir, "tokens.txt"), encoding="utf-8") as f:
            for line in f:
                line = line.rstrip("\n")
                if not line:
                    continue
                tok, _, idx = line.rpartition(" ")
                if idx.isdigit():
                    self.tokens[int(idx)] = tok
        self.normalise = os.environ.get("OMNI_CTC_NORMALISE", "1") == "1"

    def transcribe(self, audio: np.ndarray, prompt=None):
        """audio: float32 mono 16 kHz. Returns (text, None)."""
        x = audio.astype(np.float32)
        if self.normalise:  # wav2vec2-style zero mean / unit variance per utterance
            x = (x - x.mean()) / (x.std() + 1e-7)
        logits = self.sess.run(None, {self.input: x[None, :]})[0][0]
        ids = logits.argmax(-1)
        out, prev = [], -1
        for i in ids:
            i = int(i)
            if i != prev and i != 0:
                out.append(self.tokens.get(i, ""))
            prev = i
        text = "".join(out).replace("|", " ")
        return " ".join(text.split()), None

    def close(self):
        del self.sess
