package ai.risicloud.risime.voice

import ai.risicloud.risime.data.media.PhoneTranscript
import ai.risicloud.risime.data.media.VoiceEnvelope
import ai.risicloud.risime.data.media.VoiceMeta
import ai.risicloud.risime.net.ApiClient
import ai.risicloud.risime.net.ProtocolJson
import ai.risicloud.risime.net.ReplyRef
import ai.risicloud.risime.ui.settings.PhoneSttReport
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Part 12 quick fix 2 (developer flag `phone_stt`, proposal 2026-10-14-phone-transcript): on-device only, the
 * transcript inside the E2EE envelope, and **parity**: with the flag off the voice-note path is byte-identical.
 */
class PhoneSttTest {
    // ---- the envelope (proposal examples, parity) ----

    private val example = """{"v":1,"type":"voice","blob":{"blob_id":"5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8c","size":49168,"sha256":"q7FnzD0yS2bX5uJ8Kc3mT9vL1aP4eR6wY0hN2iO8gUs="},"enc":{"alg":"A256GCM-S64K","key":"Hc2Tj9wQ1mZ8rV4yK7pL3sX6aN0bF5gD2eU9iO1tR8c=","plain_size":49152},"mime":"audio/ogg; codecs=opus","duration_ms":12400,"waveform":"BAYKEihIbpa0yNK+oHhaRjxQeKrc//DIlm5QPCgeGBQePGSMqr7ItJZ4ZFpujKCWeFo8KBwUDgoIBgUEBAMCAg=="}"""
    private val withPhone = example.dropLast(1) + ""","phone_transcript":{"text":"හෙට උදේ දහයට meeting එක තියෙනවා","lang":"si-LK","engine":"android-on-device"}}"""
    private val badPhone = example.dropLast(1) + ""","phone_transcript":{"text":"hello","engine":"android-on-device"}}"""

    private fun obj(s: String): JsonObject = ProtocolJson.parseToJsonElement(s).jsonObject

    @Test fun withoutTheFieldTheEnvelopeIsByteIdenticalToV146() {
        val e = VoiceEnvelope.validate(obj(example))!!
        assertNull(e.phoneTranscript)
        assertEquals(example, e.encode().toString(Charsets.UTF_8))
        // And the contract's own example file (copied into the test resources) re-encodes to the same bytes.
        javaClass.classLoader!!.getResource("contract/v1/examples/voice_payload.json")?.readText()?.trim()?.let { file ->
            assertEquals(file, VoiceEnvelope.validate(obj(file))!!.encode().toString(Charsets.UTF_8))
        }
    }

    @Test fun storedMetaWithoutTheFieldIsTheV141Json() {
        val m = VoiceMeta(VoiceEnvelope.MIME_OGG_OPUS, 12_400, "AA==")
        assertEquals("""{"mime":"audio/ogg; codecs=opus","duration_ms":12400,"waveform":"AA==","transcribe":null,"request_id":null}""", m.encode())
        assertEquals(m, VoiceMeta.decode(m.encode()))
    }

    @Test fun theProposalExampleRoundTrips() {
        val e = VoiceEnvelope.validate(obj(withPhone))!!
        assertEquals(PhoneTranscript("හෙට උදේ දහයට meeting එක තියෙනවා", "si-LK", "android-on-device"), e.phoneTranscript)
        assertEquals(withPhone, e.encode().toString(Charsets.UTF_8))
        val meta = VoiceMeta.of(e)
        assertEquals(e.phoneTranscript, VoiceMeta.decode(meta.encode())!!.phoneTranscript)
        assertTrue(e.encode().size < ai.risicloud.risime.data.media.ImageEnvelope.MAX_ENVELOPE_BYTES)
    }

