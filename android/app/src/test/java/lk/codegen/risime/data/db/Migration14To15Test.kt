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
 * v14 -> v15 (§31 Google link tables, hard rule 9) on real SQLite from the released v13 schema: the
 * migrated database equals a fresh v14 one, every existing row of every table is unchanged, every
 * per-conversation message count is identical, and the two new tables start empty. Re-running adds nothing.
 */
class Migration14To15Test {
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

    @Test fun v14KeepsEveryRowAndGetsTheEmptyGcalTables() {
        val driver = BundledSQLiteDriver()
        val migrated = driver.open(":memory:")
        val fresh = driver.open(":memory:")
        try {
            migrated.create(14)
            val cols = "client_msg_id, message_id, conversation_id, from_id, to_id, body, server_ts, local_ts, status, outgoing, kind"
            migrated.execSQL("INSERT INTO messages ($cols) VALUES ('c1', 'm1', '$dm', 'b', 'a', 'hello', '2026-10-01T10:00:00.000Z', 1000, 'READ', 0, 'text')")
            migrated.execSQL("INSERT INTO messages ($cols, call_id) VALUES ('c4', 'm4', '$dm', 'b', 'a', '{}', '2026-10-01T10:03:00.000Z', 4000, 'READ', 0, 'call', 'call-1')")
            migrated.execSQL("INSERT INTO chat_tabs (conversation_id, chat_id, tab, chat_kind) VALUES ('$dm', '$dm', 'private', 'dm')")
            migrated.execSQL("INSERT INTO contacts (phone, display_name, company, user_id, registered, friend) VALUES ('+10000000000', 'Peer', 'Co', 'b', 1, 1)")
            val tables = entities(14).keys
            val before = tables.associateWith { migrated.dump(it) }
            val countsBefore = migrated.rows("SELECT conversation_id, COUNT(*) FROM messages GROUP BY conversation_id")
            Migration14To15.SQL.forEach { migrated.execSQL(it) }
            fresh.create(15)
            val v15 = entities(15)
            assertTrue(listOf("gcal_calendars", "gcal_copies").all { it in v15.keys })
            for (table in v15.keys) assertEquals(table, fresh.columns(table), migrated.columns(table))
            assertEquals(fresh.indices(), migrated.indices())
            for (t in tables) assertEquals("$t unchanged", before[t], migrated.dump(t))
            assertEquals(countsBefore, migrated.rows("SELECT conversation_id, COUNT(*) FROM messages GROUP BY conversation_id"))
            assertEquals(emptyList<List<String?>>(), migrated.rows("SELECT * FROM gcal_calendars"))
            assertEquals(emptyList<List<String?>>(), migrated.rows("SELECT * FROM gcal_copies"))
            for (t in entities(14).keys) assertEquals(t, entities(14)[t]!!["createSql"], v15[t]!!["createSql"])
            Migration14To15.SQL.forEach { migrated.execSQL(it) }
            for (t in tables) assertEquals("$t unchanged after a retried open", before[t], migrated.dump(t))
        } finally {
            migrated.close()
            fresh.close()
        }
    }
}
