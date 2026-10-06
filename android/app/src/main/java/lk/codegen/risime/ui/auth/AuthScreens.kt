package lk.codegen.risime.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.BuildConfig
import lk.codegen.risime.data.auth.AuthOverride
import lk.codegen.risime.data.auth.BlockKind
import lk.codegen.risime.data.auth.Blocked
import lk.codegen.risime.data.auth.SignInChoice
import lk.codegen.risime.data.auth.SignInOption
import lk.codegen.risime.data.auth.signInChoice
import lk.codegen.risime.ui.common.ErrorState
import lk.codegen.risime.ui.theme.Spacing
import lk.codegen.risime.ui.theme.WordmarkStyle

@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
    ) { content() }
}

/** Escape screens sign out without deleting chats (P0 nightly.10: people tap through to get unstuck). */
const val SIGN_OUT_KEEPS_CHATS = "Sign out (keeps your chats)"
const val SIGN_IN_ANOTHER_ACCOUNT = "Sign in with another account"
const val NOT_ALLOWLISTED_TEXT =
    "This RisiCloud account isn't on the RisiMe pilot list. Sign in with the account you were invited with."

/** "RisiMe is locked — Unlock": one fingerprint per process start (decision 014). */
@Composable
fun LockedScreen(authUi: AuthUi) {
    val busy by authUi.busy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { authUi.unlock() }
    CenteredColumn {
        Text("RisiMe", style = WordmarkStyle, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
        Text("RisiMe is locked", style = MaterialTheme.typography.titleMedium)
        Text(
            "Unlock with your fingerprint to continue.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = authUi::unlock, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Unlock") }
        TextButton(onClick = authUi::signOutLocked, enabled = !busy) { Text(SIGN_OUT_KEEPS_CHATS) }
    }
}

/** 403 not_allowlisted / 409 identity_conflict: the server's message, verbatim (§6.1). */
@Composable
fun BlockedScreen(blocked: Blocked, c: AppContainer, authUi: AuthUi) {
    val scope = rememberCoroutineScope()
    CenteredColumn {
        Text(
            if (blocked.kind == BlockKind.NOT_ALLOWLISTED) "Not on the RisiMe allowlist" else "Account conflict",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(if (blocked.kind == BlockKind.NOT_ALLOWLISTED) NOT_ALLOWLISTED_TEXT else blocked.message, textAlign = TextAlign.Center)
        Text(
            if (blocked.kind == BlockKind.NOT_ALLOWLISTED) {
                "Your chats on this phone are kept."
            } else {
                "An admin must re-bind this phone number to your account."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (blocked.kind == BlockKind.NOT_ALLOWLISTED) {
            Button(onClick = {
                scope.launch {
                    c.signOutKeepChats(notice = null)
                    val choice = signInChoice(c.api.authConfig(), BuildConfig.DEBUG, AuthOverride.FORCE_OIDC)
                    if (choice is SignInChoice.Options && SignInOption.OIDC in choice.options) authUi.signIn(choice.config, fresh = true)
                }
            }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(SIGN_IN_ANOTHER_ACCOUNT) }
        }
        OutlinedButton(onClick = { scope.launch { c.signOutKeepChats() } }, modifier = Modifier.fillMaxWidth()) { Text(SIGN_OUT_KEEPS_CHATS) }
    }
}

/** RisiCloud button + its errors, for the login screen. */
@Composable
fun RisiCloudSignIn(choice: SignInChoice.Options, authUi: AuthUi) {
    val busy by authUi.busy.collectAsStateWithLifecycle()
    val error by authUi.error.collectAsStateWithLifecycle()
    Button(
        onClick = { authUi.signIn(choice.config) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text(if (busy) "Signing in…" else "Sign in with RisiCloud") }
    error?.let { ErrorState(it) }
}
