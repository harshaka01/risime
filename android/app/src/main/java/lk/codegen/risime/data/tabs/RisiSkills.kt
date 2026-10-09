package lk.codegen.risime.data.tabs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.ClientPermission
import lk.codegen.risime.net.RisiSkill
import lk.codegen.risime.net.RisiSkillChange
import lk.codegen.risime.net.RisiSkillStates
import lk.codegen.risime.net.RisiSkillsErrors
import lk.codegen.risime.net.RisiSkillsPatch
import lk.codegen.risime.net.RisiSkillsReply

/*
 * Contract v1.26 §26.1–§26.5: Settings → Risi skills. The registry and this user's state come from
 * the server (`GET`/`PATCH /risi/skills`); the phone keeps its own record of each skill's state from
 * the last answer (the allowed path of §26.3 trusts only that), asks for Android permissions only from
 * the screen when a skill is turned on, and reports what Android says.
 */

/** What turning a skill on came to. */
sealed interface SkillTurnOn {
    data object On : SkillTurnOn

    /** A required permission was refused: the switch stays off ([text] says why; the row offers "Open settings"). */
    data class Refused(val text: String) : SkillTurnOn

    data class Failed(val text: String) : SkillTurnOn
}

fun skillsErrorText(code: String?): String = when (code) {
    RisiSkillsErrors.SKILL_UNAVAILABLE -> "This skill isn't available yet."
    "invalid_device" -> "Update RisiMe to change Risi skills on this phone."
    "agent_unavailable" -> "Risi skills aren't available right now."
    "network" -> "No connection. Try again."
    else -> "Couldn't change it. Try again."
}

/** Android permission state as `ClientPermission`, and the prompt (only from the screen, §26.2). */
interface SkillPermissions {
    /** What Android says now for [skill] on this phone. */
    fun current(skill: RisiSkill): String

    /** Asks the user for every runtime permission [skill] needs (from the screen, right now); the state after. */
    suspend fun request(skill: RisiSkill): String
}

