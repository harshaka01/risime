package ai.risicloud.risime.voice

import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.data.media.VoiceEnvelope
import ai.risicloud.risime.data.media.PhoneTranscript
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
 * setting is off (never in Private: there is nothing to opt out of); a fresh `request_id` in the Risi chat, and in Official
 * when the @Risi chip is on ([toRisi], proposal 2026-10-14-spoken-risi §37.11: the note is a request to Risi).
 */
object VoiceStamp {
    fun meta(note: RecordedNote, place: VoicePlace, transcribeMine: Boolean, toRisi: Boolean = false, newRequestId: () -> String = { UUID.randomUUID().toString() }) = VoiceMeta(
        mime = VoiceEnvelope.MIME_OGG_OPUS,
        durationMs = note.durationMs,
        waveform = Base64.getEncoder().encodeToString(note.waveform),
        transcribe = transcribeStamp(place, transcribeMine),
        requestId = if (place == VoicePlace.RISI_CHAT || (place == VoicePlace.OFFICIAL && toRisi)) newRequestId() else null,
    )

    /** §37.2 `transcribe`: `false` only in Official while the sender's setting is off; otherwise absent. */
    fun transcribeStamp(place: VoicePlace, transcribeMine: Boolean): Boolean? = if (place == VoicePlace.OFFICIAL && !transcribeMine) false else null

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
    /** The developer `phone_stt` flag (null or off: the v1.41 path, unchanged). */
    val phoneStt: PhoneSttFlag? = null,
    private val phoneSessions: PhoneSttSessions? = null,
) {
    private val _serverTranscribeOn = MutableStateFlow(prefs?.getBoolean(KEY_SERVER, false) ?: false)

    /** `/auth/config` `voice_transcribe` (drives only the one-time Official note; notes work either way). */
    val serverTranscribeOn: StateFlow<Boolean> = _serverTranscribeOn

    private val _transcribeMine = MutableStateFlow(prefs?.getBoolean(KEY_MINE, true) ?: true)

    /** Settings → Risi → "Transcribe my voice notes in Official chats" (§37.7). */
    val transcribeMine: StateFlow<Boolean> = _transcribeMine

    private val _actionItems = MutableStateFlow(prefs?.getBoolean(KEY_ACTION_ITEMS, true) ?: true)
    val actionItems: StateFlow<Boolean> = _actionItems

    private val _translateAvailable = MutableStateFlow(prefs?.getBoolean(KEY_TRANSLATE, false) ?: false)

    /** Proposal 2026-10-14-voice-translate: "Wrong language?" and Translate are on for this user (server flag). */
    val translateAvailable: StateFlow<Boolean> = _translateAvailable

    private val _alwaysTranslate = MutableStateFlow(prefs?.getString(KEY_ALWAYS, null) ?: ai.risicloud.risime.net.VoiceTranslate150.OFF)

    /** Settings → Risi → "Always translate voice notes to": off | en | si | ta. */
    val alwaysTranslate: StateFlow<String> = _alwaysTranslate

    /** The per-viewer translations (memory only, never Room). */
    val translations = VoiceTranslations()

    /** Sets "Always translate voice notes to"; false when the server didn't take it. */
    suspend fun setAlwaysTranslate(target: String): Boolean {
        val r = runCatching {
            api.putRisiVoice(RisiVoiceSettings(_transcribeMine.value, _actionItems.value, alwaysTranslate = target))
        }.getOrNull() as? ApiResult.Ok ?: return false
        store(r.value)
        return true
    }

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
        _translateAvailable.value = s.translateAvailable
        val always = if (s.translateAvailable) s.alwaysTranslate ?: ai.risicloud.risime.net.VoiceTranslate150.OFF else ai.risicloud.risime.net.VoiceTranslate150.OFF
        _alwaysTranslate.value = always
        runCatching {
            prefs?.edit()?.putBoolean(KEY_MINE, s.transcribe)?.putBoolean(KEY_ACTION_ITEMS, s.actionItems)
                ?.putBoolean(KEY_TRANSLATE, s.translateAvailable)?.putString(KEY_ALWAYS, always)?.apply()
        }
    }

    /**
     * Proposal 2026-10-14: an on-device recognition session for a recording in [conversationId], started, or null when
     * the `phone_stt` flag is off, in the Risi chat or for a note that opts out of transcription. Null keeps the v1.41
     * path exactly. [notice] gets the honest reason when the phone can't (e.g. the language isn't on-device).
     */
    fun phoneSttSession(conversationId: String, notice: (String) -> Unit = {}): PhoneSttSessionHandle? {
        val flag = phoneStt ?: return null
        val place = place(conversationId)
        if (!PhoneSttPolicy.allowed(flag.on.value, place, VoiceStamp.transcribeStamp(place, _transcribeMine.value))) return null
        val s = phoneSessions?.open(flag.locale.value) ?: return null
        if (s.start()) return s
        s.cancel()
        notice(PhoneSttStrings.notAvailable(flag.locale.value))
        return null
    }

    /**
     * Encrypts [note] with a fresh key and queues it (upload, then the `voice` envelope). The audio is wiped either way.
     * [stt] (flag on only) waits briefly for the phone's final text and attaches it as `phone_transcript`.
     */
    suspend fun send(conversationId: String, note: RecordedNote, replyTo: ReplyRef?, stt: PhoneSttSessionHandle? = null, toRisi: Boolean = false, notice: (String) -> Unit = {}): MessageEntity? = try {
        val meta = VoiceStamp.meta(note, place(conversationId), _transcribeMine.value, toRisi = toRisi)
        val phone = stt?.let { phoneTranscriptOf(it.await(PhoneSttPolicy.waitMs(note.durationMs)), notice) }
        commit(note.audio, conversationId, if (phone == null) meta else meta.copy(phoneTranscript = phone), replyTo)
    } finally {
        stt?.cancel()
        note.wipe()
    }

    private fun phoneTranscriptOf(r: PhoneSttResult?, notice: (String) -> Unit): PhoneTranscript? = when (r) {
        is PhoneSttResult.Text -> PhoneTranscript.of(r.text, r.locale)
        is PhoneSttResult.NotAvailable -> { notice(PhoneSttStrings.notAvailable(phoneStt?.locale?.value ?: "")); null }
        else -> null
    }

    companion object {
        private const val KEY_SERVER = "voice_transcribe_on"
        private const val KEY_MINE = "voice_transcribe_mine"
        private const val KEY_ACTION_ITEMS = "voice_action_items"
        private const val KEY_TRANSLATE = "voice_translate_available"
        private const val KEY_ALWAYS = "voice_always_translate"
        private const val KEY_NOTE_SHOWN = "voice_official_note_shown"
    }
}
