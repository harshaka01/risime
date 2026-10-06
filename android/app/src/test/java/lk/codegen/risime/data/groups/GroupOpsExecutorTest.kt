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
import lk.codegen.risime.net.BlobRef
import lk.codegen.risime.net.ClaimedDevice
import lk.codegen.risime.net.Group
import lk.codegen.risime.net.GroupCommitRequest
import lk.codegen.risime.net.GroupMember
import lk.codegen.risime.net.GroupMeta
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
        override suspend fun rejoin(id: String): ApiResult<Group> { calls += "rejoin"; return ApiResult.Ok(group) }
        override suspend fun reset(id: String, generation: Long): ApiResult<Long> { calls += "reset:$generation"; return ApiResult.Ok(generation + 1) }
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

    @Test fun rejoinAndResetCallTheServer() = runTest {
        groupDao.upsert(GroupEntity(conv, "x", "admin", "active", null, null, 3, null, null, null, 1))
        queue(GroupOpType.REJOIN)
        queue(GroupOpType.RESET)
        exec.runDue()
        assertEquals(listOf("rejoin", "reset:3"), api.calls)
    }
}
