package lk.codegen.risime.ui.settings

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.push.HealthCheck
import lk.codegen.risime.push.HealthFix
import lk.codegen.risime.push.NotificationHealthProbe
import lk.codegen.risime.push.oemBrand
import lk.codegen.risime.push.oemGuidance
import lk.codegen.risime.push.shouldShowHealthAfterUpdate
import lk.codegen.risime.ui.common.RisiTopBar
import lk.codegen.risime.ui.common.SectionHeader
import lk.codegen.risime.ui.theme.Spacing

const val NOTIFICATION_HEALTH_TITLE = "Notifications health"

/** Settings → Notifications health: each row ✓/✗, a ✗ row opens the page that fixes it. */
@Composable
fun NotificationHealthScreen(c: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val probe = remember { NotificationHealthProbe(context.applicationContext, c) }
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<HealthCheck>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var retrying by remember { mutableStateOf(false) }
    // Re-checked every time the user comes back from a settings page.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    LaunchedEffect(refresh) { rows = probe.checks() }
    Scaffold(
        topBar = { RisiTopBar(title = NOTIFICATION_HEALTH_TITLE, onBack = onBack) },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                "What RisiMe needs so messages and calls reach you while the app is closed or the phone is locked.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            rows?.forEach { r ->
                HealthRowView(r, retrying) {
                    val fix = r.fix ?: return@HealthRowView
                    if (fix == HealthFix.RETRY_PUSH) {
                        retrying = true
                        scope.launch { probe.retryPush(); retrying = false; refresh++ }
                    } else {
                        probe.open(fix)
                    }
                }
                HorizontalDivider()
            }
            SectionHeader("Autostart and background")
            Text(oemGuidance(oemBrand(Build.MANUFACTURER, Build.BRAND)), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { probe.openAutostart() }, modifier = Modifier.fillMaxWidth()) { Text("Open phone settings") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HealthRowView(r: HealthCheck, retrying: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (r.ok) "✓" else "✗",
            color = if (r.ok) Color(0xFF1B8A3E) else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(28.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(r.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(r.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (r.fix != null) {
                FilledTonalButton(onClick = onFix, enabled = !(r.fix == HealthFix.RETRY_PUSH && retrying)) {
                    Text(if (r.fix == HealthFix.RETRY_PUSH) (if (retrying) "Registering…" else "Retry") else "Fix")
                }
            }
        }
    }
}

private fun healthPrefs(context: Context) = context.getSharedPreferences("risime_push", Context.MODE_PRIVATE)

/**
 * Once after an update (per versionCode): when a row is ✗, open the health screen. Recorded either
 * way, so it never shows twice for the same version and never loops.
 */
@Composable
fun NotificationHealthAfterUpdate(c: AppContainer, open: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val prefs = healthPrefs(context)
        val version = NotificationHealthProbe.versionCode
        val last = prefs.getInt("health_shown_for", -1).takeIf { it >= 0 }
        if (last == version) return@LaunchedEffect
        // A fresh install isn't an update (the notification prompt comes first); debug builds never
        // auto-open it, so the device-test scripts' screens stay predictable (Settings has it).
        val pi = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        if (lk.codegen.risime.BuildConfig.DEBUG || pi == null || pi.firstInstallTime == pi.lastUpdateTime) {
            prefs.edit().putInt("health_shown_for", version).apply()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(3_000) // after the sign-in prompts settle
        val checks = runCatching { NotificationHealthProbe(context.applicationContext, c).checks() }.getOrNull() ?: return@LaunchedEffect
        val show = shouldShowHealthAfterUpdate(version, last, checks)
        prefs.edit().putInt("health_shown_for", version).apply()
        if (show) open()
    }
}
