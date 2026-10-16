package ai.risicloud.risime.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Architect 2026-10-16 14:15: every cached server flag is refetched from one place, not from one screen. */
class ServerConfigRefreshTest {
    private var t = 1_000_000L
    private val ran = mutableListOf<String>()
    private var translateOnServer = false
    private var translateCached = false

    private fun refresher(failing: Set<String> = emptySet()) = ServerConfigRefresh(
        tasks = listOf("auth_config", "risi_voice", "me").map { name ->
            name to suspend {
                ran += name
                if (name in failing) throw IllegalStateException("offline")
                if (name == "risi_voice") translateCached = translateOnServer
            }
        },
        throttleMs = 30_000,
        now = { t },
    )

    @Test fun aFlagTurnedOnReachesTheCacheOnTheNextForegroundWithoutOpeningSettings() = runBlocking {
        val r = refresher()
        assertTrue(r.refresh("start", force = true))
        assertFalse(translateCached)
        translateOnServer = true // RISI_VOICE_TRANSLATE turned on
        t += 60_000
        assertTrue(r.refresh("foreground"))
        assertTrue(translateCached)
    }

    @Test fun triggersCloseTogetherRunOnceButARejoinAlwaysRuns() = runBlocking {
        val r = refresher()
        assertTrue(r.refresh("foreground"))
        t += 2_000
        assertFalse("ON_START twice within 30 s", r.refresh("foreground"))
        assertTrue("a (re)join is forced", r.refresh("rejoin", force = true))
        assertEquals(6, ran.size)
    }

    @Test fun oneFailingFetchNeverStopsTheOthers() = runBlocking {
        translateOnServer = true
        val r = refresher(failing = setOf("auth_config"))
        assertTrue(r.refresh("start", force = true))
        assertEquals(listOf("auth_config", "risi_voice", "me"), ran)
        assertTrue(translateCached)
    }
}
