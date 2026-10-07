package lk.codegen.risime.ui.chat

import android.app.Application
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import lk.codegen.risime.AppContainer
import lk.codegen.risime.BuildConfig
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.AppDatabase
import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.DeleteOutboxEntity
import lk.codegen.risime.data.db.GroupEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.db.ReactionEntity
import lk.codegen.risime.data.deletes.DeleteFeature
import lk.codegen.risime.data.deletes.DeleteJson
import lk.codegen.risime.data.deletes.DeleteRules
import lk.codegen.risime.net.GroupMember
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.User
import lk.codegen.risime.net.dmConversationId
import lk.codegen.risime.ui.chats.ChatsScreen
import lk.codegen.risime.ui.chats.ChatsViewModel
import lk.codegen.risime.ui.friends.FriendsViewModel
import lk.codegen.risime.ui.group.GroupChatScreen
import lk.codegen.risime.ui.group.GroupChatViewModel
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * §15.7 the delete send UI, on by default since nightly.18 (§15.11), driven through the real
 * screens, ViewModels and [AppContainer] (Room on the bundled driver; the server is unreachable, so
 * every request waits in the outbox): long-press → Delete → Delete for me / for everyone, the
 * "older app versions" small print, Select mode with the count and the bin, Clear/Delete chat from
 * the chat list, the group admin rule, and a tombstone that carries no reactions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp", application = Application::class)
class DeleteFlowUiTest {
    @get:Rule val rule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val me = "5b0c1f3e-0000-4000-8000-00000000001a"
    private val kamal = "5b0c1f3e-0000-4000-8000-00000000001b"
    private val sunil = "5b0c1f3e-0000-4000-8000-00000000001c"
    private val dm = dmConversationId(me, kamal)
    private val grp = "grp:5b0c1f3e-0000-4000-8000-0000000000f1"

    private val c: AppContainer by lazy {
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { File(app.filesDir, "datastore/d-${System.nanoTime()}.preferences_pb") }
        runBlocking {
            prefs.edit {
                it[stringPreferencesKey("auth_kind")] = "DEV"
                it[stringPreferencesKey("token")] = "dev-token"
                it[stringPreferencesKey("user")] = ProtocolJson.encodeToString(User.serializer(), User(me, "+10000000001", "Me", "Co"))
                it[stringPreferencesKey("server_url")] = "http://127.0.0.1:9"
            }
        }
        AppContainer(app, { ctx ->
            Room.databaseBuilder(ctx, AppDatabase::class.java, "d-${System.nanoTime()}.db").setDriver(BundledSQLiteDriver()).build()
        }, prefs, transactions = { inlineTx }).also { container ->
            runBlocking {
                container.db.contacts().upsertAll(
                    listOf(
                        ContactEntity("+10000000002", "Kamal", "Rise", kamal, registered = true, friend = true, groupReady = true),
                        ContactEntity("+10000000003", "Sunil", "Rise", sunil, registered = true, friend = true, groupReady = true),
                    ),
                )
            }
        }
    }

