package lk.codegen.risime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.AuthKind
import lk.codegen.risime.data.Session
import lk.codegen.risime.ui.auth.AuthUi
import lk.codegen.risime.ui.auth.BlockedScreen
import lk.codegen.risime.ui.auth.LockedScreen
import lk.codegen.risime.ui.auth.RequiredUpdateScreen
import lk.codegen.risime.ui.auth.UpdateBar
import lk.codegen.risime.update.blocking
import lk.codegen.risime.ui.chat.ChatScreen
import lk.codegen.risime.ui.chat.ChatViewModel
import lk.codegen.risime.ui.chats.ChatsScreen
import lk.codegen.risime.ui.chats.ChatsViewModel
import lk.codegen.risime.ui.common.DevEncryptionBanner
import lk.codegen.risime.ui.login.LoginFlow
import lk.codegen.risime.ui.login.LoginViewModel
import lk.codegen.risime.ui.search.SearchScreen
import lk.codegen.risime.ui.search.SearchViewModel
import lk.codegen.risime.ui.settings.AppSettingsBackend
import lk.codegen.risime.ui.settings.SettingsScreen
import lk.codegen.risime.ui.settings.SettingsViewModel

private sealed interface Gate {
    data object Loading : Gate
    data object LoggedOut : Gate
    data object Locked : Gate
    data class Blocked(val b: lk.codegen.risime.data.auth.Blocked) : Gate
    data class LoggedIn(val meId: String) : Gate
}

/** App root: the dev encryption banner sits above every screen, always. */
@Composable
fun RisiMeRoot(c: AppContainer, authUi: AuthUi) {
    val session by c.sessionStore.session.collectAsState(initial = Unit)
    val unlocked by c.auth.unlocked.collectAsState()
    val blocked by c.blocked.collectAsState()
    val notice by c.signInNotice.collectAsState()
    val update by c.updater.state.collectAsState()
    val b = blocked
    val gate = if (b != null) Gate.Blocked(b) else when (val s = session) {
        Unit -> Gate.Loading
        null -> Gate.LoggedOut
        is Session -> if (s.kind == AuthKind.OIDC && !unlocked) Gate.Locked else Gate.LoggedIn(s.user.id)
        else -> Gate.Loading
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        DevEncryptionBanner()
        val required = update.blocking()
        if (required == null) UpdateBar(update, c)
        Box(Modifier.weight(1f)) {
            if (required != null) {
                RequiredUpdateScreen(update, required, c)
                return@Box
            }
            when (gate) {
                Gate.Loading -> Unit
                Gate.LoggedOut -> LoginFlow(viewModel(key = "login") { LoginViewModel(c) }, authUi, notice)
                Gate.Locked -> LockedScreen(authUi)
                is Gate.Blocked -> BlockedScreen(gate.b, c, authUi)
                is Gate.LoggedIn -> MainNav(c, gate.meId)
            }
        }
    }
}

@Composable
private fun MainNav(c: AppContainer, meId: String) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "chats") {
        composable("chats") {
            ChatsScreen(
                viewModel { ChatsViewModel(c, meId) },
                onOpen = { nav.navigate("chat/$it") },
                onSettings = { nav.navigate("settings") { launchSingleTop = true } },
                onSearch = { nav.navigate("search") { launchSingleTop = true } },
            )
        }
        composable("chat/{peer}") { entry ->
            val peer = entry.arguments?.getString("peer") ?: return@composable
            ChatScreen(viewModel { ChatViewModel(c, meId, peer) }, onBack = { nav.popBackStack() })
        }
        composable("search") {
            SearchScreen(
                viewModel { SearchViewModel(c) },
                onOpen = { peer -> nav.navigate("chat/$peer") { popUpTo("chats") } },
                onBack = { nav.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(viewModel { SettingsViewModel(AppSettingsBackend(c)) }, onBack = { nav.popBackStack() })
        }
    }
}
