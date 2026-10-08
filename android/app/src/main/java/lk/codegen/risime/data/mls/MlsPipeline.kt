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

    /** §14.4: a decrypted, validated image envelope (stored with its thumbnail in the same transaction). */
    data class Image(val message: MessageData, val envelope: lk.codegen.risime.data.media.ImageEnvelope) : MlsResult

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
    data class BeforeInstall(val conversationId: String, val serverTs: String?, val gaps: List<GapInfo> = emptyList()) : MlsResult

    /**
     * §15.4: an authenticated `delete` control: the deleter's attested user id, the core's
     * `sender_is_admin` at the control's epoch, and a verified binding (AAD = envelope = event targets).
     */
    data class Delete(val event: lk.codegen.risime.net.DeleteEvent, val deleter: String, val senderIsAdmin: Boolean?) : MlsResult

    /** §15.6: this device's own `delete` control (OwnEcho: not decryptable here); completes the outbox. */
    data class OwnDelete(val event: lk.codegen.risime.net.DeleteEvent) : MlsResult

    /** §15.4: the core said `Malformed` (no admin record for that epoch, > 3 epochs back): shown as a note on the targets. */
    data class DeleteUnverified(val event: lk.codegen.risime.net.DeleteEvent, val reason: String) : MlsResult

    /** §15.6 (android R5): a control that is pre-install, undecryptable or fails its checks: logged only, never a line or tombstone. */
    data class ControlDropped(val reason: String) : MlsResult

    /** §16.3 a decrypted, validated and bound ephemeral call envelope (applied by the call machine after the commit). */
    data class CallSignal(val event: lk.codegen.risime.net.CallSignalEvent, val env: lk.codegen.risime.calls.CallEnvelope.Env) : MlsResult

    /** §16.2 a durable `call_end` (a message event): the call-history line. */
    data class CallEnd(val message: MessageData, val env: lk.codegen.risime.calls.CallEnvelope.End) : MlsResult

    /**
     * §17.7 a decrypted, verified `history_request` for this device: the envelope (with `rpk`), the
     * MLS sender and own/member with its leaf signature key from the core (never the event's `consent`).
     */
    data class HistoryRequest(val event: lk.codegen.risime.net.HistoryRequestEvent, val env: lk.codegen.risime.data.history.HistoryRequestEnvelope, val sender: HistorySenderInfo) : MlsResult

    /** §17.4 a `history_request` this device can't decrypt (too old an epoch, a lost generation): answer `unable`/`stale`. */
    data class HistoryRequestStale(val event: lk.codegen.risime.net.HistoryRequestEvent, val reason: String) : MlsResult

    /** §17.6 a decrypted, verified `history_share` (one part) for this device. */
    data class HistoryShare(val event: lk.codegen.risime.net.HistoryShareEvent, val env: lk.codegen.risime.data.history.HistoryShareEnvelope, val sender: DeviceRef) : MlsResult

    /**
     * §18.1/§18.2 a decrypted, validated `profile_photo`: [subject] is the MLS sender's user (the
     * verified leaf), never a JSON field. Not a message: no row, unread, ack or notification.
     */
    data class ProfilePhoto(val message: MessageData, val subject: String, val env: lk.codegen.risime.data.profile.ProfilePhotoEnvelope) : MlsResult

    /** Ahead of the local epoch/generation, or no group yet: kept in mls_pending. */
    data object Pending : MlsResult

    /** Not for this device, already applied, or our own: nothing to do. */
    data object Ignored : MlsResult

    /**
     * Undecryptable or failed verification: dropped (and logged). With a [conversationId] the chat
     * shows the §13.3 "Some messages couldn't be decrypted" line (never a silent drop).
     */
    data class Dropped(val reason: String, val conversationId: String? = null, val serverTs: String? = null, val gaps: List<GapInfo> = emptyList()) : MlsResult

    /**
     * §12.8 (groups only): this device's group state can't follow any more (the core rejected a
     * commit, or a referenced blob is gone). Never for a transient error. The app rejoins.
     */
    data class Unrecoverable(val conversationId: String, val reason: String) : MlsResult
}

