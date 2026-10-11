package ai.risicloud.risime.ui.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decision 079: every listed component's licence text ships as an asset; the fonts are listed under the OFL. */
class OpenSourceLicencesTest {
    private val assets = File("src/main/assets")

    @Test
    fun everyLicenceHasItsTextAsAnAsset() {
        for (c in OSS_COMPONENTS) {
            val path = LICENCE_TEXT_ASSETS[c.licence]
            assertTrue("${c.name}: no licence text for ${c.licence}", path != null)
            val f = File(assets, path!!)
            assertTrue("missing asset $f", f.isFile && f.length() > 500)
            assertTrue("${c.licence} has no display name", LICENCE_NAMES[c.licence] != null)
        }
        assertTrue(File(assets, LICENCE_TEXT_ASSETS.getValue(LICENCE_OFL)).readText().contains("SIL OPEN FONT LICENSE"))
        assertTrue(File(assets, LICENCE_TEXT_ASSETS.getValue(LICENCE_APACHE)).readText().contains("Apache License"))
    }

    @Test
    fun fontsAndCryptoAreListed() {
        assertTrue(OSS_COMPONENTS.any { "Noto Sans" in it.name && it.licence == LICENCE_OFL })
        assertTrue(OSS_COMPONENTS.any { it.name == "OpenMLS" && it.licence == LICENCE_MIT })
        assertTrue(OSS_COMPONENTS.none { it.name.contains("codegen", ignoreCase = true) || it.holder.contains("codegen", ignoreCase = true) })
        assertTrue(LICENCES_TITLE == "Open-source licences")
    }
}
