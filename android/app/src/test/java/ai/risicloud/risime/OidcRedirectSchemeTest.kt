package ai.risicloud.risime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision 079: the old lk.codegen.risime app claims ai.risicloud.risime(.debug)://, so this app signs in through its
 * own scheme ai.risicloud.risime.app(.debug)://. Checks BuildConfig (debug here) and both merged manifests.
 */
class OidcRedirectSchemeTest {
    @Test
    fun debugBuildUsesTheAppDebugScheme() {
        assertEquals("ai.risicloud.risime.app.debug://callback", BuildConfig.OIDC_REDIRECT_URI)
        assertEquals("ai.risicloud.risime.app.debug://logout", BuildConfig.OIDC_LOGOUT_REDIRECT_URI)
    }

    @Test
    fun mergedManifestsClaimOnlyTheNewSchemes() {
        for ((variant, scheme) in listOf("debug" to "ai.risicloud.risime.app.debug", "release" to "ai.risicloud.risime.app")) {
            val f = File(System.getProperty("risime.mergedManifest.$variant") ?: error("no $variant manifest property"))
            val xml = f.readText()
            assertTrue("$variant manifest lacks android:scheme=\"$scheme\"", "android:scheme=\"$scheme\"" in xml)
            for (old in listOf("ai.risicloud.risime", "ai.risicloud.risime.debug")) {
                assertFalse("$variant manifest still claims $old://", "android:scheme=\"$old\"" in xml)
            }
        }
        // The release BuildConfig (generated source) carries the release URIs.
        val rel = File(System.getProperty("risime.buildConfig.release") ?: error("no release BuildConfig property")).readText()
        assertTrue("ai.risicloud.risime.app://callback" in rel && "ai.risicloud.risime.app://logout" in rel)
    }
}
