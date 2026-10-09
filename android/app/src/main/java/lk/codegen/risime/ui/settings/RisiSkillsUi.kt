package lk.codegen.risime.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.tabs.RisiSkillsStore
import lk.codegen.risime.data.tabs.SkillPermissions
import lk.codegen.risime.data.tabs.SkillTurnOn
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.RisiActivityEntry
import lk.codegen.risime.net.RisiActivityReply
import lk.codegen.risime.net.RisiSkill
import lk.codegen.risime.net.RisiSkillIds
import lk.codegen.risime.net.RisiSkillStates
import lk.codegen.risime.net.RisiUndo
import lk.codegen.risime.net.RisiUndoReply
import lk.codegen.risime.ui.common.RisiTopBar
import lk.codegen.risime.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Settings → Risi skills (§26.2); `?skill=<id>` opens it at that skill. */
const val RISI_SKILLS_ROUTE = "risi_skills"
const val RISI_SKILLS_TITLE = "Risi skills"
const val RISI_SKILLS_BLURB = "Every skill is off until you turn it on. Risi acts only when you ask, in your Risi chat or with @Risi in Official — never in Private."
const val ASK_EACH_TIME = "Ask me each time"
const val ALLOWED = "Allowed"

private val ENTRY_TIME = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)

fun entryTime(ts: String, zone: ZoneId = ZoneId.systemDefault()): String = runCatching { ENTRY_TIME.format(Instant.parse(ts).atZone(zone)) }.getOrDefault(ts)

/** An activity entry's line ("Set an alarm for 05:30, 'Wake up' · Fri 9 Oct, 15:10"). */
fun entryLine(e: RisiActivityEntry, zone: ZoneId = ZoneId.systemDefault()): String = "${e.summary.ifBlank { e.action }} · ${entryTime(e.at, zone)}"

/** [Undo] on an entry: a server/client undo with a token, still available. */
fun entryUndoable(e: RisiActivityEntry, nowMs: Long): Boolean {
    if (e.undo.kind != RisiUndo.UNDO_SERVER && e.undo.kind != RisiUndo.UNDO_CLIENT) return false
    if (e.undoToken.isNullOrEmpty()) return false
    if (e.undo.state != null && e.undo.state != RisiUndo.AVAILABLE && e.undo.state != RisiUndo.FAILED) return false
    val until = e.undo.until?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    return until == null || nowMs < until
}

/** The text of an entry's undo state. */
fun entryUndoState(e: RisiActivityEntry): String? = when (e.undo.state) {
    RisiUndo.PENDING -> "Undoing…"
    RisiUndo.DONE -> "Undone"
    RisiUndo.FAILED -> "Undo failed"
    RisiUndo.EXPIRED -> "Can't be undone any more"
    else -> null
}

/** One skill's activity on screen. */
data class ActivityUi(val loading: Boolean = true, val entries: List<RisiActivityEntry> = emptyList(), val error: String? = null)

/**
 * The screen's state over [RisiSkillsStore]: per-skill refusal notes, the revoke dialog, activity logs.
 * [pendingCount] is "N" in "Also cancel N pending" (this phone's own pending items; null: unknown).
 */
