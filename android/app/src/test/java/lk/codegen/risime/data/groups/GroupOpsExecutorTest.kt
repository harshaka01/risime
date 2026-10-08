package lk.codegen.risime.data.groups

import kotlinx.coroutines.test.runTest
import lk.codegen.risime.data.FakeGroupDao
import lk.codegen.risime.data.FakeGroupOpDao
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.db.GroupEntity
import lk.codegen.risime.data.mls.DeviceRef
import lk.codegen.risime.data.mls.FakeMlsEngine
import lk.codegen.risime.data.mls.GroupRef
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.BlobRef
import lk.codegen.risime.net.ClaimedDevice
import lk.codegen.risime.net.Group
import lk.codegen.risime.net.GroupCommitRequest
import lk.codegen.risime.net.GroupMember
import lk.codegen.risime.net.GroupMeta
import lk.codegen.risime.net.GroupRejoinReply
import lk.codegen.risime.net.MlsDeviceRef
import lk.codegen.risime.net.MlsMissing
import lk.codegen.risime.net.PendingOp
import lk.codegen.risime.net.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The group-op outbox state machine (review chunk 6) against a scripted server and the fake core. */
class GroupOpsExecutorTest {
    private val me = "u-me"
    private val kamal = "u-kamal"
    private val nimal = "u-nimal"
    private val conv = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private val mls = FakeMlsEngine(me, "dev-me")
    private val opsDao = FakeGroupOpDao()
    private val groupDao = FakeGroupDao()
    private val store = GroupStore(groupDao, opsDao, FakeMessageDao(), { "dev-me" }, { mls.groupMeta(it) }, { now })
    private var now = 1_000L
    private val catchUps = mutableListOf<String>()

    private val api = FakeGroupApi()

    private val exec = GroupOpsExecutor(
        { mls }, api, opsDao, groupDao, store,
        object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
        me = { me }, deviceId = { "dev-me" }, catchUp = { catchUps += it }, clock = { now }, maxAttempts = 3,
    )

    private fun member(id: String, role: String = "member", state: String = "active") = GroupMember(id, id.removePrefix("u-"), null, role, "user", state, null)

    private fun device(user: String, dev: String) = ClaimedDevice(user, dev, true, "jws", FakeMlsEngine.b64("kp-$dev"))

    inner class FakeGroupApi : GroupApi {
        var group: Group = Group(conv, "creating", me, null, 1, null, "admin", listOf(member(me, "admin"), member(kamal)))
        var createReply: ApiResult<Group>? = null
        val commits = mutableListOf<GroupCommitRequest>()
        var commitReplies = ArrayDeque<ApiResult<Long>>()
        val claims = mutableListOf<Pair<List<String>, String?>>()
        val uploads = mutableListOf<Int>()
        var leaveReply: ApiResult<Unit> = ApiResult.Ok(Unit)
        val calls = mutableListOf<String>()

        override suspend fun create(clientGroupId: String, memberIds: List<String>) = createReply ?: ApiResult.Ok(group).also { calls += "create:$clientGroupId" }
        override suspend fun group(id: String): ApiResult<Group> = ApiResult.Ok(group)
        override suspend fun addMembers(id: String, userIds: List<String>): ApiResult<Group> {
            calls += "add:$userIds"
            group = group.copy(
                members = group.members + userIds.map { member(it, state = "pending_add") },
                pending = listOf(PendingOp("op-add", PendingOp.ADD, me, userIds, committer = MlsDeviceRef(me, "dev-me"))),
            )
            return ApiResult.Ok(group)
        }
        override suspend fun removeMember(id: String, userId: String): ApiResult<Unit> { calls += "remove:$userId"; return ApiResult.Ok(Unit) }
        override suspend fun leave(id: String) = leaveReply.also { calls += "leave" }
        override suspend fun setRole(id: String, userId: String, role: String): ApiResult<Group> {
            group = group.copy(pending = listOf(PendingOp("op-role", PendingOp.ROLE, me, listOf(userId), role = role, committer = MlsDeviceRef(me, "dev-me"))))
            return ApiResult.Ok(group)
        }
        /** §12.12.2 the rejoin reply's numbers (v1.21); null candidates = a pre-v1.21 server. */
        var rejoinCandidates: Int? = 1
        var rejoinExhausted = false
        var rejoinOp: PendingOp? = PendingOp("op-rj", PendingOp.DEVICES, me, added = listOf(MlsDeviceRef(me, "dev-me")), committer = MlsDeviceRef(kamal, "dev-kamal"), createdAt = "1970-01-01T00:00:01Z")
        var resetReply: ApiResult<Long>? = null
        override suspend fun rejoin(id: String): ApiResult<GroupRejoinReply> {
            calls += "rejoin"
            return ApiResult.Ok(GroupRejoinReply(group, rejoinOp, rejoinCandidates, rejoinExhausted))
        }
        override suspend fun reset(id: String, generation: Long): ApiResult<Long> { calls += "reset:$generation"; return resetReply ?: ApiResult.Ok(generation + 1) }
        override suspend fun claim(userIds: List<String>, conversationId: String?): ApiResult<List<ClaimedDevice>> {
            claims += userIds to conversationId
            return ApiResult.Ok(userIds.filter { it != me }.map { device(it, "dev-${it.removePrefix("u-")}") } + device(me, "dev-me2"))
        }
        override suspend fun commit(id: String, body: GroupCommitRequest): ApiResult<Long> {
            commits += body
            return commitReplies.removeFirstOrNull() ?: ApiResult.Ok(body.epoch + 1)
        }
        override suspend fun uploadBlob(conversationId: String, bytes: ByteArray): ApiResult<BlobRef> {
            uploads += bytes.size
            return ApiResult.Ok(BlobRef("blob-${uploads.size}", bytes.size.toLong(), "sha"))
        }
        var officialReply: ApiResult<Group>? = null
        override suspend fun createOfficial(chatId: String): ApiResult<Group> = (officialReply ?: ApiResult.Ok(group)).also { calls += "official:$chatId" }
    }

