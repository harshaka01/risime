package lk.codegen.risime.data.deletes

import kotlinx.coroutines.runBlocking
import lk.codegen.risime.data.mls.MlsPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §15.3 envelope and AAD, §15.4 receiver rule, §15.7 eligibility and the server-clock offset. */
class DeleteRulesTest {
    private val a = "c1a2b3e1-a0b1-11f0-8000-0242ac120002"
    private val b = "c1a2b3f0-a0b1-11f0-8000-0242ac120002"

    @Test fun envelopeRoundTripsAndIsStrict() {
        val d = MlsPayload.decode(MlsPayload.delete(listOf(a, b))) as MlsPayload.Decoded.Delete
        assertEquals(listOf(a, b), d.targets)
        fun bad(json: String) = assertTrue(json, MlsPayload.decode(json.toByteArray()) is MlsPayload.Decoded.Ignored)
        bad("""{"v":1,"type":"delete","targets":[]}""")
        bad("""{"v":2,"type":"delete","targets":["$a"]}""")
        bad("""{"v":"1","type":"delete","targets":["$a"]}""")
        bad("""{"v":1,"type":"delete","targets":["$a","$a"]}""")
        bad("""{"v":1,"type":"delete","targets":["not-a-uuid"]}""")
        bad("""{"v":1,"type":"delete","targets":["${java.util.UUID.randomUUID()}"]}""") // v4, not a TimeUUID
        bad("""{"v":1,"type":"delete","targets":[1]}""")
        bad("""{"v":1,"type":"delete"}""")
        // Unknown fields are ignored.
        assertTrue(MlsPayload.decode("""{"v":1,"type":"delete","targets":["$a"],"x":1}""".toByteArray()) is MlsPayload.Decoded.Delete)
    }

    @Test fun aadIsCanonical() {
        val enc = DeleteAad.encode(listOf(b, a))
        assertEquals(2 + 32, enc.size)
        assertEquals(0x01.toByte(), enc[0])
        assertEquals(0x44.toByte(), enc[1])
        assertArrayEquals(enc, DeleteAad.encode(listOf(a, b))) // order-independent
        assertEquals(listOf(a, b), DeleteAad.decode(enc))
        // Upper case input decodes to lowercase.
        assertEquals(listOf(a, b), DeleteAad.decode(DeleteAad.encode(listOf(a.uppercase(), b))))
        // Non-canonical: unsorted, duplicate, wrong tag, truncated, empty.
        val swapped = enc.copyOf().also { System.arraycopy(enc, 18, it, 2, 16); System.arraycopy(enc, 2, it, 18, 16) }
        assertNull(DeleteAad.decode(swapped))
        assertNull(DeleteAad.decode(byteArrayOf(1, 0x44) + enc.copyOfRange(2, 18) + enc.copyOfRange(2, 18)))
        assertNull(DeleteAad.decode(byteArrayOf(1, 0x45) + enc.copyOfRange(2, 34)))
        assertNull(DeleteAad.decode(enc.copyOfRange(0, 33)))
        assertNull(DeleteAad.decode(byteArrayOf(1, 0x44)))
        assertNull(DeleteAad.decode(ByteArray(0)))
        runCatching { DeleteAad.encode(emptyList()) }.onSuccess { throw AssertionError("0 targets") }
        runCatching { DeleteAad.encode(listOf(a, a)) }.onSuccess { throw AssertionError("duplicates") }
    }

    @Test fun receiverRuleComparesUserIdsAndTheWindow() {
        val t = 1_000_000_000L
        val win = DeleteRules.WINDOW_MS + DeleteRules.RECEIVER_GRACE_MS
        assertTrue(DeleteRules.allowed("u1", "U1", t + win, t, group = false, senderIsAdmin = null))
        assertFalse(DeleteRules.allowed("u1", "u1", t + win + 1, t, group = false, senderIsAdmin = null))
        assertFalse(DeleteRules.allowed("u2", "u1", t, t, group = false, senderIsAdmin = null))
        // Admin at the control's epoch: any age, another member's message; never in DMs.
        assertTrue(DeleteRules.allowed("u2", "u1", t + 10 * win, t, group = true, senderIsAdmin = true))
        assertFalse(DeleteRules.allowed("u2", "u1", t, t, group = true, senderIsAdmin = false))
        assertFalse(DeleteRules.allowed("u2", "u1", t, t, group = false, senderIsAdmin = true))
        // Own message past 48 h in a group: only as an admin.
        assertFalse(DeleteRules.allowed("u1", "u1", t + win + 1, t, group = true, senderIsAdmin = false))
        assertTrue(DeleteRules.allowed("u1", "u1", t + win + 1, t, group = true, senderIsAdmin = true))
    }

