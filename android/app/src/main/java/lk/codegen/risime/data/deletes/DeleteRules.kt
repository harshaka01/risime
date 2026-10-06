package lk.codegen.risime.data.deletes

import lk.codegen.risime.BuildConfig
import kotlinx.serialization.builtins.serializer
import java.time.Instant
import java.util.UUID

/**
 * Contract v1.12 §15.11 rollout: the receive side ships fully; the **send UI** (long-press Delete,
 * Select, Delete for me / for everyone) stays off until a later nightly turns it on.
 *
 * The single switch is `BuildConfig.DELETES_SEND_ENABLED` (app/build.gradle.kts, `defaultConfig`,
 * overridable with `-Prisime.deletesSend=true`). [sendEnabled] reads it once; it is a plain
 * variable so a future remote flag (or a test) can flip it at runtime without another code path.
 * Clear chat and Delete chat are local plus `chat:clear` on my own inbox, so they are always on.
 */
object DeleteFeature {
    @Volatile
    var sendEnabled: Boolean = BuildConfig.DELETES_SEND_ENABLED
}

/** Time helpers: TimeUUID (v1) times in 100 ns ticks since 1582-10-15, compared as times, never as strings. */
object TimeUuid {
    private const val GREGORIAN_OFFSET = 0x01B21DD213814000L

    /** The 100 ns tick count of a version-1 UUID, or null. */
    fun ticks(id: String?): Long? = runCatching {
        val u = UUID.fromString(id ?: return null)
        if (u.version() != 1) null else u.timestamp()
    }.getOrNull()

    fun epochMs(id: String?): Long? = ticks(id)?.let { (it - GREGORIAN_OFFSET) / 10_000 }

    /** An ISO-8601 instant as ticks (ms precision: the first tick of that ms). */
    fun ticksOfIso(ts: String?): Long? = isoMs(ts)?.let { it * 10_000 + GREGORIAN_OFFSET }

    fun isoMs(ts: String?): Long? = ts?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /** Lowercase canonical form of a UUID string, or null when it isn't one. */
    fun canonical(id: String): String? = runCatching { UUID.fromString(id).toString().takeIf { it.equals(id, true) } }.getOrNull()
}

/**
 * §15.3 the canonical MLS `authenticated_data` of a delete control: `0x01 0x44` + the targets as
 * 16-byte UUIDs, sorted ascending by bytes, distinct, 1..100. Same rules as the core's
 * `deleteAadEncode`/`deleteAadDecode` (checked against it in the real-core tests).
 */
object DeleteAad {
    const val VERSION: Byte = 0x01
    const val TAG: Byte = 0x44

    private fun bytes(u: UUID): ByteArray = java.nio.ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()

    private fun cmp(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until 16) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return 0
    }

    fun encode(targets: Collection<String>): ByteArray {
        val ids = targets.map { UUID.fromString(it) }
        require(ids.size in 1..100 && ids.toSet().size == ids.size) { "1..100 distinct targets" }
        val sorted = ids.map(::bytes).sortedWith(::cmp)
        val out = java.io.ByteArrayOutputStream()
        out.write(VERSION.toInt())
        out.write(TAG.toInt())
        sorted.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** The lowercase targets of an exact canonical encoding; null for anything else (drop the control). */
    fun decode(aad: ByteArray): List<String>? {
        if (aad.size < 2 + 16 || (aad.size - 2) % 16 != 0 || aad[0] != VERSION || aad[1] != TAG) return null
        val n = (aad.size - 2) / 16
        if (n > 100) return null
        val parts = (0 until n).map { aad.copyOfRange(2 + it * 16, 2 + (it + 1) * 16) }
        for (i in 1 until n) if (cmp(parts[i - 1], parts[i]) >= 0) return null // not sorted or not distinct
        return parts.map { b ->
            val bb = java.nio.ByteBuffer.wrap(b)
            UUID(bb.long, bb.long).toString()
        }
    }
}

/** §15.4 receiver authorisation and §15.7 sender eligibility. */
object DeleteRules {
    const val WINDOW_MS = 48L * 60 * 60 * 1000

    /** Receivers' grace on top of 48 h (two clock reads on the server, §15.4). */
    const val RECEIVER_GRACE_MS = 5L * 60 * 1000

