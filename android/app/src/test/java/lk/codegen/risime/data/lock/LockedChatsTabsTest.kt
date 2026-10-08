package lk.codegen.risime.data.lock

import kotlinx.coroutines.runBlocking
import lk.codegen.risime.data.auth.VaultKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** v1.24 §24: a locked chat locks both its tabs; the stored set moves to chat ids without unlocking anything. */
class LockedChatsTabsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = object : VaultKey {
        override val alias = "risime_locked_chats_aes"
        var k: SecretKey? = null
        override fun get(): SecretKey? = k
        override fun getOrCreate(): SecretKey? = k ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { k = it }
        override fun delete() { k = null }
    }

    private val dm = "dm:a_b"
    private val dmOfficial = "grp:0fficial-dm"
    private val grp = "grp:1234"
    private val grpOfficial = "grp:0fficial-grp"

    /** The chat-tab rows (MLS-derived): two Official conversations; null = not read yet. */
    private var rows: Map<String, String>? = mapOf(dmOfficial to dm, grpOfficial to grp)

    private fun chats() = LockedChats(FileLockedChatsStore(File(tmp.root, "locked_chats.bin"), key), chatOf = { c -> rows?.let { it[c.lowercase()] ?: c } })

    @Test fun lockingEitherTabLocksTheWholeChat() = runBlocking {
        val a = chats()
        assertTrue(a.lock(dmOfficial)) // e.g. from the Official tab's ⋮ menu
        assertEquals(setOf(dm), a.ids.value)
        assertTrue(a.isLocked(dm)!!)
        assertTrue(a.isLocked(dmOfficial)!!)
        assertTrue("an Official notification of a locked chat is redacted", a.redactBlocking(dmOfficial))
        assertTrue(a.redactBlocking(dm))
        assertFalse(a.redactBlocking(grpOfficial))
        assertTrue(a.unlock(dmOfficial))
        assertEquals(emptySet<String>(), a.ids.value)
        assertFalse(a.redactBlocking(dmOfficial))
    }

    @Test fun whileTheTabsAreUnknownEverythingIsRedacted() = runBlocking {
        val a = chats()
        a.load()
        rows = null
        assertTrue(a.redactBlocking(grp))
        assertNull(a.isLocked(grp))
    }

    @Test fun theStoredSetMovesToChatIdsAndNothingIsUnlocked() = runBlocking {
        // A set as an earlier build could leave it: conversation ids, one of them an Official tab.
        val before = chats()
        rows = emptyMap() // nothing known yet: ids stored verbatim
        assertTrue(before.lock(dm))
        assertTrue(before.lock(grpOfficial))
        assertTrue(before.lock(grp))
        rows = mapOf(dmOfficial to dm, grpOfficial to grp)
        val after = chats()
        assertTrue(after.migrateToChatIds())
        assertEquals(setOf(dm, grp), after.ids.value)
        assertTrue(after.isLocked(grpOfficial)!!)
        assertFalse("already chat ids: nothing to do", chats().migrateToChatIds())
        assertEquals(setOf(dm, grp), chats().also { it.load() }.ids.value)
    }

    @Test fun preV124IdsAreAlreadyChatIds() = runBlocking {
        rows = emptyMap() // every old conversation is its own chat (chat_id = conversation_id)
        val a = chats()
        a.lock(dm)
        a.lock(grp)
        assertFalse(chats().migrateToChatIds())
        assertEquals(setOf(dm, grp), chats().also { it.load() }.ids.value)
    }
}
