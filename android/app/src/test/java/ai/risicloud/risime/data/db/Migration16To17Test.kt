package ai.risicloud.risime.data.db

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
 * v16 → v17 (chat-list clarity, decision 048, hard rule 9) on real SQLite: one nullable column. A migrated v16
 * database equals a fresh v17 one; every v16 row keeps every value; the per-conversation counts are identical.
 */
class Migration16To17Test {
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

    /** A table's rows, only over [cols] (the v16 columns), in rowid order. */
    private fun SQLiteConnection.dump(table: String, cols: List<String>): List<String> {
        val expr = cols.joinToString(" || '|' || ") { "quote(`$it`)" }
        val out = mutableListOf<String>()
        prepare("SELECT $expr FROM `$table` ORDER BY rowid").use { st -> while (st.step()) out += st.getText(0) }
        return out
    }

    private fun SQLiteConnection.long(sql: String): Long = prepare(sql).use { st -> st.step(); st.getLong(0) }

    /** The §33.18 gate counts: per conversation, 1:1 / group messages, photos and call records. */
    private fun SQLiteConnection.counts(): List<String> {
        val out = mutableListOf<String>()
        prepare(
            "SELECT conversation_id, COUNT(*), SUM(CASE WHEN kind = 'image' THEN 1 ELSE 0 END), SUM(CASE WHEN kind = 'call' THEN 1 ELSE 0 END) " +
                "FROM messages GROUP BY conversation_id ORDER BY conversation_id",
        ).use { st -> while (st.step()) out += (0..3).joinToString("|") { st.getText(it) } }
        return out
    }

    @Test fun migratedV16MatchesAFreshV17AndKeepsEveryRow() {
        val driver = BundledSQLiteDriver()
        val migrated = driver.open(":memory:")
        val fresh = driver.open(":memory:")
        try {
            migrated.create(16)
            val cols = "client_msg_id, message_id, conversation_id, from_id, to_id, body, server_ts, local_ts, status, outgoing, kind"
            migrated.execSQL("INSERT INTO messages ($cols) VALUES ('c1', 'm1', 'dm:a_b', 'b', 'a', 'hello', '2026-10-01T10:00:00.000Z', 1000, 'READ', 0, 'text')")
            migrated.execSQL("INSERT INTO messages ($cols, forward_hops) VALUES ('c2', 'm2', 'dm:a_b', 'a', 'b', 'hi back', '2026-10-01T10:01:00.000Z', 2000, 'READ', 1, 'text', 1)")
            migrated.execSQL("INSERT INTO messages ($cols, blob_id) VALUES ('c3', 'm3', 'grp:g', 'a', 'grp:g', 'pic', '2026-10-01T10:02:00.000Z', 3000, 'DELIVERED', 1, 'image', 'blob-1')")
            migrated.execSQL("INSERT INTO messages ($cols, call_id, system_json) VALUES ('c4', 'm4', 'dm:a_b', 'b', 'a', 'Missed voice call', '2026-10-01T10:03:00.000Z', 4000, 'READ', 0, 'call', 'call-1', '{}')")
            migrated.execSQL("INSERT INTO messages ($cols, fail_reason) VALUES ('c5', NULL, 'dm:a_b', 'a', 'b', 'pending', NULL, 5000, 'PENDING', 1, 'text', 'retry_later')")
            migrated.execSQL("INSERT INTO stars (message_id, conversation_id, starred_at) VALUES ('m1', 'dm:a_b', 7)")
            val v16 = entities(16)
            val v16Columns = v16.keys.associateWith { migrated.columnNames(it) }
            val before = v16.keys.associateWith { migrated.dump(it, v16Columns.getValue(it)) }
            val countsBefore = migrated.counts()

            Migration16To17.SQL.forEach { migrated.execSQL(it) }
            fresh.create(17)

            val v17 = entities(17)
            assertEquals(v16.keys, v17.keys)
            for (table in v17.keys) assertEquals(table, fresh.columns(table), migrated.columns(table))
            assertEquals(fresh.indices(), migrated.indices())
            val added = migrated.columnNames("messages") - v16Columns.getValue("messages").toSet()
            assertEquals(listOf("e2ee"), added)
            assertTrue(migrated.columns("messages").filter { it.substringBefore('|') in added }.all { it.split('|')[2] == "0" })
            for (table in v16.keys) assertEquals("rule 9: $table unchanged", before[table], migrated.dump(table, v16Columns.getValue(table)))
            assertEquals("upgrade gate counts", countsBefore, migrated.counts())
            assertEquals(5L, migrated.long("SELECT COUNT(*) FROM messages WHERE e2ee IS NULL"))
            migrated.execSQL("UPDATE messages SET e2ee = 'plain' WHERE client_msg_id = 'c1'")
            assertEquals(1L, migrated.long("SELECT COUNT(*) FROM messages WHERE e2ee = 'plain'"))
        } finally {
            migrated.close()
            fresh.close()
        }
    }
}
