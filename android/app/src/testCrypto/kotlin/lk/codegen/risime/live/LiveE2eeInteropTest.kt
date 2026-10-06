package lk.codegen.risime.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lk.codegen.risime.crypto.RealMls
import lk.codegen.risime.data.BehaviourLog
import lk.codegen.risime.data.ChatEngine
import lk.codegen.risime.data.FakeBehaviourDao
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.FakeSyncDao
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.mls.DeviceRegistrar
import lk.codegen.risime.data.mls.E2eeState
import lk.codegen.risime.data.mls.FakeMlsPendingDao
import lk.codegen.risime.data.mls.MembershipAction
import lk.codegen.risime.data.mls.MembershipExecutor
import lk.codegen.risime.data.mls.MembershipOutcome
import lk.codegen.risime.data.mls.MlsApi
import lk.codegen.risime.data.mls.MlsPipeline
import lk.codegen.risime.data.mls.MlsUpgrader
import lk.codegen.risime.data.mls.Registration
import lk.codegen.risime.net.ApiClient
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.MlsCommitEvent
import lk.codegen.risime.net.MlsCommitRequest
import lk.codegen.risime.net.MlsMembershipEvent
import lk.codegen.risime.net.MsgSend
import lk.codegen.risime.net.MsgSendE2ee
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.dmConversationId
import lk.codegen.risime.realtime.ConnectionState
import lk.codegen.risime.realtime.PhoenixRealtimeClient
import lk.codegen.risime.realtime.PushResult
import lk.codegen.risime.realtime.RealtimeListener
import lk.codegen.risime.realtime.RealtimeSession
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Live E2EE interop (contract v1.7) with the REAL MLS core on the JVM. Runs when
 * RISIME_INTEROP_CONFIG has an "e2ee" block:
 *   "e2ee": {"A": {"id","token"}, "B": {"id","token"}, "L": {"id","token"}}
 * dev tokens; A↔B and A↔L friends; server with an attestation key (E2EE on); phone gate off.
 * Prints "INTEROP PASS|FAIL e2ee: <check>".
 */
