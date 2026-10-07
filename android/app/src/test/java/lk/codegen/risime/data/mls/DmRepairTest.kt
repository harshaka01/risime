package lk.codegen.risime.data.mls

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.ClaimedDevice
import lk.codegen.risime.net.DmRejoinReply
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.KeyPackagesClaimReply
import lk.codegen.risime.net.MlsCommitReply
import lk.codegen.risime.net.MlsCommitRequest
import lk.codegen.risime.net.MlsDeviceRef
import lk.codegen.risime.net.MlsDmOpEvent
import lk.codegen.risime.net.MlsGroup
import lk.codegen.risime.net.PendingOp
import lk.codegen.risime.net.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.16 (proposal 2026-10-07-dm-device-readd): DM self-heal on the client. */
class DmRepairTest {
    private val conv = "dm:a_b"
    private val me = MlsDeviceRef("a", "d-a")

    private class Api : MlsApi {
        val groups = ArrayDeque<MlsGroup>()
        var last: MlsGroup = MlsGroup(e2ee = true, generation = 1, epoch = 5, ready = true)
        var claim: ApiResult<KeyPackagesClaimReply> = ApiResult.Ok(
            KeyPackagesClaimReply(
                listOf(
                    ClaimedDevice("b", "d-b", true, "jws", "a3A="),
                    ClaimedDevice("a", "d-a-old", true, "jws", null), // superseded, no key package left
                    ClaimedDevice("a", "d-a2", true, "jws", "a3A="),
                ),
            ),
        )
        var commit: ApiResult<MlsCommitReply> = ApiResult.Ok(MlsCommitReply(1))
        var rejoinReply: ApiResult<DmRejoinReply> = ApiResult.Ok(DmRejoinReply(null, 1))
        var resetReply: ApiResult<Long> = ApiResult.Ok(2)
        val commits = mutableListOf<MlsCommitRequest>()
        val claims = mutableListOf<List<String>>()
        var rejoins = 0
        val resets = mutableListOf<Long>()
        override suspend fun group(conversationId: String): ApiResult<MlsGroup> {
            groups.removeFirstOrNull()?.let { last = it }
            return ApiResult.Ok(last)
        }
        override suspend fun claim(userIds: List<String>) = claim.also { claims += userIds }
        override suspend fun commit(conversationId: String, body: MlsCommitRequest) = commit.also { commits += body }
        override suspend fun rejoin(conversationId: String) = rejoinReply.also { rejoins++ }
        override suspend fun reset(conversationId: String, generation: Long) = resetReply.also { resets += generation }
    }

    private var now = 1_000_000L
    private fun upgrader(mls: FakeMlsEngine, api: Api) = MlsUpgrader({ mls }, api, clock = { now })

    @Test fun notInTheGroupAsksForReAddAndShowsSettingUp() = runTest {
        val mls = FakeMlsEngine("a", "d-a")
        val api = Api()
        val u = upgrader(mls, api)
        assertEquals(E2eeState.Repairing, u.ensure(conv, "a", "b", verify = true))
        assertEquals(1, api.rejoins)
        // Throttled: no second request within 20 s.
        now += 5_000
        assertEquals(E2eeState.Repairing, u.ensure(conv, "a", "b", verify = true))
        assertEquals(1, api.rejoins)
        now += 20_000
        u.ensure(conv, "a", "b", verify = true)
        assertEquals(2, api.rejoins)
        assertTrue(api.resets.isEmpty())
        assertEquals("Setting up encryption on this phone…", e2eeStripText(E2eeState.Repairing, { it }))
    }

    @Test fun afterTwoMinutesItResetsAndRebuildsSkippingDevicesWithoutKeyPackages() = runTest {
        val mls = FakeMlsEngine("a", "d-a")
        val api = Api()
        val u = upgrader(mls, api)
        assertEquals(E2eeState.Repairing, u.ensure(conv, "a", "b", verify = true))
        now += MlsUpgrader.RESET_AFTER_MS
        api.groups += MlsGroup(e2ee = true, generation = 1, epoch = 5, ready = true)
        api.groups += MlsGroup(e2ee = true, generation = 2, epoch = null, ready = true)
        val s = u.ensure(conv, "a", "b", verify = true)
        assertEquals(listOf(1L), api.resets)
        assertEquals(E2eeState.Encrypted(1), s)
        val c = api.commits.single()
        assertEquals(2L, c.generation)
        assertEquals(0L, c.epoch)
        assertEquals(setOf("d-b", "d-a2"), c.added.map { it.deviceId }.toSet()) // d-a-old had no key package
        assertEquals(2L, mls.group(conv)!!.generation)
    }

    @Test fun noCandidateResetsAtOnce() = runTest {
        val mls = FakeMlsEngine("a", "d-a")
        val api = Api().apply { rejoinReply = ApiResult.Ok(DmRejoinReply(op(), 0)) }
        api.groups += MlsGroup(e2ee = true, generation = 3, epoch = 9, ready = true)
        api.groups += MlsGroup(e2ee = true, generation = 4, epoch = null, ready = true)
        assertEquals(E2eeState.Encrypted(1), upgrader(mls, api).ensure(conv, "a", "b", verify = true))
        assertEquals(listOf(3L), api.resets)
        assertEquals(4L, api.commits.single().generation)
    }

