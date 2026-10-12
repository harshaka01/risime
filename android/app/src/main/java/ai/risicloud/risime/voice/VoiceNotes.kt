package ai.risicloud.risime.voice

import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.data.media.VoiceEnvelope
import ai.risicloud.risime.data.media.VoiceMeta
import ai.risicloud.risime.net.ApiClient
import ai.risicloud.risime.net.ApiResult
import ai.risicloud.risime.net.ReplyRef
import ai.risicloud.risime.net.RisiVoiceSettings
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Base64
import java.util.UUID

/** Where a voice note is being recorded: Private, Official or the user's Risi chat (§37.6–§37.8). */
enum class VoicePlace { PRIVATE, OFFICIAL, RISI_CHAT }

/**
 * §37.2 / §37.7 what goes on a new note besides the audio: `transcribe: false` only in Official while the sender's
 * setting is off (never in Private: there is nothing to opt out of); a fresh `request_id` only in the Risi chat.
 */
object VoiceStamp {
    fun meta(note: RecordedNote, place: VoicePlace, transcribeMine: Boolean, newRequestId: () -> String = { UUID.randomUUID().toString() }) = VoiceMeta(
        mime = VoiceEnvelope.MIME_OGG_OPUS,
        durationMs = note.durationMs,
        waveform = Base64.getEncoder().encodeToString(note.waveform),
        transcribe = if (place == VoicePlace.OFFICIAL && !transcribeMine) false else null,
        requestId = if (place == VoicePlace.RISI_CHAT) newRequestId() else null,
    )

    /** §37.3 the one-time Official note ("Risi transcribes…"), due before the first Official note while the switch is on. */
    fun officialNoteDue(place: VoicePlace, serverTranscribeOn: Boolean, shown: Boolean): Boolean =
        place == VoicePlace.OFFICIAL && serverTranscribeOn && !shown
}

/**
 * v1.41 §37 on this phone: the recorder, the sender's setting (`/me/risi/voice`, cached so a note is stamped even
 * offline), the `voice_transcribe` switch from `/auth/config` and sending a recorded note as an E2EE blob.
 */
class VoiceNotes(
    private val api: ApiClient,
    private val prefs: SharedPreferences?,
    private val commit: suspend (audio: ByteArray, conv: String, meta: VoiceMeta, replyTo: ReplyRef?) -> MessageEntity?,
    private val placeOf: (String) -> VoicePlace,
    private val mediaReady: () -> Boolean,
    val recorder: VoiceRecorder,
) {
    private val _serverTranscribeOn = MutableStateFlow(prefs?.getBoolean(KEY_SERVER, false) ?: false)

    /** `/auth/config` `voice_transcribe` (drives only the one-time Official note; notes work either way). */
    val serverTranscribeOn: StateFlow<Boolean> = _serverTranscribeOn

    private val _transcribeMine = MutableStateFlow(prefs?.getBoolean(KEY_MINE, true) ?: true)

    /** Settings → Risi → "Transcribe my voice notes in Official chats" (§37.7). */
    val transcribeMine: StateFlow<Boolean> = _transcribeMine

    private val _actionItems = MutableStateFlow(prefs?.getBoolean(KEY_ACTION_ITEMS, true) ?: true)
    val actionItems: StateFlow<Boolean> = _actionItems

    /** This phone can record voice notes (Opus encoder: Android 10+, and the media core for the E2EE blob). */
    fun canRecord(): Boolean = mediaReady() && StreamingOpusEncoder.available()

    fun setServerTranscribe(on: Boolean) {
        _serverTranscribeOn.value = on
        runCatching { prefs?.edit()?.putBoolean(KEY_SERVER, on)?.apply() }
    }

    fun place(conversationId: String): VoicePlace = placeOf(conversationId)

    fun officialNoteDue(conversationId: String): Boolean =
        VoiceStamp.officialNoteDue(place(conversationId), _serverTranscribeOn.value, prefs?.getBoolean(KEY_NOTE_SHOWN, false) ?: false)

    fun officialNoteShown() {
        runCatching { prefs?.edit()?.putBoolean(KEY_NOTE_SHOWN, true)?.apply() }
    }

    /** §37.7 GET; the cached values stay when offline. */
    suspend fun refreshSettings() {
        (runCatching { api.risiVoice() }.getOrNull() as? ApiResult.Ok)?.value?.let(::store)
    }

    /** §37.7 PUT; false when the server didn't take it (the switch goes back). */
    suspend fun setSettings(transcribe: Boolean = _transcribeMine.value, actionItems: Boolean = _actionItems.value): Boolean {
        val r = runCatching { api.putRisiVoice(RisiVoiceSettings(transcribe, actionItems)) }.getOrNull() as? ApiResult.Ok ?: return false
        store(r.value)
        return true
    }

    private fun store(s: RisiVoiceSettings) {
        _transcribeMine.value = s.transcribe
        _actionItems.value = s.actionItems
        runCatching { prefs?.edit()?.putBoolean(KEY_MINE, s.transcribe)?.putBoolean(KEY_ACTION_ITEMS, s.actionItems)?.apply() }
    }

    /** Encrypts [note] with a fresh key and queues it (upload, then the `voice` envelope). The audio is wiped either way. */
    suspend fun send(conversationId: String, note: RecordedNote, replyTo: ReplyRef?): MessageEntity? = try {
        commit(note.audio, conversationId, VoiceStamp.meta(note, place(conversationId), _transcribeMine.value), replyTo)
    } finally {
        note.wipe()
    }

    companion object {
        private const val KEY_SERVER = "voice_transcribe_on"
        private const val KEY_MINE = "voice_transcribe_mine"
        private const val KEY_ACTION_ITEMS = "voice_action_items"
        private const val KEY_NOTE_SHOWN = "voice_official_note_shown"
    }
}
