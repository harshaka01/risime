package lk.codegen.risime.ui.tabs

import android.app.Application
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import lk.codegen.risime.data.db.ChatTabEntity
import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.CallPath
import lk.codegen.risime.data.tabs.CallTarget
import lk.codegen.risime.data.tabs.Tab
import lk.codegen.risime.data.tabs.callPathOf
import lk.codegen.risime.data.tabs.callTargetFor
import lk.codegen.risime.data.tabs.dmChatForCall
import lk.codegen.risime.ui.group.groupCallBlockedText
import lk.codegen.risime.net.dmConversationId
import lk.codegen.risime.ui.chats.tabIcon
import lk.codegen.risime.ui.search.searchResults
import lk.codegen.risime.ui.search.withoutLocked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §24.9 per-tab search (in a chat: the tab on screen; global: a tab icon per hit) and §24.5 per-tab calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TabSearchCallsTest {
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val kamal = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val dm = dmConversationId(me, kamal)
    private val grp = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private val dmOfficial = "grp:6a7b8c9d-0e1f-4a2b-8c3d-4e5f6a7b8c9d"
    private val grpOfficial = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"
    private val rows = mapOf(dmOfficial to ChatTabEntity(dmOfficial, dm, ChatTabEntity.TAB_OFFICIAL, ChatTabEntity.KIND_DM))
    private val contacts = listOf(ContactEntity("+94771234568", "Kamal", "Rise", kamal, true))

    private var n = 0
    private fun m(conv: String, from: String, body: String) =
        MessageEntity("c${++n}", "m$n", conv, from, if (conv.startsWith("grp:")) conv else if (from == me) kamal else me, body, null, n.toLong(), "READ", from == me)

    // ---- search ----

    @Test fun searchInAChatCoversTheTabOnScreenOnly() {
        assertEquals(dm, searchScopeOf(dm, TabContent.Private))
        assertEquals(dmOfficial, searchScopeOf(dm, TabContent.Official(dmOfficial, readOnly = false)))
        assertEquals(dmOfficial, searchScopeOf(dm, TabContent.Official(dmOfficial, readOnly = true)))
        assertNull(searchScopeOf(dm, TabContent.Intro(false, null)))
        assertEquals("Search in Official", tabSearchPlaceholder(Tab.OFFICIAL))
        // The view model queries exactly that conversation.
        val asked = mutableListOf<String>()
        val vm = ChatSearchViewModel(dmOfficial, Tab.OFFICIAL) { conv, _, _ -> asked += conv; flowOf(listOf(m(conv, kamal, "quote"))) }
        vm.onQuery("quote")
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { vm.results.collect {} }
        var hits: List<MessageEntity> = emptyList()
        val until = System.currentTimeMillis() + 5_000
        while (hits.isEmpty() && System.currentTimeMillis() < until) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            hits = vm.results.value
        }
        job.cancel()
        assertEquals(listOf(dmOfficial), asked.distinct())
        assertTrue(hits.all { it.conversationId == dmOfficial })
    }

    @Test fun globalSearchShowsTheTabAndOpensOfficialOnItsTab() {
        val msgs = listOf(m(dm, kamal, "quote private"), m(dmOfficial, kamal, "quote official"), m(dmOfficial, me, "my quote official"))
        val r = searchResults("quote", contacts, msgs, me, tabsOn = true) { rows[it] }
        assertEquals(3, r.messages.size)
        val priv = r.messages.single { it.message.conversationId == dm }
        assertEquals(Tab.PRIVATE, priv.tab)
        assertEquals(kamal, priv.openTarget)
        val off = r.messages.filter { it.message.conversationId == dmOfficial }
        assertTrue(off.all { it.tab == Tab.OFFICIAL && it.openTarget == dmOfficial && it.peerName == "Kamal" })
        assertEquals("● ", tabIcon(off.first().tab))
        assertEquals("🔒 ", tabIcon(priv.tab))
        // Tabs off: no icon, as before.
        assertTrue(searchResults("quote", contacts, msgs, me, tabsOn = false) { rows[it] }.messages.all { it.tab == null })
        // A locked chat hides both of its tabs' messages (by chat id).
        val locked = withoutLocked(r, setOf(dm), me) { conv -> rows[conv]?.chatId ?: conv }
        assertTrue(locked.messages.isEmpty())
    }

    // ---- calls ----

    @Test fun callsArePerTab() {
        // Private 1:1: §16/§19 on the dm:.
        assertEquals(CallTarget(dm, CallPath.PEER_TO_PEER), callTargetFor(dm, Tab.PRIVATE, dmOfficial))
        // v1.33 §24.5 (NEXT-PHASE D1) Official 1:1: the same §16/§19 call on the dm:, never §20 on the grp:.
        assertEquals(CallTarget(dm, CallPath.PEER_TO_PEER), callTargetFor(dm, Tab.OFFICIAL, dmOfficial))
        // ... also before this phone holds the Official conversation (the peer is known from the dm:).
        assertEquals(CallTarget(dm, CallPath.PEER_TO_PEER), callTargetFor(dm, Tab.OFFICIAL, null))
        // Groups: §20 on the tab's own grp: (a group's Official stays SFU).
        assertEquals(CallTarget(grp, CallPath.SFU), callTargetFor(grp, Tab.PRIVATE, grpOfficial))
        assertEquals(CallTarget(grpOfficial, CallPath.SFU), callTargetFor(grp, Tab.OFFICIAL, grpOfficial))
        // A group without an Official conversation yet: nothing to call from Official.
        assertNull(callTargetFor(grp, Tab.OFFICIAL, null))
        assertEquals(CallPath.PEER_TO_PEER, callPathOf(dm))
    }

    @Test fun placeCallRedirectsA1to1OfficialGrpToItsDm() {
        // CallManager.placeCall's guard: a grp: whose MLS row is Official with chat_kind dm calls on its dm:.
        assertEquals(dm, dmChatForCall(dmOfficial, rows))
        // A group's Official, a Private group, a dm: and an unknown grp: are left alone.
        val groupRows = rows + (grpOfficial to ChatTabEntity(grpOfficial, grp, ChatTabEntity.TAB_OFFICIAL, ChatTabEntity.KIND_GROUP))
        assertNull(dmChatForCall(grpOfficial, groupRows))
        assertNull(dmChatForCall(grp, groupRows))
        assertNull(dmChatForCall(dm, groupRows))
        assertNull(dmChatForCall(dmOfficial, null))
        // A Risi chat (official, chat_kind risi) is never a call target redirect.
        val risiConv = "grp:7b8c9d0e-1f2a-4b3c-9d4e-5f6a7b8c9d0e"
        assertNull(dmChatForCall(risiConv, mapOf(risiConv to ChatTabEntity(risiConv, risiConv, ChatTabEntity.TAB_OFFICIAL, ChatTabEntity.KIND_RISI))))
    }

    @Test fun groupCallButtonsHonourServerUnavailable() {
        // v1.33 §20.1: no LiveKit on the server → disabled with its own text, even when everyone is ready.
        assertEquals(
            "Group calls aren't available on this server yet",
            groupCallBlockedText(thisPhone = true, unavailable = "server", encrypted = true, ready = true),
        )
        // This phone's own reason still comes first.
        assertEquals(
            lk.codegen.risime.calls.CallTexts.GROUP_UPDATE_TEXT,
            groupCallBlockedText(thisPhone = false, unavailable = "server", encrypted = true, ready = true),
        )
        assertEquals(
            lk.codegen.risime.calls.CallTexts.GROUP_NOT_READY_TEXT,
            groupCallBlockedText(thisPhone = true, unavailable = null, encrypted = true, ready = false),
        )
        assertNull(groupCallBlockedText(thisPhone = true, unavailable = null, encrypted = true, ready = true))
    }
}

@Suppress("unused")
private fun <T> Flow<T>.unused() = this
