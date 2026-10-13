package ai.risicloud.risime.voice

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Part 12 quick fix 2: the "Phone (on-device)" speech candidate. Android's **on-device** recogniser
 * (`SpeechRecognizer.createOnDeviceSpeechRecognizer`, the Android System Intelligence / Gboard engine) transcribes the
 * sender's own voice note before encryption (proposal 2026-10-14-phone-transcript). **On-device only**: this app never
 * constructs the online recogniser and never falls back to it; a language without an installed on-device pack is
 * reported as such and the note goes without a phone transcript.
 *
 * This file holds the platform-free logic (unit-tested on the JVM); [AndroidPhoneRecogniser] talks to the platform.
 */
object PhoneSttLocales {
    /** A language Harsha compares, with the locale tag the recogniser is asked for. */
    data class Choice(val tag: String, val label: String)

    /** Send-path choices (the developer picks one). Singlish = Sinhala with English mixed: the si-LK pack. */
    val CHOICES = listOf(
        Choice("si-LK", "Sinhala / Singlish (si-LK)"),
        Choice("ta-LK", "Tamil (ta-LK)"),
        Choice("ta-IN", "Tamil (ta-IN)"),
        Choice("en-US", "English (en-US)"),
        Choice("en-IN", "English (en-IN)"),
    )

    /** Every tag the availability check reports (si, Singlish, ta, en and their usual variants). */
    val PROBES = listOf("si-LK", "si", "ta-LK", "ta-IN", "ta", "en-LK", "en-IN", "en-US", "en-GB")

    const val DEFAULT = "si-LK"

    /**
     * The tag to run with for [wanted] among the [installed] on-device languages: the exact tag (case-insensitive,
     * `_` = `-`), else the same language with another region (e.g. "ta-IN" for "ta-LK"), else null.
     */
    fun match(wanted: String, installed: Collection<String>): String? {
        fun norm(s: String) = s.replace('_', '-').lowercase()
        val w = norm(wanted)
        installed.firstOrNull { norm(it) == w }?.let { return it }
        val lang = w.substringBefore('-')
        return installed.firstOrNull { norm(it).substringBefore('-') == lang }
    }
}

/** What the on-device recogniser can do for one locale (never consults the online list for a decision). */
enum class PhoneSttSupport(val label: String) {
    INSTALLED("on-device, installed"),
    PENDING("on-device, downloading"),
    DOWNLOADABLE("on-device pack available (not installed)"),
    NOT_ON_DEVICE("not available on-device"),
    NO_RECOGNISER("no on-device recogniser on this phone"),
    NEEDS_ANDROID_13("needs Android 13 or newer"),
    CHECK_FAILED("couldn't check"),
    ;

    companion object {
        /** Classifies [tag] from the RecognitionSupport lists (the online list is deliberately ignored). */
        fun of(tag: String, installed: List<String>, pending: List<String>, supportedOnDevice: List<String>): Pair<PhoneSttSupport, String?> {
            PhoneSttLocales.match(tag, installed)?.let { return INSTALLED to it }
            PhoneSttLocales.match(tag, pending)?.let { return PENDING to it }
            PhoneSttLocales.match(tag, supportedOnDevice)?.let { return DOWNLOADABLE to it }
            return NOT_ON_DEVICE to null
        }
    }
}

/** One recognition run (the dev test shows text + time; the send path keeps only the text). */
sealed interface PhoneSttResult {
    data class Text(val text: String, val locale: String, val elapsedMs: Long, val confidence: Float?) : PhoneSttResult

    /** Nothing usable was heard (ERROR_NO_MATCH / an empty final result). */
    data class NoSpeech(val locale: String, val elapsedMs: Long) : PhoneSttResult

    /** The recogniser isn't usable for this locale (honest reason; the online recogniser is never tried). */
    data class NotAvailable(val reason: String) : PhoneSttResult

    data class Failed(val reason: String, val elapsedMs: Long) : PhoneSttResult
}

/** Platform error codes (android.speech.SpeechRecognizer.ERROR_*) as honest words. */
object PhoneSttErrors {
    fun describe(code: Int): String = when (code) {
        1 -> "network timeout"
        2 -> "network error"
        3 -> "audio error"
        4 -> "recogniser error"
        5 -> "client error"
        6 -> "no speech"
        7 -> "no match"
        8 -> "recogniser busy"
        9 -> "microphone permission needed"
        10 -> "too many requests"
        11 -> "recogniser disconnected"
        12 -> "language not supported on-device"
        13 -> "language pack not installed"
        14 -> "can't check support"
        15 -> "can't follow the download"
        else -> "error $code"
    }

    /** ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE: report "not available", not a failure. */
    fun isUnavailable(code: Int) = code == 12 || code == 13

