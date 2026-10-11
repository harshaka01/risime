package lk.codegen.risime.ui.common

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Decision 079: the last lk.codegen.risime build shows "RisiMe has moved" and opens the download page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MovedBannerTest {
    @get:Rule val rule = createComposeRule()

    @Before @After fun reset() { MovedNotice.dismissedThisSession = false }

    private fun nextStarted(): Intent? {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return shadowOf(app).nextStartedActivity
    }

    @Test fun bannerShowsAndTapOpensTheDownloadPage() {
        rule.setContent { RisiMeTheme { MovedBanner() } }
        rule.onNodeWithText(MovedNotice.TITLE).assertIsDisplayed()
        rule.onNodeWithTag(MovedNotice.BANNER_TAG).performClick()
        val i = nextStarted()!!
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals("https://risicloud.ai/app/risime/", i.dataString)
    }

    @Test fun dismissHidesItForThisSessionOnly() {
        rule.setContent { RisiMeTheme { MovedBanner() } }
        rule.onNodeWithTag(MovedNotice.DISMISS_TAG).performClick()
        rule.onNodeWithTag(MovedNotice.BANNER_TAG).assertDoesNotExist()
        assertEquals(null, nextStarted())
        // A new process starts with the flag false again: the banner returns.
        MovedNotice.dismissedThisSession = false
        rule.onNodeWithTag(MovedNotice.BANNER_TAG).assertIsDisplayed()
    }

    @Test fun settingsRowAlwaysShowsAndOpensTheDownloadPage() {
        MovedNotice.dismissedThisSession = true
        rule.setContent { RisiMeTheme { MovedSettingsRow() } }
        rule.onNodeWithText(MovedNotice.TITLE).assertIsDisplayed()
        rule.onNodeWithTag(MovedNotice.SETTINGS_TAG).performClick()
        val i = nextStarted()!!
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals("https://risicloud.ai/app/risime/", i.dataString)
    }
}
