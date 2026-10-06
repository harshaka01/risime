package lk.codegen.risime.data.media

/**
 * Byte-level image handling with no platform decoder (contract §14.7): format sniffing, header
 * dimensions, the EXIF orientation, metadata stripping and a metadata scan. Pure Kotlin, so the
 * strict receive checks run before any pixel is allocated and the tests run on the JVM.
 */
object ImageBytes {
    enum class Format(val mime: String?) { JPEG("image/jpeg"), PNG("image/png"), WEBP("image/webp"), HEIF(null), GIF(null), UNKNOWN(null) }

    fun sniff(b: ByteArray): Format = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> Format.JPEG
        b.size >= 8 && b.copyOfRange(0, 8).contentEquals(PNG_SIG) -> Format.PNG
        b.size >= 12 && ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WEBP" -> Format.WEBP
        b.size >= 6 && ascii(b, 0, 4) == "GIF8" -> Format.GIF
        b.size >= 12 && ascii(b, 4, 4) == "ftyp" -> Format.HEIF
        else -> Format.UNKNOWN
    }

    /** Width × height from the header (JPEG SOF, PNG IHDR, WebP VP8/VP8L/VP8X), or null. Never decodes pixels. */
    fun dimensions(b: ByteArray): Pair<Int, Int>? = runCatching {
        when (sniff(b)) {
            Format.JPEG -> jpegSegments(b).firstOrNull { it.marker in SOF_MARKERS }?.let { s ->
                val p = s.dataStart
                u16(b, p + 3) to u16(b, p + 1) // [precision][height][width]
            }
            Format.PNG -> if (ascii(b, 12, 4) == "IHDR") u32(b, 16).toInt() to u32(b, 20).toInt() else null
            Format.WEBP -> webpDimensions(b)
            else -> null
        }
    }.getOrNull()?.takeIf { (w, h) -> w > 0 && h > 0 }

    /** The EXIF orientation (1–8) of a JPEG, 1 when absent or unreadable. */
    fun exifOrientation(b: ByteArray): Int = runCatching {
        if (sniff(b) != Format.JPEG) return 1
        val app1 = jpegSegments(b).firstOrNull { it.marker == 0xE1 && ascii(b, it.dataStart, 6) == "Exif\u0000\u0000" } ?: return 1
        val t = app1.dataStart + 6
        val le = ascii(b, t, 2) == "II"
        fun r16(o: Int) = if (le) (b[t + o].toInt() and 0xFF) or ((b[t + o + 1].toInt() and 0xFF) shl 8) else u16(b, t + o)
        fun r32(o: Int) = if (le) r16(o).toLong() or (r16(o + 2).toLong() shl 16) else u32(b, t + o)
        val ifd = r32(4).toInt()
        val n = r16(ifd)
        for (i in 0 until n) {
            val e = ifd + 2 + i * 12
            if (r16(e) == 0x0112) return r16(e + 8).takeIf { it in 1..8 } ?: 1
        }
        1
    }.getOrDefault(1)

    /**
     * Removes every metadata container (§14.7 Sending 2): JPEG APP1–APP15 (EXIF, XMP, ICC, MPF,
     * IPTC …), COM, and an APP0 that isn't a plain thumbnail-free JFIF; PNG keeps only
     * IHDR/PLTE/tRNS/IDAT/IEND; WebP drops ICCP/EXIF/XMP and their VP8X flags. Unknown formats are
     * refused.
     */
    fun stripMetadata(b: ByteArray): ByteArray = when (sniff(b)) {
        Format.JPEG -> stripJpeg(b)
        Format.PNG -> stripPng(b)
        Format.WEBP -> stripWebp(b)
        else -> throw IllegalArgumentException("not JPEG, PNG or WebP")
    }

    /** Names of metadata found (empty = clean). Used by the tests and as a last check before anything leaves the app. */
    fun findMetadata(b: ByteArray): List<String> = when (sniff(b)) {
        Format.JPEG -> jpegSegments(b).mapNotNull { s ->
            when {
                s.marker == 0xE0 && !(ascii(b, s.dataStart, 5) == "JFIF\u0000" && s.length == 16) -> "APP0 (JFXX/thumbnail)"
                s.marker == 0xE1 && ascii(b, s.dataStart, 4) == "Exif" -> "APP1 EXIF"
                s.marker == 0xE1 -> "APP1 XMP"
                s.marker == 0xE2 && ascii(b, s.dataStart, 11) == "ICC_PROFILE" -> "APP2 ICC"
                s.marker == 0xE2 && ascii(b, s.dataStart, 3) == "MPF" -> "APP2 MPF"
                s.marker in 0xE2..0xEF -> "APP${s.marker - 0xE0}"
                s.marker == 0xFE -> "COM"
                else -> null
            }
        }
        Format.PNG -> pngChunks(b).map { it.first }.filter { it !in PNG_KEEP }
        Format.WEBP -> webpChunks(b).map { it.first }.filter { it in WEBP_META }
        else -> listOf("unknown format")
    }

    // ---- JPEG ----

    private class Segment(val marker: Int, val start: Int, val length: Int) {
        val dataStart get() = start + 4
        val end get() = start + 2 + length
    }

    private val SOF_MARKERS = (0xC0..0xCF).toSet() - setOf(0xC4, 0xC8, 0xCC)

    /** Segments before the scan (SOS and the entropy-coded data are not listed). */
    private fun jpegSegments(b: ByteArray): List<Segment> {
        val out = mutableListOf<Segment>()
        var p = 2
        while (p + 4 <= b.size) {
            if (b[p] != 0xFF.toByte()) throw IllegalArgumentException("bad JPEG marker at $p")
            val m = b[p + 1].toInt() and 0xFF
            if (m == 0xFF) { p++; continue } // fill byte
            if (m == 0xD9 || m == 0xDA) break
            if (m in 0xD0..0xD7 || m == 0x01) { p += 2; continue }
            val len = u16(b, p + 2)
            require(len >= 2 && p + 2 + len <= b.size) { "bad JPEG segment length" }
            out += Segment(m, p, len)
            p += 2 + len
        }
        return out
    }

    private fun stripJpeg(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        out.write(b, 0, 2)
        var p = 2
        while (p + 4 <= b.size) {
            require(b[p] == 0xFF.toByte()) { "bad JPEG marker at $p" }
            val m = b[p + 1].toInt() and 0xFF
            if (m == 0xFF) { p++; continue }
            if (m == 0xDA || m == 0xD9) break
            if (m in 0xD0..0xD7 || m == 0x01) { out.write(b, p, 2); p += 2; continue }
            val len = u16(b, p + 2)
            require(len >= 2 && p + 2 + len <= b.size) { "bad JPEG segment length" }
            val keep = when {
                m == 0xE0 -> ascii(b, p + 4, 5) == "JFIF\u0000" && len == 16 && b[p + 16] == 0.toByte() && b[p + 17] == 0.toByte()
                m in 0xE1..0xEF || m == 0xFE -> false
                else -> true
            }
            if (keep) out.write(b, p, 2 + len)
            p += 2 + len
        }
        out.write(b, p, b.size - p) // SOS, scan data, EOI
        return out.toByteArray()
    }

    // ---- PNG ----

    private val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val PNG_KEEP = setOf("IHDR", "PLTE", "tRNS", "IDAT", "IEND")

    /** (type, offset of the chunk, total bytes). */
    private fun pngChunks(b: ByteArray): List<Triple<String, Int, Int>> {
        val out = mutableListOf<Triple<String, Int, Int>>()
        var p = 8
        while (p + 12 <= b.size) {
            val len = u32(b, p)
            require(len <= b.size - p - 12L) { "bad PNG chunk length" }
            val type = ascii(b, p + 4, 4)
            out += Triple(type, p, 12 + len.toInt())
            p += 12 + len.toInt()
            if (type == "IEND") break
        }
        return out
    }

    private fun stripPng(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        out.write(PNG_SIG)
        for ((type, at, size) in pngChunks(b)) if (type in PNG_KEEP) out.write(b, at, size)
        return out.toByteArray()
    }

    // ---- WebP ----

    private val WEBP_META = setOf("ICCP", "EXIF", "XMP ")

    private fun webpChunks(b: ByteArray): List<Triple<String, Int, Int>> {
        val out = mutableListOf<Triple<String, Int, Int>>()
        var p = 12
        while (p + 8 <= b.size) {
            val len = u32le(b, p + 4)
            require(len <= b.size - p - 8L) { "bad WebP chunk length" }
            val size = 8 + len.toInt() + (len.toInt() and 1)
            out += Triple(ascii(b, p, 4), p, minOf(size, b.size - p))
            p += size
        }
        return out
    }

    private fun stripWebp(b: ByteArray): ByteArray {
        val body = java.io.ByteArrayOutputStream(b.size)
        for ((type, at, size) in webpChunks(b)) {
            if (type in WEBP_META) continue
            val chunk = b.copyOfRange(at, at + size)
            if (type == "VP8X" && chunk.size > 8) chunk[8] = (chunk[8].toInt() and (0x20 or 0x08 or 0x04).inv()).toByte()
            body.write(chunk)
        }
        val data = body.toByteArray()
        val out = java.io.ByteArrayOutputStream(data.size + 12)
        out.write("RIFF".toByteArray())
        val riff = data.size + 4
        out.write(byteArrayOf(riff.toByte(), (riff shr 8).toByte(), (riff shr 16).toByte(), (riff shr 24).toByte()))
        out.write("WEBP".toByteArray())
        out.write(data)
        return out.toByteArray()
    }

    private fun webpDimensions(b: ByteArray): Pair<Int, Int>? {
        for ((type, at, _) in webpChunks(b)) {
            val d = at + 8
            when (type) {
                "VP8X" -> return (u24le(b, d + 4) + 1) to (u24le(b, d + 7) + 1)
                "VP8 " -> {
                    // 3-byte frame tag, then the start code 9d 01 2a, then 14-bit width/height.
                    if ((b[d + 3].toInt() and 0xFF) != 0x9D || (b[d + 4].toInt() and 0xFF) != 0x01 || (b[d + 5].toInt() and 0xFF) != 0x2A) return null
                    return (u16le(b, d + 6) and 0x3FFF) to (u16le(b, d + 8) and 0x3FFF)
                }
                "VP8L" -> {
                    if ((b[d].toInt() and 0xFF) != 0x2F) return null
                    val bits = u32le(b, d + 1)
                    return ((bits and 0x3FFF) + 1).toInt() to (((bits shr 14) and 0x3FFF) + 1).toInt()
                }
            }
        }
        return null
    }

    // ---- helpers ----

    private fun ascii(b: ByteArray, at: Int, n: Int): String =
        if (at < 0 || at + n > b.size) "" else String(b, at, n, Charsets.ISO_8859_1)

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, at: Int): Long = (u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()

    private fun u16le(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u24le(b: ByteArray, at: Int) = u16le(b, at) or ((b[at + 2].toInt() and 0xFF) shl 16)

    private fun u32le(b: ByteArray, at: Int): Long = u16le(b, at).toLong() or (u16le(b, at + 2).toLong() shl 16)
}