/**
 * §17.2 the content-free metadata of one pre-install (or rejoin/reset-lost) e2ee `message` event:
 * what the gap index records. Never content.
 */
data class GapInfo(
    val conversationId: String,
    val messageId: String,
    val clientMsgId: String,
    val from: String,
    val fromDevice: String?,
    val serverTs: String,
    val generation: Long?,
    val epoch: Long?,
) {
    companion object {
        fun of(m: MessageData) = GapInfo(m.conversationId, m.messageId.lowercase(), m.clientMsgId, m.from, m.fromDevice, m.serverTs, m.generation, m.epoch)
    }
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
    /** v1.16 `mls_dm_op` naming this device: run it after the transaction (MembershipExecutor.executeOp). */
    private val onDmOp: (lk.codegen.risime.net.MlsDmOpEvent) -> Unit = {},
) {
    private val b64 = Base64.getDecoder()

    private suspend fun park(e: Event, conv: String, generation: Long, epoch: Long): MlsResult {
        pending.add(MlsPendingEntity(e.eventId, conv, generation, epoch, seq(), ProtocolJson.encodeToString(Event.serializer(), e)))
        // §12.8 groups, §16.3/android R7 DMs too: parked ahead of the local epoch → fetch the commits now.
        val g = engine()?.group(conv)
        if (g != null && g.generation == generation && epoch > g.epoch) onParkedAhead(conv)
        return MlsResult.Pending
    }

    /** The time of a version-1 (TimeUUID) event id; null for anything else. */
    private fun eventTime(eventId: String): java.time.Instant? = runCatching {
        val u = java.util.UUID.fromString(eventId)
        if (u.version() != 1) return null
        // 100 ns intervals since 1582-10-15.
        val ms = (u.timestamp() - 0x01B21DD213814000L) / 10_000
        java.time.Instant.ofEpochMilli(ms)
    }.getOrNull()

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
                // §17.2: each discarded older-generation message gets a gap row (never own-device echoes).
                val gaps = older.filter { !it.fromDevice.equals(mls.deviceId, true) }.map(GapInfo::of)
                val extra = if (older.isEmpty()) emptyList() else listOf(MlsResult.BeforeInstall(w.conversationId, older.maxByOrNull { tsKey(it.serverTs) }?.serverTs, gaps))
                MlsResult.GroupChanged(w.conversationId, GroupRef(w.conversationId, w.generation, maxOf(w.epoch, ref.epoch)), extra)
            } catch (t: Exception) {
                // §13.3: a Welcome stored before history_before was made for an earlier MLS state of this
                // device id (logout and login keep the id): expected, not a decrypt failure. Its time is the
                // event_id's TimeUUID (Welcome events carry no server_ts).
                val at = eventTime(e.eventId)
                if (historyBefore != null && at != null && at.isBefore(historyBefore)) {
                    log("welcome from before this device's MLS state ignored: ${t.message}")
                    return MlsResult.Ignored
                }
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
        e.mlsDmOp()?.let { o ->
            val c = o.op.committer
            if (c != null && c.deviceId.equals(mls.deviceId, true) && c.userId.equals(mls.userId, true)) onDmOp(o)
            return MlsResult.Ignored
        }
        e.deleteData()?.takeIf { it.encrypted }?.let { d -> return applyDelete(mls, e, d, historyBefore, joined) }
        if (e.kind == Event.KIND_CALL_SIGNAL) {
            val c = runCatching { e.callSignal() }.getOrNull() ?: return MlsResult.ControlDropped("call_signal: undecodable")
            return applyCallSignal(mls, e, c, historyBefore, joined)
        }
        if (e.kind == Event.KIND_HISTORY_REQUEST) {
            val r = runCatching { e.historyRequest() }.getOrNull() ?: return MlsResult.ControlDropped("history_request: undecodable")
            return applyHistoryRequest(mls, e, r, historyBefore, joined)
        }
        if (e.kind == Event.KIND_HISTORY_SHARE) {
            val r = runCatching { e.historyShare() }.getOrNull() ?: return MlsResult.ControlDropped("history_share: undecodable")
            return applyHistoryShare(mls, e, r, historyBefore, joined)
        }
        val msg = e.messageData()?.takeIf { it.encrypted } ?: return MlsResult.Ignored
        if (msg.fromDevice.equals(mls.deviceId, true)) return MlsResult.Ignored // our own send (already in the outbox row)
        val conv = msg.conversationId
        // §18.4 (android A3): a `silent` control (a profile photo) that is pre-install or can't be
        // decrypted leaves no trace: no marker, no gap row, not counted in a history request.
        fun undecryptable(reason: String): MlsResult =
            if (msg.silent) MlsResult.ControlDropped("silent: $reason") else MlsResult.Dropped(reason, conv, msg.serverTs)
        // §17.2: every pre-install message gets a content-free gap row (the request range, the import's match keys).
        val gap = listOf(GapInfo.of(msg))
        val beforeInstall: MlsResult = if (msg.silent) MlsResult.ControlDropped("silent: pre-install") else MlsResult.BeforeInstall(conv, msg.serverTs, gap)
        // Rule 1 (§13.3): only where the message would otherwise be parked or dropped; never on replayed rows (null there).
        val rule1 = beforeHistory(msg.serverTs, historyBefore)
        val gen = msg.generation ?: return undecryptable("no generation")
        val epoch = msg.epoch ?: return undecryptable("no epoch")
        // Rule 2: below the epoch this device joined at from the Welcome being replayed.
        if (joined != null && gen == joined.generation && epoch < joined.epoch) return beforeInstall
        val g = mls.group(conv) ?: return if (rule1) beforeInstall else park(e, conv, gen, epoch)
        // §17.2/§12.8: an older generation's message (lost to a reset or rejoin) is a gap too.
        if (gen < g.generation) return if (rule1 || msg.silent) beforeInstall else MlsResult.Dropped("stale generation", conv, msg.serverTs, gap)
        if (gen > g.generation) return if (rule1) beforeInstall else park(e, conv, gen, epoch)
        if (epoch > g.epoch) return park(e, conv, gen, epoch)
        return try {
            val d = mls.decrypt(msg.conversationId, gen, b64.decode(msg.ciphertext))
            // §10.3: the authenticated sender must be the event's from/from_device.
            if (!d.sender.userId.equals(msg.from, true) || !d.sender.deviceId.equals(msg.fromDevice ?: "", true)) {
                log("sender mismatch on ${msg.messageId}")
                undecryptable("sender mismatch")
            } else if (d.authenticatedData.isNotEmpty()) {
                // §15.3: only a delete control carries authenticated_data.
                log("non-empty authenticated_data on message ${msg.messageId}: dropped")
                undecryptable("unexpected authenticated_data")
            } else {
                when (val p = MlsPayload.decode(d.plaintext)) {
                    is MlsPayload.Decoded.Text -> MlsResult.Plaintext(msg, p.body)
                    is MlsPayload.Decoded.Reaction -> MlsResult.Reaction(msg, p.target, p.emoji, p.op)
                    is MlsPayload.Decoded.Image -> MlsResult.Image(msg, p.envelope)
                    // §15.3: a delete envelope is only valid in a `delete` event; in a `message` event it is dropped and logged.
                    is MlsPayload.Decoded.Delete -> {
                        log("delete envelope in a message event ${msg.messageId}: dropped")
                        MlsResult.Ignored
                    }
                    // §16.2: only `call_end` travels as a message; any other call envelope here is dropped.
                    is MlsPayload.Decoded.Call -> (p.env as? lk.codegen.risime.calls.CallEnvelope.End)?.let { MlsResult.CallEnd(msg, it) } ?: run {
                        log("call envelope ${p.env.type} in a message event ${msg.messageId}: dropped")
                        MlsResult.Ignored
                    }
                    // §17.6: history envelopes are controls, valid only in their own event kinds.
                    is MlsPayload.Decoded.HistoryRequest, is MlsPayload.Decoded.HistoryShare -> {
                        log("history envelope in a message event ${msg.messageId}: dropped")
                        MlsResult.Ignored
                    }
                    is MlsPayload.Decoded.ProfilePhoto -> MlsResult.ProfilePhoto(msg, d.sender.userId, p.env)
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
     * §15.4/§15.6 a `delete` control: ordered and parked exactly like a message; every failure is a
     * [MlsResult.ControlDropped] (no §13.3 line, no tombstone).
     */
    private suspend fun applyDelete(mls: MlsEngine, e: Event, d: lk.codegen.risime.net.DeleteEvent, historyBefore: java.time.Instant?, joined: GroupRef?): MlsResult {
        if (d.fromDevice.equals(mls.deviceId, true)) return MlsResult.OwnDelete(d) // our own control (OwnEcho)
        val conv = d.conversationId
        fun dropped(reason: String): MlsResult {
            log("delete ${d.messageId} dropped: $reason")
            return MlsResult.ControlDropped(reason)
        }
        val gen = d.generation ?: return dropped("no generation")
        val epoch = d.epoch ?: return dropped("no epoch")
        val rule1 = beforeHistory(d.serverTs, historyBefore)
        if (joined != null && gen == joined.generation && epoch < joined.epoch) return dropped("before this device joined")
        val g = mls.group(conv) ?: return if (rule1) dropped("pre-install") else park(e, conv, gen, epoch)
        if (gen < g.generation) return dropped("stale generation")
        if (gen > g.generation) return if (rule1) dropped("pre-install") else park(e, conv, gen, epoch)
        if (epoch > g.epoch) return park(e, conv, gen, epoch)
        val dec = try {
            mls.decrypt(conv, gen, b64.decode(d.ciphertext))
        } catch (ex: MlsMalformedException) {
            log("delete ${d.messageId} unverifiable: ${ex.message}")
            return MlsResult.DeleteUnverified(d, ex.message ?: "Malformed")
        } catch (ex: MlsDecryptException) {
            return dropped(if (rule1) "pre-install" else "decrypt: ${ex.message}")
        } catch (ex: IllegalArgumentException) {
            return dropped("bad base64")
        }
        // §15.4 step 1: the attested deleter is the event's from / from_device.
        if (!dec.sender.userId.equals(d.from, true) || !dec.sender.deviceId.equals(d.fromDevice ?: "", true)) return dropped("sender mismatch")
        val env = MlsPayload.decode(dec.plaintext) as? MlsPayload.Decoded.Delete ?: return dropped("not a delete envelope")
        // §15.4 step 2: AAD set = envelope set = event targets set (duplicates are malformed).
        val aad = lk.codegen.risime.data.deletes.DeleteAad.decode(dec.authenticatedData) ?: return dropped("bad authenticated_data")
        val eventIds = d.targets.map { t -> lk.codegen.risime.data.deletes.TimeUuid.canonical(t.messageId)?.lowercase() ?: return dropped("bad target id") }
        if (eventIds.toSet().size != eventIds.size) return dropped("duplicate targets")
        val set = aad.toSet()
        if (env.targets.toSet() != set || eventIds.toSet() != set) return dropped("binding mismatch")
        return MlsResult.Delete(d, dec.sender.userId, dec.senderIsAdmin)
    }

    /**
     * §16.3 a `call_signal`: ordered and parked exactly like a message (no fast path, crypto R2);
     * every failure is a [MlsResult.ControlDropped] (no §13.3 line, android R7); then the binding of
     * the cleartext `call_id`/`ring` to the envelope and of `from`/`from_device` to the core's sender.
     */
    private suspend fun applyCallSignal(mls: MlsEngine, e: Event, c: lk.codegen.risime.net.CallSignalEvent, historyBefore: java.time.Instant?, joined: GroupRef?): MlsResult {
        if (c.fromDevice.equals(mls.deviceId, true)) return MlsResult.Ignored // my own copy
        val conv = c.conversationId
        if (lk.codegen.risime.net.isGroupConversation(conv)) return MlsResult.ControlDropped("call_signal in a group")
        fun dropped(reason: String): MlsResult {
            log("call_signal ${c.messageId} dropped: $reason")
            return MlsResult.ControlDropped(reason)
        }
        val gen = c.generation
        val epoch = c.epoch
        val rule1 = beforeHistory(c.serverTs, historyBefore)
        if (joined != null && gen == joined.generation && epoch < joined.epoch) return dropped("before this device joined")
        val g = mls.group(conv) ?: return if (rule1) dropped("pre-install") else park(e, conv, gen, epoch)
        if (gen < g.generation) return dropped("stale generation")
        if (gen > g.generation) return if (rule1) dropped("pre-install") else park(e, conv, gen, epoch)
        if (epoch > g.epoch) return park(e, conv, gen, epoch)
        val dec = try {
            mls.decrypt(conv, gen, b64.decode(c.ciphertext))
        } catch (ex: MlsDecryptException) {
            return dropped(if (rule1) "pre-install" else "decrypt: ${ex.message}")
        } catch (ex: IllegalArgumentException) {
            return dropped("bad base64")
        }
        if (!dec.sender.userId.equals(c.from, true) || !dec.sender.deviceId.equals(c.fromDevice, true)) return dropped("sender mismatch")
        if (dec.authenticatedData.isNotEmpty()) return dropped("unexpected authenticated_data")
        val env = (MlsPayload.decode(dec.plaintext) as? MlsPayload.Decoded.Call)?.env ?: return dropped("not a valid call envelope")
        if (env is lk.codegen.risime.calls.CallEnvelope.End) return dropped("call_end in a call_signal")
        // crypto R3 binding.
        if (env.callId != c.callId) return dropped("call_id mismatch")
        if (c.ring != lk.codegen.risime.calls.CallEnvelope.ringFor(env)) return dropped("ring flag mismatch")
        return MlsResult.CallSignal(c, env)
    }

    /** One decrypted history envelope, or why it was dropped (null = parked). */
    private sealed interface HistoryDecrypt {
        class Ok(val dec: Decrypted) : HistoryDecrypt

        class Fail(val result: MlsResult) : HistoryDecrypt
    }

    /**
     * §17.6 ordering, decrypt and the AAD/sender binding shared by both history envelopes: ordered and
     * parked exactly like a message; the 'H' AAD must be exactly the event's request id (crypto R3).
     * [stale] builds the result for an envelope this device can't read.
     */
    private suspend fun decryptHistory(
        mls: MlsEngine, e: Event, conv: String, gen: Long, epoch: Long, from: String, fromDevice: String, requestId: String,
        serverTs: String?, historyBefore: java.time.Instant?, joined: GroupRef?, stale: (String) -> MlsResult,
    ): HistoryDecrypt {
        val rule1 = beforeHistory(serverTs, historyBefore)
        if (joined != null && gen == joined.generation && epoch < joined.epoch) return HistoryDecrypt.Fail(stale("before this device joined"))
        val g = mls.group(conv) ?: return HistoryDecrypt.Fail(if (rule1) stale("pre-install") else park(e, conv, gen, epoch))
        if (gen < g.generation) return HistoryDecrypt.Fail(stale("stale generation"))
        if (gen > g.generation) return HistoryDecrypt.Fail(if (rule1) stale("pre-install") else park(e, conv, gen, epoch))
        if (epoch > g.epoch) return HistoryDecrypt.Fail(park(e, conv, gen, epoch))
        val dec = try {
            mls.decrypt(conv, gen, b64.decode(e.data["ciphertext"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } ?: ""))
        } catch (ex: MlsDecryptException) {
            return HistoryDecrypt.Fail(stale("decrypt: ${ex.message}"))
        } catch (ex: IllegalArgumentException) {
            return HistoryDecrypt.Fail(MlsResult.ControlDropped("history: bad base64"))
        }
        if (!dec.sender.userId.equals(from, true) || !dec.sender.deviceId.equals(fromDevice, true)) return HistoryDecrypt.Fail(MlsResult.ControlDropped("history: sender mismatch"))
        val aadId = lk.codegen.risime.data.history.HistoryAad.decode(dec.authenticatedData)
        if (aadId == null || !aadId.equals(requestId, true)) return HistoryDecrypt.Fail(MlsResult.ControlDropped("history: bad authenticated_data"))
        return HistoryDecrypt.Ok(dec)
    }

    /** §17.7 a `history_request` naming this device: decrypted in order; own vs member from the MLS sender only. */
    private suspend fun applyHistoryRequest(mls: MlsEngine, e: Event, r: lk.codegen.risime.net.HistoryRequestEvent, historyBefore: java.time.Instant?, joined: GroupRef?): MlsResult {
        if (!mls.historySupported) return MlsResult.Ignored
        if (r.toDevices.isNotEmpty() && r.toDevices.none { it.equals(mls.deviceId, true) }) return MlsResult.Ignored
        if (r.fromDevice.equals(mls.deviceId, true)) return MlsResult.Ignored
        val d = decryptHistory(mls, e, r.conversationId, r.generation, r.epoch, r.from, r.fromDevice, r.requestId, r.serverTs, historyBefore, joined) { why ->
            log("history_request ${r.requestId}: can't read ($why): stale")
            MlsResult.HistoryRequestStale(r, why)
        }
        val dec = when (d) {
            is HistoryDecrypt.Fail -> return d.result.also { if (it is MlsResult.ControlDropped) log("history_request ${r.requestId} dropped: ${it.reason}") }
            is HistoryDecrypt.Ok -> d.dec
        }
        val env = (MlsPayload.decode(dec.plaintext) as? MlsPayload.Decoded.HistoryRequest)?.env ?: return MlsResult.ControlDropped("history_request: not a valid envelope")
        if (!env.requestId.equals(r.requestId, true)) return MlsResult.ControlDropped("history_request: request_id mismatch")
        val sender = try {
            mls.historySender(r.conversationId, dec.sender)
        } catch (ex: HistoryException) {
            return MlsResult.ControlDropped("history_request: sender ${ex.message}")
        }
        return MlsResult.HistoryRequest(r, env, sender)
    }

    /** §17.6 a `history_share` to this device: decrypted in order and bound (request id, part/parts, sender). */
    private suspend fun applyHistoryShare(mls: MlsEngine, e: Event, s: lk.codegen.risime.net.HistoryShareEvent, historyBefore: java.time.Instant?, joined: GroupRef?): MlsResult {
        if (!mls.historySupported) return MlsResult.Ignored
        if (s.toDevices.none { it.equals(mls.deviceId, true) }) return MlsResult.Ignored
        if (s.fromDevice.equals(mls.deviceId, true)) return MlsResult.Ignored
        val d = decryptHistory(mls, e, s.conversationId, s.generation, s.epoch, s.from, s.fromDevice, s.requestId, s.serverTs, historyBefore, joined) { why ->
            MlsResult.ControlDropped("history_share: $why")
        }
        val dec = when (d) {
            is HistoryDecrypt.Fail -> return d.result.also { if (it is MlsResult.ControlDropped) log("history_share ${s.requestId} dropped: ${it.reason}") }
            is HistoryDecrypt.Ok -> d.dec
        }
        val env = (MlsPayload.decode(dec.plaintext) as? MlsPayload.Decoded.HistoryShare)?.env ?: return MlsResult.ControlDropped("history_share: not a valid envelope")
        if (!env.requestId.equals(s.requestId, true) || env.part != s.part || env.parts != s.parts) return MlsResult.ControlDropped("history_share: binding mismatch")
        return MlsResult.HistoryShare(s, env, dec.sender)
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
