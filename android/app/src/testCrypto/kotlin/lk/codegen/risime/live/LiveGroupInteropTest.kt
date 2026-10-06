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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lk.codegen.risime.crypto.RealMls
import lk.codegen.risime.data.BehaviourLog
import lk.codegen.risime.data.BlobFetch
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
import lk.codegen.risime.data.groups.CreatePayload
import lk.codegen.risime.data.groups.GroupApi
import lk.codegen.risime.data.groups.GroupOpType
import lk.codegen.risime.data.groups.GroupOpsExecutor
import lk.codegen.risime.data.groups.GroupStore
import lk.codegen.risime.data.groups.INLINE_MAX_BYTES
import lk.codegen.risime.data.groups.RenamePayload
import lk.codegen.risime.data.groups.RolePayload
import lk.codegen.risime.data.groups.UsersPayload
import lk.codegen.risime.data.groups.GROUP_NAME_PENDING
import lk.codegen.risime.data.groups.blobMatches
import lk.codegen.risime.data.groups.groupDisplayName
import lk.codegen.risime.ui.group.COMPOSER_REJOINING
import lk.codegen.risime.ui.group.GroupComposer
import lk.codegen.risime.ui.group.groupComposer
import lk.codegen.risime.data.mls.DeviceRegistrar
import lk.codegen.risime.data.mls.E2eeState
import lk.codegen.risime.data.mls.FakeMlsPendingDao
import lk.codegen.risime.data.mls.MlsApi
import lk.codegen.risime.data.mls.MlsPipeline
import lk.codegen.risime.data.mls.MlsUpgrader
import lk.codegen.risime.data.mls.Registration
import lk.codegen.risime.net.ApiClient
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.BlobRef
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.GroupCommitRequest
import lk.codegen.risime.net.GroupCreate
import lk.codegen.risime.net.GroupEvent
import lk.codegen.risime.net.GroupMember
import lk.codegen.risime.net.MlsCommitEvent
import lk.codegen.risime.net.MlsCommitRequest
import lk.codegen.risime.net.MlsMissing
import lk.codegen.risime.net.MsgSendGroup
import lk.codegen.risime.net.PendingOp
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.dmConversationId
import lk.codegen.risime.net.isGroupConversation
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Live §12 group interop (contract v1.9) with the REAL MLS core on the JVM, through the app's own
 * data layer: ChatEngine + MlsPipeline + GroupStore + the GroupOpsExecutor op outbox, real sockets
 * and ApiClient. Runs when RISIME_INTEROP_CONFIG has a "groups" block:
 *   "groups": {"A"|"B"|"C"|"D"|"L": {"id","token"}}
 * dev tokens; A friends with B, C, D, L; B–C friends; server with E2EE on.
 * Prints "INTEROP PASS|FAIL group: <check>".
 */
class LiveGroupInteropTest {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val devices = mutableListOf<Dev>()
    private val legacyClients = mutableListOf<PhoenixRealtimeClient>()
    private val legacyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val b64 = Base64.getEncoder()

    @After fun tearDown() {
        legacyClients.forEach { runCatching { it.stop() } }
        devices.forEach { runCatching { it.close() } }
        legacyScope.cancel()
    }

    private fun check(name: String, block: suspend () -> String?) = runBlocking {
        val t0 = System.nanoTime()
        try {
            val d = block()
            println("INTEROP PASS group: $name (${(System.nanoTime() - t0) / 1_000_000} ms)${d?.let { ": $it" } ?: ""}")
        } catch (t: Throwable) {
            println("INTEROP FAIL group: $name: ${t.message}")
            throw AssertionError("group: $name: ${t.message}", t)
        }
    }

    private fun ensure(c: Boolean, m: () -> String) { if (!c) throw AssertionError(m()) }

