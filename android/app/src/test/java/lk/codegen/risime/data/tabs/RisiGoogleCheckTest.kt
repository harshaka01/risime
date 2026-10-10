package lk.codegen.risime.data.tabs

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lk.codegen.risime.data.gcal.GcalBusy
import lk.codegen.risime.data.gcal.GcalRead
import lk.codegen.risime.net.CalendarBlock
import lk.codegen.risime.net.GcalReadReasons
import lk.codegen.risime.net.GoogleSourceCalendar
import lk.codegen.risime.net.GoogleSourceReport
import lk.codegen.risime.net.RisiSkillStates
import lk.codegen.risime.net.RisiToolCall
import lk.codegen.risime.net.RisiToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** v1.31 §31.4 `calendar_check` with `args.sources`: exactly the sources asked for, the strict `google_api` shape, the loop guard. */
class RisiGoogleCheckTest {
    private val states = mutableMapOf("calendar" to RisiSkillStates.ASK)
    private val dao = FakeScheduledDao()
    private val be = FakeCalendarBackend().apply {
        cals += PhoneCalendarInfo(1, "harsha@example.com", "harsha@example.com", "com.google", 700, true, "harsha@example.com", true)
        cals += PhoneCalendarInfo(5, "Phone", "Phone", "LOCAL", 700, true)
    }
    private val cal = PhoneCalendar(be, MemoryCalendarChoice(), CalendarWriteLog(), zone = { ZoneId.of("Asia/Colombo") })
    private var googleRead: GcalRead? = GcalRead(
        listOf(CalendarBlock("2026-10-12T08:30:00Z", "2026-10-12T09:30:00Z", busy = true, allDay = false)),
        GoogleSourceReport("google_api", listOf(GoogleSourceCalendar("g4k2m7qa", 1), GoogleSourceCalendar("g9t3b8rc", 0)), true, null),
    )
    private var asked = 0

    private fun ex(withGoogle: Boolean = true) = RisiToolExecutor(
        me = { "me" }, history = { emptyList() }, risiChats = { emptyList() }, skillState = { states[it] ?: RisiSkillStates.OFF },
        dao = dao, scheduled = ScheduledMessages(dao, FakeArmer(), send = { _, _, _ -> true }, isMember = { true }), setAlarm = { true }, calendar = cal,
        google = if (withGoogle) ({ _, _ -> asked++; googleRead!! }) else null,
        googleAccount = { "harsha@example.com" },
    )

    private fun call(sources: List<String>?) = RisiToolCall(
        toolCallId = "t", tool = "calendar_check", expiresAt = "2026-10-12T00:00:30Z", toDevices = listOf("d"),
        args = JsonObject(buildMap {
            put("from", JsonPrimitive("2026-10-12T02:30:00.000Z")); put("to", JsonPrimitive("2026-10-12T14:30:00.000Z"))
            sources?.let { put("sources", JsonArray(it.map(::JsonPrimitive))) }
        }),
    )

    private fun ms(s: String) = Instant.parse(s).toEpochMilli()

    @Test fun googleOnlyListsExactlyTheGoogleSourceWithRefsOnly() = runBlocking {
        val r = ex().execute(call(listOf("google_api")))
        assertEquals(RisiToolResult.OK, r.status)
        val res = r.result!!
        val sources = res["sources"]!!.jsonArray
        assertEquals(1, sources.size)
        val g = sources[0].jsonObject
        assertEquals(setOf("source", "calendars", "read_ok", "reason"), g.keys)
        assertEquals("google_api", g["source"]!!.jsonPrimitive.content)
        assertEquals(setOf("ref", "events"), g["calendars"]!!.jsonArray[0].jsonObject.keys)
        assertEquals(listOf("google_api"), res["connected_sources"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, res["blocks"]!!.jsonArray.size)
        val s = r.result.toString()
        assertFalse("name" in s || "account_type" in s || "harsha" in s)
    }

    @Test fun bothSourcesAreMergedAndPhoneIsListedToo() = runBlocking {
        be.extra += BusyRow(ms("2026-10-12T09:00:00Z"), ms("2026-10-12T10:00:00Z"), false, true, calendarId = 5)
        val r = ex().execute(call(listOf("phone_provider", "google_api")))
        val names = r.result!!["sources"]!!.jsonArray.map { it.jsonObject["source"]!!.jsonPrimitive.content }
        assertEquals(listOf("phone_provider", "google_api"), names)
        val blocks = r.result["blocks"]!!.jsonArray
        assertEquals(1, blocks.size) // 08:30-09:30 + 09:00-10:00 merge
        assertEquals("2026-10-12T10:00:00Z", blocks[0].jsonObject["end"]!!.jsonPrimitive.content)
    }

    @Test fun sourcesAbsentKeepsTheV129Shape() = runBlocking {
        val r = ex().execute(call(null))
        val names = r.result!!["sources"]!!.jsonArray.map { it.jsonObject["source"]!!.jsonPrimitive.content }
        assertEquals(listOf("phone_provider", "google_api"), names)
        assertEquals(0, asked)
        assertEquals("not_connected", r.result["sources"]!!.jsonArray[1].jsonObject["reason"]!!.jsonPrimitive.content)
    }

    @Test fun aFailedGoogleReadIsNotBusyAndNotFree() = runBlocking {
        googleRead = GcalBusy.failed(GcalReadReasons.REAUTH_NEEDED, reauth = true)
        val r = ex().execute(call(listOf("google_api")))
        val g = r.result!!["sources"]!!.jsonArray[0].jsonObject
        assertEquals("false", g["read_ok"]!!.jsonPrimitive.content)
        assertEquals("reauth_needed", g["reason"]!!.jsonPrimitive.content)
        assertTrue(g["calendars"]!!.jsonArray.isEmpty())
        assertTrue(r.result["connected_sources"]!!.jsonArray.isEmpty())
        assertTrue(r.result["blocks"]!!.jsonArray.isEmpty())
    }

    @Test fun aPhoneWithoutALinkAnswersNotConnected() = runBlocking {
        val r = ex(withGoogle = false).execute(call(listOf("google_api")))
        assertEquals("not_connected", r.result!!["sources"]!!.jsonArray[0].jsonObject["reason"]!!.jsonPrimitive.content)
    }

    @Test fun theSkillOffIsDeclinedAsEver() = runBlocking {
        states["calendar"] = RisiSkillStates.OFF
        assertEquals(RisiToolResult.DECLINED, ex().execute(call(listOf("google_api"))).status)
    }

    @Test fun providerCopiesAndTheConnectedAccountAreNotCountedTwice() {
        val sync = "risi" + "a".repeat(32)
        be.extra += BusyRow(ms("2026-10-12T04:00:00Z"), ms("2026-10-12T05:00:00Z"), false, true, calendarId = 5, syncId = sync) // a RisiMe copy
        be.extra += BusyRow(ms("2026-10-12T06:00:00Z"), ms("2026-10-12T07:00:00Z"), false, true, calendarId = 1) // the Google account's own
        be.extra += BusyRow(ms("2026-10-12T11:00:00Z"), ms("2026-10-12T12:00:00Z"), false, true, calendarId = 5) // a local one
        val plain = cal.read(ms("2026-10-12T02:30:00Z"), ms("2026-10-12T14:30:00Z"))!!
        assertEquals(2, plain.blocks.size) // the copy is dropped on every device
        val both = cal.read(ms("2026-10-12T02:30:00Z"), ms("2026-10-12T14:30:00Z"), skipGoogleAccount = "harsha@example.com")!!
        assertEquals(1, both.blocks.size) // and the connected account's provider events while Google is read by API
    }
}
