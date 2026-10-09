package lk.codegen.risime.ui.tabs

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lk.codegen.risime.data.tabs.RisiControl
import lk.codegen.risime.data.tabs.RisiRequests
import lk.codegen.risime.data.tabs.SummaryPeriods
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiFactsReply
import lk.codegen.risime.net.RisiPeriod
import lk.codegen.risime.net.RisiSummaryDay
import lk.codegen.risime.net.RisiTextEnvelope
import lk.codegen.risime.ui.settings.FactsUi
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/** Proposal 2026-10-09-risi-30day-summaries: Summarise periods, the period/days on the card, Summaries in "What Risi knows". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SummaryPeriodsTest {
    @get:Rule val rule = createComposeRule()
    private val colombo = ZoneId.of("Asia/Colombo")

    @Test fun periodLinesAndDays() {
        assertEquals("Last 7 days (3–9 Oct)", SummaryPeriods.periodLine(RisiPeriod("2026-10-02T18:30:00.000Z", "2026-10-09T05:00:00.000Z", "7d"), colombo))
        assertEquals("Today (9 Oct)", SummaryPeriods.periodLine(RisiPeriod("2026-10-08T18:30:00.000Z", "2026-10-09T05:00:00.000Z", "today"), colombo))
        assertEquals("28 Sep–4 Oct", SummaryPeriods.periodLine(RisiPeriod("2026-09-27T18:30:00.000Z", "2026-10-04T18:30:00.000Z", "range"), colombo)) // `to` = next midnight
        assertNull(SummaryPeriods.periodLine(null, colombo))
        assertEquals("Mon 5 Oct", SummaryPeriods.dayLabel(RisiSummaryDay("2026-10-05"), colombo))
        assertEquals("week 28 Sep–4 Oct", SummaryPeriods.dayLabel(RisiSummaryDay("2026-09-28", "2026-10-04", "week"), colombo))
        assertEquals("Week · 28 Sep–4 Oct", SummaryPeriods.factLabel("week", RisiPeriod("2026-09-28", "2026-10-04"), colombo))
    }

    @Test fun requestsCarryThePeriodOrTheRange() = runBlocking {
        val sent = mutableListOf<JsonObject>()
        val now = Instant.parse("2026-10-09T05:00:00Z").toEpochMilli()
        val r = RisiRequests({ true }, { sent += it }, newId = { "r1" }, now = { now })
        assertTrue(r.summarisePeriod("7d"))
        assertEquals("7d", sent[0]["scope"]!!.jsonObject["period"]!!.jsonPrimitive.content)
        assertEquals("summarise", sent[0]["action"]!!.jsonPrimitive.content)
        assertFalse(r.summarisePeriod("90d"))
        assertTrue(r.summariseRange(now - 10 * 86_400_000L, now - 3 * 86_400_000L))
        assertEquals(RisiControl.iso(now - 10 * 86_400_000L), sent[1]["scope"]!!.jsonObject["from"]!!.jsonPrimitive.content)
        assertFalse(r.summariseRange(now - 40 * 86_400_000L, now)) // over 31 days back
        assertTrue(RisiControl.valid(sent[0]) && RisiControl.valid(sent[1]))
        assertFalse(RisiRequests({ false }, { sent += it }).summarisePeriod("today")) // Private: never
    }

    @Test fun summaryWithPeriodAndDaysDecodes() {
        val s = """{"v":1,"type":"text","body":"Summary of the last 7 days (3–9 Oct): …","risi":{"v":1,"kind":"summary","request_id":"r1","summary":"S","decisions":[],"action_items":[],"open_questions":[],
            "period":{"from":"2026-10-02T18:30:00.000Z","to":"2026-10-09T05:00:00.000Z","scope":"7d"},
            "days":[{"date":"2026-10-05","to":"2026-10-05","scope":"day","summary_id":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"}],"notify":[]}}"""
        val r = ProtocolJson.decodeFromString(RisiTextEnvelope.serializer(), s).risi!!
        assertEquals("7d", r.period!!.scope)
        assertEquals(1, r.days.size)
        // An older summary (v1.24 `period` without scope) still decodes.
        val old = ProtocolJson.decodeFromString(RisiPeriod.serializer(), """{"from":"a","to":"b"}""")
        assertNull(old.scope)
    }

    @Test fun summariesAreAGroupInWhatRisiKnows() {
        val reply = ProtocolJson.decodeFromString(
            RisiFactsReply.serializer(),
            """{"facts":[{"fact_id":"f1","kind":"note","text":"N"},{"fact_id":"s1","kind":"summary","text":"Talked about the quote","chat_id":"dm:x","created_at":"2026-10-09T18:00:00Z","scope":"day","period":{"from":"2026-10-09","to":"2026-10-09"}}]}""",
        )
        val groups = FactsUi(loading = false, facts = reply.facts).grouped
        assertEquals(listOf("Notes", "Summaries"), groups.map { it.first })
        assertEquals("day", groups[1].second.single().scope)
    }

    private class Host : RisiHost {
        val calls = mutableListOf<String>()
        override val me = "me"
        override fun ask(text: String) {}
        override fun summarise() { calls += "summarise" }
        override fun report() {}
        override fun act(target: String, action: String, editText: String?, editDue: String?) {}
        override fun feedback(callRef: String, rating: String, reason: String?) {}
        override fun summarisePeriod(period: String) { calls += "period:$period" }
    }

    @Test fun dialogOffersThePeriods() {
        val host = Host()
        var open = true
        rule.setContent { RisiMeTheme { if (open) SummarisePeriodDialog(host) { open = false } } }
        rule.onNodeWithTag("risi_summarise_today").assertExists()
        rule.onNodeWithTag("risi_summarise_range").assertExists()
        rule.onNodeWithTag("risi_summarise_30d").performClick()
        assertEquals(listOf("period:30d"), host.calls)
    }

    @Test fun dateRangeOpensThePicker() {
        val host = Host()
        rule.setContent { RisiMeTheme { SummarisePeriodDialog(host) {} } }
        rule.onNodeWithTag("risi_summarise_range").performClick()
        rule.onNodeWithTag("risi_summarise_range_picker").assertExists()
    }
}
