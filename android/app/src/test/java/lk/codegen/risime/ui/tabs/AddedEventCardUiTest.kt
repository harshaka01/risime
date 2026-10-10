package lk.codegen.risime.ui.tabs

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.flow.MutableStateFlow
import lk.codegen.risime.data.tabs.AddedEventView
import lk.codegen.risime.data.tabs.CalendarAddRecord
import lk.codegen.risime.data.tabs.LocalEvent
import lk.codegen.risime.data.tabs.PhoneCalendarInfo
import lk.codegen.risime.data.tabs.RisiCalendarPort
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** v1.32 §25.3 the `added_event` card: the provider row with [Open in Calendar]; gone; another device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AddedEventCardUiTest {
    @get:Rule val rule = createComposeRule()

    private class Port(val view: AddedEventView) : RisiCalendarPort {
        val opened = mutableListOf<Long>()
        override val records = MutableStateFlow(emptyMap<String, CalendarAddRecord>())
        override val chosenId = MutableStateFlow<Long?>(null)
        override fun hasPermission() = true
        override suspend fun options() = emptyList<PhoneCalendarInfo>()
        override suspend fun chosen(): PhoneCalendarInfo? = null
        override suspend fun choose(id: Long) {}
        override fun open(eventId: Long) { opened += eventId }
        override suspend fun addedEvent(eventId: Long) = view
    }

    private class Host(val port: Port) : RisiHost {
        override val me = "ff03ab6f-6457-46b1-a53b-9efd937920df"
        override fun ask(text: String) {}
        override fun summarise() {}
        override fun report() {}
        override fun act(target: String, action: String, editText: String?, editDue: String?) {}
        override fun feedback(callRef: String, rating: String, reason: String?) {}
        override val calendar: RisiCalendarPort = port
    }

    private fun show(v: AddedEventView): Port {
        val p = Port(v)
        val ctx = risiCardContext(Host(p), emptyList(), { "You" }, 0L, onRef = {})
        rule.setContent { RisiMeTheme { AddedEventCard("4711", ctx) } }
        return p
    }

    private fun ms(s: String) = Instant.parse(s).toEpochMilli()

    @Test fun showsTheRowAndOpensIt() {
        val p = show(AddedEventView.Present(LocalEvent("Meeting with Veenath", ms("2026-10-11T03:30:00Z"), ms("2026-10-11T04:30:00Z"), false), "user@example.com · Google"))
        rule.onNodeWithText("Meeting with Veenath").assertIsDisplayed()
        rule.onNodeWithTag("risi_added_event_when").assertIsDisplayed()
        rule.onNodeWithText("user@example.com · Google").assertIsDisplayed()
        rule.onNodeWithText(OPEN_IN_CALENDAR).performClick()
        assertEquals(listOf(4711L), p.opened)
    }

    @Test fun goneSaysSo() {
        show(AddedEventView.Gone)
        rule.onNodeWithText(ADDED_EVENT_GONE).assertIsDisplayed()
    }

    @Test fun anotherDeviceShowsNothingExtra() {
        show(AddedEventView.NotHere)
        rule.onNodeWithTag("risi_added_event").assertDoesNotExist()
        rule.onNodeWithTag("risi_added_event_gone").assertDoesNotExist()
    }
}
