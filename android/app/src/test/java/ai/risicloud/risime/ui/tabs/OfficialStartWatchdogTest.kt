package ai.risicloud.risime.ui.tabs

import ai.risicloud.risime.data.db.GroupEntity
import ai.risicloud.risime.data.db.GroupMemberEntity
import ai.risicloud.risime.data.groups.RejoinWait
import ai.risicloud.risime.data.groups.officialEpoch0Mine
import ai.risicloud.risime.data.mls.GROUP_WAITING_TEXT
import ai.risicloud.risime.data.mls.REPAIRING_TEXT
import ai.risicloud.risime.data.mls.RejoinDecision
import ai.risicloud.risime.data.mls.RejoinRules
import ai.risicloud.risime.data.mls.dmWaitingText
import ai.risicloud.risime.data.mls.groupWaitingText
import ai.risicloud.risime.net.Group
import ai.risicloud.risime.ui.group.e2eeStripHasTryAgain
import ai.risicloud.risime.ui.group.groupE2eeStrip
import ai.risicloud.risime.ui.group.oneToOneOfficialPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract v1.46 §12.12.9 (incident 2026-10-13): the watchdog, creator-only epoch 0, 1:1 wording, Official reset. */
class OfficialStartWatchdogTest {
    @Test fun noReasonForTheFirstMinuteThenReasonAndTryAgain() {
        assertEquals(OfficialStartingView(null, false), officialStartingView(0, true, "Kamal"))
        assertEquals(OfficialStartingView(null, false), officialStartingView(OFFICIAL_WATCHDOG_MS - 1, false, null))
        assertEquals(OfficialStartingView("Waiting for Kamal to open RisiMe", true), officialStartingView(OFFICIAL_WATCHDOG_MS, true, "Kamal"))
        assertEquals(OfficialStartingView(OFFICIAL_NOT_READY_HERE_TEXT, true), officialStartingView(10 * OFFICIAL_WATCHDOG_MS, true, null))
        assertEquals(OfficialStartingView(OFFICIAL_NOT_READY_HERE_TEXT, true), officialStartingView(OFFICIAL_WATCHDOG_MS, true, " "))
    }

    @Test fun offlineWinsOverEverything() {
        assertEquals(OfficialStartingView(OFFICIAL_OFFLINE_TEXT, true), officialStartingView(OFFICIAL_WATCHDOG_MS + 1, false, "Kamal"))
        assertEquals("You're offline. Try again when you're connected.", OFFICIAL_OFFLINE_TEXT)
        assertEquals("Try again", TRY_AGAIN_LABEL)
    }

    @Test fun epochZeroOnlyAsCreator() {
        val g = Group("grp:x", "creating", "u-kamal", null, 1, null, "admin", emptyList())
        assertFalse(officialEpoch0Mine(g, "u-me"))
        assertTrue(officialEpoch0Mine(g.copy(createdBy = "U-ME"), "u-me"))
        assertTrue(officialEpoch0Mine(g.copy(createdBy = null), "u-me")) // an older server: as before
    }

    private fun member(u: String, name: String, kind: String = "user") = GroupMemberEntity("grp:x", u, name, null, "admin", kind, "active", null)

    @Test fun oneToOneOfficialNamesThePeerNeverAGroupMember() {
        val ms = listOf(member("u-me", "Me"), member("u-kamal", "Kamal"), member("u-risi", "Risi", "agent"))
        assertEquals("Kamal", oneToOneOfficialPeer("dm:u-kamal_u-me", ms, "u-me"))
        assertNull(oneToOneOfficialPeer("grp:private-1", ms, "u-me")) // a group chat's Official
        assertNull(oneToOneOfficialPeer(null, ms, "u-me"))
        assertEquals("$REPAIRING_TEXT Waiting for Kamal to open RisiMe", groupWaitingText("Kamal"))
        assertEquals(dmWaitingText("Kamal"), groupWaitingText("Kamal"))
        val g = GroupEntity("grp:x", null, "admin", GroupEntity.STATE_ACTIVE, null, null, 1, null, null, null, 0)
        val wait = RejoinWait(candidates = 1, exhausted = false, committerNamed = false, opCreatedAtMs = 0, since = 0)
        val strip = groupE2eeStrip(g, false, wait, 60_000, "Kamal")
        assertEquals("Setting up encryption on this phone… Waiting for Kamal to open RisiMe", strip)
        assertTrue(e2eeStripHasTryAgain(strip))
        assertTrue(e2eeStripHasTryAgain(REPAIRING_TEXT))
    }

    @Test fun parityRealGroupsKeepTheGroupWording() {
        assertEquals("Setting up encryption on this phone… Waiting for a group member to open RisiMe", GROUP_WAITING_TEXT)
        assertEquals(GROUP_WAITING_TEXT, groupWaitingText(null))
        val g = GroupEntity("grp:x", "Team", "member", GroupEntity.STATE_ACTIVE, null, null, 1, null, null, null, 0)
        val wait = RejoinWait(candidates = 1, exhausted = false, committerNamed = false, opCreatedAtMs = 0, since = 0)
        assertEquals(GROUP_WAITING_TEXT, groupE2eeStrip(g, false, wait, 60_000))
        assertNull(groupE2eeStrip(g, true, wait, 60_000)) // encrypted: no strip, no Try again
        assertFalse(e2eeStripHasTryAgain(null))
        assertFalse(e2eeStripHasTryAgain("Not end-to-end encrypted yet: setting up end-to-end encryption…"))
    }

    @Test fun officialAdminResetsAtZeroCandidatesAtOnce() {
        assertEquals(RejoinDecision.RESET, RejoinRules.group(true, 0, false, 0, 1, official = true))
        assertEquals(RejoinDecision.RESET, RejoinRules.group(true, 0, false, null, 1, official = true))
        assertEquals(RejoinDecision.WAIT, RejoinRules.group(true, 1, false, 0, 10L * 24 * 3600_000, official = true)) // Risi or a member can re-add
        assertEquals(RejoinDecision.RESET, RejoinRules.group(true, 1, true, 0, 1, official = true)) // exhausted, unchanged
        assertEquals(RejoinDecision.WAIT, RejoinRules.group(false, 0, false, 0, 1, official = true)) // non-admins never reset
        assertEquals(RejoinDecision.WAIT, RejoinRules.group(true, null, false, 0, 1, official = true)) // unknown: never
    }

    @Test fun parityPrivateGroupAndDmDecisionsUnchanged() {
        val day = RejoinRules.NO_CANDIDATE_RESET_MS
        assertEquals(RejoinDecision.WAIT, RejoinRules.group(true, 0, false, 0, day - 1)) // Private: 24 h floor stays
        assertEquals(RejoinDecision.RESET, RejoinRules.group(true, 0, false, 0, day))
        assertEquals(RejoinDecision.WAIT, RejoinRules.group(true, 0, false, null, 10 * day))
        assertEquals(RejoinDecision.WAIT, RejoinRules.group(false, 0, false, 0, 10 * day))
        assertEquals(RejoinDecision.RESET, RejoinRules.group(true, 2, true, 0, 1))
        assertEquals(RejoinDecision.RESET, RejoinRules.dm(0, false))
        assertEquals(RejoinDecision.RESET, RejoinRules.dm(1, true))
        assertEquals(RejoinDecision.WAIT, RejoinRules.dm(1, false))
    }
}
