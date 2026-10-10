package lk.codegen.risime.data.gcal

import android.app.Application
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import lk.codegen.risime.data.db.AppDatabase
import lk.codegen.risime.data.db.GcalCalendarEntity
import lk.codegen.risime.data.db.GcalCopyEntity
import lk.codegen.risime.data.db.GcalCopyState
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.GcalLinkReasons
import lk.codegen.risime.net.GoogleCalendarLinkData
import lk.codegen.risime.net.GoogleLink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * HARD RULE 9 with the Google link (decision 055): connect, reconnect, disconnect, replaced and a missed disconnect
 * leave `gcal_*` empty where the contract says so and NEVER touch a chat message. Real Room (in memory) with the
 * per-conversation counts compared before and after each path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GcalKeepsChatsTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java).setDriver(BundledSQLiteDriver()).build()
    private val g = FakeGoogle()
    private val rest = FakeRest()
    private val auth = FakeAuthorizer(mutableListOf(g.token))
    private val m: GcalLinkManager

    init {
        val api = g.api(auth)
        lateinit var mm: GcalLinkManager
        val copies = GcalCopies(api, db.gcal(), events = { emptyList() }, config = { mm.copyConfig(true) })
        mm = GcalLinkManager(rest, auth, api, db.gcal(), copies, myDevice = { "me" }, skillOff = { false }, enableSkill = { true }, scheduleCopy = {})
        m = mm
    }

    @After fun close() { db.close(); g.shutdown() }

    private suspend fun seedChats() {
        val dao = db.messages()
        for ((conv, n) in listOf("dm:a_b" to 5, "grp:11111111-1111-4111-8111-111111111111" to 3, "dm:a_c" to 2)) {
            repeat(n) { i -> dao.insert(MessageEntity("$conv-$i", "m$conv$i", conv, "a", "b", "hello $i", null, 1000L + i, "READ", i % 2 == 0)) }
        }
    }

    private suspend fun counts() = listOf("dm:a_b", "grp:11111111-1111-4111-8111-111111111111", "dm:a_c").associateWith { db.messages().conversation(it).first().size }

    private val cals = listOf(GCalendar("work", "Work", true, "owner", true, false), GCalendar("pers", "Personal", false, "owner", true, false))

    @Test fun everyLinkPathKeepsEveryChatMessage() = runBlocking {
        seedChats()
        val before = counts()
        assertEquals(mapOf("dm:a_b" to 5, "grp:11111111-1111-4111-8111-111111111111" to 3, "dm:a_c" to 2), before)
        val sel = GcalSelection(cals, setOf("work", "pers"), "work", true)
        m.connect(sel, "a@test")
        assertEquals(2, db.gcal().calendars().size)
        assertEquals(before, counts())
        m.markReauth(); m.reconnected()
        assertEquals(before, counts())
        db.gcal().upsertCopy(GcalCopyEntity("e1", "work", "risi00", 1, GcalCopyState.COPIED, 1))
        m.disconnect(removeCopies = false)
        assertTrue(db.gcal().calendars().isEmpty() && db.gcal().copies().isEmpty())
        assertEquals(before, counts())
        // replaced
        m.connect(sel, "a@test")
        db.gcal().upsertCopy(GcalCopyEntity("e1", "work", "risi00", 1, GcalCopyState.COPIED, 1))
        rest.link = GoogleLink("connected", "new", "Pixel 9", 2, true, true, "t", "t")
        m.onLinkEvent(GoogleCalendarLinkData("connected", "new", GcalLinkReasons.REPLACED))
        assertTrue(db.gcal().calendars().isEmpty() && db.gcal().copies().isEmpty())
        assertEquals(before, counts())
        // a missed disconnect, then a confirmed logout
        rest.link = GoogleLink.NONE
        m.connect(sel, "a@test")
        rest.link = GoogleLink.NONE
        m.refresh()
        assertTrue(db.gcal().calendars().isEmpty())
        assertEquals(before, counts())
        m.connect(sel, "a@test")
        m.onLogout()
        assertTrue(db.gcal().calendars().isEmpty() && db.gcal().copies().isEmpty())
        assertEquals(before, counts())
    }

    @Test fun theWipeOfAConfirmedLogoutClearsTheGcalTablesWithTheChatData() = runBlocking {
        seedChats()
        db.gcal().upsertCalendars(listOf(GcalCalendarEntity("work", "g1", "Work", "owner", true, true, "a@test")))
        db.gcal().upsertCopy(GcalCopyEntity("e1", "work", "risi00", 1, GcalCopyState.COPIED, 1))
        db.wipe().allChatData()
        assertTrue(db.gcal().calendars().isEmpty() && db.gcal().copies().isEmpty())
        assertEquals(0, db.messages().countAll())
    }
}
