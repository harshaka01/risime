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
 * v15 → v16 (contract v1.34 §33, §33.18 upgrade gate, hard rule 9) on real SQLite: additive only. A
 * migrated v15 database equals a fresh v16 one; every v15 row keeps every v15 column value; the
 * per-conversation counts (1:1, groups, photos, call records) are identical; the new columns are NULL.
 */
class Migration15To16Test {
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

    /** A table's rows, only over [cols] (the v15 columns), in rowid order. */
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

    @Test fun migratedV15MatchesAFreshV16AndKeepsEveryRow() {
        val driver = BundledSQLiteDriver()
        val migrated = driver.open(":memory:")
        val fresh = driver.open(":memory:")
        try {
            migrated.create(15)
            val cols = "client_msg_id, message_id, conversation_id, from_id, to_id, body, server_ts, local_ts, status, outgoing, kind"
            migrated.execSQL("INSERT INTO messages ($cols) VALUES ('c1', 'm1', 'dm:a_b', 'b', 'a', 'hello', '2026-10-01T10:00:00.000Z', 1000, 'READ', 0, 'text')")
            migrated.execSQL("INSERT INTO messages ($cols) VALUES ('c2', 'm2', 'dm:a_b', 'a', 'b', 'hi back', '2026-10-01T10:01:00.000Z', 2000, 'READ', 1, 'text')")
            migrated.execSQL("INSERT INTO messages ($cols, blob_id) VALUES ('c3', 'm3', 'grp:g', 'a', 'grp:g', 'level 3', '2026-10-01T10:02:00.000Z', 3000, 'DELIVERED', 1, 'image', 'blob-1')")
            migrated.execSQL("INSERT INTO messages ($cols, call_id, system_json) VALUES ('c4', 'm4', 'dm:a_b', 'b', 'a', 'Missed voice call', '2026-10-01T10:03:00.000Z', 4000, 'READ', 0, 'call', 'call-1', '{}')")
            migrated.execSQL("INSERT INTO messages ($cols) VALUES ('c5', NULL, 'grp:g', 'a', 'grp:g', 'pending', NULL, 5000, 'PENDING', 1, 'text')")
            migrated.execSQL(
                "INSERT INTO media (client_msg_id, conversation_id, outgoing, state, blob_id, blob_size, blob_sha256, client_blob_id, sealed_enc, sealed_thumb, mime, w, h, file_name, bytes_have, expires_at_est, last_access, attempts, next_at, fail_reason) " +
                    "VALUES ('c3', 'grp:g', 1, 'UPLOADED', 'blob-1', 100, 'sha', 'cb', x'00', NULL, 'image/jpeg', 10, 10, 'f', 0, NULL, 0, 0, 0, NULL)",
            )
            migrated.execSQL("INSERT INTO `groups` (conversation_id, name, my_role, state, created_by, created_at, generation, epoch_seen, meta_updated_at, last_refreshed_at, local_ts) VALUES ('grp:g', 'Pilot team', 'admin', 'active', 'a', NULL, 1, 3, NULL, NULL, 5)")
            val v15 = entities(15)
            val v15Columns = v15.keys.associateWith { migrated.columnNames(it) }
            val before = v15.keys.associateWith { migrated.dump(it, v15Columns.getValue(it)) }
            val countsBefore = migrated.counts()

            Migration15To16.SQL.forEach { migrated.execSQL(it) }
            fresh.create(16)

            val v16 = entities(16)
            assertEquals(v15.keys + "stars", v16.keys)
            for (table in v16.keys) assertEquals(table, fresh.columns(table), migrated.columns(table))
            assertEquals(fresh.indices(), migrated.indices())
            // Only nullable columns were added to messages (hard rule 9: additive).
            val added = migrated.columnNames("messages") - v15Columns.getValue("messages").toSet()
            assertEquals(listOf("forward_hops", "reply_to_message_id", "reply_to_from", "delivered_at", "read_at", "view_once"), added)
            assertTrue(migrated.columns("messages").filter { it.substringBefore('|') in added }.all { it.split('|')[2] == "0" })
            for (table in v15.keys) assertEquals("rule 9: $table unchanged", before[table], migrated.dump(table, v15Columns.getValue(table)))
            assertEquals("§33.18 counts", countsBefore, migrated.counts())
            assertEquals(5L, migrated.long("SELECT COUNT(*) FROM messages"))
            assertEquals(5L, migrated.long("SELECT COUNT(*) FROM messages WHERE forward_hops IS NULL AND reply_to_message_id IS NULL AND delivered_at IS NULL AND read_at IS NULL AND view_once IS NULL"))
            // The new table and columns work as Room writes them.
            migrated.execSQL("INSERT INTO stars (message_id, conversation_id, starred_at) VALUES ('m1', 'dm:a_b', 7)")
            migrated.execSQL("UPDATE messages SET forward_hops = 2, reply_to_message_id = 'm1', reply_to_from = 'b', delivered_at = 9, read_at = 10 WHERE client_msg_id = 'c2'")
            assertEquals(1L, migrated.long("SELECT COUNT(*) FROM stars s JOIN messages m ON m.message_id = s.message_id"))
            assertEquals(2L, migrated.long("SELECT forward_hops FROM messages WHERE client_msg_id = 'c2'"))
        } finally {
            migrated.close()
            fresh.close()
        }
    }
}
