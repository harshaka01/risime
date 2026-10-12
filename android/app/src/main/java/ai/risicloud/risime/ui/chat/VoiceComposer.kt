package ai.risicloud.risime.ui.chat

import ai.risicloud.risime.AppContainer
import ai.risicloud.risime.net.ApiResult
import ai.risicloud.risime.voice.VoicePlace
import ai.risicloud.risime.voice.VoiceRecorder
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** What the composer needs to swap Send for the mic (§37.3): [recording] replaces the text field with [bar]. */
class VoiceComposerSlot(
    val recording: Boolean,
    val bar: @Composable (Modifier) -> Unit,
    val button: @Composable (Dp) -> Unit,
)

/**
 * §37.3 the composer's voice-note controls for [conversationId] (null when this phone can't record: Send stays).
 * Hold the mic to record, slide left to cancel, slide up to lock (then [Delete] and [Send]); the microphone is asked
 * on first use with the reason; the mic is live only when the chat is `voice_ready` (else the §33.1 notice);
 * Official shows the one-time "Risi transcribes" note first. [reply] takes the composer's reply target.
 */
@Composable
fun rememberVoiceComposer(
    c: AppContainer,
    conversationId: String,
    notReadyText: String,
    reply: () -> ai.risicloud.risime.net.ReplyRef?,
    toast: (String) -> Unit,
): VoiceComposerSlot? {
    if (!remember { c.voice.canRecord() }) return null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = c.voice.recorder
    val recState by recorder.state.collectAsStateWithLifecycle()
    var locked by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var ready by remember(conversationId) { mutableStateOf<Boolean?>(null) }
    var rationale by remember { mutableStateOf(false) }
    var officialNote by remember { mutableStateOf(false) }
    var mine by remember { mutableStateOf(false) } // this composer owns the running recording
    LaunchedEffect(conversationId) {
        // A chat that just became e2ee (a new group, Official just started) or whose members just updated turns ready
        // within seconds: re-check while it isn't (bounded), never after.
        repeat(24) {
            ready = c.voiceReady(conversationId) ?: ready
            if (ready == true) return@LaunchedEffect
            kotlinx.coroutines.delay(5_000)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast(VoiceStrings.MIC_DENIED)
    }
    // Leaving the chat (or the screen going away) mid-recording discards it; nothing is sent by accident.
    DisposableEffect(conversationId) {
        onDispose { if (mine) recorder.cancel() }
    }
    fun send() {
        mine = false
        locked = false
        dragX = 0f
        scope.launch {
            val note = recorder.stop()
            if (note == null) {
                toast(VoiceStrings.TOO_SHORT)
                return@launch
            }
            if (c.voice.send(conversationId, note, reply()) == null) toast(VoiceStrings.CANT_RECORD)
        }
    }
    fun cancel() {
        mine = false
        locked = false
        dragX = 0f
        recorder.cancel()
    }
    if (rationale) {
        AlertDialog(
            onDismissRequest = { rationale = false },
            title = { Text(VoiceStrings.MIC_RATIONALE_TITLE) },
            text = { Text(VoiceStrings.MIC_RATIONALE) },
            confirmButton = { TextButton(onClick = { rationale = false; permission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { rationale = false }) { Text("Not now") } },
        )
    }
    if (officialNote) {
        AlertDialog(
            onDismissRequest = { officialNote = false },
            text = { Text(VoiceStrings.OFFICIAL_NOTE) },
            confirmButton = { TextButton(onClick = { officialNote = false }) { Text("OK") } },
            modifier = Modifier.testTag("voice_official_note"),
        )
    }
    val recording = mine && recState is VoiceRecorder.State.Recording
    return VoiceComposerSlot(
        recording = recording,
        bar = { m -> RecordingBar(recState, locked, dragX, onDelete = ::cancel, modifier = m) },
        button = { size ->
            if (recording && locked) {
                FilledIconButton(
                    onClick = ::send,
                    shape = androidx.compose.foundation.shape.CircleShape,
                    modifier = Modifier.testTag("voice_send").size(size),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send voice note", Modifier.size(22.dp)) }
            } else {
                MicButton(
                    enabled = ready != false,
                    size = size,
                    onPress = press@{
                        if (ready == false) {
                            toast(notReadyText)
                            scope.launch { ready = c.voiceReady(conversationId) }
                            return@press false
                        }
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            rationale = true
                            return@press false
                        }
                        if (c.voice.officialNoteDue(conversationId)) {
                            c.voice.officialNoteShown()
                            officialNote = true
                            return@press false
                        }
                        c.voicePlayer.pause()
                        if (!recorder.start()) {
                            toast(VoiceStrings.MIC_BUSY)
                            return@press false
                        }
                        mine = true
                        locked = false
                        dragX = 0f
                        true
                    },
                    onRelease = ::send,
                    onCancel = ::cancel,
                    onLock = { locked = true; dragX = 0f },
                    onDrag = { dx, _ -> dragX = dx },
                )
            }
        },
    )
}

/** §37.1 the notice for a chat that isn't `voice_ready`. */
fun voiceNotReadyText(dm: Boolean, name: String): String = if (dm) VoiceStrings.dmNotReady(name) else VoiceStrings.GROUP_NOT_READY

/** Private never shows Risi text near the mic (§37.3); only used to decide the Official one-time note. */
fun isOfficial(c: AppContainer, conversationId: String): Boolean = c.voice.place(conversationId) == VoicePlace.OFFICIAL
