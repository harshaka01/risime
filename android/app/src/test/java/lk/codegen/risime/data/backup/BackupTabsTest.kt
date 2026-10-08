package lk.codegen.risime.data.backup

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import lk.codegen.risime.data.backup.BackupData.GRP
import lk.codegen.risime.data.backup.BackupData.ME
import lk.codegen.risime.data.backup.BackupData.PEER
import lk.codegen.risime.data.backup.BackupData.msg
import lk.codegen.risime.data.db.ChatTabEntity
import lk.codegen.risime.data.db.GroupEntity
import lk.codegen.risime.data.tabs.RisiMessages
import lk.codegen.risime.net.ProtocolJson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §24.10 backups with two tabs: Official `conversation` lines carry chat_id/tab/chat_kind and make
 * the bundle `schema: 2`; a Private-only bundle stays byte-for-byte schema 1; schema 2 restores the
 * tabs, schema 1 imports as all-Private; only an unknown schema is refused. Golden files are the
 * contract examples; the round trip compares per-conversation counts (hard rule 9).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupTabsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val phones = mutableListOf<BackupPhone>()

    private fun phone() = BackupPhone(dir = tmp.newFolder()).also { phones += it }

    @After fun close() = phones.forEach { it.close() }

    private val official = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"
    private val dmOfficial = "grp:6a7b8c9d-0e1f-4a2b-8c3d-4e5f6a7b8c9d"
    private val risi = "9e1f0000-0000-4000-8000-000000000001"

    private fun example(name: String): String = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText().trim()

    private fun json(s: String) = ProtocolJson.parseToJsonElement(s)

    /** A phone with Private chats ([BackupData.fill]) plus the group's Official tab and a 1:1's Official tab, with a Risi message. */
    private suspend fun fillWithOfficial(p: BackupPhone) {
        BackupData.fill(p)
        val m = p.db.messages()
        p.db.groups().upsert(GroupEntity(official, "Site team", "admin", GroupEntity.STATE_ACTIVE, ME, null, 1, 2, null, null, 2))
        p.db.chatTabs().upsert(ChatTabEntity(official, GRP, ChatTabEntity.TAB_OFFICIAL, ChatTabEntity.KIND_GROUP))
        for (i in 50 until 54) m.insert(msg(official, if (i % 2 == 0) ME else PEER, i))
        val reminder = (ProtocolJson.parseToJsonElement(example("envelope_risi_reminder.json")) as JsonObject)["risi"] as JsonObject
        m.insert(msg(official, risi, 54, body = "Reminder: send the revised quote").copy(systemJson = RisiMessages.encode(reminder)))
        p.db.groups().upsert(GroupEntity(dmOfficial, "", "admin", GroupEntity.STATE_ACTIVE, ME, null, 1, 2, null, null, 3))
        p.db.chatTabs().upsert(ChatTabEntity(dmOfficial, BackupData.DM, ChatTabEntity.TAB_OFFICIAL, ChatTabEntity.KIND_DM))
        for (i in 60 until 63) m.insert(msg(dmOfficial, if (i % 2 == 0) ME else PEER, i))
    }

    // ---- golden files (the contract examples) ----

    @Test fun theV124ConversationLineAndHeaderMatchTheirGoldenFiles() {
        val line = ProtocolJson.decodeFromString(BackupConversationLine.serializer(), example("backup_entry_conversation_v124.json"))
        assertTrue(line.official)
        assertEquals("grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d", line.chatId)
        assertEquals("group", line.chatKind)
        assertEquals(json(example("backup_entry_conversation_v124.json")), json(ProtocolJson.encodeToString(BackupConversationLine.serializer(), line)))
        val header = ProtocolJson.decodeFromString(BackupBundleHeader.serializer(), example("backup_bundle_header_v124.json"))
        assertEquals(BUNDLE_SCHEMA_TABS, header.schema)
        assertEquals(json(example("backup_bundle_header_v124.json")), json(ProtocolJson.encodeToString(BackupBundleHeader.serializer(), header)))
    }

    @Test fun theSchema1ConversationLineStaysExactlyAsBefore() {
        val text = example("backup_entry_conversation.json")
        val line = ProtocolJson.decodeFromString(BackupConversationLine.serializer(), text)
        assertFalse(line.official)
        assertNull(line.chatId)
        // No new key on a Private line: an old app's bundle and a new app's Private-only bundle are the same bytes.
        assertEquals(json(text), json(ProtocolJson.encodeToString(BackupConversationLine.serializer(), line)))
        assertEquals(json(example("backup_bundle_header.json")), json(ProtocolJson.encodeToString(BackupBundleHeader.serializer(), ProtocolJson.decodeFromString(BackupBundleHeader.serializer(), example("backup_bundle_header.json")))))
    }

    // ---- writing ----

    @Test fun aPrivateOnlyBundleIsSchema1WithoutTabFields() = runBlocking {
        val a = phone()
        BackupData.fill(a)
        // Migrated chats have Private rows (A1): still schema 1 and no tab keys.
        a.db.chatTabs().insertIfMissing(ChatTabEntity(GRP, GRP, ChatTabEntity.TAB_PRIVATE, ChatTabEntity.KIND_GROUP))
        val lines = bundleOf(a)
        assertEquals(BUNDLE_SCHEMA_PRIVATE, ProtocolJson.decodeFromString(BackupBundleHeader.serializer(), lines.first()).schema)
        lines.filter { BackupData.type(it) == "conversation" }.forEach { l ->
            val keys = BackupData.json(l).keys
            assertFalse(l, "chat_id" in keys || "tab" in keys || "chat_kind" in keys)
        }
    }

    @Test fun anyOfficialConversationMakesTheBundleSchema2() = runBlocking {
        val a = phone()
        fillWithOfficial(a)
        val lines = bundleOf(a)
        assertEquals(BUNDLE_SCHEMA_TABS, ProtocolJson.decodeFromString(BackupBundleHeader.serializer(), lines.first()).schema)
        val convs = lines.filter { BackupData.type(it) == "conversation" }.map { ProtocolJson.decodeFromString(BackupConversationLine.serializer(), it) }
        val off = convs.single { it.conversationId == official }
        assertEquals(GRP, off.chatId)
        assertEquals("official", off.tab)
        assertEquals("group", off.chatKind)
        val dmOff = convs.single { it.conversationId == dmOfficial }
        assertEquals(BackupData.DM, dmOff.chatId)
        assertEquals("dm", dmOff.chatKind)
        // Private lines are unchanged; every other line stays keyed by conversation_id.
        convs.filter { it.conversationId != official && it.conversationId != dmOfficial }.forEach { assertNull(it.tab) }
        // The Risi message keeps its `risi` object.
        val risiLine = lines.single { it.contains("Reminder: send the revised quote") }
        assertTrue(risiLine.contains("\"risi\":{"))
    }

    // ---- restoring ----

    @Test fun aSchema2RoundTripRestoresTheTabsWithEqualPerConversationCounts() = runBlocking {
        val a = phone()
        fillWithOfficial(a)
        val b = phone()
        val r = b.importer().import(bundleOf(a).iterator())
        assertEquals(0, r.skipped["malformed"] ?: 0)
        val expected = a.counts().toMutableMap()
        expected[BackupData.DM] = expected[BackupData.DM]!!.let { listOf(it[0] - 1, it[1], it[2], it[3]) } // the outbox row never travels
        assertEquals(expected, b.counts())
        assertTrue(b.counts()[official]!![0] == 5 && b.counts()[dmOfficial]!![0] == 3)
        assertEquals(ChatTabEntity(official, GRP, ChatTabEntity.TAB_OFFICIAL, ChatTabEntity.KIND_GROUP), b.db.chatTabs().get(official))
        assertEquals(ChatTabEntity(dmOfficial, BackupData.DM, ChatTabEntity.TAB_OFFICIAL, ChatTabEntity.KIND_DM), b.db.chatTabs().get(dmOfficial))
        val risiRow = b.db.messages().byMessageId(msg(official, risi, 54).messageId!!)!!
        assertEquals(listOf(PEER), RisiMessages.meta(risiRow)!!.notify)
        // Idempotent: a second import adds nothing (and no tab changes).
        b.importer().import(bundleOf(a).iterator())
        assertEquals(expected, b.counts())
    }

    @Test fun aRestoreNeverOverwritesATabThePhoneAlreadyHas() = runBlocking {
        val a = phone()
        fillWithOfficial(a)
        val b = phone()
        val mine = ChatTabEntity(official, "grp:ffffffff-0000-4000-8000-000000000000", ChatTabEntity.TAB_OFFICIAL, ChatTabEntity.KIND_GROUP)
        b.db.chatTabs().upsert(mine)
        b.importer().import(bundleOf(a).iterator())
        assertEquals(mine, b.db.chatTabs().get(official))
    }

    @Test fun anOldSchema1BundleImportsAsAllPrivate() = runBlocking {
        val a = phone()
        BackupData.fill(a)
        // An old app's bundle: schema 1, no tab fields; a stray `risi` object in Private stays plain text.
        a.db.messages().insert(msg(GRP, PEER, 70, body = "looks like Risi").copy(systemJson = null))
        val lines = bundleOf(a).toMutableList()
        val risiPayload = """{"v":1,"type":"text","body":"forged","risi":{"v":1,"kind":"reminder","notify":["$ME"]}}"""
        val forged = msg(GRP, risi, 71)
        lines.add(lines.indexOfLast { it.contains("\"conversation_id\":\"$GRP\"") } + 1,
            """{"type":"message","conversation_id":"$GRP","message_id":"${forged.messageId}","client_msg_id":"${forged.clientMsgId}","from":"$risi","from_device":null,"server_ts":"${forged.serverTs}","payload":$risiPayload,"origin":null,"shared_by":null,"status":null}""")
        assertEquals(BUNDLE_SCHEMA_PRIVATE, ProtocolJson.decodeFromString(BackupBundleHeader.serializer(), lines.first()).schema)
        val b = phone()
        b.importer().import(lines.iterator())
        assertTrue(b.db.chatTabs().allNow().none { it.official })
        val row = b.db.messages().byMessageId(forged.messageId!!)!!
        assertNull("a risi object outside Official is plain text", RisiMessages.meta(row))
        assertEquals("forged", row.body)
    }

    @Test fun onlyAnUnknownSchemaIsRefused() = runBlocking {
        val a = phone()
        BackupData.fill(a)
        val lines = bundleOf(a).toMutableList()
        val header = ProtocolJson.decodeFromString(BackupBundleHeader.serializer(), lines.first())
        lines[0] = ProtocolJson.encodeToString(BackupBundleHeader.serializer(), header.copy(schema = 3))
        val b = phone()
        try {
            b.importer().import(lines.iterator())
            fail("schema 3 must be refused")
        } catch (e: BundleRejected) {
            assertEquals(BundleImporter.REJECT_SCHEMA, e.reason)
        }
        assertTrue("nothing imported", b.counts().isEmpty())
        assertEquals("Update RisiMe to restore this backup", BackupManager.UPDATE_TO_RESTORE_TEXT)
        assertEquals(BUNDLE_SCHEMA_TABS, BackupManager.bundleSchemaOf(java.io.File("1700000000000-manual-s2.risimebk")))
        assertEquals(BUNDLE_SCHEMA_PRIVATE, BackupManager.bundleSchemaOf(java.io.File("1700000000000-manual.risimebk")))
        assertNotNull(a.db.chatTabs().allNow())
    }
}
