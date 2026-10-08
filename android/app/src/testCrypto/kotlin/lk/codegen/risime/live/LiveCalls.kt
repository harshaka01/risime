package lk.codegen.risime.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import lk.codegen.risime.calls.ActiveCallRecord
import lk.codegen.risime.calls.CallEnvelope
import lk.codegen.risime.calls.CallEnvironment
import lk.codegen.risime.calls.CallMark
import lk.codegen.risime.calls.CallMarks
import lk.codegen.risime.calls.CallNotice
import lk.codegen.risime.calls.CallPhase
import lk.codegen.risime.calls.CallSignals
import lk.codegen.risime.calls.CallStateMachine
import lk.codegen.risime.calls.FakeCallMedia
import lk.codegen.risime.calls.IceState
import lk.codegen.risime.calls.MachineCallHooks
import lk.codegen.risime.calls.SdpRules
import lk.codegen.risime.calls.SignalOutcome
import lk.codegen.risime.crypto.RealMls
import lk.codegen.risime.data.BehaviourLog
import lk.codegen.risime.data.ChatEngine
import lk.codegen.risime.data.FakeBehaviourDao
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.FakeSyncDao
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.deletes.ServerClock
import lk.codegen.risime.data.mls.DeviceRegistrar
import lk.codegen.risime.data.mls.E2eeState
import lk.codegen.risime.data.mls.FakeMlsPendingDao
import lk.codegen.risime.data.mls.MlsApi
import lk.codegen.risime.data.mls.MlsPipeline
import lk.codegen.risime.data.mls.MlsUpgrader
import lk.codegen.risime.data.mls.Registration
import lk.codegen.risime.net.ApiClient
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.MlsCommitEvent
import lk.codegen.risime.net.MlsCommitRequest
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.dmConversationId
import lk.codegen.risime.realtime.ConnectionState
import lk.codegen.risime.realtime.PhoenixRealtimeClient
import lk.codegen.risime.realtime.PushResult
import lk.codegen.risime.realtime.RealtimeListener
import lk.codegen.risime.realtime.RealtimeSession
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * §16 live interop with the REAL server and MLS core: caller C (one device) and callee B (two
 * devices) as app instances (ChatEngine + CallStateMachine). Media can't run on the JVM: the fake
 * media produces libwebrtc-shaped SDP and a DTLS that sees the real peer certificate, so signalling,
 * the SDP rules and the fingerprint check run end to end through the server.
 */
class LiveCalls(private val http: OkHttpClient, private val scope: CoroutineScope, private val url: String, private val trusted: List<String>) {
    val devices = mutableListOf<Dev>()

    companion object {
        /** A shorter ring (both sides use it) so the crash/kill checks don't wait 45 s each. */
        const val RING_MS = 12_000L
    }

    private fun ensure(c: Boolean, m: () -> String) { if (!c) throw AssertionError(m()) }

    private suspend fun <T> await(ms: Long, what: String, probe: suspend () -> T?): T = awaitL(ms, { what }, probe)

    private suspend fun <T> awaitL(ms: Long, what: () -> String, probe: suspend () -> T?): T =
        withTimeoutOrNull(ms) { var v = probe(); while (v == null) { delay(50); v = probe() }; v } ?: throw AssertionError("timed out waiting for ${what()}")

    /** Server rule (§16.3): at most 1 ring per callee per 5 s. Wait only what's left since the last ring to [callee]. */
    private val lastRing = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private suspend fun ringGap(callee: String) {
        // The callee window slides (a repeat can be refused for up to ~10 s): wait out the whole of it.
        lastRing[callee]?.let { val left = it + 10_500 - System.currentTimeMillis(); if (left > 0) delay(left) }
        lastRing[callee] = System.currentTimeMillis()
    }

    private suspend fun place(caller: Dev, callee: Dev, conv: String) {
        ringGap(callee.userId)
        caller.machine.placeCall(conv)
    }

    private fun Dev.why() = "$name phase=${phase()} notice=${notice()} refused=$refused logs=${logs.takeLast(6)}"

