package lk.codegen.risime.data.db

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P0 nightly.10 rule: installed builds keep their chats, so no Room destructive fallback may exist
 * anywhere in the app sources (a missing migration must crash, never wipe).
 */
class NoDestructiveMigrationTest {
    private val banned = listOf("fallbackToDestructiveMigration", "fallbackToDestructiveMigrationFrom", "fallbackToDestructiveMigrationOnDowngrade", "deleteDatabase(")

    @Test fun noDestructiveFallbackInAppSources() {
        val src = File("src") // working dir = app module
        assertTrue("app sources not found from ${File(".").absolutePath}", File(src, "main").isDirectory)
        val hits = src.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { it.name != "NoDestructiveMigrationTest.kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (banned.any { l.contains(it) }) "${f.path}:${i + 1}" else null } }
            .toList()
        assertTrue("destructive Room fallback / database deletion found: $hits", hits.isEmpty())
    }
}
