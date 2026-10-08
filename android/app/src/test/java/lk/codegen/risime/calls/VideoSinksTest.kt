package lk.codegen.risime.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The §23 review fixes in the media layer's pure parts: the renderer slot and the camera capture switch. */
class VideoSinksTest {
    private class View(val name: String) {
        var frames = 0
    }

    @Test
    fun theScreenSwapKeepsTheNewRendererAttached() {
        val slot = SinkSlot<View>()
        val surface = View("surface")
        val texture = View("texture")
        slot.attach(surface)
        // The peer starts sharing: Compose runs the new TextureRenderer's factory first…
        slot.attach(texture)
        // …then the old SurfaceRenderer's onDispose.
        assertFalse(slot.detach(surface))
        assertSame(texture, slot.current)
        repeat(3) { slot.deliver { it.frames++ } }
        assertEquals(3, texture.frames)
        assertEquals(0, surface.frames)
        assertEquals(3L, slot.frames)
        // The share stops: back to a SurfaceRenderer, same order.
        val surface2 = View("surface2")
        slot.attach(surface2)
        assertEquals("the count restarts per renderer", 0L, slot.frames)
        assertFalse(slot.detach(texture))
        slot.deliver { it.frames++ }
        assertEquals(1, surface2.frames)
        // The call screen goes: its own detach clears the slot; frames go nowhere.
        assertTrue(slot.detach(surface2))
        assertNull(slot.current)
        slot.deliver { it.frames++ }
        assertEquals(1, surface2.frames)
    }

    private class Capturer {
        var running = false
        var starts = 0
        var stops = 0
        val cap = CameraCapture(ensure = { true }, start = { running = true; starts++ }, stop = { running = false; stops++ })
    }

    @Test
    fun theCapturerStopsOnEveryExitFromVideoEvenWhenTheSessionIsNoLongerVideo() {
        // A rollback drops the video transceiver (the session isn't video any more), then the machine turns the camera off.
        val c = Capturer()
        c.cap.set(true, allowed = true)
        assertTrue(c.running && c.cap.on)
        c.cap.set(false, allowed = false)
        assertFalse("rollback: the camera must stop", c.running)
        assertFalse(c.cap.on)
        // Back to voice / a decline (the session still has video): off stops it too.
        c.cap.set(true, allowed = true)
        c.cap.set(false, allowed = true)
        assertFalse(c.running)
        // On is never allowed without video; off when already off does nothing.
        c.cap.set(true, allowed = false)
        assertFalse(c.running)
        c.cap.set(false, allowed = false)
        assertEquals(2, c.starts)
        assertEquals(2, c.stops)
    }

    @Test
    fun noCameraStaysOff() {
        var started = false
        val cap = CameraCapture(ensure = { false }, start = { started = true }, stop = {})
        cap.set(true, allowed = true)
        assertFalse(started || cap.on || cap.capturing)
    }
}