    @Test fun aMalformedFieldIsIgnoredAndTheNoteKept() {
        val e = VoiceEnvelope.validate(obj(badPhone))
        assertNotNull("the note is kept", e)
        assertNull(e!!.phoneTranscript)
        val base = obj(example)
        fun with(v: String) = VoiceEnvelope.validate(JsonObject(base + ("phone_transcript" to ProtocolJson.parseToJsonElement(v))))
        assertNull(with("\"text\"")!!.phoneTranscript)
        assertNull(with("""{"text":"","lang":"si-LK","engine":"x"}""")!!.phoneTranscript)
        assertNull(with("""{"text":"a","lang":"si LK","engine":"x"}""")!!.phoneTranscript)
        assertNull(with("""{"text":1,"lang":"si-LK","engine":"x"}""")!!.phoneTranscript)
        assertNull(with("""{"text":"${"a".repeat(4001)}","lang":"si-LK","engine":"x"}""")!!.phoneTranscript)
        // A future engine name is accepted.
        assertEquals("apple-on-device", with("""{"text":"ok","lang":"en-US","engine":"apple-on-device"}""")!!.phoneTranscript!!.engine)
    }

    @Test fun senderTextIsTrimmedAndCapped() {
        assertNull(PhoneTranscript.of("   ", "si-LK"))
        assertNull(PhoneTranscript.of(null, "si-LK"))
        assertEquals("a b", PhoneTranscript.of("  a \n b ", "si-LK")!!.text)
        val long = PhoneTranscript.of("අ".repeat(5000), "si-LK")!!
        assertTrue(long.text.length <= PhoneTranscript.MAX_CHARS && long.text.toByteArray().size <= PhoneTranscript.MAX_BYTES)
        assertNotNull(PhoneTranscript.parse(long.toJson()))
    }

    // ---- on-device only ----

    /**
     * The on-device recogniser has one construction point; the online recogniser (decision 092) has exactly **one**
     * construction site, in OnlineRecogniser.kt, behind OnlineSttGuard in the same function, and nowhere else.
     */
    @Test fun theOnlineRecogniserIsNeverConstructed() {
        val main = listOf(File("src/main"), File("app/src/main"), File("android/app/src/main")).first { it.isDirectory }
        val sources = main.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
        assertTrue(sources.size > 50)
        val online = Regex("""\bcreateSpeechRecognizer\s*\(""")
        val onlineCheck = Regex("""\bisRecognitionAvailable\s*\(""")
        val activity = Regex("""startActivity[A-Za-z]*\s*\([^)]*RecognizerIntent""")
        val actionWebSearch = Regex("""ACTION_WEB_SEARCH|ACTION_VOICE_SEARCH_HANDS_FREE|getVoiceDetailsIntent""")
        val onlineSites = sources.filter { online.containsMatchIn(it.readText()) }
        assertEquals("the online recogniser has one construction site", listOf("OnlineRecogniser.kt"), onlineSites.map { it.name })
        val site = onlineSites.single().readText()
        assertEquals(1, online.findAll(site).count())
        val at = online.find(site)!!.range.first
        val fn = site.lastIndexOf("fun ", at)
        assertTrue("the construction sits inside OnlineRecogniser.session", site.substring(fn, at).startsWith("fun session("))
        val guard = site.indexOf("if (!OnlineSttGuard.allows(request)) return null", fn)
        assertTrue("the guard runs before the construction, in the same function", guard in fn until at)
        for (f in sources) {
            val t = f.readText()
            if (f.name != "OnlineRecogniser.kt") assertFalse("${f.path} constructs the online SpeechRecognizer", online.containsMatchIn(t))
            assertFalse("${f.path} consults the online recogniser", onlineCheck.containsMatchIn(t))
            assertFalse("${f.path} starts a recogniser activity", activity.containsMatchIn(t))
            assertFalse("${f.path} uses an online voice search", actionWebSearch.containsMatchIn(t))
        }
        val creators = sources.filter { it.readText().contains("createOnDeviceSpeechRecognizer(") }
        assertEquals(listOf("AndroidPhoneRecogniser.kt"), creators.map { it.name })
        assertEquals(1, Regex("""createOnDeviceSpeechRecognizer\(""").findAll(creators.single().readText()).count())
    }

    @Test fun precheckNeedsAndroid13AndAnOnDeviceRecogniser() {
        var asked = false
        assertEquals(PhoneSttSupport.NEEDS_ANDROID_13.label, PhoneSttGate.precheck(32) { asked = true; true })
        assertFalse("below API 33 nothing is consulted", asked)
        assertEquals(PhoneSttSupport.NO_RECOGNISER.label, PhoneSttGate.precheck(34) { false })
        assertEquals(PhoneSttSupport.NO_RECOGNISER.label, PhoneSttGate.precheck(34) { error("redroid: no service") })
        assertNull(PhoneSttGate.precheck(34) { true })
    }

