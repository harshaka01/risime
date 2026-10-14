package ai.risicloud.risime.ui.chat

import ai.risicloud.risime.AppContainer
import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.data.media.PhoneTranscript
import ai.risicloud.risime.data.media.VoiceMeta
import ai.risicloud.risime.ui.theme.Spacing
import ai.risicloud.risime.voice.ConsentKind
import ai.risicloud.risime.voice.PrivateLangs
import ai.risicloud.risime.voice.PrivateNoteRef
import ai.risicloud.risime.voice.PrivateStep
import ai.risicloud.risime.voice.PrivateVoiceStrings
import ai.risicloud.risime.voice.ReaderTranscript
import ai.risicloud.risime.voice.ReaderTranslation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * Decision 092: under a Private message, while the server's RISI_PRIVATE_TRANSCRIBE applies to this account. A voice
 * note gets the reader's transcript (on this phone, or Google after the reader's yes) and Translate; a received typed
 * message gets Translate (RisiMe's server after the reader's yes). Everything here is shown only on this phone and
 * never sent to the chat. With the flag off this draws nothing (parity).
 */
@Composable
fun PrivateFooter(m: MessageEntity, x: ChatMessaging, phone: PhoneTranscript?) {
    val c = x.c
    LaunchedEffect(Unit) { c.refreshPrivateTranscribe() }
    val on by c.privateTranscribeFlag.on.collectAsStateWithLifecycle()
    if (!on || m.deleted || m.showsAsDeleted) return
    when {
        m.voice -> PrivateVoiceBlock(c, m, phone)
        m.kind == MessageEntity.KIND_TEXT && !m.outgoing && m.body.isNotBlank() ->
            PrivateTranslateRow(c, m.clientMsgId, m.body, source = null, allowed = true, mine = false)
    }
}

private fun alignPad(mine: Boolean) = Modifier.widthIn(max = 320.dp).padding(top = Spacing.xxs, start = if (mine) 0.dp else Spacing.sm, end = if (mine) Spacing.sm else 0.dp)

@Composable
private fun PrivateVoiceBlock(c: AppContainer, m: MessageEntity, phone: PhoneTranscript?) {
    val meta = remember(m.systemJson) { VoiceMeta.decode(m.systemJson) } ?: return
    val note = remember(m.clientMsgId, meta) {
        PrivateNoteRef(m.clientMsgId, m.from, meta.durationMs, phone?.lang, meta.othersMayTranscribe, m.outgoing)
    }
    val states by c.privateVoice.transcripts.collectAsStateWithLifecycle()
    val st = states[m.clientMsgId]
    val scope = rememberCoroutineScope()
    var choosing by remember(m.clientMsgId) { mutableStateOf(false) }
    var consentLang by remember(m.clientMsgId) { mutableStateOf<Pair<String, Boolean>?>(null) }

    // Item 1: an English note is transcribed on this phone automatically (nothing leaves it).
    LaunchedEffect(m.clientMsgId) { c.privateVoice.auto(note) }

    fun go(lang: String) {
        scope.launch {
            c.privateVoice.clear(note.key)
            when (val step = c.privateVoice.request(note, lang)) {
                is PrivateStep.NeedsConsent -> consentLang = lang to step.englishOnline
                else -> Unit
            }
        }
    }

    // The sender phone's own transcript (§37.10) can be translated too (not when the sender opted out).
    if (phone != null) PrivateTranslateRow(c, "${m.clientMsgId}:phone", phone.text, PrivateLangs.primary(phone.lang), note.senderAllows, m.outgoing)

    Column(alignPad(m.outgoing).testTag("private_voice_block")) {
        when (st) {
            null -> if (phone == null) {
                Link(PrivateVoiceStrings.TRANSCRIBE, "private_transcribe") { choosing = true }
            }
            is ReaderTranscript.Running -> Note(
                if (st.engine == ai.risicloud.risime.voice.PrivateEngine.GOOGLE) PrivateVoiceStrings.TRANSCRIBING_GOOGLE else PrivateVoiceStrings.TRANSCRIBING_PHONE,
            )
            is ReaderTranscript.Done -> {
                ReaderTranscriptBlock(m.clientMsgId, st.engine.label, st.text)
                PrivateTranslateRow(c, "${m.clientMsgId}:reader", st.text, st.lang, note.senderAllows, m.outgoing)
            }
            is ReaderTranscript.Mismatch -> {
                Note(PrivateVoiceStrings.mismatch(st.chosen, st.seen))
                Link(PrivateVoiceStrings.tryLang(st.seen), "private_try_other") { go(st.seen) }
            }
            is ReaderTranscript.Failed -> {
                Note(st.reason)
                Link(PrivateVoiceStrings.TRANSCRIBE, "private_transcribe_again") { c.privateVoice.clear(note.key); choosing = true }
            }
            is ReaderTranscript.Blocked -> Note(st.reason)
        }
    }

    if (choosing) {
        LanguageDialog(PrivateVoiceStrings.CHOOSE_LANGUAGE, PrivateLangs.ALL, preselected = c.privateVoice.hint(note), onPick = { lang -> choosing = false; go(lang) }, onDismiss = { choosing = false })
    }
    consentLang?.let { (lang, englishOnline) ->
        ConsentDialog(
            title = PrivateVoiceStrings.GOOGLE_TITLE,
            body = if (englishOnline) PrivateVoiceStrings.GOOGLE_BODY_EN else PrivateVoiceStrings.GOOGLE_BODY,
            tag = "private_google_consent",
        ) { yes, dontAsk ->
            consentLang = null
            scope.launch { c.privateVoice.answer(note, lang, yes, dontAsk) }
        }
    }
}

