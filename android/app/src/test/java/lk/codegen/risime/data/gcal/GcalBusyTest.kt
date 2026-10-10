package lk.codegen.risime.data.gcal

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lk.codegen.risime.data.db.GcalCalendarEntity
import lk.codegen.risime.net.GcalReadReasons
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class GcalBusyTest {
    private lateinit var g: FakeGoogle
    private lateinit var dao: MemGcalDao
    private lateinit var busy: GcalBusy
    private val from = Instant.parse("2026-10-12T00:00:00Z").toEpochMilli()
    private val to = Instant.parse("2026-10-13T00:00:00Z").toEpochMilli()

    @Before fun setUp() {
        g = FakeGoogle()
        dao = MemGcalDao()
        runBlocking {
            dao.upsertCalendars(listOf(
                GcalCalendarEntity("work", "g4k2m7qa", "Work", "owner", read = true, write = true, account = "a@test"),
                GcalCalendarEntity("pers", "g9t3b8rc", "Personal", "owner", read = true, write = false, account = "a@test"),
                GcalCalendarEntity("hol", "gzzzzzzz", "Holidays", "reader", read = false, write = false, account = "a@test"),
            ))
        }
        busy = GcalBusy(g.api(), dao)
    }

    @After fun tearDown() = g.shutdown()

    private fun priv(vararg kv: Pair<String, String>) = buildJsonObject {
        put("extendedProperties", buildJsonObject { put("private", buildJsonObject { kv.forEach { (k, v) -> put(k, v) } }) })
    }

    @Test fun blocksAreMergedAcrossCalendarsAndCopiesAndNonBusyAreLeftOut() = runBlocking {
        g.seed("work", "a", "2026-10-12T08:30:00Z", "2026-10-12T09:30:00Z", buildJsonObject { put("summary", "CANARY-GTITLE") })
        g.seed("work", "b", "2026-10-12T09:00:00Z", "2026-10-12T10:00:00Z")
        g.seed("pers", "c", "2026-10-12T15:00:00Z", "2026-10-12T16:00:00Z")
        g.seed("work", "copy", "2026-10-12T11:00:00Z", "2026-10-12T12:00:00Z", priv("risime" to "1", "risi_event_id" to "x"))
        g.seed("work", "free", "2026-10-12T13:00:00Z", "2026-10-12T14:00:00Z", buildJsonObject { put("transparency", "transparent") })
        g.seed("work", "cancelled", "2026-10-12T17:00:00Z", "2026-10-12T18:00:00Z", buildJsonObject { put("status", "cancelled") })
        g.seed("work", "declined", "2026-10-12T19:00:00Z", "2026-10-12T20:00:00Z", buildJsonObject {
            put("attendees", buildJsonArray { add(buildJsonObject { put("self", true); put("responseStatus", "declined") }) })
        })
        g.seed("hol", "holiday", "2026-10-12T05:00:00Z", "2026-10-12T06:00:00Z") // not picked
        val r = busy.read(from, to)
        assertTrue(r.report.readOk)
        assertEquals(listOf("2026-10-12T08:30:00Z" to "2026-10-12T10:00:00Z", "2026-10-12T15:00:00Z" to "2026-10-12T16:00:00Z"), r.blocks.map { it.start to it.end })
        assertEquals(mapOf("g4k2m7qa" to 2, "g9t3b8rc" to 1), r.report.calendars.associate { it.ref to it.events })
    }

    @Test fun theResultNeverCarriesANameAnIdOrATitle() = runBlocking {
        g.seed("work", "a", "2026-10-12T08:30:00Z", "2026-10-12T09:30:00Z", buildJsonObject { put("summary", "CANARY-GTITLE") })
        val r = busy.read(from, to)
        val json = lk.codegen.risime.net.ProtocolJson.encodeToString(lk.codegen.risime.net.GoogleSourceReport.serializer(), r.report) + lk.codegen.risime.net.ProtocolJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(lk.codegen.risime.net.CalendarBlock.serializer()), r.blocks)
        for (s in listOf("CANARY", "Work", "Personal", "work", "pers", "a@test", "\"name\"", "account_type", "\"id\"")) assertFalse("$s in $json", s in json)
    }

    @Test fun anyCalendarFailingFailsTheWholeSource() = runBlocking {
        g.mode = "error500"
        val r = busy.read(from, to)
        assertFalse(r.report.readOk)
        assertEquals(GcalReadReasons.API_ERROR, r.report.reason)
        assertTrue(r.report.calendars.isEmpty() && r.blocks.isEmpty())
    }

    @Test fun aRevokedGrantIsReauthNeededWithTheFlagToPutTheState() = runBlocking {
        g.mode = "revoked"
        val r = busy.read(from, to)
        assertEquals(GcalReadReasons.REAUTH_NEEDED, r.report.reason)
        assertTrue(r.reauth)
    }

    @Test fun noPickedCalendarIsNoCalendars() = runBlocking {
        dao.clearCalendars()
        assertEquals(GcalReadReasons.NO_CALENDARS, busy.read(from, to).report.reason)
    }

    @Test fun allPagesAreRead() = runBlocking {
        for (i in 1..5) g.seed("work", "e$i", "2026-10-12T0$i:00:00Z", "2026-10-12T0$i:30:00Z")
        val r = busy.read(from, to)
        assertEquals(5, r.report.calendars.first { it.ref == "g4k2m7qa" }.events)
    }
}
