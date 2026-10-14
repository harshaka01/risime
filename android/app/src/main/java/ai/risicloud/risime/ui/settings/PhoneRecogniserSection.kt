package ai.risicloud.risime.ui.settings

import ai.risicloud.risime.AppContainer
import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.data.media.Decrypted
import ai.risicloud.risime.data.media.VoiceMeta
import ai.risicloud.risime.data.media.VoiceText
import ai.risicloud.risime.ui.theme.Spacing
import ai.risicloud.risime.voice.AndroidPhoneRecogniser
import ai.risicloud.risime.voice.NoteDecoder
import ai.risicloud.risime.voice.PhoneSttLocales
import ai.risicloud.risime.voice.PhoneSttResult
import ai.risicloud.risime.voice.PhoneSttSupport
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Texts of the developer "Phone recogniser test" (platform-free, unit-tested). */
object PhoneSttReport {
    const val FLAG_LABEL = "Phone transcript on my voice notes (on-device)"
    const val FLAG_HELP = "This phone's on-device recogniser transcribes your own voice notes before they're encrypted; " +
        "the text goes inside the encrypted note and recipients see it under the bubble. On-device only: " +
        "if the language isn't on this phone, the note goes without it. No audio goes to Google."

    /** "si-LK · 1.8 s (0.15× real time) · 0.82: <text>" and the honest failures. */
    fun line(r: PhoneSttResult, durationMs: Long): String = when (r) {
        is PhoneSttResult.Text -> "${r.locale} · ${secs(r.elapsedMs)} (${rtf(r.elapsedMs, durationMs)})${r.confidence?.let { " · conf %.2f".format(java.util.Locale.ROOT, it) }.orEmpty()}: ${r.text}"
        is PhoneSttResult.NoSpeech -> "${r.locale} · ${secs(r.elapsedMs)}: nothing heard"
        is PhoneSttResult.NotAvailable -> "not available: ${r.reason}"
        is PhoneSttResult.Failed -> "failed after ${secs(r.elapsedMs)}: ${r.reason}"
    }

    /** The headlines (Sinhala, then Tamil: the priority, Harsha 2026-10-14). */
    fun headline(name: String, tag: String, s: PhoneSttSupport, matched: String?): String = when (s) {
        PhoneSttSupport.INSTALLED -> "$name ($tag) offline: YES, installed${if (matched != null && !matched.equals(tag, true)) " as $matched" else ""}"
        PhoneSttSupport.PENDING -> "$name ($tag) offline: downloading…"
        PhoneSttSupport.DOWNLOADABLE -> "$name ($tag) offline: NOT YET, a pack exists: tap Download $name"
        PhoneSttSupport.NOT_ON_DEVICE -> "$name ($tag): online only on this phone (no offline pack)"
        else -> "$name ($tag) offline: unknown (${s.label})"
    }

    fun sinhalaHeadline(s: PhoneSttSupport, matched: String?): String = headline("Sinhala", "si-LK", s, matched)

    fun support(tag: String, s: PhoneSttSupport, matched: String?): String =
        "$tag: ${s.label}${if (matched != null && !matched.equals(tag, true)) " (as $matched)" else ""}"

    private fun secs(ms: Long) = "%.1f s".format(java.util.Locale.ROOT, ms / 1000.0)

    private fun rtf(ms: Long, durationMs: Long) = if (durationMs <= 0) "?" else "%.2f× real time".format(java.util.Locale.ROOT, ms.toDouble() / durationMs)
}

