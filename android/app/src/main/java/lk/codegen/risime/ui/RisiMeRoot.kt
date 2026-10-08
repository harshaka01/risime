package lk.codegen.risime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
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
import androidx.compose.runtime.LaunchedEffect
import lk.codegen.risime.ui.common.NotificationPermissionPrompt
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
import lk.codegen.risime.ui.auth.MigrationScreen
import lk.codegen.risime.ui.auth.RequiredUpdateScreen
import lk.codegen.risime.ui.auth.UpdateBar
import lk.codegen.risime.update.blocking
import lk.codegen.risime.ui.chat.ChatScreen
import lk.codegen.risime.ui.chat.ChatViewModel
import lk.codegen.risime.ui.chats.ChatsScreen
import lk.codegen.risime.ui.chats.ChatsViewModel
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
    val oidc by c.auth.state.collectAsState()
    val appLocked by c.appLock.locked.collectAsState()
    val blocked by c.blocked.collectAsState()
    val notice by c.signInNotice.collectAsState()
    val update by c.updater.state.collectAsState()
    val current = session as? Session
    val required = update.blocking()
    val gate = appGate(session != Unit, current, oidc, blocked, required != null, appLocked)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        if (required == null) UpdateBar(update, c)
        if (gate == AppGate.CHATS) ReturnToCallBar(c)
        Box(Modifier.weight(1f)) {
            // Order: update required → blocked → signed out → migration → app lock → confirm phone → chats (contract §7, decision 064).
            when (gate) {
                AppGate.UPDATE_REQUIRED -> RequiredUpdateScreen(update, required!!, c)
                AppGate.BLOCKED -> if (blocked!!.kind == lk.codegen.risime.data.auth.BlockKind.SIGNUP_REQUIRED) {
                    lk.codegen.risime.ui.auth.SignupScreen(c, authUi)
                } else {
                    BlockedScreen(blocked!!, c, authUi)
                }
                AppGate.LOADING -> lk.codegen.risime.ui.auth.VaultStuckGate(c)
                AppGate.SIGNED_OUT -> LoginFlow(viewModel(key = "login") { LoginViewModel(c) }, authUi, notice)
                AppGate.MIGRATE -> MigrationScreen(authUi)
                AppGate.APP_LOCKED -> {
                    val lockMessage by authUi.notice.collectAsState()
                    lk.codegen.risime.ui.lock.AppLockScreen(onUnlock = authUi::unlockApp, message = lockMessage)
                }
                AppGate.CONFIRM_PHONE -> PhoneVerifyScreen(
                    viewModel(key = "phone:${current!!.user.id}") { PhoneVerifyViewModel(AppPhoneBackend(c), current.user.phone) },
                )
                AppGate.CHATS -> MainNav(c, current!!.user.id)
            }
        }
    }
    val switch by c.accountSwitch.collectAsState()
    switch?.let { q ->
        lk.codegen.risime.ui.common.AccountSwitchDialog(
            q.previousName, q.newName,
            onContinue = { q.answer.complete(true) },
            onCancel = { q.answer.complete(false) },
        )
    }
}

/**
 * A call is going on while the chats are on screen (the user pressed Home or Back on the call
 * screen, or reopened the app): one tap goes back to it. The call itself lives in CallManager and
 * the foreground service, never in an Activity.
 */
