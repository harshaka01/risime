package lk.codegen.risime.crypto

import lk.codegen.risime.BuildConfig
import lk.codegen.risime.CryptoProbes
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The real MLS core on the JVM: scripts/build-rust-host's libuniffi_risime.so through the JNA jar
 * (decision 033). Skipped when the toolchain or the host build is absent. Phase B adds the real
 * pipeline tests on top of this path.
 */
class HostMlsSmokeTest {
    @Test fun selfTestRunsOnTheHostBuild() {
        val dir = System.getProperty("jna.library.path")
        assumeTrue("no host build of risime-mls-ffi (Rust toolchain absent or build failed)",
            BuildConfig.CRYPTO_AVAILABLE && dir != null && File(dir, "libuniffi_risime.so").isFile)
        val probe = CryptoProbes.get()
        assumeTrue("debug crypto bridge not compiled", probe != null)
        val r = CryptoProbes.runSelfTest(probe)
        println("HOST MLS: $r (${probe!!.info()})")
        assertTrue(r, r.startsWith("ok: epoch 3"))
    }
}