    private suspend fun queue(type: String, payload: String = "{}", clientGroupId: String? = null, c: String? = conv) = store.queueLocal(c, type, payload, clientGroupId)

    private fun conflict() = ApiResult.Error(409, "epoch_conflict", "", epoch = 9)

    @Test fun createPostsClaimsCommitsEpochZeroWithMetaAndActivates() = runTest {
        val id = queue(GroupOpType.CREATE, ProtocolJson.encodeToString(CreatePayload.serializer(), CreatePayload("Pilot team", listOf(kamal))), "cg-1", c = null)
        assertNull(exec.runDue())
        assertEquals(GroupOpType.DONE, opsDao.rows[id]!!.state)
        assertEquals(conv, opsDao.rows[id]!!.conversationId)
        assertEquals(listOf("create:cg-1"), api.calls)
        assertEquals(listOf(listOf(kamal, me) to null), api.claims) // friends + my other devices
        val c = api.commits.single()
        assertEquals(0L, c.epoch)
        assertNull(c.opId)
        assertEquals(setOf("dev-kamal", "dev-me2"), c.added.map { it.deviceId }.toSet())
        assertTrue(c.welcome != null && c.commit != null)
        assertEquals(GroupMeta(name = "Pilot team", admins = listOf(me)), mls.groupMeta(conv))
        assertEquals(1L, mls.group(conv)!!.epoch)
        val g = groupDao.groups[conv]!!
        assertEquals(GroupEntity.STATE_ACTIVE, g.state)
        assertEquals("Pilot team", g.name)
    }

    // ---- §24.2 Official creation ----

    private val risi = "u-risi"
    private val official = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"

    private fun officialGroup(chatId: String, chatKind: String, admins: List<String>) = Group(
        official, "creating", me, null, 1, null, "admin",
        listOf(me, kamal).map { member(it, if (it in admins) "admin" else "member") } + GroupMember(risi, "Risi", null, "member", "agent", "active", null),
        chatId = chatId, tab = "official", chatKind = chatKind, agents = listOf(risi),
    )

    @Test fun startOfficialInA1to1CommitsEpochZeroWithBothUsersAdminAndRisiAsAgent() = runTest {
        val dm = "dm:${kamal}_$me"
        api.group = officialGroup(dm, "dm", listOf(me, kamal))
        val id = queue(GroupOpType.CREATE_OFFICIAL, ProtocolJson.encodeToString(OfficialPayload.serializer(), OfficialPayload(dm)), c = official)
        assertNull(exec.runDue())
        assertEquals(GroupOpType.DONE, opsDao.rows[id]!!.state)
        assertEquals(listOf("official:$dm"), api.calls)
        assertEquals(listOf(listOf(me, kamal, risi) to official), api.claims) // §12.5 with the Official id: Risi's claim is allowed only here
        val c = api.commits.single()
        assertEquals(0L, c.epoch)
        assertEquals(setOf("dev-kamal", "dev-risi", "dev-me2"), c.added.map { it.deviceId }.toSet())
        assertEquals(
            GroupMeta(name = "", admins = listOf(me, kamal), tab = "official", chatId = dm, agents = listOf(risi)),
            mls.groupMeta(official),
        )
    }

    @Test fun startOfficialInAGroupTakesNameAndAdminsFromThePrivateMlsState() = runTest {
        mls.metas[conv] = GroupMeta(name = "Site team", admins = listOf(kamal)) // the Private group's MLS meta, not the server's roles
        api.group = officialGroup(conv, "group", listOf(me))
        queue(GroupOpType.CREATE_OFFICIAL, ProtocolJson.encodeToString(OfficialPayload.serializer(), OfficialPayload(conv)), c = official)
        exec.runDue()
        assertEquals(GroupMeta(name = "Site team", admins = listOf(kamal), tab = "official", chatId = conv, agents = listOf(risi)), mls.groupMeta(official))
    }

