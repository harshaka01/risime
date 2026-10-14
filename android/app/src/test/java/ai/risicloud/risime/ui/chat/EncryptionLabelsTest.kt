package ai.risicloud.risime.ui.chat

import android.app.Application
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import ai.risicloud.risime.calls.CallPhase
import ai.risicloud.risime.calls.CallScreen
import ai.risicloud.risime.calls.CallScreenActions
import ai.risicloud.risime.calls.CallScreenUi
import ai.risicloud.risime.calls.CallSnapshot
import ai.risicloud.risime.calls.CallStage
import ai.risicloud.risime.calls.callEncSurface
import ai.risicloud.risime.data.db.LastMessage
import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.data.messaging.ForwardTarget
import ai.risicloud.risime.data.mls.E2EE_INFO_TEXT
import ai.risicloud.risime.data.tabs.Tab
import ai.risicloud.risime.push.NOTIF_OFFICIAL_SUFFIX
import ai.risicloud.risime.push.NOTIF_PRIVATE_SUFFIX
import ai.risicloud.risime.ui.chats.previewMarker
import ai.risicloud.risime.ui.chats.tabIcon
import ai.risicloud.risime.ui.common.HEADER_LOCK_PREFIX
import ai.risicloud.risime.ui.common.RisiTopBar
import ai.risicloud.risime.ui.common.headerSubtitleMarker
import ai.risicloud.risime.ui.group.CREATE_GROUP_E2EE_NOTE
import ai.risicloud.risime.ui.group.createGroupEncryptionNote
import ai.risicloud.risime.ui.settings.ABOUT_OFFICIAL_TEXT
import ai.risicloud.risime.ui.tabs.OFFICIAL_TAB_LABEL
import ai.risicloud.risime.ui.tabs.OfficialInfoUi
import ai.risicloud.risime.ui.tabs.PRIVATE_TAB_LABEL
import ai.risicloud.risime.ui.tabs.officialInfoItems
import ai.risicloud.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Item 6 (Harsha 2026-10-14, honesty): every encryption state × tab (Private 1:1, Private group, Official 1:1,
 * Official group, the Risi chat) maps to its marker and wording. Official and the Risi chat never get the Private
 * lock or the Private wording; Private stays exactly as decision 048 has it (parity).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h740dp")
class EncryptionLabelsTest {
    @get:Rule val rule = createComposeRule()

    private val privateWordings = setOf(EncryptionWording.PRIVATE_SHORT, EncryptionWording.PRIVATE_INFO, E2EE_INFO_TEXT, "End-to-end encrypted")

    /** The whole table, written out: a change to any cell must change this test. */
    private val expected: Map<Pair<EncSurface, Boolean>, EncLabel> = mapOf(
        (EncSurface.PRIVATE_DM to true) to EncLabel(EncMarker.PRIVATE_LOCK, "End-to-end encrypted", "Messages are end-to-end encrypted"),
        (EncSurface.PRIVATE_GROUP to true) to EncLabel(EncMarker.PRIVATE_LOCK, "End-to-end encrypted", "Messages are end-to-end encrypted"),
        (EncSurface.OFFICIAL_DM to true) to EncLabel(
            EncMarker.OFFICIAL_LOCK, "Encrypted · Risi can read",
            "Messages are encrypted on the way and on the server. Risi, whose keys are on RisiMe's server, can read Official. " +
                "Private is only you and the other person.",
        ),
        (EncSurface.OFFICIAL_GROUP to true) to EncLabel(
            EncMarker.OFFICIAL_LOCK, "Encrypted · Risi can read",
            "Messages are encrypted on the way and on the server. Risi, whose keys are on RisiMe's server, can read Official. " +
                "Private is only the people in this chat.",
        ),
        (EncSurface.RISI_CHAT to true) to EncLabel(
            EncMarker.OFFICIAL_LOCK, "Encrypted · Risi can read",
            "Messages are encrypted on the way and on the server. Risi, whose keys are on RisiMe's server, reads this chat to answer you.",
        ),
    ) + EncSurface.entries.associate { (it to false) to EncLabel(EncMarker.NONE, null, null) }

    @Test fun everyStateAndTabMapsToItsMarkerAndWording() {
        for (surface in EncSurface.entries) for (encrypted in listOf(true, false)) {
            assertEquals("$surface encrypted=$encrypted", expected.getValue(surface to encrypted), encryptionLabel(surface, encrypted))
        }
    }

