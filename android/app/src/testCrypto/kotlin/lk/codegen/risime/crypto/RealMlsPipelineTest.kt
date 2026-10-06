package lk.codegen.risime.crypto

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lk.codegen.risime.data.BehaviourLog
import lk.codegen.risime.data.ChatEngine
import lk.codegen.risime.data.FakeBehaviourDao
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.FakeRealtime
import lk.codegen.risime.data.FakeSyncDao
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.mls.CommitOutcome
import lk.codegen.risime.data.mls.FakeMlsPendingDao
import lk.codegen.risime.data.mls.MlsDecryptException
import lk.codegen.risime.data.mls.MlsPipeline
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.MsgSendReply
import lk.codegen.risime.net.dmConversationId
import lk.codegen.risime.realtime.PushResult
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/** The e2ee pipeline and outbox with the REAL MLS core (host build via JNA), decision 033. */
class RealMlsPipelineTest {
    private val aU = "aaaa0000-0000-4000-8000-00000000000a"
    private val bU = "bbbb0000-0000-4000-8000-00000000000b"
    private val conv = dmConversationId(aU, bU)
    private val b64 = Base64.getEncoder()
    private lateinit var a: RealMls.Device
    private lateinit var a2: RealMls.Device
    private lateinit var b: RealMls.Device

    @Before fun setUp() {
        RealMls.assumeHostLibrary()
        a = RealMls.device(aU, "a-phone")
        a2 = RealMls.device(aU, "a-tablet")
        b = RealMls.device(bU, "b-phone")
    }

    @After fun tearDown() {
        if (::a.isInitialized) listOf(a, a2, b).forEach { it.close() }
    }

    /** One app instance: real engine, fake DAOs/realtime. */
    private inner class App(val dev: RealMls.Device, scope: TestScope) {
        val messages = FakeMessageDao()
        val sync = FakeSyncDao()
        val pending = FakeMlsPendingDao()
        val realtime = FakeRealtime()
        var catchUp: suspend (String) -> Unit = {}
        val chat = ChatEngine(
            messages = messages, sync = sync,
            tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
            scope = scope, realtime = { realtime }, meId = { dev.ref.userId },
            behaviour = BehaviourLog(FakeBehaviourDao(), { "s" }, { 0L }),
            mls = MlsPipeline({ dev.engine }, pending), mlsEngine = { dev.engine }, catchUp = { catchUp(it) },
        )
        fun bodies() = messages.rows.values.sortedBy { it.localTs }.map { it.body }
    }

    private var n = 0
    private fun ev(kind: String, data: JsonObject) = Event("ev-${++n}", kind, data)

    private fun welcomeEv(welcome: ByteArray, epoch: Long, to: List<String>, gen: Long = 1) = ev("mls_welcome", buildJsonObject {
        put("conversation_id", conv); put("generation", gen); put("epoch", epoch); put("welcome", b64.encodeToString(welcome))
        put("to_devices", buildJsonArray { to.forEach { add(JsonPrimitive(it)) } })
    })

    private fun commitEv(commit: ByteArray, epoch: Long, fromDev: String, gen: Long = 1) = ev("mls_commit", buildJsonObject {
        put("conversation_id", conv); put("generation", gen); put("epoch", epoch); put("commit", b64.encodeToString(commit)); put("from_device", fromDev)
    })

    private fun msgEv(from: RealMls.Device, ct: ByteArray, epoch: Long, fromDev: String = from.ref.deviceId, gen: Long = 1) = ev("message", buildJsonObject {
        put("message_id", "m$n"); put("client_msg_id", "c$n"); put("conversation_id", conv)
        put("from", from.ref.userId); put("to", if (from.ref.userId == aU) bU else aU); put("from_device", fromDev)
        put("ciphertext", b64.encodeToString(ct)); put("generation", gen); put("epoch", epoch); put("server_ts", "2026-10-06T08:00:00.000Z")
    })

    /** A creates the group with B and A's tablet (epoch 0 → 1), "server" accepts. */
    private fun createGroup(): ByteArray {
        val pc = a.engine.createGroup(conv, 1, listOf(b.keyPackage(), a2.keyPackage()))
        assertEquals(0L, pc.epoch)
        a.engine.commitAccepted(conv)
        assertEquals(1L, a.engine.group(conv)!!.epoch)
        return pc.welcome!!
    }

    @Test fun welcomeJoinAndBothDirectionsThroughThePipeline() = runTest {
        val welcome = createGroup()
        val appB = App(b, this)
        val appA2 = App(a2, this)
        appB.chat.onEvents(listOf(welcomeEv(welcome, 1, listOf("b-phone", "a-tablet"))))
        appA2.chat.onEvents(listOf(welcomeEv(welcome, 1, listOf("b-phone", "a-tablet"))))
        assertEquals(1L, b.engine.group(conv)!!.epoch)

        appB.chat.onEvents(listOf(msgEv(a, a.engine.encrypt(conv, "hello from A".toByteArray()), 1)))
        assertEquals(listOf("hello from A"), appB.bodies())
        // A's other device sees A's message as its own (outgoing).
        val m2 = a.engine.encrypt(conv, "to everyone".toByteArray())
        appA2.chat.onEvents(listOf(msgEv(a, m2, 1)))
        assertEquals(listOf(true), appA2.messages.rows.values.map { it.outgoing })

        val reply = b.engine.encrypt(conv, "hi A".toByteArray())
        assertArrayEquals("hi A".toByteArray(), a.engine.decrypt(conv, 1, reply).plaintext)
        assertEquals(b.ref, a.engine.decrypt(conv, 1, b.engine.encrypt(conv, "x".toByteArray())).sender)
    }

