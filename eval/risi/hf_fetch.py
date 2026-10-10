"""Downloads one public, ungated Hugging Face repo (no token) into /out/<name>.

Run inside the vLLM image (it ships huggingface_hub), e.g. by scripts/risi-eval fetch:
  python3 hf_fetch.py <repo> <name> [allow-pattern ...]
Refuses gated repos (records "gated" instead of asking for a token).
"""
import sys

from huggingface_hub import HfApi, snapshot_download

repo, name, *allow = sys.argv[1:]
info = HfApi().model_info(repo)
if getattr(info, "gated", False):
    print(f"GATED {repo}: skipped")
    sys.exit(3)
path = snapshot_download(repo, local_dir=f"/out/{name}", token=False,
                         allow_patterns=allow or None,
                         ignore_patterns=["original/*", "metal/*", "*.pt", "*.pth", "optimizer*"])
print(f"OK {repo} -> {path}")
