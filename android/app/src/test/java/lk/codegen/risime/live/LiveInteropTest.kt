package lk.codegen.risime.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import lk.codegen.risime.net.ApiClient
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.MsgSend
import lk.codegen.risime.net.Presence
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.Signal
import lk.codegen.risime.realtime.AuthRefusalHandler
import lk.codegen.risime.realtime.ConnectionState
import lk.codegen.risime.realtime.PhoenixRealtimeClient
import lk.codegen.risime.realtime.PushResult
import lk.codegen.risime.realtime.RealtimeListener
import lk.codegen.risime.realtime.RealtimeSession
import lk.codegen.risime.realtime.SignalSink
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Opt-in live interop against a real RisiMe server, with the app's real ApiClient and
 * PhoenixRealtimeClient. Skipped unless RISIME_INTEROP_CONFIG points to the JSON root writes:
 * {"url", "dev": {"a": {"id","token"}, "b": {...}}, "jwt": {"A","A2","B","Bshort","Z"},
 *  "ids": {"A","B"}, "bshort_exp": <unix s>}
 * Prints one "INTEROP PASS|FAIL <check>" line per check. Takes up to ~4 min (Bshort expiry).
 *
 *   RISIME_INTEROP_CONFIG=/path/cfg.json ./gradlew testDebugUnitTest --tests '*LiveInteropTest*'
 */
class LiveInteropTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val clients = mutableListOf<PhoenixRealtimeClient>()

    @After fun tearDown() {
        clients.forEach { it.stop() }
        scope.cancel()
    }

    // ---- harness ----

    private class Cfg(o: JsonObject) {
        val url = o["url"]!!.jsonPrimitive.content
        private val dev = o["dev"]!!.jsonObject
        val devA = dev["a"]!!.jsonObject.let { it["id"]!!.jsonPrimitive.content to it["token"]!!.jsonPrimitive.content }
        val devB = dev["b"]!!.jsonObject.let { it["id"]!!.jsonPrimitive.content to it["token"]!!.jsonPrimitive.content }
        private val jwt = o["jwt"]!!.jsonObject
        fun jwt(k: String) = jwt[k]!!.jsonPrimitive.content
        private val ids = o["ids"]!!.jsonObject
        fun id(k: String) = ids[k]!!.jsonPrimitive.content
        val bshortExp: Long? = o["bshort_exp"]?.jsonPrimitive?.long
    }

    private fun check(name: String, block: suspend () -> String?) = runBlocking {
        val started = System.nanoTime()
        try {
            val detail = block()
            val ms = (System.nanoTime() - started) / 1_000_000
            println("INTEROP PASS $name (${ms} ms)${detail?.let { ": $it" } ?: ""}")
        } catch (t: Throwable) {
            println("INTEROP FAIL $name: ${t.message}")
            throw AssertionError("$name: ${t.message}", t)
        }
    }

    private fun ensure(cond: Boolean, msg: () -> String) {
        if (!cond) throw AssertionError(msg())
    }

    private suspend fun <T> await(timeoutMs: Long, what: String, probe: () -> T?): T =
        withTimeoutOrNull(timeoutMs) {
            var v = probe()
            while (v == null) {
                delay(50)
                v = probe()
            }
            v
        } ?: throw AssertionError("timed out after $timeoutMs ms waiting for $what")

    private fun api(url: String, token: String?) = ApiClient(http, { url }, { token })

    private fun now() = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC).format(Instant.now())

    /** One realtime participant: the real client plus what it received. */
    private inner class Peer(
        val name: String,
        val userId: String,
        url: String,
        token: (Boolean) -> String?,
        refusals: AuthRefusalHandler? = null,
    ) {
        val events = CopyOnWriteArrayList<Event>()
        val signals = CopyOnWriteArrayList<Signal>()
        val snapshots = CopyOnWriteArrayList<List<Presence>>()
        val tokenAsks = CopyOnWriteArrayList<Boolean>()
        @Volatile var cursor: String? = null
        @Volatile var lives = 0
        private val session = RealtimeSession(url, userId) { force -> tokenAsks += force; token(force) }
        val client = PhoenixRealtimeClient(
            http, scope,
            object : RealtimeListener {
                override suspend fun cursor() = cursor
                override suspend fun onEvents(events: List<Event>) {
                    this@Peer.events += events
                    events.lastOrNull()?.let { cursor = it.eventId }
                }
                override suspend fun onLive() { lives++ }
                override suspend fun onAuthFailed() = Unit
            },
            backoffMs = listOf(200, 500, 1_000),
            signals = object : SignalSink {
                override fun onPresenceSnapshot(presences: List<Presence>) { snapshots += presences }
                override fun onSignal(signal: Signal) { signals += signal }
                override fun onDisconnected() = Unit
            },
            refusals = refusals,
        ).also { clients += it }

        fun start() = client.start(session)
        fun stop() = client.stop()

        suspend fun live(timeoutMs: Long = 15_000) = await(timeoutMs, "$name Live") { client.state.value.takeIf { it == ConnectionState.Live } }

        fun messages() = events.mapNotNull { e -> e.messageData()?.let { e to it } }
        fun statuses() = events.mapNotNull { it.statusData() }
    }

    @Test fun liveInterop() {
        val path = System.getenv("RISIME_INTEROP_CONFIG")
        assumeTrue("RISIME_INTEROP_CONFIG not set: live interop skipped", !path.isNullOrBlank() && File(path).isFile)
        val cfg = Cfg(ProtocolJson.parseToJsonElement(File(path!!).readText()).jsonObject)
        val (aId, aTok) = cfg.devA
        val (bId, bTok) = cfg.devB
        println("INTEROP server ${cfg.url}")

        // ---- REST ----
        check("auth/config modes") {
            val r = api(cfg.url, null).authConfig()
            ensure(r is ApiResult.Ok) { "auth/config failed: $r" }
            val modes = (r as ApiResult.Ok).value.modes
            ensure(modes.isNotEmpty()) { "empty modes" }
            "modes=$modes phone_verification=${r.value.phoneVerification}"
        }
        check("REST /me (dev a)") {
            val r = api(cfg.url, aTok).me()
            ensure(r is ApiResult.Ok && r.value.user.id == aId) { "unexpected /me: $r" }
            null
        }
        check("REST /contacts (dev a)") {
            val r = api(cfg.url, aTok).contacts()
            ensure(r is ApiResult.Ok) { "contacts failed: $r" }
            val list = (r as ApiResult.Ok).value.contacts
            ensure(list.any { it.userId == bId && it.registered }) { "b ($bId) not a registered contact of a" }
            "${list.size} contacts"
        }

        // ---- dev-token chat ----
        val a = Peer("devA", aId, cfg.url, { aTok })
        val b = Peer("devB", bId, cfg.url, { bTok })
        a.start(); b.start()
        runBlocking { a.live(); b.live() }

        val cmid = UUID.randomUUID().toString()
        var messageId = ""
        check("chat A→B delivered → read") {
            val r = a.client.sendMessage(MsgSend(cmid, bId, "interop hello", now()))
            ensure(r is PushResult.Ok) { "msg:send: $r" }
            messageId = (r as PushResult.Ok).value.messageId
            await(10_000, "B receives the message") { b.messages().firstOrNull { it.second.messageId == messageId } }
            ensure(b.client.ack(listOf(messageId), "delivered") is PushResult.Ok) { "delivered ack failed" }
            await(10_000, "A sees delivered") { a.statuses().firstOrNull { it.messageId == messageId && it.status == "delivered" } }
            ensure(b.client.ack(listOf(messageId), "read") is PushResult.Ok) { "read ack failed" }
            await(10_000, "A sees read") { a.statuses().firstOrNull { it.messageId == messageId && it.status == "read" } }
            null
        }
        check("idempotent resend (same client_msg_id)") {
            val r = a.client.sendMessage(MsgSend(cmid, bId, "interop hello", now()))
            ensure(r is PushResult.Ok && r.value.messageId == messageId) { "resend gave $r, expected $messageId" }
            delay(1_500)
            val copies = b.messages().filter { it.second.messageId == messageId }.map { it.first.eventId }.toSet()
            ensure(copies.size == 1) { "B got ${copies.size} distinct events for one message" }
            null
        }
        check("empty_body rejected") {
            val r = a.client.sendMessage(MsgSend(UUID.randomUUID().toString(), bId, "", now()))
            ensure(r == PushResult.Rejected("empty_body")) { "expected empty_body, got $r" }
            null
        }
        check("offline catch-up of 3 in order") {
            b.stop()
            delay(300)
            val bodies = (1..3).map { "catch-up $it ${UUID.randomUUID().toString().take(6)}" }
            bodies.forEach { body ->
                ensure(a.client.sendMessage(MsgSend(UUID.randomUUID().toString(), bId, body, now())) is PushResult.Ok) { "send '$body' failed" }
            }
            b.start()
            b.live()
            val got = await(15_000, "B catches up 3") {
                b.messages().map { it.second.body }.filter { it in bodies }.distinct().takeIf { it.size == 3 }
            }
            ensure(got == bodies) { "order $got != $bodies" }
            null
        }

        // ---- presence + typing ----
        check("presence snapshot + online signal") {
            a.client.setWatch(setOf(bId))
            val snap = await(10_000, "watch reply") { a.snapshots.lastOrNull()?.firstOrNull { it.userId.equals(bId, true) } }
            ensure(snap.online) { "B should be online in the snapshot: $snap" }
            null
        }
        check("typing true/false without moving the cursor") {
            val cursorBefore = a.cursor
            val eventsBefore = a.events.size
            ensure(b.client.typing(aId, true) is PushResult.Ok) { "typing true push failed" }
            await(10_000, "A typing:true") { a.signals.firstOrNull { it.typing()?.let { t -> t.from.equals(bId, true) && t.typing } == true } }
            ensure(b.client.typing(aId, false) is PushResult.Ok) { "typing false push failed" }
            await(10_000, "A typing:false") { a.signals.firstOrNull { it.typing()?.let { t -> t.from.equals(bId, true) && !t.typing } == true } }
            ensure(a.cursor == cursorBefore && a.events.size == eventsBefore) { "typing moved A's cursor/events" }
            null
        }
        check("offline after the 5 s grace, online again on reconnect") {
            a.signals.clear()
            val stoppedAt = System.currentTimeMillis()
            b.stop()
            await(20_000, "B offline signal") { a.signals.firstOrNull { it.presence()?.let { p -> p.userId.equals(bId, true) && !p.online } == true } }
            val after = System.currentTimeMillis() - stoppedAt
            ensure(after >= 4_000) { "offline after only $after ms (grace is 5 s)" }
            b.start()
            b.live()
            await(15_000, "B online signal") { a.signals.firstOrNull { it.presence()?.let { p -> p.userId.equals(bId, true) && p.online } == true } }
            "offline after $after ms"
        }
        a.stop(); b.stop()

        // ---- JWT path ----
        val jA = cfg.id("A")
        val jB = cfg.id("B")
        check("JWT first-use mapping (GET /me as A)") {
            val r = api(cfg.url, cfg.jwt("A")).me()
            ensure(r is ApiResult.Ok && r.value.user.id == jA) { "unexpected /me for JWT A: $r (expected id $jA)" }
            ensure((r as ApiResult.Ok).value.user.phoneVerified) { "JWT A is phone_unverified; the socket would be refused" }
            null
        }
        check("JWT Z → 403 not_allowlisted + refused upgrade") {
            val r = api(cfg.url, cfg.jwt("Z")).me()
            ensure(r is ApiResult.Error && r.httpStatus == 403 && r.code == AuthErrors.NOT_ALLOWLISTED) { "expected 403 not_allowlisted, got $r" }
            val z = Peer("jwtZ", "zzzz", cfg.url, { cfg.jwt("Z") }, refusals = { false })
            z.start()
            await(15_000, "Z AuthFailed") { z.client.state.value.takeIf { it == ConnectionState.AuthFailed } }
            z.stop()
            null
        }
        val ja = Peer("jwtA", jA, cfg.url, { cfg.jwt("A") }, refusals = { throw AssertionError("jwtA refused") })
        ja.start()
        runBlocking { ja.live() }
        check("auth:refresh same user ok, other user identity_mismatch") {
            val ok = ja.client.refreshAuth(cfg.jwt("A2"))
            ensure(ok is PushResult.Ok) { "A2 refresh: $ok" }
            val bad = ja.client.refreshAuth(cfg.jwt("B"))
            ensure(bad == PushResult.Rejected(AuthErrors.IDENTITY_MISMATCH)) { "B on A's socket: $bad" }
            "expires_at=${(ok as PushResult.Ok).value.expiresAt}"
        }
        check("Bshort expiry → auth:expired → forced refresh → Live → delivery") {
            cfg.bshortExp?.let { exp ->
                val left = exp - Instant.now().epochSecond
                ensure(left > 5) { "Bshort already expired or about to (exp in $left s); root must mint it closer to the run" }
            }
            var refused = false
            val jb = Peer("jwtB", jB, cfg.url, { force -> if (force) cfg.jwt("B") else cfg.jwt("Bshort") }, refusals = { refused = true; true })
            jb.start()
            jb.live()
            ensure(jb.tokenAsks.toList() == listOf(false) && !refused) {
                "B didn't connect on Bshort without a refresh (asks=${jb.tokenAsks}, refused=$refused): Bshort expired at connect?"
            }
            val t0 = System.currentTimeMillis()
            await(180_000, "auth:expired → forced refresh") { jb.tokenAsks.takeIf { true in it } }
            ensure(!refused) { "the socket was refused instead of receiving auth:expired" }
            val waited = (System.currentTimeMillis() - t0) / 1000
            await(20_000, "B Live again") { jb.client.state.value.takeIf { it == ConnectionState.Live && jb.lives >= 2 } }
            val body = "after refresh ${UUID.randomUUID().toString().take(6)}"
            val r = ja.client.sendMessage(MsgSend(UUID.randomUUID().toString(), jB, body, now()))
            ensure(r is PushResult.Ok) { "A→B after refresh: $r" }
            await(10_000, "B receives after refresh") { jb.messages().firstOrNull { it.second.body == body } }
            jb.stop()
            "auth:expired after ${waited}s"
        }
        ja.stop()

        check("bad token → AuthFailed") {
            val bad = Peer("bad", "nobody", cfg.url, { "not-a-token" })
            bad.start()
            await(15_000, "AuthFailed") { bad.client.state.value.takeIf { it == ConnectionState.AuthFailed } }
            bad.stop()
            null
        }
    }
}
