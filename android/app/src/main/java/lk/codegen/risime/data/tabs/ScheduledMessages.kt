package lk.codegen.risime.data.tabs

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lk.codegen.risime.data.db.ScheduledDao
import lk.codegen.risime.data.db.ScheduledMessageEntity
import lk.codegen.risime.data.db.ScheduledSendEntity
import lk.codegen.risime.net.CancelScheduledResult
import lk.codegen.risime.net.ScheduleMessageArgs
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/*
 * Contract v1.26 §26.6: scheduled messages. The phone stores the schedule (Room), arms an exact
 * alarm (or WorkManager without SCHEDULE_EXACT_ALARM), and at the time composes the message and sends
 * it through the normal send path (encrypted then, at the current epoch), exactly as if typed. The
 * server never stores the text.
 */

/** Arming the wake-up for a schedule (an exact alarm or WorkManager); the app's is Android's. */
interface ScheduleArmer {
    /** Whether exact alarms are allowed (else the send may be a few minutes late). */
    fun exact(): Boolean

    fun arm(scheduleId: String, atMs: Long)

    fun disarm(scheduleId: String)
}

/** Why a schedule wasn't made (the tool's `error` code), or its id. */
sealed interface ScheduleOutcome {
    data class Made(val scheduleId: String) : ScheduleOutcome

    data class Refused(val code: String) : ScheduleOutcome
}

