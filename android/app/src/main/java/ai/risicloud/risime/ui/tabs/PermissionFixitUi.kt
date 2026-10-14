package ai.risicloud.risime.ui.tabs

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.data.tabs.PermissionFixit
import ai.risicloud.risime.net.RisiMeta
import ai.risicloud.risime.ui.theme.Spacing

/*
 * Proposal 2026-10-14-permission-fixit §43.5: the one fix-it button. It shows the Android prompt from this screen;
 * when Android blocks the prompt ("don't ask again"), it opens RisiMe's app-permissions page with the hint
 * "Tap Calendar → Allow" and checks again when the user comes back. Never from the background.
 */

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Whether every Android permission of [need] is granted now (notifications before API 33: the app's switch). */
fun fixitGranted(context: Context, need: PermissionFixit.Need): Boolean {
    if (need.android.isEmpty()) return true
    if (Build.VERSION.SDK_INT < need.minSdk) {
        return need.id != ai.risicloud.risime.net.RisiFixit143.NOTIFICATIONS || NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    return need.android.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}

/**
 * [Allow calendar] / [Allow contacts] / …: [onResult] gets true once Android says granted, false when the user
 * refused the prompt or came back from the settings page without allowing.
 */
@Composable
fun AllowPermissionButton(
    permission: String?,
    askedBefore: (String) -> Boolean,
    markAsked: (String) -> Unit,
    onResult: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String = PermissionFixit.buttonLabel(permission),
    tag: String = "risi_fixit_allow",
) {
    val need = PermissionFixit.need(permission) ?: return
    val context = LocalContext.current
    var waitingSettings by rememberSaveable(permission) { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        markAsked(need.id)
        onResult(fixitGranted(context, need))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (waitingSettings) {
            waitingSettings = false
            onResult(fixitGranted(context, need))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Button(
            onClick = {
                val activity = context.activity()
                val rationale = activity != null && need.android.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
                when (PermissionFixit.step(need, fixitGranted(context, need), askedBefore(need.id), rationale, Build.VERSION.SDK_INT)) {
                    PermissionFixit.Step.ALREADY -> onResult(true)
                    PermissionFixit.Step.PROMPT -> runCatching { launcher.launch(need.android.toTypedArray()) }.onFailure { onResult(fixitGranted(context, need)) }
                    PermissionFixit.Step.SETTINGS -> {
                        waitingSettings = true
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure { waitingSettings = false; onResult(false) }
                    }
                }
            },
            modifier = modifier.testTag(tag),
        ) { Text(label, maxLines = 1) }
        if (waitingSettings) {
            Text(PermissionFixit.settingsHint(permission), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("risi_fixit_hint"))
        }
    }
}

/** §43.5 the morning brief on a phone that never asked: one line on why and [Allow calendar] (turns the skill on). */
@Composable
internal fun BriefCalendarAsk(ctx: RisiCardContext) {
    var show by remember { mutableStateOf(ctx.host.calendarNeverAsked()) }
    var done by remember { mutableStateOf<Boolean?>(null) }
    when {
        done == true -> Text(PermissionFixit.grantedLine(ai.risicloud.risime.net.RisiFixit143.CALENDAR), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("risi_fixit_state"))
        done == false -> Text(PermissionFixit.NOT_ALLOWED, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("risi_fixit_state"))
        show -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.testTag("risi_brief_calendar_ask")) {
            Text(PermissionFixit.whyLine(ai.risicloud.risime.net.RisiFixit143.CALENDAR, "to put today's events in your brief"), style = MaterialTheme.typography.bodySmall)
            AllowPermissionButton(
                ai.risicloud.risime.net.RisiFixit143.CALENDAR, askedBefore = ctx.host::fixitAskedBefore, markAsked = ctx.host::fixitMarkAsked,
                onResult = { granted ->
                    show = false
                    done = granted
                    if (granted) ctx.host.reportCalendarGranted(turnOn = true)
                },
            )
        }
    }
}

/** §43.2 `needs_permission`: the sentence, [Allow calendar], then "✓ Risi can see your calendar" or "Not allowed". */
@Composable
internal fun NeedsPermissionCard(row: MessageEntity, r: RisiMeta, ctx: RisiCardContext) {
    val answered = PermissionFixit.answered(r, ctx.messages, ctx.host.me)
    var local by remember(r.resumeId) { mutableStateOf<String?>(null) }
    val state = answered ?: local
    Column(Modifier.testTag("risi_fixit_card"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(r.text ?: row.body, style = MaterialTheme.typography.bodyLarge)
        PermissionFixit.stateLine(r, state)?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("risi_fixit_state"))
        }
        if (state == null && !ctx.readOnly && PermissionFixit.canAct(r, ctx.host.me, ctx.nowMs, answered)) {
            if (PermissionFixit.need(r.permission) == null) {
                Button(onClick = { ctx.host.openSkills(r.skillId) }, modifier = Modifier.testTag("risi_fixit_allow")) { Text(PermissionFixit.OPEN_SKILLS) }
            } else {
                AllowPermissionButton(
                    r.permission, askedBefore = ctx.host::fixitAskedBefore, markAsked = ctx.host::fixitMarkAsked,
                    onResult = { granted ->
                        local = if (granted) ai.risicloud.risime.net.RisiFixit143.ACTION_GRANTED else ai.risicloud.risime.net.RisiFixit143.ACTION_DECLINED
                        ctx.host.answerFixit(r, granted)
                    },
                )
            }
        }
    }
}
