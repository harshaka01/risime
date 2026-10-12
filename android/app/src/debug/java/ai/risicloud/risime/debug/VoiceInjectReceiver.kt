package ai.risicloud.risime.debug

import ai.risicloud.risime.BuildConfig
import ai.risicloud.risime.calls.transcribe.Pcm
import ai.risicloud.risime.voice.VoiceMicInject
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/*
 * v1.41 §37 the voice-note microphone seam for redroid (DEBUG source set only; release builds have neither the class
 * nor the action, and VoiceMicInject.arm ignores a clip unless BuildConfig.DEBUG). redroid has no microphone, so the
 * voice-note gate arms a 16-bit PCM WAV that replaces THIS phone's microphone for the next recordings (paced in real
 * time, then silence). The WAV is fetched from the gate host (adb reverse) into memory only. Protected by
 * android.permission.DUMP: only `adb shell am broadcast` can send it.
 *
 *   adb shell am broadcast -a ai.risicloud.risime.debug.VOICE_INJECT -p ai.risicloud.risime.debug --es url http://127.0.0.1:8767/en.wav
 *   adb shell am broadcast -a ai.risicloud.risime.debug.VOICE_INJECT -p ai.risicloud.risime.debug --ez stop true
 */
class VoiceInjectReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "ai.risicloud.risime.debug.VOICE_INJECT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != ACTION) return
        if (intent.getBooleanExtra("stop", false)) {
            VoiceMicInject.clear()
            Log.i("RisiMe", "RisiMe debug: voice inject cleared")
            return
        }
        val url = intent.getStringExtra("url") ?: return
        val pending = goAsync()
        Thread {
            var data: ByteArray? = null
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 5_000; c.readTimeout = 10_000
                data = c.inputStream.use { it.readBytes() }
                val w = MicInjectReceiver.parseWav(data) ?: run { Log.i("RisiMe", "RisiMe debug: voice inject: not a PCM16 WAV"); return@Thread }
                val (rate, ch, off, len) = w.toList()
                // To 48 kHz mono 16-bit (the recorder's format).
                val frames = len / (2 * ch)
                val mono = ShortArray(frames)
                for (f in 0 until frames) {
                    var sum = 0
                    for (k in 0 until ch) {
                        val i = off + (f * ch + k) * 2
                        sum += ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt()
                    }
                    mono[f] = (sum / ch).toShort()
                }
                VoiceMicInject.arm(Pcm.resample(mono, frames, rate, 48_000))
                Log.i("RisiMe", "RisiMe debug: voice inject armed ${frames / rate} s")
            } catch (e: Exception) {
                Log.i("RisiMe", "RisiMe debug: voice inject failed: ${e.javaClass.simpleName}")
            } finally {
                data?.fill(0)
                pending.finish()
            }
        }.start()
    }
}
