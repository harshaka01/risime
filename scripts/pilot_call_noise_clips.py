"""Call 5 noise clips: quiet far voices under pink noise, plain noise, near-silence, and the request
followed (inside the same clip) by far voices while Risi checks. Args: <work dir>."""
import sys
import wave

import numpy as np

w = sys.argv[1]
rng = np.random.default_rng(5)


def read(name):
    f = wave.open(f"{w}/{name}.wav", "rb")
    p = f.getparams()
    x = np.frombuffer(f.readframes(f.getnframes()), dtype=np.int16).astype(np.float32)
    f.close()
    return p, x


def pink(n, rms):
    white = rng.standard_normal(n)
    b = np.fft.rfft(white)
    f = np.arange(len(b)) + 1.0
    y = np.fft.irfft(b / np.sqrt(f), n)
    return y / (np.sqrt(np.mean(y ** 2)) + 1e-9) * rms


def write(name, p, x):
    o = wave.open(f"{w}/{name}.wav", "wb")
    o.setparams(p)
    o.writeframes(np.clip(x, -32767, 32767).astype(np.int16).tobytes())
    o.close()


p, req = read("c5c")
sr = p.framerate
_, b1 = read("b1")
_, b2 = read("b2")
sil = lambda s: np.zeros(int(sr * s), dtype=np.float32)


def far(x, gain=0.04, noise_rms=250.0):
    # Far-away voices: quiet, muffled (a crude low-pass), under room noise.
    k = np.ones(9) / 9.0
    y = np.convolve(x, k, mode="same") * gain
    return y + pink(len(y), noise_rms)


write("n_far1", p, np.concatenate([sil(0.2), far(b1), sil(0.2)]))
write("n_far2", p, np.concatenate([sil(0.2), far(b2, 0.05), sil(0.2)]))
write("n_noise", p, pink(int(sr * 3.0), 900.0))
write("n_silence", p, pink(int(sr * 3.0), 30.0))
# The request, then far voices while Risi is checking (as on the call: the made-up "Bye-bye. Ready?",
# "You're on.", "you know" came right after it).
write("c5req", p, np.concatenate([req, sil(1.2), far(b2, 0.05), sil(1.0), far(b1[: len(b1) // 2])]))
