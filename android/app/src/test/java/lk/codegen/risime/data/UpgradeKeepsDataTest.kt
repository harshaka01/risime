package lk.codegen.risime.data

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.db.AppDatabase
import lk.codegen.risime.net.ProtocolJson
import org.robolectric.ParameterizedRobolectricTestRunner
import lk.codegen.risime.net.User
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P0 nightly.10: a phone updated from a released build keeps every chat. Each test writes a real
 * database file in a released schema (1–5, the exported JSON) plus the nightly.9-era DataStore
 * keys, then starts the real [AppContainer] on it (Room migrations run), signs the same account in
 * again through the container's sign-in path, and asserts that nothing was wiped.
 */
abstract class ReleasedInstallFixture {
    protected val app = ApplicationProvider.getApplicationContext<Application>()
    protected val me = "5b0c1f3e-0000-4000-8000-00000000000a"
    protected val peer = "5b0c1f3e-0000-4000-8000-00000000000b"
    protected val conv = "dm:${minOf(me, peer)}_${maxOf(me, peer)}"
    protected val schemas = File(System.getProperty("risime.schemas"), AppDatabase::class.java.name)

    protected fun schema(v: Int) = Json.parseToJsonElement(File(schemas, "$v.json").readText()).jsonObject["database"]!!.jsonObject

