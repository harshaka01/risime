package lk.codegen.risime.data.tabs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.net.RisiProgress
import lk.codegen.risime.net.RisiToolCall
import lk.codegen.risime.net.RisiToolErrorCodes
import lk.codegen.risime.net.RisiToolResult
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/*
 * Contract v1.25 §25: Risi with tools. The `risi_tools` switch (server `/auth/config` and this
 * device's advertisement), the per-device `risi_tool_call` (answered over TLS, never stored), the
 * ephemeral `risi_progress` signal, and the card rules of the v1.25 kinds.
 */

/**
 * §25.8: the server switch (`/auth/config` `risi_tools`, kept across restarts) and whether this
 * device's last registration advertised `risi_tools`. Risi's tools, the Risi chat and every v1.25
 * affordance show only while [on] (both), and only together with tabs.
 */
class RisiToolsSwitch(
    persistedServerOn: Boolean = false,
    private val persistServerOn: (Boolean) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val _serverOn = MutableStateFlow(persistedServerOn)
    val serverOn: StateFlow<Boolean> = _serverOn.asStateFlow()

    private val _advertised = MutableStateFlow(false)
    val advertised: StateFlow<Boolean> = _advertised.asStateFlow()

    private val _on = MutableStateFlow(false)

    /** The server says on and this device advertises `risi_tools`. */
    val on: StateFlow<Boolean> = _on.asStateFlow()

    @Volatile private var advertisedKnown = false

    fun setServerOn(on: Boolean) {
        if (_serverOn.value != on) {
            log("risi_tools: server switch ${if (on) "on" else "off"}")
            _serverOn.value = on
            persistServerOn(on)
        }
        recompute()
    }

    fun setAdvertised(on: Boolean) {
        advertisedKnown = true
        _advertised.value = on
        recompute()
    }

    /** Process start: the last registration of this device id advertised it (a registration in this process wins). */
    fun restoreAdvertised(on: Boolean) {
        if (advertisedKnown) return
        _advertised.value = on
        recompute()
    }

    private fun recompute() {
        _on.value = _serverOn.value && _advertised.value
    }
}

/**
 * §25.3 a `risi_tool_call` for this device. A call not naming this device in `to_devices`, or past
 * `expires_at`, is ignored. Otherwise exactly one result is posted: [execute] decides it (until the
 * real executor lands every known tool answers `declined`, which adds nothing and reads nothing;
 * an unknown tool answers `error` `unknown_tool`). Args and results are never logged or stored.
 */
class RisiToolCallHandler(
    private val deviceId: suspend () -> String?,
    private val post: suspend (toolCallId: String, result: RisiToolResult, deviceId: String) -> ApiResult<Unit>,
    private val enabled: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val execute: suspend (RisiToolCall) -> RisiToolResult = { stubResult(it) },
) {
    private val answered = ConcurrentHashMap.newKeySet<String>()

    /** Whether this device should answer [call] now (null: ignore it). */
    fun accepts(call: RisiToolCall, myDevice: String?, nowMs: Long): Boolean {
        myDevice ?: return false
        if (call.toDevices.none { it.equals(myDevice, true) }) return false
        val exp = runCatching { Instant.parse(call.expiresAt).toEpochMilli() }.getOrNull() ?: return false
        return exp > nowMs
    }

    suspend fun handle(call: RisiToolCall) {
        if (!enabled()) return
        val me = deviceId()
        if (!accepts(call, me, now())) return
        if (!answered.add(call.toolCallId.lowercase())) return
        val result = runCatching { execute(call) }.getOrElse { RisiToolResult.error(RisiToolErrorCodes.CALENDAR_UNAVAILABLE) }
        var sent = post(call.toolCallId, result, me!!)
        // An older server's strict schema refuses the optional `calendar` of a calendar_add result
        // (proposal 2026-10-09-risi-action-loop §3): send the v1.25 `{event_id}` instead.
        val res = result.result
        if (sent is ApiResult.Error && sent.code == "bad_request" && res != null && "calendar" in res) {
            sent = post(call.toolCallId, result.copy(result = kotlinx.serialization.json.JsonObject(res - "calendar")), me)
        }
        when (val r = sent) {
            is ApiResult.Ok -> log("risi tool ${call.tool}: ${result.status}")
            is ApiResult.Error -> log("risi tool ${call.tool}: result refused (${r.code})")
            is ApiResult.NetworkError -> {
                answered.remove(call.toolCallId.lowercase())
                log("risi tool ${call.tool}: result not sent (network)")
            }
        }
    }

    companion object {
        /** A7 stub: known tools are declined (nothing read, nothing added), unknown ones are `unknown_tool`. */
        fun stubResult(call: RisiToolCall): RisiToolResult = when (call.tool) {
            RisiToolCall.TOOL_CALENDAR_CHECK, RisiToolCall.TOOL_CALENDAR_ADD -> RisiToolResult.declined()
            else -> RisiToolResult.error(RisiToolErrorCodes.UNKNOWN_TOOL)
        }
    }
}

