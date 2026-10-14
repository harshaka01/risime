package ai.risicloud.risime.voice

import ai.risicloud.risime.voice.PhoneTts.VoiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone TTS developer test: on-device voices only, never network synthesis (docs/status/phone-tts.md). */
class PhoneTtsTest {
    private fun v(name: String, tag: String, net: Boolean = false, notInstalled: Boolean = false, q: Int = 300, lat: Int = 300) =
        VoiceInfo(name, tag, net, notInstalled, q, lat)

    @Test fun networkOnlyVoiceIsRejected() {
        val voices = listOf(v("ta-in-x-tac-network", "ta-IN", net = true))
        assertNull(PhoneTts.pickOnDeviceVoice(voices, "ta"))
        assertEquals("network only (rejected)", PhoneTts.rejection(voices[0]))
    }

    @Test fun notInstalledVoiceIsRejected() {
        val voices = listOf(v("si-lk-x-sfs-local", "si-LK", notInstalled = true))
        assertNull(PhoneTts.pickOnDeviceVoice(voices, "si"))
        assertEquals("voice data not installed", PhoneTts.rejection(voices[0]))
    }

    @Test fun localeMatchSiTaEn() {
        val voices = listOf(
            v("si-lk-x-local", "si-LK"),
            v("ta-lk-x-local", "ta-LK"),
            v("ta-in-x-local", "ta-IN"),
            v("en-gb-x-local", "en_GB"),
            v("en-us-x-local", "en-US"),
            v("hi-in-x-local", "hi-IN"),
        )
        assertEquals("si-lk-x-local", PhoneTts.pickOnDeviceVoice(voices, "si")?.name)
        assertEquals("ta-in-x-local", PhoneTts.pickOnDeviceVoice(voices, "ta")?.name) // ta-IN before ta-LK
        assertEquals("en-us-x-local", PhoneTts.pickOnDeviceVoice(voices, "en")?.name)
        assertEquals(2, PhoneTts.voicesFor(voices, PhoneTts.lang("ta")!!).size)
    }

    @Test fun onDevicePreferredOverNetworkEvenWithHigherQuality() {
        val voices = listOf(v("ta-net", "ta-IN", net = true, q = 500), v("ta-local", "ta-LK", q = 300))
        assertEquals("ta-local", PhoneTts.pickOnDeviceVoice(voices, "ta")?.name)
    }

    @Test fun higherQualityWinsWithinALocale() {
        val voices = listOf(v("a", "en-US", q = 300), v("b", "en-US", q = 400))
        assertEquals("b", PhoneTts.pickOnDeviceVoice(voices, "en")?.name)
    }

    @Test fun noneGivesNullAndARefusal() {
        assertNull(PhoneTts.pickOnDeviceVoice(emptyList(), "si"))
        assertNull(PhoneTts.pickOnDeviceVoice(listOf(v("en", "en-US")), "si"))
        assertNull(PhoneTts.pickOnDeviceVoice(listOf(v("en", "en-US")), "xx"))
        assertEquals("No on-device voice for Sinhala", PhoneTts.refusal("si"))
    }

    @Test fun linesNeverCarryText() {
        val line = PhoneTts.result("ta", "ta-local", 310)
        assertEquals("Tamil · ta-local · first audio 0.31 s", line)
        assertTrue(PhoneTts.line(v("x", "en-US")).endsWith("on-device"))
    }
}
