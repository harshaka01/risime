"""Image build check for risime-tts-lab: every engine's pinned packages import in its own process
(the way tts_lab_server.py starts them), and the server module imports. No model is loaded."""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ok = True
for eng in ("chatterbox", "chatterbox-turbo", "indic-parler", "orpheus", "dia2"):
    r = subprocess.run([sys.executable, "-I", os.path.join(HERE, "tts_lab_worker.py"), eng, "--check"],
                       capture_output=True, text=True, env={**os.environ, "TTS_DEVICE": "cpu"})
    print(r.stdout.strip() or f"{eng}: FAILED\n{r.stderr[-3000:]}")
    ok &= r.returncode == 0
r = subprocess.run([sys.executable, "-I", "-c", "import aiohttp, ast; ast.parse(open('%s').read())"
                    % os.path.join(HERE, "tts_lab_server.py")])
ok &= r.returncode == 0
sys.exit(0 if ok else 1)
