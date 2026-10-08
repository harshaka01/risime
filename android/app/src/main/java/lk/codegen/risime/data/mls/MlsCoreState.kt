package lk.codegen.risime.data.mls

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * P0 background delivery (rule 9): "the MLS core is open" and "this device is registered" are two
 * separate facts. Decrypting the inbox needs only the open core; registration (attestation, key
 * packages, push token) retries on its own. Before, a failed registration closed the core again, so
 * every MLS event of a push-started process was "Ignored", marked seen and lost, or (with the batch
 * gate) the whole inbox waited 15 s per batch and stalled behind it.
 */
class MlsCoreState(
    private val cryptoAvailable: Boolean,
    private val persist: Persist,
) {
    /** What survives the process (app-private prefs; public keys and a flag only). */
    interface Persist {
        /** The core was opened on this install before (so MLS state exists and MLS events can be for us). */
        fun openedBefore(): Boolean
        fun setOpenedBefore(v: Boolean)
        /** The server's attestation keys at the last successful fetch (to open the core without the network). */
        fun cachedKeys(): List<String>
        fun setCachedKeys(keys: List<String>)
    }

    val engine = MutableStateFlow<MlsEngine?>(null)

    /** The last registration with the open core succeeded (key packages up). */
    @Volatile var registered: Boolean = false
        private set

    /** MLS doesn't apply here (no core in the build, E2EE off, the server turned it down). */
    @Volatile var notApplicable: Boolean = false

    fun opened(e: MlsEngine, servedKeys: List<String>?) {
        engine.value = e
        persist.setOpenedBefore(true)
        if (!servedKeys.isNullOrEmpty()) persist.setCachedKeys(servedKeys)
    }

    /** Keys to trust when the fetch failed: the last ones served (null: never fetched, can't open offline). */
    fun offlineKeys(): List<String>? = persist.cachedKeys().takeIf { it.isNotEmpty() }

    /** activateMls()'s answer for a registration result: true = registered, null = MLS doesn't apply, false = retry. */
    fun registration(r: Registration): Boolean? = when (r) {
        is Registration.Mls -> { registered = true; true }
        Registration.MlsUnavailable -> {
            // The server turned MLS down: a v1.6 client (the push-only registration is done).
            engine.value = null
            registered = false
            notApplicable = true
            null
        }
        // Failed (network, 5xx, locked): the core stays open, so the inbox still decrypts; retried with backoff.
        else -> { registered = false; false }
    }

    /**
     * MLS applies but the core isn't open (yet): MLS events must wait (never be ignored). False when
     * the core never opened on this install: nothing can be encrypted to a device without key packages.
     */
    fun coreExpected(): Boolean = cryptoAvailable && !notApplicable && engine.value == null && persist.openedBefore()

    /** Signed out / locked session change: closed until the next activation. */
    fun closed() {
        engine.value = null
        registered = false
    }

    /** Local MLS state deleted (the confirmed wipe): nothing is expected any more. */
    fun wiped() {
        closed()
        persist.setOpenedBefore(false)
        persist.setCachedKeys(emptyList())
    }
}
