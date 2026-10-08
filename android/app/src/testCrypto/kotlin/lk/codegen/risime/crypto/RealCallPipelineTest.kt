package lk.codegen.risime.crypto

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lk.codegen.risime.calls.CallEnvelope
import lk.codegen.risime.calls.CallHooks
import lk.codegen.risime.calls.CallStateMachine
import lk.codegen.risime.calls.InboundCall
import lk.codegen.risime.calls.fakeSdp
import lk.codegen.risime.calls.fingerprintOf
import lk.codegen.risime.data.BehaviourLog
import lk.codegen.risime.data.ChatEngine
import lk.codegen.risime.data.FakeBehaviourDao
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.FakeRealtime
import lk.codegen.risime.data.FakeSyncDao
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.mls.FakeMlsPendingDao
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.data.mls.MlsPipeline
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.dmConversationId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** §16.3 call signals and §16.6 call lines through the e2ee pipeline with the REAL MLS core. */
class RealCallPipelineTest {
    private val aU = "aaaa0000-0000-4000-8000-00000000000a"
    private val bU = "bbbb0000-0000-4000-8000-00000000000b"
    private val conv = dmConversationId(aU, bU)
    private val b64 = Base64.getEncoder()
    private val callId = "4b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10"
    private lateinit var a: RealMls.Device
    private lateinit var a2: RealMls.Device
    private lateinit var b: RealMls.Device

    @Before fun setUp() {
        RealMls.assumeHostLibrary()
        a = RealMls.device(aU, "a1a1a1a1-0000-4000-8000-000000000001")
        a2 = RealMls.device(aU, "a2a2a2a2-0000-4000-8000-000000000002")
        b = RealMls.device(bU, "b1b1b1b1-0000-4000-8000-000000000003")
    }

    @After fun tearDown() {
        if (::a.isInitialized) listOf(a, a2, b).forEach { it.close() }
    }

    private class Hooks(var rang: Boolean = false) : CallHooks {
        val log = CopyOnWriteArrayList<String>()
        val signals = CopyOnWriteArrayList<InboundCall>()
        val missed = CopyOnWriteArrayList<String>()
        override suspend fun rangUnanswered(callId: String) = rang
        override suspend fun onSignal(s: InboundCall) { signals += s; log += "signal:${s.env.type}" }
        override suspend fun onCallEnd(conversationId: String, fromUser: String, fromDevice: String?, end: CallEnvelope.End) { log += "end:${end.reason}" }
        override suspend fun onPageEnd() { log += "page" }
        override fun onMissedCall(conversationId: String, from: String, video: Boolean) { missed += from }
    }

    private inner class App(val dev: RealMls.Device, scope: TestScope, val hooks: Hooks = Hooks()) {
        val messages = FakeMessageDao()
        val realtime = FakeRealtime()
        val drops = CopyOnWriteArrayList<String>()
        val chat = ChatEngine(
            messages = messages, sync = FakeSyncDao(),
            tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
            scope = scope, realtime = { realtime }, meId = { dev.ref.userId },
            behaviour = BehaviourLog(FakeBehaviourDao(), { "s" }, { 0L }),
            mls = MlsPipeline({ dev.engine }, FakeMlsPendingDao(), log = { drops += it }), mlsEngine = { dev.engine },
            calls = hooks,
        )
    }

    private var n = 0
    private fun eventId() = "e3c4d5e6-a0b2-11f0-8000-%012x".format(n)

    private fun welcomeEv(welcome: ByteArray, to: List<String>) = Event(eventId().also { n++ }, "mls_welcome", buildJsonObject {
        put("conversation_id", conv); put("generation", 1); put("epoch", 1); put("welcome", b64.encodeToString(welcome))
        put("to_devices", buildJsonArray { to.forEach { add(JsonPrimitive(it)) } })
    })

    private fun signalEv(from: RealMls.Device, env: CallEnvelope.Env, ring: Boolean = CallEnvelope.ringFor(env), cleartextCallId: String = env.callId, fromDevice: String = from.ref.deviceId, ct: ByteArray? = null): Event {
        val id = eventId().also { n++ }
        return Event(id, Event.KIND_CALL_SIGNAL, buildJsonObject {
            put("message_id", id); put("conversation_id", conv); put("from", from.ref.userId); put("to", if (from.ref.userId == aU) bU else aU)
            put("from_device", fromDevice); put("call_id", cleartextCallId); put("ring", ring)
            put("ciphertext", b64.encodeToString(ct ?: from.engine.encrypt(conv, CallEnvelope.encode(env)))); put("generation", 1); put("epoch", 1)
            put("server_ts", CallStateMachine.iso(System.currentTimeMillis()))
        })
    }