    fun isNoSpeech(code: Int) = code == 6 || code == 7
}

/**
 * 48 kHz mono PCM16 (the recorder and the Opus decoder) → 16 kHz mono PCM16 little-endian bytes (what the recogniser
 * reads from `EXTRA_AUDIO_SOURCE`): a 3-tap box low-pass and decimation by 3. Keeps the remainder between frames.
 */
class Pcm48To16 {
    private var acc = 0
    private var n = 0

    /** Converts [count] samples of [pcm]; returns the bytes to write (possibly empty). */
    fun feed(pcm: ShortArray, count: Int = pcm.size): ByteArray {
        val out = java.io.ByteArrayOutputStream(count / 3 * 2 + 2)
        for (i in 0 until count) {
            acc += pcm[i].toInt()
            if (++n == 3) {
                val s = (acc / 3).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out.write(s and 0xFF)
                out.write((s shr 8) and 0xFF)
                acc = 0
                n = 0
            }
        }
        return out.toByteArray()
    }

    companion object {
        const val OUT_RATE = 16_000
    }
}

/**
 * Taps the recorder's frames (on the recorder thread). Must never block the recorder: an implementation queues the
 * frame and returns at once. [end] is called once when the recording stops or is cancelled.
 */
interface PcmTap {
    fun feed(frame: ShortArray, count: Int)

    fun end()
}

/**
 * The developer flag `phone_stt` (Settings → About → seven taps → Developer; only for `risi_dev` accounts, i.e.
 * the server's RISI_DEV_USERS: Harsha first). Off by default and for everyone else. While it is off nothing in the
 * voice-note path changes (no recogniser, no tap, the same envelope bytes).
 */
class PhoneSttFlag(private val prefs: SharedPreferences?) {
    private val _on = MutableStateFlow(read())

    /** The effective flag: the developer switch on **and** the account was `risi_dev` when it was turned on. */
    val on: StateFlow<Boolean> = _on

    private val _locale = MutableStateFlow(prefs?.getString(KEY_LOCALE, null) ?: PhoneSttLocales.DEFAULT)
    val locale: StateFlow<String> = _locale

    private fun read(): Boolean = (prefs?.getBoolean(KEY_ON, false) ?: false) && (prefs?.getBoolean(KEY_DEV, false) ?: false)

    /** From a fresh `GET /me` in the Developer section; a non-developer account turns the flag off. */
    fun setDeveloper(dev: Boolean) {
        runCatching {
            val e = prefs?.edit() ?: return@runCatching
            e.putBoolean(KEY_DEV, dev)
            if (!dev) e.putBoolean(KEY_ON, false)
            e.apply()
        }
        _on.value = dev && (prefs?.getBoolean(KEY_ON, false) ?: false)
    }

    fun set(on: Boolean) {
        val dev = prefs?.getBoolean(KEY_DEV, false) ?: false
        runCatching { prefs?.edit()?.putBoolean(KEY_ON, on && dev)?.apply() }
        _on.value = on && dev
    }

    fun setLocale(tag: String) {
        _locale.value = tag
        runCatching { prefs?.edit()?.putString(KEY_LOCALE, tag)?.apply() }
    }

    companion object {
        const val NAME = "phone_stt"
        private const val KEY_ON = "dev_phone_stt"
        private const val KEY_DEV = "dev_phone_stt_risi_dev"
        private const val KEY_LOCALE = "dev_phone_stt_locale"
    }
}

/** One running recognition the send path owns (the platform's is [AndroidPhoneRecogniser.Session]). */
interface PhoneSttSessionHandle : PcmTap {
    fun start(): Boolean

    suspend fun await(timeoutMs: Long): PhoneSttResult?

    fun cancel()
}

/** Opens a session for a locale tag (null: none on this phone). */
fun interface PhoneSttSessions {
    fun open(tag: String): PhoneSttSessionHandle?
}

/** Where a phone transcript may be attached (proposal 2026-10-14 sender rules). */
object PhoneSttPolicy {
    /** Not in the Risi chat (Risi transcribes requests), not when the note opts out of transcription. */
    fun allowed(flagOn: Boolean, place: VoicePlace, transcribeStamp: Boolean?): Boolean =
        flagOn && place != VoicePlace.RISI_CHAT && transcribeStamp != false

    /** How long the send waits for the final text after the recording stops (then it goes without one). */
    fun waitMs(durationMs: Long): Long = (2_000 + durationMs / 10).coerceIn(2_000, 8_000)
}

/** The phone-transcript texts (developer feature; English like the rest of the Developer section). */
object PhoneSttStrings {
    fun notAvailable(tag: String) = "Phone transcript: $tag isn't available on-device on this phone. Sent without it."

    const val BLOCK_LABEL = "Transcript (sender's phone)"
}