    @Test fun officialNeverMapsToThePrivateLockOrWording() {
        for (surface in EncSurface.entries.filter { it.risiCanRead }) for (encrypted in listOf(true, false)) {
            val l = encryptionLabel(surface, encrypted)
            assertNotEquals("$surface: the Private lock", EncMarker.PRIVATE_LOCK, l.marker)
            assertFalse("$surface: Private wording ${l.short}", l.short in privateWordings)
            assertFalse("$surface: Private wording ${l.info}", l.info in privateWordings)
            if (encrypted) {
                assertTrue("$surface says Risi can read", l.short!!.contains("Risi can read"))
                assertTrue("$surface explains Risi's keys", l.info!!.contains("Risi, whose keys are on RisiMe's server"))
                assertFalse("$surface never says end-to-end", l.short.contains("nd-to-end"))
            }
            val header = headerEncryptionSubtitle(surface, encrypted, "3 members")
            assertFalse("$surface header: the Private lock", header.orEmpty().startsWith(HEADER_LOCK_PREFIX))
            assertFalse("$surface header: no 🔒 anywhere", header.orEmpty().contains("🔒"))
            assertNotEquals(EncMarker.PRIVATE_LOCK, headerSubtitleMarker(header.orEmpty()).first)
        }
        assertEquals(setOf(EncSurface.OFFICIAL_DM, EncSurface.OFFICIAL_GROUP, EncSurface.RISI_CHAT), EncSurface.entries.filter { it.risiCanRead }.toSet())
    }

    @Test fun headerSubtitles() {
        // Private: as before (parity).
        assertEquals("🔒 End-to-end encrypted", headerEncryptionSubtitle(EncSurface.PRIVATE_DM, true, null))
        assertEquals("🔒 3 members", headerEncryptionSubtitle(EncSurface.PRIVATE_GROUP, true, "3 members"))
        assertEquals("3 members", headerEncryptionSubtitle(EncSurface.PRIVATE_GROUP, false, "3 members"))
        // Official and the Risi chat.
        assertEquals("🔐 Encrypted · Risi can read · 3 members", headerEncryptionSubtitle(EncSurface.OFFICIAL_GROUP, true, "3 members"))
        assertEquals("🔐 Encrypted · Risi can read", headerEncryptionSubtitle(EncSurface.OFFICIAL_DM, true, "Official"))
        assertEquals("Official", headerEncryptionSubtitle(EncSurface.OFFICIAL_DM, false, "Official"))
        assertEquals("🔐 Encrypted · Risi can read", headerEncryptionSubtitle(EncSurface.RISI_CHAT, true, "Risi"))
        assertEquals(EncMarker.OFFICIAL_LOCK to "Encrypted · Risi can read", headerSubtitleMarker("🔐 Encrypted · Risi can read"))
        assertEquals(EncMarker.PRIVATE_LOCK to "3 members", headerSubtitleMarker("🔒 3 members"))
        assertEquals(EncMarker.NONE to "typing…", headerSubtitleMarker("typing…"))
    }

    @Test fun surfaceOf() {
        assertEquals(EncSurface.PRIVATE_DM, EncSurface.of(official = false, risiChat = false, group = false))
        assertEquals(EncSurface.PRIVATE_GROUP, EncSurface.of(official = false, risiChat = false, group = true))
        assertEquals(EncSurface.OFFICIAL_DM, EncSurface.of(official = true, risiChat = false, group = false))
        assertEquals(EncSurface.OFFICIAL_GROUP, EncSurface.of(official = true, risiChat = false, group = true))
        assertEquals(EncSurface.RISI_CHAT, EncSurface.of(official = true, risiChat = true, group = true))
    }

    @Test fun listTabNotificationAndForwardMarkersKeepOfficialDistinct() {
        val last = LastMessage("grp:o", "hi", 1, false, "READ", from = "b", kind = MessageEntity.KIND_TEXT, e2ee = MessageEntity.E2EE_YES)
        assertEquals("● ", previewMarker(last, Tab.OFFICIAL, e2eeChat = true))
        assertEquals("🔒 ", previewMarker(last, Tab.PRIVATE, e2eeChat = true))
        assertEquals("● ", tabIcon(Tab.OFFICIAL))
        assertEquals("🔒 ", tabIcon(Tab.PRIVATE))
        assertEquals("🔒 Private", PRIVATE_TAB_LABEL)
        assertEquals("● Official", OFFICIAL_TAB_LABEL)
        assertFalse(OFFICIAL_TAB_LABEL.contains("🔒"))
        assertFalse(NOTIF_OFFICIAL_SUFFIX.contains("🔒"))
        assertTrue(NOTIF_PRIVATE_SUFFIX.contains("🔒"))
        assertFalse(ForwardTarget.OFFICIAL_LABEL.contains("🔒"))
    }

