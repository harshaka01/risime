package lk.codegen.risime.data.calendar

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import lk.codegen.risime.data.db.RisiCalendarDao
import lk.codegen.risime.data.db.RisiCalendarEventEntity
import lk.codegen.risime.data.db.RisiCalendarStateEntity
import lk.codegen.risime.net.ApiClient
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiCalendarBodies
import lk.codegen.risime.net.RisiCalendarCard
import lk.codegen.risime.net.RisiCalendarChangesReply
import lk.codegen.risime.net.RisiCalendarEventsReply
import lk.codegen.risime.net.RisiCalendarSettings
import lk.codegen.risime.net.RisiCalendarSettingsReply
import lk.codegen.risime.net.RisiEvent
import lk.codegen.risime.net.RisiEventParticipant
import lk.codegen.risime.net.RisiEventReply
import lk.codegen.risime.net.RisiKinds129
import java.time.Instant

/*
 * v1.29 §29.3/§29.6 the phone's side of Risi Calendar: a local cache (Room `risi_calendar_cache`) kept in
 * step with the server by a range list and the cursor change feed. The content-free inbox event
 * `risi_calendar_changed` (and app start, and opening the Calendar tab) triggers a sync; `410
 * cursor_expired` re-lists. Writes are versioned PATCHes; a `409 version_conflict` reloads the event.
 */

/** §29.3 REST as the calendar needs it (the app's [ApiClient]; a fake in tests). */
interface RisiCalendarRemote {
    suspend fun events(from: String, to: String): ApiResult<RisiCalendarEventsReply>
    suspend fun changes(since: String, limit: Int): ApiResult<RisiCalendarChangesReply>
    suspend fun event(id: String): ApiResult<RisiEventReply>
    suspend fun create(body: JsonObject): ApiResult<RisiEventReply>
    suspend fun patch(id: String, body: JsonObject): ApiResult<RisiEventReply>
    suspend fun delete(id: String): ApiResult<Unit>
    suspend fun respond(id: String, body: JsonObject): ApiResult<RisiEventReply>
    suspend fun resolve(suggestionId: String, body: JsonObject): ApiResult<RisiEventReply>
    suspend fun settings(): ApiResult<RisiCalendarSettingsReply>
    suspend fun patchSettings(body: JsonObject): ApiResult<RisiCalendarSettingsReply>
}

class ApiCalendarRemote(private val api: ApiClient) : RisiCalendarRemote {
    override suspend fun events(from: String, to: String) = api.risiCalendarEvents(from, to)
    override suspend fun changes(since: String, limit: Int) = api.risiCalendarChanges(since, limit)
    override suspend fun event(id: String) = api.risiCalendarEvent(id)
    override suspend fun create(body: JsonObject) = api.createRisiCalendarEvent(body)
    override suspend fun patch(id: String, body: JsonObject) = api.patchRisiCalendarEvent(id, body)
    override suspend fun delete(id: String) = api.deleteRisiCalendarEvent(id)
    override suspend fun respond(id: String, body: JsonObject) = api.respondRisiCalendarEvent(id, body)
    override suspend fun resolve(suggestionId: String, body: JsonObject) = api.resolveRisiCalendarSuggestion(suggestionId, body)
    override suspend fun settings() = api.risiCalendarSettings()
    override suspend fun patchSettings(body: JsonObject) = api.patchRisiCalendarSettings(body)
}

/** The local cache (Room in the app; in memory in tests). */
interface RisiCalendarStore {
    val events: Flow<List<RisiEvent>>
    suspend fun all(): List<RisiEvent>
    suspend fun upsert(events: List<RisiEvent>)
    suspend fun remove(ids: List<String>)
    /** A re-list: the cache becomes exactly [events], the feed continues from [cursor]. */
    suspend fun replaceAll(events: List<RisiEvent>, cursor: String?)
    suspend fun cursor(): String?
    suspend fun setCursor(cursor: String?)
}

