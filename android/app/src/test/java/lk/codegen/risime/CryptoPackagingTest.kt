package lk.codegen.risime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.3 groundwork guard (decision 031): the native MLS core and JNA are in debug builds only.
 * Reads the merged native libs of both variants and the release BuildConfig (paths from Gradle).
 */
class CryptoPackagingTest {
    private fun libs(variant: String): Map<String, Set<String>> {
        val dir = File(System.getProperty("risime.nativeLibs.$variant") ?: error("risime.nativeLibs.$variant not set"))
        return dir.listFiles()?.filter { it.isDirectory }?.associate { abi -> abi.name to (abi.list()?.toSet() ?: emptySet()) } ?: emptyMap()
    }

    @Test fun releaseHasNoMlsCoreNoJnaAndTheFlagOff() {
        val release = libs("release")
        assertFalse("release packages libuniffi_risime.so: $release", release.values.any { "libuniffi_risime.so" in it })
        assertFalse("release packages JNA: $release", release.values.any { "libjnidispatch.so" in it })
        val cfg = File(System.getProperty("risime.buildConfig.release")).readText()
        assertTrue(cfg.contains("CRYPTO_AVAILABLE = false"))
    }

    @Test fun debugPackagesTheCoreForBothAbisWhenBuilt() {
        val debug = libs("debug")
        if (!BuildConfig.CRYPTO_AVAILABLE) {
            // No Rust/NDK on this machine: the app builds and runs without crypto.
            assertFalse(debug.values.any { "libuniffi_risime.so" in it })
            assertEquals(null, CryptoProbes.get())
            return
        }
        for (abi in listOf("arm64-v8a", "x86_64")) {
            assertTrue("debug $abi lacks libuniffi_risime.so: ${debug[abi]}", "libuniffi_risime.so" in debug[abi].orEmpty())
            assertTrue("debug $abi lacks JNA: ${debug[abi]}", "libjnidispatch.so" in debug[abi].orEmpty())
        }
        assertFalse("obsolete ABIs packaged: ${debug.keys}", debug.keys.any { it in setOf("armeabi", "mips", "mips64") })
    }

    @Test fun selfTestReportNeverThrows() {
        assertEquals("not available in this build", CryptoProbes.runSelfTest(null))
        val broken = object : CryptoProbe {
            override fun info() = "x"
            override fun selfTest(): String = throw UnsatisfiedLinkError("no libuniffi_risime.so")
        }
        assertEquals("failed: UnsatisfiedLinkError: no libuniffi_risime.so", CryptoProbes.runSelfTest(broken))
        val ok = object : CryptoProbe {
            override fun info() = "risime-mls 0.1.0"
            override fun selfTest() = "ok: epoch 3, risime-mls 0.1.0"
        }
        assertEquals("ok: epoch 3, risime-mls 0.1.0", CryptoProbes.runSelfTest(ok))
    }
}
