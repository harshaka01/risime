package ai.risicloud.risime.calls

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import ai.risicloud.risime.MainActivity
import ai.risicloud.risime.RisiMeApp
import ai.risicloud.risime.ui.theme.RisiMeTheme

/**
 * §16.9 the call activity: incoming (full-screen intent, lock screen, turns the screen on) and
 * in-call. Answer arrives here as an activity intent (android R5): RECORD_AUDIO is checked (asked
 * on the first call), then the service is upgraded to `phoneCall|microphone`. Nothing else of the
 * app is reachable from here, so a known caller can be answered from the lock screen.
 */
class CallActivity : ComponentActivity() {
    private val calls get() = (application as RisiMeApp).container.calls
    private val transcribe get() = (application as RisiMeApp).container.callTranscribe

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

    // ---- §23 switching and screen sharing ----

    /** "Call info" and the pre-share dialog ("Your whole screen, including notifications, will be visible"). */
    private var info by mutableStateOf(false)

    /** v1.44 §39.2 the Add participant picker (null = closed). */
    private var adding by mutableStateOf<ai.risicloud.risime.AppContainer.AddPicker?>(null)
    private var addingFor: CallSnapshot? = null

    private fun openAdd(snap: CallSnapshot) {
        addingFor = snap
        lifecycleScope.launch { adding = runCatching { (application as RisiMeApp).container.callAddPicker(snap) }.getOrNull() }
    }
    private var shareWarning by mutableStateOf(false)

