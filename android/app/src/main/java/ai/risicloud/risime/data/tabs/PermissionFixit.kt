package ai.risicloud.risime.data.tabs

import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.net.ClientPermission
import ai.risicloud.risime.net.RisiFixit143
import ai.risicloud.risime.net.RisiMeta
import ai.risicloud.risime.net.RisiSkillChange
import ai.risicloud.risime.net.RisiSkillIds
import ai.risicloud.risime.net.RisiSkillStates

/*
 * Proposal 2026-10-14-permission-fixit (v1.49 §43): "I need your calendar to do that." [Allow calendar]. One
 * sentence, one button: the Android prompt from the screen (never from the background), or, when Android blocks
 * it, RisiMe's app-permissions page with the hint "Tap Calendar → Allow". Granted: the §26.2 PATCH first, then
 * `permission_granted`, and Risi finishes the original request itself. Refused: `permission_declined`.
 */
object PermissionFixit {
    /** One permission as the phone shows and asks for it. */
    data class Need(
        val id: String,
        /** The Android runtime permissions (empty: nothing to ask, e.g. alarms). */
        val android: List<String>,
        /** "calendar" → "Allow calendar". */
        val noun: String,
        /** The settings page's row name ("Tap Calendar → Allow"). */
        val settingsName: String,
        /** The skill it belongs to (the PATCH). */
        val skillId: String?,
        /** Only from this API level (notifications: 33). */
        val minSdk: Int = 0,
    )

