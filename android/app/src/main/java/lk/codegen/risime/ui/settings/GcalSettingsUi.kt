package lk.codegen.risime.ui.settings

import android.app.PendingIntent
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lk.codegen.risime.data.db.GcalCopyState
import lk.codegen.risime.data.gcal.AuthResult
import lk.codegen.risime.data.gcal.GCalendar
import lk.codegen.risime.data.gcal.GcalLinkManager
import lk.codegen.risime.data.gcal.GcalOutcome
import lk.codegen.risime.data.gcal.GcalResult
import lk.codegen.risime.data.gcal.GcalRuntime
import lk.codegen.risime.data.gcal.GcalSection
import lk.codegen.risime.data.gcal.GcalSelection
import lk.codegen.risime.ui.theme.Spacing

/*
 * v1.31 §31.2/§31.8/§31.9 Settings → Risi skills → Calendar → "Google Calendar". The consent PendingIntent is
 * launched only from this screen. The exact texts below are what scripts/ui-entry-test --google drives.
 */

/** §31.9 English (the app localises from the codes). */
object GcalText {
    const val TITLE = "Google Calendar"
    const val CONNECT = "Connect Google Calendar"
    const val RECONNECT = "Reconnect Google Calendar"
    const val CONNECT_HERE = "Connect here instead"
    const val DISCONNECT = "Disconnect"
    const val CHANGE = "Change calendars"
    const val CHECK_FOR_BUSY = "Check for busy times"
    const val ADD_EVENTS_TO = "Add my Risi events to"
    const val COPY_SWITCH = "Copy my Risi Calendar events to Google"
    const val INFO = "Your Google sign-in stays on this phone. RisiMe's server never sees it, your Google calendar names or your Google event titles. Risi sees only busy times. Copies of your Risi events are written to Google from this phone."
    const val NOT_CONNECTED = "Not connected"
    const val NEEDS_RECONNECTING = "Needs reconnecting"
    const val PAUSED = "Paused: turn on the Calendar skill to use it"
    const val NO_PLAY = "Google Play services isn't available on this phone."
    const val NEEDS_BOTH = "Google Calendar needs both permissions"
    const val SAVE = "Save"
    const val CANCEL = "Cancel"
    const val DISCONNECT_TITLE = "Disconnect Google Calendar?"
    const val DISCONNECT_BODY = "Risi will stop checking it and stop copying events."
    const val COPIED_LABEL = "Events already copied:"
    const val KEEP = "Keep them in Google"
    const val REMOVE = "Remove them from Google"
    const val AT_MOST = "At most 10 calendars."
    const val LOAD_FAILED = "Couldn't load your Google calendars. Try again."
    const val FAILED = "Couldn't change it. Try again."
    const val KEEP_BTN = "Keep"
    const val REMOVE_BTN = "Remove"

    fun connectedOnThisPhone(read: Int, writeName: String?): String =
        "Connected on this phone · Checking ${calendars(read)}" + (writeName?.let { " · Adding events to $it" } ?: "")

    fun connectedOn(device: String?) = "Connected on ${device?.takeIf { it.isNotBlank() } ?: "another device"}"

    fun calendars(n: Int) = if (n == 1) "1 calendar" else "$n calendars"

    fun removedInGoogle(n: Int) = if (n == 1) "1 Risi event was removed in Google" else "$n Risi events were removed in Google"

    fun removeCopiesTitle(n: Int) = if (n == 1) "Remove the 1 copy from Google?" else "Remove the $n copies from Google?"

    fun connectedAs(account: String) = "Connected as $account"
}

/** The picker sheet's state (§31.2 step 3). */
data class GcalPicker(
    val calendars: List<GCalendar>,
    val read: Set<String>,
    val write: String?,
    val mirror: Boolean,
    /** Changing calendars while connected (else: the first connect, or "Connect here instead"). */
    val changing: Boolean,
    val wasMirroring: Boolean = false,
)

data class GcalUi(
    val section: GcalSection = GcalSection.Hidden,
    val busy: Boolean = false,
    val note: String? = null,
    val picker: GcalPicker? = null,
    val confirmDisconnect: Boolean = false,
    val removeCopiesAsk: Int? = null,
    val deletedInGoogle: Int = 0,
)

