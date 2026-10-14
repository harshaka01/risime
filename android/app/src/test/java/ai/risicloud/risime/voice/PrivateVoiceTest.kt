package ai.risicloud.risime.voice

import ai.risicloud.risime.data.media.VoiceEnvelope
import ai.risicloud.risime.data.media.VoiceMeta
import ai.risicloud.risime.net.ProtocolJson
import ai.risicloud.risime.net.User
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Decision 092 (proposal 2026-10-14-private-transcribe): Private transcription and translation. The mandatory
 * guarantees: no Private audio reaches the online recogniser, and no Private text reaches the server, without the
 * reader's yes event; "No" sends nothing; the sender's opt-out blocks Google; on-device English never touches the
 * network; the flag off changes nothing (parity).
 */
class PrivateVoiceTest {
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

    /** Records every recogniser use; the online one also records whether the guard allowed the request. */
    private class FakeStt(
        var installed: Map<String, String> = mapOf("en" to "en-US"),
        var onlineText: String = "හෙට උදේ දහයට",
        var onDeviceText: String = "see you at ten",
    ) : ReaderStt {
        val onDeviceCalls = mutableListOf<String>()
        val onlineCalls = mutableListOf<Pair<String, OnlineSttRequest>>()

        override suspend fun onDeviceTag(lang: String) = installed[lang]

        override suspend fun onDevice(tag: String, pcm: ShortArray, durationMs: Long): PhoneSttResult {
            onDeviceCalls += tag
            return PhoneSttResult.Text(onDeviceText, tag, 10, null)
        }

        override suspend fun online(tag: String, pcm: ShortArray, durationMs: Long, request: OnlineSttRequest): PhoneSttResult? {
            // The real OnlineRecogniser builds nothing unless the guard allows: mirror it and record only real builds.
            if (!OnlineSttGuard.allows(request)) return null
            onlineCalls += tag to request
            return PhoneSttResult.Text(onlineText, tag, 10, null)
        }
    }

    private class FakeServer : PrivateTranslator {
        val sent = mutableListOf<Triple<String, String, String?>>()
        override suspend fun translate(text: String, target: String, source: String?): Result<String> {
            sent += Triple(text, target, source)
            return Result.success("[$target] $text")
        }
    }

    private class World(flag: Boolean = true) {
        var flagOn = flag
        val prefs = MapPrefs()
        val consent = PrivateConsent(prefs)
        val stt = FakeStt()
        val server = FakeServer()
        val decoded = mutableListOf<String>()
        val ctl = PrivateVoiceController({ flagOn }, consent, stt, server, decode = { decoded += it; ShortArray(4800) }, prefs = prefs)

        /** Nothing left the phone: no online recogniser build, no server request. */
        fun nothingSent() = stt.onlineCalls.isEmpty() && server.sent.isEmpty()
    }

    private fun note(key: String = "n1", phoneLang: String? = null, allows: Boolean = true, sender: String = "kamal") =
        PrivateNoteRef(key, sender, 6_000, phoneLang, allows)

    // ---- the flag ----

    @Test fun flagOffChangesNothing() = runBlocking {
        val w = World(flag = false)
        w.prefs.m["private_voice_lang_kamal"] = "en"
        assertEquals(PrivateStep.Nothing, w.ctl.auto(note()))
        assertEquals(PrivateStep.Nothing, w.ctl.request(note(), "si"))
        assertEquals(PrivateStep.Nothing, w.ctl.answer(note(), "si", yes = true, dontAskAgain = true))
        assertEquals(PrivateStep.Nothing, w.ctl.translate("m1", "hello", "si", null))
        assertEquals(PrivateStep.Nothing, w.ctl.answerTranslate("m1", "hello", "si", null, yes = true, dontAskAgain = false))
        assertTrue(w.nothingSent())
        assertTrue(w.stt.onDeviceCalls.isEmpty() && w.decoded.isEmpty())
        assertTrue(w.ctl.transcripts.value.isEmpty())
    }