    @Test fun generationConflictMeansSomeoneElseResetAndTheRaceLoserWaits() = runTest {
        val mls = FakeMlsEngine("a", "d-a")
        val api = Api().apply {
            rejoinReply = ApiResult.Ok(DmRejoinReply(op(), 0))
            resetReply = ApiResult.Error(409, "generation_conflict", "")
            commit = ApiResult.Error(409, "epoch_conflict", "", epoch = 1)
        }
        api.groups += MlsGroup(e2ee = true, generation = 1, epoch = 4, ready = true)
        api.groups += MlsGroup(e2ee = true, generation = 2, epoch = null, ready = true)
        // The peer reset first (conflict) and also won the rebuild: we wait for its Welcome.
        assertEquals(E2eeState.Repairing, upgrader(mls, api).ensure(conv, "a", "b", verify = true))
        assertEquals(1, api.commits.size)
        assertNull(mls.group(conv))
    }

    @Test fun awaitingRebuildIsRebuiltByWhoeverChecks() = runTest {
        val mls = FakeMlsEngine("a", "d-a")
        mls.groups[conv] = GroupRef(conv, 1, 7) // the old generation's group
        val api = Api()
        api.groups += MlsGroup(e2ee = true, generation = 2, epoch = null, ready = true)
        assertEquals(E2eeState.Encrypted(1), upgrader(mls, api).ensure(conv, "a", "b", verify = true))
        assertEquals(0, api.rejoins)
        assertEquals(GroupRef(conv, 2, 1), mls.group(conv))
    }

    @Test fun inDevicesWithoutLocalGroupWaitsThenRejoins() = runTest {
        val mls = FakeMlsEngine("a", "d-a")
        val api = Api()
        api.last = MlsGroup(e2ee = true, generation = 1, epoch = 5, ready = true, devices = listOf(me))
        val u = upgrader(mls, api)
        assertEquals(E2eeState.WaitingForWelcome, u.ensure(conv, "a", "b", verify = true))
        assertEquals(0, api.rejoins)
        now += MlsUpgrader.WELCOME_WAIT_MS
        assertEquals(E2eeState.Repairing, u.ensure(conv, "a", "b", verify = true))
        assertEquals(1, api.rejoins)
    }

    @Test fun healthyGroupIsEncryptedAndFastPathSkipsTheServer() = runTest {
        val mls = FakeMlsEngine("a", "d-a")
        mls.groups[conv] = GroupRef(conv, 1, 5)
        val api = Api()
        api.last = MlsGroup(e2ee = true, generation = 1, epoch = 5, ready = true, devices = listOf(me))
        assertEquals(E2eeState.Encrypted(5), upgrader(mls, api).ensure(conv, "a", "b", verify = true))
        assertEquals(0, api.rejoins)
        // Local group of an older generation while the server moved on and lists us: wait for the Welcome.
        api.last = MlsGroup(e2ee = true, generation = 2, epoch = 1, ready = true, devices = listOf(me))
        assertEquals(E2eeState.WaitingForWelcome, upgrader(mls, api).ensure(conv, "a", "b", verify = true))
        // Without verify the local group answers.
        assertEquals(E2eeState.Encrypted(5), upgrader(mls, Api()).ensure(conv, "a", "b"))
    }

    // ---- the named committer (MembershipExecutor.executeOp) ----

    private fun op(
        added: List<MlsDeviceRef> = listOf(MlsDeviceRef("a", "d-a-new")),
        removed: List<MlsDeviceRef> = listOf(MlsDeviceRef("a", "d-a-old")),
        committer: MlsDeviceRef? = MlsDeviceRef("b", "d-b"),
    ) = PendingOp("op-1", "devices", "a", added = added, removed = removed, committer = committer)

    private class ExecApi(val mls: FakeMlsEngine) : MlsApi {
        val commits = mutableListOf<MlsCommitRequest>()
        val replies = ArrayDeque<ApiResult<MlsCommitReply>>()
        override suspend fun group(conversationId: String): ApiResult<MlsGroup> = error("unused")
        override suspend fun claim(userIds: List<String>) = ApiResult.Ok(
            KeyPackagesClaimReply(listOf(ClaimedDevice("a", "d-a-new", true, "jws", "a3A="), ClaimedDevice("a", "d-a-old", true, "jws", "a3A="))),
        )
        override suspend fun commit(conversationId: String, body: MlsCommitRequest): ApiResult<MlsCommitReply> {
            commits += body
            return replies.removeFirstOrNull() ?: ApiResult.Ok(MlsCommitReply(body.epoch + 1))
        }
    }

    private fun peerWithGroup(): FakeMlsEngine = FakeMlsEngine("b", "d-b").apply {
        groups[conv] = GroupRef(conv, 1, 5)
        memberLists[conv] = mutableListOf(DeviceRef("b", "d-b"), DeviceRef("a", "d-a-old"))
    }

