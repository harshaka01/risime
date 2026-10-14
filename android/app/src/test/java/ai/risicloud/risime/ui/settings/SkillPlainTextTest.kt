package ai.risicloud.risime.ui.settings

import ai.risicloud.risime.net.ClientPermission
import ai.risicloud.risime.net.ProtocolJson
import ai.risicloud.risime.net.RisiSkillStates
import ai.risicloud.risime.net.RisiSkillsReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Proposal 2026-10-14-send-for-me §42 item 4: Settings → Risi skills in plain words. */
class SkillPlainTextTest {
    private val skills = ProtocolJson.decodeFromString<RisiSkillsReply>(javaClass.classLoader!!.getResource("contract/v1/examples/risi_skills_reply.json")!!.readText()).skills

    @Test fun noRawPermissionNamesAndNoAskMeEachTime() {
        val perms = listOf(ClientPermission.GRANTED, ClientPermission.DENIED, ClientPermission.NOT_ASKED, ClientPermission.NOT_NEEDED, ClientPermission.UNSUPPORTED, ClientPermission.UNKNOWN)
        for (s in skills) {
            for (st in listOf(RisiSkillStates.OFF, RisiSkillStates.ASK, RisiSkillStates.ALLOWED)) {
                assertFalse(SkillPlainText.leaksRawNames(SkillPlainText.stateLine(s.copy(state = st))))
            }
            assertFalse(s.id, SkillPlainText.leaksRawNames(SkillPlainText.sentence(s)))
            s.permissions.forEach { assertFalse(SkillPlainText.leaksRawNames(SkillPlainText.permissionLabel(it.label))) }
            perms.forEach { assertFalse(SkillPlainText.leaksRawNames(SkillPlainText.permissionLine(it))) }
        }
        listOf(ASK_EACH_TIME, ALLOWED, SkillPlainText.SHOW_ME_FIRST_HELP, SkillPlainText.WITHOUT_ASKING_HELP, SkillPlainText.ALWAYS_SHOWS, SEND_FOR_ME_SENTENCE, SEND_FOR_ME_NONE)
            .forEach { assertFalse(it, SkillPlainText.leaksRawNames(it)) }
    }

    @Test fun theScheduledMessagesRowReadsAsHarshaAsked() {
        val s = skills.first { it.id == "scheduled_messages" }.copy(state = RisiSkillStates.ASK)
        assertEquals("On", SkillPlainText.stateLine(s))
        assertEquals(
            "Risi can send a message you approved at the time you choose. Your phone sends it, encrypted. Risi always shows you the message first.",
            SkillPlainText.sentence(s),
        )
        assertEquals("Allowed on this phone", SkillPlainText.permissionLine(ClientPermission.GRANTED))
        assertEquals("Not allowed on this phone · Open settings", SkillPlainText.permissionLine(ClientPermission.DENIED))
        assertEquals("Off", SkillPlainText.stateLine(s.copy(state = RisiSkillStates.OFF)))
        assertEquals("On · Do it without asking", SkillPlainText.stateLine(s.copy(state = RisiSkillStates.ALLOWED)))
    }
}
