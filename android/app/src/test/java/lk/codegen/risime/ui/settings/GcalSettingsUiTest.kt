package lk.codegen.risime.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lk.codegen.risime.data.db.GcalCopyEntity
import lk.codegen.risime.data.db.GcalCopyState
import lk.codegen.risime.data.gcal.FakeGoogle
import lk.codegen.risime.data.gcal.FakeRest
import lk.codegen.risime.data.gcal.GcalRuntime
import lk.codegen.risime.data.gcal.MemGcalDao
import lk.codegen.risime.net.GoogleLink
import lk.codegen.risime.ui.theme.RisiMeTheme
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v1.31 §31.2/§31.8 the Settings section, driven the way scripts/ui-entry-test --google drives it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GcalSettingsUiTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var g: FakeGoogle
    private lateinit var dao: MemGcalDao
    private lateinit var rest: FakeRest
    private lateinit var rt: GcalRuntime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val switchOn = MutableStateFlow(true)
    private val skillOff = MutableStateFlow(false)
    private var skill = 0

    private fun cal(id: String, name: String, role: String, primary: Boolean = false, selected: Boolean = false, hidden: Boolean = false) = buildJsonObject {
        put("id", id); put("summary", name); put("accessRole", role)
        if (primary) put("primary", true)
        if (selected) put("selected", true)
        if (hidden) put("hidden", true)
    }

    @Before fun setUp() {
        g = FakeGoogle()
        g.calendars["work"] = cal("work", "Work", "owner", primary = true, selected = true)
        g.calendars["pers"] = cal("pers", "Personal", "owner", selected = true)
        g.calendars["hol"] = cal("hol", "Holidays", "reader", hidden = true)
        dao = MemGcalDao()
        rest = FakeRest()
        rt = GcalRuntime(
            ApplicationProvider.getApplicationContext<Application>(), OkHttpClient(), rest, dao, events = { emptyList() }, myDevice = { "me" },
            skillOff = { skillOff.value }, enableSkill = { skill++; true }, switchOn = { switchOn.value }, scheduleCopy = {},
        )
        rt.installTestHooks(lk.codegen.risime.data.gcal.FakeAuthorizer(mutableListOf(g.token)), g.baseUrl())
    }

    @After fun tearDown() { g.shutdown(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    private fun show(): GcalSettingsModel {
        val model = GcalSettingsModel(rt, scope, me = { "me" }, switchOn = switchOn, skillOff = skillOff)
        rule.setContent { RisiMeTheme { GoogleCalendarSection(model) } }
        return model
    }

    private fun waitText(t: String, ms: Long = 8_000) = rule.waitUntil(ms) { rule.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun theSwitchOffShowsNoSection() {
        switchOn.value = false
        show()
        rule.onAllNodes(hasText("Connect Google Calendar")).assertCountEquals(0)
        rule.onAllNodes(hasText("Google Calendar", substring = true)).assertCountEquals(0)
    }

    @Test fun notConnectedShowsTheButtonAndTheInfoLine() {
        show()
        waitText("Connect Google Calendar")
        rule.onNodeWithText("Google Calendar").assertIsDisplayed()
        rule.onNodeWithText("Not connected").assertIsDisplayed()
        rule.onNodeWithText(GcalText.INFO).assertIsDisplayed()
    }

    @Test fun connectShowsThePickerWithDefaultsAndSaveConnects() {
        show()
        waitText("Connect Google Calendar")
        rule.onNodeWithText("Connect Google Calendar").performClick()
        waitText("Check for busy times")
        rule.onNodeWithText("Add my Risi events to").assertExists()
        rule.onNodeWithText("Copy my Risi Calendar events to Google").assertExists()
        for (n in listOf("Work", "Personal", "Holidays")) rule.onAllNodes(hasText(n)).fetchSemanticsNodes().isNotEmpty().also { assertTrue(n, it) }
        // Work and Personal are ticked, the hidden Holidays is not; only writable calendars are offered for writing.
        rule.onNodeWithTag("gcal_read_Work").assertIsOn()
        rule.onNodeWithTag("gcal_read_Personal").assertIsOn()
        rule.onNodeWithTag("gcal_read_Holidays").assertIsOff()
        rule.onNodeWithTag("gcal_write_Work").assertIsSelected()
        rule.onAllNodes(hasTestTag("gcal_write_Holidays")).assertCountEquals(0)
        rule.onNodeWithText("Save").performClick()
        waitText("Connected on this phone")
        waitText("Checking 2 calendars")
        waitText("Adding events to Work")
        assertEquals(1, rest.puts.size)
        assertEquals("connected", rest.link.state)
        assertEquals(2, rest.link.readCalendars)
        // the names stay on the phone
        assertTrue(runBlocking { dao.calendars() }.any { it.name == "Work" })
        assertTrue(rest.puts.toString().let { "Work" !in it && "work" !in it })
    }

    @Test fun anotherDeviceShowsConnectedOnItsNameWithConnectHereInstead() {
        rest.link = GoogleLink("connected", "other", "Pixel 8", 2, true, true, "t", "t")
        show()
        waitText("Connected on Pixel 8")
        rule.onNodeWithText("Connect here instead").assertIsDisplayed()
        rule.onNodeWithText("Disconnect").assertIsDisplayed()
        rule.onAllNodes(hasText("Connected on this phone")).assertCountEquals(0)
    }

    @Test fun needsReconnectingShowsReconnectAndPausedShowsTheHint() {
        runBlocking { dao.upsertCalendars(listOf(lk.codegen.risime.data.db.GcalCalendarEntity("work", "g1234567", "Work", "owner", true, true, null))) }
        rest.link = GoogleLink("reauth_needed", "me", "P", 1, true, true, "t", "t")
        show()
        waitText("Needs reconnecting")
        rule.onNodeWithText("Reconnect Google Calendar").assertIsDisplayed()
    }

    @Test fun pausedWhileTheCalendarSkillIsOff() {
        runBlocking { dao.upsertCalendars(listOf(lk.codegen.risime.data.db.GcalCalendarEntity("work", "g1234567", "Work", "owner", true, true, null))) }
        rest.link = GoogleLink("connected", "me", "P", 1, true, true, "t", "t")
        skillOff.value = true
        show()
        waitText("Paused: turn on the Calendar skill to use it")
    }

    @Test fun disconnectAsksKeepOrRemoveAndTheKeepDefaultDeletesNothingInGoogle() {
        runBlocking { dao.upsertCalendars(listOf(lk.codegen.risime.data.db.GcalCalendarEntity("work", "g1234567", "Work", "owner", true, true, null))) }
        rest.link = GoogleLink("connected", "me", "P", 1, true, true, "t", "t")
        g.seed("work", "risi" + "a".repeat(32), "2099-01-01T10:00:00Z", "2099-01-01T11:00:00Z", buildJsonObject {
            put("extendedProperties", buildJsonObject { put("private", buildJsonObject { put("risime", "1") }) })
        })
        show()
        waitText("Connected on this phone")
        rule.onNodeWithText("Disconnect").performClick()
        waitText("Disconnect Google Calendar?")
        rule.onNodeWithText("Risi will stop checking it and stop copying events.").assertExists()
        rule.onNodeWithText("Events already copied:").assertExists()
        rule.onNodeWithText("Keep them in Google").assertExists()
        rule.onNodeWithText("Remove them from Google").assertExists()
        rule.onNodeWithTag("gcal_disconnect_confirm").performClick()
        rule.waitUntil(8_000) { rest.deletedWith != null }
        assertEquals(false, rest.deletedWith)
        assertEquals(1, g.live("work").size)
        rule.waitUntil(5_000) { runBlocking { dao.calendars().isEmpty() } }
    }

    @Test fun theRemovedInGoogleRowOffersAddAgain() {
        runBlocking {
            dao.upsertCalendars(listOf(lk.codegen.risime.data.db.GcalCalendarEntity("work", "g1234567", "Work", "owner", true, true, null)))
            dao.upsertCopy(GcalCopyEntity("e1", "work", "risi00", 1, GcalCopyState.DELETED_IN_GOOGLE, 1))
        }
        rest.link = GoogleLink("connected", "me", "P", 1, true, true, "t", "t")
        show()
        waitText("1 Risi event was removed in Google")
        rule.onNodeWithText("Add again").assertIsDisplayed()
    }
}

