package lk.codegen.risime.data

import android.app.Application
import kotlinx.coroutines.runBlocking
import lk.codegen.risime.data.db.ChatTabEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.24 §24.9/§24.14 upgrade gate on the JVM (hard rule 9): a phone on the released schema (v10) or
 * the one before (v9) updates through the real [AppContainer] to v11, signs the same account in
 * again, and keeps every message; its DM is now the Private tab of its chat (`chat_id =
 * conversation_id`) and the per-chat count equals the per-conversation count.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TabsUpgradeKeepsDataTest(private val version: Int) : ReleasedInstallFixture() {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "schema v{0}")
        fun versions(): List<Array<Any>> = listOf(arrayOf<Any>(10), arrayOf<Any>(9))
    }

    @Test fun updateAndReSignInKeepEveryChatAsItsPrivateTab() {
        val c = upgradeThenSignIn(version, me, me, signedIn = true)
        assertEquals("v$version: nothing wiped", 2 to 1, c.counts())
        val tabs = runBlocking { c.db.chatTabs().allNow() }
        assertEquals(listOf(ChatTabEntity(conv, conv, ChatTabEntity.TAB_PRIVATE, ChatTabEntity.KIND_DM)), tabs)
        val perChat = runBlocking { c.db.chatTabs().byChat(conv).sumOf { c.db.messages().countIn(it.conversationId) } }
        assertEquals(2, perChat)
        assertEquals("ev-cursor", runBlocking { c.db.sync().cursor() })
        c.db.close()
    }
}
