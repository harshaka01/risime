package ai.risicloud.risime.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proposal 2026-10-14-voice-translate (v1.50 §37.13): the proposal's examples decode (they move to
 * contract/v1/examples when root applies it), "Wrong language?" builds its action from the note's own blob/enc and
 * hides the current language, a `relang` transcript replaces the earlier one, the Translate choices and the typed-message
 * rule, and the `always_translate` setting round trip. Every text is synthetic.
 */
class VoiceTranslate150Test {
    private val exAction = """{"v":1,"type":"risi_action","action":"transcript_language","target":"d1e2f3a4-a6f2-11f1-8000-0242ac120002","edit":null,"language":"ta","voice":{"blob":{"blob_id":"5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8c","size":49168,"sha256":"q7FnzD0yS2bX5uJ8Kc3mT9vL1aP4eR6wY0hN2iO8gUs="},"enc":{"alg":"A256GCM-S64K","key":"Hc2Tj9wQ1mZ8rV4yK7pL3sX6aN0bF5gD2eU9iO1tR8c=","plain_size":49152},"duration_ms":12400}}"""
    private val exRelang = """{"v":1,"type":"text","body":"Voice note from Kumar (0:12): நான் நாளைக்கு காலையில வாறன், அந்த ரிப்போர்ட்ட முடிச்சு அனுப்புறன்","risi":{"v":1,"kind":"voice_transcript","conversation_id":"grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b","message_id":"d1e2f3a4-a6f2-11f1-8000-0242ac120002","speaker":"7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3","duration_ms":12400,"status":"ok","text":"நான் நாளைக்கு காலையில வாறன், அந்த ரிப்போர்ட்ட முடிச்சு அனுப்புறன்","language":"ta","model":"tamil-asr-qwen3","relang":{"from":"si","to":"ta","by":"0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"},"call_ref":null,"made_by":{"model":"tamil-asr-qwen3","provider":"risime","at":"2026-10-14T04:12:00.000Z","also":[]},"notify":[]}}"""
    private val exRequest = """{"conversation_id":"grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b","message_id":"d1e2f3a4-a6f2-11f1-8000-0242ac120002","target":"en"}"""
    private val exReply = """{"conversation_id":"grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b","message_id":"d1e2f3a4-a6f2-11f1-8000-0242ac120002","target":"en","source_language":"ta","text":"I'll come tomorrow morning, finish that report and send it.","label":"Translated by Risi","made_by":{"model":"risi-l1","provider":"risime","at":"2026-10-14T04:12:30.000Z","also":[]}}"""
    private val exPut = """{"transcribe":true,"action_items":true,"always_translate":"en"}"""
    private val exVoiceReply = """{"transcribe":true,"action_items":true,"always_translate":"en","translate_available":true}"""

    private fun obj(s: String) = ProtocolJson.parseToJsonElement(s).jsonObject

    @Test
    fun `the examples decode`() {
        val t = RisiVoiceTranscript.parse(obj(exRelang)["risi"].toString())
        assertNotNull(t)
        assertEquals(RisiRelang("si", "ta", "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"), t!!.relang)
        assertEquals("ta", t.language)
        assertTrue(t.ok)

        val req = ProtocolJson.decodeFromString(TranslateRequest.serializer(), exRequest)
        assertEquals("en", req.target)
        assertEquals(obj(exRequest), ProtocolJson.encodeToJsonElement(TranslateRequest.serializer(), req))

        val reply = ProtocolJson.decodeFromString(TranslateReply.serializer(), exReply)
        assertEquals("Translated by Risi", reply.label)
        assertEquals("ta", reply.sourceLanguage)

        val put = ProtocolJson.decodeFromString(RisiVoiceSettings.serializer(), exPut)
        assertEquals("en", put.alwaysTranslate)
        assertEquals(obj(exPut), ProtocolJson.encodeToJsonElement(RisiVoiceSettings.serializer(), put))
        val r = ProtocolJson.decodeFromString(RisiVoiceSettings.serializer(), exVoiceReply)
        assertTrue(r.translateAvailable)

        // The action is a valid Risi control (the phone sends it like transcript_delete).
        assertTrue(ai.risicloud.risime.data.tabs.RisiControl.valid(obj(exAction)))
    }

