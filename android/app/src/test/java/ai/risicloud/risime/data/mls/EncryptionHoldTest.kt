package ai.risicloud.risime.data.mls

import ai.risicloud.risime.data.mls.EncryptionHold.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chat-list clarity item 3: the hold / timeout / explicit-fallback decision. Never plaintext without the tap. */
class EncryptionHoldTest {
    @Test fun aReadyGroupEncryptsAtOnce() {
        assertEquals(Step.ENCRYPT, EncryptionHold.decide(localGroup = true, peerDevicePending = false, plainAllowed = false, waitingSince = 0, now = 0))
        assertEquals(Step.ENCRYPT, EncryptionHold.decide(localGroup = true, peerDevicePending = false, plainAllowed = true, waitingSince = 0, now = 99_999))
    }

    @Test fun noGroupHoldsThenHoldsLongAfter30sButNeverSendsPlainOnItsOwn() {
        assertEquals(Step.HOLD, EncryptionHold.decide(false, false, false, waitingSince = 1_000, now = 1_000))
        assertEquals(Step.HOLD, EncryptionHold.decide(false, false, false, waitingSince = 1_000, now = 1_000 + EncryptionHold.HOLD_MS - 1))
        assertEquals(Step.HOLD_LONG, EncryptionHold.decide(false, false, false, waitingSince = 1_000, now = 1_000 + EncryptionHold.HOLD_MS))
        // However long it waits: still held, never SEND_PLAIN without the user's tap.
        for (t in listOf(60_000L, 3_600_000L, 86_400_000L)) assertEquals(Step.HOLD_LONG, EncryptionHold.decide(false, false, false, 0, t))
    }

    @Test fun aPeerDeviceNotInTheGroupYetHoldsTheMessage() {
        assertEquals(Step.HOLD, EncryptionHold.decide(localGroup = true, peerDevicePending = true, plainAllowed = false, waitingSince = 0, now = 10))
        assertEquals(Step.HOLD_LONG, EncryptionHold.decide(true, true, false, 0, EncryptionHold.HOLD_MS + 1))
    }

    @Test fun theExplicitTapSendsPlainOnlyWithoutAGroup() {
        assertEquals(Step.SEND_PLAIN, EncryptionHold.decide(localGroup = false, peerDevicePending = false, plainAllowed = true, waitingSince = 0, now = 1))
        // An e2ee chat (a group here) never takes plaintext: the tap only ends the wait for the new device.
        assertEquals(Step.ENCRYPT, EncryptionHold.decide(localGroup = true, peerDevicePending = true, plainAllowed = true, waitingSince = 0, now = 1))
    }

    @Test fun pendingAddsClearWhenTheDeviceJoinsOrExpire() {
        var now = 0L
        val p = PendingDeviceAdds { now }
        val conv = "dm:a_b"
        assertFalse(p.pending(conv, emptyList()))
        p.added(conv, "D-NEW")
        assertTrue(p.pending(conv, listOf(DeviceRef("a", "d-old"))))
        assertFalse("another chat isn't held", p.pending("dm:a_c", emptyList()))
        // Joined (case-insensitive): no longer pending, and forgotten.
        assertFalse(p.pending(conv, listOf(DeviceRef("a", "d-new"))))
        assertFalse(p.pending(conv, emptyList()))
        // One that never joins stops holding after PENDING_ADD_MAX_MS.
        p.added(conv, "d-never")
        now += EncryptionHold.PENDING_ADD_MAX_MS - 1
        assertTrue(p.pending(conv, emptyList()))
        now += 1
        assertFalse(p.pending(conv, emptyList()))
        // A removal announcement cancels the hold.
        p.added(conv, "d-x")
        p.removed(conv, "D-X")
        assertFalse(p.pending(conv, emptyList()))
    }
}