    @Test fun startOfficialRefusalsAreFinalAndAnActiveOfficialIsDone() = runTest {
        api.officialReply = ApiResult.Error(409, AuthErrors.NOT_READY, "")
        val id = queue(GroupOpType.CREATE_OFFICIAL, ProtocolJson.encodeToString(OfficialPayload.serializer(), OfficialPayload(conv)), c = null)
        exec.runDue()
        assertEquals(GroupOpType.FAILED, opsDao.rows[id]!!.state)
        assertEquals(AuthErrors.NOT_READY, opsDao.rows[id]!!.lastError)
        assertTrue(api.commits.isEmpty())
        // 200 with the group already active (another member created it) and held here: nothing to commit.
        api.officialReply = null
        api.group = officialGroup(conv, "group", listOf(me)).copy(state = "active", epoch = 3)
        mls.groups[official] = GroupRef(official, 1, 3)
        val id2 = queue(GroupOpType.CREATE_OFFICIAL, ProtocolJson.encodeToString(OfficialPayload.serializer(), OfficialPayload(conv)), c = null)
        exec.runDue()
        assertEquals(GroupOpType.DONE, opsDao.rows[id2]!!.state)
        assertTrue(api.commits.isEmpty())
    }

    @Test fun membersChangedRebuildsOfficialEpochZeroForTheNewMemberSet() = runTest {
        api.group = officialGroup(conv, "group", listOf(me))
        api.commitReplies.add(ApiResult.Error(409, lk.codegen.risime.net.TabsErrors.MEMBERS_CHANGED, ""))
        val id = queue(GroupOpType.CREATE_OFFICIAL, ProtocolJson.encodeToString(OfficialPayload.serializer(), OfficialPayload(conv)), c = official)
        exec.runDue()
        // Not permanent: queued again, the stale epoch 0 dropped (nothing merged).
        assertEquals(GroupOpType.QUEUED, opsDao.rows[id]!!.state)
        assertEquals(lk.codegen.risime.net.TabsErrors.MEMBERS_CHANGED, opsDao.rows[id]!!.lastError)
        assertFalse(mls.hasPending)
        assertNull(mls.group(official))
        // Nimal joined the chat meanwhile: the server re-synced the Official members.
        val nimal = "u-nimal"
        api.group = api.group.copy(members = api.group.members + member(nimal))
        now += 60_000
        exec.runDue()
        assertEquals(GroupOpType.DONE, opsDao.rows[id]!!.state)
        assertEquals(2, api.commits.size)
        assertEquals(2, api.claims.size) // key packages claimed again
        assertTrue(nimal in api.claims.last().first)
        assertTrue("dev-nimal" in api.commits.last().added.map { it.deviceId })
        assertEquals(0L, api.commits.last().epoch)
        assertEquals(1L, mls.group(official)!!.epoch)
    }

    @Test fun membersChangedIsBoundedByTheOpsAttempts() = runTest {
        api.group = officialGroup(conv, "group", listOf(me))
        repeat(3) { api.commitReplies.add(ApiResult.Error(409, lk.codegen.risime.net.TabsErrors.MEMBERS_CHANGED, "")) }
        val id = queue(GroupOpType.CREATE_OFFICIAL, ProtocolJson.encodeToString(OfficialPayload.serializer(), OfficialPayload(conv)), c = official)
        repeat(3) {
            exec.runDue()
            now += 600_000
        }
        assertEquals(GroupOpType.FAILED, opsDao.rows[id]!!.state) // maxAttempts = 3 here
        assertEquals(3, api.commits.size)
        assertFalse(mls.hasPending)
    }

    @Test fun officialEpoch0MetaNeverNamesAnAgentAdmin() {
        val g = officialGroup(conv, "group", listOf(me)).copy(members = listOf(member(me, "admin"), GroupMember(risi, "Risi", null, "admin", "agent", "active", null)))
        val m = officialEpoch0Meta(conv, g, GroupMeta(name = "Site", admins = listOf(me, risi)), null)
        assertEquals(listOf(me), m.admins)
        assertEquals(listOf(risi), m.agents)
    }

    @Test fun createNotReadyFailsWithTheReasonAndRemovesTheCreatingRow() = runTest {
        api.createReply = ApiResult.Error(409, "not_ready", "", missing = listOf(MlsMissing(kamal, null, MlsMissing.LEGACY_APP)))
        val id = queue(GroupOpType.CREATE, ProtocolJson.encodeToString(CreatePayload.serializer(), CreatePayload("x", listOf(kamal))), "cg-2", c = null)
        exec.runDue()
        assertEquals(GroupOpType.FAILED, opsDao.rows[id]!!.state)
        assertEquals("not_ready", opsDao.rows[id]!!.lastError)
        assertEquals("Someone needs to update RisiMe first.", groupOpErrorText(opsDao.rows[id]!!.lastError))
        assertTrue(api.commits.isEmpty())
    }

