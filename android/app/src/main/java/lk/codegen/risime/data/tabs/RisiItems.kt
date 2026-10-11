package lk.codegen.risime.data.tabs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.RisiItem
import lk.codegen.risime.net.RisiItemPatch
import lk.codegen.risime.net.RisiItemReply
import lk.codegen.risime.net.RisiItemsReply
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Contract v1.35 §34.4 (Phase 2): Risi's items on this phone. Settings → Risi skills → Calendar → "Risi's items"
 * lists the upcoming ones (from `GET /risi/items`), grouped by day, each with Open / Edit / Delete as its `actions`
 * allow. A `phone_event_added` or `scheduled_message` belongs to the phone in `ref.device_id`: only that phone opens,
 * edits or deletes it, and it deletes the provider row / cancels the schedule itself (after a confirm dialog), then
 * calls `DELETE` to drop the server row. "Your events" (§34.1) merges the same items, de-duplicated by event id.
 * Titles and the local text of a scheduled message never leave the phone and are never logged.
 */

/** `GET` / `PATCH` / `DELETE /api/v1/risi/items`. */
interface RisiItemsRest {
    suspend fun list(from: String? = null, to: String? = null, kinds: List<String>? = null): ApiResult<RisiItemsReply>

    suspend fun patch(id: String, body: RisiItemPatch): ApiResult<RisiItemReply>

    suspend fun delete(id: String): ApiResult<Unit>
}

class RisiItemsApi(private val api: lk.codegen.risime.net.ApiClient) : RisiItemsRest {
    override suspend fun list(from: String?, to: String?, kinds: List<String>?) = api.risiItems(from, to, kinds)

    override suspend fun patch(id: String, body: RisiItemPatch) = api.patchRisiItem(id, body)

    override suspend fun delete(id: String) = api.deleteRisiItem(id)
}

/** This phone's side of the client-action kinds (null members: not available on this build / in tests). */
interface RisiItemsLocal {
    /** This device's id (the `X-Device-Id`). */
    suspend fun deviceId(): String?

    /** The provider row of a `phone_event_added` this phone created (its own write record has the id). */
    suspend fun ownsPhoneEvent(writeId: String?, eventId: Long): Boolean

    /** Deletes the provider row this phone created (false: not deleted). */
    suspend fun deletePhoneEvent(writeId: String, eventId: Long): Boolean

    /** A pending schedule's local text on this phone (null: not here). */
    suspend fun scheduledText(scheduleId: String): String?

    /** Cancels this phone's schedule (false: not cancelled). */
    suspend fun cancelSchedule(scheduleId: String): Boolean

    /** The bubble's local Edit (§26.6). */
    suspend fun editSchedule(scheduleId: String, text: String): Boolean

    /** A promise's Edit: the §27.5 `item_edit` (sent in the Risi chat; false: not sent). */
    suspend fun editPromise(itemId: String, text: String, due: String?): Boolean = false
}

object RisiItems {
    const val TITLE = "Risi's items"
    const val BLURB = "Everything Risi set up for you: events, reminders, scheduled messages and promises."
    const val EMPTY = "Nothing coming up that Risi set up for you."
    const val NO_DATE = "No date"
    const val OPEN = "Open"
    const val EDIT = "Edit"
    const val DELETE = "Delete"
    const val CANCEL = "Cancel"
    const val SAVE = "Save"
    const val OTHER_PHONE = "On your other phone"
    const val SCHEDULED_MESSAGE = "Scheduled message"
    const val DELETED = "Deleted"
    const val DELETE_FAILED = "Couldn't delete it. Try again."
    const val SAVE_FAILED = "Couldn't save it. Try again."
    const val PHONE_EVENT_GONE = "This event is no longer on this phone"
    const val EDIT_REMINDER_TITLE = "Edit reminder"
    const val EDIT_MESSAGE_TITLE = "Edit scheduled message"

    /** The label each kind's line starts with (bold header of the row). */
    fun kindLabel(kind: String): String = when (kind) {
        RisiItem.PHONE_EVENT_ADDED -> "Phone calendar"
        RisiItem.RISI_CALENDAR_EVENT -> "Risi Calendar"
        RisiItem.REMINDER -> "Reminder"
        RisiItem.SCHEDULED_MESSAGE -> SCHEDULED_MESSAGE
        RisiItem.PROMISE -> "Your promise"
        RisiItem.FOLLOW_UP -> "Following up"
        else -> "Item"
    }

