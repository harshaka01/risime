package lk.codegen.risime.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasTestTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import lk.codegen.risime.data.tabs.RisiSkillsStore
import lk.codegen.risime.data.tabs.SkillPermissions
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.ClientPermission
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiActivityReply
import lk.codegen.risime.net.RisiSkill
import lk.codegen.risime.net.RisiSkillStates
import lk.codegen.risime.net.RisiSkillsPatch
import lk.codegen.risime.net.RisiSkillsReply
import lk.codegen.risime.net.RisiUndoReply
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §26.2/§26.4/§26.5 A13 Settings → Risi skills on screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RisiSkillsUiTest {
    @get:Rule val rule = createComposeRule()

    private fun read(name: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()

    private val patches = mutableListOf<RisiSkillsPatch>()
    private val undos = mutableListOf<String>()

    private fun model(skills: List<RisiSkill>, pending: Int? = 2): RisiSkillsModel {
        var current = skills
        val store = RisiSkillsStore(
            get = { ApiResult.Ok(RisiSkillsReply(current)) },
            patch = { p ->
                patches += p
                val changed = p.changes.map { c -> current.first { it.id == c.id }.let { s -> s.copy(state = c.state ?: s.state) } }
                current = current.map { s -> changed.firstOrNull { it.id == s.id } ?: s }
                ApiResult.Ok(RisiSkillsReply(changed))
            },
        )
        runBlocking { store.refresh() }
        return RisiSkillsModel(
            store, CoroutineScope(Dispatchers.Unconfined),
            activity = { ApiResult.Ok(ProtocolJson.decodeFromString<RisiActivityReply>(read("risi_skill_activity_reply.json"))) },
            clearActivity = { ApiResult.Ok(Unit) },
            undo = { s, e, t -> undos += "$s:$e:$t"; ApiResult.Ok(ProtocolJson.decodeFromString<RisiUndoReply>(read("risi_skill_undo_reply.json"))) },
            pendingCount = { pending },
            now = { java.time.Instant.parse("2026-10-14T00:00:00Z").toEpochMilli() },
        )
    }

    private class Perms(val after: String) : SkillPermissions {
        var prompted = 0
        override fun current(skill: RisiSkill) = ClientPermission.NOT_ASKED
        override suspend fun request(skill: RisiSkill): String { prompted++; return after }
    }

    private fun registry() = ProtocolJson.decodeFromString<RisiSkillsReply>(read("risi_skills_reply.json")).skills

    @Test fun everySkillIsListedWithItsSwitchAndEmailIsComingLater() {
        val m = model(registry())
        rule.setContent { RisiMeTheme { RisiSkillsScreen(m, Perms(ClientPermission.GRANTED), null, {}) } }
        rule.onNodeWithTag("risi_skill_switch_alarm").assertIsOn()
        rule.onNodeWithTag("risi_skills").performScrollToNode(hasTestTag("risi_skill_email"))
        rule.onNodeWithTag("risi_skill_state_email", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Coming later", useUnmergedTree = true).assertExists()
    }

    @Test fun aRefusedPermissionLeavesTheSwitchOffAndSaysWhy() {
        val m = model(registry())
        val perms = Perms(ClientPermission.DENIED)
        rule.setContent { RisiMeTheme { RisiSkillsScreen(m, perms, "calendar", {}) } }
        rule.onNodeWithTag("risi_skill_switch_calendar").performClick()
        rule.waitForIdle()
        assertEquals(1, perms.prompted)
        rule.onNodeWithTag("risi_skill_switch_calendar").assertIsOff()
        rule.onNodeWithTag("risi_skill_note_calendar").assertIsDisplayed()
        assertTrue(patches.all { p -> p.changes.all { it.state == null } })
    }

    @Test fun focusShowsCanCannotExactPermissionsAndTheModes() {
        val m = model(registry())
        rule.setContent { RisiMeTheme { RisiSkillsScreen(m, Perms(ClientPermission.GRANTED), "alarm", {}) } }
        rule.onNodeWithText("com.android.alarm.permission.SET_ALARM").assertExists()
        rule.onNodeWithText("• Set a one-time or repeating alarm on the phone you asked from").assertExists()
        rule.onNodeWithTag("risi_mode_$ASK_EACH_TIME").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(RisiSkillStates.ASK, patches.last().changes.single().state)
    }

    @Test fun revokeOffersAlsoCancelNPending() {
        val skills = registry().map { if (it.id == "scheduled_messages") it.copy(state = RisiSkillStates.ASK) else it }
        val m = model(skills, pending = 2)
        rule.setContent { RisiMeTheme { RisiSkillsScreen(m, Perms(ClientPermission.GRANTED), "scheduled_messages", {}) } }
        rule.onNodeWithTag("risi_skill_switch_scheduled_messages").performClick()
        rule.onNodeWithText("Also cancel 2 pending messages").performClick()
        rule.onNodeWithTag("risi_revoke_confirm").performClick()
        rule.waitForIdle()
        assertEquals(RisiSkillsPatch(listOf(lk.codegen.risime.net.RisiSkillChange("scheduled_messages", RisiSkillStates.OFF)), cancelPending = true), patches.last())
    }

    @Test fun activityLogShowsEntriesWithUndo() {
        val m = model(registry())
        rule.setContent { RisiMeTheme { RisiSkillsScreen(m, Perms(ClientPermission.GRANTED), "calendar", {}) } }
        rule.onNodeWithTag("risi_skill_activity_calendar").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("risi_activity_undo").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("calendar:9e8d7c6b-5a49-4837-9625-14a3b2c1d0e9:u1.QmFzZTY0VXJsUGxhY2Vob2xkZXI"), undos)
    }

    @Test fun revokeLabels() {
        assertEquals("Also cancel 1 pending message", revokeCancelLabel("scheduled_messages", 1))
        assertEquals(null, revokeCancelLabel("scheduled_messages", 0))
        assertEquals("Also cancel pending reminders", revokeCancelLabel("reminders", null))
        assertEquals(null, revokeCancelLabel("alarm", 3))
    }
}
