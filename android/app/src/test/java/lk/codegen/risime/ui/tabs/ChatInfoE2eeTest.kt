package lk.codegen.risime.ui.tabs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import lk.codegen.risime.data.mls.E2EE_CHECK_TIMEOUT
import lk.codegen.risime.data.mls.E2eeState
import lk.codegen.risime.data.mls.FakeMlsEngine
import lk.codegen.risime.data.mls.GroupRef
import lk.codegen.risime.data.mls.MlsApi
import lk.codegen.risime.data.mls.MlsUpgrader
import lk.codegen.risime.data.mls.NOT_E2EE_PREFIX
import lk.codegen.risime.data.mls.e2eeStripText
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.KeyPackagesClaimReply
import lk.codegen.risime.net.MlsCommitReply
import lk.codegen.risime.net.MlsCommitRequest
import lk.codegen.risime.net.MlsGroup
import lk.codegen.risime.net.MlsMissing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nightly.39 real-phone report: a 1:1's chat info said "Checking encryption…" for ever. It read
 * the MLS group once, on the main thread, while its view model was built (the engine's Room call
 * throws there, so it read "not encrypted") and passed no reason. Now a read-only check off the
 * main thread, bounded, repeated until encrypted.
 */
class ChatInfoE2eeTest {
    private val conv = "dm:a_b"

    private class Api(var group: ApiResult<MlsGroup>) : MlsApi {
        var claims = 0
        var commits = 0
        override suspend fun group(conversationId: String) = group
        override suspend fun claim(userIds: List<String>): ApiResult<KeyPackagesClaimReply> { claims++; return ApiResult.Error(500, "x", "") }
        override suspend fun commit(conversationId: String, body: MlsCommitRequest): ApiResult<MlsCommitReply> { commits++; return ApiResult.Error(500, "x", "") }
    }

    @Test fun peekIsReadOnlyAndSaysWhy() = runTest {
        val mls = FakeMlsEngine("a", "d-a")
        val api = Api(ApiResult.Ok(MlsGroup(e2ee = false, ready = true)))
        val up = MlsUpgrader({ mls }, api)
        // Ready but not e2ee yet: "setting up", and nothing is claimed or committed from chat info.
        assertEquals(E2eeState.WaitingForWelcome, up.peek(conv))
        assertEquals(0, api.claims)
        assertEquals(0, api.commits)
        // Not ready: the real reason.
        val missing = listOf(MlsMissing("b", null, MlsMissing.LEGACY_APP))
        api.group = ApiResult.Ok(MlsGroup(e2ee = false, ready = false, missing = missing))
        assertEquals(E2eeState.NotReady(missing), up.peek(conv))
        // E2ee on the server but not on this phone: being set up here.
        api.group = ApiResult.Ok(MlsGroup(e2ee = true, epoch = 3))
        assertEquals(E2eeState.Repairing, up.peek(conv))
        // Offline / no MLS on the server.
        api.group = ApiResult.NetworkError(java.io.IOException("down"))
        assertEquals(E2eeState.Failed("network"), up.peek(conv))
        api.group = ApiResult.Error(503, "mls_unavailable", "")
        assertEquals(E2eeState.Unavailable, up.peek(conv))
        // This phone holds the group: encrypted, without asking the server.
        mls.groups[conv] = GroupRef(conv, 1, 4)
        api.group = ApiResult.NetworkError(java.io.IOException("down"))
        assertEquals(E2eeState.Encrypted(4), up.peek(conv))
        // No MLS core at all.
        assertEquals(E2eeState.Unavailable, MlsUpgrader({ null }, api).peek(conv))
        assertEquals(0, api.claims + api.commits)
    }

    @Test fun aCheckThatNeverAnswersEndsInAReasonNotASpinner() = runTest {
        val never = CompletableDeferred<E2eeState>()
        val checks = CoroutineScope(StandardTestDispatcher(testScheduler))
        val states = mutableListOf<E2eeState>()
        val job = backgroundCollect(checks, { never.await() }, states)
        advanceTimeBy(INFO_E2EE_TIMEOUT_MS + 1)
        assertEquals(listOf<E2eeState>(E2eeState.Failed(E2EE_CHECK_TIMEOUT)), states)
        val text = e2eeStripText(states.single(), nameOf = { "Kumu" })!!
        assertTrue(text, text.startsWith(NOT_E2EE_PREFIX))
        assertEquals("Not end-to-end encrypted yet: couldn't check with the server, retrying", text)
        job.cancel()
        checks.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test fun itRechecksUntilEncryptedThenStops() = runTest {
        val answers = ArrayDeque(listOf<E2eeState>(E2eeState.WaitingForWelcome, E2eeState.Repairing, E2eeState.Encrypted(1)))
        var calls = 0
        val out = infoE2eeStates(this, { calls++; answers.removeFirst() }, timeoutMs = 1_000, pollMs = 500).take(3).toList()
        assertEquals(listOf(E2eeState.WaitingForWelcome, E2eeState.Repairing, E2eeState.Encrypted(1)), out)
        assertEquals(3, calls)
        // Encrypted (or unavailable) ends the polling: the flow completes by itself.
        assertEquals(listOf<E2eeState>(E2eeState.Encrypted(7)), infoE2eeStates(this, { E2eeState.Encrypted(7) }).toList())
        assertEquals(listOf<E2eeState>(E2eeState.Unavailable), infoE2eeStates(this, { E2eeState.Unavailable }).toList())
    }

    @Test fun aFailingCheckIsAReasonToo() = runTest {
        val out = infoE2eeStates(this, { error("boom") }, pollMs = 10).take(1).toList()
        assertEquals(listOf<E2eeState>(E2eeState.Failed("error")), out)
        assertTrue(e2eeStripText(out.single(), nameOf = { "Kumu" })!!.startsWith(NOT_E2EE_PREFIX))
    }

    private fun TestScope.backgroundCollect(checks: CoroutineScope, peek: suspend () -> E2eeState, into: MutableList<E2eeState>) =
        backgroundScope.launch { infoE2eeStates(checks, peek).collect { into += it } }
}
