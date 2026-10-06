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
 * v5 → v6 (contract v1.11 images) on real SQLite: a migrated v5 database has exactly the columns and
 * indices of a fresh v6 one, v5 rows survive, and the outbox query skips image rows until uploaded.
 */
class Migration5To6Test {
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

    @Test fun migratedV5MatchesAFreshV6AndKeepsData() {
        val driver = BundledSQLiteDriver()
        val migrated = driver.open(":memory:")
        val fresh = driver.open(":memory:")
        try {
            migrated.create(5)
            migrated.execSQL(
                "INSERT INTO messages (client_msg_id, message_id, conversation_id, from_id, to_id, body, server_ts, local_ts, status, outgoing, kind) " +
                    "VALUES ('c1', 'm1', 'dm:a_b', 'a', 'b', 'hello', '2026-10-06T08:15:30.456Z', 1, 'SENT', 1, 'text')",
            )
            migrated.execSQL("INSERT INTO `groups` (conversation_id, name, my_role, state, generation, local_ts) VALUES ('grp:g', 'Pilot', 'admin', 'active', 1, 1)")
            Migration5To6.SQL.forEach { migrated.execSQL(it) }
            fresh.create(6)

            val v6 = entities(6)
            assertTrue("media" in v6.keys)
            for (table in v6.keys) assertEquals(table, fresh.columns(table), migrated.columns(table))
            assertEquals(fresh.indices(), migrated.indices())

            assertEquals("hello", migrated.text("SELECT body FROM messages WHERE client_msg_id = 'c1'"))
            assertEquals(null, migrated.text("SELECT blob_id FROM messages WHERE client_msg_id = 'c1'"))
            assertEquals("Pilot", migrated.text("SELECT name FROM `groups`"))

            // Unchanged tables keep exactly their v5 definition.
            val v5 = entities(5)
            for (t in v5.keys - "messages") assertEquals(t, v5[t]!!["createSql"], v6[t]!!["createSql"])

            // The outbox (MessageDao.pendingOutbox) skips an image until its blob reference is stored.
            fun insertPending(id: String, kind: String, ts: Int) = migrated.execSQL(
                "INSERT INTO messages (client_msg_id, conversation_id, from_id, to_id, body, local_ts, status, outgoing, kind) VALUES ('$id', 'dm:a_b', 'a', 'b', '', $ts, 'PENDING', 1, '$kind')",
            )
            insertPending("img", "image", 10)
            insertPending("txt", "text", 11)
            migrated.execSQL(
                "INSERT INTO media (client_msg_id, conversation_id, outgoing, state, blob_size, blob_sha256, sealed_enc, mime, w, h, bytes_have, last_access, attempts, next_at) " +
                    "VALUES ('img', 'dm:a_b', 1, 'UPLOADING', 17, 'x', x'00', 'image/jpeg', 1, 1, 0, 0, 0, 0)",
            )
            val outbox = "SELECT group_concat(client_msg_id) FROM (SELECT m.client_msg_id FROM messages m LEFT JOIN media x ON x.client_msg_id = m.client_msg_id " +
                "WHERE m.outgoing = 1 AND m.status = 'PENDING' AND (m.kind != 'image' OR x.state = 'UPLOADED') ORDER BY m.local_ts ASC)"
            assertEquals("txt", migrated.text(outbox))
            migrated.execSQL("UPDATE media SET state = 'UPLOADED', blob_id = 'b1' WHERE client_msg_id = 'img'")
            assertEquals("img,txt", migrated.text(outbox))
        } finally {
            migrated.close()
            fresh.close()
        }
    }
}
