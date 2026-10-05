package lk.codegen.risime.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.MsgSend
import lk.codegen.risime.net.ProtocolJson
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Drives the client against a scripted fake Phoenix server on MockWebServer. */
class PhoenixRealtimeClientTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val received = Collections.synchronizedList(mutableListOf<PhoenixFrame>())
    private val applied = Collections.synchronizedList(mutableListOf<String>())
    private val live = CountDownLatch(1)
    private var cursor: String? = "e0"
    private val serverSockets = Collections.synchronizedList(mutableListOf<WebSocket>())

    private val listener = object : RealtimeListener {
        override suspend fun cursor() = cursor
        override suspend fun onEvents(events: List<Event>) {
            events.forEach { applied += it.eventId; cursor = it.eventId }
        }
        override suspend fun onLive() = live.countDown()
        override suspend fun onAuthFailed() = Unit
    }

    private fun event(id: String) =
        """{"event_id":"$id","kind":"status","data":{"message_id":"m","client_msg_id":"c","conversation_id":"dm:a_b","status":"read","by":"b","at":"2026-10-06T08:15:31.002Z"}}"""

    private fun reply(f: PhoenixFrame, status: String, response: String) =
        """["${f.joinRef}","${f.ref}","${f.topic}","phx_reply",{"status":"$status","response":$response}]"""

    /** Fake server: join returns page 1 (has_more), sync returns page 2, msg:send is acked. */
    private inner class FakeServer : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            serverSockets += webSocket
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val f = PhoenixFrame.decode(text)!!
            received += f
            when (f.event) {
                "phx_join" -> {
                    webSocket.send(reply(f, "ok", """{"events":[${event("e1")},${event("e2")}],"has_more":true,"server_time":"x"}"""))
                    // A live push that arrives while the client is still syncing must be applied after the sync.
                    webSocket.send("""[null,null,"${f.topic}","event",${event("e4")}]""")
                }
                "sync" -> webSocket.send(reply(f, "ok", """{"events":[${event("e3")}],"has_more":false,"server_time":"x"}"""))
                "msg:send" -> {
                    val to = (f.payload as JsonObject)["to"]!!.jsonPrimitive.content
                    if (to == "nobody") {
                        webSocket.send(reply(f, "error", """{"reason":"unknown_recipient"}"""))
                    } else {
                        webSocket.send(reply(f, "ok", """{"message_id":"m1","conversation_id":"dm:a_b","server_ts":"t"}"""))
                    }
                }
                "heartbeat" -> webSocket.send(reply(f, "ok", "{}"))
            }
        }
    }

    @Before
    fun setUp() {
        server.enqueue(MockResponse().withWebSocketUpgrade(FakeServer()))
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun joinWithCursorThenSyncLoopThenLivePushAndSend() = runBlocking {
        val client = PhoenixRealtimeClient(OkHttpClient(), scope, listener, heartbeatMs = 60_000)
        client.start(RealtimeSession(server.url("/").toString(), "tok", "u1"))
        assertTrue(live.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { client.state.first { it == ConnectionState.Live } }

        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/socket/websocket?token=tok&vsn=2.0.0"))

        val join = received.first { it.event == "phx_join" }
        assertEquals("inbox:u1", join.topic)
        assertEquals(JsonPrimitive("e0"), join.payload.jsonObject["since"])
        val sync = received.first { it.event == "sync" }
        assertEquals(JsonPrimitive("e2"), sync.payload.jsonObject["since"])

        withTimeout(5_000) { while (applied.size < 4) kotlinx.coroutines.delay(10) }
        assertEquals(listOf("e1", "e2", "e3", "e4"), applied.toList())

        val ok = client.sendMessage(MsgSend("c1", "u2", "hi", "2026-10-06T08:15:30.123Z"))
        assertEquals("m1", (ok as PushResult.Ok).value.messageId)
        val bad = client.sendMessage(MsgSend("c2", "nobody", "hi", "2026-10-06T08:15:30.123Z"))
        assertEquals(PushResult.Rejected("unknown_recipient"), bad)
        val sent = received.first { it.event == "msg:send" }
        assertEquals(join.joinRef, sent.joinRef)
        client.stop()
    }

    /** Server closes the socket after the first sync; the client rejoins with the advanced cursor. */
    @Test
    fun reconnectsAfterCloseAndRejoinsWithCursor() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(FakeServer()))
        val client = PhoenixRealtimeClient(OkHttpClient(), scope, listener, backoffMs = listOf(50), heartbeatMs = 60_000)
        client.start(RealtimeSession(server.url("/").toString(), "tok", "u1"))
        withTimeout(5_000) { while (applied.size < 4) kotlinx.coroutines.delay(10) }
        // Drop the first connection from the server side.
        server.takeRequest()
        withTimeout(5_000) { while (received.none { it.event == "phx_join" }) kotlinx.coroutines.delay(10) }
        serverSockets.first().close(1000, "bye")
        withTimeout(5_000) { while (received.count { it.event == "phx_join" } < 2) kotlinx.coroutines.delay(10) }
        val rejoin = received.filter { it.event == "phx_join" }[1]
        assertEquals(JsonPrimitive("e4"), rejoin.payload.jsonObject["since"])
        client.stop()
    }

    @Test
    fun sendWhileDisconnectedIsUnavailable() = runBlocking {
        val client = PhoenixRealtimeClient(OkHttpClient(), scope, listener)
        assertEquals(PushResult.Unavailable, client.sendMessage(MsgSend("c1", "u2", "hi", "t")))
    }

    @Test
    fun socketUrl() {
        assertEquals(
            "http://10.0.2.2:4400/socket/websocket?token=a%2Bb&vsn=2.0.0",
            PhoenixRealtimeClient.socketUrl(RealtimeSession("http://10.0.2.2:4400/", "a+b", "u")),
        )
        // keep ProtocolJson referenced for frame parsing
        assertTrue(ProtocolJson.configuration.ignoreUnknownKeys)
    }
}
