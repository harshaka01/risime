package lk.codegen.risime.data.tabs

import kotlinx.serialization.json.JsonObject
import lk.codegen.risime.data.HistoryMarkers
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.db.RisiWriteEntity
import lk.codegen.risime.data.db.ScheduledDao
import lk.codegen.risime.net.CalendarAddResult
import lk.codegen.risime.net.CalendarCheckResult
import lk.codegen.risime.net.CalendarRemoveResult
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.net.RisiSkillIds
import lk.codegen.risime.net.RisiSkillStates
import lk.codegen.risime.net.RisiSkillToolErrors
import lk.codegen.risime.net.RisiToolCall
import lk.codegen.risime.net.RisiToolErrorCodes
import lk.codegen.risime.net.RisiToolResult
import lk.codegen.risime.net.ScheduleMessageResult
import lk.codegen.risime.net.SetAlarmArgs
import lk.codegen.risime.net.SetAlarmResult
import java.time.Instant

/*
 * Contract v1.26 §25.3/§26.3/§26.4/§26.6: the phone's own executor for Risi's client tools. A write
 * runs only when the phone itself can trace it back to the user: (a) a confirm card from the agent
 * leaf whose `args` equal the call's exactly, and the user's own `confirm_write` for it; or (b) for
 * an Allowed asker-only write, the phone's own record says Allowed and its MLS history holds the
 * user's own `risi_request` for this `request_id` at most 10 minutes earlier. A `write_id` runs once;
 * an undo only touches what this phone recorded making. Otherwise the answer is `declined`.
 */
