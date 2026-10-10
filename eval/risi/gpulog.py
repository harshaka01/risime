"""Samples nvidia-smi per-process GPU memory every 10 s (GB10: per-process numbers work, the
total shows N/A) and appends `time pid name MiB` lines, plus the running risi-eval container.
  python3 -I eval/risi/gpulog.py <out.tsv>
"""
import subprocess
import sys
import time

with open(sys.argv[1], "a") as f:
    while True:
        try:
            q = subprocess.run(["nvidia-smi", "--query-compute-apps=pid,process_name,used_memory",
                                "--format=csv,noheader,nounits"], capture_output=True, text=True, timeout=20).stdout
            c = subprocess.run(["docker", "ps", "--format", "{{.Names}}", "--filter", "name=risi-eval-"],
                               capture_output=True, text=True, timeout=20).stdout.split()
            c = [x for x in c if not x.startswith("risi-eval-fetch")]
            for line in q.strip().splitlines():
                f.write(f"{time.strftime('%H:%M:%S')}\t{(c or ['-'])[0]}\t{line.replace(', ', chr(9))}\n")
            f.flush()
        except Exception as e:  # noqa: BLE001
            f.write(f"{time.strftime('%H:%M:%S')}\terror\t{e}\n")
        time.sleep(10)
