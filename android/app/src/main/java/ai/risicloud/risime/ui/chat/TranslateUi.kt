package ai.risicloud.risime.ui.chat

import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.net.ProtocolJson
import ai.risicloud.risime.net.RisiVoiceTranscript
import ai.risicloud.risime.net.VoiceTranslate150
import ai.risicloud.risime.ui.theme.Spacing
import ai.risicloud.risime.voice.VoicePlace
import ai.risicloud.risime.voice.VoiceTranslations
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/*
 * Proposal 2026-10-14-voice-translate (v1.50 §37.13): "Wrong language?" and "Translate to …" under a Risi transcript,
 * and "Translate to …" under a typed Sinhala/Tamil message, in Official only, while `/me/risi/voice` says
 * `translate_available`. The translation shows only on this phone, labelled "Translated by Risi".
 */

/** Translate and "Wrong language?" apply here: Official (never Private, never the Risi chat), and on for this user. */
fun translateHere(x: ChatMessaging, conversationId: String, available: Boolean): Boolean =
    available && x.c.voice.place(conversationId) == VoicePlace.OFFICIAL

/** Under Risi's transcript of note [m] (already shown, `ok` or `unclear`). */
@Composable
fun TranscriptLanguageTools(m: MessageEntity, x: ChatMessaging, t: RisiVoiceTranscript) {
    val available by x.c.voice.translateAvailable.collectAsStateWithLifecycle()
    if (!translateHere(x, m.conversationId, available)) return
    val id = m.messageId ?: return
    val scope = rememberCoroutineScope()
    var choosing by remember(id) { mutableStateOf(false) }
    var sent by remember(id) { mutableStateOf<String?>(null) }
    Column(Modifier.widthIn(max = 320.dp).padding(top = Spacing.xxs, start = if (m.outgoing) 0.dp else Spacing.sm).testTag("voice_language_tools")) {
        t.relang?.let { Text(VoiceTranslate150.relangLine(it), style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(alpha = 0.6f)) }
        if (t.ok) TranslateLinks(x, m.conversationId, id, t.language)
        val s = sent
        when {
            s != null -> Text("Asked Risi to transcribe it in ${VoiceTranslate150.name(s)}…", style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(alpha = 0.6f))
            !choosing -> Link(VoiceTranslate150.WRONG_LANGUAGE, "voice_wrong_language") { choosing = true }
            else -> Row {
                for (l in VoiceTranslate150.choices(t.language)) {
                    Link(VoiceTranslate150.name(l), "voice_relang_$l") {
                        scope.launch {
                            val env = x.c.images.envelope(m.clientMsgId)?.let { runCatching { ProtocolJson.parseToJsonElement(String(it)) as? JsonObject }.getOrNull() }
                            val action = env?.let { VoiceTranslate150.transcriptLanguageAction(id, l, it) }
                            if (action != null) {
                                x.c.engine.sendRisiControl(m.conversationId, action)
                                sent = l
                            }
                            choosing = false
                        }
                    }
                    Text("  ")
                }
            }
        }
    }
}

/** Under a typed message in Official with Sinhala or Tamil letters. */
@Composable
fun TypedTranslateFooter(m: MessageEntity, x: ChatMessaging) {
    if (m.voice || m.media || m.kind != MessageEntity.KIND_TEXT || m.systemJson != null) return
    val available by x.c.voice.translateAvailable.collectAsStateWithLifecycle()
    if (!translateHere(x, m.conversationId, available) || !VoiceTranslate150.typedTranslatable(m.body)) return
    val id = m.messageId ?: return
    Column(Modifier.widthIn(max = 320.dp).padding(top = Spacing.xxs, start = if (m.outgoing) 0.dp else Spacing.sm).testTag("typed_translate")) {
        TranslateLinks(x, m.conversationId, id, VoiceTranslate150.textLanguage(m.body))
    }
}

/** "Translate to …" links (the text's own language hidden), the per-viewer result, and "Always translate" applied. */
@Composable
private fun TranslateLinks(x: ChatMessaging, conversationId: String, messageId: String, language: String?) {
    val store = x.c.voice.translations
    val states by store.states.collectAsStateWithLifecycle()
    val always by x.c.voice.alwaysTranslate.collectAsStateWithLifecycle()
    val supported by x.c.voice.translateLanguages.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val auto = VoiceTranslate150.autoTarget(always, language, supported)
    LaunchedEffect(messageId, auto) { if (auto != null) store.translate(x.c.api, conversationId, messageId, auto) }
    // Architect 14:05 item 3: Sinhala / English / Tamil (the text's own hidden), then "More languages…".
    TranslateLanguageButtons(language, supported, x.c.voice.recentTranslate, label = { "Translate to " + ai.risicloud.risime.voice.TranslateLanguages.name(it) }) { l ->
        scope.launch { store.translate(x.c.api, conversationId, messageId, l) }
    }
    for (l in supported) {
        when (val s = states[store.key(messageId, l)]) {
            is VoiceTranslations.State.Working -> Text(VoiceTranslations.WORKING, style = MaterialTheme.typography.labelSmall)
            is VoiceTranslations.State.Done -> Column(Modifier.padding(top = Spacing.xxs).testTag("translation_$l")) {
                Text(s.label, style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(alpha = 0.6f))
                Text(s.text, style = MaterialTheme.typography.bodyMedium)
            }
            is VoiceTranslations.State.Failed -> Text(s.why, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            null -> Unit
        }
    }
}

@Composable
private fun Link(text: String, tag: String, onClick: () -> Unit) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag(tag).clickable(onClick = onClick),
    )
}
