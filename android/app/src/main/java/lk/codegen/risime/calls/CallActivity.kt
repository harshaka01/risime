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
        handle(intent)
        setContent {
            RisiMeTheme {
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
                LaunchedEffect(s, blind) {
                    if (s == null && blind == null) {
                        delay(300)
                        finish()
                    }
                }
                val snap = s
                when {
                    snap == null && blind != null -> IncomingCallScreen(null, onAnswer = ::answerBlind, onDecline = calls::stopBlindRing)
                    snap == null -> Unit
                    snap.phase == CallPhase.RINGING_IN -> IncomingCallScreen(name.ifEmpty { "RisiMe" }, onAnswer = ::answer, onDecline = calls::hangUp)
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
                            ),
                            onMute = calls::setMuted,
                            onEndpoint = { e -> endpoints.firstOrNull { it.identifier.toString() == e.id }?.let(calls::selectEndpoint) },
                            onEnd = calls::hangUp,
                        )
                    }
                }
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) calls.answer() else mic.launch(Manifest.permission.RECORD_AUDIO)
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
