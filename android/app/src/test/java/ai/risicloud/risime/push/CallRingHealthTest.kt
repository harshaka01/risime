package ai.risicloud.risime.push

import android.provider.Settings
import ai.risicloud.risime.calls.ACTION_OEM_AUTOSTART
import ai.risicloud.risime.calls.callsBlocking
import ai.risicloud.risime.calls.callsSetupItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0 calls (Harsha 2026-10-16): what stops an incoming call from ringing, shown with its one-tap fix. */
class CallRingHealthTest {
    private val tok = tokenHash("fcm-token-1")
    private val good = HealthInputs(
        sdk = 34, notificationsEnabled = true, messagesChannel = ChannelState(4), callsChannel = ChannelState(4),
        ignoringBatteryOptimizations = true, backgroundRestricted = false, canUseFullScreenIntent = true,
        pushConfigured = true, currentTokenHash = tok, registeredTokenHash = tok,
    )

    private fun calls(i: HealthInputs) = healthChecks(i).single { it.row == HealthRow.CALLS_CHANNEL }

    @Test fun aCallsChannelWithoutSoundIsMutedWithAFix() {
        val r = calls(good.copy(callsChannel = ChannelState(4, sound = false)))
        assertFalse(r.ok)
        assertEquals("muted", r.state)
        assertEquals(HealthFix.CALLS_CHANNEL, r.fix)
        assertTrue(r.detail.contains("don't ring"))
        assertTrue(calls(good).ok)
    }

    @Test fun mutedMeansOffLowOrSilent() {
        assertFalse(callsChannelMuted(null))
        assertFalse(callsChannelMuted(ChannelState(4)))
        assertTrue(callsChannelMuted(ChannelState(4, sound = false)))
        assertTrue(callsChannelMuted(ChannelState(3)))
        assertTrue(callsChannelMuted(ChannelState(0)))
    }

    @Test fun aDowngradedCallPushIsSeen() {
        assertTrue(pushDowngraded(priority = 2, originalPriority = 1))
        assertFalse(pushDowngraded(priority = 1, originalPriority = 1))
        assertFalse("sent normal: nothing was downgraded", pushDowngraded(priority = 2, originalPriority = 2))
    }

    @Test fun settingsCallsListsTheFixesFirstAndTheBrandOnce() {
        val all = callsSetupItems(null, fullScreen = false, batteryOptimised = true, sdk = 34, callsMuted = true, pushDowngraded = true, brand = OemBrand.XIAOMI)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, all.first().action)
        assertEquals("Fix", all.first().label)
        assertEquals("Allow", all[1].label)
        assertEquals(1, all.count { it.action == ACTION_OEM_AUTOSTART })
        assertTrue(all.last().text.startsWith("Xiaomi"))
        assertEquals(4, callsBlocking(all).size)
        val ready = callsSetupItems(null, fullScreen = true, batteryOptimised = false, sdk = 34, brand = OemBrand.SAMSUNG)
        assertTrue("nothing blocks: \"Calls are ready\"", callsBlocking(ready).isEmpty())
        assertTrue(ready.single().text.startsWith("Samsung"))
        // Below Android 14 there is no full-screen permission to ask for.
        assertTrue(callsSetupItems(null, fullScreen = false, batteryOptimised = false, sdk = 33).none { it.label == "Allow" })
    }
}
