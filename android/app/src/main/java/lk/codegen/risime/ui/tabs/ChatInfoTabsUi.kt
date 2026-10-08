package lk.codegen.risime.ui.tabs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.officialConversationOf
import lk.codegen.risime.net.GroupMember
import lk.codegen.risime.ui.common.InitialsAvatar
import lk.codegen.risime.ui.common.RisiTopBar
import lk.codegen.risime.ui.common.SectionHeader
import lk.codegen.risime.ui.group.ConfirmDialog
import lk.codegen.risime.ui.theme.Sizes
import lk.codegen.risime.ui.theme.Spacing

/** How many photos each tab's media section shows. */
private const val MEDIA_LIMIT = 60

/** Chat info's two-tab part in the app: the Official switch over REST, both tabs' media from Room. */
class ChatInfoTabsViewModel(c: AppContainer, meId: String, val chatId: String) : ViewModel() {
    val official = OfficialSwitchController(
        chatId, c.chatTabs, { id, on -> c.setOfficial(id, on) }, viewModelScope,
        iAmAdmin = c.db.groups().observe(chatId).map { it?.myRole == GroupMember.ROLE_ADMIN && it.readOnly.not() },
        officialMembers = { conv -> c.db.groups().observeMembers(conv) },
        agentUsers = { conv -> runCatching { c.mlsEngine?.agentUsers(conv) }.getOrNull().orEmpty() },
        me = meId,
    )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val media: StateFlow<TabMedia> = combine(
        c.db.messages().imagesIn(chatId, MEDIA_LIMIT),
        c.chatTabs.rows.map { officialConversationOf(chatId, it) }.flatMapLatest { conv ->
            if (conv == null) flowOf(emptyList()) else c.db.messages().imagesIn(conv, MEDIA_LIMIT)
        },
    ) { p, o -> TabMedia(p, o) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TabMedia())

