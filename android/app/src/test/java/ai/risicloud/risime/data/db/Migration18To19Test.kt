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
 * v18 → v19 (v1.44 §39 link previews, hard rule 9) on real SQLite: one nullable column. A migrated v18 database
 * equals a fresh v19 one; every v18 row keeps every value; the per-conversation counts (1:1, groups, photos,
 * calls and voice notes) are identical.
 */
class Migration18To19Test {
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

    private fun SQLiteConnection.dump(table: String, cols: List<String>): List<String> {
        val expr = cols.joinToString(" || '|' || ") { "quote(`$it`)" }
        val out = mutableListOf<String>()
        prepare("SELECT $expr FROM `$table` ORDER BY rowid").use { st -> while (st.step()) out += st.getText(0) }
        return out
    }

    private fun SQLiteConnection.long(sql: String): Long = prepare(sql).use { st -> st.step(); st.getLong(0) }

    /** The upgrade gate counts: per conversation, all messages, photos, call records and voice notes. */
    private fun SQLiteConnection.counts(): List<String> {
        val out = mutableListOf<String>()
        prepare(
            "SELECT conversation_id, COUNT(*), SUM(CASE WHEN kind = 'image' THEN 1 ELSE 0 END), SUM(CASE WHEN kind = 'call' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN kind = 'voice' THEN 1 ELSE 0 END) FROM messages GROUP BY conversation_id ORDER BY conversation_id",
        ).use { st -> while (st.step()) out += (0..4).joinToString("|") { st.getText(it) } }
        return out
    }

    @Test fun migratedV18MatchesAFreshV19AndKeepsEveryRow() {
        val driver = BundledSQLiteDriver()
        val migrated = driver.open(":memory:")
        val fresh = driver.open(":memory:")
        try {
            migrated.create(18)
            val cols = "client_msg_id, message_id, conversation_id, from_id, to_id, body, server_ts, local_ts, status, outgoing, kind"
            migrated.execSQL("INSERT INTO messages ($cols, e2ee) VALUES ('c1', 'm1', 'dm:a_b', 'b', 'a', 'see https://example.org/', '2026-10-01T10:00:00.000Z', 1000, 'READ', 0, 'text', 'e2ee')")
            migrated.execSQL("INSERT INTO messages ($cols, blob_id) VALUES ('c2', 'm2', 'grp:g', 'a', 'grp:g', 'pic', '2026-10-01T10:02:00.000Z', 3000, 'DELIVERED', 1, 'image', 'blob-1')")
            migrated.execSQL("INSERT INTO messages ($cols, call_id, system_json) VALUES ('c3', 'm3', 'dm:a_b', 'b', 'a', 'Missed voice call', '2026-10-01T10:03:00.000Z', 4000, 'READ', 0, 'call', 'call-1', '{}')")
            migrated.execSQL("INSERT INTO messages ($cols, blob_id, system_json, played_at) VALUES ('c4', 'm4', 'dm:a_b', 'b', 'a', '', '2026-10-01T10:04:00.000Z', 5000, 'READ', 0, 'voice', 'blob-2', '{}', 7)")
            val v18 = entities(18)
            val v18Columns = v18.keys.associateWith { migrated.columnNames(it) }
            val before = v18.keys.associateWith { migrated.dump(it, v18Columns.getValue(it)) }
            val countsBefore = migrated.counts()

            Migration18To19.SQL.forEach { migrated.execSQL(it) }
            fresh.create(19)

            val v19 = entities(19)
            assertEquals(v18.keys, v19.keys)
            for (table in v19.keys) assertEquals(table, fresh.columns(table), migrated.columns(table))
            assertEquals(fresh.indices(), migrated.indices())
            assertEquals(listOf("link_preview"), migrated.columnNames("messages") - v18Columns.getValue("messages").toSet())
            for (table in v18.keys) assertEquals("rule 9: $table unchanged", before[table], migrated.dump(table, v18Columns.getValue(table)))
            assertEquals("upgrade gate counts", countsBefore, migrated.counts())
            assertTrue(migrated.long("SELECT COUNT(*) FROM messages WHERE link_preview IS NULL") == 4L)
        } finally {
            migrated.close()
            fresh.close()
        }
    }
}
