package ai.risicloud.risime.ui.settings

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ai.risicloud.risime.data.tabs.RisiSkillsStore
import ai.risicloud.risime.data.tabs.SkillPermissions
import ai.risicloud.risime.net.ApiResult
import ai.risicloud.risime.net.ClientPermission
import ai.risicloud.risime.net.ProtocolJson
import ai.risicloud.risime.net.RisiSkill
import ai.risicloud.risime.net.RisiSkillStates
import ai.risicloud.risime.net.RisiSkillsReply
import ai.risicloud.risime.ui.theme.RisiMeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Proposal 2026-10-14-permission-fixit §43.5/§43.6: Settings → Risi skills → Calendar shows only "Risi can't see
 * your calendar yet." and [Allow calendar] until the permission is granted, then "✓ Risi can see your calendar" and
 * the choice; developer details stay hidden unless the developer switch is on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PermissionFixitUiTest {
    @get:Rule val rule = createComposeRule()

    private fun read(name: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()

    private fun model(): RisiSkillsModel {
        val skills = ProtocolJson.decodeFromString<RisiSkillsReply>(read("risi_skills_reply.json")).skills
            .map { if (it.id == "calendar") it.copy(state = RisiSkillStates.ASK) else it }
        val store = RisiSkillsStore(get = { ApiResult.Ok(RisiSkillsReply(skills)) }, patch = { ApiResult.Ok(RisiSkillsReply(emptyList())) })
        runBlocking { store.refresh() }
        return RisiSkillsModel(
            store, CoroutineScope(Dispatchers.Unconfined),
            activity = { ApiResult.Error(500, "x", "") }, clearActivity = { ApiResult.Ok(Unit) },
            undo = { _, _, _ -> ApiResult.Error(500, "x", "") }, pendingCount = { 0 },
            now = { java.time.Instant.parse("2026-10-14T00:00:00Z").toEpochMilli() },
        )
    }

    private object Perms : SkillPermissions {
        override fun current(skill: RisiSkill) = ClientPermission.NOT_ASKED
        override suspend fun request(skill: RisiSkill) = ClientPermission.NOT_ASKED
    }

    private fun fixit(dev: Boolean = false) = SkillFixit(askedBefore = { false }, markAsked = {}, onGranted = {}, devDetails = dev)

    @Test fun notGrantedShowsOnlyTheLineAndTheButton() {
        val m = model()
        rule.setContent { RisiMeTheme { RisiSkillsScreen(m, Perms, "calendar", {}, fixit = fixit()) } }
        rule.onNodeWithTag("risi_fixit_line_calendar", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Risi can't see your calendar yet.", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("risi_fixit_allow_calendar", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Allow calendar", useUnmergedTree = true).assertExists()
        // Nothing else: no choice yet, no details, no permission names.
        rule.onNodeWithTag("risi_mode_$ASK_EACH_TIME", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("risi_skill_dev_calendar", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("android.permission.READ_CALENDAR", useUnmergedTree = true).assertDoesNotExist()
        // The calendar's v1.26 "Can" / "Cannot" lists aren't there either (other skills keep their Details).
        rule.onNodeWithText("• Add events you agree to", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun grantedShowsTheTickAndTheChoiceOnly() {
        shadowOf(androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR")
        val m = model()
        rule.setContent { RisiMeTheme { RisiSkillsScreen(m, Perms, "calendar", {}, fixit = fixit()) } }
        rule.onNodeWithText("✓ Risi can see your calendar", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("risi_mode_$ASK_EACH_TIME", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("risi_fixit_allow_calendar", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("risi_skill_dev_calendar", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun developerDetailsOnlyWithTheDeveloperSwitch() {
        val m = model()
        rule.setContent { RisiMeTheme { RisiSkillsScreen(m, Perms, "calendar", {}, fixit = fixit(dev = true)) } }
        rule.onNodeWithTag("risi_skill_dev_calendar", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("android.permission.READ_CALENDAR", useUnmergedTree = true).assertExists()
    }
}
