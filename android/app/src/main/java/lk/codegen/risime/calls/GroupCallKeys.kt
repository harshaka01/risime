package lk.codegen.risime.calls

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lk.codegen.risime.data.mls.CallKeys
import java.security.SecureRandom
import java.util.Base64

/**
 * Where frame keys go (LiveKit's `BaseKeyProvider` and this device's sender cryptors). Keys arrive
 * as their standard base64 text: livekit-android 2.29 takes a string and uses its bytes as the key
 * material (§20.6 Install), so every member must hand over exactly the same text.
 */
interface FrameKeySink {
    fun setKey(identity: String, keyText: String, index: Int)

    /** This device's own sending switches to [index] (its sender cryptors' key index). */
    fun setOwnIndex(index: Int)
}

/**
 * §20.6 (crypto K3, K4, K7, K10) the frame keys of one group call: installs every member's key at
 * `epoch mod 16`, switches this device's sending to the new index at once, keeps the previous
 * epoch's keys for [overlapMs] (then overwrites them with random bytes), never goes back to an older
 * epoch, and knows the roster (the identities of the current epoch's leaves). Keys stay in memory
 * only (the sink's key provider); [wipeAll] at the end of the call. Pure Kotlin (a fake sink in tests).
 */
class FrameKeyRing(
    private val sink: FrameKeySink,
    private val scope: CoroutineScope,
    private val overlapMs: Long = OVERLAP_MS,
    private val random: () -> ByteArray = { ByteArray(32).also { SecureRandom().nextBytes(it) } },
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val OVERLAP_MS = 10_000L
        const val RING_SIZE = 16

        /** §20.6 `key_index = e mod 16`. */
        fun keyIndex(epoch: Long): Int = Math.floorMod(epoch, RING_SIZE.toLong()).toInt()

        /** The key's standard base64 text (the key material livekit-android 2.29 takes, §20.6 Install). */
        fun keyText(key: ByteArray): String = Base64.getEncoder().encodeToString(key)
    }

    @Volatile var epoch: Long? = null
        private set

    @Volatile var index: Int = -1
        private set

    /** The current epoch's leaf identities (K7): anyone else in the room is "Not a member". */
    @Volatile var roster: Set<String> = emptySet()
        private set

    /** Which identities hold a key at each index now (so a wipe overwrites exactly those). */
    private val installed = HashMap<Int, Set<String>>()
    private val wipes = HashMap<Int, Job>()

    /**
     * Installs [keys] (K3): every identity at the new index, then this device's sending. Older or
     * equal epochs are ignored (K4: never derive from an epoch older than the newest known).
     * [keys] is wiped afterwards in every case. @return true when a new epoch was installed.
     */
    @Synchronized
    fun install(keys: CallKeys): Boolean {
        try {
            val cur = epoch
            if (cur != null && keys.epoch <= cur) return false
            val idx = keys.keyIndex
            require(idx == keyIndex(keys.epoch)) { "key index ${keys.keyIndex} for epoch ${keys.epoch}" }
            val ids = keys.identities.toSet()
            // The same index 16 epochs ago: identities that are gone lose their key there now.
            installed[idx]?.minus(ids)?.forEach { sink.setKey(it, keyText(random()), idx) }
            wipes.remove(idx)?.cancel()
            for (k in keys.keys) sink.setKey(k.identity, keyText(k.key), idx)
            installed[idx] = ids
            sink.setOwnIndex(idx)
            val prev = index
            epoch = keys.epoch
            index = idx
            roster = ids
            log("frame keys: epoch ${keys.epoch} at index $idx for ${ids.size} members")
            if (prev >= 0 && prev != idx) scheduleWipe(prev)
            return true
        } finally {
            keys.wipe()
        }
    }

    /** K3: receivers keep the previous epoch's keys [overlapMs], then they are overwritten. */
    private fun scheduleWipe(idx: Int) {
        wipes.remove(idx)?.cancel()
        wipes[idx] = scope.launch {
            delay(overlapMs)
            synchronized(this@FrameKeyRing) {
                if (index != idx) {
                    installed.remove(idx)?.forEach { sink.setKey(it, keyText(random()), idx) }
                    log("frame keys: index $idx wiped")
                }
                wipes.remove(idx)
            }
        }
    }

    /** K10: at the end of the call every installed key is overwritten; nothing remains. */
    @Synchronized
    fun wipeAll() {
        wipes.values.forEach { it.cancel() }
        wipes.clear()
        for ((idx, ids) in installed) ids.forEach { runCatching { sink.setKey(it, keyText(random()), idx) } }
        installed.clear()
        roster = emptySet()
        epoch = null
        index = -1
    }

    /** Test hook: the indexes holding keys now. */
    @Synchronized
    internal fun installedIndexes(): Set<Int> = installed.keys.toSet()
}
