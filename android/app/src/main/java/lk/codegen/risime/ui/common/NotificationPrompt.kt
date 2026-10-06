package lk.codegen.risime.ui.common

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.push.shouldPromptNotifications

/** Android 13+: explain, then ask for POST_NOTIFICATIONS once after sign-in. A "no" is respected. */
@Composable
fun NotificationPermissionPrompt(c: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        show = shouldPromptNotifications(Build.VERSION.SDK_INT, granted, c.sessionStore.notificationsPrompted(), signedIn = true)
    }
    if (!show) return
    fun done(ask: Boolean) {
        show = false
        scope.launch { c.sessionStore.setNotificationsPrompted() }
        if (ask && Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    AlertDialog(
        onDismissRequest = { done(false) },
        title = { Text("Get notified of new messages?") },
        text = {
            Text(
                "RisiMe can tell you when friends message you or send a friend request while the app is closed. " +
                    "The notification is built on your phone; message text never goes through Google.",
            )
        },
        confirmButton = { TextButton(onClick = { done(true) }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = { done(false) }) { Text("Not now") } },
    )
}
