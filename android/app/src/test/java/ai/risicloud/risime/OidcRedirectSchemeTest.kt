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
    /** "ai.risicloud.risime.app", or nightly.1's "ai.risicloud.risime" in a RISIME_OIDC_SCHEME=legacy build. */
    private val base = System.getProperty("risime.oidcScheme") ?: "ai.risicloud.risime.app"
    private val legacy = base == "ai.risicloud.risime"

    @Test
    fun debugBuildUsesTheAppDebugScheme() {
        assertEquals("$base.debug://callback", BuildConfig.OIDC_REDIRECT_URI)
        assertEquals("$base.debug://logout", BuildConfig.OIDC_LOGOUT_REDIRECT_URI)
    }

    @Test
    fun mergedManifestsClaimOnlyTheNewSchemes() {
        for ((variant, scheme) in listOf("debug" to "$base.debug", "release" to base)) {
            val f = File(System.getProperty("risime.mergedManifest.$variant") ?: error("no $variant manifest property"))
            val xml = f.readText()
            assertTrue("$variant manifest lacks android:scheme=\"$scheme\"", "android:scheme=\"$scheme\"" in xml)
            if (!legacy) for (old in listOf("ai.risicloud.risime", "ai.risicloud.risime.debug")) {
                assertFalse("$variant manifest still claims $old://", "android:scheme=\"$old\"" in xml)
            }
        }
        // The release BuildConfig (generated source) carries the release URIs.
        val rel = File(System.getProperty("risime.buildConfig.release") ?: error("no release BuildConfig property")).readText()
        assertTrue("$base://callback" in rel && "$base://logout" in rel)
    }
}
