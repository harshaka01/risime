package ai.risicloud.risime.data

import ai.risicloud.risime.data.mls.FakeMlsEngine
import ai.risicloud.risime.data.mls.GroupRef
import ai.risicloud.risime.net.LinkPreview
import ai.risicloud.risime.net.dmConversationId
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.44 §39.3 through the real outbox: the sender's preview goes inside the E2EE envelope only; plaintext never fetches. */
class LinkPreviewEngineTest {
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val peer = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val conv = dmConversationId(me, peer)
    private val messages = FakeMessageDao()
    private val realtime = FakeRealtime()
    private val mls = FakeMlsEngine(me, "d-me")
    private var ids = 0

    private fun TestScope.engine(withMls: Boolean) = ChatEngine(
        messages = messages, sync = FakeSyncDao(),
        tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
        scope = this, realtime = { realtime }, meId = { me }, clock = { 1_000L + testScheduler.currentTime }, newClientMsgId = { "c${++ids}" },
        mlsEngine = { if (withMls) mls else null }, plaintextHold = { false },
    )

    private fun sentPlaintext(): String = realtime.sentEncrypted.single().let { m ->
        java.util.Base64.getDecoder().decode(m.ciphertext).decodeToString().substringAfter("|").substringAfter("|").substringAfter("|")
    }

    @Test fun e2eeMessageWaitsForItsPreviewAndCarriesIt() = runTest {
        mls.groups[conv] = GroupRef(conv, 1, 1)
        val e = engine(withMls = true)
        val urls = mutableListOf<String>()
        e.linkPreviews = { u -> urls += u; delay(2_000); LinkPreview(u, "Agenda", siteName = "Example") }
        val id = e.sendText(peer, "see https://example.org/agenda, thanks")!!
        advanceTimeBy(1_000)
        assertEquals("held while the preview loads", "PENDING", messages.rows.getValue(id).status)
        assertTrue(realtime.sentEncrypted.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("https://example.org/agenda"), urls)
        assertEquals("SENT", messages.rows.getValue(id).status)
        val env = sentPlaintext()
        assertTrue(env, env.contains("\"link_preview\":{\"url\":\"https://example.org/agenda\",\"title\":\"Agenda\""))
        assertEquals("Agenda", LinkPreview.decode(messages.rows.getValue(id).linkPreview, messages.rows.getValue(id).body)!!.title)
    }

    @Test fun aFailedFetchSendsWithoutAPreview() = runTest {
        mls.groups[conv] = GroupRef(conv, 1, 1)
        val e = engine(withMls = true)
        e.linkPreviews = { null }
        val id = e.sendText(peer, "https://example.org/")!!
        advanceUntilIdle()
        assertEquals("SENT", messages.rows.getValue(id).status)
        assertFalse(sentPlaintext().contains("link_preview"))
        assertNull(messages.rows.getValue(id).linkPreview)
    }

    @Test fun aPlaintextChatNeverFetches() = runTest {
        val e = engine(withMls = false)
        var calls = 0
        e.linkPreviews = { calls++; LinkPreview(it, "T") }
        e.sendText(peer, "https://example.org/")!!
        advanceUntilIdle()
        assertEquals(0, calls)
        assertTrue(realtime.sentEncrypted.isEmpty())
    }
}
