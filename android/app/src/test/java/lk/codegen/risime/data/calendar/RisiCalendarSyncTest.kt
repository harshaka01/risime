package lk.codegen.risime.data.calendar

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiCalendarCard
import lk.codegen.risime.net.RisiCalendarChangesReply
import lk.codegen.risime.net.RisiCalendarEventsReply
import lk.codegen.risime.net.RisiCalendarSettingsReply
import lk.codegen.risime.net.RisiEvent
import lk.codegen.risime.net.RisiEventReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * §29.3/§29.6 the cache and its sync: a first sync lists the window, later ones follow the cursor feed
 * page by page, `410 cursor_expired` lists again, a known cursor is a no-op, `removed` drops an event,
 * `event_update` patches a newer view silently; writes are versioned and a 409 reloads (and, for an
 * answer whose time still matches, retries once). Nothing is called while the switch is off.
 */
class RisiCalendarSyncTest {
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val ev = "5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b"
    private val ev3 = "7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d"
    private val now = Instant.parse("2026-10-09T15:11:00Z").toEpochMilli()

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("fixtures/risi_calendar/$name")!!.readText()
    private fun list() = ProtocolJson.decodeFromString(RisiCalendarEventsReply.serializer(), fixture("risi_calendar_events_reply.json"))
    private fun changes(name: String) = ProtocolJson.decodeFromString(RisiCalendarChangesReply.serializer(), fixture(name))

    private fun err(status: Int, code: String) = ApiResult.Error(status, code, "")

    private class Remote : RisiCalendarRemote {
        val calls = mutableListOf<String>()
        var lists = ArrayDeque<ApiResult<RisiCalendarEventsReply>>()
        var feed = ArrayDeque<ApiResult<RisiCalendarChangesReply>>()
        var get = ArrayDeque<ApiResult<RisiEventReply>>()
        var writes = ArrayDeque<ApiResult<RisiEventReply>>()
        var deleted: ApiResult<Unit> = ApiResult.Ok(Unit)
        val bodies = mutableListOf<JsonObject>()
        override suspend fun events(from: String, to: String) = lists.removeFirst().also { calls += "list:$from..$to" }
        override suspend fun changes(since: String, limit: Int) = feed.removeFirst().also { calls += "changes:$since" }
        override suspend fun event(id: String) = get.removeFirst().also { calls += "get:$id" }
        override suspend fun create(body: JsonObject) = writes.removeFirst().also { calls += "create"; bodies += body }
        override suspend fun patch(id: String, body: JsonObject) = writes.removeFirst().also { calls += "patch:$id"; bodies += body }
        override suspend fun delete(id: String) = deleted.also { calls += "delete:$id" }
        override suspend fun respond(id: String, body: JsonObject) = writes.removeFirst().also { calls += "respond:$id"; bodies += body }
        override suspend fun resolve(suggestionId: String, body: JsonObject) = writes.removeFirst().also { calls += "resolve:$suggestionId"; bodies += body }
        override suspend fun settings(): ApiResult<RisiCalendarSettingsReply> = ApiResult.Ok(RisiCalendarSettingsReply())
        override suspend fun patchSettings(body: JsonObject): ApiResult<RisiCalendarSettingsReply> = ApiResult.Ok(RisiCalendarSettingsReply())
    }

    private fun cal(remote: Remote, store: MemoryCalendarStore = MemoryCalendarStore(), on: () -> Boolean = { true }) =
        RisiCalendar(remote, store, enabled = on, me = { me }, zone = { "Asia/Colombo" }, now = { now }, newId = { "id-1" })

