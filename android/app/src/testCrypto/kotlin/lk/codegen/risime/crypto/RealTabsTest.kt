package lk.codegen.risime.crypto

import lk.codegen.risime.data.db.ChatTabEntity
import lk.codegen.risime.data.tabs.tabFromMls
import lk.codegen.risime.net.GroupMeta
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * v1.24 §24.1 with the REAL MLS core: [UniffiMlsEngine] maps `group_meta` `tab`/`chat_id`/`agents`
 * both ways and the leaves' attested `kind`; Private-ness is read from that MLS state; the core
 * refuses an agent in Private and a tab change after epoch 0.
 */
class RealTabsTest {
    private val aU = "aaaa0000-0000-4000-8000-00000000000a"
    private val bU = "bbbb0000-0000-4000-8000-00000000000b"
    private val risi = "9e1f0000-0000-4000-8000-000000000001"
    private val dmChat = "dm:${aU}_$bU"
    private val official = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"
    private val privateGrp = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private lateinit var a: RealMls.Device
    private lateinit var b: RealMls.Device
    private lateinit var r: RealMls.Device

    @Before fun setUp() {
        RealMls.assumeHostLibrary()
        a = RealMls.device(aU, "a-phone")
        b = RealMls.device(bU, "b-phone")
        r = RealMls.device(risi, "risi-1", agent = true)
    }

    @After fun tearDown() {
        if (::a.isInitialized) listOf(a, b, r).forEach { it.close() }
    }

    @Test fun officialMetaAndAgentKindRoundTripThroughTheRealCore() {
        assertTrue(a.engine.tabsSupported)
        // A 1:1 Official: both users admin, Risi a member in `agents`, no name.
        val meta = GroupMeta(name = "", admins = listOf(aU, bU), tab = GroupMeta.TAB_OFFICIAL, chatId = dmChat, agents = listOf(risi))
        val create = a.transaction { a.engine.createGroupWithMeta(official, 1, listOf(b.keyPackage(), r.keyPackage()), meta) }
        a.transaction { a.engine.commitAccepted(official) }
        b.transaction { b.engine.joinFromWelcome(official, 1, create.welcome!!) }

        val seen = b.engine.groupMeta(official)!!
        assertEquals(GroupMeta.TAB_OFFICIAL, seen.tab)
        assertEquals(dmChat, seen.chatId)
        assertEquals(listOf(risi), seen.agents)
        assertEquals("", seen.name) // the null name of a 1:1 Official reads as "": the app shows the peer's name
        assertEquals(setOf(risi), b.engine.agentUsers(official)) // from the leaf's attestation
        assertEquals(ChatTabEntity(official, dmChat, ChatTabEntity.TAB_OFFICIAL, ChatTabEntity.KIND_DM), tabFromMls(official, seen))

        // Rule 1: the tab never changes after epoch 0 (from anyone).
        try {
            a.transaction { a.engine.updateGroupMeta(official, meta.copy(tab = GroupMeta.TAB_PRIVATE)) }
            fail("a tab change must be refused")
        } catch (_: Exception) {
        }
        assertEquals(GroupMeta.TAB_OFFICIAL, a.engine.groupMeta(official)!!.tab)
    }

    @Test fun aGroupOfficialRefusesATabOrChatChangeUnderThePolicy() {
        val meta = GroupMeta(name = "Site team", admins = listOf(aU), tab = GroupMeta.TAB_OFFICIAL, chatId = privateGrp, agents = listOf(risi))
        a.transaction { a.engine.createGroupWithMeta(official, 1, listOf(b.keyPackage(), r.keyPackage()), meta) }
        a.transaction { a.engine.commitAccepted(official) }
        assertEquals(setOf(risi), a.engine.agentUsers(official))
        for (bad in listOf(meta.copy(tab = GroupMeta.TAB_PRIVATE, agents = emptyList()), meta.copy(chatId = "grp:00000000-0000-4000-8000-000000000000"))) {
            try {
                a.transaction { a.engine.updateGroupMeta(official, bad) }
                fail("must be refused: $bad")
            } catch (e: Exception) {
                assertTrue(e.toString(), e.toString().contains("olicy", ignoreCase = true))
            }
        }
        assertEquals(meta.copy(icon = null), a.engine.groupMeta(official))
    }

    @Test fun aPreV124GroupIsPrivateAndRefusesAnAgentLeaf() {
        val create = a.transaction { a.engine.createGroupWithMeta(privateGrp, 1, listOf(b.keyPackage()), GroupMeta(name = "Pilot team", admins = listOf(aU))) }
        a.transaction { a.engine.commitAccepted(privateGrp) }
        b.transaction { b.engine.joinFromWelcome(privateGrp, 1, create.welcome!!) }
        val seen = b.engine.groupMeta(privateGrp)!!
        assertNull(seen.tab)
        assertEquals(emptySet<String>(), b.engine.agentUsers(privateGrp))
        assertEquals(ChatTabEntity(privateGrp, privateGrp, ChatTabEntity.TAB_PRIVATE, ChatTabEntity.KIND_GROUP), tabFromMls(privateGrp, seen))
        // Rule 2: an agent leaf never enters a Private group, whatever the caller asks.
        try {
            a.transaction { a.engine.changeGroupMembers(privateGrp, listOf(r.keyPackage()), emptyList()) }
            fail("an agent leaf in Private must be refused")
        } catch (_: Exception) {
        }
    }
}
