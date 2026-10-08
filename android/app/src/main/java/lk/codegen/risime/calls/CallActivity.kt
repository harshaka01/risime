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
            calls.answer(camera = false)
        } else {
            Toast.makeText(this, "RisiMe needs the microphone to answer calls", Toast.LENGTH_LONG).show()
        }
    }

    /** §19.6 Answer on a video call: the microphone and the camera (a denied camera answers with it off, android A2). */
    private val micAndCamera = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val micOk = r[Manifest.permission.RECORD_AUDIO] ?: granted(Manifest.permission.RECORD_AUDIO)
        if (micOk) {
            calls.answer(camera = r[Manifest.permission.CAMERA] ?: granted(Manifest.permission.CAMERA))
        } else {
            Toast.makeText(this, "RisiMe needs the microphone to answer calls", Toast.LENGTH_LONG).show()
        }
    }

    /** The camera button when CAMERA isn't granted yet (android A2: asked only when the user turns it on). */
    private val cameraOnly = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) calls.setCameraWanted(true) else Toast.makeText(this, "Allow the camera in Settings to turn it on", Toast.LENGTH_LONG).show()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

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
                // The peer (1:1), "Kamal · Pilot team" while a group call rings, else the group's name (§20.4).
                LaunchedEffect(s?.callId, s?.phase == CallPhase.RINGING_IN) { s?.let { name = calls.callTitle(it) } }
                // §20.5: group participants are named from the app's own database (android A7).
                var memberNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
                val memberIds = s?.members?.map { it.userId }?.toSet().orEmpty()
                LaunchedEffect(memberIds) { memberNames = memberIds.associateWith { calls.nameOf(it) } }
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
                    snap.phase == CallPhase.RINGING_IN -> IncomingCallScreen(
                        name.ifEmpty { "RisiMe" }, onAnswer = { answer(camera = snap.video) }, onDecline = calls::hangUp,
                        photoKey = if (snap.group) snap.conversationId else snap.peerUserId,
                        onAnswerWithoutVideo = if (snap.video) ({ answer(camera = false) }) else null,
                        subtitle = if (!snap.group) null else if (snap.video) "Group video call" else "Group voice call",
                    )
                    else -> {
                        @Suppress("UNUSED_EXPRESSION") tick
                        InCallScreen(
                            InCallUi(
                                name = name,
                                status = snap.notice?.let { CallTexts.notice(it, name) } ?: CallTexts.status(snap.phase, snap.connectedAtMs, video = snap.video).orEmpty(),
                                muted = snap.muted,
                                endpoints = endpoints.map(::ui),
                                current = current?.let(::ui),
                                verified = snap.verified,
                                ended = snap.phase == CallPhase.ENDED,
                                photoKey = if (snap.group) snap.conversationId else snap.peerUserId,
                                active = snap.phase == CallPhase.ACTIVE,
                            ),
                            center = if (snap.group && snap.members.isNotEmpty() && !(snap.video && snap.phase != CallPhase.ENDED)) ({
                                GroupParticipantList(snap.members.map { m -> groupMemberUi(m, memberNames[m.userId] ?: "…") })
                            }) else null,
                            onMute = calls::setMuted,
                            onEndpoint = { e ->
                                val match = endpoints.firstOrNull { it.identifier.toString() == e.id }
                                if (match != null) calls.selectEndpoint(match) else calls.selectFallbackRoute(e.kind == EndpointUi.Kind.SPEAKER)
                            },
                            onEnd = calls::hangUp,
                            video = if (snap.group) {
                                calls.groupSession()?.takeIf { snap.video && snap.phase != CallPhase.ENDED }?.let { sess -> { GroupVideoGrid(sess, snap, memberNames) } }
                            } else {
                                (calls.media as? WebRtcCallMedia)?.takeIf { snap.video && snap.phase != CallPhase.ENDED }?.let { m -> { LiveVideoStage(m, snap, name) } }
                            },
                            extraControls = {
                                if (snap.video && snap.phase != CallPhase.ENDED) {
                                    CallControl(
                                        label = if (snap.wantCamera) "Camera" else "Camera off",
                                        description = if (snap.wantCamera) "Turn camera off" else "Turn camera on",
                                        icon = if (snap.wantCamera) lk.codegen.risime.ui.common.RisiIcons.Videocam else lk.codegen.risime.ui.common.RisiIcons.VideocamOff,
                                        on = !snap.wantCamera,
                                        onClick = { toggleCamera(!snap.wantCamera) },
                                    )
                                    if (snap.cameraOn) {
                                        CallControl(label = "Flip", description = "Switch camera", icon = lk.codegen.risime.ui.common.RisiIcons.CameraSwitch, on = false, onClick = calls::switchCamera)
                                    }
                                }
                            },
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
        // The proximity sensor may blank the screen only while this screen is in front; §19.5 the
        // camera runs only while it is visible and the phone unlocked (a locked call screen waits).
        resumed = true
        calls.onCallScreenVisible(!keyguardLocked())
        runCatching { registerReceiver(unlocked, android.content.IntentFilter(Intent.ACTION_USER_PRESENT)) }
    }

    override fun onPause() {
        resumed = false
        runCatching { unregisterReceiver(unlocked) }
        calls.onCallScreenVisible(false)
        super.onPause()
    }

    private var resumed = false

    /** Unlocked while the call screen is in front: now the camera may run. */
    private val unlocked = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (resumed) calls.onCallScreenVisible(true)
        }
    }

    private fun keyguardLocked(): Boolean = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true

    /** §19.5 the camera button; CAMERA is asked the first time it is turned on (android A2). */
    private fun toggleCamera(on: Boolean) {
        if (on && !granted(Manifest.permission.CAMERA)) return cameraOnly.launch(Manifest.permission.CAMERA)
        calls.setCameraWanted(on)
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
            // The notification's Answer is a human tap too; a video call answered from there starts with the camera off.
            CallNotifications.ACTION_ANSWER -> if (calls.state.value?.phase == CallPhase.RINGING_IN) answer(camera = false) else if (calls.blindRing.value != null) answerBlind()
        }
    }

    /** A human tapped Answer here ([camera]: "Answer" on a video call; never by itself, android A3). */
    private fun answer(camera: Boolean = false) {
        if (granted(Manifest.permission.RECORD_AUDIO) && (!camera || granted(Manifest.permission.CAMERA))) return calls.answer(camera)
        if (camera && granted(Manifest.permission.RECORD_AUDIO)) return micAndCamera.launch(arrayOf(Manifest.permission.CAMERA))
        // Decision 054: the permission dialog can't show over the keyguard (the answer looked dead on a
        // locked phone): ask to dismiss the keyguard first, then ask for the microphone.
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && km?.isKeyguardLocked == true) {
            km.requestDismissKeyguard(
                this,
                object : android.app.KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = if (camera) micAndCamera.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)) else mic.launch(Manifest.permission.RECORD_AUDIO)
                    override fun onDismissCancelled() = Toast.makeText(this@CallActivity, "Unlock the phone to allow the microphone, then answer", Toast.LENGTH_LONG).show()
                    override fun onDismissError() = if (camera) micAndCamera.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)) else mic.launch(Manifest.permission.RECORD_AUDIO)
                },
            )
        } else if (camera) {
            micAndCamera.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
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
