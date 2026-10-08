package lk.codegen.risime.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lk.codegen.risime.crypto.RealMls
import lk.codegen.risime.data.BehaviourLog
import lk.codegen.risime.data.ChatEngine
import lk.codegen.risime.data.FakeBehaviourDao
import lk.codegen.risime.data.FakeGroupDao
import lk.codegen.risime.data.FakeGroupOpDao
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.FakeSyncDao
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.db.GroupEntity
import lk.codegen.risime.data.db.GroupOpEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.groups.CreatePayload
import lk.codegen.risime.data.groups.GroupApi
import lk.codegen.risime.data.groups.GroupOpType
import lk.codegen.risime.data.groups.GroupOpsExecutor
import lk.codegen.risime.data.groups.GroupStore
import lk.codegen.risime.data.groups.OfficialPayload
import lk.codegen.risime.data.mls.DeviceRegistrar
import lk.codegen.risime.data.mls.E2eeState
import lk.codegen.risime.data.mls.FakeMlsPendingDao
import lk.codegen.risime.data.mls.MlsApi
import lk.codegen.risime.data.mls.MlsPipeline
import lk.codegen.risime.data.mls.MlsUpgrader
import lk.codegen.risime.data.mls.Registration
import lk.codegen.risime.data.tabs.RisiControl
import lk.codegen.risime.net.ApiClient
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.Group
import lk.codegen.risime.net.GroupCommitRequest
import lk.codegen.risime.net.GroupCreate
import lk.codegen.risime.net.GroupReply
import lk.codegen.risime.net.MlsCommitEvent
import lk.codegen.risime.net.MlsCommitRequest
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.dmConversationId
import lk.codegen.risime.net.isGroupConversation
import lk.codegen.risime.realtime.ConnectionState
import lk.codegen.risime.realtime.PhoenixRealtimeClient
import lk.codegen.risime.realtime.RealtimeListener
import lk.codegen.risime.realtime.RealtimeSession
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Risi canary interop (contract v1.24 §24.14, decisions 065-067), run by `scripts/risi-canary` against a
 * temp server with `TABS=on RISI=on`, the MLS NIF, throwaway `RISI_MLS_KEK`/`RISI_DATA_KEY` and
 * `scripts/fake-llm` as the model. Two real JVM apps (the app's ChatEngine + MlsPipeline + GroupStore +
 * the group-op outbox, the REAL MLS core, real sockets) that advertise `tabs`:
 *  - a Private 1:1 and a Private group each carry unique `CANARY-…` texts, before and after their
 *    Official exists (B is offline for the first ones, so the server pushes to B's recorded token);
 *  - Official (1:1 and group) is created; Risi joins at epoch 0 as an attested agent leaf;
 *  - Official text with a commitment → a commitment card both members decrypt and honour (agent
 *    leaf, Official) → B (the owner) confirms with a `risi_action` → a `commitment_update`;
 *  - a `risi_request` summarise in the group's Official → a `summary` card;
 *  - Official of the 1:1 is turned off; a `CANARY-off` send there is refused (`official_off`).
 * The script then checks that no canary reached the model, the risi_* tables, the buffer, the learning
 * log, the server log or a push. Config: RISIME_INTEROP_CONFIG with a "risi" block
 * `{"A"|"B": {"id","token"}, "canaries": {…}, "official_marker"}`. Prints "INTEROP PASS|FAIL risi: <check>".
 */
class LiveRisiInteropTest {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val devices = mutableListOf<Dev>()

    @After fun tearDown() {
        devices.forEach { runCatching { it.close() } }
    }

    private fun check(name: String, block: suspend () -> String?) = runBlocking {
        val t0 = System.nanoTime()
        try {
            val d = block()
            println("INTEROP PASS risi: $name (${(System.nanoTime() - t0) / 1_000_000} ms)${d?.let { ": $it" } ?: ""}")
        } catch (t: Throwable) {
            println("INTEROP FAIL risi: $name: ${t.message}")
            throw AssertionError("risi: $name: ${t.message}", t)
        }
    }

    private fun ensure(c: Boolean, m: () -> String) { if (!c) throw AssertionError(m()) }

    private inline fun <T, R> ApiResult<T>.map(f: (T) -> R): ApiResult<R> = when (this) {
        is ApiResult.Ok -> ApiResult.Ok(f(value))
        is ApiResult.Error -> this
        is ApiResult.NetworkError -> this
    }

    /** One tabs-capable app instance; everything of it runs on one thread (the fake DAOs aren't thread-safe). */
    private inner class Dev(val url: String, val userId: String, val token: String, val name: String, trusted: List<String>) {
        val deviceId: String = UUID.randomUUID().toString()
        private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "dev-$name").apply { isDaemon = true } }
        val dispatcher = pool.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val api = ApiClient(http, { url }, { token })
        val mls = RealMls.device(userId, deviceId, trusted, attest = false)
        val messages = FakeMessageDao()
        val groupDao = FakeGroupDao()
        val opDao = FakeGroupOpDao()
        val sync = FakeSyncDao()
        val raw = CopyOnWriteArrayList<Event>()
        private var groupsKeyPackagesFor: String? = null
        private val tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() }
        private val kicks = Channel<Unit>(Channel.CONFLATED)
        private val catchUpLock = Mutex()
        val registrar = DeviceRegistrar(
            api, { deviceId }, "0.4.0-interop", { mls.engine },
            groupsReplacedFor = { groupsKeyPackagesFor }, setGroupsReplacedFor = { groupsKeyPackagesFor = it },
            tabsSupported = { true },
        )
        val store = GroupStore(
            groupDao, opDao, messages, { deviceId },
            metaOf = { conv -> mls.engine.groupMeta(conv) },
            needsRefresh = { conv -> scope.launch { refreshGroup(conv) } },
            onOpQueued = { kicks.trySend(Unit) },
        )
        lateinit var client: PhoenixRealtimeClient
        val chat: ChatEngine = ChatEngine(
            messages = messages, sync = sync, tx = tx,
            scope = scope, realtime = { client }, meId = { userId },
            behaviour = BehaviourLog(FakeBehaviourDao(), { "s" }, { 0L }),
            mls = MlsPipeline(
                { mls.engine }, FakeMlsPendingDao(),
                log = { println("  [$name] mls: $it") },
                onJoined = { scope.launch { registrar.topUp() } },
                onParkedAhead = { conv -> scope.launch { catchUp(conv) } },
            ),
            mlsEngine = { mls.engine }, catchUp = { catchUp(it) },
            groupsEnabled = { mls.engine.groupsSupported },
            groups = store,
            onUnrecoverable = { conv -> println("  [$name] unrecoverable $conv") },
            log = { println("  [$name] $it") },
        )

        val groupApi = object : GroupApi {
            override suspend fun create(clientGroupId: String, memberIds: List<String>) =
                api.createGroup(GroupCreate(clientGroupId, memberIds), deviceId).map { it.group }
            override suspend fun group(id: String) = groupAsTabsDevice(id)
            override suspend fun addMembers(id: String, userIds: List<String>) = api.addGroupMembers(id, userIds, deviceId).map { it.group }
            override suspend fun removeMember(id: String, userId: String) = api.removeGroupMember(id, userId, deviceId)
            override suspend fun leave(id: String) = api.leaveGroup(id, deviceId)
            override suspend fun setRole(id: String, userId: String, role: String) = api.setGroupRole(id, userId, role, deviceId).map { it.group }
            override suspend fun rejoin(id: String) = api.rejoinGroup(id, deviceId)
            override suspend fun reset(id: String, generation: Long) = api.resetGroup(id, generation, deviceId).map { it.generation }
            override suspend fun claim(userIds: List<String>, conversationId: String?) =
                api.claimKeyPackages(userIds, deviceId, conversationId).map { it.devices }
            override suspend fun commit(id: String, body: GroupCommitRequest) = api.groupCommit(id, body, deviceId).map { it.epoch }
            override suspend fun uploadBlob(conversationId: String, bytes: ByteArray) = api.uploadBlob(conversationId, bytes).map { it.ref() }
            override suspend fun createOfficial(chatId: String) = api.createOfficial(chatId, deviceId).map { it.group }
        }

        val mlsApi = object : MlsApi {
            override suspend fun group(conversationId: String) = api.mlsGroup(conversationId)
            override suspend fun claim(userIds: List<String>) = api.claimKeyPackages(userIds, deviceId)
            override suspend fun commit(conversationId: String, body: MlsCommitRequest) = api.mlsCommit(conversationId, body, deviceId)
        }

        val executor = GroupOpsExecutor(
            { mls.engine }, groupApi, opDao, groupDao, store, tx,
            me = { userId }, deviceId = { deviceId }, catchUp = { catchUp(it) },
            log = { println("  [$name] $it") },
        )

        init {
            val recorder = object : RealtimeListener {
                override suspend fun cursor() = chat.cursor()
                override suspend fun onEvents(events: List<Event>) { raw += events; chat.onEvents(events) }
                override suspend fun onLive() = chat.onLive()
                override suspend fun onAuthFailed() = Unit
            }
            client = PhoenixRealtimeClient(http, scope, recorder, backoffMs = listOf(200, 500))
            devices += this
            scope.launch {
                while (isActive) {
                    runCatching { executor.runDue() }.onFailure { println("  [$name] runDue: $it") }
                    withTimeoutOrNull(250) { kicks.receive() }
                }
            }
        }

        suspend fun catchUp(conv: String): Unit = catchUpLock.withLock {
            var n = 0
            while (n++ < 40) {
                val g = mls.engine.group(conv) ?: return
                val r = when (val res = api.mlsCommits(conv, g.epoch, if (isGroupConversation(conv)) 50 else null)) {
                    is ApiResult.Ok -> res.value
                    else -> return
                }
                chat.applyOutOfBand(
                    r.commits.map { c ->
                        Event(
                            "catchup:$conv:${g.generation}:${c.epoch}", Event.KIND_MLS_COMMIT,
                            ProtocolJson.encodeToJsonElement(MlsCommitEvent.serializer(), MlsCommitEvent(conv, g.generation, c.epoch, c.commit, c.fromDevice, c.commitRef)) as JsonObject,
                        )
                    },
                )
                val after = mls.engine.group(conv)?.epoch ?: return
                if (!r.hasMore || after <= g.epoch) return
            }
        }

        /**
         * `GET /groups/{id}` WITH this device's `X-Device-Id` (§24.5: without it the server can't tell a
         * `tabs` device and answers 404 for an Official group). NOTE: the app's `ApiClient.group()` /
         * `groups()` send no `X-Device-Id` (open android item: on a 404 the app marks its Official group
         * gone and then never commits Risi's removal after Official off); this test asks as a tabs app must.
         */
        suspend fun groupAsTabsDevice(id: String): ApiResult<Group> = withContext(Dispatchers.IO) {
            val req = Request.Builder().url("${url.trimEnd('/')}/api/v1/groups/$id")
                .header("Authorization", "Bearer $token").header(ApiClient.DEVICE_HEADER, deviceId).build()
            runCatching {
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) ApiResult.Ok(ProtocolJson.decodeFromString(GroupReply.serializer(), body).group)
                    else ApiResult.Error(resp.code, "http_${resp.code}", body.take(200))
                }
            }.getOrElse { ApiResult.NetworkError(it as? java.io.IOException ?: java.io.IOException(it)) }
        }

        suspend fun refreshGroup(conv: String) {
            when (val r = groupAsTabsDevice(conv)) {
                is ApiResult.Ok -> store.applyServerGroup(r.value, userId)
                is ApiResult.Error -> if (r.httpStatus == 404) store.markGone(conv)
                is ApiResult.NetworkError -> Unit
            }
        }

        fun start() = client.start(RealtimeSession(url, userId, deviceId, "0.4.0-interop") { token })

        suspend fun <T> on(block: suspend () -> T): T = withContext(dispatcher) { block() }

        suspend fun <T> await(ms: Long, what: String, probe: suspend () -> T?): T = withTimeoutOrNull(ms) {
            var v = on { probe() }
            while (v == null) { delay(200); v = on { probe() } }
            v
        } ?: throw AssertionError("$name: timed out waiting for $what")

        suspend fun live() = await(15_000, "Live") { client.state.value.takeIf { it == ConnectionState.Live } }

        suspend fun awaitText(conv: String, text: String, ms: Long = 30_000) =
            await(ms, "\"${text.take(24)}…\" in $conv") { messages.rows.values.firstOrNull { it.conversationId == conv && it.body == text } }

        suspend fun awaitOp(id: Long, ms: Long = 60_000): GroupOpEntity =
            await(ms, "op $id") { opDao.rows[id]?.takeIf { it.state != GroupOpType.QUEUED } }

        /** Honoured Risi objects (agent leaf, Official) of the stored text rows in [conv]. */
        fun risiObjects(conv: String): List<Pair<MessageEntity, JsonObject>> =
            messages.rows.values.filter { it.conversationId == conv && it.kind == MessageEntity.KIND_TEXT && it.systemJson != null }
                .sortedBy { it.localTs }
                .mapNotNull { m -> runCatching { ProtocolJson.parseToJsonElement(m.systemJson!!).jsonObject }.getOrNull()?.let { m to it } }

        suspend fun awaitRisi(conv: String, kind: String, ms: Long, pred: (JsonObject) -> Boolean = { true }) =
            await(ms, "a Risi \"$kind\" in $conv") { risiObjects(conv).lastOrNull { (_, o) -> o.str("kind") == kind && pred(o) } }

        fun close() {
            runCatching { client.stop() }
            scope.cancel()
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
            mls.close()
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    @Test fun liveRisiCanary() {
        val path = System.getenv("RISIME_INTEROP_CONFIG")
        assumeTrue("RISIME_INTEROP_CONFIG not set", !path.isNullOrBlank() && File(path).isFile)
        val root = ProtocolJson.parseToJsonElement(File(path!!).readText()).jsonObject
        val r = root["risi"]?.jsonObject
        assumeTrue("no \"risi\" block in the interop config", r != null)
        RealMls.assumeHostLibrary()
        val url = root["url"]!!.jsonPrimitive.content
        fun u(k: String) = r!![k]!!.jsonObject.let { it["id"]!!.jsonPrimitive.content to it["token"]!!.jsonPrimitive.content }
        val (aId, aTok) = u("A"); val (bId, bTok) = u("B")
        val canary = r!!["canaries"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val marker = r["official_marker"]!!.jsonPrimitive.content
        var trusted = emptyList<String>()

        check("server: tabs on, attestation keys served") {
            val pub = ApiClient(http, { url }, { null })
            val cfg = pub.authConfig()
            ensure(cfg is ApiResult.Ok && cfg.value.tabsOn) { "GET /auth/config: tabs not on ($cfg)" }
            val k = pub.attestationKeys()
            ensure(k is ApiResult.Ok && k.value.keys.isNotEmpty()) { "GET /mls/attestation_keys: $k" }
            trusted = (k as ApiResult.Ok).value.keys.map { it.toString() }
            null
        }
        val a = Dev(url, aId, aTok, "A", trusted)
        val b = Dev(url, bId, bTok, "B", trusted)

        check("registration with groups + tabs (push tokens recorded by the server's push hook)") {
            for (x in listOf(a, b)) {
                ensure(x.mls.engine.groupsSupported && x.mls.engine.tabsSupported) { "${x.name}: the core lacks groups/tabs" }
                val reg = x.on { x.registrar.register(pushToken = "canary-${x.name}") }
                ensure(reg is Registration.Mls) { "${x.name}: $reg" }
            }
            a.start(); a.live()
            null
        }

        val dm = dmConversationId(aId, bId)
        var grp = ""
        check("Private 1:1 and Private group carry CANARY texts (B offline: pushed, then delivered)") {
            val s = a.on { MlsUpgrader({ a.mls.engine }, a.mlsApi).ensure(dm, aId, bId) }
            ensure(s is E2eeState.Encrypted) { "A: DM MLS state $s" }
            val id = a.on { a.store.queueLocal(null, GroupOpType.CREATE, ProtocolJson.encodeToString(CreatePayload.serializer(), CreatePayload("ZZ Canary group", listOf(bId))), UUID.randomUUID().toString()) }
            val op = a.awaitOp(id)
            ensure(op.state == GroupOpType.DONE) { "create group: ${op.state} ${op.lastError}" }
            grp = op.conversationId!!
            a.on { a.chat.sendText(bId, canary.getValue("dm_private")) }
            a.on { a.chat.sendText(grp, canary.getValue("grp_private")) }
            a.on { a.chat.flushOutbox() }
            delay(3_000) // B offline: the server pushes
            b.start(); b.live()
            b.awaitText(dm, canary.getValue("dm_private"))
            b.awaitText(grp, canary.getValue("grp_private"))
            ensure(a.mls.engine.groupMeta(grp)?.official != true) { "the Private group's meta says Official" }
            "dm=$dm grp=$grp"
        }

        suspend fun startOfficial(x: Dev, chatId: String): String {
            val ready = x.await(90_000, "official_ready for $chatId") {
                (x.api.chat(chatId, x.deviceId) as? ApiResult.Ok)?.value?.chat?.takeIf { it.officialReady }
            }
            ensure(ready.canToggle) { "${x.name} can't toggle $chatId" }
            val g = x.api.createOfficial(chatId, x.deviceId)
            ensure(g is ApiResult.Ok) { "POST /chats/$chatId/official: $g" }
            val conv = (g as ApiResult.Ok).value.group.id
            x.on { x.store.queueLocal(conv, GroupOpType.CREATE_OFFICIAL, ProtocolJson.encodeToString(OfficialPayload.serializer(), OfficialPayload(chatId))) }
            for (y in listOf(a, b)) {
                y.await(60_000, "Official $conv joined") { y.mls.engine.group(conv) }
                y.await(10_000, "Official meta of $conv") { y.mls.engine.groupMeta(conv)?.takeIf { it.official && it.chatId == chatId } }
                y.await(60_000, "Risi (an attested agent leaf) in $conv") { y.mls.engine.agentUsers(conv).takeIf { it.isNotEmpty() } }
            }
            return conv
        }

        var dmOff = ""; var grpOff = ""
        check("Official 1:1 and Official group: Risi joins at epoch 0 as an attested agent") {
            dmOff = startOfficial(a, dm)
            grpOff = startOfficial(a, grp)
            // Diagnostic only (open android item): the app's own ApiClient.group() carries no X-Device-Id.
            val appGet = a.api.group(dmOff)
            if (appGet !is ApiResult.Ok) println("NOTE risi: the app's ApiClient.group($dmOff) without X-Device-Id → ${(appGet as? ApiResult.Error)?.let { "${it.httpStatus} ${it.code}" } ?: appGet} (a tabs app must send it)")
            "dm→$dmOff grp→$grpOff agents=${a.mls.engine.agentUsers(dmOff)}"
        }

        check("Private after Official exists still carries CANARY texts (never reaches Risi)") {
            b.on { b.chat.sendText(aId, canary.getValue("dm_private_after")) }
            b.on { b.chat.sendText(grp, canary.getValue("grp_private_after")) }
            a.awaitText(dm, canary.getValue("dm_private_after"))
            a.awaitText(grp, canary.getValue("grp_private_after"))
            null
        }

        var commitmentId = ""
        check("Official commitment → a commitment card both members decrypt (honoured: agent leaf, Official)") {
            a.on { a.chat.sendText(dmOff, "Can you send me the quarterly report? $marker") }
            b.awaitText(dmOff, "Can you send me the quarterly report? $marker")
            b.on { b.chat.sendText(dmOff, "I'll send the report Friday 5pm") }
            a.awaitText(dmOff, "I'll send the report Friday 5pm")
            val (row, card) = a.awaitRisi(dmOff, "commitment", 120_000)
            commitmentId = card.str("commitment_id") ?: throw AssertionError("card without commitment_id: $card")
            ensure(card.str("owner").equals(bId, true)) { "owner ${card.str("owner")} (want B)" }
            ensure(b.awaitRisi(dmOff, "commitment", 30_000) { it.str("commitment_id") == commitmentId }.second.str("state") == "proposed") { "B's card state" }
            ensure(a.mls.engine.agentUsers(dmOff).any { it.equals(row.from, true) }) { "card sender ${row.from} is not the agent leaf" }
            "commitment $commitmentId \"${card.str("text")}\" due ${card["due"]}"
        }

        check("B confirms ✓ (risi_action) → commitment_update confirmed") {
            b.on { b.chat.sendRisiControl(dmOff, RisiControl.action(commitmentId, "confirm")) }
            for (x in listOf(a, b)) {
                x.awaitRisi(dmOff, "commitment_update", 90_000) { it.str("commitment_id") == commitmentId && it.str("state") == "confirmed" }
            }
            null
        }

        check("summarise in the Official group → a summary card") {
            a.on { a.chat.sendText(grpOff, "Kickoff notes for the launch $marker") }
            b.awaitText(grpOff, "Kickoff notes for the launch $marker")
            b.on { b.chat.sendText(grpOff, "Agreed, budget is fixed $marker") }
            a.awaitText(grpOff, "Agreed, budget is fixed $marker")
            val reqId = UUID.randomUUID().toString()
            a.on { a.chat.sendRisiControl(grpOff, RisiControl.request(reqId, "summarise", null, System.currentTimeMillis() - RisiControl.SUMMARY_WINDOW_MS)) }
            val (_, s) = a.awaitRisi(grpOff, "summary", 120_000) { it.str("request_id") == reqId }
            b.awaitRisi(grpOff, "summary", 30_000) { it.str("request_id") == reqId }
            "\"${s.str("summary")}\""
        }

        check("Official of the 1:1 OFF → Risi leaves; a CANARY send there is refused") {
            val p = a.api.patchChat(dm, "off", a.deviceId)
            ensure(p is ApiResult.Ok && p.value.chat.official.state == "off") { "PATCH /chats/$dm off: $p" }
            for (x in listOf(a, b)) x.await(60_000, "Risi removed from $dmOff") { x.mls.engine.agentUsers(dmOff).takeIf { it.isEmpty() } }
            a.on { a.chat.sendText(dmOff, canary.getValue("dm_official_off")) }
            a.on { a.chat.flushOutbox() }
            delay(5_000)
            val got = b.on { b.messages.rows.values.any { it.body == canary.getValue("dm_official_off") } }
            ensure(!got) { "B received a message in the Official conversation after it was turned off" }
            val st = a.on { a.messages.rows.values.firstOrNull { it.body == canary.getValue("dm_official_off") }?.status }
            ensure(st != MessageStatus.SENT.name && st != MessageStatus.DELIVERED.name && st != MessageStatus.READ.name) { "the off-state send was accepted ($st)" }
            "A's row: $st"
        }
        ensure(a.groupDao.groups[grp]?.state == GroupEntity.STATE_ACTIVE) { "the Private group is no longer active" }
        println("RISI INTEROP DONE")
    }
}
