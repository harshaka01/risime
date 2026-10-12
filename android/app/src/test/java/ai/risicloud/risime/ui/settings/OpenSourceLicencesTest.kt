package ai.risicloud.risime.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision 079 (moved 2026-10-12 to About → Legal → Third-party notices): every listed component's licence text
 * ships as an asset; the list is grouped by licence (each licence once); the owner is RisiCloud.
 */
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
        assertEquals("Third-party notices", THIRD_PARTY_NOTICES_TITLE)
        assertEquals("Legal", LEGAL_TITLE)
    }

    @Test
    fun noticesAreGroupedByLicenceWithEachLicenceOnce() {
        val groups = noticeGroups()
        assertEquals(groups.map { it.licence }.distinct(), groups.map { it.licence })
        assertEquals(OSS_COMPONENTS.map { it.licence }.toSet(), groups.map { it.licence }.toSet())
        assertEquals(OSS_COMPONENTS.size, groups.sumOf { it.copyrightLines.size })
        for (g in groups) {
            assertEquals(LICENCE_NAMES[g.licence], g.licenceName)
            assertTrue(g.copyrightLines.all { "Copyright ©" in it })
            assertEquals(OSS_COMPONENTS.filter { it.licence == g.licence }.map { it.copyrightLine }, g.copyrightLines)
        }
        val apache = groups.single { it.licence == LICENCE_APACHE }
        assertTrue(apache.copyrightLines.contains("OkHttp, Okio: Copyright © Square, Inc."))
        // A small sample: two licences, three components -> two groups in first-seen order.
        val sample = noticeGroups(listOf(OssComponent("a", "A", LICENCE_MIT), OssComponent("b", "B", LICENCE_APACHE), OssComponent("c", "C", LICENCE_MIT)))
        assertEquals(listOf(LICENCE_MIT, LICENCE_APACHE), sample.map { it.licence })
        assertEquals(listOf("a: Copyright © A", "c: Copyright © C"), sample[0].copyrightLines)
    }

    @Test
    fun noticesSayNothingAboutServerModelsOrInfrastructure() {
        val text = OSS_COMPONENTS.joinToString("\n") { "${it.name} ${it.holder}" }.lowercase()
        for (w in listOf("server", "model", "phoenix", "elixir", "postgres", "cassandra", "vllm", "gemma", "qwen", "whisper", "caddy", "codegen", "recovery")) {
            assertFalse("notices mention '$w'", w in text)
        }
    }

    @Test
    fun aboutOwnerIsRisiCloud() {
        assertEquals("© 2026 RisiCloud. All rights reserved.", ABOUT_OWNER_LINE)
        assertFalse(Regex("© 2026 Risi\\b(?!Cloud)").containsMatchIn(ABOUT_OWNER_LINE))
    }
}