    @Test fun firstSyncListsTheWindowThenFollowsTheFeed() = runBlocking {
        val r = Remote()
        val store = MemoryCalendarStore()
        val c = cal(r, store)
        r.lists += ApiResult.Ok(list())
        assertEquals(SyncOutcome.RELISTED, c.sync())
        assertEquals("list:2026-09-09T15:11:00.000Z..2026-12-10T15:11:00.000Z", r.calls.single())
        assertEquals(3, store.all().size)
        assertEquals("c1.000000000042", store.cursor())

        // Two pages: has_more, then the last one (changed + removed).
        r.feed += ApiResult.Ok(changes("risi_calendar_changes_page1.json"))
        r.feed += ApiResult.Ok(changes("risi_calendar_changes_reply.json"))
        assertEquals(SyncOutcome.SYNCED, c.sync())
        assertEquals(listOf("changes:c1.000000000042", "changes:c1.000000000043"), r.calls.drop(1))
        assertEquals("c1.000000000045", store.cursor())
        val ids = store.all().map { it.eventId }.toSet()
        assertTrue(ev3 !in ids)
        val iv = store.all().first { it.eventId == ev }
        assertEquals(2, iv.version)
        assertEquals("accepted", iv.myStatus)
    }

    @Test fun anExpiredCursorListsAgainAndContinuesFromThatCursor() = runBlocking {
        val r = Remote()
        val store = MemoryCalendarStore()
        store.replaceAll(emptyList(), "c1.old")
        val c = cal(r, store)
        r.feed += err(410, "cursor_expired")
        r.lists += ApiResult.Ok(list())
        assertEquals(SyncOutcome.RELISTED, c.sync())
        assertEquals("c1.000000000042", store.cursor())
        assertEquals(3, store.all().size)
    }

    @Test fun theInboxEventSyncsOnlyWhenTheCursorMoved() = runBlocking {
        val r = Remote()
        val store = MemoryCalendarStore()
        store.replaceAll(list().events, "c1.000000000045")
        val c = cal(r, store)
        assertEquals(SyncOutcome.SYNCED, c.onChanged("c1.000000000045"))
        assertTrue(r.calls.isEmpty())
        r.feed += ApiResult.Ok(RisiCalendarChangesReply(emptyList(), "c1.000000000046", false))
        c.onChanged("c1.000000000046")
        assertEquals(listOf("changes:c1.000000000045"), r.calls)
        assertEquals("c1.000000000046", store.cursor())
    }

    @Test fun offMeansNoCallsAtAll() = runBlocking {
        val r = Remote()
        val c = cal(r, on = { false })
        assertEquals(SyncOutcome.OFF, c.sync())
        assertEquals(SyncOutcome.OFF, c.onChanged("x"))
        assertTrue(c.create("x", now, now + 1, false) is CalendarResult.Failed)
        assertTrue(r.calls.isEmpty())
    }

    @Test fun aFailedFeedKeepsTheCacheAndCursor() = runBlocking {
        val r = Remote()
        val store = MemoryCalendarStore()
        store.replaceAll(list().events, "c1.k")
        r.feed += err(503, "agent_unavailable")
        assertEquals(SyncOutcome.FAILED, cal(r, store).sync())
        assertEquals("c1.k", store.cursor())
        assertEquals(3, store.all().size)
    }

    @Test fun anEventUpdatePatchesANewerViewSilently() = runBlocking {
        val store = MemoryCalendarStore()
        store.replaceAll(list().events, "c")
        val c = cal(Remote(), store)
        val upd = RisiCalendarCard.parse(ProtocolJson.parseToJsonElement(fixture("envelope_risi_event_update_cancelled.json")).let { (it as JsonObject)["risi"].toString() })!!
        assertTrue(c.applyUpdate(upd))
        val e = store.all().first { it.eventId == ev }
        assertEquals(3, e.version)
        assertTrue(e.cancelled)
        // An older update changes nothing.
        val old = RisiCalendarCard.parse(ProtocolJson.parseToJsonElement(fixture("envelope_risi_event_update.json")).let { (it as JsonObject)["risi"].toString() })!!
        assertTrue(!c.applyUpdate(old))
        assertEquals(3, store.all().first { it.eventId == ev }.version)
    }

