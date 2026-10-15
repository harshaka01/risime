package ai.risicloud.risime.calls.risi

import android.content.SharedPreferences
import ai.risicloud.risime.calls.GroupCallEnvelope
import ai.risicloud.risime.calls.GroupCallLines
import ai.risicloud.risime.data.mls.DeviceRegistrar
import ai.risicloud.risime.data.mls.FakeMlsEngine
import ai.risicloud.risime.data.mls.MlsEngine
import ai.risicloud.risime.net.ApiClient
import ai.risicloud.risime.net.CallErrors
import ai.risicloud.risime.net.CallsRoomReply
import ai.risicloud.risime.net.CallsRoomRequest
import ai.risicloud.risime.net.ProtocolJson
import ai.risicloud.risime.net.User
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proposal 2026-10-14-call-risi (decision 090) on the phone: the developer flag, the button rule, the
 * capability (and its parity: flag off = the v1.47 list), the wire fields (absent unless a Call Risi), the
 * history line, the error texts and the `call_transcript` card (the §36.5 line of the same name is untouched).
 */
class CallRisiTest {
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
        val f = CallRisiFlag(p)
        assertFalse(f.on.value)
        f.set(true)
        assertFalse("a non-developer can't turn it on", f.on.value)
        f.setDeveloper(true)
        assertFalse(f.on.value)
        f.set(true)
        assertTrue(f.on.value)
        assertTrue(CallRisiFlag(p).on.value)
        f.setDeveloper(false)
        assertFalse("losing risi_dev turns it off", f.on.value)
        assertFalse(CallRisiFlag(p).on.value)
        assertFalse(CallRisiFlag(null).on.value)
        assertFalse(f.explained())
        f.setExplained()
        assertTrue(CallRisiFlag(p).explained())
    }

    @Test fun theButtonNeedsTheUserFlagTheDeveloperFlagAndGroupCalls() {
        assertTrue(CallRisiRules.buttonVisible(userCallRisi = true, flagOn = true, groupCalls = true))
        assertFalse(CallRisiRules.buttonVisible(false, true, true))
        assertFalse(CallRisiRules.buttonVisible(true, false, true))
        assertFalse(CallRisiRules.buttonVisible(true, true, false))
        assertTrue(CallRisiRules.allowedRemote("U-RISI/D1", "u-risi/d1"))
        assertFalse(CallRisiRules.allowedRemote("u-b/b1", "u-risi/d1"))
    }

    /** v1.48 §41.1 (Harsha 2026-10-14): the Risi contact sheet follows the button's rules, for an existing Risi chat only. */
    @Test fun theContactSheetFollowsTheButtonRulesForAnExistingRisiChat() {
        val visible = CallRisiRules.buttonVisible(userCallRisi = true, flagOn = true, groupCalls = true)
        assertTrue(CallRisiRules.contactSheet(visible, "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"))
        // The flag off (or any rule false) → no sheet: the avatar stays as in v1.47.
        assertFalse(CallRisiRules.contactSheet(CallRisiRules.buttonVisible(true, false, true), "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"))
        assertFalse(CallRisiRules.contactSheet(CallRisiRules.buttonVisible(false, true, true), "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"))
        // The "Risi" entry before the Risi chat exists, or anything that isn't a grp:.
        assertFalse(CallRisiRules.contactSheet(visible, null))
        assertFalse(CallRisiRules.contactSheet(visible, "dm:a:b"))
    }

    @Test fun userCallRisiIsOptional() {
        val base = """{"id":"u1","phone":"+94770000000","display_name":"A","company":"C"}"""
        assertFalse(ProtocolJson.decodeFromString(User.serializer(), base).callRisi)
        assertTrue(ProtocolJson.decodeFromString(User.serializer(), base.dropLast(1) + ""","call_risi":true}""").callRisi)
    }

    @Test fun startBodyCarriesRisiCallOnlyForACallRisi() {
        val normal = ProtocolJson.encodeToString(CallsRoomRequest.serializer(), CallsRoomRequest("grp:x", "c1", "audio", "start"))
        assertEquals("""{"conversation_id":"grp:x","call_id":"c1","media":"audio","action":"start"}""", normal)
        val risi = ProtocolJson.encodeToString(CallsRoomRequest.serializer(), CallsRoomRequest("grp:x", "c1", "audio", "start", risiCall = true))
        assertEquals("""{"conversation_id":"grp:x","call_id":"c1","media":"audio","action":"start","risi_call":true}""", risi)
    }

    @Test fun theReplyParsesRisiCall() {
        val j = """{"url":"wss://x/livekit","room":"r","identity":"u/d","token":"t","expires_at":"2026-10-14T09:10:00.000Z","max_participants":2,
            "risi_call":{"state":"joining","e2ee":false,"risi_identity":"ur/dr","max_s":1800,"lang":"en"}}"""
        val r = ProtocolJson.decodeFromString(CallsRoomReply.serializer(), j)
        assertEquals("ur/dr", r.risiCall!!.risiIdentity)
        assertFalse(r.risiCall!!.e2ee)
        assertEquals(1800L, r.risiCall!!.maxS)
        assertNull(ProtocolJson.decodeFromString(CallsRoomReply.serializer(), j.substringBefore(",\n") + "}").risiCall)
    }

    @Test fun groupCallEnvelopeWithIsAbsentUnlessRisi() {
        val id = "3c8e1f4a-9b2d-4e7f-8a61-5d0c2b9e7f13"
        val plain = GroupCallEnvelope.started(id, "audio").toJson()
        assertFalse(plain.containsKey("with"))
        assertEquals("""{"v":1,"type":"group_call","call_id":"$id","media":"audio","state":"started"}""", plain.toString())
        val ended = GroupCallEnvelope.ended(id, "audio", GroupCallEnvelope.R_HANGUP, "2026-10-14T09:00:00.000Z", 192)
        assertFalse(ended.toJson().containsKey("with"))
        val risi = ended.copy(with = GroupCallEnvelope.WITH_RISI)
        assertEquals("risi", (risi.toJson()["with"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("risi", GroupCallEnvelope.validate(risi.toJson())!!.with)
        // Another value is ignored (not a Call Risi), the line stays valid.
        val other = JsonObject(plain + ("with" to kotlinx.serialization.json.JsonPrimitive("someone")))
        assertNull(GroupCallEnvelope.validate(other)!!.with)
    }

    @Test fun historyLines() {
        val id = "3c8e1f4a-9b2d-4e7f-8a61-5d0c2b9e7f13"
        val ended = GroupCallEnvelope.ended(id, "audio", GroupCallEnvelope.R_HANGUP, "2026-10-14T09:00:00.000Z", 192).copy(with = "risi")
        assertEquals("Call with Risi · 3:12", GroupCallLines.text(ended, "", true, running = false))
        val timeout = GroupCallEnvelope.ended(id, "audio", GroupCallEnvelope.R_TIMEOUT, null, null).copy(with = "risi")
        assertEquals("Call with Risi · Didn't connect", GroupCallLines.text(timeout, "", true, running = false))
        assertFalse(GroupCallLines.joinable(GroupCallEnvelope.started(id, "audio").copy(with = "risi"), running = true))
        // Parity: a normal group call's line is unchanged.
        val normal = GroupCallEnvelope.ended(id, "audio", GroupCallEnvelope.R_HANGUP, "2026-10-14T09:00:00.000Z", 192)
        assertEquals("Voice call · 3:12", GroupCallLines.text(normal, "Kamal", false, running = false))
        assertTrue(GroupCallLines.joinable(GroupCallEnvelope.started(id, "audio"), running = true))
    }

    @Test fun errorTextsAndStateLine() {
        assertEquals("Calling Risi isn't available for this account yet", CallRisiTexts.error(CallErrors.CALL_RISI_OFF))
        assertEquals("You can call Risi only from your Risi chat", CallRisiTexts.error(CallErrors.NOT_RISI_CHAT))
        assertEquals("Risi is on other calls right now. Try again in a minute", CallRisiTexts.error(CallErrors.RISI_BUSY))
        assertEquals("Risi can't take calls right now", CallRisiTexts.error(CallErrors.RISI_UNAVAILABLE))
        assertNull(CallRisiTexts.error("calls_unavailable"))
        assertEquals("Connecting…", CallRisiTexts.stateLine(false, false))
        assertEquals("Listening", CallRisiTexts.stateLine(true, false))
        assertEquals("Speaking", CallRisiTexts.stateLine(true, true))
        // Never a lock or an encryption claim.
        assertFalse(CallRisiTexts.NOT_E2EE.startsWith("End-to-end encrypted"))
    }

    @Test fun transcriptCardParses() {
        val j = """{"v":1,"type":"text","body":"Call with Risi · 3:12","risi":{"v":1,"kind":"call_transcript","call_id":"c1","duration_s":192,"reason":"hangup",
            "lines":[{"who":"user","text":"What's on tomorrow?","lang":"en","at":"t"},{"who":"risi","text":"Hmm, let me check your calendar…","lang":"en","at":"t","filler":true},
            {"who":"risi","text":"Two meetings.","lang":"en","at":"t"},{"who":"robot","text":"x"},{"who":"user","text":"  "}],"e2ee":false,"notify":["u1"]}}"""
        val t = CallTranscript.parseJson(j)!!
        assertEquals("c1", t.callId)
        assertEquals("Call with Risi · 3:12", t.title)
        assertEquals(3, t.lines.size)
        assertTrue(t.lines[1].filler && t.lines[1].risi)
        assertFalse(t.lines[0].risi)
        // §36.5's per-turn call_transcript (no `lines`) is not this card: it keeps its own rendering.
        val s36 = """{"risi":{"v":1,"kind":"call_transcript","call_id":"c1","session_id":"s1","turn":1,"speaker":"u1","text":"hi"}}"""
        assertNull(CallTranscript.parseJson(s36))
        assertNotNull(CallTranscript.parseJson(j.replace("\"call_transcript\"", "\"risi_call_transcript\"")))
        assertNull(CallTranscript.parseJson("not json"))
    }

    @Test fun callRisiCapabilityOnlyWithTheFlagGroupCallsAndRisiTools() {
        val fake = FakeMlsEngine("u1", "d1").apply { deletesOn = true; tabsOn = true; risiChatOn = true }
        val mls: MlsEngine = object : MlsEngine by fake {
            override val callKeysSupported: Boolean get() = true
        }
        var flag = false
        var groupCalls = true
        var risiTools = true
        val api = ApiClient(OkHttpClient(), { "http://127.0.0.1:1/" }, { "tok" })
        val reg = DeviceRegistrar(
            api, { "dev-1" }, "0.3.0", { mls },
            imagesSupported = { true }, callsSupported = { true }, videoSupported = { true }, groupCallsSupported = { groupCalls },
            tabsSupported = { true }, risiToolsSupported = { risiTools }, callRisiSupported = { flag },
        )
        val off = reg.capabilities(mls)!!
        assertFalse("call_risi" in off)
        assertTrue("group_calls" in off && "risi_tools" in off)
        flag = true
        val on = reg.capabilities(mls)!!
        // The only difference, appended last; v1.52 §41.8 `call_risi_cal` comes exactly with `call_risi`.
        assertEquals(off + listOf("call_risi", "call_risi_cal"), on)
        groupCalls = false
        assertFalse(reg.capabilities(mls)!!.any { it == "call_risi" || it == "call_risi_cal" })
        groupCalls = true
        risiTools = false
        assertFalse(reg.capabilities(mls)!!.any { it == "call_risi" || it == "call_risi_cal" })
    }
}
