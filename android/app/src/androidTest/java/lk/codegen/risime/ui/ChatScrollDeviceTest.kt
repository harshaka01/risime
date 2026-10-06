package lk.codegen.risime.ui

import android.app.Activity
import android.app.Application
import android.app.UiAutomation
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.ui.chat.Bubble
import lk.codegen.risime.ui.chat.ChatItem
import lk.codegen.risime.ui.chat.Composer
import lk.codegen.risime.ui.chat.DmMessageRow
import lk.codegen.risime.ui.common.CHAT_LIST_TAG
import lk.codegen.risime.ui.common.ChatMessageList
import lk.codegen.risime.ui.common.ChatScrollState
import lk.codegen.risime.ui.common.DaySeparator
import lk.codegen.risime.ui.common.NEW_MESSAGES_COUNT_TAG
import lk.codegen.risime.ui.common.NEW_MESSAGES_LABEL
import lk.codegen.risime.ui.common.RisiTopBar
import lk.codegen.risime.ui.common.rememberChatScrollState
import lk.codegen.risime.ui.group.GroupMessageList
import lk.codegen.risime.ui.theme.RisiMeTheme
import lk.codegen.risime.ui.theme.Spacing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Harsha's P3 on a real Android (redroid, Android 14 arm64): the chat scroll rules of `ChatScroll.kt`
 * with the real DM rows (Bubble) and group rows (GroupMessageList), inside the screens' own layout
 * (Scaffold, `imePadding`, Composer) in an edge-to-edge, adjustResize activity. Long histories with
 * variable heights and day separators; data comes through a StateFlow, as from Room.
 *
 * Covers: opening at the latest (preloaded and loaded after the first frame), a 50-message replay
 * in several emissions, a fresh-install restore (pages, and older history landing under a live
 * message), a group rejoin (messages after the Welcome, with and without the old history), the real
 * IME opening and closing, a real rotation (the activity is recreated), and scrolled up + incoming
 * showing "New messages ↓" without moving.
 */
