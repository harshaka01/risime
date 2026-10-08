package lk.codegen.risime.ui.chats

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lk.codegen.risime.calls.CallRecord
import lk.codegen.risime.calls.CallRecords
import lk.codegen.risime.ui.common.EmptyState
import lk.codegen.risime.ui.common.InitialsAvatar
import lk.codegen.risime.ui.common.RisiIcons
import lk.codegen.risime.ui.theme.RisiTheme
import lk.codegen.risime.ui.theme.Sizes
import lk.codegen.risime.ui.theme.Spacing

/** The Calls tab: WhatsApp-style rows, grouped "Name (3)"; tap = call info, the icon = call back with the same type. */
@Composable
fun CallsTab(vm: ChatsViewModel, onOpenChat: (String) -> Unit) {
    val rows by vm.calls.collectAsStateWithLifecycle()
    val callBack = rememberCallBack(vm)
    var info by remember { mutableStateOf<CallRow?>(null) }
    CallsList(rows, nowMs = System.currentTimeMillis(), onInfo = { info = it }, onCallBack = callBack)
    // Keep the dialog live: the newest data for the row that was opened.
    info?.let { shown ->
        val live = rows.firstOrNull { it.group.conversationId == shown.group.conversationId && it.key == shown.key } ?: shown
        CallInfoDialog(
            live, calls = rows.filter { it.group.conversationId == shown.group.conversationId }.flatMap { it.group.calls },
            nowMs = System.currentTimeMillis(), onDismiss = { info = null }, onCallBack = callBack,
            onMessage = { info = null; onOpenChat(shown.group.conversationId) },
        )
    }
}

@Composable
fun CallsList(rows: List<CallRow>, nowMs: Long, onInfo: (CallRow) -> Unit, onCallBack: (conversationId: String, video: Boolean) -> Unit) {
    if (rows.isEmpty()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { EmptyState("No calls yet. Calls you make and receive show up here.") }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.key }) { row -> CallRowItem(row, nowMs, onInfo, onCallBack) }
    }
}

@Composable
private fun CallRowItem(row: CallRow, nowMs: Long, onInfo: (CallRow) -> Unit, onCallBack: (String, Boolean) -> Unit) {
    val r = row.group.latest
    val color = if (r.missed) MaterialTheme.colorScheme.error else RisiTheme.colors.textMuted
    Row(
        Modifier.fillMaxWidth().heightIn(min = Sizes.listRowMin).clickable { onInfo(row) }
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialsAvatar(row.name, photoKey = row.userId)
        Spacer(Modifier.width(Spacing.md + Spacing.xxs))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium, color = if (r.missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Text("${CallRecords.arrow(r)} ${CallRecords.stamp(r.atMs, nowMs)}", style = MaterialTheme.typography.bodyMedium, color = if (r.missed) color else directionGreen(r), maxLines = 1)
        }
        IconButton(onClick = { onCallBack(r.conversationId, r.video) }) {
            Icon(
                if (r.video) RisiIcons.Videocam else Icons.Default.Call,
                if (r.video) "Video call ${row.name}" else "Voice call ${row.name}",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Incoming / outgoing arrows are green; missed ones use the error colour. */
@Composable
private fun directionGreen(r: CallRecord) = if (r.missed) MaterialTheme.colorScheme.error else callGreen()

@Composable
private fun callGreen(): Color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF4CD58A) else Color(0xFF0B7A3B)

/** The per-contact call list: every call with times and durations, and Call / Video call / Message. */
@Composable
fun CallInfoDialog(
    row: CallRow,
    calls: List<CallRecord>,
    nowMs: Long,
    onDismiss: () -> Unit,
    onCallBack: (conversationId: String, video: Boolean) -> Unit,
    onMessage: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row {
                    TextButton(onClick = { onCallBack(row.group.conversationId, false) }) { Text("Voice call") }
                    TextButton(onClick = { onCallBack(row.group.conversationId, true) }) { Text("Video call") }
                    TextButton(onClick = onMessage) { Text("Message") }
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(calls, key = { it.clientMsgId }) { c ->
                        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs).semantics { contentDescription = "${c.label}, ${CallRecords.stamp(c.atMs, nowMs)}" }) {
                            Text(
                                (if (c.outgoing) "Outgoing " else "Incoming ") + (if (c.video) "video" else "voice") + " call",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (c.missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "${CallRecords.arrow(c)} ${CallRecords.stamp(c.atMs, nowMs)} · " + (c.durationS?.let { CallRecords.durationText(it) } ?: c.outcomeText()),
                                style = MaterialTheme.typography.bodySmall, color = RisiTheme.colors.textMuted,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The info list's second line when there is no duration. */
private fun CallRecord.outcomeText(): String = when (outcome) {
    lk.codegen.risime.calls.CallOutcome.MISSED -> "Missed"
    lk.codegen.risime.calls.CallOutcome.DECLINED -> "Declined"
    lk.codegen.risime.calls.CallOutcome.NO_ANSWER -> "No answer"
    lk.codegen.risime.calls.CallOutcome.CANCELLED -> "Cancelled"
    lk.codegen.risime.calls.CallOutcome.FAILED -> "Couldn't connect"
    lk.codegen.risime.calls.CallOutcome.ANSWERED -> "Answered"
}

/** Asks the microphone (and the camera for video) the first time, explains when calls can't work, then calls. */
@Composable
fun rememberCallBack(vm: ChatsViewModel): (String, Boolean) -> Unit {
    val ctx = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var pending by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val (conv, video) = pending ?: return@rememberLauncherForActivityResult
        pending = null
        val mic = r[Manifest.permission.RECORD_AUDIO] ?: granted(Manifest.permission.RECORD_AUDIO)
        if (!mic) Toast.makeText(ctx, "RisiMe needs the microphone for calls", Toast.LENGTH_LONG).show()
        else vm.callBack(conv, video, camera = video && (r[Manifest.permission.CAMERA] ?: granted(Manifest.permission.CAMERA)))
    }
    return { conv, video ->
        val blocked = vm.callBlocked()
        when {
            blocked != null -> Toast.makeText(ctx, blocked, Toast.LENGTH_LONG).show()
            granted(Manifest.permission.RECORD_AUDIO) && (!video || granted(Manifest.permission.CAMERA)) -> vm.callBack(conv, video, camera = video)
            else -> {
                pending = conv to video
                ask.launch(if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO))
            }
        }
    }
}