    inner class Dev(val name: String, val userId: String, val token: String, val deviceId: String = UUID.randomUUID().toString()) {
        val api = ApiClient(http, { url }, { token })
        val mls = RealMls.device(userId, deviceId, trusted, attest = false)
        val messages = FakeMessageDao()
        val clock = ServerClock()
        val media = FakeCallMedia()
        val marks = object : CallMarks {
            val rows = java.util.concurrent.ConcurrentHashMap<String, CallMark>()
            override suspend fun get(callId: String) = rows[callId]
            override suspend fun put(mark: CallMark) { rows[mark.callId] = mark }
        }
        val missed = CopyOnWriteArrayList<String>()
        val refused = CopyOnWriteArrayList<String>()
        val logs = CopyOnWriteArrayList<String>()
        var tamper = false
        /** Decision 054: what AudioManager.mode says (2 = MODE_IN_CALL, 3 = MODE_IN_COMMUNICATION); only 2 is busy. */
        @Volatile var audioMode = 0
        lateinit var client: PhoenixRealtimeClient
        lateinit var machine: CallStateMachine
        /** The "process" the machine's timers live in: a crash cancels it. */
        private var proc = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        private val env = object : CallEnvironment {
            override fun audioBusy() = machine.state.value == null && audioMode == 2
        }
        val mlsApi = object : MlsApi {
            override suspend fun group(conversationId: String) = api.mlsGroup(conversationId)
            override suspend fun claim(userIds: List<String>) = api.claimKeyPackages(userIds, deviceId)
            override suspend fun commit(conversationId: String, body: MlsCommitRequest) = api.mlsCommit(conversationId, body, deviceId)
        }
        val chat: ChatEngine = ChatEngine(
            messages = messages, sync = FakeSyncDao(),
            tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
            scope = scope, realtime = { client }, meId = { userId },
            behaviour = BehaviourLog(FakeBehaviourDao(), { "s" }, { 0L }),
            mls = MlsPipeline({ mls.engine }, FakeMlsPendingDao(), log = { logs += "mls: $it"; println("  [$name] mls: $it") }), mlsEngine = { mls.engine },
            catchUp = { catchUp(it) }, serverClock = clock,
            calls = MachineCallHooks({ machine }, marks) { _, from, _ -> missed += from },
        )
        private val signals = object : CallSignals {
            override suspend fun signal(conversationId: String, peer: String, env: CallEnvelope.Env, media: String): SignalOutcome =
                when (val r = chat.sendCallSignal(conversationId, peer, env, media)) {
                    is PushResult.Ok -> SignalOutcome.Ok
                    is PushResult.Rejected -> SignalOutcome.Refused(r.reason).also { refused += "${env.type}:${r.reason}" }
                    PushResult.Unavailable -> SignalOutcome.Unavailable
                }

            override suspend fun end(conversationId: String, peer: String, env: CallEnvelope.End) {
                chat.queueCallEnd(conversationId, peer, env, marks.get(env.callId)?.let { it.rang && !it.answered } == true)
            }

            override suspend fun missed(conversationId: String, peer: String, callId: String, video: Boolean) {
                if (chat.insertLocalMissedCall(conversationId, peer, callId, video)) missed += peer
            }
        }

        private fun newMachine() = CallStateMachine(
            userId, deviceId, proc, media, signals, marks, env, serverNow = { clock.serverNow() },
            log = { logs += it; System.out.println("  [$name] call: $it") }, tamperRemoteFingerprint = { tamper }, ringMs = RING_MS,
        )

        /**
         * The app process dies (no call_end, no Telecom, its timers gone) and does NOT come back yet:
         * the socket closes. [revive] starts the next process (MLS state and the marks survive).
         */
        fun crash(): ActiveCallRecord? {
            val s = machine.state.value?.takeIf { it.phase != CallPhase.ENDED }
            client.stop()
            proc.cancel()
            return s?.let { ActiveCallRecord(it.callId, it.conversationId, it.peerUserId, it.outgoing, it.phase.name, it.connectedAtMs != null) }
        }

        /** The next process: a fresh machine, and CallManager's process-start cleanup (the owed call_end). */
        suspend fun revive(left: ActiveCallRecord?) {
            proc = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
            machine = newMachine()
            start()
            live()
            if (left != null && marks.get(left.callId)?.ended != true) {
                marks.put((marks.get(left.callId) ?: CallMark(left.callId)).copy(ended = true))
                ActiveCallRecord.endReason(left)?.let { chat.queueCallEnd(left.conversationId, left.peerUserId, CallEnvelope.End(left.callId, it), false) }
            }
        }

        init {
            machine = newMachine()
            client = PhoenixRealtimeClient(http, scope, object : RealtimeListener {
                override suspend fun cursor() = chat.cursor()
                override suspend fun onServerTime(ts: String?) = chat.onServerTime(ts)
                override suspend fun onHistoryBefore(ts: String?) = chat.onHistoryBefore(ts)
                override suspend fun onEvents(events: List<Event>) = chat.onEvents(events)
                override suspend fun onLive() = chat.onLive()
                override suspend fun onAuthFailed() = Unit
            }, backoffMs = listOf(200, 500))
            devices += this
        }

        suspend fun catchUp(conv: String) {
            val g = mls.engine.group(conv) ?: return
            val r = api.mlsCommits(conv, g.epoch) as? ApiResult.Ok ?: return
            chat.applyOutOfBand(r.value.commits.map { c ->
                Event("catchup:${c.epoch}", Event.KIND_MLS_COMMIT, ProtocolJson.encodeToJsonElement(MlsCommitEvent.serializer(), MlsCommitEvent(conv, g.generation, c.epoch, c.commit, c.fromDevice)) as kotlinx.serialization.json.JsonObject)
            })
        }

        suspend fun register() {
            val r = DeviceRegistrar(api, { deviceId }, "0.3.0-interop", { mls.engine }, imagesSupported = { true }, callsSupported = { true }).register(null)
            ensure(r is Registration.Mls) { "$name register: $r" }
        }

        fun start() = client.start(RealtimeSession(url, userId, deviceId, "0.3.0-interop") { token })
        suspend fun live() = await(15_000, "$name Live") { client.state.value.takeIf { it == ConnectionState.Live } }
        fun phase() = machine.state.value?.phase
        fun notice() = machine.state.value?.notice
        fun lines(conv: String) = messages.rows.values.filter { it.conversationId == conv && it.kind == MessageEntity.KIND_CALL }
        fun session() = media.sessions.last()

        fun close() = runCatching { client.stop(); mls.close() }
    }

