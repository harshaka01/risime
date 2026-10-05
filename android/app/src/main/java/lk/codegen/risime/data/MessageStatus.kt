package lk.codegen.risime.data

/**
 * Client message states (PROTOCOL.md §3) plus a local-only FAILED for sends the server rejected
 * (unknown_recipient, empty_body, too_long, bad_request). Status only ever moves forward.
 */
enum class MessageStatus(val rank: Int) {
    FAILED(-1),
    PENDING(0),
    SENT(1),
    DELIVERED(2),
    READ(3),
    ;

    /** Apply [incoming] to this state, forward-only. */
    fun advance(incoming: MessageStatus): MessageStatus = when {
        incoming == FAILED -> if (this == PENDING) FAILED else this
        incoming.rank > rank -> incoming
        else -> this
    }

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(s: String?): MessageStatus? = entries.firstOrNull { it.wire == s }
    }
}
