package lk.codegen.risime.ui.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardActions
import lk.codegen.risime.data.auth.SignInChoice
import lk.codegen.risime.data.auth.SignInOption
import lk.codegen.risime.ui.auth.AuthUi
import lk.codegen.risime.ui.auth.RisiCloudSignIn
import lk.codegen.risime.ui.common.ErrorState
import lk.codegen.risime.ui.phone.CodeLimits
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.platform.testTag
import lk.codegen.risime.ui.theme.Spacing
import lk.codegen.risime.ui.theme.WordmarkStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LoginFlow(vm: LoginViewModel, authUi: AuthUi, notice: String?) {
    val s by vm.state.collectAsStateWithLifecycle()
    // The code cooldown deadline (wall clock) survives rotation and process death.
    var savedUntil by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(vm) {
        vm.restore(savedUntil)
        vm.state.collect { savedUntil = it.resendUntilMs }
    }
    if (s.codeSentTo == null) LoginScreen(s, vm, authUi, notice) else OtpScreen(s, vm)
}

@Composable
private fun Wordmark() {
    Text("RisiMe", style = WordmarkStyle, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
    Text(
        "Talk. Connect. Act.",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FormColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) { content() }
}

@Composable
private fun LoginScreen(s: LoginUiState, vm: LoginViewModel, authUi: AuthUi, notice: String?) {
    FormColumn {
        Wordmark()
        Spacer(Modifier.height(24.dp))
        // Every build: point the app at a server before signing in (release default: risicloud.ai).
        var editServer by rememberSaveable { mutableStateOf(false) }
        if (editServer || s.serverError != null) {
            OutlinedTextField(
                value = s.serverUrl,
                onValueChange = vm::onServerUrl,
                label = { Text("Server URL") },
                singleLine = true,
                isError = s.serverError != null,
                supportingText = s.serverError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    vm.applyServer()
                    editServer = false
                }),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = {
                vm.applyServer()
                editServer = false
            }) { Text("Use this server") }
        } else {
            TextButton(onClick = { editServer = true }) {
                Text("Server: ${s.serverUrl} · Change", style = MaterialTheme.typography.bodySmall)
            }
        }
        notice?.let { Text(it, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        when (val choice = s.choice) {
            null -> Text("Checking the server…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            is SignInChoice.Unreachable -> ErrorState(choice.message, onRetry = vm::retryChoice)
            is SignInChoice.Options -> {
                val oidc = SignInOption.OIDC in choice.options
                val dev = SignInOption.DEV in choice.options
                if (oidc) RisiCloudSignIn(choice, authUi)
                if (dev && oidc) {
                    TextButton(onClick = vm::toggleDevForm) { Text("Developer sign-in (OTP)") }
                }
                if (dev && (!oidc || s.devFormOpen)) DevLoginForm(s, vm)
            }
        }
    }
}

/** Phone + email + emailed code; only against DEV_LOCAL_AUTH servers (§6.1). */
@Composable
private fun DevLoginForm(s: LoginUiState, vm: LoginViewModel) {
    OutlinedTextField(
        value = s.phone,
        onValueChange = vm::onPhone,
        label = { Text("Phone number") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = s.email,
        onValueChange = vm::onEmail,
        label = { Text("Work email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    Button(onClick = vm::requestCode, enabled = s.canSend, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(
            when {
                s.busy -> "Sending…"
                s.resendInSec > 0 -> "Send code in ${CodeLimits.countdown(s.resendInSec)}"
                else -> "Send code"
            },
        )
    }
    Text(
        "We email a 6-digit code if this phone and email are on the RisiMe allowlist.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun OtpScreen(s: LoginUiState, vm: LoginViewModel) {
    BackHandler(onBack = vm::back)
    OtpContent(s, vm::onCode, vm::verify, vm::requestCode, vm::back)
}

const val OTP_VERIFY_TAG = "otp_verify"
const val OTP_RESEND_TAG = "otp_resend"

/** The dev OTP step (stateless, UI-tested): Verify only once per request, resend after the server's cooldown. */
@Composable
fun OtpContent(s: LoginUiState, onCode: (String) -> Unit, onVerify: () -> Unit, onResend: () -> Unit, onBack: () -> Unit) {
    FormColumn {
        Wordmark()
        Spacer(Modifier.height(24.dp))
        Text("Enter the code sent for ${s.codeSentTo}", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        OutlinedTextField(
            value = s.code,
            onValueChange = onCode,
            label = { Text("6-digit code") },
            singleLine = true,
            enabled = !s.busy && !s.codeLocked,
            textStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 6.sp, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (s.error == null && !s.codeLocked && s.attemptsLeft == 1) {
            Text("1 attempt left", color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = onVerify, enabled = s.canVerify, modifier = Modifier.fillMaxWidth().height(52.dp).testTag(OTP_VERIFY_TAG)) {
            Text(if (s.busy) "Verifying…" else "Verify")
        }
        TextButton(onClick = onResend, enabled = s.canSend, modifier = Modifier.testTag(OTP_RESEND_TAG)) {
            Text(if (s.resendInSec > 0) "Resend in ${CodeLimits.countdown(s.resendInSec)}" else "Resend code")
        }
        TextButton(onClick = onBack) { Text("Change phone or email") }
    }
}
