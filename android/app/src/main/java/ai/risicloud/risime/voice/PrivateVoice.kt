package ai.risicloud.risime.voice

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/*
 * Decision 092 (proposal 2026-10-14-private-transcribe): transcription and translation in PRIVATE chats, behind the
 * server flag RISI_PRIVATE_TRANSCRIBE (sent as `/me` `private_transcribe`). Private keeps its promise: the server and
 * Risi never see Private content without an explicit per-action yes from the reader, and Risi is never a member.
 *
 *  1. English notes: the reader's phone, on-device only (createOnDeviceSpeechRecognizer); automatic, no prompt.
 *  2. Sinhala / Tamil (or no on-device English): the reader taps Transcribe, says yes to "sent to Google", and the
 *     reader's phone runs Android's online recogniser (never via our server). Blocked by the sender's opt-out.
 *  3. Translate: the reader taps, says yes to "sent to RisiMe's server, not stored", the phone sends only the text.
 *  4. The results stay on the reader's phone (memory only) and are never sent to the chat.
 *
 * Everything here is platform-free (unit-tested); [AndroidPhoneRecogniser] / [OnlineRecogniser] talk to the platform.
 */

/** Where a reader-side transcript or translation was made: always said in its label (decision 048). */
enum class PrivateEngine(val label: String) {
    ON_DEVICE("Transcript (on this phone)"),
    GOOGLE("Transcript (Google)"),
    SERVER("Translation (RisiMe server)"),
}

/** The two things the reader can say yes to; each has its own "Don't ask again". */
enum class ConsentKind(val key: String) {
    GOOGLE_TRANSCRIBE("private_consent_google"),
    SERVER_TRANSLATE("private_consent_translate"),
}

/** The dialog texts (proposal §2, §3), the settings texts and the honest notices. */
object PrivateVoiceStrings {
    const val TRANSCRIBE = "Transcribe"
    const val TRANSCRIBING_PHONE = "Transcribing on this phone…"
    const val TRANSCRIBING_GOOGLE = "Transcribing with Google…"
    const val TRANSLATING = "Translating on RisiMe's server…"
    const val TRANSLATE = "Translate"
    const val CHOOSE_LANGUAGE = "What language is this voice note in?"
    const val GOOGLE_TITLE = "Transcribe with Google?"
    const val GOOGLE_BODY = "Sinhala/Tamil voice notes are transcribed by Google. This note's audio will be sent to Google."
    const val GOOGLE_BODY_EN = "This phone can't transcribe English on the device. This note's audio will be sent to Google."
    const val TRANSLATE_TITLE = "Translate on RisiMe's server?"
    const val TRANSLATE_BODY = "The text will be sent to RisiMe's server to translate, and not stored."
    const val DONT_ASK = "Don't ask again"
    const val YES = "Yes"
    const val NO = "No"
    const val SENDER_OPTED_OUT = "The sender doesn't let others send their voice notes off the phone to transcribe."
    const val SENDER_OPTED_OUT_TRANSLATE = "The sender doesn't let others send text from their voice notes off the phone."
    const val COULDNT = "Couldn't transcribe"
    const val NO_SPEECH = "No speech heard"
    const val COULDNT_TRANSLATE = "Couldn't translate"
    const val NOT_AVAILABLE = "Transcription isn't available on this phone"
    fun tryOther(lang: String) = "This doesn't look like ${PrivateLangs.name(PrivateLangs.other(lang) ?: lang)}. Try ${PrivateLangs.name(PrivateLangs.other(lang) ?: lang)}?"
    fun mismatch(chosen: String, seen: String) = "This doesn't look like ${PrivateLangs.name(chosen)} (the text is in ${PrivateLangs.name(seen)} script)."
    fun tryLang(lang: String) = "Try ${PrivateLangs.name(lang)}"
    fun translateTo(lang: String) = "Translate to ${PrivateLangs.name(lang)}"

