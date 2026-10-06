package lk.codegen.risime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Decision 037: with the Rust toolchain, debug **and** release package the MLS core for all four
 * ABIs (plus JNA), ahead of the E2EE rollout; without it (the laptop) neither does.
 */
class CryptoPackagingTest {
    private val abis = listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")

    private fun libs(variant: String): Map<String, Set<String>> {
        val dir = File(System.getProperty("risime.nativeLibs.$variant") ?: error("risime.nativeLibs.$variant not set"))
        return dir.listFiles()?.filter { it.isDirectory }?.associate { abi -> abi.name to (abi.list()?.toSet() ?: emptySet()) } ?: emptyMap()
    }

    @Test fun bothVariantsCarryTheCoreForAllAbisWhenBuilt() {
        val releaseCfg = File(System.getProperty("risime.buildConfig.release")).readText()
        assertTrue(releaseCfg.contains("CRYPTO_AVAILABLE = ${BuildConfig.CRYPTO_AVAILABLE}"))
        for (variant in listOf("debug", "release")) {
            val l = libs(variant)
            if (!BuildConfig.CRYPTO_AVAILABLE) {
                assertFalse("$variant has the core without a toolchain", l.values.any { "libuniffi_risime.so" in it })
                continue
            }
            for (abi in abis) {
                assertTrue("$variant $abi lacks libuniffi_risime.so: ${l[abi]}", "libuniffi_risime.so" in l[abi].orEmpty())
                assertTrue("$variant $abi lacks JNA: ${l[abi]}", "libjnidispatch.so" in l[abi].orEmpty())
            }
            assertFalse("$variant: obsolete ABIs packaged: ${l.keys}", l.keys.any { it in setOf("armeabi", "mips", "mips64") })
        }
        if (!BuildConfig.CRYPTO_AVAILABLE) assertEquals(null, CryptoProbes.get())
    }

    @Test fun selfTestReportNeverThrows() {
        assertEquals("not available in this build", CryptoProbes.runSelfTest(null))
        val broken = object : CryptoProbe {
            override fun info() = "x"
            override fun selfTest(): String = throw UnsatisfiedLinkError("no libuniffi_risime.so")
        }
        assertEquals("failed: UnsatisfiedLinkError: no libuniffi_risime.so", CryptoProbes.runSelfTest(broken))
    }
}
