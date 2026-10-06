package lk.codegen.risime.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lk.codegen.risime.ui.theme.Spacing

/** "Confirm your phone" (contract §7): once per user, before anything connects. */
@Composable
fun PhoneVerifyScreen(vm: PhoneVerifyViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text("Confirm your phone", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(s.maskedPhone, style = MaterialTheme.typography.headlineSmall)
        Text(
            "This number comes from your company's RisiMe allowlist. If it's wrong, ask your admin.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (s.sent) {
            Text("We sent a 6-digit code by SMS.", textAlign = TextAlign.Center)
            OutlinedTextField(
                value = s.code,
                onValueChange = vm::onCode,
                label = { Text("6-digit code") },
                singleLine = true,
                enabled = !s.busy,
                textStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 6.sp, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                // Keyboard/autofill suggest the code from the SMS; no SMS Retriever (decision 020).
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.SmsOtpCode },
            )
        }
        s.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (s.sent) {
            Button(onClick = vm::confirm, enabled = !s.busy && s.code.length == 6, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (s.busy) "Checking…" else "Confirm")
            }
            TextButton(onClick = vm::sendCode, enabled = !s.busy && s.resendInSec == 0) {
                Text(if (s.resendInSec > 0) "Resend code in ${waitText(s.resendInSec)}" else "Resend code")
            }
        } else {
            Button(onClick = vm::sendCode, enabled = !s.busy && s.resendInSec == 0, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(
                    when {
                        s.busy -> "Sending…"
                        s.resendInSec > 0 -> "Send code in ${waitText(s.resendInSec)}"
                        else -> "Send code"
                    },
                )
            }
        }
        lk.codegen.risime.ui.common.ConfirmLogout(onConfirm = vm::signOut) { ask ->
            TextButton(onClick = ask, enabled = !s.busy) { Text("Sign out") }
        }
    }
}
