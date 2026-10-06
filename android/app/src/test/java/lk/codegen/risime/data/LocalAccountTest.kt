package lk.codegen.risime.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import lk.codegen.risime.net.User
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Every wipe path (P0 nightly.10): wipe only on explicit logout, server change or a confirmed other account. */
class LocalAccountTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy {
        SessionStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s.preferences_pb") }, "http://default")
    }
    private val wipes = mutableListOf<WipeReason>()
    private val account by lazy { LocalAccount(store, { wipes += it }) }

    private val a = "5b0c1f3e-0000-4000-8000-00000000000a"
    private val b = "5b0c1f3e-0000-4000-8000-00000000000b"

    private fun user(id: String) = User(id = id, phone = "+10000000001", displayName = "Me", company = "Co")

    @After fun close() = scope.cancel()

    @Test fun firstSignInNeverWipes() = runBlocking {
        assertEquals(SignInDecision.KEEP, account.beforeSignIn(user(a)))
        assertTrue(wipes.isEmpty())
    }

    @Test fun sameAccountAgainKeepsData() = runBlocking {
        store.saveOidcLogin(user(a))
        account.signOutKeepData() // session ended / refresh failed / key invalidated
        assertEquals(SignInDecision.KEEP, account.beforeSignIn(user(a)))
        assertEquals(SignInDecision.KEEP, account.beforeSignIn(user(a.uppercase())))
        assertEquals(SignInDecision.KEEP, account.beforeSignIn(user(" $a ")))
        assertTrue(wipes.isEmpty())
        assertEquals(a, store.lastUserId())
    }

    @Test fun storedIdFromAnOlderBuildInAnotherSpellingKeepsData() = runBlocking {
        store.saveLogin("t", user(a.uppercase())) // stored normalized from now on
        assertEquals(a, store.lastUserId())
        assertEquals(SignInDecision.KEEP, account.beforeSignIn(user(a)))
        assertTrue(wipes.isEmpty())
    }

    @Test fun uncertainIdentityKeepsData() = runBlocking {
        store.saveLogin("t", user("legacy-id"))
        assertEquals(SignInDecision.KEEP, account.beforeSignIn(user(a)))
        assertEquals(SignInDecision.KEEP, account.beforeSignIn(user("")))
        assertTrue(wipes.isEmpty())
    }

    @Test fun aDifferentAccountAsksFirstAndWipesOnlyOnYes() = runBlocking {
        store.saveOidcLogin(user(a).copy(displayName = "Kamal"))
        account.signOutKeepData() // e.g. blocked screen → "Sign in with another account"
        val asked = mutableListOf<Pair<String?, String>>()
        // Cancel: data kept, nothing wiped, the owner stays.
        assertEquals(SignInDecision.CANCELLED, account.beforeSignIn(user(b).copy(displayName = "Nimal")) { p, n -> asked += p to n; false })
        assertTrue(wipes.isEmpty())
        assertEquals(a, store.lastUserId())
        assertEquals(listOf<Pair<String?, String>>("Kamal" to "Nimal"), asked)
        // No answer given (default): never wipes.
        assertEquals(SignInDecision.CANCELLED, account.beforeSignIn(user(b)))
        assertTrue(wipes.isEmpty())
        // Yes: wipes.
        assertEquals(SignInDecision.WIPED, account.beforeSignIn(user(b)) { _, _ -> true })
        assertEquals(listOf(WipeReason.DIFFERENT_ACCOUNT), wipes)
    }

    @Test fun escapeScreenSignOutsNeverWipe() = runBlocking {
        // Blocked / identity conflict / locked / required update / confirm phone all end in signOutKeepData.
        repeat(5) {
            store.saveOidcLogin(user(a))
            account.signOutKeepData()
        }
        assertTrue(wipes.isEmpty())
        assertEquals(a, store.lastUserId())
        assertEquals(SignInDecision.KEEP, account.beforeSignIn(user(a)) { _, _ -> error("same account: never asks") })
    }

    @Test fun signOutKeepDataNeverWipesAndKeepsTheOwner() = runBlocking {
        store.saveOidcLogin(user(a))
        account.signOutKeepData()
        assertNull(store.current())
        assertEquals(a, store.lastUserId())
        assertTrue(wipes.isEmpty())
    }

    /** Decision 050: plain "Log out" keeps everything; the same account resumes without asking or wiping. */
    @Test fun plainLogoutKeepsChatsAndTheOwner() = runBlocking {
        store.saveOidcLogin(user(a))
        val device = store.deviceId()
        account.logoutKeepChats()
        assertNull(store.current())
        assertEquals(a, store.lastUserId())
        assertEquals(device, store.deviceId())
        assertTrue(wipes.isEmpty())
        assertEquals(SignInDecision.KEEP, account.beforeSignIn(user(a)) { _, _ -> error("same account: never asks") })
        assertTrue(wipes.isEmpty())
    }

    /** Decision 050: after a plain logout a different account is asked first; only yes wipes. */
    @Test fun plainLogoutThenADifferentAccountAsksThenWipes() = runBlocking {
        store.saveOidcLogin(user(a))
        account.logoutKeepChats()
        assertEquals(SignInDecision.CANCELLED, account.beforeSignIn(user(b)) { _, _ -> false })
        assertTrue(wipes.isEmpty())
        assertEquals(SignInDecision.WIPED, account.beforeSignIn(user(b)) { _, _ -> true })
        assertEquals(listOf(WipeReason.DIFFERENT_ACCOUNT), wipes)
    }

    @Test fun logoutAndDeleteChatsWipesAndForgetsTheOwner() = runBlocking {
        store.saveOidcLogin(user(a))
        account.logoutAndDeleteChats()
        assertEquals(listOf(WipeReason.LOGOUT), wipes)
        assertNull(store.current())
        assertNull(store.lastUserId())
        // The next sign-in (any account) has nothing more to wipe.
        assertEquals(SignInDecision.KEEP, account.beforeSignIn(user(b)))
        assertEquals(1, wipes.size)
    }

    @Test fun switchServerWipesAndForgetsTheOwner() = runBlocking {
        store.saveLogin("t", user(a))
        account.switchServer("https://other.example")
        assertEquals(listOf(WipeReason.SWITCH_SERVER), wipes)
        assertNull(store.lastUserId())
        assertEquals("https://other.example", store.currentServerUrl())
    }

    @Test fun recoveryReplaysOnceThenNotAgain() = runBlocking {
        var cursor: String? = "ev-9"
        var messages = 5
        val reset = suspend { cursor = null }
        assertTrue("one-time replay on the fixed build", account.recoverHistoryIfNeeded({ messages }, { cursor }, reset))
        assertNull(cursor)
        cursor = "ev-10"
        assertFalse(account.recoverHistoryIfNeeded({ messages }, { cursor }, reset))
        // Lost data later (a cursor but no rows): replay once for that cursor, not on every start.
        messages = 0
        assertTrue(account.recoverHistoryIfNeeded({ messages }, { cursor }, reset))
        cursor = "ev-10"
        assertFalse(account.recoverHistoryIfNeeded({ messages }, { cursor }, reset))
        assertTrue(wipes.isEmpty())
    }

    @Test fun noCursorIsAlreadyAFullReplay() = runBlocking {
        assertFalse(account.recoverHistoryIfNeeded({ 0 }, { null }, { error("not needed") }))
        assertEquals(LocalAccount.HISTORY_REPLAY_VERSION, store.historyReplayVersion())
    }

    @Test fun accountIds() {
        assertTrue(AccountIds.confirmedDifferent(a, b))
        assertFalse(AccountIds.confirmedDifferent(a, a.uppercase()))
        assertFalse(AccountIds.confirmedDifferent(null, a))
        assertFalse(AccountIds.confirmedDifferent("kc-sub-123", a)) // never compare a Keycloak sub with a RisiMe id
    }
}
