package lk.codegen.risime.calls

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import lk.codegen.risime.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Call records exactly like WhatsApp: labels per outcome and side, grouping, date format, local direction key. */
class CallRecordsTest {
    private val conv = "dm:a:b"
    private val zone = ZoneId.of("Asia/Colombo")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    /** The row each phone stores: [sentByMe] = this phone sent the call_end; [dir] the local direction key (null = old row). */
    private fun row(reason: String, sentByMe: Boolean, dir: Boolean?, video: Boolean = false, dur: Long? = null, body: String = "", at: Long = 1L, id: String = "c$at$reason$sentByMe") =
        MessageEntity(
            clientMsgId = id, messageId = null, conversationId = conv, from = if (sentByMe) "me" else "peer", to = "x",
            body = body.ifEmpty { CallLines.line(reason, sentByMe, dur, false, video).text }, serverTs = null, localTs = at, status = "SENT", outgoing = sentByMe,
            kind = MessageEntity.KIND_CALL,
            systemJson = CallRecords.withDir(
                CallRecordsTestJson.end("00000000-0000-4000-8000-000000000001", reason, dur, video),
                dir,
            ),
            callId = "00000000-0000-4000-8000-000000000001",
        )

    private fun label(r: MessageEntity) = CallRecords.of(r)!!.label

    @Test fun everyOutcomeVoiceAndVideoBothSides() {
        for ((video, kind, Kind) in listOf(Triple(false, "voice", "Voice"), Triple(true, "video", "Video"))) {
            // Caller cancels before answer: caller "Cancelled", callee "Missed".
            assertEquals("Cancelled $kind call", label(row("cancelled", true, true, video)))
            assertEquals("Missed $kind call", label(row("cancelled", false, false, video)))
            // Ring ran out: caller "No answer", callee "Missed".
            assertEquals("$Kind call · No answer", label(row("timeout", true, true, video)))
            assertEquals("Missed $kind call", label(row("timeout", false, false, video)))
            // Callee busy.
            assertEquals("$Kind call · No answer", label(row("busy", true, true, video)))
            assertEquals("Missed $kind call", label(row("busy", false, false, video)))
            // Callee declines: callee sent it.
            assertEquals("Declined $kind call", label(row("declined", true, false, video)))
            assertEquals("$Kind call · Declined", label(row("declined", false, true, video)))
            // Answered, with durations on either side.
            assertEquals("$Kind call · 3 min", label(row("hangup", true, true, video, dur = 192)))
            assertEquals("$Kind call · 12 s", label(row("hangup", false, false, video, dur = 12)))
            assertEquals("$Kind call · 1 h 5 min", label(row("hangup", false, true, video, dur = 3900)))
            assertEquals("$Kind call", label(row("hangup", false, true, video)))
            assertEquals("$Kind call · Couldn't connect", label(row("failed", true, true, video)))
        }
    }

    @Test fun missedIsRedAndOnlyTheCalleesView() {
        assertTrue(CallRecords.of(row("cancelled", false, false))!!.missed)
        assertFalse(CallRecords.of(row("cancelled", true, true))!!.missed)
        assertFalse(CallRecords.of(row("declined", true, false))!!.missed)
        // a failed call this phone rang for, without an answer: "Missed" (body from CallLines).
        assertTrue(CallRecords.of(row("failed", false, false, body = CallLines.MISSED))!!.missed)
    }

    @Test fun bothPhonesAgreeOnTheSameCall() {
        // reason, caller's row, callee's row -> (caller outcome, callee outcome)
        val caller = CallRecords.of(row("cancelled", true, true))!!
        val callee = CallRecords.of(row("cancelled", false, false))!!
        assertTrue(caller.outgoing && !callee.outgoing)
        assertEquals(CallOutcome.CANCELLED, caller.outcome)
        assertEquals(CallOutcome.MISSED, callee.outcome)
        assertEquals(caller.video, callee.video)
        // declined: callee wrote it.
        assertEquals(CallOutcome.DECLINED, CallRecords.of(row("declined", false, true))!!.outcome)
        assertEquals(CallOutcome.DECLINED, CallRecords.of(row("declined", true, false))!!.outcome)
    }

