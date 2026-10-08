package lk.codegen.risime.data.backup

import kotlinx.serialization.json.JsonObject
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.ReactionStore
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.db.BackupDao
import lk.codegen.risime.data.db.ChatStateEntity
import lk.codegen.risime.data.db.ContactDao
import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.DeleteDao
import lk.codegen.risime.data.db.DeletedIdEntity
import lk.codegen.risime.data.db.GroupDao
import lk.codegen.risime.data.db.GroupEntity
import lk.codegen.risime.data.db.HistoryDao
import lk.codegen.risime.data.db.MessageDao
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.deletes.TimeUuid
import lk.codegen.risime.data.groups.SystemLine
import lk.codegen.risime.data.groups.systemText
import lk.codegen.risime.data.history.HistoryImporter
import lk.codegen.risime.data.media.ImageHooks
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.net.GroupMember
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.dmPeer
import lk.codegen.risime.net.isGroupConversation

/** Where a restore got to (§22.6: resumable per `backup_id` and line). */
interface RestoreProgress {
    /** Lines (after the header) already committed for [backupId], or 0. */
    fun done(backupId: String): Int

    fun save(backupId: String, lines: Int)

    fun clear()
}

/** What a restore did (counts by kind; never content). */
data class RestoreResult(
    val imported: Int,
    val tombstones: Int,
    val groupEvents: Int,
    val conversations: Int,
    val contacts: Int,
    val skipped: Map<String, Int>,
    val images: Boolean,
)

/** The bundle is not this app's or not this account's: nothing was imported. */
class BundleRejected(val reason: String) : Exception(reason)

/**
 * §22.6 restore: extends the §17.7 import. Only inserts (hard rule 9): an entry is imported unless
 * a local row with that `message_id` (or `client_msg_id`) exists, a local (hidden) tombstone covers
 * it (deletes win), it is at or before the local Clear chat watermark, or its payload fails live
 * validation. Restored rows are never notified, unread or acked and outgoing keep their status. One
 * transaction per at most [batch] lines with the progress saved after each, so a killed restore
 * resumes and a repeated one changes nothing.
 */
