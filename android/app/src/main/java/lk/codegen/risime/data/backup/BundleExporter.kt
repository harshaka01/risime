package lk.codegen.risime.data.backup

import kotlinx.serialization.json.JsonObject
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.BackupDao
import lk.codegen.risime.data.db.DeleteDao
import lk.codegen.risime.data.db.GroupDao
import lk.codegen.risime.data.db.GroupEntity
import lk.codegen.risime.data.db.MediaDao
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.groups.SystemLine
import lk.codegen.risime.data.media.ImageEnvelope
import lk.codegen.risime.data.media.MediaSealer
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.net.BlobRef
import lk.codegen.risime.net.GroupMember
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.dmPeer
import lk.codegen.risime.net.isGroupConversation

/**
 * §22.5 the bundle writer (android A4): JSON Lines per conversation in pages, never the whole
 * bundle in memory. Every conversation, not bound to the gap index or 30 days; call lines always
 * (a backup is the user's own). Never in a backup (§22.5): MLS state and `mls_kv`, the sync cursor,
 * the device id, tokens, gap rows and §13.3/§17.12 markers, outbox rows (pending/failed), media
 * files, avatars, settings and the behaviour log.
 */
class BundleExporter(
    private val dao: BackupDao,
    private val groups: GroupDao,
    private val deletes: DeleteDao,
    private val media: MediaDao?,
    private val sealer: MediaSealer?,
    private val me: String,
    /** A DM with an MLS group on this device (the conversation line's `e2ee`); groups always are. */
    private val dmE2ee: (String) -> Boolean = { false },
    private val pageSize: Int = 500,
) {
    /** The lines in order, through [out] (one call per line). Returns the counts written. */
    suspend fun write(out: (ByteArray) -> Unit): BackupCounts {
        var conversations = 0
        var messages = 0
        var tombstones = 0
        for (conv in dao.conversationIds()) {
            conversations++
            out(encodeLine(BackupConversationLine.serializer(), conversationLine(conv)))
            var afterTs = Long.MIN_VALUE
            var afterId = ""
            while (true) {
                val page = dao.page(conv, afterTs, afterId, pageSize)
                if (page.isEmpty()) break
                for (m in page) {
                    val line = rowLine(conv, m) ?: continue
                    when (line) {
                        is BackupMessageLine -> { messages++; out(encodeLine(BackupMessageLine.serializer(), line)) }
                        is BackupTombstoneLine -> { tombstones++; out(encodeLine(BackupTombstoneLine.serializer(), line)) }
                        is BackupGroupEventLine -> out(encodeLine(BackupGroupEventLine.serializer(), line))
                    }
                }
                val last = page.last()
                afterTs = last.localTs
                afterId = last.clientMsgId
            }
            // Reactions after every row of the conversation (a reaction applies only to a held target).
            for (r in dao.confirmedAdds(conv)) {
                val id = r.confirmedMessageId ?: continue
                val ts = r.confirmedTs ?: continue
                messages++
                out(
                    encodeLine(
                        BackupMessageLine.serializer(),
                        BackupMessageLine(
                            conversationId = conv, messageId = id.lowercase(), clientMsgId = r.confirmedClientMsgId ?: id.lowercase(),
                            from = r.reactorUserId.lowercase(), serverTs = ts,
                            payload = json(MlsPayload.reaction(r.targetMessageId, r.emoji, "add")),
                            status = if (r.reactorUserId.equals(me, true)) "sent" else null,
                        ),
                    ),
                )
            }
            // Hidden tombstones (§15.6): deletes keep winning after a restore.
            for (d in dao.deletedIds(conv)) {
                tombstones++
                out(encodeLine(BackupTombstoneLine.serializer(), BackupTombstoneLine(conversationId = conv, messageId = d.messageId, from = d.deletedBy, serverTs = d.deleteServerTs, scope = d.scope, hidden = true)))
            }
        }
        val contacts = contactLines()
        contacts.forEach { out(encodeLine(BackupContactLine.serializer(), it)) }
        return BackupCounts(conversations, messages, tombstones, contacts.size)
    }

    private fun json(b: ByteArray): JsonObject = ProtocolJson.parseToJsonElement(b.decodeToString()) as JsonObject

    private suspend fun conversationLine(conv: String): BackupConversationLine {
        val group = isGroupConversation(conv)
        val g = if (group) groups.get(conv) else null
        val state = deletes.chatState(conv)
        val info = g?.let { e ->
            val admins = groups.members(conv).filter { it.role == GroupMember.ROLE_ADMIN && it.current }.map { it.userId.lowercase() }
            BackupGroupInfo(
                name = e.name, icon = null, admins = admins, createdBy = e.createdBy?.lowercase(),
                state = when (e.state) {
                    GroupEntity.STATE_LEFT -> "left"
                    GroupEntity.STATE_REMOVED -> "removed"
                    else -> "active"
                },
            )
        }
        return BackupConversationLine(
            conversationId = conv, kind = if (group) "group" else "dm", e2ee = group || dmE2ee(conv),
            peer = if (group) null else dmPeer(conv, me)?.lowercase(),
            group = info ?: if (group) BackupGroupInfo() else null,
            chat = BackupChatInfo(clearedUpto = state?.clearedUpto?.let(BackupTime::ticksToIso), hidden = state?.hidden == true),
        )
    }

    /** One row → its line, or null when it never goes into a backup. */
    private suspend fun rowLine(conv: String, m: MessageEntity): Any? {
        if (m.system) {
            // §12.7 group lines only; §13.3/§17.12 markers and other local lines stay out.
            if (!m.clientMsgId.startsWith("sys:")) return null
            val l = SystemLine.decode(m.systemJson) ?: return null
            if (l.action in SystemLine.LOCAL_ACTIONS || l.actor.isEmpty()) return null
            return BackupGroupEventLine(
                conversationId = conv, eventId = m.clientMsgId.removePrefix("sys:"), action = l.action, actor = l.actor.lowercase(),
                targets = l.targets.map { it.lowercase() }, role = l.role, serverTs = m.serverTs ?: BackupTime.iso(m.localTs),
            )
        }
        val id = m.messageId?.lowercase()
        if (m.deleted || m.deleteState != null) {
            // A tombstone, or a row being deleted for everyone (shown as one meanwhile).
            return id?.let { BackupTombstoneLine(conversationId = conv, messageId = it, from = m.from.lowercase(), serverTs = m.serverTs, scope = "everyone", hidden = false) }
        }
        // Outbox rows (never accepted by the server) are not in a backup.
        if (m.status == MessageStatus.PENDING.name || m.status == MessageStatus.FAILED.name) return null
        val payload: JsonObject = when (m.kind) {
            MessageEntity.KIND_TEXT -> json(MlsPayload.text(m.body))
            MessageEntity.KIND_IMAGE -> imagePayload(m) ?: return null
            MessageEntity.KIND_CALL -> callPayload(m) ?: return null
            else -> return null
        }
        if (id == null && !m.call) return null
        return BackupMessageLine(
            conversationId = conv, messageId = id, clientMsgId = m.clientMsgId, from = m.from.lowercase(), fromDevice = m.fromDevice?.lowercase(),
            serverTs = m.serverTs, payload = payload, origin = m.origin, sharedBy = m.sharedBy?.lowercase(),
            status = if (m.outgoing) statusName(m.status) else null,
        )
    }

    private fun statusName(s: String): String = when (s) {
        MessageStatus.READ.name -> "read"
        MessageStatus.DELIVERED.name -> "delivered"
        else -> "sent"
    }

    /** §17.7: by reference with `enc` and `thumb`, reconstructed and §14.4-validated (expired images still go). */
    private suspend fun imagePayload(m: MessageEntity): JsonObject? {
        val row = media?.get(m.clientMsgId) ?: return null
        val s = sealer ?: return null
        val blobId = row.blobId ?: m.blobId ?: return null
        val enc = runCatching { s.openEnc(m.clientMsgId, row.sealedEnc) }.getOrNull() ?: return null
        val thumb = row.sealedThumb?.let { t -> runCatching { s.openThumb(m.clientMsgId, t) }.getOrNull() }
        val env = ImageEnvelope(BlobRef(blobId, row.blobSize, row.blobSha256), enc, row.mime, row.w, row.h, thumb, m.body.takeIf { it.isNotEmpty() })
        val obj = json(env.encode())
        ImageEnvelope.validate(obj) ?: return null
        return obj
    }

    /** `call_end` or `group_call` as stored (the local `x_over` flag stays on the device). */
    private fun callPayload(m: MessageEntity): JsonObject? {
        val obj = runCatching { ProtocolJson.parseToJsonElement(m.systemJson ?: "") as? JsonObject }.getOrNull() ?: return null
        val clean = JsonObject(obj - lk.codegen.risime.calls.GroupCallLines.LOCAL_OVER)
        return when (MlsPayload.decode(ProtocolJson.encodeToString(JsonObject.serializer(), clean).toByteArray())) {
            is MlsPayload.Decoded.Call, is MlsPayload.Decoded.GroupCall -> clean
            else -> null
        }
    }

    /** Contacts with a user id, then group members not among them (names for people in old chats). */
    private suspend fun contactLines(): List<BackupContactLine> {
        val out = linkedMapOf<String, BackupContactLine>()
        for (c in dao.contacts()) {
            val id = c.userId?.lowercase() ?: continue
            out.putIfAbsent(id, BackupContactLine(userId = id, displayName = c.displayName, phone = c.phone.takeIf { it.startsWith("+") }))
        }
        for (m in dao.allMembers()) {
            val id = m.userId.lowercase()
            if (id == me.lowercase() || m.displayName.isBlank()) continue
            out.putIfAbsent(id, BackupContactLine(userId = id, displayName = m.displayName, phone = m.phone))
        }
        return out.values.toList()
    }
}
