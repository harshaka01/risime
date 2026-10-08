package lk.codegen.risime.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import lk.codegen.risime.data.auth.SignupOutcome
import lk.codegen.risime.data.auth.normalisePhone
import lk.codegen.risime.data.auth.signupFormError
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

// ---- §21 open sign-up (v1.20) ----

const val SIGNUP_TITLE = "Create your RisiMe account"
const val SIGNUP_CREATE = "Create account"
const val PHONE_NOT_VERIFIED = "Phone not verified"

/** UI state of "Create your RisiMe account". */
data class SignupFormState(
    val displayName: String = "",
    val countryCode: String = "+94",
    val number: String = "",
    val busy: Boolean = false,
    /** Shown under the phone field (phone_taken, a bad number). */
    val phoneError: String? = null,
    /** Shown under the button (name, limits, network). */
    val error: String? = null,
)

/** §21.8: 403 signup_required after sign-in. Escape routes as on the blocked screen; chats are never touched. */
@Composable
fun SignupScreen(c: AppContainer, authUi: AuthUi) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(SignupFormState(displayName = c.signupNamePrefill().orEmpty())) }
    SignupContent(
        state = state,
        onChange = { state = it },
        onCreate = {
            val phone = normalisePhone(state.countryCode, state.number)
            val nameError = signupFormError(state.displayName, "+0")
            val phoneError = if (phone == null) signupFormError("x", null) else null
            if (nameError != null || phoneError != null) {
                state = state.copy(error = nameError, phoneError = phoneError)
            } else {
                state = state.copy(busy = true, error = null, phoneError = null)
                scope.launch {
                    val o = c.signUp(phone!!, state.displayName)
                    state = when (o) {
                        is SignupOutcome.FieldError -> if (o.onPhone) state.copy(busy = false, phoneError = o.message) else state.copy(busy = false, error = o.message)
                        is SignupOutcome.Failed -> state.copy(busy = false, error = o.message)
                        else -> state.copy(busy = false)
                    }
                }
            }
        },
        onAnotherAccount = {
            scope.launch {
                c.signOutKeepChats(notice = null)
                val choice = signInChoice(c.api.authConfig(), BuildConfig.DEBUG, AuthOverride.FORCE_OIDC)
                if (choice is SignInChoice.Options && SignInOption.OIDC in choice.options) authUi.signIn(choice.config, fresh = true)
            }
        },
        onSignOut = { scope.launch { c.signOutKeepChats() } },
    )
}

@Composable
fun SignupContent(
    state: SignupFormState,
    onChange: (SignupFormState) -> Unit,
    onCreate: () -> Unit,
    onAnotherAccount: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(SIGNUP_TITLE, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(
            "You're signed in with RisiCloud, but you don't have a RisiMe account yet. Choose the name your friends will see and your mobile number.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.displayName,
            onValueChange = { onChange(state.copy(displayName = it.take(64), error = null)) },
            label = { Text("Your name") },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(
                value = state.countryCode,
                onValueChange = { v -> onChange(state.copy(countryCode = v.filter { it == '+' || it.isDigit() }.take(5), phoneError = null)) },
                label = { Text("Code") },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                modifier = Modifier.weight(0.35f),
            )
            OutlinedTextField(
                value = state.number,
                onValueChange = { onChange(state.copy(number = it.take(20), phoneError = null)) },
                label = { Text("Mobile number") },
                singleLine = true,
                enabled = !state.busy,
                isError = state.phoneError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (!state.busy) onCreate() }),
                modifier = Modifier.weight(0.65f),
            )
        }
        state.phoneError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth()) }
        Text(
            "Your number shows as \"$PHONE_NOT_VERIFIED\" to others until you confirm it by SMS. Friends must accept your request before you can chat.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        state.error?.let { ErrorState(it) }
        Button(onClick = onCreate, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(if (state.busy) "Creating…" else SIGNUP_CREATE)
        }
        TextButton(onClick = onAnotherAccount, enabled = !state.busy) { Text(SIGN_IN_ANOTHER_ACCOUNT, textAlign = TextAlign.Center) }
        TextButton(onClick = onSignOut, enabled = !state.busy) { Text(SIGN_OUT_KEEPS_CHATS, textAlign = TextAlign.Center) }
    }
}

/** §21.4: the small note next to a phone whose `phone_confirmed` is false. */
@Composable
fun PhoneNotVerifiedNote(modifier: Modifier = Modifier) {
    Text(PHONE_NOT_VERIFIED, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = modifier)
}
