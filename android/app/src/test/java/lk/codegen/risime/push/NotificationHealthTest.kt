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
        val rows = healthChecks(good.copy(account = AccountInputs("Offline", null)))
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

    @Test fun autoOpensOnlyForFailuresThatStopMessagesOrCalls() {
        val none = emptySet<String>()
        assertFalse("all ✓", shouldShowHealthAfterUpdate(healthChecks(good), none))
        assertFalse("battery never opens it", shouldShowHealthAfterUpdate(healthChecks(good.copy(ignoringBatteryOptimizations = false)), none))
        assertFalse("battery Restricted is shown, not pushed", shouldShowHealthAfterUpdate(healthChecks(good.copy(backgroundRestricted = true)), none))
        assertFalse("a Silent Messages channel is a hint", shouldShowHealthAfterUpdate(healthChecks(good.copy(messagesChannel = ChannelState(2))), none))
        assertFalse("a Silent Calls channel is a hint", shouldShowHealthAfterUpdate(healthChecks(good.copy(callsChannel = ChannelState(3))), none))
        assertTrue(shouldShowHealthAfterUpdate(healthChecks(good.copy(notificationsEnabled = false)), none))
        assertTrue(shouldShowHealthAfterUpdate(healthChecks(good.copy(messagesChannel = ChannelState(0))), none))
        assertTrue(shouldShowHealthAfterUpdate(healthChecks(good.copy(callsChannel = ChannelState(0))), none))
        assertTrue(shouldShowHealthAfterUpdate(healthChecks(good.copy(canUseFullScreenIntent = false)), none))
        assertTrue(shouldShowHealthAfterUpdate(healthChecks(good.copy(registeredTokenHash = null)), none))
        assertFalse("push before the registration settled", shouldShowHealthAfterUpdate(healthChecks(good.copy(registeredTokenHash = null, pushSettled = false)), none))
        assertFalse("no Firebase in this build", shouldShowHealthAfterUpdate(healthChecks(good.copy(pushConfigured = false)), none))
    }

    @Test fun silentChannelIsAHintNotAFailure() {
        val r = row(good.copy(messagesChannel = ChannelState(2)), HealthRow.MESSAGES_CHANNEL)
        assertFalse(r.ok); assertTrue(r.hint); assertFalse(r.autoOpen); assertEquals("MESSAGES_CHANNEL:silent", r.key)
        val off = row(good.copy(messagesChannel = ChannelState(0)), HealthRow.MESSAGES_CHANNEL)
        assertFalse(off.hint); assertTrue(off.autoOpen); assertEquals("MESSAGES_CHANNEL:off", off.key)
    }

    @Test fun dismissalsAreRememberedPerRowAndState() {
        val callsOff = healthChecks(good.copy(callsChannel = ChannelState(0)))
        assertTrue(shouldShowHealthAfterUpdate(callsOff, emptySet()))
        val d1 = healthDismissals(callsOff, emptySet())
        assertEquals(setOf("CALLS_CHANNEL:off"), d1)
        assertFalse("the same failure after the next update: not again", shouldShowHealthAfterUpdate(callsOff, d1))
        val alsoFullScreen = healthChecks(good.copy(callsChannel = ChannelState(0), canUseFullScreenIntent = false))
        assertTrue("a new failure shows", shouldShowHealthAfterUpdate(alsoFullScreen, d1))
        // Fixed, then broken again later: shown again.
        val d2 = healthDismissals(healthChecks(good), d1)
        assertTrue(d2.isEmpty())
        assertTrue(shouldShowHealthAfterUpdate(callsOff, d2))
        // A not-yet-settled push failure is never remembered (it may show once it has settled).
        assertTrue(healthDismissals(healthChecks(good.copy(registeredTokenHash = null, pushSettled = false)), emptySet()).isEmpty())
    }

    @Test fun accountRowShowsTheSessionTypeAndTheLastSignOut() {
        assertTrue(healthChecks(good).none { it.row == HealthRow.ACCOUNT }) // dev session: no row
        val offline = healthChecks(good.copy(account = AccountInputs("Offline", null))).single { it.row == HealthRow.ACCOUNT }
        assertTrue(offline.ok)
        assertEquals("Stays signed in (offline session)", offline.title)
        assertFalse(offline.autoOpen)
        val short = healthChecks(good.copy(account = AccountInputs("Refresh", "invalid_grant"))).single { it.row == HealthRow.ACCOUNT }
        assertFalse(short.ok)
        assertEquals("Short session — ask the admin", short.title)
        assertTrue(short.detail.contains("Last sign-out: invalid_grant"))
        assertFalse(short.autoOpen) // only an admin can fix it: never pushed at the user
        assertEquals(null, short.fix)
        assertFalse(shouldShowHealthAfterUpdate(healthChecks(good.copy(account = AccountInputs("Refresh", null))), emptySet()))
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
