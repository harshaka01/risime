package lk.codegen.risime.data.tabs

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.os.Bundle
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat

/** [CalendarBackend] over Android's `CalendarContract` (runs off the main thread). */
class AndroidCalendarBackend(private val context: Context) : CalendarBackend {
    companion object {
        /**
         * `content://com.android.calendar/instances/when/<fromMs>/<toMs>`: the provider expands recurring
         * events into instances for the window (an `Events` DTSTART range would miss every repeat and
         * every event that started before the window). Times are epoch milliseconds.
         */
        fun instancesUri(fromMs: Long, toMs: Long): android.net.Uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMs)
            ContentUris.appendId(it, toMs)
        }.build()

        /**
         * No selection: every calendar's instances, whatever VISIBLE or SYNC_EVENTS say (P0 2026-10-10). Every
         * column is in AOSP's Instances projection map (`CalendarProvider2.sInstancesProjectionMap`): `deleted` is
         * "Events.deleted", `_sync_id` the Events column (v1.31 §31.4 loop guard). A provider that refuses one
         * gets [INSTANCES_PROJECTION_V1]; when both fail the error is thrown, never an empty list.
         */
        val INSTANCES_PROJECTION = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.AVAILABILITY,
            CalendarContract.Instances.STATUS,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.VISIBLE,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Events._SYNC_ID,
            CalendarContract.Events.DELETED,
        )
        val INSTANCES_PROJECTION_V1 = INSTANCES_PROJECTION.copyOf(8).requireNoNulls()

        /** Non-recurring `Events` of calendars the provider doesn't expand into Instances (SYNC_EVENTS = 0). */
        val EVENTS_PROJECTION = arrayOf(
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DURATION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.AVAILABILITY,
            CalendarContract.Events.STATUS,
            CalendarContract.Events.SELF_ATTENDEE_STATUS,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.RDATE,
            CalendarContract.Events._SYNC_ID,
        )

        /** One `Instances` cursor row ([INSTANCES_PROJECTION] or its V1 prefix) as a raw row. */
        fun instanceRow(q: android.database.Cursor): InstanceRow {
            fun int(i: Int): Int? = if (i >= q.columnCount || q.isNull(i)) null else q.getInt(i)
            return InstanceRow(
                begin = q.getLong(0), end = if (q.isNull(1)) q.getLong(0) else q.getLong(1), allDay = int(2) == 1,
                availability = int(3), status = int(4), selfStatus = int(5),
                deleted = (int(9) ?: 0) != 0, calendarId = if (q.isNull(7)) 0 else q.getLong(7), visible = int(6)?.let { it != 0 } ?: true,
                syncId = if (q.columnCount > 8 && !q.isNull(8)) q.getString(8) else null,
            )
        }
    }

    private val cr get() = context.contentResolver

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    override fun canRead(): Boolean = granted(Manifest.permission.READ_CALENDAR)

    override fun canWrite(): Boolean = granted(Manifest.permission.WRITE_CALENDAR)

    override fun calendars(): List<PhoneCalendarInfo> {
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.SYNC_EVENTS,
        )
        val out = ArrayList<PhoneCalendarInfo>()
        cr.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { q ->
            while (q.moveToNext()) {
                out += PhoneCalendarInfo(
                    id = q.getLong(0),
                    displayName = q.getString(1).orEmpty(),
                    accountName = q.getString(2).orEmpty(),
                    accountType = q.getString(3).orEmpty(),
                    accessLevel = q.getInt(4),
                    visible = q.getInt(5) != 0,
                    ownerAccount = q.getString(6),
                    isPrimary = !q.isNull(7) && q.getInt(7) != 0,
                    syncEvents = q.isNull(8) || q.getInt(8) != 0,
                )
            }
        }
        return out
    }

    override fun masterSyncOn(): Boolean = ContentResolver.getMasterSyncAutomatically()

    override fun accountSyncOn(accountName: String, accountType: String): Boolean =
        ContentResolver.getSyncAutomatically(Account(accountName, accountType), CalendarContract.AUTHORITY)

    override fun requestSync(accountName: String, accountType: String) {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        ContentResolver.requestSync(Account(accountName, accountType), CalendarContract.AUTHORITY, extras)
    }

    /** The filtered busy rows (raw provider times); [PhoneCalendar] reads [instanceRows] and localizes all-day ones. */
    override fun instances(fromMs: Long, toMs: Long): List<BusyRow> =
        instanceRows(fromMs, toMs).mapNotNull { r -> if (InstanceFilter.exclusion(r) != null) null else BusyRow(r.begin, r.end, r.allDay, r.availability != InstanceFilter.AVAILABILITY_FREE, r.calendarId, r.visible, r.syncId) }

    override fun instanceRows(fromMs: Long, toMs: Long): List<InstanceRow> {
        val uri = instancesUri(fromMs, toMs)
        val first = runCatching { cr.query(uri, INSTANCES_PROJECTION, null, null, null) }
        val q = first.getOrNull() ?: runCatching { cr.query(uri, INSTANCES_PROJECTION_V1, null, null, null) }.getOrElse { e ->
            throw CalendarQueryException("Instances query failed (${first.exceptionOrNull()?.javaClass?.simpleName ?: "no cursor"}, then ${e.javaClass.simpleName}: ${e.message})", e)
        } ?: throw CalendarQueryException("Instances query returned no cursor (calendar provider unavailable)")
        val out = ArrayList<InstanceRow>()
        q.use { while (it.moveToNext()) out += instanceRow(it) }
        return out + unexpandedEvents(fromMs, toMs)
    }

    /**
     * AOSP expands only calendars with SYNC_EVENTS != 0 into Instances (`CalendarInstancesHelper.getEntries`), so a
     * calendar with sync off (a local calendar made without the flag) would read as empty: its non-recurring
     * events are read from `Events` instead. Recurring ones there can't be expanded here (Diagnostics shows the raw count).
     */
    private fun unexpandedEvents(fromMs: Long, toMs: Long): List<InstanceRow> {
        val ids = calendars().filter { !it.syncEvents }.map { it.id }
        if (ids.isEmpty()) return emptyList()
        val sel = "${CalendarContract.Events.CALENDAR_ID} IN (${ids.joinToString(",") { "?" }}) AND ${CalendarContract.Events.DELETED} = 0 AND ${CalendarContract.Events.DTSTART} < ?"
        val args = (ids.map { it.toString() } + toMs.toString()).toTypedArray()
        val q = cr.query(CalendarContract.Events.CONTENT_URI, EVENTS_PROJECTION, sel, args, null)
            ?: throw CalendarQueryException("Events query returned no cursor (calendar provider unavailable)")
        val out = ArrayList<InstanceRow>()
        q.use {
            while (it.moveToNext()) {
                if (!it.isNull(8) || !it.isNull(9)) continue // recurring
                val b = it.getLong(0)
                val e = if (!it.isNull(1)) it.getLong(1) else b + (InstanceFilter.durationMs(it.getString(2)) ?: 0L)
                fun int(i: Int): Int? = if (it.isNull(i)) null else it.getInt(i)
                out += InstanceRow(b, e, int(3) == 1, int(4), int(5), int(6), false, it.getLong(7), true, if (it.isNull(10)) null else it.getString(10))
            }
        }
        return out.filter { r -> r.end > fromMs && r.begin < toMs }
    }

    override fun eventCounts(): Map<Long, Int> {
        val q = cr.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events.CALENDAR_ID), "${CalendarContract.Events.DELETED} = 0", null, null)
            ?: throw CalendarQueryException("Events query returned no cursor (calendar provider unavailable)")
        val out = HashMap<Long, Int>()
        q.use { while (it.moveToNext()) if (!it.isNull(0)) out.merge(it.getLong(0), 1, Int::plus) }
        return out
    }

    override fun syncState(accountName: String, accountType: String): String? {
        val a = Account(accountName, accountType)
        return when {
            ContentResolver.isSyncActive(a, CalendarContract.AUTHORITY) -> "active"
            ContentResolver.isSyncPending(a, CalendarContract.AUTHORITY) -> "pending"
            else -> "idle"
        }
    }

    /** Android has no public "last sync" time for another app's sync adapter: unknown. */
    override fun lastSync(accountName: String, accountType: String): String? = null

    override fun insert(calendarId: Long, title: String, startMs: Long, endMs: Long, allDay: Boolean, timeZone: String): Long? {
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, timeZone)
            put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
        }
        val uri = cr.insert(CalendarContract.Events.CONTENT_URI, v) ?: return null
        return runCatching { ContentUris.parseId(uri) }.getOrNull()?.takeIf { it > 0 }
    }

    override fun event(id: Long): EventRow? {
        val proj = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.DELETED,
        )
        cr.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), proj, null, null, null)?.use { q ->
            if (!q.moveToFirst()) return null
            if (!q.isNull(6) && q.getInt(6) != 0) return null
            return EventRow(q.getLong(0), q.getLong(1), q.getString(2), q.getLong(3), q.getLong(4), q.getInt(5) != 0)
        }
        return null
    }

    override fun delete(id: Long): Boolean = cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) > 0
}