    @Test fun supportIsClassifiedFromOnDeviceListsOnly() {
        val installed = listOf("en-US", "si-LK")
        val pending = listOf("ta-IN")
        val supported = listOf("en-US", "si-LK", "ta-IN", "en-IN")
        assertEquals(PhoneSttSupport.INSTALLED to "si-LK", PhoneSttSupport.of("si-LK", installed, pending, supported))
        assertEquals(PhoneSttSupport.INSTALLED to "si-LK", PhoneSttSupport.of("si", installed, pending, supported))
        assertEquals(PhoneSttSupport.PENDING to "ta-IN", PhoneSttSupport.of("ta-LK", installed, pending, supported))
        assertEquals(PhoneSttSupport.INSTALLED to "en-US", PhoneSttSupport.of("en_us", installed, pending, supported))
        assertEquals(PhoneSttSupport.NOT_ON_DEVICE to null, PhoneSttSupport.of("ta-LK", emptyList(), emptyList(), listOf("en-US")))
        assertEquals(PhoneSttSupport.DOWNLOADABLE to "ta-IN", PhoneSttSupport.of("ta-IN", emptyList(), emptyList(), supported))
        // A failed check never claims support.
        assertEquals(PhoneSttSupport.CHECK_FAILED, AndroidPhoneRecogniser.SupportReport(error = "no recogniser").of("si-LK").first)
        assertTrue(PhoneSttErrors.isUnavailable(12) && PhoneSttErrors.isUnavailable(13) && PhoneSttErrors.isNoSpeech(7))
    }

    // ---- audio ----

    @Test fun pcm48kBecomes16kLittleEndian() {
        val c = Pcm48To16()
        assertArrayEquals(ByteArray(0), c.feed(shortArrayOf(300, 300), 2)) // the remainder waits for the next frame
        val out = c.feed(shortArrayOf(300, 1000, 1000, 1000, -2, -4, -6), 7)
        // 300 → 0x012C, 1000 → 0x03E8, (-2-4-6)/3 = -4 → 0xFFFC
        assertArrayEquals(byteArrayOf(0x2C, 0x01, 0xE8.toByte(), 0x03, 0xFC.toByte(), 0xFF.toByte()), out)
        assertArrayEquals(ByteArray(0), c.feed(shortArrayOf(-2), 1))
        assertArrayEquals(shortArrayOf(2, 4), PcmShape.mono(shortArrayOf(1, 3, 3, 5), 2))
        assertEquals(48_000, PcmShape.to48k(ShortArray(16_000), 16_000).size)
        val same = ShortArray(10)
        assertTrue(PcmShape.to48k(same, 48_000) === same)
    }

    @Test fun theRecorderTapGetsEveryFrameAndNeverFailsTheRecording() = runBlocking {
        val frames = 60
        val src = object : PcmSource {
            override val rate = 48_000
            var n = 0
            override fun start() = true
            override fun read(buf: ShortArray): Int {
                if (n >= frames) { Thread.sleep(5); return 0 }
                n++
                buf.fill(1000)
                return buf.size
            }
            override fun stop() = Unit
        }
        var fed = 0
        var ends = 0
        val tap = object : PcmTap {
            override fun feed(frame: ShortArray, count: Int) { fed += count; error("a broken tap") }
            override fun end() { ends++ }
        }
        val rec = VoiceRecorder(newEncoder = { CountingEncoder() }, newSource = { src })
        assertTrue(rec.start(tap))
        val until = System.currentTimeMillis() + 5_000
        while (src.n < frames && System.currentTimeMillis() < until) Thread.sleep(5)
        Thread.sleep(20)
        val note = rec.stop()
        assertNotNull("a throwing tap doesn't fail the note", note)
        assertEquals(frames * 960, fed)
        assertEquals(1, ends)
    }

    private class CountingEncoder : VoiceEncoder {
        var fed = 0
        override fun feed(pcm: ShortArray, n: Int) { fed += n }
        override fun finish(): ByteArray = "OggS".toByteArray() + ByteArray(fed / 960)
        override fun release() = Unit
    }

