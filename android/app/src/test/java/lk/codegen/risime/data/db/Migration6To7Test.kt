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
 * v6 → v7 (contract v1.12 deletes) on real SQLite: a migrated v6 database has exactly the columns and
 * indices of a fresh v7 one, v6 rows survive with the new defaults, and a PENDING row counts as pushed.
 */
class Migration6To7Test {
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
                // name, type, notnull, dflt_value, pk
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

    private fun SQLiteConnection.text(sql: String): String? = prepare(sql).use { st -> if (st.step() && !st.isNull(0)) st.getText(0) else null }

    private fun SQLiteConnection.long(sql: String): Long = prepare(sql).use { st -> st.step(); st.getLong(0) }

    @Test fun migratedV6MatchesAFreshV7AndKeepsData() {
        val driver = BundledSQLiteDriver()
        val migrated = driver.open(":memory:")
        val fresh = driver.open(":memory:")
        try {
            migrated.create(6)
            migrated.execSQL(
                "INSERT INTO messages (client_msg_id, message_id, conversation_id, from_id, to_id, body, server_ts, local_ts, status, outgoing, kind, blob_id) " +
                    "VALUES ('c1', 'm1', 'dm:a_b', 'a', 'b', 'hello', '2026-10-06T08:15:30.456Z', 1, 'SENT', 1, 'text', NULL)",
            )
            migrated.execSQL(
                "INSERT INTO messages (client_msg_id, conversation_id, from_id, to_id, body, local_ts, status, outgoing, kind) " +
                    "VALUES ('p1', 'dm:a_b', 'a', 'b', 'pending', 2, 'PENDING', 1, 'text')",
            )
            Migration6To7.SQL.forEach { migrated.execSQL(it) }
            fresh.create(7)

            val v7 = entities(7)
            assertTrue(listOf("deleted_ids", "delete_outbox", "chat_state").all { it in v7.keys })
            for (table in v7.keys) assertEquals(table, fresh.columns(table), migrated.columns(table))
            assertEquals(fresh.indices(), migrated.indices())

            assertEquals("hello", migrated.text("SELECT body FROM messages WHERE client_msg_id = 'c1'"))
            assertEquals(null, migrated.text("SELECT deleted_by FROM messages WHERE client_msg_id = 'c1'"))
            assertEquals(0L, migrated.long("SELECT deleted_by_admin + delete_unverified + send_attempts FROM messages WHERE client_msg_id = 'c1'"))
            assertEquals(null, migrated.text("SELECT delete_state FROM messages WHERE client_msg_id = 'c1'"))
            // Android R2: a PENDING row from before v7 may have been pushed (reply lost): never cancelled silently.
            assertEquals(1L, migrated.long("SELECT send_attempts FROM messages WHERE client_msg_id = 'p1'"))
            assertEquals(0L, migrated.long("SELECT hidden FROM chat_state UNION ALL SELECT 0 LIMIT 1"))

            // Unchanged tables keep exactly their v6 definition.
            val v6 = entities(6)
            for (t in v6.keys - "messages") assertEquals(t, v6[t]!!["createSql"], v7[t]!!["createSql"])
        } finally {
            migrated.close()
            fresh.close()
        }
    }
}
