package lk.codegen.risime.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsInputTest {
    private fun valid(input: String) = (checkServerUrl(input) as Checked.Valid).value

    @Test fun acceptsHttpAndHttps() {
        assertEquals("http://10.0.2.2:4400", valid("http://10.0.2.2:4400"))
        assertEquals("http://127.0.0.1:4000", valid("  http://127.0.0.1:4000/ "))
        assertEquals("https://spark2.tail1234.ts.net", valid("https://spark2.tail1234.ts.net//"))
        assertEquals("HTTPS://Spark2.example.net", valid("HTTPS://Spark2.example.net"))
    }

    @Test fun rejectsOtherInput() {
        listOf("", "   ", "10.0.2.2:4400", "ftp://host", "ws://host:4000", "http://", "https://host/?a=1",
            "https://host/#x", "https://user:pw@host", "not a url").forEach {
            assertTrue("should reject '$it'", checkServerUrl(it) is Checked.Invalid)
        }
    }

    @Test fun sameServerIgnoresTrailingSlashAndHostCase() {
        assertTrue(sameServer("http://10.0.2.2:4400/", "http://10.0.2.2:4400"))
        assertTrue(sameServer("https://Spark2.example.net", "https://spark2.example.net/"))
        assertFalse(sameServer("http://10.0.2.2:4400", "http://127.0.0.1:4000"))
        assertFalse(sameServer("http://h:80", "https://h:80"))
    }

    @Test fun displayName() {
        assertEquals("Harsha", (checkDisplayName("  Harsha ") as Checked.Valid).value)
        assertTrue(checkDisplayName("   ") is Checked.Invalid)
        assertTrue(checkDisplayName("x".repeat(64)) is Checked.Valid)
        assertTrue(checkDisplayName("x".repeat(65)) is Checked.Invalid)
    }
}
