package lk.codegen.risime.ui.notes

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.notes.NoteView
import lk.codegen.risime.data.notes.NotesListUi
import lk.codegen.risime.data.notes.RisiNotes
import lk.codegen.risime.data.notes.ShareUi
import lk.codegen.risime.data.tabs.RisiMessages
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiNote
import lk.codegen.risime.net.RisiNoteReply
import lk.codegen.risime.net.RisiNotesReply
import lk.codegen.risime.ui.tabs.RisiCardRow
import lk.codegen.risime.ui.tabs.RisiHost
import lk.codegen.risime.ui.tabs.risiCardContext
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/** v1.29 §30.4/§30.6 the note card, "Notes saved", the Notes list, the note screen and the share picker as Compose. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h1600dp")
class RisiNotesUiTest {
    @get:Rule val rule = createComposeRule()

    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val shenika = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val noteId = "a7b8c9d0-e1f2-4a3b-8c4d-5e6f7a8b9c0d"
    private val mine = "c3d4e5f6-a7b8-4c9d-8e0f-2a3b4c5d6e7f"
    private val now = java.time.Instant.parse("2026-10-09T10:00:00Z").toEpochMilli()
    private var tz: TimeZone? = null

    @Before fun zone() {
        tz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Colombo"))
    }

    @After fun restore() = TimeZone.setDefault(tz)

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("fixtures/risi_notes/$name")!!.readText()

    private fun row(name: String, id: String = name): MessageEntity {
        val env = ProtocolJson.parseToJsonElement(fixture(name)).jsonObject
        return MessageEntity(
            clientMsgId = id, messageId = "m-$id", conversationId = "grp:risi", from = "risi", to = "grp:risi",
            body = (env["body"] as JsonPrimitive).content, serverTs = "2026-10-09T09:41:42.000Z", localTs = 1,
            status = "READ", outgoing = false, systemJson = RisiMessages.encode(env["risi"] as JsonObject),
        )
    }

    private class Host(override val me: String, override val notesOn: Boolean) : RisiHost {
        val calls = mutableListOf<String>()
        override fun ask(text: String) {}
        override fun summarise() {}
        override fun report() {}
        override fun act(target: String, action: String, editText: String?, editDue: String?) { calls += "act:$action:$target" }
        override fun actItem(itemId: String, action: String, text: String?, due: String?, allDay: Boolean) { calls += "$action:$itemId" }
        override fun feedback(callRef: String, rating: String, reason: String?) {}
        override val myName: String get() = "Harsha"
        override fun openNote(noteId: String) { calls += "note:$noteId" }
    }

    private val names = mapOf(shenika to "Shenika")

    private fun show(rows: List<MessageEntity>, host: Host, target: MessageEntity = rows.first()) {
        val r = RisiMessages.meta(target)!!
        rule.setContent {
            RisiMeTheme {
                Column { RisiCardRow(target, r, risiCardContext(host, rows, { names[it] ?: "You" }, now, onRef = {})) }
            }
        }
        rule.waitForIdle()
    }

    @Test fun noteCardShowsTitlePointsItemsAndMeetingsAndActs() {
        val host = Host(me, notesOn = true)
        show(listOf(row("envelope_risi_note_card.json")), host)
        rule.onNodeWithTag("risi_card_note_card").assertExists()
        rule.onNodeWithText("Risi · Notes").assertExists()
        rule.onNodeWithText("Harsha × Shenika · interview planning · Fri 9 Oct").assertExists()
        assertEquals(3, rule.onAllNodesWithTag("risi_note_key_point", useUnmergedTree = true).fetchSemanticsNodes().size)
        rule.onNodeWithText("Shenika · by Mon 12 Oct · waiting").assertExists() // her proposed item: read-only
        rule.onNodeWithTag("risi_note_item_confirm").performClick() // ✓ on my proposed item
        rule.onNodeWithText("• Interview · Mon 12 Oct, 14:00").assertExists()
        rule.onNodeWithTag("risi_note_open").performClick()
        assertEquals(listOf("item_confirm:$mine", "note:$noteId"), host.calls)
    }

    @Test fun aConfirmedItemIsATickBoxAndTickingSendsDone() {
        val host = Host(me, notesOn = true)
        val card = row("envelope_risi_note_card.json")
        val upd = ProtocolJson.parseToJsonElement(fixture("envelope_risi_item_update_done.json")).jsonObject["risi"]!!.jsonObject
        val confirmed = JsonObject(upd + ("state" to JsonPrimitive("confirmed")))
        val u = card.copy(clientMsgId = "u1", messageId = "m-u1", body = "confirmed", systemJson = RisiMessages.encode(confirmed))
        show(listOf(card, u), host, card)
        rule.onNodeWithTag("risi_note_item_tick").performClick()
        assertEquals(listOf("done:$mine"), host.calls)
    }

    @Test fun aDoneItemIsTickedAndUntickingSendsReopen() {
        val host = Host(me, notesOn = true)
        val card = row("envelope_risi_note_card.json")
        show(listOf(card, row("envelope_risi_item_update_done.json")), host, card)
        rule.onNodeWithTag("risi_note_item_ticked").performClick()
        assertEquals(listOf("item_reopen:$mine"), host.calls)
    }

    @Test fun notesSavedOpensMyNoteOnlyOnANotesDevice() {
        val on = Host(me, notesOn = true)
        show(listOf(row("envelope_risi_notes_saved.json")), on)
        rule.onNodeWithText("Risi · Notes saved").assertExists()
        rule.onNodeWithText("Interview planning: the interview moves to Monday; questions go out on Friday.").assertExists()
        rule.onNodeWithText("2 agreed · 1 meeting").assertExists()
        rule.onNodeWithTag("risi_notes_saved_open").performClick()
        assertEquals(listOf("note:$noteId"), on.calls)
    }

    @Test fun notesSavedForANonNotesPhoneOrANonParticipantIsTheLine() {
        show(listOf(row("envelope_risi_notes_saved.json")), Host(me, notesOn = false))
        rule.onNodeWithTag("risi_notes_saved_open").assertDoesNotExist()
        rule.onNodeWithText("Interview planning: the interview moves to Monday; questions go out on Friday.").assertExists()
    }

    @Test fun notesListRowsSearchShareDelete() {
        val notes = ProtocolJson.decodeFromString(RisiNotesReply.serializer(), fixture("risi_notes_reply.json")).notes
        val calls = mutableListOf<String>()
        rule.setContent {
            RisiMeTheme {
                NotesListScreen(
                    NotesListUi(loading = false, notes = notes, hasMore = true), titleOf = { it.topic },
                    onQuery = { calls += "q:$it" }, onOpen = { calls += "open:$it" }, onShare = { calls += "share:$it" }, onDelete = { calls += "delete:$it" },
                    onMore = { calls += "more" }, onAskDeleteAll = { calls += "all" }, onConfirmDeleteAll = {}, onCancelDeleteAll = {}, onBack = {},
                )
            }
        }
        rule.onNodeWithText(RisiNotes.INFO_LINE).assertExists()
        rule.onNodeWithText("interview planning").assertExists()
        rule.onNodeWithText("2 agreed · 1 open · 1 meeting · chat").assertExists()
        rule.onNodeWithTag("risi_notes_search").performTextInput("room")
        rule.onNodeWithText("interview planning").performClick()
        rule.onAllNodesWithTag("risi_notes_row_menu")[0].performClick()
        rule.onNodeWithTag("risi_notes_row_share").performClick()
        rule.onAllNodesWithTag("risi_notes_row_menu")[1].performClick()
        rule.onNodeWithTag("risi_notes_row_delete").performClick()
        rule.onNodeWithTag("risi_notes_more").performClick()
        assertEquals(listOf("q:room", "open:$noteId", "share:$noteId", "delete:b8c9d0e1-f2a3-4b4c-9d5e-6f7a8b9c0d1e", "more"), calls)
    }

    @Test fun noteScreenTicksAcceptsOpensAndShares() {
        val n = ProtocolJson.decodeFromString(RisiNoteReply.serializer(), fixture("risi_note_reply.json")).note
            .let { it.copy(events = it.events.map { e -> e.copy(myStatus = "proposed") }) }
        val items = RisiNotes.liveItems(n.items, n.noteId, emptyList(), null)
        val calls = mutableListOf<String>()
        rule.setContent {
            RisiMeTheme {
                NoteScreen(
                    NoteView(n, items, emptyMap(), expired = false), title = "Harsha × Shenika · interview planning · Fri 9 Oct",
                    loading = false, error = null, notice = null, me = me, nameOf = { names[it] ?: "You" }, canDelete = true, canAnswerEvents = true,
                    actions = NoteItemActions(tick = { id, c -> calls += "tick:$id:$c" }, send = { id, a, _, _, _ -> calls += "$a:$id" }),
                    onAcceptEvent = { calls += "accept:$it" }, onOpenEvent = { calls += "event:$it" }, onOpenChat = { calls += "chat:${it.conversationId}" },
                    onShare = { calls += "share" }, onDelete = { calls += "delete" }, onBack = {},
                )
            }
        }
        rule.onNodeWithText("Harsha × Shenika · interview planning · Fri 9 Oct").assertExists()
        rule.onNodeWithTag("risi_note_item_tick").performClick() // mine: confirmed → done
        rule.onNodeWithTag("risi_note_item_ticked").performClick() // Shenika's done (I am the counterpart) → reopen
        rule.onNodeWithTag("risi_note_event_accept").performClick()
        rule.onNodeWithTag("risi_note_event_open").performClick()
        rule.onNodeWithTag("risi_note_open_chat").performClick()
        rule.onNodeWithTag("risi_note_share").performClick()
        rule.onNodeWithTag("risi_note_menu").performClick()
        rule.onNodeWithTag("risi_note_delete").performClick()
        rule.onNodeWithTag("risi_note_delete_confirm").performClick()
        assertEquals(
            listOf("tick:$mine:true", "tick:d4e5f6a7-b8c9-4d0e-9f1a-3b4c5d6e7f8a:false", "accept:5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b", "event:5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b", "chat:grp:9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d", "share", "delete"),
            calls,
        )
    }

    @Test fun sharePickerListsChatsAndPicks() {
        var picked: Pair<String, String>? = null
        rule.setContent {
            RisiMeTheme { ShareChatPicker(ShareUi(text = "Notes: x"), listOf("dm:a_b" to "Kumu", "grp:x" to "Site team"), onPick = { c, n -> picked = c to n }, onCancel = {}) }
        }
        rule.onNodeWithText("It is sent as your own message.").assertExists()
        rule.onNodeWithText("Site team").performClick()
        assertEquals("grp:x" to "Site team", picked)
        assertTrue(RisiNote.parse(row("envelope_risi_note_card.json").systemJson) != null)
    }
}
