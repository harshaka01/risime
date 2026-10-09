package lk.codegen.risime.data.tabs

import kotlinx.coroutines.runBlocking
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.ClientPermission
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiSkill
import lk.codegen.risime.net.RisiSkillChange
import lk.codegen.risime.net.RisiSkillClient
import lk.codegen.risime.net.RisiSkillStates
import lk.codegen.risime.net.RisiSkillsPatch
import lk.codegen.risime.net.RisiSkillsReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §26.2/§26.5 A13: the turn-on flow (a refused permission keeps the switch off), modes, revoke, reports, the local record. */
class RisiSkillsStoreTest {
    private fun read(name: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()

    /** A fake server holding the registry; PATCH applies the changes like §26.2 (all or nothing). */
    private class Server(var skills: List<RisiSkill>) {
        val patches = mutableListOf<RisiSkillsPatch>()

        fun get(): ApiResult<RisiSkillsReply> = ApiResult.Ok(RisiSkillsReply(skills))

        fun patch(p: RisiSkillsPatch): ApiResult<RisiSkillsReply> {
            patches += p
            for (c in p.changes) {
                val s = skills.first { it.id == c.id }
                if (c.state != null && c.state != RisiSkillStates.OFF && !s.available) return ApiResult.Error(409, "skill_unavailable", "")
                if (c.state == RisiSkillStates.ALLOWED && RisiSkillStates.ALLOWED !in s.modes) return ApiResult.Error(422, "bad_request", "")
            }
            val changed = p.changes.map { c ->
                val s = skills.first { it.id == c.id }
                s.copy(state = c.state ?: s.state, client = c.clientPermission?.let { RisiSkillClient("dev", it, null) } ?: s.client)
            }
            skills = skills.map { s -> changed.firstOrNull { it.id == s.id } ?: s }
            return ApiResult.Ok(RisiSkillsReply(changed))
        }
    }

    private class Perms(var now: Map<String, String>, val afterPrompt: Map<String, String> = now) : SkillPermissions {
        val prompted = mutableListOf<String>()
        override fun current(skill: RisiSkill) = now[skill.id] ?: ClientPermission.NOT_NEEDED
        override suspend fun request(skill: RisiSkill): String {
            prompted += skill.id
            now = now + (skill.id to (afterPrompt[skill.id] ?: current(skill)))
            return current(skill)
        }
    }

    private fun registry() = ProtocolJson.decodeFromString<RisiSkillsReply>(read("risi_skills_reply.json")).skills
        .map { it.copy(state = RisiSkillStates.OFF, client = null) }

    private fun store(server: Server, saved: MutableMap<String, String> = mutableMapOf(), cancelled: MutableList<String> = mutableListOf()) = RisiSkillsStore(
        get = { server.get() }, patch = { server.patch(it) },
        loadStates = { saved.toMap() }, saveStates = { saved.clear(); saved.putAll(it) },
        cancelLocal = { cancelled += it },
    )

    @Test fun aRefusedPermissionKeepsTheSwitchOffAndIsReported() = runBlocking {
        val server = Server(registry())
        val s = store(server)
        s.refresh()
        val perms = Perms(mapOf("scheduled_messages" to ClientPermission.NOT_ASKED), afterPrompt = mapOf("scheduled_messages" to ClientPermission.DENIED))
        val r = s.turnOn("scheduled_messages", perms)
        assertTrue(r is SkillTurnOn.Refused)
        assertEquals(listOf("scheduled_messages"), perms.prompted)
        assertEquals(RisiSkillStates.OFF, s.skill("scheduled_messages")!!.state)
        assertEquals(listOf(RisiSkillChange("scheduled_messages", clientPermission = ClientPermission.DENIED)), server.patches.single().changes)
        assertEquals(RisiSkillStates.OFF, s.localState("scheduled_messages"))
    }

    @Test fun aGrantedPermissionTurnsItOnAsAskAndTheRecordIsKept() = runBlocking {
        val server = Server(registry())
        val saved = mutableMapOf<String, String>()
        val s = store(server, saved)
        s.refresh()
        val perms = Perms(mapOf("calendar" to ClientPermission.NOT_ASKED), afterPrompt = mapOf("calendar" to ClientPermission.GRANTED))
        assertEquals(SkillTurnOn.On, s.turnOn("calendar", perms))
        assertEquals(listOf(RisiSkillChange("calendar", RisiSkillStates.ASK, ClientPermission.GRANTED)), server.patches.single().changes)
        assertEquals(RisiSkillStates.ASK, s.localState("calendar"))
        assertEquals(RisiSkillStates.ASK, saved["calendar"])
        // A new process reads the phone's own record.
        assertEquals(RisiSkillStates.ASK, store(Server(registry()), saved).localState("calendar"))
    }

    @Test fun alarmNeedsNoPromptButAnUnsupportedPhoneStaysOff() = runBlocking {
        val server = Server(registry())
        val s = store(server)
        s.refresh()
        assertEquals(SkillTurnOn.On, s.turnOn("alarm", Perms(mapOf("alarm" to ClientPermission.NOT_NEEDED))))
        val s2 = store(Server(registry()))
        s2.refresh()
        assertTrue(s2.turnOn("alarm", Perms(mapOf("alarm" to ClientPermission.UNSUPPORTED))) is SkillTurnOn.Refused)
    }

    @Test fun anUnavailableSkillCannotBeTurnedOn() = runBlocking {
        val server = Server(registry())
        val s = store(server)
        s.refresh()
        assertTrue(s.turnOn("email", Perms(emptyMap())) is SkillTurnOn.Failed)
        assertTrue(server.patches.isEmpty())
    }

    @Test fun allowedOnlyWhereModesAllowIt() = runBlocking {
        val server = Server(registry())
        val s = store(server)
        s.refresh()
        s.turnOn("alarm", Perms(emptyMap()))
        assertNull(s.setMode("alarm", RisiSkillStates.ALLOWED))
        assertEquals(RisiSkillStates.ALLOWED, s.localState("alarm"))
        s.turnOn("scheduled_messages", Perms(mapOf("scheduled_messages" to ClientPermission.GRANTED)))
        assertNotNull(s.setMode("scheduled_messages", RisiSkillStates.ALLOWED)) // modes: ask only (never sent)
        assertEquals(RisiSkillStates.ASK, s.localState("scheduled_messages"))
    }

    @Test fun revokeSendsOffWithCancelPendingAndCancelsLocally() = runBlocking {
        val server = Server(registry())
        val cancelled = mutableListOf<String>()
        val s = store(server, cancelled = cancelled)
        s.refresh()
        s.turnOn("scheduled_messages", Perms(mapOf("scheduled_messages" to ClientPermission.GRANTED)))
        assertNull(s.revoke("scheduled_messages", cancelPending = true))
        assertEquals(RisiSkillsPatch(listOf(RisiSkillChange("scheduled_messages", RisiSkillStates.OFF)), cancelPending = true), server.patches.last())
        assertEquals(listOf("scheduled_messages"), cancelled)
        assertEquals(RisiSkillStates.OFF, s.localState("scheduled_messages"))
        s.revoke("alarm", cancelPending = false)
        assertEquals(listOf("scheduled_messages"), cancelled)
    }

    @Test fun permissionsAreReportedOnlyWhenTheyDiffer() = runBlocking {
        val server = Server(registry())
        val s = store(server)
        val perms = Perms(mapOf("alarm" to ClientPermission.NOT_NEEDED, "reminders" to ClientPermission.GRANTED, "calendar" to ClientPermission.NOT_ASKED, "scheduled_messages" to ClientPermission.DENIED))
        s.refresh(perms)
        val first = server.patches.single().changes
        assertEquals(setOf("alarm", "reminders", "calendar", "scheduled_messages"), first.map { it.id }.toSet()) // email: unavailable, server-side
        assertTrue(first.all { it.state == null })
        s.refresh(perms)
        assertEquals(1, server.patches.size) // nothing changed
        perms.now = perms.now + ("calendar" to ClientPermission.GRANTED)
        s.reportPermissions(perms)
        assertEquals(listOf(RisiSkillChange("calendar", clientPermission = ClientPermission.GRANTED)), server.patches.last().changes)
    }

    @Test fun statesRoundTrip() {
        val m = mapOf("alarm" to "allowed", "calendar" to "off")
        assertEquals(m, RisiSkillsStore.decodeStates(RisiSkillsStore.encodeStates(m)))
        assertEquals(emptyMap<String, String>(), RisiSkillsStore.decodeStates("garbage"))
    }
}
