package lk.codegen.risime.ui.chats

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import lk.codegen.risime.data.db.LastMessage
import lk.codegen.risime.net.Presence
import lk.codegen.risime.ui.common.PHOTO_SHEET_GALLERY
import lk.codegen.risime.ui.common.PHOTO_SHEET_REMOVE
import lk.codegen.risime.ui.common.PHOTO_SHEET_TAKE
import lk.codegen.risime.ui.common.PhotoSheet
import lk.codegen.risime.ui.common.photoSheetRows
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** UI batch 2026-10-10: the one round FAB and its sheet, the Requests tab on one line, the photo sheet, the list row, no fake chips. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h851dp-xxhdpi", application = Application::class)
class UiBatch20261010Test {
    @get:Rule val rule = createComposeRule()

    @Test fun theFabIsDescribedNewAndOpensExactlyThreeRows() {
        val events = mutableListOf<String>()
        var sheet by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent {
            RisiMeTheme {
                NewFab({ sheet = true })
                if (sheet) NewSheet({ events += "chat" }, { events += "group" }, { events += "friend" }, { sheet = false })
            }
        }
        assertEquals("New", NEW_FAB_DESCRIPTION)
        rule.onNodeWithContentDescription("New").assertIsDisplayed().performClick()
        assertEquals(listOf("New chat", "New group", "Add friend"), NEW_SHEET_ROWS)
        rule.onNodeWithText("New chat").assertExists()
        rule.onNodeWithText("New group").assertExists()
        rule.onNodeWithText("Add friend").assertExists().performClick()
        rule.onNodeWithTag("new_sheet_chat").performClick()
        rule.onNodeWithTag("new_sheet_group").performClick()
        assertEquals(listOf("friend", "chat", "group"), events)
    }

    @Test fun requestsTabStaysOnOneLineAtFontScale13() {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 1.3f)) {
                RisiMeTheme { ChatsTabRow(tab = 2, calendarOn = true, incoming = 12, onSelect = {}) }
            }
        }
        // One line of label text at 1.3x (labelLarge 14sp -> ~18sp): the node is not taller than a single line.
        rule.onNodeWithText("Requests").assertIsDisplayed()
        val one = rule.onNodeWithText("Chats").fetchSemanticsNode().size.height
        val req = rule.onNodeWithText("Requests").fetchSemanticsNode().size.height
        assertTrue("Requests wrapped: $req px vs one line $one px", req <= one + 2)
        rule.onNodeWithText("Chats").assertIsDisplayed()
        rule.onNodeWithText("Calendar").assertIsDisplayed()
    }

    @Test fun photoSheetRowsRemoveOnlyWithAPhoto() {
        assertEquals(listOf("Take photo", "Gallery"), photoSheetRows(false))
        assertEquals(listOf("Take photo", "Gallery", "Remove photo"), photoSheetRows(true))
        assertEquals(listOf(PHOTO_SHEET_TAKE, PHOTO_SHEET_GALLERY, PHOTO_SHEET_REMOVE), photoSheetRows(true))
        val ev = mutableListOf<String>()
        rule.setContent { RisiMeTheme { PhotoSheet(true, { ev += "take" }, { ev += "gallery" }, { ev += "remove" }, {}) } }
        rule.onNodeWithText("Gallery").performClick()
        rule.onNodeWithText("Remove photo").performClick()
        rule.onNodeWithText("Take photo").performClick()
        assertEquals(listOf("gallery", "remove", "take"), ev)
    }

    @Test fun noLastSeenInTheListRow() {
        val row = ChatRow(
            userId = "u1", name = "Kamal", company = "Rise", registered = true,
            last = LastMessage("dm:a:b", "see you", 1_000L, false, "DELIVERED"),
            presence = Presence("u1", false, "2026-10-09T10:00:00Z"),
        )
        rule.setContent { RisiMeTheme { ChatRowItem(row, onClick = {}) } }
        rule.onNodeWithText("Kamal").assertIsDisplayed()
        rule.onNodeWithText("see you").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("last seen", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
    }

    @Test fun newChatListsOnlyRegisteredFriendsOneToOne() {
        fun r(id: String?, name: String, friend: Boolean = true, registered: Boolean = true, group: Boolean = false, risi: Boolean = false) =
            ChatRow(id, name, "", registered, null, friend = friend, group = group, risi = risi, conversationId = if (group) "grp:1" else null)
        val rows = listOf(r("1", "Kamal"), r("2", "Ex", friend = false), r("3", "Pending", registered = false), r(null, "Team", group = true), r(null, "Risi", risi = true))
        assertEquals(listOf("Kamal"), newChatCandidates(rows).map { it.name })
    }

    // ---- chip audit (item 7): a source scan, no device needed ----

    private val chipCalls = Regex("""\b(AssistChip|SuggestionChip|FilterChip|InputChip(?:Default)?)\(""")

    private fun sources(): List<Pair<String, String>> {
        val root = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
        return root.walkTopDown().filter { it.extension == "kt" }.map { it.path to it.readText() }.toList()
    }

    /** The text of one call starting at [open] (balanced parentheses). */
    private fun callText(s: String, open: Int): String {
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) { '(' -> depth++; ')' -> { depth--; if (depth == 0) return s.substring(open, i + 1) } }
            i++
        }
        return s.substring(open)
    }

    @Test fun everyChipDoesSomethingAndNoneSendsItsLabel() {
        val bad = mutableListOf<String>()
        var chips = 0
        for ((path, text) in sources()) {
            if (path.endsWith("PhotoCrop.kt") && false) continue
            for (m in chipCalls.findAll(text)) {
                chips++
                val call = callText(text, m.range.last)
                val click = call.substringAfter("onClick =", "").substringBefore("label").replace("\\s".toRegex(), "")
                val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                if (click.startsWith("{}") || click.isEmpty()) bad += "$path:$line has no action"
                // P0 2026-10-10: Risi's next-step chips are requests to Risi and RUN them (`ctx.sendChip` → `risi_request` ask);
                // that is their action, not a paste of the label. Any other chip that sends or pre-fills its label is still a fake chip.
                val risiRequestChip = path.endsWith("RisiSkillCardsUi.kt") && click.startsWith("{RisiChips.run(c,ctx.host,send)")
                if (!risiRequestChip && Regex("""\b(send|onSend|ask|sendText|sendMessage|prefill)\(""").containsMatchIn(click)) bad += "$path:$line sends/prefills its label"
            }
        }
        assertTrue("chips: $chips", chips > 0)
        assertEquals(emptyList<String>(), bad)
    }

    // P0 2026-10-10 supersedes item 7: the next-step chips are back, and they run the request instead of pasting it.
    @Test fun risiNextStepChipsRunTheRequestAndNeverPrefill() {
        val skill = sources().first { it.first.endsWith("RisiSkillCardsUi.kt") }.second
        val extras = skill.substringAfter("internal fun AnswerExtras").substringBefore("\n}\n")
        assertTrue("RisiChips.run(c, ctx.host, send)" in extras)
        assertTrue("ctx.sendChip" in extras)
        assertFalse("prefill" in extras)
    }
}
