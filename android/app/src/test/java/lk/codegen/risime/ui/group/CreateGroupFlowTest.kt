package lk.codegen.risime.ui.group

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.db.AppDatabase
import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.User
import lk.codegen.risime.ui.theme.RisiMeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * New group with the real [CreateGroupViewModel] and [AppContainer] (P0 hotfix): ticking members
 * (whole row), naming, and Create queues the create op with exactly the ticked members. A friend
 * who isn't group-ready can't be ticked, and a tap explains why.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CreateGroupFlowTest {
    @get:Rule val rule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val me = "5b0c1f3e-0000-4000-8000-00000000000a"
    private val kamal = "5b0c1f3e-0000-4000-8000-00000000000b"
    private val sunil = "5b0c1f3e-0000-4000-8000-00000000000c"
    private val nimal = "5b0c1f3e-0000-4000-8000-00000000000d"

    private val c: AppContainer by lazy {
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { File(app.filesDir, "datastore/g-${System.nanoTime()}.preferences_pb") }
        runBlocking {
            prefs.edit {
                it[stringPreferencesKey("auth_kind")] = "DEV"
                it[stringPreferencesKey("token")] = "dev-token"
                it[stringPreferencesKey("user")] = ProtocolJson.encodeToString(User.serializer(), User(me, "+10000000001", "Me", "Co"))
                it[stringPreferencesKey("server_url")] = "http://127.0.0.1:9"
            }
        }
        AppContainer(app, { ctx ->
            Room.databaseBuilder(ctx, AppDatabase::class.java, "g-${System.nanoTime()}.db").setDriver(BundledSQLiteDriver()).build()
        }, prefs).also { container ->
            runBlocking {
                container.db.contacts().upsertAll(
                    listOf(
                        ContactEntity("+10000000002", "Kamal", "Rise", kamal, registered = true, friend = true, groupReady = true),
                        ContactEntity("+10000000003", "Sunil", "Rise", sunil, registered = true, friend = true, groupReady = true),
                        ContactEntity("+10000000004", "Nimal", "CodeGen", nimal, registered = true, friend = true, groupReady = false),
                    ),
                )
            }
        }
    }

    // The DB stays open: the ViewModel's create coroutine outlives the test body.
    @After fun tearDown() = scope.cancel()

    private fun row(name: String) = rule.onAllNodes(isToggleable() and hasText(name)).onFirst()

    @Test fun ticksMembersNamesAndCreates() {
        val vm = CreateGroupViewModel(c)
        rule.setContent { RisiMeTheme { CreateGroupScreen(vm, onCreated = {}, onBack = {}) } }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Kamal")).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("Next").assertIsNotEnabled()
        row("Kamal").assertIsOff()
        rule.onNodeWithText("Rise").let { } // company line is part of the row
        row("Kamal").performClick()
        row("Kamal").assertIsOn()
        rule.onNodeWithText("Sunil").performClick() // a tap on the name toggles the whole row
        row("Sunil").assertIsOn()
        rule.onNodeWithText("2 selected").let { }
        row("Sunil").performClick()
        row("Sunil").assertIsOff()
        row("Sunil").performClick()

        // Not group-ready: not toggleable; a tap explains.
        rule.onNodeWithText("Nimal").performClick()
        rule.onNodeWithText(notReadyExplanation("Nimal")).let { }
        assertEquals(setOf(kamal, sunil), vm.ui.value.selected)

        rule.onNodeWithText("Next").assertIsEnabled().performClick()
        rule.onNodeWithText("Create").assertIsNotEnabled()
        rule.onNodeWithText("Group name").performTextInput("Pilot team")
        rule.onNodeWithText("Create").assertIsEnabled().performClick()

        rule.waitUntil(10_000) { runBlocking { c.db.groupOps().get(1) } != null }
        val create = runBlocking { c.db.groupOps().get(1) }!!
        assertEquals("create", create.type)
        assertTrue(create.payloadJson.contains(kamal) && create.payloadJson.contains(sunil) && !create.payloadJson.contains(nimal))
        assertTrue(create.payloadJson.contains("Pilot team"))
    }

    @Test fun rowsAreAtLeast48dp() {
        val vm = CreateGroupViewModel(c)
        rule.setContent { RisiMeTheme { CreateGroupScreen(vm, onCreated = {}, onBack = {}) } }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Kamal")).fetchSemanticsNodes().isNotEmpty() }
        val h = row("Kamal").fetchSemanticsNode().size.height
        val density = app.resources.displayMetrics.density
        assertTrue("row height ${h / density}dp", h / density >= 48f)
    }
}
