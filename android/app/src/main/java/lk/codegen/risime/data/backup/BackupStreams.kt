package lk.codegen.risime.data.backup

import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * §22.2 `D`: the bundle compressed with raw DEFLATE (`Deflater(nowrap = true)`, decision 059), fed
 * to the core writer in pieces. Never a plaintext temp file, never the whole bundle in memory.
 */
class DeflatingSink(private val target: (ByteArray) -> Unit) {
    private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    private val buf = ByteArray(64 * 1024)
    var plainBytes = 0L
        private set

    fun write(bytes: ByteArray) {
        plainBytes += bytes.size
        deflater.setInput(bytes)
        while (!deflater.needsInput()) drain(Deflater.NO_FLUSH)
    }

    private fun drain(flush: Int) {
        val n = deflater.deflate(buf, 0, buf.size, flush)
        if (n > 0) target(buf.copyOf(n))
    }

    fun finish() {
        deflater.finish()
        while (!deflater.finished()) drain(Deflater.NO_FLUSH)
        deflater.end()
    }

    fun abort() = deflater.end()
}

/** The core reader's DEFLATE pieces (empty = the end) as a stream. */
class PiecesInputStream(private val next: () -> ByteArray) : InputStream() {
    private var cur = ByteArray(0)
    private var pos = 0
    private var done = false

    // Raw inflate may ask for one byte past the stream's end (java.util.zip.Inflater with nowrap).
    private var padded = false

    private fun fill(): Boolean {
        while (pos >= cur.size) {
            if (done) {
                if (padded) return false
                padded = true
                cur = byteArrayOf(0)
                pos = 0
                return true
            }
            val p = next()
            if (p.isEmpty()) {
                done = true
                continue
            }
            cur = p
            pos = 0
        }
        return true
    }

    override fun read(): Int = if (!fill()) -1 else cur[pos++].toInt() and 0xff

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, cur.size - pos)
        System.arraycopy(cur, pos, b, off, n)
        pos += n
        return n
    }
}

/** The bundle's lines from the inflated stream (each at most 1 MiB, §22.5). */
fun bundleLines(deflated: InputStream): Iterator<String> {
    val reader = InflaterInputStream(deflated, Inflater(true), 64 * 1024).bufferedReader(Charsets.UTF_8)
    return object : Iterator<String> {
        private var nextLine: String? = reader.readLine()

        override fun hasNext(): Boolean = nextLine != null

        override fun next(): String {
            val l = nextLine ?: throw NoSuchElementException()
            if (l.length > MAX_LINE) throw IllegalStateException("bundle line over 1 MiB")
            nextLine = reader.readLine()
            if (nextLine == null) reader.close()
            return l
        }
    }
}

const val MAX_LINE = 1 shl 20