    const val SETTINGS_TITLE = "Private voice & translate"
    const val SETTING_LET_OTHERS = "Let others transcribe my voice notes (Sinhala/Tamil go through Google)"
    const val SETTING_LET_OTHERS_SUB = "When off, people you send Private voice notes to can't send them to Google. Their phone can still transcribe English on the device (nothing leaves it)."
    const val SENDER_NOTICE = "Others can transcribe your voice notes. Change in Settings"
    const val SENDER_NOTICE_SETTINGS = "Settings"
    const val SENDER_NOTICE_OK = "OK"
    const val SETTING_GOOGLE = "Sinhala/Tamil transcription by Google"
    const val SETTING_TRANSLATE = "Translation on RisiMe's server"
    const val SETTING_ASKS = "Asks each time"
    const val SETTING_NO_ASK = "Don't ask again: on"
    const val SETTING_RESET = "Ask again"
}

/**
 * The server's RISI_PRIVATE_TRANSCRIBE for this account, as `/me` `private_transcribe` (canary = RISI_DEV_USERS,
 * decided on the server, never in the app). Cached so it survives a restart; refreshed at most every 10 minutes.
 * Off (the default, and for every account the server doesn't name): nothing in Private changes.
 */
class PrivateTranscribeFlag(private val prefs: SharedPreferences?, private val now: () -> Long = System::currentTimeMillis) {
    private val _on = MutableStateFlow(runCatching { prefs?.getBoolean(KEY, false) ?: false }.getOrDefault(false))
    val on: StateFlow<Boolean> = _on

    @Volatile private var checkedAt: Long? = null

    fun set(on: Boolean) {
        checkedAt = now()
        _on.value = on
        runCatching { prefs?.edit()?.putBoolean(KEY, on)?.apply() }
    }

    /** Calls [fetch] (`GET /me` → `private_transcribe`, null when unreachable) unless it ran in the last 10 minutes. */
    suspend fun refreshIfStale(fetch: suspend () -> Boolean?) {
        checkedAt?.let { if (now() - it < 600_000) return }
        checkedAt = now()
        fetch()?.let(::set)
    }

    companion object {
        private const val KEY = "private_transcribe_server"
    }
}

/** si / ta / en, the recogniser locale for each, and their names. */
object PrivateLangs {
    val ALL = listOf("en", "si", "ta")
    fun name(lang: String) = when (lang) { "si" -> "Sinhala"; "ta" -> "Tamil"; "en" -> "English"; else -> lang }

    /** The locale tag the recogniser is asked for. */
    fun tag(lang: String) = when (lang) { "si" -> "si-LK"; "ta" -> "ta-LK"; else -> "en-US" }

    /** "si-LK" → "si"; null for anything that isn't si / ta / en. */
    fun primary(tag: String?): String? = tag?.replace('_', '-')?.substringBefore('-')?.lowercase()?.takeIf { it in ALL }

    /** The language si / ta are confused with (the Tamil fix: offer the other one). */
    fun other(lang: String): String? = when (lang) { "si" -> "ta"; "ta" -> "si"; else -> null }

    /** On-device English: never the network. Everything else needs Google (no si / ta on-device packs today). */
    fun onDeviceOnly(lang: String) = lang == "en"
}

/**
 * The on-device script check (the server's §36.11 Tamil fix, on the phone): count Sinhala (U+0D80–U+0DFF), Tamil
 * (U+0B80–U+0BFF) and Latin letters. A Tamil transcript in Sinhala script is never shown as Tamil (and vice versa):
 * the reader is offered the other language instead.
 */
object ScriptCheck {
    data class Counts(val sinhala: Int, val tamil: Int, val latin: Int)

    fun counts(text: String): Counts {
        var si = 0
        var ta = 0
        var la = 0
        for (ch in text) when (ch.code) {
            in 0x0D80..0x0DFF -> si++
            in 0x0B80..0x0BFF -> ta++
            in 'a'.code..'z'.code, in 'A'.code..'Z'.code -> la++
        }
        return Counts(si, ta, la)
    }

    /** The script the text is mostly in among si / ta (null: neither dominates, e.g. Latin or mixed). */
    fun dominant(text: String): String? {
        val c = counts(text)
        return when {
            c.sinhala > 0 && c.sinhala >= 2 * c.tamil -> "si"
            c.tamil > 0 && c.tamil >= 2 * c.sinhala -> "ta"
            else -> null
        }
    }