class GcalSettingsModel(
    private val rt: GcalRuntime,
    private val scope: CoroutineScope,
    private val me: suspend () -> String?,
    /** The switch (`google_calendar` on and advertised, with `risi_events`). */
    switchOn: Flow<Boolean>,
    /** The Calendar skill is `off` (the section pauses). */
    skillOff: Flow<Boolean>,
) {
    private val manager: GcalLinkManager get() = rt.manager
    private val local = MutableStateFlow(GcalUi())
    private val myId = MutableStateFlow<String?>(null)

    val state: StateFlow<GcalUi> =
        combine(local, manager.link, manager.localReauth, rt.dao.observeCalendars(), combine(switchOn, skillOff, rt.dao.observeCopies(), myId) { a, b, c, d -> Quad(a, b, c, d) }) { ui, link, reauth, cals, q ->
            val write = cals.firstOrNull { it.write }
            ui.copy(
                section = manager.section(
                    link, q.me, reauth, q.skillOff, cals.isNotEmpty(), q.switchOn, write?.name, cals.count { it.read }, cals.firstNotNullOfOrNull { it.account },
                ),
                deletedInGoogle = q.copies.count { it.state == GcalCopyState.DELETED_IN_GOOGLE },
            )
        }.stateIn(scope, SharingStarted.Eagerly, GcalUi())

    private data class Quad(val switchOn: Boolean, val skillOff: Boolean, val copies: List<lk.codegen.risime.data.db.GcalCopyEntity>, val me: String?)

    private fun ui(f: (GcalUi) -> GcalUi) = local.update(f)

    /** Screen open: remember this device id and `GET` the link. */
    fun open() {
        scope.launch {
            myId.value = runCatching { me() }.getOrNull()
            manager.refresh()
        }
    }

    private fun busy(on: Boolean) = ui { it.copy(busy = on) }

    private fun note(t: String?) = ui { it.copy(note = t) }

    /** [Connect Google Calendar] / [Connect here instead] / [Reconnect Google Calendar]: step 1, the consent, from this screen. */
    fun connect(launch: suspend (PendingIntent) -> Intent?, reconnect: Boolean = false) {
        if (state.value.busy) return
        busy(true); note(null)
        scope.launch {
            try {
                when (val a = rt.authorizer.authorizeInteractive(launch)) {
                    is AuthResult.Token -> {
                        if (reconnect) {
                            val r = manager.reconnected()
                            if (r is GcalOutcome.Failed) note(GcalText.FAILED)
                        } else {
                            openPicker(changing = false)
                        }
                    }
                    AuthResult.Cancelled -> {} // not an error: the section stays as it was
                    is AuthResult.NeedsResolution -> note(GcalText.NEEDS_BOTH)
                    is AuthResult.Unavailable -> note(GcalText.NO_PLAY)
                }
            } finally {
                busy(false)
            }
        }
    }

    /** [Change calendars]: no new consent (the grant is held), straight to the picker. */
    fun change() {
        if (state.value.busy) return
        busy(true); note(null)
        scope.launch {
            try { openPicker(changing = true) } finally { busy(false) }
        }
    }

    private suspend fun openPicker(changing: Boolean) {
        when (val list = manager.listCalendars()) {
            is GcalResult.Fail -> {
                if (list.err == lk.codegen.risime.data.gcal.GcalErr.REAUTH) { if (changing) manager.markReauth() else note(GcalText.NEEDS_BOTH) } else note(GcalText.LOAD_FAILED)
            }
            is GcalResult.Ok -> {
                val all = list.value
                val mine = if (changing) rt.dao.calendars() else emptyList()
                val read = if (changing) mine.filter { it.read }.map { it.calendarId }.toSet() else GcalLinkManager.defaultRead(all)
                val write = if (changing) mine.firstOrNull { it.write }?.calendarId else GcalLinkManager.defaultWrite(all)
                val mirror = if (changing) manager.link.value.mirror else true
                ui { it.copy(picker = GcalPicker(all, read, write, mirror, changing, wasMirroring = changing && manager.link.value.mirror)) }
            }
        }
    }

    fun toggleRead(id: String) = ui { u ->
        val p = u.picker ?: return@ui u
        val next = if (id in p.read) p.read - id else if (p.read.size < GcalLinkManager.MAX_READ) p.read + id else p.read
        u.copy(picker = p.copy(read = next), note = if (id !in p.read && p.read.size >= GcalLinkManager.MAX_READ) GcalText.AT_MOST else null)
    }

    fun pickWrite(id: String) = ui { u -> u.copy(picker = u.picker?.copy(write = id)) }

    fun setMirror(on: Boolean) = ui { u -> u.copy(picker = u.picker?.copy(mirror = on)) }

    fun cancelPicker() = ui { it.copy(picker = null, note = null) }

    /** The picker's [Save]. */
    fun save() {
        val p = state.value.picker ?: return
        if (state.value.busy) return
        busy(true)
        scope.launch {
            try {
                val sel = GcalSelection(p.calendars, p.read, p.write, p.mirror)
                val account = rt.authorizer.accountName()
                val r = if (p.changing) manager.change(sel, account) else manager.connect(sel, account)
                rt.clearDayCache()
                if (r is GcalOutcome.Failed) {
                    ui { it.copy(note = GcalText.FAILED) }
                } else {
                    ui { it.copy(picker = null, note = null) }
                    // §31.6 mirroring switched off: ask to remove the copies already in Google.
                    if (p.changing && p.wasMirroring && !p.mirror) {
                        val n = rt.dao.copies().count { it.state == GcalCopyState.COPIED }
                        if (n > 0) ui { it.copy(removeCopiesAsk = n) }
                    }
                }
            } finally {
                busy(false)
            }
        }
    }

    fun answerRemoveCopies(remove: Boolean) {
        ui { it.copy(removeCopiesAsk = null) }
        if (!remove) return
        scope.launch { runCatching { rt.copies.removeAll(rt.dao.calendars().filter { it.write }.map { it.calendarId }) } }
    }

    fun askDisconnect() = ui { it.copy(confirmDisconnect = true) }

    fun dismissDisconnect() = ui { it.copy(confirmDisconnect = false) }

    /** The sheet's [Disconnect] with Keep (default) or Remove. */
    fun disconnect(removeCopies: Boolean) {
        ui { it.copy(confirmDisconnect = false) }
        busy(true)
        scope.launch {
            try {
                val r = manager.disconnect(removeCopies)
                rt.clearDayCache()
                if (r is GcalOutcome.Failed) note(GcalText.FAILED) else note(null)
            } finally {
                busy(false)
            }
        }
    }

    fun addAgain() {
        scope.launch { runCatching { rt.copies.addAllAgain() } }
    }
}

