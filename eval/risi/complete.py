"""Exit 0 when a downloaded model dir is complete: every safetensors shard of the index exists
and is non-empty, and no Hugging Face download is still in progress.
  python3 -I eval/risi/complete.py <model dir>
"""
import glob
import json
import os
import sys

d = sys.argv[1]
if not os.path.isdir(d) or glob.glob(os.path.join(d, ".cache/huggingface/download/**/*.incomplete"), recursive=True):
    sys.exit(1)
idx = os.path.join(d, "model.safetensors.index.json")
files = set(json.load(open(idx))["weight_map"].values()) if os.path.exists(idx) else {"model.safetensors"}
ok = all(os.path.exists(os.path.join(d, f)) and os.path.getsize(os.path.join(d, f)) > 0 for f in files)
sys.exit(0 if ok else 1)
