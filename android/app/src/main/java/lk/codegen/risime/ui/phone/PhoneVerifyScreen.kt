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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
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
    // The cooldown deadline (wall clock) and step survive rotation and process death.
    var savedUntil by rememberSaveable { mutableLongStateOf(0L) }
    var savedSent by rememberSaveable { mutableStateOf(false) }
    var savedLocked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(vm) {
        vm.restore(savedUntil, savedSent, savedLocked)
        vm.state.collect {
            savedUntil = it.resendUntilMs
            savedSent = it.sent
            savedLocked = it.codeLocked
        }
    }
    PhoneVerifyContent(s, vm::onCode, vm::confirm, vm::sendCode) {
        TextButton(onClick = vm::signOut, enabled = !s.busy) { Text(lk.codegen.risime.ui.auth.SIGN_OUT_KEEPS_CHATS) }
    }
}

const val PHONE_RESEND_TAG = "phone_resend"
const val PHONE_CONFIRM_TAG = "phone_confirm"

/** Stateless "Confirm your phone" (UI-tested): countdowns, locked states and one request at a time. */
@Composable
fun PhoneVerifyContent(
    s: PhoneVerifyState,
    onCode: (String) -> Unit,
    onConfirm: () -> Unit,
    onSend: () -> Unit,
    signOut: @Composable () -> Unit = {},
) {
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
                onValueChange = onCode,
                label = { Text("6-digit code", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                singleLine = true,
                enabled = !s.busy && !s.codeLocked,
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
        if (s.sent && !s.codeLocked && s.attemptsLeft == 1 && s.error == null) {
            Text("1 attempt left", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        if (s.sent) {
            Button(
                onClick = onConfirm, enabled = s.canConfirm,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag(PHONE_CONFIRM_TAG),
            ) {
                Text(if (s.busy) "Checking…" else "Confirm")
            }
            TextButton(onClick = onSend, enabled = s.canSend, modifier = Modifier.testTag(PHONE_RESEND_TAG)) {
                Text(if (s.resendInSec > 0) "Resend in ${CodeLimits.countdown(s.resendInSec)}" else "Resend code")
            }
        } else {
            Button(onClick = onSend, enabled = s.canSend, modifier = Modifier.fillMaxWidth().height(52.dp).testTag(PHONE_RESEND_TAG)) {
                Text(
                    when {
                        s.busy -> "Sending…"
                        s.resendInSec > 0 -> "Send code in ${CodeLimits.countdown(s.resendInSec)}"
                        else -> "Send code"
                    },
                )
            }
        }
        signOut()
    }
}
