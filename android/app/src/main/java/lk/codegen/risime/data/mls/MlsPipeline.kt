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

    /** Group state changed (commit applied, welcome joined): replay this conversation's pending events. */
    data class GroupChanged(val conversationId: String) : MlsResult

    /** Ahead of the local epoch/generation, or no group yet: kept in mls_pending. */
    data object Pending : MlsResult

    /** Not for this device, already applied, or our own: nothing to do. */
    data object Ignored : MlsResult

    /** Undecryptable or failed verification: dropped (and logged). */
    data class Dropped(val reason: String) : MlsResult
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
) {
    private val b64 = Base64.getDecoder()

    private suspend fun park(e: Event, conv: String, generation: Long, epoch: Long): MlsResult {
        pending.add(MlsPendingEntity(e.eventId, conv, generation, epoch, seq(), ProtocolJson.encodeToString(Event.serializer(), e)))
        return MlsResult.Pending
    }

    suspend fun apply(e: Event): MlsResult {
        val mls = engine() ?: return MlsResult.Ignored // no MLS core: behave like a v1.6 app
        e.mlsWelcome()?.let { w ->
            if (w.toDevices.none { it.equals(mls.deviceId, true) }) return MlsResult.Ignored
            val current = mls.group(w.conversationId)
            if (current != null && current.generation >= w.generation && current.epoch >= w.epoch) return MlsResult.Ignored
            return try {
                mls.joinFromWelcome(w.conversationId, w.generation, b64.decode(w.welcome))
                pending.dropOlderGenerations(w.conversationId, w.generation)
                onJoined()
                MlsResult.GroupChanged(w.conversationId)
            } catch (t: Exception) {
                log("welcome rejected: ${t.message}")
                MlsResult.Dropped("welcome: ${t.message}")
            }
        }
        e.mlsCommit()?.let { c ->
            if (c.fromDevice.equals(mls.deviceId, true)) return MlsResult.Ignored // merged on our own 200
            val g = mls.group(c.conversationId) ?: return park(e, c.conversationId, c.generation, c.epoch)
            return when {
                c.generation < g.generation -> MlsResult.Ignored
                c.generation > g.generation -> park(e, c.conversationId, c.generation, c.epoch)
                c.epoch < g.epoch -> MlsResult.Ignored // already applied, or below our Welcome's epoch
                c.epoch > g.epoch -> park(e, c.conversationId, c.generation, c.epoch) // missing commits first
                else -> when (val r = mls.processCommit(c.conversationId, c.generation, b64.decode(c.commit))) {
                    is CommitOutcome.Applied -> MlsResult.GroupChanged(c.conversationId)
                    CommitOutcome.RemovedSelf -> {
                        mls.deleteGroup(c.conversationId)
                        MlsResult.GroupChanged(c.conversationId)
                    }
                    is CommitOutcome.Rejected -> {
                        log("commit rejected: ${r.reason}")
                        MlsResult.Dropped(r.reason)
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
        val gen = msg.generation ?: return MlsResult.Dropped("no generation")
        val epoch = msg.epoch ?: return MlsResult.Dropped("no epoch")
        val g = mls.group(msg.conversationId) ?: return park(e, msg.conversationId, gen, epoch)
        if (gen < g.generation) return MlsResult.Dropped("stale generation")
        if (gen > g.generation || epoch > g.epoch) return park(e, msg.conversationId, gen, epoch)
        return try {
            val d = mls.decrypt(msg.conversationId, gen, b64.decode(msg.ciphertext))
            // §10.3: the authenticated sender must be the event's from/from_device.
            if (!d.sender.userId.equals(msg.from, true) || !d.sender.deviceId.equals(msg.fromDevice ?: "", true)) {
                log("sender mismatch on ${msg.messageId}")
                MlsResult.Dropped("sender mismatch")
            } else {
                when (val p = MlsPayload.decode(d.plaintext)) {
                    is MlsPayload.Decoded.Text -> MlsResult.Plaintext(msg, p.body)
                    is MlsPayload.Decoded.Ignored -> {
                        log("ignored payload type ${p.type} in ${msg.messageId}")
                        MlsResult.Ignored
                    }
                }
            }
        } catch (ex: MlsDecryptException) {
            log("undecryptable ${msg.messageId}: ${ex.message}")
            MlsResult.Dropped("decrypt: ${ex.message}")
        }
    }

    /**
     * After a group change: re-apply this conversation's pending events in arrival order. Returns
     * the results of the ones that are no longer pending (they're removed from mls_pending).
     */
    suspend fun replay(conversationId: String): List<MlsResult> {
        val out = mutableListOf<MlsResult>()
        var progressed = true
        while (progressed) {
            progressed = false
            for (p in pending.forConversation(conversationId)) {
                val ev = ProtocolJson.decodeFromString(Event.serializer(), p.eventJson)
                pending.remove(p.eventId)
                val r = apply(ev)
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
