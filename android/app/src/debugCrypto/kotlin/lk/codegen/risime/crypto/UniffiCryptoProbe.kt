package lk.codegen.risime.crypto

import lk.codegen.risime.CryptoProbe

/**
 * Debug-only bridge to the generated UniFFI bindings (compiled only when the Rust toolchain built
 * libuniffi_risime.so). Found by name from main, so release never references generated classes.
 */
class UniffiCryptoProbe : CryptoProbe {
    override fun info(): String = lk.codegen.risime.crypto.mlsInfo()

    override fun selfTest(): String = lk.codegen.risime.crypto.selfTest()
}
