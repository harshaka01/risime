package ai.risicloud.risime.ui.tabs

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ai.risicloud.risime.data.MessageStatus
import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.data.tabs.MadeByLabels
import ai.risicloud.risime.data.tabs.RisiMessages
import ai.risicloud.risime.net.ProtocolJson
import ai.risicloud.risime.net.RisiMadeBy
import ai.risicloud.risime.net.RisiMadeByAlso
import ai.risicloud.risime.net.RisiMeta
import ai.risicloud.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/** §27.1 (A13): "Made by" on every Risi card — the labels, the time, `also`, and the sheet from the header. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RisiMadeByTest {
    @get:Rule val rule = createComposeRule()

    private val colombo = ZoneId.of("Asia/Colombo")
    private val at = "2026-10-09T03:44:31.402Z" // 09:14 in Colombo (a Friday)
    private val sameDay = Instant.parse("2026-10-09T10:00:00Z").toEpochMilli()
    private val nextDay = Instant.parse("2026-10-13T10:00:00Z").toEpochMilli()

    @Test fun labels() {
        assertEquals("RisiMe model (risi-l1)", MadeByLabels.label("risi-l1", "risime"))
        assertEquals("RisiMe (no AI model)", MadeByLabels.label(null, "risime"))
        assertEquals("Anthropic (claude-x)", MadeByLabels.label("claude-x", "Anthropic"))
        // Developer view only (no developer UI yet): the model/provider strings.
        assertEquals("Made by: RisiMe model (risi-l1) · 09:14", MadeByLabels.developerTitle(RisiMadeBy("risi-l1", "risime", at), sameDay, colombo))
        assertEquals("Made by: RisiMe (no AI model) · Fri 09:14", MadeByLabels.developerTitle(RisiMadeBy(null, "risime", at), nextDay, colombo))
        assertEquals(MadeByLabels.NOT_RECORDED, MadeByLabels.developerTitle(null, sameDay, colombo))
        // What users see.
        assertEquals("Made by Risi · 09:14", MadeByLabels.title(RisiMadeBy("risi-l1", "risime", at), sameDay, colombo))
        assertEquals("Made by Risi · Fri 09:14", MadeByLabels.title(RisiMadeBy(null, "risime", at), nextDay, colombo))
        assertEquals("Made by Risi", MadeByLabels.title(null, sameDay, colombo))
        val also = RisiMadeBy("risi-l1", "risime", at, listOf(RisiMadeByAlso("faster-whisper-large-v3", "risime", "transcribe"), RisiMadeByAlso("m2", "Anthropic", "plan")))
        assertEquals(listOf("Transcribed by: RisiMe model (faster-whisper-large-v3)", "Also used: Anthropic (m2)"), MadeByLabels.alsoLines(also))
    }

    private class Host(override val me: String) : RisiHost {
        val calls = mutableListOf<String>()
        override fun ask(text: String) {}
        override fun summarise() {}
        override fun report() {}
        override fun act(target: String, action: String, editText: String?, editDue: String?) {}
        override fun feedback(callRef: String, rating: String, reason: String?) { calls += "feedback:$rating" }
    }

    private fun row(file: String): Pair<MessageEntity, RisiMeta> {
        val env = ProtocolJson.parseToJsonElement(javaClass.classLoader!!.getResource("contract/v1/examples/$file")!!.readText()) as JsonObject
        val m = MessageEntity(
            clientMsgId = file, messageId = "c1a2b3f1-a4f0-11f1-8000-0242ac120009", conversationId = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b",
            from = "9e1f0000-0000-4000-8000-000000000001", to = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b",
            body = (env["body"] as JsonPrimitive).content, serverTs = "2026-10-09T03:45:31.402Z", localTs = 1L,
            status = MessageStatus.DELIVERED.name, outgoing = false, systemJson = RisiMessages.encode(env["risi"] as JsonObject),
        )
        return m to RisiMessages.meta(m)!!
    }

    @Test fun tapOnTheHeaderShowsTheSheetWithFeedback() {
        val host = Host("7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3")
        val (m, r) = row("envelope_risi_answer_made_by.json")
        val ctx = risiCardContext(host, listOf(m), { "Someone" }, Instant.parse("2026-10-09T05:00:00Z").toEpochMilli(), onRef = {})
        rule.setContent { RisiMeTheme { Column { RisiCardRow(m, r, ctx) } } }
        rule.onNodeWithTag("risi_made_by_open").performClick()
        rule.onNodeWithTag("risi_made_by_sheet").assertExists()
        rule.onNodeWithText("Made by Risi", substring = true).assertExists()
        rule.onNodeWithText("risi-l1", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("risi_made_by_up").performClick()
        assertEquals(listOf("feedback:up"), host.calls)
    }

    @Test fun ruleMessageSaysNoModel() {
        val host = Host("7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3")
        val (m, r) = row("envelope_risi_item_due.json")
        val ctx = risiCardContext(host, listOf(m), { "Someone" }, Instant.parse("2026-10-09T10:40:00Z").toEpochMilli(), onRef = {})
        rule.setContent { RisiMeTheme { Column { RisiCardRow(m, r, ctx) } } }
        rule.onNodeWithTag("risi_made_by_open").performClick()
        rule.onNodeWithText("Made by Risi", substring = true).assertExists()
        rule.onNodeWithText("no AI model", substring = true).assertDoesNotExist()
    }

    /** Harsha 2026-10-12: a made_by naming models (and `also` models) never puts a model or vendor name on screen. */
    @Test fun labelNeverNamesAModelOrVendor() {
        val m = RisiMadeBy(
            "gemma-3-27b", "Google", at,
            listOf(RisiMadeByAlso("faster-whisper-large-v3", "risime", "transcribe"), RisiMadeByAlso("qwen2.5-7b", "Alibaba", "plan"), RisiMadeByAlso("claude-x", "Anthropic", "plan")),
        )
        val shown = MadeByLabels.title(m, sameDay, colombo)
        assertEquals("Made by Risi · 09:14", shown)
        for (w in listOf("gemma", "google", "whisper", "faster-whisper", "qwen", "alibaba", "anthropic", "claude", "model", "risime")) {
            assertTrue("label shows '$w': $shown", w !in shown.lowercase())
        }
    }

    @Test fun sheetShowsNoAlsoModels() {
        val host = Host("7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3")
        val (m, r0) = row("envelope_risi_answer_made_by.json")
        val r = r0.copy(madeBy = RisiMadeBy("gemma-3-27b", "Google", at, listOf(RisiMadeByAlso("faster-whisper-large-v3", "risime", "transcribe"))))
        val ctx = risiCardContext(host, listOf(m), { "Someone" }, Instant.parse("2026-10-09T05:00:00Z").toEpochMilli(), onRef = {})
        rule.setContent { RisiMeTheme { Column { RisiCardRow(m, r, ctx) } } }
        rule.onNodeWithTag("risi_made_by_open").performClick()
        rule.onNodeWithText("Made by Risi", substring = true).assertExists()
        for (w in listOf("gemma", "Google", "whisper", "Transcribed by")) rule.onNodeWithText(w, substring = true, ignoreCase = true).assertDoesNotExist()
    }
}
