package lk.codegen.risime.ui.settings

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiCommitmentsReply
import lk.codegen.risime.net.RisiFactsReply
import lk.codegen.risime.net.RisiRest
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §24.11 "What Risi knows about me" (grouped by kind, per-item delete, "Delete everything") and "My promises". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RisiDataTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    private fun example(name: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()

    private class Fake(
        var facts: RisiFactsReply,
        var commitments: RisiCommitmentsReply = RisiCommitmentsReply(emptyList()),
    ) : RisiRest {
        val calls = mutableListOf<String>()
        var down = false
        override suspend fun feedback(callRef: String, rating: String, reason: String?): ApiResult<Unit> { calls += "feedback"; return ApiResult.Ok(Unit) }
        override suspend fun facts(): ApiResult<RisiFactsReply> = if (down) ApiResult.NetworkError(java.io.IOException("x")) else ApiResult.Ok(facts)
        override suspend fun deleteFact(factId: String): ApiResult<Unit> {
            calls += "delete:$factId"
            if (down) return ApiResult.NetworkError(java.io.IOException("x"))
            facts = RisiFactsReply(facts.facts.filter { it.factId != factId })
            return ApiResult.Ok(Unit)
        }
        override suspend fun deleteAllFacts(): ApiResult<Unit> {
            calls += "delete_all"
            if (down) return ApiResult.NetworkError(java.io.IOException("x"))
            facts = RisiFactsReply(emptyList())
            return ApiResult.Ok(Unit)
        }
        override suspend fun commitments(state: String): ApiResult<RisiCommitmentsReply> { calls += "commitments:$state"; return ApiResult.Ok(commitments) }
    }

    private val facts get() = ProtocolJson.decodeFromString(RisiFactsReply.serializer(), example("risi_facts_reply.json"))

    private fun await(p: () -> Boolean) = runBlocking { withTimeout(5_000) { while (!p()) kotlinx.coroutines.delay(10) } }

    private fun screen(rest: Fake): RisiFactsModel {
        val model = RisiFactsModel(rest, scope)
        model.load()
        rule.setContent { RisiMeTheme { RisiFactsScreen(model, onBack = {}) } }
        await { !model.state.value.loading }
        rule.waitForIdle()
        return model
    }

    @Test fun factsAreGroupedByKindInTheContractOrder() {
        val rest = Fake(facts)
        val model = screen(rest)
        assertEquals(listOf("Commitments", "Preferences"), model.state.value.grouped.map { it.first })
        rule.onNodeWithText("Send the revised quote to Harsha by Friday").assertExists()
        rule.onNodeWithText("Prefers calls after 4 pm").assertExists()
        assertEquals(2, rule.onAllNodesWithTag("risi_fact").fetchSemanticsNodes().size)
    }

    @Test fun deletingOneItemCallsTheServerAndRemovesOnlyThatRow() {
        val rest = Fake(facts)
        val model = screen(rest)
        rule.onAllNodesWithTag("risi_fact_delete")[0].performClick()
        await { model.state.value.facts.size == 1 }
        rule.waitForIdle()
        assertEquals(listOf("delete:a0b1c2d3-e4f5-4a6b-8c7d-8e9f0a1b2c3d"), rest.calls.filter { it.startsWith("delete") })
        rule.onNodeWithText("Prefers calls after 4 pm").assertExists()
        assertEquals(1, rule.onAllNodesWithTag("risi_fact").fetchSemanticsNodes().size)
    }

    @Test fun deleteEverythingAsksFirstAndCancelDeletesNothing() {
        val rest = Fake(facts)
        screen(rest)
        rule.onNodeWithTag("risi_delete_all").performClick()
        rule.onNodeWithTag("risi_delete_all_confirm").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertTrue(rest.calls.none { it.startsWith("delete") })
        assertEquals(2, rule.onAllNodesWithTag("risi_fact").fetchSemanticsNodes().size)
    }

    @Test fun deleteEverythingConfirmedClearsTheList() {
        val rest = Fake(facts)
        val model = screen(rest)
        rule.onNodeWithTag("risi_delete_all").performClick()
        rule.onNodeWithTag("risi_delete_all_confirm").performClick()
        await { model.state.value.facts.isEmpty() }
        rule.waitForIdle()
        assertEquals(listOf("delete_all"), rest.calls)
        rule.onNodeWithTag("risi_facts_empty").assertExists()
    }

    @Test fun anOfflineDeleteKeepsTheRowAndSaysSo() {
        val rest = Fake(facts)
        val model = screen(rest)
        rest.down = true
        rule.onAllNodesWithTag("risi_fact_delete")[0].performClick()
        await { model.state.value.error != null }
        rule.waitForIdle()
        assertEquals(2, model.state.value.facts.size)
        rule.onNodeWithTag("risi_facts_error").assertExists()
    }

    @Test fun myPromisesLoadsTheOpenOnes() {
        val rest = Fake(facts, ProtocolJson.decodeFromString(RisiCommitmentsReply.serializer(), example("risi_commitments_reply.json")))
        val model = RisiPromisesModel(rest, scope)
        model.load()
        rule.setContent { RisiMeTheme { RisiPromisesScreen(model, me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3", nameOf = { "Kamal" }, onBack = {}) } }
        await { !model.state.value.loading }
        rule.waitForIdle()
        assertEquals(listOf("commitments:open"), rest.calls)
        rule.onNodeWithText("Send the revised quote").assertExists()
        rule.onNodeWithTag("risi_promise").assertExists()
    }

    /** v1.29 §30.5 a Notes device: "From note: <title>", [Done] = the note's tick, [Reopen] in "Done (last 7 days)". */
    @Config(qualifiers = "w411dp-h1600dp")
    @Test fun myPromisesOnANotesDeviceTicksLikeTheNote() {
        val open = ProtocolJson.decodeFromString(RisiCommitmentsReply.serializer(), javaClass.classLoader!!.getResource("fixtures/risi_notes/risi_commitments_reply_v129.json")!!.readText())
        val doneRecently = open.commitments[0].copy(commitmentId = "e5f6a7b8-c9d0-4e1f-8a2b-4c5d6e7f8a9b", text = "Share the CV", state = "done", status = "done", updatedAt = "2026-10-09T08:00:00.000Z")
        val doneLongAgo = doneRecently.copy(commitmentId = "f6a7b8c9-d0e1-4f2a-9b3c-5d6e7f8a9b0c", text = "Old thing", updatedAt = "2026-09-01T08:00:00.000Z")
        val rest = object : RisiRest by Fake(facts) {
            val calls = mutableListOf<String>()
            override suspend fun commitments(state: String): ApiResult<RisiCommitmentsReply> {
                calls += state
                return ApiResult.Ok(if (state == "all") RisiCommitmentsReply(open.commitments + doneRecently + doneLongAgo) else open)
            }
        }
        val acts = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val notes = object : RisiPromiseNotes {
            override suspend fun title(noteId: String) = "Harsha × Shenika · interview planning · Fri 9 Oct"
            override fun open(noteId: String) { opened += noteId }
            override suspend fun act(itemId: String, action: String): Boolean { acts += "$action:$itemId"; return true }
        }
        val now = java.time.Instant.parse("2026-10-09T12:00:00Z").toEpochMilli()
        val model = RisiPromisesModel(rest, scope, null, notes, now = { now })
        model.load()
        rule.setContent { RisiMeTheme { RisiPromisesScreen(model, me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3", nameOf = { "Shenika" }, onBack = {}) } }
        await { !model.state.value.loading && model.state.value.noteTitles.isNotEmpty() }
        rule.waitForIdle()
        assertEquals(listOf("open", "all"), rest.calls)
        assertEquals(listOf("Share the CV"), model.state.value.done.map { it.text }) // within 7 days only
        rule.onAllNodesWithText("From note: Harsha × Shenika · interview planning · Fri 9 Oct")[0].performClick()
        assertEquals(listOf("a7b8c9d0-e1f2-4a3b-8c4d-5e6f7a8b9c0d"), opened)
        // [Done] only on the note's ledger item (the legacy-shaped item without summary/note has none).
        assertEquals(1, rule.onAllNodesWithTag("risi_promise_done").fetchSemanticsNodes().size)
        rule.onNodeWithTag("risi_promise_done").performClick()
        await { model.state.value.done.size == 2 }
        assertEquals(listOf("done:c3d4e5f6-a7b8-4c9d-8e0f-2a3b4c5d6e7f"), acts)
        rule.waitForIdle()
        rule.onAllNodesWithTag("risi_promise_reopen")[0].performClick()
        await { acts.size == 2 }
        assertEquals("item_reopen:c3d4e5f6-a7b8-4c9d-8e0f-2a3b4c5d6e7f", acts[1])
        await { model.state.value.items.any { it.commitmentId == "c3d4e5f6-a7b8-4c9d-8e0f-2a3b4c5d6e7f" } }
    }

    /** Notes off: My promises is exactly as before (no `state=all`, no Done/Reopen, no "From note"). */
    @Test fun myPromisesWithoutNotesIsUnchanged() {
        val open = ProtocolJson.decodeFromString(RisiCommitmentsReply.serializer(), javaClass.classLoader!!.getResource("fixtures/risi_notes/risi_commitments_reply_v129.json")!!.readText())
        val rest = Fake(facts, open)
        val model = RisiPromisesModel(rest, scope)
        model.load()
        rule.setContent { RisiMeTheme { RisiPromisesScreen(model, me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3", nameOf = { "Shenika" }, onBack = {}) } }
        await { !model.state.value.loading }
        rule.waitForIdle()
        assertEquals(listOf("commitments:open"), rest.calls)
        assertEquals(0, rule.onAllNodesWithTag("risi_promise_done").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("risi_promise_from_note").fetchSemanticsNodes().size)
    }

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("fixtures/risi_items_8_10/$name")!!.readText()

    private class Opener(private val here: Set<String>) : RisiPromiseOpener {
        val opened = mutableListOf<Pair<String, String?>>()
        override suspend fun has(conversationId: String) = conversationId in here
        override fun open(conversationId: String, messageId: String?) { opened += conversationId to messageId }
    }

    private fun promises(opener: Opener?): RisiPromisesModel {
        val rest = Fake(facts, ProtocolJson.decodeFromString(RisiCommitmentsReply.serializer(), fixture("risi_commitments_reply_item9.json")))
        val model = RisiPromisesModel(rest, scope, opener)
        model.load()
        rule.setContent { RisiMeTheme { RisiPromisesScreen(model, me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3", nameOf = { "Someone" }, onBack = {}) } }
        await { !model.state.value.loading }
        rule.waitForIdle()
        return model
    }

    @Config(qualifiers = "w411dp-h1200dp")
    @Test fun myPromisesSplitsByDirectionWithTheServersTotals() {
        val model = promises(null)
        assertEquals(listOf("I promised", "Promised to me", "Others"), model.state.value.sections.map { it.title })
        rule.onNodeWithText("I promised (2)").assertExists()
        rule.onNodeWithText("Promised to me (1)").assertExists()
        rule.onNodeWithText("Others (1)").assertExists()
        assertEquals(2, rule.onAllNodesWithTag("risi_promise").fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodesWithTag("risi_owed").fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodesWithTag("risi_promise_other").fetchSemanticsNodes().size)
        // owner_name, due (all-day aware) and the status, "Needs a date" for the vague item.
        rule.onNodeWithText("Needs a date").assertExists()
        rule.onNodeWithText("Shenika · ", substring = true).assertExists()
        rule.onNodeWithText("Kamal · ", substring = true).assertExists()
    }

    @Test fun tappingARowOpensItsSourceChatAtTheSourceMessage() {
        val opener = Opener(setOf("grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"))
        promises(opener)
        rule.onNodeWithText("Book the site visit transport").performClick()
        await { opener.opened.isNotEmpty() }
        assertEquals(listOf("grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b" to "c1a2b3f1-a4f0-11f1-8000-0242ac120009"), opener.opened)
    }

    @Config(qualifiers = "w411dp-h1200dp")
    @Test fun aChatNotOnThisPhoneSaysSoInsteadOfOpening() {
        val opener = Opener(emptySet())
        val model = promises(opener)
        rule.onNodeWithText("Kamal sends the minutes").performClick()
        await { model.state.value.note != null }
        rule.waitForIdle()
        assertTrue(opener.opened.isEmpty())
        rule.onNodeWithTag("risi_promises_note").assertExists()
    }
}
