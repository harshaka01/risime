package ai.risicloud.risime.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Architect 2026-10-16 14:15 (a flag turned on never reached the phone until Settings was opened): the one
 * place that refetches every server-driven capability the app caches. [tasks] are named fetches
 * (`/auth/config` switches, `/risi/voice` translate, `GET /me` private transcribe / Call Risi …); each runs
 * on its own (one failing never stops the others) and keeps its cached value when offline.
 *
 * Triggers: app start, every ON_START, every socket (re)join, and later the `config_changed` event
 * (proposal 2026-10-16-config-changed). Several triggers close together (ON_START reconnects the socket)
 * run once: a non-forced refresh within [throttleMs] of the last one is skipped; a forced one always runs.
 */
class ServerConfigRefresh(
    private val tasks: List<Pair<String, suspend () -> Unit>>,
    private val throttleMs: Long = 30_000,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    @Volatile private var lastAt: Long? = null

    /** Runs every task (unless [force] is false and the last refresh was under [throttleMs] ago). True if it ran. */
    suspend fun refresh(why: String, force: Boolean = false): Boolean = mutex.withLock {
        val t = now()
        lastAt?.let { if (!force && t - it < throttleMs) return@withLock false }
        lastAt = t
        val failed = tasks.mapNotNull { (name, task) -> name.takeIf { runCatching { task() }.isFailure } }
        log("server config refreshed ($why)" + if (failed.isEmpty()) "" else "; failed: ${failed.joinToString()}")
        true
    }
}