    @Test fun calls() {
        assertEquals(EncMarker.PRIVATE_LOCK to "End-to-end encrypted", callEncryptionLabel(EncSurface.PRIVATE_DM, true))
        assertEquals(EncMarker.PRIVATE_LOCK to "End-to-end encrypted", callEncryptionLabel(EncSurface.PRIVATE_GROUP, true))
        assertEquals(EncMarker.OFFICIAL_LOCK to "Encrypted · Risi can listen", callEncryptionLabel(EncSurface.OFFICIAL_GROUP, true))
        assertEquals(EncMarker.OFFICIAL_LOCK to "End-to-end encrypted · Official", callEncryptionLabel(EncSurface.OFFICIAL_DM, true))
        assertNull("Call Risi: its own banner, never a lock", callEncryptionLabel(EncSurface.RISI_CHAT, true))
        for (s in EncSurface.entries) assertNull("never claimed unverified", callEncryptionLabel(s, false))
        for (s in EncSurface.entries.filter { it.risiCanRead }) {
            assertNotEquals(EncMarker.PRIVATE_LOCK, callEncryptionLabel(s, true)?.first)
            assertNotEquals("End-to-end encrypted", callEncryptionLabel(s, true)?.second)
            assertNotEquals(EncryptionWording.PRIVATE_CALL_INFO, callEncryptionInfo(s, true))
        }
        assertEquals("End-to-end encrypted (MLS-bound DTLS-SRTP)", callEncryptionInfo(EncSurface.PRIVATE_DM, true))
        assertEquals("Not verified yet", callEncryptionInfo(EncSurface.OFFICIAL_GROUP, false))
        val dm = CallSnapshot("c", "dm:a_b", "b", true, CallPhase.ACTIVE)
        val grp = CallSnapshot("c", "grp:o", "b", true, CallPhase.ACTIVE, group = true)
        assertEquals(EncSurface.PRIVATE_DM, callEncSurface(dm, officialGroup = false))
        assertEquals(EncSurface.OFFICIAL_DM, callEncSurface(dm.copy(official = true), officialGroup = false))
        assertEquals(EncSurface.PRIVATE_GROUP, callEncSurface(grp, officialGroup = false))
        assertEquals(EncSurface.OFFICIAL_GROUP, callEncSurface(grp, officialGroup = true))
        assertEquals(EncSurface.RISI_CHAT, callEncSurface(dm.copy(risiCall = true), officialGroup = false))
    }

    @Test fun helpTexts() {
        assertEquals(CREATE_GROUP_E2EE_NOTE, createGroupEncryptionNote(tabsOn = false))
        assertTrue(createGroupEncryptionNote(tabsOn = true).contains("In Official, Risi can read"))
        assertTrue(ABOUT_OFFICIAL_TEXT.contains("Risi can read"))
        assertFalse(VoiceStrings.MIC_RATIONALE.contains("end-to-end"))
    }

    // ---- UI: the header, chat info and the call screen draw the right marker ----

    private fun header(subtitle: String) = rule.setContent { RisiMeTheme { RisiTopBar(title = "Team", subtitle = subtitle, onBack = {}) } }

    @Test fun officialHeaderDrawsTheOfficialLockNotThePrivateOne() {
        header(headerEncryptionSubtitle(EncSurface.OFFICIAL_GROUP, true, "3 members")!!)
        rule.onNodeWithTag("risi_header_official_lock", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("risi_header_lock", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("Encrypted · Risi can read · 3 members", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun privateHeaderStillDrawsThePrivateLock() {
        header(headerEncryptionSubtitle(EncSurface.PRIVATE_GROUP, true, "3 members")!!)
        rule.onNodeWithTag("risi_header_lock", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("risi_header_official_lock", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("3 members", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun chatInfoOfficialSectionExplainsRisiCanRead() {
        rule.setContent { RisiMeTheme { LazyColumn { officialInfoItems(OfficialInfoUi(), {}, {}, {}, dm = true) } } }
        rule.onNodeWithTag(OFFICIAL_ENCRYPTION_TAG).assertIsDisplayed()
        rule.onNodeWithText("Encrypted · Risi can read").assertIsDisplayed()
        rule.onNodeWithText(EncryptionWording.OFFICIAL_INFO_DM).assertIsDisplayed()
    }

    private fun call(surface: EncSurface) = rule.setContent {
        RisiMeTheme {
            CallScreen(CallScreenUi(name = "Team", status = "0:12", stage = CallStage.ACTIVE, encrypted = true, encSurface = surface), CallScreenActions())
        }
    }

    @Test fun officialGroupCallNeverSaysEndToEnd() {
        call(EncSurface.OFFICIAL_GROUP)
        rule.onNodeWithText("Encrypted · Risi can listen").assertIsDisplayed()
        rule.onNodeWithText("End-to-end encrypted").assertDoesNotExist()
        rule.onNodeWithTag("call_enc_official_lock", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("call_enc_private_lock", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun privateCallUnchanged() {
        call(EncSurface.PRIVATE_DM)
        rule.onNodeWithText("End-to-end encrypted").assertIsDisplayed()
        rule.onNodeWithTag("call_enc_private_lock", useUnmergedTree = true).assertExists()
    }
}
