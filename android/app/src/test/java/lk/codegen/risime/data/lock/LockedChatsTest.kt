package lk.codegen.risime.data.lock

import kotlinx.coroutines.runBlocking
import lk.codegen.risime.data.auth.VaultKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Locked chats: stored sealed on the device, survive a restart/update, the secret code only as a salted hash. */
class LockedChatsTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Key(private val fixed: SecretKey? = null) : VaultKey {
        override val alias = "risime_locked_chats_aes"
        var key: SecretKey? = fixed
        var failure: Exception? = null

        override fun get(): SecretKey? {
            failure?.let { throw it }
            return key
        }

        override fun getOrCreate(): SecretKey? {
            get()
            if (key == null) key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            return key
        }

        override fun delete() {
            key = null
        }
    }

    private fun file() = File(tmp.root, "locked_chats.bin")

    private fun chats(key: Key = Key(), f: File = file()) = LockedChats(FileLockedChatsStore(f, key))

    @Test fun lockAndUnlockPersistAcrossARestart() = runBlocking {
        val key = Key()
        val a = chats(key)
        assertTrue(a.load())
        assertEquals(emptySet<String>(), a.ids.value)
        assertTrue(a.lock("dm:a_b"))
        assertTrue(a.lock("grp:1234"))
        assertEquals(setOf("dm:a_b", "grp:1234"), a.ids.value)
        // A new process (an update restarts the app): same file, same key.
        val b = chats(key)
        assertNull(b.ids.value) // unknown until read
        assertTrue(b.load())
        assertEquals(setOf("dm:a_b", "grp:1234"), b.ids.value)
        assertTrue(b.unlock("DM:A_B")) // conversation ids compare case-insensitively
        assertEquals(setOf("grp:1234"), chats(key).also { it.load() }.ids.value)
    }

    @Test fun theFileHoldsNoPlaintext() = runBlocking {
        val a = chats()
        a.lock("dm:secret-peer_other")
        a.setSecretCode("hunter22")
        val raw = file().readBytes().decodeToString(throwOnInvalidSequence = false)
        assertFalse(raw.contains("dm:secret-peer"))
        assertFalse(raw.contains("hunter22"))
        assertFalse(raw.contains("ids"))
    }

    @Test fun anOldFileStillOpensAfterAnUpdate() = runBlocking {
        // A blob written today with a fixed key must stay readable by every later version (rule 9).
        val fixed = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val key = Key(fixed)
        val data = LockedChatsData(ids = listOf("dm:a_b", "grp:g1"))
        val blob = lk.codegen.risime.data.auth.SessionVault.seal(
            lk.codegen.risime.net.ProtocolJson.encodeToString(LockedChatsData.serializer(), data).toByteArray(), fixed, key.alias,
        )
        file().writeBytes(blob)
        val c = chats(key)
        assertTrue(c.load())
        assertEquals(setOf("dm:a_b", "grp:g1"), c.ids.value)
        // The JSON shape is stable (adding fields with defaults stays compatible).
        assertEquals("""{"ids":["dm:a_b","grp:g1"],"codeSalt":null,"codeHash":null,"codeIterations":0}""", lk.codegen.risime.net.ProtocolJson.encodeToString(LockedChatsData.serializer(), data))
    }

    @Test fun secretCodeIsStoredOnlyAsASaltedHashAndMatchesExactly() = runBlocking {
        val key = Key()
        val a = chats(key)
        a.load()
        assertFalse(a.hasCode.value)
        assertFalse(a.codeMatches("anything"))
        assertFalse(a.setSecretCode("abc")) // too short
        assertTrue(a.setSecretCode("Sesame 42"))
        assertTrue(a.hasCode.value)
        assertTrue(a.codeMatches("Sesame 42"))
        assertFalse(a.codeMatches("sesame 42"))
        assertFalse(a.codeMatches("Sesame 42 "))
        assertFalse(a.codeMatches("Sesame 4"))
        // After a restart.
        val b = chats(key)
        b.load()
        assertTrue(b.hasCode.value)
        assertTrue(b.codeMatches("Sesame 42"))
        assertFalse(b.codeMatches("Sesame 43"))
        assertTrue(b.clearSecretCode())
        assertFalse(b.hasCode.value)
        assertFalse(b.codeMatches("Sesame 42"))
    }

    @Test fun twoCodesGetDifferentSaltsAndHashes() {
        val (s1, h1, _) = SecretCode.create("same-code", 1000)
        val (s2, h2, _) = SecretCode.create("same-code", 1000)
        assertTrue(s1 != s2)
        assertTrue(h1 != h2)
        assertFalse(h1.contains("same-code"))
        val d = LockedChatsData(codeSalt = s1, codeHash = h1, codeIterations = 1000)
        assertTrue(SecretCode.matches(d, "same-code"))
        assertFalse(SecretCode.matches(d, "other-code"))
    }

    @Test fun lockingNeverDependsOnTheNetworkOrMessages() {
        // The runtime class has no operation on messages, tokens or the network.
        val methods = LockedChats::class.java.declaredMethods.map { it.name.lowercase() }
        listOf("message", "token", "network", "delete", "signout", "logout").forEach { bad ->
            assertTrue("LockedChats must not have a '$bad' operation", methods.none { it.contains(bad) })
        }
    }

    @Test fun anUnreadableKeystoreIsNotEmptyAndNeverOverwritesTheFile() = runBlocking {
        val key = Key()
        val a = chats(key)
        a.lock("dm:a_b")
        val before = file().readBytes()
        val b = chats(key)
        key.failure = java.security.KeyStoreException("keystore2 busy")
        assertFalse(b.load()) // transient: unknown, not "empty"
        assertNull(b.ids.value)
        assertTrue(b.redactBlocking("dm:anything")) // unknown list: every chat is redacted in notifications
        assertFalse(b.lock("dm:c_d")) // never writes over what could not be read
        assertTrue(before.contentEquals(file().readBytes()))
        key.failure = null
        assertTrue(b.load())
        assertEquals(setOf("dm:a_b"), b.ids.value)
        assertTrue(b.redactBlocking("dm:a_b"))
        assertFalse(b.redactBlocking("dm:c_d"))
    }

    @Test fun aGoneKeyStartsEmptyWithoutCrashing() = runBlocking {
        val key = Key()
        chats(key).lock("dm:a_b")
        key.key = null // key lost (restore on a new phone)
        val b = chats(key)
        assertTrue(b.load())
        assertEquals(emptySet<String>(), b.ids.value)
        assertTrue(b.lock("dm:x_y")) // rewritten under a new key
        assertNotNull(b.ids.value)
    }

    @Test fun resetAllAndWipe() = runBlocking {
        val key = Key()
        val a = chats(key)
        a.lock("dm:a_b")
        a.setSecretCode("abcd1234")
        a.openFolder()
        assertTrue(a.folderOpen.value)
        assertTrue(a.resetAll())
        assertEquals(emptySet<String>(), a.ids.value)
        assertFalse(a.hasCode.value)
        a.lock("dm:a_b")
        a.wipe()
        assertFalse(file().exists())
        assertFalse(a.folderOpen.value)
        assertEquals(emptySet<String>(), a.ids.value)
    }

    @Test fun theFolderClosesOnDemand() {
        val a = chats()
        a.openFolder()
        assertTrue(a.folderOpen.value)
        a.closeFolder()
        assertFalse(a.folderOpen.value)
    }
}