    @Test fun theFlagComesFromMeAndIsAbsentByDefault() = runBlocking {
        val u = ProtocolJson.decodeFromString(User.serializer(), """{"id":"u","phone":"+94","display_name":"A","company":"C"}""")
        assertFalse(u.privateTranscribe)
        assertTrue(ProtocolJson.decodeFromString(User.serializer(), """{"id":"u","phone":"+94","display_name":"A","company":"C","private_transcribe":true}""").privateTranscribe)
        var t = 0L
        var fetches = 0
        val f = PrivateTranscribeFlag(MapPrefs()) { t }
        assertFalse(f.on.value)
        f.refreshIfStale { fetches++; true }
        assertTrue(f.on.value)
        t += 60_000
        f.refreshIfStale { fetches++; false }
        assertEquals("at most every 10 minutes", 1, fetches)
        t += 600_000
        f.refreshIfStale { fetches++; null } // unreachable: the cached value stays
        assertTrue(f.on.value)
        f.refreshIfStale { fetches++; false }
        assertEquals(2, fetches)
    }

    // ---- 1. English on this phone ----

    @Test fun englishIsAutomaticOnDeviceAndNeverTouchesTheNetwork() = runBlocking {
        val w = World()
        // No hint yet: nothing runs by itself.
        assertEquals(PrivateStep.Nothing, w.ctl.auto(note()))
        assertTrue(w.decoded.isEmpty())
        // The reader picks English once: on this phone at once, no dialog.
        assertEquals(PrivateStep.Started, w.ctl.request(note(), "en"))
        assertEquals(listOf("en-US"), w.stt.onDeviceCalls)
        val done = w.ctl.transcripts.value["n1"] as ReaderTranscript.Done
        assertEquals(PrivateEngine.ON_DEVICE, done.engine)
        assertEquals("Transcript (on this phone)", done.engine.label)
        // The next note from the same sender is automatic.
        assertEquals(PrivateStep.Started, w.ctl.auto(note("n2")))
        assertEquals(2, w.stt.onDeviceCalls.size)
        assertTrue("nothing left the phone", w.nothingSent())
    }

    @Test fun anEnglishPhoneTranscriptHintIsUsedButAnExistingSenderTranscriptIsNotRedone() = runBlocking {
        val w = World()
        assertEquals(PrivateStep.Nothing, w.ctl.auto(note(phoneLang = "en-US")))
        assertEquals("en", w.ctl.hint(note(phoneLang = "en-IN")))
        assertTrue(w.stt.onDeviceCalls.isEmpty())
    }

    @Test fun withoutOnDeviceEnglishTheSameYesNoIsAsked() = runBlocking {
        val w = World()
        w.stt.installed = emptyMap()
        w.prefs.m["private_voice_lang_kamal"] = "en"
        assertEquals(PrivateStep.Nothing, w.ctl.auto(note()))
        assertEquals(PrivateStep.NeedsConsent(ConsentKind.GOOGLE_TRANSCRIBE, englishOnline = true), w.ctl.request(note(), "en"))
        assertTrue(w.nothingSent() && w.decoded.isEmpty())
    }

    // ---- 2. Sinhala / Tamil with Google, after the yes ----

    @Test fun noYesNoGoogle_andNoSendsNothing() = runBlocking {
        val w = World()
        assertEquals(PrivateStep.NeedsConsent(ConsentKind.GOOGLE_TRANSCRIBE), w.ctl.request(note(), "si"))
        assertTrue("asking sends nothing and decodes nothing", w.nothingSent() && w.decoded.isEmpty())
        assertEquals(PrivateStep.Nothing, w.ctl.answer(note(), "si", yes = false, dontAskAgain = true))
        assertTrue("No sends nothing", w.nothingSent() && w.decoded.isEmpty())
        assertFalse("No never remembers anything", w.consent.isRemembered(ConsentKind.GOOGLE_TRANSCRIBE))
        assertNull(w.ctl.transcripts.value["n1"])
    }

