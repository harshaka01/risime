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
data class BusyRow(
    val begin: Long, val end: Long, val allDay: Boolean, val busy: Boolean, val calendarId: Long = 0, val visible: Boolean = true,
    /** The provider's `_SYNC_ID` (v1.31 §31.4 loop guard): `risi` + 32 hex digits marks a RisiMe copy. */
    val syncId: String? = null,
)

/** An event read back from the provider (`CalendarContract.Events`). */
data class EventRow(val id: Long, val calendarId: Long, val title: String?, val dtStart: Long, val dtEnd: Long, val allDay: Boolean)

/** The provider (Android's `CalendarContract`, or a fake in tests). */
interface CalendarBackend {
    fun canRead(): Boolean

    fun canWrite(): Boolean

    fun calendars(): List<PhoneCalendarInfo>

    /** v1.32 §29.7: `ContentResolver.getMasterSyncAutomatically()` (true when unknown). */
    fun masterSyncOn(): Boolean = true

    /** v1.32 §29.7: `ContentResolver.getSyncAutomatically(account, CalendarContract.AUTHORITY)` (true when unknown). */
    fun accountSyncOn(accountName: String, accountType: String): Boolean = true

    /** v1.32 Details "Refresh": `ContentResolver.requestSync` for the account's calendar authority (MANUAL + EXPEDITED). */
    fun requestSync(accountName: String, accountType: String) {}

    /**
     * Instances of every calendar overlapping [fromMs, toMs) (cancelled and declined ones left out), over
     * `CalendarContract.Instances` (recurring events expanded); [BusyRow.visible] says if its calendar is shown.
     */
    fun instances(fromMs: Long, toMs: Long): List<BusyRow>

    /**
     * P0 2026-10-10: the raw rows (no filter; all-day rows as UTC midnights) of every calendar overlapping
     * [fromMs, toMs): the provider's `Instances`, plus the non-recurring `Events` of calendars it doesn't
     * expand (`SYNC_EVENTS` = 0). Throws [CalendarQueryException] when the provider can't be read, never
     * returns an empty list for an error. Default (fakes): [instances] as raw rows.
     */
    fun instanceRows(fromMs: Long, toMs: Long): List<InstanceRow> =
        instances(fromMs, toMs).map { InstanceRow(it.begin, it.end, it.allDay, if (it.busy) null else InstanceFilter.AVAILABILITY_FREE, calendarId = it.calendarId, visible = it.visible, syncId = it.syncId) }

    /**
     * v1.32 §25.3: creates the local "RisiMe" calendar (ACCOUNT_TYPE_LOCAL, owner access, visible, synced) and
     * returns its id; null when the provider refuses. Default (fakes): none.
     */
    fun createLocalCalendar(name: String): Long? = null

    /** Diagnostics: raw `Events` rows (not deleted) per calendar id; null when not available. */
    fun eventCounts(): Map<Long, Int>? = null

    /** Diagnostics: "active" / "pending" / "idle" for the account's calendar sync; null when unknown. */
    fun syncState(accountName: String, accountType: String): String? = null

    /** Diagnostics: the account's last calendar sync, when the phone says it; null (unknown) otherwise. */
    fun lastSync(accountName: String, accountType: String): String? = null

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

    /** v1.32 §25.3: a calendar the provider lets us add to (`CALENDAR_ACCESS_LEVEL >= CAL_ACCESS_CONTRIBUTOR`). */
    fun accepts(c: PhoneCalendarInfo): Boolean = c.accessLevel >= CAL_ACCESS_CONTRIBUTOR

    /** A Google account's primary calendar: `IS_PRIMARY`, or the calendar named after (or owned by) its account. */
    fun isPrimaryGoogle(c: PhoneCalendarInfo): Boolean =
        isGoogle(c) && (c.isPrimary || c.displayName.equals(c.accountName, true) || c.ownerAccount.equals(c.accountName, true))

