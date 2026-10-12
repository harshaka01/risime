package ai.risicloud.risime.debug

import ai.risicloud.risime.BuildConfig
import ai.risicloud.risime.calls.transcribe.CallMicTap
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/*
 * v1.39 §36 the microphone seam for redroid (DEBUG source set only; release builds have neither the class nor the
 * action, and CallMicTap ignores injection unless BuildConfig.DEBUG). redroid has no microphone, so the
 * call-transcribe gate feeds a 16-bit PCM WAV in place of THIS phone's microphone for the transcription tap only
 * (the call's own audio is untouched). The WAV is fetched from the gate host (adb reverse) into memory, paced in
 * real time in 20 ms frames, and zeroed when done: nothing is written to disk. Protected by android.permission.DUMP:
 * only `adb shell am broadcast` can send it.
 *
 *   adb shell am broadcast -a ai.risicloud.risime.debug.MIC_INJECT -p ai.risicloud.risime.debug \
 *       --es url http://127.0.0.1:8765/ta.wav [--ei repeat 1] [--ei gap_ms 1000]
 *   adb shell am broadcast -a ai.risicloud.risime.debug.MIC_INJECT -p ai.risicloud.risime.debug --ez stop true
 */
class MicInjectReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "ai.risicloud.risime.debug.MIC_INJECT"

        @Volatile private var worker: Thread? = null

        /** RIFF/WAVE PCM16 → (rate, channels, data offset, data length); null if not PCM16. */
        fun parseWav(b: ByteArray): IntArray? {
            fun le32(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
            fun le16(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
            if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            var at = 12
            var rate = 0
            var ch = 0
            var bits = 0
            while (at + 8 <= b.size) {
                val id = String(b, at, 4, Charsets.US_ASCII)
                val len = le32(at + 4)
                if (id == "fmt ") {
                    if (le16(at + 8) != 1) return null
                    ch = le16(at + 10); rate = le32(at + 12); bits = le16(at + 22)
                } else if (id == "data") {
                    if (bits != 16 || rate == 0) return null
                    return intArrayOf(rate, ch, at + 8, minOf(len, b.size - at - 8))
                }
                at += 8 + len + (len and 1)
            }
            return null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != ACTION) return
        worker?.interrupt()
        worker = null
        if (intent.getBooleanExtra("stop", false)) {
            CallMicTap.setInjecting(false)
            Log.i("RisiMe", "RisiMe debug: mic inject stopped")
            return
        }
        val url = intent.getStringExtra("url") ?: return
        val repeat = intent.getIntExtra("repeat", 1).coerceIn(1, 20)
        val gapMs = intent.getIntExtra("gap_ms", 1_000).coerceIn(0, 60_000)
        val t = Thread {
            var data: ByteArray? = null
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 5_000; c.readTimeout = 10_000
                data = c.inputStream.use { it.readBytes() }
                val w = parseWav(data) ?: run { Log.i("RisiMe", "RisiMe debug: mic inject: not a PCM16 WAV"); return@Thread }
                val (rate, ch, off, len) = w.toList()
                val frame = rate * ch * 2 / 50 // 20 ms
                CallMicTap.setInjecting(true)
                Log.i("RisiMe", "RisiMe debug: mic inject start ${len / (rate * ch * 2)} s x$repeat")
                val buf = ByteArray(frame)
                val silence = ByteArray(frame)
                val start = System.nanoTime()
                var sent = 0L
                fun pace() {
                    sent++
                    val due = start + sent * 20_000_000L
                    val wait = (due - System.nanoTime()) / 1_000_000
                    if (wait > 0) Thread.sleep(wait)
                }
                repeat(repeat) {
                    var p = 0
                    while (p < len) {
                        val n = minOf(frame, len - p)
                        System.arraycopy(data, off + p, buf, 0, n)
                        CallMicTap.onInjected(buf, n, rate, ch)
                        p += n
                        pace()
                    }
                    repeat(gapMs / 20) { CallMicTap.onInjected(silence, frame, rate, ch); pace() }
                }
                buf.fill(0)
                Log.i("RisiMe", "RisiMe debug: mic inject done")
            } catch (e: InterruptedException) {
                Log.i("RisiMe", "RisiMe debug: mic inject interrupted")
            } catch (e: Exception) {
                Log.i("RisiMe", "RisiMe debug: mic inject failed: ${e.javaClass.simpleName}")
            } finally {
                data?.fill(0)
                CallMicTap.setInjecting(false)
            }
        }
        worker = t
        t.start()
    }
}
