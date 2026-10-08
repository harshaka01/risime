package lk.codegen.risime.data.mls

/**
 * The app's MLS engine with a change signal: [onChanged] after an own commit merges, a commit is
 * applied or a Welcome joined (§18.5 triggers 2 and 3 read the leaves afterwards). Called inside the
 * caller's transaction: it must only signal (the listener acts later, after a debounce).
 */
class ObservedMlsEngine(private val inner: MlsEngine, private val onChanged: (conversationId: String) -> Unit) : MlsEngine by inner {
    override fun commitAccepted(conversationId: String) {
        inner.commitAccepted(conversationId)
        onChanged(conversationId)
    }

    override fun processCommit(conversationId: String, generation: Long, commit: ByteArray): CommitOutcome =
        inner.processCommit(conversationId, generation, commit).also { onChanged(conversationId) }

    override fun joinFromWelcome(conversationId: String, generation: Long, welcome: ByteArray): GroupRef =
        inner.joinFromWelcome(conversationId, generation, welcome).also { onChanged(conversationId) }
}
