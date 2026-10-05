package lk.codegen.risime.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lk.codegen.risime.net.Presence
import lk.codegen.risime.net.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PresenceTest {
    private fun typing(from: String, on: Boolean) = Signal(
        "typing",
        JsonObject(mapOf("from" to JsonPrimitive(from), "conversation_id" to JsonPrimitive("dm:a_b"), "typing" to JsonPrimitive(on))),
    )

    private fun presence(id: String, online: Boolean, lastSeen: String? = null) = Signal(
        "presence",
        JsonObject(mapOf("user_id" to JsonPrimitive(id), "online" to JsonPrimitive(online), "last_seen" to JsonPrimitive(lastSeen))),
    )

    @Test fun snapshotReplacesAndSignalsUpdate() = runTest {
        val t = PresenceTracker(backgroundScope)
        t.onPresenceSnapshot(listOf(Presence("U1", true), Presence("u2", false, "2026-10-06T08:10:00.000Z")))
        assertTrue(t.presence.value["u1"]!!.online)
        t.onSignal(presence("u1", false, "2026-10-06T09:00:00.000Z"))
        assertFalse(t.presence.value["u1"]!!.online)
        // A new snapshot is the whole truth: u2 missing now means unknown, not offline.
        t.onPresenceSnapshot(listOf(Presence("u1", true)))
        assertNull(t.presence.value["u2"])
        t.onSignal(Signal("mood", JsonObject(emptyMap())))
        assertEquals(1, t.presence.value.size)
        t.onDisconnected()
        assertTrue(t.presence.value.isEmpty())
    }

    @Test fun typingExpiresAfterSixSecondsWithoutRefresh() = runTest {
        val t = PresenceTracker(backgroundScope)
        t.onSignal(typing("u2", true))
        assertEquals(setOf("u2"), t.typing.value)
        advanceTimeBy(4_000)
        t.onSignal(typing("u2", true)) // refresh restarts the window
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(setOf("u2"), t.typing.value)
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(t.typing.value.isEmpty())
    }

    @Test fun typingEndsOnFalseMessageOfflineOrDisconnect() = runTest {
        val t = PresenceTracker(backgroundScope)
        t.onSignal(typing("u2", true))
        t.onSignal(typing("u2", false))
        assertTrue(t.typing.value.isEmpty())

        t.onSignal(typing("u2", true))
        t.onMessageFrom("U2")
        assertTrue(t.typing.value.isEmpty())

        t.onSignal(typing("u2", true))
        t.onSignal(presence("u2", false, "2026-10-06T09:00:00.000Z"))
        assertTrue(t.typing.value.isEmpty())

        t.onSignal(typing("u3", true))
        t.onDisconnected()
        assertTrue(t.typing.value.isEmpty())
        advanceTimeBy(10_000) // a stale expiry job must not resurrect anything
        assertTrue(t.typing.value.isEmpty())
    }

    @Test fun watchListPutsOpenChatFirstAndCaps() {
        val ids = (1..250).map { "u$it" }
        val w = watchList(ids, "OPEN")
        assertEquals(200, w.size)
        assertEquals("open", w.first())
        assertEquals(setOf("u1"), watchList(listOf("U1", "u1"), "u1"))
    }

    // ---- TypingSender ----

    private class Recorder(val scope: TestScope) {
        val sent = mutableListOf<Pair<Long, Boolean>>()
        val sender = TypingSender(scope.backgroundScope, { scope.testScheduler.currentTime }, { sent += scope.testScheduler.currentTime to it })
    }

    @Test fun trueIsThrottledToEveryThreeSeconds() = runTest {
        val r = Recorder(this)
        r.sender.onInput("h")
        advanceTimeBy(1_000); r.sender.onInput("he")
        advanceTimeBy(1_000); r.sender.onInput("hel")
        advanceTimeBy(1_000); r.sender.onInput("hell") // t=3000: refresh due
        advanceTimeBy(1_000); r.sender.onInput("hello")
        assertEquals(listOf(0L to true, 3_000L to true), r.sent)
    }

    @Test fun falseAfterThreeSecondsIdle() = runTest {
        val r = Recorder(this)
        r.sender.onInput("h")
        advanceTimeBy(2_000); r.sender.onInput("hi")
        advanceTimeBy(2_999); runCurrent()
        assertEquals(listOf(0L to true), r.sent)
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf(0L to true, 5_000L to false), r.sent)
        // Typing again starts fresh with an immediate true.
        r.sender.onInput("hi!")
        assertEquals(testScheduler.currentTime to true, r.sent.last())
    }

    @Test fun falseOnClearOrSendOnlyOnce() = runTest {
        val r = Recorder(this)
        r.sender.onInput("h")
        r.sender.onInput("")
        assertEquals(listOf(0L to true, 0L to false), r.sent)
        r.sender.stop() // already stopped: nothing more
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, r.sent.size)
        r.sender.onInput("x")
        r.sender.stop() // message sent
        assertEquals(listOf(true, false, true, false), r.sent.map { it.second })
    }
}
