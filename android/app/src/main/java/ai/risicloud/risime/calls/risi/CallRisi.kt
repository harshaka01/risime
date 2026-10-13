package ai.risicloud.risime.calls.risi

import android.content.SharedPreferences
import ai.risicloud.risime.calls.CallLines
import ai.risicloud.risime.calls.GroupCallEnvelope
import ai.risicloud.risime.net.CallErrors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * "Call Risi" (decision 090, proposal 2026-10-14-call-risi): a voice call in this user's Risi chat that
 * Risi answers. v1 is behind the developer flag `call_risi` (Settings → About → seven taps → Developer;
 * only for `risi_dev` accounts, i.e. the server's RISI_DEV_USERS: Harsha first). Off by default and for
 * everyone else; while it is off this phone never advertises `call_risi`, never shows the button and
 * every call path is the v1.47 one.
 */
class CallRisiFlag(private val prefs: SharedPreferences?) {
    private val _on = MutableStateFlow(read())

    /** The effective flag: the developer switch on **and** the account was `risi_dev` when it was turned on. */
    val on: StateFlow<Boolean> = _on

    private fun read(): Boolean = (prefs?.getBoolean(KEY_ON, false) ?: false) && (prefs?.getBoolean(KEY_DEV, false) ?: false)

    /** From a fresh `GET /me` in the Developer section; a non-developer account turns the flag off. */
    fun setDeveloper(dev: Boolean) {
        runCatching {
            val e = prefs?.edit() ?: return@runCatching
            e.putBoolean(KEY_DEV, dev)
            if (!dev) e.putBoolean(KEY_ON, false)
            e.apply()
        }
        _on.value = dev && (prefs?.getBoolean(KEY_ON, false) ?: false)
    }

    fun set(on: Boolean) {
        val dev = prefs?.getBoolean(KEY_DEV, false) ?: false
        runCatching { prefs?.edit()?.putBoolean(KEY_ON, on && dev)?.apply() }
        _on.value = on && dev
    }

    /** The one-time explainer sheet (§41.4) was shown on this phone. */
    fun explained(): Boolean = prefs?.getBoolean(KEY_EXPLAINED, false) ?: false

    fun setExplained() {
        runCatching { prefs?.edit()?.putBoolean(KEY_EXPLAINED, true)?.apply() }
    }

    companion object {
        const val NAME = "call_risi"
        private const val KEY_ON = "dev_call_risi"
        private const val KEY_DEV = "dev_call_risi_risi_dev"
        private const val KEY_EXPLAINED = "call_risi_explained"
    }
}

/** The pure rules of Call Risi (unit-tested). */
object CallRisiRules {
    /** §41.1: the button shows only with `User.call_risi`, the developer flag, and a `group_calls` device. */
    fun buttonVisible(userCallRisi: Boolean, flagOn: Boolean, groupCalls: Boolean): Boolean = userCallRisi && flagOn && groupCalls

    /** §41.1: `call_risi` is advertised only with the flag on and both `group_calls` and `risi_tools`. */
    fun advertise(flagOn: Boolean, groupCalls: Boolean, risiTools: Boolean): Boolean = flagOn && groupCalls && risiTools

    /** §41.3: Risi's identity not in the room this long after connect → leave. */
    const val JOIN_TIMEOUT_MS = 12_000L

    /** §41.3 fail closed: the only remote identity the phone plays is Risi's. */
    fun allowedRemote(identity: String, risiIdentity: String): Boolean = identity.equals(risiIdentity, ignoreCase = true)
}

/** The Call Risi texts (§41.3, §41.4; English like the rest of the call screen). */
object CallRisiTexts {
    const val TITLE = "Risi"
    const val BUTTON = "Call Risi"
    const val BANNER = "Risi hears this call"
    const val NOT_E2EE = "Not end-to-end encrypted: audio is processed by Risi on RisiMe's server. Audio isn't kept."
    const val CONNECTING = "Connecting…"
    const val LISTENING = "Listening"
    const val SPEAKING = "Speaking"
    const val NO_ANSWER = "Risi couldn't answer. Try again in a moment."
    const val UNSAFE = "This call isn't safe any more"
    const val EXPLAIN_TITLE = "You're calling Risi"
    const val EXPLAIN_TEXT = "You're calling Risi. Risi hears what you say and answers. A transcript goes to your Risi chat. Audio isn't kept."
    const val LINE = "Call with Risi"
    const val TRANSCRIPT_TITLE = "Call with Risi"

    /** The error a refused `start` shows (§41.2), null for other failures (the generic "Can't connect"). */
    fun error(code: String?): String? = when (code) {
        CallErrors.CALL_RISI_OFF -> "Calling Risi isn't available for this account yet"
        CallErrors.NOT_RISI_CHAT -> "You can call Risi only from your Risi chat"
        CallErrors.RISI_BUSY -> "Risi is on other calls right now. Try again in a minute"
        CallErrors.RISI_UNAVAILABLE -> "Risi can't take calls right now"
        else -> null
    }

    /** The state line under "Risi" (§41.4). */
    fun stateLine(risiPresent: Boolean, risiSpeaking: Boolean): String = when {
        !risiPresent -> CONNECTING
        risiSpeaking -> SPEAKING
        else -> LISTENING
    }

    /** §41.3 the history line of a Call Risi (`group_call` with `"with": "risi"`). */
    fun historyLine(env: GroupCallEnvelope, running: Boolean): String = when {
        env.state == GroupCallEnvelope.STARTED -> if (running) "$LINE…" else LINE
        env.reason == GroupCallEnvelope.R_TIMEOUT -> "$LINE · Didn't connect"
        else -> env.durationS?.let { "$LINE · ${CallLines.duration(it)}" } ?: LINE
    }
}

/** §41.5 one line of a `call_transcript`. */
data class CallTranscriptLine(val who: String, val text: String, val filler: Boolean = false, val interrupted: Boolean = false) {
    val risi: Boolean get() = who == WHO_RISI

    companion object {
        const val WHO_USER = "user"
        const val WHO_RISI = "risi"
    }
}

/** §41.5 the Risi kind `call_transcript` (in the Risi chat). */
data class CallTranscript(val callId: String, val durationS: Long?, val lines: List<CallTranscriptLine>, val truncated: Boolean = false) {
    companion object {
        /**
         * The proposal's kind. It shares its name with §36.5's per-turn `call_transcript` (which has no `lines`),
         * so a post is a Call Risi transcript only when it carries `lines`; [ALT_KIND] is accepted too in case the
         * contract renames it.
         */
        const val KIND = "call_transcript"
        const val ALT_KIND = "risi_call_transcript"
        private const val MAX_LINES = 400

        /** From a stored row's `system_json` (the envelope or its `risi` object). */
        fun parseJson(json: String?): CallTranscript? {
            json ?: return null
            val o = runCatching { ai.risicloud.risime.net.ProtocolJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
            return parse((o["risi"] as? JsonObject) ?: o)
        }
        private const val MAX_TEXT = 2_000

        /** Parses the post's `risi` object; null = render the `body` (malformed or another kind). */
        fun parse(risi: JsonObject?): CallTranscript? {
            risi ?: return null
            fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (str(risi, "kind") != KIND && str(risi, "kind") != ALT_KIND) return null
            val callId = str(risi, "call_id") ?: return null
            val arr = risi["lines"] as? JsonArray ?: return null
            val lines = arr.take(MAX_LINES).mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val who = str(o, "who")?.takeIf { it == CallTranscriptLine.WHO_USER || it == CallTranscriptLine.WHO_RISI } ?: return@mapNotNull null
                val text = str(o, "text")?.trim()?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                CallTranscriptLine(
                    who, text,
                    filler = (o["filler"] as? JsonPrimitive)?.booleanOrNull == true,
                    interrupted = (o["interrupted"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            }
            if (lines.isEmpty()) return null
            val dur = (risi["duration_s"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 0..(24 * 3600L) }
            return CallTranscript(callId, dur, lines, truncated = (risi["truncated"] as? JsonPrimitive)?.booleanOrNull == true)
        }
    }

    /** The title line of the card ("Call with Risi · 3:12"). */
    val title: String get() = durationS?.let { "${CallRisiTexts.TRANSCRIPT_TITLE} · ${CallLines.duration(it)}" } ?: CallRisiTexts.TRANSCRIPT_TITLE
}
