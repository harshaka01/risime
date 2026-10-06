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
        assertEquals("only clearLocal (confirmed delete) and switchServer", 2, sites(Regex("""localAccount\.(logoutAndDeleteChats|switchServer)\(""")).size)
        assertEquals(setOf("AppContainer.kt"), files(Regex("""localAccount\.(logoutKeepChats|logoutAndDeleteChats)\(""")))
        // Only the explicit "Log out" (Settings, chats menu) calls the wiping logout; escape screens
        // (blocked, identity conflict, locked, required update, confirm phone) use signOutKeepChats.
        assertEquals(setOf("ChatsViewModel.kt", "SettingsViewModel.kt"), files(Regex("""\bc\.logout\(""")))
        assertEquals(
            setOf("AppContainer.kt", "AuthScreens.kt", "AuthUi.kt", "UpdateUi.kt", "PhoneVerifyViewModel.kt"),
            files(Regex("""signOutKeepChats\(""")),
        )
        // Decision 050: only the labelled "Log out and delete chats" wipes or removes the device.
        val app = main.walkTopDown().first { it.name == "AppContainer.kt" }.readLines()
        val clear = app.withIndex().filter { (_, l) -> l.trim() == "clearLocal()" }
        assertEquals("clearLocal() is called once, from the logout", 1, clear.size)
        assertTrue("clearLocal() only when the user picked delete", app[clear.single().index - 2].contains("if (confirmed.deleteChats)"))
        val unreg = app.filter { it.contains("push.unregister()") }
        assertEquals(1, unreg.size)
        assertTrue("DELETE /me/devices only for delete: ${unreg.single()}", unreg.single().contains("if (confirmed.deleteChats) push.unregister() else push.unregisterPushOnly()"))
        // Nothing outside the confirmed logout calls the container's logout.
        val callers = sites(Regex("""\bc\.logout\(|\bcontainer\.logout\("""))
        assertTrue("every caller passes the dialog's confirmation: $callers", callers.all { site ->
            val (file, line) = site.split(':')
            main.walkTopDown().first { it.name == file }.readLines()[line.toInt() - 1].contains(Regex("""logout\((confirmed|it)\)"""))
        })
    }
}
