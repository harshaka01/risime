package lk.codegen.risime.crypto

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import lk.codegen.risime.data.FakeGroupDao
import lk.codegen.risime.data.FakeGroupOpDao
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.groups.GroupApi
import lk.codegen.risime.data.groups.GroupOpType
import lk.codegen.risime.data.groups.GroupOpsExecutor
import lk.codegen.risime.data.groups.GroupStore
import lk.codegen.risime.data.mls.DeviceRef
import lk.codegen.risime.data.mls.MembershipExecutor
import lk.codegen.risime.data.mls.MembershipOutcome
import lk.codegen.risime.data.mls.MlsApi
import lk.codegen.risime.data.mls.MlsCommitGate
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.BlobRef
import lk.codegen.risime.net.ClaimedDevice
import lk.codegen.risime.net.Group
import lk.codegen.risime.net.GroupCommitRequest
import lk.codegen.risime.net.GroupMember
import lk.codegen.risime.net.GroupMeta
import lk.codegen.risime.net.GroupRejoinReply
import lk.codegen.risime.net.KeyPackagesClaimReply
import lk.codegen.risime.net.MlsCommitReply
import lk.codegen.risime.net.MlsCommitRequest
import lk.codegen.risime.net.MlsDeviceRef
import lk.codegen.risime.net.MlsDmOpEvent
import lk.codegen.risime.net.MlsGroup
import lk.codegen.risime.net.PendingOp
import lk.codegen.risime.net.dmConversationId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.Collections

/**
 * Regression for the nightly.31 reinstall-gate failure: a non-admin member C is named for both the
 * DM re-add and the group re-add of a reinstalled user A's new device at the same time. The first
 * group attempt failed between building its commit and the server accepting it (an exception, or
 * the outbox runner cancelling the attempt), which left a staged commit in C's MLS state; every
 * later attempt claimed (burned) another of A's key packages and failed with "a commit is already
 * pending for this group". REAL core (host build via JNA) on every device.
 */
class ConcurrentReAddTest {
    private val aU = "aaaa0000-0000-4000-8000-0000000000a1"
    private val cU = "cccc0000-0000-4000-8000-0000000000c1"
    private val grp = "grp:6b7c8d9e-0f1a-4b2c-8d3e-4f5a6b7c8d9e"
    private val dm = dmConversationId(aU, cU)
    private val b64 = Base64.getEncoder()
    private lateinit var aOld: RealMls.Device
    private lateinit var aNew: RealMls.Device
    private lateinit var c: RealMls.Device

    @Before fun setUp() {
        RealMls.assumeHostLibrary()
        aOld = RealMls.device(aU, "a-old")
        c = RealMls.device(cU, "c-phone")
        // A (the only admin) created the group with C, and the DM; C joined both.
        val g = aOld.transaction { aOld.engine.createGroupWithMeta(grp, 1, listOf(c.keyPackage()), GroupMeta(name = "Upgrade group", admins = listOf(aU))) }
        aOld.transaction { aOld.engine.commitAccepted(grp) }
        c.transaction { c.engine.joinFromWelcome(grp, 1, g.welcome!!) }
        val d = aOld.engine.createGroup(dm, 1, listOf(c.keyPackage()))
        aOld.engine.commitAccepted(dm)
        c.engine.joinFromWelcome(dm, 1, d.welcome!!)
        // A reinstalls: a new device (new MLS state); the old one is gone.
        aNew = RealMls.device(aU, "a-new")
    }

    @After fun tearDown() {
        if (::c.isInitialized) listOf(aOld, aNew, c).forEach { it.close() }
    }

    private val groupOp = PendingOp(
        "op-grp", PendingOp.DEVICES, aU, added = listOf(MlsDeviceRef(aU, "a-new")),
        committer = MlsDeviceRef(cU, "c-phone"),
    )
    private val dmOp = PendingOp(
        "op-dm", PendingOp.DEVICES, aU, added = listOf(MlsDeviceRef(aU, "a-new")), removed = listOf(MlsDeviceRef(aU, "a-old")),
        committer = MlsDeviceRef(cU, "c-phone"),
    )

