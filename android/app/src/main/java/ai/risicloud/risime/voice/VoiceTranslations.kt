package ai.risicloud.risime.voice

import ai.risicloud.risime.net.ApiClient
import ai.risicloud.risime.net.ApiResult
import ai.risicloud.risime.net.TranslateRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Proposal 2026-10-14-voice-translate (v1.50 §37.13.2): this viewer's translations, **in memory only** (never Room,
 * never a backup, never sent to anyone). Keyed by `message_id` + target.
 */
class VoiceTranslations {
    sealed class State {
        data object Working : State()
        data class Done(val text: String, val label: String) : State()
        data class Failed(val why: String) : State()
    }

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states

    fun key(messageId: String, target: String) = "${messageId.lowercase()}:$target"

    fun state(messageId: String, target: String): State? = _states.value[key(messageId, target)]

    /** Requests the translation once (a running or finished one isn't asked again; a failed one is). */
    suspend fun translate(api: ApiClient, conversationId: String, messageId: String, target: String) {
        val k = key(messageId, target)
        when (_states.value[k]) {
            is State.Working, is State.Done -> return
            else -> Unit
        }
        put(k, State.Working)
        val r = runCatching { api.risiTranslate(TranslateRequest(conversationId, messageId.lowercase(), target)) }.getOrNull()
        put(k, if (r is ApiResult.Ok) State.Done(r.value.text, r.value.label) else State.Failed(FAILED))
    }

    private fun put(k: String, s: State) {
        _states.value = _states.value + (k to s)
    }

    fun clear() {
        _states.value = emptyMap()
    }

    companion object {
        const val FAILED = "Risi couldn't translate this. Try again later"
        const val WORKING = "Translating…"
    }
}
