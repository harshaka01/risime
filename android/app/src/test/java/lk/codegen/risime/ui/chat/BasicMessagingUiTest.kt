package lk.codegen.risime.ui.chat

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
import kotlinx.coroutines.flow.firstOrNull
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.AppDatabase
import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.db.StarEntity
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.User
import lk.codegen.risime.net.dmConversationId
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.After
import org.junit.Assert.assertEquals
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
 * v1.34 §33 through the real DM screen, ViewModel and [AppContainer] (Room on the bundled driver; no
 * server): the Forwarded labels, a reply's quote and the star icon; the selection bar's Star and Copy
 * (the §33.3 lines, "RisiMe", sensitive in Private); Reply from the menu with the composer's quote bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp", application = Application::class)
class BasicMessagingUiTest {
    @get:Rule val rule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val me = "5b0c1f3e-0000-4000-8000-00000000002a"
    private val kamal = "5b0c1f3e-0000-4000-8000-00000000002b"
    private val dm = dmConversationId(me, kamal)

    private val inlineTx = object : lk.codegen.risime.data.TransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T = block()
    }

    private val c: AppContainer by lazy {
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { File(app.filesDir, "datastore/m-${System.nanoTime()}.preferences_pb") }
        runBlocking {
            prefs.edit {
                it[stringPreferencesKey("auth_kind")] = "DEV"
                it[stringPreferencesKey("token")] = "dev-token"
                it[stringPreferencesKey("user")] = ProtocolJson.encodeToString(User.serializer(), User(me, "+10000000001", "Harsha", "Co"))
                it[stringPreferencesKey("server_url")] = "http://127.0.0.1:9"
            }
        }
        AppContainer(app, { ctx ->
            Room.databaseBuilder(ctx, AppDatabase::class.java, "m-${System.nanoTime()}.db").setDriver(BundledSQLiteDriver()).build()
        }, prefs, transactions = { inlineTx }).also { container ->
            runBlocking {
                container.db.contacts().upsertAll(listOf(ContactEntity("+10000000002", "Kamal", "Rise", kamal, registered = true, friend = true, groupReady = true)))
            }
        }
    }

    @After fun tearDown() {
        scope.cancel()
    }

    private var seq = 0L

    private fun timeUuid(agoMs: Long): String {
        val ticks = (System.currentTimeMillis() - agoMs) * 10_000 + 0x01B21DD213814000L + seq
        val msb = (ticks and 0xFFFFFFFFL shl 32) or (ticks ushr 32 and 0xFFFFL shl 16) or 0x1000L or (ticks ushr 48 and 0x0FFFL)
        return UUID(msb, (0x8000L shl 48) or (System.nanoTime() and 0x3FFFFFFFFFFFL)).toString()
    }

    private fun msg(body: String, mine: Boolean, agoMs: Long): MessageEntity {
        val id = timeUuid(agoMs)
        return MessageEntity(
            UUID.randomUUID().toString(), id, dm, if (mine) me else kamal, if (mine) kamal else me, body,
            Instant.ofEpochMilli(System.currentTimeMillis() - agoMs).toString(), System.currentTimeMillis() - agoMs + seq++,
            if (mine) MessageStatus.DELIVERED.name else MessageStatus.READ.name, mine,
        )
    }

    private fun put(vararg ms: MessageEntity) = runBlocking { ms.forEach { c.db.messages().insert(it) } }

    private fun waitText(t: String, ms: Long = 5_000) = rule.waitUntil(ms) { rule.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitDesc(t: String) = rule.waitUntil(5_000) {
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(t, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun labelsQuoteAndStar() {
        val original = msg("Can we move the site visit?", mine = false, agoMs = 300_000)
        val fwd = msg("Site visit moved to 3 PM", mine = false, agoMs = 200_000).copy(forwardHops = 1)
        val many = msg("Forward this to everyone", mine = false, agoMs = 150_000).copy(forwardHops = 7)
        val reply = msg("Yes, 3 works", mine = true, agoMs = 100_000).copy(replyToMessageId = original.messageId, replyToFrom = kamal)
        put(original, fwd, many, reply)
        runBlocking { c.db.stars().put(listOf(StarEntity(fwd.messageId!!, dm, 1))) }
        rule.setContent { RisiMeTheme { ChatScreen(ChatViewModel(c, me, kamal), onBack = {}) } }
        waitDesc("Yes, 3 works")
        rule.onNodeWithContentDescription("Forwarded, Site visit moved to 3 PM", substring = true).assertExists()
        rule.onNodeWithContentDescription("Forwarded many times, Forward this to everyone", substring = true).assertExists()
        waitDesc("starred")
        // The quote is rendered from this phone's copy of the target.
        waitDesc("Replying to Kamal: Can we move the site visit?")
    }

    @Test fun starAndCopyFromTheSelectionBar() {
        val a = msg("first line", mine = false, agoMs = 120_000)
        val b = msg("second line", mine = true, agoMs = 60_000)
        put(a, b)
        val vm = ChatViewModel(c, me, kamal)
        rule.setContent { RisiMeTheme { ChatScreen(vm, onBack = {}) } }
        waitDesc("second line")
        vm.del.select(a.clientMsgId)
        vm.del.select(b.clientMsgId)
        waitText("2 selected")
        rule.onNodeWithTag("sel_star").performClick()
        rule.waitUntil(5_000) { runBlocking { c.db.stars().all().size == 2 } }
        vm.del.select(a.clientMsgId)
        vm.del.select(b.clientMsgId)
        waitText("2 selected")
        rule.onNodeWithTag("sel_copy").performClick()
        rule.waitUntil(5_000) { (app.getSystemService(ClipboardManager::class.java).primaryClip?.itemCount ?: 0) > 0 }
        val clip = app.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals("RisiMe", clip.description.label)
        val text = clip.getItemAt(0).text.toString()
        val lines = text.split("\n")
        assertEquals(2, lines.size)
        assertTrue(text, lines[0].matches(Regex("^\\[[0-9/.\\-]+, [0-9:]+( ?[^\\]]+)?\\] Kamal: first line$")))
        assertTrue(text, lines[1].endsWith("] Harsha: second line"))
        // Private: marked sensitive (API 33+), so the clipboard preview doesn't show it.
        assertTrue(clip.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }

    @Test fun replyFromTheMenuSendsReplyTo() {
        val a = msg("Are you coming?", mine = false, agoMs = 60_000)
        put(a)
        val vm = ChatViewModel(c, me, kamal)
        rule.setContent { RisiMeTheme { ChatScreen(vm, onBack = {}) } }
        waitDesc("Are you coming?")
        rule.onNodeWithContentDescription("Are you coming?", substring = true).performTouchInput { longClick() }
        waitText("Reply")
        rule.onNodeWithText("Reply").performClick()
        waitText("Reply to Kamal")
        rule.onNodeWithTag("reply_bar").assertExists()
        // The quote bar's target goes with the message (the engine's send path the composer uses).
        val ref = vm.messaging.takeReply()
        runBlocking { c.engine.sendText(kamal, "Yes!", replyTo = ref) }
        fun rows(): List<MessageEntity> = runBlocking { c.db.messages().conversation(dm).firstOrNull().orEmpty() }
        rule.waitUntil(5_000) { rows().any { it.body == "Yes!" } }
        waitDesc("Yes!")
        assertTrue("the quote bar closes", rule.onAllNodes(hasText("Reply to Kamal")).fetchSemanticsNodes().isEmpty())
        val sent = rows().single { it.body == "Yes!" }
        assertEquals(a.messageId, sent.replyToMessageId)
        assertEquals(kamal, sent.replyToFrom)
    }
}