    /** The default without a remembered pick: a Google account's primary calendar, writable and shown (never a local one). */
    fun defaultGoogle(all: List<PhoneCalendarInfo>): PhoneCalendarInfo? = all.filter { writable(it) && isPrimaryGoogle(it) }.sortedWith(preferred).firstOrNull()

    /** v1.32 §25.3 the local fallback calendar's name. */
    const val RISIME_LOCAL = "RisiMe"

    /** The local "RisiMe" calendar, when this phone has one that accepts events. */
    fun localRisiMe(all: List<PhoneCalendarInfo>): PhoneCalendarInfo? =
        all.firstOrNull { it.accountType.equals(LOCAL, true) && it.displayName == RISIME_LOCAL && accepts(it) }

    /**
     * v1.32 §25.3 where an event goes: the remembered pick while it still accepts events; else a Google account's
     * primary calendar; else the local "RisiMe" calendar (created by [PhoneCalendar.add] when missing). Read-only
     * calendars (holidays, birthdays, someone else's) never.
     */
    fun target(all: List<PhoneCalendarInfo>, rememberedId: Long?): PhoneCalendarInfo? {
        rememberedId?.let { id -> all.firstOrNull { it.id == id && accepts(it) }?.let { return it } }
        return defaultGoogle(all) ?: localRisiMe(all)
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
enum class CalendarFailure(val text: String, val code: String) {
    NO_PERMISSION("Calendar permission is off on this phone.", lk.codegen.risime.net.CalendarAddErrors.NO_PERMISSION),
    NO_GOOGLE_CALENDAR("No calendar on this phone accepts new events. Add your Google account in Android Settings → Accounts, or choose a calendar.", lk.codegen.risime.net.CalendarAddErrors.READ_ONLY_CALENDAR),
    INSERT_FAILED("Your calendar didn't accept the event.", lk.codegen.risime.net.CalendarAddErrors.INSERT_FAILED),
    VERIFY_MISMATCH("The event wasn't there when the phone checked, so nothing was added.", lk.codegen.risime.net.CalendarAddErrors.VERIFY_FAILED),
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
data class CalendarOverview(
    /** Each calendar with its count; null: the count couldn't be read (see [error]). */
    val calendars: List<Pair<PhoneCalendarInfo, Int?>>,
    val readOk: Boolean,
    val reason: String?,
    /** v1.32: master auto-sync is off on this phone. */
    val masterSyncOff: Boolean = false,
    /** v1.32: non-local accounts holding a visible calendar whose calendar sync is off (account name, account type). */
    val syncOffAccounts: List<Pair<String, String>> = emptyList(),
    /** P0 2026-10-10: the query error's text when the read failed (shown in Details and diagnostics). */
    val error: String? = null,
)

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
        const val SYNC_OFF = "sync_off"

        fun source(cals: List<lk.codegen.risime.net.CalendarSourceCalendar>, ok: Boolean, reason: String?) =
            lk.codegen.risime.net.CalendarSourceReport(SOURCE, cals, ok, if (ok) null else reason)

        const val GOOGLE_API = "google_api"

        private val RISI_SYNC_ID = Regex("^risi[0-9a-f]{32}$")

        /** v1.31 §31.4: a provider event synced down from one of RisiMe's Google copies. */
        fun isRisiCopy(syncId: String?): Boolean = syncId != null && RISI_SYNC_ID.matches(syncId)

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
                else -> true to null
            }
        }

        /** The non-local accounts that hold a visible calendar, as (name, type), in calendar order. */
        fun syncAccounts(cals: List<PhoneCalendarInfo>): List<Pair<String, String>> =
            cals.filter { it.visible && !it.accountType.equals(CalendarSelection.LOCAL, true) }.map { it.accountName to it.accountType }.distinct()

        /** v1.32 §29.7: the accounts (with a visible calendar) whose sync is off; all of them when master sync is off. */
        fun syncOffAccounts(cals: List<PhoneCalendarInfo>, b: CalendarBackend): List<Pair<String, String>> {
            val accts = syncAccounts(cals)
            return if (!b.masterSyncOn()) accts else accts.filter { !b.accountSyncOn(it.first, it.second) }
        }

