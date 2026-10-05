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
import lk.codegen.risime.ui.chat.ChatScreen
import lk.codegen.risime.ui.chat.ChatViewModel
import lk.codegen.risime.ui.chats.ChatsScreen
import lk.codegen.risime.ui.chats.ChatsViewModel
import lk.codegen.risime.ui.common.DevEncryptionBanner
import lk.codegen.risime.ui.login.LoginFlow
import lk.codegen.risime.ui.login.LoginViewModel

private sealed interface Gate {
    data object Loading : Gate
    data object LoggedOut : Gate
    data class LoggedIn(val meId: String) : Gate
}

/** App root: the dev encryption banner sits above every screen, always. */
@Composable
fun RisiMeRoot(c: AppContainer) {
    val session by c.sessionStore.session.collectAsState(initial = Unit)
    val gate = when (val s = session) {
        Unit -> Gate.Loading
        null -> Gate.LoggedOut
        is lk.codegen.risime.data.Session -> Gate.LoggedIn(s.user.id)
        else -> Gate.Loading
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        DevEncryptionBanner()
        Box(Modifier.weight(1f)) {
            when (gate) {
                Gate.Loading -> Unit
                Gate.LoggedOut -> LoginFlow(viewModel(key = "login") { LoginViewModel(c) })
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
            ChatsScreen(viewModel { ChatsViewModel(c, meId) }, onOpen = { nav.navigate("chat/$it") })
        }
        composable("chat/{peer}") { entry ->
            val peer = entry.arguments?.getString("peer") ?: return@composable
            ChatScreen(viewModel { ChatViewModel(c, meId, peer) }, onBack = { nav.popBackStack() })
        }
    }
}