    private fun activeGroup(epoch: Long = 4) {
        mls.groups[conv] = GroupRef(conv, 1, epoch)
        mls.metas[conv] = GroupMeta(name = "Pilot team", admins = listOf(me))
        mls.memberLists[conv] = mutableListOf(DeviceRef(me, "dev-me"), DeviceRef(kamal, "dev-kamal"))
        api.group = api.group.copy(state = "active", epoch = epoch)
    }

    @Test fun adminAddQueuesTheNamedOpAndCommitsItWithOpId() = runTest {
        activeGroup()
        queue(GroupOpType.ADD, ProtocolJson.encodeToString(UsersPayload.serializer(), UsersPayload(listOf(nimal))))
        exec.runDue() // POST members → the reply's op names this device → queued
        assertEquals("op-add", opsDao.rows.values.last().opId)
        exec.runDue() // the commit op: re-derived from GET /groups, claim by group, commit
        val c = api.commits.single()
        assertEquals("op-add", c.opId)
        assertEquals(4L, c.epoch)
        assertEquals(listOf("dev-nimal", "dev-me2"), c.added.map { it.deviceId })
        assertEquals(listOf(nimal) to conv, api.claims.single())
        assertTrue(opsDao.rows.values.all { it.state == GroupOpType.DONE })
        assertEquals(5L, mls.group(conv)!!.epoch)
    }

    /** §12.4a: this device is a member (not admin) in a group whose admin is offline; nimal holds a leaf. */
    private fun memberGroup() {
        mls.groups[conv] = GroupRef(conv, 1, 4)
        mls.metas[conv] = GroupMeta(name = "Pilot team", admins = listOf(kamal))
        mls.memberLists[conv] = mutableListOf(DeviceRef(kamal, "dev-kamal"), DeviceRef(me, "dev-me"), DeviceRef(nimal, "dev-nimal-old"))
        api.group = Group(conv, "active", kamal, null, 1, 4, "member", listOf(member(kamal, "admin"), member(me), member(nimal)))
    }

    private fun namedDevicesOp(id: String, added: List<MlsDeviceRef>, removed: List<MlsDeviceRef> = emptyList()): PendingOp {
        val op = PendingOp(id, PendingOp.DEVICES, null, added = added, removed = removed, committer = MlsDeviceRef(me, "dev-me"))
        api.group = api.group.copy(pending = listOf(op))
        return op
    }

    @Test fun aMemberNamedForAnotherMembersNewPhoneCommitsItWithTheOpIdAndExactLists() = runTest {
        memberGroup()
        store.queueCommit(conv, namedDevicesOp("op-dev", listOf(MlsDeviceRef(nimal, "dev-nimal"))))
        assertNull(exec.runDue())
        val c = api.commits.single()
        assertEquals("op-dev", c.opId)
        assertEquals(listOf(MlsDeviceRef(nimal, "dev-nimal")), c.added) // exactly the op's added: not my own dev-me2
        assertTrue(c.removed.isEmpty())
        assertTrue(c.welcome != null)
        assertEquals(listOf(nimal) to conv, api.claims.single())
        assertEquals(GroupOpType.DONE, opsDao.rows.values.single().state)
        assertTrue(DeviceRef(nimal, "dev-nimal") in mls.members(conv))
    }

    @Test fun aMemberReAddsTheSameDeviceOfAnotherMember() = runTest {
        memberGroup()
        val d = MlsDeviceRef(nimal, "dev-nimal")
        mls.memberLists[conv]!! += DeviceRef(nimal, "dev-nimal")
        store.queueCommit(conv, namedDevicesOp("op-rejoin", listOf(d), listOf(d)))
        exec.runDue()
        val c = api.commits.single()
        assertEquals(listOf(d), c.added)
        assertEquals(listOf(d), c.removed)
    }

    @Test fun aMemberRefusesAnOpItMayNotCommitWithoutClaimingOrLooping() = runTest {
        memberGroup()
        // A new user (no leaf), and a swap of nimal's old phone for another device: both admin-only.
        val newUser = store.queueCommit(conv, namedDevicesOp("op-new", listOf(MlsDeviceRef("u-sunil", "dev-sunil"))))
        assertTrue(newUser)
        exec.runDue()
        store.queueCommit(conv, namedDevicesOp("op-swap", listOf(MlsDeviceRef(nimal, "dev-nimal")), listOf(MlsDeviceRef(nimal, "dev-nimal-old"))))
        assertNull("nothing left to retry", exec.runDue())
        assertTrue(api.commits.isEmpty())
        assertTrue(api.claims.isEmpty())
        assertTrue(opsDao.rows.values.all { it.state == GroupOpType.FAILED && it.lastError!!.startsWith("policy") })
        // A repeated naming of the same op isn't queued again.
        assertFalse(store.queueCommit(conv, api.group.pending.single()))
    }

