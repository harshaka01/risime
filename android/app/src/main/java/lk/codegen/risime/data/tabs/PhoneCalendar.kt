package lk.codegen.risime.data.tabs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import lk.codegen.risime.net.CalendarBlock
import lk.codegen.risime.net.ProtocolJson
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/*
 * Contract v1.25 §25.3 / v1.26 §26.6 (P0, Harsha's phone): the phone's real calendar behind Risi's
 * client tools. `calendar_check` returns merged free/busy blocks only; `calendar_add` writes into a
 * synced Google account calendar (ACCOUNT_TYPE com.google, visible, CAL_ACCESS ≥ CONTRIBUTOR) — a local
 * "Phone" calendar only when no Google calendar exists and the user picked it — then reads the event
 * back to verify it; `calendar_remove` (undo) deletes only an event this phone added. The Android
 * provider sits behind [CalendarBackend] so the logic is tested without a device.
 */

/** One calendar as the provider lists it (`CalendarContract.Calendars`). */
data class PhoneCalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    /** `CALENDAR_ACCESS_LEVEL`. */
    val accessLevel: Int,
    val visible: Boolean,
    val ownerAccount: String? = null,
    val isPrimary: Boolean = false,
    /** `SYNC_EVENTS`: false when the account's sync for this calendar is off (the provider holds no events for it). */
    val syncEvents: Boolean = true,
)

/**
 * One instance in a time window (`CalendarContract.Instances`): only what free/busy needs, plus its
 * calendar (for the per-calendar counts a check reports) and whether that calendar is visible.
 */
data class BusyRow(val begin: Long, val end: Long, val allDay: Boolean, val busy: Boolean, val calendarId: Long = 0, val visible: Boolean = true)

/** An event read back from the provider (`CalendarContract.Events`). */
data class EventRow(val id: Long, val calendarId: Long, val title: String?, val dtStart: Long, val dtEnd: Long, val allDay: Boolean)

/** The provider (Android's `CalendarContract`, or a fake in tests). */
interface CalendarBackend {
    fun canRead(): Boolean

    fun canWrite(): Boolean

    fun calendars(): List<PhoneCalendarInfo>

    /**
     * Instances of every calendar overlapping [fromMs, toMs) (cancelled and declined ones left out), over
     * `CalendarContract.Instances` (recurring events expanded); [BusyRow.visible] says if its calendar is shown.
     */
    fun instances(fromMs: Long, toMs: Long): List<BusyRow>

    /** The new event's id, or null when the provider refused it. */
    fun insert(calendarId: Long, title: String, startMs: Long, endMs: Long, allDay: Boolean, timeZone: String): Long?

    fun event(id: Long): EventRow?

    fun delete(id: Long): Boolean
}

object CalendarSelection {
    const val GOOGLE = "com.google"
    const val LOCAL = "LOCAL"

    /** `CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR`. */
    const val CAL_ACCESS_CONTRIBUTOR = 500

    fun writable(c: PhoneCalendarInfo): Boolean = c.visible && c.accessLevel >= CAL_ACCESS_CONTRIBUTOR

    fun isGoogle(c: PhoneCalendarInfo): Boolean = c.accountType == GOOGLE

    /** The account's own calendar first (primary, or the one owned by the account itself), then by id. */
    private val preferred = compareByDescending<PhoneCalendarInfo> { it.isPrimary || it.ownerAccount.equals(it.accountName, true) }.thenBy { it.id }

    /**
     * What the picker lists: every writable Google calendar; other writable calendars (a local "Phone"
     * calendar, Exchange…) only when there is no Google one. Read-only calendars (Holidays, Birthdays,
     * someone else's shared calendar) never.
     */
    fun pickerOptions(all: List<PhoneCalendarInfo>): List<PhoneCalendarInfo> {
        val w = all.filter(::writable)
        val google = w.filter(::isGoogle)
        return (google.ifEmpty { w }).sortedWith(preferred)
    }

