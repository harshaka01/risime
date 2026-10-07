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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
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
        devices.forEach { runCatching { it.client.stop(); it.mls.close(); it.img.close() } }
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
    private inner class Dev(val url: String, val userId: String, val token: String, val deviceId: String, trusted: List<String>, pageLimit: Int = 500) {
        val api = ApiClient(http, { url }, { token })
        val mls = RealMls.device(userId, deviceId, trusted, attest = false)
        val messages = FakeMessageDao()
        val reactions = lk.codegen.risime.data.FakeReactionDao()
        /** §14: this device's image stack (real media core). */
        val img = LiveImageKit(api, messages, deviceId.take(8))
        val raw = CopyOnWriteArrayList<Event>()
        val memberships = CopyOnWriteArrayList<MembershipAction>()
        /** v1.16 `mls_dm_op` events naming this device (run by the test, like the app's onDmOp). */
        val dmOps = CopyOnWriteArrayList<lk.codegen.risime.net.MlsDmOpEvent>()
        val conflicts = CopyOnWriteArrayList<String>()
        val mlsApi = object : MlsApi {
            override suspend fun group(conversationId: String) = api.mlsGroup(conversationId)
            override suspend fun claim(userIds: List<String>) = api.claimKeyPackages(userIds, deviceId)
            override suspend fun commit(conversationId: String, body: MlsCommitRequest) =
                api.mlsCommit(conversationId, body, deviceId).also { if (it is ApiResult.Error && it.code == AuthErrors.EPOCH_CONFLICT) conflicts += conversationId }
            override suspend fun rejoin(conversationId: String) = api.mlsRejoin(conversationId, deviceId)
            override suspend fun reset(conversationId: String, generation: Long): ApiResult<Long> =
                when (val r = api.resetGroup(conversationId, generation, deviceId)) {
                    is ApiResult.Ok -> ApiResult.Ok(r.value.generation)
                    is ApiResult.Error -> r
                    is ApiResult.NetworkError -> r
                }
        }
        lateinit var client: PhoenixRealtimeClient
        /** §13.2: every page's history_before, in order. */
        val hbs = CopyOnWriteArrayList<String?>()
        @Volatile var freshReplayDone = 0
        val chat: ChatEngine = ChatEngine(
            messages = messages, sync = FakeSyncDao(),
            tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
            scope = scope, realtime = { client }, meId = { userId },
            behaviour = BehaviourLog(FakeBehaviourDao(), { "s" }, { 0L }),
            mls = MlsPipeline({ mls.engine }, FakeMlsPendingDao(), onMembership = { memberships += it }, log = { println("  [$deviceId] mls: $it") }, onDmOp = { dmOps += it }),
            mlsEngine = { mls.engine }, catchUp = { catchUp(it) },
            reactionsDao = reactions,
            onFreshReplayDone = { freshReplayDone++ },
            images = img.repo,
        )

        /** Effective adds on [target] as (reactor, emoji). */
        fun adds(target: String) = reactions.rows.values.filter { it.targetMessageId == target && it.op == "add" }.map { it.reactorUserId to it.emoji }.toSet()
        val executor = MembershipExecutor({ mls.engine }, mlsApi) { catchUp(it) }

        init {
            val recorder = object : RealtimeListener {
                override suspend fun cursor() = chat.cursor()
                override suspend fun onHistoryBefore(ts: String?) { hbs += ts; chat.onHistoryBefore(ts) }
                override suspend fun onEvents(events: List<Event>) { raw += events; chat.onEvents(events) }
                override suspend fun onLive() = chat.onLive()
                override suspend fun onAuthFailed() = Unit
            }
            client = PhoenixRealtimeClient(http, scope, recorder, backoffMs = listOf(200, 500), pageLimit = pageLimit)
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
        // P0-1: the pilot shape. Before (re)installing, each user had an old pre-v1.7 app (no
        // device_id) and an earlier install whose device id is no longer registered. Those census
        // rows can't receive anything and must not block the 1:1 upgrade below (§12.1 rule).
        check("stale census rows (pre-v1.7 socket, dead reinstall device id) recorded before registration") {
            for ((id, tok) in listOf(aId to aTok, bId to bTok)) {
                for (dev in listOf<String?>(null, UUID.randomUUID().toString())) {
                    val old = PhoenixRealtimeClient(http, scope, object : RealtimeListener {
                        override suspend fun cursor(): String? = null
                        override suspend fun onEvents(events: List<Event>) = Unit
                        override suspend fun onLive() = Unit
                        override suspend fun onAuthFailed() = Unit
                    })
                    old.start(RealtimeSession(url, id, dev, dev?.let { "0.2.0-nightly.7" }) { tok })
                    await(15_000, "stale ${dev ?: "no-device"} Live") { old.state.value.takeIf { it == ConnectionState.Live } }
                    old.stop()
                }
            }
            delay(50) // registration below is strictly later than these rows
            "2 per user"
        }
        val a1 = Dev(url, aId, aTok, java.util.UUID.randomUUID().toString(), trusted)
        val a2 = Dev(url, aId, aTok, java.util.UUID.randomUUID().toString(), trusted)
        val b1 = Dev(url, bId, bTok, java.util.UUID.randomUUID().toString(), trusted)

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

        check("stale rows don't block: GET /mls/groups says ready with nothing missing") {
            val g = a1.api.mlsGroup(conv)
            ensure(g is ApiResult.Ok && g.value.ready && g.value.missing.isEmpty()) { "A–B readiness: $g" }
            null
        }
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
        // ---- §14 images (v1.11) in the e2ee DM ----
        var imgId = ""
        var imgSha = ""
        check("image DM: A sends; B gets the thumbnail first, then downloads and decrypts with a SHA-256 match") {
            val (id, sha) = a1.img.send(conv, aId, bId, "Site visit $run")
            imgId = id; imgSha = sha
            a1.chat.flushOutbox()
            await(15_000, "A's image accepted") { a1.messages.rows[id]?.takeIf { it.status != "PENDING" } }
            ensure(a1.messages.rows[id]!!.status in listOf("SENT", "DELIVERED", "READ")) { "A's image: ${a1.messages.rows[id]}" }
            val row = await(15_000, "B stores the envelope") { b1.img.media.rows.value[id] }
            // Thumbnail first: sealed, clean, ≤ 4096 bytes, before any download.
            ensure(row.state == "NONE" && row.fileName == null) { "B row ${row.state}" }
            val t = b1.img.repo.thumb(id, row) ?: throw AssertionError("no thumbnail")
            ensure(t.data.size <= 4096 && lk.codegen.risime.data.media.ImageBytes.findMetadata(t.data).isEmpty()) { "thumb ${t.data.size}" }
            val bm = b1.messages.rows[id]!!
            ensure(bm.image && bm.body == "Site visit $run" && bm.blobId == row.blobId) { "B message $bm" }
            ensure(b1.img.fetch(id) == sha) { "B's plaintext differs" }
            // The stored event is ordinary ciphertext: no blob id, mime or size visible.
            val ev = b1.raw.first { it.messageData()?.clientMsgId == id }
            ensure(ev.messageData()!!.body == null && row.blobId!! !in ev.data.toString()) { "event leaks: ${ev.data.keys}" }
            "${row.blobSize} bytes, ${row.w}x${row.h}"
        }
        check("image DM: the sender's other device gets it as its own and downloads it") {
            val row = await(15_000, "A's tablet stores it") { a2.img.media.rows.value[imgId] }
            ensure(row.outgoing && a2.messages.rows[imgId]!!.outgoing) { "tablet row not outgoing" }
            ensure(a2.img.fetch(imgId) == imgSha) { "tablet plaintext differs" }
            null
        }
        check("image DM: a repeat upload with the same client_blob_id returns the same blob") {
            val m = a1.img.media.get(imgId)!!
            val again = a1.api.uploadMediaBlob(conv, m.clientBlobId!!, a1.img.files.file(m.fileName!!))
            ensure(again is ApiResult.Ok && again.value.blobId == m.blobId && again.value.sha256 == m.blobSha256) { "replay: $again" }
            null
        }
        check("image DM: Range resume (206 + Content-Range), and the downloader resumes a .part") {
            val m = b1.img.media.get(imgId)!!
            val full = b1.img.files.file(m.fileName!!).readBytes()
            val req = okhttp3.Request.Builder().url("$url/api/v1/blobs/${m.blobId}").header("Authorization", "Bearer $bTok").header("Range", "bytes=100-").build()
            http.newCall(req).execute().use { r ->
                ensure(r.code == 206) { "range: ${r.code}" }
                ensure(r.header("Content-Range") == "bytes 100-${full.size - 1}/${full.size}") { "Content-Range ${r.header("Content-Range")}" }
                ensure((full.copyOf(100) + r.body.bytes()).contentEquals(full)) { "stitched bytes differ" }
            }
            // Drop B's copy, keep 100 000 bytes as a partial download: resumed from the verified 65 552.
            b1.img.files.file(m.fileName).delete()
            b1.img.files.part(imgId).writeBytes(full.copyOf(100_000))
            b1.img.media.update(m.copy(state = "NONE", fileName = null))
            ensure(b1.img.fetch(imgId) == imgSha) { "resumed plaintext differs" }
            null
        }
        check("image refusals: plaintext DM 409 not_e2ee, wrong type 415, no client_blob_id 400, non-participant 404") {
            val f = a1.img.files.file(a1.img.media.get(imgId)!!.fileName!!)
            val plain = a1.api.uploadMediaBlob(dmConversationId(aId, lId), UUID.randomUUID().toString(), f)
            ensure(plain is ApiResult.Error && plain.httpStatus == 409 && plain.code == AuthErrors.NOT_E2EE) { "plaintext DM: $plain" }
            fun post(q: String, type: String, token: String): Int = http.newCall(
                okhttp3.Request.Builder().url("$url/api/v1/blobs?$q").header("Authorization", "Bearer $token")
                    .post(f.readBytes().toRequestBody(type.toMediaType())).build(),
            ).execute().use { it.code }
            val t415 = post("purpose=media&conversation_id=$conv&client_blob_id=${UUID.randomUUID()}", "text/plain", aTok)
            ensure(t415 == 415) { "wrong type: $t415" }
            val t400 = post("purpose=media&conversation_id=$conv", "application/octet-stream", aTok)
            ensure(t400 == 400) { "no client_blob_id: $t400" }
            val lApi = ApiClient(http, { url }, { lTok })
            val up = lApi.uploadMediaBlob(conv, UUID.randomUUID().toString(), f)
            ensure(up is ApiResult.Error && up.httpStatus == 404) { "L upload into A–B: $up" }
            val down = lApi.downloadBlob(a1.img.media.get(imgId)!!.blobId!!)
            ensure(down is ApiResult.Error && down.httpStatus == 404) { "L download: $down" }
            null
        }
        check("images_ready: false while installs advertise only groups, true once every install advertises images") {
            val before = a1.api.mlsGroup(conv)
            ensure(before is ApiResult.Ok && !before.value.imagesReady && before.value.missingImages.isNotEmpty()) { "before: $before" }
            for (d in listOf(a1, a2, b1)) {
                val r = DeviceRegistrar(d.api, { d.deviceId }, "0.3.0-interop", { d.mls.engine }, imagesSupported = { true }, groupsReplacedFor = { "done" }).register(null)
                ensure(r is Registration.Mls) { "${d.deviceId}: $r" }
            }
            val after = a1.api.mlsGroup(conv)
            ensure(after is ApiResult.Ok && after.value.imagesReady && after.value.missingImages.isEmpty()) { "after: $after" }
            "missing before: ${(before as ApiResult.Ok).value.missingImages.size}"
        }
        check("blob usage counts the upload") {
            val u = a1.api.blobUsage()
            ensure(u is ApiResult.Ok && u.value.media.used >= a1.img.media.get(imgId)!!.blobSize && u.value.media.uploadsLastHour >= 1) { "usage: $u" }
            "${(u as ApiResult.Ok).value.media.used} / ${u.value.media.limit}"
        }
        var bMsgId = ""
        check("e2ee reaction ❤️: B decodes a reaction, A's tablet sees it as A's own, event looks like any ciphertext") {
            bMsgId = a1.messages.rows.values.first { it.body == "hi A $run" }.messageId!!
            a1.chat.react(bId, bMsgId, "❤️", "add")
            await(15_000, "B shows A's ❤️") { b1.adds(bMsgId).takeIf { (aId to "❤️") in it } }
            await(15_000, "A's tablet shows its own ❤️") { a2.adds(bMsgId).takeIf { (aId to "❤️") in it } }
            ensure(b1.bodies().none { it.contains("reaction") }) { "a reaction surfaced as a message" }
            val confirmed = a1.reactions.rows.values.first { it.targetMessageId == bMsgId }.confirmedMessageId!!
            val ev = await(10_000, "the stored reaction event at B") { b1.raw.firstOrNull { it.messageData()?.messageId == confirmed } }
            ensure(ev.kind == "message" && ev.messageData()!!.body == null && "reaction" !in ev.data && "target" !in ev.data) { "server can see it's a reaction: ${ev.data.keys}" }
            // A v1.7 decoder (anything but "text" is ignored) shows nothing for it.
            val plain = lk.codegen.risime.data.mls.MlsPayload.reaction(bMsgId, "❤️", "add").decodeToString()
            ensure(Regex("\"type\":\"reaction\"").containsMatchIn(plain)) { "envelope type" }
            null
        }
        check("e2ee reaction remove → cleared on every device") {
            a1.chat.react(bId, bMsgId, "❤️", "remove")
            await(15_000, "B cleared") { b1.adds(bMsgId).takeIf { (aId to "❤️") !in it } }
            await(15_000, "tablet cleared") { a2.adds(bMsgId).takeIf { (aId to "❤️") !in it } }
            ensure(a1.adds(bMsgId).isEmpty()) { "A still shows it" }
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
            val a3 = Dev(url, aId, aTok, java.util.UUID.randomUUID().toString(), trusted)
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
            // Reactions across the member-change commit converge on all four devices.
            val target4 = await(15_000, "B has the four-device message") { b1.messages.rows.values.firstOrNull { it.body == "four devices $run" }?.messageId }
            b1.chat.react(aId, target4, "😂", "add")
            for (d in listOf(a1, a2, a3)) await(15_000, "${d.deviceId} shows B's 😂") { d.adds(target4).takeIf { (bId to "😂") in it } }
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

    /**
     * §16 (v1.13) 1:1 voice calls: signalling end to end through the real server (the "groups" block's
     * A, B and C: the DMs C–B, A–B and A–C, which no other live test uses). Prints "INTEROP PASS|FAIL e2ee: calls: …".
     */
    @Test fun liveCalls() {
        val path = System.getenv("RISIME_INTEROP_CONFIG")
        assumeTrue("RISIME_INTEROP_CONFIG not set", !path.isNullOrBlank() && File(path).isFile)
        RealMls.assumeHostLibrary()
        val root = ProtocolJson.parseToJsonElement(File(path!!).readText()).jsonObject
        val g = root["groups"]?.jsonObject
        assumeTrue("no \"groups\" block in the interop config", g != null)
        val url = root["url"]!!.jsonPrimitive.content
        fun u(k: String) = g!![k]!!.jsonObject.let { it["id"]!!.jsonPrimitive.content to it["token"]!!.jsonPrimitive.content }
        val trusted = (runBlocking { ApiClient(http, { url }, { null }).attestationKeys() } as ApiResult.Ok).value.keys.map { it.toString() }
        LiveCalls(http, scope, url, trusted).run(u("A"), u("B"), u("C")) { name, block -> check(name, block) }
    }

    /**
     * v1.16 (proposal 2026-10-07-dm-device-readd): DMs heal after a reinstall (the "readd" block's
     * A and B, a DM no other live test uses). (1) A reinstalls: A's new phone sees it isn't in the
     * group ("Setting up encryption on this phone…"), the server names B's device, which adds A's new
     * device and drops A's superseded leaf; then the DM sends both ways (the gate check). (2) Both
     * reinstall: nobody can re-add, so A's phone resets the DM and rebuilds it; both ways again.
     */
    @Test fun liveDmReadd() {
        val path = System.getenv("RISIME_INTEROP_CONFIG")
        assumeTrue("RISIME_INTEROP_CONFIG not set", !path.isNullOrBlank() && File(path).isFile)
        RealMls.assumeHostLibrary()
        val root = ProtocolJson.parseToJsonElement(File(path!!).readText()).jsonObject
        val r = root["readd"]?.jsonObject
        assumeTrue("no \"readd\" block in the interop config", r != null)
        val url = root["url"]!!.jsonPrimitive.content
        fun u(k: String) = r!![k]!!.jsonObject.let { it["id"]!!.jsonPrimitive.content to it["token"]!!.jsonPrimitive.content }
        val (aId, aTok) = u("A"); val (bId, bTok) = u("B")
        val conv = dmConversationId(aId, bId)
        val run = UUID.randomUUID().toString().take(8)
        val trusted = (runBlocking { ApiClient(http, { url }, { null }).attestationKeys() } as ApiResult.Ok).value.keys.map { it.toString() }

        suspend fun install(userId: String, token: String): Dev {
            val d = Dev(url, userId, token, UUID.randomUUID().toString(), trusted)
            val reg = DeviceRegistrar(d.api, { d.deviceId }, "0.4.0-interop", { d.mls.engine }).register(pushToken = null)
            ensure(reg is Registration.Mls) { "${d.deviceId}: $reg" }
            d.start(); d.live()
            return d
        }
        suspend fun leaves(d: Dev): Set<String> {
            val g = d.api.mlsGroup(conv)
            ensure(g is ApiResult.Ok) { "GET group: $g" }
            return (g as ApiResult.Ok).value.devices.map { it.deviceId.lowercase() }.toSet()
        }
        suspend fun bothWays(x: Dev, xPeer: String, y: Dev, yPeer: String, label: String) {
            x.chat.sendText(xPeer, "$label x→y $run")
            await(20_000, "$label: y decrypts") { y.bodies().firstOrNull { it == "$label x→y $run" } }
            y.chat.sendText(yPeer, "$label y→x $run")
            await(20_000, "$label: x decrypts") { x.bodies().firstOrNull { it == "$label y→x $run" } }
        }

        var a1: Dev? = null
        var b1: Dev? = null
        check("readd: A and B have an e2ee DM") {
            a1 = install(aId, aTok); b1 = install(bId, bTok)
            val s = MlsUpgrader({ a1!!.mls.engine }, a1!!.mlsApi).ensure(conv, aId, bId)
            ensure(s is E2eeState.Encrypted) { "upgrade: $s" }
            await(15_000, "B joined") { b1!!.mls.engine.group(conv) }
            bothWays(a1!!, bId, b1!!, aId, "before")
            null
        }
        var a2: Dev? = null
        check("readd: A reinstalls; the new phone shows 'Setting up encryption on this phone…'") {
            a1!!.client.stop() // the old install is never seen again
            delay(50)
            a2 = install(aId, aTok)
            val s = MlsUpgrader({ a2!!.mls.engine }, a2!!.mlsApi).ensure(conv, aId, bId, verify = true)
            ensure(s == E2eeState.Repairing) { "new phone: $s" }
            ensure(lk.codegen.risime.data.mls.e2eeStripText(s, { "B" }) == "Setting up encryption on this phone…") { "strip" }
            null
        }
        check("readd: B's device is named, adds A's new device and drops A's old leaf (op_id on each commit)") {
            val op = await(20_000, "mls_dm_op naming b1") { b1!!.dmOps.lastOrNull { it.conversationId == conv } }
            ensure(op.op.added.any { it.deviceId.equals(a2!!.deviceId, true) }) { "op added ${op.op.added}" }
            ensure(op.op.removed.any { it.deviceId.equals(a1!!.deviceId, true) }) { "op removed ${op.op.removed}" }
            val outcome = b1!!.executor.executeOp(op)
            ensure(outcome == MembershipOutcome.Done) { "executeOp: $outcome" }
            await(20_000, "A's new phone joined from the Welcome") { a2!!.mls.engine.group(conv) }
            val l = leaves(b1!!)
            ensure(a2!!.deviceId.lowercase() in l && a1!!.deviceId.lowercase() !in l) { "leaves $l" }
            val again = MlsUpgrader({ a2!!.mls.engine }, a2!!.mlsApi).ensure(conv, aId, bId, verify = true)
            ensure(again is E2eeState.Encrypted) { "after re-add: $again" }
            "leaves ${l.size}"
        }
        check("readd gate: after A's device changed, the DM still sends both ways") {
            bothWays(a2!!, bId, b1!!, aId, "after reinstall")
            null
        }
        check("readd: both reinstall → no candidate → A's phone resets and rebuilds; both ways") {
            a2!!.client.stop(); b1!!.client.stop()
            delay(50)
            val a3 = install(aId, aTok)
            val b3 = install(bId, bTok)
            val before = (a3.api.mlsGroup(conv) as ApiResult.Ok).value.generation
            val s = MlsUpgrader({ a3.mls.engine }, a3.mlsApi).ensure(conv, aId, bId, verify = true)
            ensure(s is E2eeState.Encrypted) { "a3: $s" }
            val g = (a3.api.mlsGroup(conv) as ApiResult.Ok).value
            ensure(g.e2ee && g.generation == before + 1 && g.epoch != null) { "after reset: $g" }
            await(20_000, "B's new phone joined the new generation") { b3.mls.engine.group(conv)?.takeIf { it.generation == g.generation } }
            val sb = MlsUpgrader({ b3.mls.engine }, b3.mlsApi).ensure(conv, bId, aId, verify = true)
            ensure(sb is E2eeState.Encrypted) { "b3: $sb" }
            bothWays(a3, bId, b3, aId, "after reset")
            "generation ${g.generation}"
        }
    }

    /**
     * §13 (v1.10) with the real core: plaintext sender copies and history_before on the wire, then
     * (i) a second fresh device for A while the first stays active, (ii) a reinstall (new device id),
     * (iii) logout and login on the same device id. Each restores both sides of the plaintext chat with
     * ticks, shows one "Earlier messages…" marker for the e2ee part, decrypts new messages and doesn't
     * notify for the replay.
     */
    @Test fun liveHistory() {
        val path = System.getenv("RISIME_INTEROP_CONFIG")
        assumeTrue("RISIME_INTEROP_CONFIG not set", !path.isNullOrBlank() && File(path).isFile)
        RealMls.assumeHostLibrary()
        val root = ProtocolJson.parseToJsonElement(File(path!!).readText()).jsonObject
        val h = root["history"]?.jsonObject
        assumeTrue("no \"history\" block in the interop config", h != null)
        val url = root["url"]!!.jsonPrimitive.content
        fun u(k: String) = h!![k]!!.jsonObject.let { it["id"]!!.jsonPrimitive.content to it["token"]!!.jsonPrimitive.content }
        val (aId, aTok) = u("A"); val (bId, bTok) = u("B")
        val conv = dmConversationId(aId, bId)
        val run = UUID.randomUUID().toString().take(8)
        val trusted = (runBlocking { ApiClient(http, { url }, { null }).attestationKeys() } as ApiResult.Ok).value.keys.map { it.toString() }

        suspend fun register(d: Dev) {
            val r = DeviceRegistrar(d.api, { d.deviceId }, "0.3.0-interop", { d.mls.engine }).register(pushToken = null)
            ensure(r is Registration.Mls) { "${d.deviceId}: $r" }
        }
        val a1 = Dev(url, aId, aTok, UUID.randomUUID().toString(), trusted)
        val b1 = Dev(url, bId, bTok, UUID.randomUUID().toString(), trusted)
        runBlocking { register(a1); register(b1) }
        a1.start(); b1.start()
        runBlocking { a1.live(); b1.live() }

        check("history: history_before set on the first connect, stable across a rejoin") {
            val first = await(5_000, "a1 history_before") { a1.hbs.firstOrNull() }
            Instant.parse(first)
            a1.client.stop(); a1.start(); a1.live()
            ensure(a1.hbs.all { it == first }) { "changed across a rejoin: ${a1.hbs}" }
            first
        }
        var copyEventId = ""
        check("history: plaintext sender copy on A's own device, never acked; B reads → ticks") {
            a1.chat.sendText(bId, "plain from A $run")
            val atB = await(15_000, "B gets it") { b1.raw.firstOrNull { it.messageData()?.body == "plain from A $run" } }
            copyEventId = atB.eventId
            // The sending device skips its own copy (same client_msg_id): still one row.
            val atA = await(15_000, "A's copy event") { a1.raw.firstOrNull { it.eventId == atB.eventId } }
            ensure(atA.messageData()!!.from == aId) { "copy from ${atA.messageData()!!.from}" }
            ensure(a1.messages.rows.values.count { it.body == "plain from A $run" } == 1) { "duplicate row at A" }
            b1.chat.sendText(aId, "plain from B $run")
            await(15_000, "A gets B's") { a1.bodies().firstOrNull { it == "plain from B $run" } }
            b1.chat.markConversationRead(conv)
            await(15_000, "A's row read") { a1.messages.rows.values.firstOrNull { it.body == "plain from A $run" && it.status == "READ" } }
            null
        }
        check("history: the DM upgrades to e2ee and carries messages both ways") {
            val s = MlsUpgrader({ a1.mls.engine }, a1.mlsApi).ensure(conv, aId, bId)
            ensure(s is E2eeState.Encrypted) { "upgrade: $s" }
            await(15_000, "B joined") { b1.mls.engine.group(conv) }
            a1.chat.sendText(bId, "secret from A $run")
            await(15_000, "B decrypts") { b1.bodies().firstOrNull { it == "secret from A $run" } }
            b1.chat.sendText(aId, "secret from B $run")
            await(15_000, "A decrypts") { a1.bodies().firstOrNull { it == "secret from B $run" } }
            null
        }

        /** A new install of A: register, connect with since:null, get added by a1, then assert the restored chat. */
        suspend fun freshInstall(label: String, deviceId: String, previousHb: String?): Dev {
            val d = Dev(url, aId, aTok, deviceId, trusted, pageLimit = 3)
            register(d)
            d.start(); d.live()
            // §10.3: a1 (same user) is the named committer for A's new device.
            val m = withTimeoutOrNull(10_000) {
                var v = a1.memberships.lastOrNull { it.event.deviceId == deviceId && it.event.change == "added" }
                while (v == null) { delay(50); v = a1.memberships.lastOrNull { it.event.deviceId == deviceId && it.event.change == "added" } }
                v
            }?.also { a1.memberships.remove(it) } ?: MembershipAction(MlsMembershipEvent(conv, aId, deviceId, "added"), 0)
            val r = a1.executor.execute(m.copy(delayMs = 0))
            ensure(r == MembershipOutcome.Done || r == MembershipOutcome.NotNeeded) { "add $label: $r" }
            await(20_000, "$label joined from the Welcome") { d.mls.engine.group(conv) }
            // history_before: present, the same on every page of the join (pageLimit 3 → several sync pages).
            val hb = d.hbs.firstOrNull() ?: throw AssertionError("$label: no history_before")
            ensure(d.hbs.size > 1 && d.hbs.all { it == hb }) { "$label pages: ${d.hbs}" }
            previousHb?.let { ensure(Instant.parse(hb).isAfter(Instant.parse(it))) { "$label: history_before $hb not after $it" } }
            // Both sides of plaintext, the copy outgoing with read ticks and the same event id as B's event.
            val rows = d.messages.rows.values.filter { it.conversationId == conv }
            val mine = rows.firstOrNull { it.body == "plain from A $run" } ?: throw AssertionError("$label: own plaintext missing: ${d.bodies()}")
            ensure(mine.outgoing && mine.status == "READ") { "$label: own copy ${mine.outgoing}/${mine.status}" }
            ensure(d.raw.any { it.eventId == copyEventId }) { "$label: copy event id differs" }
            ensure(rows.any { it.body == "plain from B $run" && !it.outgoing }) { "$label: B's plaintext missing" }
            // The e2ee part: not decrypted, one marker; no stray undecryptable line.
            ensure(rows.none { it.body.startsWith("secret from") }) { "$label decrypted pre-install history" }
            ensure(rows.count { it.clientMsgId == "sys:history:$conv" } == 1) { "$label markers: ${rows.filter { it.system }.map { it.clientMsgId }}" }
            ensure(rows.none { it.clientMsgId.startsWith("sys:undecryptable") }) { "$label: undecryptable line" }
            // No notifications: the replay completed as a fresh one, and nothing restored is unread.
            ensure(d.freshReplayDone == 1 && !d.chat.replayingFresh) { "$label fresh replay: ${d.freshReplayDone}" }
            ensure(d.messages.unreadIncoming().none { (lk.codegen.risime.data.HistoryMarkers.epochMs(it.serverTs) ?: 0) < Instant.parse(hb).toEpochMilli() }) {
                "$label: pre-install rows unread"
            }
            // New messages decrypt on both of A's devices.
            b1.chat.sendText(aId, "after $label $run")
            await(15_000, "$label decrypts B") { d.bodies().firstOrNull { it == "after $label $run" } }
            await(15_000, "a1 decrypts B") { a1.bodies().firstOrNull { it == "after $label $run" } }
            d.chat.sendText(bId, "from $label $run")
            await(15_000, "B decrypts $label") { b1.bodies().firstOrNull { it == "from $label $run" } }
            await(15_000, "a1 sees $label's send") { a1.bodies().firstOrNull { it == "from $label $run" } }
            return d
        }

        var second: Dev? = null
        check("history (i): a second fresh device for A while the first stays active") {
            second = freshInstall("second device", UUID.randomUUID().toString(), null)
            "history_before ${second!!.hbs.first()}"
        }
        var reinstall: Dev? = null
        check("history (ii): a reinstall (new device id; the old install stays listed)") {
            second!!.client.stop()
            reinstall = freshInstall("reinstall", UUID.randomUUID().toString(), second!!.hbs.first())
            null
        }
        check("history (iii): logout and login on the same device id (MLS state wiped, new history_before)") {
            val old = reinstall!!
            val del = old.api.deleteDevice(old.deviceId) // what logout does (PushManager.unregister)
            ensure(del is ApiResult.Ok) { "logout DELETE: $del" }
            old.client.stop()
            val removed = await(20_000, "mls_membership removed at a1") {
                a1.memberships.lastOrNull { it.event.deviceId == old.deviceId && it.event.change == "removed" }
            }
            a1.memberships.remove(removed)
            val r = a1.executor.execute(removed.copy(delayMs = 0))
            ensure(r == MembershipOutcome.Done || r == MembershipOutcome.NotNeeded) { "remove: $r" }
            val again = freshInstall("re-login", old.deviceId, old.hbs.first())
            "history_before ${old.hbs.first()} → ${again.hbs.first()}"
        }
        // Last: a socket without device_id counts as a legacy app and would block the e2ee upgrade above.
        check("history: history_before is null without a device_id") {
            val noDevice = CopyOnWriteArrayList<String?>()
            val legacy = PhoenixRealtimeClient(http, scope, object : RealtimeListener {
                override suspend fun cursor(): String? = null
                override suspend fun onHistoryBefore(ts: String?) { noDevice += ts ?: "null" }
                override suspend fun onEvents(events: List<Event>) = Unit
                override suspend fun onLive() = Unit
                override suspend fun onAuthFailed() = Unit
            })
            legacy.start(RealtimeSession(url, aTok, aId))
            await(15_000, "no-device Live") { legacy.state.value.takeIf { it == ConnectionState.Live } }
            legacy.stop()
            ensure(noDevice.isNotEmpty() && noDevice.all { it == "null" }) { "without device_id: $noDevice" }
            null
        }
    }
}