class BundleImporter(
    private val messages: MessageDao,
    private val deletes: DeleteDao,
    private val groups: GroupDao,
    private val contacts: ContactDao,
    private val backupDao: BackupDao,
    private val history: HistoryDao?,
    private val reactions: ReactionStore,
    private val images: ImageHooks?,
    private val tx: TransactionRunner,
    private val progress: RestoreProgress,
    private val me: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val batch: Int = 1_000,
) {
    private var imported = 0
    private var tombstones = 0
    private var groupEvents = 0
    private var conversations = 0
    private var contactsAdded = 0
    private var anyImage = false
    private val skipped = sortedMapOf<String, Int>()
    private val gapsTouched = mutableSetOf<String>()

    private fun skip(why: String) {
        skipped[why] = (skipped[why] ?: 0) + 1
    }

    /**
     * Imports [lines] (the inflated bundle, one JSON object per line, header first). [afterBatch]
     * runs after every committed batch (tests kill a restore there).
     */
    suspend fun import(lines: Iterator<String>, afterBatch: (Int) -> Unit = {}): RestoreResult {
        val headerText = nextNonBlank(lines) ?: throw BundleRejected("empty bundle")
        val header = runCatching { ProtocolJson.decodeFromString(BackupBundleHeader.serializer(), headerText) }.getOrNull()
            ?: throw BundleRejected("bad header")
        if (header.v != 1 || header.type != BackupBundleHeader.TYPE) throw BundleRejected("not a backup bundle")
        if (header.schema > BUNDLE_SCHEMA) throw BundleRejected("schema ${header.schema}")
        if (!header.userId.equals(me, true)) throw BundleRejected("another account")
        val start = progress.done(header.backupId)
        var n = 0
        // Skip what a killed restore already committed.
        while (n < start && lines.hasNext()) {
            lines.next()
            n++
        }
        while (lines.hasNext()) {
            val chunk = ArrayList<String>(batch)
            while (chunk.size < batch && lines.hasNext()) chunk += lines.next()
            tx.run { chunk.forEach { applyLine(it) } }
            n += chunk.size
            progress.save(header.backupId, n)
            afterBatch(n)
        }
        // §17.12: gap markers follow the remaining gap rows (no "history shared" line for a backup).
        val h = history
        if (h != null) for (conv in gapsTouched) tx.run { HistoryImporter(messages, h, null, null, null).refreshGapMarker(conv, residual = false) }
        progress.clear()
        val r = RestoreResult(imported, tombstones, groupEvents, conversations, contactsAdded, skipped.toMap(), anyImage)
        log("restore ${header.backupId}: $imported imported, $tombstones tombstones, $groupEvents group lines, $conversations chats, $contactsAdded contacts, skipped $skipped")
        return r
    }

    private fun nextNonBlank(lines: Iterator<String>): String? {
        while (lines.hasNext()) {
            val l = lines.next()
            if (l.isNotBlank()) return l
        }
        return null
    }

    private suspend fun applyLine(text: String) {
        if (text.isBlank()) return
        val obj = runCatching { ProtocolJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return skip("malformed")
        try {
            when (obj.str("type")) {
                BackupConversationLine.TYPE -> conversation(ProtocolJson.decodeFromJsonElement(BackupConversationLine.serializer(), obj))
                BackupMessageLine.TYPE -> message(ProtocolJson.decodeFromJsonElement(BackupMessageLine.serializer(), obj))
                BackupTombstoneLine.TYPE -> tombstone(ProtocolJson.decodeFromJsonElement(BackupTombstoneLine.serializer(), obj))
                BackupGroupEventLine.TYPE -> groupEvent(ProtocolJson.decodeFromJsonElement(BackupGroupEventLine.serializer(), obj))
                BackupContactLine.TYPE -> contact(ProtocolJson.decodeFromJsonElement(BackupContactLine.serializer(), obj))
                else -> skip("unknown_type") // forward-compatible additions keep schema 1
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            skip("malformed")
        } catch (e: IllegalArgumentException) {
            skip("malformed")
        }
    }

    private fun validConv(conv: String): Boolean = conv.startsWith("dm:") || conv.startsWith("grp:")

    private suspend fun cleared(conv: String, ticks: Long?): Boolean {
        val upto = deletes.chatState(conv)?.clearedUpto ?: return false
        return ticks != null && ticks <= upto
    }

    private suspend fun conversation(c: BackupConversationLine) {
        val conv = c.conversationId
        if (!validConv(conv)) return skip("malformed")
        conversations++
        val backupCleared = BackupTime.isoToTicks(c.chat.clearedUpto)
        val local = deletes.chatState(conv)
        if (local == null) {
            if (backupCleared != null || c.chat.hidden) backupDao.insertChatState(ChatStateEntity(conv, backupCleared, c.chat.hidden))
        } else if (backupCleared != null && (local.clearedUpto == null || backupCleared > local.clearedUpto)) {
            // The later watermark wins; `hidden` only for a chat new on this device.
            deletes.putChatState(local.copy(clearedUpto = backupCleared))
        }
        val g = c.group
        if (isGroupConversation(conv) && g != null && groups.get(conv) == null) {
            // A display cache until the server's GET /groups and a Welcome bring the truth.
            val mine = g.admins.any { it.equals(me, true) }
            backupDao.insertGroup(
                GroupEntity(
                    conversationId = conv, name = g.name, myRole = if (mine) GroupMember.ROLE_ADMIN else GroupMember.ROLE_MEMBER,
                    state = when (g.state) {
                        "left" -> GroupEntity.STATE_LEFT
                        "removed" -> GroupEntity.STATE_REMOVED
                        else -> GroupEntity.STATE_ACTIVE
                    },
                    createdBy = g.createdBy, createdAt = null, generation = 0, epochSeen = null, metaUpdatedAt = null, lastRefreshedAt = null,
                    localTs = clock(),
                ),
            )
        }
    }

    private suspend fun message(e: BackupMessageLine) {
        val conv = e.conversationId
        if (!validConv(conv)) return skip("malformed")
        val id = e.messageId?.lowercase()
        if (id != null && TimeUuid.canonical(id) == null) return skip("malformed")
        if (id != null && messages.byMessageId(id) != null) return skip("existing_row")
        if (messages.byClientMsgId(e.clientMsgId) != null) return skip("existing_row")
        if (id != null && deletes.deletedId(id) != null) return skip("tombstone")
        if (cleared(conv, TimeUuid.ticks(id) ?: BackupTime.isoToTicks(e.serverTs))) return skip("cleared")
        val payload = MlsPayload.decode(ProtocolJson.encodeToString(JsonObject.serializer(), e.payload).toByteArray())
        val from = e.from.lowercase()
        val outgoing = from.equals(me, true)
        val ts = BackupTime.ms(e.serverTs) ?: TimeUuid.epochMs(id) ?: return skip("malformed")
        if (payload is MlsPayload.Decoded.Reaction) {
            if (id == null) return skip("malformed")
            val target = messages.byMessageId(payload.target.lowercase())
            if (target == null || target.showsAsDeleted || !target.conversationId.equals(conv, true)) return skip("no_target")
            if (reactions.applyConfirmed(conv, target.messageId!!, from, payload.emoji, payload.op, e.serverTs ?: BackupTime.iso(ts), id, e.clientMsgId)) imported++ else skip("existing_row")
            return
        }
        val to = if (isGroupConversation(conv)) conv else (if (outgoing) dmPeer(conv, me) else me) ?: conv
        var kind = MessageEntity.KIND_TEXT
        var body: String
        var systemJson: String? = null
        var callId: String? = null
        var image: lk.codegen.risime.data.media.ImageEnvelope? = null
        when (payload) {
            is MlsPayload.Decoded.Text -> body = payload.body
            is MlsPayload.Decoded.Image -> {
                if (images == null) return skip("images_unavailable")
                kind = MessageEntity.KIND_IMAGE
                body = payload.envelope.caption.orEmpty()
                image = payload.envelope
            }
            is MlsPayload.Decoded.Call -> {
                val end = payload.env as? lk.codegen.risime.calls.CallEnvelope.End ?: return skip("malformed")
                if (messages.callLine(conv, end.callId) != null) return skip("existing_row")
                kind = MessageEntity.KIND_CALL
                body = lk.codegen.risime.calls.CallLines.line(end.reason, outgoing, end.durationS, false, end.media == lk.codegen.risime.calls.CallEnvelope.MEDIA_VIDEO).text
                systemJson = ProtocolJson.encodeToString(JsonObject.serializer(), e.payload)
                callId = end.callId
            }
            is MlsPayload.Decoded.GroupCall -> {
                if (messages.callLine(conv, payload.env.callId) != null) return skip("existing_row")
                kind = MessageEntity.KIND_CALL
                body = lk.codegen.risime.calls.GroupCallLines.text(payload.env, "", outgoing, running = false)
                systemJson = ProtocolJson.encodeToString(JsonObject.serializer(), e.payload)
                callId = payload.env.callId
            }
            else -> return skip("malformed")
        }
        if (id == null && kind != MessageEntity.KIND_CALL) return skip("malformed")
        val row = MessageEntity(
            clientMsgId = e.clientMsgId, messageId = id, conversationId = conv, from = from, to = to, body = body,
            serverTs = e.serverTs, localTs = ts,
            status = if (outgoing) outgoingStatus(e.status) else MessageStatus.READ.name,
            outgoing = outgoing, ackedStatus = if (outgoing) null else MessageStatus.READ.name,
            kind = kind, systemJson = systemJson, blobId = image?.blob?.blobId, callId = callId,
            origin = e.origin ?: ORIGIN_BACKUP, sharedBy = e.sharedBy?.lowercase(), fromDevice = e.fromDevice?.lowercase(),
        )
        if (messages.insert(row) == -1L) return skip("existing_row")
        image?.let {
            images?.stored(row, it)
            anyImage = true
        }
        imported++
        // §22.6: a matching gap row goes; its marker is updated at the end.
        if (id != null) history?.let { h -> if (h.gap(id) != null) { h.deleteGaps(listOf(id)); gapsTouched += conv } }
    }

    private fun outgoingStatus(s: String?): String = when (s) {
        "read" -> MessageStatus.READ.name
        "delivered" -> MessageStatus.DELIVERED.name
        else -> MessageStatus.SENT.name
    }

    private suspend fun tombstone(t: BackupTombstoneLine) {
        val conv = t.conversationId
        if (!validConv(conv)) return skip("malformed")
        val id = TimeUuid.canonical(t.messageId.lowercase()) ?: return skip("malformed")
        if (t.scope != "everyone" && t.scope != "me") return skip("malformed")
        if (cleared(conv, TimeUuid.ticks(id))) return skip("cleared")
        val group = isGroupConversation(conv)
        val from = t.from.lowercase()
        // The hidden tombstone keeps deletes winning against a later arrival (the deleter is trusted: it is my own backup).
        val hidden = backupDao.insertDeletedId(DeletedIdEntity(id, conv, from, group, t.serverTs, t.scope, clock())) != -1L
        if (!t.hidden && messages.byMessageId(id) == null) {
            val ts = BackupTime.ms(t.serverTs) ?: TimeUuid.epochMs(id) ?: clock()
            val inserted = messages.insert(
                MessageEntity(
                    clientMsgId = MessageEntity.placeholderId(id), messageId = id, conversationId = conv, from = from, to = conv, body = "",
                    serverTs = t.serverTs, localTs = ts, status = MessageStatus.READ.name, outgoing = from.equals(me, true),
                    ackedStatus = MessageStatus.READ.name, kind = MessageEntity.KIND_DELETED, deletedBy = from, deletedByAdmin = false,
                    deletedAt = clock(), origin = ORIGIN_BACKUP,
                ),
            ) != -1L
            if (inserted) tombstones++
        } else if (hidden) {
            tombstones++
        }
        history?.let { h -> if (h.gap(id) != null) { h.deleteGaps(listOf(id)); gapsTouched += conv } }
    }

    private suspend fun groupEvent(g: BackupGroupEventLine) {
        val conv = g.conversationId
        if (!isGroupConversation(conv)) return skip("malformed")
        val clientMsgId = "sys:${g.eventId}"
        if (messages.byClientMsgId(clientMsgId) != null) return skip("existing_row")
        val ts = BackupTime.ms(g.serverTs) ?: TimeUuid.epochMs(g.eventId) ?: return skip("malformed")
        if (cleared(conv, BackupTime.isoToTicks(g.serverTs))) return skip("cleared")
        val line = SystemLine(g.action, g.actor, g.targets, g.role)
        val names = groups.members(conv).associate { it.userId.lowercase() to it.displayName }
        val body = systemText(line, me) { id -> names[id.lowercase()] ?: "Someone" }
        val ok = messages.insert(
            MessageEntity(
                clientMsgId = clientMsgId, messageId = null, conversationId = conv, from = g.actor, to = conv, body = body,
                serverTs = null, localTs = ts, status = MessageStatus.READ.name, outgoing = false,
                kind = MessageEntity.KIND_SYSTEM, systemJson = line.encode(),
            ),
        ) != -1L
        if (ok) groupEvents++
    }

    private suspend fun contact(c: BackupContactLine) {
        val phone = c.phone?.takeIf { it.startsWith("+") } ?: return skip("contact_without_phone")
        if (c.userId.equals(me, true)) return
        if (contacts.byUserId(c.userId) != null || contacts.byUserId(c.userId.lowercase()) != null) return skip("contact_known")
        if (backupDao.contactByPhone(phone) != null) return skip("contact_known")
        // A name cache only: never a friend, never messageable until the server says so.
        if (backupDao.insertContact(ContactEntity(phone, c.displayName, "", c.userId.lowercase(), registered = false, friend = false)) != -1L) contactsAdded++
    }

    companion object {
        /** §22.6: the `origin` of a restored row whose backup line had none. */
        const val ORIGIN_BACKUP = "backup"
    }
}
