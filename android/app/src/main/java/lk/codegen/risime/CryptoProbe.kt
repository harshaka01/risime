package lk.codegen.risime

/** The native MLS core, as far as the app needs it before 0.3 (debug builds only, decision 031). */
interface CryptoProbe {
    /** e.g. "risime-mls 0.1.0 (MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519)". */
    fun info(): String

    /** Runs the MLS lifecycle in-process; e.g. "ok: epoch 3, risime-mls 0.1.0 (…)". Throws on failure. */
    fun selfTest(): String
}

object CryptoProbes {
    private const val IMPL = "lk.codegen.risime.crypto.UniffiCryptoProbe"

    /** Null in release builds and in debug builds made without the Rust toolchain. */
    fun get(): CryptoProbe? {
        if (!BuildConfig.CRYPTO_AVAILABLE) return null
        return runCatching { Class.forName(IMPL).getDeclaredConstructor().newInstance() as CryptoProbe }.getOrNull()
    }

    /** What Settings → About shows. Never throws (a missing/broken .so is reported, not crashed on). */
    fun runSelfTest(probe: CryptoProbe?): String {
        if (probe == null) return "not available in this build"
        return try {
            probe.selfTest()
        } catch (t: Throwable) { // UnsatisfiedLinkError from JNA counts too
            "failed: ${t.javaClass.simpleName}: ${t.message}"
        }
    }
}