        /**
         * v1.32 §29.7: `sync_off` exactly when there is a non-local account with a visible calendar and sync is off for
         * every such account (or master sync is off). Calendars of a `LOCAL` account never cause it.
         */
        fun syncOff(cals: List<PhoneCalendarInfo>, b: CalendarBackend): Boolean {
            val accts = syncAccounts(cals)
            return accts.isNotEmpty() && syncOffAccounts(cals, b).size == accts.size
        }

        /** The words for a reason, as Settings shows it. */
        fun reasonText(reason: String?): String = when (reason) {
            null -> ""
            NO_CALENDARS -> "This phone has no calendars."
            NO_GOOGLE_CALENDAR -> "No Google account calendar on this phone. Add your Google account in Android Settings → Accounts."
            GOOGLE_SYNC_OFF -> "Calendar sync is off for your Google account. Turn it on in Android Settings → Accounts → Google → Account sync → Calendar."
            GOOGLE_HIDDEN -> "Your Google calendars are hidden on this phone."
            QUERY_FAILED -> "The phone's calendar couldn't be read."
            SYNC_OFF -> "Calendar sync is off on this phone, so what Risi reads may be out of date."
            else -> reason
        }
    }

    val wire: lk.codegen.risime.net.CalendarCheckResult
        get() = lk.codegen.risime.net.CalendarCheckResult(
            blocks,
            listOf(source.copy(reason = wireReason(source.reason)), GOOGLE_API_NOT_CONNECTED),
            if (source.readOk || source.reason == SYNC_OFF) listOf(SOURCE) else emptyList(),
        )
}

/** v1.32 §25.3 what the event card shows. */
sealed interface AddedEventView {
    /** Not added by this phone: the card shows the body text only. */
    data object NotHere : AddedEventView

    /** "This event is no longer on this phone". */
    data object Gone : AddedEventView

    data class Present(val event: LocalEvent, val calendarLabel: String?) : AddedEventView
}

sealed interface CalendarAddOutcome {
    data class Added(val record: CalendarAddRecord, val calendar: PhoneCalendarInfo) : CalendarAddOutcome

    /** [detail]: the exception's class/message or what didn't match (≤ 200 chars, never a title). */
    data class Failed(val reason: CalendarFailure, val detail: String? = null) : CalendarAddOutcome
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

    /** v1.32 Details "Refresh": ask the provider to sync [accountName] now (no-op in tests). */
    fun requestSync(accountName: String, accountType: String) = backend.requestSync(accountName, accountType)

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
    fun read(fromMs: Long, toMs: Long, skipGoogleAccount: String? = null): CalendarRead? {
        if (!backend.canRead()) return null
        val cals = runCatching { backend.calendars() }.getOrElse {
            log("risi calendar: calendar list failed")
            return CalendarRead(emptyList(), CalendarRead.source(emptyList(), false, CalendarRead.QUERY_FAILED))
        }
        val rows = runCatching { busyRows(fromMs, toMs) }.getOrElse {
            log("risi calendar: instances query failed")
            return CalendarRead(emptyList(), CalendarRead.source(emptyList(), false, CalendarRead.QUERY_FAILED))
        }
        // P0 2026-10-10: every calendar counts, whatever its VISIBLE or SYNC_EVENTS flag says.
        // v1.31 §31.4 loop guard: RisiMe's own copies (a `risi<hex32>` sync id) are never busy here (the Risi Calendar
        // counts them); and while Google is read through the API in the same call, the connected account's provider
        // events are skipped so nothing is counted twice.
        val skipIds = if (skipGoogleAccount == null) emptySet() else cals.filter { it.accountType == CalendarSelection.GOOGLE && it.accountName.equals(skipGoogleAccount, true) }.map { it.id }.toSet()
        val used = rows.filter { r -> !CalendarRead.isRisiCopy(r.syncId) && r.calendarId !in skipIds }
        val inWindow = used
        val read = cals.sortedWith(googleFirst).take(CalendarRead.MAX_CALENDARS).map { c ->
            lk.codegen.risime.net.CalendarSourceCalendar(CalendarRead.wireName(c), c.accountType.take(100), inWindow.count { it.calendarId == c.id })
        }
        val (ok, reason) = CalendarRead.verdict(cals).let { v ->
            if (runCatching { CalendarRead.syncOff(cals, backend) }.getOrDefault(false)) false to CalendarRead.SYNC_OFF else v
        }
        log("risi calendar: check read ${cals.size} calendars (${cals.count { it.visible }} visible), ${inWindow.size} instances, read_ok=$ok${reason?.let { " ($it)" } ?: ""}")
        return CalendarRead(merge(used, fromMs, toMs), CalendarRead.source(read, ok, reason))
    }