@RunWith(AndroidJUnit4::class)
class ChatScrollDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app = instr.targetContext.applicationContext as Application
    private var scenario: ActivityScenario<ComponentActivity>? = null

    private val base = System.currentTimeMillis() - 400L * 3_600_000
    private var seq = 0

    private fun msg(key: String, outgoing: Boolean = false, system: Boolean = false, from: String = "peer"): MessageEntity {
        val n = seq++
        val lines = if (system) 1 else 1 + (n * 7) % 9 // 1..9 lines: variable heights
        val body = if (system) "#$key system line" else (listOf("#$key") + List(lines - 1) { "line $it of a longer message with some words" }).joinToString("\n")
        return MessageEntity(
            clientMsgId = key, messageId = "s-$key", conversationId = "c", from = if (outgoing) "me" else from,
            to = "x", body = body, serverTs = null, localTs = base + n * 1_500_000L, status = "DELIVERED", outgoing = outgoing,
            kind = if (system) MessageEntity.KIND_SYSTEM else MessageEntity.KIND_TEXT,
        )
    }

    private fun history(n: Int, prefix: String = "h", group: Boolean = false) =
        List(n) { i -> msg("$prefix$i", outgoing = i % 4 == 0, from = if (group) "peer${i % 3}" else "peer") }

    // ---- host: the screens' layout, recreated with the activity ----

    private object Host {
        val rows = MutableStateFlow<List<MessageEntity>>(emptyList())
        var group = false
        @Volatile var scroll: ChatScrollState? = null
        @Volatile var activity: Activity? = null
    }

    @Composable
    private fun Screen() {
        val messages by Host.rows.collectAsState()
        val scroll = rememberChatScrollState()
        Host.scroll = scroll
        var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
        RisiMeTheme(dark = false) {
            Scaffold(topBar = { RisiTopBar(title = "Chat", onBack = {}) }, contentWindowInsets = WindowInsets(0)) { pad ->
                Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
                    if (Host.group) {
                        GroupMessageList(
                            messages = messages, meId = "me", nameOf = { "Name $it" }, memberName = { "Name $it" },
                            readOnly = false, reactions = emptyMap(), onReact = { _, _, _ -> }, onOpenReactions = {},
                            onRetry = {}, onDelete = {}, onInfo = {}, scroll = scroll,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    } else {
                        ChatMessageList(
                            messages = messages, modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.md),
                            spacing = Spacing.xs + Spacing.xxs, scroll = scroll,
                        ) { items, i ->
                            when (val item = items[i]) {
                                is ChatItem.Day -> DaySeparator(item.label)
                                is ChatItem.Msg -> DmMessageRow(item.m) {
                                    Bubble(
                                        item.m, canRetry = true, onRetry = {}, onDelete = {}, chips = emptyList(),
                                        canReact = true, onReact = { _, _ -> }, onOpenReactions = {},
                                    )
                                }
                            }
                        }
                    }
                    Composer(value = draft, onValue = { draft = it }, onSend = {})
                }
            }
        }
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: Activity, s: Bundle?) {}
        override fun onActivityPostCreated(a: Activity, s: Bundle?) {
            if (a !is ComponentActivity) return
            a.enableEdgeToEdge()
            @Suppress("DEPRECATION")
            a.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            a.setContent { Screen() }
        }
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityResumed(a: Activity) { Host.activity = a }
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
        override fun onActivityDestroyed(a: Activity) { if (Host.activity === a) Host.activity = null }
    }

    @Before fun setUp() {
        Host.rows.value = emptyList()
        Host.scroll = null
        Host.activity = null
        app.registerActivityLifecycleCallbacks(callbacks)
        instr.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
    }

    @After fun tearDown() {
        scenario?.close()
        app.unregisterActivityLifecycleCallbacks(callbacks)
        instr.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
    }

    private fun open(group: Boolean, rows: List<MessageEntity>) {
        Host.group = group
        Host.rows.value = rows
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        rule.waitUntil(10_000) { Host.scroll != null }
        rule.waitForIdle()
    }

    /** One emission, then wait for the frame (or not: several emissions inside one frame). */
    private fun emit(vararg m: MessageEntity, frame: Boolean = true) {
        Host.rows.value = Host.rows.value + m
        if (frame) rule.waitForIdle()
    }

    private fun node(key: String) = rule.onNodeWithText("#$key", substring = true, useUnmergedTree = true)

    private fun assertAtBottom(key: String) {
        rule.waitForIdle()
        val s = Host.scroll!!
        assertTrue("list should be at the bottom (index ${s.list.firstVisibleItemIndex}, offset ${s.list.firstVisibleItemScrollOffset})", s.atBottom)
        node(key).assertIsDisplayed()
        val list = rule.onNodeWithTag(CHAT_LIST_TAG).getBoundsInRoot()
        val newest = node(key).getBoundsInRoot()
        assertTrue("newest message ends near the list bottom (${newest.bottom} vs ${list.bottom})", list.bottom - newest.bottom < 72.dp)
        assertTrue("newest message inside the list", newest.bottom <= list.bottom)
        rule.onNodeWithText(NEW_MESSAGES_LABEL).assertDoesNotExist()
    }

    private fun scrollUpByHand() {
        repeat(3) {
            rule.onNodeWithTag(CHAT_LIST_TAG).performTouchInput { swipeDown(startY = top + height * 0.2f, endY = bottom - height * 0.1f, durationMillis = 250) }
            rule.waitForIdle()
        }
        assertFalse("scrolled up", Host.scroll!!.atBottom)
    }

    private fun imeVisible(): Boolean {
        val a = Host.activity ?: return false
        var v = false
        instr.runOnMainSync { v = ViewCompat.getRootWindowInsets(a.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        return v
    }

    // ---- open ----

    @Test fun dmOpensAtTheLatestOf240() {
        val h = history(240)
        open(group = false, rows = h)
        assertAtBottom(h.last().clientMsgId)
        node("h0").assertDoesNotExist()
    }

    @Test fun groupOpensAtTheLatestOf240() {
        val h = history(240, group = true)
        open(group = true, rows = h)
        assertAtBottom(h.last().clientMsgId)
    }

    @Test fun opensAtTheLatestWhenRoomLoadsAfterTheFirstFrame() {
        open(group = false, rows = emptyList())
        val h = history(240)
        emit(*h.toTypedArray())
        assertAtBottom(h.last().clientMsgId)
    }

    // ---- replay / restore ----

    @Test fun aReplayOf50InSeveralEmissionsStaysAtTheBottom() {
        for (group in listOf(false, true)) {
            scenario?.close()
            val h = history(220, prefix = if (group) "g" else "d", group = group)
            open(group, h)
            // 3 emissions inside one frame, then 7 more each with its own frame (a sync batch).
            val batch = List(50) { msg("r${if (group) "g" else "d"}$it", from = "peer${it % 3}") }
            emit(*batch.subList(0, 5).toTypedArray(), frame = false)
            emit(*batch.subList(5, 10).toTypedArray(), frame = false)
            emit(*batch.subList(10, 15).toTypedArray())
            batch.drop(15).chunked(5).forEach { emit(*it.toTypedArray()); Thread.sleep(30) }
            assertAtBottom(batch.last().clientMsgId)
        }
    }

    @Test fun freshInstallRestoreInPagesOpensAtTheLatest() {
        open(group = true, rows = emptyList())
        val marker = msg("marker", system = true)
        val restored = history(250, prefix = "p", group = true)
        emit(marker)
        restored.chunked(50).forEach { emit(*it.toTypedArray()) }
        assertAtBottom(restored.last().clientMsgId)
    }

    @Test fun olderHistoryRestoredUnderALiveMessageKeepsTheLatestInView() {
        open(group = false, rows = emptyList())
        val restored = history(240, prefix = "o") // older: local_ts from server_ts
        val live = msg("live")
        emit(live)
        assertAtBottom("live")
        // The replay lands above the live message, in pages.
        restored.chunked(60).forEach { page -> Host.rows.value = (page + Host.rows.value).sortedBy { it.localTs }; rule.waitForIdle() }
        assertAtBottom("live")
    }

    // ---- rejoin ----

    @Test fun groupRejoinKeepsOldHistoryAndFollowsTheMessagesAfterTheWelcome() {
        val old = history(200, prefix = "k", group = true)
        open(group = true, rows = old + msg("rejoining", system = true))
        assertAtBottom("rejoining")
        // The Welcome: a system line, then the queued sends go out and the backlog arrives in emissions.
        emit(msg("rejoined", system = true))
        val after = List(40) { msg("w$it", outgoing = it % 5 == 0, from = "peer${it % 3}") }
        after.chunked(8).forEachIndexed { i, c -> emit(*c.toTypedArray(), frame = i % 2 == 1) }
        rule.waitForIdle()
        assertAtBottom(after.last().clientMsgId)
    }

    @Test fun groupRejoinAfterAWipeStartsEmptyThenOpensAtTheLatest() {
        open(group = true, rows = emptyList())
        emit(msg("rejoined", system = true))
        assertAtBottom("rejoined")
        val after = history(120, prefix = "x", group = true)
        after.chunked(30).forEach { emit(*it.toTypedArray()) }
        assertAtBottom(after.last().clientMsgId)
    }

    // ---- keyboard ----

    @Test fun theRealKeyboardKeepsTheLatestInView() {
        for (group in listOf(false, true)) {
            scenario?.close()
            val h = history(220, prefix = if (group) "kg" else "kd", group = group)
            open(group, h)
            assertAtBottom(h.last().clientMsgId)
            val listBefore = rule.onNodeWithTag(CHAT_LIST_TAG).getBoundsInRoot()
            rule.onNode(hasSetTextAction()).performClick()
            rule.waitUntil(10_000) { imeVisible() }
            rule.waitUntil(5_000) { rule.onNodeWithTag(CHAT_LIST_TAG).getBoundsInRoot().bottom < listBefore.bottom }
            assertAtBottom(h.last().clientMsgId)
            // A message arrives while typing.
            val m = msg("typing-${if (group) "g" else "d"}")
            emit(m)
            assertAtBottom(m.clientMsgId)
            // Keyboard down.
            instr.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitUntil(10_000) { !imeVisible() }
            rule.waitForIdle()
            assertAtBottom(m.clientMsgId)
        }
    }

    // ---- rotation ----

    private fun rotate(rotation: Int) {
        val before = Host.activity
        instr.uiAutomation.setRotation(rotation)
        rule.waitUntil(15_000) { Host.activity != null && Host.activity !== before }
        rule.waitUntil(10_000) { Host.scroll != null }
        rule.waitForIdle()
    }

    @Test fun rotationKeepsTheLatestInViewAndTheScrolledUpState() {
        val h = history(240, group = true)
        open(group = true, rows = h)
        assertAtBottom(h.last().clientMsgId)
        Host.scroll = null
        rotate(UiAutomation.ROTATION_FREEZE_90)
        val land = rule.onNodeWithTag(CHAT_LIST_TAG).getBoundsInRoot()
        assertTrue("landscape after rotation", land.right - land.left > land.bottom - land.top)
        assertAtBottom(h.last().clientMsgId)
        val m = msg("land")
        emit(m)
        assertAtBottom("land")
        // Scrolled up with two unseen: survives rotating back.
        scrollUpByHand()
        emit(msg("u1"), msg("u2"))
        rule.onNodeWithTag(NEW_MESSAGES_COUNT_TAG, useUnmergedTree = true).assertTextEquals("2")
        Host.scroll = null
        rotate(UiAutomation.ROTATION_FREEZE_0)
        assertFalse(Host.scroll!!.atBottom)
        rule.onNodeWithText(NEW_MESSAGES_LABEL).assertIsDisplayed()
        rule.onNodeWithTag(NEW_MESSAGES_COUNT_TAG, useUnmergedTree = true).assertTextEquals("2")
        rule.onNodeWithText(NEW_MESSAGES_LABEL).performClick()
        rule.waitUntil(5_000) { Host.scroll!!.atBottom }
        assertAtBottom("u2")
    }

    // ---- scrolled up ----

    @Test fun scrolledUpStaysPutAndShowsNewMessages() {
        for (group in listOf(false, true)) {
            scenario?.close()
            val h = history(240, prefix = if (group) "sg" else "sd", group = group)
            open(group, h)
            scrollUpByHand()
            val s = Host.scroll!!
            // A message fully in the viewport (keys are client_msg_ids; day separators are "day:…").
            val anchorKey = s.list.layoutInfo.visibleItemsInfo.map { it.key as String }.filter { !it.startsWith("day:") }.let { it[it.size / 2] }
            val anchorTop = node(anchorKey).getBoundsInRoot().top
            // Incoming one by one, then a replay of 50 in several emissions.
            emit(msg("in1-$group"))
            emit(msg("in2-$group"), msg("in3-$group"))
            rule.onNodeWithText(NEW_MESSAGES_LABEL).assertIsDisplayed()
            rule.onNodeWithTag(NEW_MESSAGES_COUNT_TAG, useUnmergedTree = true).assertTextEquals("3")
            assertTrue("the viewport did not move", abs((node(anchorKey).getBoundsInRoot().top - anchorTop).value) < 1f)
            val batch = List(50) { msg("b$group$it") }
            batch.chunked(10).forEachIndexed { i, c -> emit(*c.toTypedArray(), frame = i % 2 == 0) }
            rule.waitForIdle()
            rule.onNodeWithTag(NEW_MESSAGES_COUNT_TAG, useUnmergedTree = true).assertTextEquals("53")
            assertTrue("the viewport did not move", abs((node(anchorKey).getBoundsInRoot().top - anchorTop).value) < 1f)
            assertFalse(s.atBottom)
            // The button jumps to the newest and hides.
            rule.onNodeWithText(NEW_MESSAGES_LABEL).performClick()
            rule.waitUntil(5_000) { s.atBottom }
            assertAtBottom(batch.last().clientMsgId)
            assertEquals(0, s.unseen)
        }
    }
}