    @Test fun yesRunsGoogleFromThisPhoneOnceAndSaysSo() = runBlocking {
        val w = World()
        w.ctl.request(note(), "si")
        assertEquals(PrivateStep.Started, w.ctl.answer(note(), "si", yes = true, dontAskAgain = false))
        assertEquals(1, w.stt.onlineCalls.size)
        val (tag, req) = w.stt.onlineCalls.single()
        assertEquals("si-LK", tag)
        assertTrue(OnlineSttGuard.allows(req))
        val done = w.ctl.transcripts.value["n1"] as ReaderTranscript.Done
        assertEquals("Transcript (Google)", done.engine.label)
        assertTrue("the transcript is never sent to our server", w.server.sent.isEmpty())
        // A yes is per action: the next note asks again.
        assertEquals(PrivateStep.NeedsConsent(ConsentKind.GOOGLE_TRANSCRIBE), w.ctl.request(note("n2"), "si"))
        assertEquals(1, w.stt.onlineCalls.size)
    }

    @Test fun dontAskAgainIsRememberedAndResettable() = runBlocking {
        val w = World()
        w.ctl.answer(note(), "ta", yes = true, dontAskAgain = true)
        assertTrue(w.consent.isRemembered(ConsentKind.GOOGLE_TRANSCRIBE))
        w.stt.onlineText = "நாளை காலை பத்து மணிக்கு"
        assertEquals(PrivateStep.Started, w.ctl.request(note("n2"), "ta"))
        assertEquals(2, w.stt.onlineCalls.size)
        assertTrue(PrivateConsent(w.prefs).isRemembered(ConsentKind.GOOGLE_TRANSCRIBE)) // survives a restart
        w.consent.reset(ConsentKind.GOOGLE_TRANSCRIBE)
        assertEquals(PrivateStep.NeedsConsent(ConsentKind.GOOGLE_TRANSCRIBE), w.ctl.request(note("n3"), "ta"))
        assertEquals(2, w.stt.onlineCalls.size)
        // Each kind is separate: the translate choice is untouched.
        assertFalse(w.consent.isRemembered(ConsentKind.SERVER_TRANSLATE))
    }

    // ---- 4. the sender's opt-out ----

    @Test fun theSendersOptOutBlocksGoogleEvenWithDontAskAgain() = runBlocking {
        val w = World()
        w.consent.remember(ConsentKind.GOOGLE_TRANSCRIBE)
        val opted = note(allows = false)
        assertTrue(w.ctl.request(opted, "si") is PrivateStep.Blocked)
        assertTrue(w.ctl.answer(opted, "si", yes = true, dontAskAgain = true) is PrivateStep.Blocked)
        assertTrue(w.ctl.transcripts.value["n1"] is ReaderTranscript.Blocked)
        assertTrue("no Google build, no audio decoded", w.stt.onlineCalls.isEmpty() && w.decoded.isEmpty())
        // English on this phone still works (nothing leaves it).
        assertEquals(PrivateStep.Started, w.ctl.request(opted, "en"))
        assertEquals(1, w.stt.onDeviceCalls.size)
        // Text derived from that note can't go to our server either.
        assertTrue(w.ctl.translate("n1:reader", "see you", "si", "en", allowed = false) is PrivateStep.Blocked)
        assertEquals(PrivateStep.Nothing, w.ctl.answerTranslate("n1:reader", "see you", "si", "en", yes = true, dontAskAgain = false, allowed = false))
        assertTrue(w.nothingSent())
    }

