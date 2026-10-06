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
import org.junit.Test
import java.io.File

/** v3 → v4 on real SQLite: the reactions table matches the exported v4 schema exactly; data survives. */
class Migration3To4Test {
    private val dir = File(System.getProperty("risime.schemas"), AppDatabase::class.java.name)

    private fun db(v: Int): JsonObject = Json.parseToJsonElement(File(dir, "$v.json").readText()).jsonObject["database"]!!.jsonObject

    private fun entities(v: Int) = db(v)["entities"]!!.jsonArray.map { it.jsonObject }.associateBy { it["tableName"]!!.jsonPrimitive.content }

    private fun SQLiteConnection.master(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        prepare("SELECT name, sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'").use { st ->
            while (st.step()) out[st.getText(0)] = st.getText(1).replace("IF NOT EXISTS ", "")
        }
        return out
    }

    @Test fun reactionsTableMatchesTheExportedSchema() {
        val conn = BundledSQLiteDriver().open(":memory:")
        try {
            entities(3).forEach { (name, e) ->
                conn.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name))
            }
            conn.execSQL("INSERT INTO contacts (phone, display_name, company, user_id, registered, friend) VALUES ('+941', 'Kamal', 'Rise', 'u1', 1, 1)")

            Migration3To4.SQL.forEach { conn.execSQL(it) }

            val v4 = entities(4)
            val master = conn.master()
            for (table in listOf("reactions")) {
                val want = v4[table]!!["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table).replace("IF NOT EXISTS ", "")
                assertEquals(table, want, master[table])
                v4[table]!!["indices"]?.jsonArray?.forEach { idx ->
                    val o = idx.jsonObject
                    val name = o["name"]!!.jsonPrimitive.content
                    val sql = o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table).replace("IF NOT EXISTS ", "")
                    assertEquals(name, sql, master[name])
                }
            }
            // Everything that existed in v3 is unchanged in v4.
            val v3 = entities(3)
            v3.keys.forEach { t -> assertEquals(t, v3[t]!!["createSql"], v4[t]!!["createSql"]) }
            conn.prepare("SELECT display_name FROM contacts").use { st -> st.step(); assertEquals("Kamal", st.getText(0)) }
        } finally {
            conn.close()
        }
    }
}