    private val NEEDS = listOf(
        Need(RisiFixit143.CALENDAR, listOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"), "calendar", "Calendar", RisiSkillIds.CALENDAR),
        Need(RisiFixit143.CONTACTS, listOf("android.permission.READ_CONTACTS"), "contacts", "Contacts", null),
        Need(RisiFixit143.MICROPHONE, listOf("android.permission.RECORD_AUDIO"), "microphone", "Microphone", null),
        Need(RisiFixit143.NOTIFICATIONS, listOf("android.permission.POST_NOTIFICATIONS"), "notifications", "Notifications", RisiSkillIds.REMINDERS, minSdk = 33),
        Need(RisiFixit143.LOCATION, listOf("android.permission.ACCESS_COARSE_LOCATION"), "location", "Location", null),
        Need(RisiFixit143.ALARMS, emptyList(), "alarms", "Alarms & reminders", RisiSkillIds.ALARM),
    )

    fun need(permission: String?): Need? = NEEDS.firstOrNull { it.id == permission }

    /** The permission a skill's settings row offers (calendar, notifications for reminders; null: none to fix). */
    fun forSkill(skillId: String): Need? = when (skillId) {
        RisiSkillIds.CALENDAR -> need(RisiFixit143.CALENDAR)
        RisiSkillIds.REMINDERS -> need(RisiFixit143.NOTIFICATIONS)
        else -> null
    }

    /** "Allow calendar"; an unknown permission: "Open Risi skills". */
    fun buttonLabel(permission: String?): String = need(permission)?.let { "Allow ${it.noun}" } ?: OPEN_SKILLS

    const val OPEN_SKILLS = "Open Risi skills"
    const val NOT_ALLOWED = "Not allowed"

    /** "✓ Risi can see your calendar" (alarms / notifications: "can set …" / "can notify you"). */
    fun grantedLine(permission: String?): String = when (permission) {
        RisiFixit143.ALARMS -> "✓ Risi can set your alarms"
        RisiFixit143.NOTIFICATIONS -> "✓ Risi can notify you"
        RisiFixit143.MICROPHONE -> "✓ Risi can use your microphone"
        else -> "✓ Risi can see your ${need(permission)?.noun ?: "phone's data"}"
    }

    /** Settings, not granted: "Risi can't see your calendar yet." */
    fun notGrantedLine(permission: String?): String = when (permission) {
        RisiFixit143.ALARMS -> "Risi can't set your alarms yet."
        RisiFixit143.NOTIFICATIONS -> "Risi can't notify you yet."
        RisiFixit143.MICROPHONE -> "Risi can't use your microphone yet."
        else -> "Risi can't see your ${need(permission)?.noun ?: "phone's data"} yet."
    }

    /** The hint on the app-permissions page: "Tap Calendar → Allow". */
    fun settingsHint(permission: String?): String = "Tap ${need(permission)?.settingsName ?: "the permission"} → Allow"

    /** §43.5 "ask at first need": one line on why, next to the prompt. */
    fun whyLine(permission: String?, forWhat: String): String = "Risi needs your ${need(permission)?.noun ?: "permission"} $forWhat."

    /** What the button does now. */
    enum class Step {
        /** Already allowed (or nothing to ask): report and answer at once. */
        ALREADY,

        /** Show the Android prompt. */
        PROMPT,

        /** Android blocks the prompt ("don't ask again"): open the app-permissions page with the hint. */
        SETTINGS,
    }

    /**
     * §43.5: [granted] all of the permission now; [askedBefore] this phone has shown the prompt for it;
     * [rationale] Android's `shouldShowRequestPermissionRationale` for any of them. Asked before and no rationale
     * means Android won't show the prompt again: the settings page.
     */
    fun step(need: Need, granted: Boolean, askedBefore: Boolean, rationale: Boolean, sdk: Int): Step = when {
        need.android.isEmpty() || sdk < need.minSdk || granted -> Step.ALREADY
        askedBefore && !rationale -> Step.SETTINGS
        else -> Step.PROMPT
    }

    /** The §26.2 PATCH change after a grant: `client_permission: granted`, plus `state: ask` when the skill was off. */
    fun patchChange(skillId: String?, turnOn: Boolean, localState: String): RisiSkillChange? {
        skillId ?: return null
        val on = turnOn && localState == RisiSkillStates.OFF
        return RisiSkillChange(skillId, state = if (on) RisiSkillStates.ASK else null, clientPermission = ClientPermission.GRANTED)
    }

    /**
     * §43.3/§43.5 the answer to a card: granted → the PATCH first (the server then sees the permission), then
     * `permission_granted`; refused → `permission_declined`. [send] sends a `risi_action` with target `resume_id`.
     * Returns the action sent (null: the card has no `resume_id`).
     */
    suspend fun answer(
        card: RisiMeta,
        granted: Boolean,
        localState: (String) -> String,
        patch: suspend (RisiSkillChange) -> Boolean,
        send: suspend (target: String, action: String) -> Unit,
    ): String? {
        val target = card.resumeId ?: return null
        if (!granted) {
            send(target, RisiFixit143.ACTION_DECLINED)
            return RisiFixit143.ACTION_DECLINED
        }
        val skill = card.skillId ?: need(card.permission)?.skillId
        patchChange(skill, card.turnOn == true, skill?.let(localState) ?: RisiSkillStates.OFF)?.let { patch(it) }
        send(target, RisiFixit143.ACTION_GRANTED)
        return RisiFixit143.ACTION_GRANTED
    }

    /** The card's state from MY answers in this chat (the first one decides): granted / declined / null (open). */
    fun answered(card: RisiMeta, rows: List<MessageEntity>, me: String): String? {
        val target = card.resumeId ?: return null
        return rows.firstNotNullOfOrNull { m ->
            if (m.kind != MessageEntity.KIND_RISI_CTL || !m.from.equals(me, true)) return@firstNotNullOfOrNull null
            if (RisiControl.targetOf(m.systemJson)?.equals(target, true) != true) return@firstNotNullOfOrNull null
            RisiControl.actionOf(m.systemJson)?.takeIf { it in RisiFixit143.ACTIONS }
        }
    }

    fun isCard(r: RisiMeta): Boolean = r.kind == RisiFixit143.KIND_NEEDS_PERMISSION

    /** The tap may be offered: for me, not expired, not answered, Allow in the buttons. */
    fun canAct(r: RisiMeta, me: String, nowMs: Long, answered: String?): Boolean {
        if (answered != null || r.resumeId == null) return false
        if (r.buttons.isNotEmpty() && RisiFixit143.BUTTON_ALLOW !in r.buttons) return false
        if (r.notify.isNotEmpty() && r.notify.none { it.equals(me, true) }) return false
        val exp = r.expiresAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
        return exp == null || nowMs < exp
    }

    /** The line under an answered card. */
    fun stateLine(r: RisiMeta, answered: String?): String? = when (answered) {
        RisiFixit143.ACTION_GRANTED -> grantedLine(r.permission)
        RisiFixit143.ACTION_DECLINED -> NOT_ALLOWED
        else -> null
    }
}
