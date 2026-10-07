package lk.codegen.risime.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * nightly.16: people on the current build were told "<name> needs to update". The wire doesn't
 * carry a missing install's version, so the DM call and photo gates never say "update" for a
 * peer, and this phone's own reason is shown as it is.
 */
class ReadinessTextsTest {
    @Test fun callGateShowsTheRealReason() {
        val notif = "Turn on notifications so RisiMe calls can ring"
        assertEquals(notif, callBlockedText(notif, true, false, "Kumu"))
        assertEquals("This phone's calling service isn't available to RisiMe", callBlockedText("This phone's calling service isn't available to RisiMe", true, true, "Kumu"))
        assertEquals("Kumu's phone can't take calls yet", callBlockedText(null, true, false, "Kumu"))
        assertEquals("Update RisiMe on this phone to make calls", callBlockedText(CALLS_OFF_IN_BUILD, true, true, "Kumu"))
        listOf(callBlockedText(notif, true, true, "Kumu"), callBlockedText(null, true, false, "Kumu"))
            .forEach { assertFalse(it!!, it.contains("update", ignoreCase = true)) }
    }

    @Test fun photoGatesNeverSayUpdate() {
        assertEquals("Kumu's phone can't receive photos yet", dmImagesBlockedText(true, false, false, "Kumu"))
        assertEquals("Your other phone can't receive photos yet", dmImagesBlockedText(true, false, true, "Kumu"))
        listOf(dmImagesBlockedText(true, false, false, "Kumu")!!, dmImagesBlockedText(true, false, true, "Kumu")!!, GROUP_IMAGES_NOTICE)
            .forEach { assertFalse(it, it.contains("update", ignoreCase = true)) }
    }
}
