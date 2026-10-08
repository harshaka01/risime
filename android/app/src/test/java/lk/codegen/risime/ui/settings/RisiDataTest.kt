package lk.codegen.risime.ui.settings

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
}