@Composable
private fun ReturnToCallBar(c: AppContainer) {
    val call by c.calls.state.collectAsState()
    val s = call?.takeIf { it.phase != lk.codegen.risime.calls.CallPhase.ENDED } ?: return
    val text = if (s.phase == lk.codegen.risime.calls.CallPhase.RINGING_IN) "Incoming call — tap to answer" else "Call in progress — tap to return"
    androidx.compose.material3.Surface(
        color = androidx.compose.ui.graphics.Color(0xFF1B8A3E),
        modifier = Modifier.fillMaxWidth().clickable { c.calls.openCallScreen() },
    ) {
        androidx.compose.material3.Text(
            text, color = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun MainNav(c: AppContainer, meId: String) {
    val nav = rememberNavController()
    // Notification tap → that chat (once signed in, unlocked and verified).
    val openChat by c.openChatRequest.collectAsState()
    LaunchedEffect(openChat) {
        openChat?.let { target ->
            c.openChatRequest.value = null
            nav.navigate("chat/${android.net.Uri.encode(target)}") { popUpTo("chats") }
        }
    }
    NotificationPermissionPrompt(c)
    lk.codegen.risime.ui.settings.NotificationHealthAfterUpdate(c) { runCatching { nav.navigate("notif_health") { launchSingleTop = true } } }
    lk.codegen.risime.ui.history.HistoryPromptHost(c)
    // §22.7: a fresh install with a server backup offers "Restore your chats" first.
    lk.codegen.risime.ui.backup.RestoreGateHost(c) {
    NavHost(nav, startDestination = "chats") {
        composable("chats") {
            ChatsScreen(
                viewModel { ChatsViewModel(c, meId) },
                viewModel(key = "friends") { FriendsViewModel(c) },
                onOpen = { nav.navigate("chat/${android.net.Uri.encode(it)}") },
                onNewGroup = { nav.navigate("group_new") { launchSingleTop = true } },
                onSettings = { nav.navigate("settings") { launchSingleTop = true } },
                onSearch = { nav.navigate("search") { launchSingleTop = true } },
                onAddFriend = { nav.navigate("add_friend") { launchSingleTop = true } },
                onInvites = { nav.navigate("invites") { launchSingleTop = true } },
            )
        }
        // A conversation id (dm:/grp:) or a DM peer's user id (older notification intents, search).
        composable("chat/{target}") { entry ->
            val conv = lk.codegen.risime.net.conversationFor(meId, entry.arguments?.getString("target") ?: return@composable)
            if (lk.codegen.risime.net.isGroupConversation(conv)) {
                lk.codegen.risime.ui.group.GroupChatScreen(
                    viewModel(key = conv) { lk.codegen.risime.ui.group.GroupChatViewModel(c, meId, conv) }, meId,
                    onBack = { nav.popBackStack() },
                    onInfo = { nav.navigate("group_info/${android.net.Uri.encode(conv)}") { launchSingleTop = true } },
                )
                return@composable
            }
            val peer = lk.codegen.risime.net.dmPeer(conv, meId) ?: return@composable
            ChatScreen(viewModel(key = conv) { ChatViewModel(c, meId, peer) }, onBack = { nav.popBackStack() })
        }
        composable("group_new") {
            lk.codegen.risime.ui.group.CreateGroupScreen(
                viewModel { lk.codegen.risime.ui.group.CreateGroupViewModel(c) },
                onCreated = { conv -> nav.navigate("chat/${android.net.Uri.encode(conv)}") { popUpTo("chats") } },
                onBack = { nav.popBackStack() },
            )
        }
        composable("group_info/{conv}") { entry ->
            val conv = entry.arguments?.getString("conv") ?: return@composable
            lk.codegen.risime.ui.group.GroupInfoScreen(
                viewModel(key = "info:$conv") { lk.codegen.risime.ui.group.GroupInfoViewModel(c, meId, conv) },
                onBack = { nav.popBackStack() },
            )
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
                onOpen = { peer -> nav.navigate("chat/${android.net.Uri.encode(peer)}") { popUpTo("chats") } },
                onBack = { nav.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(
                viewModel { SettingsViewModel(AppSettingsBackend(c)) }, onBack = { nav.popBackStack() },
                onBackups = { nav.navigate("backups") { launchSingleTop = true } },
                onNotificationHealth = { nav.navigate("notif_health") { launchSingleTop = true } },
            )
        }
        composable("notif_health") {
            lk.codegen.risime.ui.settings.NotificationHealthScreen(c, onBack = { nav.popBackStack() })
        }
        composable("backups") {
            lk.codegen.risime.ui.backup.BackupsScreen(c, onBack = { nav.popBackStack() })
        }
    }
    }
}
