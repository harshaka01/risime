package lk.codegen.risime.data.media

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.net.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §33.13 / §33.20 gate 15: `file` validation (bad names dropped, `parts` placeholder, mime fallback, APK rule). */
class FileEnvelopeTest {
    private val example = ProtocolJson.parseToJsonElement(javaClass.classLoader!!.getResource("contract/v1/examples/file_payload.json")!!.readText()) as JsonObject

    private fun with(k: String, v: String) = JsonObject(example + (k to JsonPrimitive(v)))

    @Test fun badNamesAreDropped() {
        for (bad in listOf("", "a/b.pdf", "a\\b.pdf", "a\u0000b", "x‮exe.pdf", "x⁦y", "tab\there", "c1\u0085", "x".repeat(256))) {
            assertNull(bad, FileEnvelope.validate(with("name", bad)))
            assertTrue(bad, MlsPayload.decode(with("name", bad).toString().toByteArray()) is MlsPayload.Decoded.Ignored)
        }
        // Not NFC: dropped.
        assertNull(FileEnvelope.validate(with("name", "é.pdf")))
        assertNotNull(FileEnvelope.validate(with("name", "é.pdf")))
        // 255 bytes exactly is fine.
        assertNotNull(FileEnvelope.validate(with("name", "x".repeat(251) + ".pdf")))
    }

    @Test fun mimeFallsBackAndIsNeverTrusted() {
        assertEquals(FileEnvelope.OCTET_STREAM, FileEnvelope.validate(with("mime", "Application/PDF"))!!.mime)
        assertEquals(FileEnvelope.OCTET_STREAM, FileEnvelope.validate(with("mime", "text"))!!.mime)
        assertEquals(FileEnvelope.OCTET_STREAM, FileEnvelope.validate(JsonObject(example - "mime"))!!.mime)
        assertEquals("application/zip", FileEnvelope.validate(with("mime", "application/zip"))!!.mime)
    }

    @Test fun apkHasNoOpen() {
        assertTrue(FileEnvelope.validate(with("mime", FileEnvelope.MIME_APK))!!.isApk)
        assertTrue(FileEnvelope.validate(with("name", "game.APK"))!!.isApk)
        assertFalse(FileEnvelope.validate(example)!!.isApk)
    }

    @Test fun displayNameStripsLeadingDotsAndCutsAt120Graphemes() {
        assertEquals("bashrc", FileEnvelope.displayName("..bashrc"))
        assertEquals(120, FileEnvelope.displayName("ශ්‍රී".repeat(200)).let { s -> java.text.BreakIterator.getCharacterInstance().apply { setText(s) }.let { bi -> var n = 0; while (bi.next() != java.text.BreakIterator.DONE) n++; n } })
    }

    @Test fun sendersSanitiseNames() {
        assertEquals("a-b-c.pdf", FileEnvelope.sanitizeName("a/b\\c.pdf"))
        assertEquals("x-y", FileEnvelope.sanitizeName("  x‮y  "))
        val long = FileEnvelope.sanitizeName("ශ".repeat(200))
        assertTrue(long.toByteArray().size <= 255)
        assertTrue(FileEnvelope.validName(long))
        assertEquals("file", FileEnvelope.sanitizeName("   "))
    }

    @Test fun pagesAndThumbAreChecked() {
        assertNull(FileEnvelope.validate(JsonObject(example + ("pages" to JsonPrimitive(0)))))
        assertNull(FileEnvelope.validate(JsonObject(example + ("pages" to JsonPrimitive(10_001)))))
        assertNull(FileEnvelope.validate(JsonObject(example + ("pages" to JsonPrimitive("3")))))
        assertNotNull(FileEnvelope.validate(JsonObject(example - "pages")))
        assertNull(FileEnvelope.validate(JsonObject(example + ("thumb" to JsonObject(mapOf("mime" to JsonPrimitive("image/png")))))))
    }

    @Test fun aLongCaptionDropsTheThumbFirst() {
        val e = FileEnvelope.validate(example)!!.copy(caption = "c".repeat(22_400))
        val obj = ProtocolJson.parseToJsonElement(e.encode().decodeToString()) as JsonObject
        assertEquals(kotlinx.serialization.json.JsonNull, obj["thumb"])
    }
}
