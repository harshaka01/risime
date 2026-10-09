package lk.codegen.risime.data.tabs

import kotlinx.serialization.json.JsonObject
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiActionEnvelope
import lk.codegen.risime.net.RisiProgress
import lk.codegen.risime.net.RisiTextEnvelope
import lk.codegen.risime.net.RisiUndo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** §26.5 A10: the pure rules behind the skill cards. */
class RisiSkillCardsTest {
    private fun read(name: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()
    private fun risi(name: String) = ProtocolJson.decodeFromString<RisiTextEnvelope>(read(name)).risi!!

    @Test fun alarmArgsAreShownExactly() {
        val lines = RisiSkillCards.argLines(risi("envelope_risi_confirm_set_alarm.json"))
        assertEquals(listOf("Time" to "05:30", "Label" to "“Wake up”", "Repeats" to "Once (next 05:30)"), lines)
    }

    @Test fun scheduleArgsNameTheRecipientAndEveryKey() {
        val r = risi("envelope_risi_confirm_schedule_message.json")
        val lines = RisiSkillCards.argLines(r, { if (it.startsWith("dm:")) "Kumu" else null }, ZoneOffset.ofHoursMinutes(5, 30))
        assertEquals(listOf("To" to "Kumu", "Message" to "“Good morning”", "First send" to "Sat 10 Oct, 06:00", "Repeats" to "Every day"), lines)
        // A recipient not on this phone says so; an extra key is shown as it is.
        val extra = r.copy(args = JsonObject(r.args!! + ("silent" to kotlinx.serialization.json.JsonPrimitive(true))))
        val l2 = RisiSkillCards.argLines(extra)
        assertEquals("a chat not on this phone", l2.first().second)
        assertEquals("silent" to "true", l2.last())
    }

    @Test fun daysText() {
        assertEquals("Mon, Wed", RisiSkillCards.daysText(listOf(3, 1)))
        assertEquals("Every day", RisiSkillCards.daysText((1..7).toList()))
        assertNull(RisiSkillCards.daysText(null))
    }

    @Test fun undoRules() {
        val manual = risi("envelope_risi_skill_done.json")
        assertFalse(RisiSkillCards.canUndo(manual, 0, emptySet()))
        assertTrue(RisiSkillCards.opensClock(manual))
        val client = manual.copy(undo = RisiUndo(RisiUndo.UNDO_CLIENT, RisiUndo.AVAILABLE, "2026-10-10T00:00:00Z"), undoToken = "u1.x")
        val before = java.time.Instant.parse("2026-10-09T00:00:00Z").toEpochMilli()
        assertTrue(RisiSkillCards.canUndo(client, before, emptySet()))
        assertFalse(RisiSkillCards.canUndo(client, before + 2 * 86_400_000L, emptySet())) // past `until`
        assertFalse(RisiSkillCards.canUndo(client, before, setOf(client.entryId!!))) // already tapped
        assertFalse(RisiSkillCards.canUndo(client.copy(undoToken = null), before, emptySet()))
        assertFalse(RisiSkillCards.canUndo(client.copy(undo = client.undo!!.copy(state = RisiUndo.DONE)), before, emptySet()))
    }

    @Test fun neededTexts() {
        val r = risi("envelope_risi_skill_needed.json")
        assertEquals("I no longer have access to your alarms. Turn it on in Settings → Risi skills.", RisiSkillCards.neededText(r, ""))
        assertEquals("I don't have access to your alarms yet. Turn it on in Settings → Risi skills.", RisiSkillCards.neededText(r.copy(wasOn = false), ""))
        assertEquals("Calendar permission is off on this phone.", RisiSkillCards.neededText(r.copy(skillId = "calendar", reason = "no_permission"), ""))
        assertEquals("Email is coming later.", RisiSkillCards.neededText(r.copy(skillId = "email", reason = "unavailable"), ""))
        assertEquals("body", RisiSkillCards.neededText(r.copy(skillId = "lia"), "body"))
    }

    @Test fun answerStepsAndNextSteps() {
        val r = risi("envelope_risi_answer_v2.json")
        assertEquals(listOf("✓ Checked your calendar", "✓ Searched your chats"), RisiSkillCards.stepLines(r))
        assertEquals(listOf("Ask me to add it to your calendar"), RisiSkillCards.nextSteps(r))
        assertEquals(3, RisiSkillCards.nextSteps(r.copy(nextSteps = listOf("a", " ", "b", "c", "d"))).size)
    }

    @Test fun unknownConfirmToolHasNoButtons() {
        val r = risi("envelope_risi_confirm_set_alarm.json")
        val me = r.forUsers.single()
        assertEquals(listOf("add", "cancel"), RisiToolCards.confirmButtons(me, r, RisiToolCards.ConfirmState.OPEN, emptySet()))
        assertEquals(emptyList<String>(), RisiToolCards.confirmButtons(me, r.copy(tool = "teleport"), RisiToolCards.ConfirmState.OPEN, emptySet()))
    }

    @Test fun calendarAcceptAndDeclineEncodeLikeTheExamples() {
        val target = "0f1e2d3c-4b5a-4968-8776-5a4b3c2d1e0f"
        assertEquals(ProtocolJson.parseToJsonElement(read("envelope_risi_action_calendar_accept.json")), RisiControl.action(target, "calendar_accept", reminder = true))
        assertEquals(ProtocolJson.parseToJsonElement(read("envelope_risi_action_calendar_decline.json")), RisiControl.action(target, "calendar_decline"))
        assertTrue(RisiControl.valid(RisiControl.action(target, "calendar_accept", reminder = false)))
        assertEquals("You added it to their calendar", RisiControl.line(String(RisiControl.encode(RisiControl.action(target, "calendar_accept"))), "You"))
        // Older actions stay exactly as before (no `options`).
        val confirm = RisiControl.action(target, "confirm_write")
        assertFalse("options" in confirm)
        ProtocolJson.decodeFromJsonElement(RisiActionEnvelope.serializer(), confirm)
    }

    private fun answerRow(requestId: String, localTs: Long) = MessageEntity(
        clientMsgId = "a$localTs", messageId = "m$localTs", conversationId = "grp:x", from = "risi", to = "grp:x", body = "",
        serverTs = null, localTs = localTs, status = "READ", outgoing = false,
        systemJson = """{"v":1,"kind":"answer","request_id":"$requestId","answer":"ok"}""",
    )

    @Test fun progressEndsWhenTheTurnsMessageArrives() {
        val p = RisiTools_progress("r1", "grp:x")
        val shown = RisiProgressStore.Shown(p, 1_000L)
        val other = RisiProgressStore.Shown(RisiTools_progress("r2", "grp:other"), 1_000L)
        assertEquals(listOf(shown), RisiProgressStore.visible(listOf(shown, other), emptyList(), "grp:x", 2_000L))
        assertTrue(RisiProgressStore.visible(listOf(shown), listOf(answerRow("r1", 1_500L)), "grp:x", 2_000L).isEmpty())
        // A newer signal after the message (e.g. after a confirm) shows again.
        assertEquals(1, RisiProgressStore.visible(listOf(shown.copy(atMs = 1_600L)), listOf(answerRow("r1", 1_500L)), "grp:x", 2_000L).size)
        // A bubble whose `done` was lost goes away.
        assertTrue(RisiProgressStore.visible(listOf(shown), emptyList(), "grp:x", 1_000L + RisiProgressStore.STALE_MS).isEmpty())
    }

    @Suppress("FunctionName")
    private fun RisiTools_progress(rid: String, conv: String) = RisiProgress(rid, conv, RisiProgress.WORKING, seq = 1)
}
