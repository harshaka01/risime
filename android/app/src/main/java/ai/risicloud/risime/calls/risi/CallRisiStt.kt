package ai.risicloud.risime.calls.risi

import ai.risicloud.risime.net.CallRisiStt153

/**
 * Contract v1.53 §41.10: the caller's English speech recognised **on the phone** during a Call Risi. The pure rules and
 * the per-call controller live here (unit-tested); the platform recogniser is [CallSttEngine] (OnDeviceCallStt.kt, the
 * on-device recogniser fed from the call's own microphone capture; never a networked recogniser).
 */
object CallRisiSttRules {
    /** `EXTRA_AUDIO_SOURCE` (feeding the recogniser the call's captured audio) needs API 33. */
    const val MIN_SDK = 33

    /** At most 5 partials per second. */
    const val PARTIAL_MIN_GAP_MS = 200L

    /**
     * §41.10: `call_risi_stt` is advertised only with `call_risi`, on API 33+ (the call's audio can be fed to the
     * recogniser) and when the phone has an on-device recogniser ([onDeviceAvailable] is consulted only then).
     */
    fun advertise(callRisi: Boolean, sdk: Int, onDeviceAvailable: () -> Boolean): Boolean =
        callRisi && sdk >= MIN_SDK && runCatching(onDeviceAvailable).getOrDefault(false)

    /**
     * The recogniser's confidence for the top result (`CONFIDENCE_SCORES[0]`), or null when it gives none. Recognisers
     * that don't score report -1 **or 0.0**; either one sent as-is would make Risi answer "Sorry, I didn't catch that."
     * to every clear sentence (conf < 0.4), so both mean "no confidence".
     */
    fun conf(scores: FloatArray?): Float? = scores?.firstOrNull()?.takeIf { !it.isNaN() && it > 0f }?.coerceAtMost(1f)
}

/**
 * The phone's own end-of-speech and "is it hearing anything" check over the 16 kHz mono PCM16 it feeds the recogniser
 * (§41.10; Harsha's first call 2026-10-16 03:55Z got no `final` and no `off` for 25 s).
 * - **Endpointing:** a recogniser reading `EXTRA_AUDIO_SOURCE` may not end an utterance on silence the way it does on
 *   its own microphone (it can read until the stream ends), so the phone ends it: once [MIN_VOICED_MS] of speech was
 *   followed by [HANGOVER_MS] of quiet, [feed] answers true (once per utterance); the engine then stops listening and
 *   closes the stream, and the recogniser gives its final.
 * - **Heard speech:** [heardSpeech] tells a recogniser that returned no words over clear speech ("deaf": not reading
 *   the app's audio, or reading a silent microphone of its own) from a quiet caller.
 * Pure (JVM-tested); one instance per utterance stream, fed by one thread.
 */
class CallSttEndpointer(
    private val speechRms: Int = SPEECH_RMS,
    private val minVoicedMs: Int = MIN_VOICED_MS,
    private val hangoverMs: Int = HANGOVER_MS,
) {
    /** Voiced audio (20-ms blocks over the threshold) in this utterance so far. */
    @Volatile var voicedMs = 0
        private set
    private var silenceMs = 0
    private var fired = false
    private var n = 0
    private var sumSq = 0.0

    /** The utterance had clear speech (enough voiced audio that a working recogniser returns words). */
    val heardSpeech: Boolean get() = voicedMs >= CLEAR_SPEECH_MS

    /** Feeds 16 kHz mono PCM16 LE; true exactly once, when the speech of this utterance has ended. */
    fun feed(pcm: ByteArray, length: Int = pcm.size): Boolean {
        var end = false
        var i = 0
        val len = length.coerceAtMost(pcm.size)
        while (i + 1 < len) {
            val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toDouble()
            sumSq += s * s
            if (++n == BLOCK) {
                val rms = kotlin.math.sqrt(sumSq / BLOCK)
                if (rms >= speechRms) {
                    voicedMs += BLOCK_MS
                    silenceMs = 0
                } else if (voicedMs > 0) {
                    silenceMs += BLOCK_MS
                }
                if (!fired && voicedMs >= minVoicedMs && silenceMs >= hangoverMs) {
                    fired = true
                    end = true
                }
                n = 0
                sumSq = 0.0
            }
            i += 2
        }
        return end
    }

    companion object {
        const val BLOCK = 320 // 20 ms at 16 kHz
        const val BLOCK_MS = 20

        /** About -40 dBFS: the call's capture is after WebRTC's noise suppression and AGC. */
        const val SPEECH_RMS = 330
        const val MIN_VOICED_MS = 240
        const val HANGOVER_MS = 900

        /** The server's "clear speech" (§41.10 fallback rule, PhoneStt.clear_speech_ms). */
        const val CLEAR_SPEECH_MS = 400
    }
}

