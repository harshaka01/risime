package lk.codegen.risime.data.mls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review finding (HIGH): "core open" and "registered" are separate; a failed registration keeps the core. */
class MlsCoreStateTest {
    private class Prefs : MlsCoreState.Persist {
        var opened = false
        var keys = emptyList<String>()
        override fun openedBefore() = opened
        override fun setOpenedBefore(v: Boolean) { opened = v }
        override fun cachedKeys() = keys
        override fun setCachedKeys(keys: List<String>) { this.keys = keys }
    }

    private val prefs = Prefs()
    private val core = FakeMlsEngine("u", "d")

    @Test fun aFailedRegistrationKeepsTheCoreOpen() {
        val s = MlsCoreState(true, prefs)
        s.opened(core, listOf("k1"))
        assertEquals(false, s.registration(Registration.Failed("http_503")))
        assertNotNull("decrypting needs no registration", s.engine.value)
        assertFalse(s.registered)
        assertFalse("nothing to wait for: the core is open", s.coreExpected())
        assertEquals(true, s.registration(Registration.Mls(10)))
        assertTrue(s.registered)
    }

    @Test fun theServerTurningMlsDownClosesItForGood() {
        val s = MlsCoreState(true, prefs)
        s.opened(core, listOf("k1"))
        assertNull(s.registration(Registration.MlsUnavailable))
        assertNull(s.engine.value)
        assertTrue(s.notApplicable)
        assertFalse("MLS doesn't apply: events are ignored as before", s.coreExpected())
    }

    @Test fun aNewProcessExpectsTheCoreOnlyIfItWasOpenedBefore() {
        assertFalse("fresh install: nothing can be encrypted to this device yet", MlsCoreState(true, prefs).coreExpected())
        MlsCoreState(true, prefs).opened(core, listOf("k1"))
        val next = MlsCoreState(true, prefs) // a push-started process
        assertTrue(next.coreExpected())
        assertEquals("opens offline with the last served keys", listOf("k1"), next.offlineKeys())
        assertFalse("no crypto in this build", MlsCoreState(false, prefs).coreExpected())
    }

    @Test fun signOutClosesAndTheConfirmedWipeForgets() {
        val s = MlsCoreState(true, prefs)
        s.opened(core, listOf("k1"))
        s.registration(Registration.Mls(1))
        s.closed()
        assertNull(s.engine.value); assertFalse(s.registered)
        s.wiped()
        assertFalse(s.coreExpected())
        assertNull(s.offlineKeys())
    }

    @Test fun anEmptyServedListDoesNotEraseTheCache() {
        val s = MlsCoreState(true, prefs)
        s.opened(core, listOf("k1"))
        s.closed()
        s.opened(core, null) // opened offline
        assertEquals(listOf("k1"), s.offlineKeys())
    }
}