    private val googleFirst = compareByDescending<PhoneCalendarInfo> { CalendarSelection.isGoogle(it) }.thenBy { it.id }

    /**
     * The rows that count in [fromMs, toMs): the provider's raw rows over a window one day wider (all-day rows are
     * UTC midnights), deleted / cancelled / declined ones left out, all-day ones moved to the phone's own dates.
     */
    private fun busyRows(fromMs: Long, toMs: Long): List<BusyRow> {
        val (qf, qt) = InstanceFilter.queryWindow(fromMs, toMs)
        return InstanceFilter.busyRows(backend.instanceRows(qf, qt), zone(), fromMs, toMs)
    }

    /**
     * v1.32 §29.7 "Your events": this phone's own events in [fromMs, toMs) with their titles, from the same rows and
     * exclusions as the busy read (every calendar; deleted, cancelled, declined out; all-day on local dates; RisiMe's
     * own Google copies left out, the Risi Calendar lists them). Null without permission; throws on a query error.
     */
    fun localEvents(fromMs: Long, toMs: Long): List<LocalEvent>? {
        if (!backend.canRead()) return null
        val (qf, qt) = InstanceFilter.queryWindow(fromMs, toMs)
        val z = zone()
        return backend.instanceRows(qf, qt).mapNotNull { r ->
            if (CalendarRead.isRisiCopy(r.syncId)) return@mapNotNull null
            val b = InstanceFilter.busy(r, z) ?: return@mapNotNull null
            if (!InstanceFilter.overlaps(b.begin, b.end, fromMs, toMs)) null else LocalEvent(r.title.orEmpty(), b.begin, b.end, r.allDay)
        }
    }

    /**
     * Settings → Calendar → Details: every calendar with its instances in the next [days] days (null: no permission).
     * A failed instances query keeps the calendars listed with no count (null) and the error: never "0 events".
     */
    fun overview(days: Int = 7): CalendarOverview? {
        if (!backend.canRead()) return null
        val from = now()
        val cals = runCatching { backend.calendars() }.getOrElse { return CalendarOverview(emptyList(), false, CalendarRead.QUERY_FAILED, error = errorText("calendars", it)) }
        val sorted = cals.sortedWith(googleFirst)
        val rows = runCatching { busyRows(from, from + days * InstanceFilter.DAY_MS) }.getOrElse {
            log("risi calendar: Details instances query failed")
            return CalendarOverview(sorted.map { it to null }, false, CalendarRead.QUERY_FAILED, error = errorText("instances", it))
        }
        val (ok, reason) = CalendarRead.verdict(cals).let { v ->
            if (runCatching { CalendarRead.syncOff(cals, backend) }.getOrDefault(false)) false to CalendarRead.SYNC_OFF else v
        }
        val off = runCatching { CalendarRead.syncOffAccounts(cals, backend) }.getOrDefault(emptyList())
        val master = runCatching { !backend.masterSyncOn() }.getOrDefault(false)
        return CalendarOverview(sorted.map { c -> c to rows.count { it.calendarId == c.id } }, ok, reason, master, off)
    }

