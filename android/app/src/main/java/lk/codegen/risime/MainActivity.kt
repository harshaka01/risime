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
            // §23.5: and FLAG_SECURE while a screen share runs (other chats never reach the viewer).
            kotlinx.coroutines.flow.combine(container.appLock.settings, lk.codegen.risime.calls.ScreenSharing.flow) { s, sharing -> (s?.enabled == true) to sharing }
                .collect { (lock, sharing) -> lk.codegen.risime.ui.lock.LockPrivacy.apply(this@MainActivity, lock, sharing = sharing) }
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
        val callBack = intent?.getStringExtra(lk.codegen.risime.calls.CallNotifications.EXTRA_CALL_BACK)
        intent?.getStringExtra(Notifier.EXTRA_OPEN_CHAT)?.let { conv ->
            val c = (application as RisiMeApp).container
            if (callBack != null) c.callBackRequest.value = conv to (callBack == "video")
            c.openChatRequest.value = conv
        }
        intent?.removeExtra(lk.codegen.risime.calls.CallNotifications.EXTRA_CALL_BACK)
    }

    companion object {
        const val EXTRA_BLIND_ANSWER = "lk.codegen.risime.BLIND_ANSWER"
    }

    override fun onDestroy() {
        authUi.dispose()
        super.onDestroy()
    }
}
