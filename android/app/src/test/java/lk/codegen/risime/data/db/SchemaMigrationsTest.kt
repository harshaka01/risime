package lk.codegen.risime.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM-level guard for Room upgrades (the SQL itself is verified on device): every version has a
 * committed exported schema, and every step n-1 → n has exactly one Migration.
 */
class SchemaMigrationsTest {
    private val dir = File("schemas/${AppDatabase::class.java.name}") // working dir = app module

    @Test fun everyVersionHasAnExportedSchema() {
        for (v in 1..AppDatabase.VERSION) {
            val f = File(dir, "$v.json")
            assertTrue("missing exported schema ${f.path}; build once and commit app/schemas", f.isFile)
            assertTrue(f.readText().contains("\"version\": $v"))
        }
        val extra = dir.listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.filter { it > AppDatabase.VERSION }
        assertTrue("schemas newer than AppDatabase.VERSION: $extra", extra.isEmpty())
    }

    @Test fun everyStepHasOneMigration() {
        val steps = AppDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals((2..AppDatabase.VERSION).map { it - 1 to it }, steps.sortedBy { it.first })
    }
}
