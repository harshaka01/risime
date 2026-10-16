package ai.risicloud.risime.calls.risi

import ai.risicloud.risime.calls.transcribe.CallMicTap
import ai.risicloud.risime.net.CallRisiStt153
import ai.risicloud.risime.voice.AndroidPhoneRecogniser
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.time.Duration

/**
 * Contract v1.53 §41.10, the platform engine on Robolectric's recogniser shadow (redroid has no RecognitionService, so
 * this is as close as the gates get): the on-device recogniser is fed the app's pipe at 16 kHz mono PCM16, an on-device
 * recogniser stops after one result so the engine starts the next utterance after every result / "no match" /
 * "no speech", a missing confidence is null, a fatal error sends `off`, and a call capture that delivers no audio at
 * all sends `off` instead of silence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OnDeviceCallSttTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private class Events : CallSttEvents {
        val finals = mutableListOf<Pair<String, Float?>>()
        val offs = mutableListOf<String>()
        val partials = mutableListOf<String>()
        override fun partial(text: String) { partials += text }
        override fun final(text: String, conf: Float?) { finals += text to conf }
        override fun off(reason: String) { offs += reason }
    }

    private lateinit var engine: OnDeviceCallStt
    private val events = Events()

    @Before fun setUp() {
        ShadowSpeechRecognizer.reset()
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        engine = OnDeviceCallStt(AndroidPhoneRecogniser(app))
        engine.start(events)
        idle()
    }

    @After fun tearDown() {
        engine.release()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun recogniser(): SpeechRecognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()!!
    private fun lastIntent(): Intent = shadowOf(recogniser()).lastRecognizerIntent

    private fun results(text: String?, conf: FloatArray?) = Bundle().apply {
        if (text != null) putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        if (conf != null) putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, conf)
    }

    /** The audio keeps coming from the call's capture (so the no-audio check stays quiet). */
    private fun audio() = CallMicTap.onMicrophone(ByteArray(960), 48_000, 1)

    @Test fun listensOnDeviceToThePipeAt16kMono() {
        val i = lastIntent()
        @Suppress("DEPRECATION")
        assertNotNull(i.getParcelableExtra<ParcelFileDescriptor>(RecognizerIntent.EXTRA_AUDIO_SOURCE))
        assertEquals(16_000, i.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 0))
        assertEquals(1, i.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 0))
        assertEquals(android.media.AudioFormat.ENCODING_PCM_16BIT, i.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, 0))
        assertEquals("en-US", i.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(i.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertTrue(i.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        assertNotNull(CallMicTap.sttSink)
    }

    @Test fun restartsAfterEveryResultNoMatchAndNoSpeech() {
        val r = recogniser()
        val first = lastIntent()
        shadowOf(r).triggerOnResults(results("Do I have anything to do today?", floatArrayOf(0f)))
        idle()
        // Missing confidence (0.0 or -1) is null, never a "low" score.
        assertEquals(listOf("Do I have anything to do today?" to null), events.finals)
        val second = lastIntent()
        assertNotSame(first, second)
        assertSame(r, recogniser()) // the same on-device recogniser, a fresh utterance

        shadowOf(r).triggerOnResults(results("And tomorrow?", floatArrayOf(-1f)))
        idle()
        assertNull(events.finals.last().second)
        shadowOf(r).triggerOnResults(results("Thanks", floatArrayOf(0.87f)))
        idle()
        assertEquals(0.87f, events.finals.last().second)
        val third = lastIntent()

        // ERROR_NO_MATCH (7) and ERROR_SPEECH_TIMEOUT (6) over quiet audio: the next utterance, no `off`.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        shadowOf(r).triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        idle()
        val fourth = lastIntent()
        assertNotSame(third, fourth)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        shadowOf(r).triggerOnError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        idle()
        assertNotSame(fourth, lastIntent())
        assertTrue(events.offs.isEmpty())
    }

    @Test fun aFatalErrorSendsOffAndReleases() {
        audio()
        shadowOf(recogniser()).triggerOnError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        idle()
        assertEquals(listOf(CallRisiStt153.OFF_LANGUAGE), events.offs)
        assertTrue(shadowOf(recogniser()).isDestroyed)
        assertNull(CallMicTap.sttSink)
    }

    @Test fun noAudioFromTheCallCaptureSendsOffUnavailable() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
        assertEquals(listOf(CallRisiStt153.OFF_UNAVAILABLE), events.offs)
    }

    @Test fun withAudioThereIsNoNoAudioOff() {
        audio()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
        assertTrue(events.offs.isEmpty())
    }

    @Test fun aRecogniserThatNeverAnswersIsCancelledAndCountsAsAnError() {
        audio()
        // The length cap asks for the result; no answer within 4 s: cancelled, the next utterance starts.
        val first = lastIntent()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(35))
        assertNotSame(first, lastIntent())
        assertTrue(events.offs.isEmpty())
        // A recogniser that never answers keeps failing: after a few in a row, `off`.
        repeat(CallSttHealth.MAX_ERRORS) {
            audio()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(35))
        }
        assertEquals(listOf(CallRisiStt153.OFF_ERROR), events.offs)
    }
}