/** §25.4 the latest `risi_progress` per request (in memory only); a lower `seq` than one shown is dropped. */
class RisiProgressStore(private val now: () -> Long = System::currentTimeMillis) {
    data class Shown(val progress: RisiProgress, val atMs: Long)

    private val _byRequest = MutableStateFlow<Map<String, Shown>>(emptyMap())

    /** request id (lowercase) → its bubble state. */
    val byRequest: StateFlow<Map<String, Shown>> = _byRequest.asStateFlow()

    fun apply(p: RisiProgress) {
        val key = p.requestId.lowercase()
        _byRequest.update { m ->
            val prev = m[key]
            when {
                prev != null && p.seq <= prev.progress.seq -> m
                p.state == RisiProgress.DONE -> m - key
                else -> m + (key to Shown(p, now()))
            }
        }
    }

    /** The turn's message arrived: its bubble ends. */
    fun finish(requestId: String) = _byRequest.update { it - requestId.lowercase() }

    fun clear() {
        _byRequest.value = emptyMap()
    }

    companion object {
        /** A bubble without a signal for this long says "Still working…". */
        const val STILL_WORKING_MS = 90_000L

        /** A bubble with no signal for this long is dropped (a lost `done`). */
        const val STALE_MS = 10L * 60_000

        /**
         * §25.4 the bubbles to draw in [conversationId]: its requests, minus those a Risi message for
         * the same request arrived for since their last signal (the turn's message ends the bubble).
         */
        fun visible(shown: Collection<Shown>, rows: List<MessageEntity>, conversationId: String, nowMs: Long = System.currentTimeMillis()): List<Shown> {
            val answered = HashMap<String, Long>()
            for (m in rows) {
                val rid = RisiMessages.meta(m)?.requestId?.lowercase() ?: continue
                answered[rid] = maxOf(answered[rid] ?: 0L, m.localTs)
            }
            return shown.filter { s ->
                s.progress.conversationId.equals(conversationId, true) &&
                    nowMs - s.atMs < STALE_MS &&
                    (answered[s.progress.requestId.lowercase()]?.let { it < s.atMs } ?: true)
            }.sortedBy { it.atMs }
        }
    }
}

/** §25.4 the bubble text. */
object RisiStepLabels {
    fun tool(tool: String?): String = when (tool) {
        "calendar_check" -> "Checking your calendar…"
        "calendar_add" -> "Adding to your calendar…"
        "set_reminder" -> "Setting a reminder…"
        "search_chats" -> "Searching your chats…"
        "summarise" -> "Summarising…"
        "draft_reply" -> "Drafting a reply…"
        "remember" -> "Remembering that…"
        "forget" -> "Forgetting that…"
        "ask_risiwork" -> "Asking RisiWork…"
        "capabilities" -> "Checking what I can do…"
        // v1.26 §26.6
        "set_alarm" -> "Setting an alarm…"
        "schedule_message" -> "Scheduling your message…"
        "cancel_scheduled" -> "Cancelling a scheduled message…"
        "calendar_remove" -> "Removing it from your calendar…"
        "need_skill" -> "Checking your Risi skills…"
        else -> "Working…"
    }

    /** A finished step in an answer ("Checked your calendar"). */
    fun done(tool: String?): String = when (tool) {
        "calendar_check" -> "Checked your calendar"
        "calendar_add" -> "Added to your calendar"
        "calendar_remove" -> "Removed from your calendar"
        "set_reminder" -> "Set a reminder"
        "search_chats" -> "Searched your chats"
        "summarise" -> "Summarised"
        "draft_reply" -> "Drafted a reply"
        "remember" -> "Remembered"
        "forget" -> "Forgot"
        "ask_risiwork" -> "Asked RisiWork"
        "capabilities" -> "Checked what I can do"
        "set_alarm" -> "Set an alarm"
        "schedule_message" -> "Scheduled your message"
        "cancel_scheduled" -> "Cancelled a scheduled message"
        "need_skill" -> "Checked your Risi skills"
        else -> "Worked on it"
    }

