package lk.codegen.risime.data.tabs

import kotlinx.coroutines.test.runTest
import lk.codegen.risime.data.ChatEngine
import lk.codegen.risime.data.FakeGroupDao
import lk.codegen.risime.data.FakeGroupOpDao
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.FakeRealtime
import lk.codegen.risime.data.FakeSyncDao
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.groups.GroupStore
import lk.codegen.risime.net.ChatEventData
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.Group
import lk.codegen.risime.net.GroupEvent
import lk.codegen.risime.net.GroupMeta
import lk.codegen.risime.net.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** §24.8: `chat_event` reaches the tabs hook in event order; group events hand the MLS meta (and the server's hint) to the tab store. */
class TabsEventsTest {
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val official = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"
    private val chat = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"

    private fun example(name: String) = File(System.getProperty("user.dir"), "../../contract/v1/examples/$name").readText()

    @Test fun chatEventsAreAppliedAndAdvanceTheCursor() = runTest {
        val seen = mutableListOf<ChatEventData>()
        val sync = FakeSyncDao()
        val engine = ChatEngine(
            messages = FakeMessageDao(), sync = sync,
            tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
            scope = this, realtime = { FakeRealtime() }, meId = { me },
            chatEvents = { _, e, _ -> seen += e },
        )
        val events = listOf("event_chat_official_created.json", "event_chat_official_off.json", "event_chat_official_on.json").map { ProtocolJson.decodeFromString<Event>(example(it)) }
        engine.onEvents(events)
        assertEquals(listOf("official_created", "official_off", "official_on"), seen.map { it.action })
        assertEquals(events.last().eventId, sync.cursor())
    }

    @Test fun anAppWithoutTabsSkipsChatEventsButMovesOn() = runTest {
        val sync = FakeSyncDao()
        val engine = ChatEngine(
            messages = FakeMessageDao(), sync = sync,
            tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
            scope = this, realtime = { FakeRealtime() }, meId = { me },
        )
        val e = ProtocolJson.decodeFromString<Event>(example("event_chat_official_off.json"))
        engine.onEvents(listOf(e))
        assertEquals(e.eventId, sync.cursor())
    }

    @Test fun groupStoreHandsTheMlsMetaAndTheServerHintToTheTabs() = runTest {
        val metas = mutableListOf<Pair<String, GroupMeta?>>()
        val hints = mutableListOf<Triple<String, String?, String?>>()
        var meta: GroupMeta? = null
        val store = GroupStore(
            FakeGroupDao(), FakeGroupOpDao(), FakeMessageDao(), { "dev" }, { meta },
            onMlsMeta = { c, m -> metas += c to m }, onServerTab = { c, t, id -> hints += Triple(c, t, id) },
        )
        val ev = ProtocolJson.decodeFromString<Event>(example("event_group_created_official.json")).groupEvent()!!
        store.applyEvent("e1", ev, me)
        assertEquals(listOf(Triple(official, "official", chat)), hints)
        assertEquals(listOf<Pair<String, GroupMeta?>>(official to null), metas) // not readable yet: nothing recorded
        meta = GroupMeta(name = "Site team", tab = "official", chatId = chat, agents = listOf("9e1f0000-0000-4000-8000-000000000001"))
        store.onGroupStateChanged(official, removedSelf = false)
        assertEquals(official to meta, metas.last())
        store.applyServerGroup(Group(official, tab = "official", chatId = chat), me)
        assertEquals(official to meta, metas.last())
        // A pre-v1.24 group_event carries no tab: no hint.
        store.applyEvent("e2", GroupEvent(chat, 1, 1, GroupEvent.METADATA_CHANGED, me), me)
        assertEquals(Triple(chat, null, null), hints.last())
    }
}
