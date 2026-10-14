package ai.risicloud.risime.data.tabs

import android.content.SharedPreferences
import ai.risicloud.risime.data.HistoryMarkers
import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.net.RisiMeta
import ai.risicloud.risime.net.RisiSend149
import ai.risicloud.risime.net.RisiSendTabs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Proposal 2026-10-14-send-for-me (v1.49 §42): "Send a message for me". The developer flag `send_for_me`
 * (advertises `risi_send`), the per-contact "Send without asking next time" (stored on this phone only), and the
 * pure card rules. The phone's acceptance rule for the tool call itself is in [RisiToolExecutor].
 */

/**
 * The developer flag `send_for_me`: on by default for `risi_dev` accounts (Harsha first, the server's
 * RISI_DEV_USERS), off for everyone else; a switch in Settings → Developer. While it is off this phone never
 * advertises `risi_send`, and the server keeps the Phase 1 draft card.
 */
class SendForMeFlag(private val prefs: SharedPreferences?) {
    private val _on = MutableStateFlow(read(false))

    /** The effective flag (updated by [setDeveloper] / [set]). */
    val on: StateFlow<Boolean> = _on

    private fun read(sessionDev: Boolean): Boolean = SendForMeRules.flagOn(
        dev = sessionDev || (prefs?.getBoolean(KEY_DEV, false) ?: false),
        choice = prefs?.getString(KEY_CHOICE, null),
    )

    /** The effective flag given the signed-in user's `risi_dev` from the session (no network). */
    fun effective(sessionDev: Boolean): Boolean = read(sessionDev).also { _on.value = it }

    /** From a fresh `GET /me`: a non-developer account turns it off. */
    fun setDeveloper(dev: Boolean) {
        runCatching { prefs?.edit()?.putBoolean(KEY_DEV, dev)?.apply() }
        _on.value = read(false)
    }

    /** The Developer switch (only meaningful for a developer account). */
    fun set(on: Boolean) {
        runCatching { prefs?.edit()?.putString(KEY_CHOICE, if (on) "on" else "off")?.apply() }
        _on.value = read(false)
    }

    companion object {
        const val NAME = "send_for_me"
        private const val KEY_DEV = "dev_send_for_me_risi_dev"
        private const val KEY_CHOICE = "dev_send_for_me_choice"
    }
}

/** One contact the user lets Risi send to without asking (per tab). */
data class AutoSendContact(val userId: String, val tab: String, val name: String)

/** §42.3 "Send without asking next time": per contact and tab, never leaves this phone. */
interface AutoSendStore {
    fun isOn(userId: String, tab: String): Boolean

    fun set(userId: String, tab: String, name: String, on: Boolean)

    fun list(): List<AutoSendContact>

    /** The contacts list changed (Settings re-reads it). */
    val version: StateFlow<Int>
}

class PrefsAutoSendStore(private val prefs: SharedPreferences?) : AutoSendStore {
    private val _version = MutableStateFlow(0)
    override val version: StateFlow<Int> = _version

    private fun key(userId: String, tab: String) = "auto_${userId.lowercase()}_${tab.lowercase()}"

    override fun isOn(userId: String, tab: String): Boolean = prefs?.getString(key(userId, tab), null) != null

    override fun set(userId: String, tab: String, name: String, on: Boolean) {
        runCatching {
            val e = prefs?.edit() ?: return
            if (on) e.putString(key(userId, tab), name) else e.remove(key(userId, tab))
            e.apply()
        }
        _version.value++
    }

    override fun list(): List<AutoSendContact> = runCatching {
        prefs?.all.orEmpty().mapNotNull { (k, v) ->
            if (!k.startsWith("auto_") || v !is String) return@mapNotNull null
            val rest = k.removePrefix("auto_")
            val tab = rest.substringAfterLast('_')
            val user = rest.substringBeforeLast('_')
            if (user.isBlank() || tab !in setOf(RisiSendTabs.PRIVATE, RisiSendTabs.OFFICIAL)) null else AutoSendContact(user, tab, v)
        }.sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())
}

object SendForMeRules {
    /** §42.3 the request must be at most this old when the card arrives. */
    const val AUTO_WINDOW_MS = 10L * 60_000
    private const val SKEW_MS = 60_000L

    /** The developer flag: a developer account, unless the user switched it off. */
    fun flagOn(dev: Boolean, choice: String?): Boolean = dev && choice != "off"

    fun isSendCard(r: RisiMeta): Boolean = r.kind == RisiKinds.CONFIRM && r.tool == RisiSend149.TOOL_SEND_MESSAGE

    /** §42.2/§42.4 the one-tap card: `send_message`, or a `schedule_message` card with [Schedule] (a `risi_send` card). */
    fun isOneTapCard(r: RisiMeta): Boolean = isSendCard(r) ||
        (r.kind == RisiKinds.CONFIRM && r.tool == ai.risicloud.risime.net.RisiToolCall.TOOL_SCHEDULE_MESSAGE && RisiSend149.BUTTON_SCHEDULE in r.buttons)

