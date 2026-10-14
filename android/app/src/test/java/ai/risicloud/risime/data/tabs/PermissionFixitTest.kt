package ai.risicloud.risime.data.tabs

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.net.ClientPermission
import ai.risicloud.risime.net.ProtocolJson
import ai.risicloud.risime.net.RisiFixit143
import ai.risicloud.risime.net.RisiMeta
import ai.risicloud.risime.net.RisiSkillChange
import ai.risicloud.risime.net.RisiSkillStates
import ai.risicloud.risime.net.RisiTextEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Proposal 2026-10-14-permission-fixit §43.6 (Android, JVM): the card decodes; the button picks the prompt or the
 * settings page; granted sends the PATCH and then `permission_granted`; declined sends `permission_declined`. One
 * case per permission.
 */
class PermissionFixitTest {
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val resume = "3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f"

    // §43.7 examples (copies until root adds them to contract/v1/examples).
    private val cardJson = """{"v":1,"type":"text","body":"I need your calendar to do that. Update RisiMe to allow it here, or turn it on in Settings → Risi skills.","risi":{"v":1,"kind":"needs_permission","request_id":"6d1f2e3a-4b5c-4d6e-9f7a-8b9c0d1e2f3a","resume_id":"$resume","permission":"calendar","skill_id":"calendar","turn_on":true,"text":"I need your calendar to do that.","buttons":["allow"],"expires_at":"2026-10-15T05:30:00.000Z","turn_ref":"8a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d","call_ref":null,"made_by":{"model":null,"provider":"rule","at":"2026-10-14T05:30:00.000Z","also":[]},"notify":["$me"]}}"""
    private val grantedJson = """{"v":1,"type":"risi_action","action":"permission_granted","target":"$resume","edit":null}"""

    private fun card(permission: String = "calendar", skill: String? = "calendar", turnOn: Boolean = true): RisiMeta =
        ProtocolJson.decodeFromString(RisiTextEnvelope.serializer(), cardJson).risi!!.copy(permission = permission, skillId = skill, turnOn = turnOn)

    private val now = Instant.parse("2026-10-14T06:00:00Z").toEpochMilli()

    /** The fake phone: the PATCHes and the actions, in order. */
    private val log = mutableListOf<String>()

    private suspend fun answer(r: RisiMeta, granted: Boolean, state: String = RisiSkillStates.OFF) = PermissionFixit.answer(
        r, granted, localState = { state },
        patch = { c -> log += "patch:${c.id}:${c.state}:${c.clientPermission}"; true },
        send = { t, a -> log += "$a:$t" },
    )

    @Test fun theCardAndTheAnswerDecode() {
        val r = card()
        assertTrue(PermissionFixit.isCard(r))
        assertEquals(resume, r.resumeId)
        assertEquals("calendar", r.permission)
        assertEquals(true, r.turnOn)
        assertEquals("I need your calendar to do that.", r.text)
        assertEquals("Allow calendar", PermissionFixit.buttonLabel(r.permission))
        val a = ProtocolJson.parseToJsonElement(grantedJson) as JsonObject
        assertEquals(RisiFixit143.ACTION_GRANTED, RisiControl.actionOf(String(RisiControl.encode(a))))
        assertTrue(RisiFixit143.ACTION_GRANTED in RisiControl.ACTIONS && RisiFixit143.ACTION_DECLINED in RisiControl.ACTIONS)
        // The control the phone builds is the example's shape.
        val built = String(RisiControl.encode(RisiControl.action(resume, RisiFixit143.ACTION_GRANTED)))
        assertEquals(RisiFixit143.ACTION_GRANTED, RisiControl.actionOf(built))
        assertEquals(resume, RisiControl.targetOf(built))
    }