    @Test fun executeOpAddsFirstThenDropsTheSupersededLeafEachWithOpId() = runTest {
        val mls = peerWithGroup()
        val api = ExecApi(mls)
        val r = MembershipExecutor({ mls }, api) {}.executeOp(MlsDmOpEvent(conv, 1, op()))
        assertEquals(MembershipOutcome.Done, r)
        assertEquals(2, api.commits.size)
        assertEquals(listOf("d-a-new"), api.commits[0].added.map { it.deviceId })
        assertTrue(api.commits[0].removed.isEmpty() && api.commits[0].welcome != null)
        assertEquals(listOf("d-a-old"), api.commits[1].removed.map { it.deviceId })
        assertTrue(api.commits[1].added.isEmpty())
        assertTrue(api.commits.all { it.opId == "op-1" })
        assertEquals(setOf(DeviceRef("b", "d-b"), DeviceRef("a", "d-a-new")), mls.members(conv).toSet())
    }

    @Test fun executeOpReAddsALostDeviceRemovalFirst() = runTest {
        val mls = peerWithGroup()
        val api = ExecApi(mls)
        val same = MlsDeviceRef("a", "d-a-old")
        val r = MembershipExecutor({ mls }, api) {}.executeOp(MlsDmOpEvent(conv, 1, op(added = listOf(same), removed = listOf(same))))
        assertEquals(MembershipOutcome.Done, r)
        assertEquals(listOf("removed:d-a-old", "added:d-a-old"), api.commits.map {
            if (it.added.isEmpty()) "removed:${it.removed.single().deviceId}" else "added:${it.added.single().deviceId}"
        })
    }

    @Test fun executeOpOnlyForTheNamedDeviceAndTheCurrentGeneration() = runTest {
        val mls = peerWithGroup()
        val api = ExecApi(mls)
        val ex = MembershipExecutor({ mls }, api) {}
        assertEquals(MembershipOutcome.NotNeeded, ex.executeOp(MlsDmOpEvent(conv, 1, op(committer = MlsDeviceRef("b", "d-b2")))))
        assertEquals(MembershipOutcome.NotNeeded, ex.executeOp(MlsDmOpEvent(conv, 2, op())))
        assertTrue(api.commits.isEmpty())
    }

    @Test fun executeOpCatchesUpOnEpochConflictAndReDerives() = runTest {
        val mls = peerWithGroup()
        val api = ExecApi(mls)
        api.replies += ApiResult.Error(409, "epoch_conflict", "", epoch = 6)
        var caughtUp = 0
        // The catch-up shows the add already landed (someone else's commit): only the removal is left.
        val r = MembershipExecutor({ mls }, api) {
            caughtUp++
            mls.memberLists[conv]!!.add(DeviceRef("a", "d-a-new"))
            mls.groups[conv] = GroupRef(conv, 1, 6)
        }.executeOp(MlsDmOpEvent(conv, 1, op()))
        assertEquals(MembershipOutcome.Done, r)
        assertEquals(1, caughtUp)
        assertEquals(2, api.commits.size)
        assertEquals(listOf("d-a-old"), api.commits[1].removed.map { it.deviceId })
    }

    @Test fun dmOpEventParsesAndReachesTheCallbackOnlyWhenNamed() = runTest {
        val json = """{"conversation_id":"$conv","generation":1,"op":{"op_id":"op-1","type":"devices","actor":"a","user_ids":[],"role":null,
            "added":[{"user_id":"a","device_id":"d-a-new"}],"removed":[{"user_id":"a","device_id":"d-a-old"}],
            "committer":{"user_id":"b","device_id":"d-b"},"committer_until":"2026-10-07T10:00:00Z","expires_at":null,"created_at":"2026-10-07T09:59:00Z"}}"""
        val e = Event("e1", Event.KIND_MLS_DM_OP, ProtocolJson.parseToJsonElement(json) as JsonObject)
        val parsed = e.mlsDmOp()!!
        assertEquals("op-1", parsed.op.opId)
        assertEquals(MlsDeviceRef("b", "d-b"), parsed.op.committer)
        val seen = mutableListOf<MlsDmOpEvent>()
        val named = MlsPipeline({ peerWithGroup() }, FakeMlsPendingDao(), onDmOp = { seen += it })
        assertEquals(MlsResult.Ignored, named.apply(e))
        assertEquals(1, seen.size)
        val other = MlsPipeline({ FakeMlsEngine("b", "d-b2") }, FakeMlsPendingDao(), onDmOp = { seen += it })
        assertEquals(MlsResult.Ignored, other.apply(e))
        assertEquals(1, seen.size)
    }

    @Test fun commitRequestCarriesOpId() {
        val s = ProtocolJson.encodeToString(MlsCommitRequest.serializer(), MlsCommitRequest(1, 2, "Yw==", opId = "op-1"))
        assertTrue(s, s.contains("\"op_id\":\"op-1\""))
    }
}
