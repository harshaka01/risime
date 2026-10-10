package lk.codegen.risime.debug

import kotlinx.coroutines.runBlocking
import lk.codegen.risime.data.gcal.AuthResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v1.31 §31.11: the debug seam refuses any host but loopback / the emulator host, and its pieces behave. */
class GcalTestSeamTest {
    @Test fun onlyLoopbackAndTheEmulatorHostAreAccepted() {
        assertTrue(GcalTest.baseUrlAllowed("http://127.0.0.1:8198/"))
        assertTrue(GcalTest.baseUrlAllowed("http://10.0.2.2:8198/"))
        for (bad in listOf(
            "https://127.0.0.1:8198/", "http://localhost:8198/", "http://192.168.1.5:8198/", "http://127.0.0.1.evil.com:8198/", "http://evil.com:8198/",
            "http://127.0.0.1:8198", "http://127.0.0.1/", "http://127.0.0.1:99999/", "http://127.0.0.1:0/", "https://www.googleapis.com/calendar/v3/", "http://10.0.2.2:8198/x/", "",
        )) assertFalse(bad, GcalTest.baseUrlAllowed(bad))
        assertFalse(GcalTest.baseUrlAllowed(null))
    }

    @Test fun aRefusedHostInstallsNothing() {
        assertNull(GcalTest.hooks("http://evil.com:1/", "T", "a@test", "connected"))
        assertNull(GcalTest.hooks("http://127.0.0.1:8198/", "", "a@test", "connected"))
        assertNull(GcalTest.hooks("http://127.0.0.1:8198/", "T", "a@test", "none"))
        assertNotNull(GcalTest.hooks("http://127.0.0.1:8198/", "T", "a@test", "connected"))
    }

    @Test fun theFakeAuthorizerReturnsTheTokenOrNeedsAResolution() = runBlocking {
        val ok = GcalTest.hooks("http://10.0.2.2:8198/", "T", "a@test", "connected")!!.first
        assertEquals("T", (ok.authorize() as AuthResult.Token).value)
        assertEquals("a@test", ok.accountName())
        val re = GcalTest.hooks("http://10.0.2.2:8198/", "T", "a@test", "reauth_needed")!!.first
        assertTrue(re.authorize() is AuthResult.NeedsResolution)
    }

    @Test fun theReceiverIsInTheDebugManifestOnlyAndProtectedByDump() {
        val dir = File(System.getProperty("user.dir"))
        val debug = File(dir, "src/debug/AndroidManifest.xml").readText()
        assertTrue("android.permission.DUMP" in debug && "lk.codegen.risime.debug.GCAL_TEST" in debug)
        val main = File(dir, "src/main/AndroidManifest.xml").readText()
        val release = File(dir, "src/release/AndroidManifest.xml").readText()
        assertFalse("GCAL_TEST" in main || "GcalTestReceiver" in main)
        assertFalse("GCAL_TEST" in release || "GcalTestReceiver" in release)
        // No main-source file mentions the action string or the receiver.
        val leaks = File(dir, "src/main").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { val t = it.readText(); "GCAL_TEST" in t || "GcalTestReceiver" in t }.map { it.path }.toList()
        assertEquals(emptyList<String>(), leaks)
    }
}