class RisiSkillsModel(
    val store: RisiSkillsStore,
    private val scope: CoroutineScope,
    private val activity: suspend (String) -> ApiResult<RisiActivityReply>,
    private val clearActivity: suspend (String) -> ApiResult<Unit>,
    private val undo: suspend (skillId: String, entryId: String, token: String) -> ApiResult<RisiUndoReply>,
    private val pendingCount: suspend (String) -> Int?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _notes = MutableStateFlow<Map<String, String>>(emptyMap())

    /** skill id → why its switch stayed off (or a failure). */
    val notes: StateFlow<Map<String, String>> = _notes

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy

    data class Revoke(val skill: RisiSkill, val pending: Int?)

    private val _revoke = MutableStateFlow<Revoke?>(null)
    val revoke: StateFlow<Revoke?> = _revoke

    private val _activity = MutableStateFlow<Map<String, ActivityUi>>(emptyMap())
    val activityOf: StateFlow<Map<String, ActivityUi>> = _activity

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    fun nowMs() = now()

    fun load(permissions: SkillPermissions?) {
        scope.launch { store.refresh(permissions) }
    }

    private fun note(id: String, text: String?) = _notes.update { if (text == null) it - id else it + (id to text) }

    private fun busy(id: String, on: Boolean) = _busy.update { if (on) it + id else it - id }

    /** The switch: on runs the §26.2 turn-on flow; off opens the revoke dialog. */
    fun onSwitch(skill: RisiSkill, on: Boolean, permissions: SkillPermissions) {
        if (skill.id in _busy.value) return
        if (!on) {
            scope.launch { _revoke.value = Revoke(skill, runCatching { pendingCount(skill.id) }.getOrNull()) }
            return
        }
        busy(skill.id, true)
        note(skill.id, null)
        scope.launch {
            try {
                when (val r = store.turnOn(skill.id, permissions)) {
                    SkillTurnOn.On -> note(skill.id, null)
                    is SkillTurnOn.Refused -> note(skill.id, r.text)
                    is SkillTurnOn.Failed -> note(skill.id, r.text)
                }
            } finally {
                busy(skill.id, false)
            }
        }
    }

    fun dismissRevoke() {
        _revoke.value = null
    }

    fun confirmRevoke(cancelPending: Boolean) {
        val r = _revoke.value ?: return
        _revoke.value = null
        busy(r.skill.id, true)
        scope.launch {
            try {
                note(r.skill.id, store.revoke(r.skill.id, cancelPending))
            } finally {
                busy(r.skill.id, false)
            }
        }
    }

    fun setMode(skill: RisiSkill, state: String) {
        if (skill.state == state) return
        busy(skill.id, true)
        scope.launch {
            try {
                note(skill.id, store.setMode(skill.id, state))
            } finally {
                busy(skill.id, false)
            }
        }
    }

    fun loadActivity(id: String) {
        _activity.update { it + (id to ActivityUi(loading = true, entries = it[id]?.entries.orEmpty())) }
        scope.launch {
            val ui = when (val r = activity(id)) {
                is ApiResult.Ok -> ActivityUi(loading = false, entries = r.value.entries)
                else -> ActivityUi(loading = false, error = risiErrorMessage(r))
            }
            _activity.update { it + (id to ui) }
        }
    }

    fun clear(id: String) {
        scope.launch {
            if (clearActivity(id) is ApiResult.Ok) _activity.update { it + (id to ActivityUi(loading = false)) } else _toast.value = "Couldn't clear it. Try again."
        }
    }

    fun undo(e: RisiActivityEntry) {
        val token = e.undoToken ?: return
        scope.launch {
            val r = undo(e.skillId, e.entryId, token)
            _toast.value = undoResultText(r)
            if (r is ApiResult.Ok) _activity.update { m -> m + (e.skillId to (m[e.skillId] ?: ActivityUi(false)).let { a -> a.copy(entries = a.entries.map { if (it.entryId == e.entryId) r.value.entry else it }) }) }
        }
    }

    fun toastShown() {
        _toast.value = null
    }
}

/**
 * Bridges [SkillPermissions.request] to the screen's launchers: the runtime permission dialog and
 * the "Alarms & reminders" page. Only works while the screen is showing (never from the background).
 */
class ScreenPermissionAsker {
    @Volatile var runtime: (suspend (Array<String>) -> Unit)? = null

    @Volatile var exactAlarm: (suspend () -> Unit)? = null
}

@Composable
fun RisiSkillsRoute(c: AppContainer, skillId: String?, onBack: () -> Unit) {
    val asker = remember { ScreenPermissionAsker() }
    val permissions = remember { c.skillPermissions(asker) }
    val model = remember {
        RisiSkillsModel(
            c.risiSkillsStore, c.scope,
            activity = { c.api.risiSkillActivity(it) },
            clearActivity = { c.api.clearRisiSkillActivity(it) },
            undo = { s, e, t -> c.api.undoRisiSkillEntry(s, e, t) },
            pendingCount = { c.pendingSkillItems(it) },
        )
    }
    var runtimeWait by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { runtimeWait?.complete(Unit) }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { runtimeWait?.complete(Unit) }
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        asker.runtime = { perms ->
            val d = CompletableDeferred<Unit>()
            runtimeWait = d
            runtimeLauncher.launch(perms)
            d.await()
        }
        asker.exactAlarm = {
            val d = CompletableDeferred<Unit>()
            runtimeWait = d
            val ok = runCatching {
                settingsLauncher.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
            }.isSuccess
            if (ok) d.await()
        }
        onDispose {
            asker.runtime = null
            asker.exactAlarm = null
        }
    }
    LaunchedEffect(Unit) { model.load(permissions) }
    RisiSkillsScreen(model, permissions, skillId, onBack, calendar = c.calendarPort, openSettings = {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }, openClock = {
        runCatching { ctx.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    })
}