    // ---- the flag and the send path ----

    private class MapPrefs : SharedPreferences {
        val m = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = m
        override fun getString(key: String, defValue: String?) = m[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = m[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = m[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = m[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = m[key] as? Boolean ?: defValue
        override fun contains(key: String) = key in m
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { m[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { m[key] = values }
            override fun putInt(key: String, value: Int) = apply { m[key] = value }
            override fun putLong(key: String, value: Long) = apply { m[key] = value }
            override fun putFloat(key: String, value: Float) = apply { m[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { m[key] = value }
            override fun remove(key: String) = apply { m.remove(key) }
            override fun clear() = apply { m.clear() }
            override fun commit() = true
            override fun apply() = Unit
        }
    }

    @Test fun theFlagIsOffByDefaultAndOnlyForDevelopers() {
        val p = MapPrefs()
        val f = PhoneSttFlag(p)
        assertFalse(f.on.value)
        f.set(true)
        assertFalse("a non-developer can't turn it on", f.on.value)
        f.setDeveloper(true)
        assertFalse("being a developer alone doesn't turn it on", f.on.value)
        f.set(true)
        assertTrue(f.on.value)
        assertTrue(PhoneSttFlag(p).on.value) // remembered
        f.setDeveloper(false)
        assertFalse("losing risi_dev turns it off", f.on.value)
        assertFalse(PhoneSttFlag(p).on.value)
        assertFalse(PhoneSttFlag(null).on.value)
        assertEquals("si-LK", f.locale.value)
    }

    @Test fun policyKeepsTheRisiChatAndOptOutsWithout() {
        assertFalse(PhoneSttPolicy.allowed(false, VoicePlace.PRIVATE, null))
        assertTrue(PhoneSttPolicy.allowed(true, VoicePlace.PRIVATE, null))
        assertTrue(PhoneSttPolicy.allowed(true, VoicePlace.OFFICIAL, null))
        assertFalse(PhoneSttPolicy.allowed(true, VoicePlace.OFFICIAL, false))
        assertTrue("the server compares and falls back to it", PhoneSttPolicy.allowed(true, VoicePlace.RISI_CHAT, null))
        assertEquals(2_000, PhoneSttPolicy.waitMs(0))
        assertEquals(3_240, PhoneSttPolicy.waitMs(12_400))
        assertEquals(8_000, PhoneSttPolicy.waitMs(1_800_000))
    }

    private class FakeSession(val result: PhoneSttResult?, val starts: Boolean = true) : PhoneSttSessionHandle {
        var cancelled = false
        override fun start() = starts
        override suspend fun await(timeoutMs: Long) = result
        override fun cancel() { cancelled = true }
        override fun feed(frame: ShortArray, count: Int) = Unit
        override fun end() = Unit
    }

    private fun notes(flag: PhoneSttFlag?, place: VoicePlace, sessions: PhoneSttSessions?, committed: MutableList<VoiceMeta>) = VoiceNotes(
        ApiClient(OkHttpClient(), { "http://127.0.0.1:9" }, { null }), null,
        commit = { _, _, meta, _ -> committed += meta; null },
        placeOf = { place }, mediaReady = { true }, recorder = VoiceRecorder(), phoneStt = flag, phoneSessions = sessions,
    )

    private fun note() = RecordedNote(ByteArray(10) { 1 }, 12_400, ByteArray(64) { 5 })

    private fun onFlag() = PhoneSttFlag(MapPrefs()).apply { setDeveloper(true); set(true) }

    /** Parity: with the flag off (or absent) no session is opened and the committed meta is exactly v1.41's. */
    @Test fun flagOffSendsTheV141NoteUnchanged() = runBlocking {
        for (place in VoicePlace.entries) for (flag in listOf(null, PhoneSttFlag(MapPrefs()))) {
            var opened = 0
            val committed = mutableListOf<VoiceMeta>()
            val v = notes(flag, place, { opened++; FakeSession(null) }, committed)
            assertNull(v.phoneSttSession("grp:x"))
            val n = note()
            val expected = VoiceStamp.meta(n, place, true) { "id" }
            v.send("grp:x", n, null as ReplyRef?)
            assertEquals(0, opened)
            val got = committed.single()
            assertNull(got.phoneTranscript)
            assertEquals(expected.copy(requestId = got.requestId).encode(), got.encode())
            assertFalse(got.encode().contains("phone_transcript"))
        }
    }

    @Test fun flagOnAttachesThePhoneTextInsideTheNote() = runBlocking {
        val committed = mutableListOf<VoiceMeta>()
        val s = FakeSession(PhoneSttResult.Text(" හෙට meeting ", "si-LK", 900, 0.8f))
        val v = notes(onFlag(), VoicePlace.PRIVATE, { s }, committed)
        val h = v.phoneSttSession("dm:x")
        assertTrue(h === s)
        v.send("dm:x", note(), null, h)
        assertEquals(PhoneTranscript("හෙට meeting", "si-LK"), committed.single().phoneTranscript)
        assertTrue(s.cancelled)
    }

    @Test fun flagOnButNotOnDeviceSaysSoAndSendsWithout() = runBlocking {
        val committed = mutableListOf<VoiceMeta>()
        val notices = mutableListOf<String>()
        val v = notes(onFlag(), VoicePlace.OFFICIAL, { FakeSession(null, starts = false) }, committed)
        assertNull(v.phoneSttSession("grp:x") { notices += it })
        assertTrue(notices.single().contains("isn't available on-device"))
        val later = notes(onFlag(), VoicePlace.OFFICIAL, { FakeSession(PhoneSttResult.NotAvailable("si-LK: language pack not installed")) }, committed)
        later.send("grp:x", note(), null, later.phoneSttSession("grp:x")) { notices += it }
        assertNull(committed.single().phoneTranscript)
        assertEquals(2, notices.size)
        // Timeout / no speech: sent without, silently.
        notes(onFlag(), VoicePlace.OFFICIAL, null, committed).send("grp:x", note(), null, FakeSession(null))
        assertNull(committed.last().phoneTranscript)
    }

    @Test fun officialAndRisiChatNotesCarryThePhoneText() = runBlocking {
        for (place in listOf(VoicePlace.OFFICIAL, VoicePlace.RISI_CHAT)) {
            val committed = mutableListOf<VoiceMeta>()
            val v = notes(onFlag(), place, { FakeSession(PhoneSttResult.Text("හෙට", "si-LK", 500, null)) }, committed)
            v.send("grp:x", note(), null, v.phoneSttSession("grp:x"))
            assertEquals(PhoneTranscript("හෙට", "si-LK"), committed.single().phoneTranscript)
            if (place == VoicePlace.RISI_CHAT) assertNotNull("the request_id stays", committed.single().requestId)
        }
    }

    @Test fun sinhalaHeadlineIsClear() {
        assertEquals("Sinhala (si-LK) offline: YES, installed", PhoneSttReport.sinhalaHeadline(PhoneSttSupport.INSTALLED, "si-LK"))
        assertTrue(PhoneSttReport.sinhalaHeadline(PhoneSttSupport.DOWNLOADABLE, "si-LK").contains("Download Sinhala"))
        assertEquals("Sinhala (si-LK): online only on this phone (no offline pack)", PhoneSttReport.sinhalaHeadline(PhoneSttSupport.NOT_ON_DEVICE, null))
        assertEquals("Tamil (ta-LK): online only on this phone (no offline pack)", PhoneSttReport.headline("Tamil", "ta-LK", PhoneSttSupport.NOT_ON_DEVICE, null))
    }

    @Test fun reportLinesShowTextAndTime() {
        assertEquals("si-LK · 1.8 s (0.15× real time) · conf 0.82: හෙට", PhoneSttReport.line(PhoneSttResult.Text("හෙට", "si-LK", 1_800, 0.82f), 12_000))
        assertEquals("not available: no on-device recogniser on this phone", PhoneSttReport.line(PhoneSttResult.NotAvailable(PhoneSttSupport.NO_RECOGNISER.label), 1))
        assertEquals("ta-LK: on-device, installed (as ta-IN)", PhoneSttReport.support("ta-LK", PhoneSttSupport.INSTALLED, "ta-IN"))
    }
}