    /**
     * Null = the text fits [lang]; otherwise the language whose script it is in. Only si / ta are checked (an English
     * or Singlish transcript in Latin letters is fine; English with Sinhala or Tamil script is offered that language).
     */
    fun mismatch(lang: String, text: String): String? {
        val d = dominant(text) ?: return null
        return if (d == lang) null else d
    }
}

/**
 * The reader's "Don't ask again" choices and the sender's own setting, on this phone only (resettable in Settings →
 * Private voice & translate). Absent = ask each time / let others transcribe.
 */
class PrivateConsent(private val prefs: SharedPreferences?) {
    private val _remembered = MutableStateFlow(ConsentKind.entries.filter { read(it) }.toSet())
    val remembered: StateFlow<Set<ConsentKind>> = _remembered

    private val _letOthers = MutableStateFlow(prefs?.getBoolean(KEY_LET_OTHERS, true) ?: true)

    /** Settings → "Let others transcribe my voice notes" (default on). */
    val letOthersTranscribe: StateFlow<Boolean> = _letOthers

    private fun read(k: ConsentKind) = runCatching { prefs?.getBoolean(k.key, false) ?: false }.getOrDefault(false)

    fun isRemembered(k: ConsentKind) = k in _remembered.value

    fun remember(k: ConsentKind) {
        runCatching { prefs?.edit()?.putBoolean(k.key, true)?.apply() }
        _remembered.update { it + k }
    }

    fun reset(k: ConsentKind) {
        runCatching { prefs?.edit()?.remove(k.key)?.apply() }
        _remembered.update { it - k }
    }

    /**
     * Harsha 10:31: the sender's one-time notice ("Others can transcribe your voice notes. Change in Settings"), due
     * the first time this user records a Private voice note while the feature applies; never again once shown.
     */
    fun senderNoticeDue(flagOn: Boolean, place: VoicePlace): Boolean =
        flagOn && place == VoicePlace.PRIVATE && !(runCatching { prefs?.getBoolean(KEY_NOTICE, false) ?: false }.getOrDefault(false) || noticeShown)

    @Volatile private var noticeShown = false

    fun senderNoticeShown() {
        noticeShown = true
        runCatching { prefs?.edit()?.putBoolean(KEY_NOTICE, true)?.apply() }
    }

    fun setLetOthers(on: Boolean) {
        runCatching { prefs?.edit()?.putBoolean(KEY_LET_OTHERS, on)?.apply() }
        _letOthers.value = on
    }

    companion object {
        private const val KEY_LET_OTHERS = "private_let_others_transcribe"
        private const val KEY_NOTICE = "private_sender_notice_shown"
    }
}

/**
 * The yes event. The only ways to get one are the reader's Yes in the dialog ([PrivateVoiceController.answer] /
 * [PrivateVoiceController.answerTranslate]) or a "Don't ask again" the reader set earlier. Nothing that sends Private
 * audio or text off the phone runs without one ([OnlineSttGuard], [PrivateVoiceController]).
 */
class ConsentYes private constructor(val kind: ConsentKind, val key: String, val remembered: Boolean) {
    companion object {
        internal fun fromDialog(kind: ConsentKind, key: String) = ConsentYes(kind, key, remembered = false)

        internal fun fromRemembered(consent: PrivateConsent, kind: ConsentKind, key: String): ConsentYes? =
            if (consent.isRemembered(kind)) ConsentYes(kind, key, remembered = true) else null
    }
}

/**
 * What asks to build the online recogniser. **The only path** to `SpeechRecognizer`'s online constructor goes
 * through [OnlineSttGuard.allows] in [OnlineRecogniser]. Today's only kind is a Private reader's note; the Official
 * "Use Google for Sinhala and Tamil voice" opt-in (own notes) adds its own kind here.
 */