@Composable
fun RisiSkillsScreen(
    model: RisiSkillsModel,
    permissions: SkillPermissions,
    focus: String?,
    onBack: () -> Unit,
    openSettings: () -> Unit = {},
    openClock: () -> Unit = {},
    /** P0 Calendar → Details: the calendar Risi adds to (changeable); null: not shown. */
    calendar: lk.codegen.risime.data.tabs.RisiCalendarPort? = null,
) {
    val skills by model.store.skills.collectAsStateWithLifecycle()
    val error by model.store.error.collectAsStateWithLifecycle()
    val notes by model.notes.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val revoke by model.revoke.collectAsStateWithLifecycle()
    val activity by model.activityOf.collectAsStateWithLifecycle()
    val toast by model.toast.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(setOfNotNull(focus)) }
    val listState = rememberLazyListState()
    LaunchedEffect(skills, focus) {
        val i = skills?.indexOfFirst { it.id == focus } ?: -1
        if (i >= 0) listState.scrollToItem(i + 1)
    }
    Scaffold(topBar = { RisiTopBar(title = RISI_SKILLS_TITLE, onBack = onBack) }, contentWindowInsets = WindowInsets(0)) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).testTag("risi_skills"),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(RISI_SKILLS_BLURB, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("risi_skills_error")) }
                    toast?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("risi_skills_toast"))
                        LaunchedEffect(it) { kotlinx.coroutines.delay(4_000); model.toastShown() }
                    }
                }
            }
            val list = skills
            if (list == null) {
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
            } else {
                items(list, key = { it.id }) { s ->
                    SkillCard(
                        s, open = s.id in expanded, busy = s.id in busy, note = notes[s.id], activity = activity[s.id], nowMs = model.nowMs(),
                        onToggleOpen = { expanded = if (s.id in expanded) expanded - s.id else expanded + s.id },
                        onSwitch = { on -> model.onSwitch(s, on, permissions) },
                        onMode = { model.setMode(s, it) },
                        onActivity = { model.loadActivity(s.id) },
                        onUndo = model::undo,
                        onClear = { model.clear(s.id) },
                        openSettings = openSettings, openClock = openClock,
                        calendar = calendar.takeIf { s.id == RisiSkillIds.CALENDAR },
                    )
                }
            }
        }
    }
    revoke?.let { r -> RevokeDialog(r, onConfirm = model::confirmRevoke, onDismiss = model::dismissRevoke) }
}

