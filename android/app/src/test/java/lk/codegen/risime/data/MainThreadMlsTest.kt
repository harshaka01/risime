package lk.codegen.risime.data

import android.app.Application
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import lk.codegen.risime.calls.CallEnvelope
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.mls.FakeMlsEngine
import lk.codegen.risime.data.mls.MlsEngine
import lk.codegen.risime.net.dmConversationId
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * nightly.17 P0: opening a DM while MLS activated crashed with "Cannot access database on the main
 * thread": the chat screen (main thread) flushed the outbox, and the MLS core's storage callback
 * opened a Room transaction there. Every ChatEngine entry point that reaches the MLS core must run
 * on its io context even when called from the main thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MainThreadMlsTest {
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val peer = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val conv = dmConversationId(me, peer)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val messages = FakeMessageDao()
    private val engineThreads = CopyOnWriteArrayList<Boolean>()

    /** Like the real core: every call runs a storage transaction, which Room refuses on the main thread. */
    private val fake = FakeMlsEngine(me, "dev-1")
    private val mls: MlsEngine = object : MlsEngine by fake {
        private fun onMain() = (Looper.myLooper() == Looper.getMainLooper()).also { engineThreads += it }
        override fun group(conversationId: String) = fake.group(conversationId).also { check(!onMain()) { "Cannot access database on the main thread" } }
        override fun encrypt(conversationId: String, plaintext: ByteArray) = fake.encrypt(conversationId, plaintext).also { check(!onMain()) { "Cannot access database on the main thread" } }
    }

    @After fun tearDown() = scope.cancel()

    private fun engine() = ChatEngine(
        messages = messages, sync = FakeSyncDao(),
        tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
        scope = scope, realtime = { FakeRealtime() }, meId = { me },
        behaviour = BehaviourLog(FakeBehaviourDao(), { "s" }, { 0L }),
        mlsEngine = { mls },
        io = Dispatchers.IO,
    )

    @Test fun flushingTheOutboxFromTheMainThreadNeverReachesTheMlsCoreThere() = runBlocking {
        assertTrue("the test runs on the main looper", Looper.myLooper() == Looper.getMainLooper())
        messages.insert(MessageEntity("c1", null, conv, me, peer, "hi", null, 1L, "PENDING", true))
        engine().flushOutbox() // what ChatViewModel does when the DM turns e2ee as it opens
        assertTrue("the MLS core was used", engineThreads.isNotEmpty())
        assertTrue("never on the main thread: $engineThreads", engineThreads.none { it })
    }

    @Test fun aCallSignalFromTheMainThreadEncryptsOffIt() = runBlocking {
        engine().sendCallSignal(conv, peer, CallEnvelope.Ringing("call-1"))
        assertTrue("the MLS core was used", engineThreads.isNotEmpty())
        assertTrue("never on the main thread: $engineThreads", engineThreads.none { it })
    }
}
