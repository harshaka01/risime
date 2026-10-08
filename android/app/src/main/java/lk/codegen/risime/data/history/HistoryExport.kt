package lk.codegen.risime.data.history

import kotlinx.serialization.json.JsonObject
import lk.codegen.risime.data.HistoryMarkers
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.DeleteDao
import lk.codegen.risime.data.db.HistoryDao
import lk.codegen.risime.data.db.MediaDao
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.deletes.TimeUuid
import lk.codegen.risime.data.groups.SystemLine
import lk.codegen.risime.data.media.ImageEnvelope
import lk.codegen.risime.data.media.MediaSealer
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.net.GroupEvent
import lk.codegen.risime.net.HistoryBundleEntry
import lk.codegen.risime.net.HistoryBundleHeader
import lk.codegen.risime.net.HistoryRange
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.isGroupConversation

/** One bundle entry: a message (with its reactions after it) as JSON lines, sorted by server time. */
class ExportUnit(val messageId: String, val ts: Long, val lines: List<ByteArray>) {
    val bytes: Int get() = lines.sumOf { it.size }
    val entries: Int get() = lines.size
}

/** A closed time window in epoch ms. */
data class Window(val from: Long, val to: Long)

/** What the provider exports for one request (§17.7, android R2/R3/R4). */
class ExportRequest(
    val requestId: String,
    val conversationId: String,
    val requesterUser: String,
    /** Same user (own device): call lines go too. */
    val own: Boolean,
    val range: HistoryRange,
    /** The event's `intervals` (the server's view, already clipped to the range). */
    val intervals: List<HistoryRange>,
)

object HistoryExport {
    /** A local membership line's clock may lag the server's by a moment; the server's intervals bound it anyway. */
    const val LOCAL_VIEW_SLACK_MS = 2_000L

    private fun ms(ts: String?): Long? = HistoryMarkers.epochMs(ts)

    /** Intersection of two sorted window lists. */
    fun intersect(a: List<Window>, b: List<Window>): List<Window> {
        val out = mutableListOf<Window>()
        for (x in a) for (y in b) {
            val f = maxOf(x.from, y.from)
            val t = minOf(x.to, y.to)
            if (f <= t) out += Window(f, t)
        }
        return out.sortedBy { it.from }
    }

    /**
     * android R2: the provider's local view of the requester's membership, from this device's
     * group lines. A requester who was already a member when this device joined starts at -∞
     * (nothing earlier is held anyway); a later `removed`/`left` line ends an interval; an `added`
     * line starts one (several for rejoins). A DM: everything.
     */
    fun localView(conversationId: String, requester: String, systemRows: List<MessageEntity>): List<Window> {
        if (!isGroupConversation(conversationId)) return listOf(Window(Long.MIN_VALUE, Long.MAX_VALUE))
        val lines = systemRows.mapNotNull { r -> SystemLine.decode(r.systemJson)?.let { (ms(r.serverTs) ?: r.localTs) to it } }.sortedBy { it.first }
        fun hits(l: SystemLine) = l.targets.any { it.equals(requester, true) }
        val relevant = lines.filter { (_, l) ->
            when (l.action) {
                GroupEvent.CREATED -> l.actor.equals(requester, true) || hits(l)
                GroupEvent.ADDED, GroupEvent.REMOVED -> hits(l)
                GroupEvent.LEFT -> (l.targets.firstOrNull() ?: l.actor).equals(requester, true)
                else -> false
            }
        }
        val out = mutableListOf<Window>()
        // Inside from the start unless the first thing seen is the requester joining.
        val first = relevant.firstOrNull()?.second?.action
        var start: Long? = if (first == GroupEvent.ADDED || first == GroupEvent.CREATED) null else Long.MIN_VALUE
        for ((t, l) in relevant) {
            when (l.action) {
                GroupEvent.CREATED, GroupEvent.ADDED -> if (start == null) start = t - LOCAL_VIEW_SLACK_MS
                GroupEvent.REMOVED, GroupEvent.LEFT -> start?.let { s -> out += Window(s, t + LOCAL_VIEW_SLACK_MS); start = null }
            }
        }
        start?.let { out += Window(it, Long.MAX_VALUE) }
        return out
    }

    /** range ∩ the event's intervals ∩ the local view (the narrower wins). */
    fun windows(req: ExportRequest, local: List<Window>): List<Window> {
        val r = Window(ms(req.range.from) ?: return emptyList(), ms(req.range.to) ?: return emptyList())
        val server = req.intervals.mapNotNull { i -> Window(ms(i.from) ?: return@mapNotNull null, ms(i.to) ?: return@mapNotNull null) }
        return intersect(intersect(listOf(r), server), local)
    }

    private fun inside(ws: List<Window>, t: Long) = ws.any { t >= it.from && t <= it.to }

    private fun line(e: HistoryBundleEntry): ByteArray = (ProtocolJson.encodeToString(HistoryBundleEntry.serializer(), e) + "\n").toByteArray(Charsets.UTF_8)

    private fun json(bytes: ByteArray): JsonObject = ProtocolJson.parseToJsonElement(bytes.decodeToString()) as JsonObject