    /** The bundled driver has no SupportSQLiteOpenHelper for withTransaction: each DAO call is its own transaction. */
    private val inlineTx = object : lk.codegen.risime.data.TransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T = block()
    }

    @After fun tearDown() {
        DeleteFeature.sendEnabled = BuildConfig.DELETES_SEND_ENABLED
        scope.cancel()
    }

    /** A version-1 (time) UUID [agoMs] in the past, as the server's message ids are. */
    private fun timeUuid(agoMs: Long): String {
        val ticks = (System.currentTimeMillis() - agoMs) * 10_000 + 0x01B21DD213814000L + (System.nanoTime() % 10_000).let { if (it < 0) -it else it }
        val msb = (ticks and 0xFFFFFFFFL shl 32) or (ticks ushr 32 and 0xFFFFL shl 16) or 0x1000L or (ticks ushr 48 and 0x0FFFL)
        return UUID(msb, (0x8000L shl 48) or (System.nanoTime() and 0x3FFFFFFFFFFFL)).toString()
    }

    private var seq = 0L

    private fun msg(conv: String, body: String, mine: Boolean, from: String = if (mine) me else kamal, agoMs: Long = 60_000, kind: String = MessageEntity.KIND_TEXT): MessageEntity {
        val id = timeUuid(agoMs)
        val ts = Instant.ofEpochMilli(System.currentTimeMillis() - agoMs).toString()
        return MessageEntity(
            UUID.randomUUID().toString(), id, conv, from, if (conv.startsWith("grp:")) conv else if (mine) kamal else me, body, ts,
            System.currentTimeMillis() - agoMs + seq++, if (mine) MessageStatus.DELIVERED.name else MessageStatus.READ.name, mine, kind = kind,
        )
    }

    private fun put(vararg ms: MessageEntity) = runBlocking { ms.forEach { c.db.messages().insert(it) } }

    private fun bubble(text: String): SemanticsNodeInteraction = rule.onNodeWithContentDescription(text, substring = true)

    private fun longPress(text: String) = bubble(text).performTouchInput { longClick() }

    private fun waitText(t: String, ms: Long = 5_000) = rule.waitUntil(ms) { rule.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitDesc(t: String, ms: Long = 5_000) = rule.waitUntil(ms) {
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(t, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun gone(t: String) = rule.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isEmpty()

    private fun row(clientMsgId: String) = runBlocking { c.db.messages().byClientMsgId(clientMsgId) }

    private fun outbox(): List<DeleteOutboxEntity> = runBlocking { c.db.deletes().queued() }

    @Test fun theSendUiIsOnByDefault() {
        assertTrue("nightly.18 ships the delete send UI (§15.11)", BuildConfig.DELETES_SEND_ENABLED)
        assertTrue(DeleteFeature.sendEnabled)
    }

    @Test fun dmLongPressDeleteForEveryoneAndForMe() {
        val mine = msg(dm, "my recent note", mine = true)
        val old = msg(dm, "my old note", mine = true, agoMs = DeleteRules.WINDOW_MS + 3_600_000)
        val theirs = msg(dm, "kamal says hi", mine = false)
        put(old, mine, theirs)
        // Kamal's 👍 on my message: it goes with the message.
        runBlocking {
            c.db.reactions().upsert(ReactionEntity(dm, mine.messageId!!, kamal, "👍", "add", "add", mine.serverTs, timeUuid(1_000), false, null, 1))
        }
        val vm = ChatViewModel(c, me, kamal)
        rule.setContent { RisiMeTheme { ChatScreen(vm, onBack = {}) } }
        waitDesc("my recent note")
        waitDesc("Reactions: 👍 1")

        // My own message within 48 h: both options, and (no deletes_ready from the server) the old-apps line.
        longPress("my recent note")
        waitText("Delete")
        rule.onNodeWithText("Select").assertExists()
        rule.onNodeWithText("Delete").performClick()
        waitText("Delete message?")
        rule.onNodeWithText("Delete for everyone").assertExists()
        rule.onNodeWithText("Delete for me").assertExists()
        rule.onNodeWithText(DeleteRules.SEEN_HINT + ". " + DeleteRules.OLD_APPS_HINT + ".").assertExists()
        rule.onNodeWithText("Delete for everyone").performClick()

        // Shown as my tombstone at once (no reactions); the request waits in the outbox with the target.
        waitDesc(DeleteRules.YOU_DELETED)
        assertEquals(MessageEntity.DELETE_STATE_DELETING, row(mine.clientMsgId)!!.deleteState)
        rule.waitUntil(5_000) { outbox().any { it.scope == "everyone" } }
        val o = outbox().single { it.scope == "everyone" }
        assertEquals(listOf(mine.messageId!!.lowercase()), DeleteJson.ids(o.targetsJson))
        assertTrue("a tombstone shows no reaction chips", gone("👍 1") && rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Reactions:", substring = true)).fetchSemanticsNodes().isEmpty())

        // Older than 48 h: only "Delete for me", no small print.
        longPress("my old note")
        waitText("Select")
        rule.onNodeWithText("Delete").performClick()
        waitText("Delete message?")
        assertTrue(gone("Delete for everyone"))
        assertTrue(gone(DeleteRules.SEEN_HINT))
        rule.onNodeWithText("Cancel").performClick()

        // The other person's message: only "Delete for me"; it disappears and a `me` request is queued.
        longPress("kamal says hi")
        waitText("Select")
        rule.onNodeWithText("Delete").performClick()
        waitText("Delete message?")
        assertTrue(gone("Delete for everyone"))
        rule.onNodeWithText("Delete for me").performClick()
        rule.waitUntil(5_000) { row(theirs.clientMsgId) == null }
        rule.waitUntil(5_000) { outbox().any { it.scope == "me" } }
        assertEquals(listOf(theirs.messageId!!.lowercase()), DeleteJson.ids(outbox().single { it.scope == "me" }.targetsJson))
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("kamal says hi", substring = true)).fetchSemanticsNodes().isEmpty() }

        // A tombstone's long-press offers only "Delete for me".
        bubble(DeleteRules.YOU_DELETED).performTouchInput { longClick() }
        waitText("Delete message?")
        assertTrue(gone("Delete for everyone"))
        rule.onNodeWithText("Cancel").performClick()
    }

    @Test fun deletesReadyHidesTheOldAppsLine() {
        val mine = msg(dm, "ready chat", mine = true)
        put(mine)
        val vm = ChatViewModel(c, me, kamal)
        vm.del.deletesReady.value = true
        rule.setContent { RisiMeTheme { ChatScreen(vm, onBack = {}) } }
        waitDesc("ready chat")
        longPress("ready chat")
        waitText("Select")
        rule.onNodeWithText("Delete").performClick()
        waitText("Delete message?")
        rule.onNodeWithText(DeleteRules.SEEN_HINT + ".").assertExists()
        assertTrue(gone(DeleteRules.OLD_APPS_HINT))
    }

    @Test fun selectModeCountsTogglesAndDeletesTheSelection() {
        val ms = (1..4).map { msg(dm, "pick $it", mine = true, agoMs = 120_000L - it * 1_000) }
        put(*ms.toTypedArray())
        val vm = ChatViewModel(c, me, kamal)
        rule.setContent { RisiMeTheme { ChatScreen(vm, onBack = {}) } }
        waitDesc("pick 4")
        longPress("pick 1")
        waitText("Select")
        rule.onNodeWithText("Select").performClick()
        waitText("1 selected")
        bubble("pick 2").performClick()
        bubble("pick 3").performClick()
        waitText("3 selected")
        bubble("pick 3").performClick() // toggles off
        waitText("2 selected")
        rule.onNodeWithContentDescription("Delete selected").performClick()
        waitText("Delete 2 messages?")
        rule.onNodeWithText("Delete for everyone").performClick()
        rule.waitUntil(5_000) { outbox().any { it.scope == "everyone" } }
        assertEquals(setOf(ms[0].messageId, ms[1].messageId), DeleteJson.ids(outbox().single().targetsJson).toSet())
        assertNull(row(ms[2].clientMsgId)!!.deleteState)
        assertTrue("select mode ends", gone("selected"))
    }

    @Test fun moreThan100SelectedGoAsRequestsOfAtMost100() {
        val ms = (1..105).map { msg(dm, "bulk $it", mine = true, agoMs = 600_000L - it * 1_000) }
        put(*ms.toTypedArray())
        val vm = ChatViewModel(c, me, kamal)
        rule.setContent { RisiMeTheme { ChatScreen(vm, onBack = {}) } }
        waitDesc("bulk 105")
        longPress("bulk 105")
        waitText("Select")
        rule.onNodeWithText("Select").performClick()
        waitText("1 selected")
        ms.dropLast(1).forEach { vm.del.select(it.clientMsgId) }
        waitText("105 selected")
        rule.onNodeWithContentDescription("Delete selected").performClick()
        waitText("Delete 105 messages?")
        rule.onNodeWithText("Delete for everyone").performClick()
        rule.waitUntil(5_000) { outbox().count { it.scope == "everyone" } == 2 }
        val sizes = outbox().map { DeleteJson.ids(it.targetsJson).size }.sortedDescending()
        assertEquals(listOf(100, 5), sizes)
    }

    @Test fun groupAdminDeletesAnyMessageAMemberOnlyTheirOwn() {
        runBlocking {
            c.db.groups().upsert(GroupEntity(grp, "Pilot team", GroupMember.ROLE_ADMIN, "active", me, null, 1, 0, null, null, 1))
        }
        val theirs = msg(grp, "sunil in group", mine = false, from = sunil, agoMs = DeleteRules.WINDOW_MS * 2)
        put(theirs)
        val vm = GroupChatViewModel(c, me, grp)
        rule.setContent { RisiMeTheme { GroupChatScreen(vm, meId = me, onBack = {}, onInfo = {}) } }
        waitDesc("sunil in group")
        rule.waitUntil(5_000) { vm.group.value != null }
        longPress("sunil in group")
        waitText("Select")
        rule.onNodeWithText("Delete").performClick()
        waitText("Delete message?")
        rule.onNodeWithText("Delete for everyone").performClick() // admin: any age, anyone's message
        rule.waitUntil(5_000) { outbox().any { it.scope == "everyone" } }

    }

    @Test fun aPlainGroupMemberDeletesOthersMessagesOnlyForThemselves() {
        runBlocking { c.db.groups().upsert(GroupEntity(grp, "Pilot team", GroupMember.ROLE_MEMBER, "active", sunil, null, 1, 0, null, null, 1)) }
        val other = msg(grp, "sunil again", mine = false, from = sunil)
        val mine = msg(grp, "mine in group", mine = true)
        put(other, mine)
        val vm = GroupChatViewModel(c, me, grp)
        rule.setContent { RisiMeTheme { GroupChatScreen(vm, meId = me, onBack = {}, onInfo = {}) } }
        waitDesc("sunil again")
        rule.waitUntil(5_000) { vm.group.value != null }
        longPress("sunil again")
        waitText("Select")
        rule.onNodeWithText("Delete").performClick()
        waitText("Delete message?")
        assertTrue(gone("Delete for everyone"))
        rule.onNodeWithText("Cancel").performClick()
        longPress("mine in group")
        waitText("Select")
        rule.onNodeWithText("Delete").performClick()
        waitText("Delete message?")
        rule.onNodeWithText("Delete for everyone").assertExists()
    }

    @Test fun clearAndDeleteChatFromTheChatList() {
        val a = msg(dm, "keep the chat", mine = false)
        put(a)
        val vm = ChatsViewModel(c, me)
        val fvm = FriendsViewModel(c)
        rule.setContent {
            RisiMeTheme { ChatsScreen(vm, fvm, onOpen = {}, onSettings = {}, onSearch = {}, onAddFriend = {}, onInvites = {}) }
        }
        waitText("Kamal")
        waitText("keep the chat")

        rule.onNodeWithText("Kamal").performTouchInput { longClick() }
        waitText("Clear chat")
        rule.onNodeWithText("Clear chat").performClick()
        waitText("Clear this chat?")
        rule.onAllNodesWithText("Clear chat").let { it.fetchSemanticsNodes() }
        rule.onNodeWithText("Clear chat").performClick()
        rule.waitUntil(5_000) { runBlocking { c.db.deletes().conversationRows(dm) }.isEmpty() }
        val st = runBlocking { c.db.deletes().chatState(dm) }
        assertNotNull("the watermark", st?.clearedUpto)
        assertFalse(st!!.hidden)
        rule.waitUntil(5_000) { outbox().any { it.scope == DeleteOutboxEntity.SCOPE_CLEAR } }
        waitText("Kamal") // the chat stays listed (a friend)
        assertTrue(gone("keep the chat"))

        // Delete chat: hidden from the list until a new message.
        put(msg(dm, "second round", mine = false, agoMs = 1_000))
        waitText("second round")
        rule.onNodeWithText("Kamal").performTouchInput { longClick() }
        waitText("Delete chat")
        rule.onNodeWithText("Delete chat").performClick()
        waitText("Delete this chat?")
        rule.onNodeWithText("Delete chat").performClick()
        rule.waitUntil(5_000) { runBlocking { c.db.deletes().chatState(dm) }?.hidden == true }
        rule.waitUntil(5_000) { gone("second round") }
    }
}