    /**
     * Details → "Calendar diagnostics": the permissions, each calendar's flags and sync state, its raw Events
     * count and its Instances in the next [days] days (raw and counted), and every query error's text.
     */
    fun diagnostics(days: Int = 7, writeGranted: Boolean = backend.canWrite()): CalendarDiagnostics {
        val readOk = backend.canRead()
        val errors = ArrayList<String>()
        val master = runCatching { backend.masterSyncOn() }.getOrNull()
        if (!readOk) return CalendarDiagnostics(false, writeGranted, master, days, emptyList(), listOf("READ_CALENDAR is not granted: nothing can be read"))
        val cals = runCatching { backend.calendars() }.getOrElse { errors += errorText("calendars", it); emptyList() }
        val from = now()
        val to = from + days * InstanceFilter.DAY_MS
        val (qf, qt) = InstanceFilter.queryWindow(from, to)
        val raw = runCatching { backend.instanceRows(qf, qt) }.getOrElse { errors += errorText("instances", it); null }
        val z = zone()
        val rawIn = raw?.filter { r ->
            val (b, e) = if (r.allDay) InstanceFilter.localAllDay(r.begin, z) to InstanceFilter.localAllDay(r.end, z) else r.begin to r.end
            InstanceFilter.overlaps(b, e, from, to)
        }
        val counted = raw?.let { InstanceFilter.busyRows(it, z, from, to) }
        val events = runCatching { backend.eventCounts() }.getOrElse { errors += errorText("events", it); null }
        val list = cals.sortedWith(googleFirst).map { c ->
            val local = c.accountType.equals(CalendarSelection.LOCAL, true)
            CalendarDiag(
                c,
                accountSync = if (local) null else runCatching { backend.accountSyncOn(c.accountName, c.accountType) }.getOrNull(),
                syncState = if (local) null else runCatching { backend.syncState(c.accountName, c.accountType) }.getOrNull(),
                lastSync = if (local) null else runCatching { backend.lastSync(c.accountName, c.accountType) }.getOrNull(),
                events = events?.let { it[c.id] ?: 0 },
                instances = rawIn?.count { it.calendarId == c.id },
                counted = counted?.count { it.calendarId == c.id },
            )
        }
        return CalendarDiagnostics(true, writeGranted, master, days, list, errors, addLines())
    }

    /** The last 5 Risi adds (no titles): when, where (or why not), the event id and whether its row is still here. */
    private fun addLines(): List<String> = writes.records.value.values.sortedByDescending { it.at }.take(5).map { r ->
        val at = Instant.ofEpochMilli(r.at).toString()
        val where = r.failure?.let { f -> "failed: " + (CalendarFailure.entries.firstOrNull { it.name == f }?.code ?: f) } ?: (r.calendarName ?: "unknown calendar")
        val present = r.eventId?.let { id -> if (runCatching { backend.event(id) }.getOrNull() != null) "yes" else "no" }
        "$at · $where" + (r.eventId?.let { " · event $it · on this phone: $present" } ?: "") + if (r.removed) " · undone" else ""
    }

