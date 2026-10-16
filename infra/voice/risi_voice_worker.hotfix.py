"""risi-voice: Risi's media worker for "Call Risi" (decision 090).

A thin media bridge (infrastructure, no decisions): it joins one LiveKit room as Risi, cleans the
caller's audio (WebRTC APM echo cancellation against Risi's own outgoing audio + high-pass, then
RNNoise), finds the caller's turns (Silero VAD + end-of-turn silence), hands each finished utterance
to the Elixir Voice agent, and speaks what the agent tells it to (Kokoro via risime-tts on loopback),
stopping at once on barge-in. Everything is in memory; nothing is written to disk or logged.

It runs as an Erlang Port of `RisiMe.Agent.CallRisi.Session` ({:packet, 4}: a 4-byte big-endian
length, then one JSON object) and dies with it (stdin EOF -> leave and exit).

Commands (Elixir -> worker):
  {"cmd": "join", "url", "token", "caller", "tts_url", "tts_key"?, "eot_ms"?, "barge_ms"?, "min_speech_ms"?}
  {"cmd": "say", "seq", "utt", "text", "voice", "speed"?}     queue a sentence for utterance `utt`
  {"cmd": "stop_speaking"}                                    drop everything queued and playing
  {"cmd": "cal_req", "id", "from", "to"}     contract v1.52 §41.8: ask the caller's phone for its events
  {"cmd": "stt_mode", "mode": "phone"|"server"}  contract v1.53 §41.10: who recognises the caller's speech
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
  {"ev": "stt_mode", "mode"}                                 the mode was published to the phone (v1.53)
  {"ev": "stt", "t": "final", "seq", "text", "conf", "during_risi"}   the phone's words (v1.53 §41.10)
  {"ev": "stt", "t": "off", "reason"}                         the phone stopped recognising (v1.53 §41.10)
  {"ev": "error", "code"}

The caller's speech recognised on the phone (contract v1.53 §41.10): `stt_mode` publishes
{"t": "mode", "mode"} reliably on topic "risi.stt" to the caller only and acks with
{"ev": "stt_mode", "mode"} ("server" when it could not be sent). While the mode is "phone" the
worker publishes {"t": "speaking", "on": true|false} when Risi's own audio starts and ends, and
forwards the caller's `final` and `off` packets on "risi.stt" (partials are dropped). The phone's
text passes through memory once and is never logged or written anywhere.

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
CAL_TOPIC = "risi.cal"  # contract v1.52 §41.8
CAL_MAX_BYTES = 64 * 1024  # 100 events with 200-char titles fit well within this
CAL_PENDING_MAX = 8
CAL_REASONS = ("no_permission", "no_calendars", "off", "error")
STT_TOPIC = "risi.stt"  # contract v1.53 §41.10
STT_MAX_BYTES = 4096
STT_MAX_TEXT = 1000
STT_OFF_REASONS = ("unavailable", "error", "busy", "language")


def stt_event(payload):
    """The Port event for a phone packet on "risi.stt" (bytes), or None to drop it."""
    if not isinstance(payload, (bytes, bytearray)) or len(payload) > STT_MAX_BYTES:
        return None
    try:
        msg = json.loads(bytes(payload).decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return None
    if not isinstance(msg, dict):
        return None
    t = msg.get("t")
    if t == "final":
        text, conf, seq = msg.get("text"), msg.get("conf"), msg.get("seq")
        if not isinstance(text, str) or not text.strip():
            return None
        if conf is not None and (isinstance(conf, bool) or not isinstance(conf, (int, float))
                                 or not 0 <= conf <= 1):
            return None
        return {"ev": "stt", "t": "final", "seq": seq if isinstance(seq, int) else None,
                "text": text[:STT_MAX_TEXT], "conf": conf,
                "during_risi": msg.get("during_risi") is True}
    if t == "off":
        reason = msg.get("reason")
        return {"ev": "stt", "t": "off", "reason": reason if reason in STT_OFF_REASONS else "error"}
    return None  # partials and anything else


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
        self.say_q = asyncio.Queue()
        self.play_task = None
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
        self.stt_phone = False  # v1.53 §41.10: the phone recognises the caller's speech
        self.stt_speaking = False  # the last `speaking` state sent to the phone

    # ---- control

    async def run(self):
        while True:
            msg = await self.port.inbox.get()
            cmd = msg.get("cmd")
            try:
                if cmd == "join":
                    await self.join(msg)
                elif cmd == "say":
                    self.say_q.put_nowait((self.gen, msg))
                elif cmd == "stop_speaking":
                    self.stop_speaking()
                elif cmd == "cal_req":
                    await self.cal_req(msg)
                elif cmd == "stt_mode":
                    await self.stt_mode(msg)
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
        # Only the caller's replies on "risi.cal" to a request this worker sent, and (v1.53) the
        # caller's "risi.stt" finals/off while the phone mode is on; drop the rest.
        topic = getattr(packet, "topic", None)
        if topic not in (CAL_TOPIC, STT_TOPIC):
            return
        p = getattr(packet, "participant", None)
        if p is None or self.caller is None or not p.identity.startswith(self.caller):
            return
        if topic == STT_TOPIC:
            if not self.stt_phone:
                return
            ev = stt_event(packet.data)
            if ev is None:
                return
            if ev["t"] == "off":
                self.stt_phone = False
            self.port.send(ev)
            return
        ev = cal_event(packet.data, self.cal_pending)
        if ev is None:
            return
        self.cal_pending.pop(ev["id"], None)
        self.port.send(ev)

    # ---- the caller's speech recognised on the phone (contract v1.53 §41.10)

    async def stt_publish(self, packet):
        to = self.caller_identity()
        if to is None or self.room is None:
            return False
        try:
            await self.room.local_participant.publish_data(
                json.dumps(packet, separators=(",", ":")), reliable=True,
                destination_identities=[to], topic=STT_TOPIC)
            return True
        except Exception:
            return False

    async def stt_mode(self, msg):
        mode = msg.get("mode")
        if mode not in ("phone", "server"):
            return
        ok = await self.stt_publish({"t": "mode", "mode": mode})
        self.stt_phone = ok and mode == "phone"
        self.stt_speaking = False
        if self.stt_phone and self.speaking:
            self.set_speaking(True)
        self.port.send({"ev": "stt_mode", "mode": "phone" if self.stt_phone else "server"})

    def set_speaking(self, on):
        # Tells the phone when Risi's own audio starts and ends (its finals then carry
        # `during_risi`). Only in phone mode, only on a change; never blocks the audio path.
        if not self.stt_phone or on == self.stt_speaking:
            return
        self.stt_speaking = on
        asyncio.ensure_future(self.stt_publish({"t": "speaking", "on": on}))

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
        while not self.say_q.empty():
            self.say_q.get_nowait()
        if self.source is not None:
            self.source.clear_queue()
        self.speaking = False
        self.set_speaking(False)

    async def player(self):
        rs = None
        while True:
            gen, msg = await self.say_q.get()
            if gen != self.gen:
                continue
            self.speaking = True
            self.playing_utt = msg.get("utt")
            t_say = now_ms()
            sent = 0
            first = True
            interrupted = False
            rs = soxr.ResampleStream(24000, SR, 1, dtype="int16")
            rest = np.zeros(0, dtype=np.int16)
            try:
                headers = {"Authorization": "Bearer " + self.cfg["tts_key"]} if self.cfg["tts_key"] else {}
                body = {"text": msg["text"], "voice": msg.get("voice") or "", "speed": msg.get("speed") or 1.0}
                async with self.http.post(self.cfg["tts_url"] + "/v1/tts", json=body, headers=headers) as r:
                    if r.status != 200:
                        self.port.send({"ev": "error", "code": "tts_%d" % r.status})
                        continue
                    async for chunk in r.content.iter_chunked(4800):
                        if gen != self.gen:
                            interrupted = True
                            break
                        pcm = np.frombuffer(chunk[: len(chunk) // 2 * 2], dtype=np.int16)
                        up = np.concatenate([rest, rs.resample_chunk(pcm)])
                        n = len(up) // FRAME * FRAME
                        up, rest = up[:n], up[n:]
                        for i in range(0, n, FRAME):
                            if gen != self.gen:
                                interrupted = True
                                break
                            frame = rtc.AudioFrame(up[i:i + FRAME].tobytes(), SR, 1, FRAME)
                            self.apm.process_reverse_stream(frame)
                            if first:
                                first = False
                                self.set_speaking(True)
                                self.first_audio(msg, t_say)
                            await self.source.capture_frame(frame)
                            t_out = now_ms()
                            self.speak_until = t_out + TAIL_MS
                            seg = np.frombuffer(frame.data, dtype=np.int16).astype(np.float32)
                            self.out_levels.append((t_out, float(np.sqrt(np.mean(seg ** 2)))))
                            while self.out_levels and self.out_levels[0][0] < t_out - 1500:
                                self.out_levels.popleft()
                            sent += FRAME
                        if interrupted:
                            break
            except (aiohttp.ClientError, asyncio.TimeoutError):
                self.port.send({"ev": "error", "code": "tts_unreachable"})
            self.port.send({"ev": "said", "seq": msg.get("seq"), "utt": msg.get("utt"),
                            "ms": int(sent * 1000 / SR), "interrupted": interrupted or gen != self.gen})
            if self.say_q.empty() and gen == self.gen:
                try:
                    await asyncio.wait_for(self.source.wait_for_playout(), timeout=5)
                except asyncio.TimeoutError:
                    pass
                if self.say_q.empty() and gen == self.gen:
                    self.speaking = False
                    self.playing_utt = None
                    self.set_speaking(False)
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