    @Test fun oldRowsWithoutTheDirectionKeyAreInferredFromTheReason() {
        assertTrue(CallRecords.of(row("cancelled", true, null))!!.outgoing)
        assertFalse(CallRecords.of(row("cancelled", false, null))!!.outgoing)
        assertTrue(CallRecords.of(row("declined", false, null))!!.outgoing) // the peer declined my call
        assertFalse(CallRecords.of(row("declined", true, null))!!.outgoing)
        assertTrue(CallRecords.of(row("hangup", true, null))!!.outgoing) // unknown: falls back to who sent the line
    }

    @Test fun directionKeyIsLocalOnlyNeverOnTheWire() {
        val json = CallRecordsTestJson.end("00000000-0000-4000-8000-000000000001", "cancelled", null, false)
        val stored = CallRecords.withDir(json, true)
        assertTrue(stored.contains(CallRecords.DIR))
        assertEquals(json.replace(" ", ""), CallRecords.strip(stored).replace(" ", ""))
        assertNotNull(CallEnvelope.decode(CallRecords.strip(stored).toByteArray()))
        assertNull(CallRecords.of(MessageEntity("x", null, conv, "a", "b", "t", null, 1, "SENT", true)))
    }

    @Test fun durations() {
        assertEquals("0 s", CallRecords.durationText(0))
        assertEquals("59 s", CallRecords.durationText(59))
        assertEquals("1 min", CallRecords.durationText(60))
        assertEquals("59 min", CallRecords.durationText(3599))
        assertEquals("2 h", CallRecords.durationText(7200))
    }

    @Test fun callsTabDatesTodayYesterdayElseLocalised() {
        val now = ms(2026, 10, 8, 18, 0)
        assertEquals("Today 14:05", CallRecords.stamp(ms(2026, 10, 8, 14, 5), now, zone, Locale.ENGLISH))
        assertEquals("Yesterday 09:12", CallRecords.stamp(ms(2026, 10, 7, 9, 12), now, zone, Locale.ENGLISH))
        assertEquals("6 October, 14:05", CallRecords.stamp(ms(2026, 10, 6, 14, 5), now, zone, Locale.ENGLISH))
        assertEquals("6 octobre, 14:05", CallRecords.stamp(ms(2026, 10, 6, 14, 5), now, zone, Locale.FRENCH))
        // Midnight boundary: 23:59 yesterday is still "Yesterday".
        assertEquals("Yesterday 23:59", CallRecords.stamp(ms(2026, 10, 7, 23, 59), ms(2026, 10, 8, 0, 1), zone, Locale.ENGLISH))
    }

    @Test fun groupingSamePersonSameClassSameDayConsecutiveOnly() {
        fun rec(conv: String, out: Boolean, missed: Boolean, at: Long) = CallRecord("m$at", "c$at", conv, false, out, if (missed) CallOutcome.MISSED else CallOutcome.ANSWERED, null, at)
        val list = listOf(
            rec("a", false, true, ms(2026, 10, 8, 14, 5)),
            rec("a", false, true, ms(2026, 10, 8, 13, 5)),
            rec("a", false, true, ms(2026, 10, 8, 9, 5)), // 3 missed from a today
            rec("a", true, false, ms(2026, 10, 8, 8, 5)), // outgoing: a new row
            rec("b", true, false, ms(2026, 10, 8, 7, 5)),
            rec("a", true, false, ms(2026, 10, 8, 6, 5)), // not consecutive with the earlier a-outgoing
            rec("a", true, false, ms(2026, 10, 7, 23, 5)), // other day
            rec("a", true, false, ms(2026, 10, 7, 22, 5)),
        )
        val g = groupCalls(list, zone)
        assertEquals(listOf(3, 1, 1, 1, 2), g.map { it.count })
        assertEquals(CallDirClass.MISSED, g[0].latest.dirClass)
        assertEquals(CallDirClass.OUTGOING, g[1].latest.dirClass)
        assertEquals(ms(2026, 10, 8, 14, 5), g[0].latest.atMs)
    }
}

/** A call_end JSON as the wire has it. */
object CallRecordsTestJson {
    fun end(callId: String, reason: String, dur: Long?, video: Boolean): String = String(
        CallEnvelope.encode(CallEnvelope.End(callId, reason, durationS = dur, media = if (video) CallEnvelope.MEDIA_VIDEO else CallEnvelope.MEDIA_AUDIO)),
        Charsets.UTF_8,
    )
}
