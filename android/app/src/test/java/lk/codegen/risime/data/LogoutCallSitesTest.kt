package lk.codegen.risime.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P0 nightly.10: no automatic path may log out or wipe. Structurally, [lk.codegen.risime.AppContainer.logout]
 * needs a [UserConfirmation], which only the confirm dialog creates; the wipe itself is reachable
 * only through [LocalAccount]. This test pins those call sites in the app sources.
 */
class LogoutCallSitesTest {
    private val main = File("src/main/java") // working dir = app module

    private fun sites(pattern: Regex): List<String> = main.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (pattern.containsMatchIn(l) && !l.trimStart().startsWith("*") && !l.trimStart().startsWith("//")) "${f.relativeTo(main).path.substringAfterLast('/')}:${i + 1}" else null } }
        .toList()

    private fun files(pattern: Regex) = sites(pattern).map { it.substringBefore(':') }.toSet()

    @Test fun onlyTheConfirmDialogCreatesAConfirmation() {
        assertTrue(main.isDirectory)
        assertEquals(setOf("LocalAccount.kt", "ConfirmLogout.kt"), files(Regex("""fromConfirmDialog\(""")))
        assertEquals(1, sites(Regex("""fromConfirmDialog\(""")).count { it.startsWith("ConfirmLogout.kt") })
    }

    @Test fun wipesAreReachableOnlyThroughLocalAccount() {
        assertEquals(setOf("AppContainer.kt", "Daos.kt"), files(Regex("""allChatData\(""")))
        assertEquals(setOf("AppContainer.kt"), files(Regex("""wipeDb\(""")))
        assertEquals("only clearLocal (explicit logout) and switchServer", 2, sites(Regex("""localAccount\.(logout|switchServer)\(""")).size)
        // Nothing outside the confirmed logout calls the container's logout.
        val callers = sites(Regex("""\bc\.logout\(|\bcontainer\.logout\("""))
        assertTrue("every caller passes the dialog's confirmation: $callers", callers.all { site ->
            val (file, line) = site.split(':')
            main.walkTopDown().first { it.name == file }.readLines()[line.toInt() - 1].contains(Regex("""logout\((confirmed|it)\)"""))
        })
    }
}
