package lk.codegen.risime.data.mls

import lk.codegen.risime.data.db.MlsPendingDao
import lk.codegen.risime.data.db.MlsPendingEntity
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.MessageData
import lk.codegen.risime.net.MlsMembershipEvent
import lk.codegen.risime.net.ProtocolJson
import java.util.Base64

/** What one inbox event meant for MLS (contract §10.3, decision 033). */
sealed interface MlsResult {
    /** A decrypted e2ee message: insert it like a plaintext one (same transaction). */
    data class Plaintext(val message: MessageData, val body: String) : MlsResult

    /** §11.2: a decrypted reaction (effective state by server_ts + message_id). */
    data class Reaction(val message: MessageData, val target: String, val emoji: String, val op: String) : MlsResult

    /**
     * Group state changed (commit applied, welcome joined): replay this conversation's pending
     * events. [joined] = the Welcome's (generation, epoch) for §13.3 rule 2; [extra] = results the
     * change produced besides (a BeforeInstall for discarded older-generation rows).
     */
    data class GroupChanged(val conversationId: String, val joined: GroupRef? = null, val extra: List<MlsResult> = emptyList()) : MlsResult

    /**
     * §13.3: an e2ee message encrypted before this device (or its current MLS state) existed. Not
     * parked, not decrypted, not stored, not acked: the chat shows one "Earlier messages…" marker.
     */
    data class BeforeInstall(val conversationId: String, val serverTs: String?) : MlsResult

    /** Ahead of the local epoch/generation, or no group yet: kept in mls_pending. */
    data object Pending : MlsResult

    /** Not for this device, already applied, or our own: nothing to do. */
    data object Ignored : MlsResult

    /**
     * Undecryptable or failed verification: dropped (and logged). With a [conversationId] the chat
     * shows the §13.3 "Some messages couldn't be decrypted" line (never a silent drop).
     */
    data class Dropped(val reason: String, val conversationId: String? = null, val serverTs: String? = null) : MlsResult

    /**
     * §12.8 (groups only): this device's group state can't follow any more (the core rejected a
     * commit, or a referenced blob is gone). Never for a transient error. The app rejoins.
     */
    data class Unrecoverable(val conversationId: String, val reason: String) : MlsResult
}

/** §10.3 `mls_membership`: who commits the add/remove, and when. */
data class MembershipAction(val event: MlsMembershipEvent, val delayMs: Long)

/**
 * The named-committer rule: a device of the same user commits at once; everyone else waits a
 * random 5–30 s and acts only if no commit has landed meanwhile. A device's own change: nothing.
 */
fun membershipAction(e: MlsMembershipEvent, myUserId: String, myDeviceId: String, random: (IntRange) -> Int): MembershipAction? = when {
    e.userId.equals(myUserId, true) && e.deviceId.equals(myDeviceId, true) -> null
    e.userId.equals(myUserId, true) -> MembershipAction(e, 0)
    else -> MembershipAction(e, random(5_000..30_000).toLong())
}

/**
 * Applies e2ee inbox events in strict per-group order. Every call runs inside the caller's
 * transaction together with the message insert, seen event and cursor (ChatEngine), so pending
 * rows and MLS state commit atomically with the cursor.
 */
