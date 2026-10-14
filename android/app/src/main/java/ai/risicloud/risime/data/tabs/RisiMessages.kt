package ai.risicloud.risime.data.tabs

import kotlinx.serialization.json.JsonObject
import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.net.GroupMeta
import ai.risicloud.risime.net.ProtocolJson
import ai.risicloud.risime.net.RisiMeta

/*
 * §24.11 a Risi message is an ordinary `text` envelope with a structured `risi` object. It is
 * honoured only from a leaf whose attested kind is `agent`, and only in Official; anywhere else it is
 * plain text. An honoured object is kept with the row (`system_json` of a `text` row; no Room
 * change) so notifications can apply `risi.notify` and later chunks can render cards.
 */
object RisiMessages {
    /**
     * §24.11: [risi] (the envelope's object) is honoured only when the conversation's MLS state is
     * Official ([meta]) and the authenticated sender is one of its attested agent users. Else null.
     */
    fun honoured(risi: JsonObject?, meta: GroupMeta?, sender: String, agentUsers: Set<String>): JsonObject? {
        risi ?: return null
        if (meta?.official != true) return null
        if (agentUsers.none { it.equals(sender, true) }) return null
        return risi
    }

    fun encode(risi: JsonObject): String = ProtocolJson.encodeToString(JsonObject.serializer(), risi)

    /** The honoured `risi` object of a stored row (null: an ordinary message). */
    fun meta(m: MessageEntity): RisiMeta? {
        if (m.kind != MessageEntity.KIND_TEXT) return null
        val json = m.systemJson ?: return null
        return runCatching { ProtocolJson.decodeFromString(RisiMeta.serializer(), json) }.getOrNull()
    }

    /**
     * Hotfix 2026-10-14: the row's Risi model for drawing. A stored `risi` object that this version
     * can't read becomes `kind: "unknown"`, which draws as its body or the "update RisiMe" line
     * ([RisiRender]); a Risi message never disappears. Null: an ordinary message.
     */
    fun metaForDisplay(m: MessageEntity): RisiMeta? {
        if (m.kind != MessageEntity.KIND_TEXT || m.systemJson.isNullOrBlank()) return null
        return meta(m) ?: RisiMeta(kind = UNKNOWN_KIND)
    }

    const val UNKNOWN_KIND = "unknown"

    /**
     * §24.9/§24.11: a Risi message notifies only the users in its `notify`; for everyone else it is
     * silent (no sound, no heads-up, no notification) and still counts as unread. Any other message
     * notifies as before.
     */
    fun notifies(m: MessageEntity, me: String?): Boolean {
        val r = meta(m) ?: return true
        me ?: return false
        return r.notify.any { it.equals(me, true) }
    }
}
