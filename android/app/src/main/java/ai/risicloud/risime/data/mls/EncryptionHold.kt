package ai.risicloud.risime.data.mls

import java.util.concurrent.ConcurrentHashMap

/**
 * Chat-list clarity item 3 (decision 048, Harsha 2026-10-12): a 1:1 message is never sent in plaintext on its own.
 *
 * While end-to-end encryption with the peer isn't ready — no MLS group on this phone yet (a new chat, or this phone
 * was just reinstalled), or the peer's new phone was announced (`mls_membership` added) but isn't in the group yet —
 * the message is held as "Waiting for encryption…" and goes out **encrypted** as soon as it can. After [HOLD_MS] the
 * bubble also offers "Send without encryption"; only that explicit tap (per message, persisted as
 * `messages.e2ee = plain_ok`, so it survives a restart) lets it go in plaintext, and the bubble then shows
 * "⚠ Not encrypted". There is no automatic fallback.
 */
object EncryptionHold {
    /** How long a message waits quietly before the bubble offers "Send without encryption". */
    const val HOLD_MS = 30_000L

    /** How often the outbox looks again while a message is held. */
    const val RECHECK_MS = 2_000L

    /** An announced peer device that never joins stops holding messages after this long (they go to the group as it is). */
    const val PENDING_ADD_MAX_MS = 5 * 60_000L

    enum class Step {
        /** Encrypt to the group now. */
        ENCRYPT,

        /** Keep the message pending: "Waiting for encryption…". */
        HOLD,

        /** Still waiting after [HOLD_MS]: "Waiting for encryption…" + "Send without encryption". */
        HOLD_LONG,

        /** The user tapped "Send without encryption" and there is no group to encrypt to: plaintext. */
        SEND_PLAIN,
    }

    /**
     * @param localGroup this phone holds the DM's MLS group.
     * @param peerDevicePending a device announced for this DM is not in the group yet (it couldn't read the message).
     * @param plainAllowed the user tapped "Send without encryption" for this message.
     * @param waitingSince when this message started waiting (device ms).
     */
    fun decide(localGroup: Boolean, peerDevicePending: Boolean, plainAllowed: Boolean, waitingSince: Long, now: Long): Step = when {
        localGroup && !peerDevicePending -> Step.ENCRYPT
        // An e2ee chat never takes plaintext (the server refuses it too): the explicit choice can only skip the wait.
        localGroup && plainAllowed -> Step.ENCRYPT
        plainAllowed -> Step.SEND_PLAIN
        now - waitingSince < HOLD_MS -> Step.HOLD
        else -> Step.HOLD_LONG
    }
}

/**
 * `mls_membership` "added" announcements per DM, until the device shows up among the group's members (or
 * [EncryptionHold.PENDING_ADD_MAX_MS] passes). In memory: after a restart the next `mls_membership`/commit tells again.
 */
class PendingDeviceAdds(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Key(val conv: String, val deviceId: String)

    private val added = ConcurrentHashMap<Key, Long>()

    fun added(conversationId: String, deviceId: String) {
        added[Key(conversationId.lowercase(), deviceId.lowercase())] = clock()
    }

    fun removed(conversationId: String, deviceId: String) {
        added.remove(Key(conversationId.lowercase(), deviceId.lowercase()))
    }

    /** True while an announced device of [conversationId] is missing from [members]; joined or expired ones are dropped. */
    fun pending(conversationId: String, members: List<DeviceRef>): Boolean {
        val conv = conversationId.lowercase()
        val now = clock()
        val inGroup = members.map { it.deviceId.lowercase() }.toSet()
        var any = false
        for ((k, at) in added.entries.toList()) {
            if (k.conv != conv) continue
            if (k.deviceId in inGroup || now - at >= EncryptionHold.PENDING_ADD_MAX_MS) added.remove(k) else any = true
        }
        return any
    }
}
