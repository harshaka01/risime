package lk.codegen.risime.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.net.ApiResult

/** §16.1: the DM header's call button state, refetched on chat open and on `mls_membership`. */
class CallActions(private val c: AppContainer, private val scope: CoroutineScope, private val conversationId: String) {
    private val _ready = MutableStateFlow(false)
    val callsReady: StateFlow<Boolean> = _ready

    fun refresh() {
        scope.launch {
            val r = c.api.mlsGroup(conversationId)
            if (r is ApiResult.Ok) _ready.value = r.value.e2ee && r.value.callsReady
        }
    }

    /** Null = the button works; otherwise what a tap explains (§16.1 UI). */
    fun blockedText(encrypted: Boolean, ready: Boolean, peerName: String): String? = callBlockedText(
        thisPhone = runCatching { c.calls.unsupportedReason() }.getOrElse { "Calls aren't supported on this phone" },
        encrypted = encrypted, ready = ready, peerName = peerName,
    )

    fun start() {
        c.calls.placeCall(conversationId)
        c.calls.openCallScreen()
    }
}

/** The rule order of §16.1: this phone first, then e2ee, then the peer's readiness. */
fun callBlockedText(thisPhone: String?, encrypted: Boolean, ready: Boolean, peerName: String): String? = when {
    thisPhone != null -> if (thisPhone.startsWith("Calls aren't supported")) thisPhone else "Update RisiMe on this phone to make calls ($thisPhone)"
    !encrypted -> "Calls need an end-to-end encrypted chat."
    !ready -> "$peerName needs to update the app to receive calls"
    else -> null
}

/** The call button: asks RECORD_AUDIO on the first call, explains when calls can't work yet. */
@Composable
fun CallHeaderButton(blocked: String?, onBlocked: (String) -> Unit, onCall: () -> Unit) {
    val ctx = LocalContext.current
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) onCall() else onBlocked("RisiMe needs the microphone for calls")
    }
    IconButton(onClick = {
        when {
            blocked != null -> onBlocked(blocked)
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> onCall()
            else -> mic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }) {
        Icon(Icons.Default.Call, if (blocked == null) "Voice call" else "Voice call (unavailable)", tint = if (blocked == null) androidx.compose.material3.LocalContentColor.current else Color.Gray)
    }
}
