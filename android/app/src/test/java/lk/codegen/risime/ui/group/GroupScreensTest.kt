package lk.codegen.risime.ui.group

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import lk.codegen.risime.net.GroupReceipt
import lk.codegen.risime.net.GroupReceiptsReply
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** Create group, group info and "Read by" on a small phone, in light and dark (contract v1.9 §12). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h480dp", application = Application::class)
class GroupScreensTest(private val dark: Boolean) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }

    @get:Rule val rule = createComposeRule()

    private val friends = listOf(
        PickFriend("u-kamal", "Kamal", "Rise", ready = true),
        PickFriend("u-nimal", "Nimal", "CodeGen", ready = false),
        PickFriend("u-sunil", "Sunil", "Rise", ready = true),
    )

    // ---- Create group ----

    private var ui by mutableUi(CreateGroupUi(friends = friends))
    private val events = mutableListOf<String>()

    private fun mutableUi(initial: CreateGroupUi) = androidx.compose.runtime.mutableStateOf(initial)

    private fun create(dark: Boolean) {
        rule.setContent {
            RisiMeTheme(dark = dark) {
                CreateGroupContent(
                    ui,
                    onQuery = { ui = ui.copy(query = it) },
                    onToggle = { id -> ui = ui.copy(selected = if (id in ui.selected) ui.selected - id else ui.selected + id) },
                    onNext = { ui = ui.copy(step = 1) },
                    onName = { ui = ui.copy(name = it, nameError = groupNameError(it) { s -> s.length }) },
                    onCreate = { events += "create" },
                    onBack = { events += "back" },
                )
            }
        }
    }

    @Test fun createFlowPicksReadyFriendsNamesAndCreates() {
        create(dark)
        rule.onNodeWithText("Nimal needs to update RisiMe").assertIsDisplayed()
        rule.onNodeWithText("Next").assertIsNotEnabled()
        rule.onNodeWithText("Nimal").performClick() // greyed: not selectable
        assertTrue(ui.selected.isEmpty())
        rule.onNodeWithText("Kamal").performClick()
        rule.onNodeWithText("1 selected").assertIsDisplayed()
        rule.onNodeWithText("Next").assertIsEnabled().performClick()
        rule.onNodeWithText("Create").assertIsNotEnabled()
        ui = ui.copy(name = "Pilot team", nameError = null)
        rule.onNodeWithText("2 members, you included").assertIsDisplayed()
        rule.onNodeWithText("Create").assertIsEnabled().performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("create", "back"), events)
    }

    @Test fun createShowsBusyAndErrorsWithBackStillWorking() {
        ui = CreateGroupUi(friends = friends, selected = setOf("u-kamal"), step = 1, name = "x", busy = true, error = "Someone needs to update RisiMe first.")
        create(dark)
        rule.onNodeWithContentDescription("Creating group").assertIsDisplayed()
        rule.onNodeWithText("Someone needs to update RisiMe first.").assertIsDisplayed()
        rule.onNodeWithText("Create").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        assertEquals(listOf("back"), events)
    }

    @Test fun searchFiltersFriends() {
        create(dark)
        ui = ui.copy(query = "sun")
        rule.onNodeWithText("Sunil").assertIsDisplayed()
        rule.onAllNodesWithText("Kamal").assertCountEquals(0)
    }

    /** Scrolls the (lazy) list until a node with [text] is composed, then returns it. */
    private fun scrollTo(text: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        rule.onAllNodes(androidx.compose.ui.test.hasScrollAction())[0].performScrollToNode(androidx.compose.ui.test.hasText(text))
        return rule.onNodeWithText(text)
    }

    // ---- Group info ----

    private val members = listOf(
        MemberUi("u-me", "Harsha", admin = true, state = "active", me = true, joinedAt = "2026-10-06T08:00:00.000Z"),
        MemberUi("u-kamal", "Kamal", admin = false, state = "active", me = false, joinedAt = "2026-10-06T08:00:00.000Z"),
        MemberUi("u-nimal", "Nimal", admin = false, state = "pending_add", me = false, joinedAt = null),
        MemberUi("u-sunil", "Sunil", admin = false, state = "pending_remove", me = false, joinedAt = "2026-10-06T09:00:00.000Z"),
    )

    private fun info(state: GroupInfoUi, dark: Boolean) {
        rule.setContent {
            RisiMeTheme(dark = dark) {
                GroupInfoContent(
                    state, onBack = { events += "back" }, onAdd = { events += "add:$it" }, onRemove = { events += "remove:$it" },
                    onSetAdmin = { id, a -> events += "admin:$id:$a" }, onRename = { events += "rename:$it" },
                    onLeave = { events += "leave" }, onReset = { events += "reset" }, onDismissError = { events += "ok" },
                )
            }
        }
    }

    /** §12.4a: a member's new phone waiting in a `devices` op shows on that member's row; no prompt. */
    @Test fun aMembersNewPhoneWaitingToBeAddedIsShownOnTheirRow() {
        info(GroupInfoUi(name = "Pilot team", members = members.map { if (it.userId == "u-kamal") it.copy(newPhone = true) else it }), dark)
        scrollTo("Kamal's new phone is being added").assertIsDisplayed()
    }

    @Test fun adminInfoShowsMembersRolesPendingAndActions() {
        info(GroupInfoUi(name = "Pilot team", members = members, iAmAdmin = true), dark)
        rule.onNodeWithText("Pilot team").assertIsDisplayed()
        rule.onNodeWithText("3 members").assertIsDisplayed() // the pending add isn't counted yet
        rule.onNodeWithText("Harsha (you)").assertIsDisplayed()
        rule.onNodeWithText("Admin").assertIsDisplayed()
        scrollTo("Adding…").assertIsDisplayed()
        scrollTo("Removing…").assertIsDisplayed()
        scrollTo("Kamal").performClick()
        rule.onNodeWithText("Make admin").performClick()
        rule.onNodeWithText("Kamal").performClick()
        rule.onNodeWithText("Remove Kamal").performClick()
        scrollTo("Leave group").performClick()
        rule.onNodeWithText("Leave group?").assertIsDisplayed()
        rule.onNodeWithText("Leave").performClick()
        assertEquals(listOf("admin:u-kamal:true", "remove:u-kamal", "leave"), events)
        scrollTo("Reset encryption").assertIsDisplayed()
    }

    @Test fun memberInfoHasNoAdminActionsButCanLeaveAndCancel() {
        info(GroupInfoUi(name = "Pilot team", members = members.map { it.copy(admin = !it.me && it.userId == "u-kamal") }, iAmAdmin = false), dark)
        rule.onAllNodesWithText("Add members").assertCountEquals(0)
        rule.onAllNodesWithText("Rename").assertCountEquals(0)
        rule.onAllNodesWithText("Reset encryption").assertCountEquals(0)
        scrollTo("Kamal").performClick() // no menu for non-admins
        rule.onAllNodesWithText("Remove Kamal").assertCountEquals(0)
        scrollTo("Leave group").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(events.isEmpty())
    }

    @Test fun lastAdminErrorIsShownWithASuggestionAndCanBeDismissed() {
        val state = GroupInfoUi(name = "Pilot team", members = members, iAmAdmin = true)
        assertEquals("Kamal", state.suggestedAdmin!!.name)
        info(state.copy(error = "Make another member an admin before you leave. Make Kamal an admin, then leave."), dark)
        rule.onNodeWithText("Make another member an admin before you leave. Make Kamal an admin, then leave.").assertIsDisplayed()
        rule.onNodeWithText("OK").performClick()
        assertEquals(listOf("ok"), events)
    }

    @Test fun leftGroupIsReadOnly() {
        info(GroupInfoUi(name = "Pilot team", members = members.filter { !it.me }, readOnly = true, stateLine = "You left this group"), dark)
        rule.onNodeWithText("You left this group").assertIsDisplayed()
        rule.onAllNodesWithText("Leave group").assertCountEquals(0)
    }

    // ---- Read by ----

    private val reply = GroupReceiptsReply(
        of = 3,
        receipts = listOf(
            GroupReceipt("u-kamal", "2026-10-06T08:15:31.002Z", "2026-10-06T08:15:40.120Z"),
            GroupReceipt("u-sunil", "2026-10-06T08:15:33.000Z", null),
            GroupReceipt("u-nimal", null, null),
        ),
    )

    private fun readBy(state: ReadByState, dark: Boolean) {
        rule.setContent {
            RisiMeTheme(dark = dark) {
                ReadByContent(state, { mapOf("u-kamal" to "Kamal", "u-sunil" to "Sunil", "u-nimal" to "Nimal")[it] ?: it }, { events += "retry" }, { events += "close" })
            }
        }
    }

    @Test fun readBySplitsReadDeliveredAndWaiting() {
        readBy(ReadByState.Loaded(reply), dark)
        rule.onNodeWithText("Read by 1 of 3").assertIsDisplayed()
        rule.onNodeWithText("Kamal").assertIsDisplayed()
        rule.onNodeWithText("Delivered to 1").assertIsDisplayed()
        rule.onNodeWithText("Sunil").assertIsDisplayed()
        scrollTo("Waiting 1").assertIsDisplayed()
        rule.onAllNodesWithText("Nimal").assertCountEquals(1)
        rule.onNodeWithText("Close").performClick()
        assertEquals(listOf("close"), events)
    }

    @Test fun readByLoadingAndErrorWithRetry() {
        readBy(ReadByState.Error("Offline — try again"), dark)
        rule.onNodeWithText("Offline — try again").assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(listOf("retry"), events)
    }

    @Test fun readByLoading() {
        readBy(ReadByState.Loading, dark)
        rule.onNodeWithContentDescription("Loading").assertIsDisplayed()
        rule.onNodeWithText("Close").assertIsDisplayed()
    }
}