    /** "instances: IllegalArgumentException: Invalid column x" (the exception's class and message; no user data). */
    private fun errorText(what: String, t: Throwable): String =
        if (t is CalendarQueryException) "$what: ${t.message?.take(300)}" else "$what: ${t.javaClass.simpleName}${t.message?.let { ": ${it.take(300)}" } ?: ""}"

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
        suspend fun fail(f: CalendarFailure, detail: String? = null): CalendarAddOutcome {
            log("risi calendar: add failed (${f.name})")
            writes.put(base.copy(failure = f.name))
            return CalendarAddOutcome.Failed(f, detail?.take(200))
        }
        if (!backend.canWrite() || !backend.canRead()) return fail(CalendarFailure.NO_PERMISSION, "WRITE_CALENDAR or READ_CALENDAR not granted")
        val all = runCatching { backend.calendars() }.getOrElse { return fail(CalendarFailure.INSERT_FAILED, "calendars: ${errText(it)}") }
        // v1.32 §25.3: the pick, else a Google primary, else the local "RisiMe" calendar (created on first use).
        val cal = CalendarSelection.target(all, choice.chosen.value)
            ?: runCatching { backend.createLocalCalendar(CalendarSelection.RISIME_LOCAL) }.getOrNull()?.let { id ->
                runCatching { backend.calendars() }.getOrDefault(emptyList()).firstOrNull { it.id == id && CalendarSelection.accepts(it) }
            }
            ?: return fail(CalendarFailure.NO_GOOGLE_CALENDAR, "no calendar with access >= contributor (${all.size} calendars)")
        val (s, e, tz) = if (allDay) allDaySpan(startMs, endMs).let { Triple(it.first, it.second, "UTC") } else Triple(startMs, endMs, zone().id)
        val id = try {
            backend.insert(cal.id, title, s, e, allDay, tz)
        } catch (t: Exception) {
            return fail(CalendarFailure.INSERT_FAILED, "insert: ${errText(t)}")
        } ?: return fail(CalendarFailure.INSERT_FAILED, "insert: the provider returned no row")
        val back = try {
            backend.event(id)
        } catch (t: Exception) {
            return fail(CalendarFailure.VERIFY_MISMATCH, "read-back: ${errText(t)}")
        }
        val mismatch = verifyMismatch(back, id, cal.id, title, s, e, allDay)
        if (mismatch != null) {
            if (back != null) runCatching { backend.delete(id) }
            return fail(CalendarFailure.VERIFY_MISMATCH, mismatch)
        }
        val rec = base.copy(eventId = id, calendarId = cal.id, calendarName = CalendarSelection.label(cal), accountType = cal.accountType)
        writes.put(rec)
        // v1.32 §25.3: push it to the account now (expedited), so it reaches Google and the user's other devices.
        if (!cal.accountType.equals(CalendarSelection.LOCAL, true)) runCatching { backend.requestSync(cal.accountName, cal.accountType) }
        log("risi calendar: added and verified (${CalendarSelection.typeLabel(cal.accountType)})")
        return CalendarAddOutcome.Added(rec, cal)
    }

    private fun errText(t: Throwable) = "${t.javaClass.simpleName}${t.message?.let { ": $it" } ?: ""}"

    /** v1.32 §25.3 the read-back check: what didn't match (never the title's text), or null. */
    private fun verifyMismatch(back: EventRow?, id: Long, calId: Long, title: String, s: Long, e: Long, allDay: Boolean): String? = when {
        back == null -> "read-back: no row for id $id"
        back.calendarId != calId -> "read-back: calendar ${back.calendarId} != $calId"
        back.title != title -> "read-back: title differs"
        back.dtStart != s -> "read-back: start ${back.dtStart} != $s"
        back.dtEnd != e -> "read-back: end ${back.dtEnd} != $e"
        back.allDay != allDay -> "read-back: all_day differs"
        else -> null
    }

    /**
     * v1.32 §25.3 the event card: the provider row of an event THIS phone added ([writes] holds it), with its
     * calendar's label. [AddedEventView.NotHere] on another device; [AddedEventView.Gone] when the row is gone.
     */
    fun addedEvent(eventId: Long): AddedEventView {
        if (writes.records.value.values.none { it.eventId == eventId }) return AddedEventView.NotHere
        if (!backend.canRead()) return AddedEventView.Gone
        val row = runCatching { backend.event(eventId) }.getOrNull() ?: return AddedEventView.Gone
        val cal = runCatching { backend.calendars() }.getOrDefault(emptyList()).firstOrNull { it.id == row.calendarId }
        val (b, en) = if (row.allDay) InstanceFilter.localAllDay(row.dtStart, zone()) to InstanceFilter.localAllDay(row.dtEnd, zone()) else row.dtStart to row.dtEnd
        return AddedEventView.Present(LocalEvent(row.title.orEmpty(), b, en, row.allDay), cal?.let { CalendarSelection.label(it) })
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