class ScheduledMessages(
    private val dao: ScheduledDao,
    private val armer: ScheduleArmer,
    /** Queues the message on the normal send path with [clientMsgId] (idempotent); false: couldn't. */
    private val send: suspend (conversationId: String, text: String, clientMsgId: String) -> Boolean,
    /** The phone holds this conversation and the user is an active member of it (never a Risi chat). */
    private val isMember: suspend (conversationId: String) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val graphemes: (String) -> Int = { it.codePointCount(0, it.length) },
    private val log: (String) -> Unit = {},
) {
    private val lock = Mutex()

    companion object {
        const val MAX_PENDING = 50
        const val MAX_TEXT_GRAPHEMES = 4096
        const val MAX_AHEAD_MS = 366L * 24 * 3600_000
        /** A send more than this late shows "Sent late" on the sender's bubble. */
        const val LATE_MS = 2L * 60_000
        /** A one-off more than this late is not sent automatically; a daily occurrence is skipped. */
        const val MISSED_MS = 12L * 3600_000

        private val DAY_TIME = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)

        /** "Scheduled for Tue 06:00 (every day)". */
        fun label(s: ScheduledMessageEntity, zone: ZoneId = ZoneId.systemDefault()): String {
            val at = DAY_TIME.format(Instant.ofEpochMilli(s.nextAt).atZone(zone))
            return when {
                s.state == ScheduledMessageEntity.STATE_MISSED -> "Not sent: your phone was off"
                s.repeat == ScheduledMessageEntity.REPEAT_DAILY -> "Scheduled for $at (every day)"
                else -> "Scheduled for $at"
            }
        }

        /** The first daily occurrence at [hour]:[minute] (wall clock in [zone]) strictly after [afterMs]. */
        fun nextDaily(afterMs: Long, hour: Int, minute: Int, zone: ZoneId): Long {
            val after = Instant.ofEpochMilli(afterMs).atZone(zone)
            var d = after.toLocalDate()
            while (true) {
                val t = ZonedDateTime.of(d, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()
                if (t > afterMs) return t
                d = d.plusDays(1)
            }
        }

        /** The latest daily occurrence at or before [nowMs], not before [fromMs] (null: none yet). */
        fun latestDaily(fromMs: Long, nowMs: Long, hour: Int, minute: Int, zone: ZoneId): Long? {
            if (nowMs < fromMs) return null
            var d = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            repeat(3) {
                val t = ZonedDateTime.of(d, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()
                if (t <= nowMs) return if (t >= fromMs) t else null
                d = d.minusDays(1)
            }
            return null
        }
    }

    suspend fun pendingCount(): Int = dao.pendingCount()

    /** §26.6 `schedule_message` once its acceptance rule held: validated, stored, armed. */
    suspend fun schedule(a: ScheduleMessageArgs): ScheduleOutcome = lock.withLock {
        val text = a.text.trim()
        val at = runCatching { Instant.parse(a.at).toEpochMilli() }.getOrNull() ?: return@withLock ScheduleOutcome.Refused("bad_args")
        val t = now()
        if (text.isEmpty() || graphemes(text) > MAX_TEXT_GRAPHEMES) return@withLock ScheduleOutcome.Refused("bad_args")
        if (at <= t || at - t > MAX_AHEAD_MS) return@withLock ScheduleOutcome.Refused("bad_args")
        if (a.repeat != null && a.repeat != ScheduledMessageEntity.REPEAT_DAILY) return@withLock ScheduleOutcome.Refused("bad_args")
        if (!isMember(a.conversationId)) return@withLock ScheduleOutcome.Refused("not_member")
        if (dao.pendingCount() >= MAX_PENDING) return@withLock ScheduleOutcome.Refused("too_many_scheduled")
        val local = Instant.ofEpochMilli(at).atZone(zone())
        val s = ScheduledMessageEntity(
            scheduleId = newId(), writeId = a.writeId, conversationId = a.conversationId, text = text, repeat = a.repeat,
            localHour = local.hour, localMinute = local.minute, nextAt = at, state = ScheduledMessageEntity.STATE_PENDING, createdAt = t,
        )
        dao.upsert(s)
        armer.arm(s.scheduleId, s.nextAt)
        log("scheduled message ${s.scheduleId}: armed (${if (armer.exact()) "exact" else "inexact"})")
        ScheduleOutcome.Made(s.scheduleId)
    }

    /** §26.6 `cancel_scheduled` (and the bubble's Cancel): a cancelled daily schedule stops every later send. */
    suspend fun cancel(scheduleId: String): CancelScheduledResult = lock.withLock {
        val s = dao.get(scheduleId) ?: return@withLock CancelScheduledResult(false, "unknown")
        when (s.state) {
            ScheduledMessageEntity.STATE_PENDING, ScheduledMessageEntity.STATE_MISSED -> {
                dao.upsert(s.copy(state = ScheduledMessageEntity.STATE_CANCELLED))
                armer.disarm(scheduleId)
                CancelScheduledResult(true, null)
            }
            ScheduledMessageEntity.STATE_SENT -> CancelScheduledResult(false, "already_sent")
            else -> CancelScheduledResult(false, "unknown")
        }
    }

    /** §26.5 revoke with "Also cancel N pending". */
    suspend fun cancelAll(): Int {
        val ids = dao.pendingNow().map { it.scheduleId }
        ids.forEach { cancel(it) }
        return ids.size
    }

    /** The bubble's Edit (local, until it is sent). */
    suspend fun edit(scheduleId: String, text: String): Boolean = lock.withLock {
        val s = dao.get(scheduleId) ?: return@withLock false
        val t = text.trim()
        if (!s.pending || t.isEmpty() || graphemes(t) > MAX_TEXT_GRAPHEMES) return@withLock false
        dao.upsert(s.copy(text = t))
        true
    }

    /** The bubble's Send now (also for a missed one-off): this occurrence goes now; a daily one continues. */
    suspend fun sendNow(scheduleId: String): Boolean = lock.withLock {
        val s = dao.get(scheduleId) ?: return@withLock false
        if (s.state != ScheduledMessageEntity.STATE_PENDING && s.state != ScheduledMessageEntity.STATE_MISSED) return@withLock false
        // A daily schedule's Send now stands in for its next occurrence (the one after goes as planned).
        val t = now()
        sendOccurrence(s, if (s.repeat == ScheduledMessageEntity.REPEAT_DAILY) maxOf(s.nextAt, t) else t)
    }

    /** A missed one-off's [Discard]. */
    suspend fun discard(scheduleId: String) {
        cancel(scheduleId)
    }

    /** After a reboot, an update or a process start: re-arm every pending schedule, then send what is due. */
    suspend fun rearmAll() {
        for (s in dao.pendingNow()) armer.arm(s.scheduleId, s.nextAt)
        runDue()
    }

    /**
     * §26.6 at (or after) the time: a one-off is sent unless it is more than 12 h late (then it waits
     * for [Send now] / [Discard]); for `daily` the missed earlier occurrences are skipped and only the
     * latest is sent, if it is under 12 h late. Returns how many were queued.
     */
    suspend fun runDue(): Int = lock.withLock {
        val t = now()
        var queued = 0
        for (s in dao.pendingNow()) {
            if (s.nextAt > t) continue
            if (s.repeat == ScheduledMessageEntity.REPEAT_DAILY) {
                val latest = latestDaily(s.nextAt, t, s.localHour, s.localMinute, zone()) ?: s.nextAt
                if (t - latest > MISSED_MS) {
                    val next = nextDaily(t, s.localHour, s.localMinute, zone())
                    dao.upsert(s.copy(nextAt = next))
                    armer.arm(s.scheduleId, next)
                    log("scheduled message ${s.scheduleId}: daily occurrence skipped (over 12 h late)")
                    continue
                }
                if (sendOccurrence(s, latest)) queued++
            } else {
                if (t - s.nextAt > MISSED_MS) {
                    dao.upsert(s.copy(state = ScheduledMessageEntity.STATE_MISSED))
                    armer.disarm(s.scheduleId)
                    log("scheduled message ${s.scheduleId}: not sent, over 12 h late")
                    continue
                }
                if (sendOccurrence(s, s.nextAt)) queued++
            }
        }
        queued
    }

    /** Persist this occurrence's client_msg_id first, then queue it; advance (daily) or finish (one-off). */
    private suspend fun sendOccurrence(s0: ScheduledMessageEntity, occurrence: Long): Boolean {
        val s = if (s0.occurrenceAt == occurrence && s0.occurrenceClientMsgId != null) s0 else s0.copy(occurrenceAt = occurrence, occurrenceClientMsgId = newId()).also { dao.upsert(it) }
        val id = s.occurrenceClientMsgId!!
        val t = now()
        if (!send(s.conversationId, s.text, id)) {
            log("scheduled message ${s.scheduleId}: not queued (retry later)")
            return false
        }
        dao.insertSend(ScheduledSendEntity(id, s.scheduleId, occurrence, t))
        if (s.repeat == ScheduledMessageEntity.REPEAT_DAILY) {
            val next = nextDaily(maxOf(t, occurrence), s.localHour, s.localMinute, zone())
            dao.upsert(s.copy(nextAt = next, state = ScheduledMessageEntity.STATE_PENDING))
            armer.arm(s.scheduleId, next)
        } else {
            dao.upsert(s.copy(state = ScheduledMessageEntity.STATE_SENT))
            armer.disarm(s.scheduleId)
        }
        log("scheduled message ${s.scheduleId}: queued (${if (t - occurrence > LATE_MS) "late" else "on time"})")
        return true
    }
}

/** §26.6 "Sent late": the bubble's message went out more than 2 minutes after its occurrence. */
fun sentLate(send: ScheduledSendEntity?, serverTsMs: Long?, localTs: Long): Boolean {
    send ?: return false
    val at = serverTsMs ?: localTs
    return at - send.occurrenceAt > ScheduledMessages.LATE_MS
}
