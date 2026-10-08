package lk.codegen.risime.data.mls

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * One own commit per conversation at a time, from build to the server's answer, across every
 * committer on this device: the group-op outbox, the DM membership/op executor and the DM upgrade.
 *
 * The core keeps a built commit staged ("pending") until [MlsEngine.commitAccepted] or
 * [MlsEngine.commitRejected]; while one is staged it refuses to build another ("a commit is already
 * pending for this group") and to encrypt. So no staged commit may outlive the attempt that built
 * it: whatever ends the attempt (a thrown exception, a cancelled coroutine, a missed reject path),
 * [withCommit] drops what is still staged. Holding the lock means no submit of ours is in flight for
 * that conversation, so a commit found staged on entry is stale (an older build, a killed process)
 * and is dropped too. A dropped commit the server did take arrives later as a commit from this
 * device that can't be processed: the existing rejoin path (§12.8) covers that rare case.
 *
 * Different conversations are separate MLS groups (the core serialises its own calls), so a DM and
 * a group commit may build at the same time.
 */
class MlsCommitGate(private val log: (String) -> Unit = {}) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockOf(conversationId: String) = locks.computeIfAbsent(conversationId.lowercase()) { Mutex() }

    /** Runs [block] (build, submit, merge or reject) holding [conversationId]'s commit lock; see the class doc. */
    suspend fun <T> withCommit(conversationId: String, mls: MlsEngine, block: suspend () -> T): T =
        lockOf(conversationId).withLock {
            dropStaged(conversationId, mls, "stale")
            try {
                block()
            } finally {
                withContext(NonCancellable) { dropStaged(conversationId, mls, "left by a failed attempt") }
            }
        }

    /**
     * Start-up recovery: drops a staged commit in each of [conversationIds] (nothing of ours is in
     * flight in a new process). @return the conversations that had one.
     */
    suspend fun sweep(mls: MlsEngine, conversationIds: Collection<String>): List<String> =
        conversationIds.distinct().filter { conv -> lockOf(conv).withLock { dropStaged(conv, mls, "stale at start-up") } }

    private fun dropStaged(conversationId: String, mls: MlsEngine, why: String): Boolean = try {
        if (mls.hasPendingCommit(conversationId)) {
            mls.commitRejected(conversationId)
            log("mls $conversationId: dropped a staged commit ($why)")
            true
        } else {
            false
        }
    } catch (e: Exception) {
        log("mls $conversationId: couldn't check for a staged commit: ${e.javaClass.simpleName}")
        false
    }
}