    /** The default without a remembered pick: the account's own Google calendar (never a local one). */
    fun defaultGoogle(all: List<PhoneCalendarInfo>): PhoneCalendarInfo? = all.filter { writable(it) && isGoogle(it) }.sortedWith(preferred).firstOrNull()

    /**
     * Where an event goes: the remembered pick while it is still writable (a local calendar only gets here
     * by the user's explicit pick); else the default Google calendar; else nothing.
     */
    fun target(all: List<PhoneCalendarInfo>, rememberedId: Long?): PhoneCalendarInfo? {
        rememberedId?.let { id -> all.firstOrNull { it.id == id && writable(it) }?.let { return it } }
        return defaultGoogle(all)
    }

    fun typeLabel(accountType: String): String = when (accountType) {
        GOOGLE -> "Google"
        LOCAL -> "Phone only"
        "com.google.android.gm.exchange", "com.android.exchange", "com.microsoft.office.outlook.USER_ACCOUNT" -> "Exchange"
        else -> accountType.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    /** "harsha@example.com · Google" (account name + type), as the picker and Settings show it. */
    fun label(c: PhoneCalendarInfo): String = "${c.accountName.ifBlank { c.displayName }} · ${typeLabel(c.accountType)}"

    /**
     * Proposal 2026-10-09-risi-action-loop §2/§3: the calendar as the server keeps it. `name` is what the
     * success line says ("Added to your Google Calendar: …"): "Google Calendar" for a Google account's
     * calendar, else its own name; `account` the account name.
     */
    fun ref(c: PhoneCalendarInfo): lk.codegen.risime.net.RisiCalendarRef {
        val name = if (isGoogle(c)) {
            if (c.ownerAccount.equals(c.accountName, true) || c.isPrimary || c.displayName.isBlank() || c.displayName.equals(c.accountName, true)) "Google Calendar"
            else "Google Calendar (${c.displayName})"
        } else {
            c.displayName.ifBlank { typeLabel(c.accountType) }
        }
        return lk.codegen.risime.net.RisiCalendarRef(name.take(100), c.accountName.takeIf { it.isNotBlank() }?.take(200))
    }

    /** The card's `confirm.calendar` hint, matched to a writable calendar on this phone (null: none matches). */
    fun matchHint(all: List<PhoneCalendarInfo>, hint: lk.codegen.risime.net.RisiCalendarRef?): PhoneCalendarInfo? {
        hint ?: return null
        return all.filter(::writable).firstOrNull { ref(it).name == hint.name && it.accountName.equals(hint.account ?: "", true) }
    }

    /** The calendar's own name under the label, when it says more than the account ("Work"). */
    fun subLabel(c: PhoneCalendarInfo): String? = c.displayName.takeIf { it.isNotBlank() && !it.equals(c.accountName, true) }
}

/** Why an add failed, kept on the phone and shown on the card (the wire only says `no_permission` / `calendar_unavailable`). */
enum class CalendarFailure(val text: String) {
    NO_PERMISSION("Calendar permission is off on this phone."),
    NO_GOOGLE_CALENDAR("No writable Google calendar on this phone. Add your Google account in Android Settings → Accounts, or choose a calendar."),
    INSERT_FAILED("Your calendar didn't accept the event."),
    VERIFY_MISMATCH("The event didn't read back as it was added, so it was removed again."),
}

/** What this phone added for a `calendar_add` (`write_id` → event), or why it couldn't. Local only. */
@Serializable
data class CalendarAddRecord(
    @SerialName("write_id") val writeId: String,
    @SerialName("request_id") val requestId: String? = null,
    val title: String,
    val start: String,
    val end: String,
    @SerialName("all_day") val allDay: Boolean = false,
    @SerialName("event_id") val eventId: Long? = null,
    @SerialName("calendar_id") val calendarId: Long? = null,
    @SerialName("calendar_name") val calendarName: String? = null,
    @SerialName("calendar_account_type") val accountType: String? = null,
    val failure: String? = null,
    val removed: Boolean = false,
    val at: Long = 0,
) {
    val added: Boolean get() = eventId != null && failure == null
    val failureText: String? get() = failure?.let { f -> CalendarFailure.entries.firstOrNull { it.name == f }?.text ?: f }
}

/** The remembered calendar pick (DataStore in the app). */
interface CalendarChoiceStore {
    val chosen: StateFlow<Long?>

