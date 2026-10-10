package lk.codegen.risime.data.gcal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lk.codegen.risime.data.db.GcalCopyEntity
import lk.codegen.risime.data.db.GcalCopyState
import lk.codegen.risime.data.db.GcalDao
import lk.codegen.risime.net.RisiEvent
import lk.codegen.risime.net.RisiEventStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * v1.31 §31.6 copies: Risi Calendar -> Google (the Google device only). Accepted, active events are written to
 * the ONE write calendar with a fixed id ("risi" + hex32(event_id)), the tag risime=1, no attendees,
 * sendUpdates=none. Edits made in Google are never copied back; a copy the user deleted in Google is never
 * added again (until [Add again]). RisiMe touches only events that carry risime=1.
 */

/** What the copy job needs to know right now (null from the provider: do nothing, the link is not usable). */
data class GcalCopyConfig(val writeCalendarId: String, val mirror: Boolean)

/** How a run ended: [REAUTH] pauses everything (the link goes to `reauth_needed`); [RETRY] backs off; [DONE] is fine. */
enum class CopyOutcome { DONE, RETRY, REAUTH, IDLE }

class GcalCopies(
    private val api: GcalApi,
    private val dao: GcalDao,
    /** The synced Risi Calendar cache (§29.6). */
    private val events: suspend () -> List<RisiEvent>,
    /** Null: the link is not usable (not connected on this phone, `reauth_needed`, `paused`, key absent, no write calendar). */
    private val config: suspend () -> GcalCopyConfig?,
    private val me: suspend () -> String? = { null },
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val TAG_KEY = "risime"
        const val DESCRIPTION_SENTENCE = "Added by RisiMe. Change it in RisiMe: changes made here are not copied back."
        const val DAY_MS = 86_400_000L

        /** The reconcile window: now - 1 day ... now + 92 days. */
        const val BACK_DAYS = 1L
        const val AHEAD_DAYS = 92L

        /** The Risi Calendar cache covers 62 days ahead (§29.6), so copies beyond it are not orphans. */
        const val CACHE_AHEAD_DAYS = 62L

        /** A local-only state: the Risi event was removed from the feed; the next run deletes its copy. */
        const val REMOVE_PENDING = "remove_pending"

        fun googleId(eventId: String): String = "risi" + eventId.lowercase().replace("-", "")

        fun isCopyId(id: String): Boolean = Regex("^risi[0-9a-f]{32}$").matches(id)

        fun ms(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
    }

    private val lock = Mutex()

    /** §31.6 which events are copied: active, accepted by me, not removed, ending after now - 1 day. */
    fun wanted(e: RisiEvent): Boolean {
        if (!eligible(e)) return false
        val end = ms(e.end) ?: return false
        return end > now() - DAY_MS
    }

    /** Active and accepted by me (a copy of an event that stays eligible is never deleted just because it is in the past). */
    fun eligible(e: RisiEvent): Boolean = !e.cancelled && e.state == RisiEvent.STATE_ACTIVE && e.myStatus == RisiEventStatus.ACCEPTED

    /** The §31.6 copy body (`POST`, `PUT`; a `PATCH` uses [patchBody]). */
    fun body(e: RisiEvent): JsonObject = buildJsonObject {
        put("id", googleId(e.eventId))
        fields(e, this)
        put("transparency", "opaque")
        put("reminders", buildJsonObject { put("useDefault", false); put("overrides", buildJsonArray { }) })
    }

    fun patchBody(e: RisiEvent): JsonObject = buildJsonObject { fields(e, this) }

    private fun fields(e: RisiEvent, b: kotlinx.serialization.json.JsonObjectBuilder) {
        b.put("summary", e.title)
        val notes = e.notes?.trim().orEmpty()
        b.put("description", if (notes.isEmpty()) DESCRIPTION_SENTENCE else notes + "\n\n" + DESCRIPTION_SENTENCE)
        val tz = e.tz?.takeIf { runCatching { ZoneId.of(it) }.isSuccess } ?: zone().id
        val z = ZoneId.of(tz)
        if (e.allDay) {
            val s = Instant.ofEpochMilli(ms(e.start) ?: 0).atZone(z)
            val en = Instant.ofEpochMilli(ms(e.end) ?: 0).atZone(z)
            var d1: LocalDate = en.toLocalDate()
            if (en.toLocalTime().toSecondOfDay() != 0) d1 = d1.plusDays(1)
            if (!d1.isAfter(s.toLocalDate())) d1 = s.toLocalDate().plusDays(1)
            b.put("start", buildJsonObject { put("date", s.toLocalDate().toString()) })
            b.put("end", buildJsonObject { put("date", d1.toString()) })
        } else {
            b.put("start", buildJsonObject { put("dateTime", e.start); put("timeZone", tz) })
            b.put("end", buildJsonObject { put("dateTime", e.end); put("timeZone", tz) })
        }
        b.put("extendedProperties", buildJsonObject {
            put("private", buildJsonObject {
                put(TAG_KEY, "1")
                put("risi_event_id", e.eventId.lowercase())
                put("risi_version", e.version.toString())
            })
        })
    }

    /**
     * A full run: reconcile first (adopt existing copies by id, notice copies the user deleted in Google,
     * delete orphans), then bring every event in step. [full] false skips the reconcile (a change was applied).
     */
    suspend fun run(full: Boolean): CopyOutcome = lock.withLock {
        val cfg = config() ?: return@withLock CopyOutcome.IDLE
        if (!cfg.mirror) return@withLock CopyOutcome.IDLE
        val all = events()
        if (full) when (val r = reconcile(cfg, all)) { CopyOutcome.DONE -> {}; else -> return@withLock r }
        var retry = false
        val byId = dao.copies().associateBy { it.eventId.lowercase() }
        for (e in all) {
            val id = e.eventId.lowercase()
            val entry = byId[id]
            val r = if (wanted(e)) ensure(cfg, e, entry) else if (!eligible(e)) retire(cfg, e, entry) else CopyOutcome.DONE
            when (r) {
                CopyOutcome.REAUTH -> return@withLock CopyOutcome.REAUTH
                CopyOutcome.RETRY -> retry = true
                else -> {}
            }
        }
        // Entries whose Risi event left the feed (a `removed` change): delete the copy.
        val known = all.map { it.eventId.lowercase() }.toSet()
        for (entry in dao.copies()) {
            if (entry.eventId.lowercase() in known || entry.state != REMOVE_PENDING) continue
            when (deleteCopy(entry)) {
                CopyOutcome.REAUTH -> return@withLock CopyOutcome.REAUTH
                CopyOutcome.RETRY -> retry = true
                else -> {}
            }
        }
        if (retry) CopyOutcome.RETRY else CopyOutcome.DONE
    }

    /** The feed removed these events (no longer in the cache): their copies are deleted by the next run. */
    suspend fun onRemoved(ids: List<String>) {
        for (id in ids) dao.copy(id.lowercase())?.takeIf { it.state == GcalCopyState.COPIED }?.let { dao.upsertCopy(it.copy(state = REMOVE_PENDING, updatedAt = now())) }
    }

    private suspend fun set(entry: GcalCopyEntity?, e: RisiEvent, calendarId: String, state: String) =
        dao.upsertCopy(GcalCopyEntity(e.eventId.lowercase(), calendarId, googleId(e.eventId), e.version, state, now()))

    /** A wanted event: make sure its copy exists, is current and sits in the write calendar. */
    private suspend fun ensure(cfg: GcalCopyConfig, e: RisiEvent, entry: GcalCopyEntity?): CopyOutcome {
        val gid = googleId(e.eventId)
        when (entry?.state) {
            GcalCopyState.DELETED_IN_GOOGLE -> return CopyOutcome.DONE
            GcalCopyState.COPIED -> {
                var cal = entry.calendarId
                if (cal != cfg.writeCalendarId) {
                    when (val m = api.move(cal, gid, cfg.writeCalendarId)) {
                        is GcalResult.Ok -> { cal = cfg.writeCalendarId; dao.upsertCopy(entry.copy(calendarId = cal, updatedAt = now())) }
                        is GcalResult.Fail -> return fail(m, e, entry)
                    }
                }
                if (entry.risiVersion == e.version) return CopyOutcome.DONE
                return when (val p = api.patch(cal, gid, patchBody(e))) {
                    is GcalResult.Ok -> {
                        if (p.value["status"]?.jsonPrimitive?.contentOrNull == "cancelled") set(entry, e, cal, GcalCopyState.DELETED_IN_GOOGLE)
                        else set(entry, e, cal, GcalCopyState.COPIED)
                        CopyOutcome.DONE
                    }
                    is GcalResult.Fail -> fail(p, e, entry)
                }
            }
            else -> {}
        }
        // No entry, or removed_by_risi / remove_pending (accepted again): insert; a 409 means the id exists.
        return when (val r = api.insert(cfg.writeCalendarId, body(e))) {
            is GcalResult.Ok -> { set(entry, e, cfg.writeCalendarId, GcalCopyState.COPIED); CopyOutcome.DONE }
            is GcalResult.Fail -> if (r.err == GcalErr.CONFLICT) adopt(cfg, e, entry) else fail(r, e, entry)
        }
    }

    /** §31.6 an insert got 409: GET it. A match is updated with PUT; a cancelled copy is restored only if the map says removed_by_risi. */
    private suspend fun adopt(cfg: GcalCopyConfig, e: RisiEvent, entry: GcalCopyEntity?): CopyOutcome {
        val gid = googleId(e.eventId)
        val got = when (val g = api.get(cfg.writeCalendarId, gid)) {
            is GcalResult.Ok -> g.value
            is GcalResult.Fail -> return fail(g, e, entry)
        }
        val tag = got["extendedProperties"]?.let { (it as? JsonObject)?.get("private") as? JsonObject }
        if (tag?.get("risi_event_id")?.jsonPrimitive?.contentOrNull?.lowercase() != e.eventId.lowercase()) return CopyOutcome.DONE // not ours: left alone
        val cancelled = got["status"]?.jsonPrimitive?.contentOrNull == "cancelled"
        if (cancelled && entry?.state != GcalCopyState.REMOVED_BY_RISI && entry?.state != REMOVE_PENDING) {
            set(entry, e, cfg.writeCalendarId, GcalCopyState.DELETED_IN_GOOGLE)
            return CopyOutcome.DONE
        }
        return when (val p = api.put(cfg.writeCalendarId, gid, body(e).let { JsonObject(it + ("status" to kotlinx.serialization.json.JsonPrimitive("confirmed"))) })) {
            is GcalResult.Ok -> { set(entry, e, cfg.writeCalendarId, GcalCopyState.COPIED); CopyOutcome.DONE }
            is GcalResult.Fail -> fail(p, e, entry)
        }
    }

    /** An event that is no longer wanted: delete its copy (404/410 counts as done). */
    private suspend fun retire(cfg: GcalCopyConfig, e: RisiEvent, entry: GcalCopyEntity?): CopyOutcome {
        if (entry == null || entry.state != GcalCopyState.COPIED) return CopyOutcome.DONE
        return deleteCopy(entry)
    }

    private suspend fun deleteCopy(entry: GcalCopyEntity): CopyOutcome =
        when (val d = api.delete(entry.calendarId, googleId(entry.eventId))) {
            is GcalResult.Ok -> { dao.upsertCopy(entry.copy(state = GcalCopyState.REMOVED_BY_RISI, updatedAt = now())); CopyOutcome.DONE }
            is GcalResult.Fail ->
                if (d.err == GcalErr.GONE) { dao.upsertCopy(entry.copy(state = GcalCopyState.REMOVED_BY_RISI, updatedAt = now())); CopyOutcome.DONE }
                else failOutcome(d)
        }

    /** A failed call: reauth pauses; gone means the user deleted the copy in Google; the rest is retried later. */
    private suspend fun fail(f: GcalResult.Fail, e: RisiEvent, entry: GcalCopyEntity?): CopyOutcome {
        if (f.err == GcalErr.GONE && entry != null && entry.state == GcalCopyState.COPIED) {
            dao.upsertCopy(entry.copy(state = GcalCopyState.DELETED_IN_GOOGLE, updatedAt = now()))
            return CopyOutcome.DONE
        }
        return failOutcome(f)
    }

    private fun failOutcome(f: GcalResult.Fail): CopyOutcome {
        log("gcal copy: failed (${f.err})")
        return if (f.err == GcalErr.REAUTH) CopyOutcome.REAUTH else CopyOutcome.RETRY
    }

    /**
     * §31.6 the reconcile: list this phone's write calendar's tagged copies (now - 1 day ... now + 92 days).
     * Existing copies of wanted events are adopted by id (a new device); a mapped copy that is gone from
     * Google was deleted there; tagged copies whose Risi event is no longer accepted are deleted.
     */
    private suspend fun reconcile(cfg: GcalCopyConfig, all: List<RisiEvent>): CopyOutcome {
        val t = now()
        val tagged = when (val r = api.taggedEvents(cfg.writeCalendarId, t - BACK_DAYS * DAY_MS, t + AHEAD_DAYS * DAY_MS)) {
            is GcalResult.Ok -> r.value
            is GcalResult.Fail -> return failOutcome(r)
        }.filter { isCopyId(it.id) && it.private[TAG_KEY] == "1" && it.status != "cancelled" }
        val inGoogle = tagged.associateBy { it.id }
        val byEvent = all.associateBy { googleId(it.eventId) }
        val entries = dao.copies().associateBy { googleId(it.eventId) }
        // Adopt / notice deletions.
        for (e in all) {
            val gid = googleId(e.eventId)
            val entry = entries[gid]
            if (!wanted(e)) continue
            val g = inGoogle[gid]
            if (g != null && entry == null) {
                dao.upsertCopy(GcalCopyEntity(e.eventId.lowercase(), cfg.writeCalendarId, gid, g.private["risi_version"]?.toIntOrNull() ?: 0, GcalCopyState.COPIED, now()))
            } else if (g == null && entry != null && entry.state == GcalCopyState.COPIED && entry.calendarId == cfg.writeCalendarId) {
                dao.upsertCopy(entry.copy(state = GcalCopyState.DELETED_IN_GOOGLE, updatedAt = now()))
            }
        }
        // Orphans: tagged in Google but not (or no longer) a wanted Risi event. Only within the cache's window.
        for (g in tagged) {
            val e = byEvent[g.id]
            val inCache = e != null
            if (e != null && eligible(e)) continue
            if (!inCache && g.startMs > t + CACHE_AHEAD_DAYS * DAY_MS) continue
            when (val d = api.delete(cfg.writeCalendarId, g.id)) {
                is GcalResult.Ok -> {}
                is GcalResult.Fail -> if (d.err != GcalErr.GONE) return failOutcome(d)
            }
            dao.copy(e?.eventId?.lowercase() ?: continue)?.let { dao.upsertCopy(it.copy(state = GcalCopyState.REMOVED_BY_RISI, updatedAt = now())) }
        }
        return CopyOutcome.DONE
    }

    /** [Add again] on a copy the user deleted in Google. */
    suspend fun addAgain(eventId: String): CopyOutcome {
        val entry = dao.copy(eventId.lowercase()) ?: return CopyOutcome.DONE
        if (entry.state != GcalCopyState.DELETED_IN_GOOGLE) return CopyOutcome.DONE
        dao.upsertCopy(entry.copy(state = GcalCopyState.REMOVED_BY_RISI, updatedAt = now()))
        return run(full = false)
    }

    /** [Add again] for all of them. */
    suspend fun addAllAgain(): CopyOutcome {
        for (c in dao.copies().filter { it.state == GcalCopyState.DELETED_IN_GOOGLE }) dao.upsertCopy(c.copy(state = GcalCopyState.REMOVED_BY_RISI, updatedAt = now()))
        return run(full = false)
    }

    /**
     * Disconnect with [Remove them from Google], or "Remove the N copies" when mirroring is switched off:
     * every tagged copy in [calendarIds] is deleted (404/410 is done). Returns false if one could not be.
     */
    suspend fun removeAll(calendarIds: List<String>): Boolean = lock.withLock {
        var ok = true
        val t = now()
        for (cal in calendarIds.distinct()) {
            val tagged = when (val r = api.taggedEvents(cal, t - 400 * DAY_MS, t + 400 * DAY_MS)) {
                is GcalResult.Ok -> r.value
                is GcalResult.Fail -> { ok = false; continue }
            }.filter { isCopyId(it.id) && it.private[TAG_KEY] == "1" }
            for (g in tagged) {
                when (val d = api.delete(cal, g.id)) {
                    is GcalResult.Ok -> {}
                    is GcalResult.Fail -> if (d.err != GcalErr.GONE) ok = false
                }
            }
        }
        for (c in dao.copies()) if (c.state == GcalCopyState.COPIED) dao.upsertCopy(c.copy(state = GcalCopyState.REMOVED_BY_RISI, updatedAt = now()))
        ok
    }
}
