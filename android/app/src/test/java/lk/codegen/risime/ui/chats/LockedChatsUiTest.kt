package lk.codegen.risime.ui.chats

import android.app.Application
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTextClearance
import lk.codegen.risime.data.db.LastMessage
import lk.codegen.risime.net.dmConversationId
import lk.codegen.risime.ui.lock.ChatLockAuth
import lk.codegen.risime.ui.lock.ChatLockAuthStatus
import lk.codegen.risime.ui.lock.LOCKED_CHATS_TITLE
import lk.codegen.risime.ui.lock.LocalChatLockAuth
import lk.codegen.risime.ui.lock.SCREEN_LOCK_NEEDED_TITLE
import lk.codegen.risime.ui.lock.chatLockStatus
import lk.codegen.risime.ui.lock.lockAuthenticators
import lk.codegen.risime.ui.lock.rememberLockGate
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LockedChatsUiTest {
    @get:Rule val rule = createComposeRule()

    private class FakeAuth(var status: ChatLockAuthStatus = ChatLockAuthStatus.READY, var ok: Boolean = true) : ChatLockAuth {
        var prompts = 0
        var lastTitle: String? = null
        override fun status() = status
        override suspend fun confirm(title: String, subtitle: String?): Boolean {
            prompts++
            lastTitle = title
            return ok
        }
    }

    private fun row(id: String, name: String) =
        ChatRow(userId = id, name = name, company = "", registered = true, last = LastMessage(dmConversationId("me", id), "hi", 1L, false, "READ"))

    // ---- list filtering ----

    @Test fun lockedChatsLeaveTheMainListAndFillTheFolder() {
        val rows = listOf(row("a", "Amal"), row("b", "Bimal"), row("c", "Chamari"))
        val locked = setOf(dmConversationId("me", "b"))
        val s = splitLocked(rows, locked, "me")
        assertEquals(listOf("Amal", "Chamari"), s.visible.map { it.name })
        assertEquals(listOf("Bimal"), s.locked.map { it.name })
        // Nothing locked: everything stays.
        assertEquals(3, splitLocked(rows, emptySet(), "me").visible.size)
        // Unknown (file busy): nothing at all, never a locked chat by mistake.
        assertEquals(LockedSplit(), splitLocked(rows, null, "me"))
    }

    @Test fun aLockedGroupLeavesTheMainListToo() {
        val g = ChatRow(null, "Pilot", "", true, null, conversationId = "grp:abc", group = true)
        val s = splitLocked(listOf(g, row("a", "Amal")), setOf("grp:abc"), "me")
        assertEquals(listOf("Amal"), s.visible.map { it.name })
        assertEquals(listOf("Pilot"), s.locked.map { it.name })
    }

    // ---- authenticators ----

    @Test fun phonesWithoutAFingerprintCanUseTheirScreenLockOnApi30Plus() {
        val both = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        assertEquals(both, lockAuthenticators(30))
        assertEquals(both, lockAuthenticators(34))
        assertEquals(BiometricManager.Authenticators.BIOMETRIC_STRONG, lockAuthenticators(29)) // API 28/29 can't combine them
        assertEquals(ChatLockAuthStatus.READY, chatLockStatus(BiometricManager.BIOMETRIC_SUCCESS))
        assertEquals(ChatLockAuthStatus.NEEDS_SCREEN_LOCK, chatLockStatus(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        assertEquals(ChatLockAuthStatus.NEEDS_SCREEN_LOCK, chatLockStatus(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
    }

    // ---- the confirmation gate ----

    @Test fun theActionRunsOnlyAfterTheConfirmation() {
        val auth = FakeAuth()
        var ran = 0
        rule.setContent {
            CompositionLocalProvider(LocalChatLockAuth provides auth) {
                RisiMeTheme {
                    val gate = rememberLockGate()
                    Button(onClick = { gate.run("Lock chat") { ran++ } }) { Text("go") }
                }
            }
        }
        rule.onNodeWithText("go").performClick()
        rule.waitForIdle()
        assertEquals(1, auth.prompts)
        assertEquals("Lock chat", auth.lastTitle)
        assertEquals(1, ran)
        // Cancelled / failed prompt: nothing happens.
        auth.ok = false
        rule.onNodeWithText("go").performClick()
        rule.waitForIdle()
        assertEquals(2, auth.prompts)
        assertEquals(1, ran)
    }

    @Test fun withoutAnyScreenLockTheUserIsToldWhy() {
        val auth = FakeAuth(status = ChatLockAuthStatus.NEEDS_SCREEN_LOCK)
        var ran = 0
        rule.setContent {
            CompositionLocalProvider(LocalChatLockAuth provides auth) {
                RisiMeTheme {
                    val gate = rememberLockGate()
                    Button(onClick = { gate.run("Lock chat") { ran++ } }) { Text("go") }
                    lk.codegen.risime.ui.lock.LockGateDialog(gate)
                }
            }
        }
        rule.onNodeWithText("go").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(SCREEN_LOCK_NEEDED_TITLE).assertIsDisplayed()
        assertEquals(0, auth.prompts)
        assertEquals(0, ran)
        rule.onNodeWithText("OK").performClick()
        rule.onNodeWithText(SCREEN_LOCK_NEEDED_TITLE).assertDoesNotExist()
    }

    // ---- the pull-down ----

    @Test fun pullingDownAtTheTopRevealsTheLockedChatsEntryAndScrollingUpHidesIt() {
        val pull = PullToRevealState()
        rule.setContent {
            RisiMeTheme {
                LazyColumn(Modifier.fillMaxSize().nestedScroll(pull.connection)) {
                    if (pull.revealed) item { LockedFolderEntry(2) {} }
                    items(List(40) { "row $it" }) { Text(it) }
                }
            }
        }
        rule.onNodeWithText(LOCKED_CHATS_TITLE).assertDoesNotExist()
        rule.onNodeWithText("row 0").assertIsDisplayed() // nothing shown at rest
        rule.onRoot().performTouchInput { swipeDown() }
        rule.waitForIdle()
        assertTrue(pull.revealed)
        rule.onNodeWithText(LOCKED_CHATS_TITLE).assertIsDisplayed()
        rule.onNodeWithContentDescription("$LOCKED_CHATS_TITLE, 2").assertIsDisplayed()
        rule.onNodeWithText("2").assertIsDisplayed()
        rule.onRoot().performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertFalse(pull.revealed)
        rule.onNodeWithText(LOCKED_CHATS_TITLE).assertDoesNotExist()
    }

    @Test fun anOrdinaryScrollDownTheListDoesNotRevealIt() {
        val pull = PullToRevealState()
        rule.setContent {
            RisiMeTheme {
                LazyColumn(Modifier.fillMaxSize().nestedScroll(pull.connection)) {
                    items(List(60) { "row $it" }) { Text(it) }
                }
            }
        }
        rule.onRoot().performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertFalse(pull.revealed)
    }

    @Test fun tappingTheEntryAsksForTheConfirmationFirst() {
        val auth = FakeAuth()
        var opened = 0
        rule.setContent {
            CompositionLocalProvider(LocalChatLockAuth provides auth) {
                RisiMeTheme {
                    val gate = rememberLockGate()
                    LockedFolderEntry(1) { gate.run(LOCKED_CHATS_TITLE) { opened++ } }
                }
            }
        }
        rule.onNodeWithText(LOCKED_CHATS_TITLE).performClick()
        rule.waitForIdle()
        assertEquals(1, auth.prompts)
        assertEquals(1, opened)
    }

    // ---- the secret code dialog ----

    @Test fun theSecretCodeNeedsFourCharactersTwiceTheSame() {
        var saved: String? = null
        rule.setContent { RisiMeTheme { SecretCodeDialog(onSave = { saved = it }, onDismiss = {}) } }
        val fields = rule.onAllNodes(hasSetTextAction())
        rule.onNodeWithText("Save").assertIsNotEnabled()
        fields[0].performTextInput("abc")
        rule.onNodeWithText("Use at least 4 characters.").assertIsDisplayed()
        fields[0].performTextInput("d")
        fields[1].performTextInput("abcX")
        rule.onNodeWithText("The codes don't match.").assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        fields[1].performTextClearance()
        fields[1].performTextInput("abcd")
        rule.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals("abcd", saved)
    }
}