/** A reader-side transcript, collapsed to its honest label ("Transcript (on this phone)" / "Transcript (Google)"). */
@Composable
private fun ReaderTranscriptBlock(key: String, label: String, text: String) {
    var open by remember(key) { mutableStateOf(false) }
    Row(
        Modifier.clickable { open = !open }.semantics { contentDescription = "$label: ${if (open) "expanded" else "collapsed"}" }.testTag("private_reader_transcript"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.75f))
        Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp), tint = LocalContentColor.current.copy(alpha = 0.75f))
    }
    if (open) Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.xxs).testTag("private_reader_transcript_text"))
}

/**
 * "Translate" under a text: choose English / Sinhala / Tamil, then the translate dialog (unless "Don't ask again"),
 * then "Translation (RisiMe server)" with the text, on this phone only.
 */
@Composable
fun PrivateTranslateRow(c: AppContainer, key: String, text: String, source: String?, allowed: Boolean, mine: Boolean) {
    val all by c.privateVoice.translations.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var choosing by remember(key) { mutableStateOf(false) }
    var consentTarget by remember(key) { mutableStateOf<String?>(null) }
    var notice by remember(key) { mutableStateOf<String?>(null) }
    val targets = PrivateLangs.ALL.filter { it != source }
    Column(alignPad(mine).testTag("private_translate")) {
        Link(PrivateVoiceStrings.TRANSLATE, "private_translate_link") { choosing = true }
        notice?.let { Note(it) }
        for (t in targets) {
            when (val r = all[c.privateVoice.translationKey(key, t)]) {
                ReaderTranslation.Running -> Note(PrivateVoiceStrings.TRANSLATING)
                is ReaderTranslation.Done -> {
                    Text("${ai.risicloud.risime.voice.PrivateEngine.SERVER.label} · ${PrivateLangs.name(r.target)}", style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.75f))
                    Text(r.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("private_translation_text"))
                }
                is ReaderTranslation.Failed -> Note(r.reason)
                null -> Unit
            }
        }
    }
    if (choosing) {
        LanguageDialog(PrivateVoiceStrings.TRANSLATE, targets, preselected = null, label = PrivateVoiceStrings::translateTo, onPick = { t ->
            choosing = false
            scope.launch {
                when (val step = c.privateVoice.translate(key, text, t, source, allowed)) {
                    is PrivateStep.NeedsConsent -> consentTarget = t
                    is PrivateStep.Blocked -> notice = step.reason
                    else -> Unit
                }
            }
        }, onDismiss = { choosing = false })
    }
    consentTarget?.let { t ->
        ConsentDialog(PrivateVoiceStrings.TRANSLATE_TITLE, PrivateVoiceStrings.TRANSLATE_BODY, "private_translate_consent") { yes, dontAsk ->
            consentTarget = null
            scope.launch { c.privateVoice.answerTranslate(key, text, t, source, yes, dontAsk, allowed) }
        }
    }
}

@Composable
private fun Link(text: String, tag: String, onClick: () -> Unit) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Spacing.xxs).clickable(onClick = onClick).testTag(tag),
    )
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(alpha = 0.7f), modifier = Modifier.padding(top = Spacing.xxs))
}

@Composable
private fun LanguageDialog(
    title: String,
    langs: List<String>,
    preselected: String?,
    label: (String) -> String = PrivateLangs::name,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                val ordered = preselected?.let { p -> langs.sortedBy { if (it == p) 0 else 1 } } ?: langs
                ordered.forEach { l ->
                    TextButton(onClick = { onPick(l) }, modifier = Modifier.testTag("private_lang_$l")) { Text(label(l)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The per-action yes/no with "Don't ask again" (decision 092). Dismissing counts as No: nothing is sent. */
@Composable
private fun ConsentDialog(title: String, body: String, tag: String, onAnswer: (yes: Boolean, dontAskAgain: Boolean) -> Unit) {
    var dontAsk by remember { mutableStateOf(false) }
    AlertDialog(
        modifier = Modifier.testTag(tag),
        onDismissRequest = { onAnswer(false, false) },
        title = { Text(title) },
        text = {
            Column {
                Text(body)
                Row(Modifier.clickable { dontAsk = !dontAsk }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = dontAsk, onCheckedChange = { dontAsk = it }, modifier = Modifier.testTag("${tag}_dont_ask"))
                    Text(PrivateVoiceStrings.DONT_ASK)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true, dontAsk) }, modifier = Modifier.testTag("${tag}_yes")) { Text(PrivateVoiceStrings.YES) } },
        dismissButton = { TextButton(onClick = { onAnswer(false, false) }, modifier = Modifier.testTag("${tag}_no")) { Text(PrivateVoiceStrings.NO) } },
    )
}

/** Consent kinds as the Settings section lists them. */
internal val PRIVATE_CONSENT_ROWS = listOf(
    ConsentKind.GOOGLE_TRANSCRIBE to PrivateVoiceStrings.SETTING_GOOGLE,
    ConsentKind.SERVER_TRANSLATE to PrivateVoiceStrings.SETTING_TRANSLATE,
)