    @Test fun calendarDeniedThenGrantedPatchesFirstThenGranted() = runBlocking {
        val need = PermissionFixit.need("calendar")!!
        assertEquals(listOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"), need.android)
        assertEquals(PermissionFixit.Step.PROMPT, PermissionFixit.step(need, granted = false, askedBefore = false, rationale = false, sdk = 34))
        assertEquals(RisiFixit143.ACTION_GRANTED, answer(card(), granted = true))
        assertEquals(listOf("patch:calendar:ask:${ClientPermission.GRANTED}", "permission_granted:$resume"), log)
        assertEquals("✓ Risi can see your calendar", PermissionFixit.stateLine(card(), RisiFixit143.ACTION_GRANTED))
    }

    @Test fun aSkillAlreadyOnIsNotTurnedOnAgain() = runBlocking {
        answer(card(), granted = true, state = RisiSkillStates.ALLOWED)
        assertEquals("patch:calendar:null:granted", log.first())
    }

    @Test fun calendarDeclinedSendsDeclinedAndNoPatch() = runBlocking {
        assertEquals(RisiFixit143.ACTION_DECLINED, answer(card(), granted = false))
        assertEquals(listOf("permission_declined:$resume"), log)
        assertEquals("Not allowed", PermissionFixit.stateLine(card(), RisiFixit143.ACTION_DECLINED))
    }

    @Test fun aBlockedPromptOpensTheSettingsPageWithTheHint() {
        val need = PermissionFixit.need("calendar")!!
        // Asked before, and Android gives no rationale: it won't show the prompt again.
        assertEquals(PermissionFixit.Step.SETTINGS, PermissionFixit.step(need, granted = false, askedBefore = true, rationale = false, sdk = 34))
        // Asked once and refused (rationale true): the prompt again.
        assertEquals(PermissionFixit.Step.PROMPT, PermissionFixit.step(need, granted = false, askedBefore = true, rationale = true, sdk = 34))
        // Already granted: no prompt at all.
        assertEquals(PermissionFixit.Step.ALREADY, PermissionFixit.step(need, granted = true, askedBefore = true, rationale = false, sdk = 34))
        assertEquals("Tap Calendar → Allow", PermissionFixit.settingsHint("calendar"))
    }

    @Test fun contactsMicrophoneLocation() = runBlocking {
        for ((p, android) in listOf(
            "contacts" to "android.permission.READ_CONTACTS",
            "microphone" to "android.permission.RECORD_AUDIO",
            "location" to "android.permission.ACCESS_COARSE_LOCATION",
        )) {
            log.clear()
            val need = PermissionFixit.need(p)!!
            assertEquals(listOf(android), need.android)
            assertEquals("Allow $p", PermissionFixit.buttonLabel(p))
            assertEquals(PermissionFixit.Step.PROMPT, PermissionFixit.step(need, false, false, false, 34))
            assertEquals(PermissionFixit.Step.SETTINGS, PermissionFixit.step(need, false, true, false, 34))
            // No skill of its own: no PATCH, just the answer.
            answer(card(p, skill = null), granted = true)
            assertEquals(listOf("permission_granted:$resume"), log)
            log.clear()
            answer(card(p, skill = null), granted = false)
            assertEquals(listOf("permission_declined:$resume"), log)
        }
    }

    @Test fun notificationsPromptOnlyFromAndroid13() = runBlocking {
        val need = PermissionFixit.need("notifications")!!
        assertEquals(PermissionFixit.Step.PROMPT, PermissionFixit.step(need, false, false, false, 33))
        assertEquals(PermissionFixit.Step.ALREADY, PermissionFixit.step(need, false, false, false, 32))
        answer(card("notifications", skill = "reminders"), granted = true)
        assertEquals(listOf("patch:reminders:ask:granted", "permission_granted:$resume"), log)
        assertEquals("Allow notifications", PermissionFixit.buttonLabel("notifications"))
    }

    @Test fun alarmsHaveNoPromptTheTapOnlyTurnsTheSkillOn() = runBlocking {
        val need = PermissionFixit.need("alarms")!!
        assertTrue(need.android.isEmpty())
        assertEquals(PermissionFixit.Step.ALREADY, PermissionFixit.step(need, false, false, false, 34))
        answer(card("alarms", skill = "alarm"), granted = true)
        assertEquals(listOf("patch:alarm:ask:granted", "permission_granted:$resume"), log)
        assertEquals("Allow alarms", PermissionFixit.buttonLabel("alarms"))
    }

    @Test fun anUnknownPermissionOpensRisiSkills() {
        assertNull(PermissionFixit.need("teleport"))
        assertEquals("Open Risi skills", PermissionFixit.buttonLabel("teleport"))
    }

    @Test fun theCardIsAnsweredOnceAndOnlyByMe() {
        val r = card()
        val rows = mutableListOf<MessageEntity>()
        assertNull(PermissionFixit.answered(r, rows, me))
        assertTrue(PermissionFixit.canAct(r, me, now, null))
        assertFalse(PermissionFixit.canAct(r, "someone-else", now, null))
        assertFalse(PermissionFixit.canAct(r, me, Instant.parse("2026-10-16T00:00:00Z").toEpochMilli(), null))
        rows += MessageEntity("o", "m0", "grp:x", "someone-else", "grp:x", "", "2026-10-14T05:31:00.000Z", 0, "READ", false,
            kind = MessageEntity.KIND_RISI_CTL, systemJson = String(RisiControl.encode(RisiControl.action(resume, RisiFixit143.ACTION_GRANTED))))
        assertNull(PermissionFixit.answered(r, rows, me)) // not mine
        rows += MessageEntity("a", "m1", "grp:x", me, "grp:x", "", "2026-10-14T05:31:00.000Z", 1, "READ", true,
            kind = MessageEntity.KIND_RISI_CTL, systemJson = String(RisiControl.encode(RisiControl.action(resume, RisiFixit143.ACTION_DECLINED))))
        rows += MessageEntity("b", "m2", "grp:x", me, "grp:x", "", "2026-10-14T05:32:00.000Z", 2, "READ", true,
            kind = MessageEntity.KIND_RISI_CTL, systemJson = String(RisiControl.encode(RisiControl.action(resume, RisiFixit143.ACTION_GRANTED))))
        assertEquals(RisiFixit143.ACTION_DECLINED, PermissionFixit.answered(r, rows, me)) // the first one decides
        assertFalse(PermissionFixit.canAct(r, me, now, RisiFixit143.ACTION_DECLINED))
    }

    @Test fun thePatchChange() {
        assertEquals(RisiSkillChange("calendar", RisiSkillStates.ASK, ClientPermission.GRANTED), PermissionFixit.patchChange("calendar", true, RisiSkillStates.OFF))
        assertEquals(RisiSkillChange("calendar", null, ClientPermission.GRANTED), PermissionFixit.patchChange("calendar", false, RisiSkillStates.OFF))
        assertNull(PermissionFixit.patchChange(null, true, RisiSkillStates.OFF))
        assertEquals("Risi can't see your calendar yet.", PermissionFixit.notGrantedLine("calendar"))
    }
}