    /** The confirm dialog's question. */
    fun deleteQuestion(item: RisiItem): String = when (item.kind) {
        RisiItem.PHONE_EVENT_ADDED -> "Delete this event from your phone's calendar?"
        RisiItem.RISI_CALENDAR_EVENT -> "Delete this event from your Risi Calendar?"
        RisiItem.REMINDER -> "Cancel this reminder?"
        RisiItem.SCHEDULED_MESSAGE -> "Cancel this scheduled message? It won't be sent."
        else -> "Delete this item?"
    }

    fun isMine(item: RisiItem, myDeviceId: String?): Boolean =
        myDeviceId != null && item.deviceId?.equals(myDeviceId, ignoreCase = true) == true

    /** Kinds whose source of truth is the phone in `ref.device_id`. */
    fun phoneLocal(item: RisiItem): Boolean = item.kind == RisiItem.PHONE_EVENT_ADDED || item.kind == RisiItem.SCHEDULED_MESSAGE

    /**
     * The buttons this phone shows: `actions` (the server's, for this device), minus what this phone can't do:
     * a phone-local kind only on its own phone; Edit only for kinds this build edits; Delete never for promises.
     */
    fun buttons(item: RisiItem, myDeviceId: String?): List<String> {
        val known = item.kind in RisiItem.KINDS
        if (!known) return emptyList()
        if (phoneLocal(item) && !isMine(item, myDeviceId)) return emptyList()
        return listOf(RisiItem.OPEN, RisiItem.EDIT, RisiItem.DELETE).filter { a ->
            item.can(a) && when (a) {
                RisiItem.DELETE -> item.kind != RisiItem.PROMISE && item.kind != RisiItem.FOLLOW_UP
                RisiItem.EDIT -> item.kind != RisiItem.FOLLOW_UP
                else -> true
            }
        }
    }

    enum class DeleteRoute {
        /** `DELETE` only (the server cancels / deletes: `risi_calendar_event`, `reminder`). */
        SERVER,

        /** This phone deletes its provider row first, then `DELETE`. */
        PHONE_EVENT,

        /** This phone cancels its schedule first, then `DELETE`. */
        SCHEDULE,

        /** Not deletable here. */
        NONE,
    }

    fun deleteRoute(item: RisiItem, myDeviceId: String?): DeleteRoute = when {
        RisiItem.DELETE !in buttons(item, myDeviceId) -> DeleteRoute.NONE
        item.kind == RisiItem.RISI_CALENDAR_EVENT || item.kind == RisiItem.REMINDER -> DeleteRoute.SERVER
        item.kind == RisiItem.PHONE_EVENT_ADDED -> if (item.eventId?.toLongOrNull() != null && item.writeId != null) DeleteRoute.PHONE_EVENT else DeleteRoute.NONE
        item.kind == RisiItem.SCHEDULED_MESSAGE -> if (item.scheduleId != null) DeleteRoute.SCHEDULE else DeleteRoute.NONE
        else -> DeleteRoute.NONE
    }

