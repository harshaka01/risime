package lk.codegen.risime.data.gcal

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lk.codegen.risime.data.db.GcalCalendarEntity
import lk.codegen.risime.data.db.GcalCopyEntity
import lk.codegen.risime.data.db.GcalCopyState
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.GcalLinkReasons
import lk.codegen.risime.net.GcalLinkStates
import lk.codegen.risime.net.GoogleCalendarLinkData
import lk.codegen.risime.net.GoogleLink
import lk.codegen.risime.net.GoogleLinkPut
import lk.codegen.risime.net.GoogleLinkReply
import lk.codegen.risime.net.RisiEvent
import lk.codegen.risime.net.RisiEventStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A scripted server for `/risi/calendar/google`. */
class FakeRest : GcalRest {
    var link = GoogleLink.NONE
    val puts = mutableListOf<GoogleLinkPut>()
    var deletedWith: Boolean? = null
    var failPut: ApiResult<GoogleLinkReply>? = null
    override suspend fun get() = ApiResult.Ok(GoogleLinkReply(link))
    override suspend fun put(body: GoogleLinkPut): ApiResult<GoogleLinkReply> {
        failPut?.let { return it }
        puts += body
        val cur = link
        if (!body.connect && !cur.linked) return ApiResult.Error(409, "not_google_device", "x")
        link = GoogleLink(body.state, "me", "Pixel 8", body.readCalendars, body.writeCalendar, body.mirror, "t", "t")
        return ApiResult.Ok(GoogleLinkReply(link))
    }
    override suspend fun delete(removeCopies: Boolean): ApiResult<Unit> { deletedWith = removeCopies; link = GoogleLink.NONE; return ApiResult.Ok(Unit) }
}

class GcalLinkTest {
    private lateinit var g: FakeGoogle
    private lateinit var dao: MemGcalDao
    private lateinit var rest: FakeRest
    private lateinit var auth: FakeAuthorizer
    private lateinit var m: GcalLinkManager
    private var skillOff = false
    private var enabledSkill = 0
    private var scheduled = mutableListOf<Boolean>()
    private var events = mutableListOf<RisiEvent>()
    private var refN = 0
    private val cals = listOf(
        GCalendar("work", "Work", primary = true, accessRole = "owner", selected = true, hidden = false),
        GCalendar("pers", "Personal", primary = false, accessRole = "owner", selected = true, hidden = false),
        GCalendar("hol", "Holidays", primary = false, accessRole = "reader", selected = false, hidden = true),
    )

    @Before fun setUp() {
        g = FakeGoogle()
        dao = MemGcalDao()
        rest = FakeRest()
        auth = FakeAuthorizer(mutableListOf(g.token))
        val api = g.api(auth)
        val copies = GcalCopies(api, dao, events = { events.toList() }, config = { m.copyConfig(true) })
        m = GcalLinkManager(rest, auth, api, dao, copies, myDevice = { "me" }, skillOff = { skillOff }, enableSkill = { enabledSkill++; true }, scheduleCopy = { scheduled += it }, newRef = { "g%07d".format(++refN) })
    }

    @After fun tearDown() = g.shutdown()

    private fun sel(read: Set<String> = setOf("work", "pers"), write: String? = "work", mirror: Boolean = true) = GcalSelection(cals, read, write, mirror)

    @Test fun defaultsArePrimaryPlusShownAndWritePrimary() {
        assertEquals(setOf("work", "pers"), GcalLinkManager.defaultRead(cals))
        assertEquals("work", GcalLinkManager.defaultWrite(cals))
        assertEquals(10, GcalLinkManager.defaultRead((1..15).map { GCalendar("c$it", "c$it", false, "owner", true, false) }).size)
    }

