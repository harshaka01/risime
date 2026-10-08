package lk.codegen.risime.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lk.codegen.risime.data.db.MessageDao
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.db.SeenEventEntity
import lk.codegen.risime.data.db.SyncDao
import lk.codegen.risime.data.db.SyncStateEntity
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.MessageData
import lk.codegen.risime.data.mls.MlsEngine
import lk.codegen.risime.data.mls.MlsNotReady
import lk.codegen.risime.data.mls.MlsPipeline
import lk.codegen.risime.data.mls.MlsResult
import lk.codegen.risime.data.groups.SystemLine
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.MsgSend
import lk.codegen.risime.net.MsgSendE2ee
import lk.codegen.risime.net.StatusData
import lk.codegen.risime.net.conversationFor
import lk.codegen.risime.net.dmPeer
import lk.codegen.risime.net.isGroupConversation
import lk.codegen.risime.realtime.PushResult
import lk.codegen.risime.realtime.RealtimeClient
import lk.codegen.risime.realtime.RealtimeListener
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Runs a block in one DB transaction (Room's withTransaction in the app; inline in tests). */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

/**
 * Store-and-forward client side (RELEASE-0.1 A2): outbox, incoming dedupe, cursor, acks.
 * It is the realtime listener: every event goes through [onEvents] in order.
 */
class ChatEngine(
    private val messages: MessageDao,
    private val sync: SyncDao,
    private val tx: TransactionRunner,
    private val scope: CoroutineScope,
    private val realtime: () -> RealtimeClient,
    private val meId: suspend () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newClientMsgId: () -> String = { UUID.randomUUID().toString() },
    private val behaviour: BehaviourLog? = null,
    private val rateLimitRetryMs: Long = 10_000,
    /** Called after a new incoming message from this user id is stored (ends their "typing…"). */
    private val onIncomingFrom: (String) -> Unit = {},
    /** §10: e2ee events (null = no MLS core: e2ee events are skipped like a v1.6 app). */
    private val mls: MlsPipeline? = null,
    private val mlsEngine: () -> MlsEngine? = { null },
    /** §10.4: fetch missing commits for a conversation (stale_epoch / e2ee_required). */
    private val catchUp: suspend (conversationId: String) -> Unit = {},
    private val staleEpochRetries: Int = 3,
    /** §11.2 reaction state (null in tests that don't cover reactions). */
    private val reactionsDao: lk.codegen.risime.data.db.ReactionDao? = null,
    private val reactionDebounceMs: Long = 500,
    /** §12: false = a pre-groups app; `grp:` events are skipped (they still advance the cursor). */
    private val groupsEnabled: () -> Boolean = { false },
    /** §12.7 group_event / group_op / group_receipt (null = no groups). */
    private val groups: lk.codegen.risime.data.groups.GroupStore? = null,
    /** §12.6: fetches a referenced commit/Welcome before the event's transaction. */
    private val blobs: (suspend (lk.codegen.risime.net.BlobRef) -> BlobFetch)? = null,
    /** §12.8: this device can't follow the group any more (rejoin). */
    private val onUnrecoverable: (conversationId: String) -> Unit = {},
    /** v1.16: an e2ee DM this device can't encrypt for (no or stale group): check and repair (MlsUpgrader verify). */
    private val onDmNeedsRepair: (conversationId: String) -> Unit = {},
    /**
     * Where the work that touches the MLS core runs: its storage callbacks open Room transactions
     * synchronously, which Room refuses on the main thread (the nightly.17 crash on opening a DM while
     * MLS activated). The app passes Dispatchers.IO; tests keep the caller's context.
     */
    private val io: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
    /**
     * §13.3: the first `since: null` join has caught up (called at the start of that onLive): treat
     * everything up to now as already notified.
     */
    private val onFreshReplayDone: suspend () -> Unit = {},
    /** §14: image envelopes (null = an app without images: `image` payloads are ignored). */
    private val images: lk.codegen.risime.data.media.ImageHooks? = null,
    /** §15 deletes (null = an app without delete support: `delete` events are skipped like a v1.11 app). */
    private val deletes: lk.codegen.risime.data.db.DeleteDao? = null,
    /**
     * After the transactions of a batch that deleted something (files already unlinked): refresh
     * posted notifications silently and checkpoint the WAL (§15.6). Never inside a transaction.
     */
    private val onDeletesApplied: () -> Unit = {},
    /** §15.7 the server-clock offset (from every join/sync reply). */
    private val serverClock: lk.codegen.risime.data.deletes.ServerClock? = null,
    private val log: (String) -> Unit = {},
    /** §16 calls (null = an app without calls: `call_signal` events are skipped, `call_end` is stored invisibly). */
    private val calls: lk.codegen.risime.calls.CallHooks? = null,
    /** §17.2 the gap index (null = an app without history sharing: no gap rows). */
    private val historyDao: lk.codegen.risime.data.db.HistoryDao? = null,
    /** §17 history sharing (null = an app without it: history events are skipped, they still advance the cursor). */
    private val history: lk.codegen.risime.data.history.HistoryHooks? = null,
    /** §18 profile photos (null = an app without them: `profile_photo` envelopes are ignored). */
    private val profilePhotos: lk.codegen.risime.data.profile.ProfilePhotoHooks? = null,
    /**
     * P0 background delivery: true once the MLS core is open (or MLS doesn't apply to this app or
     * server); may wait a little (bounded). A process started by a push joins the inbox while the core
     * is still opening: an MLS event applied then was "Ignored", marked seen and the cursor moved past
     * it, so the message (or the call invite) was lost on this device. Now the batch is applied up to
     * the first MLS event and stops there ([MlsNotReady]); the socket rejoins from that cursor. The
     * pipeline refuses on its own too (the core can close between this check and the event).
     */
    private val mlsReady: suspend () -> Boolean = { true },
) : RealtimeListener, lk.codegen.risime.data.history.SendLanes {
    /** §15.4–§15.6 applied inside each event's transaction. */
    private val applier = deletes?.let { lk.codegen.risime.data.deletes.DeleteApplier(it, messages, images, clock, log) }

    /** Set inside a transaction that purged something; acted on after the batch. */
    @Volatile private var deletesApplied = false
    /** §13.2: this join's `history_before` (in memory only; every join returns it). */
    @Volatile private var historyBefore: Instant? = null

    /** §13.3: a `since: null` join is replaying the inbox (no notifications until it is live). */
    @Volatile var replayingFresh: Boolean = false
        private set
    private val reactionStore = reactionsDao?.let { ReactionStore(it, clock) }
    private val reactionLock = Mutex()


    private val outboxLock = Mutex()
    private val ackLock = Mutex()

    /**
     * §15.7 (android R6) / §16.4 (crypto R2, android R8): one serial encrypt-and-push lane per
     * conversation, shared by texts, reactions, images, deletes and `call:signal`: generation n is
     * pushed before n+1 is encrypted. A push that fails gives up the lane and is re-encrypted on retry.
     */
    private val lanes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private fun lane(conversationId: String): Mutex = lanes.getOrPut(conversationId.lowercase()) { Mutex() }

    /** §17.16: history envelopes (request, refresh, deliver) go through the same serial lane. */
    override suspend fun <T> withLane(conversationId: String, block: suspend () -> T): T = lane(conversationId).withLock { block() }

    /** §16: call results of the current event, handed to [calls] after its transaction commits (never inside). */
    private val callQueue = java.util.concurrent.ConcurrentLinkedQueue<suspend (lk.codegen.risime.calls.CallHooks) -> Unit>()
    private val deleteLock = Mutex()

    // ---- RealtimeListener ----

    override suspend fun cursor(): String? = sync.cursor().also { if (it == null) replayingFresh = true }

    override suspend fun onHistoryBefore(ts: String?) {
        historyBefore = ts?.let { runCatching { Instant.parse(it) }.getOrNull() }
    }

    override suspend fun onServerTime(ts: String?) {
        serverClock?.onServerTime(ts)
    }

    override suspend fun onEvents(events: List<Event>): Unit = withContext(io) { onEventsImpl(events) }

    private suspend fun onEventsImpl(events: List<Event>) {
        if (events.isEmpty()) return
        val me = meId() ?: return
        var newIncoming = false
        try {
            applyBatch(me, events) { newIncoming = it || newIncoming }
        } catch (e: MlsNotReady) {
            // Plaintext events before it are applied (and notify); the MLS event and everything after it
            // wait for the rejoin from the unchanged cursor (rule 9: never marked seen, never skipped).
            callQueue.clear() // anything queued by the refused (rolled-back) event
            finishBatch(newIncoming)
            throw e
        }
        finishBatch(newIncoming)
    }

    private suspend fun applyBatch(me: String, events: List<Event>, onApplied: (Boolean) -> Unit) {
        for ((index, e0) in events.withIndex()) {
            if (mls != null && requiresMls(e0) && !mlsReady()) {
                log("mls: core not open yet: ${events.size - index} event(s) left for the rejoin")
                throw MlsNotReady()
            }
            // §12.6: a referenced blob is fetched first, outside the ordered transaction. A transient
            // failure stops here without moving the cursor (the next sync redelivers from it).
            val e = when (val r = resolveBlobRefs(e0, me)) {
                null -> break
                else -> r
            }
            val applied = tx.run {
                if (sync.seenCount(e.eventId) > 0) {
                    // Already applied (e.g. the one-time inbox replay): still advance the cursor to it,
                    // or every reconnect would join with `since: null` again (P0 found by the upgrade gate).
                    sync.setState(SyncStateEntity(0, e.eventId))
                    return@run false
                }
                // Unknown kinds and undecodable data are skipped but still advance the cursor.
                val incoming = if (!groupsEnabled() && isGroupEvent(e)) false else when (e.kind) {
                    Event.KIND_MESSAGE -> runCatching { e.messageData() }.getOrNull()?.let { md ->
                        if (md.encrypted) applyMls(me, e) else md.body?.let { applyMessage(me, md, it) } ?: false
                    } ?: false
                    Event.KIND_REACTION -> {
                        runCatching { e.reaction() }.getOrNull()?.let { r ->
                            applyReaction(r.conversationId, r.target, r.from, r.emoji, r.op, r.serverTs, r.messageId, r.clientMsgId)
                        }
                        false
                    }
                    Event.KIND_MLS_COMMIT, Event.KIND_MLS_WELCOME, Event.KIND_MLS_MEMBERSHIP, Event.KIND_MLS_DM_OP ->
                        runCatching { applyMls(me, e) }.getOrElse { if (it is MlsNotReady) throw it else false }
                    Event.KIND_STATUS -> {
                        runCatching { e.statusData() }.getOrNull()?.let { applyStatus(it) }
                        false
                    }
                    Event.KIND_GROUP_EVENT -> {
                        runCatching { e.groupEvent() }.getOrNull()?.let {
                            // §15.7: a cleared chat's system lines at or before the watermark stay gone (state still applies).
                            val suppress = applier?.cleared(it.groupId, lk.codegen.risime.data.deletes.TimeUuid.ticks(e.eventId)) == true
                            groups?.applyEvent(e.eventId, it, me, historicalLocalTs(it.serverTs), suppressLine = suppress)
                        }
                        false
                    }
                    Event.KIND_DELETE -> {
                        runCatching { e.deleteData() }.getOrNull()?.let { dropGaps(it) }
                        if (applier != null) {
                            runCatching { e.deleteData() }.getOrNull()?.let { d -> if (d.encrypted) applyMls(me, e) else applyPlainDelete(me, d) }
                        }
                        false
                    }
                    Event.KIND_GROUP_OP -> {
                        runCatching { e.groupOp() }.getOrNull()?.let { groups?.applyOp(it, me) }
                        false
                    }
                    // §16.3: ordered with the DM's other events, through the MLS pipeline; never a message.
                    Event.KIND_CALL_SIGNAL -> {
                        if (calls != null) runCatching { applyMls(me, e) }.onFailure { if (it is MlsNotReady) throw it; log("call_signal: ${it.message}") }
                        false
                    }
                    Event.KIND_GROUP_RECEIPT -> {
                        runCatching { e.groupReceipt() }.getOrNull()?.let { groups?.applyReceipt(it) }
                        false
                    }
                    // §17.5/§17.6: the MLS history envelopes in event order; the plaintext status events directly.
                    Event.KIND_HISTORY_REQUEST, Event.KIND_HISTORY_SHARE -> {
                        if (history != null) runCatching { applyMls(me, e) }.onFailure { if (it is MlsNotReady) throw it; log("history: ${it.message}") }
                        false
                    }
                    Event.KIND_HISTORY_STATUS -> {
                        history?.let { h -> runCatching { e.historyStatus() }.getOrNull()?.let { h.onStatusInTx(it); historyTouched = true } }
                        false
                    }
                    Event.KIND_HISTORY_REQUEST_CLOSED -> {
                        history?.let { h -> runCatching { e.historyRequestClosed() }.getOrNull()?.let { h.onClosedInTx(it); historyTouched = true } }
                        false
                    }
                    else -> false
                }
                sync.markSeen(SeenEventEntity(e.eventId))
                sync.setState(SyncStateEntity(0, e.eventId))
                incoming
            }
            onApplied(applied)
            dispatchCalls()
            if (historyTouched) {
                historyTouched = false
                history?.afterCommit()
            }
        }
    }

    /** After the applied part of a batch (all of it, or up to an event left for the rejoin). */
    private suspend fun finishBatch(newIncoming: Boolean) {
        calls?.let { runCatching { it.onPageEnd() }.onFailure { e -> log("calls page end: ${e.message}") } }
        if (newIncoming) flushAcks()
        afterPhotos()
        if (imagesStored) {
            imagesStored = false
            images?.received()
        }
        afterDeletes()
    }

    /** §16: after the event's transaction committed, in event order. */
    private suspend fun dispatchCalls() {
        val hooks = calls ?: return
        while (true) {
            val f = callQueue.poll() ?: break
            runCatching { f(hooks) }.onFailure { log("calls: ${it.message}") }
        }
    }

    /** After the commit (android R10): unlink purged files, cancel their transfers; then refresh notifications and checkpoint. */
    private fun afterDeletes() {
        if (!deletesApplied) return
        deletesApplied = false
        applier?.takePurged()?.forEach { p -> runCatching { images?.afterPurge(p.clientMsgId, p.files) } }
        onDeletesApplied()
    }

    /** §18: set inside a transaction that applied a profile photo; downloads are scheduled after the commit. */
    @Volatile private var photosTouched = false

    private fun afterPhotos() {
        if (!photosTouched) return
        photosTouched = false
        profilePhotos?.afterCommit()
    }

    /** §17: set inside a transaction that changed history state; the manager acts after the commit. */
    @Volatile private var historyTouched = false

    /** §14.7: set when an image row was stored; downloads are scheduled after the transactions (never inside). */
    @Volatile private var imagesStored = false

    /**
     * Inline a referenced commit/Welcome (§12.6). Returns the event to apply, or null on a transient
     * failure. A blob that is gone (404, bad hash) is left unresolved: the pipeline reports it as
     * unrecoverable.
     */
    private suspend fun resolveBlobRefs(e: Event, me: String): Event? {
        val fetch = blobs ?: return e
        val (field, ref) = when (e.kind) {
            Event.KIND_MLS_COMMIT -> runCatching { e.mlsCommit() }.getOrNull()?.takeIf { it.commit == null }?.commitRef?.let { "commit" to it }
            Event.KIND_MLS_WELCOME -> runCatching { e.mlsWelcome() }.getOrNull()
                ?.takeIf { w -> w.welcome == null && w.toDevices.any { it.equals(mlsEngine()?.deviceId, true) } }?.welcomeRef?.let { "welcome" to it }
            else -> null
        } ?: return e
        if (sync.seenCount(e.eventId) > 0) return e
        return when (val r = fetch(ref)) {
            is BlobFetch.Ok -> e.copy(data = kotlinx.serialization.json.JsonObject(e.data + (field to kotlinx.serialization.json.JsonPrimitive(java.util.Base64.getEncoder().encodeToString(r.bytes)))))
            BlobFetch.Gone -> e
            BlobFetch.Transient -> null
        }
    }

    /** Events whose handling goes through the MLS pipeline ([applyMls]). */
    private fun requiresMls(e: Event): Boolean = when (e.kind) {
        Event.KIND_MESSAGE -> runCatching { e.messageData() }.getOrNull()?.encrypted == true
        Event.KIND_DELETE -> runCatching { e.deleteData() }.getOrNull()?.encrypted == true
        Event.KIND_MLS_COMMIT, Event.KIND_MLS_WELCOME, Event.KIND_MLS_MEMBERSHIP, Event.KIND_MLS_DM_OP,
        Event.KIND_CALL_SIGNAL, Event.KIND_HISTORY_REQUEST, Event.KIND_HISTORY_SHARE -> true
        else -> false
    }

    private fun isGroupEvent(e: Event): Boolean {
        val conv = (e.data["conversation_id"] ?: e.data["group_id"]) as? kotlinx.serialization.json.JsonPrimitive
        return conv?.isString == true && isGroupConversation(conv.content)
    }

    /** Commits fetched by catch-up (GET …/commits): applied in order, each in its own transaction, no cursor move. */
    suspend fun applyOutOfBand(events: List<Event>): Unit = withContext(io) { applyOutOfBandImpl(events) }

    private suspend fun applyOutOfBandImpl(events: List<Event>) {
        val me = meId() ?: return
        var newIncoming = false
        for (e0 in events) {
            val e = resolveBlobRefs(e0, me) ?: return
            newIncoming = tx.run { runCatching { applyMls(me, e) }.getOrDefault(false) } || newIncoming
            dispatchCalls()
        }
        calls?.let { runCatching { it.onPageEnd() } }
        if (newIncoming) flushAcks()
        afterPhotos()
        afterDeletes()
        if (historyTouched) {
            historyTouched = false
            history?.afterCommit()
        }
    }

    override suspend fun onLive() {
        if (replayingFresh) {
            onFreshReplayDone()
            replayingFresh = false
        }
        flushOutbox()
        flushAcks()
        history?.afterCommit() // §17: owed pushes, imports and exports resume once live
    }

    override suspend fun onAuthFailed() = Unit // handled by the session owner via ConnectionState

    // ---- Applying events ----

    /**
     * §10.3: e2ee events through the MLS pipeline, inside this event's transaction. A group change
     * replays the conversation's pending events in arrival order. @return true if a new incoming
     * message was stored.
     */
    private suspend fun applyMls(me: String, e: Event): Boolean {
        val pipeline = mls ?: return false
        var incoming = false
        val results = mutableListOf(pipeline.apply(e, historyBefore))
        var i = 0
        while (i < results.size) {
            val r = results[i++]
            // §13.3: lost history is always visible (one deduplicated line per chat), never silent.
            if (r is MlsResult.BeforeInstall) {
                // §22.6 (§13.3 extended): a pre-install message already held (restored from a backup) adds no marker and no gap row.
                val held = r.gaps.isNotEmpty() && r.gaps.all { messages.byMessageId(it.messageId) != null }
                if (!held) upsertMarker(r.conversationId, SystemLine.HISTORY_GAP, r.serverTs)
                recordGaps(r.gaps)
            }
            if (r is MlsResult.Dropped) {
                r.conversationId?.let { upsertMarker(it, SystemLine.UNDECRYPTABLE, r.serverTs) }
                recordGaps(r.gaps)
            }
            if (r is MlsResult.Plaintext) incoming = applyMessage(me, r.message, r.body) || incoming
            if (r is MlsResult.Image) {
                val hooks = images
                if (hooks != null) {
                    incoming = applyMessage(me, r.message, r.envelope.caption.orEmpty(), MessageEntity.KIND_IMAGE, r.envelope.blob.blobId) { row ->
                        hooks.stored(row, r.envelope)
                        imagesStored = true
                    } || incoming
                }
            }
            if (r is MlsResult.Reaction) {
                applyReaction(r.message.conversationId, r.target, r.message.from, r.emoji, r.op, r.message.serverTs, r.message.messageId, r.message.clientMsgId)
            }
            // §15.4–§15.6: controls never create §13.3 lines (ControlDropped is logged by the pipeline).
            if (r is MlsResult.Delete) applyDeleteEveryone(me, r.event, r.deleter, r.senderIsAdmin, e2ee = true)
            if (r is MlsResult.OwnDelete) completeOwnDelete(me, r.event)
            if (r is MlsResult.DeleteUnverified) markUnverified(r.event)
            if (r is MlsResult.Unrecoverable) onUnrecoverable(r.conversationId)
            if (r is MlsResult.CallSignal) queueCall(r)
            if (r is MlsResult.CallEnd) incoming = applyCallEnd(me, r.message, r.env) || incoming
            if (r is MlsResult.GroupCall) applyGroupCall(me, r.message, r.env)
            // §18.2: applied in the event's transaction; never a row, unread count, ack or notification.
            if (r is MlsResult.ProfilePhoto) profilePhotos?.let { pp ->
                pp.applyInTx(r.subject, r.message.conversationId, r.env, me)
                photosTouched = true
            }
            history?.let { h ->
                when (r) {
                    is MlsResult.HistoryRequest -> { h.onRequestInTx(r); historyTouched = true }
                    is MlsResult.HistoryRequestStale -> { h.onRequestStaleInTx(r.event); historyTouched = true }
                    is MlsResult.HistoryShare -> { h.onShareInTx(r); historyTouched = true }
                    else -> Unit
                }
            }
            (r as? MlsResult.GroupChanged)?.let { gc ->
                val conv = gc.conversationId
                results += gc.extra
                if (isGroupConversation(conv)) {
                    groups?.onGroupStateChanged(conv, removedSelf = mlsEngine()?.group(conv) == null)
                    scope.launch { flushOutbox() } // messages waiting for this group's Welcome
                } else if (mlsEngine()?.group(conv) != null) {
                    scope.launch { flushOutbox() } // v1.16: DM messages waiting while this phone was re-added
                }
                results += pipeline.replay(conv, gc.joined)
            }
        }
        return incoming
    }

    private fun queueCall(r: MlsResult.CallSignal) {
        val ev = r.event
        val ts = HistoryMarkers.epochMs(ev.serverTs) ?: return
        val inbound = lk.codegen.risime.calls.InboundCall(ev.conversationId, ev.from, ev.fromDevice, ts, r.env, inPage = true, media = ev.media ?: lk.codegen.risime.calls.CallEnvelope.MEDIA_AUDIO)
        callQueue.add { h -> h.onSignal(inbound) }
    }

    /**
     * §16.6 a durable `call_end`: one line per call id (the first by event order wins), from this
     * user's perspective; "Missed voice call" is unread and notifies (never on a replay).
     * @return true if a new incoming row needs a delivered ack.
     */
    private suspend fun applyCallEnd(me: String, m: MessageData, env: lk.codegen.risime.calls.CallEnvelope.End): Boolean {
        val hooks = calls
        val conv = m.conversationId
        if (hooks != null) callQueue.add { h -> h.onCallEnd(conv, m.from, m.fromDevice, env) }
        if (hooks == null) return false // an app without calls stores nothing visible (§16.14)
        val a = applier
        if (a != null && a.cleared(conv, lk.codegen.risime.data.deletes.TimeUuid.ticks(m.messageId))) return false
        if (messages.byMessageId(m.messageId) != null || messages.byClientMsgId(m.clientMsgId) != null) return false
        if (messages.callLine(conv, env.callId) != null) return false
        val outgoing = m.from.equals(me, true)
        val line = lk.codegen.risime.calls.CallLines.line(env.reason, outgoing, env.durationS, !outgoing && hooks.rangUnanswered(env.callId), env.media == lk.codegen.risime.calls.CallEnvelope.MEDIA_VIDEO)
        val restored = historicalLocalTs(m.serverTs)
        val preInstall = !outgoing && historyBefore?.let { hb -> HistoryMarkers.epochMs(m.serverTs)?.let { it < hb.toEpochMilli() } } == true
        val unread = !outgoing && line.missed && !preInstall
        val row = MessageEntity(
            clientMsgId = m.clientMsgId, messageId = m.messageId, conversationId = conv, from = m.from,
            to = m.to ?: conv, body = line.text, serverTs = m.serverTs, localTs = restored ?: clock(),
            status = when {
                outgoing -> MessageStatus.SENT
                unread -> MessageStatus.DELIVERED
                else -> MessageStatus.READ
            }.name,
            outgoing = outgoing,
            ackedStatus = if (preInstall) MessageStatus.READ.name else null,
            kind = MessageEntity.KIND_CALL,
            systemJson = lk.codegen.risime.net.ProtocolJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), lk.codegen.risime.calls.CallEnvelope.toJson(env)),
            callId = env.callId,
            fromDevice = m.fromDevice?.lowercase(),
        )
        if (messages.insert(row) == -1L) return false
        deletes?.unhide(conv)
        if (outgoing || preInstall) return false
        // A live missed call notifies (replays and restored history never do, §16.6).
        if (unread && !replayingFresh && restored == null) callQueue.add { h -> h.onMissedCall(conv, m.from, env.media == lk.codegen.risime.calls.CallEnvelope.MEDIA_VIDEO) }
        return true
    }

    /**
     * §20.4 a durable `group_call`: one line per call id, created by `started`, updated by the first
     * `ended` (later duplicates ignored). Silent: never unread, never acked, never a notification.
     */
    private suspend fun applyGroupCall(me: String, m: MessageData, env: lk.codegen.risime.calls.GroupCallEnvelope) {
        val conv = m.conversationId
        val hooks = calls
        if (hooks != null) callQueue.add { h -> h.onGroupCallLine(conv, m.from, env) }
        if (hooks == null) return // an app without calls stores nothing visible (§20.10)
        val a = applier
        if (a != null && a.cleared(conv, lk.codegen.risime.data.deletes.TimeUuid.ticks(m.messageId))) return
        if (messages.byMessageId(m.messageId) != null || messages.byClientMsgId(m.clientMsgId) != null) return
        val existing = messages.callLine(conv, env.callId)
        if (existing != null) {
            updateGroupCallLine(me, existing, env)
            return
        }
        val starterIsMe = m.from.equals(me, true)
        val row = MessageEntity(
            clientMsgId = m.clientMsgId, messageId = m.messageId, conversationId = conv, from = m.from,
            to = conv, body = groupCallBody(env, starterIsMe), serverTs = m.serverTs, localTs = historicalLocalTs(m.serverTs) ?: clock(),
            status = if (starterIsMe) MessageStatus.SENT.name else MessageStatus.READ.name,
            outgoing = starterIsMe, ackedStatus = MessageStatus.READ.name,
            kind = MessageEntity.KIND_CALL,
            systemJson = lk.codegen.risime.net.ProtocolJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), env.toJson()),
            callId = env.callId, fromDevice = m.fromDevice?.lowercase(),
        )
        if (messages.insert(row) != -1L) deletes?.unhide(conv)
    }

    /** The stored body of a group call line (the chat list preview; the chat renders names from the JSON). */
    private fun groupCallBody(env: lk.codegen.risime.calls.GroupCallEnvelope, starterIsMe: Boolean, running: Boolean = true): String =
        if (env.state == lk.codegen.risime.calls.GroupCallEnvelope.STARTED && running) {
            if (env.video) "Video call in progress" else "Voice call in progress"
        } else {
            lk.codegen.risime.calls.GroupCallLines.text(env, "", starterIsMe, running)
        }

    /** The first `ended` wins; a `started` never overwrites anything. */
    private suspend fun updateGroupCallLine(me: String, row: MessageEntity, env: lk.codegen.risime.calls.GroupCallEnvelope) {
        if (env.state != lk.codegen.risime.calls.GroupCallEnvelope.ENDED) return
        val old = lk.codegen.risime.calls.GroupCallEnvelope.decode(row.systemJson)
        if (old?.state == lk.codegen.risime.calls.GroupCallEnvelope.ENDED) return
        messages.updateCallLine(row.clientMsgId, groupCallBody(env, row.from.equals(me, true)), lk.codegen.risime.net.ProtocolJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), env.toJson()))
    }

    /**
     * §20.4 my `group_call` `started`: an outgoing outbox row (kind call, sent `silent`) that is also
     * my history line. Nothing is queued when the call already has a line.
     */
    suspend fun queueGroupCallStarted(conv: String, env: lk.codegen.risime.calls.GroupCallEnvelope): String? {
        val me = meId() ?: return null
        if (messages.callLine(conv, env.callId) != null) return null
        val id = newClientMsgId()
        messages.insert(
            MessageEntity(
                clientMsgId = id, messageId = null, conversationId = conv, from = me, to = conv, body = groupCallBody(env, true),
                serverTs = null, localTs = clock(), status = MessageStatus.PENDING.name, outgoing = true,
                kind = MessageEntity.KIND_CALL,
                systemJson = lk.codegen.risime.net.ProtocolJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), env.toJson()),
                callId = env.callId,
            ),
        )
        deletes?.unhide(conv)
        scope.launch { flushOutbox() }
        return id
    }

    /**
     * §20.4 my `group_call` `ended` (the last one out, or the starter's timeout): sent `silent` now
     * (a few tries), and my own line updated. A line that never got an `ended` reads "Voice call
     * ended" once `status` says the room is gone, so a lost send leaves nothing wrong behind.
     */
    suspend fun sendGroupCallEnded(conv: String, env: lk.codegen.risime.calls.GroupCallEnvelope): Boolean {
        val me = meId() ?: return false
        withContext(io) {
            messages.callLine(conv, env.callId)?.let { updateGroupCallLine(me, it, env) } ?: messages.insert(
                MessageEntity(
                    clientMsgId = "local-group-call:${env.callId}", messageId = null, conversationId = conv, from = me, to = conv,
                    body = groupCallBody(env, true), serverTs = isoMillis(clock()), localTs = clock(), status = MessageStatus.SENT.name, outgoing = true,
                    kind = MessageEntity.KIND_CALL,
                    systemJson = lk.codegen.risime.net.ProtocolJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), env.toJson()),
                    callId = env.callId,
                ),
            )
        }
        repeat(3) {
            if (sendSilent(conv, env.encode()) is PushResult.Ok) return true
            kotlinx.coroutines.delay(2_000)
        }
        return false
    }

    /** §20.4: `status` said the room is gone (or a Join found it ended): the `started` line turns "… ended". */
    suspend fun markGroupCallOver(conv: String, callId: String) = withContext(io) {
        val me = meId() ?: return@withContext
        val row = messages.callLine(conv, callId) ?: return@withContext
        val env = lk.codegen.risime.calls.GroupCallEnvelope.decode(row.systemJson) ?: return@withContext
        if (env.state != lk.codegen.risime.calls.GroupCallEnvelope.STARTED) return@withContext
        val json = lk.codegen.risime.net.ProtocolJson.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.JsonObject(env.toJson() + (lk.codegen.risime.calls.GroupCallLines.LOCAL_OVER to kotlinx.serialization.json.JsonPrimitive(true))),
        )
        messages.updateCallLine(row.clientMsgId, groupCallBody(env, row.from.equals(me, true), running = false), json)
    }

    /**
     * §20.3 one ephemeral group `call:signal` (`conversation_id`, no `to`) through the group's lane;
     * stale_epoch → catch up and re-encrypt with the same client_msg_id.
     */
    suspend fun sendGroupCallSignal(conv: String, env: lk.codegen.risime.calls.CallEnvelope.Env, media: String): PushResult<lk.codegen.risime.net.CallSignalReply> =
        withContext(io) { sendCallSignalImpl(conv, null, env, media) }

    /** §20.6 K4: catch up the group's commits before deriving call keys (public for the call layer). */
    suspend fun catchUpGroup(conv: String) = withContext(io) { runCatching { catchUp(conv) } }

    // ---- Calls: sending (§16.3, §16.4) ----

    /**
     * One ephemeral `call:signal` through the conversation's lane (encrypt and push together);
     * stale_epoch → catch up and re-encrypt with the same client_msg_id.
     */
    suspend fun sendCallSignal(conv: String, peer: String, env: lk.codegen.risime.calls.CallEnvelope.Env, media: String = lk.codegen.risime.calls.CallEnvelope.MEDIA_AUDIO): PushResult<lk.codegen.risime.net.CallSignalReply> =
        withContext(io) { sendCallSignalImpl(conv, peer, env, media) }

    private suspend fun sendCallSignalImpl(conv: String, peer: String?, env: lk.codegen.risime.calls.CallEnvelope.Env, media: String): PushResult<lk.codegen.risime.net.CallSignalReply> {
        val clientMsgId = newClientMsgId()
        val plaintext = lk.codegen.risime.calls.CallEnvelope.encode(env)
        var attempt = 0
        while (true) {
            val engine = mlsEngine() ?: return PushResult.Rejected(AuthErrors.NOT_E2EE)
            val r = lane(conv).withLock {
                val group = engine.group(conv) ?: return PushResult.Rejected(AuthErrors.NOT_E2EE)
                val ct = tx.run { engine.encrypt(conv, plaintext) }
                realtime().sendCallSignal(
                    lk.codegen.risime.net.CallSignalPush(
                        clientMsgId, peer, env.callId, lk.codegen.risime.calls.CallEnvelope.ringFor(env),
                        java.util.Base64.getEncoder().encodeToString(ct), group.generation, group.epoch, isoMillis(clock()),
                        // §19.2: the call's media on every signal (1:1 voice calls keep the v1.13 shape; §20.3 groups always carry it).
                        media.takeIf { it == lk.codegen.risime.calls.CallEnvelope.MEDIA_VIDEO || peer == null },
                        conversationId = conv.takeIf { peer == null },
                    ),
                )
            }
            val reason = (r as? PushResult.Rejected)?.reason
            if (reason != AuthErrors.STALE_EPOCH) return r
            if (attempt++ >= staleEpochRetries) return r
            catchUp(conv)
        }
    }

    /**
     * §16.2 my durable `call_end`: an outgoing outbox row (kind call) that is also my history line.
     * Nothing is queued when this call already has a line (the peer's `call_end` came first).
     */
    suspend fun queueCallEnd(conv: String, peer: String, env: lk.codegen.risime.calls.CallEnvelope.End, rangUnanswered: Boolean = false): String? {
        val me = meId() ?: return null
        if (messages.callLine(conv, env.callId) != null) return null
        val id = newClientMsgId()
        val line = lk.codegen.risime.calls.CallLines.line(env.reason, true, env.durationS, rangUnanswered, env.media == lk.codegen.risime.calls.CallEnvelope.MEDIA_VIDEO)
        messages.insert(
            MessageEntity(
                clientMsgId = id, messageId = null, conversationId = conv, from = me, to = peer, body = line.text,
                serverTs = null, localTs = clock(), status = MessageStatus.PENDING.name, outgoing = true,
                kind = MessageEntity.KIND_CALL,
                systemJson = lk.codegen.risime.net.ProtocolJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), lk.codegen.risime.calls.CallEnvelope.toJson(env)),
                callId = env.callId,
            ),
        )
        deletes?.unhide(conv)
        scope.launch { flushOutbox() }
        return id
    }

    /**
     * Decision 054: "Missed voice call" when this device rang and no durable `call_end` came (the
     * caller crashed or was killed mid-ring). One line per call id: a later `call_end` is dropped
     * against it (and this is a no-op when that line came first). Unread, never acked (no message id).
     * @return true if the line was inserted.
     */
    suspend fun insertLocalMissedCall(conv: String, peer: String, callId: String, video: Boolean = false): Boolean {
        val me = meId() ?: return false
        if (messages.callLine(conv, callId) != null) return false
        val now = clock()
        val media = if (video) lk.codegen.risime.calls.CallEnvelope.MEDIA_VIDEO else lk.codegen.risime.calls.CallEnvelope.MEDIA_AUDIO
        val env = lk.codegen.risime.calls.CallEnvelope.End(callId, lk.codegen.risime.calls.CallEnvelope.R_TIMEOUT, media = media)
        val row = MessageEntity(
            clientMsgId = "local-missed:$callId", messageId = null, conversationId = conv, from = peer, to = me,
            body = if (video) lk.codegen.risime.calls.CallLines.MISSED_VIDEO else lk.codegen.risime.calls.CallLines.MISSED, serverTs = isoMillis(now), localTs = now,
            status = MessageStatus.DELIVERED.name, outgoing = false, ackedStatus = MessageStatus.READ.name,
            kind = MessageEntity.KIND_CALL,
            systemJson = lk.codegen.risime.net.ProtocolJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), lk.codegen.risime.calls.CallEnvelope.toJson(env)),
            callId = callId,
        )
        if (messages.insert(row) == -1L) return false
        deletes?.unhide(conv)
        return true
    }

    /**
     * §17.2: content-free gap rows, in this event's transaction (with the cursor and the marker).
     * Never for a message already held, deleted (hidden tombstone) or at/before a Clear chat watermark.
     */
    private suspend fun recordGaps(gaps: List<lk.codegen.risime.data.mls.GapInfo>) {
        val dao = historyDao ?: return
        for (g in gaps) {
            if (applier?.cleared(g.conversationId, lk.codegen.risime.data.deletes.TimeUuid.ticks(g.messageId)) == true) continue
            if (messages.byMessageId(g.messageId) != null) continue
            if (deletes?.deletedId(g.messageId) != null) continue
            dao.insertGap(
                lk.codegen.risime.data.db.HistoryGapEntity(
                    messageId = g.messageId, conversationId = g.conversationId, clientMsgId = g.clientMsgId, from = g.from.lowercase(),
                    fromDevice = g.fromDevice?.lowercase(), serverTs = g.serverTs, generation = g.generation, epoch = g.epoch, createdAt = clock(),
                ),
            )
        }
    }

    /** §17.2: a `delete` event covering gap rows removes them (deletes win; nothing deleted comes back). */
    private suspend fun dropGaps(d: lk.codegen.risime.net.DeleteEvent) {
        val dao = historyDao ?: return
        val ids = d.targets.map { it.messageId.lowercase() }.filter { id -> dao.gap(id)?.conversationId?.equals(d.conversationId, true) == true }
        if (ids.isNotEmpty()) dao.deleteGaps(ids)
    }

    private suspend fun upsertMarker(conversationId: String, action: String, serverTs: String?) {
        // §15.7: no marker at or before a Clear chat watermark.
        if (applier?.cleared(conversationId, lk.codegen.risime.data.deletes.TimeUuid.ticksOfIso(serverTs)) == true) return
        messages.upsertSystemLine(HistoryMarkers.row(conversationId, action, serverTs, clock()))
    }

    // ---- Deletes (§15) ----

    /** §15.4–§15.6 a delete for everyone from [deleter] (attested user id; plaintext: the event's `from`). */
    private suspend fun applyDeleteEveryone(me: String, d: lk.codegen.risime.net.DeleteEvent, deleter: String, senderIsAdmin: Boolean?, e2ee: Boolean) {
        val a = applier ?: return
        if (a.cleared(d.conversationId, lk.codegen.risime.data.deletes.TimeUuid.ticks(d.messageId))) return
        if (a.applyEveryone(d.conversationId, deleter, d.serverTs, d.targets, senderIsAdmin, e2ee, historyBefore, me)) deletesApplied = true
    }

    /**
     * §15.5 a plaintext `delete` (legacy DMs only: the server's authorisation is the check). Never in
     * a group or a DM this device holds an MLS group for (those carry ciphertext; a plaintext one there
     * could only come from a misbehaving server).
     */
    private suspend fun applyPlainDelete(me: String, d: lk.codegen.risime.net.DeleteEvent) {
        if (isGroupConversation(d.conversationId) || mlsEngine()?.group(d.conversationId) != null) {
            log("plaintext delete ${d.messageId} in an e2ee conversation: ignored")
            return
        }
        if (d.from.equals(me, true) && deletes?.outboxRow(d.clientMsgId) != null) return completeOwnDelete(me, d)
        applyDeleteEveryone(me, d, d.from, null, e2ee = false)
    }

    /**
     * §15.6 my own control (crypto R4): an outbox row with that client_msg_id completes (a crash before
     * the reply); without one, only cleartext targets whose local row is mine, or any if group_meta names
     * me admin now.
     */
    private suspend fun completeOwnDelete(me: String, d: lk.codegen.risime.net.DeleteEvent) {
        val a = applier ?: return
        val dao = deletes ?: return
        if (a.cleared(d.conversationId, lk.codegen.risime.data.deletes.TimeUuid.ticks(d.messageId))) return
        val row = dao.outboxRow(d.clientMsgId)
        val eventIds = d.targets.map { it.messageId.lowercase() }.toSet()
        val ids = if (row != null) decodeIds(row.targetsJson).filter { it in eventIds } else eventIds.toList()
        val admin = isGroupConversation(d.conversationId) && mlsEngine()?.groupMeta(d.conversationId)?.admins?.any { it.equals(me, true) } == true
        for (id in ids) {
            val local = messages.byMessageId(id) ?: continue
            if (!local.conversationId.equals(d.conversationId, true) || local.system || local.deleted) continue
            if (row == null && !local.from.equals(me, true) && !admin) continue
            a.purge(local, me, byAdmin = !local.from.equals(me, true))
            deletesApplied = true
        }
        if (row != null) dao.removeOutbox(row.clientMsgId)
    }

    /** §15.4: the core couldn't verify the control (Malformed): a note on the stored targets, never a silent drop. */
    private suspend fun markUnverified(d: lk.codegen.risime.net.DeleteEvent) {
        val a = applier ?: return
        if (a.cleared(d.conversationId, lk.codegen.risime.data.deletes.TimeUuid.ticks(d.messageId))) return
        a.markUnverified(d.conversationId, d.targets)
    }

    private fun decodeIds(json: String): List<String> =
        lk.codegen.risime.data.deletes.DeleteJson.ids(json)

    /**
     * §13.3 R5: a row restored from the inbox that is clearly historical (before `history_before`, or
     * more than [HISTORICAL_MS] older than the device clock) takes its local time from `server_ts`.
     * Null = live: keep the device clock.
     */
    private fun historicalLocalTs(serverTs: String?): Long? {
        val ts = HistoryMarkers.epochMs(serverTs) ?: return null
        val hb = historyBefore?.toEpochMilli()
        return ts.takeIf { (hb != null && it < hb) || it < clock() - HISTORICAL_MS }
    }

    /** @return true if a new incoming message was stored (needs a delivered ack). */
    private suspend fun applyMessage(
        me: String,
        m: MessageData,
        body: String,
        kind: String = MessageEntity.KIND_TEXT,
        blobId: String? = null,
        /** In the same transaction, right after the new row (§14.7: the image's media row). */
        onInserted: suspend (MessageEntity) -> Unit = {},
    ): Boolean {
        val a = applier
        // §15.7: nothing at or before a Clear chat watermark comes back (parked replays, re-login replays).
        if (a != null && a.cleared(m.conversationId, lk.codegen.risime.data.deletes.TimeUuid.ticks(m.messageId))) return false
        val existing = messages.byMessageId(m.messageId)
        // §15.6 (crypto R3): a (hidden or placed) tombstone is re-judged now that the message is decrypted.
        val arrival = when {
            existing != null && (a == null || !existing.clientMsgId.startsWith(lk.codegen.risime.data.deletes.DeleteApplier.PLACEHOLDER)) -> return false
            a != null -> a.judgeArrival(m.conversationId, m.messageId, m.from, m.serverTs)
            else -> lk.codegen.risime.data.deletes.DeleteApplier.Arrival.Store
        }
        if (existing != null && arrival != lk.codegen.risime.data.deletes.DeleteApplier.Arrival.Store) return false // the placed tombstone stays
        if (arrival == lk.codegen.risime.data.deletes.DeleteApplier.Arrival.Drop) return false
        val outgoing = m.from.equals(me, ignoreCase = true)
        messages.byClientMsgId(m.clientMsgId)?.let { row ->
            // §13.1 S-a: my own copy for a PENDING outbox row (the msg:send reply was lost): it reached the server.
            if (outgoing && row.outgoing && row.status == MessageStatus.PENDING.name) {
                messages.updateStatus(row.clientMsgId, MessageStatus.SENT.name, m.messageId, m.serverTs, null)
                if (row.deleteState == MessageEntity.DELETE_STATE_CANCEL_AFTER_SEND) cancelAfterSend(row.clientMsgId, m.messageId)
            }
            return false
        }
        if (arrival is lk.codegen.risime.data.deletes.DeleteApplier.Arrival.Tombstone) {
            // Authorised: stored as a tombstone, never notified, unread or acked; no image key.
            messages.insert(
                MessageEntity(
                    clientMsgId = m.clientMsgId, messageId = m.messageId, conversationId = m.conversationId, from = m.from,
                    to = m.to ?: m.conversationId, body = "", serverTs = m.serverTs, localTs = historicalLocalTs(m.serverTs) ?: clock(),
                    status = if (outgoing) MessageStatus.SENT.name else MessageStatus.READ.name, outgoing = outgoing,
                    ackedStatus = if (outgoing) null else MessageStatus.READ.name, kind = MessageEntity.KIND_DELETED,
                    deletedBy = arrival.by, deletedByAdmin = arrival.byAdmin, deletedAt = clock(),
                ),
            )
            return false
        }
        val restored = historicalLocalTs(m.serverTs)
        // S-b: received history from before this install was handled by the old one: read, no acks, no badge.
        val preInstall = !outgoing && historyBefore?.let { hb -> HistoryMarkers.epochMs(m.serverTs)?.let { it < hb.toEpochMilli() } } == true
        val row = MessageEntity(
            clientMsgId = m.clientMsgId,
            messageId = m.messageId,
            conversationId = m.conversationId,
            from = m.from,
            to = m.to ?: m.conversationId, // §12.7: group messages have no `to`
            body = body,
            serverTs = m.serverTs,
            localTs = restored ?: clock(),
            status = when {
                outgoing -> MessageStatus.SENT
                preInstall -> MessageStatus.READ
                else -> MessageStatus.DELIVERED
            }.name,
            outgoing = outgoing,
            ackedStatus = if (preInstall) MessageStatus.READ.name else null,
            kind = kind,
            blobId = blobId,
            fromDevice = m.fromDevice?.lowercase(),
        )
        if (messages.insert(row) == -1L) return false
        deletes?.unhide(row.conversationId) // §15.7 Delete chat: a new message brings the chat back
        onInserted(row)
        if (outgoing || preInstall) return false
        onIncomingFrom(m.from)
        return true
    }

    private suspend fun applyStatus(s: StatusData) {
        val row = messages.byClientMsgId(s.clientMsgId) ?: messages.byMessageId(s.messageId) ?: return
        if (!row.outgoing) return
        val incoming = MessageStatus.fromWire(s.status) ?: return
        val current = MessageStatus.valueOf(row.status)
        val next = current.advance(incoming)
        if (next != current || row.messageId == null) {
            messages.updateStatus(row.clientMsgId, next.name, s.messageId, null, if (next == MessageStatus.FAILED) row.failReason else null)
        }
    }

    // ---- Outbox ----

    /**
     * Insert as pending first, then try to push. [target] is a conversation id (`dm:`/`grp:`) or,
     * for a DM, the peer's user id. Returns the client_msg_id.
     */
    suspend fun sendText(target: String, text: String): String? {
        val body = text.trim()
        // §11.1: the server counts graphemes (authoritative); the composer warns with ICU. Here only the byte cap.
        if (body.isEmpty() || body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) return null
        val me = meId() ?: return null
        val id = newClientMsgId()
        val conv = conversationFor(me, target)
        // §12 (Room v5): `to_id` holds the conversation id for groups.
        val to = dmPeer(conv, me) ?: conv
        val previous = messages.lastInConversation(conv)
        val now = clock()
        messages.insert(
            MessageEntity(
                clientMsgId = id,
                messageId = null,
                conversationId = conv,
                from = me,
                to = to,
                body = body,
                serverTs = null,
                localTs = now,
                status = MessageStatus.PENDING.name,
                outgoing = true,
            ),
        )
        deletes?.unhide(conv) // §15.7: a deleted chat comes back with my new message
        behaviour?.messageSent(to, body.length, previous?.takeIf { !it.outgoing }?.let { now - it.localTs })
        scope.launch { flushOutbox() }
        return id
    }

    /** Push every pending message in local_ts order with its original client_msg_id. */
    /**
     * §10.4: e2ee conversations are encrypted **at send time** (inside a transaction, the ratchet
     * advances). stale_epoch → catch up, re-encrypt with the same client_msg_id, up to
     * [staleEpochRetries] times, then back off; e2ee_required → catch up and encrypt (never FAILED).
     */
    private suspend fun send(m: MessageEntity): PushResult<lk.codegen.risime.net.MsgSendReply> {
        if (m.call) {
            // §16.2: the stored call_end envelope, encrypted at send time; e2ee only (never plaintext).
            val json = m.systemJson ?: return PushResult.Rejected(AuthErrors.BAD_REQUEST)
            // §20.4: a group call's `group_call` goes `silent` (no push), and only its envelope fields (no local flags).
            val groupCall = lk.codegen.risime.calls.GroupCallEnvelope.decode(json)
            val payload = groupCall?.encode() ?: json.toByteArray(Charsets.UTF_8)
            return sendPayload(m.conversationId, m.to, m.clientMsgId, m.localTs, { payload }, silent = groupCall != null) { PushResult.Rejected(AuthErrors.NOT_E2EE) }
        }
        if (m.image) {
            // §14.7 Sending 6: the stored envelope, encrypted at send time; never in plaintext.
            val env = images?.envelope(m.clientMsgId) ?: return PushResult.Rejected(IMAGE_UNAVAILABLE)
            return sendPayload(m.conversationId, m.to, m.clientMsgId, m.localTs, { env }) { PushResult.Rejected(AuthErrors.NOT_E2EE) }
        }
        return sendPayload(m.conversationId, m.to, m.clientMsgId, m.localTs, { lk.codegen.risime.data.mls.MlsPayload.text(m.body) }) {
            realtime().sendMessage(MsgSend(m.clientMsgId, m.to, m.body, isoMillis(m.localTs)))
        }
    }

    /** e2ee when the conversation has a group (envelope encrypted at send time), else [plain]; shared retry loops. */
    private suspend fun sendPayload(
        conv: String,
        to: String,
        clientMsgId: String,
        localTs: Long,
        envelope: () -> ByteArray,
        silent: Boolean = false,
        plain: suspend () -> PushResult<lk.codegen.risime.net.MsgSendReply>,
    ): PushResult<lk.codegen.risime.net.MsgSendReply> {
        var attempt = 0
        while (true) {
            val engine = mlsEngine()
            val group = engine?.group(conv)
            // §15.7 (android R6): one serial encrypt-and-push lane for every application message
            // (text, reaction, image, delete): generation n is pushed before n+1 is encrypted.
            val r = lane(conv).withLock { if (isGroupConversation(conv)) {
                // §12.9: groups are e2ee-only; without the local group (Welcome not here yet) the message waits.
                if (engine == null || group == null) return PushResult.Rejected(WAITING_FOR_GROUP)
                val ct = tx.run { engine.encrypt(conv, envelope()) }
                realtime().sendGroup(
                    lk.codegen.risime.net.MsgSendGroup(clientMsgId, conv, java.util.Base64.getEncoder().encodeToString(ct), group.generation, group.epoch, isoMillis(localTs), silent.takeIf { it }),
                )
            } else if (engine != null && group != null) {
                val ct = tx.run { engine.encrypt(conv, envelope()) }
                realtime().sendEncrypted(
                    MsgSendE2ee(clientMsgId, to, java.util.Base64.getEncoder().encodeToString(ct), group.generation, group.epoch, isoMillis(localTs), silent.takeIf { it }),
                )
            } else {
                plain()
            } }
            val reason = (r as? PushResult.Rejected)?.reason
            if (reason != AuthErrors.STALE_EPOCH && reason != AuthErrors.E2EE_REQUIRED) return r
            if (attempt++ >= staleEpochRetries) {
                if (!isGroupConversation(conv)) onDmNeedsRepair(conv) // v1.16: maybe no longer in the DM group
                return PushResult.Rejected(RETRY_LATER)
            }
            catchUp(conv)
            if (reason == AuthErrors.E2EE_REQUIRED && mlsEngine()?.group(conv) == null) {
                // v1.16: an e2ee DM this phone has no group for: ask to be re-added; the message waits (pending).
                if (!isGroupConversation(conv) && mlsEngine() != null) {
                    onDmNeedsRepair(conv)
                    return PushResult.Rejected(WAITING_FOR_GROUP)
                }
                return PushResult.Rejected(RETRY_LATER)
            }
            if (isGroupConversation(conv) && mlsEngine()?.group(conv) == null) return PushResult.Rejected(WAITING_FOR_GROUP)
        }
    }

    /**
     * §18.1/§18.4 a silent control (only `profile_photo`): encrypted at send time through the
     * conversation's lane with an empty `authenticated_data` and `silent: true`; e2ee only, never
     * plaintext, never a row. stale_epoch → catch up and re-encrypt with the same client_msg_id.
     */
    suspend fun sendSilent(conv: String, plaintext: ByteArray): PushResult<lk.codegen.risime.net.MsgSendReply> = withContext(io) {
        val me = meId() ?: return@withContext PushResult.Unavailable
        sendPayload(conv, conversationPeer(conv, me), newClientMsgId(), clock(), { plaintext }, silent = true) { PushResult.Rejected(AuthErrors.NOT_E2EE) }
    }

    // ---- Reactions (§11.2) ----

    /**
     * My tap: shown at once (pending), sent after [reactionDebounceMs] if nothing changed, as the
     * final state only. Only on messages that have a server message_id.
     */
    suspend fun react(target: String, targetMessageId: String, emoji: String, op: String) {
        val store = reactionStore ?: return
        val me = meId() ?: return
        val conv = conversationFor(me, target)
        val row = store.tap(conv, targetMessageId, me, emoji, op)
        scope.launch {
            delay(reactionDebounceMs)
            val cur = reactionsDao?.get(conv, targetMessageId, me, emoji)
            if (cur != null && cur.localTs == row.localTs) flushReactions(debounced = false)
        }
    }

    private suspend fun flushReactions(debounced: Boolean = true): Unit = reactionLock.withLock {
        val store = reactionStore ?: return@withLock
        val dao = reactionsDao ?: return@withLock
        val me = meId() ?: return@withLock
        for (r0 in dao.pending()) {
            if (debounced && clock() - r0.localTs < reactionDebounceMs) continue // its own timer sends it
            if (!store.needsSend(r0)) { store.settleNoSend(r0); continue }
            val r = store.assignId(r0, newClientMsgId)
            val peer = conversationPeer(r.conversationId, me)
            val body = lk.codegen.risime.net.ReactionBody(r.targetMessageId, r.emoji, r.op)
            val res = sendPayload(r.conversationId, peer, r.pendingClientMsgId!!, r.localTs, { lk.codegen.risime.data.mls.MlsPayload.reaction(body.target, body.emoji, body.op) }) {
                realtime().sendReaction(lk.codegen.risime.net.MsgSendReaction(r.pendingClientMsgId, peer, body, isoMillis(r.localTs)))
            }
            when (res) {
                is PushResult.Ok -> store.confirmOwn(r, res.value.serverTs, res.value.messageId)
                is PushResult.Rejected -> if (res.reason == WAITING_FOR_GROUP) {
                    continue
                } else if (res.reason == "rate_limited" || res.reason == RETRY_LATER) {
                    scope.launch { delay(rateLimitRetryMs); flushReactions() }
                    return@withLock
                } else {
                    store.revert(r) // unknown_target, invalid_emoji, not_friends, bad_request, …
                }
                PushResult.Unavailable -> return@withLock // retried on the next onLive()
            }
        }
    }

    /** The `to` of a send: the DM peer, or the conversation id itself for a group. */
    private fun conversationPeer(conv: String, me: String): String = dmPeer(conv, me) ?: conv

    private suspend fun applyReaction(conv: String, target: String, reactor: String, emoji: String, op: String, ts: String, messageId: String, clientMsgId: String?) {
        if (applier != null) {
            if (applier.cleared(conv, lk.codegen.risime.data.deletes.TimeUuid.ticks(messageId))) return
            // §15.6: reactions on a tombstone (or a message deleted for me) are dropped and never notified.
            val row = messages.byMessageId(target)
            if (row != null && row.showsAsDeleted) return
            if (deletes?.deletedId(target)?.scope == lk.codegen.risime.net.MsgDelete.SCOPE_ME) return
        }
        reactionStore?.applyConfirmed(conv, target, reactor, emoji, op, ts, messageId, clientMsgId)
    }

    /** §15.7 (android R2): a pushed message the user deleted meanwhile: now that it has a message_id, delete it for everyone. */
    private suspend fun cancelAfterSend(clientMsgId: String, messageId: String) {
        val dao = deletes ?: return
        val row = messages.byClientMsgId(clientMsgId) ?: return
        dao.setDeleteState(listOf(clientMsgId), MessageEntity.DELETE_STATE_DELETING)
        queueEveryone(row.conversationId, listOf(row.copy(messageId = messageId)))
        scope.launch { flushDeletes() }
    }

    // ---- Sending deletes (§15.2, §15.7). The UI that calls these is behind DeleteFeature.sendEnabled. ----

    /** §15.7: one notice for the chat that asked ("Couldn't delete for everyone", with the rows to offer "Delete for me"). */
    data class DeleteNotice(val conversationId: String, val text: String, val failedClientMsgIds: List<String>)

    val deleteNotices = kotlinx.coroutines.flow.MutableSharedFlow<DeleteNotice>(extraBufferCapacity = 8)

    /** Rows without a message_id (§15.7 pending rules): never pushed → cancelled locally; pushed → cancel after send. */
    private suspend fun cancelOrDefer(rows: List<MessageEntity>) {
        val dao = deletes ?: return
        for (r in rows) {
            val neverAccepted = r.status == MessageStatus.FAILED.name || r.sendAttempts == 0
            if (neverAccepted) {
                cancelUnsent(r)
            } else {
                dao.setDeleteState(listOf(r.clientMsgId), MessageEntity.DELETE_STATE_CANCEL_AFTER_SEND)
            }
        }
    }

    /** Never-pushed or refused message: it exists nowhere else (an image's upload is cancelled and an uploaded blob deleted). */
    private suspend fun cancelUnsent(r: MessageEntity) {
        if (r.image && images is lk.codegen.risime.data.media.ImageRepository) {
            images.deleteUnsent(r.clientMsgId)
            return
        }
        tx.run {
            messages.delete(r.clientMsgId)
            if (r.image) applier?.purge(r, meId() ?: "", byAdmin = false, tombstone = false)
        }
        if (r.image) deletesApplied = true
    }

    private suspend fun queueEveryone(conv: String, rows: List<MessageEntity>) {
        val dao = deletes ?: return
        for (chunk in rows.filter { it.messageId != null }.chunked(lk.codegen.risime.net.MsgDelete.MAX_TARGETS)) {
            dao.queue(
                lk.codegen.risime.data.db.DeleteOutboxEntity(
                    clientMsgId = newClientMsgId(), conversationId = conv, scope = lk.codegen.risime.net.MsgDelete.SCOPE_EVERYONE,
                    targetsJson = lk.codegen.risime.data.deletes.DeleteJson.encode(chunk.map { it.messageId!!.lowercase() }),
                    blobIdsJson = lk.codegen.risime.data.deletes.DeleteJson.encode(chunk.mapNotNull { it.blobId }),
                    state = lk.codegen.risime.data.db.DeleteOutboxEntity.QUEUED, createdAt = clock(),
                ),
            )
        }
    }

    /**
     * §15.7 Delete for everyone: the rows show as "You deleted this message" at once (content and ticks
     * kept until the reply), requests of ≤ 100 targets go through the outbox; pending rows follow the
     * pending rules (cancel, or cancel after send).
     */
    suspend fun deleteForEveryone(conversationId: String, clientMsgIds: List<String>) {
        val dao = deletes ?: return
        outboxLock.withLock {
            val rows = clientMsgIds.mapNotNull { messages.byClientMsgId(it) }.filter { it.conversationId == conversationId && !it.system && !it.deleted }
            cancelOrDefer(rows.filter { it.messageId == null })
            val sent = rows.filter { it.messageId != null }
            tx.run {
                dao.setDeleteState(sent.map { it.clientMsgId }, MessageEntity.DELETE_STATE_DELETING)
                queueEveryone(conversationId, sent)
            }
        }
        afterDeletes()
        scope.launch { flushDeletes() }
    }

    /**
     * §15.7 Delete for me: the rows (or tombstones, system lines) disappear here, a hidden tombstone
     * (scope me) keeps a replay from bringing them back, then `msg:delete` `me` for my own inbox copies.
     */
    suspend fun deleteForMe(conversationId: String, clientMsgIds: List<String>) {
        val dao = deletes ?: return
        val a = applier ?: return
        val me = meId() ?: return
        outboxLock.withLock {
            val rows = clientMsgIds.mapNotNull { messages.byClientMsgId(it) }.filter { it.conversationId == conversationId }
            cancelOrDefer(rows.filter { it.messageId == null && !it.system })
            val local = rows.filter { it.messageId != null || it.system }
            val requested = mutableListOf<String>()
            tx.run {
                for (r in local) {
                    a.purge(r, me, byAdmin = false, tombstone = false)
                    val id = r.messageId?.lowercase() ?: continue
                    dao.putDeletedId(lk.codegen.risime.data.db.DeletedIdEntity(id, conversationId, me, false, null, lk.codegen.risime.net.MsgDelete.SCOPE_ME, clock()))
                    // Tombstones and placed rows have no server copy left: local only.
                    if (!r.deleted) requested += id
                }
                for (chunk in requested.chunked(lk.codegen.risime.net.MsgDelete.MAX_TARGETS)) {
                    dao.queue(
                        lk.codegen.risime.data.db.DeleteOutboxEntity(
                            clientMsgId = newClientMsgId(), conversationId = conversationId, scope = lk.codegen.risime.net.MsgDelete.SCOPE_ME,
                            targetsJson = lk.codegen.risime.data.deletes.DeleteJson.encode(chunk), blobIdsJson = "[]",
                            state = lk.codegen.risime.data.db.DeleteOutboxEntity.QUEUED, createdAt = clock(),
                        ),
                    )
                }
            }
            if (local.isNotEmpty()) deletesApplied = true
        }
        afterDeletes()
        scope.launch { flushDeletes() }
    }

    /**
     * §15.7 Clear chat ([hide] = false) / Delete chat ([hide] = true): every row of the chat goes (pushed
     * pending messages still complete and appear after it), with the `cleared_upto` watermark in the
     * same transaction; then `chat:clear` for my inbox. Never touches MLS state, the cursor, pending
     * `msg:delete` requests or membership.
     */
    suspend fun clearChat(conversationId: String, hide: Boolean) {
        val dao = deletes ?: return
        val a = applier ?: return
        val me = meId() ?: return
        outboxLock.withLock {
            tx.run {
                val rows = dao.conversationRows(conversationId)
                val keep = rows.filter { it.outgoing && it.status == MessageStatus.PENDING.name && it.messageId == null && it.sendAttempts > 0 }.toSet()
                val remove = rows - keep
                val cursor = sync.cursor()?.takeIf { lk.codegen.risime.data.deletes.TimeUuid.ticks(it) != null }
                val candidates = listOfNotNull(cursor) + remove.mapNotNull { it.messageId }.filter { lk.codegen.risime.data.deletes.TimeUuid.ticks(it) != null }
                val upto = candidates.maxByOrNull { lk.codegen.risime.data.deletes.TimeUuid.ticks(it)!! }
                for (r in remove) {
                    if (r.image) a.purge(r, me, byAdmin = false, tombstone = false) else messages.delete(r.clientMsgId)
                }
                dao.deleteConversationReactions(conversationId)
                // §17.13: Clear chat and Delete chat delete the gap rows in the purge transaction.
                historyDao?.deleteConversationGaps(conversationId)
                val prev = dao.chatState(conversationId)?.clearedUpto
                val ticks = listOfNotNull(prev, upto?.let { lk.codegen.risime.data.deletes.TimeUuid.ticks(it) }).maxOrNull()
                dao.putChatState(lk.codegen.risime.data.db.ChatStateEntity(conversationId, ticks, hidden = hide))
                if (upto != null) {
                    dao.queue(
                        lk.codegen.risime.data.db.DeleteOutboxEntity(
                            clientMsgId = newClientMsgId(), conversationId = conversationId, scope = lk.codegen.risime.data.db.DeleteOutboxEntity.SCOPE_CLEAR,
                            targetsJson = "[]", blobIdsJson = "[]", state = lk.codegen.risime.data.db.DeleteOutboxEntity.QUEUED, createdAt = clock(), upto = upto.lowercase(),
                        ),
                    )
                }
            }
            deletesApplied = true
        }
        afterDeletes()
        // §17.13: an open history request for this chat is cancelled.
        history?.let { h -> runCatching { h.onChatCleared(conversationId) }.onFailure { log("history clear: ${it.message}") } }
        scope.launch { flushDeletes() }
    }

    /** The delete outbox: `msg:delete` (me / everyone) and `chat:clear`, each retried with its own client_msg_id. */
    suspend fun flushDeletes(): Unit = withContext(io) { flushDeletesImpl() }

    private suspend fun flushDeletesImpl(): Unit = deleteLock.withLock {
        val dao = deletes ?: return@withLock
        for (o in dao.queued()) {
            if (o.nextAt > clock()) continue
            val targets = lk.codegen.risime.data.deletes.DeleteJson.ids(o.targetsJson)
            when (o.scope) {
                lk.codegen.risime.data.db.DeleteOutboxEntity.SCOPE_CLEAR -> when (val r = realtime().clearChat(lk.codegen.risime.net.ChatClear(o.conversationId, o.upto ?: ""))) {
                    is PushResult.Ok -> dao.removeOutbox(o.clientMsgId)
                    is PushResult.Rejected -> if (r.reason == "rate_limited") retryLater(o) else { log("chat:clear refused: ${r.reason}"); dao.removeOutbox(o.clientMsgId) }
                    PushResult.Unavailable -> return@withLock
                }
                lk.codegen.risime.net.MsgDelete.SCOPE_ME -> when (val r = realtime().deleteMessages(lk.codegen.risime.net.MsgDelete(o.clientMsgId, o.conversationId, lk.codegen.risime.net.MsgDelete.SCOPE_ME, targets))) {
                    is PushResult.Ok -> dao.removeOutbox(o.clientMsgId)
                    // Failures are logged, never shown (§15.7).
                    is PushResult.Rejected -> if (r.reason == "rate_limited") retryLater(o) else { log("delete for me refused: ${r.reason}"); dao.removeOutbox(o.clientMsgId) }
                    PushResult.Unavailable -> return@withLock
                }
                else -> if (!sendEveryone(o, targets)) return@withLock
            }
        }
    }

    private suspend fun retryLater(o: lk.codegen.risime.data.db.DeleteOutboxEntity) {
        deletes?.updateOutbox(o.copy(attempts = o.attempts + 1, nextAt = clock() + rateLimitRetryMs))
        scope.launch { delay(rateLimitRetryMs); flushDeletes() }
    }

    /** One `msg:delete` `everyone`; false = stop the flush (offline). */
    private suspend fun sendEveryone(o: lk.codegen.risime.data.db.DeleteOutboxEntity, targets: List<String>): Boolean {
        val dao = deletes ?: return true
        val conv = o.conversationId
        val blobIds = lk.codegen.risime.data.deletes.DeleteJson.ids(o.blobIdsJson)
        var attempt = 0
        var res: PushResult<lk.codegen.risime.net.MsgDeleteReply>
        while (true) {
            val engine = mlsEngine()
            val group = engine?.group(conv)
            res = lane(conv).withLock {
                if (isGroupConversation(conv) && (engine == null || group == null)) return true // waits for the group (Welcome)
                val msg = if (engine != null && group != null) {
                    // §15.3: the envelope, with the targets bound into the PrivateMessage's authenticated_data.
                    val ct = tx.run { engine.encryptWithAad(conv, lk.codegen.risime.data.mls.MlsPayload.delete(targets), lk.codegen.risime.data.deletes.DeleteAad.encode(targets)) }
                    lk.codegen.risime.net.MsgDelete(
                        o.clientMsgId, conv, lk.codegen.risime.net.MsgDelete.SCOPE_EVERYONE, targets, blobIds,
                        java.util.Base64.getEncoder().encodeToString(ct), group.generation, group.epoch, isoMillis(o.createdAt),
                    )
                } else {
                    lk.codegen.risime.net.MsgDelete(o.clientMsgId, conv, lk.codegen.risime.net.MsgDelete.SCOPE_EVERYONE, targets, clientTs = isoMillis(o.createdAt))
                }
                realtime().deleteMessages(msg)
            }
            val reason = (res as? PushResult.Rejected)?.reason
            if (reason != AuthErrors.STALE_EPOCH && reason != AuthErrors.E2EE_REQUIRED) break
            if (attempt++ >= staleEpochRetries) { retryLater(o); return true }
            catchUp(conv)
        }
        val me = meId() ?: return true
        when (val r = res) {
            is PushResult.Ok -> {
                // §15.7 step 3: purge `deleted` and `gone` (gone = already deleted, idempotent).
                val done = (r.value.deleted + r.value.gone).map { it.lowercase() }.toSet()
                tx.run {
                    for (id in targets.filter { it in done }) {
                        val row = messages.byMessageId(id) ?: continue
                        if (!row.deleted) applier?.purge(row, me, byAdmin = !row.from.equals(me, true))
                    }
                    dao.removeOutbox(o.clientMsgId)
                }
                deletesApplied = true
                afterDeletes()
            }
            is PushResult.Rejected -> when (r.reason) {
                "rate_limited", RETRY_LATER -> retryLater(o)
                else -> refused(o, targets, r)
            }
            PushResult.Unavailable -> return false
        }
        return true
    }

    /**
     * §15.7 a refusal: the rows come back as they were; the failing targets are offered "Delete for
     * me"; the others (all-or-nothing refused them too) are requested again on their own.
     */
    private suspend fun refused(o: lk.codegen.risime.data.db.DeleteOutboxEntity, targets: List<String>, r: PushResult.Rejected) {
        val dao = deletes ?: return
        val failures = runCatching { r.body?.let { lk.codegen.risime.net.ProtocolJson.decodeFromJsonElement(lk.codegen.risime.net.DeleteError.serializer(), it).failures } }
            .getOrNull().orEmpty()
        val failing = failures.map { it.target.lowercase() }.toSet().ifEmpty { targets.toSet() }
        val rows = targets.mapNotNull { messages.byMessageId(it) }
        val retry = rows.filter { it.messageId!!.lowercase() !in failing }
        tx.run {
            dao.setDeleteState(rows.filter { it.messageId!!.lowercase() in failing }.map { it.clientMsgId }, null)
            dao.removeOutbox(o.clientMsgId)
            if (retry.isNotEmpty()) queueEveryone(o.conversationId, retry)
        }
        log("delete for everyone refused: ${r.reason} ${failures.size} failure(s)")
        val text = if (r.reason == AuthErrors.TOO_OLD) lk.codegen.risime.data.deletes.DeleteRules.TOO_OLD_TEXT else lk.codegen.risime.data.deletes.DeleteRules.FAILED
        deleteNotices.tryEmit(DeleteNotice(o.conversationId, text, rows.filter { it.messageId!!.lowercase() in failing }.map { it.clientMsgId }))
        if (retry.isNotEmpty()) scope.launch { flushDeletes() }
    }

    suspend fun flushOutbox(): Unit = withContext(io) {
        flushMessages()
        flushReactions()
        flushDeletesImpl()
    }

    /** First time each pending message was told "retry later" (in memory): after [OUTBOX_GIVE_UP_MS] it fails visibly. */
    private val retryingSince = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * The outbox keeps the order **per conversation** only. nightly.19: one message that could only be
     * retried later (an e2ee DM whose MLS group wasn't usable) stopped the whole outbox, so every new
     * message in every chat stayed pending (clock) for ever. Now a conversation that has to wait is
     * skipped for this pass (its later messages wait behind it), the others go out, and a message that
     * still can't be sent after [OUTBOX_GIVE_UP_MS] becomes "Not sent — tap to retry" with its reason.
     */
    private suspend fun flushMessages(): Unit = outboxLock.withLock {
        val waiting = HashSet<String>()
        var retry = false
        for (m in messages.pendingOutbox()) {
            if (m.conversationId in waiting) continue
            // §18.5 (android A2): a dirty group gets this device's photo before its next own message.
            if (isGroupConversation(m.conversationId)) profilePhotos?.let { runCatching { it.beforeOwnMessage(m.conversationId) } }
            deletes?.countAttempt(m.clientMsgId) // android R2: from now on it may be on the server
            when (val r = send(m)) {
                is PushResult.Ok -> {
                    val cur = messages.byClientMsgId(m.clientMsgId) ?: continue
                    val next = MessageStatus.valueOf(cur.status).advance(MessageStatus.SENT)
                    messages.updateStatus(m.clientMsgId, next.name, r.value.messageId, r.value.serverTs, null)
                    // §15.7: deleted while it was being sent: now delete it for everyone.
                    if (cur.deleteState == MessageEntity.DELETE_STATE_CANCEL_AFTER_SEND) cancelAfterSend(m.clientMsgId, r.value.messageId)
                    retryingSince.remove(m.clientMsgId)
                }
                is PushResult.Rejected -> if (r.reason == WAITING_FOR_GROUP) {
                    waiting += m.conversationId // stays PENDING; retried when the group arrives (and on every onLive)
                } else if (r.reason == "rate_limited" || r.reason == RETRY_LATER) {
                    val since = retryingSince.getOrPut(m.clientMsgId) { clock() }
                    if (r.reason == RETRY_LATER && clock() - since >= OUTBOX_GIVE_UP_MS) {
                        retryingSince.remove(m.clientMsgId)
                        messages.updateStatus(m.clientMsgId, MessageStatus.FAILED.name, null, null, E2EE_NOT_READY)
                    } else {
                        retry = true
                    }
                    waiting += m.conversationId
                } else {
                    retryingSince.remove(m.clientMsgId)
                    messages.updateStatus(m.clientMsgId, MessageStatus.FAILED.name, null, null, r.reason)
                }
                PushResult.Unavailable -> return@withLock // the socket is down: retried on the next onLive()
            }
        }
        if (retry) scope.launch {
            delay(rateLimitRetryMs)
            flushOutbox()
        }
    }

    /** FAILED → PENDING, then resend with the same client_msg_id. Returns false if it wasn't FAILED. */
    suspend fun retry(clientMsgId: String): Boolean {
        val row = messages.byClientMsgId(clientMsgId) ?: return false
        if (MessageStatus.valueOf(row.status).retry() == null) return false
        if (messages.retryFailed(clientMsgId) == 0) return false
        scope.launch { flushOutbox() }
        return true
    }

    /** Deletes a FAILED message (never accepted by the server), with an image's key and cached file. */
    suspend fun deleteFailed(clientMsgId: String): Boolean {
        val deleted = tx.run {
            (messages.deleteFailed(clientMsgId) > 0).also { if (it) images?.deleted(clientMsgId) }
        }
        return deleted
    }

    // ---- Acks ----

    /** The chat screen is showing this conversation: mark incoming as read and ack. */
    suspend fun markConversationRead(conversationId: String) {
        if (messages.markIncomingRead(conversationId) > 0) flushAcks()
    }

    /** Send delivered/read acks the server hasn't confirmed yet. Safe to call any time. */
    suspend fun flushAcks(): Unit = ackLock.withLock {
        val todo = messages.unackedIncoming()
        for ((status, rows) in todo.groupBy { it.status }) {
            val wire = MessageStatus.valueOf(status).wire
            for (chunk in rows.chunked(ACK_BATCH)) {
                when (realtime().ack(chunk.mapNotNull { it.messageId }, wire)) {
                    is PushResult.Ok -> messages.setAcked(chunk.map { it.clientMsgId }, status)
                    is PushResult.Rejected -> messages.setAcked(chunk.map { it.clientMsgId }, status) // don't loop on a bad ack
                    PushResult.Unavailable -> return@withLock
                }
            }
        }
    }

    companion object {
        const val MAX_BODY = 4096
        const val MAX_BODY_BYTES = 16 * 1024

        /** Local only: stale_epoch/e2ee_required couldn't be resolved now; stays PENDING, retried later. */
        const val RETRY_LATER = "retry_later"
        /** A message encryption couldn't send for [OUTBOX_GIVE_UP_MS] fails visibly with this reason. */
        const val E2EE_NOT_READY = "e2ee_not_ready"
        const val OUTBOX_GIVE_UP_MS = 30_000L

        /** Local only: an image row without a stored envelope (deleted meanwhile). */
        const val IMAGE_UNAVAILABLE = "image_unavailable"

        /** Local only: a group message whose MLS group isn't here yet; stays PENDING without blocking the outbox. */
        const val WAITING_FOR_GROUP = "waiting_for_group"
        const val ACK_BATCH = 100

        /** §13.3: rows this much older than the device clock are restored history. */
        const val HISTORICAL_MS = 5 * 60_000L
        private val ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun isoMillis(epochMs: Long): String = ISO_MILLIS.format(Instant.ofEpochMilli(epochMs))
    }
}

/** §12.6 blob download outcome. */
sealed interface BlobFetch {
    class Ok(val bytes: ByteArray) : BlobFetch

    /** 404 / expired / size or SHA-256 mismatch: unrecoverable for this event. */
    data object Gone : BlobFetch

    /** Network or server trouble: retry later. */
    data object Transient : BlobFetch
}