    /** The chat's name: the peer's for a 1:1, else the Private group's. */
    val title: StateFlow<String> = combine(c.contacts.contacts, c.db.groups().all()) { contacts, groups ->
        val peer = lk.codegen.risime.net.dmPeer(chatId, meId)
        if (peer != null) contacts.firstOrNull { it.userId.equals(peer, true) }?.displayName ?: "Chat"
        else lk.codegen.risime.data.groups.groupDisplayName(groups.firstOrNull { it.conversationId.equals(chatId, true) }?.name)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** A 1:1 is end-to-end encrypted once this phone holds its MLS group (Official always is, §24.9). */
    val encrypted: Boolean = runCatching { c.mlsEngine?.group(chatId) != null }.getOrDefault(false)

    /** The tab bar is on (tabs UI): otherwise chat info looks as before. */
    val tabsOn: StateFlow<Boolean> = c.chatTabs.uiOn

    init {
        // The server's Official state and who may toggle (tabs devices only).
        viewModelScope.launch { runCatching { c.refreshChat(chatId) }.getOrNull()?.let { official.setServerCanToggle(it.canToggle) } }
    }
}

/** A photo's sealed thumbnail in a media section (nothing is downloaded for it). */
@Composable
fun MediaThumb(c: AppContainer, m: MessageEntity, onOpen: () -> Unit) {
    val bmp by produceState<android.graphics.Bitmap?>(null, m.clientMsgId) {
        value = runCatching { c.db.media().get(m.clientMsgId)?.let { c.imageLoader.thumb(it) } }.getOrNull()
    }
    Box(
        Modifier.size(Sizes.avatarLarge).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .clickable(onClickLabel = "Open chat", onClick = onOpen),
    ) {
        bmp?.let { Image(it.asImageBitmap(), contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/**
 * §24.4 the Official part of chat info: the switch (disabled with the reason for non-admins in a
 * group), "Official history (read-only)" while off, and the Official member list (Risi with an
 * "AI agent" badge and no actions).
 */
fun LazyListScope.officialInfoItems(ui: OfficialInfoUi, onToggle: (Boolean) -> Unit, onHistory: () -> Unit, onDismissError: () -> Unit) {
    item(key = "official_header") { SectionHeader("Official", Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) }
    item(key = "official_switch") {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Sizes.listRowMin)
                .toggleable(value = ui.on, enabled = ui.canToggle && !ui.busy, role = Role.Switch, onValueChange = onToggle)
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                .testTag("official_switch_row"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(OFFICIAL_SWITCH_LABEL, style = MaterialTheme.typography.bodyLarge)
                Text(
                    ui.disabledReason ?: if (ui.on) OFFICIAL_SWITCH_ON_TEXT else OFFICIAL_SWITCH_OFF_TEXT,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("official_switch_text"),
                )
            }
            Switch(checked = ui.on, onCheckedChange = null, enabled = ui.canToggle && !ui.busy, modifier = Modifier.testTag("official_switch"))
        }
    }
    ui.error?.let { e ->
        item(key = "official_error") {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
                Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Text(e, Modifier.weight(1f).testTag("official_toggle_error"), color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = onDismissError) { Text("OK") }
                }
            }
        }
    }
    if (ui.historyAvailable) {
        item(key = "official_history") {
            TextButton(onClick = onHistory, modifier = Modifier.padding(horizontal = Spacing.sm).heightIn(min = Sizes.minTouch).testTag("official_history")) {
                Text(OFFICIAL_HISTORY_LABEL)
            }
        }
    }
    if (ui.officialMembers.isNotEmpty()) {
        item(key = "official_members_header") { SectionHeader(OFFICIAL_MEMBERS_HEADER, Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) }
        items(ui.officialMembers, key = { "om:" + it.userId }) { m -> OfficialMemberRow(m) }
    }
}

/** A member of Official: never clickable (Risi is never offered actions; humans are managed from the chat's member list). */
@Composable
private fun OfficialMemberRow(m: OfficialMemberUi) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Sizes.listRowMin).padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            .testTag(if (m.agent) "official_member_agent" else "official_member"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialsAvatar(m.name, photoKey = if (m.agent) null else m.userId)
        Spacer(Modifier.width(Spacing.md))
        Text(if (m.me) "${m.name} (you)" else m.name, Modifier.weight(1f), maxLines = 1)
        if (m.agent) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Text(AI_AGENT_BADGE, Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp).testTag("ai_agent_badge"), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** §24.9 chat info media: Private and Official in separate sections (Official only once it exists). */
fun LazyListScope.tabMediaItems(media: TabMedia, showOfficial: Boolean, thumb: @Composable (MessageEntity) -> Unit) {
    fun section(key: String, title: String, rows: List<MessageEntity>) {
        item(key = "${key}_header") { SectionHeader(title, Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) }
        item(key = "${key}_grid") {
            if (rows.isEmpty()) {
                Text(NO_MEDIA_TEXT, Modifier.padding(horizontal = Spacing.lg).testTag("${key}_empty"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyRow(Modifier.testTag(key), contentPadding = PaddingValues(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    items(rows, key = { it.clientMsgId }) { thumb(it) }
                }
            }
        }
    }
    section("media_private", PRIVATE_MEDIA_HEADER, media.private)
    if (showOfficial) section("media_official", OFFICIAL_MEDIA_HEADER, media.official)
}

/** "Turn Official off?" (§24.4). */
@Composable
fun OfficialOffDialog(ui: OfficialInfoUi, onConfirm: () -> Unit, onCancel: () -> Unit) {
    if (ui.confirmingOff) ConfirmDialog(OFFICIAL_OFF_CONFIRM_TITLE, OFFICIAL_OFF_CONFIRM_TEXT, OFFICIAL_OFF_CONFIRM_BUTTON, onConfirm, onCancel)
}

/** A 1:1's chat info while tabs are on: the person, encryption, the Official switch and both tabs' media. */
@Composable
fun DmChatInfoContent(
    name: String,
    peerId: String?,
    encrypted: Boolean,
    notEncryptedText: String?,
    official: OfficialInfoUi,
    media: TabMedia,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onConfirmOff: () -> Unit,
    onCancelOff: () -> Unit,
    onHistory: () -> Unit,
    onDismissError: () -> Unit,
    thumb: @Composable (MessageEntity) -> Unit,
) {
    Scaffold(topBar = { RisiTopBar(title = "Chat info", onBack = onBack) }, contentWindowInsets = WindowInsets(0)) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = Spacing.xxl)) {
            item(key = "head") {
                Column(Modifier.fillMaxWidth().padding(Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
                    InitialsAvatar(name, size = Sizes.avatarLarge, photoKey = peerId)
                    Spacer(Modifier.heightIn(min = Spacing.sm))
                    Text(name, style = MaterialTheme.typography.headlineSmall)
                    lk.codegen.risime.ui.chat.E2eeInfoLine(encrypted = encrypted, notEncryptedText = notEncryptedText)
                }
            }
            officialInfoItems(official, onToggle, onHistory, onDismissError)
            item(key = "div") { HorizontalDivider(Modifier.padding(vertical = Spacing.sm)) }
            tabMediaItems(media, showOfficial = official.officialConversation != null, thumb)
        }
    }
    OfficialOffDialog(official, onConfirmOff, onCancelOff)
}