    @Test fun connectStoresPicksWithFreshRefsPutsCountsOnlyAndTurnsTheSkillOn() = runBlocking {
        skillOff = true
        assertEquals(GcalOutcome.Ok, m.connect(sel(), "a@test"))
        val rows = dao.calendars()
        assertEquals(2, rows.size)
        assertTrue(rows.all { Regex("^g[a-z0-9]{7}$").matches(it.ref) })
        assertEquals("work", rows.single { it.write }.calendarId)
        assertEquals(GoogleLinkPut(true, "connected", 2, true, true), rest.puts.single())
        assertEquals(1, enabledSkill)
        assertEquals(listOf(true), scheduled)
        // a new connect gets new refs
        val before = rows.map { it.ref }.toSet()
        m.connect(sel(), "a@test")
        assertTrue(dao.calendars().none { it.ref in before })
    }

    @Test fun aFailedPutLeavesNoLocalPicks() = runBlocking {
        rest.failPut = ApiResult.Error(503, "agent_unavailable", "x")
        assertEquals(GcalOutcome.Failed("agent_unavailable"), m.connect(sel(), "a@test"))
        assertTrue(dao.calendars().isEmpty())
        assertEquals(0, enabledSkill)
    }

    @Test fun changeKeepsRefsOfCalendarsThatStay() = runBlocking {
        m.connect(sel(), "a@test")
        val ref = dao.calendars().single { it.calendarId == "work" }.ref
        m.change(sel(read = setOf("work"), write = "work"), null)
        assertEquals(ref, dao.calendars().single { it.calendarId == "work" }.ref)
        assertEquals(1, dao.calendars().size)
        assertEquals("a@test", dao.calendars().single().account)
        assertEquals(1, rest.puts.last().readCalendars)
    }

    @Test fun reauthPutsTheStateAndReconnectKeepsThePicksAndRunsAReconcile() = runBlocking {
        m.connect(sel(), "a@test")
        scheduled.clear()
        m.markReauth()
        assertEquals("reauth_needed", rest.link.state)
        assertTrue(m.localReauth.value)
        assertEquals(null, m.copyConfig(true))
        val refs = dao.calendars().map { it.ref }
        assertEquals(GcalOutcome.Ok, m.reconnected())
        assertEquals("connected", rest.link.state)
        assertFalse(m.localReauth.value)
        assertEquals(refs, dao.calendars().map { it.ref })
        assertEquals(listOf(true), scheduled)
        assertEquals(GcalCopyConfig("work", true), m.copyConfig(true))
    }

