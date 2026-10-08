package lk.codegen.risime.calls

import android.util.Log

/** A started tone that can be stopped and released (ToneGenerator in production). */
interface RingTone {
    fun stop()
    fun release()
}

/**
 * Thread-safe owner of the ringback tone. The native ToneGenerator must be released exactly once:
 * callers may race (hang-up path vs. state collector), so the reference is taken-and-nulled under
 * the lock and stop/release happen inside it, never twice.
 */
class RingbackTone(private val create: () -> RingTone?) {
    private val lock = Any()
    private var tone: RingTone? = null

    fun update(want: Boolean) {
        synchronized(lock) {
            if (want) {
                if (tone == null) {
                    tone = runCatching { create() }.onFailure { Log.w("RisiMe", "ringback: ${it.message}") }.getOrNull()
                }
            } else {
                val t = tone
                tone = null
                if (t != null) {
                    runCatching { t.stop() }
                    runCatching { t.release() }
                }
            }
        }
    }
}