    @Test fun theGuardNeedsTheFlagTheYesForThisNoteAndTheSendersAllow() {
        val consent = PrivateConsent(MapPrefs())
        val yes = ConsentYes.fromDialog(ConsentKind.GOOGLE_TRANSCRIBE, "n1")
        assertFalse(OnlineSttGuard.allows(null))
        assertTrue(OnlineSttGuard.allows(OnlineSttRequest.PrivateNote(true, yes, "n1", true)))
        assertFalse("no yes", OnlineSttGuard.allows(OnlineSttRequest.PrivateNote(true, null, "n1", true)))
        assertFalse("flag off", OnlineSttGuard.allows(OnlineSttRequest.PrivateNote(false, yes, "n1", true)))
        assertFalse("sender opted out", OnlineSttGuard.allows(OnlineSttRequest.PrivateNote(true, yes, "n1", false)))
        assertFalse("another note's yes", OnlineSttGuard.allows(OnlineSttRequest.PrivateNote(true, yes, "n2", true)))
        val translateYes = ConsentYes.fromDialog(ConsentKind.SERVER_TRANSLATE, "n1")
        assertFalse("a translate yes isn't a Google yes", OnlineSttGuard.allows(OnlineSttRequest.PrivateNote(true, translateYes, "n1", true)))
        assertNull("no remembered choice, no yes", ConsentYes.fromRemembered(consent, ConsentKind.GOOGLE_TRANSCRIBE, "n1"))
        consent.remember(ConsentKind.GOOGLE_TRANSCRIBE)
        assertTrue(OnlineSttGuard.allows(OnlineSttRequest.PrivateNote(true, ConsentYes.fromRemembered(consent, ConsentKind.GOOGLE_TRANSCRIBE, "n1"), "n1", true)))
    }

    // ---- the script check (never Tamil in Sinhala script) ----

    @Test fun neverTamilInSinhalaScript_theOtherLanguageIsOffered() = runBlocking {
        val w = World()
        w.ctl.answer(note(), "ta", yes = true, dontAskAgain = false) // Google answers in Sinhala script
        val mm = w.ctl.transcripts.value["n1"] as ReaderTranscript.Mismatch
        assertEquals("ta", mm.chosen)
        assertEquals("si", mm.seen)
        assertEquals("si", PrivateLangs.other("ta"))
        w.stt.onlineText = "நாளை காலை"
        w.ctl.clear("n1")
        w.ctl.answer(note(), "si", yes = true, dontAskAgain = false)
        assertEquals("ta", (w.ctl.transcripts.value["n1"] as ReaderTranscript.Mismatch).seen)
        assertNull(ScriptCheck.mismatch("si", "හෙට meeting එක"))
        assertNull("Singlish in Latin letters is fine", ScriptCheck.mismatch("si", "heta meeting eka"))
        assertNull(ScriptCheck.mismatch("en", "see you"))
        assertEquals("si", ScriptCheck.mismatch("en", "හෙට උදේ"))
        assertEquals("ta", ScriptCheck.dominant("நாளை"))
    }

    // ---- 3. translate on our server, after the yes ----

    @Test fun translateAsksFirst_noSendsNothing_yesSendsOnlyTheText() = runBlocking {
        val w = World()
        assertEquals(PrivateStep.NeedsConsent(ConsentKind.SERVER_TRANSLATE), w.ctl.translate("m1", " see you at ten ", "si", null))
        assertTrue(w.server.sent.isEmpty())
        assertEquals(PrivateStep.Nothing, w.ctl.answerTranslate("m1", "see you at ten", "si", null, yes = false, dontAskAgain = true))
        assertTrue("No sends nothing", w.server.sent.isEmpty())
        assertEquals(PrivateStep.Started, w.ctl.answerTranslate("m1", " see you at ten ", "si", "en", yes = true, dontAskAgain = false))
        assertEquals(listOf(Triple("see you at ten", "si", "en")), w.server.sent)
        val done = w.ctl.translations.value[w.ctl.translationKey("m1", "si")] as ReaderTranslation.Done
        assertEquals("[si] see you at ten", done.text)
        assertEquals("Translation (RisiMe server)", PrivateEngine.SERVER.label)
        // Per action: another message asks again, until "Don't ask again".
        assertTrue(w.ctl.translate("m2", "ok", "ta", null) is PrivateStep.NeedsConsent)
        w.ctl.answerTranslate("m2", "ok", "ta", null, yes = true, dontAskAgain = true)
        assertEquals(PrivateStep.Started, w.ctl.translate("m3", "fine", "en", "si"))
        assertEquals(3, w.server.sent.size)
        assertTrue("translating never builds the online recogniser", w.stt.onlineCalls.isEmpty())
    }