    /** One of A's new device's key packages per claim, like the server (each claim consumes one). */
    private val claimedKps = Collections.synchronizedList(mutableListOf<String>())
    private fun claimANew(): ClaimedDevice {
        val kp = b64.encodeToString(aNew.engine.createKeyPackages(1).single())
        claimedKps += kp
        return ClaimedDevice(aU, "a-new", true, "jws", kp)
    }

    /** The scripted server for C's group outbox: the group with its devices op naming C. */
    private inner class GroupServer(val firstCommit: suspend () -> Unit) : GroupApi {
        val commits = Collections.synchronizedList(mutableListOf<GroupCommitRequest>())
        var commitCalls = 0
        val claims = Collections.synchronizedList(mutableListOf<List<String>>())
        var group = Group(
            grp, Group.STATE_ACTIVE, aU, null, 1, 1, GroupMember.ROLE_MEMBER,
            listOf(GroupMember(aU, "A", role = GroupMember.ROLE_ADMIN), GroupMember(cU, "C")), listOf(groupOp),
        )

        override suspend fun create(clientGroupId: String, memberIds: List<String>) = error("unused")
        override suspend fun group(id: String): ApiResult<Group> = ApiResult.Ok(group)
        override suspend fun addMembers(id: String, userIds: List<String>) = error("unused")
        override suspend fun removeMember(id: String, userId: String) = error("unused")
        override suspend fun leave(id: String) = error("unused")
        override suspend fun setRole(id: String, userId: String, role: String) = error("unused")
        override suspend fun rejoin(id: String): ApiResult<GroupRejoinReply> = error("unused")
        override suspend fun reset(id: String, generation: Long) = error("unused")
        override suspend fun claim(userIds: List<String>, conversationId: String?): ApiResult<List<ClaimedDevice>> {
            claims += userIds
            return ApiResult.Ok(listOf(claimANew()))
        }
        override suspend fun commit(id: String, body: GroupCommitRequest): ApiResult<Long> {
            if (++commitCalls == 1) firstCommit() // the injected failure between build and submit
            commits += body
            group = group.copy(epoch = body.epoch + 1, pending = emptyList())
            return ApiResult.Ok(body.epoch + 1)
        }
        override suspend fun uploadBlob(conversationId: String, bytes: ByteArray): ApiResult<BlobRef> = error("unused")
    }

    /** The scripted server for C's DM op (§10.6): every commit is accepted. */
    private inner class DmServer(val onCommit: suspend () -> Unit = {}) : MlsApi {
        val commits = Collections.synchronizedList(mutableListOf<MlsCommitRequest>())
        override suspend fun group(conversationId: String): ApiResult<MlsGroup> = error("unused")
        override suspend fun claim(userIds: List<String>) = ApiResult.Ok(KeyPackagesClaimReply(listOf(claimANew())))
        override suspend fun commit(conversationId: String, body: MlsCommitRequest): ApiResult<MlsCommitReply> {
            onCommit()
            commits += body
            return ApiResult.Ok(MlsCommitReply(body.epoch + 1))
        }
    }

    private var now = 1_000L
    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private val gate = MlsCommitGate { logs += it }
    private val ops = FakeGroupOpDao()
    private val groups = FakeGroupDao()
    private val store = GroupStore(groups, ops, FakeMessageDao(), { "c-phone" }, { c.engine.groupMeta(it) }, { now })

    private fun executor(api: GroupApi) = GroupOpsExecutor(
        { c.engine }, api, ops, groups, store,
        // C's outer transaction (Room's in the app) around each engine call.
        object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
        me = { cU }, deviceId = { "c-phone" }, catchUp = {}, clock = { now }, maxAttempts = 8,
        log = { logs += it }, gate = gate,
    )

    private fun membership(api: MlsApi) = MembershipExecutor({ c.engine }, api, gate) { }

    private fun assertReAdded(server: GroupServer) {
        // Exactly one group commit landed, adding A's new device with a Welcome it can join from.
        val commit = server.commits.single()
        assertEquals("op-grp", commit.opId)
        assertEquals(listOf(MlsDeviceRef(aU, "a-new")), commit.added)
        assertEquals(2L, c.engine.group(grp)!!.epoch)
        assertTrue(DeviceRef(aU, "a-new") in c.engine.members(grp))
        aNew.transaction { aNew.engine.joinFromWelcome(grp, 1, Base64.getDecoder().decode(commit.welcome!!)) }
        assertEquals(2L, aNew.engine.group(grp)!!.epoch)
        // The retry reused the key package it had claimed: the target's packages aren't burned.
        assertEquals(1, server.claims.size)
        assertEquals(GroupOpType.DONE, ops.rows.values.single().state)
        assertFalse(c.engine.hasPendingCommit(grp))
        // C can send in the group again (a staged commit blocks encryption).
        val ct = c.engine.encrypt(grp, "after".toByteArray())
        assertEquals("after", aNew.engine.decrypt(grp, 1, ct).plaintext.decodeToString())
    }

