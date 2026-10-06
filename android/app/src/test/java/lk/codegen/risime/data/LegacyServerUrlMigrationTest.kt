package lk.codegen.risime.data

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyServerUrlMigrationTest {
    private val key = SessionStore.SERVER_URL_KEY
    private val m = LegacyServerUrlMigration("https://risime.risicloud.ai")

    @Test fun rewritesOnlyTheOldDefault() = runTest {
        for (old in listOf("https://risicloud.ai/risime", "https://risicloud.ai/risime/")) {
            val p = mutablePreferencesOf(key to old, stringPreferencesKey("token") to "t")
            assertTrue(m.shouldMigrate(p))
            val out = m.migrate(p)
            assertEquals("https://risime.risicloud.ai", out[key])
            assertEquals("t", out[stringPreferencesKey("token")]) // nothing else touched
        }
    }

    @Test fun keepsUserChosenUrlsAndEmptyStores() = runTest {
        listOf(
            "https://risime.risicloud.ai",
            "http://10.0.2.2:4400",
            "https://risicloud.ai/risime2",
            "https://spark2.tail1234.ts.net",
        ).forEach { assertFalse(it, m.shouldMigrate(mutablePreferencesOf(key to it))) }
        assertFalse(m.shouldMigrate(emptyPreferences()))
        // A build whose default *is* the old URL never loops.
        assertFalse(LegacyServerUrlMigration("https://risicloud.ai/risime").shouldMigrate(mutablePreferencesOf(key to "https://risicloud.ai/risime")))
    }
}