/**
 * Developer section (risi_dev accounts only): the `phone_stt` flag, the on-device language check (si, Singlish, ta, en)
 * with a download for an on-device pack, and the "Phone recogniser test" on a chosen voice note's audio (decrypted in
 * memory, fed through `EXTRA_AUDIO_SOURCE`), showing text + time per language to compare with the server models.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneRecogniserSection(c: AppContainer) {
    val flagOn by c.phoneSttFlag.on.collectAsStateWithLifecycle()
    val locale by c.phoneSttFlag.locale.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var report by remember { mutableStateOf<AndroidPhoneRecogniser.SupportReport?>(null) }
    var checking by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf<List<MessageEntity>?>(null) }
    var chosen by remember { mutableStateOf<MessageEntity?>(null) }
    var paced by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    val results = remember { mutableStateListOf<String>() }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.testTag("dev_phone_stt")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(PhoneSttReport.FLAG_LABEL, style = MaterialTheme.typography.bodyMedium)
                Text(PhoneSttReport.FLAG_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = flagOn, onCheckedChange = { c.phoneSttFlag.set(it) }, modifier = Modifier.testTag("dev_phone_stt_switch"))
        }
        Text("Language", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            PhoneSttLocales.CHOICES.forEach { ch ->
                FilterChip(selected = locale == ch.tag, onClick = { c.phoneSttFlag.setLocale(ch.tag) }, label = { Text(ch.label) })
            }
        }

        OutlinedButton(enabled = !checking, onClick = {
            checking = true
            scope.launch {
                report = c.phoneRecogniser.supportReport()
                checking = false
            }
        }, modifier = Modifier.testTag("dev_phone_stt_check")) { Text(if (checking) "Checking…" else "Check on-device languages") }
        report?.let { r ->
            listOf("Sinhala" to "si-LK", "Tamil" to "ta-LK").forEach { (name, tag) ->
                val (st, matched) = r.of(tag)
                Text(
                    PhoneSttReport.headline(name, tag, st, matched) + (r.error?.let { " ($it)" }.orEmpty()),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (st == PhoneSttSupport.INSTALLED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("dev_phone_stt_headline_$tag"),
                )
                if (st == PhoneSttSupport.DOWNLOADABLE) androidx.compose.material3.Button(onClick = {
                    scope.launch {
                        results.add(0, c.phoneRecogniser.download(matched ?: tag)?.let { "download $tag: $it" } ?: "download $tag requested (check again in a minute)")
                    }
                }, modifier = Modifier.testTag("dev_phone_stt_download_$tag")) { Text("Download $name") }
            }
            PhoneSttLocales.PROBES.forEach { tag ->
                val (s, matched) = r.of(tag)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(PhoneSttReport.support(tag, s, matched), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (s == PhoneSttSupport.DOWNLOADABLE) TextButton(onClick = {
                        scope.launch { results.add(0, c.phoneRecogniser.download(matched ?: tag)?.let { "download $tag: $it" } ?: "download $tag requested (check again in a minute)") }
                    }) { Text("Download") }
                }
            }
            if (r.error == null) {
                Text("Installed on-device: ${r.installed.joinToString().ifEmpty { "none" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Text("Phone recogniser test", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.sm))
        Text(
            "Runs the on-device recogniser on a voice note already on this phone and shows text + time, to compare with the server models on the same note.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (notes == null) {
            OutlinedButton(onClick = { scope.launch { notes = recentVoiceNotes(c) } }) { Text("Choose a voice note") }
        }
        notes?.let { list ->
            if (list.isEmpty()) Text("No voice notes on this phone yet.", style = MaterialTheme.typography.bodySmall)
            list.forEach { m ->
                val meta = VoiceMeta.decode(m.systemJson)
                val selected = chosen?.clientMsgId == m.clientMsgId
                Text(
                    (if (selected) "● " else "○ ") + (if (m.outgoing) "Sent" else "Received") + " · " + VoiceText.duration(meta?.durationMs ?: 0) +
                        " · " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(m.localTs)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().clickable { chosen = m }.padding(vertical = Spacing.xxs),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Real-time pacing", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(checked = paced, onCheckedChange = { paced = it })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            OutlinedButton(enabled = chosen != null && !running, onClick = {
                val m = chosen ?: return@OutlinedButton
                running = true
                scope.launch { runTest(c, m, listOf(locale), paced) { results.add(0, it) }; running = false }
            }) { Text("Run ($locale)") }
            OutlinedButton(enabled = chosen != null && !running, onClick = {
                val m = chosen ?: return@OutlinedButton
                running = true
                scope.launch { runTest(c, m, PhoneSttLocales.CHOICES.map { it.tag }, paced) { results.add(0, it) }; running = false }
            }) { Text("Run all languages") }
            if (results.isNotEmpty()) TextButton(onClick = {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Phone recogniser test", results.reversed().joinToString("\n")))
            }) { Text("Copy results") }
        }
        if (running) Text("Running…", style = MaterialTheme.typography.bodySmall)
        results.forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("dev_phone_stt_result")) }
    }
}

/** The 12 newest voice notes on this phone (any chat). */
private suspend fun recentVoiceNotes(c: AppContainer): List<MessageEntity> = withContext(Dispatchers.IO) {
    val dao = c.db.messages()
    dao.recentConversations(40).flatMap { dao.voiceNotesIn(it.conversationId) }
        .filter { !it.showsAsDeleted }.sortedByDescending { it.localTs }.take(12)
}

/** Decrypts [m] in memory, decodes it, and runs each locale in turn; the audio is wiped afterwards. */
private suspend fun runTest(c: AppContainer, m: MessageEntity, tags: List<String>, paced: Boolean, out: (String) -> Unit) {
    c.phoneRecogniser.precheck()?.let { out("not available: $it"); return }
    val durationMs = VoiceMeta.decode(m.systemJson)?.durationMs ?: 0
    val pcm = withContext(Dispatchers.IO) {
        var d = c.images.decrypt(m.clientMsgId)
        if (d is Decrypted.Missing) {
            c.downloadImage(m.clientMsgId)
            d = c.images.decrypt(m.clientMsgId)
        }
        val ok = d as? Decrypted.Ok ?: return@withContext null
        try { NoteDecoder.decode(ok.bytes) } finally { ok.bytes.fill(0) }
    }
    if (pcm == null) { out("couldn't decode this voice note on the phone"); return }
    try {
        for (tag in tags) {
            val s = c.phoneRecogniser.session(tag, paced)
            if (!s.start()) {
                out(PhoneSttReport.line(s.await(1_000) ?: PhoneSttResult.Failed("didn't start", 0), durationMs))
                continue
            }
            withContext(Dispatchers.Default) {
                val frame = 960
                var i = 0
                while (i < pcm.size) {
                    val n = minOf(frame, pcm.size - i)
                    s.feed(pcm.copyOfRange(i, i + n), n)
                    i += n
                }
                s.end()
            }
            out(PhoneSttReport.line(s.await(durationMs + 60_000) ?: PhoneSttResult.Failed("no result in time", durationMs + 60_000), durationMs))
        }
    } finally {
        pcm.fill(0)
    }
}
