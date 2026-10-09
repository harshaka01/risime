package lk.codegen.risime.data.tabs

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import lk.codegen.risime.data.db.RisiWriteEntity
import lk.codegen.risime.data.db.ScheduledDao
import lk.codegen.risime.data.db.ScheduledMessageEntity
import lk.codegen.risime.data.db.ScheduledSendEntity
import lk.codegen.risime.net.CancelScheduledResult
import lk.codegen.risime.net.ScheduleMessageArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** An in-memory [ScheduledDao] (the Room one is the same queries). */
class FakeScheduledDao : ScheduledDao {
    val rows = MutableStateFlow<Map<String, ScheduledMessageEntity>>(emptyMap())
    val sendRows = MutableStateFlow<Map<String, ScheduledSendEntity>>(emptyMap())
    val writes = LinkedHashMap<String, RisiWriteEntity>()

    override fun open(): Flow<List<ScheduledMessageEntity>> = rows.map { m -> m.values.filter { it.state in setOf("pending", "missed") }.sortedBy { it.nextAt } }
    override suspend fun pendingNow() = rows.value.values.filter { it.pending }.sortedBy { it.nextAt }
    override suspend fun pendingCount() = rows.value.values.count { it.pending }
    override suspend fun get(id: String) = rows.value[id]
    override suspend fun upsert(s: ScheduledMessageEntity) { rows.value = rows.value + (s.scheduleId to s) }
    override suspend fun insertSend(s: ScheduledSendEntity): Long {
        if (s.clientMsgId in sendRows.value) return -1
        sendRows.value = sendRows.value + (s.clientMsgId to s)
        return 1
    }
    override fun sends(): Flow<List<ScheduledSendEntity>> = sendRows.map { it.values.toList() }
    override suspend fun write(writeId: String) = writes[writeId]
    override suspend fun insertWrite(w: RisiWriteEntity): Long {
        if (w.writeId in writes) return -1
        writes[w.writeId] = w
        return 1
    }
}

class FakeArmer(var exactOk: Boolean = true) : ScheduleArmer {
    val armed = LinkedHashMap<String, Long>()
    override fun exact() = exactOk
    override fun arm(scheduleId: String, atMs: Long) { armed[scheduleId] = atMs }
    override fun disarm(scheduleId: String) { armed.remove(scheduleId) }
}

/** §26.6 A14: the scheduler (validation, exact/fallback arming, late and over-12-h rules, daily skipping, idempotent sends). */
class ScheduledMessagesTest {
    private val zone = ZoneId.of("Asia/Colombo") // +05:30
    private val conv = "dm:3b4c5d6e-7f8a-4b9c-8d0e-1f2a3b4c5d6e_7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private fun ms(ts: String) = Instant.parse(ts).toEpochMilli()

    private var now = ms("2026-10-09T15:10:00Z")
    private val dao = FakeScheduledDao()
    private val armer = FakeArmer()
    private val sent = mutableListOf<Triple<String, String, String>>()
    private var sendOk = true
    private var member = true
    private var ids = 0

    private val s = ScheduledMessages(
        dao, armer,
        send = { c, t, id -> if (sendOk) { sent += Triple(c, t, id); true } else false },
        isMember = { member },
        now = { now }, zone = { zone }, newId = { "id-${++ids}" },
    )

    private fun args(at: String, repeat: String? = null, text: String = "Good morning") = ScheduleMessageArgs("w-${ids + 100}", conv, text, at, repeat)

    private fun made(o: ScheduleOutcome) = (o as ScheduleOutcome.Made).scheduleId

    @Test fun validatesAndArms() = runBlocking {
        assertEquals(ScheduleOutcome.Refused("bad_args"), s.schedule(args("2026-10-09T15:00:00Z"))) // in the past
        assertEquals(ScheduleOutcome.Refused("bad_args"), s.schedule(args("2028-10-09T15:00:00Z"))) // > 1 year
        assertEquals(ScheduleOutcome.Refused("bad_args"), s.schedule(args("2026-10-10T00:30:00Z", repeat = "weekly")))
        assertEquals(ScheduleOutcome.Refused("bad_args"), s.schedule(args("2026-10-10T00:30:00Z", text = "  ")))
        member = false
        assertEquals(ScheduleOutcome.Refused("not_member"), s.schedule(args("2026-10-10T00:30:00Z")))
        member = true
        val id = made(s.schedule(args("2026-10-10T00:30:00Z", repeat = "daily")))
        assertEquals(ms("2026-10-10T00:30:00Z"), armer.armed[id])
        val row = dao.get(id)!!
        assertEquals(6 to 0, row.localHour to row.localMinute) // 06:00 in Colombo
        assertTrue(sent.isEmpty()) // nothing is sent before the time
    }

    @Test fun atMost50Pending() = runBlocking {
        repeat(50) { made(s.schedule(args("2026-10-10T00:30:00Z"))) }
        assertEquals(ScheduleOutcome.Refused("too_many_scheduled"), s.schedule(args("2026-10-10T00:30:00Z")))
    }