    @Test
    fun `the v1_41 settings shape is unchanged when the new fields are absent`() {
        val s = RisiVoiceSettings(transcribe = false, actionItems = true)
        assertEquals(obj("""{"transcribe":false,"action_items":true}"""), ProtocolJson.encodeToJsonElement(RisiVoiceSettings.serializer(), s))
        val old = ProtocolJson.decodeFromString(RisiVoiceSettings.serializer(), """{"transcribe":true,"action_items":true}""")
        assertFalse(old.translateAvailable)
        assertNull(old.alwaysTranslate)
    }

    @Test
    fun `wrong language builds the action from the note's own blob and enc`() {
        val note = obj(
            """{"v":1,"type":"voice","blob":{"blob_id":"5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8c","size":49168,"sha256":"q7FnzD0yS2bX5uJ8Kc3mT9vL1aP4eR6wY0hN2iO8gUs="},"enc":{"alg":"A256GCM-S64K","key":"Hc2Tj9wQ1mZ8rV4yK7pL3sX6aN0bF5gD2eU9iO1tR8c=","plain_size":49152},"mime":"audio/ogg; codecs=opus","duration_ms":12400,"waveform":"AAAA","phone_transcript":{"text":"x","lang":"si-LK","engine":"e"}}""",
        )
        val a = VoiceTranslate150.transcriptLanguageAction("d1e2f3a4-a6f2-11f1-8000-0242ac120002", "ta", note)
        assertEquals(obj(exAction), a)
        // Nothing but blob, enc and duration travels (no waveform, no phone transcript).
        assertEquals(setOf("blob", "enc", "duration_ms"), (a!!["voice"] as JsonObject).keys)
        assertNull(VoiceTranslate150.transcriptLanguageAction("x", "fr", note))
        assertNull(VoiceTranslate150.transcriptLanguageAction("x", "ta", obj("""{"type":"voice"}""")))
    }

    @Test
    fun `the choices hide the current language`() {
        assertEquals(listOf("ta", "en"), VoiceTranslate150.choices("si"))
        assertEquals(listOf("si", "en"), VoiceTranslate150.choices("ta"))
        assertEquals(listOf("ta", "si"), VoiceTranslate150.choices("en"))
        assertEquals(listOf("ta", "si", "en"), VoiceTranslate150.choices(null))
        assertEquals("Translate to Tamil", VoiceTranslate150.translateLabel("ta"))
        assertEquals("Re-transcribed in Tamil", VoiceTranslate150.relangLine(RisiRelang("si", "ta")))
    }

    @Test
    fun `a relang transcript replaces the earlier one`() {
        val first = RisiVoiceTranscript(kind = RisiKinds141.VOICE_TRANSCRIPT, messageId = "n", status = "ok", text = "නාන් වාරන්", language = "si")
        val second = first.copy(text = "நான் வாறன்", language = "ta", relang = RisiRelang("si", "ta", "u"))
        assertEquals(second, VoiceTranslate150.current(listOf(10L to first, 20L to second)))
        // Even when the relang row's local time is earlier (arrival order differs).
        assertEquals(second, VoiceTranslate150.current(listOf(30L to first, 20L to second)))
        assertEquals(first, VoiceTranslate150.current(listOf(10L to first)))
        assertNull(VoiceTranslate150.current(emptyList()))
    }

    @Test
    fun `typed messages get Translate only with Sinhala or Tamil letters`() {
        assertTrue(VoiceTranslate150.typedTranslatable("நான் நாளைக்கு வாறன்"))
        assertTrue(VoiceTranslate150.typedTranslatable("මම හෙට එනවා"))
        assertFalse(VoiceTranslate150.typedTranslatable("mama heta enawa"))
        assertFalse(VoiceTranslate150.typedTranslatable(""))
        assertEquals("ta", VoiceTranslate150.textLanguage("நான் ok"))
        assertEquals("si", VoiceTranslate150.textLanguage("හරි ok"))
    }

    @Test
    fun `always translate targets only another language`() {
        assertEquals("en", VoiceTranslate150.autoTarget("en", "ta"))
        assertNull(VoiceTranslate150.autoTarget("en", "en"))
        assertNull(VoiceTranslate150.autoTarget("off", "ta"))
        assertNull(VoiceTranslate150.autoTarget(null, "ta"))
    }

    @Test
    fun `the translations live only in memory`() {
        val store = ai.risicloud.risime.voice.VoiceTranslations()
        assertNull(store.state("N", "en"))
        assertEquals("n:en", store.key("N", "en"))
        store.clear()
        assertTrue(store.states.value.isEmpty())
    }

    @Test
    fun `the action reaches the json unchanged`() {
        val a = obj(exAction)
        assertEquals("transcript_language", a["action"]!!.jsonPrimitive.content)
    }
}
