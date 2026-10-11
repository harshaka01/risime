package lk.codegen.risime.data.history

import kotlinx.serialization.json.JsonObject
import lk.codegen.risime.data.HistoryMarkers
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.ReactionStore
import lk.codegen.risime.data.db.HistoryDao
import lk.codegen.risime.data.db.HistoryGapEntity
import lk.codegen.risime.data.db.MessageDao
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.deletes.DeleteApplier
import lk.codegen.risime.data.deletes.TimeUuid
import lk.codegen.risime.data.groups.SystemLine
import lk.codegen.risime.data.media.ImageHooks
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.net.HistoryBundleEntry
import lk.codegen.risime.net.HistoryBundleHeader
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.dmPeer

/** A parsed part: the header line and the entry lines that parse (the rest counted as malformed). */
class ParsedBundle(val header: HistoryBundleHeader, val entries: List<HistoryBundleEntry>, val malformed: Int)

object HistoryBundle {
    /** JSON Lines: a header, then entries. Null when the header itself is missing or malformed. */
    fun parse(plaintext: ByteArray): ParsedBundle? {
        val lines = plaintext.decodeToString().split('\n').filter { it.isNotBlank() }
        val header = lines.firstOrNull()?.let { runCatching { ProtocolJson.decodeFromString(HistoryBundleHeader.serializer(), it) }.getOrNull() } ?: return null
        if (header.v != 1 || header.type != HistoryBundleHeader.TYPE) return null
        var bad = 0
        val entries = lines.drop(1).mapNotNull { l -> runCatching { ProtocolJson.decodeFromString(HistoryBundleEntry.serializer(), l) }.getOrNull().also { if (it == null) bad++ } }
        return ParsedBundle(header, entries, bad)
    }
}

/** Counts by reason (android S5): what was imported and why the rest was skipped. Never content. */
class ImportResult(val imported: Int, val skipped: Map<String, Int>, val oldestLocalTs: Long?, val images: Boolean)

/**
 * §17.7 import (android R4b): one part, in the caller's transaction, the cursor untouched. Only
 * entries matching a gap row on message_id + client_msg_id + from + server_ts (from_device only
 * when both have it), created before the request was sent; never a duplicate, never past a
 * hidden tombstone (§17.7 step 4) or the Clear chat watermark; payloads validated like live ones.
 * Imported rows: `client_msg_id` from the gap row, origin/shared_by, historical time, read, no ticks,
 * never notified, unread or acked.
 */