    /**
     * The export selection (android R2, R3), newest first. Only text/image (and, for own shares,
     * call) rows with a message id, not deleted / being deleted / unverified / hidden-tombstoned /
     * outbox, after `cleared_upto` by TimeUUID time, inside the windows; images by reference
     * (reconstructed and validated like a received envelope); reactions after their target.
     */
    suspend fun select(
        req: ExportRequest,
        dao: HistoryDao,
        deletes: DeleteDao?,
        media: MediaDao?,
        sealer: MediaSealer?,
    ): List<ExportUnit> {
        val conv = req.conversationId
        val ws = windows(req, localView(conv, req.requesterUser, dao.systemRows(conv)))
        if (ws.isEmpty()) return emptyList()
        val cleared = deletes?.chatState(conv)?.clearedUpto
        val reactions = dao.confirmedReactions(conv).groupBy { it.targetMessageId.lowercase() }
        val out = mutableListOf<ExportUnit>()
        for (m in dao.exportCandidates(conv)) {
            val id = m.messageId?.lowercase() ?: continue
            if (m.deleted || m.system || m.deleteState != null || m.deleteUnverified) continue
            if (m.status == MessageStatus.PENDING.name || m.status == MessageStatus.FAILED.name) continue
            if (m.call && !req.own) continue // android R3: a call line is from one user's perspective
            val ticks = TimeUuid.ticks(id)
            if (cleared != null && (ticks == null || ticks <= cleared)) continue
            if (deletes?.deletedId(id) != null) continue
            val ts = ms(m.serverTs) ?: continue
            if (!inside(ws, ts)) continue
            val payload: JsonObject = when {
                m.image -> {
                    val row = media?.get(m.clientMsgId) ?: continue
                    val s = sealer ?: continue
                    val blobId = row.blobId ?: continue
                    val enc = runCatching { s.openEnc(m.clientMsgId, row.sealedEnc) }.getOrNull() ?: continue
                    val thumb = row.sealedThumb?.let { t -> runCatching { s.openThumb(m.clientMsgId, t) }.getOrNull() }
                    val env = ImageEnvelope(lk.codegen.risime.net.BlobRef(blobId, row.blobSize, row.blobSha256), enc, row.mime, row.w, row.h, thumb, m.body.takeIf { it.isNotEmpty() })
                    val obj = json(env.encode())
                    ImageEnvelope.validate(obj) ?: continue // §14.4 before export; expired images still go
                    obj
                }
                m.call -> {
                    val obj = runCatching { lk.codegen.risime.calls.CallRecords.strip(ProtocolJson.parseToJsonElement(m.systemJson ?: "") as JsonObject) }.getOrNull() ?: continue
                    if (lk.codegen.risime.calls.CallEnvelope.decode(obj.toString().toByteArray()) !is lk.codegen.risime.calls.CallEnvelope.End) continue
                    obj
                }
                else -> json(MlsPayload.text(m.body))
            }
            val lines = mutableListOf(line(HistoryBundleEntry(id, m.clientMsgId, m.from.lowercase(), m.fromDevice, m.serverTs!!, payload)))
            for (r in reactions[id].orEmpty().sortedBy { ms(it.confirmedTs) ?: 0 }) {
                val rid = r.confirmedMessageId?.lowercase() ?: continue
                val cid = r.confirmedClientMsgId ?: continue // reactions confirmed before v1.15: no second match key
                val rts = r.confirmedTs ?: continue
                val op = r.confirmedOp ?: continue
                if (cleared != null && (TimeUuid.ticks(rid) ?: Long.MIN_VALUE) <= cleared) continue
                lines += line(HistoryBundleEntry(rid, cid, r.reactorUserId.lowercase(), null, rts, json(MlsPayload.reaction(id, r.emoji, op))))
            }
            out += ExportUnit(id, ts, lines)
        }
        return out.sortedWith(compareByDescending<ExportUnit> { it.ts }.thenByDescending { it.messageId })
    }

    /** The JSON header line of one part. */
    fun header(requestId: String, conversationId: String, provider: String, part: Int, parts: Int, count: Int): ByteArray =
        (ProtocolJson.encodeToString(HistoryBundleHeader.serializer(), HistoryBundleHeader(1, HistoryBundleHeader.TYPE, requestId, conversationId, provider, part, parts, count)) + "\n")
            .toByteArray(Charsets.UTF_8)

    /** Room for the header line inside the plaintext bound. */
    const val HEADER_RESERVE = 1_024

    /**
     * android R4: units newest first fill part 1, then part 2 (older), …; a part ends at
     * [maxEntries] entries or [maxBytes] of plaintext; the [maxParts] cap cuts the **oldest** end.
     * Returns the parts in delivery order (part 1 = newest), each oldest first. A unit larger than a
     * whole part is skipped.
     */
    fun parts(
        newestFirst: List<ExportUnit>,
        maxParts: Int = HistoryLimits.MAX_PARTS,
        maxEntries: Int = HistoryLimits.MAX_ENTRIES,
        maxBytes: Long = HistoryLimits.MAX_PLAIN,
    ): List<List<ExportUnit>> {
        val budget = maxBytes - HEADER_RESERVE
        val out = mutableListOf<MutableList<ExportUnit>>()
        var cur = mutableListOf<ExportUnit>()
        var bytes = 0L
        var entries = 0
        for (u in newestFirst) {
            if (u.bytes > budget || u.entries > maxEntries) continue
            if (cur.isNotEmpty() && (bytes + u.bytes > budget || entries + u.entries > maxEntries)) {
                out += cur
                if (out.size == maxParts) return out.map { it.asReversed().toList() }
                cur = mutableListOf()
                bytes = 0
                entries = 0
            }
            cur += u
            bytes += u.bytes
            entries += u.entries
        }
        if (cur.isNotEmpty()) out += cur
        return out.map { it.asReversed().toList() }
    }

    /** One part's plaintext: the header line, then every entry line (oldest first). */
    fun plaintext(requestId: String, conversationId: String, provider: String, part: Int, parts: Int, units: List<ExportUnit>): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        bos.write(header(requestId, conversationId, provider, part, parts, units.sumOf { it.entries }))
        units.forEach { u -> u.lines.forEach(bos::write) }
        return bos.toByteArray()
    }
}