/** The "Google Calendar" section of the Calendar skill (Settings → Risi skills). Nothing is composed while the switch is off. */
@Composable
fun GoogleCalendarSection(model: GcalSettingsModel) {
    val ui by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { model.open() }
    if (ui.section == GcalSection.Hidden) return
    // The consent PendingIntent is launched from here (never from the background).
    var wait by remember { mutableStateOf<CompletableDeferred<Intent?>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r -> wait?.complete(r.data) }
    val launch: suspend (PendingIntent) -> Intent? = { pi ->
        val d = CompletableDeferred<Intent?>()
        wait = d
        if (runCatching { launcher.launch(IntentSenderRequest.Builder(pi).build()) }.isSuccess) d.await() else null
    }
    Column(Modifier.fillMaxWidth().testTag("gcal_section"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(GcalText.TITLE, style = MaterialTheme.typography.titleSmall)
        when (val s = ui.section) {
            GcalSection.Hidden -> {}
            GcalSection.NoPlayServices -> {
                Text(GcalText.NO_PLAY, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("gcal_status"))
                Button(onClick = {}, enabled = false, modifier = Modifier.testTag("gcal_connect")) { Text(GcalText.CONNECT) }
            }
            GcalSection.NotConnected -> {
                Text(GcalText.NOT_CONNECTED, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("gcal_status"))
                Button(onClick = { model.connect(launch) }, enabled = !ui.busy, modifier = Modifier.testTag("gcal_connect")) { Text(GcalText.CONNECT) }
            }
            is GcalSection.Connected -> {
                Text(GcalText.connectedOnThisPhone(s.read, s.writeName), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("gcal_status"))
                s.account?.let { Text(GcalText.connectedAs(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("gcal_account")) }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = model::change, enabled = !ui.busy, modifier = Modifier.testTag("gcal_change")) { Text(GcalText.CHANGE) }
                    OutlinedButton(onClick = model::askDisconnect, enabled = !ui.busy, modifier = Modifier.testTag("gcal_disconnect")) { Text(GcalText.DISCONNECT) }
                }
            }
            is GcalSection.OnOther -> {
                Text(GcalText.connectedOn(s.deviceName), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("gcal_status"))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = { model.connect(launch) }, enabled = !ui.busy, modifier = Modifier.testTag("gcal_connect_here")) { Text(GcalText.CONNECT_HERE) }
                    OutlinedButton(onClick = model::askDisconnect, enabled = !ui.busy, modifier = Modifier.testTag("gcal_disconnect")) { Text(GcalText.DISCONNECT) }
                }
            }
            GcalSection.NeedsReconnecting -> {
                Text(GcalText.NEEDS_RECONNECTING, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("gcal_status"))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(onClick = { model.connect(launch, reconnect = true) }, enabled = !ui.busy, modifier = Modifier.testTag("gcal_reconnect")) { Text(GcalText.RECONNECT) }
                    OutlinedButton(onClick = model::askDisconnect, enabled = !ui.busy, modifier = Modifier.testTag("gcal_disconnect")) { Text(GcalText.DISCONNECT) }
                }
            }
            GcalSection.Paused -> {
                Text(GcalText.PAUSED, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("gcal_status"))
                OutlinedButton(onClick = model::askDisconnect, enabled = !ui.busy, modifier = Modifier.testTag("gcal_disconnect")) { Text(GcalText.DISCONNECT) }
            }
        }
        if (ui.busy) CircularProgressIndicator()
        ui.note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("gcal_note")) }
        if (ui.deletedInGoogle > 0 && ui.section is GcalSection.Connected) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("gcal_removed_row")) {
                Text("${GcalText.removedInGoogle(ui.deletedInGoogle)} · ", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = model::addAgain, modifier = Modifier.testTag("gcal_add_again")) { Text("Add again") }
            }
        }
        Text(GcalText.INFO, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("gcal_info"))
    }
    ui.picker?.let { GcalPickerDialog(it, ui.note, ui.busy, model) }
    if (ui.confirmDisconnect) GcalDisconnectDialog(model)
    ui.removeCopiesAsk?.let { n ->
        AlertDialog(
            onDismissRequest = { model.answerRemoveCopies(false) },
            title = { Text(GcalText.removeCopiesTitle(n)) },
            confirmButton = { TextButton(onClick = { model.answerRemoveCopies(true) }, modifier = Modifier.testTag("gcal_remove_copies")) { Text(GcalText.REMOVE_BTN) } },
            dismissButton = { TextButton(onClick = { model.answerRemoveCopies(false) }, modifier = Modifier.testTag("gcal_keep_copies")) { Text(GcalText.KEEP_BTN) } },
        )
    }
}

