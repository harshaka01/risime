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
 * v4 → v5 (contract v1.9 groups) on real SQLite. Room validates by column info, not SQL text, so a
 * migrated v4 database must have exactly the columns (type, NOT NULL, default, pk) and indices of a
 * fresh v5 one, and v4 rows must survive with the new defaults.
 */
class Migration4To5Test {
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

    @Test fun migratedV4MatchesAFreshV5AndKeepsData() {
        val driver = BundledSQLiteDriver()
        val migrated = driver.open(":memory:")
        val fresh = driver.open(":memory:")
        try {
            migrated.create(4)
            migrated.execSQL(
                "INSERT INTO messages (client_msg_id, message_id, conversation_id, from_id, to_id, body, server_ts, local_ts, status, outgoing) " +
                    "VALUES ('c1', 'm1', 'dm:a_b', 'a', 'b', 'hello', '2026-10-06T08:15:30.456Z', 1, 'SENT', 1)",
            )
            migrated.execSQL("INSERT INTO contacts (phone, display_name, company, user_id, registered, friend) VALUES ('+941', 'Kamal', 'Rise', 'u1', 1, 1)")
            migrated.execSQL(
                "INSERT INTO reactions (conversation_id, target_message_id, reactor_user_id, emoji, op, pending, local_ts) VALUES ('dm:a_b', 'm1', 'b', '👍', 'add', 0, 2)",
            )
            migrated.execSQL("INSERT INTO mls_kv (namespace, key, value) VALUES ('mls', x'01', x'02')")

            Migration4To5.SQL.forEach { migrated.execSQL(it) }
            fresh.create(5)

            val v5 = entities(5)
            assertTrue(v5.keys.containsAll(listOf("groups", "group_members", "group_ops")))
            for (table in v5.keys) assertEquals(table, fresh.columns(table), migrated.columns(table))
            assertEquals(fresh.indices(), migrated.indices())

            // v4 data survives, with the new defaults.
            assertEquals("hello", migrated.text("SELECT body FROM messages WHERE client_msg_id = 'c1'"))
            assertEquals("text", migrated.text("SELECT kind FROM messages WHERE client_msg_id = 'c1'"))
            assertEquals(null, migrated.text("SELECT receipt_of FROM messages WHERE client_msg_id = 'c1'"))
            assertEquals("0", migrated.text("SELECT group_ready FROM contacts WHERE phone = '+941'"))
            assertEquals("👍", migrated.text("SELECT emoji FROM reactions"))
            assertEquals("1", migrated.text("SELECT COUNT(*) FROM mls_kv"))

            // Unchanged tables keep exactly their v4 definition.
            val v4 = entities(4)
            for (t in listOf("sync_state", "seen_events", "behaviour_events", "mls_kv", "mls_pending", "reactions")) {
                assertEquals(t, v4[t]!!["createSql"], v5[t]!!["createSql"])
            }

            // A group system line and an op row insert as the app writes them.
            migrated.execSQL(
                "INSERT INTO messages (client_msg_id, message_id, conversation_id, from_id, to_id, body, server_ts, local_ts, status, outgoing, kind, system_json) " +
                    "VALUES ('sys:e1', NULL, 'grp:g', 'a', 'grp:g', 'Kamal created the group', NULL, 3, 'READ', 0, 'system', '{}')",
            )
            migrated.execSQL("INSERT INTO group_ops (conversation_id, type, payload_json, state, attempts, created_at, op_id, next_at) VALUES ('grp:g', 'commit', '{}', 'queued', 0, 1, 'op1', 0)")
            assertEquals("system", migrated.text("SELECT kind FROM messages WHERE client_msg_id = 'sys:e1'"))
        } finally {
            migrated.close()
            fresh.close()
        }
    }

    @Test fun repeatedOpIdIsRejectedByTheUniqueIndex() {
        val conn = BundledSQLiteDriver().open(":memory:")
        try {
            conn.create(4)
            Migration4To5.SQL.forEach { conn.execSQL(it) }
            conn.execSQL("INSERT INTO group_ops (conversation_id, type, payload_json, state, attempts, created_at, op_id, next_at) VALUES ('grp:g', 'commit', '{}', 'queued', 0, 1, 'op1', 0)")
            conn.execSQL("INSERT OR IGNORE INTO group_ops (conversation_id, type, payload_json, state, attempts, created_at, op_id, next_at) VALUES ('grp:g', 'commit', '{}', 'queued', 0, 1, 'op1', 0)")
            // Ops without a server op id (create, rename, leave …) never collide.
            repeat(2) { conn.execSQL("INSERT INTO group_ops (conversation_id, type, payload_json, state, attempts, created_at, next_at) VALUES ('grp:g', 'rename', '{}', 'queued', 0, 1, 0)") }
            assertEquals("3", conn.text("SELECT COUNT(*) FROM group_ops"))
        } finally {
            conn.close()
        }
    }
}