    private fun msgEv(from: RealMls.Device, plaintext: ByteArray): Event {
        val id = eventId().also { n++ }
        return Event(id, "message", buildJsonObject {
            put("message_id", id); put("client_msg_id", "c$n"); put("conversation_id", conv)
            put("from", from.ref.userId); put("to", if (from.ref.userId == aU) bU else aU); put("from_device", from.ref.deviceId)
            put("ciphertext", b64.encodeToString(from.engine.encrypt(conv, plaintext))); put("generation", 1); put("epoch", 1)
            put("server_ts", CallStateMachine.iso(System.currentTimeMillis()))
        })
    }

    private fun group(): ByteArray {
        val pc = a.engine.createGroup(conv, 1, listOf(b.keyPackage(), a2.keyPackage()))
        a.engine.commitAccepted(conv)
        return pc.welcome!!
    }

    private fun offer() = CallEnvelope.Offer(callId, fakeSdp(true, fingerprintOf(1), "u1"), CallStateMachine.iso(System.currentTimeMillis()))

    @Test fun boundSignalsReachTheCallsLayerAfterThePageInOrder() = runTest {
        val w = group()
        val appB = App(b, this)
        appB.chat.onEvents(listOf(welcomeEv(w, listOf(b.ref.deviceId, a2.ref.deviceId))))
        appB.hooks.log.clear()
        appB.chat.onEvents(listOf(signalEv(a, offer()), signalEv(a, CallEnvelope.Cancel(callId))))
        assertEquals(listOf("signal:call_offer", "signal:call_cancel", "page"), appB.hooks.log)
        val s = appB.hooks.signals.first()
        assertEquals(a.ref.deviceId, s.fromDevice)
        assertTrue(s.inPage)
        assertTrue("no message row for a signal", appB.messages.rows.isEmpty())
    }

    @Test fun bindingFailuresAndUndecryptableSignalsAreDroppedWithoutAnyLine() = runTest {
        val w = group()
        val appB = App(b, this)
        appB.chat.onEvents(listOf(welcomeEv(w, listOf(b.ref.deviceId))))
        appB.hooks.log.clear()
        appB.chat.onEvents(
            listOf(
                signalEv(a, offer(), ring = false), // ring flag relabelled
                signalEv(a, CallEnvelope.Ringing(callId), ring = true), // an ICE/ringing relabelled as ring:true
                signalEv(a, offer(), cleartextCallId = "5b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10"), // swapped call_id
                signalEv(a, offer(), fromDevice = a2.ref.deviceId), // sender mismatch
                signalEv(a, offer(), ct = "garbage".toByteArray()), // undecryptable
                signalEv(a, CallEnvelope.End(callId, "hangup")), // call_end is never a signal
            ),
        )
        assertEquals(listOf("page"), appB.hooks.log)
        assertTrue("no §13.3 line for call controls: ${appB.messages.rows.values}", appB.messages.rows.isEmpty())
        assertTrue(appB.drops.any { it.contains("ring flag mismatch") } && appB.drops.any { it.contains("call_id mismatch") } && appB.drops.any { it.contains("sender mismatch") })
        // The ratchet still works afterwards: a text decrypts.
        appB.chat.onEvents(listOf(msgEv(a, MlsPayload.text("after"))))
        assertEquals(listOf("after"), appB.messages.rows.values.map { it.body })
    }

