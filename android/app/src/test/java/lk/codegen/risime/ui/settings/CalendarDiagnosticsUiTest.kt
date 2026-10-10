package lk.codegen.risime.ui.settings

import android.app.Application
import android.content.ClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.verticalScroll
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import lk.codegen.risime.data.tabs.CalendarAddRecord
import lk.codegen.risime.data.tabs.CalendarDiag
import lk.codegen.risime.data.tabs.CalendarDiagnostics
import lk.codegen.risime.data.tabs.PhoneCalendarInfo
import lk.codegen.risime.data.tabs.RisiCalendarPort
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P0 2026-10-10 Details → "Calendar diagnostics": the lines shown and "Copy diagnostics" copying the same text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CalendarDiagnosticsUiTest {
    @get:Rule val rule = createComposeRule()

    private val long = "a.very.long.account.name.that.goes.on.and.on@example-company-with-a-long-domain.com"
    private val cal = PhoneCalendarInfo(3, long, long, "com.google", 700, true, long, true)
    private val diag = CalendarDiagnostics(true, true, true, 7, listOf(CalendarDiag(cal, true, "idle", null, 12, 3, 2)), emptyList())

    private val port = object : RisiCalendarPort {
        override val records: StateFlow<Map<String, CalendarAddRecord>> = MutableStateFlow(emptyMap())
        override val chosenId: StateFlow<Long?> = MutableStateFlow(null)
        override fun hasPermission() = true
        override suspend fun options() = emptyList<PhoneCalendarInfo>()
        override suspend fun chosen(): PhoneCalendarInfo? = null
        override suspend fun choose(id: Long) {}
        override fun open(eventId: Long) {}
        override suspend fun diagnostics() = diag
    }

    @Test fun showsTheSectionAndCopiesItAsPlainText() {
        rule.setContent { RisiMeTheme { androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) { CalendarDiagnosticsBlock(port) } } }
        rule.onNodeWithText(CalendarDiagnostics.TITLE).assertIsDisplayed()
        rule.onNodeWithText("READ_CALENDAR: granted").assertIsDisplayed()
        rule.onNodeWithText("Events (raw): 12 · Instances next 7 days: 3 · Counted: 2").assertExists()
        rule.onNodeWithText("Account: $long").assertExists()
        rule.onNodeWithTag("risi_calendar_diagnostics_copy").performScrollTo().performClick()
        rule.onNodeWithText(CalendarDiagnostics.COPIED).assertExists()
        val cm = ApplicationProvider.getApplicationContext<Application>().getSystemService(ClipboardManager::class.java)
        assertEquals(diag.text(), cm.primaryClip!!.getItemAt(0).text.toString())
    }
}