class MlsPipeline(
    private val engine: () -> MlsEngine?,
    private val pending: MlsPendingDao,
    private val seq: () -> Long = System::nanoTime,
    private val onMembership: (MembershipAction) -> Unit = {},
    private val random: (IntRange) -> Int = { it.random() },
    private val log: (String) -> Unit = {},
    /** After joining from a Welcome (a key package was used): top up. */
    private val onJoined: () -> Unit = {},
    /** §12.8: a `grp:` event was parked ahead of the local epoch: fetch the missing commits (no limit beyond the rate limit). */
    private val onParkedAhead: (conversationId: String) -> Unit = {},
) {
    private val b64 = Base64.getDecoder()

    private suspend fun park(e: Event, conv: String, generation: Long, epoch: Long): MlsResult {
        pending.add(MlsPendingEntity(e.eventId, conv, generation, epoch, seq(), ProtocolJson.encodeToString(Event.serializer(), e)))
        if (lk.codegen.risime.net.isGroupConversation(conv)) {
            val g = engine()?.group(conv)
            if (g != null && g.generation == generation && epoch > g.epoch) onParkedAhead(conv)
        }
        return MlsResult.Pending
    }

    private fun tsKey(ts: String): Long = runCatching { java.time.Instant.parse(ts).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)

    private fun unrecoverableOr(conv: String, reason: String): MlsResult =
        if (lk.codegen.risime.net.isGroupConversation(conv)) MlsResult.Unrecoverable(conv, reason) else MlsResult.Dropped(reason)

    /**
     * An event first seen from the inbox. [historyBefore] (§13.2) enables rule 1, only where the
     * message would otherwise be parked or dropped.
     */
    suspend fun apply(e: Event, historyBefore: java.time.Instant? = null): MlsResult = applyInner(e, historyBefore, null)

    private fun beforeHistory(ts: String?, historyBefore: java.time.Instant?): Boolean {
        if (historyBefore == null || ts == null) return false
        val t = runCatching { java.time.Instant.parse(ts) }.getOrNull() ?: return false
        return t.isBefore(historyBefore)
    }

    private suspend fun applyInner(e: Event, historyBefore: java.time.Instant?, joined: GroupRef?): MlsResult {
        val mls = engine() ?: return MlsResult.Ignored // no MLS core: behave like a v1.6 app
        e.mlsWelcome()?.let { w ->
            if (w.toDevices.none { it.equals(mls.deviceId, true) }) return MlsResult.Ignored
            val current = mls.group(w.conversationId)
            if (current != null && current.generation >= w.generation && current.epoch >= w.epoch) return MlsResult.Ignored
            // §12.6: a referenced Welcome is fetched and inlined before this call; a missing one is unrecoverable.
            val welcome = w.welcome ?: return unrecoverableOr(w.conversationId, "welcome blob not fetched")
            return try {
                val ref = mls.joinFromWelcome(w.conversationId, w.generation, b64.decode(welcome))
                // §13.3: parked messages of an older generation are pre-install (one marker), never a silent drop.
                val older = pending.olderGenerations(w.conversationId, w.generation).mapNotNull { p ->
                    runCatching { ProtocolJson.decodeFromString(Event.serializer(), p.eventJson).messageData() }.getOrNull()?.takeIf { it.encrypted }
                }
                pending.dropOlderGenerations(w.conversationId, w.generation)
                onJoined()
                val extra = if (older.isEmpty()) emptyList() else listOf(MlsResult.BeforeInstall(w.conversationId, older.maxByOrNull { tsKey(it.serverTs) }?.serverTs))
                MlsResult.GroupChanged(w.conversationId, GroupRef(w.conversationId, w.generation, maxOf(w.epoch, ref.epoch)), extra)
            } catch (t: Exception) {
                log("welcome rejected: ${t.message}")
                MlsResult.Dropped("welcome: ${t.message}", w.conversationId)
            }
        }
        e.mlsCommit()?.let { c ->
            // DMs: merged on our own 200. Groups: an own commit still at our epoch means the process died
            // between the 200 and the merge (or the event beat the reply); the core merges it from the log.
            if (c.fromDevice.equals(mls.deviceId, true) && !lk.codegen.risime.net.isGroupConversation(c.conversationId)) return MlsResult.Ignored
            val g = mls.group(c.conversationId) ?: return park(e, c.conversationId, c.generation, c.epoch)
            return when {
                c.generation < g.generation -> MlsResult.Ignored
                c.generation > g.generation -> park(e, c.conversationId, c.generation, c.epoch)
                c.epoch < g.epoch -> MlsResult.Ignored // already applied, or below our Welcome's epoch
                c.epoch > g.epoch -> park(e, c.conversationId, c.generation, c.epoch) // missing commits first
                c.commit == null -> unrecoverableOr(c.conversationId, "commit blob not fetched")
                else -> when (val r = mls.processCommit(c.conversationId, c.generation, b64.decode(c.commit))) {
                    is CommitOutcome.Applied -> MlsResult.GroupChanged(c.conversationId)
                    CommitOutcome.RemovedSelf -> {
                        mls.deleteGroup(c.conversationId)
                        MlsResult.GroupChanged(c.conversationId)
                    }
                    is CommitOutcome.Rejected -> {
                        log("commit rejected: ${r.reason}")
                        unrecoverableOr(c.conversationId, r.reason)
                    }
                }
            }
        }
        e.mlsMembership()?.let { m ->
            membershipAction(m, mls.userId, mls.deviceId, random)?.let(onMembership)
            return MlsResult.Ignored
        }
        val msg = e.messageData()?.takeIf { it.encrypted } ?: return MlsResult.Ignored
        if (msg.fromDevice.equals(mls.deviceId, true)) return MlsResult.Ignored // our own send (already in the outbox row)
        val conv = msg.conversationId
        fun undecryptable(reason: String) = MlsResult.Dropped(reason, conv, msg.serverTs)
        val beforeInstall = MlsResult.BeforeInstall(conv, msg.serverTs)
        // Rule 1 (§13.3): only where the message would otherwise be parked or dropped; never on replayed rows (null there).
        val rule1 = beforeHistory(msg.serverTs, historyBefore)
        val gen = msg.generation ?: return undecryptable("no generation")
        val epoch = msg.epoch ?: return undecryptable("no epoch")
        // Rule 2: below the epoch this device joined at from the Welcome being replayed.
        if (joined != null && gen == joined.generation && epoch < joined.epoch) return beforeInstall
        val g = mls.group(conv) ?: return if (rule1) beforeInstall else park(e, conv, gen, epoch)
        if (gen < g.generation) return if (rule1) beforeInstall else undecryptable("stale generation")
        if (gen > g.generation) return if (rule1) beforeInstall else park(e, conv, gen, epoch)
        if (epoch > g.epoch) return park(e, conv, gen, epoch)
        return try {
            val d = mls.decrypt(msg.conversationId, gen, b64.decode(msg.ciphertext))
            // §10.3: the authenticated sender must be the event's from/from_device.
            if (!d.sender.userId.equals(msg.from, true) || !d.sender.deviceId.equals(msg.fromDevice ?: "", true)) {
                log("sender mismatch on ${msg.messageId}")
                undecryptable("sender mismatch")
            } else {
                when (val p = MlsPayload.decode(d.plaintext)) {
                    is MlsPayload.Decoded.Text -> MlsResult.Plaintext(msg, p.body)
                    is MlsPayload.Decoded.Reaction -> MlsResult.Reaction(msg, p.target, p.emoji, p.op)
                    is MlsPayload.Decoded.Ignored -> {
                        log("ignored payload type ${p.type} in ${msg.messageId}")
                        MlsResult.Ignored
                    }
                }
            }
        } catch (ex: MlsDecryptException) {
            if (rule1) return beforeInstall
            log("undecryptable ${msg.messageId}: ${ex.message}")
            undecryptable("decrypt: ${ex.message}")
        }
    }

    /**
     * After a group change: re-apply this conversation's pending events in arrival order. Returns
     * the results of the ones that are no longer pending (they're removed from mls_pending).
     */
    suspend fun replay(conversationId: String, joined: GroupRef? = null): List<MlsResult> {
        val out = mutableListOf<MlsResult>()
        var progressed = true
        while (progressed) {
            progressed = false
            for (p in pending.forConversation(conversationId)) {
                val ev = ProtocolJson.decodeFromString(Event.serializer(), p.eventJson)
                pending.remove(p.eventId)
                // Parked rows are never re-judged by rule 1 (no historyBefore); rule 2 applies after a Welcome.
                val r = applyInner(ev, null, joined)
                if (r == MlsResult.Pending) continue // apply() parked it again
                out += r
                if (r is MlsResult.GroupChanged) {
                    progressed = true
                    break
                }
            }
        }
        return out
    }
}
