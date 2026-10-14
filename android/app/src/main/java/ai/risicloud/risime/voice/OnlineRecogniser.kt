package ai.risicloud.risime.voice

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decision 092: **the one construction site of Android's online recogniser** (Google's, on most phones). It is built
 * only after [OnlineSttGuard.allows] said yes: for a Private note that means the feature flag, the reader's yes event
 * for this note (or their "Don't ask again") and the sender's `allow_transcribe`. The audio goes from this phone to
 * the recogniser service, never via RisiMe's server. A unit test scans the sources: this file holds the only online
 * construction, behind the guard.
 */
class OnlineRecogniser(private val context: Context, private val phone: AndroidPhoneRecogniser) {
    /** A started session for [tag], or null when the guard refuses [request] or the phone can't (Android 13+). */
    fun session(tag: String, request: OnlineSttRequest): PhoneSttSessionHandle? {
        if (!OnlineSttGuard.allows(request)) return null
        if (Build.VERSION.SDK_INT < PhoneSttGate.MIN_SDK) return null
        return phone.sessionWith(tag) { SpeechRecognizer.createSpeechRecognizer(context) }
    }
}

/**
 * The platform side of [PrivateVoiceController]: the on-device recogniser for English, the guarded online one for
 * Sinhala / Tamil. The on-device support check is cached for 10 minutes.
 */
class AndroidReaderStt(private val phone: AndroidPhoneRecogniser, private val online: OnlineRecogniser) : ReaderStt {
    @Volatile private var report: Pair<Long, AndroidPhoneRecogniser.SupportReport>? = null

    override suspend fun onDeviceTag(lang: String): String? {
        val now = System.currentTimeMillis()
        val r = report?.takeIf { now - it.first < 600_000 }?.second ?: phone.supportReport().also { report = now to it }
        if (r.error != null) return null
        return PhoneSttLocales.match(PrivateLangs.tag(lang), r.installed)
    }

    override suspend fun onDevice(tag: String, pcm: ShortArray, durationMs: Long): PhoneSttResult =
        run(phone.session(tag), pcm, durationMs)

    override suspend fun online(tag: String, pcm: ShortArray, durationMs: Long, request: OnlineSttRequest): PhoneSttResult? {
        val s = withContext(Dispatchers.Main) { online.session(tag, request) } ?: return null
        return run(s, pcm, durationMs)
    }

    private suspend fun run(s: PhoneSttSessionHandle, pcm: ShortArray, durationMs: Long): PhoneSttResult {
        if (!s.start()) return s.await(1_000) ?: PhoneSttResult.NotAvailable(PrivateVoiceStrings.NOT_AVAILABLE)
        withContext(Dispatchers.Default) {
            var i = 0
            while (i < pcm.size) {
                val n = minOf(960, pcm.size - i)
                s.feed(pcm.copyOfRange(i, i + n), n)
                i += n
            }
            s.end()
        }
        return s.await(durationMs + 60_000) ?: PhoneSttResult.Failed("no result in time", durationMs + 60_000)
    }
}
