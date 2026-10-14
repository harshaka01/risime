package ai.risicloud.risime.ui.settings

import ai.risicloud.risime.ui.theme.Spacing
import ai.risicloud.risime.voice.PhoneTts
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag

/** Flattens a platform voice for [PhoneTts] (the only place that reads `android.speech.tts.Voice`). */
private fun Voice.info() = PhoneTts.VoiceInfo(
    name = name,
    localeTag = locale.toLanguageTag(),
    networkRequired = isNetworkConnectionRequired,
    notInstalled = features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true,
    quality = quality,
    latency = latency,
)

/**
 * Developer "Phone voice (TTS) test" (risi_dev accounts only, under Developer; docs/status/phone-tts.md): which
 * si / ta / en voices this phone's TextToSpeech engine has **on the device**, and a sentence spoken with one of them,
 * with the time from `speak()` to the first audio (`onStart`). Network-only voices are listed as rejected and never
 * used; with no on-device voice the test refuses. The sentence is neither stored nor logged.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneTtsSection() {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    var ready by remember { mutableStateOf<Boolean?>(null) }
    var engine by remember { mutableStateOf("") }
    var engines by remember { mutableStateOf<List<String>>(emptyList()) }
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var lang by remember { mutableStateOf("ta") }
    var text by remember { mutableStateOf(PhoneTts.lang("ta")!!.sample) }
    var speaking by remember { mutableStateOf(false) }
    val results = remember { mutableStateListOf<String>() }
    val started = remember { mutableMapOf<String, Triple<Long, String, String>>() }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(Unit) {
        // The init callback is posted to the main thread, so `holder` is assigned by the time it runs.
        lateinit var holder: TextToSpeech
        holder = TextToSpeech(context.applicationContext) { status ->
            main.post {
                val t = holder
                if (status == TextToSpeech.SUCCESS) {
                    engine = t.defaultEngine.orEmpty()
                    engines = t.engines.map { it.name }
                    voices = runCatching { t.voices?.toList().orEmpty() }.getOrDefault(emptyList())
                    ready = true
                } else ready = false
            }
        }
        holder.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                val at = SystemClock.elapsedRealtime()
                main.post {
                    started.remove(utteranceId)?.let { (t0, l, v) -> results.add(0, PhoneTts.result(l, v, at - t0)) }
                }
            }
            override fun onDone(utteranceId: String) { main.post { speaking = false } }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String) { main.post { speaking = false; results.add(0, "failed") } }
            override fun onError(utteranceId: String, errorCode: Int) {
                main.post { speaking = false; started.remove(utteranceId); results.add(0, "failed (error $errorCode)") }
            }
        })
        tts = holder
        onDispose {
            holder.stop()
            holder.shutdown()
            tts = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.testTag("dev_phone_tts")) {
        Text("Phone voice (TTS) test", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.sm))
        Text(
            "This phone's own text-to-speech, on-device voices only (network voices are never used). Compare with the server voices on the admin console's Voices page.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (ready) {
            null -> Text("Starting the phone's TTS engine…", style = MaterialTheme.typography.bodySmall)
            false -> Text("This phone's TTS engine didn't start.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            true -> {
                Text("Engine: ${engine.ifEmpty { "none" }}${if (engines.size > 1) " (installed: ${engines.joinToString()})" else ""}", style = MaterialTheme.typography.bodySmall)
                val infos = voices.map { it.info() }
                PhoneTts.LANGS.forEach { l ->
                    val pick = PhoneTts.pickOnDeviceVoice(infos, l.code)
                    Text(
                        "${l.label}: ${pick?.let { "on-device voice ${it.name}" } ?: PhoneTts.refusal(l.code)}",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("dev_phone_tts_${l.code}"),
                    )
                    PhoneTts.voicesFor(infos, l).forEach {
                        Text("  " + PhoneTts.line(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    PhoneTts.LANGS.forEach { l ->
                        FilterChip(selected = lang == l.code, onClick = { lang = l.code; text = l.sample }, label = { Text(l.label) })
                    }
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(300) }, label = { Text("Sentence") },
                    modifier = Modifier.fillMaxWidth().testTag("dev_phone_tts_text"),
                )
                OutlinedButton(enabled = !speaking && text.isNotBlank(), onClick = {
                    val t = tts ?: return@OutlinedButton
                    val pick = PhoneTts.pickOnDeviceVoice(infos, lang)
                    val voice = pick?.let { p -> voices.firstOrNull { it.name == p.name } }
                    if (voice == null) { results.add(0, PhoneTts.refusal(lang)); return@OutlinedButton }
                    if (t.setVoice(voice) != TextToSpeech.SUCCESS) { results.add(0, "couldn't select ${voice.name}"); return@OutlinedButton }
                    val params = Bundle().apply {
                        putString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS, "true")
                        putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "false")
                    }
                    val id = "risime-tts-test-" + SystemClock.elapsedRealtime()
                    started[id] = Triple(SystemClock.elapsedRealtime(), lang, voice.name) // no text kept
                    speaking = true
                    if (t.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) {
                        started.remove(id); speaking = false; results.add(0, "speak failed")
                    }
                }, modifier = Modifier.testTag("dev_phone_tts_speak")) { Text(if (speaking) "Speaking…" else "Speak") }
            }
        }
        results.forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("dev_phone_tts_result")) }
    }
}
