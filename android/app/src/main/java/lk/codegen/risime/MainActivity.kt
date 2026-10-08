package lk.codegen.risime

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import lk.codegen.risime.push.Notifier
import lk.codegen.risime.ui.RisiMeRoot
import lk.codegen.risime.ui.auth.AuthUi
import lk.codegen.risime.ui.theme.RisiMeTheme

/** A FragmentActivity because BiometricPrompt needs one (decision 014). */
class MainActivity : FragmentActivity() {
    private lateinit var authUi: AuthUi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RisiMeApp).container
        authUi = AuthUi(this, container)
        handleOpenChat(intent)
        val avatars = if (BuildConfig.CRYPTO_AVAILABLE) container.avatarLoader else null
        setContent {
            RisiMeTheme {
                androidx.compose.runtime.CompositionLocalProvider(lk.codegen.risime.ui.common.LocalAvatars provides avatars) { RisiMeRoot(container, authUi) }
            }
        }
        // Decision 064: no Recents thumbnail of the chats while the fingerprint lock is on.
        lifecycleScope.launch {
            container.appLock.settings.collect { s -> lk.codegen.risime.ui.lock.LockPrivacy.apply(this@MainActivity, s?.enabled == true) }
        }
        // The installer's confirmation (when Android doesn't allow a silent self-update).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.updater.confirm.filterNotNull().collect { intent ->
                    container.updater.confirmShown()
                    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenChat(intent)
    }

    /** Notification tap → that chat (after any gate: unlock, phone, update). */
    private fun handleOpenChat(intent: Intent?) {
        // §16.8 (decision 051): Answer on the nameless ring (only a pre-064 vault waiting for its
        // migration has no session) → the migration fingerprint, then the sync decides.
        if (intent?.getBooleanExtra(EXTRA_BLIND_ANSWER, false) == true) {
            intent.removeExtra(EXTRA_BLIND_ANSWER)
            val c = (application as RisiMeApp).container
            lifecycleScope.launch {
                c.auth.state.first { it == lk.codegen.risime.data.auth.SessionState.READY }
                c.calls.onUnlockedAfterBlindAnswer { text -> android.widget.Toast.makeText(this@MainActivity, text, android.widget.Toast.LENGTH_LONG).show() }
            }
        }
        intent?.getStringExtra(Notifier.EXTRA_OPEN_CHAT)?.let { (application as RisiMeApp).container.openChatRequest.value = it }
    }

    companion object {
        const val EXTRA_BLIND_ANSWER = "lk.codegen.risime.BLIND_ANSWER"
    }

    override fun onDestroy() {
        authUi.dispose()
        super.onDestroy()
    }
}