    // ---- 4. allow_transcribe in the E2EE envelope ----

    private val example = """{"v":1,"type":"voice","blob":{"blob_id":"5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8c","size":49168,"sha256":"q7FnzD0yS2bX5uJ8Kc3mT9vL1aP4eR6wY0hN2iO8gUs="},"enc":{"alg":"A256GCM-S64K","key":"Hc2Tj9wQ1mZ8rV4yK7pL3sX6aN0bF5gD2eU9iO1tR8c=","plain_size":49152},"mime":"audio/ogg; codecs=opus","duration_ms":12400,"waveform":"BAYKEihIbpa0yNK+oHhaRjxQeKrc//DIlm5QPCgeGBQePGSMqr7ItJZ4ZFpujKCWeFo8KBwUDgoIBgUEBAMCAg=="}"""
    private val optedOut = example.dropLast(1) + ""","allow_transcribe":false}"""

    private fun obj(s: String): JsonObject = ProtocolJson.parseToJsonElement(s).jsonObject

    @Test fun allowTranscribeRoundTripsAndAbsentIsByteIdentical() {
        val plain = VoiceEnvelope.validate(obj(example))!!
        assertNull(plain.allowTranscribe)
        assertTrue(plain.othersMayTranscribe)
        assertEquals("parity: absent stays absent", example, plain.encode().toString(Charsets.UTF_8))
        val e = VoiceEnvelope.validate(obj(optedOut))!!
        assertEquals(false, e.allowTranscribe)
        assertFalse(e.othersMayTranscribe)
        assertEquals(optedOut, e.encode().toString(Charsets.UTF_8))
        assertFalse(VoiceMeta.decode(VoiceMeta.of(e).encode())!!.othersMayTranscribe)
        // The stored meta of a note without the field is the v1.47 JSON (nothing added).
        assertFalse(VoiceMeta.of(plain).encode().contains("allow_transcribe"))
        // `true` is the same as absent; a malformed value is read as the opt-out; the note is always kept.
        fun with(v: String) = VoiceEnvelope.validate(JsonObject(obj(example) + ("allow_transcribe" to ProtocolJson.parseToJsonElement(v))))!!
        assertNull(with("true").allowTranscribe)
        assertEquals(false, with("\"no\"").allowTranscribe)
        assertEquals(false, with("0").allowTranscribe)
        assertEquals(JsonPrimitive(false), obj(optedOut)["allow_transcribe"])
    }

    @Test fun theSenderStampsItOnlyInPrivateAndOnlyWhenOff() {
        assertEquals(false, VoiceStamp.allowTranscribeStamp(VoicePlace.PRIVATE, othersMayTranscribe = false))
        assertNull(VoiceStamp.allowTranscribeStamp(VoicePlace.PRIVATE, othersMayTranscribe = true))
        assertNull(VoiceStamp.allowTranscribeStamp(VoicePlace.OFFICIAL, othersMayTranscribe = false))
        assertNull(VoiceStamp.allowTranscribeStamp(VoicePlace.RISI_CHAT, othersMayTranscribe = false))
        val c = PrivateConsent(MapPrefs())
        assertTrue("default on", c.letOthersTranscribe.value)
        c.setLetOthers(false)
        assertFalse(c.letOthersTranscribe.value)
    }

    // ---- Harsha 10:31: the sender setting in Settings → Privacy, and the one-time notice ----