class LiveE2eeInteropTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val devices = mutableListOf<Dev>()

    @After fun tearDown() {
        devices.forEach { runCatching { it.client.stop(); it.mls.close() } }
        scope.cancel()
    }

    private fun check(name: String, block: suspend () -> String?) = runBlocking {
        val t0 = System.nanoTime()
        try {
            val d = block()
            println("INTEROP PASS e2ee: $name (${(System.nanoTime() - t0) / 1_000_000} ms)${d?.let { ": $it" } ?: ""}")
        } catch (t: Throwable) {
            println("INTEROP FAIL e2ee: $name: ${t.message}")
            throw AssertionError("e2ee: $name: ${t.message}", t)
        }
    }

    private fun ensure(c: Boolean, m: () -> String) { if (!c) throw AssertionError(m()) }

    private suspend fun <T> await(ms: Long, what: String, probe: () -> T?): T =
        withTimeoutOrNull(ms) { var v = probe(); while (v == null) { delay(50); v = probe() }; v } ?: throw AssertionError("timed out waiting for $what")

    private fun now() = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC).format(Instant.now())

    /** One MLS-capable app instance: real engine + the app's pipeline/outbox + a real socket. */
    private inner class Dev(val url: String, val userId: String, val token: String, val deviceId: String, trusted: List<String>) {
        val api = ApiClient(http, { url }, { token })
        val mls = RealMls.device(userId, deviceId, trusted, attest = false)
        val messages = FakeMessageDao()
        val raw = CopyOnWriteArrayList<Event>()
        val memberships = CopyOnWriteArrayList<MembershipAction>()
        val conflicts = CopyOnWriteArrayList<String>()
        val mlsApi = object : MlsApi {
            override suspend fun group(conversationId: String) = api.mlsGroup(conversationId)
            override suspend fun claim(userIds: List<String>) = api.claimKeyPackages(userIds, deviceId)
            override suspend fun commit(conversationId: String, body: MlsCommitRequest) =
                api.mlsCommit(conversationId, body, deviceId).also { if (it is ApiResult.Error && it.code == AuthErrors.EPOCH_CONFLICT) conflicts += conversationId }
        }
        lateinit var client: PhoenixRealtimeClient
        val chat: ChatEngine = ChatEngine(
            messages = messages, sync = FakeSyncDao(),
            tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
            scope = scope, realtime = { client }, meId = { userId },
            behaviour = BehaviourLog(FakeBehaviourDao(), { "s" }, { 0L }),
            mls = MlsPipeline({ mls.engine }, FakeMlsPendingDao(), onMembership = { memberships += it }, log = { println("  [$deviceId] mls: $it") }),
            mlsEngine = { mls.engine }, catchUp = { catchUp(it) },
        )
        val executor = MembershipExecutor({ mls.engine }, mlsApi) { catchUp(it) }

        init {
            val recorder = object : RealtimeListener {
                override suspend fun cursor() = chat.cursor()
                override suspend fun onEvents(events: List<Event>) { raw += events; chat.onEvents(events) }
                override suspend fun onLive() = chat.onLive()
                override suspend fun onAuthFailed() = Unit
            }
            client = PhoenixRealtimeClient(http, scope, recorder, backoffMs = listOf(200, 500))
            devices += this
        }

        suspend fun catchUp(conv: String) {
            val g = mls.engine.group(conv) ?: return
            val r = api.mlsCommits(conv, g.epoch) as? ApiResult.Ok ?: return
            chat.applyOutOfBand(r.value.commits.map { c ->
                Event("catchup:${c.epoch}", Event.KIND_MLS_COMMIT,
                    ProtocolJson.encodeToJsonElement(MlsCommitEvent.serializer(), MlsCommitEvent(conv, g.generation, c.epoch, c.commit, c.fromDevice)) as JsonObject)
            })
        }

        fun start() = client.start(RealtimeSession(url, userId, deviceId, "0.3.0-interop") { token })

        suspend fun live() = await(15_000, "$deviceId Live") { client.state.value.takeIf { it == ConnectionState.Live } }

        fun bodies() = messages.rows.values.sortedBy { it.localTs }.map { it.body }
    }

    @Test fun liveE2ee() {
        val path = System.getenv("RISIME_INTEROP_CONFIG")
        assumeTrue("RISIME_INTEROP_CONFIG not set", !path.isNullOrBlank() && File(path).isFile)
        RealMls.assumeHostLibrary()
        val root = ProtocolJson.parseToJsonElement(File(path!!).readText()).jsonObject
        val e = root["e2ee"]?.jsonObject
        assumeTrue("no \"e2ee\" block in the interop config", e != null)
        val url = root["url"]!!.jsonPrimitive.content
        fun u(k: String) = e!![k]!!.jsonObject.let { it["id"]!!.jsonPrimitive.content to it["token"]!!.jsonPrimitive.content }
        val (aId, aTok) = u("A"); val (bId, bTok) = u("B"); val (lId, lTok) = u("L")
        val conv = dmConversationId(aId, bId)
        val run = UUID.randomUUID().toString().take(8)
        var trusted = emptyList<String>()

        check("attestation keys served") {
            val r = ApiClient(http, { url }, { null }).attestationKeys()
            ensure(r is ApiResult.Ok && r.value.keys.isNotEmpty()) { "GET /mls/attestation_keys: $r (is E2EE on?)" }
            trusted = (r as ApiResult.Ok).value.keys.map { it.toString() }
            "${trusted.size} key(s)"
        }
        val a1 = Dev(url, aId, aTok, "a1-$run", trusted)
        val a2 = Dev(url, aId, aTok, "a2-$run", trusted)
        val b1 = Dev(url, bId, bTok, "b1-$run", trusted)

        check("registration: attestation verifies in the core, key packages uploaded") {
            for (d in listOf(a1, a2, b1)) {
                val r = DeviceRegistrar(d.api, { d.deviceId }, "0.3.0-interop", { d.mls.engine }).register(pushToken = null)
                ensure(r is Registration.Mls && r.keyPackages >= 20) { "${d.deviceId}: $r" }
                val c = d.api.keyPackageCount(d.deviceId)
                ensure(c is ApiResult.Ok && c.value.count >= 20) { "${d.deviceId} count: $c" }
            }
            null
        }
        listOf(a1, a2, b1).forEach { it.start() }
        runBlocking { listOf(a1, a2, b1).forEach { it.live() } }

        check("A opens the DM → claim → epoch-0 commit + Welcome → B and A's tablet join") {
            val s = MlsUpgrader({ a1.mls.engine }, a1.mlsApi).ensure(conv, aId, bId)
            ensure(s is E2eeState.Encrypted) { "upgrade: $s" }
            await(15_000, "B joined") { b1.mls.engine.group(conv) }
            await(15_000, "A's tablet joined") { a2.mls.engine.group(conv) }
            "epoch ${(s as E2eeState.Encrypted).epoch}"
        }
        check("encrypted both ways; stored events carry no body") {
            a1.chat.sendText(bId, "hello B $run")
            await(15_000, "B decrypts") { b1.bodies().firstOrNull { it == "hello B $run" } }
            await(15_000, "A's tablet sees A's message") { a2.bodies().firstOrNull { it == "hello B $run" } }
            b1.chat.sendText(aId, "hi A $run")
            await(15_000, "A decrypts") { a1.bodies().firstOrNull { it == "hi A $run" } }
            val msgEvents = (a1.raw + b1.raw + a2.raw).mapNotNull { it.messageData() }.filter { it.conversationId == conv }
            ensure(msgEvents.isNotEmpty() && msgEvents.all { it.body == null && it.ciphertext != null }) { "plaintext in e2ee events: $msgEvents" }
            null
        }
        check("plaintext refused: e2ee_required") {
            val r = a1.client.sendMessage(MsgSend(UUID.randomUUID().toString(), bId, "plain", now()))
            ensure(r == PushResult.Rejected(AuthErrors.E2EE_REQUIRED)) { "got $r" }
            null
        }
        check("stale_epoch, then a retry at the current epoch") {
            val g = a1.mls.engine.group(conv)!!
            val ct = Base64.getEncoder().encodeToString(a1.mls.engine.encrypt(conv, "stale".toByteArray()))
            val r = a1.client.sendEncrypted(MsgSendE2ee(UUID.randomUUID().toString(), bId, ct, g.generation, g.epoch + 5, now()))
            ensure(r == PushResult.Rejected(AuthErrors.STALE_EPOCH)) { "got $r" }
            val ct2 = Base64.getEncoder().encodeToString(a1.mls.engine.encrypt(conv, "fresh".toByteArray()))
            val ok = a1.client.sendEncrypted(MsgSendE2ee(UUID.randomUUID().toString(), bId, ct2, g.generation, g.epoch, now()))
            ensure(ok is PushResult.Ok) { "retry: $ok" }
            null
        }
        check("new device: concurrent adds → exactly one epoch_conflict, then converged") {
            val a3 = Dev(url, aId, aTok, "a3-$run", trusted)
            val reg = DeviceRegistrar(a3.api, { a3.deviceId }, "0.3.0-interop", { a3.mls.engine }).register(null)
            ensure(reg is Registration.Mls) { "a3: $reg" }
            a3.start(); a3.live()
            val action = MembershipAction(MlsMembershipEvent(conv, aId, a3.deviceId, "added"), 0)
            val (r1, r2) = listOf(scope.async { a1.executor.execute(action) }, scope.async { b1.executor.execute(action) }).map { it.await() }
            val outcomes = listOf(r1, r2)
            ensure(outcomes.count { it == MembershipOutcome.Done } == 1) { "outcomes $outcomes" }
            ensure(a1.conflicts.size + b1.conflicts.size == 1) { "conflicts a1=${a1.conflicts} b1=${b1.conflicts}" }
            await(15_000, "a3 joined") { a3.mls.engine.group(conv) }
            a1.chat.sendText(bId, "four devices $run")
            await(15_000, "a3 decrypts") { a3.bodies().firstOrNull { it == "four devices $run" } }
            "winner=${if (r1 == MembershipOutcome.Done) "A" else "B"}"
        }
        check("A's tablet removed → it can't decrypt new messages") {
            val del = a2.api.deleteDevice(a2.deviceId)
            ensure(del is ApiResult.Ok) { "DELETE device: $del" }
            val m = await(15_000, "mls_membership removed at A's phone") {
                a1.memberships.firstOrNull { it.event.deviceId == a2.deviceId && it.event.change == "removed" }
            }
            val r = a1.executor.execute(m.copy(delayMs = 0))
            ensure(r == MembershipOutcome.Done || r == MembershipOutcome.NotNeeded) { "remove: $r" }
            await(15_000, "tablet sees removed_self") { a2.mls.engine.group(conv)?.let { null } ?: Unit }
            a1.chat.sendText(bId, "after removal $run")
            await(15_000, "B decrypts") { b1.bodies().firstOrNull { it == "after removal $run" } }
            delay(1_500)
            ensure(a2.bodies().none { it == "after removal $run" }) { "removed tablet decrypted a new message" }
            null
        }
        check("a legacy app keeps a conversation not ready") {
            // L connects like a pre-v1.7 app: no device_id, no app_version.
            val legacy = PhoenixRealtimeClient(http, scope, object : RealtimeListener {
                override suspend fun cursor(): String? = null
                override suspend fun onEvents(events: List<Event>) = Unit
                override suspend fun onLive() = Unit
                override suspend fun onAuthFailed() = Unit
            })
            legacy.start(RealtimeSession(url, lTok, lId))
            await(15_000, "L Live") { legacy.state.value.takeIf { it == ConnectionState.Live } }
            legacy.stop()
            val g = a1.api.mlsGroup(dmConversationId(aId, lId))
            ensure(g is ApiResult.Ok && !g.value.ready && g.value.missing.any { it.userId == lId }) { "A–L group: $g" }
            "missing=${(g as ApiResult.Ok).value.missing.map { it.reason }}"
        }
    }
}