/**
 * Whether the recogniser works at all, utterance by utterance (pure, JVM-tested). [ended] answers the `off` reason when
 * recognition should stop for the call, so Risi's server takes over at once (an `off`) instead of after its own
 * 3 silent misses:
 * - [MAX_DEAF] utterances in a row with clear speech on the call's audio and no words → `error`;
 * - [MAX_ERRORS] recogniser errors in a row other than "no speech"/"no match" (slow ones too) → `error`.
 */
class CallSttHealth {
    private var deaf = 0
    private var errors = 0

    /**
     * One utterance ended: with [words] (a final with text) or without; [heardSpeech] from [CallSttEndpointer];
     * [error] the recogniser's error code when it ended with one.
     */
    fun ended(words: Boolean, heardSpeech: Boolean, error: Int? = null): String? {
        if (words) {
            deaf = 0
            errors = 0
            return null
        }
        if (heardSpeech) deaf++ else if (error == null || error == NO_SPEECH || error == NO_MATCH) deaf = 0
        if (error != null && error != NO_SPEECH && error != NO_MATCH) errors++ else errors = 0
        return if (deaf >= MAX_DEAF || errors >= MAX_ERRORS) CallRisiStt153.OFF_ERROR else null
    }

    companion object {
        const val MAX_DEAF = 2
        const val MAX_ERRORS = 6
        private const val NO_SPEECH = 6
        private const val NO_MATCH = 7
    }
}

/** What the platform recogniser reports (any thread). */
interface CallSttEvents {
    fun partial(text: String)

    /** The end of one utterance: its text (maybe empty: nothing recognised) and the confidence, if any. */
    fun final(text: String, conf: Float?)

    /** Recognition stopped for good (one of [CallRisiStt153.OFF_REASONS]). */
    fun off(reason: String)
}

/**
 * The continuous on-device recogniser of one call: recognises the call's own captured microphone audio until
 * [release], restarting after each final. Implementations never fall back to a networked recogniser.
 */
interface CallSttEngine {
    fun start(events: CallSttEvents)

    /** Stops and releases the recogniser and the audio tap. Idempotent. */
    fun release()
}

/**
 * One Call Risi's §41.10 session. The machine creates it once the room is connected (before publishing the
 * microphone, so an early `mode` is never lost) and [end]s it when the call ends. It sends nothing before Risi's `{"t":"mode","mode":"phone"}`, nothing after [end] or an
 * `off`, and only through [send] (the call machine publishes to `risi_identity` only). The recogniser starts on
 * `mode: "phone"` and stops on `mode: "server"`.
 */