object RisiEventRows {
    fun ms(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    fun toRow(e: RisiEvent): RisiCalendarEventEntity? {
        val s = ms(e.start) ?: return null
        val end = ms(e.end) ?: return null
        return RisiCalendarEventEntity(e.eventId.lowercase(), s, end, e.version, ProtocolJson.encodeToString(RisiEvent.serializer(), e))
    }

    fun fromRow(r: RisiCalendarEventEntity): RisiEvent? = runCatching { ProtocolJson.decodeFromString(RisiEvent.serializer(), r.json) }.getOrNull()
}

class RoomCalendarStore(private val dao: RisiCalendarDao, private val tx: suspend (suspend () -> Unit) -> Unit) : RisiCalendarStore {
    override val events: Flow<List<RisiEvent>> = dao.observeAll().map { rows -> rows.mapNotNull(RisiEventRows::fromRow) }
    override suspend fun all() = dao.all().mapNotNull(RisiEventRows::fromRow)
    override suspend fun upsert(events: List<RisiEvent>) = dao.upsert(events.mapNotNull(RisiEventRows::toRow))
    override suspend fun remove(ids: List<String>) = dao.remove(ids.map { it.lowercase() })
    override suspend fun replaceAll(events: List<RisiEvent>, cursor: String?) = tx {
        dao.clearEvents()
        dao.upsert(events.mapNotNull(RisiEventRows::toRow))
        dao.setState(RisiCalendarStateEntity(0, cursor))
    }
    override suspend fun cursor() = dao.cursor()
    override suspend fun setCursor(cursor: String?) = dao.setState(RisiCalendarStateEntity(0, cursor))
}

class MemoryCalendarStore : RisiCalendarStore {
    private val rows = MutableStateFlow<Map<String, RisiEvent>>(emptyMap())
    private var cur: String? = null
    override val events: Flow<List<RisiEvent>> = rows.map { m -> m.values.sortedBy { RisiEventRows.ms(it.start) } }
    override suspend fun all() = rows.value.values.toList()
    override suspend fun upsert(events: List<RisiEvent>) { rows.value = rows.value + events.associateBy { it.eventId.lowercase() } }
    override suspend fun remove(ids: List<String>) { rows.value = rows.value - ids.map { it.lowercase() }.toSet() }
    override suspend fun replaceAll(events: List<RisiEvent>, cursor: String?) { rows.value = events.associateBy { it.eventId.lowercase() }; cur = cursor }
    override suspend fun cursor() = cur
    override suspend fun setCursor(cursor: String?) { cur = cursor }
}

/** What the Risi cards use of the calendar (the local cache for the timeline, and the REST answers). */
interface RisiCalendarCardsPort {
    val events: StateFlow<List<RisiEvent>>

    suspend fun respond(eventId: String, response: String, version: Int?, expectStart: String?, expectEnd: String?, suggest: Triple<String, String, Boolean>? = null): CalendarResult

    suspend fun resolve(suggestionId: String, action: String): CalendarResult

    suspend fun delete(eventId: String): CalendarResult

    /** The Calendar tab at this event. */
    fun open(eventId: String)
}

/** The outcome of a calendar write. */
sealed interface CalendarResult {
    data class Ok(val event: RisiEvent?) : CalendarResult

    /** `409 version_conflict`: the event changed elsewhere; [current] is the reloaded event (null: gone). */
    data class Conflict(val current: RisiEvent?) : CalendarResult