    @Test fun aCorePolicyRefusalIsReportedOnceAndNeverRetried() = runTest {
        memberGroup()
        mls.policyRefusal = "non-admin may not swap"
        store.queueCommit(conv, namedDevicesOp("op-dev", listOf(MlsDeviceRef(nimal, "dev-nimal"))))
        assertNull(exec.runDue())
        val row = opsDao.rows.values.single()
        assertEquals(GroupOpType.FAILED, row.state)
        assertEquals(1, api.claims.size)
        assertTrue(api.commits.isEmpty())
        assertFalse(mls.hasPending)
        assertTrue(row.lastError!!.startsWith("policy"))
    }

    @Test fun aMemberWaitsWhenTheNewPhoneHasNoKeyPackageYet() = runTest {
        memberGroup()
        // The claim returns nimal's other device, not the op's: a subset would be bad_request.
        store.queueCommit(conv, namedDevicesOp("op-dev", listOf(MlsDeviceRef(nimal, "dev-nimal-new"))))
        val next = exec.runDue()
        assertTrue(next != null)
        assertTrue(api.commits.isEmpty())
        assertEquals(GroupOpType.QUEUED, opsDao.rows.values.single().state)
    }

    @Test fun aServerRefusalOfAMembersCommitIsDroppedSilently() = runTest {
        memberGroup()
        api.commitReplies.add(ApiResult.Error(403, "not_admin", ""))
        store.queueCommit(conv, namedDevicesOp("op-dev", listOf(MlsDeviceRef(nimal, "dev-nimal"))))
        assertNull(exec.runDue())
        assertEquals(GroupOpType.FAILED, opsDao.rows.values.single().state)
        assertFalse("our commit was dropped, not merged", mls.hasPending)
        assertEquals(4L, mls.group(conv)!!.epoch)
    }

    @Test fun anOpCompletedElsewhereIsDoneWithoutACommit() = runTest {
        activeGroup()
        store.queueCommit(conv, PendingOp("op-gone", PendingOp.REMOVE, me, listOf(kamal), committer = MlsDeviceRef(me, "dev-me")))
        exec.runDue()
        assertTrue(api.commits.isEmpty())
        assertEquals(GroupOpType.DONE, opsDao.rows.values.single().state)
    }

    @Test fun epochConflictDropsOurCommitCatchesUpAndRebuildsFromServerState() = runTest {
        activeGroup()
        val op = PendingOp("op-rm", PendingOp.REMOVE, me, listOf(kamal), committer = MlsDeviceRef(me, "dev-me"))
        api.group = api.group.copy(pending = listOf(op))
        store.queueCommit(conv, op)
        api.commitReplies.add(conflict())
        val next = exec.runDue()
        assertEquals(listOf(conv), catchUps)
        assertFalse(mls.hasPending) // never merged, never kept
        assertEquals(4L, mls.group(conv)!!.epoch)
        val row = opsDao.rows.values.single()
        assertEquals(GroupOpType.QUEUED, row.state)
        assertEquals(1, row.attempts)
        assertEquals(now + 500, next)

        now += 1_000
        exec.runDue()
        assertEquals(2, api.commits.size)
        assertEquals(listOf("dev-kamal"), api.commits[1].removed.map { it.deviceId })
        assertEquals(GroupOpType.DONE, opsDao.rows.values.single().state)
    }

    @Test fun largeCommitGoesByBlobReference() = runTest {
        activeGroup()
        mls.bigCommit = true
        queue(GroupOpType.RENAME, ProtocolJson.encodeToString(RenamePayload.serializer(), RenamePayload("Rise core")))
        exec.runDue()
        val c = api.commits.single()
        assertNull(c.commit)
        assertEquals("blob-1", c.commitRef!!.blobId)
        assertEquals(listOf(70_000), api.uploads)
        assertTrue(c.metaChanged)
        assertEquals("Rise core", mls.groupMeta(conv)!!.name)
    }

    @Test fun roleOpWritesTheServerAdminListPlusTheTarget() = runTest {
        activeGroup()
        queue(GroupOpType.ROLE, ProtocolJson.encodeToString(RolePayload.serializer(), RolePayload(kamal, "admin")))
        exec.runDue()
        exec.runDue()
        assertEquals("op-role", api.commits.single().opId)
        assertEquals(listOf(me, kamal), mls.groupMeta(conv)!!.admins)
    }

