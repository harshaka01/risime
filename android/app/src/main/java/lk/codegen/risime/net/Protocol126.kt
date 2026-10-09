package lk.codegen.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Contract v1.26 (§26): Risi skills. Wire models; every model tolerates unknown fields and
 * defaults what a v1.25 server omits.
 */

/** Capability a v1.26 client advertises (only with `risi_tools`) while `/auth/config` says `risi_skills: on`. */
const val CAPABILITY_RISI_SKILLS = "risi_skills"

/** §26.1 the registry's skill ids. */
object RisiSkillIds {
    const val ALARM = "alarm"
    const val REMINDERS = "reminders"
    const val CALENDAR = "calendar"
    const val SCHEDULED_MESSAGES = "scheduled_messages"
    const val EMAIL = "email"
}

/** §26.2 skill states. */
object RisiSkillStates {
    const val OFF = "off"
    const val ASK = "ask"
    const val ALLOWED = "allowed"
}

/** §26.1 `ClientPermission`. */
object ClientPermission {
    const val GRANTED = "granted"
    const val DENIED = "denied"
    const val NOT_ASKED = "not_asked"
    const val NOT_NEEDED = "not_needed"
    const val UNSUPPORTED = "unsupported"
    const val UNKNOWN = "unknown"
}

@Serializable
data class RisiSkillPermission(val scope: String, val name: String, val label: String = "", val runtime: Boolean = false)

@Serializable
data class RisiSkillClient(
    @SerialName("device_id") val deviceId: String? = null,
    val permission: String = ClientPermission.UNKNOWN,
    @SerialName("reported_at") val reportedAt: String? = null,
)

/** §26.1 `Skill`. */
@Serializable
data class RisiSkill(
    val id: String,
    val kind: String = "builtin",
    val title: String = "",
    val description: String = "",
    val can: List<String> = emptyList(),
    val cannot: List<String> = emptyList(),
    val permissions: List<RisiSkillPermission> = emptyList(),
    val tools: List<String> = emptyList(),
    val where: String = "server",
    val modes: List<String> = listOf(RisiSkillStates.ASK),
    val undo: String = "none",
    val available: Boolean = true,
    val state: String = RisiSkillStates.OFF,
    @SerialName("state_changed_at") val stateChangedAt: String? = null,
    val client: RisiSkillClient? = null,
) {
    val on: Boolean get() = state == RisiSkillStates.ASK || state == RisiSkillStates.ALLOWED
    val allowsAllowed: Boolean get() = RisiSkillStates.ALLOWED in modes
}

@Serializable
data class RisiSkillsReply(val skills: List<RisiSkill>)

@Serializable
data class RisiSkillChange(
    val id: String,
    /** Optional on the wire: omitted (never `null`) when absent (§26.2). */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val state: String? = null,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("client_permission") val clientPermission: String? = null,
    /** Proposal 2026-10-09-risi-action-loop §2: the picked calendar (`id: "calendar"` only); omitted when absent. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val calendar: RisiCalendarRef? = null,
)

/** Proposal 2026-10-09-risi-action-loop: a calendar as the server keeps it (`name` 1–100, `account` ≤ 200 or null). */
@Serializable
data class RisiCalendarRef(val name: String, val account: String? = null)

/** `PATCH /api/v1/risi/skills` body. */
@Serializable
data class RisiSkillsPatch(
    val changes: List<RisiSkillChange>,
    @SerialName("cancel_pending") val cancelPending: Boolean = false,
)

/** §26.4 an entry's (and a `skill_done`'s) undo. */
@Serializable
data class RisiUndo(
    val kind: String = UNDO_NONE,
    val state: String? = null,
    val until: String? = null,
    val hint: String? = null,
) {
    companion object {
        const val UNDO_SERVER = "server"
        const val UNDO_CLIENT = "client"
        const val UNDO_MANUAL = "manual"
        const val UNDO_NONE = "none"
        const val AVAILABLE = "available"
        const val PENDING = "pending"
        const val DONE = "done"
        const val FAILED = "failed"
        const val EXPIRED = "expired"
    }
}

/** §26.4 an activity entry. */
@Serializable
data class RisiActivityEntry(
    @SerialName("entry_id") val entryId: String,
    @SerialName("skill_id") val skillId: String,
    val action: String,
    val summary: String = "",
    val at: String,
    val via: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("target_conversation_id") val targetConversationId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    val undo: RisiUndo = RisiUndo(),
    @SerialName("undo_token") val undoToken: String? = null,
)

@Serializable
data class RisiActivityReply(val entries: List<RisiActivityEntry>, @SerialName("has_more") val hasMore: Boolean = false)

@Serializable
data class RisiUndoRequest(@SerialName("undo_token") val undoToken: String)

@Serializable
data class RisiUndoReply(val entry: RisiActivityEntry)

/** §26.6 `set_alarm` args. */
@Serializable
data class SetAlarmArgs(
    @SerialName("write_id") val writeId: String,
    val time: String,
    val label: String = "",
    val days: List<Int>? = null,
)

@Serializable
data class SetAlarmResult(@SerialName("alarm_set") val alarmSet: Boolean)

/** §26.6 `schedule_message` args. */
@Serializable
data class ScheduleMessageArgs(
    @SerialName("write_id") val writeId: String,
    @SerialName("conversation_id") val conversationId: String,
    val text: String,
    val at: String,
    val repeat: String? = null,
)

@Serializable
data class ScheduleMessageResult(@SerialName("schedule_id") val scheduleId: String)

@Serializable
data class CancelScheduledArgs(@SerialName("write_id") val writeId: String? = null, @SerialName("schedule_id") val scheduleId: String)

@Serializable
data class CancelScheduledResult(val cancelled: Boolean, val reason: String? = null)

@Serializable
data class CalendarRemoveArgs(@SerialName("target_write_id") val targetWriteId: String)

@Serializable
data class CalendarRemoveResult(val removed: Boolean, val reason: String? = null)

/** §26.6/§26.9 tool error codes (in the result). */
object RisiSkillToolErrors {
    const val ALARM_UNAVAILABLE = "alarm_unavailable"
    const val TOO_MANY_SCHEDULED = "too_many_scheduled"
    const val NOT_MEMBER = "not_member"
}

/** §26.9 REST error codes. */
object RisiSkillsErrors {
    const val SKILL_UNAVAILABLE = "skill_unavailable"
    const val UNDO_UNAVAILABLE = "undo_unavailable"
}

/** §26.7 risi_action values. */
object RisiActions126 {
    const val CALENDAR_ACCEPT = "calendar_accept"
    const val CALENDAR_DECLINE = "calendar_decline"
}