    @Test fun theSenderSettingIsInPrivacyWithTheGoogleWordingAndDefaultOn() {
        assertEquals("Let others transcribe my voice notes (Sinhala/Tamil go through Google)", PrivateVoiceStrings.SETTING_LET_OTHERS)
        assertTrue(PrivateConsent(MapPrefs()).letOthersTranscribe.value)
        val main = listOf(File("src/main"), File("app/src/main"), File("android/app/src/main")).first { it.isDirectory }
        val settings = main.walkTopDown().first { it.name == "SettingsScreen.kt" }.readText()
        val privacy = settings.indexOf("SectionHeader(\"Privacy\")")
        val switch = settings.indexOf("PrivateSenderSetting(container)")
        assertTrue("the switch sits in the Privacy section", privacy in 0 until switch && switch - privacy < 200)
        val section = main.walkTopDown().first { it.name == "PrivateVoiceSection.kt" }.readText()
        assertTrue(section.contains("fun PrivateSenderSetting") && section.contains("PrivateVoiceStrings.SETTING_LET_OTHERS"))
    }

    @Test fun theSenderNoticeShowsOnceOnTheFirstPrivateNoteAndTapsThroughToSettings() = runBlocking {
        val prefs = MapPrefs()
        val c = PrivateConsent(prefs)
        assertFalse("not while the feature is off", c.senderNoticeDue(flagOn = false, VoicePlace.PRIVATE))
        assertFalse("not in Official", c.senderNoticeDue(flagOn = true, VoicePlace.OFFICIAL))
        assertFalse("not in the Risi chat", c.senderNoticeDue(flagOn = true, VoicePlace.RISI_CHAT))
        assertTrue(c.senderNoticeDue(flagOn = true, VoicePlace.PRIVATE))
        c.senderNoticeShown()
        assertFalse("once only", c.senderNoticeDue(flagOn = true, VoicePlace.PRIVATE))
        assertFalse("survives a restart", PrivateConsent(prefs).senderNoticeDue(flagOn = true, VoicePlace.PRIVATE))
        assertEquals("Others can transcribe your voice notes. Change in Settings", PrivateVoiceStrings.SENDER_NOTICE)
        // [Settings] opens Settings through the app's navigation bus.
        val bus = ai.risicloud.risime.data.tabs.RisiUiBus()
        val got = async(Dispatchers.Unconfined) { bus.nav.first() }
        bus.openSettings()
        assertEquals(ai.risicloud.risime.data.tabs.RisiUiBus.Nav.Settings, got.await())
        val main = listOf(File("src/main"), File("app/src/main"), File("android/app/src/main")).first { it.isDirectory }
        val composer = main.walkTopDown().first { it.name == "VoiceComposer.kt" }.readText()
        assertTrue(composer.contains("c.privateConsent.senderNoticeDue(") && composer.contains("c.risiUi.openSettings()"))
        val root = main.walkTopDown().first { it.name == "RisiMeRoot.kt" }.readText()
        assertTrue(root.contains("RisiUiBus.Nav.Settings -> nav.navigate(\"settings\")"))
    }

    // ---- the sources: on-device English never touches the network ----

    @Test fun theOnDevicePathHasNoNetworkAndThePrivateLogicHasNoBackDoor() {
        val main = listOf(File("src/main"), File("app/src/main"), File("android/app/src/main")).first { it.isDirectory }
        fun src(name: String) = main.walkTopDown().first { it.name == name }.readText()
        val logic = src("PrivateVoice.kt")
        for (bad in listOf("okhttp", "ApiClient", "import android.speech", "java.net", "HttpURLConnection")) {
            assertFalse("PrivateVoice.kt must not reach $bad directly", logic.contains(bad))
        }
        val stt = src("OnlineRecogniser.kt")
        val onDevice = stt.substringAfter("override suspend fun onDevice(").substringBefore("override suspend fun online(")
        assertTrue("on-device runs the on-device session", onDevice.contains("phone.session(tag)"))
        assertFalse("on-device never uses the online recogniser", onDevice.contains("online."))
        // The on-device recogniser asks for offline recognition.
        assertTrue(src("AndroidPhoneRecogniser.kt").contains("EXTRA_PREFER_OFFLINE, !online"))
        // The online construction runs only through the guard and the reader's request.
        assertTrue(stt.contains("phone.sessionWith(tag) { SpeechRecognizer.createSpeechRecognizer(context) }"))
    }
}
