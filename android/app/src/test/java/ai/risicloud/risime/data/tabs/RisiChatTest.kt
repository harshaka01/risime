package ai.risicloud.risime.data.tabs

import kotlinx.coroutines.runBlocking
import ai.risicloud.risime.data.db.ChatTabEntity
import ai.risicloud.risime.net.ApiResult
import ai.risicloud.risime.net.GroupMeta
import ai.risicloud.risime.net.ProtocolJson
import ai.risicloud.risime.net.RisiChatReply
import ai.risicloud.risime.ui.chats.ChatRow
import ai.risicloud.risime.data.db.LastMessage
import ai.risicloud.risime.ui.chats.RISI_NEW_TARGET
import ai.risicloud.risime.ui.chats.applyRisiRows
import ai.risicloud.risime.ui.chats.mergeTabRows
import ai.risicloud.risime.ui.tabs.RisiHost
import ai.risicloud.risime.ui.tabs.sendFromComposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §25 A8: the Risi chat from MLS state, its chat-list row, its composer and its first open. */
class RisiChatTest {
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val risiConv = "grp:2f3a4b5c-6d7e-4f8a-9b0c-1d2e3f4a5b6c"
    private val other = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"

    private fun read(name: String): String =
        javaClass.classLoader!!.getResource("contract/v1/examples/$name")?.readText() ?: error("missing $name")

    private val risiMeta = GroupMeta(name = "", admins = listOf(me), tab = "official", chatId = risiConv, agents = listOf("r"), chatKind = "risi")

    @Test fun tabFromMlsMarksTheRisiChat() {
        val t = tabFromMls(risiConv, risiMeta)!!
        assertEquals(ChatTabEntity(risiConv, risiConv, ChatTabEntity.TAB_OFFICIAL, ChatTabEntity.KIND_RISI), t)
        assertTrue(t.risi)
        // A normal Official is not; nor is a Private group.
        assertFalse(tabFromMls(other, risiMeta.copy(chatKind = null, chatId = "grp:private"))!!.risi)
        assertFalse(tabFromMls(other, GroupMeta(name = "x"))!!.risi)
        // The Risi chat is never some chat's Official tab.
        assertNull(officialConversationOf(risiConv, mapOf(risiConv to t)))
        assertTrue(isRisiChat(risiConv, mapOf(risiConv to t)))
    }

    private fun groupRow(conv: String, name: String, ts: Long? = null, stateLine: String? = null) = ChatRow(
        userId = null, name = name, company = "", registered = true,
        last = ts?.let { LastMessage(conv, "hi", it, false, "READ") }, conversationId = conv, group = true, stateLine = stateLine,
    )

    private val tabs = mapOf(risiConv to tabFromMls(risiConv, risiMeta)!!, other to tabFromMls(other, GroupMeta(name = "Team"))!!)

    @Test fun chatListShowsOneRisiRowFirstOnlyWithRisiTools() {
        val rows = listOf(groupRow(other, "Team", 200), groupRow(risiConv, "", 100))
        val merged = mergeTabRows(rows, tabs, emptyMap(), me, true)
        assertEquals(2, merged.size) // never merged into another chat
        val on = applyRisiRows(merged, tabs, risiOn = true)
        assertEquals(listOf("Risi", "Team"), on.map { it.name })
        assertTrue(on.first().risi && on.first().conversationId == risiConv)
        val off = applyRisiRows(merged, tabs, risiOn = false)
        assertEquals(listOf("Team"), off.map { it.name })
    }

    @Test fun anEntryCreatesTheRisiChatWhenThereIsNone() {
        val rows = listOf(groupRow(other, "Team", 200))
        val on = applyRisiRows(rows, tabs, risiOn = true)
        assertEquals(RISI_NEW_TARGET, on.first().target)
        assertTrue(on.first().openable)
        // After leaving it: the old one stays (read-only) and the entry is back.
        val left = applyRisiRows(listOf(groupRow(risiConv, "", 100, stateLine = "You left")), tabs, risiOn = true)
        assertEquals(listOf(RISI_NEW_TARGET, risiConv), left.map { it.target })
        // Not before the tab rows are known; never without risi_tools.
        assertTrue(applyRisiRows(rows, null, risiOn = true).none { it.risi })
        assertTrue(applyRisiRows(rows, tabs, risiOn = false).none { it.risi })
    }

    private class Host : RisiHost {
        val asks = mutableListOf<String>()
        override val me = "me"
        override fun ask(text: String) { asks += text }
        override fun summarise() = Unit
        override fun report() = Unit
        override fun act(target: String, action: String, editText: String?, editDue: String?) = Unit
        override fun feedback(callRef: String, rating: String, reason: String?) = Unit
    }