    private val ONE_TAP_BUTTONS = setOf(RisiSend149.BUTTON_SEND, RisiSend149.BUTTON_SCHEDULE, RisiSend149.BUTTON_EDIT, RisiSend149.BUTTON_CANCEL)

    /** The buttons [me] sees on a one-tap card: only for `for`, while open, not while my answer is on its way. */
    fun buttons(me: String?, r: RisiMeta, state: RisiToolCards.ConfirmState, awaiting: Set<String>): List<String> {
        me ?: return emptyList()
        if (!isOneTapCard(r) || state != RisiToolCards.ConfirmState.OPEN) return emptyList()
        if (r.forUsers.none { it.equals(me, true) }) return emptyList()
        if (r.writeId?.lowercase() in awaiting) return emptyList()
        return r.buttons.filter { it in ONE_TAP_BUTTONS }
    }

    /** The card's text (the exact `args.text`, else `text`). */
    fun text(r: RisiMeta): String? =
        (r.args?.get("text") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: r.text

    /** "To: Upali Perera · 🔒 Private" / "· ● Official". */
    fun toLine(r: RisiMeta): String? {
        val s = r.send ?: return null
        val tab = if (s.tab == RisiSendTabs.OFFICIAL) "● Official" else "🔒 Private"
        return "To: ${s.to.name} · $tab"
    }

    fun firstName(name: String): String = name.trim().substringBefore(' ').ifBlank { name }

    /** "Send to Upali without asking next time". */
    fun tickLabel(r: RisiMeta): String? = r.send?.let { "Send to ${firstName(it.to.name)} without asking next time" }

    /** The success line of `message_sent` (the body, else built from `to` and `state`). */
    fun sentLine(r: RisiMeta, body: String): String = body.ifBlank {
        val who = r.to?.name ?: "them"
        if (r.state == "queued") "Queued for $who: it goes as soon as your phone is online." else "✓ Sent to $who"
    }

    /** The edit a [Send] after [Edit] carries: `{"text": trimmed}` (null for blank or unchanged text). */
    fun edit(original: String?, edited: String): kotlinx.serialization.json.JsonObject? {
        val t = edited.trim()
        if (t.isEmpty() || t == original?.trim()) return null
        return kotlinx.serialization.json.JsonObject(mapOf(RisiSend149.EDIT_TEXT to kotlinx.serialization.json.JsonPrimitive(t)))
    }

    /**
     * §42.3 auto-send: this phone sends the card's `confirm_write` itself only when ALL hold:
     * - it is a `send_message` card for [me] (in `for`), not expired, not answered by me yet;
     * - the user turned "Send without asking" on for that contact and tab;
     * - THIS phone sent the request: a row from my own user, sent here (not imported, not from my other device),
     *   carrying the card's `request_id`, at most 10 minutes before the card.
     */
    fun autoConfirm(
        card: MessageEntity,
        r: RisiMeta,
        rows: List<MessageEntity>,
        me: String,
        myDevice: String?,
        nowMs: Long,
        store: AutoSendStore,
    ): Boolean {
        if (!isSendCard(r)) return false
        val wid = r.writeId ?: return false
        val rid = r.requestId ?: return false
        val to = r.send?.to?.userId ?: return false
        if (r.forUsers.none { it.equals(me, true) }) return false
        val exp = r.expiresAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return false
        if (nowMs > exp) return false
        if (!store.isOn(to, r.send.tab)) return false
        // Already answered (by me, from any device): never a second confirm.
        val answered = rows.any { m ->
            m.kind == MessageEntity.KIND_RISI_CTL && m.from.equals(me, true) &&
                RisiControl.targetOf(m.systemJson)?.equals(wid, true) == true
        }
        if (answered) return false
        val cardAt = HistoryMarkers.epochMs(card.serverTs) ?: card.localTs
        return rows.any { m ->
            m.from.equals(me, true) && m.origin == null && sentHere(m, myDevice) &&
                (RisiControl.requestIdOf(m.systemJson)?.equals(rid, true) == true || m.systemJson?.contains(rid, ignoreCase = true) == true) &&
                run {
                    val at = HistoryMarkers.epochMs(m.serverTs) ?: m.localTs
                    at <= cardAt + SKEW_MS && cardAt - at <= AUTO_WINDOW_MS
                }
        }
    }

    private fun sentHere(m: MessageEntity, myDevice: String?): Boolean =
        m.fromDevice == null || (myDevice != null && m.fromDevice.equals(myDevice, true))

    /** The card was answered by MY auto-send (the line under it): my `confirm_write` and the contact's setting on. */
    fun sentWithoutAsking(r: RisiMeta, autoSent: Set<String>): Boolean = r.writeId?.lowercase() in autoSent
}
