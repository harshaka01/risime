package lk.codegen.risime.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Badge
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.ui.chat.ChatItem
import lk.codegen.risime.ui.chat.withDaySeparators
import lk.codegen.risime.ui.theme.Spacing

const val NEW_MESSAGES_LABEL = "New messages ↓"
const val NEW_MESSAGES_COUNT_TAG = "new_messages_count"
const val CHAT_LIST_TAG = "chat_list"

/**
 * Scroll state of a chat's message list (DM and group alike). The list is laid out reversed:
 * index 0 is the newest message at the bottom, so "at the bottom" is simply index 0 / offset 0 and
 * the layout itself keeps it there when the viewport shrinks (keyboard), an item grows (reactions,
 * images) or the screen is recreated (rotation, process restore).
 */
@Stable
class ChatScrollState internal constructor(
    val list: LazyListState,
    unseen: Int,
    internal var newestKey: String?,
) {
    /** Messages that arrived below the viewport while the user read history. */
    var unseen by mutableIntStateOf(unseen)
        internal set

    /** From the last layout: the newest message's bottom edge is in view. */
    val atBottom: Boolean by derivedStateOf {
        list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0
    }

    /**
     * Called from a SideEffect when the message list changed: after composition, before the next
     * measure, so [atBottom] still describes what the user saw. Sticking uses requestScrollToItem,
     * which wins over the list's key-based position retention in that same measure, with no
     * animation to race or cancel when a sync batch arrives in several emissions.
     */
    internal fun onMessages(messages: List<MessageEntity>) {
        val newest = messages.lastOrNull() ?: return
        val previous = newestKey
        if (newest.clientMsgId == previous) return
        newestKey = newest.clientMsgId
        val at = messages.indexOfLast { it.clientMsgId == previous }
        val added = when {
            previous == null -> 0
            at < 0 -> 1 // the previous newest was deleted
            else -> messages.size - 1 - at
        }
        if (previous == null || atBottom || newest.outgoing) {
            list.requestScrollToItem(0)
            unseen = 0
        } else if (added > 0) {
            unseen += messages.subList(messages.size - added, messages.size).count { !it.outgoing }
        }
    }

    suspend fun jumpToBottom() {
        list.animateScrollToItem(0)
        unseen = 0
    }

    companion object {
        val Saver: Saver<ChatScrollState, *> = listSaver(
            save = { listOf(it.list.firstVisibleItemIndex, it.list.firstVisibleItemScrollOffset, it.unseen, it.newestKey) },
            restore = { ChatScrollState(LazyListState(it[0] as Int, it[1] as Int), it[2] as Int, it[3] as String?) },
        )
    }
}

/** The one scroll state both chat screens use; saved across rotation and process death. */
@Composable
fun rememberChatScrollState(): ChatScrollState =
    rememberSaveable(saver = ChatScrollState.Saver) { ChatScrollState(LazyListState(), 0, null) }

/**
 * The message list for DM and group chats:
 * - opens at the newest message;
 * - stays at the bottom when messages arrive (one or a sync batch), when the user sends, when the
 *   keyboard opens, when a message grows, and across recreation;
 * - when the user has scrolled up, stays put and shows "New messages ↓" with the unread count,
 *   which jumps to the bottom and hides once there.
 *
 * [messages] are oldest first (as stored); [row] draws `items[index]` (day separators included,
 * oldest first, so `index - 1` is the item above).
 */
@Composable
fun ChatMessageList(
    messages: List<MessageEntity>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    spacing: Dp,
    scroll: ChatScrollState = rememberChatScrollState(),
    emptyText: String = "No messages yet. Say hello!",
    row: @Composable LazyItemScope.(items: List<ChatItem>, index: Int) -> Unit,
) {
    val items = remember(messages) { withDaySeparators(messages, System.currentTimeMillis()) }
    SideEffect { scroll.onMessages(messages) }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.atBottom }.distinctUntilChanged().filter { it }.collect { scroll.unseen = 0 }
    }
    val coroutines = rememberCoroutineScope()
    Box(modifier) {
        LazyColumn(
            state = scroll.list,
            modifier = Modifier.fillMaxWidth().testTag(CHAT_LIST_TAG),
            contentPadding = contentPadding,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(spacing, Alignment.Bottom),
        ) {
            if (messages.isEmpty()) item(key = "empty") { EmptyState(emptyText) }
            val last = items.lastIndex
            items(items.size, key = { items[last - it].key }) { i -> row(items, last - i) }
        }
        if (scroll.unseen > 0 && !scroll.atBottom) {
            ExtendedFloatingActionButton(
                onClick = { coroutines.launch { scroll.jumpToBottom() } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.md),
            ) {
                Badge {
                    Text(if (scroll.unseen > 99) "99+" else scroll.unseen.toString(), Modifier.testTag(NEW_MESSAGES_COUNT_TAG))
                }
                Spacer(Modifier.width(Spacing.sm))
                Text(NEW_MESSAGES_LABEL)
            }
        }
    }
}
