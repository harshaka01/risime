package lk.codegen.risime.calls

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.dmConversationId
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * The two-device smoke test (§16 on real arm64 Androids): run once per redroid container with
 * `-e role caller|callee -e relayPort <port>`; a line relay on the host (reached through
 * `adb reverse`) stands in for the server. Each side runs the real CallStateMachine with the real
 * libwebrtc; ICE must connect directly between the containers (host candidates on the docker
 * network) and the §16.10 check must pass. Skipped without the arguments.
 */
@RunWith(AndroidJUnit4::class)
class WebRtcTwoDeviceTest {
    private val args = InstrumentationRegistry.getArguments()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val ua = "aaaaaaaa-0000-4000-8000-000000000001"
    private val ub = "bbbbbbbb-0000-4000-8000-000000000002"
    private val devs = mapOf(ua to "a1a1a1a1-0000-4000-8000-000000000001", ub to "b1b1b1b1-0000-4000-8000-000000000003")

    @Test fun oneSideOfADirectCallBetweenTwoDevices() = runBlocking {
        val role = args.getString("role")
        val port = args.getString("relayPort")?.toIntOrNull()
        assumeTrue("two-device run only (-e role caller|callee -e relayPort N)", role != null && port != null)
        val me = if (role == "caller") ua else ub
        val peer = if (role == "caller") ub else ua
        val conv = dmConversationId(ua, ub)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val socket = withContext(Dispatchers.IO) { Socket("127.0.0.1", port!!) }
        val out = PrintWriter(socket.getOutputStream(), true)
        val input = BufferedReader(InputStreamReader(socket.getInputStream()))
        val media = WebRtcCallMedia(ctx, debug = true)
        assertTrue(media.available)
        var machine: CallStateMachine? = null
        val signals = object : CallSignals {
            override suspend fun signal(conversationId: String, peer: String, env: CallEnvelope.Env, media: String): SignalOutcome {
                send(out, "signal", env)
                return SignalOutcome.Ok
            }
            override suspend fun end(conversationId: String, peer: String, env: CallEnvelope.End) = send(out, "end", env)
        }
        val marks = object : CallMarks {
            val rows = ConcurrentHashMap<String, CallMark>()
            override suspend fun get(callId: String) = rows[callId]
            override suspend fun put(mark: CallMark) { rows[mark.callId] = mark }
        }
        val m = CallStateMachine(me, devs[me]!!, scope, media, signals, marks, log = { Log.i("RisiMe", "[$role] $it") })
        machine = m
        // The relay says "go" once both sides are connected.
        val reader = scope.launch(Dispatchers.IO) {
            while (true) {
                val line = input.readLine() ?: break
                if (line == "go") continue
                val o = ProtocolJson.parseToJsonElement(line).jsonObject
                val env = CallEnvelope.decode(o["env"]!!.jsonObject, 0) ?: continue
                if (o["kind"]!!.jsonPrimitive.content == "end") {
                    machine?.onCallEnd(conv, peer, devs[peer], env as CallEnvelope.End)
                } else {
                    machine?.onSignal(InboundCall(conv, peer, devs[peer]!!, System.currentTimeMillis(), env))
                }
            }
        }
        try {
            delay(1_500)
            if (role == "caller") m.placeCall(conv)
            if (role == "callee") {
                wait(30_000, "ringing") { m.state.value?.phase == CallPhase.RINGING_IN }
                m.answer()
            }
            wait(40_000, "active+verified (${m.state.value})") { m.state.value?.phase == CallPhase.ACTIVE && m.state.value?.verified == true }
            val stats = media.lastStats ?: error("no stats")
            Log.i("RisiMe", "[$role] TWO-DEVICE OK pair=${stats.localCandidateType}/${stats.remoteCandidateType} srtp=${stats.srtpCipher}")
            assertEquals("host", stats.localCandidateType)
            assertTrue(stats.remoteCandidateType in setOf("host", "prflx"))
            if (role == "caller") {
                delay(3_000)
                m.hangUp()
            } else {
                wait(20_000, "ended by the caller") { m.state.value == null || m.state.value?.phase == CallPhase.ENDED }
            }
            delay(1_000)
        } finally {
            reader.cancel()
            runCatching { socket.close() }
            scope.cancel()
        }
    }

    private fun send(out: PrintWriter, kind: String, env: CallEnvelope.Env) {
        val line = ProtocolJson.encodeToString(JsonObject.serializer(), buildJsonObject { put("kind", kind); put("env", CallEnvelope.toJson(env)) })
        synchronized(out) { out.println(line) }
    }

    private suspend fun wait(ms: Long, what: String, p: () -> Boolean) {
        if (withTimeoutOrNull(ms) { while (!p()) delay(100); true } == null) throw AssertionError("timed out: $what")
    }
}
