package lk.codegen.risime.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * P0-1: the APK download. Bytes go to [part]; a later run continues it with `Range: bytes=N-`
 * ([rangeOutcome] decides what the answer means); when complete it is renamed to [apk]. The
 * coroutine can be cancelled at any byte: the HTTP call is cancelled at once and [part] is kept.
 * Nothing here is trusted: the sha256 and the signing certificate are checked afterwards.
 */
class ResumableDownload(private val http: OkHttpClient) {
    /** [onPercent]: 0..100 (null while the size is unknown), called when the value changes. */
    suspend fun fetch(url: String, part: File, apk: File, onPercent: (Int?) -> Unit) {
        if (apk.isFile) return // complete earlier; the hash check decides
        var wholeAgain = false
        while (true) {
            val from = if (part.isFile) part.length() else 0L
            val req = Request.Builder().url(url).apply { if (from > 0) header("Range", "bytes=$from-") }.build()
            val outcome = http.newCall(req).awaitCancellable { r ->
                val o = rangeOutcome(from, r.code, r.header("Content-Range"), r.body.contentLength())
                when (o) {
                    is RangeOutcome.Append -> write(r, part, append = true, done = from, total = o.total, onPercent)
                    is RangeOutcome.Restart -> write(r, part, append = false, done = 0, total = o.total, onPercent)
                    else -> Unit
                }
                o
            }
            when (outcome) {
                is RangeOutcome.Append, is RangeOutcome.Restart, RangeOutcome.Complete -> {
                    check(part.renameTo(apk)) { "couldn't store the download" }
                    return
                }
                RangeOutcome.Mismatch -> {
                    if (wholeAgain) throw UpdateHttpException(206)
                    part.delete()
                    wholeAgain = true
                }
                is RangeOutcome.Fail -> throw UpdateHttpException(outcome.code)
            }
        }
    }

    private fun write(r: Response, out: File, append: Boolean, done: Long, total: Long?, onPercent: (Int?) -> Unit) {
        var written = done
        var shown = -2
        r.body.byteStream().use { input ->
            FileOutputStream(out, append).use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val pct = downloadPercent(written, total) ?: -1
                    if (pct != shown) {
                        shown = pct
                        onPercent(pct.takeIf { it >= 0 })
                    }
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    written += n
                }
                output.fd.sync()
            }
        }
    }
}

fun sha256Hex(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Runs a blocking OkHttp call off the caller's thread and cancels the call itself the moment the
 * coroutine is cancelled (P0-1 A: a stopped update no longer hangs ~70 s in a socket read).
 */
internal suspend fun <T> Call.awaitCancellable(block: (Response) -> T): T = coroutineScope {
    // The child never fails: after a cancel its "Socket closed" must not replace the cancellation.
    val work = async(Dispatchers.IO) { runCatching { execute().use(block) } }
    val result = try {
        work.await()
    } catch (e: CancellationException) {
        cancel()
        throw e
    }
    result.getOrThrow()
}
