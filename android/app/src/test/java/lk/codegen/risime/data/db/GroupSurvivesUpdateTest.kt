package lk.codegen.risime.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lk.codegen.risime.data.groups.rejoinPlan
import lk.codegen.risime.net.GroupReply
import lk.codegen.risime.net.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * "Groups disappeared after updating to nightly.16": an update over nightly.12 (DB v5) runs the
 * additive migrations 5→6→7→8 and keeps the group rows, its members and the MLS state (mls_kv), so
 * the updated app holds the group's generation and owes no rejoin. (The pilot's lost groups were
 * new device ids, i.e. fresh installs, not the update path.)
 */
class GroupSurvivesUpdateTest {
    private val dir = File(System.getProperty("risime.schemas"), AppDatabase::class.java.name)

    private fun entities(v: Int): Map<String, JsonObject> =
        Json.parseToJsonElement(File(dir, "$v.json").readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject }.associateBy { it["tableName"]!!.jsonPrimitive.content }

    private fun SQLiteConnection.create(v: Int) = entities(v).forEach { (name, e) ->
        execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name))
        e["indices"]?.jsonArray?.forEach { execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name)) }
    }

    private fun SQLiteConnection.long(sql: String): Long = prepare(sql).use { st -> st.step(); st.getLong(0) }

    private fun SQLiteConnection.text(sql: String): String? = prepare(sql).use { st -> if (st.step() && !st.isNull(0)) st.getText(0) else null }

    @Test fun aNightly12DatabaseKeepsItsGroupsThroughTheNightly16Migrations() {
        val group = ProtocolJson.decodeFromString(GroupReply.serializer(), File(System.getProperty("user.dir"), "../../contract/v1/examples/group_reply.json").readText()).group
        val conv = group.id
        val me = group.members.first { it.role == "admin" }.userId
        val db = BundledSQLiteDriver().open(":memory:")
        try {
            db.create(5)
            db.execSQL("INSERT INTO `groups` (conversation_id, name, my_role, state, created_by, created_at, generation, epoch_seen, meta_updated_at, last_refreshed_at, local_ts) VALUES ('$conv', 'Team', 'admin', 'active', '$me', '2026-10-06T08:00:00.000Z', ${group.generation}, 4, 1, 1, 1)")
            group.members.forEach { m ->
                db.execSQL("INSERT INTO group_members (conversation_id, user_id, display_name, phone, role, kind, state, joined_at) VALUES ('$conv', '${m.userId}', '${m.displayName}', NULL, '${m.role}', 'user', '${m.state}', NULL)")
            }
            val kvCols = entities(5)["mls_kv"]!!["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
            db.execSQL("INSERT INTO mls_kv (${kvCols.joinToString()}) VALUES (${kvCols.joinToString { c -> if (c == kvCols.first()) "'group:$conv'" else "x'00'" }})")
            db.execSQL(
                "INSERT INTO messages (client_msg_id, message_id, conversation_id, from_id, to_id, body, server_ts, local_ts, status, outgoing, kind) " +
                    "VALUES ('g1', 'm1', '$conv', '$me', '$conv', 'hello group', '2026-10-06T08:15:30.456Z', 1, 'SENT', 1, 'text')",
            )

            (Migration5To6.SQL + Migration6To7.SQL + Migration7To8.SQL + Migration8To9.SQL).forEach { db.execSQL(it) }

            assertEquals("Team", db.text("SELECT name FROM `groups` WHERE conversation_id = '$conv'"))
            assertEquals(group.members.size.toLong(), db.long("SELECT COUNT(*) FROM group_members WHERE conversation_id = '$conv'"))
            assertEquals(1L, db.long("SELECT COUNT(*) FROM mls_kv"))
            assertEquals("hello group", db.text("SELECT body FROM messages WHERE client_msg_id = 'g1'"))
            // The MLS state still holds the generation: nothing to rejoin after the update.
            assertNull(rejoinPlan(group, me, db.long("SELECT generation FROM `groups` WHERE conversation_id = '$conv'")))
        } finally {
            db.close()
        }
    }
}
