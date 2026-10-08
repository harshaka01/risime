package lk.codegen.risime.ui.backup

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import lk.codegen.risime.data.backup.BackupRecord
import lk.codegen.risime.data.backup.BackupStatus
import lk.codegen.risime.data.backup.PassphraseFloor
import lk.codegen.risime.data.backup.SecretKind
import lk.codegen.risime.net.BackupsReply
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §22.7 on the JVM: Settings → Backups, the recovery key (show once, type two groups back), the secret entry and the restore gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupUiTest {
    @get:Rule val rule = createComposeRule()

    private val key = "7K2M-Q9XD-4HTW-PB3N-8FZR-CJ6V-1E0A"

    /** Sets a field's text without focusing it (a focused field's cursor blinks forever under Robolectric). */
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.setText(t: String) =
        rule.runOnUiThread { fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.SetText].action!!.invoke(androidx.compose.ui.text.AnnotatedString(t)) }

    private fun frames() = Unit

    @Test fun backupsScreenShowsStateAndActions() {
        val clicks = mutableListOf<String>()
        val s = BackupStatus(available = true, lastLocal = BackupRecord(1_760_000_000_000, 3_145_728, "b1"), serverOn = true, keySetUp = true)
        rule.setContent {
            RisiMeTheme {
                BackupsContent(
                    s, serverAvailable = true,
                    onBackUpNow = { clicks += "now" }, onExport = { clicks += "export" }, onRestoreFile = { clicks += "file" },
                    onServer = { clicks += "server:$it" }, onMobileData = { clicks += "mobile:$it" }, onShowKey = { clicks += "key" },
                    onChangeKey = { clicks += "change" }, onResetKey = { clicks += "reset" },
                )
            }
        }
        rule.onNodeWithText(BACK_UP_NOW).performClick()
        rule.onNodeWithText(EXPORT_BACKUP).performClick()
        rule.onNodeWithText(RESTORE_FROM_FILE).performClick()
        rule.onNodeWithText("Last backup: ${recordText(s.lastLocal, "")}").assertIsDisplayed()
        rule.onNodeWithText("3.0 MB", substring = true).assertIsDisplayed()
        rule.onNodeWithText(SERVER_BACKUP_LABEL).performScrollTo().performClick()
        rule.onNodeWithText(USE_MOBILE_DATA).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(RESET_BACKUP_KEY).performScrollTo().performClick()
        assertEquals(listOf("now", "export", "file", "server:false", "reset"), clicks)
    }

    @Test fun withoutTheCoreOrAServerSwitchNothingPretends() {
        rule.setContent {
            RisiMeTheme { BackupsContent(BackupStatus(available = false), false, {}, {}, {}, {}, {}, {}, {}, {}) }
        }
        rule.onNodeWithText(BACK_UP_NOW).assertIsNotEnabled()
        rule.onNodeWithText("This server doesn't offer backups.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Last backup: none yet").assertIsDisplayed()
    }

    @Test fun theRecoveryKeyIsShownOnceAndConfirmedWithTwoGroups() {
        var confirmed = false
        var copied = false
        rule.setContent { RisiMeTheme { RecoveryKeyContent(key, onCopy = { copied = true }, onConfirmed = { confirmed = true }, onCancel = {}) } }
        rule.onNodeWithTag("recovery_key").assertIsDisplayed()
        rule.onNodeWithText(key).assertIsDisplayed()
        rule.onNodeWithText("Copy").performClick()
        rule.onNodeWithText(I_SAVED_IT).performClick()
        rule.onNodeWithText("type group 2 and group 7", substring = true).assertIsDisplayed()
        frames() // a focused field's cursor blinks forever: step the clock by hand from here
        rule.onNodeWithTag("confirm_group_0").setText("q9xd")
        rule.onNodeWithTag("confirm_group_1").setText("1E0B")
        rule.waitForIdle()
        rule.onNodeWithText("Confirm").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("That doesn't match", substring = true).assertIsDisplayed()
        assertFalse(confirmed)
        rule.onNodeWithText("Show key again").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(I_SAVED_IT).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("confirm_group_0").setText("q9xd")
        rule.onNodeWithTag("confirm_group_1").setText("1EOA") // O→0
        rule.waitForIdle()
        rule.onNodeWithText("Confirm").performClick()
        rule.waitForIdle()
        assertTrue(confirmed && copied)
    }

    @Test fun secretEntryRestoresWithAKeyOrAPassphrase() {
        val submitted = mutableListOf<Pair<String, SecretKind>>()
        rule.setContent {
            RisiMeTheme { SecretEntryContent(RESTORE_TITLE, "Enter it", "That recovery key or passphrase doesn't match", false, { null }, { s, k -> submitted += s to k }, {}) }
        }
        rule.onNodeWithText("That recovery key or passphrase doesn't match").assertIsDisplayed()
        rule.onNodeWithText(RESTORE_BUTTON).assertIsNotEnabled()
        frames()
        rule.onNodeWithTag("secret").setText(key)
        rule.waitForIdle()
        rule.onNodeWithText(RESTORE_BUTTON).assertIsEnabled().performClick()
        rule.onNodeWithText(USE_PASSPHRASE).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(PASSPHRASE_LABEL).assertIsDisplayed()
        rule.onNodeWithTag("secret").setText("correct horse battery staple")
        rule.waitForIdle()
        rule.onNodeWithText(RESTORE_BUTTON).performClick()
        assertEquals(listOf(key to SecretKind.RecoveryKey, "correct horse battery staple" to SecretKind.Passphrase), submitted)
    }

    @Test fun theGateOffersTheNewestBackupAndSkipNeedsAConfirmation() {
        val backups = ProtocolJson.decodeFromString(BackupsReply.serializer(), javaClass.classLoader!!.getResource("contract/v1/examples/backups_reply.json")!!.readText())
        var restore = 0
        var skipped = 0
        rule.setContent { RisiMeTheme { RestoreGateContent(backups.newest()!!, null, null, { restore++ }, {}, { skipped++ }) } }
        rule.onNodeWithText(RESTORE_TITLE).assertIsDisplayed()
        rule.onNodeWithText("Pixel 8", substring = true).assertIsDisplayed()
        rule.onNodeWithText(RESTORE_BUTTON).performClick()
        rule.onNodeWithText(SKIP_BUTTON).performClick()
        rule.onNodeWithText(SKIP_CONFIRM_TEXT).assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(0, skipped)
        rule.onNodeWithText(SKIP_BUTTON).performClick()
        rule.onNodeWithText("Skip restore?").assertIsDisplayed()
        rule.onAllNodes(androidx.compose.ui.test.hasText(SKIP_BUTTON) and androidx.compose.ui.test.hasClickAction())[1].performClick()
        assertEquals(1, restore)
        assertEquals(1, skipped)
    }

    @Test fun passphraseFloorAndGroupChecks() {
        val common = setOf("password1234567")
        assertEquals("Enter a passphrase", passphraseProblem(" ", null, common))
        assertTrue(passphraseProblem("short", PassphraseFloor.TooShort, common)!!.startsWith("Use at least 14"))
        assertTrue(passphraseProblem("my 0771234567 phone", PassphraseFloor.PhoneNumber, common)!!.contains("phone number"))
        assertEquals("That's one of the most common passwords", passphraseProblem("Password1234567", PassphraseFloor.Ok, common))
        assertNull(passphraseProblem("correct horse battery staple", PassphraseFloor.Ok, common))
        assertTrue(confirmGroupsMatch(key, listOf("Q9XD", "1E0A")))
        assertFalse(confirmGroupsMatch(key, listOf("Q9XD")))
        assertFalse(confirmGroupsMatch("ABCD", listOf("Q9XD", "1E0A")))
        assertEquals("risime-backup-", lk.codegen.risime.data.backup.BackupManager.exportName(0).take(14))
        assertTrue(lk.codegen.risime.data.backup.BackupManager.exportName(0).endsWith(".risimebk"))
    }
}
