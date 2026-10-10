package lk.codegen.risime.ui.tabs

import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.net.RisiNextAction
import lk.codegen.risime.net.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.32 §25.4 chips: every `next_actions` type, the old `next_steps` (instructions are deep links or nothing, never text). */
class RisiChipsTest {
    private val me = "ff03ab6f-6457-46b1-a53b-9efd937920df"

    private class Host(override val me: String) : RisiHost {
        val calls = mutableListOf<String>()
        override fun ask(text: String) { calls += "ask:$text" }
        override fun summarise() {}
        override fun report() {}
        override fun act(target: String, action: String, editText: String?, editDue: String?) {}
        override fun feedback(callRef: String, rating: String, reason: String?) {}
        override fun openSkills(skillId: String?) { calls += "skills:$skillId" }
        override fun openNotificationSettings() { calls += "notifications" }
        override val calendar: lk.codegen.risime.data.tabs.RisiCalendarPort = object : lk.codegen.risime.data.tabs.RisiCalendarPort {
            override val records = kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, lk.codegen.risime.data.tabs.CalendarAddRecord>())
            override val chosenId = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
            override fun hasPermission() = true
            override suspend fun options() = emptyList<lk.codegen.risime.data.tabs.PhoneCalendarInfo>()
            override suspend fun chosen(): lk.codegen.risime.data.tabs.PhoneCalendarInfo? = null
            override suspend fun choose(id: Long) {}
            override fun open(eventId: Long) { calls += "event:$eventId" }
        }
    }

    private fun meta(actions: List<RisiNextAction>? = null, steps: List<String> = emptyList()) = RisiMeta(kind = "answer", nextActions = actions, nextSteps = steps)

    private fun run(c: RisiChips.Chip, h: Host) {
        RisiChips.run(c, h, send = { RisiChips.send(h, it) }, askPermission = { h.calls += "permission" })
    }

    @Test fun everyNextActionType() {
        val h = Host(me)
        val actions = listOf(
            RisiNextAction("Check Monday", RisiNextAction.ASK, text = "Check if I'm free on Monday"),
            RisiNextAction("Calendar settings", RisiNextAction.OPEN, target = RisiNextAction.SETTINGS_CALENDAR),
            RisiNextAction("Risi skills", RisiNextAction.OPEN, target = RisiNextAction.SETTINGS_RISI_SKILLS),
        )
        val chips = RisiChips.chips(meta(actions, steps = listOf("ignored when next_actions is present")), me, readOnly = false, canSend = true)
        assertEquals(listOf("Check Monday", "Calendar settings", "Risi skills"), chips.map { it.label })
        chips.forEach { run(it, h) }
        assertEquals(listOf("ask:Check if I'm free on Monday", "skills:calendar", "skills:null"), h.calls)
        val more = listOf(
            RisiNextAction("Notifications", RisiNextAction.OPEN, target = RisiNextAction.SETTINGS_NOTIFICATIONS),
            RisiNextAction("Allow calendar", RisiNextAction.OPEN, target = RisiNextAction.SETTINGS_CALENDAR_PERMISSION),
            RisiNextAction("Open event", RisiNextAction.OPEN, target = RisiNextAction.CALENDAR_EVENT, eventId = "4711"),
        )
        h.calls.clear()
        RisiChips.chips(meta(more), me, false, true).forEach { run(it, h) }
        assertEquals(listOf("notifications", "permission", "event:4711"), h.calls)
    }

    @Test fun unknownActionsAndTargetsAreDropped() {
        val list = listOf(
            RisiNextAction("Teleport", "teleport"),
            RisiNextAction("Somewhere", RisiNextAction.OPEN, target = "settings.wifi"),
            RisiNextAction("Event without id", RisiNextAction.OPEN, target = RisiNextAction.CALENDAR_EVENT),
            RisiNextAction("Empty ask", RisiNextAction.ASK, text = " "),
            RisiNextAction("", RisiNextAction.OPEN, target = RisiNextAction.SETTINGS_CALENDAR),
        )
        assertTrue(RisiChips.chips(meta(list), me, false, true).isEmpty())
        // An empty `next_actions` means none, even with old steps.
        assertTrue(RisiChips.chips(meta(emptyList(), listOf("Check Monday")), me, false, true).isEmpty())
    }

    @Test fun oldNextStepsInstructionsAreDeepLinksNeverText() {
        val h = Host(me)
        val chips = RisiChips.chips(meta(steps = listOf("Connect your calendar in Settings", "Check if you are free on Monday", "What is next?")), me, false, true)
        assertEquals(listOf("Connect your calendar in Settings", "Check if you are free on Monday"), chips.map { it.label })
        assertEquals(RisiChips.Action.Open(RisiNextAction.SETTINGS_CALENDAR), chips[0].action)
        chips.forEach { run(it, h) }
        // The 14:17 chip opens Settings → Risi skills → Calendar; it is never sent as text.
        assertEquals(listOf("skills:calendar", "ask:Check if you are free on Monday"), h.calls)
        assertEquals(RisiNextAction.SETTINGS_NOTIFICATIONS, RisiChips.deepLinkFor("Turn on notifications"))
        assertEquals(RisiNextAction.SETTINGS_CALENDAR_PERMISSION, RisiChips.deepLinkFor("Allow calendar access"))
        assertEquals(RisiNextAction.SETTINGS_RISI_SKILLS, RisiChips.deepLinkFor("Enable the Reminders skill"))
        assertEquals(RisiNextAction.SETTINGS_CALENDAR, RisiChips.deepLinkFor("Open your calendar"))
        // An instruction with no known place is not shown at all.
        assertTrue(RisiChips.chips(meta(steps = listOf("Open the pod bay doors", "Enable dark mode")), me, false, true).isEmpty())
        for (w in listOf("Settings", "Connect", "Turn on", "Allow", "Enable", "Open")) assertTrue(w, RisiChips.isInstruction("$w something"))
    }

    @Test fun notShownReadOnlyWithoutSendOrOnSomeoneElsesAnswer() {
        val m = meta(listOf(RisiNextAction("Check", RisiNextAction.ASK, text = "Check")))
        assertTrue(RisiChips.chips(m, me, readOnly = true, canSend = true).isEmpty())
        assertTrue(RisiChips.chips(m, me, readOnly = false, canSend = false).isEmpty())
        assertTrue(RisiChips.chips(m.copy(forUsers = listOf("someone-else")), me, false, true).isEmpty())
    }

    @Test fun nextActionsDecode() {
        val r = ProtocolJson.decodeFromString(
            RisiMeta.serializer(),
            """{"kind":"answer","next_actions":[{"label":"Open Calendar settings","action":"open","target":"settings.calendar"},{"label":"Retry","action":"ask","text":"Check my calendar this week"}],"added_event":{"event_id":"4711"}}""",
        )
        assertEquals(2, r.nextActions!!.size)
        assertEquals("4711", r.addedEvent!!.eventId)
        assertEquals(listOf("Open Calendar settings", "Retry"), RisiChips.chips(r, me, false, true).map { it.label })
    }
}