    @Test fun aOneOffIsSentOnceAtTheTimeWithAPersistedId() = runBlocking {
        val id = made(s.schedule(args("2026-10-09T15:11:00Z")))
        assertEquals(0, s.runDue())
        now = ms("2026-10-09T15:11:00Z")
        sendOk = false
        assertEquals(0, s.runDue()) // couldn't queue: the occurrence id is kept for the retry
        val firstId = dao.get(id)!!.occurrenceClientMsgId!!
        sendOk = true
        now += 30_000
        assertEquals(1, s.runDue())
        assertEquals(listOf(Triple(conv, "Good morning", firstId)), sent)
        assertEquals(ScheduledMessageEntity.STATE_SENT, dao.get(id)!!.state)
        assertNull(armer.armed[id])
        assertEquals(0, s.runDue()) // never twice
        assertFalse(sentLate(dao.sendRows.value[firstId], now, now))
    }

    @Test fun sentLateAfterTwoMinutes() = runBlocking {
        made(s.schedule(args("2026-10-09T15:11:00Z")))
        now = ms("2026-10-09T15:20:00Z") // phone was off for 9 minutes
        s.runDue()
        val send = dao.sendRows.value.values.single()
        assertTrue(sentLate(send, now, now))
        assertFalse(sentLate(send, ms("2026-10-09T15:12:00Z"), 0))
    }

    @Test fun aOneOffOver12HoursLateWaitsForSendNowOrDiscard() = runBlocking {
        val id = made(s.schedule(args("2026-10-09T15:11:00Z")))
        now = ms("2026-10-10T04:00:00Z")
        assertEquals(0, s.runDue())
        assertEquals(ScheduledMessageEntity.STATE_MISSED, dao.get(id)!!.state)
        assertEquals("Not sent: your phone was off", ScheduledMessages.label(dao.get(id)!!, zone))
        assertTrue(s.sendNow(id))
        assertEquals(1, sent.size)
        val id2 = made(s.schedule(args("2026-10-10T05:00:00Z")))
        now = ms("2026-10-10T18:00:00Z")
        s.runDue()
        s.discard(id2)
        assertEquals(ScheduledMessageEntity.STATE_CANCELLED, dao.get(id2)!!.state)
        assertEquals(1, sent.size)
    }

    @Test fun dailyRepeatsAtTheSameLocalTimeAndSkipsMissedDays() = runBlocking {
        val id = made(s.schedule(args("2026-10-10T00:30:00Z", repeat = "daily")))
        now = ms("2026-10-10T00:30:05Z")
        assertEquals(1, s.runDue())
        assertEquals(ms("2026-10-11T00:30:00Z"), dao.get(id)!!.nextAt)
        assertEquals(ms("2026-10-11T00:30:00Z"), armer.armed[id])
        assertEquals("Scheduled for Sun 06:00 (every day)", ScheduledMessages.label(dao.get(id)!!, zone))
        // Phone off for three days: only the latest occurrence goes, if under 12 h late.
        now = ms("2026-10-14T03:00:00Z")
        assertEquals(1, s.runDue())
        assertEquals(2, sent.size)
        assertEquals(ms("2026-10-14T00:30:00Z"), dao.sendRows.value.values.maxByOrNull { it.queuedAt }!!.occurrenceAt)
        assertEquals(ms("2026-10-15T00:30:00Z"), dao.get(id)!!.nextAt)
        // Over 12 h late: skipped, re-armed for the next day.
        now = ms("2026-10-15T14:00:00Z")
        assertEquals(0, s.runDue())
        assertEquals(ms("2026-10-16T00:30:00Z"), dao.get(id)!!.nextAt)
        assertEquals(ScheduledMessageEntity.STATE_PENDING, dao.get(id)!!.state)
    }

    @Test fun cancelStopsEveryLaterSendAndRearmAllArmsPending() = runBlocking {
        val a = made(s.schedule(args("2026-10-10T00:30:00Z", repeat = "daily")))
        val b = made(s.schedule(args("2026-10-11T00:30:00Z")))
        armer.armed.clear()
        s.rearmAll() // boot / update / process start
        assertEquals(setOf(a, b), armer.armed.keys)
        assertEquals(CancelScheduledResult(true, null), s.cancel(a))
        assertEquals(CancelScheduledResult(false, "unknown"), s.cancel("nope"))
        now = ms("2026-10-11T00:31:00Z")
        s.runDue()
        assertEquals(1, sent.size) // only b
        assertEquals(CancelScheduledResult(false, "already_sent"), s.cancel(b))
        assertEquals(1, s.cancelAll() + 1) // nothing pending left
    }

    @Test fun editAndSendNowAreLocal() = runBlocking {
        val id = made(s.schedule(args("2026-10-10T00:30:00Z", repeat = "daily")))
        assertTrue(s.edit(id, "Good morning!"))
        assertTrue(s.sendNow(id))
        assertEquals("Good morning!", sent.single().second)
        // A daily schedule's Send now stands in for the next occurrence.
        assertEquals(ms("2026-10-11T00:30:00Z"), dao.get(id)!!.nextAt)
    }

    @Test fun nextAndLatestDailyAcrossDst() {
        val ny = ZoneId.of("America/New_York")
        val before = ms("2026-11-01T05:00:00Z") // 01:00 EDT on the change day
        val next = ScheduledMessages.nextDaily(before, 6, 0, ny)
        assertEquals(ms("2026-11-01T11:00:00Z"), next) // 06:00 EST
        assertEquals(ms("2026-10-31T10:00:00Z"), ScheduledMessages.latestDaily(0, before, 6, 0, ny)) // 06:00 EDT the day before
    }
}