    @Test fun lastAdminLeaveFailsAndTheLocalLeaveIsUndone() = runTest {
        groupDao.upsert(GroupEntity(conv, "x", "admin", GroupEntity.STATE_LEFT, null, null, 1, null, null, null, 1))
        api.leaveReply = ApiResult.Error(409, "last_admin", "")
        val id = queue(GroupOpType.LEAVE)
        exec.runDue()
        assertEquals("last_admin", opsDao.rows[id]!!.lastError)
        assertEquals(GroupEntity.STATE_ACTIVE, groupDao.groups[conv]!!.state)
    }

    @Test fun networkErrorsBackOffThenFail() = runTest {
        api.leaveReply = ApiResult.NetworkError(IOException("down"))
        val id = queue(GroupOpType.LEAVE)
        assertEquals(now + 5_000, exec.runDue())
        assertNull(exec.runDue().takeIf { false }) // not due yet: nothing ran
        assertEquals(1, opsDao.rows[id]!!.attempts)
        now += 5_000; exec.runDue()
        assertEquals(2, opsDao.rows[id]!!.attempts)
        assertEquals(1_000 + 5_000 + 10_000L, opsDao.rows[id]!!.nextAt) // doubled
        now += 10_000; exec.runDue()
        assertEquals(GroupOpType.FAILED, opsDao.rows[id]!!.state)
    }

    @Test fun rebuildMakesTheNewGenerationWithServerAdmins() = runTest {
        activeGroup()
        groupDao.upsert(GroupEntity(conv, "Pilot team", "admin", "active", null, null, 2, null, null, null, 1))
        val op = PendingOp("op-rb", PendingOp.REBUILD, me, committer = MlsDeviceRef(me, "dev-me"))
        api.group = api.group.copy(generation = 2, epoch = null, members = listOf(member(me, "admin"), member(kamal, "admin")), pending = listOf(op))
        store.queueCommit(conv, op)
        exec.runDue()
        val c = api.commits.single()
        assertEquals(2L, c.generation)
        assertEquals(0L, c.epoch)
        assertEquals("op-rb", c.opId)
        assertEquals(GroupMeta(name = "Pilot team", admins = listOf(me, kamal)), mls.groupMeta(conv))
        assertEquals(2L, mls.group(conv)!!.generation)
    }

    @Test fun rejoinAndAManualResetCallTheServer() = runTest {
        groupDao.upsert(GroupEntity(conv, "x", "admin", "active", null, null, 3, null, null, null, 1))
        api.group = api.group.copy(state = "active", generation = 3, epoch = 2)
        queue(GroupOpType.REJOIN)
        queue(GroupOpType.RESET)
        exec.runDue()
        assertEquals(listOf("rejoin", "reset:3"), api.calls)
    }

    // ---- v1.21 §12.12.3: a rejoin waits; only an admin resets, and only as the last resort ----

    private val day = lk.codegen.risime.data.mls.RejoinRules.NO_CANDIDATE_RESET_MS
    private val auto = ProtocolJson.encodeToString(RejoinPayload.serializer(), RejoinPayload(ifMissing = true))

    /** I'm the only admin of an active group and this device holds no state for it (a reinstall). */
    private suspend fun reinstalledOnlyAdmin() {
        api.group = api.group.copy(state = "active", epoch = 9, myRole = GroupMember.ROLE_ADMIN, members = listOf(member(me, "admin"), member(kamal)))
        groupDao.upsert(GroupEntity(conv, "Pilot team", "admin", "active", null, null, 1, null, null, null, 1))
    }

    @Test fun theReinstalledOnlyAdminRejoinsAndWaitsWhileAMemberCanReAddIt() = runTest {
        reinstalledOnlyAdmin()
        val id = queue(GroupOpType.REJOIN, auto)
        exec.runDue()
        assertEquals(listOf("rejoin"), api.calls) // no reset, no generation change
        assertEquals(GroupOpType.DONE, opsDao.rows[id]!!.state)
        val w = store.rejoinWaits.value[conv]!!
        assertEquals(1, w.candidates)
        assertTrue(w.committerNamed)
        assertFalse(w.waitingForOthers(now)) // a committer is named: "Setting up encryption on this phone…"
        assertTrue(w.waitingForOthers(now + 60_000)) // nothing landed for a minute: "Waiting for a group member…"
    }

    @Test fun anOldAutomaticResetRowIsARejoinNow() = runTest {
        reinstalledOnlyAdmin()
        val id = queue(GroupOpType.RESET, auto) // queued by a pre-v1.21 app before the update
        exec.runDue()
        assertEquals(listOf("rejoin"), api.calls)
        assertEquals(GroupOpType.DONE, opsDao.rows[id]!!.state)
    }

    @Test fun anAdminResetsWhenTheOpIsExhausted() = runTest {
        reinstalledOnlyAdmin()
        api.rejoinExhausted = true
        api.rejoinOp = api.rejoinOp!!.copy(committer = null)
        queue(GroupOpType.REJOIN, auto)
        exec.runDue()
        assertEquals(listOf("rejoin", "reset:1"), api.calls)
        assertNull(store.rejoinWaits.value[conv])
    }

