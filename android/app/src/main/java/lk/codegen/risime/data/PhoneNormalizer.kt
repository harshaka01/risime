package lk.codegen.risime.data

import io.michaelrocks.libphonenumber.android.NumberParseException
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil

/** Normalises user input to E.164 (PROTOCOL.md §0). Local numbers are read as Sri Lankan by default. */
class PhoneNormalizer(private val util: PhoneNumberUtil, private val defaultRegion: String = "LK") {
    /** E.164 string, or null if the input isn't a valid number. */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val n = util.parse(trimmed, defaultRegion)
            if (util.isValidNumber(n)) util.format(n, PhoneNumberUtil.PhoneNumberFormat.E164) else null
        } catch (_: NumberParseException) {
            null
        }
    }
}