class RisiToolExecutor(
    private val me: suspend () -> String?,
    /** The conversation's stored rows (MLS history on this phone). */
    private val history: suspend (conversationId: String) -> List<MessageEntity>,
    /** The user's Risi chat conversation(s) on this phone (personal cards are posted there, §25.1). */
    private val risiChats: suspend () -> List<String>,
    /** §26.3 the phone's own record of a skill's state (from its last GET/PATCH). */
    private val skillState: (String) -> String,
    private val dao: ScheduledDao,
    private val scheduled: ScheduledMessages,
    /** Fires `AlarmClock.ACTION_SET_ALARM` (skip UI); false: no app handles it. */
    private val setAlarm: suspend (SetAlarmArgs) -> Boolean,
    private val graphemes: (String) -> Int = { it.codePointCount(0, it.length) },
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /** §25.3/§26.6 the phone's calendar (null: no calendar on this build; calendar tools answer `calendar_unavailable`). */
    private val calendar: PhoneCalendar? = null,
) {
    companion object {
        /** §26.3 the request must be at most this old at the call's `server_ts`. */
        const val ALLOWED_WINDOW_MS = 10L * 60_000

        /** Clock skew between this phone and the server's `server_ts`. */
        const val SKEW_MS = 60_000L

        val SKILL_OF = mapOf(
            RisiToolCall.TOOL_SET_ALARM to RisiSkillIds.ALARM,
            RisiToolCall.TOOL_SCHEDULE_MESSAGE to RisiSkillIds.SCHEDULED_MESSAGES,
            RisiToolCall.TOOL_CANCEL_SCHEDULED to RisiSkillIds.SCHEDULED_MESSAGES,
            RisiToolCall.TOOL_CALENDAR_CHECK to RisiSkillIds.CALENDAR,
            RisiToolCall.TOOL_CALENDAR_ADD to RisiSkillIds.CALENDAR,
            RisiToolCall.TOOL_CALENDAR_REMOVE to RisiSkillIds.CALENDAR,
        )

        /** §26.3 the only writes the Allowed path may run without a card. */
        val ALLOWED_TOOLS = setOf(RisiToolCall.TOOL_SET_ALARM, RisiToolCall.TOOL_CALENDAR_ADD)

        private val TIME = Regex("^([01][0-9]|2[0-3]):([0-5][0-9])$")

        fun ok(result: JsonObject) = RisiToolResult(RisiToolResult.OK, result)

        private fun <T> ok(ser: kotlinx.serialization.KSerializer<T>, v: T) = ok(ProtocolJson.encodeToJsonElement(ser, v) as JsonObject)

        /** The call's args without `write_id` (what a v1.26 card's `args` must equal exactly). */
        fun argsWithoutWriteId(call: RisiToolCall): JsonObject = JsonObject(call.args - "write_id")

        fun tsMs(ts: String?): Long? = ts?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    }

    suspend fun execute(call: RisiToolCall): RisiToolResult = when (call.tool) {
        RisiToolCall.TOOL_SET_ALARM -> setAlarmTool(call)
        RisiToolCall.TOOL_SCHEDULE_MESSAGE -> scheduleTool(call)
        RisiToolCall.TOOL_CANCEL_SCHEDULED -> cancelTool(call)
        RisiToolCall.TOOL_CALENDAR_REMOVE -> calendarRemoveTool(call)
        RisiToolCall.TOOL_CALENDAR_CHECK -> calendarCheckTool(call)
        RisiToolCall.TOOL_CALENDAR_ADD -> calendarAddTool(call)
        else -> RisiToolResult.error(RisiToolErrorCodes.UNKNOWN_TOOL)
    }

    /**
     * §25.3 on a device without `risi_skills` (v1.25): only `calendar_check` and `calendar_add`, with no
     * skill gate and no Allowed path (the card rule alone); every other tool is `unknown_tool` (§26.9).
     */
    suspend fun executeV125(call: RisiToolCall): RisiToolResult = when (call.tool) {
        RisiToolCall.TOOL_CALENDAR_CHECK -> calendarCheckTool(call, gated = false)
        RisiToolCall.TOOL_CALENDAR_ADD -> calendarAddTool(call, gated = false)
        else -> RisiToolResult.error(RisiToolErrorCodes.UNKNOWN_TOOL)
    }

    // ---- acceptance ----

    /** The agent's confirm card for this call's `write_id` and tool, wherever this phone holds it. */
    private suspend fun cardRows(call: RisiToolCall): Pair<RisiMeta, List<MessageEntity>>? {
        val wid = call.writeId ?: return null
        val convs = (listOfNotNull(call.conversationId) + risiChats()).distinctBy { it.lowercase() }
        for (conv in convs) {
            val rows = history(conv)
            val card = rows.firstNotNullOfOrNull { m ->
                RisiMessages.meta(m)?.takeIf { it.kind == RisiKinds.CONFIRM && it.writeId.equals(wid, true) && it.tool == call.tool }
            } ?: continue
            return card to rows
        }
        return null
    }

    /**
     * §25.3/§26.5 rule (a): the card's `args` equal the call's (minus `write_id`) exactly, the user is
     * in its `for`, and the first answer to it is the user's own `confirm_write`, before `expires_at`.
     */
    suspend fun cardConfirmed(call: RisiToolCall): Boolean {
        val my = me() ?: return false
        val (card, rows) = cardRows(call) ?: return false
        val args = card.args
        if (args != null) {
            if (args != argsWithoutWriteId(call)) return false
        } else if (!v125CalendarCardMatches(card, call)) {
            return false
        }
        if (card.forUsers.none { it.equals(my, true) }) return false
        val exp = tsMs(card.expiresAt) ?: return false
        val confirm = rows.firstOrNull { m ->
            m.kind == MessageEntity.KIND_RISI_CTL && m.from.equals(my, true) &&
                RisiControl.targetOf(m.systemJson)?.equals(call.writeId, true) == true &&
                RisiControl.actionOf(m.systemJson) in setOf(RisiActions126Local.CONFIRM, RisiActions126Local.CANCEL)
        } ?: return false
        if (RisiControl.actionOf(confirm.systemJson) != RisiActions126Local.CONFIRM) return false
        val at = HistoryMarkers.epochMs(confirm.serverTs) ?: confirm.localTs
        return at <= exp
    }

    /**
     * §25.3 a v1.25 `calendar_add` card (no `args`): the call's `title`, `start`, `end` and `all_day`
     * equal the card's `text` and `when` (timestamps compared as instants).
     */
    private fun v125CalendarCardMatches(card: RisiMeta, call: RisiToolCall): Boolean {
        if (call.tool != RisiToolCall.TOOL_CALENDAR_ADD) return false
        val a = call.calendarAddArgs() ?: return false
        val w = card.confirmWhen() ?: return false
        if (card.text != a.title || w.allDay != a.allDay) return false
        val s = tsMs(a.start) ?: return false
        val e = tsMs(a.end) ?: return false
        return tsMs(w.start) == s && tsMs(w.end) == e
    }

    /**
     * §26.3 rule (b): an asker-only tool, the phone's own record says Allowed, and this conversation's
     * history holds the user's own `risi_request` with the call's `request_id`, ≤ 10 min before `server_ts`.
     */
    suspend fun allowedByOwnRequest(call: RisiToolCall): Boolean {
        if (call.tool !in ALLOWED_TOOLS) return false
        val skill = SKILL_OF[call.tool] ?: return false
        if (skillState(skill) != RisiSkillStates.ALLOWED) return false
        val my = me() ?: return false
        val rid = call.requestId ?: return false
        val conv = call.conversationId ?: return false
        val callTs = tsMs(call.serverTs) ?: return false
        val req = history(conv).firstOrNull { m ->
            m.kind == MessageEntity.KIND_RISI_CTL && m.from.equals(my, true) && RisiControl.requestIdOf(m.systemJson)?.equals(rid, true) == true
        } ?: return false
        val at = HistoryMarkers.epochMs(req.serverTs) ?: req.localTs
        return at <= callTs + SKEW_MS && callTs - at <= ALLOWED_WINDOW_MS
    }

    private suspend fun unused(writeId: String?): Boolean = writeId != null && dao.write(writeId) == null

    /** A skill tool needs its skill on in the phone's own record (a revoke voids open cards, §26.5). */
    private fun skillOn(call: RisiToolCall): Boolean = SKILL_OF[call.tool]?.let { skillState(it) != RisiSkillStates.OFF } ?: false

    private suspend fun accepted(call: RisiToolCall, allowedPath: Boolean, gated: Boolean = true): Boolean {
        if (gated && !skillOn(call)) { log("risi tool ${call.tool}: declined (skill off on this phone)"); return false }
        if (!unused(call.writeId)) { log("risi tool ${call.tool}: declined (write_id used or missing)"); return false }
        if (cardConfirmed(call)) return true
        if (gated && allowedPath && allowedByOwnRequest(call)) return true
        log("risi tool ${call.tool}: declined (no confirmed card or own request)")
        return false
    }

    private suspend fun record(call: RisiToolCall, target: String?): Boolean =
        dao.insertWrite(RisiWriteEntity(call.writeId!!, call.tool, target, now())) != -1L

    // ---- tools ----

    private suspend fun setAlarmTool(call: RisiToolCall): RisiToolResult {
        val a = call.setAlarmArgs() ?: return RisiToolResult.error(RisiToolErrorCodes.BAD_ARGS)
        if (!TIME.matches(a.time) || a.days?.any { it !in 1..7 } == true || graphemes(a.label) > 60) return RisiToolResult.error(RisiToolErrorCodes.BAD_ARGS)
        if (!accepted(call, allowedPath = true)) return RisiToolResult.declined()
        if (!record(call, null)) return RisiToolResult.declined()
        return if (setAlarm(a)) ok(SetAlarmResult.serializer(), SetAlarmResult(true)) else RisiToolResult.error(RisiSkillToolErrors.ALARM_UNAVAILABLE)
    }

    private suspend fun scheduleTool(call: RisiToolCall): RisiToolResult {
        val a = call.scheduleMessageArgs() ?: return RisiToolResult.error(RisiToolErrorCodes.BAD_ARGS)
        // Always confirmed (sent to other people): never the Allowed path.
        if (!accepted(call, allowedPath = false)) return RisiToolResult.declined()
        return when (val r = scheduled.schedule(a)) {
            is ScheduleOutcome.Made -> {
                record(call, r.scheduleId)
                ok(ScheduleMessageResult.serializer(), ScheduleMessageResult(r.scheduleId))
            }
            is ScheduleOutcome.Refused -> RisiToolResult.error(r.code)
        }
    }

    private suspend fun cancelTool(call: RisiToolCall): RisiToolResult {
        val a = call.cancelScheduledArgs() ?: return RisiToolResult.error(RisiToolErrorCodes.BAD_ARGS)
        if (call.undoEntryId != null && a.writeId == null) {
            // §26.4 an undo: only a schedule this phone made (its own local record), no card needed.
            val s = dao.get(a.scheduleId)
            if (s == null) return ok(lk.codegen.risime.net.CancelScheduledResult.serializer(), lk.codegen.risime.net.CancelScheduledResult(false, "unknown"))
            return ok(lk.codegen.risime.net.CancelScheduledResult.serializer(), scheduled.cancel(a.scheduleId))
        }
        // A cancel by request: always after its confirm card.
        if (!accepted(call, allowedPath = false)) return RisiToolResult.declined()
        record(call, a.scheduleId)
        return ok(lk.codegen.risime.net.CancelScheduledResult.serializer(), scheduled.cancel(a.scheduleId))
    }

    /** §25.3 free/busy blocks only (merged, ≤ 14 days); `no_permission` without READ_CALENDAR. */
    private fun calendarCheckTool(call: RisiToolCall, gated: Boolean = true): RisiToolResult {
        val a = call.calendarCheckArgs() ?: return RisiToolResult.error(RisiToolErrorCodes.BAD_ARGS)
        val from = tsMs(a.from) ?: return RisiToolResult.error(RisiToolErrorCodes.BAD_ARGS)
        val to = tsMs(a.to) ?: return RisiToolResult.error(RisiToolErrorCodes.BAD_ARGS)
        if (to <= from || to - from > PhoneCalendar.MAX_WINDOW_MS) return RisiToolResult.error(RisiToolErrorCodes.BAD_ARGS)
        if (gated && !skillOn(call)) { log("risi tool ${call.tool}: declined (skill off on this phone)"); return RisiToolResult.declined() }
        val cal = calendar ?: return RisiToolResult.error(RisiToolErrorCodes.CALENDAR_UNAVAILABLE)
        val blocks = runCatching { cal.check(from, to) }.getOrElse { return RisiToolResult.error(RisiToolErrorCodes.CALENDAR_UNAVAILABLE) }
            ?: return RisiToolResult(RisiToolResult.NO_PERMISSION, null)
        return ok(CalendarCheckResult.serializer(), CalendarCheckResult(blocks))
    }

    /**
     * §25.3/§26.3 `calendar_add`: only after the card rule (a) or the Allowed rule (b); into the chosen
     * (else the account's own Google) calendar, read back to verify. The wire result is `{event_id}`
     * (the contract's exact schema); the calendar's name/type and any failure reason stay in the local
     * record the card shows. A failed add releases its `write_id`, so [Retry] can run it again.
     */
    private suspend fun calendarAddTool(call: RisiToolCall, gated: Boolean = true): RisiToolResult {
        val a = call.calendarAddArgs() ?: return RisiToolResult.error(RisiToolErrorCodes.BAD_ARGS)
        val start = tsMs(a.start)
        val end = tsMs(a.end)
        val n = graphemes(a.title)
        if (start == null || end == null || start >= end || n < 1 || n > 200 || a.title.isBlank()) return RisiToolResult.error(RisiToolErrorCodes.BAD_ARGS)
        if (!accepted(call, allowedPath = true, gated = gated)) return RisiToolResult.declined()
        val cal = calendar ?: return RisiToolResult.error(RisiToolErrorCodes.CALENDAR_UNAVAILABLE)
        if (!cal.canWrite() || !cal.canRead()) {
            cal.writes.put(CalendarAddRecord(a.writeId, call.requestId, a.title, a.start, a.end, a.allDay, failure = CalendarFailure.NO_PERMISSION.name, at = now()))
            return RisiToolResult(RisiToolResult.NO_PERMISSION, null)
        }
        if (!record(call, null)) return RisiToolResult.declined()
        return when (val r = runCatching { cal.add(a.writeId, call.requestId, a.title, start, end, a.allDay) }.getOrElse { CalendarAddOutcome.Failed(CalendarFailure.INSERT_FAILED) }) {
            is CalendarAddOutcome.Added -> {
                dao.setWriteTarget(a.writeId, r.record.eventId.toString())
                ok(CalendarAddResult.serializer(), CalendarAddResult(r.record.eventId.toString()))
            }
            is CalendarAddOutcome.Failed -> {
                dao.deleteWrite(a.writeId)
                if (r.reason == CalendarFailure.NO_PERMISSION) RisiToolResult(RisiToolResult.NO_PERMISSION, null)
                else RisiToolResult.error(RisiToolErrorCodes.CALENDAR_UNAVAILABLE)
            }
        }
    }

    /** §26.4/§26.6 undo only: deletes an event this phone added for `target_write_id`, if it is still there. */
    private suspend fun calendarRemoveTool(call: RisiToolCall): RisiToolResult {
        val a = call.calendarRemoveArgs() ?: return RisiToolResult.error(RisiToolErrorCodes.BAD_ARGS)
        if (call.undoEntryId == null) return RisiToolResult.declined()
        val notFound = ok(CalendarRemoveResult.serializer(), CalendarRemoveResult(false, "not_found"))
        val w = dao.write(a.targetWriteId)
        if (w == null || w.tool != RisiToolCall.TOOL_CALENDAR_ADD) return notFound
        val eventId = w.target?.toLongOrNull() ?: return notFound
        val cal = calendar ?: return RisiToolResult.error(RisiToolErrorCodes.CALENDAR_UNAVAILABLE)
        if (!cal.canWrite()) return RisiToolResult(RisiToolResult.NO_PERMISSION, null)
        return if (cal.remove(a.targetWriteId, eventId)) ok(CalendarRemoveResult.serializer(), CalendarRemoveResult(true, null)) else notFound
    }
}

private object RisiActions126Local {
    const val CONFIRM = "confirm_write"
    const val CANCEL = "cancel_write"
}

/** Weekday mapping for `EXTRA_DAYS`: ISO 1 = Monday … 7 = Sunday → `java.util.Calendar` constants. */
fun isoToCalendarDay(iso: Int): Int = if (iso == 7) java.util.Calendar.SUNDAY else iso + 1