    /** Video in a voice call: CAMERA first (asked only now, android A2), then the request. */
    private val cameraForRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) calls.requestVideo() else Toast.makeText(this, "Allow the camera in Settings to switch to video", Toast.LENGTH_LONG).show()
    }

    /** The prompt's Accept: CAMERA if needed; denied = accepted with my camera off (§23.2 step 2). */
    private val cameraForAccept = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        calls.answerVideoRequest(accept = true, camera = ok)
    }

    /** §23.5 the system MediaProjection consent (asked for every share; the result is used once). */
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) calls.onScreenConsent(data) else Toast.makeText(this, "Screen sharing not started", Toast.LENGTH_SHORT).show()
    }

    private fun onVideo(action: VideoAction) {
        when (action) {
            VideoAction.REQUEST -> if (granted(Manifest.permission.CAMERA)) calls.requestVideo() else cameraForRequest.launch(Manifest.permission.CAMERA)
            VideoAction.BACK_TO_VOICE -> calls.backToVoice()
            VideoAction.CAMERA_ON -> toggleCamera(true)
            VideoAction.CAMERA_OFF -> toggleCamera(false)
            VideoAction.NONE -> Unit
        }
    }

    private fun answerPrompt(accept: Boolean, camera: Boolean) {
        if (accept && camera && !granted(Manifest.permission.CAMERA)) return cameraForAccept.launch(Manifest.permission.CAMERA)
        calls.answerVideoRequest(accept, camera)
    }

    /** Share: Stop while sharing; else "Your whole screen … will be visible" before every share, then the system consent. */
    private fun share() {
        val s = calls.state.value ?: return
        if (s.sharing) return calls.stopShare("button")
        shareWarning = true
    }

    private fun launchConsent() {
        val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java) ?: return
        runCatching { projection.launch(mpm.createScreenCaptureIntent()) }
            .onFailure { Toast.makeText(this, "Screen sharing isn't available on this phone", Toast.LENGTH_LONG).show() }
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
            RisiMeTheme { androidx.compose.runtime.CompositionLocalProvider(ai.risicloud.risime.ui.common.LocalAvatars provides avatars()) {
                val s by calls.state.collectAsStateWithLifecycle()
                val blind by calls.blindRing.collectAsStateWithLifecycle()
                val routes by calls.routes.collectAsStateWithLifecycle()
                // v1.44 §39.3: "Adding Sunil…", "Couldn't add people…".
                val mv by calls.move.collectAsStateWithLifecycle()
                // v1.39 §36: the Official-call transcription (button, prompt, red bar); nothing on a Private call.
                val tx by transcribe.view.collectAsStateWithLifecycle()
                var txNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
                val txIds = listOfNotNull(tx.prompt?.requestedBy, tx.notice?.by).toSet()
                LaunchedEffect(txIds) { txNames = txIds.associateWith { calls.nameOf(it) } }
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
                // (The call's end closes this screen from [watchCallEnd], whatever the lifecycle state.)
                val snap = s
                @Suppress("UNUSED_EXPRESSION") tick
                when {
                    snap == null && blind != null -> IncomingCallScreen(null, onAnswer = ::answerBlind, onDecline = calls::stopBlindRing)
                    // Never an empty (black) window while the screen closes: the ended card, no controls.
                    snap == null -> CallScreen(CallScreenUi(name = name, status = "Call ended", stage = CallStage.ENDED), CallScreenActions())
                    else -> {
                        val now = System.currentTimeMillis()
                        val title = name.ifEmpty { "RisiMe" }
                        val status = when {
                            snap.phase == CallPhase.RINGING_IN -> if (snap.group) (if (snap.video) "Group video call" else "Group voice call") else if (snap.video) "Incoming video call" else "RisiMe voice call"
                            else -> snap.notice?.let { CallTexts.notice(it, title) } ?: CallTexts.status(snap.phase, snap.connectedAtMs, now, video = snap.video).orEmpty()
                        }
                        val ui = callScreenUi(snap, title, status, routes, now, groupAdd = calls.canAdvertiseAddToCall(), moveLine = mv?.takeIf { it.callId == snap.callId }?.line)
                        val wm = calls.media as? WebRtcCallMedia
                        CallScreen(
                            ui,
                            CallScreenActions(
                                onMinimise = ::leaveToChat,
                                onAnswer = { answer(camera = snap.video) },
                                onAnswerWithoutVideo = { answer(camera = false) },
                                onDecline = calls::hangUp,
                                onMute = calls::setMuted,
                                onEndpoint = calls::selectRoute,
                                onVideo = { onVideo(videoAction(snap, System.currentTimeMillis())) },
                                onShare = ::share,
                                onEnd = calls::hangUp,
                                onFlip = calls::switchCamera,
                                onCameraOff = { calls.setCameraWanted(false) },
                                onBackToVoice = calls::backToVoice,
                                onCallInfo = { info = true },
                                onCancelAsk = calls::cancelVideoRequest,
                                onPrompt = ::answerPrompt,
                                onStopShare = { calls.stopShare("banner") },
                                onAdd = { openAdd(snap) },
                            ),
                            remote = if (snap.group) {
                                calls.groupSession()?.takeIf { snap.video && snap.phase != CallPhase.ENDED }?.let { sess -> { m -> androidx.compose.foundation.layout.Box(m) { GroupVideoGrid(sess, snap, memberNames) } } }
                            } else {
                                wm?.takeIf { snap.video && snap.phase != CallPhase.ENDED }?.let { m -> { mod -> LiveRemoteVideo(m, snap, title, mod) } }
                            },
                            local = if (snap.group) null else wm?.let { m -> { mod -> LiveLocalVideo(m, mod) } },
                            allowAutoHide = !keepControls,
                            statusBar = if (snap.group) null else ({ ai.risicloud.risime.calls.transcribe.TranscribeBar(tx, onStop = transcribe::stop) }),
                            extraBanners = if (snap.group) null else ({
                                ai.risicloud.risime.calls.transcribe.TranscribeControls(
                                    tx, title, name = { txNames[it] ?: title },
                                    onTranscribe = transcribe::request,
                                    onCancel = { transcribe.stop(false) },
                                    onNoticeShown = transcribe::dismissNotice,
                                )
                            }),
                            overlay = if (snap.group) null else ({
                                ai.risicloud.risime.calls.transcribe.TranscribePrompt(
                                    tx, name = { txNames[it] ?: title }, serverNow = { (application as RisiMeApp).container.serverClock.serverNow() },
                                    onAllow = transcribe::allow, onDecline = transcribe::decline,
                                )
                            }),
                            center = if (snap.group && snap.members.isNotEmpty() && !(snap.video && snap.phase != CallPhase.ENDED)) ({
                                GroupParticipantList(snap.members.map { m -> groupMemberUi(m, memberNames[m.userId] ?: "…") })
                            }) else null,
                        )
                        if (info) CallInfoDialog(snap, title, routes, wm?.lastStats, onDismiss = { info = false })
                        adding?.let { p ->
                            AddPeopleDialog(
                                p.candidates, p.room, p.peerName, p.groupName, p.official,
                                onAdd = { ring, add ->
                                    adding = null
                                    addingFor?.let { (application as RisiMeApp).container.onCallAddPicked(it, ring, add) }
                                },
                                onDismiss = { adding = null },
                            )
                        }
                        if (shareWarning) ShareConfirmDialog(
                            dnd = shareDialogShowsDnd(Build.VERSION.SDK_INT),
                            onStart = { shareWarning = false; launchConsent() },
                            onDnd = { shareWarning = false; runCatching { startActivity(Intent(android.provider.Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)) }.onFailure { runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } } },
                            onCancel = { shareWarning = false },
                        )
                    }
                }
            } }
        }
        watchCallEnd()
    }

    /**
     * P0 E (12 Oct, black screen after calls): the call's end closes this screen in EVERY lifecycle state.
     * It used to be a LaunchedEffect on `collectAsStateWithLifecycle`, which stops collecting once the
     * activity is STOPPED (Home, the screen off at the ear, another app): a call that ended there left
     * this activity alive in its own task with a dead "End" screen in Recents, which closed itself again
     * when the user came back to it (an empty transition instead of the app).
     */
    private fun watchCallEnd() {
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(calls.state, calls.blindRing) { s, b -> s == null && b == null }
                .distinctUntilChanged()
                .collectLatest { over ->
                    if (!over) return@collectLatest
                    delay(300) // the "Call ended" card for a moment when it is on screen
                    Log.i("RisiMe", "calls: call screen closes (call over, resumed=$resumed)")
                    leaveToChat(showChat = resumed)
                }
        }
    }

    /** Debug builds only: the device test (uiautomator) keeps the call controls on screen. */
    private val keepControls by lazy { ai.risicloud.risime.BuildConfig.DEBUG && java.io.File(filesDir, "debug_keep_call_controls").exists() }

    private fun avatars(): ai.risicloud.risime.ui.common.AvatarSource? =
        if (ai.risicloud.risime.BuildConfig.CRYPTO_AVAILABLE) (application as RisiMeApp).container.avatarLoader else null

    private var lastConversation: String? = null

    private fun hasCall(): Boolean = calls.inCall() || calls.state.value != null

    override fun onResume() {
        super.onResume()
        calls.state.value?.conversationId?.let { lastConversation = it }
        // Coming to the front without a call (Recents, a stale notification): straight to the chat. The
        // lifecycle is still STARTED inside onResume, so this must not depend on it (it used to finish only).
        if (!hasCall()) return leaveToChat(showChat = true)
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

    /**
     * Finishes this screen and its task (never an empty activity behind it). [showChat] (the screen is in
     * front: Back, Minimise, the end of a call on screen): the app comes to the front first, on the call's chat
     * when it is known, and only then does this screen finish — one transition from the call screen to the
     * app. Finishing first started a second, cross-task transition to whatever task was below the call's
     * (the home screen, another app, a stale call task) while the app's own task was being brought up.
     * Not in front (Home, the screen off): only finishes, quietly.
     */
    private fun leaveToChat(showChat: Boolean = true) {
        if (isFinishing) return
        val conv = calls.state.value?.conversationId ?: lastConversation
        if (showChat) {
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            conv?.let { i.putExtra(ai.risicloud.risime.push.Notifier.EXTRA_OPEN_CHAT, it) }
            runCatching { startActivity(i) }.onFailure { Log.w("RisiMe", "calls: back to the chat: ${it.message}") }
        }
        // finish(), not finishAndRemoveTask(): removing a task tells the call service the app was
        // swiped away (onTaskRemoved), which ends the call. autoRemoveFromRecents drops the card.
        finish()
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
}
