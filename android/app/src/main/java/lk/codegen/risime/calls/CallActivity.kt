package lk.codegen.risime.calls

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.telecom.CallEndpointCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import lk.codegen.risime.MainActivity
import lk.codegen.risime.RisiMeApp
import lk.codegen.risime.ui.theme.RisiMeTheme

/**
 * §16.9 the call activity: incoming (full-screen intent, lock screen, turns the screen on) and
 * in-call. Answer arrives here as an activity intent (android R5): RECORD_AUDIO is checked (asked
 * on the first call), then the service is upgraded to `phoneCall|microphone`. Nothing else of the
 * app is reachable from here, so a known caller can be answered from the lock screen.
 */
class CallActivity : ComponentActivity() {
    private val calls get() = (application as RisiMeApp).container.calls

    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            calls.answer()
        } else {
            Toast.makeText(this, "RisiMe needs the microphone to answer calls", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        // Never an empty call screen: started without a call (a stale notification, Recents), it closes at once.
        if (!hasCall()) {
            finishAndRemoveTask()
            return
        }
        // Back from the call screen goes to the chat; the call goes on (the bar over the chats returns to it).
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leaveToChat()
        })
        handle(intent)
        setContent {
            RisiMeTheme { androidx.compose.runtime.CompositionLocalProvider(lk.codegen.risime.ui.common.LocalAvatars provides avatars()) {
                val s by calls.state.collectAsStateWithLifecycle()
                val blind by calls.blindRing.collectAsStateWithLifecycle()
                val endpoints by calls.endpoints.collectAsStateWithLifecycle()
                val current by calls.currentEndpoint.collectAsStateWithLifecycle()
                var name by remember { mutableStateOf("") }
                var tick by remember { mutableLongStateOf(0L) }
                LaunchedEffect(s?.peerUserId) { s?.peerUserId?.let { name = calls.nameOf(it) } }
                LaunchedEffect(s?.phase) {
                    while (true) {
                        delay(1_000)
                        tick++
                    }
                }
                // The call ended: close the call screen (and its task) and go back to the chat.
                LaunchedEffect(s, blind) {
                    if (s == null && blind == null) {
                        delay(300)
                        leaveToChat()
                    }
                }
                val snap = s
                when {
                    snap == null && blind != null -> IncomingCallScreen(null, onAnswer = ::answerBlind, onDecline = calls::stopBlindRing)
                    // Never an empty (black) window while the screen closes: the ended card, no controls.
                    snap == null -> InCallScreen(InCallUi(name = name, status = "Call ended", ended = true), {}, {}, {})
                    snap.phase == CallPhase.RINGING_IN -> IncomingCallScreen(name.ifEmpty { "RisiMe" }, onAnswer = ::answer, onDecline = calls::hangUp, photoKey = snap.peerUserId)
                    else -> {
                        @Suppress("UNUSED_EXPRESSION") tick
                        InCallScreen(
                            InCallUi(
                                name = name,
                                status = snap.notice?.let { CallTexts.notice(it, name) } ?: CallTexts.status(snap.phase, snap.connectedAtMs).orEmpty(),
                                muted = snap.muted,
                                endpoints = endpoints.map(::ui),
                                current = current?.let(::ui),
                                verified = snap.verified,
                                ended = snap.phase == CallPhase.ENDED,
                                photoKey = snap.peerUserId,
                            ),
                            onMute = calls::setMuted,
                            onEndpoint = { e -> endpoints.firstOrNull { it.identifier.toString() == e.id }?.let(calls::selectEndpoint) },
                            onEnd = calls::hangUp,
                        )
                    }
                }
            } }
        }
    }

    private fun avatars(): lk.codegen.risime.ui.common.AvatarSource? =
        if (lk.codegen.risime.BuildConfig.CRYPTO_AVAILABLE) (application as RisiMeApp).container.avatarLoader else null

    private var lastConversation: String? = null

    private fun hasCall(): Boolean = calls.inCall() || calls.state.value != null

    override fun onResume() {
        super.onResume()
        calls.state.value?.conversationId?.let { lastConversation = it }
        if (!hasCall()) return leaveToChat()
        // The proximity sensor may blank the screen only while this screen is in front.
        calls.onCallScreenVisible(true)
    }

    override fun onPause() {
        calls.onCallScreenVisible(false)
        super.onPause()
    }

    /** Finishes this screen and its task (never an empty activity behind it) and shows the chat. */
    private fun leaveToChat() {
        if (isFinishing) return
        val conv = calls.state.value?.conversationId ?: lastConversation
        val visible = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        // finish(), not finishAndRemoveTask(): removing a task tells the call service the app was
        // swiped away (onTaskRemoved), which ends the call. autoRemoveFromRecents drops the card.
        finish()
        if (visible && conv != null) {
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(lk.codegen.risime.push.Notifier.EXTRA_OPEN_CHAT, conv),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            CallNotifications.ACTION_ANSWER -> if (calls.state.value?.phase == CallPhase.RINGING_IN) answer() else if (calls.blindRing.value != null) answerBlind()
        }
    }

    private fun answer() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return calls.answer()
        // Decision 054: the permission dialog can't show over the keyguard (the answer looked dead on a
        // locked phone): ask to dismiss the keyguard first, then ask for the microphone.
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && km?.isKeyguardLocked == true) {
            km.requestDismissKeyguard(
                this,
                object : android.app.KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = mic.launch(Manifest.permission.RECORD_AUDIO)
                    override fun onDismissCancelled() = Toast.makeText(this@CallActivity, "Unlock the phone to allow the microphone, then answer", Toast.LENGTH_LONG).show()
                    override fun onDismissError() = mic.launch(Manifest.permission.RECORD_AUDIO)
                },
            )
        } else {
            mic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** §16.8: the fingerprint first (MainActivity's lock), then the unlocked sync decides. */
    private fun answerBlind() {
        calls.pendingBlindAnswer = true
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_BLIND_ANSWER, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun ui(e: CallEndpointCompat) = EndpointUi(
        e.identifier.toString(), e.name.toString(),
        when (e.type) {
            CallEndpointCompat.TYPE_EARPIECE -> EndpointUi.Kind.EARPIECE
            CallEndpointCompat.TYPE_SPEAKER -> EndpointUi.Kind.SPEAKER
            CallEndpointCompat.TYPE_WIRED_HEADSET -> EndpointUi.Kind.WIRED
            CallEndpointCompat.TYPE_BLUETOOTH -> EndpointUi.Kind.BLUETOOTH
            else -> EndpointUi.Kind.OTHER
        },
    )
}
