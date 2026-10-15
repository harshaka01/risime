"""risi-voice: Risi's media worker for "Call Risi" (decision 090).

A thin media bridge (infrastructure, no decisions): it joins one LiveKit room as Risi, cleans the
caller's audio (WebRTC APM echo cancellation against Risi's own outgoing audio + high-pass, then
RNNoise), finds the caller's turns (Silero VAD + end-of-turn silence), hands each finished utterance
to the Elixir Voice agent, and speaks what the agent tells it to (Kokoro via risime-tts on loopback),
stopping at once on barge-in. Speech is gapless (2026-10-15): the next sentence is synthesised while
the current one plays, the TTS's padding silence is trimmed, and sentences of one answer follow each
other after one natural ~150 ms pause; a stop clears the queue and the synthesis in flight. Everything is in memory; nothing is written to disk or logged.

It runs as an Erlang Port of `RisiMe.Agent.CallRisi.Session` ({:packet, 4}: a 4-byte big-endian
length, then one JSON object) and dies with it (stdin EOF -> leave and exit).

Commands (Elixir -> worker):
  {"cmd": "join", "url", "token", "caller", "tts_url", "tts_key"?, "eot_ms"?, "barge_ms"?, "min_speech_ms"?}
  {"cmd": "say", "seq", "utt", "text", "voice", "speed"?}     queue a sentence for utterance `utt`
  {"cmd": "stop_speaking"}                                    drop everything queued and playing
  {"cmd": "cal_req", "id", "from", "to"}     contract v1.52 §41.8: ask the caller's phone for its events
  {"cmd": "leave"}
Events (worker -> Elixir):
  {"ev": "joined"} / {"ev": "caller_joined"} / {"ev": "caller_left"} / {"ev": "left", "reason"}
  {"ev": "speech_start", "speaking": bool}
  {"ev": "barge_in", "utt_playing": int|null}
  {"ev": "utterance", "utt": int, "pcm16k": base64 s16le mono, "speech_ms", "eot_wait_ms"}
  {"ev": "first_audio", "utt", "seq", "after_end_ms", "tts_first_ms"}   first Risi frame of a turn
  {"ev": "said", "seq", "utt", "ms", "interrupted": bool}
  {"ev": "idle"}                                              nothing queued or playing any more
  {"ev": "cal", "id", "ok", "events"? | "reason"?}           the phone's reply to a cal_req (v1.52 §41.8)
  {"ev": "error", "code"}

Meeting titles (contract v1.52 §41.8): `cal_req` is published as a reliable data packet on topic
"risi.cal" ({"t": "cal_req", "id", "from", "to"}) to the caller's identity only. A packet comes back
as {"ev": "cal", ...} only when it is on "risi.cal", from the caller, a {"t": "cal"} reply to an id
this worker sent and has not seen answered; everything else is dropped. When the request can't be
sent (no caller in the room, a publish error) the worker answers {"ev": "cal", "id", "ok": false,
"reason": "error"} at once. Packets are never logged or written anywhere.
"""

import asyncio
import collections
import base64
import json
import os
import struct
import sys
import threading
import time

os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("DO_NOT_TRACK", "1")

import aiohttp  # noqa: E402
import numpy as np  # noqa: E402
import soxr  # noqa: E402
import torch  # noqa: E402
from livekit import rtc  # noqa: E402
from pyrnnoise import rnnoise as _rnn  # noqa: E402
from silero_vad import load_silero_vad  # noqa: E402

torch.set_num_threads(1)

