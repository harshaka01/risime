package lk.codegen.risime.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * §16.9 (android A1): exactly one libwebrtc, only for arm64-v8a and x86_64, in debug and release;
 * §16.10 (b): the factory options never disable encryption.
 */
class WebRtcPackagingTest {
    private fun libs(variant: String): Map<String, Set<String>> {
        val dir = File(System.getProperty("risime.nativeLibs.$variant") ?: error("risime.nativeLibs.$variant not set"))
        return dir.listFiles()?.filter { it.isDirectory }?.associate { abi -> abi.name to (abi.list()?.toSet() ?: emptySet()) } ?: emptyMap()
    }

    @Test fun oneWebRtcLibraryOnlyOn64BitAbis() {
        for (variant in listOf("debug", "release")) {
            val l = libs(variant)
            for ((abi, files) in l) {
                val jingle = files.filter { it.endsWith("jingle_peerconnection_so.so") }
                val expected = if (abi == "arm64-v8a" || abi == "x86_64") listOf("liblkjingle_peerconnection_so.so") else emptyList()
                assertEquals("$variant $abi", expected, jingle)
            }
        }
    }

    @Test fun encryptionCanNeverBeDisabled() {
        val o = WebRtcConfig.factoryOptions()
        assertFalse(o.disableEncryption)
    }
}
