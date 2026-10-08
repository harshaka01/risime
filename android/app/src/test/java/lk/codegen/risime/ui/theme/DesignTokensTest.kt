package lk.codegen.risime.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** WCAG 2.x contrast of the design tokens (text ≥ 4.5:1, meaningful marks ≥ 3:1), light and dark. */
class DesignTokensTest {
    private fun channel(c: Float): Double = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color): Double {
        assertEquals("tokens must be opaque", 1f, c.alpha)
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test fun formulaMatchesKnownValues() {
        assertEquals(21.0, contrast(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrast(BrandBlue, BrandBlue), 0.001)
        assertEquals(6.42, contrast(Color(0xFF0056D3), Color.White), 0.05)
    }

    @Test fun textPairsMeetAA() {
        val failures = mutableListOf<String>()
        for ((theme, p) in listOf("light" to LightPalette, "dark" to DarkPalette)) {
            p.textPairs.forEach { (name, fg, bg) ->
                val r = contrast(fg, bg)
                if (r < 4.5) failures += "$theme $name = ${"%.2f".format(r)}"
            }
            p.markPairs.forEach { (name, fg, bg) ->
                val r = contrast(fg, bg)
                if (r < 3.0) failures += "$theme $name = ${"%.2f".format(r)} (marks need 3:1)"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun seedsAndReadTickDistinguishable() {
        // GO UX: the logo's blue (seed #1565E8, tone 40) and its cyan for read ticks.
        assertEquals(Color(0xFF0056D3), LightPalette.primary)
        assertEquals(Color(0xFF8AF7FF), DarkPalette.readTick)
        for (p in listOf(LightPalette, DarkPalette)) {
            // Read must not look like delivered: different colour, and ≥ 1.5:1 apart.
            assertTrue(contrast(p.readTick, p.bubbleMineMeta) >= 1.5)
        }
    }

    @Test fun touchTargetsAndScale() {
        assertTrue(Sizes.minTouch.value >= 48f)
        assertTrue(Sizes.listRowMin.value >= 48f)
        val scale = listOf(Spacing.xxs, Spacing.xs, Spacing.sm, Spacing.md, Spacing.lg, Spacing.xl, Spacing.xxl).map { it.value }
        assertEquals(scale.sorted(), scale)
        assertTrue(scale.all { it % 2f == 0f })
    }
}