    private fun assertDmDone(dmServer: DmServer) {
        assertEquals(2, dmServer.commits.size) // (b) add the new device, (c) remove the old leaf
        val leaves = c.engine.members(dm)
        assertTrue(DeviceRef(aU, "a-new") in leaves)
        assertFalse(DeviceRef(aU, "a-old") in leaves)
        assertFalse(c.engine.hasPendingCommit(dm))
    }

    @Test fun groupReAddFailsBetweenBuildAndSubmitWhileTheDmReAddRuns() = runBlocking {
        val groupBuilt = CompletableDeferred<Unit>()
        val dmDone = CompletableDeferred<Unit>()
        val server = GroupServer {
            groupBuilt.complete(Unit)
            dmDone.await()
            throw IOException("injected: connection reset before the server read the commit")
        }
        val dmServer = DmServer { groupBuilt.await() } // the DM commits go out while the group commit is staged
        val exec = executor(server)
        store.queueCommit(grp, groupOp)
        withTimeout(60_000) {
            val group = async(Dispatchers.Default) { exec.runDue() }
            val dmRun = async(Dispatchers.Default) { membership(dmServer).executeOp(MlsDmOpEvent(dm, 1, dmOp)).also { dmDone.complete(Unit) } }
            assertEquals(MembershipOutcome.Done, dmRun.await())
            group.await()
        }
        val op = ops.rows.values.single()
        assertEquals(GroupOpType.QUEUED, op.state)
        assertTrue(op.lastError!!, op.lastError!!.contains("IOException"))
        // The failed attempt left nothing staged.
        assertFalse(c.engine.hasPendingCommit(grp))
        assertDmDone(dmServer)

        now = op.nextAt
        assertNull(exec.runDue())
        assertReAdded(server)
        // Every attempt was logged.
        assertTrue(logs.toString(), logs.any { it.contains("done on attempt 2") })
        assertTrue(logs.toString(), logs.any { it.contains("attempt 1") && it.contains("IOException") })
    }

    @Test fun groupReAddCancelledBetweenBuildAndSubmitIsBuiltAfresh() = runBlocking {
        val submitting = CompletableDeferred<Unit>()
        val server = GroupServer {
            submitting.complete(Unit)
            awaitCancellation() // the outbox runner's collectLatest cancels the attempt here
        }
        val dmServer = DmServer()
        val exec = executor(server)
        store.queueCommit(grp, groupOp)
        withTimeout(60_000) {
            val run = launch(Dispatchers.Default) { exec.runDue() }
            submitting.await()
            val dmRun = async(Dispatchers.Default) { membership(dmServer).executeOp(MlsDmOpEvent(dm, 1, dmOp)) }
            run.cancel()
            run.join()
            assertEquals(MembershipOutcome.Done, dmRun.await())
        }
        assertFalse("a cancelled attempt leaves nothing staged", c.engine.hasPendingCommit(grp))
        assertDmDone(dmServer)

        now = ops.rows.values.single().nextAt
        assertNull(exec.runDue())
        assertReAdded(server)
    }

    @Test fun startUpClearsAStaleStagedCommit() = runBlocking {
        // An older build left a staged group commit that was never submitted (process died).
        c.transaction { c.engine.changeGroupMembers(grp, listOf(aNew.keyPackage()), emptyList()) }
        assertTrue(c.engine.hasPendingCommit(grp))
        assertEquals(listOf(grp), gate.sweep(c.engine, listOf(grp, dm, "grp:unknown")))
        assertFalse(c.engine.hasPendingCommit(grp))
        assertEquals(1L, c.engine.group(grp)!!.epoch)
        // The op builds and commits normally now.
        val server = GroupServer {}
        store.queueCommit(grp, groupOp)
        assertNull(executor(server).runDue())
        assertReAdded(server)
    }
}
