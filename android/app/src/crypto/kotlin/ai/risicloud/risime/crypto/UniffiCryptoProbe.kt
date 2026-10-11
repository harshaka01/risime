package ai.risicloud.risime.crypto

import ai.risicloud.risime.CryptoProbe

/**
 * Bridge to the generated UniFFI bindings (compiled only when the Rust toolchain built
 * libuniffi_risime.so). Found by name from main, so release never references generated classes.
 */
class UniffiCryptoProbe : CryptoProbe {
    override fun info(): String = ai.risicloud.risime.crypto.mlsInfo()

    override fun selfTest(): String = ai.risicloud.risime.crypto.selfTest()
}
