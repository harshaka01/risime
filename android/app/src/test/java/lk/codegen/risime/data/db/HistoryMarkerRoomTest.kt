package lk.codegen.risime.data.db

import android.app.Application
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import lk.codegen.risime.data.HistoryMarkers
import lk.codegen.risime.data.groups.SystemLine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §13.3 marker rows against the real Room SQL (in-memory): forward-only upsert and every exclusion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HistoryMarkerRoomTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java)
        .setDriver(BundledSQLiteDriver())
        .build()
    private val dao = db.messages()
    private val conv = "dm:a_b"

    @After fun close() = db.close()

    private fun msg(id: String, ts: Long, outgoing: Boolean = false, status: String = "DELIVERED") =
        MessageEntity(id, "m-$id", conv, if (outgoing) "a" else "b", if (outgoing) "b" else "a", "text $id", null, ts, status, outgoing)

    @Test
    fun upsertIsOneRowAndMovesForwardOnly() = runBlocking {
        val id = HistoryMarkers.historyId(conv)
        dao.upsertSystemLine(HistoryMarkers.row(conv, SystemLine.HISTORY_GAP, "2026-10-01T10:00:00.000Z", 0))
        dao.upsertSystemLine(HistoryMarkers.row(conv, SystemLine.HISTORY_GAP, "2026-09-01T10:00:00.000Z", 0)) // earlier: ignored
        assertEquals("2026-10-01T10:00:00.000Z", dao.byClientMsgId(id)!!.serverTs)
        dao.upsertSystemLine(HistoryMarkers.row(conv, SystemLine.HISTORY_GAP, "2026-10-02T10:00:00.000Z", 0)) // later: moves
        val row = dao.byClientMsgId(id)!!
        assertEquals("2026-10-02T10:00:00.000Z", row.serverTs)
        assertEquals(HistoryMarkers.epochMs("2026-10-02T10:00:00.000Z")!! + 1, row.localTs)
        assertEquals(SystemLine.HISTORY_GAP_TEXT, row.body)
        assertEquals(1, dao.conversation(conv).first().size)
        // The undecryptable line is a separate row.
        dao.upsertSystemLine(HistoryMarkers.row(conv, SystemLine.UNDECRYPTABLE, "2026-10-03T10:00:00.000Z", 0))
        assertEquals(listOf(id, HistoryMarkers.undecryptableId(conv)), dao.conversation(conv).first().map { it.clientMsgId })
    }

    @Test
    fun markersAreNeverUnreadAckedNotifiedOrSearchedButCanBeThePreview() = runBlocking {
        val ts = HistoryMarkers.epochMs("2026-10-01T10:00:00.000Z")!!
        dao.insert(msg("old", ts - 1000, status = "READ").copy(ackedStatus = "READ"))
        dao.upsertSystemLine(HistoryMarkers.row(conv, SystemLine.HISTORY_GAP, "2026-10-01T10:00:00.000Z", 0))
        assertTrue(dao.unreadCounts().first().isEmpty())
        assertTrue(dao.unreadIncoming().isEmpty())
        assertTrue(dao.unackedIncoming().isEmpty())
        assertEquals(0, dao.markIncomingRead(conv))
        assertTrue(dao.search("%available%", 10).first().isEmpty())
        // Marker-only tail: the chat-list preview is the marker (muted, no "You:").
        val last = dao.lastMessages().first().single()
        assertEquals(MessageEntity.KIND_SYSTEM, last.kind)
        assertEquals(SystemLine.HISTORY_GAP_TEXT, last.body)
        // A readable message after it becomes the preview.
        dao.insert(msg("new", ts + 5000, outgoing = true, status = "SENT"))
        assertEquals("text new", dao.lastMessages().first().single().body)
    }
}
