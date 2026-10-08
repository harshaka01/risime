package lk.codegen.risime.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationHealthTest {
    private val tok = tokenHash("fcm-token-1")
    private val good = HealthInputs(
        sdk = 34, notificationsEnabled = true, messagesChannel = ChannelState(4), callsChannel = ChannelState(4),
        ignoringBatteryOptimizations = true, backgroundRestricted = false, canUseFullScreenIntent = true,
        pushConfigured = true, currentTokenHash = tok, registeredTokenHash = tok,
    )

    private fun row(i: HealthInputs, r: HealthRow) = healthChecks(i).single { it.row == r }

    @Test fun allGood() {
        val rows = healthChecks(good)
        assertEquals(HealthRow.values().toList(), rows.map { it.row })
        assertTrue(rows.all { it.ok && it.fix == null })
    }

    @Test fun notificationsOffFailsTheChannelsToo() {
        val i = good.copy(notificationsEnabled = false)
        assertEquals(HealthFix.APP_NOTIFICATIONS, row(i, HealthRow.NOTIFICATIONS).fix)
        assertFalse(row(i, HealthRow.MESSAGES_CHANNEL).ok)
        assertEquals(HealthFix.APP_NOTIFICATIONS, row(i, HealthRow.MESSAGES_CHANNEL).fix)
        assertEquals(HealthFix.APP_NOTIFICATIONS, row(i, HealthRow.CALLS_CHANNEL).fix)
    }

    @Test fun channelImportance() {
        assertTrue("not created yet: created high on first use", row(good.copy(messagesChannel = null), HealthRow.MESSAGES_CHANNEL).ok)
        val low = row(good.copy(messagesChannel = ChannelState(3)), HealthRow.MESSAGES_CHANNEL)
        assertFalse(low.ok); assertEquals(HealthFix.MESSAGES_CHANNEL, low.fix)
        val off = row(good.copy(messagesChannel = ChannelState(0)), HealthRow.MESSAGES_CHANNEL)
        assertFalse(off.ok); assertTrue(off.detail.contains("turned off"))
        val calls = row(good.copy(callsChannel = ChannelState(0)), HealthRow.CALLS_CHANNEL)
        assertFalse(calls.ok); assertEquals(HealthFix.CALLS_CHANNEL, calls.fix)
    }

    @Test fun battery() {
        val opt = row(good.copy(ignoringBatteryOptimizations = false), HealthRow.BATTERY)
        assertFalse(opt.ok); assertEquals(HealthFix.BATTERY, opt.fix)
        val restricted = row(good.copy(backgroundRestricted = true), HealthRow.BATTERY)
        assertFalse("exempt but Restricted still blocks", restricted.ok); assertTrue(restricted.detail.contains("Restricted"))
    }

    @Test fun fullScreen() {
        val r = row(good.copy(canUseFullScreenIntent = false), HealthRow.FULL_SCREEN)
        assertFalse(r.ok); assertEquals(HealthFix.FULL_SCREEN, r.fix)
    }

    @Test fun pushToken() {
        assertFalse(row(good.copy(registeredTokenHash = null), HealthRow.PUSH).ok)
        assertEquals(HealthFix.RETRY_PUSH, row(good.copy(registeredTokenHash = null), HealthRow.PUSH).fix)
        assertFalse("the server holds an older token", row(good.copy(registeredTokenHash = tokenHash("old")), HealthRow.PUSH).ok)
        assertFalse(row(good.copy(currentTokenHash = null), HealthRow.PUSH).ok)
        val none = row(good.copy(pushConfigured = false), HealthRow.PUSH)
        assertFalse(none.ok); assertNull("nothing to retry without Firebase", none.fix)
    }

    @Test fun tokenHashIsStableAndNotTheToken() {
        assertEquals(tokenHash("a"), tokenHash("a"))
        assertNotNull(tokenHash("a")); assertFalse(tokenHash("fcm-token-1")!!.contains("fcm"))
        assertEquals(24, tokenHash("x")!!.length)
        assertNull(tokenHash("")); assertNull(tokenHash(null))
    }

    @Test fun shownOncePerVersionAndOnlyWhenSomethingIsWrong() {
        val bad = healthChecks(good.copy(ignoringBatteryOptimizations = false))
        assertTrue(shouldShowHealthAfterUpdate(2_000_032, 2_000_031, bad))
        assertTrue(shouldShowHealthAfterUpdate(2_000_032, null, bad))
        assertFalse("already shown for this version", shouldShowHealthAfterUpdate(2_000_032, 2_000_032, bad))
        assertFalse("all ✓", shouldShowHealthAfterUpdate(2_000_032, 2_000_031, healthChecks(good)))
    }

    @Test fun oemDetection() {
        assertEquals(OemBrand.XIAOMI, oemBrand("Xiaomi", "Redmi"))
        assertEquals(OemBrand.XIAOMI, oemBrand("", "POCO"))
        assertEquals(OemBrand.HUAWEI, oemBrand("HUAWEI"))
        assertEquals(OemBrand.HUAWEI, oemBrand("HONOR"))
        assertEquals(OemBrand.SAMSUNG, oemBrand("samsung"))
        assertEquals(OemBrand.ONEPLUS, oemBrand("OnePlus"))
        assertEquals(OemBrand.REALME, oemBrand("realme"))
        assertEquals(OemBrand.OPPO, oemBrand("OPPO"))
        assertEquals(OemBrand.VIVO, oemBrand("vivo"))
        assertEquals(OemBrand.OTHER, oemBrand("Google", "google"))
        OemBrand.values().forEach { assertTrue(oemGuidance(it).isNotBlank()) }
        assertTrue(oemAutostartComponents(OemBrand.XIAOMI).first().second.contains("AutoStart"))
        assertTrue(oemAutostartComponents(OemBrand.OTHER).isEmpty())
    }
}