    /**
     * Runs every check; [check] prints PASS/FAIL like the other live tests. Every ringing step uses a
     * (caller, callee) pair of its own: the server allows 1 ring per pair per 5 s with a sliding
     * window (a repeat can be refused for up to ~10 s), and the run must stay short.
     */
    fun run(aUser: Pair<String, String>, bUser: Pair<String, String>, cUser: Pair<String, String>, check: (String, suspend () -> String?) -> Unit) {
        val a1 = Dev("A1", aUser.first, aUser.second)
        val b1 = Dev("B1", bUser.first, bUser.second)
        val b2 = Dev("B2", bUser.first, bUser.second)
        val c1 = Dev("C1", cUser.first, cUser.second)
        val cb = dmConversationId(cUser.first, bUser.first)
        val ab = dmConversationId(aUser.first, bUser.first)
        val ac = dmConversationId(aUser.first, cUser.first)
        suspend fun idle(vararg ds: Dev) = await(10_000, "idle: ${ds.joinToString { it.why() }}") { if (ds.all { it.phase() == null || it.phase() == CallPhase.ENDED }) Unit else null }
        try {
            check("calls: register with the calls capability; Live; the DMs C–B, A–B, A–C upgrade to e2ee") {
                listOf(a1, b1, b2, c1).forEach { it.register() }
                listOf(a1, b1, b2, c1).forEach { it.start() }
                listOf(a1, b1, b2, c1).forEach { it.live() }
                for ((conv, owner, peer) in listOf(Triple(cb, c1, bUser.first), Triple(ab, a1, bUser.first), Triple(ac, a1, cUser.first))) {
                    val s = MlsUpgrader({ owner.mls.engine }, owner.mlsApi).ensure(conv, owner.userId, peer)
                    ensure(s is E2eeState.Encrypted) { "upgrade $conv: $s" }
                }
                await(15_000, "B1 joined") { b1.mls.engine.group(cb)?.let { b1.mls.engine.group(ab) } }
                await(15_000, "B2 joined") { b2.mls.engine.group(cb)?.let { b2.mls.engine.group(ab) } }
                await(15_000, "C1 joined A–C") { c1.mls.engine.group(ac) }
                null
            }
            check("calls: GET /mls/groups says calls_ready; GET /calls/turn is 200 or 503 calls_unavailable (direct ICE only)") {
                for (conv in listOf(cb, ab, ac)) {
                    val g = a1.api.mlsGroup(conv).takeIf { conv != cb } ?: c1.api.mlsGroup(conv)
                    ensure(g is ApiResult.Ok && g.value.callsReady) { "calls_ready $conv: $g" }
                }
                val t = c1.api.callsTurn()
                ensure(t is ApiResult.Ok || (t is ApiResult.Error && t.httpStatus == 503 && t.code == "calls_unavailable")) { "turn: $t" }
                if (t is ApiResult.Ok) "turn 200, ${t.value.iceServers.size} servers" else "turn 503 calls_unavailable"
            }
            var firstCall = ""
            check("calls: C calls B → both of B's devices ring (call_ringing back), C shows Ringing") {
                c1.machine.placeCall(cb)
                await(15_000, "B1 rings") { b1.phase()?.takeIf { it == CallPhase.RINGING_IN } }
                await(15_000, "B2 rings") { b2.phase()?.takeIf { it == CallPhase.RINGING_IN } }
                await(15_000, "C1 Ringing") { c1.phase()?.takeIf { it == CallPhase.RINGING_OUT } }
                firstCall = c1.machine.state.value!!.callId
                ensure(b1.machine.state.value!!.callId == firstCall && b2.machine.state.value!!.callId == firstCall) { "call ids differ" }
                firstCall
            }
            check("calls: B answers on B1 → B2 stops (answered elsewhere), C accepts B1; the SDP passed the strict rules; ICE + DTLS fingerprint check → active on both") {
                b1.machine.answer()
                await(15_000, "B2 stops") { if (b2.phase() != CallPhase.RINGING_IN) Unit else null }
                ensure(b2.machine.state.value == null || b2.notice() == CallNotice.ANSWERED_ELSEWHERE) { b2.why() }
                await(15_000, "C1 connecting") { c1.phase()?.takeIf { it == CallPhase.CONNECTING } }
                await(15_000, "B1 connecting") { b1.phase()?.takeIf { it == CallPhase.CONNECTING } }
                val atC = c1.session().remote!!
                val atB = b1.session().remote!!
                ensure(SdpRules.validate(atC, SdpRules.Role.ANSWER) == null && SdpRules.validate(atB, SdpRules.Role.OFFER) == null) { "SDP rules" }
                ensure(!atC.contains(SdpRules.AUDIO_LEVEL) && !atB.contains(SdpRules.AUDIO_LEVEL)) { "audio level not stripped" }
                ensure(SdpRules.sameFingerprint(SdpRules.fingerprint(atC), b1.session().fp) && SdpRules.sameFingerprint(SdpRules.fingerprint(atB), c1.session().fp)) { "fingerprints" }
                c1.session().candidate(1)
                b1.session().candidate(2)
                await(10_000, "B1 got C's candidate") { b1.session().remoteCandidates.firstOrNull() }
                await(10_000, "C1 got B1's candidate") { c1.session().remoteCandidates.firstOrNull() }
                ensure(b2.media.sessions.isEmpty()) { "B2 opened media without answering" }
                c1.session().listener.onIceState(IceState.CONNECTED)
                b1.session().listener.onIceState(IceState.CONNECTED)
                await(10_000, "C1 active") { c1.phase()?.takeIf { it == CallPhase.ACTIVE } }
                await(10_000, "B1 active") { b1.phase()?.takeIf { it == CallPhase.ACTIVE } }
                ensure(c1.machine.state.value!!.verified && b1.machine.state.value!!.verified) { "not verified" }
                null
            }
            check("calls: C hangs up → durable call_end; one line per call id on every device (C: own, B1, B2)") {
                delay(1_100)
                c1.machine.hangUp()
                await(15_000, "B1 ended") { if (b1.machine.state.value == null || b1.phase() == CallPhase.ENDED) Unit else null }
                for (d in listOf(c1, b1, b2)) {
                    val l = await(15_000, "${d.name} line") { d.lines(cb).firstOrNull { it.callId == firstCall } }
                    ensure(l.body.startsWith("Voice call · ") && d.lines(cb).count { it.callId == firstCall } == 1) { "${d.name}: ${l.body}" }
                }
                ensure(c1.lines(cb).single { it.callId == firstCall }.outgoing) { "C's own line" }
                b1.lines(cb).single { it.callId == firstCall }.body
            }
            check("calls: the missed-call line — B calls C from B1 (B2 never rings for its own user's call), B cancels → 'Missed voice call' unread at C, 'No answer' on B1 and B2") {
                idle(b1, c1)
                b1.machine.placeCall(cb)
                await(15_000, "C1 rings") { c1.phase()?.takeIf { it == CallPhase.RINGING_IN } }
                ensure(b2.phase() != CallPhase.RINGING_IN) { "B2 rang for its own user's call: ${b2.why()}" }
                val id = b1.machine.state.value!!.callId
                b1.machine.hangUp()
                val l = await(15_000, "C1 missed line") { c1.lines(cb).firstOrNull { it.callId == id } }
                ensure(l.body == "Missed voice call" && l.status == "DELIVERED" && !l.outgoing) { "C1: $l" }
                await(5_000, "C1 stopped ringing") { if (c1.phase() != CallPhase.RINGING_IN) Unit else null }
                ensure(c1.missed.isNotEmpty()) { "no missed-call notification" }
                for (d in listOf(b1, b2)) ensure(await(15_000, "${d.name} line") { d.lines(cb).firstOrNull { it.callId == id } }.body == "Voice call · No answer") { "${d.name}'s line" }
                null
            }
            check("calls: A calls B, B2 declines → A sees 'Call declined', B1 stops; lines 'Declined voice call' (B) / 'Voice call · Declined' (A)") {
                idle(a1, b1, b2)
                a1.machine.placeCall(ab)
                await(15_000, "B2 rings") { b2.phase()?.takeIf { it == CallPhase.RINGING_IN } }
                await(15_000, "B1 rings") { b1.phase()?.takeIf { it == CallPhase.RINGING_IN } }
                val id = a1.machine.state.value!!.callId
                b2.machine.hangUp()
                await(15_000, "A declined") { a1.notice()?.takeIf { it == CallNotice.DECLINED } }
                await(15_000, "B1 stops") { if (b1.phase() != CallPhase.RINGING_IN) Unit else null }
                ensure(await(15_000, "B1 line") { b1.lines(ab).firstOrNull { it.callId == id } }.body == "Declined voice call") { "B1 line" }
                ensure(await(15_000, "A line") { a1.lines(ab).firstOrNull { it.callId == id } }.body == "Voice call · Declined") { "A line" }
                null
            }
            check("calls: negative fingerprint test (§16.10 f) — B's debug hook tampers A's answer fingerprint → never active, call_end failed") {
                idle(a1, b1, b2)
                b1.tamper = true
                b1.machine.placeCall(ab)
                await(15_000, "A1 rings: ${b1.why()}") { a1.phase()?.takeIf { it == CallPhase.RINGING_IN } }
                val id = b1.machine.state.value!!.callId
                a1.machine.answer()
                await(15_000, "B1 connecting") { b1.phase()?.takeIf { it == CallPhase.CONNECTING } }
                await(15_000, "A1 connecting") { a1.phase()?.takeIf { it == CallPhase.CONNECTING } }
                b1.session().listener.onIceState(IceState.CONNECTED)
                a1.session().listener.onIceState(IceState.CONNECTED)
                await(10_000, "B1 fails") { b1.notice()?.takeIf { it == CallNotice.CANT_CONNECT } }
                ensure(b1.machine.state.value?.verified != true) { "verified a tampered call" }
                val l = await(15_000, "A1 line") { a1.lines(ab).firstOrNull { it.callId == id } }
                b1.tamper = false
                l.body
            }
            check("calls: glare — A and C call each other at once → the lower call_id wins, the loser cancels (glare) and auto-answers") {
                idle(a1, c1)
                listOf(scope.launch { a1.machine.placeCall(ac) }, scope.launch { c1.machine.placeCall(ac) }).forEach { it.join() }
                awaitL(20_000, { "A1 connecting: ${a1.why()} / ${c1.why()}" }) { a1.phase()?.takeIf { it == CallPhase.CONNECTING || it == CallPhase.ANSWERING } }
                awaitL(20_000, { "C1 connecting: ${c1.why()} / ${a1.why()}" }) { c1.phase()?.takeIf { it == CallPhase.CONNECTING || it == CallPhase.ANSWERING } }
                val ids = setOf(a1.machine.state.value!!.callId, c1.machine.state.value!!.callId)
                ensure(ids.size == 1) { "different calls: $ids" }
                ensure(a1.refused.isEmpty() && c1.refused.isEmpty()) { "refused: ${a1.refused} ${c1.refused}" }
                a1.machine.hangUp()
                val winner = ids.single()
                await(15_000, "C1 line for the winner") { c1.lines(ac).firstOrNull { it.callId == winner } }
                ensure(c1.lines(ac).size == 1 && a1.lines(ac).size == 1) { "glare produced a call_end for the loser: ${c1.lines(ac).map { it.body }}" }
                "winner $winner"
            }
            check("calls: ring:true to a user without a calls device is refused (calls_not_ready)") {
                val r = DeviceRegistrar(c1.api, { c1.deviceId }, "0.3.0-interop", { c1.mls.engine }, imagesSupported = { true }, callsSupported = { false }).register(null)
                ensure(r is Registration.Mls) { "re-register: $r" }
                idle(b1)
                b1.machine.placeCall(cb) // refused before the ring limits
                await(10_000, "B1 refused: ${b1.why()}") { b1.notice()?.takeIf { it == CallNotice.NOT_READY } }
                DeviceRegistrar(c1.api, { c1.deviceId }, "0.3.0-interop", { c1.mls.engine }, imagesSupported = { true }, callsSupported = { true }).register(null)
                b1.refused.joinToString()
            }
            // ---- decision 054: nothing survives a crash or a kill; busy only for real ----
            // The checks above ring without place(): let every ring window close first.
            listOf(aUser.first, bUser.first, cUser.first).forEach { lastRing[it] = System.currentTimeMillis() }
            check("calls (054): the caller crashes mid-ring → the callee shows a missed call by itself; the restarted caller sends the owed call_end (one line), and can call again at once") {
                idle(c1, b1, b2)
                place(c1, b1, cb)
                await(15_000, "B1 rings") { b1.phase()?.takeIf { it == CallPhase.RINGING_IN } }
                val id = c1.machine.state.value!!.callId
                val left = c1.crash()
                ensure(left != null && left.outgoing) { "nothing to clean: ${c1.why()}" }
                // B's ring runs out; no call_end comes (C is gone): a local "Missed voice call" after the grace.
                val l = await(RING_MS + CallStateMachine.MISSED_GRACE_MS + 10_000, "B1 local missed line: ${b1.why()}") { b1.lines(cb).firstOrNull { it.callId == id } }
                ensure(l.body == "Missed voice call" && l.status == "DELIVERED") { "B1: $l" }
                ensure(b1.phase() == null) { b1.why() }
                await(10_000, "B2 local missed line") { b2.lines(cb).firstOrNull { it.callId == id } }
                // The caller's next process: the owed call_end (cancelled) goes out; B still has one line.
                c1.revive(left)
                val own = await(15_000, "C1 own line") { c1.lines(cb).firstOrNull { it.callId == id } }
                ensure(own.body == "Voice call · No answer") { "C1: ${own.body}" }
                delay(1_500)
                ensure(b1.lines(cb).count { it.callId == id } == 1) { "B1 lines: ${b1.lines(cb).map { it.body }}" }
                // Not "in a call": a new call rings at once.
                place(c1, b1, cb)
                await(15_000, "B1 rings again: ${c1.why()}") { b1.phase()?.takeIf { it == CallPhase.RINGING_IN } }
                ensure(c1.notice() != CallNotice.IN_ANOTHER_CALL) { c1.why() }
                c1.machine.hangUp()
                await(10_000, "B1 stops") { if (b1.phase() != CallPhase.RINGING_IN) Unit else null }
                id
            }
            check("calls (054): the callee is killed mid-ring → the caller gets 'No answer' at the ring timeout, then calls again and B rings") {
                idle(a1, b1, b2)
                place(a1, b1, ab)
                await(15_000, "B1 rings") { b1.phase()?.takeIf { it == CallPhase.RINGING_IN } }
                await(15_000, "B2 rings") { b2.phase()?.takeIf { it == CallPhase.RINGING_IN } }
                val id = a1.machine.state.value!!.callId
                val l1 = b1.crash()
                val l2 = b2.crash()
                ensure(l1 != null && l2 != null && ActiveCallRecord.endReason(l1) == null) { "a ringing callee owes nothing" }
                await(RING_MS + 10_000, "A1 No answer: ${a1.why()}") { a1.notice()?.takeIf { it == CallNotice.NO_ANSWER } }
                ensure(await(15_000, "A1 line") { a1.lines(ab).firstOrNull { it.callId == id } }.body == "Voice call · No answer") { "A1 line" }
                b1.revive(l1)
                b2.revive(l2)
                // The revived callee gets the durable call_end on its sync: one missed line.
                ensure(await(15_000, "B1 missed line after the restart") { b1.lines(ab).firstOrNull { it.callId == id } }.body == "Missed voice call") { "B1 line" }
                idle(a1)
                place(a1, b1, ab)
                await(15_000, "B1 rings again: ${a1.why()}") { b1.phase()?.takeIf { it == CallPhase.RINGING_IN } }
                b1.machine.hangUp()
                await(15_000, "A declined") { a1.notice()?.takeIf { it == CallNotice.DECLINED } }
                null
            }
            check("calls (054): busy only for real — a stale MODE_IN_COMMUNICATION still rings; a cellular call (MODE_IN_CALL) or an active RisiMe call is busy") {
                idle(a1, b1, b2, c1)
                a1.audioMode = 3
                place(b1, a1, ab)
                await(15_000, "A1 rings despite the stale mode: ${a1.why()}") { a1.phase()?.takeIf { it == CallPhase.RINGING_IN } }
                b1.machine.hangUp()
                await(10_000, "A1 stops") { if (a1.phase() != CallPhase.RINGING_IN) Unit else null }
                idle(a1, b1)
                a1.audioMode = 2
                place(b1, a1, ab)
                await(15_000, "B1 sees busy (cellular): ${b1.why()}") { b1.notice()?.takeIf { it == CallNotice.BUSY } }
                a1.audioMode = 0
                // An active RisiMe call A–C: B's call gets busy.
                idle(a1, c1, b1)
                place(c1, a1, ac)
                await(15_000, "A1 rings (C)") { a1.phase()?.takeIf { it == CallPhase.RINGING_IN } }
                a1.machine.answer()
                await(15_000, "C1 connecting") { c1.phase()?.takeIf { it == CallPhase.CONNECTING } }
                await(15_000, "A1 connecting") { a1.phase()?.takeIf { it == CallPhase.CONNECTING } }
                c1.session().listener.onIceState(IceState.CONNECTED)
                a1.session().listener.onIceState(IceState.CONNECTED)
                await(10_000, "A1 active") { a1.phase()?.takeIf { it == CallPhase.ACTIVE } }
                place(b1, a1, ab)
                await(15_000, "B1 busy (A is in a call): ${b1.why()}") { b1.notice()?.takeIf { it == CallNotice.BUSY } }
                ensure(a1.phase() == CallPhase.ACTIVE) { "A's call was disturbed: ${a1.why()}" }
                a1.machine.hangUp()
                null
            }
        } finally {
            devices.forEach { it.close() }
        }
    }
}