    @Test fun eligibilityUsesTheServerClockOffset() {
        val sent = 5_000_000_000L
        val limit = sent + DeleteRules.WINDOW_MS - DeleteRules.SENDER_MARGIN_MS
        fun el(now: Long, offset: Long = 0, mine: Boolean = true, group: Boolean = false, admin: Boolean = false, id: String? = a) =
            DeleteRules.eligibleForEveryone(id, sent, mine, kindIsMessage = true, group = group, iAmAdmin = admin, deviceNowMs = now, offsetMs = offset)
        assertTrue(el(limit - 1))
        assertFalse(el(limit))
        // The device clock is 2 h behind the server: the offset decides.
        assertFalse(el(limit - 1, offset = 2 * 3600_000L))
        assertTrue(el(limit + 3600_000L, offset = -2 * 3600_000L))
        assertFalse(el(sent, mine = false))
        assertTrue(el(sent + 10 * DeleteRules.WINDOW_MS, mine = false, group = true, admin = true))
        assertFalse(el(sent, mine = false, group = false, admin = true))
        assertTrue(el(sent, id = null)) // pending own message: cancel
        assertFalse(DeleteRules.eligibleForEveryone(a, sent, true, kindIsMessage = false, group = false, iAmAdmin = false, deviceNowMs = sent, offsetMs = 0))
    }

    @Test fun tombstoneTexts() {
        assertEquals("You deleted this message", DeleteRules.tombstoneText("ME", "me", byAdmin = false))
        assertEquals("You deleted this message", DeleteRules.tombstoneText("me", "me", byAdmin = true))
        assertEquals("This message was deleted", DeleteRules.tombstoneText("u1", "me", byAdmin = false))
        assertEquals("This message was deleted by an admin", DeleteRules.tombstoneText("u2", "me", byAdmin = true))
    }

    @Test fun serverClockKeepsAndPersistsTheOffset() = runBlocking {
        var saved: Long? = null
        val c = ServerClock(now = { 1_000L }, persist = { saved = it })
        c.onServerTime("1970-01-01T00:00:03.500Z")
        assertEquals(2_500L, c.offsetMs)
        assertEquals(2_500L, saved)
        assertEquals(3_500L, c.serverNow())
        c.onServerTime(null)
        c.onServerTime("garbage")
        assertEquals(2_500L, c.offsetMs)
        val cold = ServerClock(now = { 0L })
        cold.restore(saved)
        assertEquals(2_500L, cold.serverNow())
    }

    @Test fun timeUuidTicksCompareAsTimesNotStrings() {
        // c1a2b3f0-… is later than d2b3… in string order? Compare by time.
        val early = "ffffffff-0000-11f0-8000-0242ac120002" // time_low high, mid 0
        val late = "00000000-0001-11f0-8000-0242ac120002" // time_mid 1 > 0
        assertTrue(early > late)
        assertTrue(TimeUuid.ticks(early)!! < TimeUuid.ticks(late)!!)
        assertNull(TimeUuid.ticks(java.util.UUID.randomUUID().toString()))
        // An ISO time and a TimeUUID made from it compare as the same moment.
        val ms = TimeUuid.isoMs("2026-10-06T08:15:30.456Z")!!
        assertEquals(ms, (TimeUuid.ticksOfIso("2026-10-06T08:15:30.456Z")!! - 0x01B21DD213814000L) / 10_000)
        assertEquals(TimeUuid.epochMs("c1a2b3e1-a0b1-11f0-8000-0242ac120002")!!, TimeUuid.ticks("c1a2b3e1-a0b1-11f0-8000-0242ac120002")!!.let { (it - 0x01B21DD213814000L) / 10_000 })
    }
}
