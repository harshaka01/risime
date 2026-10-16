"""A scripted, headless Call Risi caller (decision 090, scripts/call-risi-harness).

Joins a LiveKit room as the caller, waits for Risi's greeting, then plays each WAV as the caller's
microphone in real time and measures, per turn, the time from the end of the caller's speech to the
first audible frame of Risi's answer **as received by the caller** (so it includes the SFU hop and
jitter buffer). Optional: a barge-in turn (talk over Risi 1 s into its answer and measure how long
Risi's audio keeps coming) and an echo mode (Risi's audio is fed back into the caller's microphone at
--echo-db, like a phone on speaker without echo cancellation). Prints one JSON object on stdout.

  python3 -I harness_caller.py --url ws://127.0.0.1:17896 --token T [--echo-db -6] [--barge WAV] a.wav b.wav
  --phone-stt FILE (contract v1.53 §41.10): act as a phone that recognises its own speech: after
  Risi's {"t":"mode","mode":"phone"} on "risi.stt", send FILE's [{"text","conf"}] (one per clip) as
  `final` packets ~--phone-delay-ms after each clip; Risi's mode/speaking packets are counted.
  --wait-answer [--turn-log server.log]: behave like a person (pilot-call-test): after each clip wait
  until Risi's answer has finished (not just its holding line), then speak the next clip.
"""

import argparse
import asyncio
import collections
import json
import re
import sys
import time
import wave

import numpy as np
import soxr
from livekit import rtc

SR = 48000
FRAME = 480
LOUD = 300.0  # RMS (int16) of an audible frame of Risi's voice
GAP_MIN_MS = 100


def load(path):
    with wave.open(path, "rb") as w:
        sr, n, ch = w.getframerate(), w.getnframes(), w.getnchannels()
        pcm = np.frombuffer(w.readframes(n), dtype=np.int16)
    if ch > 1:
        pcm = pcm.reshape(-1, ch)[:, 0].copy()
    if sr != SR:
        pcm = soxr.resample(pcm, sr, SR, quality="HQ").astype(np.int16)
    return pcm