class RisiSkillsStore(
    private val get: suspend () -> ApiResult<RisiSkillsReply>,
    private val patch: suspend (RisiSkillsPatch) -> ApiResult<RisiSkillsReply>,
    private val loadStates: () -> Map<String, String> = { emptyMap() },
    private val saveStates: (Map<String, String>) -> Unit = {},
    /** "Also cancel N pending": this phone's own pending items of a skill (scheduled messages), cancelled locally. */
    private val cancelLocal: suspend (skillId: String) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val _skills = MutableStateFlow<List<RisiSkill>?>(null)

    /** In registry order; null until loaded. */
    val skills: StateFlow<List<RisiSkill>?> = _skills.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val lock = Mutex()

    @Volatile private var states: Map<String, String> = runCatching(loadStates).getOrDefault(emptyMap())

    /** §26.3: the phone's own record of [skillId]'s state from its last GET or PATCH (`off` when unknown). */
    fun localState(skillId: String): String = states[skillId] ?: RisiSkillStates.OFF

    fun skill(id: String): RisiSkill? = _skills.value?.firstOrNull { it.id == id }

    private fun record(list: List<RisiSkill>) {
        val next = states + list.associate { it.id to it.state }
        if (next != states) {
            states = next
            runCatching { saveStates(next) }
        }
    }

    private fun merge(changed: List<RisiSkill>) {
        val cur = _skills.value
        _skills.value = if (cur == null) changed else cur.map { s -> changed.firstOrNull { it.id == s.id } ?: s }
        record(changed)
    }

    /** `GET /risi/skills`, then report any permission Android changed since ([permissions]). */
    suspend fun refresh(permissions: SkillPermissions? = null): Boolean = lock.withLock {
        when (val r = get()) {
            is ApiResult.Ok -> {
                _skills.value = r.value.skills
                record(r.value.skills)
                _error.value = null
            }
            is ApiResult.Error -> { _error.value = skillsErrorText(r.code); return@withLock false }
            is ApiResult.NetworkError -> { _error.value = skillsErrorText("network"); return@withLock false }
        }
        if (permissions != null) reportLocked(permissions)
        true
    }

    /** §26.2: report the Android permission of every skill whose reported state differs (on start and on change). */
    suspend fun reportPermissions(permissions: SkillPermissions) = lock.withLock { reportLocked(permissions) }

    private suspend fun reportLocked(permissions: SkillPermissions) {
        val list = _skills.value ?: return
        val changes = list.filter { it.available && (it.where != "server" || it.permissions.any { p -> p.scope == "android" }) }
            .mapNotNull { s ->
                val now = permissions.current(s)
                if (s.client?.permission == now) null else RisiSkillChange(s.id, clientPermission = now)
            }.take(10)
        if (changes.isEmpty()) return
        when (val r = patch(RisiSkillsPatch(changes))) {
            is ApiResult.Ok -> merge(r.value.skills)
            else -> log("risi skills: permission report not sent")
        }
    }

    /**
     * §26.2 turning a skill on: the permission prompt runs first (from the screen), then the `PATCH`
     * with `state: "ask"` and the result. A refused permission keeps the switch off and is reported.
     */
    suspend fun turnOn(id: String, permissions: SkillPermissions): SkillTurnOn {
        val skill = skill(id) ?: return SkillTurnOn.Failed(skillsErrorText(null))
        if (!skill.available) return SkillTurnOn.Failed(skillsErrorText(RisiSkillsErrors.SKILL_UNAVAILABLE))
        val perm = permissions.request(skill)
        return lock.withLock {
            if (perm != ClientPermission.GRANTED && perm != ClientPermission.NOT_NEEDED) {
                patch(RisiSkillsPatch(listOf(RisiSkillChange(id, clientPermission = perm)))).let { if (it is ApiResult.Ok) merge(it.value.skills) }
                return@withLock SkillTurnOn.Refused(refusedText(skill, perm))
            }
            when (val r = patch(RisiSkillsPatch(listOf(RisiSkillChange(id, state = RisiSkillStates.ASK, clientPermission = perm))))) {
                is ApiResult.Ok -> { merge(r.value.skills); SkillTurnOn.On }
                is ApiResult.Error -> SkillTurnOn.Failed(skillsErrorText(r.code))
                is ApiResult.NetworkError -> SkillTurnOn.Failed(skillsErrorText("network"))
            }
        }
    }

    /**
     * Proposal 2026-10-09-risi-action-loop §2: tell the server which calendar the user picked (the next
     * card's `confirm.calendar` hint). Best effort: an older server's 422 is only logged.
     */
    suspend fun reportCalendar(calendar: lk.codegen.risime.net.RisiCalendarRef?): Boolean = lock.withLock {
        when (patch(RisiSkillsPatch(listOf(RisiSkillChange(lk.codegen.risime.net.RisiSkillIds.CALENDAR, calendar = calendar))))) {
            is ApiResult.Ok -> true
            else -> { log("risi skills: calendar choice not reported"); false }
        }
    }

    /** "Ask me each time" / "Allowed" (only where the skill's `modes` allow it). */
    suspend fun setMode(id: String, state: String): String? = lock.withLock {
        val skill = skill(id) ?: return@withLock skillsErrorText(null)
        if (state !in skill.modes || !skill.on) return@withLock skillsErrorText("bad_request")
        when (val r = patch(RisiSkillsPatch(listOf(RisiSkillChange(id, state = state))))) {
            is ApiResult.Ok -> { merge(r.value.skills); null }
            is ApiResult.Error -> skillsErrorText(r.code)
            is ApiResult.NetworkError -> skillsErrorText("network")
        }
    }

    /** §26.5 revoke: `state: "off"`; with [cancelPending] the server cancels its items and the phone its own. */
    suspend fun revoke(id: String, cancelPending: Boolean): String? = lock.withLock {
        when (val r = patch(RisiSkillsPatch(listOf(RisiSkillChange(id, state = RisiSkillStates.OFF)), cancelPending = cancelPending))) {
            is ApiResult.Ok -> {
                merge(r.value.skills)
                if (cancelPending) runCatching { cancelLocal(id) }
                null
            }
            is ApiResult.Error -> skillsErrorText(r.code)
            is ApiResult.NetworkError -> skillsErrorText("network")
        }
    }

    companion object {
        fun encodeStates(m: Map<String, String>): String =
            lk.codegen.risime.net.ProtocolJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(m.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))

        fun decodeStates(s: String?): Map<String, String> = runCatching {
            (lk.codegen.risime.net.ProtocolJson.parseToJsonElement(s ?: "{}") as kotlinx.serialization.json.JsonObject)
                .mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content }
        }.getOrDefault(emptyMap())

        fun refusedText(skill: RisiSkill, perm: String): String = when (perm) {
            ClientPermission.UNSUPPORTED -> "This phone can't do it (no app handles it)."
            else -> {
                val what = skill.permissions.filter { it.scope == "android" && it.runtime }.joinToString(" and ") { it.label.ifBlank { it.name.substringAfterLast('.') } }
                "Permission needed: ${what.ifBlank { "Android permission" }}. ${skill.title} stays off."
            }
        }
    }
}