sealed interface OnlineSttRequest {
    /** A reader transcribing a received Private note: the feature flag, the yes event and the sender's allow flag. */
    data class PrivateNote(val flagOn: Boolean, val yes: ConsentYes?, val noteKey: String, val senderAllows: Boolean) : OnlineSttRequest
}

/** The guard of the one online construction site. */
object OnlineSttGuard {
    fun allows(r: OnlineSttRequest?): Boolean = when (r) {
        null -> false
        is OnlineSttRequest.PrivateNote ->
            r.flagOn && r.senderAllows && r.yes != null && r.yes.kind == ConsentKind.GOOGLE_TRANSCRIBE && r.yes.key == r.noteKey
    }
}

/** A received Private note as the reader's controller sees it. */
data class PrivateNoteRef(
    val key: String,
    val senderId: String,
    val durationMs: Long,
    /** The sender phone's own transcript language (§37.10), if any: the language hint. */
    val phoneLang: String?,
    /** The sender's `allow_transcribe` (decision 092): false = nothing derived from it leaves this phone. */
    val senderAllows: Boolean,
    val outgoing: Boolean = false,
)

/** The reader-side state of one note's transcript. */
sealed interface ReaderTranscript {
    data class Running(val engine: PrivateEngine) : ReaderTranscript
    data class Done(val text: String, val lang: String, val engine: PrivateEngine) : ReaderTranscript

    /** The chosen language doesn't match the script heard ([seen]): never shown as [chosen]; offer [seen]. */
    data class Mismatch(val chosen: String, val seen: String, val engine: PrivateEngine) : ReaderTranscript
    data class Failed(val reason: String) : ReaderTranscript
    data class Blocked(val reason: String) : ReaderTranscript
}

/** One translation, shown only to the reader who tapped. */
sealed interface ReaderTranslation {
    data object Running : ReaderTranslation
    data class Done(val text: String, val target: String) : ReaderTranslation
    data class Failed(val reason: String) : ReaderTranslation
}

/** What a tap leads to. */
sealed interface PrivateStep {
    /** Work started (on-device, or a remembered yes). */
    data object Started : PrivateStep

    /** The UI must show the yes/no dialog of [kind]; nothing has been sent. */
    data class NeedsConsent(val kind: ConsentKind, val englishOnline: Boolean = false) : PrivateStep
    data class Blocked(val reason: String) : PrivateStep

    /** Nothing to do (the flag is off, or a result already exists). */
    data object Nothing : PrivateStep
}

/** The platform side the controller drives (fakes in the tests). */
interface ReaderStt {
    /** The installed on-device tag for [lang], or null (nothing on-device: then only Google could). */
    suspend fun onDeviceTag(lang: String): String?

    /** The on-device recogniser over [pcm] (48 kHz mono). Never the network. */
    suspend fun onDevice(tag: String, pcm: ShortArray, durationMs: Long): PhoneSttResult

    /** The online recogniser, built only if [OnlineSttGuard] allows [request]; null when refused. */
    suspend fun online(tag: String, pcm: ShortArray, durationMs: Long, request: OnlineSttRequest): PhoneSttResult?
}

/** The translate request (`POST /api/v1/translate`): only the text and the languages. */
interface PrivateTranslator {
    suspend fun translate(text: String, target: String, source: String?): Result<String>
}

/**
 * The reader's Private voice & translate logic (decision 092). Results live in memory on this phone only.
 * [decode] decrypts and decodes a note in memory (48 kHz mono PCM16; null = not on the phone).
 */
