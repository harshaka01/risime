package lk.codegen.risime.calls

/**
 * One renderer slot (the remote video or my preview). The call screen swaps renderers (a
 * SurfaceViewRenderer for a camera, a TextureViewRenderer for a shared screen): Compose creates the
 * new view (its factory attaches it) before the old view's `onDispose` runs, so a detach only
 * clears the slot when that view still owns it — otherwise the new renderer would go black.
 * [frames] counts the frames handed to the current owner (the debug stats and the device gate read it).
 */
class SinkSlot<T : Any> {
    @Volatile var current: T? = null
        private set

    @Volatile var frames: Long = 0
        private set

    @Synchronized
    fun attach(sink: T) {
        current = sink
        frames = 0
    }

    /** Clears the slot only if [sink] still owns it; true = it did. */
    @Synchronized
    fun detach(sink: T): Boolean {
        if (current !== sink) return false
        current = null
        return true
    }

    /** Hands a frame to the owner, if any. */
    fun deliver(block: (T) -> Unit) {
        val s = current ?: return
        frames++
        block(s)
    }
}

/**
 * §19.5 / §23.3 the camera capturer's on/off. Turning on needs a session that may carry video
 * ([set]'s `allowed`); turning off never does: every exit from video (a rollback that drops the
 * video transceiver, a decline, back to voice, the end) stops a running capturer (privacy).
 */
class CameraCapture(
    /** Creates the capturer once; false = no camera on this phone. */
    private val ensure: () -> Boolean,
    private val start: () -> Unit,
    private val stop: () -> Unit,
    private val log: (String) -> Unit = {},
) {
    @Volatile var capturing = false
        private set

    /** The camera is on (capturing for the sender). */
    @Volatile var on = false
        private set

    fun set(want: Boolean, allowed: Boolean) {
        if (want) {
            if (!allowed) return
            if (!ensure()) {
                log("no camera on this phone: camera stays off")
                return
            }
            if (!capturing) {
                start()
                capturing = true
            }
            on = true
        } else {
            if (capturing) {
                runCatching { stop() }.onFailure { log("stop capture: ${it.message}") }
                capturing = false
            }
            on = false
        }
    }
}
