package lk.codegen.risime.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import lk.codegen.risime.data.mls.DeviceRef
import lk.codegen.risime.data.mls.MlsDecryptException
import lk.codegen.risime.net.dmConversationId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression for the v0.2.0-nightly.8 interop SIGSEGV: one device's engine called from many
 * threads at once (pipeline, outbox, registrar, executor). Every engine/KvStore access must be
 * serialised per device; before the fix this crashes the JVM in native SQLite.
 */
class MlsConcurrencyStressTest {
    private val aU = "aaaa0000-0000-4000-8000-0000000000aa"
    private val bU = "bbbb0000-0000-4000-8000-0000000000bb"
    private val conv = dmConversationId(aU, bU)
    private lateinit var a: RealMls.Device
    private lateinit var b: RealMls.Device

    @Before fun setUp() {
        RealMls.assumeHostLibrary()
        a = RealMls.device(aU, "a-1")
        b = RealMls.device(bU, "b-1")
        val pc = a.engine.createGroup(conv, 1, listOf(b.keyPackage()))
        a.engine.commitAccepted(conv)
        b.engine.joinFromWelcome(conv, 1, pc.welcome!!)
    }

    @After fun tearDown() {
        if (::a.isInitialized) { a.close(); b.close() }
    }

    @Test fun manyThreadsOnOneDevice() = runBlocking {
        repeat(5) { round ->
            val jobs = (0 until 64).map { i ->
                async(Dispatchers.Default) {
                    when (i % 6) {
                        0 -> a.engine.group(conv) // the pipeline's lookups
                        1 -> b.engine.decrypt(conv, 1, a.engine.encrypt(conv, "m$round-$i".toByteArray())) // outbox + pipeline
                        2 -> a.engine.createKeyPackages(2) // registrar top-up
                        3 -> a.engine.members(conv) // membership executor
                        4 -> a.transaction { a.engine.group(conv); a.engine.encrypt(conv, "tx$i".toByteArray()) } // ChatEngine's outer transaction
                        else -> runCatching { b.engine.decrypt(conv, 1, ByteArray(8)) }.exceptionOrNull() as? MlsDecryptException
                    }
                }
            }
            jobs.awaitAll()
        }
        // Still consistent after the storm.
        assertEquals(1L, a.engine.group(conv)!!.epoch)
        assertTrue(a.engine.members(conv).contains(DeviceRef(bU, "b-1")))
        val ct = a.engine.encrypt(conv, "after".toByteArray())
        assertEquals("after", b.engine.decrypt(conv, 1, ct).plaintext.decodeToString())
    }
}
