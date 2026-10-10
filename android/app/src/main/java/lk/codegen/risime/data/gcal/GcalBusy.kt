package lk.codegen.risime.data.gcal

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import lk.codegen.risime.data.db.GcalCalendarEntity
import lk.codegen.risime.data.db.GcalDao
import lk.codegen.risime.data.tabs.BusyRow
import lk.codegen.risime.data.tabs.PhoneCalendar
import lk.codegen.risime.net.CalendarBlock
import lk.codegen.risime.net.GcalReadReasons
import lk.codegen.risime.net.GoogleSourceCalendar
import lk.codegen.risime.net.GoogleSourceReport

/*
 * v1.31 §31.4 Google busy reads on the Google phone: every picked read calendar in parallel (8 s each, 12 s in
 * all), all pages. Not busy: cancelled, transparent, declined by me, and RisiMe copies (a private property
 * `risi_event_id`: the Risi Calendar already counts those). Any calendar failing fails the whole source: it is
 * never an empty calendar. The report carries refs and counts only: never a name, a Google id or a title.
 */

/** The blocks and the `google_api` source entry; [reauth] means the link must go to `reauth_needed`. */
data class GcalRead(val blocks: List<CalendarBlock>, val report: GoogleSourceReport, val reauth: Boolean = false)

class GcalBusy(
    private val api: GcalApi,
    private val dao: GcalDao,
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val SOURCE = "google_api"

        fun isBusy(e: GEvent): Boolean =
            e.status != "cancelled" && e.transparency != "transparent" && !e.selfDeclined && "risi_event_id" !in e.private

        fun failed(reason: String, reauth: Boolean = false) = GcalRead(emptyList(), GoogleSourceReport(SOURCE, emptyList(), false, reason), reauth)

        /** The worst reason first: reconnecting beats a timeout beats no network beats a plain API error. */
        fun reasonOf(errs: List<GcalErr>): String = when {
            GcalErr.REAUTH in errs -> GcalReadReasons.REAUTH_NEEDED
            GcalErr.TIMEOUT in errs -> GcalReadReasons.TIMEOUT
            GcalErr.NETWORK in errs -> GcalReadReasons.NETWORK
            else -> GcalReadReasons.API_ERROR
        }
    }

    /** The picked read calendars as they are now. */
    suspend fun readCalendars(): List<GcalCalendarEntity> = dao.calendars().filter { it.read }

    suspend fun read(fromMs: Long, toMs: Long): GcalRead {
        val cals = readCalendars()
        if (cals.isEmpty()) return failed(GcalReadReasons.NO_CALENDARS)
        val all = withTimeoutOrNull(GcalApi.TOTAL_TIMEOUT_MS) {
            coroutineScope { cals.map { c -> async { c to api.busyEvents(c.calendarId, fromMs, toMs) } }.awaitAll() }
        } ?: return failed(GcalReadReasons.TIMEOUT).also { log("gcal busy: timeout") }
        val errs = all.mapNotNull { (it.second as? GcalResult.Fail)?.err }
        if (errs.isNotEmpty()) {
            log("gcal busy: failed (${errs.joinToString(",") { it.name }})")
            return failed(reasonOf(errs), reauth = GcalErr.REAUTH in errs)
        }
        val rows = ArrayList<BusyRow>()
        val counts = ArrayList<GoogleSourceCalendar>()
        for ((c, r) in all) {
            val busy = (r as GcalResult.Ok).value.filter(::isBusy)
            counts += GoogleSourceCalendar(c.ref, busy.size)
            busy.mapTo(rows) { BusyRow(it.startMs, it.endMs, it.allDay, busy = true) }
        }
        return GcalRead(PhoneCalendar.merge(rows, fromMs, toMs), GoogleSourceReport(SOURCE, counts, true, null))
    }
}