    suspend fun set(id: Long?)
}

class MemoryCalendarChoice(initial: Long? = null) : CalendarChoiceStore {
    private val s = MutableStateFlow(initial)
    override val chosen: StateFlow<Long?> = s.asStateFlow()

    override suspend fun set(id: Long?) {
        s.value = id
    }
}

/** The local record of calendar adds (write_id → event), newest kept, persisted as one JSON value. */
class CalendarWriteLog(
    initialJson: String? = null,
    private val persist: suspend (String) -> Unit = {},
) {
    companion object {
        const val MAX = 300
        private val ser = ListSerializer(CalendarAddRecord.serializer())
    }

    private val _records = MutableStateFlow(
        runCatching { ProtocolJson.decodeFromString(ser, initialJson ?: "[]") }.getOrDefault(emptyList()).associateBy { it.writeId.lowercase() },
    )
    val records: StateFlow<Map<String, CalendarAddRecord>> = _records.asStateFlow()

    fun get(writeId: String): CalendarAddRecord? = _records.value[writeId.lowercase()]

    /** Loads the stored records under any made since start (those win). */
    fun restore(json: String?) {
        val stored = runCatching { ProtocolJson.decodeFromString(ser, json ?: "[]") }.getOrDefault(emptyList()).associateBy { it.writeId.lowercase() }
        _records.update { stored + it }
    }

    suspend fun put(r: CalendarAddRecord) {
        _records.update { m -> (m + (r.writeId.lowercase() to r)).values.sortedByDescending { it.at }.take(MAX).associateBy { it.writeId.lowercase() } }
        runCatching { persist(ProtocolJson.encodeToString(ser, _records.value.values.toList())) }
    }
}

/** Settings → Calendar → Details: each calendar with its instances in the next days, and the read verdict. */
data class CalendarOverview(val calendars: List<Pair<PhoneCalendarInfo, Int>>, val readOk: Boolean, val reason: String?)

/** What a `calendar_check` read: the merged blocks and the `phone_provider` source report. */
data class CalendarRead(val blocks: List<CalendarBlock>, val source: lk.codegen.risime.net.CalendarSourceReport) {
    companion object {
        const val SOURCE = "phone_provider"
        const val MAX_CALENDARS = 30
        const val NO_CALENDARS = "no_calendars"
        const val NO_GOOGLE_CALENDAR = "no_google_calendar"
        const val GOOGLE_SYNC_OFF = "google_sync_off"
        const val GOOGLE_HIDDEN = "google_calendars_hidden"
        const val QUERY_FAILED = "query_failed"

        fun source(cals: List<lk.codegen.risime.net.CalendarSourceCalendar>, ok: Boolean, reason: String?) =
            lk.codegen.risime.net.CalendarSourceReport(SOURCE, cals, ok, if (ok) null else reason)

        const val GOOGLE_API = "google_api"

        /** v1.29 §29.2 (root alignment): the direct Google Calendar connection isn't built yet. */
        val GOOGLE_API_NOT_CONNECTED = lk.codegen.risime.net.CalendarSourceReport(GOOGLE_API, emptyList(), false, "not_connected")

        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+$")

        /** The calendar's name as this phone shows it (Settings; never sent). */
        fun nameOf(c: PhoneCalendarInfo): String = c.displayName.ifBlank { c.accountName }.ifBlank { CalendarSelection.typeLabel(c.accountType) }.take(100)

        /** The name a check sends: an email address (the account's own calendar) becomes "Primary calendar". */
        fun wireName(c: PhoneCalendarInfo): String = nameOf(c).let { if (EMAIL.matches(it.trim())) "Primary calendar" else it }

        /** v1.29 §29.2 reason codes on the wire (the precise reason stays on the phone, for Settings). */
        fun wireReason(r: String?): String? = when (r) {
            null -> null
            NO_CALENDARS, NO_GOOGLE_CALENDAR, GOOGLE_SYNC_OFF, GOOGLE_HIDDEN -> "no_calendars"
            QUERY_FAILED -> "api_error"
            else -> r
        }

        /**
         * Can a read of these calendars be trusted? Only with a Google account calendar that syncs events
         * to this phone and is shown; else the exact reason.
         */
        fun verdict(cals: List<PhoneCalendarInfo>): Pair<Boolean, String?> {
            val google = cals.filter(CalendarSelection::isGoogle)
            return when {
                cals.isEmpty() -> false to NO_CALENDARS
                google.isEmpty() -> false to NO_GOOGLE_CALENDAR
                google.none { it.syncEvents } -> false to GOOGLE_SYNC_OFF
                google.none { it.syncEvents && it.visible } -> false to GOOGLE_HIDDEN
                else -> true to null
            }
        }

        /** The words for a reason, as Settings shows it. */
        fun reasonText(reason: String?): String = when (reason) {
            null -> ""
            NO_CALENDARS -> "This phone has no calendars."
            NO_GOOGLE_CALENDAR -> "No Google account calendar on this phone. Add your Google account in Android Settings → Accounts."
            GOOGLE_SYNC_OFF -> "Calendar sync is off for your Google account. Turn it on in Android Settings → Accounts → Google → Account sync → Calendar."
            GOOGLE_HIDDEN -> "Your Google calendars are hidden on this phone."
            QUERY_FAILED -> "The phone's calendar couldn't be read."
            else -> reason
        }
    }