    @Test fun callEndLinesOnePerCallIdBothPerspectivesMissedNotifies() = runTest {
        val w = group()
        val appB = App(b, this)
        val appA2 = App(a2, this)
        appB.chat.onEvents(listOf(welcomeEv(w, listOf(b.ref.deviceId, a2.ref.deviceId))))
        appA2.chat.onEvents(listOf(welcomeEv(w, listOf(b.ref.deviceId, a2.ref.deviceId))))
        val end = msgEv(a, CallEnvelope.encode(CallEnvelope.End(callId, CallEnvelope.R_TIMEOUT)))
        appB.chat.onEvents(listOf(end))
        val row = appB.messages.rows.values.single()
        assertEquals(MessageEntity.KIND_CALL, row.kind)
        assertEquals(callId, row.callId)
        assertEquals("Missed voice call", row.body)
        assertEquals("DELIVERED", row.status) // unread
        assertEquals(listOf(aU), appB.hooks.missed)
        assertTrue(appB.hooks.log.contains("end:timeout"))
        // A second call_end for the same call (both hung up) is ignored.
        appB.chat.onEvents(listOf(msgEv(a, CallEnvelope.encode(CallEnvelope.End(callId, CallEnvelope.R_CANCELLED)))))
        assertEquals(1, appB.messages.rows.size)
        // A's tablet (the caller's own user) sees "No answer", outgoing, not unread.
        appA2.chat.onEvents(listOf(msgEv(a, CallEnvelope.encode(CallEnvelope.End(callId, CallEnvelope.R_TIMEOUT)))))
        val mine = appA2.messages.rows.values.single()
        assertEquals("Voice call · No answer", mine.body)
        assertTrue(mine.outgoing)
        // A hangup with a duration.
        appB.chat.onEvents(listOf(msgEv(a, CallEnvelope.encode(CallEnvelope.End("6b7e1c1e-3c0e-4b55-9f43-0b8f8a1f2d10", CallEnvelope.R_HANGUP, "2026-10-06T08:15:41.880Z", 192)))))
        assertTrue(appB.messages.rows.values.any { it.body == "Voice call · 3:12" && it.status == "READ" })
        // Other call envelopes in a message event are dropped.
        appB.chat.onEvents(listOf(msgEv(a, CallEnvelope.encode(offer()))))
        assertEquals(2, appB.messages.rows.size)
    }

    @Test fun sendCallSignalUsesTheLaneAndTheReceiverDecryptsInOrderWithTexts() = runTest {
        val w = group()
        val appA = App(a, this)
        val appB = App(b, this)
        appB.chat.onEvents(listOf(welcomeEv(w, listOf(b.ref.deviceId))))
        val r = appA.chat.sendCallSignal(conv, bU, offer())
        assertTrue(r is lk.codegen.risime.realtime.PushResult.Ok)
        val push = appA.realtime.sentCallSignals.single()
        assertTrue(push.ring && push.callId == callId && push.to == bU)
        val ev = Event(eventId().also { n++ }, Event.KIND_CALL_SIGNAL, buildJsonObject {
            put("message_id", "x"); put("conversation_id", conv); put("from", aU); put("to", bU); put("from_device", a.ref.deviceId)
            put("call_id", push.callId); put("ring", push.ring); put("ciphertext", push.ciphertext); put("generation", push.generation); put("epoch", push.epoch)
            put("server_ts", CallStateMachine.iso(System.currentTimeMillis()))
        })
        appB.chat.onEvents(listOf(ev, msgEv(a, MlsPayload.text("text after the offer"))))
        assertEquals("call_offer", appB.hooks.signals.single().env.type)
        assertEquals(listOf("text after the offer"), appB.messages.rows.values.map { it.body })
    }

    @Test fun twoThousandExpiredSignalsThenATextStillDecrypts() = runTest {
        val w = group()
        val appB = App(b, this)
        appB.chat.onEvents(listOf(welcomeEv(w, listOf(b.ref.deviceId))))
        repeat(2_000) { a.engine.encrypt(conv, CallEnvelope.encode(CallEnvelope.Ringing(callId))) } // never delivered (expired)
        appB.chat.onEvents(listOf(msgEv(a, MlsPayload.text("still here"))))
        assertEquals(listOf("still here"), appB.messages.rows.values.map { it.body })
    }

    @Test fun outOfOrderWithinThe32KeyToleranceDecrypts() = runTest {
        val w = group()
        val appB = App(b, this)
        appB.chat.onEvents(listOf(welcomeEv(w, listOf(b.ref.deviceId))))
        val early = msgEv(a, MlsPayload.text("early"))
        val burst = (1..20).map { signalEv(a, CallEnvelope.Ringing(callId)) }
        appB.chat.onEvents(burst + early) // generation n arrives after n+1..n+20
        runCurrent()
        assertEquals(listOf("early"), appB.messages.rows.values.map { it.body })
    }
}
