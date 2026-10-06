package lk.codegen.risime.data.media

/**
 * Blob format `A256GCM-S64K` sizes (contract §14.3), in pure Kotlin so the receive checks run
 * without the native core. The tests compare it with the core's `mediaCipherSize` and the vectors.
 */
object MediaFormat {
    const val ALG = "A256GCM-S64K"
    const val SEGMENT = 65_536L
    const val TAG = 16L

    /** §14.5 `media` cap on the ciphertext (16 MiB). */
    const val MAX_MEDIA_CIPHER = 16L * 1024 * 1024

    /** The largest plaintext under the media cap. */
    const val MAX_MEDIA_PLAIN = 16_515_072L

    /** Padmé(L) for L ≥ 1 (§14.3). */
    fun padme(l: Long): Long {
        require(l >= 1)
        if (l < 2) return 1
        val e = 63 - java.lang.Long.numberOfLeadingZeros(l) // ⌊log2 L⌋
        val s = (31 - Integer.numberOfLeadingZeros(e)) + 1 // ⌊log2 E⌋ + 1
        val mask = (1L shl (e - s)) - 1
        return (l + mask) and mask.inv()
    }

    /** `cipher_size` of an `L`-byte plaintext, or null for L < 1 or over the cap. */
    fun cipherSize(plainSize: Long): Long? {
        if (plainSize < 1 || plainSize > MAX_MEDIA_PLAIN) return null
        val p = padme(plainSize)
        val n = (p + SEGMENT - 1) / SEGMENT
        return p + TAG * n
    }
}
