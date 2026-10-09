package lk.codegen.risime.calls

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Harsha's report: a shared screen looked enlarged and cropped on the viewer. The viewer must see
 * exactly what the sender sees: fitted (letterboxed), never cropped, at the sender's orientation.
 */
class ShareFitTest {
    private fun aspect(p: Pair<Int, Int>) = p.first.toDouble() / p.second

    /** Fitted inside the box, one side filling it, the frame's aspect ratio kept (within 1 px of rounding). */
    private fun assertFitted(boxW: Int, boxH: Int, fw: Int, fh: Int) {
        val (w, h) = ShareFit.fit(boxW, boxH, fw, fh)
        assertTrue("never larger than the box (never cropped): ${w}x$h in ${boxW}x$boxH", w <= boxW && h <= boxH)
        assertTrue("one side fills the box: ${w}x$h in ${boxW}x$boxH", w == boxW || h == boxH)
        val want = fw.toDouble() / fh
        assertTrue("aspect ${aspect(w to h)} vs frame $want", abs(aspect(w to h) - want) / want < 0.01)
    }

    @Test fun portraitSenderOnALandscapeViewerIsPillarboxed() {
        // A portrait phone (1080×2400 → capture 720×1600) watched on a landscape phone.
        val (w, h) = ShareFit.fit(2400, 1080, 720, 1600)
        assertEquals(1080, h)
        assertEquals(486, w)
        assertFitted(2400, 1080, 720, 1600)
    }

    @Test fun landscapeSenderOnAPortraitViewerIsLetterboxed() {
        val (w, h) = ShareFit.fit(1080, 2400, 1600, 720)
        assertEquals(1080, w)
        assertEquals(486, h)
        assertFitted(1080, 2400, 1600, 720)
    }

    @Test fun samePortraitShapesButDifferentAspectsAreNeverCropped() {
        // redroid 720×1280 sender on a 1080×2400 viewer stage (and the reverse), plus odd sizes.
        assertFitted(1080, 2400, 720, 1280)
        assertFitted(720, 1180, 1080, 2400)
        for (bw in listOf(320, 720, 1080, 1440)) for (bh in listOf(480, 1280, 2400, 3120)) for ((fw, fh) in listOf(720 to 1280, 1600 to 720, 1080 to 1080, 718 to 1600)) {
            assertFitted(bw, bh, fw, fh)
        }
    }

    @Test fun theFramesRotationGivesTheSendersOrientation() {
        assertEquals(1280 to 720, ShareFit.oriented(1280, 720, 0))
        assertEquals(720 to 1280, ShareFit.oriented(1280, 720, 90))
        assertEquals(1280 to 720, ShareFit.oriented(1280, 720, 180))
        assertEquals(720 to 1280, ShareFit.oriented(1280, 720, 270))
        assertEquals(720 to 1280, ShareFit.oriented(1280, 720, -90))
        // A rotated frame is fitted as the sender sees it (portrait), not as it was encoded.
        val (w, h) = ShareFit.oriented(1600, 720, 90).let { (fw, fh) -> ShareFit.fit(1080, 2000, fw, fh) }
        assertTrue("portrait after rotation: ${w}x$h", h > w)
    }

    @Test fun theSenderRotatingReFits() {
        val portrait = ShareFit.fit(1080, 1920, 720, 1600)
        val landscape = ShareFit.fit(1080, 1920, 1600, 720)
        assertEquals(1920, portrait.second)
        assertEquals(1080, landscape.first)
        assertTrue(landscape.second < portrait.second)
    }

    @Test fun noFrameYetUsesTheWholeBox() {
        assertEquals(1080 to 1920, ShareFit.fit(1080, 1920, 0, 0))
    }

    @Test fun panIsClampedToTheZoomedOverflowAndCentredAtZoom1() {
        // Fitted 1080×486 in a 1080×2400 box: at zoom 1 no pan; at zoom 2 x may move ±540, y not at all (972 < 2400).
        assertEquals(Offset.Zero, ShareFit.clampPan(Offset(300f, 300f), 1080, 486, 1080, 2400, 1f))
        assertEquals(Offset(540f, 0f), ShareFit.clampPan(Offset(900f, 900f), 1080, 486, 1080, 2400, 2f))
        assertEquals(Offset(-540f, 0f), ShareFit.clampPan(Offset(-900f, -900f), 1080, 486, 1080, 2400, 2f))
        // At zoom 5 the height overflows too: (486*5 - 2400)/2 = 15.
        assertEquals(Offset(10f, 15f), ShareFit.clampPan(Offset(10f, 100f), 1080, 486, 1080, 2400, 5f))
    }
}
