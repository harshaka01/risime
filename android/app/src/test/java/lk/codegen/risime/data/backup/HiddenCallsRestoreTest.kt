package lk.codegen.risime.data.backup

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import lk.codegen.risime.calls.CallRecords
import lk.codegen.risime.calls.visibleCalls
import lk.codegen.risime.data.backup.BackupData.DM
import lk.codegen.risime.data.db.CallLogMarkEntity
import lk.codegen.risime.ui.chats.hideCallRecords
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Calls tab's local hidden set (hard rule 9): hiding a call changes only the Calls list. The call row in the
 * chat stays, and a restore / history import that brings the same call back never resurrects it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HiddenCallsRestoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val phones = mutableListOf<BackupPhone>()
    private fun phone() = BackupPhone(dir = tmp.newFolder()).also { phones += it }

    @After fun close() = phones.forEach { it.close() }

    private suspend fun BackupPhone.lines() = db.messages().observeCallLines().first()
    private suspend fun BackupPhone.records() = lines().mapNotNull { CallRecords.of(it) }
    private suspend fun BackupPhone.visible() = visibleCalls(records(), db.callLog().hiddenIds().first().toSet(), db.callLog().clearedBefore().first())

    @Test fun hiddenCallsStayHiddenAfterARestoreAndTheChatRowStays() = runBlocking {
        val a = phone()
        BackupData.fill(a)
        val all = a.records()
        assertTrue(all.isNotEmpty())
        val chatRows = a.lines().size
        assertEquals(all.size, a.visible().size)
        hideCallRecords(a.db.callLog(), all, 1L)
        assertEquals(0, a.visible().size)
        // The chat's call rows are messages: untouched.
        assertEquals(chatRows, a.lines().size)
        // A restore of the same bundle into the same phone (merge) and into a phone that hid the calls first.
        val bundle = bundleOf(a)
        a.importer().import(bundle.iterator())
        val b = phone()
        BackupData.fill(b)
        hideCallRecords(b.db.callLog(), b.records(), 1L)
        b.importer().import(bundle.iterator())
        for (p in listOf(a, b)) {
            assertEquals(0, p.visible().size)
            assertEquals(chatRows, p.lines().size)
            assertTrue(p.counts()[DM]!![2] >= 1)
        }
    }

    @Test fun clearedBeforeMarkHidesCallsUpToIt() = runBlocking {
        val a = phone()
        BackupData.fill(a)
        val all = a.records()
        a.db.callLog().setMark(CallLogMarkEntity(0, all.maxOf { it.atMs }))
        assertEquals(0, a.visible().size)
        assertEquals(all, visibleCalls(all, emptySet(), null))
    }

    @Test fun logoutWipeClearsTheHiddenSetToo() = runBlocking {
        val a = phone()
        BackupData.fill(a)
        hideCallRecords(a.db.callLog(), a.records(), 1L)
        a.db.callLog().setMark(CallLogMarkEntity(0, 5))
        a.db.wipe().allChatData()
        assertTrue(a.db.callLog().hiddenIds().first().isEmpty())
        assertEquals(null, a.db.callLog().clearedBefore().first())
    }
}