SR = 48000
FRAME = 480  # 10 ms at 48 kHz (RNNoise's and APM's frame)
VAD_SR = 16000
VAD_WIN = 512  # 32 ms at 16 kHz (Silero's window)
WIN_MS = 32
MAX_UTT_S = 30
PREROLL_WINS = 10  # 320 ms kept before the speech start
TAIL_MS = 700
ECHO_RATIO = 0.35
BARGE_KEEP_WINS = 94  # 3 s kept while Risi speaks (a barge-in run may start that far back)
# Gapless speech (2026-10-15, Harsha's item C): Kokoro pads every sentence it speaks with ~300 ms of
# silence before and ~550 ms after (measured: af_heart, af_bella, bf_emma), so two sentences played
# back to back had ~0.9 s of dead air between them, and every answer began ~0.3 s late. Each piece is
# trimmed to SIL_KEEP_LEAD before its first sound and SIL_KEEP_TAIL after its last, a silence inside
# a piece longer than SIL_INNER_MAX (Kokoro's own sentence split) is cut to SIL_INNER_KEEP, and the
# next piece of the same answer follows after one PIECE_PAUSE_MS pause.
SIL_RMS = 120.0  # a 10-ms frame quieter than this (int16 RMS) is silence
SIL_KEEP_LEAD = 3  # frames (30 ms)
SIL_KEEP_TAIL = 5  # frames (50 ms)
SIL_INNER_MAX = 35  # frames (350 ms)
SIL_INNER_KEEP = (15, 5)  # frames kept from the start and the end of a long inner silence (200 ms)
EDGE_KEPT_MS = (SIL_KEEP_LEAD + SIL_KEEP_TAIL) * 10
PIECE_PAUSE_MS = max(EDGE_KEPT_MS, int(os.environ.get("RISI_VOICE_PAUSE_MS", "150")))
SILENT_FRAME = np.zeros(FRAME, dtype=np.int16)
CAL_TOPIC = "risi.cal"  # contract v1.52 §41.8
CAL_MAX_BYTES = 64 * 1024  # 100 events with 200-char titles fit well within this
CAL_PENDING_MAX = 8
CAL_REASONS = ("no_permission", "no_calendars", "off", "error")


def cal_packet(msg):
    """The request packet for a `cal_req` command, or None when the command is malformed."""
    rid, frm, to = msg.get("id"), msg.get("from"), msg.get("to")
    if not all(isinstance(v, str) and 0 < len(v) <= 64 for v in (rid, frm, to)):
        return None
    return {"t": "cal_req", "id": rid, "from": frm, "to": to}