class CallRisiStt(
    private val risiIdentity: String,
    /** A new engine, or null when the phone has no on-device recogniser now (→ `off`, `unavailable`). */
    private val engine: () -> CallSttEngine?,
    /** Publishes one `risi.stt` packet to `risi_identity` (reliable). Called outside the lock, never blocks. */
    private val send: (ByteArray) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private var ended = false
    private var phone = false
    private var off = false
    private var duringRisi = false
    private var running: CallSttEngine? = null
    private var seq = 0L
    private var open = false
    private var lastPartialAt = Long.MIN_VALUE

    /** The recogniser is running (tests and the debug line). */
    val listening: Boolean get() = synchronized(lock) { running != null }

    /** A data packet of the call: only `risi.stt` from Risi's identity counts; anything else is ignored. */
    fun onData(topic: String?, from: String?, payload: ByteArray) {
        if (topic != CallRisiStt153.TOPIC) return
        if (from == null || !CallRisiRules.allowedRemote(from, risiIdentity)) return
        val msg = CallRisiStt153.parseIncoming(payload) ?: return
        var start = false
        var stop: CallSttEngine? = null
        synchronized(lock) {
            if (ended) return
            when (msg) {
                is CallRisiStt153.Incoming.Speaking -> duringRisi = msg.on
                is CallRisiStt153.Incoming.Mode -> if (msg.mode == CallRisiStt153.MODE_PHONE) {
                    if (!phone && !off) {
                        phone = true
                        start = true
                    }
                } else {
                    phone = false
                    stop = running
                    running = null
                }
            }
        }
        stop?.let { runCatching { it.release() } }
        if (start) startEngine()
    }

    private fun startEngine() {
        val e = runCatching { engine() }.getOrNull()
        if (e == null) return events.off(CallRisiStt153.OFF_UNAVAILABLE)
        val go = synchronized(lock) {
            if (ended || !phone || off) false else {
                running = e
                true
            }
        }
        if (!go) {
            runCatching { e.release() }
            return
        }
        log("risi stt: on-device recognition on")
        runCatching { e.start(events) }.onFailure { events.off(CallRisiStt153.OFF_ERROR) }
    }

    private val events = object : CallSttEvents {
        override fun partial(text: String) {
            val t = text.trim()
            if (t.isEmpty()) return
            val bytes = synchronized(lock) {
                if (ended || !phone || off) return
                if (!open) {
                    open = true
                    seq++
                }
                val at = now()
                if (lastPartialAt != Long.MIN_VALUE && at - lastPartialAt < CallRisiSttRules.PARTIAL_MIN_GAP_MS) return
                lastPartialAt = at
                CallRisiStt153.partial(seq, t)
            }
            send(bytes)
        }

        override fun final(text: String, conf: Float?) {
            val t = text.trim()
            val bytes = synchronized(lock) {
                val wasOpen = open
                open = false
                lastPartialAt = Long.MIN_VALUE
                if (ended || !phone || off) return
                // An utterance with no recognised words sends nothing.
                if (t.isEmpty()) return
                if (!wasOpen) seq++
                CallRisiStt153.final(seq, t, conf, duringRisi)
            }
            send(bytes)
        }

        override fun off(reason: String) {
            val (bytes, stop) = synchronized(lock) {
                if (ended || off) return
                off = true
                val r = running
                running = null
                // Before `mode: "phone"` (or after `server`) nothing is sent: the server's speech-to-text is in use.
                (if (phone) CallRisiStt153.off(reason) else null) to r
            }
            log("risi stt: off ($reason)")
            stop?.let { runCatching { it.release() } }
            bytes?.let(send)
        }
    }

    /** The call ended: release the recogniser; nothing is sent from now on. Idempotent. */
    fun end() {
        val r = synchronized(lock) {
            if (ended) return
            ended = true
            running.also { running = null }
        }
        r?.let { runCatching { it.release() } }
    }
}

/**
 * The call's captured PCM16 little-endian (any rate, 1 or 2 channels; the WebRTC capture is usually 48 kHz mono) →
 * 16 kHz mono PCM16 little-endian, what the recogniser reads from `EXTRA_AUDIO_SOURCE`. A box low-pass over each output
 * period (repeats the sample when the input is slower than 16 kHz). Keeps its state between frames.
 */
class PcmTo16k {
    private var acc = 0L
    private var n = 0
    private var phase = 0L

    fun feed(data: ByteArray, length: Int, rate: Int, channels: Int): ByteArray {
        if (rate <= 0 || channels !in 1..2) return EMPTY
        val frameBytes = 2 * channels
        val frames = length.coerceAtMost(data.size) / frameBytes
        val out = java.io.ByteArrayOutputStream(frames * 2 * OUT_RATE / rate + 4)
        for (f in 0 until frames) {
            var s = 0
            for (ch in 0 until channels) {
                val i = f * frameBytes + ch * 2
                s += ((data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)).toShort().toInt()
            }
            acc += s / channels
            n++
            phase += OUT_RATE
            if (phase >= rate) {
                val v = (acc / n).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                acc = 0
                n = 0
                while (phase >= rate) {
                    phase -= rate
                    out.write(v and 0xFF)
                    out.write((v shr 8) and 0xFF)
                }
            }
        }
        return out.toByteArray()
    }

    companion object {
        const val OUT_RATE = 16_000
        private val EMPTY = ByteArray(0)
    }
}
