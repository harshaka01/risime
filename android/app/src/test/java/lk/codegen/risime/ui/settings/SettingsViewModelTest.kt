package lk.codegen.risime.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import lk.codegen.risime.data.Session
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.User
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val me = User("u1", "+94770000001", "Harsha", "CodeGen")

    private class FakeBackend(initial: Session) : SettingsBackend {
        override val session = MutableStateFlow<Session?>(initial)
        var nameReply: ApiResult<User>? = null
        val names = mutableListOf<String>()
        val switched = mutableListOf<String>()
        var loggedOut = 0

        override suspend fun updateDisplayName(name: String): ApiResult<User> {
            names += name
            val r = nameReply ?: ApiResult.Ok(session.value!!.user.copy(displayName = name))
            if (r is ApiResult.Ok) session.value = session.value!!.copy(user = r.value)
            return r
        }

        override suspend fun switchServer(url: String) {
            switched += url
            session.value = null
        }

        override suspend fun logout() {
            loggedOut++
            session.value = null
        }
    }

    private lateinit var backend: FakeBackend
    private lateinit var vm: SettingsViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        backend = FakeBackend(Session("http://10.0.2.2:4400", "tok", me))
        vm = SettingsViewModel(backend)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun showsProfileAndServer() {
        val s = vm.state.value
        assertEquals(me, s.user)
        assertEquals("Harsha", s.nameDraft)
        assertEquals("http://10.0.2.2:4400", s.serverDraft)
    }

    @Test fun invalidServerUrlShowsErrorAndDoesNotAsk() {
        vm.onServerDraft("10.0.2.2:4400")
        vm.saveServer()
        assertNotNull(vm.state.value.serverError)
        assertNull(vm.state.value.confirmServer)
        assertTrue(backend.switched.isEmpty())
    }

    @Test fun sameServerIsANoOp() {
        vm.onServerDraft("http://10.0.2.2:4400/")
        vm.saveServer()
        assertNull(vm.state.value.confirmServer)
        assertNull(vm.state.value.serverError)
    }

    @Test fun newServerNeedsConfirmationThenLogsOutAndSwitches() {
        vm.onServerDraft(" https://spark2.example.ts.net/ ")
        vm.saveServer()
        assertEquals("https://spark2.example.ts.net", vm.state.value.confirmServer)
        assertTrue(backend.switched.isEmpty())

        vm.cancelServerChange()
        assertNull(vm.state.value.confirmServer)
        assertTrue(backend.switched.isEmpty())

        vm.saveServer()
        vm.confirmServerChange()
        assertEquals(listOf("https://spark2.example.ts.net"), backend.switched)
    }

    @Test fun savesDisplayName() {
        vm.onNameDraft("  Harsha K ")
        vm.saveName()
        assertEquals(listOf("Harsha K"), backend.names)
        assertEquals("Harsha K", vm.state.value.user?.displayName)
        assertTrue(vm.state.value.nameSaved)
    }

    @Test fun displayNameValidationAndServerErrors() {
        vm.onNameDraft("   ")
        vm.saveName()
        assertNotNull(vm.state.value.nameError)
        assertTrue(backend.names.isEmpty())

        vm.onNameDraft("Harsha") // unchanged: no request
        vm.saveName()
        assertTrue(backend.names.isEmpty())

        backend.nameReply = ApiResult.Error(422, "invalid_display_name", "")
        vm.onNameDraft("New")
        vm.saveName()
        assertEquals("Display name must be 1–64 characters", vm.state.value.nameError)

        backend.nameReply = ApiResult.NetworkError(IOException("down"))
        vm.saveName()
        assertEquals("Can't reach the server", vm.state.value.nameError)
        assertEquals("Harsha", vm.state.value.user?.displayName)
    }

    @Test fun logoutAsksFirst() {
        vm.askLogout()
        assertTrue(vm.state.value.confirmLogout)
        assertEquals(0, backend.loggedOut)
        vm.logout()
        assertEquals(1, backend.loggedOut)
    }
}
