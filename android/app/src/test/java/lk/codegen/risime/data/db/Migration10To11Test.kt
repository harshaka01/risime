package lk.codegen.risime.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v10 → v11 (contract v1.24 §24.9, hard rule 9) on real SQLite, from the released schema (v10) and the
 * one before (v9 → v10 → v11): the migrated database equals a fresh v11 one, every existing row of every
 * table is byte-for-byte unchanged, every per-conversation message count is identical, and every
 * existing conversation (DM and group) is its chat's Private tab with `chat_id = conversation_id`.
 */
class Migration10To11Test {
    private val dir = File(System.getProperty("risime.schemas"), AppDatabase::class.java.name)

    private fun db(v: Int): JsonObject = Json.parseToJsonElement(File(dir, "$v.json").readText()).jsonObject["database"]!!.jsonObject

    private fun entities(v: Int) = db(v)["entities"]!!.jsonArray.map { it.jsonObject }.associateBy { it["tableName"]!!.jsonPrimitive.content }

    private fun SQLiteConnection.create(v: Int) = entities(v).forEach { (name, e) ->
        execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name))
        e["indices"]?.jsonArray?.forEach { execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name)) }
    }

    private fun SQLiteConnection.columnNames(table: String): List<String> {
        val out = mutableListOf<String>()
        prepare("PRAGMA table_info(`$table`)").use { st -> while (st.step()) out += st.getText(1) }
        return out
    }

    private fun SQLiteConnection.columns(table: String): List<String> {
        val out = mutableListOf<String>()
        prepare("PRAGMA table_info(`$table`)").use { st ->
            while (st.step()) {
                out += listOf(st.getText(1), st.getText(2), st.getLong(3).toString(), if (st.isNull(4)) "null" else st.getText(4), st.getLong(5).toString()).joinToString("|")
            }
        }
        return out.sorted()
    }

    private fun SQLiteConnection.indices(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        prepare("SELECT name, sql FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL").use { st ->
            while (st.step()) out[st.getText(0)] = st.getText(1).replace("IF NOT EXISTS ", "")
        }
        return out
    }

    /** Every row of [table], every column `quote()`d (blobs as hex), in rowid order. */
    private fun SQLiteConnection.dump(table: String): List<String> {
        val expr = columnNames(table).joinToString(" || '|' || ") { "quote(`$it`)" }
        val out = mutableListOf<String>()
        prepare("SELECT $expr FROM `$table` ORDER BY rowid").use { st -> while (st.step()) out += st.getText(0) }
        return out
    }

    private fun SQLiteConnection.rows(sql: String): List<List<String?>> {
        val out = mutableListOf<List<String?>>()
        prepare(sql).use { st ->
            while (st.step()) out += (0 until st.getColumnCount()).map { if (st.isNull(it)) null else st.getText(it) }
        }
        return out
    }

    private fun SQLiteConnection.countsPerConversation(): Map<String, Long> =
        rows("SELECT conversation_id, COUNT(*) FROM messages GROUP BY conversation_id").associate { it[0]!! to it[1]!!.toLong() }

    private val dm = "dm:5b0c1f3e-0000-4000-8000-00000000000a_5b0c1f3e-0000-4000-8000-00000000000b"
    private val dm2 = "dm:5b0c1f3e-0000-4000-8000-00000000000a_5b0c1f3e-0000-4000-8000-00000000000c"
    private val grp = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"
    private val emptyGrp = "grp:00000000-8c9d-4e0f-9a1b-2c3d4e5f6a7b"
    private val hiddenDm = "dm:5b0c1f3e-0000-4000-8000-00000000000a_5b0c1f3e-0000-4000-8000-00000000000d"

    /** Real rows as a pilot phone has them: DMs (plaintext, e2ee, image, call line), a group with system lines, an empty group, a deleted chat. */
    private fun SQLiteConnection.seed() {
        val cols = "client_msg_id, message_id, conversation_id, from_id, to_id, body, server_ts, local_ts, status, outgoing, kind"
        execSQL("INSERT INTO messages ($cols) VALUES ('c1', 'm1', '$dm', 'b', 'a', 'hello', '2026-10-01T10:00:00.000Z', 1000, 'READ', 0, 'text')")
        execSQL("INSERT INTO messages ($cols) VALUES ('c2', 'm2', '$dm', 'a', 'b', 'hi back', '2026-10-01T10:01:00.000Z', 2000, 'DELIVERED', 1, 'text')")
        execSQL("INSERT INTO messages ($cols, blob_id) VALUES ('c3', 'm3', '$dm', 'b', 'a', '', '2026-10-01T10:02:00.000Z', 3000, 'DELIVERED', 0, 'image', 'blob-1')")
        execSQL("INSERT INTO messages ($cols, call_id) VALUES ('c4', 'm4', '$dm', 'b', 'a', '{}', '2026-10-01T10:03:00.000Z', 4000, 'READ', 0, 'call', 'call-1')")
        execSQL("INSERT INTO messages ($cols) VALUES ('c5', 'm5', '$dm2', 'c', 'a', 'other chat', '2026-10-01T11:00:00.000Z', 5000, 'DELIVERED', 0, 'text')")
        execSQL("INSERT INTO messages ($cols) VALUES ('g1', 'm6', '$grp', 'a', '$grp', 'hello group', '2026-10-01T12:00:00.000Z', 6000, 'SENT', 1, 'text')")
        execSQL("INSERT INTO messages ($cols, system_json) VALUES ('sys:e1', NULL, '$grp', 'a', '$grp', 'You created the group', NULL, 5900, 'READ', 0, 'system', '{\"action\":\"created\"}')")
        execSQL("INSERT INTO messages ($cols, deleted_by, delete_state) VALUES ('g2', 'm7', '$grp', 'b', '$grp', '', '2026-10-01T12:01:00.000Z', 6100, 'READ', 0, 'deleted', 'b', NULL)")
        execSQL("INSERT INTO `groups` (conversation_id, name, my_role, state, created_by, created_at, generation, epoch_seen, meta_updated_at, last_refreshed_at, local_ts) VALUES ('$grp', 'Pilot team', 'admin', 'active', 'a', NULL, 1, 3, NULL, NULL, 5)")
        execSQL("INSERT INTO `groups` (conversation_id, name, my_role, state, created_by, created_at, generation, epoch_seen, meta_updated_at, last_refreshed_at, local_ts) VALUES ('$emptyGrp', 'Empty', 'member', 'active', 'b', NULL, 2, 0, NULL, NULL, 6)")
        execSQL("INSERT INTO group_members (conversation_id, user_id, display_name, phone, role, kind, state, joined_at) VALUES ('$grp', 'a', 'Me', NULL, 'admin', 'user', 'active', NULL)")
        execSQL("INSERT INTO group_members (conversation_id, user_id, display_name, phone, role, kind, state, joined_at) VALUES ('$grp', 'b', 'Kamal', NULL, 'member', 'user', 'active', NULL)")
        execSQL("INSERT INTO chat_state (conversation_id, cleared_upto, hidden) VALUES ('$hiddenDm', 123, 1)")
        execSQL("INSERT INTO mls_kv (namespace, `key`, value) VALUES ('group', x'0102', x'00ff10')")
        execSQL("INSERT INTO contacts (phone, display_name, company, user_id, registered, friend) VALUES ('+10000000000', 'Peer', 'Co', 'b', 1, 1)")
        execSQL("INSERT INTO sync_state (id, last_event_id) VALUES (0, 'ev-cursor')")
        execSQL("INSERT INTO reactions (conversation_id, target_message_id, reactor_user_id, emoji, op, pending, local_ts) VALUES ('$dm', 'm1', 'a', '👍', 'add', 0, 1)")
    }

    private fun check(from: Int) {
        val driver = BundledSQLiteDriver()
        val migrated = driver.open(":memory:")
        val fresh = driver.open(":memory:")
        try {
            migrated.create(from)
            migrated.seed()
            val tables = entities(from).keys
            val before = tables.associateWith { migrated.dump(it) }
            val countsBefore = migrated.countsPerConversation()
            if (from == 9) Migration9To10.SQL.forEach { migrated.execSQL(it) }
            val afterV10 = if (from == 9) entities(10).keys.associateWith { migrated.dump(it) } else before
            Migration10To11.SQL.forEach { migrated.execSQL(it) }
            fresh.create(11)
            val v11 = entities(11)
            assertTrue(listOf("chat_tabs", "chat_prefs").all { it in v11.keys })
            for (table in v11.keys) assertEquals(table, fresh.columns(table), migrated.columns(table))
            assertEquals(fresh.indices(), migrated.indices())
            // Rule 9: nothing removed or changed, in any table (v10's own additive columns included).
            for (t in entities(10).keys) assertEquals("v$from: $t unchanged", afterV10[t], migrated.dump(t))
            if (from == 9) for (t in tables - "groups") assertEquals("v9: $t unchanged", before[t], migrated.dump(t))
            assertEquals("every message count per conversation identical", countsBefore, migrated.countsPerConversation())
            assertEquals(8L, countsBefore.values.sum())
            // Every existing conversation is Private, chat_id = conversation_id; nothing in chat_prefs.
            val tabs = migrated.rows("SELECT conversation_id, chat_id, tab, chat_kind FROM chat_tabs ORDER BY conversation_id")
            assertEquals(
                listOf(
                    listOf(dm, dm, "private", "dm"),
                    listOf(dm2, dm2, "private", "dm"),
                    listOf(hiddenDm, hiddenDm, "private", "dm"),
                    listOf(emptyGrp, emptyGrp, "private", "group"),
                    listOf(grp, grp, "private", "group"),
                ),
                tabs,
            )
            assertEquals(emptyList<List<String?>>(), migrated.rows("SELECT * FROM chat_prefs"))
            // Per chat: the same counts (chat_id = conversation_id for every old conversation).
            val perChat = migrated.rows("SELECT t.chat_id, COUNT(m.client_msg_id) FROM chat_tabs t JOIN messages m ON m.conversation_id = t.conversation_id GROUP BY t.chat_id")
                .associate { it[0]!! to it[1]!!.toLong() }
            assertEquals(countsBefore, perChat)
            // Running it twice (a retried open) adds nothing.
            Migration10To11.SQL.forEach { migrated.execSQL(it) }
            assertEquals(tabs, migrated.rows("SELECT conversation_id, chat_id, tab, chat_kind FROM chat_tabs ORDER BY conversation_id"))
            // The schemas of every older table are untouched.
            val v10 = entities(10)
            for (t in v10.keys) assertEquals(t, v10[t]!!["createSql"], v11[t]!!["createSql"])
        } finally {
            migrated.close()
            fresh.close()
        }
    }

    @Test fun releasedV10KeepsEveryRowAndBecomesPrivateTabs() = check(10)

    @Test fun previousV9ThroughV10KeepsEveryRowAndBecomesPrivateTabs() = check(9)
}