    fun of(shown: RisiProgressStore.Shown, nowMs: Long): String {
        if (nowMs - shown.atMs >= RisiProgressStore.STILL_WORKING_MS) return "Still working…"
        val p = shown.progress
        return when (p.state) {
            RisiProgress.QUEUED -> p.position?.let { "Queued ($it)…" } ?: "Queued…"
            RisiProgress.STEP -> tool(p.step?.tool)
            RisiProgress.WAITING_CONFIRM -> "Waiting for you to confirm…"
            else -> "Working…"
        }
    }
}

/** §25.4 the rules of the v1.25 cards (confirm, reminder_set, draft). */
object RisiToolCards {
    /** Card state from the actions seen after it. */
    enum class ConfirmState { OPEN, CONFIRMED, CANCELLED, EXPIRED }

    fun confirmExpired(r: RisiMeta, nowMs: Long): Boolean {
        val exp = r.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return true
        return nowMs >= exp
    }

    /**
     * The confirm card's state for everyone: the first `confirm_write`/`cancel_write` for its `write_id`
     * from a user in `for` decides it (others are ignored, §25.4); else expired after `expires_at`.
     */
    fun confirmState(r: RisiMeta, actions: List<MessageEntity>, nowMs: Long): ConfirmState {
        val wid = r.writeId ?: return ConfirmState.EXPIRED
        for (m in actions) {
            if (m.kind != MessageEntity.KIND_RISI_CTL) continue
            if (RisiControl.targetOf(m.systemJson)?.equals(wid, true) != true) continue
            if (r.forUsers.none { it.equals(m.from, true) }) continue
            when (RisiControl.actionOf(m.systemJson)) {
                "confirm_write" -> return ConfirmState.CONFIRMED
                "cancel_write" -> return ConfirmState.CANCELLED
            }
        }
        return if (confirmExpired(r, nowMs)) ConfirmState.EXPIRED else ConfirmState.OPEN
    }

    /** Only a user in `for` sees the buttons, while the card is open and their answer isn't on its way. */
    fun confirmButtons(me: String?, r: RisiMeta, state: ConfirmState, awaiting: Set<String>): List<String> {
        me ?: return emptyList()
        if (state != ConfirmState.OPEN) return emptyList()
        if (r.forUsers.none { it.equals(me, true) }) return emptyList()
        if (r.writeId?.lowercase() in awaiting) return emptyList()
        // §26.9: an unknown `tool` shows its summary only.
        if (!RisiSkillCards.knownConfirmTool(r)) return emptyList()
        return r.buttons.filter { it in setOf("add", "cancel", "allow") }
    }

    /**
     * §25.4 who a group reminder will remind: `participants` plus the `me_too`s minus the `not_me`s
     * seen from human members (agent rows are never actions). The firing `reminder`'s `notify` is authoritative.
     */
    fun reminderParticipants(r: RisiMeta, actions: List<MessageEntity>, agentUsers: Set<String>): List<String> {
        val rid = r.reminderId ?: return r.participants
        val out = LinkedHashMap<String, String>()
        r.participants.forEach { out[it.lowercase()] = it }
        for (m in actions) {
            if (m.kind != MessageEntity.KIND_RISI_CTL) continue
            if (agentUsers.any { it.equals(m.from, true) }) continue
            if (RisiControl.targetOf(m.systemJson)?.equals(rid, true) != true) continue
            when (RisiControl.actionOf(m.systemJson)) {
                "me_too" -> out[m.from.lowercase()] = m.from
                "not_me" -> out.remove(m.from.lowercase())
            }
        }
        return out.values.toList()
    }

    /** [Me too] / [Not me] for a `me_too` reminder, before it fires; never for the asker's Me too. */
    fun reminderButtons(me: String?, r: RisiMeta, participants: List<String>, nowMs: Long): List<String> {
        me ?: return emptyList()
        if (!r.meToo) return emptyList()
        val fires = r.reminderWhen()?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        if (fires != null && nowMs >= fires) return emptyList()
        return if (participants.any { it.equals(me, true) }) listOf("not_me") else listOf("me_too")
    }

    /** A draft's [Use] shows only when its target conversation is on this phone; it never sends. */
    fun draftUsable(r: RisiMeta, hasConversation: (String) -> Boolean): Boolean {
        val target = r.targetConversationId ?: return false
        return !r.text.isNullOrBlank() && hasConversation(target)
    }
}