    @Test fun afterAReinstallTheServerListedRisiChatIsTheRisiRowNeverAStrayGroup() {
        // P0 2026-10-12: no MLS state yet (no chat_tabs row): the server's hint makes it the Risi row.
        val rows = listOf(groupRow(risiConv, "Rejoining group…", 100), groupRow(other, "Team", 50))
        val noRisiRow = mapOf(other to tabFromMls(other, GroupMeta(name = "Team"))!!)
        val pending = setOf(risiConv)
        val merged = mergeTabRows(rows, noRisiRow, mapOf(risiConv to risiConv), me, showTabIcon = true, pendingRisi = pending)
        val out = applyRisiRows(merged, noRisiRow, risiOn = true, pendingRisi = pending)
        assertEquals(listOf("Risi", "Team"), out.map { it.name })
        assertTrue(out.first().risi && out.first().conversationId == risiConv)
        assertTrue("no second 'Risi' entry", out.none { it.target == RISI_NEW_TARGET })
        assertTrue(out.none { it.name == "Rejoining group…" || it.name == "Group" })
        // Without the hint the old behaviour: hidden, plus the create entry (the reported "Rejoining group…" path).
        val old = applyRisiRows(mergeTabRows(rows, noRisiRow, mapOf(risiConv to risiConv), me, showTabIcon = true), noRisiRow, risiOn = true)
        assertTrue(old.none { it.conversationId == risiConv })
        // The MLS row wins over the hint: a Private group is never shown as Risi on the server's word.
        assertFalse(isRisiChatOrPending(other, noRisiRow, setOf(other)))
        assertTrue(isRisiChatOrPending(risiConv, noRisiRow, pending))
        assertTrue(isRisiChatOrPending(risiConv, tabs, emptySet()))
    }

    @Test fun theServerSaysRisiChatOnlyForItsExactShape() {
        assertTrue(serverSaysRisiChat(risiConv, "official", risiConv.uppercase(), "risi"))
        assertFalse(serverSaysRisiChat(risiConv, "official", other, "risi"))
        assertFalse(serverSaysRisiChat(risiConv, "private", risiConv, "risi"))
        assertFalse(serverSaysRisiChat(risiConv, "official", risiConv, "group"))
        assertFalse(serverSaysRisiChat(risiConv, "official", null, "risi"))
    }

    @Test fun theRisiChatComposerAlwaysAsksNeverPlainText() {
        val h = Host()
        val plain = mutableListOf<String>()
        sendFromComposer(h, chip = false, text = "Am I free Tuesday?", plain = { plain += it }, risiChat = true)
        sendFromComposer(null, chip = false, text = "lost", plain = { plain += it }, risiChat = true)
        assertEquals(listOf("Am I free Tuesday?"), h.asks)
        assertTrue(plain.isEmpty())
    }

    @Test fun ownAskShowsAsABubbleText() {
        val json = ProtocolJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), RisiControl.request("r1", "ask", "Who is my dentist", null))
        assertEquals("Who is my dentist", RisiControl.askText(json))
        assertNull(RisiControl.askText(ProtocolJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), RisiControl.request("r2", "summarise", null, 0))))
    }

    private val reply = ProtocolJson.decodeFromString<RisiChatReply>(read("risi_chat_create_reply.json"))

    @Test fun firstOpenCreatesAndQueuesEpochZeroOnce() = runBlocking {
        val queued = mutableListOf<String>()
        val applied = mutableListOf<String>()
        var hasMls = false
        val opener = RisiChatOpener(
            enabled = { true }, create = { ApiResult.Ok(reply) }, applyGroup = { applied += it.id },
            hasMlsGroup = { hasMls }, queueEpoch0 = { queued += it },
        )
        assertEquals(RisiChatOpen.Ready(risiConv), opener.open())
        assertEquals(listOf(risiConv), queued)
        hasMls = true
        assertEquals(RisiChatOpen.Ready(risiConv), opener.open())
        assertEquals(1, queued.size)
        assertEquals(2, applied.size)
        // An existing, active chat queues nothing.
        val active = RisiChatOpener({ true }, { ApiResult.Ok(reply.copy(group = reply.group.copy(state = "active"))) }, {}, { false }, { queued += it })
        active.open()
        assertEquals(1, queued.size)
    }

    @Test fun firstOpenFailuresAndNoRisiTools() = runBlocking {
        var called = false
        val off = RisiChatOpener({ false }, { called = true; ApiResult.Ok(reply) }, {}, { false }, {})
        assertTrue(off.open() is RisiChatOpen.Failed)
        assertFalse(called)
        val down = RisiChatOpener({ true }, { ApiResult.Error(503, "agent_unavailable", "") }, {}, { false }, {})
        assertEquals(RisiChatOpen.Failed("Risi isn't available right now. Try again later."), down.open())
    }
}