/** [RisiCalendarPort] for the cards and Settings: provider reads off the main thread; [open] shows the event. */
class AndroidCalendarPort(
    private val context: Context,
    private val cal: PhoneCalendar,
    /** Proposal 2026-10-09-risi-action-loop §2: the Calendar skill PATCH with the picked calendar. */
    private val report: suspend (lk.codegen.risime.net.RisiCalendarRef) -> Unit = {},
) : RisiCalendarPort {
    override val records = cal.writes.records
    override val chosenId = cal.choice.chosen

    override fun hasPermission(): Boolean = cal.canRead() && cal.canWrite()

    override suspend fun options(): List<PhoneCalendarInfo> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cal.options() }

    override suspend fun chosen(): PhoneCalendarInfo? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cal.chosen() }

    override suspend fun choose(id: Long) {
        cal.choose(id)
        val c = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cal.chosen() } ?: return
        runCatching { report(CalendarSelection.ref(c)) }
    }

    override suspend fun matchHint(hint: lk.codegen.risime.net.RisiCalendarRef?): PhoneCalendarInfo? =
        if (hint == null || !cal.canRead()) null
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { CalendarSelection.matchHint(cal.allCalendars(), hint) }.getOrNull() }

    /** v1.32 Details: opens Android's sync settings for that account type. */
    override fun openSyncSettings(accountType: String?) {
        val i = android.content.Intent(android.provider.Settings.ACTION_SYNC_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!accountType.isNullOrBlank()) i.putExtra(android.provider.Settings.EXTRA_ACCOUNT_TYPES, arrayOf(accountType))
        runCatching { context.startActivity(i) }
    }

    /** v1.32 Details "Refresh": ask the provider to sync the account, then Details re-reads. */
    override suspend fun refreshSync(accountName: String?, accountType: String?) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val accts = if (accountName != null && accountType != null) listOf(accountName to accountType)
            else CalendarRead.syncAccounts(cal.allCalendars())
            accts.forEach { (n, t) -> runCatching { cal.requestSync(n, t) } }
        }
    }

    override suspend fun overview(): CalendarOverview? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cal.overview() }

    override suspend fun diagnostics(): CalendarDiagnostics = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cal.diagnostics() }

    override fun open(eventId: Long) {
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }.onFailure {
            // No calendar app for the event: the calendar app at its day.
            runCatching {
                val day = cal.writes.records.value.values.firstOrNull { it.eventId == eventId }?.start?.let { java.time.Instant.parse(it).toEpochMilli() } ?: System.currentTimeMillis()
                val u = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also { b -> ContentUris.appendId(b, day) }.build()
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