    private fun ms(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /** Upcoming: no start, or not yet ended (an item without an end is upcoming until its start). */
    fun upcoming(items: List<RisiItem>, nowMs: Long): List<RisiItem> = items.filter { i ->
        val s = ms(i.start) ?: return@filter true
        (ms(i.end) ?: s) >= nowMs
    }

    /** Sorted by start, items without a start last (the server's order, kept stable). */
    fun sorted(items: List<RisiItem>): List<RisiItem> =
        items.withIndex().sortedWith(compareBy({ ms(it.value.start) == null }, { ms(it.value.start) ?: 0L }, { it.index })).map { it.value }

    /** Grouped by local day ("Mon 12 Oct"); the items without a date under "No date", last. */
    fun groupByDay(items: List<RisiItem>, zone: ZoneId, locale: Locale = Locale.getDefault(), dayPattern: String = LocalEvents.DEFAULT_DAY_PATTERN): List<Pair<String, List<RisiItem>>> {
        val df = DateTimeFormatter.ofPattern(dayPattern, locale)
        val out = LinkedHashMap<String, MutableList<RisiItem>>()
        for (i in sorted(items)) {
            val key = ms(i.start)?.let { Instant.ofEpochMilli(it).atZone(zone).format(df) } ?: NO_DATE
            out.getOrPut(key) { mutableListOf() } += i
        }
        return out.entries.map { it.key to it.value.toList() }
    }

    /** "10:00–11:00", "06:00 (every day)", "All day"; null without a start. */
    fun timeText(item: RisiItem, zone: ZoneId, use24h: Boolean = true, locale: Locale = Locale.getDefault()): String? {
        val s = ms(item.start) ?: return null
        if (item.allDay) return LocalEvents.ALL_DAY
        val tf = DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a", locale)
        val t0 = Instant.ofEpochMilli(s).atZone(zone).format(tf)
        val e = ms(item.end)
        val base = if (e != null && e > s) "$t0–${Instant.ofEpochMilli(e).atZone(zone).format(tf)}" else t0
        return if (item.repeat == "daily") "$base (every day)" else base
    }

    /**
     * The row's text (single line, ellipsized by the UI): the title, or for a `scheduled_message` its local text on
     * its own phone ("On your other phone" elsewhere; `title` is always null on the wire).
     */
    fun titleText(item: RisiItem, myDeviceId: String?, localText: String?): String = when (item.kind) {
        RisiItem.SCHEDULED_MESSAGE -> if (isMine(item, myDeviceId)) localText?.trim()?.takeIf { it.isNotEmpty() } ?: SCHEDULED_MESSAGE else OTHER_PHONE
        else -> item.title?.trim()?.takeIf { it.isNotEmpty() } ?: LocalEvents.NO_TITLE
    }

    /** The calendar line of an event ("Work (Google)"), or null. */
    fun calendarText(item: RisiItem): String? = item.calendar?.let { c -> if (c.account.isNullOrBlank()) c.name else "${c.name} (${c.account})" }

    /**
     * v1.35 §34.1 the items as "Your events" rows for [fromMs, toMs): only those with a start in range. A
     * `phone_event_added` of this phone carries its provider id (dropped when the provider already shows it); one
     * of another phone carries none (provider ids are per phone). A `risi_calendar_event` carries its event id.
     */
    fun toLocalEvents(items: List<RisiItem>, fromMs: Long, toMs: Long, myDeviceId: String?, localText: (String) -> String? = { null }): List<LocalEvent> = items.mapNotNull { i ->
        if (i.kind !in RisiItem.KINDS) return@mapNotNull null
        val b = ms(i.start) ?: return@mapNotNull null
        val e = maxOf(ms(i.end) ?: b, b)
        if (!InstanceFilter.overlaps(b, maxOf(e, b + 1), fromMs, toMs)) return@mapNotNull null
        val id = when (i.kind) {
            RisiItem.PHONE_EVENT_ADDED -> if (isMine(i, myDeviceId)) i.eventId else null
            RisiItem.RISI_CALENDAR_EVENT -> i.eventId
            else -> null
        }
        val title = when (i.kind) {
            RisiItem.PHONE_EVENT_ADDED, RisiItem.RISI_CALENDAR_EVENT -> titleText(i, myDeviceId, null)
            RisiItem.SCHEDULED_MESSAGE -> SCHEDULED_MESSAGE + ": " + titleText(i, myDeviceId, i.scheduleId?.let(localText))
            else -> kindLabel(i.kind) + ": " + titleText(i, myDeviceId, null)
        }
        LocalEvent(title, b, e, i.allDay, risi = i.kind == RisiItem.RISI_CALENDAR_EVENT, eventId = id, itemKind = i.kind)
    }
}

/** One screen's state. */
data class RisiItemsUi(
    val loading: Boolean = true,
    val items: List<RisiItem> = emptyList(),
    val error: String? = null,
    val note: String? = null,
    /** Ids being deleted or saved (their buttons are off). */
    val busy: Set<String> = emptySet(),
    val deviceId: String? = null,
    /** schedule_id → this phone's local text. */
    val localTexts: Map<String, String> = emptyMap(),
)

/** Settings → Risi skills → Calendar → "Risi's items": the upcoming list and Delete / Edit. */
class RisiItemsModel(
    private val rest: RisiItemsRest,
    private val local: RisiItemsLocal,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val errorText: (ApiResult<*>?) -> String = { "Couldn't load Risi's items." },
) {
    private val _state = MutableStateFlow(RisiItemsUi())
    val state: StateFlow<RisiItemsUi> = _state.asStateFlow()

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            val device = runCatching { local.deviceId() }.getOrNull()
            val from = Instant.ofEpochMilli(now()).toString()
            when (val r = runCatching { rest.list(from = from) }.getOrNull()) {
                is ApiResult.Ok -> {
                    val items = RisiItems.sorted(RisiItems.upcoming(r.value.items, now()))
                    val texts = HashMap<String, String>()
                    items.filter { it.kind == RisiItem.SCHEDULED_MESSAGE && RisiItems.isMine(it, device) }.forEach { i ->
                        val sid = i.scheduleId ?: return@forEach
                        runCatching { local.scheduledText(sid) }.getOrNull()?.let { texts[sid] = it }
                    }
                    _state.update { it.copy(loading = false, items = items, error = null, deviceId = device, localTexts = texts) }
                }
                else -> _state.update { it.copy(loading = false, error = errorText(r), deviceId = device) }
            }
        }
    }

    private fun setBusy(id: String, on: Boolean) = _state.update { s -> s.copy(busy = if (on) s.busy + id else s.busy - id) }

    /**
     * Delete after the confirm dialog, routed by kind (§34.4): a phone event or a schedule is deleted / cancelled
     * on this phone first; then `DELETE` drops the row. The row leaves the list once that is done.
     */
    fun delete(item: RisiItem) {
        val device = _state.value.deviceId
        val route = RisiItems.deleteRoute(item, device)
        if (route == RisiItems.DeleteRoute.NONE || item.id in _state.value.busy) return
        setBusy(item.id, true)
        scope.launch {
            val localOk = when (route) {
                RisiItems.DeleteRoute.PHONE_EVENT -> {
                    val ev = item.eventId!!.toLong()
                    // §26.4: only a row in this phone's own record of what it created.
                    runCatching { local.ownsPhoneEvent(item.writeId, ev) && local.deletePhoneEvent(item.writeId!!, ev) }.getOrDefault(false)
                }
                RisiItems.DeleteRoute.SCHEDULE -> runCatching { local.cancelSchedule(item.scheduleId!!) }.getOrDefault(false)
                else -> true
            }
            if (!localOk) {
                _state.update { it.copy(busy = it.busy - item.id, note = RisiItems.DELETE_FAILED) }
                return@launch
            }
            val r = runCatching { rest.delete(item.id) }.getOrNull()
            // A row already gone on the server (404) is gone here too.
            val ok = r is ApiResult.Ok || (r is ApiResult.Error && r.httpStatus == 404)
            _state.update { s ->
                if (ok) s.copy(items = s.items.filterNot { it.id == item.id }, busy = s.busy - item.id, note = RisiItems.DELETED)
                else s.copy(busy = s.busy - item.id, note = RisiItems.DELETE_FAILED)
            }
        }
    }

    /** A reminder's Edit: `PATCH` with the new time and/or text. */
    fun editReminder(item: RisiItem, atIso: String?, text: String?) {
        if (item.kind != RisiItem.REMINDER) return
        val body = RisiItemPatch.of(atIso, text) ?: return
        setBusy(item.id, true)
        scope.launch {
            val r = runCatching { rest.patch(item.id, body) }.getOrNull()
            _state.update { s ->
                if (r is ApiResult.Ok) s.copy(items = RisiItems.sorted(s.items.map { if (it.id == item.id) r.value.item else it }), busy = s.busy - item.id, note = null)
                else s.copy(busy = s.busy - item.id, note = RisiItems.SAVE_FAILED)
            }
        }
    }

    /** A scheduled message's Edit: the local text on this phone (§26.6); nothing goes to the server. */
    fun editScheduled(item: RisiItem, text: String) {
        val sid = item.scheduleId ?: return
        if (!RisiItems.isMine(item, _state.value.deviceId)) return
        setBusy(item.id, true)
        scope.launch {
            val ok = runCatching { local.editSchedule(sid, text) }.getOrDefault(false)
            _state.update { s ->
                if (ok) s.copy(localTexts = s.localTexts + (sid to text.trim()), busy = s.busy - item.id, note = null)
                else s.copy(busy = s.busy - item.id, note = RisiItems.SAVE_FAILED)
            }
        }
    }

    /** A promise's Edit (§27.5 `item_edit`); the row shows the new text once it is sent. */
    fun editPromise(item: RisiItem, text: String, due: String?) {
        if (item.kind != RisiItem.PROMISE) return
        val t = text.trim().takeIf { it.isNotEmpty() } ?: return
        setBusy(item.id, true)
        scope.launch {
            val ok = runCatching { local.editPromise(item.itemId ?: item.id, t, due) }.getOrDefault(false)
            _state.update { s ->
                if (ok) s.copy(items = RisiItems.sorted(s.items.map { if (it.id == item.id) it.copy(title = t, start = due ?: it.start) else it }), busy = s.busy - item.id, note = null)
                else s.copy(busy = s.busy - item.id, note = RisiItems.SAVE_FAILED)
            }
        }
    }

    fun noteShown() = _state.update { it.copy(note = null) }
}
