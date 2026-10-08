package lk.codegen.risime.ui.auth

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import lk.codegen.risime.net.FriendRequest
import lk.codegen.risime.ui.friends.IncomingRowContent
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §21.8 "Create your RisiMe account" and the §21.4 note: small phone, 2× font, light and dark, long names. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h480dp", application = Application::class)
class SignupScreenTest {
    @get:Rule val rule = createComposeRule()

    private val longName = "Kumarasinghe Mudiyanselage Dissanayake Wickramaratne Jayasuriya"

    private fun host(dark: Boolean, fontScale: Float = 2f, content: @Composable () -> Unit) {
        rule.setContent {
            RisiMeTheme(dark = dark) {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                    Column(Modifier.fillMaxSize()) { Box(Modifier.weight(1f)) { content() } }
                }
            }
        }
    }

    private var creates = 0
    private var others = 0
    private var signOuts = 0

    private fun signup(initial: SignupFormState, dark: Boolean) = host(dark) {
        var s by remember { mutableStateOf(initial) }
        SignupContent(s, { s = it }, { creates++ }, { others++ }, { signOuts++ })
    }

    private fun assertReachable(text: String) {
        rule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    @Test fun longNameLargeFontLight() {
        signup(SignupFormState(displayName = longName), dark = false)
        assertReachable(SIGNUP_TITLE)
        assertReachable(longName)
        assertReachable("+94")
        rule.onNodeWithText(SIGNUP_CREATE).performScrollTo().assertIsDisplayed().assertIsEnabled().assertHasClickAction().performClick()
        rule.onNodeWithText(SIGN_IN_ANOTHER_ACCOUNT).performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText(SIGN_OUT_KEEPS_CHATS).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf(1, 1, 1), listOf(creates, others, signOuts))
    }

    @Test fun errorsShowInDarkMode() {
        val taken = "This phone number can't be used for a new account."
        signup(SignupFormState(displayName = longName, number = "0771234567", phoneError = taken, error = "Too many sign-up attempts. Try again in 30 min."), dark = true)
        assertReachable(taken)
        assertReachable("Too many sign-up attempts. Try again in 30 min.")
        assertReachable(SIGNUP_CREATE)
    }

    @Test fun busyDisablesTheButtons() {
        signup(SignupFormState(displayName = "Kamal", number = "771234567", busy = true), dark = false)
        rule.onNodeWithText("Creating…").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithText(SIGN_OUT_KEEPS_CHATS).performScrollTo().assertIsNotEnabled()
    }

    @Test fun incomingRequestFromUnconfirmedPhoneShowsTheNote() {
        val r = FriendRequest("r1", "+94770000009", "u1", longName, "", "2026-10-08T09:00:00.000Z", phoneConfirmed = false)
        var accepted: String? = null
        host(dark = true) { Column { IncomingRowContent(r, { accepted = it }, {}, {}, { _, _ -> }) } }
        rule.onNodeWithText(PHONE_NOT_VERIFIED).assertIsDisplayed()
        rule.onNodeWithText("Accept").performClick()
        assertEquals("r1", accepted)
    }

    @Test fun confirmedPhoneHasNoNote() {
        val r = FriendRequest("r2", "+94771234568", "u2", "Kamal", "Rise", null)
        host(dark = false, fontScale = 1f) { Column { IncomingRowContent(r, {}, {}, {}, { _, _ -> }) } }
        rule.onNodeWithText("Kamal").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTextCount(PHONE_NOT_VERIFIED))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(t: String) =
        onAllNodes(androidx.compose.ui.test.hasText(t)).fetchSemanticsNodes().size
}
