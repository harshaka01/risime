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
 * v11 → v12 (contract v1.26 §26.6 scheduled messages) on real SQLite: additive only. A migrated v11
 * database equals a fresh v12 one, and every row of every v11 table is untouched (hard rule 9).
 */
class Migration11To12Test {
    private val dir = File(System.getProperty("risime.schemas"), AppDatabase::class.java.name)

    private fun db(v: Int): JsonObject = Json.parseToJsonElement(File(dir, "$v.json").readText()).jsonObject["database"]!!.jsonObject

    private fun entities(v: Int) = db(v)["entities"]!!.jsonArray.map { it.jsonObject }.associateBy { it["tableName"]!!.jsonPrimitive.content }

    private fun SQLiteConnection.create(v: Int) = entities(v).forEach { (name, e) ->
        execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name))
        e["indices"]?.jsonArray?.forEach { execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name)) }
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

    private fun SQLiteConnection.columnNames(table: String): List<String> {
        val out = mutableListOf<String>()
        prepare("PRAGMA table_info(`$table`)").use { st -> while (st.step()) out += st.getText(1) }
        return out
    }

    private fun SQLiteConnection.indices(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        prepare("SELECT name, sql FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL").use { st ->
            while (st.step()) out[st.getText(0)] = st.getText(1).replace("IF NOT EXISTS ", "")
        }
        return out
    }

    private fun SQLiteConnection.dump(table: String): List<String> {
        val expr = columnNames(table).joinToString(" || '|' || ") { "quote(`$it`)" }
        val out = mutableListOf<String>()
        prepare("SELECT $expr FROM `$table` ORDER BY rowid").use { st -> while (st.step()) out += st.getText(0) }
        return out
    }

    private fun SQLiteConnection.long(sql: String): Long = prepare(sql).use { st -> st.step(); st.getLong(0) }

    @Test fun migratedV11MatchesAFreshV12AndKeepsEveryRow() {
        val driver = BundledSQLiteDriver()
        val migrated = driver.open(":memory:")
        val fresh = driver.open(":memory:")
        try {
            migrated.create(11)
            val cols = "client_msg_id, message_id, conversation_id, from_id, to_id, body, server_ts, local_ts, status, outgoing, kind"
            migrated.execSQL("INSERT INTO messages ($cols) VALUES ('c1', 'm1', 'dm:a_b', 'b', 'a', 'hello', '2026-10-01T10:00:00.000Z', 1000, 'READ', 0, 'text')")
            migrated.execSQL("INSERT INTO messages ($cols, blob_id) VALUES ('c2', 'm2', 'grp:g', 'a', 'grp:g', '', '2026-10-01T10:02:00.000Z', 3000, 'DELIVERED', 1, 'image', 'blob-1')")
            migrated.execSQL("INSERT INTO messages ($cols, call_id) VALUES ('c3', 'm3', 'dm:a_b', 'b', 'a', '{}', '2026-10-01T10:03:00.000Z', 4000, 'READ', 0, 'call', 'call-1')")
            migrated.execSQL("INSERT INTO `groups` (conversation_id, name, my_role, state, created_by, created_at, generation, epoch_seen, meta_updated_at, last_refreshed_at, local_ts) VALUES ('grp:g', 'Pilot team', 'admin', 'active', 'a', NULL, 1, 3, NULL, NULL, 5)")
            migrated.execSQL("INSERT INTO chat_tabs (conversation_id, chat_id, tab, chat_kind) VALUES ('grp:r', 'grp:r', 'official', 'risi')")
            val v11Tables = entities(11).keys
            val before = v11Tables.associateWith { migrated.dump(it) }
            Migration11To12.SQL.forEach { migrated.execSQL(it) }
            fresh.create(12)
            val v12 = entities(12)
            assertTrue(listOf("scheduled_messages", "scheduled_sends", "risi_writes").all { it in v12.keys })
            assertEquals(v11Tables + setOf("scheduled_messages", "scheduled_sends", "risi_writes"), v12.keys)
            for (table in v12.keys) assertEquals(table, fresh.columns(table), migrated.columns(table))
            assertEquals(fresh.indices(), migrated.indices())
            for (table in v11Tables) assertEquals("rule 9: $table unchanged", before[table], migrated.dump(table))
            assertEquals(3L, migrated.long("SELECT COUNT(*) FROM messages"))
            // The new tables work as Room writes them.
            migrated.execSQL("INSERT INTO scheduled_messages (schedule_id, write_id, conversation_id, text, repeat, local_hour, local_minute, next_at, state, created_at) VALUES ('s1', 'w1', 'dm:a_b', 'Good morning', 'daily', 6, 0, 1, 'pending', 0)")
            assertEquals(1L, migrated.long("SELECT COUNT(*) FROM scheduled_messages WHERE state = 'pending'"))
        } finally {
            migrated.close()
            fresh.close()
        }
    }
}
