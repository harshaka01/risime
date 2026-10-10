"""Samples /proc/meminfo (GB) every second until killed: MemAvailable, Cached, AnonPages,
Mapped, Shmem, Unevictable. Used to see where a candidate's load transient goes.
  python3 -I eval/risi/memlog.py <out.tsv>
"""
import sys
import time

keys = ["MemAvailable", "Cached", "AnonPages", "Mapped", "Shmem", "Unevictable", "MemFree"]
with open(sys.argv[1], "w") as f:
    f.write("t\t" + "\t".join(keys) + "\n")
    while True:
        m = {}
        for line in open("/proc/meminfo"):
            k, v = line.split(":", 1)
            m[k] = int(v.split()[0]) / 1048576
        f.write(time.strftime("%H:%M:%S") + "\t" + "\t".join(f"{m.get(k, 0):.1f}" for k in keys) + "\n")
        f.flush()
        time.sleep(1)