/** §31.2 step 3 the picker: busy-time checkboxes (at most 10), the one write calendar, the copy switch. */
@Composable
private fun GcalPickerDialog(p: GcalPicker, note: String?, busy: Boolean, model: GcalSettingsModel) {
    AlertDialog(
        onDismissRequest = model::cancelPicker,
        title = { Text(GcalText.TITLE) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).testTag("gcal_picker"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(GcalText.CHECK_FOR_BUSY, style = MaterialTheme.typography.titleSmall)
                p.calendars.forEach { c ->
                    // The box and the name are separate nodes (so each is addressable); the name toggles it too.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(c.id in p.read, onCheckedChange = { model.toggleRead(c.id) }, modifier = Modifier.testTag("gcal_read_${c.name}"))
                        Text(c.name, Modifier.clickable { model.toggleRead(c.id) })
                    }
                }
                note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(GcalText.ADD_EVENTS_TO, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.sm))
                p.calendars.filter { it.canWrite }.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(p.write == c.id, onClick = { model.pickWrite(c.id) }, modifier = Modifier.testTag("gcal_write_${c.name}"))
                        Text(c.name, Modifier.clickable { model.pickWrite(c.id) })
                    }
                }
                Row(Modifier.padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Text(GcalText.COPY_SWITCH, Modifier.weight(1f))
                    Switch(
                        p.mirror && p.write != null, onCheckedChange = model::setMirror, enabled = p.write != null,
                        modifier = Modifier.semantics { contentDescription = GcalText.COPY_SWITCH }.testTag("gcal_mirror_switch"),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = model::save, enabled = !busy, modifier = Modifier.testTag("gcal_picker_save")) { Text(GcalText.SAVE) } },
        dismissButton = { TextButton(onClick = model::cancelPicker, modifier = Modifier.testTag("gcal_picker_cancel")) { Text(GcalText.CANCEL) } },
    )
}

/** §31.8 the confirm sheet: "Events already copied:" Keep (default) / Remove. */
@Composable
private fun GcalDisconnectDialog(model: GcalSettingsModel) {
    var remove by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = model::dismissDisconnect,
        title = { Text(GcalText.DISCONNECT_TITLE) },
        text = {
            Column(Modifier.testTag("gcal_disconnect_sheet"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(GcalText.DISCONNECT_BODY)
                Text(GcalText.COPIED_LABEL, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(!remove, onClick = { remove = false }, modifier = Modifier.testTag("gcal_keep"))
                    Text(GcalText.KEEP, Modifier.clickable { remove = false })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(remove, onClick = { remove = true }, modifier = Modifier.testTag("gcal_remove"))
                    Text(GcalText.REMOVE, Modifier.clickable { remove = true })
                }
            }
        },
        confirmButton = { TextButton(onClick = { model.disconnect(remove) }, modifier = Modifier.testTag("gcal_disconnect_confirm")) { Text(GcalText.DISCONNECT) } },
        dismissButton = { TextButton(onClick = model::dismissDisconnect) { Text(GcalText.CANCEL) } },
    )
}