def cal_event(payload, pending):
    """The Port event for a reply packet (bytes) to one of the `pending` ids, or None to drop it."""
    if not isinstance(payload, (bytes, bytearray)) or len(payload) > CAL_MAX_BYTES:
        return None
    try:
        msg = json.loads(bytes(payload).decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return None
    if not isinstance(msg, dict) or msg.get("t") != "cal" or msg.get("id") not in pending:
        return None
    ev = {"ev": "cal", "id": msg["id"]}
    if msg.get("ok") is True and isinstance(msg.get("events"), list):
        ev["ok"] = True
        ev["events"] = [e for e in msg["events"][:100] if isinstance(e, dict)]
    elif msg.get("ok") is False and msg.get("reason") in CAL_REASONS:
        ev["ok"] = False
        ev["reason"] = msg["reason"]
    else:
        ev["ok"] = False
        ev["reason"] = "error"
    return ev


def now_ms():
    return time.monotonic() * 1000.0


class EdgeTrim:
    """Drops the TTS's padding silence from one piece, frame by frame (48-kHz int16, 10 ms), without
    delaying any sound: a silent run is held back until the next sound (then kept, or cut to
    SIL_INNER_KEEP when longer than SIL_INNER_MAX) or the piece's end (then cut to SIL_KEEP_TAIL)."""

    def __init__(self):
        self.started = False
        self.sil = []

    def push(self, frame):
        if float(np.sqrt(np.mean(frame.astype(np.float32) ** 2))) < SIL_RMS:
            self.sil.append(frame)
            return []
        if not self.started:
            self.started = True
            out = self.sil[-SIL_KEEP_LEAD:]
        elif len(self.sil) > SIL_INNER_MAX:
            out = self.sil[: SIL_INNER_KEEP[0]] + self.sil[-SIL_INNER_KEEP[1]:]
        else:
            out = self.sil
        self.sil = []
        out.append(frame)
        return out

    def end(self):
        out = self.sil[:SIL_KEEP_TAIL] if self.started else []
        self.sil = []
        return out


class Piece:
    """One `say` on its way to the caller: its frames arrive while it is synthesised (None ends)."""

    def __init__(self, gen, msg):
        self.gen = gen
        self.msg = msg
        self.frames = asyncio.Queue()
        self.t0 = now_ms()  # synthesis start (tts_first_ms counts from here)
        self.error = None
        self.said = True  # False when the TTS service refused it: no "said" (as before)


class Port:
    """{:packet, 4} JSON over stdin/stdout."""

    def __init__(self, loop):
        self.loop = loop
        self.inbox = asyncio.Queue()
        self.lock = threading.Lock()
        threading.Thread(target=self._reader, daemon=True).start()

    def _reader(self):
        stdin = sys.stdin.buffer
        while True:
            hdr = stdin.read(4)
            if len(hdr) < 4:
                break
            (n,) = struct.unpack(">I", hdr)
            body = stdin.read(n)
            if len(body) < n:
                break
            try:
                msg = json.loads(body)
            except ValueError:
                continue
            self.loop.call_soon_threadsafe(self.inbox.put_nowait, msg)
        self.loop.call_soon_threadsafe(self.inbox.put_nowait, {"cmd": "leave", "reason": "port_closed"})

    def send(self, ev):
        data = json.dumps(ev, separators=(",", ":")).encode()
        with self.lock:
            try:
                sys.stdout.buffer.write(struct.pack(">I", len(data)) + data)
                sys.stdout.buffer.flush()
            except (BrokenPipeError, ValueError):
                # The session (the Port's owner) is gone: so is this call.
                os._exit(0)


class Denoiser:
    """RNNoise on 10-ms frames at 48 kHz (BSD-3; pyrnnoise's bundled librnnoise)."""

    def __init__(self):
        self.state = _rnn.lib.rnnoise_create(None)
        self.inp = (_rnn.ctypes.c_float * FRAME)()
        self.out = (_rnn.ctypes.c_float * FRAME)()

    def frame(self, pcm_i16):
        np.copyto(np.frombuffer(self.inp, dtype=np.float32), pcm_i16.astype(np.float32))
        _rnn.lib.rnnoise_process_frame(self.state, self.out, self.inp)
        return np.frombuffer(self.out, dtype=np.float32).copy()

    def close(self):
        if self.state:
            _rnn.lib.rnnoise_destroy(self.state)
            self.state = None


class Worker:
    def __init__(self, port):
        self.port = port
        self.room = None
        self.source = None
        self.apm = rtc.AudioProcessingModule(echo_cancellation=True, high_pass_filter=True)
        self.vad = load_silero_vad()
        self.cfg = {}
        self.caller = None
        self.say_q = asyncio.Queue()  # (gen, say) not yet being synthesised
        self.play_q = asyncio.Queue(maxsize=1)  # the next Piece, synthesised while the current one plays
        self.pending = 0  # says of this generation not yet played to the end
        self.play_clock = 0.0  # monotonic ms when the audio handed to the source so far has played
        self.play_task = None
        self.synth_task = None
        self.http = None
        self.speaking = False
        self.utt_n = 0
        self.ends = {}  # utt -> monotonic ms of its last voiced window
        self.first_done = set()
        self.playing_utt = None
        self.gen = 0  # bumped on every stop: queued sentences of an older generation are dropped
        self.listen_task = None
        # Echo guard (harness 2026-10-14: with Risi's audio fed back at -6 dB and no phone AEC, the
        # tail of its own sentences was heard as the caller): Risi counts as speaking until
        # TAIL_MS after its last frame, and while it speaks a barge-in needs the caller's level to
        # beat ECHO_RATIO x Risi's own recent output level.
        self.speak_until = 0.0
        self.out_levels = collections.deque()  # (t_ms, rms) of Risi's frames, last ~1.5 s
        self.cal_pending = collections.OrderedDict()  # v1.52 §41.8: request ids sent, not yet answered

    # ---- control

    async def run(self):
        while True:
            msg = await self.port.inbox.get()
            cmd = msg.get("cmd")
            try:
                if cmd == "join":
                    await self.join(msg)
                elif cmd == "say":
                    self.pending += 1
                    self.say_q.put_nowait((self.gen, msg))
                elif cmd == "stop_speaking":
                    self.stop_speaking()
                elif cmd == "cal_req":
                    await self.cal_req(msg)
                elif cmd == "leave":
                    await self.leave(msg.get("reason", "leave"))
                    return
            except Exception as e:  # never crash on one bad command; tell the agent
                self.port.send({"ev": "error", "code": type(e).__name__})

    async def join(self, msg):
        self.cfg = {
            "eot_ms": int(msg.get("eot_ms") or 350),
            "barge_ms": int(msg.get("barge_ms") or 300),
            "min_speech_ms": int(msg.get("min_speech_ms") or 250),
            "tts_url": msg["tts_url"],
            "tts_key": msg.get("tts_key") or "",
        }
        self.caller = msg.get("caller")  # identity prefix "<user_id>/" of the only human allowed
        self.http = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=60, sock_read=15))
        self.room = rtc.Room()
        self.room.on("track_subscribed", self.on_track)
        self.room.on("participant_disconnected", self.on_left)
        self.room.on("data_received", self.on_data)
        self.room.on("disconnected", lambda *_: self.port.send({"ev": "left", "reason": "disconnected"}))
        await self.room.connect(msg["url"], msg["token"], rtc.RoomOptions(auto_subscribe=True))
        self.source = rtc.AudioSource(SR, 1, queue_size_ms=200)
        track = rtc.LocalAudioTrack.create_audio_track("risi", self.source)
        opts = rtc.TrackPublishOptions()
        opts.source = rtc.TrackSource.SOURCE_MICROPHONE
        await self.room.local_participant.publish_track(track, opts)
        self.play_task = asyncio.create_task(self.player())
        self.synth_task = asyncio.create_task(self.synthesizer())
        self.port.send({"ev": "joined"})
        for p in self.room.remote_participants.values():
            for pub in p.track_publications.values():
                if pub.track is not None:
                    self.on_track(pub.track, pub, p)

    def allowed(self, participant):
        return self.caller is None or participant.identity.startswith(self.caller)

    def on_track(self, track, _pub, participant):
        if track.kind != rtc.TrackKind.KIND_AUDIO or not self.allowed(participant):
            return
        if self.listen_task is None:
            self.port.send({"ev": "caller_joined"})
            self.listen_task = asyncio.create_task(self.listen(track))

    def on_left(self, participant):
        if self.allowed(participant):
            self.port.send({"ev": "caller_left"})

    # ---- meeting titles (contract v1.52 §41.8)

    def caller_identity(self):
        if self.room is None or self.caller is None:
            return None
        for p in self.room.remote_participants.values():
            if p.identity.startswith(self.caller):
                return p.identity
        return None

    async def cal_req(self, msg):
        packet = cal_packet(msg)
        if packet is None:
            return
        rid = packet["id"]
        to = self.caller_identity()
        if to is None:
            self.port.send({"ev": "cal", "id": rid, "ok": False, "reason": "error"})
            return
        self.cal_pending[rid] = now_ms()
        while len(self.cal_pending) > CAL_PENDING_MAX:
            self.cal_pending.popitem(last=False)
        try:
            await self.room.local_participant.publish_data(
                json.dumps(packet, separators=(",", ":")), reliable=True,
                destination_identities=[to], topic=CAL_TOPIC)
        except Exception:
            self.cal_pending.pop(rid, None)
            self.port.send({"ev": "cal", "id": rid, "ok": False, "reason": "error"})

    def on_data(self, packet):
        # Only the caller's replies on "risi.cal" to a request this worker sent; drop the rest.
        if getattr(packet, "topic", None) != CAL_TOPIC:
            return
        p = getattr(packet, "participant", None)
        if p is None or self.caller is None or not p.identity.startswith(self.caller):
            return
        ev = cal_event(packet.data, self.cal_pending)
        if ev is None:
            return
        self.cal_pending.pop(ev["id"], None)
        self.port.send(ev)

    async def leave(self, reason):
        self.stop_speaking()
        if self.room is not None:
            try:
                await self.room.disconnect()
            except Exception:
                pass
        if self.http is not None:
            await self.http.close()
        self.port.send({"ev": "left", "reason": reason})

    # ---- listening

    async def listen(self, track):
        stream = rtc.AudioStream(track, sample_rate=SR, num_channels=1, frame_size_ms=10)
        den = Denoiser()
        rs = soxr.ResampleStream(SR, VAD_SR, 1, dtype="float32")
        buf16 = np.zeros(0, dtype=np.float32)
        lvl = []
        pre = []
        utt = None  # list of 16-kHz float windows while in speech
        voiced = 0
        run = 0
        silence = 0
        speech_ms = 0
        last_voice = 0.0
        barged = False
        try:
            async for ev in stream:
                f = ev.frame
                if f.samples_per_channel != FRAME:
                    continue
                self.apm.process_stream(f)  # AEC (reverse = Risi's own audio) + high-pass, in place
                pcm = np.frombuffer(f.data, dtype=np.int16)
                lvl.append(float(np.sqrt(np.mean(pcm.astype(np.float32) ** 2))))
                clean = den.frame(pcm)
                buf16 = np.concatenate([buf16, rs.resample_chunk(clean / 32768.0)])
                while len(buf16) >= VAD_WIN:
                    win, buf16 = buf16[:VAD_WIN], buf16[VAD_WIN:]
                    p = float(self.vad(torch.from_numpy(win.copy()), VAD_SR).item())
                    t_now = now_ms()
                    speaking = self.speaking or t_now < self.speak_until
                    in_rms = max(lvl[-4:]) if lvl else 0.0
                    lvl = lvl[-4:]
                    is_voice = p >= (0.7 if speaking else 0.5)
                    if speaking and is_voice and in_rms < ECHO_RATIO * self.out_level(t_now):
                        is_voice = False  # as loud as Risi's own echo would be: not the caller
                    t = now_ms()
                    if utt is None:
                        pre.append(win)
                        pre = pre[-BARGE_KEEP_WINS:]
                        # Strictly consecutive voiced windows. (A leaky count that forgave gaps during
                        # double-talk caught barge-ins sooner but let Risi's own echo through at -6 dB:
                        # harness 2026-10-14, 3 self-barge-ins. Never hearing itself wins.)
                        if is_voice:
                            voiced += 1
                            run += 1
                        else:
                            voiced, run = 0, 0
                        need = self.cfg["barge_ms"] if speaking else 64
                        if voiced * WIN_MS >= need:
                            if speaking and not barged:
                                barged = True
                                self.port.send({"ev": "barge_in", "utt_playing": self.playing_utt})
                                self.stop_speaking()
                            utt = list(pre[-(run + PREROLL_WINS):])
                            run = 0
                            speech_ms = voiced * WIN_MS
                            silence = 0
                            last_voice = t
                            self.port.send({"ev": "speech_start", "speaking": speaking})
                        continue
                    utt.append(win)
                    if is_voice:
                        speech_ms += WIN_MS
                        silence = 0
                        last_voice = t
                    else:
                        silence += WIN_MS
                    too_long = len(utt) * WIN_MS >= MAX_UTT_S * 1000
                    if silence >= self.cfg["eot_ms"] or too_long:
                        if speech_ms >= self.cfg["min_speech_ms"] or too_long:
                            keep = len(utt) - max(0, silence // WIN_MS - 3)  # ~100 ms tail kept
                            self.emit_utterance(utt[:keep], speech_ms, last_voice)
                        utt, voiced, run, pre, barged = None, 0, 0, [], False
        finally:
            den.close()

    def emit_utterance(self, wins, speech_ms, last_voice):
        self.utt_n += 1
        n = self.utt_n
        self.ends[n] = last_voice
        pcm = (np.clip(np.concatenate(wins), -1.0, 1.0) * 32767.0).astype(np.int16).tobytes()
        self.port.send({
            "ev": "utterance", "utt": n, "pcm16k": base64.b64encode(pcm).decode(),
            "speech_ms": speech_ms, "eot_wait_ms": int(now_ms() - last_voice),
        })
        # The audio leaves memory with this message (the agent drops it after STT).
        del pcm

    # ---- speaking

    def out_level(self, t):
        # Risi's loudest output in the last 800 ms (the echo path is a few hundred ms long).
        return max((r for (ts, r) in self.out_levels if ts >= t - 800), default=0.0)

    def stop_speaking(self):
        self.gen += 1
        self.pending = 0
        while not self.say_q.empty():
            self.say_q.get_nowait()
        while not self.play_q.empty():
            self.play_q.get_nowait()
        # The synthesis in flight stops at once: its request is dropped (the TTS service stops after
        # the sentence it is on) and a fresh synthesiser waits for the next turn.
        if self.synth_task is not None:
            self.synth_task.cancel()
            self.synth_task = asyncio.create_task(self.synthesizer())
        if self.source is not None:
            self.source.clear_queue()
        self.play_clock = now_ms()
        self.speaking = False

    async def synthesizer(self):
        """Synthesises the queued pieces in order, at most one ahead of the piece playing (play_q
        holds one): piece N+1 is ready before piece N has finished playing."""
        while True:
            gen, msg = await self.say_q.get()
            if gen != self.gen:
                continue
            piece = Piece(gen, msg)
            await self.play_q.put(piece)
            if gen != self.gen:
                piece.frames.put_nowait(None)
                continue
            await self.synthesize(piece)

    async def synthesize(self, piece):
        trim = EdgeTrim()
        try:
            msg = piece.msg
            headers = {"Authorization": "Bearer " + self.cfg["tts_key"]} if self.cfg["tts_key"] else {}
            body = {"text": msg["text"], "voice": msg.get("voice") or "", "speed": msg.get("speed") or 1.0}
            async with self.http.post(self.cfg["tts_url"] + "/v1/tts", json=body, headers=headers) as r:
                if r.status != 200:
                    piece.error, piece.said = "tts_%d" % r.status, False
                    return
                rs = soxr.ResampleStream(24000, SR, 1, dtype="int16")
                rest = np.zeros(0, dtype=np.int16)
                async for chunk in r.content.iter_chunked(4800):
                    if piece.gen != self.gen:
                        return
                    pcm = np.frombuffer(chunk[: len(chunk) // 2 * 2], dtype=np.int16)
                    up = np.concatenate([rest, rs.resample_chunk(pcm)])
                    n = len(up) // FRAME * FRAME
                    up, rest = up[:n], up[n:]
                    for i in range(0, n, FRAME):
                        for f in trim.push(up[i:i + FRAME]):
                            piece.frames.put_nowait(f)
                for f in trim.end():
                    piece.frames.put_nowait(f)
        except (aiohttp.ClientError, asyncio.TimeoutError):
            piece.error = "tts_unreachable"
        finally:
            piece.frames.put_nowait(None)  # also on a stop (cancelled): the player never waits forever

    async def play_frame(self, pcm):
        frame = rtc.AudioFrame(pcm.tobytes(), SR, 1, FRAME)
        self.apm.process_reverse_stream(frame)
        t_in = now_ms()
        await self.source.capture_frame(frame)
        t_out = now_ms()
        # When the audio handed over so far will have played out (the source buffers ~200 ms).
        self.play_clock = max(self.play_clock, t_in) + FRAME * 1000.0 / SR
        self.speak_until = t_out + TAIL_MS
        self.out_levels.append((t_out, float(np.sqrt(np.mean(pcm.astype(np.float32) ** 2)))))
        while self.out_levels and self.out_levels[0][0] < t_out - 1500:
            self.out_levels.popleft()

    async def player(self):
        prev_utt = None
        while True:
            piece = await self.play_q.get()
            if piece.gen != self.gen:
                continue
            msg = piece.msg
            self.speaking = True
            self.playing_utt = msg.get("utt")
            sent = 0
            first = True
            interrupted = False
            while True:
                f = await piece.frames.get()
                if f is None:
                    break
                if piece.gen != self.gen:
                    interrupted = True
                    break
                if first:
                    first = False
                    if prev_utt is not None and prev_utt == msg.get("utt"):
                        # The next sentence of the same answer: one natural pause, no dead air.
                        dry = max(0.0, now_ms() - self.play_clock)  # silence already heard
                        for _ in range(int(max(0.0, PIECE_PAUSE_MS - EDGE_KEPT_MS - dry) // 10)):
                            if piece.gen != self.gen:
                                break
                            await self.play_frame(SILENT_FRAME)
                        if piece.gen != self.gen:
                            interrupted = True
                            break
                    self.first_audio(msg, piece.t0)
                await self.play_frame(f)
                sent += FRAME
            if piece.error:
                self.port.send({"ev": "error", "code": piece.error})
            if piece.said:
                self.port.send({"ev": "said", "seq": msg.get("seq"), "utt": msg.get("utt"),
                                "ms": int(sent * 1000 / SR), "interrupted": interrupted or piece.gen != self.gen})
            prev_utt = msg.get("utt")
            if piece.gen != self.gen:
                continue
            self.pending = max(0, self.pending - 1)
            if self.pending == 0:
                try:
                    await asyncio.wait_for(self.source.wait_for_playout(), timeout=5)
                except asyncio.TimeoutError:
                    pass
                if self.pending == 0 and piece.gen == self.gen:
                    self.speaking = False
                    self.playing_utt = None
                    self.port.send({"ev": "idle"})

    def first_audio(self, msg, t_say):
        utt = msg.get("utt")
        if utt in self.first_done:
            return
        self.first_done.add(utt)
        end = self.ends.get(utt)
        self.port.send({"ev": "first_audio", "utt": utt, "seq": msg.get("seq"),
                        "after_end_ms": int(now_ms() - end) if end else None,
                        "tts_first_ms": int(now_ms() - t_say)})


async def main():
    loop = asyncio.get_running_loop()
    port = Port(loop)
    await Worker(port).run()


if __name__ == "__main__":
    asyncio.run(main())
