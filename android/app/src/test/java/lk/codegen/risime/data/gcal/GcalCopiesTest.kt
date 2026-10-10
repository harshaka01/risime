package lk.codegen.risime.data.gcal

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lk.codegen.risime.data.db.GcalCalendarEntity
import lk.codegen.risime.data.db.GcalCopyState
import lk.codegen.risime.net.RisiEvent
import lk.codegen.risime.net.RisiEventStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class GcalCopiesTest {
    private val now = Instant.parse("2026-10-10T08:00:00Z").toEpochMilli()
    private val e1 = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val e2 = "1a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val gid1 = "risi0a1b2c3d4e5f4a6b8c7d9e0f1a2b3c4d"
    private lateinit var g: FakeGoogle
    private lateinit var dao: MemGcalDao
    private var events = mutableListOf<RisiEvent>()
    private var mirror = true
    private var active = true
    private var write = "work"
    private lateinit var copies: GcalCopies

    private fun ev(id: String, title: String = "Standup", start: String = "2026-10-13T04:30:00.000Z", end: String = "2026-10-13T05:00:00.000Z", status: String = RisiEventStatus.ACCEPTED, version: Int = 1, state: String = RisiEvent.STATE_ACTIVE, notes: String? = null, allDay: Boolean = false) =
        RisiEvent(eventId = id, version = version, title = title, notes = notes, start = start, end = end, allDay = allDay, tz = "Asia/Colombo", myStatus = status, state = state)

    @Before fun setUp() {
        g = FakeGoogle()
        dao = MemGcalDao()
        copies = GcalCopies(g.api(), dao, events = { events.toList() }, config = { if (active) GcalCopyConfig(write, mirror) else null }, now = { now }, zone = { java.time.ZoneId.of("Asia/Colombo") })
    }

    @After fun tearDown() = g.shutdown()

    @Test fun anAcceptedEventIsInsertedWithTheContractFields() = runBlocking {
        events += ev(e1, notes = "Room 4")
        assertEquals(CopyOutcome.DONE, copies.run(full = false))
        val c = g.live("work").single()
        assertEquals(gid1, c["id"]!!.jsonPrimitive.content)
        assertEquals("Standup", c["summary"]!!.jsonPrimitive.content)
        assertEquals("Room 4\n\nAdded by RisiMe. Change it in RisiMe: changes made here are not copied back.", c["description"]!!.jsonPrimitive.content)
        assertEquals("opaque", c["transparency"]!!.jsonPrimitive.content)
        assertEquals("Asia/Colombo", c["start"]!!.jsonObject["timeZone"]!!.jsonPrimitive.content)
        assertEquals(false, c["reminders"]!!.jsonObject["useDefault"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(0, c["reminders"]!!.jsonObject["overrides"]!!.jsonArray.size)
        val p = c["extendedProperties"]!!.jsonObject["private"]!!.jsonObject
        assertEquals("1", p["risime"]!!.jsonPrimitive.content)
        assertEquals(e1, p["risi_event_id"]!!.jsonPrimitive.content)
        assertEquals("1", p["risi_version"]!!.jsonPrimitive.content)
        assertFalse("attendees" in c || "location" in c || "conferenceData" in c)
        assertTrue(g.requests.filter { it.first == "POST" }.all { true })
        assertTrue(g.queries.filter { it.queryParameter("sendUpdates") != null }.all { it.queryParameter("sendUpdates") == "none" })
        assertEquals(GcalCopyState.COPIED, dao.copy(e1)!!.state)
    }

    @Test fun proposedDeclinedAndOldEventsAreNeverCopied() = runBlocking {
        events += ev(e1, status = RisiEventStatus.PROPOSED)
        events += ev(e2, status = RisiEventStatus.DECLINED)
        events += ev("2a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", start = "2026-10-01T04:30:00.000Z", end = "2026-10-01T05:00:00.000Z")
        copies.run(full = false)
        assertTrue(g.live("work").isEmpty())
    }

    @Test fun anAllDayEventUsesDatesWithTheEndTheNextDay() = runBlocking {
        events += ev(e1, start = "2026-10-12T18:30:00.000Z", end = "2026-10-13T18:30:00.000Z", allDay = true)
        copies.run(full = false)
        val c = g.live("work").single()
        assertEquals("2026-10-13", c["start"]!!.jsonObject["date"]!!.jsonPrimitive.content)
        assertEquals("2026-10-14", c["end"]!!.jsonObject["date"]!!.jsonPrimitive.content)
    }

    @Test fun aTimeChangeIsAPatchAndADeclineADelete() = runBlocking {
        events += ev(e1)
        copies.run(false)
        events[0] = ev(e1, start = "2026-10-13T06:00:00.000Z", end = "2026-10-13T06:30:00.000Z", version = 2)
        copies.run(false)
        assertTrue(g.requests.any { it.first == "PATCH" })
        assertEquals("2026-10-13T06:00:00.000Z", g.live("work").single()["start"]!!.jsonObject["dateTime"]!!.jsonPrimitive.content)
        assertEquals(2, dao.copy(e1)!!.risiVersion)
        events[0] = ev(e1, status = RisiEventStatus.DECLINED, version = 3)
        copies.run(false)
        assertTrue(g.live("work").isEmpty())
        assertEquals(GcalCopyState.REMOVED_BY_RISI, dao.copy(e1)!!.state)
    }

    @Test fun aRetryAfterALostReplyMakesNoDuplicate() = runBlocking {
        events += ev(e1)
        // the insert reached Google but the app never saw the reply: no map entry, the event exists
        g.requests.clear()
        copies.run(false)
        dao.deleteCopy(e1)
        copies.run(false)
        assertEquals(1, g.live("work").size)
        assertEquals(GcalCopyState.COPIED, dao.copy(e1)!!.state)
        assertTrue(g.requests.any { it.first == "PUT" })
    }

    @Test fun aCopyDeletedInGoogleIsNotAddedAgainUntilAddAgain() = runBlocking {
        events += ev(e1)
        copies.run(false)
        g.events[("work" to gid1)] = JsonObject(g.events[("work" to gid1)]!! + ("status" to JsonPrimitive("cancelled")))
        events[0] = ev(e1, title = "Standup 2", version = 2)
        copies.run(false) // PATCH answers cancelled -> deleted_in_google
        assertEquals(GcalCopyState.DELETED_IN_GOOGLE, dao.copy(e1)!!.state)
        g.requests.clear()
        copies.run(full = true)
        copies.run(false)
        assertTrue(g.requests.none { it.first == "POST" || it.first == "PUT" || it.first == "PATCH" })
        assertTrue(g.live("work").isEmpty())
        assertEquals(CopyOutcome.DONE, copies.addAgain(e1))
        assertEquals(GcalCopyState.COPIED, dao.copy(e1)!!.state)
        assertEquals(1, g.live("work").size)
    }

    @Test fun aCopyThatVanishedFromGoogleIsFoundByTheReconcile() = runBlocking {
        events += ev(e1)
        copies.run(false)
        g.events.remove("work" to gid1)
        copies.run(full = true)
        assertEquals(GcalCopyState.DELETED_IN_GOOGLE, dao.copy(e1)!!.state)
        assertTrue(g.live("work").isEmpty())
    }

    @Test fun aNewDeviceAdoptsTheExistingCopiesByIdAndTouchesNoOthers() = runBlocking {
        // another device already copied e1; the user's own event and a foreign "risi" lookalike exist too
        g.seed("work", gid1, "2026-10-13T04:30:00.000Z", "2026-10-13T05:00:00.000Z", buildJsonObject {
            put("summary", "Standup")
            put("extendedProperties", buildJsonObject { put("private", buildJsonObject { put("risime", "1"); put("risi_event_id", e1); put("risi_version", "1") }) })
        })
        g.seed("work", "personal1", "2026-10-13T04:30:00.000Z", "2026-10-13T05:00:00.000Z")
        g.seed("work", "risi" + "f".repeat(32), "2026-10-14T04:30:00.000Z", "2026-10-14T05:00:00.000Z") // untagged lookalike
        events += ev(e1)
        copies.run(full = true)
        assertEquals(GcalCopyState.COPIED, dao.copy(e1)!!.state)
        assertTrue(g.requests.none { it.first == "POST" })
        assertEquals(3, g.live("work").size)
        // an event that is no longer accepted: only the TAGGED orphan goes
        g.seed("work", "risi" + "e".repeat(32), "2026-10-15T04:30:00.000Z", "2026-10-15T05:00:00.000Z", buildJsonObject {
            put("extendedProperties", buildJsonObject { put("private", buildJsonObject { put("risime", "1"); put("risi_event_id", "ee") }) })
        })
        copies.run(full = true)
        assertEquals(setOf(gid1, "personal1", "risi" + "f".repeat(32)), g.live("work").map { it["id"]!!.jsonPrimitive.content }.toSet())
    }

    @Test fun changingTheWriteCalendarMovesTheCopies() = runBlocking {
        events += ev(e1)
        copies.run(false)
        write = "other"
        copies.run(false)
        assertEquals(1, g.live("other").size)
        assertTrue(g.live("work").isEmpty())
        assertEquals("other", dao.copy(e1)!!.calendarId)
    }

    @Test fun nothingRunsWhileTheLinkIsNotUsable() = runBlocking {
        events += ev(e1)
        active = false
        assertEquals(CopyOutcome.IDLE, copies.run(true))
        active = true; mirror = false
        assertEquals(CopyOutcome.IDLE, copies.run(true))
        assertTrue(g.requests.isEmpty())
    }

    @Test fun aRevokedGrantEndsTheRunAsReauth() = runBlocking {
        events += ev(e1)
        g.mode = "revoked"
        assertEquals(CopyOutcome.REAUTH, copies.run(false))
        g.mode = "error500"
        assertEquals(CopyOutcome.RETRY, copies.run(false))
    }

    @Test fun aRemovedEventDeletesItsCopyAndRemoveAllClearsEveryTaggedCopy() = runBlocking {
        events += ev(e1)
        events += ev(e2)
        copies.run(false)
        assertEquals(2, g.live("work").size)
        events.removeAt(0)
        copies.onRemoved(listOf(e1))
        copies.run(false)
        assertEquals(1, g.live("work").size)
        g.seed("work", "mine", "2026-10-13T04:30:00.000Z", "2026-10-13T05:00:00.000Z")
        assertTrue(copies.removeAll(listOf("work")))
        assertEquals(listOf("mine"), g.live("work").map { it["id"]!!.jsonPrimitive.content })
        assertNull(dao.copy(e2)?.takeIf { it.state == GcalCopyState.COPIED })
    }
}