    @Test fun anAdminWithNoCandidateResetsOnlyAfter24Hours() = runTest {
        reinstalledOnlyAdmin()
        api.rejoinCandidates = 0
        api.rejoinOp = api.rejoinOp!!.copy(committer = null) // created at t = 1 s
        queue(GroupOpType.REJOIN, auto)
        exec.runDue()
        assertEquals(listOf("rejoin"), api.calls)
        assertFalse(store.rejoinWaits.value[conv]!!.waitingForOthers(now + day)) // nobody to wait for: just "Setting up…"
        now = 1_000 + day
        queue(GroupOpType.REJOIN, auto)
        exec.runDue()
        assertEquals(listOf("rejoin", "rejoin", "reset:1"), api.calls)
    }

    @Test fun aNonAdminNeverResets() = runTest {
        reinstalledOnlyAdmin()
        api.group = api.group.copy(myRole = GroupMember.ROLE_MEMBER, members = listOf(member(me), member(kamal, "admin")))
        api.rejoinExhausted = true
        queue(GroupOpType.REJOIN, auto)
        exec.runDue()
        api.rejoinExhausted = false
        api.rejoinCandidates = 0
        now = 10 * day
        queue(GroupOpType.REJOIN, auto)
        exec.runDue()
        assertEquals(listOf("rejoin", "rejoin"), api.calls)
    }

    @Test fun aPreV121ReplyWithoutCandidatesNeverResets() = runTest {
        reinstalledOnlyAdmin()
        api.rejoinCandidates = null
        api.rejoinOp = null
        now = 10 * day
        queue(GroupOpType.REJOIN, auto)
        exec.runDue()
        assertEquals(listOf("rejoin"), api.calls)
    }

    @Test fun rejoinPendingOnTheLastResortResetKeepsWaiting() = runTest {
        reinstalledOnlyAdmin()
        api.rejoinExhausted = true
        api.resetReply = ApiResult.Error(409, AuthErrors.REJOIN_PENDING, "")
        val id = queue(GroupOpType.REJOIN, auto)
        exec.runDue()
        assertEquals(listOf("rejoin", "reset:1"), api.calls)
        assertEquals(GroupOpType.DONE, opsDao.rows[id]!!.state) // not failed, not retried
        assertTrue(conv in store.rejoinWaits.value)
        assertTrue(opsDao.queued().isEmpty())
    }

    @Test fun aManualResetRefusedWithRejoinPendingFailsWithTheReasonAndQueuesARejoin() = runTest {
        reinstalledOnlyAdmin()
        api.resetReply = ApiResult.Error(409, AuthErrors.REJOIN_PENDING, "")
        val id = queue(GroupOpType.RESET)
        exec.runDue()
        assertEquals(GroupOpType.FAILED, opsDao.rows[id]!!.state)
        assertEquals(AuthErrors.REJOIN_PENDING, opsDao.rows[id]!!.lastError)
        assertEquals(GroupOpType.RESET_REJOIN_PENDING_TEXT, groupOpErrorText(opsDao.rows[id]!!.lastError))
        exec.runDue() // the queued rejoin
        assertEquals(listOf("reset:1", "rejoin"), api.calls)
    }

    @Test fun theWaitClearsWhenTheWelcomeIsApplied() = runTest {
        reinstalledOnlyAdmin()
        queue(GroupOpType.REJOIN, auto)
        exec.runDue()
        assertTrue(conv in store.rejoinWaits.value)
        store.onGroupStateChanged(conv, removedSelf = false)
        assertNull(store.rejoinWaits.value[conv])
    }

    // ---- v1.21 §12.12.6: a cleanup op (`added: []`) named to this device ----

    @Test fun aCleanupOpRemovesMyOwnOldLeafWithoutAWelcome() = runTest {
        activeGroup()
        api.group = api.group.copy(myRole = GroupMember.ROLE_MEMBER, members = listOf(member(me), member(kamal, "admin")))
        mls.memberLists[conv]!!.add(DeviceRef(me, "dev-me-old"))
        val op = PendingOp("op-clean", PendingOp.DEVICES, me, added = emptyList(), removed = listOf(MlsDeviceRef(me, "dev-me-old")), committer = MlsDeviceRef(me, "dev-me"))
        api.group = api.group.copy(pending = listOf(op))
        store.queueCommit(conv, op)
        exec.runDue()
        val c = api.commits.single()
        assertEquals("op-clean", c.opId)
        assertTrue(c.added.isEmpty())
        assertEquals(listOf("dev-me-old"), c.removed.map { it.deviceId })
        assertNull(c.welcome)
        assertNull(c.welcomeRef)
        assertTrue(api.claims.isEmpty()) // nothing to add: nothing claimed
        assertFalse(DeviceRef(me, "dev-me-old") in mls.members(conv))
    }