class Caller:
    def __init__(self, echo_db):
        self.room = rtc.Room()
        self.source = rtc.AudioSource(SR, 1, queue_size_ms=100)
        self.last_loud = 0.0
        self.first_loud_after = None  # (mark, t)
        self.mark = None
        self.risi = asyncio.Event()
        self.echo = None if echo_db is None else 10 ** (echo_db / 20.0)
        self.echo_buf = np.zeros(0, dtype=np.float32)
        self.loud_frames = 0
        self.play_q = collections.deque()
        # Gaps inside one answer (2026-10-15, gapless TTS): silent runs between two audible frames of
        # Risi after its answer began, >= GAP_MIN_MS (shorter ones are inside words).
        self.gaps = None  # a list while an answer is being measured
        self.answer_on = False
        self.silent_since = None
        # v1.53 §41.10 (--phone-stt)
        self.risi_identity = None
        self.stt_mode = None
        self.risi_speaking = False
        self.stt_rx = []  # [(t_mono, packet)] from Risi on "risi.stt"
        self.stt_seq = 0
        self.stt_sent = []

    async def connect(self, url, token):
        self.room.on("track_subscribed", self.on_track)
        self.room.on("data_received", self.on_data)
        await self.room.connect(url, token, rtc.RoomOptions(auto_subscribe=True))
        track = rtc.LocalAudioTrack.create_audio_track("mic", self.source)
        opts = rtc.TrackPublishOptions()
        opts.source = rtc.TrackSource.SOURCE_MICROPHONE
        await self.room.local_participant.publish_track(track, opts)
        asyncio.create_task(self.mic_idle())

    def on_data(self, packet):
        if getattr(packet, "topic", None) != "risi.stt":
            return
        try:
            msg = json.loads(bytes(packet.data).decode("utf-8"))
        except ValueError:
            return
        self.stt_rx.append((time.monotonic(), msg))
        if msg.get("t") == "mode":
            self.stt_mode = msg.get("mode")
        elif msg.get("t") == "speaking":
            self.risi_speaking = msg.get("on") is True

    async def send_final(self, text, conf, delay_s):
        await asyncio.sleep(delay_s)
        if self.stt_mode != "phone" or self.risi_identity is None:
            return
        self.stt_seq += 1
        pkt = {"t": "final", "seq": self.stt_seq, "text": text, "conf": conf, "lang": "en"}
        if self.risi_speaking:
            pkt["during_risi"] = True
        await self.room.local_participant.publish_data(
            json.dumps(pkt), reliable=True, destination_identities=[self.risi_identity], topic="risi.stt")
        self.stt_sent.append({"seq": self.stt_seq, "conf": conf, "during_risi": self.risi_speaking})

    def on_track(self, track, _pub, p):
        if track.kind == rtc.TrackKind.KIND_AUDIO:
            self.risi_identity = p.identity
            self.risi.set()
            asyncio.create_task(self.hear(track))

    async def hear(self, track):
        async for ev in rtc.AudioStream(track, sample_rate=SR, num_channels=1, frame_size_ms=10):
            pcm = np.frombuffer(ev.frame.data, dtype=np.int16)
            rms = float(np.sqrt(np.mean(pcm.astype(np.float32) ** 2))) if len(pcm) else 0.0
            now = time.monotonic()
            if rms >= LOUD:
                if self.gaps is not None and self.answer_on and self.silent_since is not None:
                    gap = int((now - self.silent_since) * 1000)
                    if gap >= GAP_MIN_MS:
                        self.gaps.append(gap)
                if self.gaps is not None:
                    self.answer_on = True
                self.silent_since = None
                self.last_loud = now
                self.loud_frames += 1
            elif self.silent_since is None:
                self.silent_since = now
                if self.mark is not None and self.first_loud_after is None:
                    self.first_loud_after = now
            if self.echo is not None:
                self.echo_buf = np.concatenate([self.echo_buf, pcm.astype(np.float32) * self.echo])[-SR:]

    def gaps_start(self):
        self.gaps, self.answer_on, self.silent_since = [], False, None

    def gaps_stop(self):
        g, self.gaps = self.gaps or [], None
        return g

    def echo_frame(self):
        if self.echo is None or len(self.echo_buf) < FRAME:
            return np.zeros(FRAME, dtype=np.float32)
        f, self.echo_buf = self.echo_buf[:FRAME], self.echo_buf[FRAME:]
        return f

    async def push(self, pcm_f32):
        frame = rtc.AudioFrame(np.clip(pcm_f32, -32768, 32767).astype(np.int16).tobytes(), SR, 1, FRAME)
        await self.source.capture_frame(frame)

    async def mic_idle(self):
        # The only writer of the mic track: the scripted clip's frames when one is playing, else
        # silence; plus the echo of Risi's audio in echo mode. Real-time pacing by capture_frame.
        while True:
            f = self.play_q.popleft() if self.play_q else np.zeros(FRAME, dtype=np.float32)
            await self.push(f + self.echo_frame())

    async def play(self, pcm):
        for i in range(0, len(pcm) - FRAME + 1, FRAME):
            self.play_q.append(pcm[i:i + FRAME].astype(np.float32))
        while self.play_q:
            await asyncio.sleep(0.005)

    async def wait_quiet(self, quiet_s=1.2, timeout=40.0):
        t0 = time.monotonic()
        while time.monotonic() - t0 < timeout:
            if time.monotonic() - max(self.last_loud, t0) >= quiet_s and self.last_loud > 0:
                return True
            await asyncio.sleep(0.05)
        return False

    async def wait_answer(self, turn_log=None, quiet_s=2.5, cap_s=45.0):
        """Like a person on the phone: wait until Risi's ANSWER has finished, not just its holding
        line. With `turn_log` (the server's log), the answer is done when a new "call risi turn:"
        line with a final status appears (a turn is recorded when its answer was spoken; replaced
        and barged turns are not answers); then >= quiet_s of Risi silence. Without it: Risi's
        audio, then quiet_s of silence, and while all Risi said since is short (< 3 s, a holding
        line) more audio is awaited. Capped at cap_s from the end of the clip (45 s: Risi's tool budget is
        25 s and a long answer is spoken after it; at 25 s the caller talked over answers, 15 Oct)."""
        t0 = time.monotonic()
        heard_from = self.loud_frames
        pos = getattr(self, "log_pos", None) if turn_log else None
        done = False
        while time.monotonic() - t0 < cap_s:
            if turn_log and pos is not None and not done:
                try:
                    with open(turn_log, "rb") as f:
                        f.seek(pos)
                        chunk = f.read()
                    for m in re.finditer(rb"call risi turn:[^\n]*?status=(\w+)", chunk):
                        if m.group(1) not in (b"replaced", b"barged"):
                            done = True
                except OSError:
                    turn_log = None
            quiet = self.last_loud > 0 and time.monotonic() - max(self.last_loud, t0) >= quiet_s
            if turn_log and pos is not None:
                if done and quiet:
                    return True
            elif quiet and self.loud_frames > heard_from and (self.loud_frames - heard_from) * 10 >= 3000:
                return True
            await asyncio.sleep(0.05)
        return False

    def mark_log(self, turn_log):
        try:
            with open(turn_log, "rb") as f:
                f.seek(0, 2)
                self.log_pos = f.tell()
        except (OSError, TypeError):
            self.log_pos = None

    async def wait_first(self, timeout=15.0):
        t0 = time.monotonic()
        while self.first_loud_after is None and time.monotonic() - t0 < timeout:
            await asyncio.sleep(0.005)
        return self.first_loud_after


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--token", required=True)
    ap.add_argument("--echo-db", type=float, default=None)
    ap.add_argument("--barge", default=None)
    ap.add_argument("--rounds", type=int, default=1)
    ap.add_argument("--wait-answer", action="store_true",
                    help="after each clip wait until Risi's answer has finished (2.5 s quiet, 45 s cap)")
    ap.add_argument("--turn-log", default=None,
                    help="the server log: with --wait-answer, a new final 'call risi turn:' line ends the answer")
    ap.add_argument("--phone-stt", default=None, help="JSON [{text, conf}] per clip: send them as risi.stt finals")
    ap.add_argument("--phone-delay-ms", type=int, default=700)
    ap.add_argument("wavs", nargs="+")
    a = ap.parse_args()

    c = Caller(a.echo_db)
    await c.connect(a.url, a.token)
    out = {"turns": [], "barge": None, "greeting": False, "echo_db": a.echo_db}
    await asyncio.wait_for(c.risi.wait(), 20)
    out["greeting"] = await c.wait_quiet(timeout=20)

    clips = [(p, load(p)) for p in a.wavs]
    phone = None
    if a.phone_stt:
        with open(a.phone_stt) as f:
            phone = json.load(f)
    for _ in range(a.rounds):
        for i, (path, pcm) in enumerate(clips):
            c.first_loud_after = None
            if a.wait_answer and a.turn_log:
                c.mark_log(a.turn_log)
            await c.play(pcm)
            c.mark = time.monotonic()
            if phone is not None and i < len(phone):
                asyncio.create_task(c.send_final(phone[i]["text"], phone[i].get("conf"),
                                                 a.phone_delay_ms / 1000.0))
            c.gaps_start()
            spoke_from = c.loud_frames
            if a.wait_answer:
                answered = await c.wait_answer(a.turn_log)
                first = c.first_loud_after
            else:
                first = await c.wait_first()
                answered = None
            lat = None if first is None else int((first - c.mark) * 1000)
            c.mark = None
            if not a.wait_answer:
                # 2 s of quiet ends the answer (a pause between Risi's sentences is shorter).
                await c.wait_quiet(quiet_s=2.0)
            out["turns"].append({"clip": path.rsplit("/", 1)[-1], "first_audio_ms": lat,
                                 "risi_audio_ms": (c.loud_frames - spoke_from) * 10,
                                 "answered": answered, "gaps_ms": c.gaps_stop()})

    if a.barge:
        _, pcm = clips[0]
        b = load(a.barge)
        lead_ms = 0
        for i in range(0, len(b) - FRAME, FRAME):
            if float(np.sqrt(np.mean(b[i:i + FRAME].astype(np.float32) ** 2))) >= LOUD:
                break
            lead_ms += 10
        c.first_loud_after = None
        await c.play(pcm)
        c.mark = time.monotonic()
        await c.wait_first()
        c.mark = None
        await asyncio.sleep(1.0)  # Risi is speaking now
        t_talk = time.monotonic()
        task = asyncio.create_task(c.play(b))
        # Risi's audio should stop within ~barge_ms + the jitter buffer.
        # Stopped = no audible Risi frame for 0.7 s (longer than a pause between its sentences);
        # the stop time is the last audible frame after the caller started talking.
        stopped = None
        while time.monotonic() - t_talk < 6:
            if time.monotonic() - c.last_loud > 0.7:
                stopped = max(c.last_loud, t_talk)
                break
            await asyncio.sleep(0.01)
        await task
        out["barge"] = {"stop_ms": None if stopped is None else int((stopped - t_talk) * 1000),
                        "risi_was_speaking": c.last_loud > t_talk - 0.3,
                        # Host monotonic ms (the worker's clock too): to split the stop time into the
                        # worker's detection and the audio path (RISI_VOICE_LEVEL_LOG FIRE lines).
                        # The barge clip's own lead silence (TTS padding): stop_after_voice_ms is the
                        # stop time from the caller's first audible frame.
                        "clip_lead_ms": lead_ms,
                        "stop_after_voice_ms": None if stopped is None else int((stopped - t_talk) * 1000) - lead_ms,
                        "t_talk_mono_ms": int(t_talk * 1000),
                        "t_stop_mono_ms": None if stopped is None else int(stopped * 1000)}
        c.first_loud_after = None
        c.mark = time.monotonic()
        first = await c.wait_first()
        out["barge"]["answer_after_ms"] = None if first is None else int((first - c.mark) * 1000)
        c.mark = None
        await c.wait_quiet()

    if phone is not None:
        out["phone_stt"] = {
            "mode_rx": [m.get("mode") for _, m in c.stt_rx if m.get("t") == "mode"],
            "speaking_on_rx": sum(1 for _, m in c.stt_rx if m.get("t") == "speaking" and m.get("on") is True),
            "speaking_off_rx": sum(1 for _, m in c.stt_rx if m.get("t") == "speaking" and m.get("on") is False),
            "finals_sent": c.stt_sent,
        }
    await c.room.disconnect()
    print(json.dumps(out))


if __name__ == "__main__":
    asyncio.run(main())
    sys.exit(0)
