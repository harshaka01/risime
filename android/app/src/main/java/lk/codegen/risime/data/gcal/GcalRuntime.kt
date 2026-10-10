package lk.codegen.risime.data.gcal

import android.app.PendingIntent
import android.content.Context
import lk.codegen.risime.data.db.GcalDao
import lk.codegen.risime.net.GcalLinkStates
import lk.codegen.risime.net.GcalReadReasons
import lk.codegen.risime.net.RisiEvent
import okhttp3.OkHttpClient

/**
 * v1.31 §31: the Google link as the app wires it (one per process). The authorizer and the base URL can be
 * replaced ONLY by the debug source set (the debug broadcast, §31.11): [installTestHooks] is called from nowhere in main.
 */
class GcalRuntime(
    context: Context,
    http: OkHttpClient,
    rest: GcalRest,
    val dao: GcalDao,
    events: suspend () -> List<RisiEvent>,
    myDevice: suspend () -> String?,
    skillOff: () -> Boolean,
    enableSkill: suspend () -> Boolean,
    /** `/auth/config` says `google_calendar: on` and this device advertises `google_calendar`. */
    private val switchOn: () -> Boolean,
    scheduleCopy: (full: Boolean) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val play = PlayAuthorizer(context)

    @Volatile private var override: GcalAuthorizer? = null

    @Volatile private var baseUrlOverride: String? = null

    /** What the app uses: the test seam's fake while installed, else Google Play services. */
    val authorizer: GcalAuthorizer = object : GcalAuthorizer {
        private val cur get() = override ?: play
        override suspend fun authorize() = cur.authorize()
        override suspend fun authorizeInteractive(launch: suspend (PendingIntent) -> android.content.Intent?) = cur.authorizeInteractive(launch)
        override fun invalidate() = cur.invalidate()
        override suspend fun revoke(account: String?, token: String?) = cur.revoke(account, token)
        override fun available() = cur.available()
        override fun accountName() = cur.accountName()
    }

    val api = GcalApi(http, authorizer, baseUrl = { baseUrlOverride ?: GcalApi.BASE_URL })

    val busy = GcalBusy(api, dao, log)

    val copies: GcalCopies

    val manager: GcalLinkManager

    init {
        lateinit var m: GcalLinkManager
        copies = GcalCopies(api, dao, events, config = { m.copyConfig(switchOn()) }, log = log)
        m = GcalLinkManager(rest, authorizer, api, dao, copies, myDevice, skillOff, enableSkill, scheduleCopy, log = log)
        manager = m
    }

    /** The debug seam only (src/debug): a fake authorizer and a loopback base URL; both null again to remove them. */
    fun installTestHooks(authorizer: GcalAuthorizer?, baseUrl: String?) {
        override = authorizer
        baseUrlOverride = baseUrl
    }

    /** The connected Google account while this phone holds the link (null otherwise). */
    suspend fun accountIfMine(me: String?): String? {
        val l = manager.link.value
        if (!manager.isMine(l, me) || l.state != GcalLinkStates.CONNECTED) return null
        return dao.calendars().firstNotNullOfOrNull { it.account } ?: authorizer.accountName()
    }

    /** §31.4: the `google_api` read for a `calendar_check` on this phone (it holds the link when the server sends it here). */
    suspend fun readForCheck(fromMs: Long, toMs: Long, skillOff: Boolean, me: String?): GcalRead {
        if (!switchOn()) return GcalBusy.failed(GcalReadReasons.NOT_CONNECTED)
        if (manager.link.value == lk.codegen.risime.net.GoogleLink.NONE) manager.refresh()
        val l = manager.link.value
        if (!manager.isMine(l, me) || dao.calendars().isEmpty()) return GcalBusy.failed(GcalReadReasons.NOT_CONNECTED)
        if (l.state == GcalLinkStates.REAUTH_NEEDED || manager.localReauth.value) return GcalBusy.failed(GcalReadReasons.REAUTH_NEEDED)
        // `paused` and `no_answer` come from the server only (§31.4); a skill that is off on this phone is declined before we get here.
        val r = busy.read(fromMs, toMs)
        // §31.4 a 401 that a silent authorize does not fix: the link goes to reauth_needed.
        if (r.reauth) runCatching { manager.markReauth() }
        return r
    }

    private data class Cached(val at: Long, val read: GcalRead)

    private val dayCache = java.util.concurrent.ConcurrentHashMap<Pair<Long, Long>, Cached>()

    /**
     * §31.7 the Google side of an event card's day timeline. On the Google phone: that day's busy blocks, read when
     * the card renders and kept in memory for 5 minutes (never stored, no titles); a failed read gives the caption
     * "Google Calendar not checked", never an empty day. On the user's other devices: "Google Calendar is checked on <device>".
     */
    suspend fun timelineFor(startMs: Long, zone: java.time.ZoneId, skillOff: Boolean, me: String?, nowMs: Long = System.currentTimeMillis()): lk.codegen.risime.data.calendar.TimelineGoogle? {
        if (!switchOn()) return null
        val l = manager.link.value
        if (!l.linked) return null
        if (!manager.isMine(l, me)) {
            return lk.codegen.risime.data.calendar.TimelineGoogle(note = "Google Calendar is checked on ${l.deviceName?.takeIf { it.isNotBlank() } ?: "another device"}")
        }
        val notChecked = lk.codegen.risime.data.calendar.TimelineGoogle(note = "Google Calendar not checked")
        if (l.state != GcalLinkStates.CONNECTED || manager.localReauth.value || skillOff || dao.calendars().none { it.read }) return notChecked
        val day = java.time.Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val key = from to to
        val hit = dayCache[key]?.takeIf { nowMs - it.at < 5 * 60_000L }
        val read = hit?.read ?: busy.read(from, to).also { dayCache[key] = Cached(nowMs, it) }
        if (read.reauth) runCatching { manager.markReauth() }
        if (!read.report.readOk) return notChecked
        return lk.codegen.risime.data.calendar.TimelineGoogle(read.blocks.mapNotNull { b ->
            val s = lk.codegen.risime.data.calendar.RisiEventRows.ms(b.start) ?: return@mapNotNull null
            val e = lk.codegen.risime.data.calendar.RisiEventRows.ms(b.end) ?: return@mapNotNull null
            s to e
        })
    }

    /** Drops what the timeline cached (Disconnect, reconnect, picks changed). */
    fun clearDayCache() = dayCache.clear()

    /** The `gcal-copy` job's body: refresh the link if this process doesn't know it yet, run, and pause on a lost grant. */
    suspend fun runCopies(full: Boolean): CopyOutcome {
        if (!switchOn()) return CopyOutcome.IDLE
        if (manager.link.value == lk.codegen.risime.net.GoogleLink.NONE) manager.refresh()
        val out = copies.run(full)
        if (out == CopyOutcome.REAUTH) runCatching { manager.markReauth() }
        return out
    }
}