@Composable
private fun SkillCard(
    s: RisiSkill,
    open: Boolean,
    busy: Boolean,
    note: String?,
    activity: ActivityUi?,
    nowMs: Long,
    onToggleOpen: () -> Unit,
    onSwitch: (Boolean) -> Unit,
    onMode: (String) -> Unit,
    onActivity: () -> Unit,
    onUndo: (RisiActivityEntry) -> Unit,
    onClear: () -> Unit,
    openSettings: () -> Unit,
    openClock: () -> Unit,
    calendar: lk.codegen.risime.data.tabs.RisiCalendarPort? = null,
) {
    Card(Modifier.fillMaxWidth().testTag("risi_skill_${s.id}")) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(onClick = onToggleOpen)) {
                    Text(s.title.ifBlank { s.id }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            !s.available -> "Coming later"
                            s.state == RisiSkillStates.ALLOWED -> ALLOWED
                            s.state == RisiSkillStates.ASK -> ASK_EACH_TIME
                            else -> "Off"
                        },
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("risi_skill_state_${s.id}"),
                    )
                }
                if (busy) CircularProgressIndicator(Modifier.padding(end = Spacing.sm))
                Switch(
                    checked = s.on,
                    onCheckedChange = onSwitch,
                    enabled = s.available && !busy,
                    modifier = Modifier.semantics { contentDescription = "${s.title} skill" }.testTag("risi_skill_switch_${s.id}"),
                )
            }
            Text(s.description, style = MaterialTheme.typography.bodyMedium)
            note?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("risi_skill_note_${s.id}"))
                OutlinedButton(onClick = openSettings) { Text("Open settings") }
            }
            if (!open) {
                TextButton(onClick = onToggleOpen) { Text("Details") }
                return@Column
            }
            Bullets("Can", s.can)
            Bullets("Cannot", s.cannot)
            if (s.permissions.isNotEmpty()) {
                Text("Permissions", style = MaterialTheme.typography.titleSmall)
                s.permissions.forEach { p ->
                    Column(Modifier.testTag("risi_skill_perm")) {
                        Text(p.label.ifBlank { p.name }, style = MaterialTheme.typography.bodyMedium)
                        Text(p.name + if (p.runtime) " (asked on this phone)" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            s.client?.let { Text("This phone: " + permissionText(it.permission), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("risi_skill_client_${s.id}")) }
            if (calendar != null) {
                HorizontalDivider()
                CalendarSourcesBlock(calendar)
            }
            if (calendar != null && s.on) {
                HorizontalDivider()
                CalendarChoiceRow(calendar)
            }
            if (s.on) {
                HorizontalDivider()
                if (s.allowsAllowed) {
                    ModeRow(ASK_EACH_TIME, "Risi shows a card and waits for your Add.", s.state == RisiSkillStates.ASK, !busy) { onMode(RisiSkillStates.ASK) }
                    ModeRow(ALLOWED, "Risi does it for you, without a card, when you ask for something only you are affected by.", s.state == RisiSkillStates.ALLOWED, !busy) { onMode(RisiSkillStates.ALLOWED) }
                } else {
                    Text("Always asks you first (it affects other people).", style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Activity", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onActivity, modifier = Modifier.testTag("risi_skill_activity_${s.id}")) { Text(if (activity == null) "Show" else "Refresh") }
            }
            activity?.let { a ->
                if (a.loading) CircularProgressIndicator()
                a.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!a.loading && a.error == null && a.entries.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
                a.entries.forEach { e ->
                    Column(Modifier.testTag("risi_activity_entry")) {
                        Text(entryLine(e), style = MaterialTheme.typography.bodyMedium)
                        entryUndoState(e)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (entryUndoable(e, nowMs)) OutlinedButton(onClick = { onUndo(e) }, modifier = Modifier.testTag("risi_activity_undo")) { Text("Undo") }
                        if (e.undo.kind == RisiUndo.UNDO_MANUAL) {
                            Text(e.undo.hint ?: "Remove it yourself", style = MaterialTheme.typography.bodySmall)
                            if (e.skillId == RisiSkillIds.ALARM) TextButton(onClick = openClock) { Text("Open Clock") }
                        }
                    }
                }
                if (a.entries.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear activity") }
            }
        }
    }
}

@Composable
private fun Bullets(title: String, items: List<String>) {
    if (items.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall)
    items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun ModeRow(title: String, sub: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled = enabled, onClick = onClick).testTag("risi_mode_$title"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = Spacing.sm)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun permissionText(p: String): String = when (p) {
    "granted" -> "permission granted"
    "denied" -> "permission refused"
    "not_asked" -> "permission not asked yet"
    "not_needed" -> "no permission needed"
    "unsupported" -> "not supported"
    else -> "unknown"
}

/** §26.5 the revoke dialog: what stays, and "Also cancel N pending" where there can be pending items. */
@Composable
private fun RevokeDialog(r: RisiSkillsModel.Revoke, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var cancel by remember { mutableStateOf(false) }
    val pendingKind = r.skill.id == RisiSkillIds.REMINDERS || r.skill.id == RisiSkillIds.SCHEDULED_MESSAGES
    val label = revokeCancelLabel(r.skill.id, r.pending)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Turn off ${r.skill.title}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("Risi won't be able to use it until you turn it back on. Things it already made stay, and the activity log stays.")
                if (pendingKind && label != null) {
                    Row(Modifier.fillMaxWidth().clickable { cancel = !cancel }.testTag("risi_revoke_cancel_pending"), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(cancel, onCheckedChange = { cancel = it })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(cancel) }, modifier = Modifier.testTag("risi_revoke_confirm")) { Text("Turn off") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep on") } },
    )
}

/** "Also cancel 3 pending" (a known count), "Also cancel pending reminders" (the server's), null for none. */
fun revokeCancelLabel(skillId: String, pending: Int?): String? = when {
    skillId == RisiSkillIds.SCHEDULED_MESSAGES -> if (pending == null || pending > 0) "Also cancel ${pending ?: "the"} pending" + if (pending == 1) " message" else " messages" else null
    skillId == RisiSkillIds.REMINDERS -> "Also cancel pending reminders"
    else -> null
}

/**
 * P0 2026-10-09 Calendar → Details: what Risi can read on this phone: the permission, each calendar (name,
 * account, events in the next 7 days, hidden / sync off) and why a check can't be trusted.
 */
@Composable
private fun CalendarSourcesBlock(port: lk.codegen.risime.data.tabs.RisiCalendarPort) {
    var loaded by remember { mutableStateOf(false) }
    var ov by remember { mutableStateOf<lk.codegen.risime.data.tabs.CalendarOverview?>(null) }
    LaunchedEffect(Unit) { ov = runCatching { port.overview() }.getOrNull(); loaded = true }
    Column(Modifier.testTag("risi_calendar_sources"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text("What Risi can read", style = MaterialTheme.typography.titleSmall)
        val o = ov
        when {
            !loaded -> Text("Reading the phone's calendars…", style = MaterialTheme.typography.bodySmall)
            o == null -> Text("Phone calendar: calendar permission is off on this phone, so Risi can't read it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("risi_calendar_sources_permission"))
            else -> {
                Text("Phone calendar: permission on", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("risi_calendar_sources_permission"))
                if (o.calendars.isEmpty()) Text("No calendars on this phone.", style = MaterialTheme.typography.bodySmall)
                o.calendars.forEach { (c, n) ->
                    val flags = listOfNotNull("hidden".takeIf { !c.visible }, "sync off".takeIf { !c.syncEvents }).joinToString(", ")
                    Column(Modifier.testTag("risi_calendar_source_row")) {
                        Text(lk.codegen.risime.data.tabs.CalendarRead.nameOf(c), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            lk.codegen.risime.data.tabs.CalendarSelection.label(c) + " · " + (if (n == 1) "1 event" else "$n events") + " in the next 7 days" + if (flags.isNotEmpty()) " · $flags" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!o.readOk) Text(lk.codegen.risime.data.tabs.CalendarRead.reasonText(o.reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("risi_calendar_sources_reason"))
            }
        }
    }
}

/** Calendar → Details: "Adds to: harsha@… · Google [Change]" (kept on this phone; the contract has no field for it). */
@Composable
private fun CalendarChoiceRow(port: lk.codegen.risime.data.tabs.RisiCalendarPort) {
    val chosenId by port.chosenId.collectAsStateWithLifecycle()
    var chosen by remember { mutableStateOf<lk.codegen.risime.data.tabs.PhoneCalendarInfo?>(null) }
    var options by remember { mutableStateOf<List<lk.codegen.risime.data.tabs.PhoneCalendarInfo>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(chosenId) { chosen = port.chosen() }
    Column(Modifier.testTag("risi_calendar_settings"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text("Calendar", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(chosen?.let { lk.codegen.risime.data.tabs.CalendarSelection.label(it) } ?: "Not chosen yet: Risi asks the first time it adds an event.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("risi_calendar_settings_label"))
                chosen?.let { lk.codegen.risime.data.tabs.CalendarSelection.subLabel(it) }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = {
                scope.launch {
                    if (!port.hasPermission()) { note = "Calendar permission is off on this phone."; return@launch }
                    val o = port.options()
                    if (o.isEmpty()) note = "No writable Google calendar on this phone. Add your Google account in Android Settings → Accounts." else options = o
                }
            }, modifier = Modifier.testTag("risi_calendar_settings_change")) { Text(if (chosen == null) "Choose" else "Change") }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    options?.let { o ->
        lk.codegen.risime.ui.tabs.CalendarPickerDialog(o, chosen?.id, onPick = { c -> options = null; scope.launch { port.choose(c.id); chosen = c; note = null } }, onDismiss = { options = null })
    }
}
