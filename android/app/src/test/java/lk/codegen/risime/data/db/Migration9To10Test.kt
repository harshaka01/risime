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

/** v9 → v10 (contract v1.17 profile photos) on real SQLite: a migrated v9 database equals a fresh v10 one and keeps every chat (hard rule 9). */
class Migration9To10Test {
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

    private fun SQLiteConnection.indices(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        prepare("SELECT name, sql FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL").use { st ->
            while (st.step()) out[st.getText(0)] = st.getText(1).replace("IF NOT EXISTS ", "")
        }
        return out
    }

    private fun SQLiteConnection.text(sql: String): String? = prepare(sql).use { st -> if (st.step() && !st.isNull(0)) st.getText(0) else null }

    private fun SQLiteConnection.long(sql: String): Long = prepare(sql).use { st -> st.step(); st.getLong(0) }

    @Test fun migratedV9MatchesAFreshV10AndKeepsData() {
        val driver = BundledSQLiteDriver()
        val migrated = driver.open(":memory:")
        val fresh = driver.open(":memory:")
        try {
            migrated.create(9)
            migrated.execSQL(
                "INSERT INTO messages (client_msg_id, message_id, conversation_id, from_id, to_id, body, server_ts, local_ts, status, outgoing, kind) " +
                    "VALUES ('c1', 'm1', 'dm:a_b', 'a', 'b', 'hello', '2026-10-06T08:15:30.456Z', 1, 'SENT', 1, 'text')",
            )
            migrated.execSQL(
                "INSERT INTO messages (client_msg_id, message_id, conversation_id, from_id, to_id, body, server_ts, local_ts, status, outgoing, kind, blob_id) " +
                    "VALUES ('c2', 'm2', 'grp:g', 'a', 'grp:g', '', '2026-10-06T08:15:31.456Z', 2, 'READ', 0, 'image', 'b1')",
            )
            migrated.execSQL(
                "INSERT INTO groups (conversation_id, name, my_role, state, created_by, created_at, generation, epoch_seen, meta_updated_at, last_refreshed_at, local_ts) " +
                    "VALUES ('grp:g', 'Pilot team', 'member', 'active', NULL, NULL, 1, 3, NULL, NULL, 5)",
            )
            Migration9To10.SQL.forEach { migrated.execSQL(it) }
            fresh.create(10)
            val v10 = entities(10)
            assertTrue(listOf("profile_photos", "profile_photo_convs").all { it in v10.keys })
            for (table in v10.keys) assertEquals(table, fresh.columns(table), migrated.columns(table))
            assertEquals(fresh.indices(), migrated.indices())
            assertEquals(2L, migrated.long("SELECT COUNT(*) FROM messages"))
            assertEquals("hello", migrated.text("SELECT body FROM messages WHERE client_msg_id = 'c1'"))
            assertEquals("b1", migrated.text("SELECT blob_id FROM messages WHERE client_msg_id = 'c2'"))
            assertEquals("Pilot team", migrated.text("SELECT name FROM groups WHERE conversation_id = 'grp:g'"))
            assertEquals(null, migrated.text("SELECT icon_sha FROM groups WHERE conversation_id = 'grp:g'"))
            val v9 = entities(9)
            for (t in v9.keys - "groups") assertEquals(t, v9[t]!!["createSql"], v10[t]!!["createSql"])
        } finally {
            migrated.close()
            fresh.close()
        }
    }
}