    @Test fun messageAheadOfItsCommitIsParkedThenReplayed() = runTest {
        val welcome = createGroup()
        val appB = App(b, this)
        appB.chat.onEvents(listOf(welcomeEv(welcome, 1, listOf("b-phone", "a-tablet"))))
        // A removes the tablet (epoch 1 → 2) and immediately sends at epoch 2.
        val pc = a.engine.changeMembers(conv, emptyList(), listOf(a2.ref))
        a.engine.commitAccepted(conv)
        val ct = a.engine.encrypt(conv, "after the change".toByteArray())
        // B gets the message first: parked; then the commit: applied + replayed.
        appB.chat.onEvents(listOf(msgEv(a, ct, 2)))
        assertTrue(appB.bodies().isEmpty())
        assertEquals(1, appB.pending.rows.size)
        appB.chat.onEvents(listOf(commitEv(pc.commit, 1, "a-phone")))
        assertEquals(listOf("after the change"), appB.bodies())
        assertTrue(appB.pending.rows.isEmpty())
        // The removed tablet can't read epoch-2 traffic.
        val appA2 = App(a2, this)
        appA2.chat.onEvents(listOf(welcomeEv(welcome, 1, listOf("a-tablet"))))
        appA2.chat.onEvents(listOf(commitEv(pc.commit, 1, "a-phone")))
        assertNull(a2.engine.group(conv))
    }

    @Test fun creationRaceLoserJoinsTheWinner() {
        val pcA = a.engine.createGroup(conv, 1, listOf(b.keyPackage()))
        val pcB = b.engine.createGroup(conv, 1, listOf(a.keyPackage()))
        assertEquals(0L, pcA.epoch)
        b.engine.commitAccepted(conv) // the server took B's
        a.engine.commitRejected(conv) // A got epoch_conflict: its local group is gone
        assertNull(a.engine.group(conv))
        a.engine.joinFromWelcome(conv, 1, pcB.welcome!!)
        assertArrayEquals("race".toByteArray(), a.engine.decrypt(conv, 1, b.engine.encrypt(conv, "race".toByteArray())).plaintext)
    }

    @Test fun welcomeForOthersOwnCommitSkipAndForgedSender() = runTest {
        val welcome = createGroup()
        val appB = App(b, this)
        appB.chat.onEvents(listOf(welcomeEv(welcome, 1, listOf("someone-else"))))
        assertNull(b.engine.group(conv))
        appB.chat.onEvents(listOf(welcomeEv(welcome, 1, listOf("b-phone"))))
        // A real message from A's phone, but the event claims the tablet: dropped.
        appB.chat.onEvents(listOf(msgEv(a, a.engine.encrypt(conv, "forged".toByteArray()), 1, fromDev = "a-tablet")))
        assertTrue(appB.messages.rows.isEmpty())
        // A's own commit event is skipped by A (it merged on the 200).
        val appA = App(a, this)
        val pc = a.engine.changeMembers(conv, emptyList(), listOf(a2.ref))
        a.engine.commitAccepted(conv)
        appA.chat.onEvents(listOf(commitEv(pc.commit, 1, "a-phone")))
        assertEquals(2L, a.engine.group(conv)!!.epoch)
    }

    @Test fun outerRollbackRestoresTheRatchet() {
        val welcome = createGroup()
        b.engine.joinFromWelcome(conv, 1, welcome)
        val ct = a.engine.encrypt(conv, "once".toByteArray())
        // A crash after decrypting but before the message insert/cursor commit: roll back.
        runCatching { b.transaction { b.engine.decrypt(conv, 1, ct); error("crash before insert") } }
        // The ratchet came back with the rollback: the same message decrypts again.
        assertArrayEquals("once".toByteArray(), b.transaction { b.engine.decrypt(conv, 1, ct) }.plaintext)
        // Committed: now it's consumed.
        assertTrue(runCatching { b.engine.decrypt(conv, 1, ct) }.exceptionOrNull() is MlsDecryptException)
        // A tampered commit is rejected and leaves the state unchanged.
        val r = b.engine.processCommit(conv, 1, ByteArray(40) { 1 })
        assertTrue(r is CommitOutcome.Rejected)
        assertEquals(1L, b.engine.group(conv)!!.epoch)
    }

    @Test fun outboxEncryptsAtSendTimeAndRetriesAfterStaleEpoch() = runTest {
        val welcome = createGroup()
        b.engine.joinFromWelcome(conv, 1, welcome)
        val appB = App(b, this)
        // The group moved on (A removed the tablet) but B hasn't seen it yet.
        val pc = a.engine.changeMembers(conv, emptyList(), listOf(a2.ref))
        a.engine.commitAccepted(conv)
        var first = true
        appB.realtime.encryptedReplies = { m ->
            if (first) { first = false; PushResult.Rejected("stale_epoch") } else PushResult.Ok(MsgSendReply("mid", conv, "t"))
        }
        appB.catchUp = { appB.chat.applyOutOfBand(listOf(commitEv(pc.commit, 1, "a-phone"))) }
        val id = appB.chat.sendText(aU, "late")!!
        advanceUntilIdle()
        val sent = appB.realtime.sentEncrypted
        assertEquals(listOf(1L, 2L), sent.map { it.epoch })
        assertEquals(setOf(id), sent.map { it.clientMsgId }.toSet())
        // A reads the re-encrypted one.
        val ct = Base64.getDecoder().decode(sent.last().ciphertext)
        assertArrayEquals("late".toByteArray(), a.engine.decrypt(conv, 1, ct).plaintext)
        assertEquals("SENT", appB.messages.rows[id]!!.status)
    }
}
