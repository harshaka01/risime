package lk.codegen.risime.ui.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lk.codegen.risime.ui.common.EmptyState
import lk.codegen.risime.ui.common.InitialsAvatar
import lk.codegen.risime.ui.common.RisiTopBar
import lk.codegen.risime.ui.theme.Sizes
import lk.codegen.risime.ui.theme.Spacing

/** v1.32 UI batch 2026-10-10 item 5: the chat list's one round FAB and its bottom sheet (exact strings). */
const val NEW_FAB_DESCRIPTION = "New"
const val NEW_SHEET_NEW_CHAT = "New chat"
const val NEW_SHEET_NEW_GROUP = "New group"
const val NEW_SHEET_ADD_FRIEND = "Add friend"

/** The sheet's rows, in order. */
val NEW_SHEET_ROWS = listOf(NEW_SHEET_NEW_CHAT, NEW_SHEET_NEW_GROUP, NEW_SHEET_ADD_FRIEND)

/** The one round FAB ("+", content description [NEW_FAB_DESCRIPTION]). */
@Composable
fun NewFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.CircleShape,
        modifier = modifier.navigationBarsPadding().testTag("new_fab").semantics { contentDescription = NEW_FAB_DESCRIPTION },
    ) { Icon(Icons.Default.Add, null) }
}

/** Tap on [NewFab]: exactly three rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSheet(onNewChat: () -> Unit, onNewGroup: () -> Unit, onAddFriend: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().testTag("new_sheet"), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            NewSheetRow(NEW_SHEET_NEW_CHAT, Icons.Default.Create, "new_sheet_chat", onNewChat)
            NewSheetRow(NEW_SHEET_NEW_GROUP, Icons.Default.Person, "new_sheet_group", onNewGroup)
            NewSheetRow(NEW_SHEET_ADD_FRIEND, Icons.Default.Add, "new_sheet_friend", onAddFriend)
        }
    }
}

@Composable
private fun NewSheetRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = Spacing.lg).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(Spacing.lg))
        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "New chat": the friends you can message; a tap opens that chat. */
@Composable
fun NewChatScreen(friends: List<ChatRow>, onBack: () -> Unit, onOpen: (String) -> Unit) {
    Scaffold(topBar = { RisiTopBar(title = NEW_SHEET_NEW_CHAT, onBack = onBack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).testTag("new_chat_list")) {
            if (friends.isEmpty()) item { EmptyState("No friends to message yet. Add a friend first.") }
            items(friends, key = { it.key }) { r ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Sizes.listRowMin).clickable { r.target?.let(onOpen) }.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InitialsAvatar(r.name, photoKey = r.userId)
                    Spacer(Modifier.width(Spacing.md + Spacing.xxs))
                    Text(r.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The friends [NewChatScreen] lists: registered, accepted, one-to-one (not Risi, not groups). */
fun newChatCandidates(rows: List<ChatRow>): List<ChatRow> = rows.filter { !it.group && !it.risi && it.friend && it.registered && it.userId != null }
