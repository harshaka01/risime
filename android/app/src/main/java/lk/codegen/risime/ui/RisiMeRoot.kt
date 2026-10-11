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
import kotlinx.coroutines.launch
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
import lk.codegen.risime.ui.settings.RISI_SKILLS_ROUTE
import lk.codegen.risime.ui.settings.SettingsViewModel
import lk.codegen.risime.ui.tabs.officialInfoItems
import lk.codegen.risime.ui.tabs.tabMediaItems
import lk.codegen.risime.ui.tabs.chatLockItems


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
                    val lockMessage by authUi.lock.message.collectAsState()
                    val showPin by authUi.lock.showPin.collectAsState()
                    lk.codegen.risime.ui.lock.AppLockScreen(
                        onUnlock = authUi::unlockApp,
                        message = lockMessage,
                        onAutoPrompt = authUi::autoUnlockApp,
                        showPin = showPin,
                        onUsePin = authUi::unlockAppWithPin,
                    )
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(openChat) {
        openChat?.let { target ->
            c.openChatRequest.value = null
            nav.navigate("chat/${android.net.Uri.encode(target)}") { popUpTo("chats") }
            // Missed-call notification "Call back": call at once when the permissions are already granted, else the chat is open for the tap.
            c.callBackRequest.value?.let { (conv, video) ->
                c.callBackRequest.value = null
                fun granted(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (conv == target && c.calls.unsupportedReason() == null && granted(android.Manifest.permission.RECORD_AUDIO)) {
                    c.calls.placeCall(conv, video, camera = video && granted(android.Manifest.permission.CAMERA))
                    c.calls.openCallScreen()
                }
            }
        }
    }
    // Locked chats: leaving to the chat list, or the folder closing (background), re-locks everything under it.
    val lockedFolderOpen by c.lockedChats.folderOpen.collectAsState()
    LaunchedEffect(nav) {
        nav.currentBackStackEntryFlow.collect { e -> if (e.destination.route == "chats") c.lockedChats.closeFolder() }
    }
    LaunchedEffect(lockedFolderOpen) {
        if (!lockedFolderOpen && nav.currentBackStackEntry?.destination?.route.let { it == "locked" || it == "locked_settings" || it == "chat/{target}" }) {
            val under = runCatching { nav.getBackStackEntry("locked") }.isSuccess
            // Chat lock settings opened from the chat list's ⋮ (no folder under it) close the same way.
            if (under || nav.currentBackStackEntry?.destination?.route == "locked_settings") nav.popBackStack("chats", false)
        }
    }
    // §25.4/§26.5 Risi cards: a draft's [Use] opens its chat (composer filled), "Open Risi skills" opens Settings.
    LaunchedEffect(nav) {
        c.risiUi.nav.collect { req ->
            runCatching {
                when (req) {
                    is lk.codegen.risime.data.tabs.RisiUiBus.Nav.Chat -> nav.navigate("chat/${android.net.Uri.encode(req.conversationId)}")
                    is lk.codegen.risime.data.tabs.RisiUiBus.Nav.Skills -> nav.navigate(RISI_SKILLS_ROUTE + (req.skillId?.let { "?skill=${android.net.Uri.encode(it)}" } ?: "")) { launchSingleTop = true }
                    is lk.codegen.risime.data.tabs.RisiUiBus.Nav.Calendar -> nav.popBackStack("chats", false)
                    is lk.codegen.risime.data.tabs.RisiUiBus.Nav.Note -> nav.navigate("risi_note/${android.net.Uri.encode(req.noteId)}") { launchSingleTop = true }
                    is lk.codegen.risime.data.tabs.RisiUiBus.Nav.Notes -> nav.navigate("risi_notes") { launchSingleTop = true }
                }
            }
        }
    }
    NotificationPermissionPrompt(c)
    lk.codegen.risime.ui.settings.NotificationHealthAfterUpdate(c) { runCatching { nav.navigate("notif_health") { launchSingleTop = true } } }
    lk.codegen.risime.ui.history.HistoryPromptHost(c)
    // v1.34 §33.14 Export PDF from anywhere (⋮ on a card, the note screen, the Calendar, the `pdf` chip).
    lk.codegen.risime.ui.pdf.PdfExportHost(c)
    // §22.7: a fresh install with a server backup offers "Restore your chats" first.
    lk.codegen.risime.ui.backup.RestoreGateHost(c) {
    NavHost(nav, startDestination = "chats") {
        composable("chats") {
            ChatsScreen(
                viewModel { ChatsViewModel(c, meId) },
                viewModel(key = "friends") { FriendsViewModel(c) },
                calendar = viewModel(key = "risi_calendar") { lk.codegen.risime.ui.calendar.RisiCalendarViewModel(c, meId) },
                onOpen = {
                    // §25.2 the "Risi" entry before the Risi chat exists: create it first.
                    if (it == lk.codegen.risime.ui.chats.RISI_NEW_TARGET) nav.navigate("risi_open") { launchSingleTop = true }
                    else nav.navigate("chat/${android.net.Uri.encode(it)}")
                },
                onNewGroup = { nav.navigate("group_new") { launchSingleTop = true } },
                onSettings = { nav.navigate("settings") { launchSingleTop = true } },
                onSearch = { nav.navigate("search") { launchSingleTop = true } },
                onAddFriend = { nav.navigate("add_friend") { launchSingleTop = true } },
                onInvites = { nav.navigate("invites") { launchSingleTop = true } },
                onLockedFolder = { nav.navigate("locked") { launchSingleTop = true } },
                onChatLockSettings = { nav.navigate("locked_settings") { launchSingleTop = true } },
            )
        }
        // Locked chats: the folder is reachable only after the confirmation (folderOpen) and closes when
        // the user leaves it or the app goes to the background.
        composable("locked") {
            val open by c.lockedChats.folderOpen.collectAsState()
            if (open) {
                lk.codegen.risime.ui.chats.LockedChatsScreen(
                    viewModel(key = "locked") { ChatsViewModel(c, meId) },
                    onOpen = { nav.navigate("chat/${android.net.Uri.encode(it)}") },
                    onSettings = { nav.navigate("locked_settings") { launchSingleTop = true } },
                    onBack = { nav.popBackStack("chats", false) },
                )
            }
        }
        composable("locked_settings") {
            val open by c.lockedChats.folderOpen.collectAsState()
            if (open) lk.codegen.risime.ui.chats.LockedChatsSettingsScreen(c, onBack = { nav.popBackStack() })
        }
        // §25.2 the Risi chat's first open: POST /risi/chat (+ epoch 0), then the chat itself.
        composable("risi_open") {
            lk.codegen.risime.ui.tabs.RisiChatOpenScreen(
                open = { c.openRisiChat() },
                onReady = { conv -> nav.navigate("chat/${android.net.Uri.encode(conv)}") { popUpTo("chats") } },
                onBack = { nav.popBackStack() },
            )
        }
        // A conversation id (dm:/grp:) or a DM peer's user id (older notification intents, search).
        composable("chat/{target}") { entry ->
            val target = lk.codegen.risime.net.conversationFor(meId, entry.arguments?.getString("target") ?: return@composable)
            // §24: a chat is its Private (anchor) id; an Official conversation id opens its chat on the Official tab.
            val tabRows by c.chatTabs.rows.collectAsState()
            val conv = lk.codegen.risime.data.tabs.chatIdOf(target.lowercase(), tabRows).let { if (it.equals(target, true)) target else it }
            val openedOnOfficial = !conv.equals(target, true)
            val tabsOn by c.chatTabs.uiOn.collectAsState()
            val lockedIds by c.lockedChats.ids.collectAsState()
            val folderOpen by c.lockedChats.folderOpen.collectAsState()
            // Locked chats lock the whole chat (both tabs): by chat id.
            val isLocked = (if (tabRows == null) null else lockedIds?.contains(conv.lowercase())) ?: true
            val gate = lk.codegen.risime.ui.lock.rememberLockGate()
            // A locked chat opens only after the confirmation, whichever screen sent the user here
            // (the folder has already confirmed; a notification, call record or deep link has not).
            if (isLocked && !folderOpen) {
                lk.codegen.risime.ui.lock.LockedChatGate(
                    gate, onUnlock = { c.lockedChats.openFolder() }, onBack = { nav.popBackStack() },
                    loading = lockedIds == null || tabRows == null,
                )
                return@composable
            }
            lk.codegen.risime.ui.lock.LockGateDialog(gate)
            val headerLock = chatLockControl(c, nav, conv, isLocked, gate)
            // A locked chat is FLAG_SECURE while open (normal chats never are: an entire-screen share shows them).
            lk.codegen.risime.ui.lock.SecureWindow(lk.codegen.risime.ui.lock.SecureScreen.LOCKED_CHAT, on = isLocked)
            // §25.2 the Risi chat (MLS says chat_kind "risi"): one conversation, Official styling, no tabs or
            // toggle; its composer only asks Risi. Not shown at all on a device without risi_tools.
            if (lk.codegen.risime.data.tabs.isRisiChat(conv, tabRows)) {
                val risiOn by c.risiTools.on.collectAsState()
                if (!risiOn || !tabsOn) {
                    lk.codegen.risime.ui.tabs.RisiChatUnavailable(onBack = { nav.popBackStack() })
                    return@composable
                }
                val risiVm = viewModel(key = conv) { lk.codegen.risime.ui.group.GroupChatViewModel(c, meId, conv) }
                lk.codegen.risime.ui.group.GroupChatScreen(
                    risiVm, meId,
                    onBack = { nav.popBackStack() },
                    onInfo = {},
                    tabBar = { lk.codegen.risime.ui.tabs.RisiChatStrip() },
                    titleOverride = lk.codegen.risime.data.tabs.RISI_CHAT_NAME,
                    risi = risiVm.risi,
                    risiChat = true,
                )
                return@composable
            }
            val groupInfo = { nav.navigate("group_info/${android.net.Uri.encode(conv)}") { launchSingleTop = true }; Unit }
            val chatInfo = { nav.navigate("chat_info/${android.net.Uri.encode(conv)}") { launchSingleTop = true }; Unit }
            // §24.9: with tabs off (server switch or capability) this is exactly the v1.23 screen.
            lk.codegen.risime.ui.tabs.TabbedChat(
                onInfo = if (lk.codegen.risime.net.isGroupConversation(conv)) groupInfo else chatInfo,
                tabsOn = tabsOn,
                vm = {
                    viewModel(key = "tabs:$conv") {
                        lk.codegen.risime.ui.tabs.ChatTabsViewModel(c, meId, conv, if (openedOnOfficial) lk.codegen.risime.data.tabs.Tab.OFFICIAL else null)
                    }
                },
                onBack = { nav.popBackStack() },
                privateScreen = { tabBar ->
                    if (lk.codegen.risime.net.isGroupConversation(conv)) {
                        lk.codegen.risime.ui.group.GroupChatScreen(
                            viewModel(key = conv) { lk.codegen.risime.ui.group.GroupChatViewModel(c, meId, conv) }, meId,
                            onBack = { nav.popBackStack() }, onInfo = groupInfo, tabBar = tabBar, lock = headerLock,
                        )
                    } else {
                        lk.codegen.risime.net.dmPeer(conv, meId)?.let { peer ->
                            ChatScreen(
                                viewModel(key = conv) { ChatViewModel(c, meId, peer) }, onBack = { nav.popBackStack() }, tabBar = tabBar,
                                // Chat info (WhatsApp): Lock chat, media; with tabs on also the Official switch (§24.4).
                                onInfo = chatInfo, lock = headerLock,
                            )
                        }
                    }
                },
                onSearch = { searchConv, tab -> nav.navigate("chat_search/${android.net.Uri.encode(searchConv)}/${tab.wire}") { launchSingleTop = true } },
                officialScreen = { official, readOnly, tabBar ->
                    val dmChat = !lk.codegen.risime.net.isGroupConversation(conv)
                    val peerName = if (dmChat) viewModel<lk.codegen.risime.ui.tabs.ChatTabsViewModel>(key = "tabs:$conv").title.collectAsState().value else null
                    val officialVm = viewModel(key = official) { lk.codegen.risime.ui.group.GroupChatViewModel(c, meId, official) }
                    lk.codegen.risime.ui.group.GroupChatScreen(
                        officialVm, meId,
                        risi = officialVm.risi, // §24.9: only the Official tab has Risi (cards, @Risi chip, Summarise/Report)
                        onBack = { nav.popBackStack() },
                        // Chat info is the chat's (the Private group's); a 1:1 Official has no member management (§24.1 dm_chat).
                        onInfo = if (dmChat) chatInfo else groupInfo,
                        tabBar = tabBar,
                        lock = headerLock,
                        // A 1:1 Official: "Kumu · Risi", never "3 members" (its members are the two of you and Risi).
                        titleOverride = peerName,
                        titleSuffix = if (dmChat) lk.codegen.risime.ui.tabs.OFFICIAL_DM_TITLE_SUFFIX else null,
                        subtitleOverride = if (dmChat) lk.codegen.risime.ui.tabs.OFFICIAL_DM_SUBTITLE else null,
                        readOnlyReason = if (readOnly) lk.codegen.risime.ui.tabs.OFFICIAL_HISTORY_LABEL else null,
                        // The composer says "Message"; "Risi is listening" stays in the strip under the tabs.
                        composerHint = lk.codegen.risime.ui.tabs.OFFICIAL_COMPOSER_HINT,
                        // v1.33 §24.5 (NEXT-PHASE D1): a 1:1's Official calls are the §16/§19 peer-to-peer
                        // calls on its dm: (the peer's name on the call screen), never §20 on the grp:.
                        dmCallButtons = if (dmChat) ({ toast ->
                            val dmCalls = viewModel(key = "dmcalls:$conv") { lk.codegen.risime.ui.chat.DmCallsViewModel(c, conv) }
                            lk.codegen.risime.ui.chat.DmCallButtons(dmCalls.calls, peerName ?: "", toast)
                        }) else null,
                    )
                },
            )
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
            val tabsOn by c.chatTabs.uiOn.collectAsState()
            // §24.4/§24.9: with tabs on, the Official switch, its history, Official members and both tabs' media.
            val tabsVm = if (tabsOn) viewModel(key = "chatinfo:$conv") { lk.codegen.risime.ui.tabs.ChatInfoTabsViewModel(c, meId, conv) } else null
            val official = tabsVm?.official?.ui?.collectAsState()?.value
            val media = tabsVm?.media?.collectAsState()?.value
            val lockGate = lk.codegen.risime.ui.lock.rememberLockGate()
            lk.codegen.risime.ui.lock.LockGateDialog(lockGate)
            val lockedIds by c.lockedChats.ids.collectAsState()
            val lock = chatLockControl(c, nav, conv, lockedIds?.contains(c.chatTabs.chatId(conv).lowercase()) == true, lockGate)
            // A locked group's info (its media) is part of the open locked chat: FLAG_SECURE too.
            lk.codegen.risime.ui.lock.SecureWindow(lk.codegen.risime.ui.lock.SecureScreen.LOCKED_CHAT, on = lock.locked)
            lk.codegen.risime.ui.group.GroupInfoScreen(
                viewModel(key = "info:$conv") { lk.codegen.risime.ui.group.GroupInfoViewModel(c, meId, conv) },
                onBack = { nav.popBackStack() },
                tabsItems = {
                    // §24.4 the Official section (tabs on), then WhatsApp's "Lock chat" (always), then both tabs' media.
                    if (tabsVm != null && official != null) {
                        officialInfoItems(
                            official, tabsVm.official::request,
                            onHistory = { official.officialConversation?.let { nav.navigate("chat/${android.net.Uri.encode(it)}") { popUpTo("chats") } } },
                            onDismissError = tabsVm.official::dismissError,
                        )
                    }
                    chatLockItems(lock)
                    if (tabsVm != null && official != null && media != null) {
                        tabMediaItems(media, showOfficial = official.officialConversation != null) { m ->
                            lk.codegen.risime.ui.tabs.MediaThumb(c, m) { nav.navigate("chat/${android.net.Uri.encode(m.conversationId)}") { popUpTo("chats") } }
                        }
                    }
                },
            )
            if (tabsVm != null && official != null) lk.codegen.risime.ui.tabs.OfficialOffDialog(official, tabsVm.official::confirmOff, tabsVm.official::cancelOff)
        }
        // §24.9 search inside one tab of a chat (that conversation only).
        composable("chat_search/{conv}/{tab}") { entry ->
            val conv = entry.arguments?.getString("conv") ?: return@composable
            val tab = lk.codegen.risime.data.tabs.Tab.of(entry.arguments?.getString("tab")) ?: lk.codegen.risime.data.tabs.Tab.PRIVATE
            val members by c.db.groups().observeAllMembers().collectAsState(emptyList())
            val contacts by c.contacts.contacts.collectAsState(emptyList())
            lk.codegen.risime.ui.tabs.ChatSearchScreen(
                viewModel(key = "search:$conv") { lk.codegen.risime.ui.tabs.ChatSearchViewModel(conv, tab) { id, p, n -> c.db.messages().searchIn(id, p, n) } },
                senderName = { m ->
                    contacts.firstOrNull { it.userId.equals(m.from, true) }?.displayName
                        ?: members.firstOrNull { it.userId.equals(m.from, true) }?.displayName ?: "Someone"
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable("chat_info/{chat}") { entry ->
            val chat = entry.arguments?.getString("chat") ?: return@composable
            val vm = viewModel(key = "chatinfo:$chat") { lk.codegen.risime.ui.tabs.ChatInfoTabsViewModel(c, meId, chat) }
            val official by vm.official.ui.collectAsState()
            val media by vm.media.collectAsState()
            val title by vm.title.collectAsState()
            val tabsOn by vm.tabsOn.collectAsState()
            val e2ee by vm.e2ee.collectAsState()
            val lockGate = lk.codegen.risime.ui.lock.rememberLockGate()
            lk.codegen.risime.ui.lock.LockGateDialog(lockGate)
            val lockedIds by c.lockedChats.ids.collectAsState()
            val lock = chatLockControl(c, nav, chat, lockedIds?.contains(chat.lowercase()) == true, lockGate)
            // A locked chat's info (its media) is part of the open locked chat: FLAG_SECURE too.
            lk.codegen.risime.ui.lock.SecureWindow(lk.codegen.risime.ui.lock.SecureScreen.LOCKED_CHAT, on = lock.locked)
            lk.codegen.risime.ui.tabs.DmChatInfoContent(
                title, lk.codegen.risime.net.dmPeer(chat, meId),
                encrypted = e2ee is lk.codegen.risime.data.mls.E2eeState.Encrypted,
                // Decision 048: the real reason (or "Checking…" only until the first bounded check answers).
                notEncryptedText = lk.codegen.risime.data.mls.e2eeStripText(e2ee, nameOf = { title.ifBlank { "Your contact" } }, isMe = { it.equals(meId, true) }),
                official = if (tabsOn) official else null, media = media,
                lock = lock,
                onBack = { nav.popBackStack() },
                onToggle = vm.official::request, onConfirmOff = vm.official::confirmOff, onCancelOff = vm.official::cancelOff,
                onHistory = { official.officialConversation?.let { nav.navigate("chat/${android.net.Uri.encode(it)}") { popUpTo("chats") } } },
                onDismissError = vm.official::dismissError,
                thumb = { m -> lk.codegen.risime.ui.tabs.MediaThumb(c, m) { nav.navigate("chat/${android.net.Uri.encode(m.conversationId)}") { popUpTo("chats") } } },
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
                viewModel { SearchViewModel(c, meId) },
                onOpen = { peer -> nav.navigate("chat/${android.net.Uri.encode(peer)}") { popUpTo("chats") } },
                onBack = { nav.popBackStack() },
                onLockedFolder = { nav.navigate("locked") { popUpTo("chats") } },
            )
        }
        composable("settings") {
            SettingsScreen(
                viewModel { SettingsViewModel(AppSettingsBackend(c)) }, onBack = { nav.popBackStack() },
                onBackups = { nav.navigate("backups") { launchSingleTop = true } },
                onNotificationHealth = { nav.navigate("notif_health") { launchSingleTop = true } },
                onRisiKnows = { nav.navigate("risi_facts") { launchSingleTop = true } },
                onMyPromises = { nav.navigate("risi_promises") { launchSingleTop = true } },
                onRisiSkills = { nav.navigate(RISI_SKILLS_ROUTE) { launchSingleTop = true } },
                onRisiNotes = { nav.navigate("risi_notes") { launchSingleTop = true } },
            )
        }
        composable("$RISI_SKILLS_ROUTE?skill={skill}", arguments = listOf(androidx.navigation.navArgument("skill") { nullable = true; defaultValue = null })) { entry ->
            lk.codegen.risime.ui.settings.RisiSkillsRoute(c, entry.arguments?.getString("skill"), onBack = { nav.popBackStack() })
        }
        composable("risi_facts") {
            lk.codegen.risime.ui.settings.RisiFactsScreen(viewModel { lk.codegen.risime.ui.settings.RisiFactsViewModel(c.risiRest) }.model, onBack = { nav.popBackStack() })
        }
        composable("risi_promises") {
            val contacts by c.contacts.contacts.collectAsState(emptyList())
            lk.codegen.risime.ui.settings.RisiPromisesScreen(
                viewModel {
                    // Server item 9: a row opens its source chat at the source message (when that chat is on this phone).
                    lk.codegen.risime.ui.settings.RisiPromisesViewModel(c.risiRest, object : lk.codegen.risime.ui.settings.RisiPromiseOpener {
                        override suspend fun has(conversationId: String): Boolean = c.db.groups().get(conversationId) != null
                        override fun open(conversationId: String, messageId: String?) =
                            c.risiUi.openChat(conversationId, messageId?.let { lk.codegen.risime.data.tabs.RisiUiBus.Focus(messageId = it) })
                    }, notes = if (!c.risiNotesOn()) null else { scope ->
                        // v1.29 §30.5: [Done]/[Reopen] = the note's tick (in the Risi chat); "From note: …" opens the note.
                        val env = lk.codegen.risime.ui.notes.NotesEnv(c, meId, scope)
                        object : lk.codegen.risime.ui.settings.RisiPromiseNotes {
                            override suspend fun title(noteId: String): String? = env.noteTitle(noteId)
                            override fun open(noteId: String) = c.risiUi.openNote(noteId)
                            override suspend fun act(itemId: String, action: String): Boolean = c.sendItemAction(itemId, action)
                        }
                    })
                }.model, meId,
                nameOf = { id -> contacts.firstOrNull { it.userId.equals(id, true) }?.displayName ?: "Someone" },
                onBack = { nav.popBackStack() },
            )
        }
        // v1.29 §30.6 Risi Notes: the list and one note.
        composable("risi_notes") {
            lk.codegen.risime.ui.notes.NotesListRoute(
                viewModel { lk.codegen.risime.ui.notes.RisiNotesListViewModel(c, meId) },
                onOpen = { id -> nav.navigate("risi_note/${android.net.Uri.encode(id)}") { launchSingleTop = true } },
                onBack = { nav.popBackStack() },
            )
        }
        composable("risi_note/{id}") { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            lk.codegen.risime.ui.notes.NoteRoute(viewModel(key = "note:$id") { lk.codegen.risime.ui.notes.RisiNoteViewModel(c, meId, id) }, onBack = { nav.popBackStack() })
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

/**
 * WhatsApp "Lock chat" / "Unlock chat" for chat [chat] (its Private id; both tabs lock together), from
 * chat info: the fingerprint/PIN confirmation first ([gate]); locking returns to the
 * chat list, where the chat has left for the Locked chats folder.
 */
private fun chatLockControl(
    c: AppContainer,
    nav: androidx.navigation.NavController,
    chat: String,
    locked: Boolean,
    gate: lk.codegen.risime.ui.lock.LockGate,
) = lk.codegen.risime.ui.lock.ChatLockControl(locked) {
    if (locked) {
        gate.run(lk.codegen.risime.ui.lock.UNLOCK_CHAT_LABEL) { c.scope.launch { c.lockedChats.unlock(chat) } }
    } else {
        gate.run(lk.codegen.risime.ui.lock.LOCK_CHAT_LABEL) {
            nav.popBackStack("chats", false)
            c.scope.launch { c.lockedChats.lock(chat) }
        }
    }
}
