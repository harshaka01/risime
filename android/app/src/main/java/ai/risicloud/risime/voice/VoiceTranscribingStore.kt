package ai.risicloud.risime.voice

import ai.risicloud.risime.net.ApiClient
import ai.risicloud.risime.net.ApiResult
import ai.risicloud.risime.net.VoiceTranscribeTarget
import ai.risicloud.risime.net.VoiceTranscribing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Proposal 2026-10-14-transcribe-progress (v1.48 §37.11) strings. */
object TranscribingStrings {
    const val STOP = "Stop"
    const val TRY_AGAIN = "Try again"
    const val STOPPED = "Transcription stopped"
    const val COULDNT = "Couldn't transcribe"
    const val EXPIRED = "Can't try again. Send the note again."

    /** "Transcribing (Sinhala)…" / "Transcribing (Tamil)…" / "Transcribing…" (the language is a hint, never English). */
    fun working(language: String?): String = when (language) {
        "si" -> "Transcribing (Sinhala)…"
        "ta" -> "Transcribing (Tamil)…"
        else -> "Transcribing…"
    }
}

/**
 * The sender's own notes being transcribed by Risi (the `voice_transcribing` signal), keyed by the note's message id
 * (lowercase). **In memory only**: never persisted, never backed up. A signal with a `seq` not above the one applied is
 * dropped; an unknown `state` is ignored; `done` removes the line.
 */
class VoiceTranscribingStore(private val now: () -> Long = System::currentTimeMillis) {
    /** One line under a note. [expired] = Try again answered 410. */
    data class Line(
        val signal: VoiceTranscribing,
        val atMs: Long,
        val expired: Boolean = false,
        val busy: Boolean = false,
    ) {
        val state: String get() = signal.state
    }

    /** What the line shows: its text and its button (null = none). */
    data class View(val text: String, val button: String?)

    private val _lines = MutableStateFlow<Map<String, Line>>(emptyMap())
    val lines: StateFlow<Map<String, Line>> = _lines.asStateFlow()

    fun apply(s: VoiceTranscribing) {
        if (s.state !in VoiceTranscribing.STATES) return
        val key = s.messageId.lowercase()
        _lines.update { m ->
            val prev = m[key]
            when {
                prev != null && s.seq <= prev.signal.seq -> m
                s.state == VoiceTranscribing.DONE -> m - key
                else -> m + (key to Line(s, now()))
            }
        }
    }

    /** The line of [messageId] for a note of [durationMs], or null (none, or a `working` line that went quiet). */
    fun lineFor(messageId: String?, durationMs: Long?, nowMs: Long = now()): Line? {
        val l = _lines.value[messageId?.lowercase() ?: return null] ?: return null
        if (l.state == VoiceTranscribing.WORKING && nowMs - l.atMs >= quietMs(durationMs)) return null
        return l
    }

    /** Stop answered: the line becomes "stopped" locally (the server's `cancelled` signal may also arrive). */
    fun cancelled(messageId: String, canRetry: Boolean) = local(messageId) { l ->
        l.copy(signal = l.signal.copy(state = VoiceTranscribing.CANCELLED, canCancel = false, canRetry = canRetry), busy = false)
    }

    /** Try again answered 202: back to working (the timer restarts). */
    fun retrying(messageId: String) = local(messageId) { l ->
        l.copy(signal = l.signal.copy(state = VoiceTranscribing.WORKING, canCancel = true, canRetry = false), atMs = now(), busy = false)
    }

    /** Try again answered 410: the note is no longer held by the server. */
    fun expired(messageId: String) = local(messageId) { l -> l.copy(expired = true, busy = false, signal = l.signal.copy(canRetry = false)) }

    /** A request is in flight (the button is disabled), or it ended without a change. */
    fun busy(messageId: String, on: Boolean) = local(messageId) { it.copy(busy = on) }

    /** Nothing is running any more (Stop answered 404): the line goes. */
    fun gone(messageId: String) = _lines.update { it - messageId.lowercase() }

    fun clear() {
        _lines.value = emptyMap()
    }

    private fun local(messageId: String, f: (Line) -> Line) = _lines.update { m ->
        val key = messageId.lowercase()
        m[key]?.let { m + (key to f(it)) } ?: m
    }

    /** Stop (in `working`) and Try again (in `cancelled` / `failed`) through [api]. */
    suspend fun stop(api: ApiClient, conversationId: String, messageId: String) {
        busy(messageId, true)
        when (val r = api.cancelVoiceTranscribe(VoiceTranscribeTarget(conversationId, messageId))) {
            is ApiResult.Ok -> cancelled(messageId, r.value.canRetry)
            is ApiResult.Error -> if (r.httpStatus == 404) gone(messageId) else busy(messageId, false)
            is ApiResult.NetworkError -> busy(messageId, false)
        }
    }

    suspend fun retry(api: ApiClient, conversationId: String, messageId: String) {
        busy(messageId, true)
        when (val r = api.retryVoiceTranscribe(VoiceTranscribeTarget(conversationId, messageId))) {
            is ApiResult.Ok -> retrying(messageId)
            is ApiResult.Error -> when (r.httpStatus) {
                409 -> retrying(messageId)
                404, 410 -> expired(messageId)
                else -> busy(messageId, false)
            }
            is ApiResult.NetworkError -> busy(messageId, false)
        }
    }

    companion object {
        /** A `working` line without a signal for max(60 s, 4 × the note's duration) disappears. */
        fun quietMs(durationMs: Long?): Long = maxOf(60_000L, 4L * (durationMs ?: 0L))

        /** The line's text and button, or null when nothing is shown. */
        fun view(l: Line): View? = when {
            l.expired -> View(TranscribingStrings.EXPIRED, null)
            l.state == VoiceTranscribing.WORKING ->
                View(TranscribingStrings.working(l.signal.language), if (l.signal.canCancel) TranscribingStrings.STOP else null)
            l.state == VoiceTranscribing.CANCELLED ->
                View(TranscribingStrings.STOPPED, if (l.signal.canRetry) TranscribingStrings.TRY_AGAIN else null)
            l.state == VoiceTranscribing.FAILED ->
                View(TranscribingStrings.COULDNT, if (l.signal.canRetry) TranscribingStrings.TRY_AGAIN else null)
            else -> null
        }

        /** Only under the sender's own note, and never in Private. */
        fun shows(place: VoicePlace, outgoing: Boolean): Boolean = outgoing && place != VoicePlace.PRIVATE
    }
}
