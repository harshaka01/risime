package lk.codegen.risime.data

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.groups.GroupStore
import lk.codegen.risime.data.mls.FakeMlsEngine
import lk.codegen.risime.data.mls.FakeMlsPendingDao
import lk.codegen.risime.data.mls.GroupRef
import lk.codegen.risime.data.tabs.RisiControl
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.GroupMeta
import lk.codegen.risime.net.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** §24.11 a member's `risi_request` / `risi_action`: sent as an MLS application message, shown as a small system line. */
class RisiControlEngineTest {
    private val me = "u-me"
    private val kamal = "u-kamal"
    private val risi = "9e1f0000-0000-4000-8000-000000000001"
    private val official = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"
    private val privateGrp = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private val messages = FakeMessageDao()
    private val sync = FakeSyncDao()
    private val realtime = FakeRealtime()
    private val mls = FakeMlsEngine(me, "dev-me").apply {
        groups[official] = lk.codegen.risime.data.mls.GroupRef(official, 1, 1)
        groups[privateGrp] = GroupRef(privateGrp, 1, 1)
        metas[official] = GroupMeta(name = "", tab = "official", chatId = privateGrp, agents = listOf(risi))
        metas[privateGrp] = GroupMeta(name = "Site team")
    }
    private var ids = 0
    private var now = 1_000L

    private fun TestScope.engine() = ChatEngine(
        messages = messages, sync = sync,
        tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
        scope = this, realtime = { realtime }, meId = { me }, clock = { now++ }, newClientMsgId = { "c${++ids}" },
        mls = lk.codegen.risime.data.mls.MlsPipeline({ mls }, FakeMlsPendingDao()),
        mlsEngine = { mls },
        groupsEnabled = { true },
        groups = GroupStore(FakeGroupDao(), FakeGroupOpDao(), messages, { "dev-me" }, { mls.groupMeta(it) }, { now++ }),
    )

    private fun incoming(conv: String, mid: String, json: String) = ProtocolJson.decodeFromString<Event>(
        """{"event_id":"e-$mid","kind":"message","data":{"message_id":"$mid","client_msg_id":"cm-$mid","conversation_id":"$conv",
        "from":"$kamal","from_device":"dev-k","ciphertext":"${FakeMlsEngine.ciphertext(1, 1, kamal, "dev-k", json)}",
        "generation":1,"epoch":1,"server_ts":"2026-10-08T09:15:30.456Z"}}""",
    )

    @Test fun sendingAnActionQueuesARowAndEncryptsTheEnvelopeIntoTheOfficialGroup() = runTest {
        val e = engine()
        val id = e.sendRisiControl(official, RisiControl.action("5c0e1d2f-3a4b-4c5d-8e6f-7a8b9c0d1e2f", "confirm"))!!
        advanceUntilIdle()
        val row = messages.rows[id]!!
        assertEquals(MessageEntity.KIND_RISI_CTL, row.kind)
        assertEquals("SENT", row.status)
        assertEquals("Confirmed", row.body)
        val sent = realtime.sentGroup.single()
        assertEquals(official, sent.conversationId)
        val plain = String(Base64.getDecoder().decode(sent.ciphertext))
        assertTrue(plain, plain.contains("risi_action") && plain.contains("confirm") && plain.contains("5c0e1d2f-3a4b-4c5d-8e6f-7a8b9c0d1e2f"))
        assertEquals("You confirmed", RisiControl.line(row.systemJson, "You"))
    }

    @Test fun aMembersRequestInOfficialIsASmallReadLineWithNoUnreadAndNoNotification() = runTest {
        val e = engine()
        val json = ProtocolJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), RisiControl.request("r1", "summarise", null, 1L))
        e.onEvents(listOf(incoming(official, "m1", json)))
        val row = messages.rows.values.single()
        assertEquals(MessageEntity.KIND_RISI_CTL, row.kind)
        assertEquals(kamal, row.from)
        assertEquals("READ", row.status)
        assertEquals("Kamal asked Risi to summarise", RisiControl.line(row.systemJson, "Kamal"))
    }

    @Test fun theSameControlInPrivateStoresNothing() = runTest {
        val e = engine()
        val json = ProtocolJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), RisiControl.request("r1", "ask", "hi", null))
        e.onEvents(listOf(incoming(privateGrp, "m2", json)))
        assertTrue(messages.rows.isEmpty())
    }
}