    @Test fun anAdminCommitsAnotherUsersCleanupOp() = runTest {
        activeGroup() // I'm admin
        mls.memberLists[conv]!!.add(DeviceRef(kamal, "dev-kamal-old"))
        val op = PendingOp("op-clean", PendingOp.DEVICES, kamal, added = emptyList(), removed = listOf(MlsDeviceRef(kamal, "dev-kamal-old")), committer = MlsDeviceRef(me, "dev-me"))
        api.group = api.group.copy(pending = listOf(op))
        store.queueCommit(conv, op)
        exec.runDue()
        assertEquals(listOf("dev-kamal-old"), api.commits.single().removed.map { it.deviceId })
    }

    @Test fun aMemberNeverCommitsAnotherUsersCleanupOp() = runTest {
        activeGroup()
        api.group = api.group.copy(myRole = GroupMember.ROLE_MEMBER, members = listOf(member(me), member(kamal, "admin")))
        mls.memberLists[conv]!!.add(DeviceRef(kamal, "dev-kamal-old"))
        val op = PendingOp("op-clean", PendingOp.DEVICES, kamal, added = emptyList(), removed = listOf(MlsDeviceRef(kamal, "dev-kamal-old")), committer = MlsDeviceRef(me, "dev-me"))
        api.group = api.group.copy(pending = listOf(op))
        store.queueCommit(conv, op)
        exec.runDue()
        assertTrue(api.commits.isEmpty()) // H1: a member can't remove another user's different device
        assertTrue(opsDao.rows.values.single { it.opId == "op-clean" }.lastError!!.startsWith("policy"))
    }

    @Test fun aCleanupOpNeverRemovesThisDevice() = runTest {
        activeGroup()
        val op = PendingOp("op-clean", PendingOp.DEVICES, me, added = emptyList(), removed = listOf(MlsDeviceRef(me, "dev-me")), committer = MlsDeviceRef(me, "dev-me"))
        api.group = api.group.copy(pending = listOf(op))
        store.queueCommit(conv, op)
        exec.runDue()
        assertTrue(api.commits.isEmpty())
    }

    @Test fun anAutomaticRejoinSkipsItselfWhenTheWelcomeArrivedMeanwhile() = runTest {
        groupDao.upsert(GroupEntity(conv, null, "member", "active", null, null, 2, null, null, null, 1))
        api.group = api.group.copy(state = "active", generation = 2, epoch = 5, myRole = GroupMember.ROLE_MEMBER)
        // Still no state: the server is asked.
        val first = queue(GroupOpType.REJOIN, auto)
        exec.runDue()
        assertEquals(listOf("rejoin"), api.calls)
        assertEquals(GroupOpType.DONE, opsDao.rows[first]!!.state)
        // Joined from the Welcome at the current generation: done without a call.
        mls.groups[conv] = lk.codegen.risime.data.mls.GroupRef(conv, 2, 5)
        queue(GroupOpType.REJOIN, auto)
        queue(GroupOpType.RESET, auto)
        exec.runDue()
        assertEquals(listOf("rejoin"), api.calls)
        // An old generation still needs it; a rejoin for broken state (no if_missing) always calls.
        mls.groups[conv] = lk.codegen.risime.data.mls.GroupRef(conv, 1, 5)
        queue(GroupOpType.REJOIN, auto)
        exec.runDue()
        mls.groups[conv] = lk.codegen.risime.data.mls.GroupRef(conv, 2, 5)
        queue(GroupOpType.REJOIN)
        exec.runDue()
        assertEquals(listOf("rejoin", "rejoin", "rejoin"), api.calls)
    }

    /** Retries keep the first error (the cause) and reuse the op's claimed key packages; exhaustion reports first and last. */
    @Test fun retriesKeepTheFirstErrorAndReuseTheClaimedKeyPackages() = runTest {
        memberGroup()
        store.queueCommit(conv, namedDevicesOp("op-dev", listOf(MlsDeviceRef(nimal, "dev-nimal"))))
        api.commitReplies.add(ApiResult.Error(503, "unavailable", ""))
        api.commitReplies.add(ApiResult.NetworkError(IOException("down")))
        api.commitReplies.add(ApiResult.Error(502, "bad_gateway", ""))
        repeat(3) {
            exec.runDue()
            now = opsDao.rows.values.single().nextAt
        }
        val op = opsDao.rows.values.single()
        assertEquals(GroupOpType.FAILED, op.state)
        assertEquals("unavailable (last: bad_gateway)", op.lastError)
        assertEquals(1, api.claims.size) // one claim for three builds
        assertEquals(3, api.commits.size)
        assertFalse(mls.hasPending)
    }
}