    val wire: lk.codegen.risime.net.CalendarCheckResult
        get() = lk.codegen.risime.net.CalendarCheckResult(
            blocks,
            listOf(source.copy(reason = wireReason(source.reason)), GOOGLE_API_NOT_CONNECTED),
            if (source.readOk) listOf(SOURCE) else emptyList(),
        )
}

sealed interface CalendarAddOutcome {
    data class Added(val record: CalendarAddRecord, val calendar: PhoneCalendarInfo) : CalendarAddOutcome

    data class Failed(val reason: CalendarFailure) : CalendarAddOutcome
}

class PhoneCalendar(
    private val backend: CalendarBackend,
    val choice: CalendarChoiceStore,
    val writes: CalendarWriteLog,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /** Waits until the remembered pick and the write log are loaded from storage. */
    private val ready: suspend () -> Unit = {},
) {
    companion object {
        const val MAX_WINDOW_MS = 14L * 24 * 3600_000
        const val MAX_BLOCKS = 200

        private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

        /**
         * §25.3 free/busy only: overlapping blocks of the same kind (busy/free, all-day/timed) merged,
         * clipped to the window, sorted by start, at most 200. Never a title, place, calendar or id.
         */
        fun merge(rows: List<BusyRow>, fromMs: Long, toMs: Long): List<CalendarBlock> {
            val out = ArrayList<Triple<Long, Long, Pair<Boolean, Boolean>>>()
            rows.filter { it.end > fromMs && it.begin < toMs && it.end > it.begin }
                .groupBy { it.busy to it.allDay }
                .forEach { (kind, list) ->
                    var s = -1L
                    var e = -1L
                    for (r in list.sortedBy { it.begin }) {
                        val b = maxOf(r.begin, fromMs)
                        val en = minOf(r.end, toMs)
                        if (s >= 0 && b <= e) {
                            e = maxOf(e, en)
                        } else {
                            if (s >= 0) out += Triple(s, e, kind)
                            s = b
                            e = en
                        }
                    }
                    if (s >= 0) out += Triple(s, e, kind)
                }
            return out.sortedWith(compareBy({ it.first }, { it.second }))
                .take(MAX_BLOCKS)
                .map { (s, e, k) -> CalendarBlock(iso(s), iso(e), busy = k.first, allDay = k.second) }
        }
    }

    fun canRead() = backend.canRead()

    fun canWrite() = backend.canWrite()

    /** The picker's list (empty without read permission). */
    fun options(): List<PhoneCalendarInfo> = if (backend.canRead()) runCatching { CalendarSelection.pickerOptions(backend.calendars()) }.getOrDefault(emptyList()) else emptyList()

    /** Every calendar (empty without read permission). */
    fun allCalendars(): List<PhoneCalendarInfo> = if (backend.canRead()) runCatching { backend.calendars() }.getOrDefault(emptyList()) else emptyList()

    /** The calendar an add goes to now (the remembered pick or the default Google one). */
    fun target(): PhoneCalendarInfo? = if (backend.canRead()) runCatching { CalendarSelection.target(backend.calendars(), choice.chosen.value) }.getOrNull() else null

    /** The remembered pick, while it still exists and is writable. */
    fun chosen(): PhoneCalendarInfo? {
        val id = choice.chosen.value ?: return null
        if (!backend.canRead()) return null
        return runCatching { backend.calendars().firstOrNull { it.id == id && CalendarSelection.writable(it) } }.getOrNull()
    }

    suspend fun choose(id: Long) {
        choice.set(id)
        log("risi calendar: calendar chosen")
    }

    /** Null without read permission. */
    fun check(fromMs: Long, toMs: Long): List<CalendarBlock>? = read(fromMs, toMs)?.blocks

    /**
     * P0 2026-10-09 (honesty): free/busy over the visible calendars' instances, and what was read: the
     * calendars (name, account type, instances in the window) and whether the read can be trusted
     * (`read_ok`: a visible, synced Google calendar exists). Null without read permission.
     */
    fun read(fromMs: Long, toMs: Long): CalendarRead? {
        if (!backend.canRead()) return null
        val cals = runCatching { backend.calendars() }.getOrElse {
            log("risi calendar: calendar list failed")
            return CalendarRead(emptyList(), CalendarRead.source(emptyList(), false, CalendarRead.QUERY_FAILED))
        }
        val rows = runCatching { backend.instances(fromMs, toMs) }.getOrElse {
            log("risi calendar: instances query failed")
            return CalendarRead(emptyList(), CalendarRead.source(emptyList(), false, CalendarRead.QUERY_FAILED))
        }
        val known = cals.map { it.id }.toSet()
        val visibleIds = cals.filter { it.visible }.map { it.id }.toSet()
        // A row of a calendar the list doesn't know (a race) counts when its own flag says visible.
        val used = rows.filter { r -> r.visible && (r.calendarId !in known || r.calendarId in visibleIds) }
        val inWindow = used.filter { it.end > fromMs && it.begin < toMs && it.end > it.begin }
        val read = cals.filter { it.visible }.sortedWith(googleFirst).take(CalendarRead.MAX_CALENDARS).map { c ->
            lk.codegen.risime.net.CalendarSourceCalendar(CalendarRead.wireName(c),c.accountType.take(100), inWindow.count { it.calendarId == c.id })
        }
        val (ok, reason) = CalendarRead.verdict(cals)
        log("risi calendar: check read ${cals.size} calendars (${read.size} visible), ${inWindow.size} instances, read_ok=$ok${reason?.let { " ($it)" } ?: ""}")
        return CalendarRead(merge(used, fromMs, toMs), CalendarRead.source(read, ok, reason))
    }

    private val googleFirst = compareByDescending<PhoneCalendarInfo> { CalendarSelection.isGoogle(it) }.thenBy { it.id }

    /** Settings → Calendar → Details: every calendar with its instances in the next [days] days (null: no permission). */
    fun overview(days: Int = 7): CalendarOverview? {
        if (!backend.canRead()) return null
        val from = now()
        val cals = runCatching { backend.calendars() }.getOrElse { return CalendarOverview(emptyList(), false, CalendarRead.QUERY_FAILED) }
        val rows = runCatching { backend.instances(from, from + days * 86_400_000L) }.getOrElse { return CalendarOverview(emptyList(), false, CalendarRead.QUERY_FAILED) }
        val (ok, reason) = CalendarRead.verdict(cals)
        return CalendarOverview(cals.sortedWith(googleFirst).map { c -> c to rows.count { it.calendarId == c.id } }, ok, reason)
    }

    /** All-day events are stored as UTC midnights of the phone's dates (Android's rule). */
    private fun allDaySpan(startMs: Long, endMs: Long): Pair<Long, Long> {
        val z = zone()
        val d0 = Instant.ofEpochMilli(startMs).atZone(z).toLocalDate()
        var d1 = Instant.ofEpochMilli(endMs).atZone(z).toLocalDate()
        val endIsMidnight = Instant.ofEpochMilli(endMs).atZone(z).toLocalTime().toSecondOfDay() == 0
        if (!endIsMidnight) d1 = d1.plusDays(1)
        if (!d1.isAfter(d0)) d1 = d0.plusDays(1)
        return utcMidnight(d0) to utcMidnight(d1)
    }

    private fun utcMidnight(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /**
     * Adds the event to the target calendar and reads it back (title, start, end, calendar id). A
     * mismatch deletes what was inserted. Every outcome is recorded locally for the card.
     */
    suspend fun add(writeId: String, requestId: String?, title: String, startMs: Long, endMs: Long, allDay: Boolean): CalendarAddOutcome {
        ready()
        val base = CalendarAddRecord(writeId, requestId, title, iso(startMs), iso(endMs), allDay, at = now())
        suspend fun fail(f: CalendarFailure): CalendarAddOutcome {
            log("risi calendar: add failed (${f.name})")
            writes.put(base.copy(failure = f.name))
            return CalendarAddOutcome.Failed(f)
        }
        if (!backend.canWrite() || !backend.canRead()) return fail(CalendarFailure.NO_PERMISSION)
        val cal = target() ?: return fail(CalendarFailure.NO_GOOGLE_CALENDAR)
        val (s, e, tz) = if (allDay) allDaySpan(startMs, endMs).let { Triple(it.first, it.second, "UTC") } else Triple(startMs, endMs, zone().id)
        val id = runCatching { backend.insert(cal.id, title, s, e, allDay, tz) }.getOrNull() ?: return fail(CalendarFailure.INSERT_FAILED)
        val back = runCatching { backend.event(id) }.getOrNull()
        if (back == null || back.calendarId != cal.id || back.title != title || back.dtStart != s || back.dtEnd != e || back.allDay != allDay) {
            runCatching { backend.delete(id) }
            return fail(CalendarFailure.VERIFY_MISMATCH)
        }
        val rec = base.copy(eventId = id, calendarId = cal.id, calendarName = CalendarSelection.label(cal), accountType = cal.accountType)
        writes.put(rec)
        log("risi calendar: added and verified (${CalendarSelection.typeLabel(cal.accountType)})")
        return CalendarAddOutcome.Added(rec, cal)
    }

    /** Undo: deletes the event this phone added for [writeId] if it is still there; false: not found. */
    suspend fun remove(writeId: String, eventId: Long): Boolean {
        ready()
        val present = backend.canRead() && runCatching { backend.event(eventId) }.getOrNull() != null
        val ok = present && backend.canWrite() && runCatching { backend.delete(eventId) }.getOrDefault(false)
        writes.get(writeId)?.let { if (ok || !present) writes.put(it.copy(removed = true)) }
        return ok
    }
}
