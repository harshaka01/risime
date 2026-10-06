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
import lk.codegen.risime.data.auth.AppGate
import lk.codegen.risime.data.auth.appGate
import lk.codegen.risime.ui.friends.AddFriendScreen
import lk.codegen.risime.ui.friends.FriendsViewModel
import lk.codegen.risime.ui.friends.InvitesScreen
import lk.codegen.risime.ui.phone.AppPhoneBackend
import lk.codegen.risime.ui.phone.PhoneVerifyScreen
import lk.codegen.risime.ui.phone.PhoneVerifyViewModel
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


/** App root: the dev encryption banner sits above every screen, always. */
@Composable
fun RisiMeRoot(c: AppContainer, authUi: AuthUi) {
    val session by c.sessionStore.session.collectAsState(initial = Unit)
    val unlocked by c.auth.unlocked.collectAsState()
    val blocked by c.blocked.collectAsState()
    val notice by c.signInNotice.collectAsState()
    val update by c.updater.state.collectAsState()
    val current = session as? Session
    val required = update.blocking()
    val gate = appGate(session != Unit, current, unlocked, blocked, required != null)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        DevEncryptionBanner()
        if (required == null) UpdateBar(update, c)
        Box(Modifier.weight(1f)) {
            // Order: update required → blocked → locked → confirm phone → chats (contract §7).
            when (gate) {
                AppGate.UPDATE_REQUIRED -> RequiredUpdateScreen(update, required!!, c)
                AppGate.BLOCKED -> BlockedScreen(blocked!!, c, authUi)
                AppGate.LOADING -> Unit
                AppGate.SIGNED_OUT -> LoginFlow(viewModel(key = "login") { LoginViewModel(c) }, authUi, notice)
                AppGate.LOCKED -> LockedScreen(authUi)
                AppGate.CONFIRM_PHONE -> PhoneVerifyScreen(
                    viewModel(key = "phone:${current!!.user.id}") { PhoneVerifyViewModel(AppPhoneBackend(c), current.user.phone) },
                )
                AppGate.CHATS -> MainNav(c, current!!.user.id)
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
                viewModel(key = "friends") { FriendsViewModel(c) },
                onOpen = { nav.navigate("chat/$it") },
                onSettings = { nav.navigate("settings") { launchSingleTop = true } },
                onSearch = { nav.navigate("search") { launchSingleTop = true } },
                onAddFriend = { nav.navigate("add_friend") { launchSingleTop = true } },
                onInvites = { nav.navigate("invites") { launchSingleTop = true } },
            )
        }
        composable("chat/{peer}") { entry ->
            val peer = entry.arguments?.getString("peer") ?: return@composable
            ChatScreen(viewModel { ChatViewModel(c, meId, peer) }, onBack = { nav.popBackStack() })
        }
        composable("add_friend") {
            AddFriendScreen(viewModel { FriendsViewModel(c) }, onBack = { nav.popBackStack() })
        }
        composable("invites") {
            InvitesScreen(viewModel { FriendsViewModel(c) }, onBack = { nav.popBackStack() })
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
