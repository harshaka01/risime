"""Qwen3-ASR (e.g. lemuralabs/tamil-asr-qwen3) on transformers, for the whisper service.

The checkpoint is in Qwen's original layout (config `thinker_config`, weights `thinker.*`, the
audio projector inside the audio tower). transformers 5.x ships a native
Qwen3ASRForConditionalGeneration with a different layout (`Qwen/Qwen3-ASR-1.7B-hf`), so we build
the native config from `thinker_config` and remap the weight names at load time. Text positions
are 1-D for ASR prompts (audio + text only), so the original interleaved M-RoPE equals plain RoPE.
"""

import json
import os
import re

import numpy as np
import torch
from transformers import AutoTokenizer, Qwen3ASRConfig, Qwen3ASRForConditionalGeneration
from transformers.models.qwen3_asr import Qwen3ASRFeatureExtractor, Qwen3ASRProcessor

KEY_MAP = {
    r"^thinker\.audio_tower\.proj1\.": "model.multi_modal_projector.linear_1.",
    r"^thinker\.audio_tower\.proj2\.": "model.multi_modal_projector.linear_2.",
    r"^thinker\.audio_tower\.": "model.audio_tower.",
    r"^thinker\.model\.": "model.language_model.",
    r"^thinker\.lm_head\.": "lm_head.",
}

AUDIO_KEYS = ("num_mel_bins", "encoder_layers", "encoder_attention_heads", "encoder_ffn_dim",
              "d_model", "activation_function", "scale_embedding", "n_window", "output_dim",
              "n_window_infer", "downsample_hidden_size")
TEXT_KEYS = ("vocab_size", "hidden_size", "intermediate_size", "num_hidden_layers",
             "num_attention_heads", "num_key_value_heads", "head_dim", "hidden_act",
             "max_position_embeddings", "rms_norm_eps", "attention_bias", "tie_word_embeddings")


def native_config(model_dir):
    with open(os.path.join(model_dir, "config.json")) as f:
        orig = json.load(f)
    if orig.get("model_type") == "qwen3_asr" and "audio_config" in orig:
        return Qwen3ASRConfig.from_pretrained(model_dir)  # already native
    th = orig["thinker_config"]
    a, t = th["audio_config"], th["text_config"]
    text = {k: t[k] for k in TEXT_KEYS if k in t}
    text.update(model_type="qwen3", rope_parameters={"rope_type": "default",
                                                     "rope_theta": t.get("rope_theta", 1e6)})
    return Qwen3ASRConfig(
        audio_config={"model_type": "qwen3_asr_encoder", **{k: a[k] for k in AUDIO_KEYS if k in a}},
        text_config=text,
        audio_token_id=th["audio_token_id"],
        pad_token_id=orig.get("pad_token_id", 151643),
        tie_word_embeddings=t.get("tie_word_embeddings", True),
    )


def load_weights(model_dir, cfg, device, dtype):
    """Build the model on the GPU and stream the safetensors straight into it (no CPU copy;
    from_pretrained would need accelerate for device_map and stage 4 GB in host RAM)."""
    from safetensors import safe_open

    prev = torch.get_default_dtype()
    torch.set_default_dtype(dtype)
    try:
        with torch.device(device):
            model = Qwen3ASRForConditionalGeneration(cfg)
    finally:
        torch.set_default_dtype(prev)
    params = model.state_dict()
    seen = set()
    with torch.no_grad(), safe_open(os.path.join(model_dir, "model.safetensors"), framework="pt",
                                    device=device) as f:
        for key in f.keys():
            name = key
            for pat, rep in KEY_MAP.items():
                name, n = re.subn(pat, rep, name)
                if n:
                    break
            if name not in params:
                raise ValueError(f"unexpected weight {key} -> {name}")
            params[name].copy_(f.get_tensor(key))
            seen.add(name)
    missing = [k for k in params if k not in seen and k != "lm_head.weight"]
    if missing:
        raise ValueError(f"weights missing from checkpoint: {missing[:5]}")
    model.tie_weights()
    return model.eval()


class Qwen3ASR:
    def __init__(self, model_dir, device="cuda", dtype=torch.bfloat16, num_beams=1,
                 max_new_tokens=1024):
        self.device = device
        self.num_beams = num_beams
        self.max_new_tokens = max_new_tokens
        cfg = native_config(model_dir)
        self.model = load_weights(model_dir, cfg, device, dtype)
        tok = AutoTokenizer.from_pretrained(model_dir, local_files_only=True)
        fe = Qwen3ASRFeatureExtractor(n_window=cfg.audio_config.n_window)
        self.processor = Qwen3ASRProcessor(feature_extractor=fe, tokenizer=tok)
        self.n_window = cfg.audio_config.n_window
        self.eos = [i for i in (tok.convert_tokens_to_ids("<|im_end|>"),
                                tok.convert_tokens_to_ids("<|endoftext|>")) if i is not None]

    @staticmethod
    def _prompt(context):
        return ("<|im_start|>system\n" + (context or "") + "<|im_end|>\n"
                "<|im_start|>user\n<|audio_start|><|audio_pad|><|audio_end|><|im_end|>\n"
                "<|im_start|>assistant\n")

    @torch.inference_mode()
    def transcribe(self, audio: np.ndarray, prompt=None):
        """audio: float32 mono 16 kHz. Returns (text, language name or None)."""
        inputs = self.processor(text=[self._prompt(prompt)], audio=[audio],
                                audio_kwargs={"n_window": self.n_window})
        inputs.pop("num_audio_tokens", None)
        inputs = inputs.to(self.device)
        inputs["input_features"] = inputs["input_features"].to(self.model.dtype)
        # Tamil script is token-heavy (~8-12 tokens/s of speech); cap scales with the audio length.
        cap = min(self.max_new_tokens, 64 + int(30 * len(audio) / 16000))
        out = self.model.generate(**inputs, max_new_tokens=cap,
                                  num_beams=self.num_beams, do_sample=False,
                                  eos_token_id=self.eos, pad_token_id=self.eos[-1])
        gen = out[0, inputs["input_ids"].shape[1]:]
        # <asr_text> is an added (possibly special) token: keep it, drop the <|...|> markers.
        raw = self.processor.tokenizer.decode(gen, skip_special_tokens=False)
        raw = re.sub(r"<\|[^|>]*\|>", "", raw)
        lang, text = None, raw
        if "<asr_text>" in raw:
            head, text = raw.split("<asr_text>", 1)
            m = re.match(r"\s*language\s+(\w+)", head)
            lang = m.group(1) if m and m.group(1).lower() != "none" else None
        return text.strip(), lang

    def close(self):
        del self.model
        torch.cuda.empty_cache()
