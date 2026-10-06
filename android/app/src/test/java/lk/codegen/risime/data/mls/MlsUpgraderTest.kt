package lk.codegen.risime.data.mls

import kotlinx.coroutines.test.runTest
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.ClaimedDevice
import lk.codegen.risime.net.KeyPackagesClaimReply
import lk.codegen.risime.net.MlsCommitReply
import lk.codegen.risime.net.MlsCommitRequest
import lk.codegen.risime.net.MlsGroup
import lk.codegen.risime.net.MlsMissing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MlsUpgraderTest {
    private val conv = "dm:a_b"
    private val mls = FakeMlsEngine("a", "d-a")

    private class Api : MlsApi {
        var group: ApiResult<MlsGroup> = ApiResult.Ok(MlsGroup(e2ee = false, generation = 1, ready = true))
        var claim: ApiResult<KeyPackagesClaimReply> = ApiResult.Ok(
            KeyPackagesClaimReply(listOf(ClaimedDevice("b", "d-b", true, "jws", "a3A="), ClaimedDevice("a", "d-a2", true, "jws", "a3A="))),
        )
        var commit: ApiResult<MlsCommitReply> = ApiResult.Ok(MlsCommitReply(1))
        val commits = mutableListOf<MlsCommitRequest>()
        val claims = mutableListOf<List<String>>()
        override suspend fun group(conversationId: String) = group
        override suspend fun claim(userIds: List<String>) = claim.also { claims += userIds }
        override suspend fun commit(conversationId: String, body: MlsCommitRequest) = commit.also { commits += body }
    }

    @Test fun readyConversationIsCreatedAndMergedOnlyAfterThe200() = runTest {
        val api = Api()
        val s = MlsUpgrader({ mls }, api).ensure(conv, "a", "b")
        assertEquals(E2eeState.Encrypted(1), s)
        assertEquals(listOf(listOf("b", "a")), api.claims) // peer and my own other devices
        val c = api.commits.single()
        assertEquals(0L, c.epoch)
        assertEquals(setOf("d-b", "d-a2"), c.added.map { it.deviceId }.toSet())
        assertTrue(c.welcome != null)
        assertEquals(1L, mls.group(conv)!!.epoch)
        // Already e2ee locally → no more calls.
        assertEquals(E2eeState.Encrypted(1), MlsUpgrader({ mls }, api).ensure(conv, "a", "b"))
        assertEquals(1, api.commits.size)
    }

    @Test fun lostRaceNotReadyAndUnavailable() = runTest {
        val api = Api().apply { commit = ApiResult.Error(409, "epoch_conflict", "", epoch = 1) }
        assertEquals(E2eeState.WaitingForWelcome, MlsUpgrader({ mls }, api).ensure(conv, "a", "b"))
        assertNull(mls.group(conv)) // local group discarded, nothing merged
        assertFalse(mls.hasPending)

        val missing = listOf(MlsMissing("b", null, MlsMissing.LEGACY_APP))
        val notReady = Api().apply { group = ApiResult.Ok(MlsGroup(false, 1, null, ready = false, missing = missing)) }
        assertEquals(E2eeState.NotReady(missing), MlsUpgrader({ mls }, notReady).ensure(conv, "a", "b"))
        assertTrue(notReady.claims.isEmpty())

        val raced = Api().apply { commit = ApiResult.Error(409, "not_ready", "", missing = missing) }
        assertEquals(E2eeState.NotReady(missing), MlsUpgrader({ mls }, raced).ensure(conv, "a", "b"))
        assertFalse(mls.hasPending)

        val noKp = Api().apply { claim = ApiResult.Ok(KeyPackagesClaimReply(listOf(ClaimedDevice("b", "d-b", true, "jws", null)))) }
        assertTrue(MlsUpgrader({ mls }, noKp).ensure(conv, "a", "b") is E2eeState.NotReady)
        assertTrue(noKp.commits.isEmpty())

        assertEquals(E2eeState.Unavailable, MlsUpgrader({ null }, Api()).ensure(conv, "a", "b"))
        val off = Api().apply { group = ApiResult.Error(503, "mls_unavailable", "") }
        assertEquals(E2eeState.Unavailable, MlsUpgrader({ mls }, off).ensure(conv, "a", "b"))
        val serverSide = Api().apply { group = ApiResult.Ok(MlsGroup(true, 1, 3, ready = true)) }
        assertEquals(E2eeState.WaitingForWelcome, MlsUpgrader({ mls }, serverSide).ensure(conv, "a", "b"))
    }

    @Test fun stripTexts() {
        val name = { id: String -> if (id == "b") "Kamal" else id }
        assertEquals("Not end-to-end encrypted yet: Kamal needs to update",
            e2eeStripText(E2eeState.NotReady(listOf(MlsMissing("b", null, MlsMissing.LEGACY_APP))), name))
        assertEquals("Not end-to-end encrypted yet: Kamal needs to update",
            e2eeStripText(E2eeState.NotReady(listOf(MlsMissing("b", "d", MlsMissing.NO_MLS))), name))
        assertEquals("Not end-to-end encrypted yet: waiting for Kamal's phone",
            e2eeStripText(E2eeState.NotReady(listOf(MlsMissing("b", "d", "no_key_package"))), name))
        assertNull(e2eeStripText(E2eeState.Encrypted(3), name))
        assertNull(e2eeStripText(E2eeState.Unavailable, name))
    }
}