    /** A database file exactly as build [v] left it: its tables, Room's identity hash and two DM messages. */
    protected fun writeReleasedDb(v: Int) {
        val file = app.getDatabasePath("risime.db").also { it.parentFile!!.mkdirs(); it.delete() }
        val db = BundledSQLiteDriver().open(file.absolutePath)
        try {
            val s = schema(v)
            s["entities"]!!.jsonArray.map { it.jsonObject }.forEach { e ->
                val name = e["tableName"]!!.jsonPrimitive.content
                db.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                e["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name)) }
            }
            s["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            val cols = "client_msg_id, message_id, conversation_id, from_id, to_id, body, server_ts, local_ts, status, outgoing"
            db.execSQL("INSERT INTO messages ($cols) VALUES ('c1', 'm1', '$conv', '$peer', '$me', 'hello', '2026-10-01T10:00:00.000Z', 1000, 'READ', 0)")
            db.execSQL("INSERT INTO messages ($cols) VALUES ('c2', 'm2', '$conv', '$me', '$peer', 'hi back', '2026-10-01T10:01:00.000Z', 2000, 'READ', 1)")
            db.execSQL("INSERT INTO contacts (phone, display_name, company, user_id, registered) VALUES ('+10000000000', 'Peer', 'Co', '$peer', 1)")
            db.execSQL("INSERT INTO sync_state (id, last_event_id) VALUES (0, 'ev-cursor')")
            db.execSQL("INSERT INTO seen_events (event_id) VALUES ('ev-cursor')")
            db.execSQL("PRAGMA user_version = $v")
        } finally {
            db.close()
        }
    }

    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    protected val prefsFile = File(app.filesDir, "datastore/upgrade-${System.nanoTime()}.preferences_pb")
    protected val prefs = PreferenceDataStoreFactory.create(scope = scope) { prefsFile }

    @After fun tearDown() = scope.cancel()

    /** The DataStore as a nightly.9 sign-in left it ([lastUser] exactly as that build stored it). */
    protected fun writeReleasedPrefs(lastUser: String, signedIn: Boolean) = runBlocking {
        prefs.edit {
            it.clear()
            if (signedIn) {
                it[stringPreferencesKey("auth_kind")] = "DEV"
                it[stringPreferencesKey("token")] = "dev-token"
                it[stringPreferencesKey("user")] = ProtocolJson.encodeToString(User.serializer(), user(lastUser))
            }
            it[stringPreferencesKey("last_user_id")] = lastUser
            it[stringPreferencesKey("device_id")] = "dev-1"
            it[stringPreferencesKey("server_url")] = "http://127.0.0.1:9"
        }
    }

    /** The production container on the same file; only the SQLite driver differs (JVM). */
    protected fun container() = AppContainer(app, { ctx ->
        Room.databaseBuilder(ctx, AppDatabase::class.java, "risime.db")
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
    }, prefs)

    protected fun user(id: String) = User(id = id, phone = "+10000000001", displayName = "Me", company = "Co")

    protected fun AppContainer.counts() = runBlocking { db.messages().countAll() to db.contacts().all().first().size }

    protected fun upgradeThenSignIn(fromVersion: Int, storedId: String, signInId: String, signedIn: Boolean): AppContainer {
        writeReleasedDb(fromVersion)
        writeReleasedPrefs(storedId, signedIn)
        val c = container()
        assertEquals("v$fromVersion rows survive the migration", 2 to 1, c.counts())
        runBlocking {
            // A forced re-sign-in (session ended), then the same account signs in again.
            c.signOutKeepData("Sign in again — your chats are kept.")
            c.onLoggedIn("dev-token", user(signInId))
        }
        return c
    }

}

/**
 * nightly.9 (schema v4) installs, signed in again through [AppContainer]: only a confirmed other
 * account wipes; history recovery replays the inbox once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpgradeKeepsDataTest : ReleasedInstallFixture() {
    @Test fun nightly9SchemaUpgradesAndTheSameAccountKeepsItsChats() {
        val c = upgradeThenSignIn(4, me, me, signedIn = true) // nightly.9 shipped schema v4
        assertEquals("nothing wiped", 2 to 1, c.counts())
        assertEquals("ev-cursor", runBlocking { c.db.sync().cursor() })
        c.db.close()
    }

    @Test fun nightly9SameAccountWithADifferentIdSpellingKeepsChats() {
        val c = upgradeThenSignIn(4, me.uppercase(), " $me ", signedIn = false)
        assertEquals(2 to 1, c.counts())
        c.db.close()
    }

    @Test fun anUncertainPreviousIdKeepsChats() {
        val c = upgradeThenSignIn(4, "not-a-uuid", me, signedIn = false)
        assertEquals(2 to 1, c.counts())
        c.db.close()
    }

    @Test fun aConfirmedDifferentAccountWipes() {
        val c = upgradeThenSignIn(4, peer, me, signedIn = false)
        assertEquals(0 to 0, c.counts())
        assertNull(runBlocking { c.db.sync().cursor() })
        c.db.close()
    }

    /** Every former "back to sign-in" path keeps the chats and the owner (no automatic logout). */
    @Test fun automaticSignInProblemsKeepChats() {
        writeReleasedDb(4)
        writeReleasedPrefs(me, signedIn = true)
        val c = container()
        runBlocking {
            c.handleAuthError(lk.codegen.risime.net.ApiResult.Error(401, "invalid_token", ""))
            c.handleAuthError401(lk.codegen.risime.net.ApiResult.Error(401, "invalid_token", ""))
            c.signOutKeepData("Your RisiCloud session ended. Sign in again — your chats are kept.")
        }
        assertEquals(2 to 1, c.counts())
        assertEquals(me, runBlocking { c.sessionStore.lastUserId() })
        assertNull("signed out: the sign-in screen shows", runBlocking { c.sessionStore.current() })
        c.db.close()
    }

    @Test fun confirmedLogoutWipesLocallyEvenWithTheServerUnreachable() {
        writeReleasedDb(4)
        writeReleasedPrefs(me, signedIn = true) // server_url points at a closed port
        val c = container()
        runBlocking { c.logout(testConfirmation()) }
        assertEquals(0 to 0, c.counts())
        assertNull(runBlocking { c.sessionStore.current() })
        c.db.close()
    }

    @Test fun recoveryReplaysTheInboxOnceOnTheFixedBuild() {
        writeReleasedDb(4)
        writeReleasedPrefs(me, signedIn = true)
        val c = container()
        val reset = runBlocking {
            c.localAccount.recoverHistoryIfNeeded({ c.db.messages().countAll() }, { c.db.sync().cursor() }, { c.db.wipe().syncState() })
        }
        assertEquals(true, reset)
        assertNull("one-time replay: the next join is since:null", runBlocking { c.db.sync().cursor() })
        assertNotNull("seen_events stay, so the replay only adds what is missing", runBlocking { c.db.sync().seenCount("ev-cursor") })
        assertEquals(2 to 1, c.counts())
        c.db.close()
    }
}

/** Every released schema (1..[AppDatabase.VERSION]) upgrades through the real container with its rows. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EveryReleasedSchemaUpgradeTest(private val version: Int) : ReleasedInstallFixture() {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "schema v{0}")
        fun versions(): List<Array<Any>> = (1..AppDatabase.VERSION).map { arrayOf<Any>(it) }
    }

    @Test fun upgradesAndTheSameAccountKeepsItsChats() {
        val c = upgradeThenSignIn(version, me, me, signedIn = true)
        assertEquals("v$version: nothing wiped", 2 to 1, c.counts())
        assertEquals("ev-cursor", runBlocking { c.db.sync().cursor() })
        c.db.close()
    }
}