    private fun now() = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC).format(Instant.now())

    /**
     * One groups-capable app instance. Everything of this device (socket loop, event handling, op
     * outbox, test probes) runs on one thread, as the in-memory DAOs aren't thread-safe.
     */
    private inner class Dev(
        val url: String,
        val userId: String,
        val token: String,
        val name: String,
        trusted: List<String>,
        /** A sign-in after a logout keeps the device id (and starts a new MLS state). */
        val deviceId: String = UUID.randomUUID().toString(),
    ) {
        private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "dev-$name").apply { isDaemon = true } }
        val dispatcher = pool.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val api = ApiClient(http, { url }, { token })
        val mls = RealMls.device(userId, deviceId, trusted, attest = false)
        val messages = FakeMessageDao()
        /** §14: this device's image stack (real media core). */
        val img = LiveImageKit(api, messages, name + deviceId.take(4))
        val groupDao = FakeGroupDao()
        val opDao = FakeGroupOpDao()
        val raw = CopyOnWriteArrayList<Event>()
        val blobChecks = CopyOnWriteArrayList<Pair<BlobRef, Boolean>>()
        val unrecoverable = CopyOnWriteArrayList<String>()

        /** Commits per catch-up page (GET …/commits). */
        val pages = CopyOnWriteArrayList<Int>()
        var catchUpLimit = 50
        private var groupsKeyPackagesFor: String? = null
        private val tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() }
        private val kicks = Channel<Unit>(Channel.CONFLATED)
        private val catchUpLock = Mutex()
        val registrar = DeviceRegistrar(
            api, { deviceId }, "0.4.0-interop", { mls.engine },
            groupsReplacedFor = { groupsKeyPackagesFor }, setGroupsReplacedFor = { groupsKeyPackagesFor = it },
        )
        val store = GroupStore(
            groupDao, opDao, messages, { deviceId },
            metaOf = { conv -> mls.engine.groupMeta(conv) },
            needsRefresh = { conv -> scope.launch { refreshGroup(conv) } },
            onOpQueued = { kicks.trySend(Unit) },
        )
        lateinit var client: PhoenixRealtimeClient
        val chat: ChatEngine = ChatEngine(
            messages = messages, sync = FakeSyncDao(), tx = tx,
            scope = scope, realtime = { client }, meId = { userId },
            behaviour = BehaviourLog(FakeBehaviourDao(), { "s" }, { 0L }),
            mls = MlsPipeline(
                { mls.engine }, FakeMlsPendingDao(),
                log = { println("  [$name] mls: $it") },
                onJoined = { scope.launch { registrar.topUp() } },
                onParkedAhead = { conv -> scope.launch { catchUp(conv) } },
            ),
            mlsEngine = { mls.engine }, catchUp = { catchUp(it) },
            reactionsDao = lk.codegen.risime.data.FakeReactionDao(),
            groupsEnabled = { mls.engine.groupsSupported },
            groups = store,
            blobs = { ref -> fetchBlob(ref) },
            onUnrecoverable = { conv -> println("  [$name] unrecoverable $conv"); unrecoverable += conv },
            images = img.repo,
        )

        /** The app's GroupApi (AppContainer): every mutating call carries this device's X-Device-Id. */
        val groupApi = object : GroupApi {
            override suspend fun create(clientGroupId: String, memberIds: List<String>) =
                api.createGroup(GroupCreate(clientGroupId, memberIds), deviceId).map { it.group }
            override suspend fun group(id: String) = api.group(id).map { it.group }
            override suspend fun addMembers(id: String, userIds: List<String>) = api.addGroupMembers(id, userIds, deviceId).map { it.group }
            override suspend fun removeMember(id: String, userId: String) = api.removeGroupMember(id, userId, deviceId)
            override suspend fun leave(id: String) = api.leaveGroup(id, deviceId)
            override suspend fun setRole(id: String, userId: String, role: String) = api.setGroupRole(id, userId, role, deviceId).map { it.group }
            override suspend fun rejoin(id: String) = api.rejoinGroup(id, deviceId).map { it.group }
            override suspend fun reset(id: String, generation: Long) = api.resetGroup(id, generation, deviceId).map { it.generation }
            override suspend fun claim(userIds: List<String>, conversationId: String?) =
                api.claimKeyPackages(userIds, deviceId, conversationId).map { it.devices }
            override suspend fun commit(id: String, body: GroupCommitRequest) = api.groupCommit(id, body, deviceId).map { it.epoch }
            override suspend fun uploadBlob(conversationId: String, bytes: ByteArray) = api.uploadBlob(conversationId, bytes).map { it.ref() }
        }

        val mlsApi = object : MlsApi {
            override suspend fun group(conversationId: String) = api.mlsGroup(conversationId)
            override suspend fun claim(userIds: List<String>) = api.claimKeyPackages(userIds, deviceId)
            override suspend fun commit(conversationId: String, body: MlsCommitRequest) = api.mlsCommit(conversationId, body, deviceId)
        }

        fun opsExecutor(inlineMaxBytes: Int) = GroupOpsExecutor(
            { mls.engine }, groupApi, opDao, groupDao, store, tx,
            me = { userId }, deviceId = { deviceId }, catchUp = { catchUp(it) },
            log = { println("  [$name] $it") }, inlineMaxBytes = inlineMaxBytes,
        )

        /** Swapped (on this device's thread) to force blob references. */
        var executor = opsExecutor(INLINE_MAX_BYTES)

        init {
            val recorder = object : RealtimeListener {
                override suspend fun cursor() = chat.cursor()
                override suspend fun onEvents(events: List<Event>) { raw += events; chat.onEvents(events) }
                override suspend fun onLive() = chat.onLive()
                override suspend fun onAuthFailed() = Unit
            }
            client = PhoenixRealtimeClient(http, scope, recorder, backoffMs = listOf(200, 500))
            devices += this
            // The op outbox runner (AppContainer's kickGroupOps loop).
            scope.launch {
                while (isActive) {
                    runCatching { executor.runDue() }.onFailure { println("  [$name] runDue: $it") }
                    withTimeoutOrNull(250) { kicks.receive() }
                }
            }
        }

        /** AppContainer.catchUpCommits: page through GET …/commits while has_more. */
        suspend fun catchUp(conv: String): Unit = catchUpLock.withLock {
            var n = 0
            while (n++ < 40) {
                val g = mls.engine.group(conv) ?: return
                val r = when (val res = api.mlsCommits(conv, g.epoch, if (isGroupConversation(conv)) catchUpLimit else null)) {
                    is ApiResult.Ok -> res.value
                    is ApiResult.Error -> {
                        if (res.code == AuthErrors.LOG_EXPIRED || res.httpStatus == 410) unrecoverable += conv
                        return
                    }
                    is ApiResult.NetworkError -> return
                }
                pages += r.commits.size
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
         * AppContainer.syncGroups after registration / on every join: server truth, then §12.8
         * automatic rejoin for groups this device holds no MLS state for.
         */
        suspend fun syncGroups(graceMs: Long): List<String> {
            val r = api.groups() as? ApiResult.Ok ?: return emptyList()
            r.value.groups.forEach { store.applyServerGroup(it, userId) }
            return store.queueRejoins(r.value.groups, userId, { conv -> mls.engine.group(conv)?.generation }, graceMs)
        }

        suspend fun refreshGroup(conv: String) {
            when (val r = api.group(conv)) {
                is ApiResult.Ok -> store.applyServerGroup(r.value.group, userId)
                is ApiResult.Error -> if (r.httpStatus == 404) store.markGone(conv)
                is ApiResult.NetworkError -> Unit
            }
        }

        /** AppContainer.fetchBlob: download, then check size and SHA-256. */
        suspend fun fetchBlob(ref: BlobRef): BlobFetch = when (val r = api.downloadBlob(ref.blobId)) {
            is ApiResult.Ok -> blobMatches(r.value, ref).let { ok -> blobChecks += ref to ok; if (ok) BlobFetch.Ok(r.value) else BlobFetch.Gone }
            is ApiResult.Error -> if (r.httpStatus == 404) BlobFetch.Gone else BlobFetch.Transient
            is ApiResult.NetworkError -> BlobFetch.Transient
        }

        fun start() = client.start(RealtimeSession(url, userId, deviceId, "0.4.0-interop") { token })

        suspend fun <T> on(block: suspend () -> T): T = withContext(dispatcher) { block() }

        suspend fun <T> await(ms: Long, what: String, probe: suspend () -> T?): T = withTimeoutOrNull(ms) {
            var v = on { probe() }
            while (v == null) { delay(100); v = on { probe() } }
            v
        } ?: throw AssertionError("$name: timed out waiting for $what")

        suspend fun live() = await(15_000, "Live") { client.state.value.takeIf { it == ConnectionState.Live } }

        fun epoch(conv: String) = mls.engine.group(conv)?.epoch

        suspend fun queue(conv: String?, type: String, payload: String = "{}", clientGroupId: String? = null): Long =
            on { store.queueLocal(conv, type, payload, clientGroupId) }

        /** The op's final row (done or failed). */
        suspend fun awaitOp(id: Long, ms: Long = 45_000): GroupOpEntity =
            await(ms, "op $id") { opDao.rows[id]?.takeIf { it.state != GroupOpType.QUEUED } }

        suspend fun texts(conv: String) = on { messages.rows.values.filter { it.conversationId == conv && !it.system }.sortedBy { it.localTs }.map { it.body } }

        suspend fun awaitText(conv: String, text: String, ms: Long = 20_000) =
            await(ms, "\"$text\"") { messages.rows.values.firstOrNull { it.conversationId == conv && it.body == text } }

        fun groupEvents(conv: String) = raw.mapNotNull { runCatching { it.groupEvent() }.getOrNull() }.filter { it.groupId == conv }

        suspend fun awaitGroupEvent(conv: String, action: String, pred: (GroupEvent) -> Boolean = { true }) =
            await(20_000, "group_event $action") { groupEvents(conv).lastOrNull { it.action == action && pred(it) } }

        fun commits(conv: String) = raw.mapNotNull { runCatching { it.mlsCommit() }.getOrNull() }.filter { it.conversationId == conv }

        fun close() {
            runCatching { client.stop() }
            scope.cancel()
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
            mls.close()
            img.close()
        }
    }

    private inline fun <T, R> ApiResult<T>.map(f: (T) -> R): ApiResult<R> = when (this) {
        is ApiResult.Ok -> ApiResult.Ok(f(value))
        is ApiResult.Error -> this
        is ApiResult.NetworkError -> this
    }

    private fun isGroupEvent(e: Event): Boolean {
        val conv = (e.data["conversation_id"] ?: e.data["group_id"]) as? JsonPrimitive
        return conv?.isString == true && isGroupConversation(conv.content)
    }

    /** A pre-v1.7 app's socket (no device_id, no app_version) that records what it receives. */
    private fun legacySocket(url: String, userId: String, token: String, into: MutableList<Event>): PhoenixRealtimeClient {
        val c = PhoenixRealtimeClient(http, legacyScope, object : RealtimeListener {
            var last: String? = null
            override suspend fun cursor(): String? = last
            override suspend fun onEvents(events: List<Event>) { into += events; events.lastOrNull()?.let { last = it.eventId } }
            override suspend fun onLive() = Unit
            override suspend fun onAuthFailed() = Unit
        })
        legacyClients += c
        c.start(RealtimeSession(url, token, userId))
        return c
    }

    private fun json(s: kotlinx.serialization.KSerializer<*>, v: Any): String {
        @Suppress("UNCHECKED_CAST")
        return ProtocolJson.encodeToString(s as kotlinx.serialization.KSerializer<Any>, v)
    }

    @Test fun liveGroups() {
        val path = System.getenv("RISIME_INTEROP_CONFIG")
        assumeTrue("RISIME_INTEROP_CONFIG not set", !path.isNullOrBlank() && File(path).isFile)
        RealMls.assumeHostLibrary()
        val root = ProtocolJson.parseToJsonElement(File(path!!).readText()).jsonObject
        val g = root["groups"]?.jsonObject
        assumeTrue("no \"groups\" block in the interop config", g != null)
        val url = root["url"]!!.jsonPrimitive.content
        fun u(k: String) = g!![k]!!.jsonObject.let { it["id"]!!.jsonPrimitive.content to it["token"]!!.jsonPrimitive.content }
        val (aId, aTok) = u("A"); val (bId, bTok) = u("B"); val (cId, cTok) = u("C"); val (dId, dTok) = u("D"); val (lId, lTok) = u("L")
        val run = UUID.randomUUID().toString().take(8)
        var trusted = emptyList<String>()

        check("attestation keys served") {
            val r = ApiClient(http, { url }, { null }).attestationKeys()
            ensure(r is ApiResult.Ok && r.value.keys.isNotEmpty()) { "GET /mls/attestation_keys: $r (is E2EE on?)" }
            trusted = (r as ApiResult.Ok).value.keys.map { it.toString() }
            null
        }
        val a = Dev(url, aId, aTok, "A", trusted)
        val b = Dev(url, bId, bTok, "B", trusted)
        val c = Dev(url, cId, cTok, "C", trusted)
        val d = Dev(url, dId, dTok, "D", trusted)
        val l = Dev(url, lId, lTok, "L", trusted)
        val all = listOf(a, b, c, d, l)

        check("registration with the groups capability (0xFA01 key packages, replace)") {
            for (x in all) {
                ensure(x.mls.engine.groupsSupported) { "${x.name}: the MLS core has no groups support" }
                val r = x.on { x.registrar.register(pushToken = null) }
                ensure(r is Registration.Mls) { "${x.name}: $r" }
            }
            all.forEach { it.start() }
            all.forEach { it.live() }
            null
        }

        var conv = ""
        val groupName = "ZZ Group $run"
        check("1. A creates a group with B and C → everyone joins from the Welcome; name from group_meta") {
            val id = a.queue(null, GroupOpType.CREATE, json(CreatePayload.serializer(), CreatePayload(groupName, listOf(bId, cId))), clientGroupId = UUID.randomUUID().toString())
            val op = a.awaitOp(id)
            ensure(op.state == GroupOpType.DONE) { "create op: ${op.state} ${op.lastError}" }
            conv = op.conversationId!!
            ensure(isGroupConversation(conv)) { "conversation id $conv" }
            for (x in listOf(b, c)) {
                x.await(20_000, "joined") { x.mls.engine.group(conv) }
                x.await(20_000, "group row named from group_meta") { x.groupDao.groups[conv]?.takeIf { it.name == groupName } }
                val created = x.awaitGroupEvent(conv, GroupEvent.CREATED)
                ensure(created.actor == aId && created.members.orEmpty().map { it.userId }.toSet() == setOf(aId, bId, cId)) { "${x.name} created: $created" }
            }
            val meta = a.on { a.mls.engine.groupMeta(conv) }
            ensure(meta?.name == groupName && meta.admins == listOf(aId)) { "A meta: $meta" }
            ensure(a.on { a.groupDao.groups[conv]?.state } == GroupEntity.STATE_ACTIVE) { "A's row isn't active" }
            ensure(listOf(b, c).all { it.epoch(conv) == a.epoch(conv) }) { "epochs a=${a.epoch(conv)} b=${b.epoch(conv)} c=${c.epoch(conv)}" }
            "$conv epoch ${a.epoch(conv)}"
        }

        // B also runs a pre-v1.7 app (no device_id): it must never see group traffic (§12.1).
        val bLegacyEvents = CopyOnWriteArrayList<Event>()
        val bLegacy = legacySocket(url, bId, bTok, bLegacyEvents)
        runBlocking { withTimeoutOrNull(15_000) { while (bLegacy.state.value != ConnectionState.Live) delay(50) } }

        check("2. messages each way; every member decrypts; sender and names right") {
            val ma = "hello group $run"; val mb = "hi from B $run"; val mc = "C here $run"
            a.on { a.chat.sendText(conv, ma) }
            for (x in listOf(b, c)) ensure(x.awaitText(conv, ma).from == aId) { "${x.name}: wrong sender for A's message" }
            b.on { b.chat.sendText(conv, mb) }
            for (x in listOf(a, c)) ensure(x.awaitText(conv, mb).from == bId) { "${x.name}: wrong sender for B's message" }
            c.on { c.chat.sendText(conv, mc) }
            for (x in listOf(a, b)) ensure(x.awaitText(conv, mc).from == cId) { "${x.name}: wrong sender for C's message" }
            // Names from the group's member list (created event / GET /groups/{id}).
            for (x in listOf(a, b, c)) {
                val names = x.await(10_000, "member names") {
                    x.groupDao.members.values.filter { it.conversationId == conv }.associate { it.userId to it.displayName }.takeIf { it.size >= 3 }
                }
                ensure(names[aId] == "ZZ Interop GA" && names[bId] == "ZZ Interop GB" && names[cId] == "ZZ Interop GC") { "${x.name} names: $names" }
            }
            val ev = (a.raw + b.raw + c.raw).mapNotNull { it.messageData() }.filter { it.conversationId == conv }
            ensure(ev.isNotEmpty() && ev.all { it.body == null && it.ciphertext != null && it.to == null }) { "group message events: $ev" }
            null
        }

        var img1 = ""
        var img1Sha = ""
        check("13a. image in the group: B and C get the thumbnail first, then download and decrypt (SHA match)") {
            val (id, sha) = a.on { a.img.send(conv, aId, conv, "Group photo $run", w = 700, h = 500) }
            img1 = id; img1Sha = sha
            a.on { a.chat.flushOutbox() }
            for (x in listOf(b, c)) {
                val row = x.await(20_000, "image envelope") { x.img.media.rows.value[id] }
                ensure(row.state == "NONE" && x.on { x.img.repo.thumb(id, row) } != null) { "${x.name}: no thumbnail first (${row.state})" }
                ensure(x.on { x.img.fetch(id) } == sha) { "${x.name}: plaintext differs" }
                ensure(x.messages.rows[id]?.body == "Group photo $run") { "${x.name}: caption" }
            }
            "${a.img.media.get(id)!!.blobSize} bytes"
        }

        check("10b. a member's legacy app (no groups) gets no group fan-out") {
            val sent = a.on { a.client.sendMessage(lk.codegen.risime.net.MsgSend(UUID.randomUUID().toString(), bId, "dm to legacy $run", now())) }
            if (sent is PushResult.Ok) {
                withTimeoutOrNull(10_000) { while (bLegacyEvents.none { it.messageData()?.body == "dm to legacy $run" }) delay(50) }
                    ?: throw AssertionError("legacy socket never got the plaintext DM")
            }
            delay(500)
            val leaked = bLegacyEvents.filter { isGroupEvent(it) }
            ensure(leaked.isEmpty()) { "legacy socket got grp: events: ${leaked.map { it.kind }}" }
            "legacy saw ${bLegacyEvents.size} event(s), none for grp: (DM send: ${if (sent is PushResult.Ok) "ok" else sent})"
        }

        check("3. aggregated group_receipt; receipts GET shows delivered/read per member") {
            val text = "receipt probe $run"
            val cm = a.on { a.chat.sendText(conv, text) }!!
            b.awaitText(conv, text); c.awaitText(conv, text)
            val delivered = a.await(20_000, "all_delivered") {
                a.messages.rows[cm]?.takeIf { it.receiptDelivered == 2 && it.receiptOf == 2 && it.status in listOf(MessageStatus.DELIVERED.name, MessageStatus.READ.name) }
            }
            val mid = delivered.messageId!!
            b.on { b.chat.markConversationRead(conv) }
            val r1 = a.await(20_000, "receipts GET with B read") {
                (a.api.groupReceipts(conv, mid) as? ApiResult.Ok)?.value?.takeIf { r -> r.receipts.any { it.userId == bId && it.readAt != null } }
            }
            val rb = r1.receipts.first { it.userId == bId }; val rc = r1.receipts.first { it.userId == cId }
            ensure(r1.of == 2 && rb.deliveredAt != null && rc.deliveredAt != null && rc.readAt == null && r1.receipts.none { it.userId == aId }) { "receipts: $r1" }
            c.on { c.chat.markConversationRead(conv) }
            a.await(20_000, "all_read → READ") { a.messages.rows[cm]?.takeIf { it.status == MessageStatus.READ.name && it.receiptRead == 2 } }
            val events = a.raw.mapNotNull { runCatching { it.groupReceipt() }.getOrNull() }.filter { it.messageId == mid }
            ensure(events.any { it.allDelivered } && events.any { it.allRead }) { "group_receipt events: $events" }
            ensure(listOf(b, c).none { x -> x.raw.any { it.kind == Event.KIND_STATUS && it.data["message_id"]?.jsonPrimitive?.content == mid } }) { "a status event for a group message" }
            val bad = b.api.groupReceipts(conv, mid)
            ensure(bad is ApiResult.Error && bad.httpStatus == 404) { "receipts GET by a non-sender: $bad" }
            "${events.size} group_receipt event(s)"
        }

        val renamed = "ZZ Renamed $run"
        check("4. A renames → metadata_changed everywhere; non-admin B's rename refused") {
            val e0 = a.epoch(conv)!!
            val op = a.awaitOp(a.queue(conv, GroupOpType.RENAME, json(RenamePayload.serializer(), RenamePayload(renamed))))
            ensure(op.state == GroupOpType.DONE) { "rename: ${op.lastError}" }
            for (x in listOf(a, b, c)) {
                x.await(20_000, "renamed") { x.groupDao.groups[conv]?.takeIf { it.name == renamed && x.mls.engine.groupMeta(conv)?.name == renamed } }
                x.awaitGroupEvent(conv, GroupEvent.METADATA_CHANGED) { it.actor == aId }
            }
            ensure(a.epoch(conv) == e0 + 1) { "epoch ${a.epoch(conv)} after $e0" }
            val bOp = b.awaitOp(b.queue(conv, GroupOpType.RENAME, json(RenamePayload.serializer(), RenamePayload("B's name $run"))))
            ensure(bOp.state == GroupOpType.FAILED && (bOp.lastError == AuthErrors.NOT_ADMIN || bOp.lastError.orEmpty().contains("olicy"))) { "B rename: ${bOp.state} ${bOp.lastError}" }
            ensure(b.on { b.mls.engine.groupMeta(conv)?.name } == renamed && b.epoch(conv) == a.epoch(conv)) { "B's state moved" }
            "B refused: ${bOp.lastError?.take(80)}"
        }

        check("5. A adds D (not B's friend): B claims D's key package as co-member; D gets no history") {
            val op = a.awaitOp(a.queue(conv, GroupOpType.ADD, json(UsersPayload.serializer(), UsersPayload(listOf(dId)))))
            ensure(op.state == GroupOpType.DONE) { "add: ${op.lastError}" }
            d.await(30_000, "D joined") { d.mls.engine.group(conv) }
            d.await(20_000, "D's row named") { d.groupDao.groups[conv]?.takeIf { it.name == renamed } }
            for (x in listOf(a, b, c)) x.awaitGroupEvent(conv, GroupEvent.ADDED) { it.targets == listOf(dId) }
            ensure(listOf(b, c, d).all { it.epoch(conv) == a.epoch(conv) }) { "epochs differ" }
            val co = b.api.claimKeyPackages(listOf(dId), b.deviceId, conv)
            ensure(co is ApiResult.Ok && co.value.devices.any { it.userId == dId && it.deviceId == d.deviceId && it.keyPackage != null }) { "co-member claim: $co" }
            val plain = b.api.claimKeyPackages(listOf(dId), b.deviceId)
            ensure(plain is ApiResult.Error) { "claim without conversation_id for a non-friend: $plain" }
            val history = d.texts(conv)
            ensure(history.isEmpty()) { "D sees history: $history" }
            val m = "welcome D $run"
            a.on { a.chat.sendText(conv, m) }
            for (x in listOf(b, c, d)) x.awaitText(conv, m)
            val dTexts = d.texts(conv)
            ensure(dTexts == listOf(m)) { "D: $dTexts" }
            "plain claim: ${(plain as ApiResult.Error).code}"
        }

        check("6. A removes C → C can't decrypt new messages; the epoch advances") {
            val e0 = a.epoch(conv)!!
            val op = a.awaitOp(a.queue(conv, GroupOpType.REMOVE, json(UsersPayload.serializer(), UsersPayload(listOf(cId)))))
            ensure(op.state == GroupOpType.DONE) { "remove: ${op.lastError}" }
            c.await(30_000, "C removed_self") { if (c.mls.engine.group(conv) == null) Unit else null }
            c.await(10_000, "C's row removed") { c.groupDao.groups[conv]?.takeIf { it.state == GroupEntity.STATE_REMOVED } }
            a.await(10_000, "A's epoch advanced") { a.epoch(conv)?.takeIf { it > e0 } }
            for (x in listOf(b, d)) x.await(20_000, "epoch") { x.epoch(conv)?.takeIf { it == a.epoch(conv) } }
            c.awaitGroupEvent(conv, GroupEvent.REMOVED) { it.targets == listOf(cId) }
            val m = "after removal $run"
            a.on { a.chat.sendText(conv, m) }
            b.awaitText(conv, m); d.awaitText(conv, m)
            val ev = b.raw.mapNotNull { it.messageData() }.first { it.conversationId == conv && it.from == aId && it.epoch == a.epoch(conv) }
            val decrypted = c.on { runCatching { c.mls.engine.decrypt(conv, ev.generation!!, Base64.getDecoder().decode(ev.ciphertext)) } }
            ensure(decrypted.isFailure) { "removed C decrypted a new message" }
            delay(1_000)
            ensure(m !in c.texts(conv)) { "C shows the new message" }
            ensure(c.raw.none { it.messageData()?.clientMsgId == ev.clientMsgId }) { "C was sent the new message" }
            "epoch $e0 → ${a.epoch(conv)}"
        }

        check("13b. media reads: removed C still fetches the old image, not a new one; non-member L gets 404") {
            // C fetches the image from while it was a member again (fresh download).
            c.on {
                val row = c.img.media.get(img1)!!
                row.fileName?.let { c.img.files.file(it).delete() }
                c.img.media.update(row.copy(state = "NONE", fileName = null))
            }
            ensure(c.on { c.img.fetch(img1) } == img1Sha) { "removed C can't fetch its old image" }
            val (id2, sha2) = a.on { a.img.send(conv, aId, conv, "after C left $run", w = 300, h = 200) }
            a.on { a.chat.flushOutbox() }
            for (x in listOf(b, d)) {
                x.await(20_000, "second image") { x.img.media.rows.value[id2] }
                ensure(x.on { x.img.fetch(id2) } == sha2) { "${x.name}: second image differs" }
            }
            val blob2 = a.img.media.get(id2)!!.blobId!!
            val blob1 = a.img.media.get(img1)!!.blobId!!
            val cNew = c.api.downloadBlob(blob2)
            ensure(cNew is ApiResult.Error && cNew.httpStatus == 404) { "removed C fetched a new image: $cNew" }
            // §14.2: D's active interval overlaps [uploaded_at, now], so it may read the older blob if it learns
            // the id (§14.9: joiners only learn ids from envelopes they can decrypt).
            val dOld = d.api.downloadBlob(blob1)
            ensure(dOld is ApiResult.Ok) { "D (active since after the upload): $dOld" }
            val lOld = l.api.downloadBlob(blob1)
            ensure(lOld is ApiResult.Error && lOld.httpStatus == 404) { "non-member L: $lOld" }
            val own = a.api.downloadBlob(blob1)
            ensure(own is ApiResult.Ok) { "the owner can't read its own blob: $own" }
            null
        }

        check("9. blob refs: A re-adds C with commit_ref + welcome_ref; size/SHA-256 verified") {
            a.on { a.executor = a.opsExecutor(inlineMaxBytes = 0) }
            val e0 = a.epoch(conv)!!
            val op = a.awaitOp(a.queue(conv, GroupOpType.ADD, json(UsersPayload.serializer(), UsersPayload(listOf(cId)))))
            ensure(op.state == GroupOpType.DONE) { "add: ${op.lastError}" }
            c.await(30_000, "C rejoined") { c.mls.engine.group(conv) }
            a.on { a.executor = a.opsExecutor(INLINE_MAX_BYTES) }
            val commit = b.await(20_000, "the add commit at B") { b.commits(conv).firstOrNull { it.epoch == e0 } }
            ensure(commit.commit == null && commit.commitRef != null && commit.fromDevice == a.deviceId) { "commit event: $commit" }
            val welcome = c.raw.mapNotNull { runCatching { it.mlsWelcome() }.getOrNull() }.last { it.conversationId == conv }
            ensure(welcome.welcome == null && welcome.welcomeRef != null) { "welcome event: $welcome" }
            ensure(c.blobChecks.contains(welcome.welcomeRef!! to true)) { "C didn't verify the Welcome blob: ${c.blobChecks}" }
            for (x in listOf(b, d)) {
                x.await(20_000, "epoch") { x.epoch(conv)?.takeIf { it == a.epoch(conv) } }
                ensure(x.blobChecks.contains(commit.commitRef!! to true)) { "${x.name} didn't verify the commit blob" }
            }
            val bytes = (c.api.downloadBlob(welcome.welcomeRef!!.blobId) as ApiResult.Ok).value
            val ref = welcome.welcomeRef!!
            ensure(blobMatches(bytes, ref)) { "the Welcome blob doesn't match its ref" }
            val flipped = Base64.getEncoder().encodeToString(ByteArray(32))
            ensure(!blobMatches(bytes, ref.copy(sha256 = flipped)) && !blobMatches(bytes, ref.copy(size = ref.size + 1))) { "a tampered ref matched" }
            val outsider = l.api.downloadBlob(ref.blobId)
            ensure(outsider is ApiResult.Error && outsider.httpStatus == 404) { "non-member read the blob: $outsider" }
            c.awaitGroupEvent(conv, GroupEvent.ADDED) { it.targets == listOf(cId) }
            c.await(10_000, "C's row active") { c.groupDao.groups[conv]?.takeIf { it.state == GroupEntity.STATE_ACTIVE } }
            val m = "after re-add $run"
            a.on { a.chat.sendText(conv, m) }
            for (x in listOf(b, c, d)) x.awaitText(conv, m)
            "commit ${commit.commitRef!!.size} B, welcome ${ref.size} B by ref"
        }

        check("10a. catch-up after missing several commits, paged (limit 2, has_more)") {
            d.on { d.client.stop() }
            val e0 = a.epoch(conv)!!
            val names = (1..5).map { "ZZ Paging $it $run" }
            for (n in names) {
                val op = a.awaitOp(a.queue(conv, GroupOpType.RENAME, json(RenamePayload.serializer(), RenamePayload(n))))
                ensure(op.state == GroupOpType.DONE) { "rename $n: ${op.lastError}" }
            }
            ensure(a.epoch(conv) == e0 + 5) { "A epoch ${a.epoch(conv)} after $e0" }
            val m = "while D was away $run"
            a.on { a.chat.sendText(conv, m) }
            b.awaitText(conv, m)
            ensure(d.epoch(conv) == e0) { "D moved while offline" }
            d.on { d.catchUpLimit = 2; d.pages.clear(); d.catchUp(conv) }
            ensure(d.epoch(conv) == a.epoch(conv)) { "D at ${d.epoch(conv)}, A at ${a.epoch(conv)}" }
            ensure(d.pages.size >= 3 && d.pages.take(2).all { it == 2 }) { "pages ${d.pages}" }
            ensure(d.on { d.mls.engine.groupMeta(conv)?.name } == names.last()) { "D's name" }
            d.on { d.catchUpLimit = 50 }
            d.start(); d.live()
            d.awaitText(conv, m)
            d.await(10_000, "D's row name") { d.groupDao.groups[conv]?.takeIf { it.name == names.last() } }
            ensure(d.unrecoverable.isEmpty()) { "D unrecoverable: ${d.unrecoverable}" }
            "pages ${d.pages}"
        }

        check("10c. a user with a legacy app is not group-ready: add → 409 not_ready (legacy_app)") {
            val lLegacyEvents = CopyOnWriteArrayList<Event>()
            val leg = legacySocket(url, lId, lTok, lLegacyEvents)
            withTimeoutOrNull(15_000) { while (leg.state.value != ConnectionState.Live) delay(50) } ?: throw AssertionError("L legacy Live")
            leg.stop()
            val op = a.awaitOp(a.queue(conv, GroupOpType.ADD, json(UsersPayload.serializer(), UsersPayload(listOf(lId)))))
            ensure(op.state == GroupOpType.FAILED && op.lastError == AuthErrors.NOT_READY) { "add L: ${op.state} ${op.lastError}" }
            val r = a.api.addGroupMembers(conv, listOf(lId), a.deviceId)
            ensure(r is ApiResult.Error && r.httpStatus == 409 && r.code == AuthErrors.NOT_READY) { "add L: $r" }
            val missing = (r as ApiResult.Error).missing.orEmpty()
            ensure(missing.any { it.userId == lId && it.reason == MlsMissing.LEGACY_APP }) { "missing: $missing" }
            val g1 = (a.api.group(conv) as ApiResult.Ok).value.group
            ensure(g1.members.none { it.userId == lId }) { "L is listed: ${g1.members}" }
            ensure(l.raw.none { isGroupEvent(it) }) { "L got grp: events" }
            "missing=${missing.map { it.reason }}"
        }

        check("11. 1:1 E2EE (A–D) still works in the same run") {
            val dm = dmConversationId(aId, dId)
            val s = a.on { MlsUpgrader({ a.mls.engine }, a.mlsApi).ensure(dm, aId, dId) }
            ensure(s is E2eeState.Encrypted) { "upgrade: $s" }
            d.await(20_000, "D joined the DM") { d.mls.engine.group(dm) }
            a.on { a.chat.sendText(dId, "dm hello $run") }
            ensure(d.awaitText(dm, "dm hello $run").from == aId) { "sender" }
            d.on { d.chat.sendText(aId, "dm back $run") }
            a.awaitText(dm, "dm back $run")
            val ev = (a.raw + d.raw).mapNotNull { it.messageData() }.filter { it.conversationId == dm }
            ensure(ev.isNotEmpty() && ev.all { it.body == null && it.ciphertext != null }) { "plaintext in the DM" }
            null
        }

        check("7. B leaves → an admin device commits it (named committer); left event; B's msg:send → not_member") {
            b.on { b.groupDao.groups[conv]?.let { b.groupDao.upsert(it.copy(state = GroupEntity.STATE_LEFT)) } } // as GroupInfoViewModel.leave
            val op = b.awaitOp(b.queue(conv, GroupOpType.LEAVE))
            ensure(op.state == GroupOpType.DONE) { "leave: ${op.lastError}" }
            val named = a.await(20_000, "group_op naming A's device") {
                a.raw.mapNotNull { runCatching { it.groupOp() }.getOrNull() }
                    .firstOrNull { it.groupId == conv && it.op.type == PendingOp.REMOVE && it.op.userIds == listOf(bId) }
            }
            ensure(named.op.committer?.deviceId == a.deviceId && named.op.actor == bId) { "committer: ${named.op}" }
            b.await(30_000, "B removed_self") { if (b.mls.engine.group(conv) == null) Unit else null }
            val left = b.awaitGroupEvent(conv, GroupEvent.LEFT)
            ensure(left.actor == bId && left.targets == listOf(bId)) { "left: $left" }
            val last = b.commits(conv).last()
            ensure(last.fromDevice == a.deviceId) { "the removal commit came from ${last.fromDevice}" }
            for (x in listOf(c, d)) x.awaitGroupEvent(conv, GroupEvent.LEFT) { it.actor == bId }
            ensure(b.on { b.groupDao.groups[conv]?.state } == GroupEntity.STATE_LEFT) { "B's row" }
            val gen = a.mls.engine.group(conv)!!.generation
            val r = b.client.sendGroup(MsgSendGroup(UUID.randomUUID().toString(), conv, b64.encodeToString(ByteArray(64)), gen, a.epoch(conv)!!, now()))
            ensure(r == PushResult.Rejected(AuthErrors.NOT_MEMBER)) { "B msg:send: $r" }
            val m = "B is gone $run"
            a.on { a.chat.sendText(conv, m) }
            c.awaitText(conv, m); d.awaitText(conv, m)
            delay(1_000)
            ensure(m !in b.texts(conv)) { "B got a message after leaving" }
            "committer ${named.op.committer?.userId?.take(8)}"
        }

        check("8. last admin can't leave (409 last_admin); promote D, then A leaves") {
            a.on { a.groupDao.groups[conv]?.let { a.groupDao.upsert(it.copy(state = GroupEntity.STATE_LEFT)) } }
            val op = a.awaitOp(a.queue(conv, GroupOpType.LEAVE))
            ensure(op.state == GroupOpType.FAILED && op.lastError == AuthErrors.LAST_ADMIN) { "leave: ${op.state} ${op.lastError}" }
            ensure(a.on { a.groupDao.groups[conv]?.state } == GroupEntity.STATE_ACTIVE) { "A's row not restored after last_admin" }
            val role = a.awaitOp(a.queue(conv, GroupOpType.ROLE, json(RolePayload.serializer(), RolePayload(dId, GroupMember.ROLE_ADMIN))))
            ensure(role.state == GroupOpType.DONE) { "role: ${role.lastError}" }
            d.await(30_000, "D in group_meta admins") { d.mls.engine.groupMeta(conv)?.admins?.takeIf { dId in it } }
            d.awaitGroupEvent(conv, GroupEvent.ROLE_CHANGED) { it.targets == listOf(dId) && it.role == GroupMember.ROLE_ADMIN }
            d.await(10_000, "D's row admin") { d.groupDao.groups[conv]?.takeIf { it.myRole == GroupMember.ROLE_ADMIN } }
            a.on { a.groupDao.groups[conv]?.let { a.groupDao.upsert(it.copy(state = GroupEntity.STATE_LEFT)) } }
            val leave = a.awaitOp(a.queue(conv, GroupOpType.LEAVE))
            ensure(leave.state == GroupOpType.DONE) { "second leave: ${leave.lastError}" }
            val named = d.await(20_000, "group_op naming D's device") {
                d.raw.mapNotNull { runCatching { it.groupOp() }.getOrNull() }
                    .firstOrNull { it.groupId == conv && it.op.type == PendingOp.REMOVE && it.op.userIds == listOf(aId) }
            }
            ensure(named.op.committer?.deviceId == d.deviceId) { "committer: ${named.op.committer}" }
            a.await(30_000, "A removed_self") { if (a.mls.engine.group(conv) == null) Unit else null }
            c.awaitGroupEvent(conv, GroupEvent.LEFT) { it.actor == aId }
            val m = "A has left $run"
            d.on { d.chat.sendText(conv, m) }
            c.awaitText(conv, m)
            delay(1_000)
            ensure(m !in a.texts(conv)) { "A got a message after leaving" }
            val g1 = (d.api.group(conv) as ApiResult.Ok).value.group
            ensure(g1.members.map { it.userId }.toSet() == setOf(cId, dId) && g1.members.first { it.userId == dId }.admin) { "members: ${g1.members}" }
            null
        }
    
        // P0 nightly.12: logout (wipe) and login on the same device id start a new MLS state; the
        // app rejoins its groups (§12.8) and shows the §13.3 marker for what it can't read.
        bLegacy.stop()
        var b2: Dev? = null
        var conv2 = ""
        val name2 = "ZZ Rejoin $run"
        check("12a. a new group of A, B, C; messages each way") {
            ensure(b.on { b.registrar.register(pushToken = null) } is Registration.Mls) { "B re-register (supersedes B's legacy app)" }
            val id = a.queue(null, GroupOpType.CREATE, json(CreatePayload.serializer(), CreatePayload(name2, listOf(bId, cId))), clientGroupId = UUID.randomUUID().toString())
            val op = a.awaitOp(id)
            ensure(op.state == GroupOpType.DONE) { "create op: ${op.state} ${op.lastError}" }
            conv2 = op.conversationId!!
            for (x in listOf(b, c)) x.await(20_000, "joined $conv2") { x.mls.engine.group(conv2) }
            a.on { a.chat.sendText(conv2, "before 1 $run") }
            b.on { b.chat.sendText(conv2, "before 2 $run") }
            c.on { c.chat.sendText(conv2, "before 3 $run") }
            for (x in listOf(a, b, c)) for (m in listOf("before 1 $run", "before 2 $run", "before 3 $run")) x.awaitText(conv2, m)
            conv2
        }

        check("12b. B logs out (device deleted, MLS state wiped) while A, the only admin, is offline; C writes meanwhile") {
            a.client.stop()
            ensure(b.api.deleteDevice(b.deviceId) is ApiResult.Ok) { "DELETE /me/devices" }
            b.close()
            c.on { c.chat.sendText(conv2, "while B was away $run") }
            null
        }

        check("12c. B logs in again (same device id, new MLS state): the group shows its pending name, the composer says rejoining") {
            val nb = Dev(url, bId, bTok, "B2", trusted, deviceId = b.deviceId)
            b2 = nb
            ensure(nb.on { nb.registrar.register(pushToken = null) } is Registration.Mls) { "B2 registration" }
            nb.start()
            nb.live()
            val queued = nb.on { nb.syncGroups(graceMs = 300) }
            ensure(conv2 in queued) { "B2 queued rejoins: $queued" }
            val row = nb.await(10_000, "B2's group row") { nb.groupDao.groups[conv2] }
            ensure(nb.mls.engine.group(conv2) == null) { "B2 has the group before the rejoin" }
            ensure(groupDisplayName(row.name) == GROUP_NAME_PENDING) { "B2 shows \"${groupDisplayName(row.name)}\"" }
            ensure(groupComposer(row, encrypted = false) == GroupComposer.Disabled(COMPOSER_REJOINING)) { "composer: ${groupComposer(row, false)}" }
            val rejoin = nb.await(15_000, "B2's rejoin op done") { nb.opDao.rows.values.firstOrNull { it.conversationId == conv2 && it.type == GroupOpType.REJOIN && it.state != GroupOpType.QUEUED } }
            ensure(rejoin.state == GroupOpType.DONE) { "rejoin: ${rejoin.lastError}" }
            // The server holds one op that removes and re-adds B2's device, waiting for an admin device.
            val g = (nb.api.group(conv2) as ApiResult.Ok).value.group
            val pend = g.pending.filter { it.type == PendingOp.DEVICES }
            ensure(pend.size == 1 && pend[0].added.map { it.deviceId } == listOf(nb.deviceId) && pend[0].removed.map { it.deviceId } == listOf(nb.deviceId)) { "pending: ${g.pending}" }
            ensure(pend[0].committer == null) { "named while no admin device is online: ${pend[0].committer}" }
            // A send while rejoining waits in the outbox (retried after the join).
            nb.on { nb.chat.sendText(conv2, "queued while rejoining $run") }
            delay(500)
            ensure(nb.on { nb.messages.rows.values.first { it.body == "queued while rejoining $run" }.status } == MessageStatus.PENDING.name) { "queued send state" }
            null
        }

        check("12d. A comes back → A's device commits the re-add; B2 joins: right name, history marker, send and receive") {
            val nb = b2!!
            a.start()
            a.live()
            nb.await(45_000, "B2 joined from the Welcome") { nb.mls.engine.group(conv2) }
            val row = nb.await(10_000, "B2's name from group_meta") { nb.groupDao.groups[conv2]?.takeIf { it.name == name2 } }
            ensure(groupComposer(row, encrypted = true) == GroupComposer.Enabled) { "composer after join" }
            ensure(row.state == GroupEntity.STATE_ACTIVE) { "B2 row state ${row.state}" }
            val last = a.commits(conv2).last()
            ensure(last.fromDevice == a.deviceId) { "the re-add commit came from ${last.fromDevice}" }
            // §13.3: one marker for what this device can't read; never silent gaps, never those messages.
            val marker = nb.await(10_000, "history marker") { nb.messages.rows.values.firstOrNull { it.clientMsgId == "sys:history:$conv2" } }
            ensure(marker.body == "Earlier messages aren't available on this device") { "marker: ${marker.body}" }
            ensure(nb.on { nb.messages.rows.values.count { it.conversationId == conv2 && it.clientMsgId.startsWith("sys:history:") } } == 1) { "more than one marker" }
            val texts = nb.texts(conv2)
            ensure(texts.none { it.startsWith("before ") || it.startsWith("while B was away") }) { "B2 shows pre-rejoin messages: $texts" }
            ensure(nb.unrecoverable.isEmpty()) { "unrecoverable: ${nb.unrecoverable}" }
            // The queued send went out after the join; a new one too; A and C receive both.
            for (x in listOf(a, c)) ensure(x.awaitText(conv2, "queued while rejoining $run", 30_000).from == bId) { "${x.name}: queued send" }
            nb.on { nb.chat.sendText(conv2, "B is back $run") }
            for (x in listOf(a, c)) ensure(x.awaitText(conv2, "B is back $run").from == bId) { "${x.name}: B's new message" }
            // B2 receives new messages from both.
            a.on { a.chat.sendText(conv2, "welcome back $run") }
            c.on { c.chat.sendText(conv2, "hi again $run") }
            ensure(nb.awaitText(conv2, "welcome back $run").from == aId) { "B2: A's message" }
            ensure(nb.awaitText(conv2, "hi again $run").from == cId) { "B2: C's message" }
            ensure(listOf(a, c).all { it.epoch(conv2) == nb.epoch(conv2) }) { "epochs a=${a.epoch(conv2)} b2=${nb.epoch(conv2)} c=${c.epoch(conv2)}" }
            // Server: no ops left, B2's device is in the group again.
            val g = (nb.api.group(conv2) as ApiResult.Ok).value.group
            ensure(g.pending.isEmpty()) { "pending after the rejoin: ${g.pending}" }
            "epoch ${nb.epoch(conv2)}"
        }
}
}
