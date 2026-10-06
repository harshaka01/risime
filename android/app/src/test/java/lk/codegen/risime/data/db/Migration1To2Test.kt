package lk.codegen.risime.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Runs the real v1 → v2 migration SQL on a real SQLite (bundled JVM driver): builds the exported
 * v1 schema, inserts data, migrates, then checks the contacts table matches the exported v2 schema
 * column by column (what Room validates on open) and that the data survived.
 */
class Migration1To2Test {
    private val dir = File(System.getProperty("risime.schemas"), AppDatabase::class.java.name)

    private fun schema(v: Int): JsonObject = Json.parseToJsonElement(File(dir, "$v.json").readText()).jsonObject["database"]!!.jsonObject

    private fun entity(v: Int, table: String) =
        schema(v)["entities"]!!.jsonArray.map { it.jsonObject }.first { it["tableName"]!!.jsonPrimitive.content == table }

    private data class Col(val name: String, val type: String, val notNull: Boolean, val default: String?)

    private fun SQLiteConnection.columns(table: String): List<Col> {
        val out = mutableListOf<Col>()
        prepare("PRAGMA table_info(`$table`)").use { st ->
            while (st.step()) {
                out += Col(st.getText(1), st.getText(2), st.getLong(3) == 1L, if (st.isNull(4)) null else st.getText(4))
            }
        }
        return out.sortedBy { it.name }
    }

    private fun expected(table: String): List<Col> = entity(2, table)["fields"]!!.jsonArray.map {
        val f = it.jsonObject
        Col(
            f["columnName"]!!.jsonPrimitive.content,
            f["affinity"]!!.jsonPrimitive.content,
            f["notNull"]?.jsonPrimitive?.boolean ?: false,
            f["defaultValue"]?.jsonPrimitive?.content,
        )
    }.sortedBy { it.name }

    @Test fun contactsMigrateToV2AndKeepTheirData() {
        val conn = BundledSQLiteDriver().open(":memory:")
        try {
            schema(1)["entities"]!!.jsonArray.forEach { e ->
                val o = e.jsonObject
                conn.execSQL(o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", o["tableName"]!!.jsonPrimitive.content))
            }
            conn.execSQL("INSERT INTO contacts (phone, display_name, company, user_id, registered) VALUES ('+941', 'Kamal', 'Rise', 'u1', 1)")
            conn.execSQL("INSERT INTO contacts (phone, display_name, company, user_id, registered) VALUES ('+942', 'Ghost', 'Rise', NULL, 0)")

            Migration1To2.SQL.forEach { conn.execSQL(it) }

            assertEquals(expected("contacts"), conn.columns("contacts"))
            val rows = mutableListOf<String>()
            conn.prepare("SELECT phone, display_name, friend, vouched_by_name FROM contacts ORDER BY phone").use { st ->
                while (st.step()) rows += "${st.getText(0)}|${st.getText(1)}|${st.getLong(2)}|${if (st.isNull(3)) null else st.getText(3)}"
            }
            assertEquals(listOf("+941|Kamal|1|null", "+942|Ghost|0|null"), rows)
            // Every other table is unchanged between v1 and v2.
            for (t in listOf("messages", "sync_state", "seen_events", "behaviour_events")) {
                assertEquals(t, entity(1, t)["createSql"], entity(2, t)["createSql"])
            }
        } finally {
            conn.close()
        }
    }
}
