package lk.codegen.risime.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** §20.4/§20.5 UI rules without Android: ring texts, the one-call pick, the grid, member rows. */
class GroupCallUiTest {
    @Test fun incomingTexts() {
        assertEquals("Incoming voice call", incomingText(video = false))
        assertEquals("Incoming video call", incomingText(video = true)) // the v1.18 notification bug
        assertEquals("Incoming group voice call", incomingText(video = false, group = true))
        assertEquals("Incoming group video call", incomingText(video = true, group = true))
    }

    @Test fun oneCallAtATimeLiveWins() {
        val a = CallSnapshot("a", "dm:x", "u", true, CallPhase.ACTIVE)
        val g = CallSnapshot("g", "grp:x", "u", true, CallPhase.ACTIVE, group = true)
        assertEquals(a, pickCall(a, g))
        assertEquals(g, pickCall(a.copy(phase = CallPhase.ENDED), g))
        assertEquals(g, pickCall(null, g))
        assertEquals(a.copy(phase = CallPhase.ENDED), pickCall(a.copy(phase = CallPhase.ENDED), null))
        assertNull(pickCall(null, null))
    }

    @Test fun gridOfAtMostEightTiles() {
        assertEquals(listOf<Int>(), gridRows(0))
        assertEquals(listOf(1), gridRows(1))
        assertEquals(listOf(1, 1), gridRows(2))
        assertEquals(listOf(2, 1), gridRows(3))
        assertEquals(listOf(2, 2, 2, 2), gridRows(8))
        assertEquals(listOf(2, 2, 2, 2), gridRows(12)) // §20.9 cap
    }

    @Test fun memberRowsNameOnlyMembers() {
        val ghost = groupMemberUi(GroupMember("u-x/1", "u-x", local = false, member = false), "Mallory")
        assertEquals("Not a member", ghost.name)
        assertNull(ghost.photoKey)
        val bad = groupMemberUi(GroupMember("u-b/1", "u-b", local = false, member = true, cantVerify = true), "Kamal")
        assertEquals("Kamal", bad.name)
        assertEquals("Can't verify · not played", bad.warning)
        assertEquals("You", groupMemberUi(GroupMember("u-a/1", "u-a", local = true, member = true), "Me").name)
    }
}