    /** The sender offers "for everyone" only up to 1 min before the server's limit (§15.7). */
    const val SENDER_MARGIN_MS = 60L * 1000

    /**
     * §15.4 step 4: the deleter [deleter] (attested **user** id) may delete a message of [sender]:
     * the same user within 48 h + 5 min of the target (delete `server_ts` − target `server_ts`), or
     * an admin at the control's epoch ([senderIsAdmin], groups only). Device ids are never compared.
     */
    fun allowed(deleter: String, sender: String, deleteMs: Long?, targetMs: Long?, group: Boolean, senderIsAdmin: Boolean?): Boolean {
        if (group && senderIsAdmin == true) return true
        if (!deleter.equals(sender, ignoreCase = true)) return false
        if (deleteMs == null || targetMs == null) return false
        return deleteMs - targetMs <= WINDOW_MS + RECEIVER_GRACE_MS
    }

    /**
     * §15.7 eligibility of "Delete for everyone" for one row: my own message while
     * `server_ts + 48 h − 1 min > device_now + offset`, or any message with a `message_id` when I
     * am a group admin (`groups.my_role`). A pending own message (no `message_id`) is cancelled
     * instead, which is always possible.
     */
    fun eligibleForEveryone(
        messageId: String?,
        serverTsMs: Long?,
        mine: Boolean,
        kindIsMessage: Boolean,
        group: Boolean,
        iAmAdmin: Boolean,
        deviceNowMs: Long,
        offsetMs: Long,
    ): Boolean {
        if (!kindIsMessage) return false
        if (messageId == null) return mine // pending: cancelled (§15.7 pending rules)
        if (group && iAmAdmin) return true
        if (!mine) return false
        val ts = serverTsMs ?: TimeUuid.epochMs(messageId) ?: return false
        return ts + WINDOW_MS - SENDER_MARGIN_MS > deviceNowMs + offsetMs
    }

    /** "This message was deleted" / "You deleted this message" / "This message was deleted by an admin" (§15.6). */
    fun tombstoneText(deletedBy: String?, me: String, byAdmin: Boolean): String = when {
        deletedBy != null && deletedBy.equals(me, ignoreCase = true) -> YOU_DELETED
        byAdmin -> DELETED_BY_ADMIN
        else -> DELETED
    }

    const val DELETED = "This message was deleted"
    const val YOU_DELETED = "You deleted this message"
    const val DELETED_BY_ADMIN = "This message was deleted by an admin"

    /** The note on a message whose delete control couldn't be verified (§15.4: core `Malformed`, e.g. > 3 epochs back). */
    const val UNVERIFIED_NOTE = "Couldn't verify a delete for this message"

    /** §15.1 small print while the conversation isn't `deletes_ready`. */
    const val OLD_APPS_HINT = "People on older app versions may still see it"
    const val SEEN_HINT = "Recipients may have already seen it"
    const val FAILED = "Couldn't delete for everyone"
    const val TOO_OLD_TEXT = "You can only delete messages for everyone within 48 hours"
}

/**
 * §15.7 (android R8): `offset = server_time − device_now`, from every join and sync reply, kept in
 * memory and persisted for a cold start.
 */
class ServerClock(private val now: () -> Long = System::currentTimeMillis, private val persist: suspend (Long) -> Unit = {}) {
    @Volatile var offsetMs: Long = 0
        private set

    fun restore(saved: Long?) {
        if (saved != null) offsetMs = saved
    }

    suspend fun onServerTime(ts: String?) {
        val server = TimeUuid.isoMs(ts) ?: return
        val o = server - now()
        offsetMs = o
        persist(o)
    }

    /** The server's "now" as this device estimates it. */
    fun serverNow(): Long = now() + offsetMs
}

/** The outbox's JSON id lists. */
object DeleteJson {
    private val ser = kotlinx.serialization.builtins.ListSerializer(String.serializer())

    fun ids(json: String): List<String> = runCatching { lk.codegen.risime.net.ProtocolJson.decodeFromString(ser, json) }.getOrDefault(emptyList()).map { it.lowercase() }

    fun encode(ids: List<String>): String = lk.codegen.risime.net.ProtocolJson.encodeToString(ser, ids)
}