    @Test fun disconnectOnTheGoogleDeviceRemovesCopiesRevokesAndEmptiesTheTables() = runBlocking {
        events += RisiEvent(eventId = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", title = "X", start = "2099-01-01T10:00:00.000Z", end = "2099-01-01T11:00:00.000Z", myStatus = RisiEventStatus.ACCEPTED)
        m.connect(sel(), "a@test")
        m.refresh()
        m.copies().run(false)
        assertEquals(1, g.live("work").size)
        assertEquals(GcalOutcome.Ok, m.disconnect(removeCopies = true))
        assertTrue(g.live("work").isEmpty())
        assertEquals(1, auth.revoked)
        assertTrue(dao.calendars().isEmpty() && dao.copies().isEmpty())
        assertEquals(true, rest.deletedWith)
        assertEquals(GoogleLink.NONE, m.link.value)
    }

    @Test fun disconnectKeepingCopiesLeavesThemInGoogle() = runBlocking {
        events += RisiEvent(eventId = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", title = "X", start = "2099-01-01T10:00:00.000Z", end = "2099-01-01T11:00:00.000Z", myStatus = RisiEventStatus.ACCEPTED)
        m.connect(sel(), "a@test"); m.refresh()
        m.copies().run(false)
        m.disconnect(removeCopies = false)
        assertEquals(1, g.live("work").size)
        assertTrue(dao.calendars().isEmpty())
    }

    @Test fun anotherDevicesDisconnectEventMakesTheGoogleDeviceDoStepsOneToThree() = runBlocking {
        events += RisiEvent(eventId = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", title = "X", start = "2099-01-01T10:00:00.000Z", end = "2099-01-01T11:00:00.000Z", myStatus = RisiEventStatus.ACCEPTED)
        m.connect(sel(), "a@test"); m.refresh()
        m.copies().run(false)
        rest.link = GoogleLink.NONE // the other device deleted the link
        m.onLinkEvent(GoogleCalendarLinkData("not_connected", "me", GcalLinkReasons.DISCONNECTED, removeCopies = true))
        assertTrue(g.live("work").isEmpty())
        assertEquals(1, auth.revoked)
        assertTrue(dao.calendars().isEmpty() && dao.copies().isEmpty())
    }

    @Test fun aDisconnectedEventForAnotherDeviceChangesNothingHere() = runBlocking {
        m.connect(sel(), "a@test"); m.refresh()
        m.onLinkEvent(GoogleCalendarLinkData("not_connected", "someone-else", GcalLinkReasons.DISCONNECTED, removeCopies = true))
        assertEquals(2, dao.calendars().size)
        assertEquals(0, auth.revoked)
    }

    @Test fun replacedDeletesLocalDataNeverRevokesAndKeepsTheCopies() = runBlocking {
        events += RisiEvent(eventId = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", title = "X", start = "2099-01-01T10:00:00.000Z", end = "2099-01-01T11:00:00.000Z", myStatus = RisiEventStatus.ACCEPTED)
        m.connect(sel(), "a@test"); m.refresh()
        m.copies().run(false)
        rest.link = GoogleLink("connected", "the-new-device", "Pixel 9", 2, true, true, "t", "t")
        m.onLinkEvent(GoogleCalendarLinkData("connected", "the-new-device", GcalLinkReasons.REPLACED))
        assertEquals(0, auth.revoked)
        assertTrue(dao.calendars().isEmpty() && dao.copies().isEmpty())
        assertEquals(1, g.live("work").size)
    }

    @Test fun anOfflineDeviceThatMissedTheDisconnectCleansUpOnTheNextGet() = runBlocking {
        m.connect(sel(), "a@test")
        rest.link = GoogleLink.NONE
        m.refresh()
        assertTrue(dao.calendars().isEmpty())
        assertEquals(1, auth.revoked)
    }

    @Test fun logoutRevokesAndWipes() = runBlocking {
        m.connect(sel(), "a@test")
        m.onLogout()
        assertTrue(dao.calendars().isEmpty())
        assertEquals(1, auth.revoked)
    }

    @Test fun theSectionStatesFollowSection314Of312() {
        val me = "me"
        fun s(l: GoogleLink, reauth: Boolean = false, off: Boolean = false, local: Boolean = true, on: Boolean = true) = m.section(l, me, reauth, off, local, on, "Work", 2, null)
        assertEquals(GcalSection.Hidden, s(GoogleLink.NONE, on = false))
        assertEquals(GcalSection.NotConnected, s(GoogleLink.NONE, local = false))
        assertEquals(GcalSection.Connected(2, "Work", null), s(GoogleLink("connected", "me", "P", 2, true, true, null, null)))
        assertEquals(GcalSection.OnOther("Pixel 8"), s(GoogleLink("connected", "x", "Pixel 8", 2, true, true, null, null)))
        assertEquals(GcalSection.NeedsReconnecting, s(GoogleLink("reauth_needed", "me", "P", 2, true, true, null, null)))
        assertEquals(GcalSection.NeedsReconnecting, s(GoogleLink("connected", "me", "P", 2, true, true, null, null), reauth = true))
        assertEquals(GcalSection.Paused, s(GoogleLink("paused", "me", "P", 2, true, true, null, null)))
        assertEquals(GcalSection.Paused, s(GoogleLink("connected", "me", "P", 2, true, true, null, null), off = true))
    }

    private fun GcalLinkManager.copies() = copiesForTest
    private val copiesForTest get() = GcalCopies(g.api(auth), dao, events = { events.toList() }, config = { m.copyConfig(true) })
}