    @Test fun aStalePatchReloadsTheEvent() = runBlocking {
        val r = Remote()
        val store = MemoryCalendarStore()
        store.replaceAll(list().events, "c")
        val current = list().events[0].copy(version = 3, title = "Interview (moved)")
        r.writes += err(409, "version_conflict")
        r.get += ApiResult.Ok(RisiEventReply(current))
        val res = cal(r, store).patch(ev, lk.codegen.risime.net.RisiCalendarBodies.patch(1, title = "New"))
        assertTrue(res is CalendarResult.Conflict)
        assertEquals("Interview (moved)", (res as CalendarResult.Conflict).current!!.title)
        assertEquals("Interview (moved)", store.all().first { it.eventId == ev }.title)
        assertEquals(listOf("patch:$ev", "get:$ev"), r.calls)
    }

    @Test fun aStaleAnswerRetriesOnceWhenTheTimeStillMatches() = runBlocking {
        val r = Remote()
        val store = MemoryCalendarStore()
        store.replaceAll(list().events, "c")
        val iv = list().events[0]
        val v2 = iv.copy(version = 2)
        r.writes += err(409, "version_conflict")
        r.get += ApiResult.Ok(RisiEventReply(v2))
        r.writes += ApiResult.Ok(RisiEventReply(v2.copy(version = 3, myStatus = "accepted")))
        val res = cal(r, store).respond(ev, "accept", 1, iv.start, iv.end)
        assertTrue(res is CalendarResult.Ok)
        assertEquals(listOf(1, 2), r.bodies.map { it["version"]!!.jsonPrimitive.int })
        assertEquals("accepted", store.all().first { it.eventId == ev }.myStatus)
    }

    @Test fun aStaleAnswerForAMovedEventIsNotSent() = runBlocking {
        val r = Remote()
        val store = MemoryCalendarStore()
        store.replaceAll(list().events, "c")
        val iv = list().events[0]
        r.writes += err(409, "version_conflict")
        r.get += ApiResult.Ok(RisiEventReply(iv.copy(version = 2, start = "2026-10-13T08:30:00.000Z", end = "2026-10-13T09:30:00.000Z")))
        val res = cal(r, store).respond(ev, "accept", 1, iv.start, iv.end)
        assertTrue(res is CalendarResult.Conflict)
        assertEquals(1, r.bodies.size)
    }

    @Test fun deleteCancelsMineAndRemovesAnInvite() = runBlocking {
        val r = Remote()
        val store = MemoryCalendarStore()
        store.replaceAll(list().events, "c")
        val c = cal(r, store)
        val mine = store.all().first { it.owner == me }
        assertTrue(c.delete(mine) is CalendarResult.Ok)
        assertTrue(store.all().first { it.eventId == mine.eventId }.cancelled)
        val theirs = store.all().first { it.eventId == ev }
        assertTrue(c.delete(theirs) is CalendarResult.Ok)
        assertNull(store.all().firstOrNull { it.eventId == ev })
    }

    @Test fun createSendsTheDefaultsAndCachesTheReply() = runBlocking {
        val r = Remote()
        val store = MemoryCalendarStore()
        val made = list().events[1]
        r.writes += ApiResult.Ok(RisiEventReply(made))
        val start = Instant.parse("2026-10-12T09:00:00Z").toEpochMilli()
        val res = cal(r, store).create(" Standup ", start, start + 3_600_000, false)
        assertTrue(res is CalendarResult.Ok)
        val body = r.bodies.single()
        assertEquals("Standup", body["title"]!!.jsonPrimitive.content)
        assertEquals("Asia/Colombo", body["tz"]!!.jsonPrimitive.content)
        assertEquals(30, body["reminder_min"]!!.jsonPrimitive.int)
        assertEquals("id-1", body["client_event_id"]!!.jsonPrimitive.content)
        assertEquals(listOf(made.eventId), store.events.first().map(RisiEvent::eventId))
    }
}
