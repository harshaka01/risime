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
import lk.codegen.risime.ui.theme.Spacing
import lk.codegen.risime.ui.theme.WordmarkStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LoginFlow(vm: LoginViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    if (s.codeSentTo == null) LoginScreen(s, vm) else OtpScreen(s, vm)
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
private fun LoginScreen(s: LoginUiState, vm: LoginViewModel) {
    FormColumn {
        Wordmark()
        Spacer(Modifier.height(24.dp))
        // Every build: release users point the app at the tailnet HTTPS URL before logging in.
        var editServer by rememberSaveable { mutableStateOf(false) }
        if (editServer || s.serverError != null) {
            OutlinedTextField(
                value = s.serverUrl,
                onValueChange = vm::onServerUrl,
                label = { Text("Server URL") },
                singleLine = true,
                isError = s.serverError != null,
                supportingText = s.serverError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            TextButton(onClick = { editServer = true }) {
                Text("Server: ${s.serverUrl} · Change", style = MaterialTheme.typography.bodySmall)
            }
        }
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
        Button(onClick = vm::requestCode, enabled = !s.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (s.busy) "Sending…" else "Send code")
        }
        Text(
            "We email a 6-digit code if this phone and email are on the RisiMe allowlist.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun OtpScreen(s: LoginUiState, vm: LoginViewModel) {
    BackHandler(onBack = vm::back)
    FormColumn {
        Wordmark()
        Spacer(Modifier.height(24.dp))
        Text("Enter the code sent for ${s.codeSentTo}", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        OutlinedTextField(
            value = s.code,
            onValueChange = vm::onCode,
            label = { Text("6-digit code") },
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 6.sp, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        Button(onClick = vm::verify, enabled = !s.busy && s.code.length == 6, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (s.busy) "Verifying…" else "Verify")
        }
        TextButton(onClick = vm::requestCode, enabled = !s.busy && s.resendInSec == 0) {
            Text(if (s.resendInSec > 0) "Resend code in ${s.resendInSec}s" else "Resend code")
        }
        TextButton(onClick = vm::back) { Text("Change phone or email") }
    }
}