    data class Failed(val code: String) : CalendarResult
}

/** How a sync ended. */
enum class SyncOutcome { SYNCED, RELISTED, FAILED, OFF }

class RisiCalendar(
    private val remote: RisiCalendarRemote,
    private val store: RisiCalendarStore,
    /** The switch, the advertisement and the prerequisites: only a `risi_events` device calls the REST. */
    private val enabled: () -> Boolean,
    private val me: suspend () -> String?,
    private val zone: () -> String = { java.time.ZoneId.systemDefault().id },
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val log: (String) -> Unit = {},
    loadShowDeclined: () -> Boolean = { false },
    private val saveShowDeclined: (Boolean) -> Unit = {},
    /** v1.31 §31.6: the cache changed (a feed page, a re-list, an own write); [removed] are events that left it. Must not block. */
    private val onCacheChanged: (removed: List<String>) -> Unit = {},
) {
    val events: Flow<List<RisiEvent>> = store.events

    /** The cache as it is now (v1.31 §31.6: the copy job's input). */
    suspend fun eventsNow(): List<RisiEvent> = store.all()

    /** §29.2 the phone setting "Show declined". */
    private val _showDeclined = MutableStateFlow(loadShowDeclined())
    val showDeclined: StateFlow<Boolean> = _showDeclined.asStateFlow()

    fun setShowDeclined(on: Boolean) {
        _showDeclined.value = on
        saveShowDeclined(on)
    }

    private val _settings = MutableStateFlow(RisiCalendarSettings())
    val settings: StateFlow<RisiCalendarSettings> = _settings.asStateFlow()

    /** A card's [Open] asked the Calendar tab to show this event (taken once by the tab). */
    private val _focus = MutableStateFlow<String?>(null)
    val focus: StateFlow<String?> = _focus.asStateFlow()

    fun requestFocus(eventId: String) { _focus.value = eventId.lowercase() }

    fun takeFocus(): String? = _focus.value.also { _focus.value = null }

    private val lock = Mutex()

    /** The range a re-list covers (≤ 92 days): 30 days back, 62 ahead. */
    fun window(): Pair<String, String> {
        val n = Instant.ofEpochMilli(now())
        return iso(n.minusSeconds(WINDOW_BACK_DAYS * 86_400)) to iso(n.plusSeconds(WINDOW_AHEAD_DAYS * 86_400))
    }

    /** Range listed from an older month or a later one (upserts only; the feed cursor is untouched). */
    suspend fun loadRange(fromMs: Long, toMs: Long): Boolean {
        if (!enabled()) return false
        val to = minOf(toMs, fromMs + MAX_RANGE_DAYS * 86_400_000L)
        val r = remote.events(iso(Instant.ofEpochMilli(fromMs)), iso(Instant.ofEpochMilli(to)))
        if (r !is ApiResult.Ok) return false
        store.upsert(r.value.events)
        return true
    }

    /**
     * §29.6: no cursor → list the window; else the change feed until `has_more` is false; `410
     * cursor_expired` → list again and continue from that reply's cursor. One at a time.
     */
    suspend fun sync(): SyncOutcome {
        if (!enabled()) return SyncOutcome.OFF
        return lock.withLock {
            val cursor = store.cursor()
            if (cursor == null) relist() else feed(cursor)
        }
    }

    /** The inbox event: nothing to do when this phone is already at [cursor]. */
    suspend fun onChanged(cursor: String?): SyncOutcome {
        if (!enabled()) return SyncOutcome.OFF
        if (cursor != null && cursor == store.cursor()) return SyncOutcome.SYNCED
        return sync()
    }

    private suspend fun relist(): SyncOutcome {
        val (from, to) = window()
        return when (val r = remote.events(from, to)) {
            is ApiResult.Ok -> {
                val before = runCatching { store.all().map { it.eventId.lowercase() } }.getOrDefault(emptyList())
                store.replaceAll(r.value.events, r.value.cursor)
                val now = r.value.events.map { it.eventId.lowercase() }.toSet()
                runCatching { onCacheChanged(before.filter { it !in now }) }
                log("risi_calendar: listed ${r.value.events.size}")
                SyncOutcome.RELISTED
            }
            is ApiResult.Error -> SyncOutcome.FAILED.also { log("risi_calendar: list failed ${r.code}") }
            is ApiResult.NetworkError -> SyncOutcome.FAILED
        }
    }

    private suspend fun feed(start: String): SyncOutcome {
        var cursor = start
        repeat(MAX_PAGES) {
            when (val r = remote.changes(cursor, PAGE)) {
                is ApiResult.Ok -> {
                    apply(r.value)
                    r.value.cursor?.let { cursor = it; store.setCursor(it) }
                    if (!r.value.hasMore) return SyncOutcome.SYNCED
                }
                is ApiResult.Error ->
                    return if (r.httpStatus == 410 || r.code == "cursor_expired") relist() else SyncOutcome.FAILED.also { log("risi_calendar: changes failed ${r.code}") }
                is ApiResult.NetworkError -> return SyncOutcome.FAILED
            }
        }
        return SyncOutcome.SYNCED
    }

    /** Changes are coalesced per event and ordered: the latest view wins; `removed` drops it. */
    private suspend fun apply(page: RisiCalendarChangesReply) {
        val removed = page.changes.filter { it.removed || it.event == null }.mapNotNull { it.id }
        val events = page.changes.filter { !it.removed }.mapNotNull { it.event }
        if (removed.isNotEmpty()) store.remove(removed)
        if (events.isNotEmpty()) store.upsert(events)
        if (removed.isNotEmpty() || events.isNotEmpty()) runCatching { onCacheChanged(removed.map { it.lowercase() }) }
    }

    /**
     * §29.10 an `event_update` card: applied silently to a cached event it is newer than (title, time,
     * participants, state); the caller's own status follows its participant row. Then a sync fetches the
     * full view.
     */
    suspend fun applyUpdate(card: RisiCalendarCard): Boolean {
        if (card.kind != RisiKinds129.EVENT_UPDATE) return false
        val id = card.eventId?.lowercase() ?: return false
        val cached = store.all().firstOrNull { it.eventId.equals(id, true) } ?: return false
        val v = card.version ?: return false
        if (v <= cached.version) return false
        val parts = if (card.participants.isEmpty()) cached.participants else card.participants.map { p ->
            val old = cached.participants.firstOrNull { it.userId.equals(p.userId, true) }
            RisiEventParticipant(p.userId, p.status, if (old?.status == p.status) old.respondedAt else null)
        }
        val mine = me()?.let { m -> parts.firstOrNull { it.userId.equals(m, true) }?.status } ?: cached.myStatus
        store.upsert(listOf(cached.copy(
            version = v, title = card.title ?: cached.title, start = card.start ?: cached.start, end = card.end ?: cached.end,
            allDay = if (card.start != null) card.allDay else cached.allDay, participants = parts, myStatus = mine,
            state = card.state ?: if (card.change == "cancelled") RisiEvent.STATE_CANCELLED else cached.state,
        )))
        runCatching { onCacheChanged(emptyList()) }
        return true
    }

    // ---- writes ----

    private suspend fun result(r: ApiResult<RisiEventReply>): CalendarResult = when (r) {
        is ApiResult.Ok -> { store.upsert(listOf(r.value.event)); runCatching { onCacheChanged(emptyList()) }; CalendarResult.Ok(r.value.event) }
        is ApiResult.Error -> CalendarResult.Failed(r.code)
        is ApiResult.NetworkError -> CalendarResult.Failed("network")
    }

    /** Reloads one event after a 409 (or a 404: removed from the cache). */
    private suspend fun reload(id: String): RisiEvent? = when (val r = remote.event(id)) {
        is ApiResult.Ok -> r.value.event.also { store.upsert(listOf(it)) }
        is ApiResult.Error -> { if (r.httpStatus == 404) store.remove(listOf(id)); null }
        is ApiResult.NetworkError -> null
    }

    /** A new event from the Calendar tab (`created_by: user`: accepted for me, proposed for [with]). */
    suspend fun create(title: String, startMs: Long, endMs: Long, allDay: Boolean, with: List<String> = emptyList(), reminderMin: Int? = settings.value.defaultReminderMin): CalendarResult {
        if (!enabled()) return CalendarResult.Failed("agent_unavailable")
        val body = RisiCalendarBodies.create(newId(), title.trim(), iso(Instant.ofEpochMilli(startMs)), iso(Instant.ofEpochMilli(endMs)), allDay, zone(), with, reminderMin, sendReminder = true)
        return result(remote.create(body))
    }

    /** A versioned PATCH ([body] from [RisiCalendarBodies.patch]); a stale version reloads the event. */
    suspend fun patch(eventId: String, body: JsonObject): CalendarResult {
        if (!enabled()) return CalendarResult.Failed("agent_unavailable")
        return when (val r = remote.patch(eventId, body)) {
            is ApiResult.Error -> if (r.httpStatus == 409 || r.code == "version_conflict") CalendarResult.Conflict(reload(eventId)) else CalendarResult.Failed(r.code)
            else -> result(r)
        }
    }

    /** Owner: cancelled for everyone; participant: declined and gone from my calendar (§29.4). */
    suspend fun delete(event: RisiEvent): CalendarResult {
        if (!enabled()) return CalendarResult.Failed("agent_unavailable")
        return when (val r = remote.delete(event.eventId)) {
            is ApiResult.Ok -> {
                if (event.isOwner(me())) store.upsert(listOf(event.copy(state = RisiEvent.STATE_CANCELLED))) else store.remove(listOf(event.eventId))
                runCatching { onCacheChanged(if (event.isOwner(me())) emptyList() else listOf(event.eventId.lowercase())) }
                CalendarResult.Ok(null)
            }
            is ApiResult.Error -> if (r.httpStatus == 404) { store.remove(listOf(event.eventId)); CalendarResult.Ok(null) } else CalendarResult.Failed(r.code)
            is ApiResult.NetworkError -> CalendarResult.Failed("network")
        }
    }

    /**
     * accept | decline | suggest. [version] is the card's or the cache's; on a 409 the event is reloaded
     * and the answer is sent again once when its time still matches what the user saw ([expectStart]/[expectEnd]).
     */
    suspend fun respond(
        eventId: String, response: String, version: Int?, expectStart: String? = null, expectEnd: String? = null,
        suggest: Triple<String, String, Boolean>? = null, reminderMin: Int? = null, sendReminder: Boolean = false,
    ): CalendarResult {
        if (!enabled()) return CalendarResult.Failed("agent_unavailable")
        val v = version ?: store.all().firstOrNull { it.eventId.equals(eventId, true) }?.version ?: reload(eventId)?.version ?: return CalendarResult.Failed("not_found")
        val first = remote.respond(eventId, RisiCalendarBodies.respond(response, v, suggest, reminderMin, sendReminder))
        if (first is ApiResult.Error && (first.httpStatus == 409 || first.code == "version_conflict")) {
            val cur = reload(eventId) ?: return CalendarResult.Conflict(null)
            val same = (expectStart == null || sameInstant(expectStart, cur.start)) && (expectEnd == null || sameInstant(expectEnd, cur.end))
            if (!same || cur.cancelled) return CalendarResult.Conflict(cur)
            return result(remote.respond(eventId, RisiCalendarBodies.respond(response, cur.version, suggest, reminderMin, sendReminder)))
        }
        return result(first)
    }

    /** The owner's [Use] / [Keep] on a suggestion. */
    suspend fun resolve(suggestionId: String, action: String): CalendarResult {
        if (!enabled()) return CalendarResult.Failed("agent_unavailable")
        return result(remote.resolve(suggestionId, RisiCalendarBodies.resolve(action)))
    }

    /** My own reminder on an event (anyone; null = none). */
    suspend fun setReminder(event: RisiEvent, minutes: Int?): CalendarResult =
        patch(event.eventId, RisiCalendarBodies.patch(event.version, reminderMin = minutes, sendReminder = true))

    suspend fun loadSettings() {
        if (!enabled()) return
        (remote.settings() as? ApiResult.Ok)?.let { _settings.value = it.value.settings }
    }

    suspend fun saveSettings(s: RisiCalendarSettings): Boolean {
        if (!enabled()) return false
        val r = remote.patchSettings(RisiCalendarBodies.settings(s))
        if (r is ApiResult.Ok) _settings.value = r.value.settings
        return r is ApiResult.Ok
    }

    companion object {
        const val PAGE = 500
        const val MAX_PAGES = 50
        const val WINDOW_BACK_DAYS = 30L
        const val WINDOW_AHEAD_DAYS = 62L
        const val MAX_RANGE_DAYS = 92L

        private val ISO = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)

        fun iso(i: Instant): String = ISO.format(i)

        fun sameInstant(a: String, b: String): Boolean = RisiEventRows.ms(a) != null && RisiEventRows.ms(a) == RisiEventRows.ms(b)

        /** Error codes as the user reads them. */
        fun errorText(code: String): String = when (code) {
            "not_owner" -> "Only the person who made this event can change it."
            "not_invitable" -> "That person can't be invited (you share no chat)."
            "version_conflict" -> "This event changed elsewhere. Showing the latest."
            "agent_unavailable" -> "Risi Calendar isn't available right now."
            "rate_limited" -> "Too many changes. Try again in a minute."
            "not_found" -> "This event is no longer in your calendar."
            "network" -> "No connection. Try again."
            else -> "Couldn't update the calendar."
        }
    }
}