class PrivateVoiceController(
    private val flagOn: () -> Boolean,
    val consent: PrivateConsent,
    private val stt: ReaderStt,
    private val translator: PrivateTranslator,
    private val decode: suspend (String) -> ShortArray?,
    private val prefs: SharedPreferences? = null,
) {
    private val _transcripts = MutableStateFlow<Map<String, ReaderTranscript>>(emptyMap())
    val transcripts: StateFlow<Map<String, ReaderTranscript>> = _transcripts

    private val _translations = MutableStateFlow<Map<String, ReaderTranslation>>(emptyMap())
    val translations: StateFlow<Map<String, ReaderTranslation>> = _translations

    fun enabled(): Boolean = runCatching(flagOn).getOrDefault(false)

    /** The language hint: the sender phone's transcript language, else this reader's last choice for that sender. */
    fun hint(note: PrivateNoteRef): String? = PrivateLangs.primary(note.phoneLang)
        ?: runCatching { prefs?.getString(KEY_SENDER_LANG + note.senderId.lowercase(), null) }.getOrNull()?.takeIf { it in PrivateLangs.ALL }

    private fun rememberLang(senderId: String, lang: String) {
        runCatching { prefs?.edit()?.putString(KEY_SENDER_LANG + senderId.lowercase(), lang)?.apply() }
    }

    /**
     * Item 1: an English note is transcribed on this phone automatically (no prompt, nothing leaves the phone), when
     * the hint says English and an on-device English pack is installed. Otherwise nothing happens until a tap.
     */
    suspend fun auto(note: PrivateNoteRef): PrivateStep {
        if (!enabled() || note.key in _transcripts.value || note.phoneLang != null) return PrivateStep.Nothing
        if (hint(note) != "en") return PrivateStep.Nothing
        val tag = stt.onDeviceTag("en") ?: return PrivateStep.Nothing
        runOnDevice(note, "en", tag)
        return PrivateStep.Started
    }

    /**
     * A tap on Transcribe with the chosen [lang]. English with an on-device pack runs at once on the phone. Anything
     * that needs Google: blocked by the sender's opt-out; else a remembered yes runs it; else the dialog is asked for.
     */
    suspend fun request(note: PrivateNoteRef, lang: String): PrivateStep {
        if (!enabled() || lang !in PrivateLangs.ALL) return PrivateStep.Nothing
        if (PrivateLangs.onDeviceOnly(lang)) {
            stt.onDeviceTag(lang)?.let { tag -> runOnDevice(note, lang, tag); return PrivateStep.Started }
        }
        if (!note.senderAllows) {
            _transcripts.update { it + (note.key to ReaderTranscript.Blocked(PrivateVoiceStrings.SENDER_OPTED_OUT)) }
            return PrivateStep.Blocked(PrivateVoiceStrings.SENDER_OPTED_OUT)
        }
        val yes = ConsentYes.fromRemembered(consent, ConsentKind.GOOGLE_TRANSCRIBE, note.key)
            ?: return PrivateStep.NeedsConsent(ConsentKind.GOOGLE_TRANSCRIBE, englishOnline = lang == "en")
        runOnline(note, lang, yes)
        return PrivateStep.Started
    }

    /** The reader's answer to the Google dialog. No sends nothing; Yes (with "Don't ask again") runs it. */
    suspend fun answer(note: PrivateNoteRef, lang: String, yes: Boolean, dontAskAgain: Boolean): PrivateStep {
        if (!yes || !enabled()) return PrivateStep.Nothing
        if (!note.senderAllows) return PrivateStep.Blocked(PrivateVoiceStrings.SENDER_OPTED_OUT)
        if (dontAskAgain) consent.remember(ConsentKind.GOOGLE_TRANSCRIBE)
        runOnline(note, lang, ConsentYes.fromDialog(ConsentKind.GOOGLE_TRANSCRIBE, note.key))
        return PrivateStep.Started
    }

    private suspend fun runOnDevice(note: PrivateNoteRef, lang: String, tag: String) {
        _transcripts.update { it + (note.key to ReaderTranscript.Running(PrivateEngine.ON_DEVICE)) }
        val pcm = decode(note.key)
        if (pcm == null) { set(note.key, ReaderTranscript.Failed(PrivateVoiceStrings.COULDNT)); return }
        val r = try { stt.onDevice(tag, pcm, note.durationMs) } finally { pcm.fill(0) }
        finish(note, lang, r, PrivateEngine.ON_DEVICE)
    }

    private suspend fun runOnline(note: PrivateNoteRef, lang: String, yes: ConsentYes) {
        val request = OnlineSttRequest.PrivateNote(enabled(), yes, note.key, note.senderAllows)
        if (!OnlineSttGuard.allows(request)) return
        _transcripts.update { it + (note.key to ReaderTranscript.Running(PrivateEngine.GOOGLE)) }
        val pcm = decode(note.key)
        if (pcm == null) { set(note.key, ReaderTranscript.Failed(PrivateVoiceStrings.COULDNT)); return }
        val r = try { stt.online(PrivateLangs.tag(lang), pcm, note.durationMs, request) } finally { pcm.fill(0) }
        finish(note, lang, r ?: PhoneSttResult.NotAvailable(PrivateVoiceStrings.NOT_AVAILABLE), PrivateEngine.GOOGLE)
    }

    private fun finish(note: PrivateNoteRef, lang: String, r: PhoneSttResult, engine: PrivateEngine) {
        val state = when (r) {
            is PhoneSttResult.Text -> {
                val seen = ScriptCheck.mismatch(lang, r.text)
                if (seen != null && seen != lang) {
                    ReaderTranscript.Mismatch(lang, seen, engine)
                } else {
                    rememberLang(note.senderId, lang)
                    ReaderTranscript.Done(r.text, lang, engine)
                }
            }
            is PhoneSttResult.NoSpeech -> ReaderTranscript.Failed(PrivateVoiceStrings.NO_SPEECH)
            is PhoneSttResult.NotAvailable -> ReaderTranscript.Failed(PrivateVoiceStrings.NOT_AVAILABLE)
            is PhoneSttResult.Failed -> ReaderTranscript.Failed(PrivateVoiceStrings.COULDNT)
        }
        set(note.key, state)
    }

    /** Clears a note's result so the reader can try again (another language). */
    fun clear(key: String) = _transcripts.update { it - key }

    private fun set(key: String, s: ReaderTranscript) = _transcripts.update { it + (key to s) }

    // ---- translate (item 3) ----

    fun translationKey(key: String, target: String) = "$key>$target"

    /**
     * A tap on "Translate to [target]" for [text] (a typed message or a transcript). [allowed] is false for text
     * derived from a note whose sender opted out. A remembered yes sends it; otherwise the dialog is asked for.
     */
    suspend fun translate(key: String, text: String, target: String, source: String?, allowed: Boolean = true): PrivateStep {
        if (!enabled() || target !in PrivateLangs.ALL || text.isBlank()) return PrivateStep.Nothing
        if (!allowed) return PrivateStep.Blocked(PrivateVoiceStrings.SENDER_OPTED_OUT_TRANSLATE)
        val yes = ConsentYes.fromRemembered(consent, ConsentKind.SERVER_TRANSLATE, translationKey(key, target))
            ?: return PrivateStep.NeedsConsent(ConsentKind.SERVER_TRANSLATE)
        send(key, text, target, source, yes)
        return PrivateStep.Started
    }

    /** The reader's answer to the translate dialog. No sends nothing. */
    suspend fun answerTranslate(key: String, text: String, target: String, source: String?, yes: Boolean, dontAskAgain: Boolean, allowed: Boolean = true): PrivateStep {
        if (!yes || !enabled() || !allowed) return PrivateStep.Nothing
        if (dontAskAgain) consent.remember(ConsentKind.SERVER_TRANSLATE)
        send(key, text, target, source, ConsentYes.fromDialog(ConsentKind.SERVER_TRANSLATE, translationKey(key, target)))
        return PrivateStep.Started
    }

    private suspend fun send(key: String, text: String, target: String, source: String?, yes: ConsentYes) {
        val k = translationKey(key, target)
        if (yes.kind != ConsentKind.SERVER_TRANSLATE || yes.key != k) return
        _translations.update { it + (k to ReaderTranslation.Running) }
        val r = translator.translate(text.trim(), target, source?.takeIf { it in PrivateLangs.ALL && it != target })
        _translations.update { m ->
            m + (k to r.fold({ ReaderTranslation.Done(it, target) }, { ReaderTranslation.Failed(PrivateVoiceStrings.COULDNT_TRANSLATE) }))
        }
    }

    companion object {
        private const val KEY_SENDER_LANG = "private_voice_lang_"
    }
}
