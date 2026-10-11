package lk.codegen.risime.ui.share

import android.app.Application
import android.content.ContentValues
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * §33.10 / §33.20 gate 15: the ShareProvider pipe. The bytes stream through a pipe (no file under the
 * app's cache/ or files/), the name and size come from query(), writes are refused, URIs expire.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShareProviderTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val provider = ShareProvider()
    private var now = 1_000L

    @After fun clear() {
        ShareRegistry.clear()
        ShareRegistry.clock = System::currentTimeMillis
    }

    private fun files(): Set<String> = listOf(app.cacheDir, app.filesDir, app.noBackupFilesDir).flatMap { d -> d.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList() }.toSet()

    @Test fun pipeStreamsWithoutDiskWritesAndRefusesWrites() {
        ShareRegistry.clock = { now }
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val before = files()
        val uri = ShareRegistry.register(app.packageName, ShareItem("Plan.pdf", "application/pdf", bytes.size.toLong(), producer = { it.write(bytes) }, expiresAt = now + ShareRegistry.TTL_MS))
        assertEquals("application/pdf", provider.getType(uri))
        provider.query(uri, null, null, null, null)!!.use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Plan.pdf", c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(bytes.size.toLong(), c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        // The read side is a pipe (Robolectric's pipes don't carry data: the producer is checked directly).
        assertTrue(provider.openFile(uri, "r") != null)
        val out = java.io.ByteArrayOutputStream()
        ShareRegistry.get(uri)!!.producer!!(out)
        assertArrayEquals(bytes, out.toByteArray())
        assertNull("a share item is never a file", ShareRegistry.get(uri)!!.file)
        assertEquals("no plaintext file was written", before, files())
        for (mode in listOf("w", "rw", "wt", "wa")) {
            try { provider.openFile(uri, mode); fail(mode) } catch (e: SecurityException) { /* refused */ }
        }
        try { provider.insert(uri, ContentValues()); fail("insert") } catch (e: SecurityException) { /* refused */ }
        try { provider.delete(uri, null, null); fail("delete") } catch (e: SecurityException) { /* refused */ }
        // After 10 minutes the URI is gone.
        now += ShareRegistry.TTL_MS + 1
        assertNull(provider.getType(uri))
        try { provider.openFile(uri, "r"); fail("expired") } catch (e: java.io.FileNotFoundException) { /* gone */ }
    }

    @Test fun anOpenWithItemIsTheFileItself() {
        val f = File(app.noBackupFilesDir, "open/x/Plan.pdf").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val uri = ShareRegistry.register(app.packageName, ShareItem("Plan.pdf", "application/pdf", 3, file = f, expiresAt = System.currentTimeMillis() + 60_000))
        val got = android.os.ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r")!!).use { it.readBytes() }
        assertArrayEquals(byteArrayOf(1, 2, 3), got)
    }
}