class HistoryImporter(
    private val messages: MessageDao,
    private val dao: HistoryDao,
    private val applier: DeleteApplier?,
    private val reactions: ReactionStore?,
    private val images: ImageHooks?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    class Context(
        val requestId: String,
        val conversationId: String,
        val requestCreatedAt: Long,
        val part: Int,
        val parts: Int,
        val providerUser: String,
        val me: String,
    ) {
        val own: Boolean get() = providerUser.equals(me, true)
    }

    private fun ms(ts: String?) = HistoryMarkers.epochMs(ts)

    /** Header check (step 2): null = the whole part is dropped (and still acked `rejected`). */
    fun headerMatches(c: Context, h: HistoryBundleHeader): Boolean =
        h.requestId.equals(c.requestId, true) && h.conversationId.equals(c.conversationId, true) && h.part == c.part && h.parts == c.parts

    private fun matches(g: HistoryGapEntity, e: HistoryBundleEntry, c: Context): Boolean {
        if (!g.conversationId.equals(c.conversationId, true)) return false
        if (g.clientMsgId != e.clientMsgId) return false
        if (!g.from.equals(e.from, true)) return false
        val a = ms(g.serverTs) ?: return false
        if (a != ms(e.serverTs)) return false
        if (g.fromDevice != null && e.fromDevice != null && !g.fromDevice.equals(e.fromDevice, true)) return false
        return true
    }

    suspend fun importPart(c: Context, entries: List<HistoryBundleEntry>, malformed: Int = 0): ImportResult {
        val skipped = mutableMapOf<String, Int>()
        fun skip(why: String) { skipped[why] = (skipped[why] ?: 0) + 1 }
        if (malformed > 0) skipped["malformed"] = malformed
        val conv = c.conversationId
        val clearedUpto = applier?.clearedUpto(conv)
        var imported = 0
        var oldest: Long? = null
        var anyImage = false
        val done = mutableListOf<String>()
        // Messages first, reactions after (a reaction applies only to a target held after this part).
        val decoded = entries.map { it to MlsPayload.decode(ProtocolJson.encodeToString(JsonObject.serializer(), it.payload).toByteArray()) }
        val ordered = decoded.filter { it.second !is MlsPayload.Decoded.Reaction } + decoded.filter { it.second is MlsPayload.Decoded.Reaction }
        for ((e, payload) in ordered) {
            val id = e.messageId.lowercase()
            val g = dao.gap(id)
            if (g == null) { skip("no_gap_row"); continue }
            if (!matches(g, e, c)) { skip("mismatch"); continue }
            if (g.createdAt >= c.requestCreatedAt) { skip("gap_after_request"); continue }
            if (messages.byMessageId(id) != null || messages.byClientMsgId(g.clientMsgId) != null) {
                skip("existing_row")
                done += id
                continue
            }
            val ticks = TimeUuid.ticks(id)
            if (clearedUpto != null && (ticks == null || ticks <= clearedUpto)) { skip("cleared"); continue }
            val ts = ms(g.serverTs)!!
            val outgoing = g.from.equals(c.me, true)
            val to = if (lk.codegen.risime.net.isGroupConversation(conv)) conv else (if (outgoing) dmPeer(conv, c.me) else c.me) ?: conv
            val origin = if (c.own) MessageEntity.ORIGIN_OWN_DEVICE else MessageEntity.ORIGIN_SHARED
            if (payload is MlsPayload.Decoded.Reaction) {
                val store = reactions
                if (store == null) { skip("malformed"); continue }
                val target = messages.byMessageId(payload.target.lowercase()) ?: messages.byMessageId(payload.target)
                if (target == null || target.showsAsDeleted || !target.conversationId.equals(conv, true)) { skip("no_target"); continue }
                store.applyConfirmed(conv, target.messageId!!, g.from, payload.emoji, payload.op, g.serverTs, id, g.clientMsgId)
                imported++
                done += id
                continue
            }
            val kind: String
            val body: String
            var systemJson: String? = null
            var callId: String? = null
            var image: lk.codegen.risime.data.media.ImageEnvelope? = null
            var file: lk.codegen.risime.data.media.FileEnvelope? = null
            var extras = lk.codegen.risime.net.EnvelopeExtras.NONE
            when (payload) {
                is MlsPayload.Decoded.Text -> { kind = MessageEntity.KIND_TEXT; body = payload.body; extras = payload.extras }
                is MlsPayload.Decoded.Image -> {
                    if (images == null) { skip("malformed"); continue }
                    kind = MessageEntity.KIND_IMAGE; body = payload.envelope.caption.orEmpty(); image = payload.envelope; extras = payload.envelope.extras
                }
                // v1.34 §17.7: a `file` by reference (a `parts` file as its placeholder row).
                is MlsPayload.Decoded.File -> {
                    if (images == null && !payload.envelope.partsOnly) { skip("malformed"); continue }
                    kind = MessageEntity.KIND_FILE; body = payload.envelope.caption.orEmpty()
                    systemJson = lk.codegen.risime.data.media.FileMeta.of(payload.envelope).encode()
                    file = payload.envelope.takeIf { !it.partsOnly }; extras = payload.envelope.extras
                }
                is MlsPayload.Decoded.Call -> {
                    val end = payload.env as? lk.codegen.risime.calls.CallEnvelope.End
                    // android R3: call lines only between own devices.
                    if (end == null || !c.own) { skip("malformed"); continue }
                    if (messages.callLine(conv, end.callId) != null) { skip("existing_row"); done += id; continue }
                    kind = MessageEntity.KIND_CALL
                    body = lk.codegen.risime.calls.CallLines.line(end.reason, outgoing, end.durationS, false, end.media == lk.codegen.risime.calls.CallEnvelope.MEDIA_VIDEO).text
                    systemJson = ProtocolJson.encodeToString(JsonObject.serializer(), e.payload)
                    callId = end.callId
                }
                else -> { skip("malformed"); continue }
            }
            // §17.7 step 4 (android R8): a hidden tombstone `me` blocks; `everyone` is re-judged with S = the gap row's sender.
            val arrival = applier?.judgeArrival(conv, id, g.from, g.serverTs) ?: DeleteApplier.Arrival.Store
            if (arrival == DeleteApplier.Arrival.Drop) { skip("tombstone"); done += id; continue }
            if (arrival is DeleteApplier.Arrival.Tombstone) {
                messages.insert(
                    MessageEntity(
                        clientMsgId = g.clientMsgId, messageId = id, conversationId = conv, from = g.from, to = to, body = "", serverTs = g.serverTs,
                        localTs = ts, status = if (outgoing) MessageStatus.SENT.name else MessageStatus.READ.name, outgoing = outgoing,
                        ackedStatus = MessageStatus.READ.name, kind = MessageEntity.KIND_DELETED, deletedBy = arrival.by,
                        deletedByAdmin = arrival.byAdmin, deletedAt = clock(), origin = origin, sharedBy = c.providerUser.lowercase(),
                    ),
                )
                skip("tombstone")
                done += id
                continue
            }
            val row = MessageEntity(
                clientMsgId = g.clientMsgId, messageId = id, conversationId = conv, from = g.from, to = to, body = body,
                serverTs = g.serverTs, localTs = ts, status = if (outgoing) MessageStatus.SENT.name else MessageStatus.READ.name,
                outgoing = outgoing, ackedStatus = MessageStatus.READ.name, kind = kind, systemJson = systemJson,
                blobId = image?.blob?.blobId ?: file?.blob?.blobId, callId = callId, origin = origin, sharedBy = c.providerUser.lowercase(),
                fromDevice = g.fromDevice ?: e.fromDevice?.lowercase(),
                forwardHops = extras.forwardHops, replyToMessageId = extras.replyTo?.messageId, replyToFrom = extras.replyTo?.from,
                viewOnce = if (extras.viewOnce) true else null,
            )
            if (messages.insert(row) == -1L) { skip("existing_row"); done += id; continue }
            if (image != null) {
                images?.stored(row, image)
                anyImage = true
            }
            if (file != null) {
                images?.storedFile(row, file)
                anyImage = true
            }
            imported++
            oldest = minOf(oldest ?: ts, ts)
            done += id
        }
        if (done.isNotEmpty()) dao.deleteGaps(done)
        if (skipped.isNotEmpty()) log("history import ${c.requestId} part ${c.part}: $imported imported, skipped $skipped")
        return ImportResult(imported, skipped, oldest, anyImage)
    }

    /**
     * §17.12 the marker exception (android R5): `sys:history-shared` just before the oldest imported
     * row (moves only earlier; the text names the latest provider); the gap marker goes when no gap
     * rows remain, else moves to the newest remaining gap row (it may move earlier).
     */
    suspend fun updateMarkers(conv: String, oldestImported: Long?, own: Boolean, providerName: String, residual: Boolean = false) {
        if (oldestImported != null) {
            val id = HistoryMarkers.sharedId(conv)
            val prev = messages.byClientMsgId(id)
            val at = minOf(prev?.localTs ?: Long.MAX_VALUE, oldestImported - 1)
            val text = if (own) SystemLine.HISTORY_RESTORED_TEXT else SystemLine.historySharedText(providerName)
            if (prev != null) messages.delete(id)
            messages.insert(HistoryMarkers.line(id, conv, SystemLine.HISTORY_SHARED, text, at))
        }
        refreshGapMarker(conv, residual)
    }

    suspend fun refreshGapMarker(conv: String, residual: Boolean) {
        val id = HistoryMarkers.historyId(conv)
        val prev = messages.byClientMsgId(id) ?: return
        val left = dao.gaps(conv)
        messages.delete(id)
        val newest = left.mapNotNull { ms(it.serverTs) }.maxOrNull() ?: return
        val text = if (residual) SystemLine.HISTORY_GAP_RESIDUAL_TEXT else SystemLine.HISTORY_GAP_SOME_TEXT
        messages.insert(HistoryMarkers.line(id, conv, SystemLine.HISTORY_GAP, text, newest + 1).copy(serverTs = left.maxBy { ms(it.serverTs) ?: 0 }.serverTs))
    }
}
